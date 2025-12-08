package server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import server.db.Message;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final int clientId;
    private final ChatServer server;
    private String username;
    private InputStream input;
    private OutputStream output;

    public ClientHandler(Socket socket, int clientId, ChatServer server) {
        this.socket = socket;
        this.clientId = clientId;
        this.server = server;
    }

    public String getUsername() {
        return username;
    }

    public OutputStream getOutput() {
        return output;
    }

    @Override
    public void run() {
        try {
            input = socket.getInputStream();
            output = socket.getOutputStream();
            
            System.out.println("[Client " + clientId + "] Connection established");
            
            // Read and process messages
            while (!socket.isClosed()) {
                try {
                    ChatKLV.KLVMessage message = ChatKLV.readKLVFromStream(input);
                    System.out.println("[Client " + clientId + "] Received: " + message.key);
                    
                    if (message.key.equals(ChatKLV.KEY_JOIN)) {
                        handleJoin(message);
                    } else if (username == null) {
                        // Must JOIN first
                        sendErrorResponse("Must JOIN before sending other messages");
                        break;
                    } else if (message.key.equals(ChatKLV.KEY_MSG)) {
                        handleMessage(message);
                    } else if (message.key.equals(ChatKLV.KEY_READ)) {
                        handleRead();
                    } else if (message.key.equals(ChatKLV.KEY_EXIT)) {
                        handleExit(message);
                        break; // Exit after handling EXIT
                    } else {
                        // Other message types
                        System.out.println("[Client " + clientId + "] Unhandled message type: " + message.key);
                    }
                } catch (IOException e) {
                    if (!socket.isClosed()) {
                        System.err.println("[Client " + clientId + "] Read error: " + e.getMessage());
                        // Connection lost - handle as abrupt disconnect
                        handleAbruptDisconnect();
                    }
                    break; // Connection lost
                } catch (Exception e) {
                    System.err.println("[Client " + clientId + "] Error processing message: " + e.getMessage());
                    e.printStackTrace();
                    // Don't break on parsing errors, continue to next message
                }
            }
            
        } catch (Exception e) {
            System.err.println("[Client " + clientId + "] Error: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    private void handleJoin(ChatKLV.KLVMessage message) throws Exception {
        String requestedUsername = new String(message.value, StandardCharsets.UTF_8).trim();
        
        if (requestedUsername.isEmpty()) {
            sendErrorResponse("Username cannot be empty");
            return;
        }

        ConcurrentHashMap<String, Boolean> usernames = server.getActiveUsernames();
        
        // Check if username is already taken
        if (usernames.putIfAbsent(requestedUsername, true) != null) {
            // Username already exists
            sendUsernameTakenResponse();
            return;
        }

        // Username is available
        this.username = requestedUsername;
        sendSuccessResponse();
        
        // Add to broadcast manager
        server.getBroadcastManager().addClient(this);
        
        // Broadcast JOIN to all clients (including the joining user per spec)
        byte[] joinBroadcast = ChatKLV.encodeKLV(ChatKLV.KEY_JOIN, 
            requestedUsername.getBytes(StandardCharsets.UTF_8));
        server.getBroadcastManager().broadcastToAll(joinBroadcast);
        
        System.out.println("[Client " + clientId + "] User '" + username + "' joined successfully");
    }

    private void handleMessage(ChatKLV.KLVMessage message) throws Exception {
        // Parse nested FROM and BODY fields from the MSG value
        // The message.value contains concatenated FROM and BODY KLV structures
        List<ChatKLV.KLVMessage> nestedFields = new java.util.ArrayList<>();
        int offset = 0;
        byte[] innerData = message.value;
        
        // Parse KLV structures from the value bytes directly
        // The value contains concatenated FROM and BODY KLV structures
        while (offset + 8 <= innerData.length) {
            try {
                ChatKLV.KLVMessage nested = ChatKLV.decodeKLV(innerData, offset);
                nestedFields.add(nested);
                offset += 8 + nested.value.length;
            } catch (Exception e) {
                // No more complete KLV structures
                break;
            }
        }
        
        if (nestedFields.isEmpty()) {
            System.err.println("[Client " + clientId + "] MSG has no nested fields");
            System.err.println("[Client " + clientId + "] MSG value length: " + message.value.length);
            System.err.println("[Client " + clientId + "] MSG value hex: " + ChatKLV.bytesToHex(message.value));
            sendErrorResponse("Invalid MSG format: missing nested fields");
            return;
        }
        
        String bodyText = null;
        
        for (ChatKLV.KLVMessage field : nestedFields) {
            if (field.key.equals(ChatKLV.KEY_BODY)) {
                bodyText = field.getValueAsString();
                break; // We only need the BODY field
            }
        }
        
        // Validate message
        if (bodyText == null || bodyText.trim().isEmpty()) {
            // RESP:37:CODE:3:400:TYPE:18:Empty message body
            byte[] codeValue = "400".getBytes(StandardCharsets.US_ASCII);
            byte[] codeKLV = ChatKLV.encodeKLV(ChatKLV.KEY_CODE, codeValue);
            
            byte[] typeValue = "Empty message body".getBytes(StandardCharsets.UTF_8);
            byte[] typeKLV = ChatKLV.encodeKLV(ChatKLV.KEY_TYPE, typeValue);
            
            java.io.ByteArrayOutputStream respValue = new java.io.ByteArrayOutputStream();
            respValue.write(codeKLV);
            respValue.write(typeKLV);
            
            byte[] respKLV = ChatKLV.encodeKLV(ChatKLV.KEY_RESP, respValue.toByteArray());
            output.write(respKLV);
            output.flush();
            return;
        }
        
        // Use the client's username (not the FROM field, which should match)
        // Store message in database
        server.getMessageStore().addMessage(username, bodyText);
        
        // Send success response
        sendSuccessResponse();
        
        // Broadcast MSG to all clients (including sender)
        // Reconstruct the MSG KLV with correct FROM field
        byte[] fromValue = username.getBytes(StandardCharsets.UTF_8);
        byte[] fromKLV = ChatKLV.encodeKLV(ChatKLV.KEY_FROM, fromValue);
        
        byte[] bodyValue = bodyText.getBytes(StandardCharsets.UTF_8);
        byte[] bodyKLV = ChatKLV.encodeKLV(ChatKLV.KEY_BODY, bodyValue);
        
        // Combine FROM and BODY in MSG value
        java.io.ByteArrayOutputStream msgValue = new java.io.ByteArrayOutputStream();
        msgValue.write(fromKLV);
        msgValue.write(bodyKLV);
        
        // Encode MSG (note: KEY_MSG is "MSG" which needs to be padded to "MSG\0")
        byte[] msgKLV = ChatKLV.encodeKLV(ChatKLV.KEY_MSG, msgValue.toByteArray());
        
        // Broadcast MSG to all clients (including sender per spec)
        server.getBroadcastManager().broadcastToAll(msgKLV);
        
        System.out.println("[Client " + clientId + "] User '" + username + "' sent message: " + bodyText);
    }

    private void handleRead() throws Exception {
        // Retrieve last 20 messages from database
        List<Message> messages = server.getMessageStore().getLastMessages(20);
        
        if (messages.isEmpty()) {
            // RESP:11:CODE:3:200 (no MSGS field)
            sendSuccessResponse();
            System.out.println("[Client " + clientId + "] User '" + username + "' requested history (empty)");
            return;
        }
        
        // Build MSGS structure: concatenate all MSG KLV structures
        ByteArrayOutputStream msgsValue = new ByteArrayOutputStream();
        
        for (Message msg : messages) {
            // Create FROM KLV
            byte[] fromValue = msg.getSender().getBytes(StandardCharsets.UTF_8);
            byte[] fromKLV = ChatKLV.encodeKLV(ChatKLV.KEY_FROM, fromValue);
            
            // Create BODY KLV
            byte[] bodyValue = msg.getBody().getBytes(StandardCharsets.UTF_8);
            byte[] bodyKLV = ChatKLV.encodeKLV(ChatKLV.KEY_BODY, bodyValue);
            
            // Combine FROM and BODY in MSG value
            ByteArrayOutputStream msgValue = new ByteArrayOutputStream();
            msgValue.write(fromKLV);
            msgValue.write(bodyKLV);
            
            // Encode MSG
            byte[] msgKLV = ChatKLV.encodeKLV(ChatKLV.KEY_MSG, msgValue.toByteArray());
            msgsValue.write(msgKLV);
        }
        
        // Wrap MSGS
        byte[] msgsKLV = ChatKLV.encodeKLV(ChatKLV.KEY_MSGS, msgsValue.toByteArray());
        
        // Build RESP: CODE:3:200 + MSGS:length:...
        byte[] codeValue = "200".getBytes(StandardCharsets.US_ASCII);
        byte[] codeKLV = ChatKLV.encodeKLV(ChatKLV.KEY_CODE, codeValue);
        
        // Combine CODE and MSGS in RESP value
        ByteArrayOutputStream respValue = new ByteArrayOutputStream();
        respValue.write(codeKLV);
        respValue.write(msgsKLV);
        
        byte[] respKLV = ChatKLV.encodeKLV(ChatKLV.KEY_RESP, respValue.toByteArray());
        output.write(respKLV);
        output.flush();
        
        System.out.println("[Client " + clientId + "] User '" + username + "' requested history (" + 
            messages.size() + " messages)");
    }

    private void handleExit(ChatKLV.KLVMessage message) throws Exception {
        // Parse username from EXIT message
        String exitUsername = new String(message.value, StandardCharsets.UTF_8).trim();
        
        // Send success response to exiting client
        sendSuccessResponse();
        
        // Mark as cleaned up to prevent double cleanup
        String exitingUsername = username;
        username = null; // Clear username to prevent cleanup() from broadcasting again
        
        // Remove client from broadcast manager before broadcasting
        // (so they don't receive their own EXIT broadcast)
        server.getBroadcastManager().removeClient(this);
        
        // Remove username from active usernames set
        if (exitingUsername != null) {
            server.getActiveUsernames().remove(exitingUsername);
        }
        
        // Broadcast EXIT to all remaining clients
        byte[] exitBroadcast = ChatKLV.encodeKLV(ChatKLV.KEY_EXIT, 
            exitUsername.getBytes(StandardCharsets.UTF_8));
        server.getBroadcastManager().broadcastToAll(exitBroadcast);
        
        System.out.println("[Client " + clientId + "] User '" + exitUsername + "' exited");
    }

    private void sendSuccessResponse() throws Exception {
        // RESP:11:CODE:3:200
        byte[] codeValue = "200".getBytes(StandardCharsets.US_ASCII);
        byte[] codeKLV = ChatKLV.encodeKLV(ChatKLV.KEY_CODE, codeValue);
        
        byte[] respKLV = ChatKLV.encodeKLV(ChatKLV.KEY_RESP, codeKLV);
        output.write(respKLV);
        output.flush();
    }

    private void sendUsernameTakenResponse() throws Exception {
        // RESP:41:CODE:3:403:TYPE:22:Username already taken
        byte[] codeValue = "403".getBytes(StandardCharsets.US_ASCII);
        byte[] codeKLV = ChatKLV.encodeKLV(ChatKLV.KEY_CODE, codeValue);
        
        byte[] typeValue = "Username already taken".getBytes(StandardCharsets.UTF_8);
        byte[] typeKLV = ChatKLV.encodeKLV(ChatKLV.KEY_TYPE, typeValue);
        
        // Combine CODE and TYPE in RESP value
        java.io.ByteArrayOutputStream respValue = new java.io.ByteArrayOutputStream();
        respValue.write(codeKLV);
        respValue.write(typeKLV);
        
        byte[] respKLV = ChatKLV.encodeKLV(ChatKLV.KEY_RESP, respValue.toByteArray());
        output.write(respKLV);
        output.flush();
    }

    private void sendErrorResponse(String errorMessage) throws Exception {
        // RESP with CODE:3:400 and TYPE
        byte[] codeValue = "400".getBytes(StandardCharsets.US_ASCII);
        byte[] codeKLV = ChatKLV.encodeKLV(ChatKLV.KEY_CODE, codeValue);
        
        byte[] typeValue = errorMessage.getBytes(StandardCharsets.UTF_8);
        byte[] typeKLV = ChatKLV.encodeKLV(ChatKLV.KEY_TYPE, typeValue);
        
        java.io.ByteArrayOutputStream respValue = new java.io.ByteArrayOutputStream();
        respValue.write(codeKLV);
        respValue.write(typeKLV);
        
        byte[] respKLV = ChatKLV.encodeKLV(ChatKLV.KEY_RESP, respValue.toByteArray());
        output.write(respKLV);
        output.flush();
    }

    /**
     * Handle abrupt disconnection (client closed without sending EXIT).
     * Broadcasts EXIT to remaining clients and cleans up.
     */
    private void handleAbruptDisconnect() {
        if (username != null) {
            // Remove from broadcast manager first
            server.getBroadcastManager().removeClient(this);
            
            // Broadcast EXIT to remaining clients
            try {
                byte[] exitBroadcast = ChatKLV.encodeKLV(ChatKLV.KEY_EXIT, 
                    username.getBytes(StandardCharsets.UTF_8));
                server.getBroadcastManager().broadcastToAll(exitBroadcast);
            } catch (Exception e) {
                System.err.println("[Client " + clientId + "] Error broadcasting EXIT: " + e.getMessage());
            }
            
            // Remove username from active usernames
            server.getActiveUsernames().remove(username);
        }
    }

    private void cleanup() {
        // Capture username for logging before cleanup
        String loggedUsername = username;
        
        // If username is still set, this is an abrupt disconnect (not handled by handleExit)
        // handleExit() clears username, so if it's still set, we need to handle abrupt disconnect
        if (username != null) {
            handleAbruptDisconnect();
        }
        
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            // Ignore
        }
        
        // Log disconnection
        if (loggedUsername != null) {
            System.out.println("[Client " + clientId + "] Disconnected (user: " + loggedUsername + ")");
        } else {
            System.out.println("[Client " + clientId + "] Disconnected");
        }
    }
}
