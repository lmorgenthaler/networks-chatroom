package server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

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
                    } else {
                        // Other message types will be handled in later phases
                        System.out.println("[Client " + clientId + "] Unhandled message type: " + message.key);
                    }
                } catch (IOException e) {
                    if (!socket.isClosed()) {
                        System.err.println("[Client " + clientId + "] Read error: " + e.getMessage());
                    }
                    break; // Connection lost
                } catch (Exception e) {
                    System.err.println("[Client " + clientId + "] Error processing message: " + e.getMessage());
                    e.printStackTrace();
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
        
        // Broadcast JOIN to all clients
        byte[] joinBroadcast = ChatKLV.encodeKLV(ChatKLV.KEY_JOIN, 
            requestedUsername.getBytes(StandardCharsets.UTF_8));
        server.getBroadcastManager().broadcast(joinBroadcast, this);
        
        System.out.println("[Client " + clientId + "] User '" + username + "' joined successfully");
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

    private void cleanup() {
        // Remove from broadcast manager
        if (username != null) {
            server.getBroadcastManager().removeClient(this);
            server.getActiveUsernames().remove(username);
        }
        
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            // Ignore
        }
        System.out.println("[Client " + clientId + "] Disconnected" + 
            (username != null ? " (user: " + username + ")" : ""));
    }
}
