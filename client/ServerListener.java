package client;

import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;

/**
 * Thread that listens for incoming messages from the server.
 * Reads KLV messages and adds them to a queue for processing.
 */
public class ServerListener implements Runnable {
    private final InputStream input;
    private final BlockingQueue<ChatKLV.KLVMessage> messageQueue;
    private final Consumer<ChatKLV.KLVMessage> broadcastHandler;
    private volatile boolean running = true;

    /**
     * Create a server listener.
     * @param input Input stream from server
     * @param messageQueue Queue to add all messages to
     * @param broadcastHandler Handler for broadcast messages (JOIN, MSG, EXIT)
     */
    public ServerListener(InputStream input, 
                         BlockingQueue<ChatKLV.KLVMessage> messageQueue,
                         Consumer<ChatKLV.KLVMessage> broadcastHandler) {
        this.input = input;
        this.messageQueue = messageQueue;
        this.broadcastHandler = broadcastHandler;
    }

    @Override
    public void run() {
        try {
            while (running) {
                ChatKLV.KLVMessage msg = ChatKLV.readKLVFromStream(input);
                
                // Add to queue for RESP messages
                messageQueue.put(msg);
                
                // Handle broadcasts immediately
                if (!msg.key.equals(ChatKLV.KEY_RESP)) {
                    if (broadcastHandler != null) {
                        broadcastHandler.accept(msg);
                    }
                }
            }
        } catch (Exception e) {
            if (running) {
                // Connection closed or error
                running = false;
            }
        }
    }

    /**
     * Stop the listener thread.
     */
    public void stop() {
        running = false;
    }
}
