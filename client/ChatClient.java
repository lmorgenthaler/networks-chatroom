package client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Main client class for chatroom communication.
 * Handles connection, message sending, and response parsing.
 */
public class ChatClient {
    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private String username;
    private volatile boolean connected;
    private final BlockingQueue<ChatKLV.KLVMessage> messageQueue;
    private ServerListener listener;
    private BroadcastHandler broadcastHandler;

    /**
     * Interface for handling broadcast messages (JOIN, MSG, EXIT).
     */
    public interface BroadcastHandler {
        void onJoin(String username);
        void onMessage(String from, String body);
        void onExit(String username);
    }

    public ChatClient() {
        this.messageQueue = new LinkedBlockingQueue<>();
        this.connected = false;
    }

    /**
     * Connect to the chat server and join with a username.
     * @param host Server hostname or IP
     * @param port Server port
     * @param username Username to join with
     * @return true if connection and join successful, false otherwise
     * @throws IOException If connection fails
     */
    public boolean connect(String host, int port, String username) throws IOException {
        this.username = username;
        socket = new Socket(host, port);
        input = socket.getInputStream();
        output = socket.getOutputStream();
        connected = true;

        // Send JOIN message
        try {
            byte[] joinMsg = ChatKLV.encodeKLV(ChatKLV.KEY_JOIN, 
                username.getBytes(StandardCharsets.UTF_8));
            output.write(joinMsg);
            output.flush();

            // Read and parse response
            ChatKLV.KLVMessage response = ChatKLV.readKLVFromStream(input);
            
            // Parse CODE from response
            List<ChatKLV.KLVMessage> nested = parseNestedKLV(response.value);
            for (ChatKLV.KLVMessage n : nested) {
                if (n.key.equals(ChatKLV.KEY_CODE)) {
                    String code = n.getValueAsString();
                    if (code.equals("200")) {
                        // Successfully joined
                        startListener();
                        return true;
                    } else {
                        // Join failed
                        close();
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            close();
            throw new IOException("Failed to join: " + e.getMessage(), e);
        }

        // If we got here, assume success and start listener
        startListener();
        return true;
    }

    /**
     * Start the server listener thread.
     */
    private void startListener() {
        listener = new ServerListener(input, messageQueue, this::handleBroadcast);
        Thread listenerThread = new Thread(listener);
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    /**
     * Set the broadcast handler for receiving JOIN, MSG, and EXIT broadcasts.
     * @param handler Handler to receive broadcast callbacks
     */
    public void setBroadcastHandler(BroadcastHandler handler) {
        this.broadcastHandler = handler;
    }

    /**
     * Send a chat message.
     * @param body Message body text
     * @return Response code ("200" for success, "400" for error)
     * @throws IOException If sending fails
     */
    public String sendMessage(String body) throws IOException {
        try {
            // Build MSG: FROM:length:username:BODY:length:body
            byte[] fromValue = username.getBytes(StandardCharsets.UTF_8);
            byte[] fromKLV = ChatKLV.encodeKLV(ChatKLV.KEY_FROM, fromValue);
            
            byte[] bodyValue = body.getBytes(StandardCharsets.UTF_8);
            byte[] bodyKLV = ChatKLV.encodeKLV(ChatKLV.KEY_BODY, bodyValue);
            
            // Combine in MSG value
            java.io.ByteArrayOutputStream msgValue = new java.io.ByteArrayOutputStream();
            msgValue.write(fromKLV);
            msgValue.write(bodyKLV);
            
            byte[] msgKLV = ChatKLV.encodeKLV(ChatKLV.KEY_MSG, msgValue.toByteArray());
            output.write(msgKLV);
            output.flush();

            // Wait for RESP
            ChatKLV.KLVMessage resp = waitForResponse();
            if (resp == null) {
                return null;
            }

            // Parse CODE from response
            List<ChatKLV.KLVMessage> nested = parseNestedKLV(resp.value);
        for (ChatKLV.KLVMessage n : nested) {
            if (n.key.equals(ChatKLV.KEY_CODE)) {
                return n.getValueAsString();
            }
        }
        return null;
        } catch (Exception e) {
            throw new IOException("Failed to send message: " + e.getMessage(), e);
        }
    }

    /**
     * Request message history.
     * @return List of messages, or null if error
     * @throws IOException If sending fails
     */
    public List<Message> readHistory() throws IOException {
        try {
            // READ:0: (zero-length value)
            byte[] readKLV = ChatKLV.encodeKLV(ChatKLV.KEY_READ, new byte[0]);
            output.write(readKLV);
            output.flush();

            // Wait for RESP
            ChatKLV.KLVMessage resp = waitForResponse();
            if (resp == null) {
                return null;
            }

            // Parse RESP value: contains concatenated CODE and MSGS KLV structures
            List<ChatKLV.KLVMessage> nested = parseNestedKLV(resp.value);
        
        for (ChatKLV.KLVMessage n : nested) {
            if (n.key.equals(ChatKLV.KEY_MSGS)) {
                // Parse MSGS value: contains concatenated MSG KLV structures
                List<ChatKLV.KLVMessage> msgs = parseNestedKLV(n.value);
                List<Message> messages = new ArrayList<>();
                
                for (ChatKLV.KLVMessage msg : msgs) {
                    if (msg.key.equals(ChatKLV.KEY_MSG)) {
                        // Parse MSG value: contains concatenated FROM and BODY KLV structures
                        List<ChatKLV.KLVMessage> msgFields = parseNestedKLV(msg.value);
                        
                        String from = null, body = null;
                        for (ChatKLV.KLVMessage field : msgFields) {
                            if (field.key.equals(ChatKLV.KEY_FROM)) {
                                from = field.getValueAsString();
                            } else if (field.key.equals(ChatKLV.KEY_BODY)) {
                                body = field.getValueAsString();
                            }
                        }
                        if (from != null && body != null) {
                            messages.add(new Message(from, body));
                        }
                    }
                }
                return messages;
            }
        }
        return new ArrayList<>(); // Empty history
        } catch (Exception e) {
            throw new IOException("Failed to read history: " + e.getMessage(), e);
        }
    }

    /**
     * Disconnect from the server.
     * @throws IOException If sending EXIT fails
     */
    public void disconnect() throws IOException {
        if (!connected) {
            return;
        }

        try {
            byte[] exitKLV = ChatKLV.encodeKLV(ChatKLV.KEY_EXIT, 
                username.getBytes(StandardCharsets.UTF_8));
            output.write(exitKLV);
            output.flush();

            // Read RESP (ignore it)
            waitForResponse();
        } catch (Exception e) {
            // Ignore errors during disconnect
        } finally {
            close();
        }
    }

    /**
     * Wait for a RESP message from the server.
     * @return RESP message, or null if timeout/error
     */
    private ChatKLV.KLVMessage waitForResponse() {
        long timeout = System.currentTimeMillis() + 5000; // 5 second timeout
        while (System.currentTimeMillis() < timeout) {
            synchronized (messageQueue) {
                ChatKLV.KLVMessage resp = messageQueue.poll();
                if (resp != null && resp.key.equals(ChatKLV.KEY_RESP)) {
                    return resp;
                }
                if (resp != null) {
                    // Not a RESP, put it back
                    try {
                        messageQueue.put(resp);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                try {
                    messageQueue.wait(1000); // Wait 1 second
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        return null;
    }

    /**
     * Parse nested KLV structures from raw bytes.
     * @param data Raw bytes containing concatenated KLV structures
     * @return List of parsed KLV messages
     */
    private List<ChatKLV.KLVMessage> parseNestedKLV(byte[] data) {
        List<ChatKLV.KLVMessage> result = new ArrayList<>();
        int offset = 0;
        
        while (offset + 8 <= data.length) {
            try {
                ChatKLV.KLVMessage nested = ChatKLV.decodeKLV(data, offset);
                result.add(nested);
                offset += 8 + nested.value.length;
            } catch (Exception e) {
                break; // No more complete KLV structures
            }
        }
        return result;
    }

    /**
     * Handle broadcast messages from the server.
     * @param broadcast The broadcast message
     */
    private void handleBroadcast(ChatKLV.KLVMessage broadcast) {
        if (broadcastHandler == null) {
            return;
        }

        if (broadcast.key.equals(ChatKLV.KEY_JOIN)) {
            broadcastHandler.onJoin(broadcast.getValueAsString());
        } else if (broadcast.key.equals(ChatKLV.KEY_MSG)) {
            // Parse FROM and BODY
            List<ChatKLV.KLVMessage> fields = parseNestedKLV(broadcast.value);
            String from = null, body = null;
            for (ChatKLV.KLVMessage field : fields) {
                if (field.key.equals(ChatKLV.KEY_FROM)) {
                    from = field.getValueAsString();
                } else if (field.key.equals(ChatKLV.KEY_BODY)) {
                    body = field.getValueAsString();
                }
            }
            if (from != null && body != null) {
                broadcastHandler.onMessage(from, body);
            }
        } else if (broadcast.key.equals(ChatKLV.KEY_EXIT)) {
            broadcastHandler.onExit(broadcast.getValueAsString());
        }
    }

    /**
     * Close the connection.
     */
    public void close() {
        connected = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            // Ignore
        }
    }

    /**
     * Check if client is connected.
     * @return true if connected
     */
    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    /**
     * Get the username.
     * @return Username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Simple message record for client-side use.
     * Note: Client only needs from/body for display
     */
    public record Message(String from, String body) {
        @Override
        public String toString() {
            return from + ": " + body;
        }
    }
}
