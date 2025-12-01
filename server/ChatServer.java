package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatServer {
    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean running;
    private final AtomicInteger clientCount = new AtomicInteger(0);
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Boolean> activeUsernames;
    private final BroadcastManager broadcastManager;

    public ChatServer(int port) {
        this.port = port;
        this.running = false;
        this.executor = Executors.newCachedThreadPool();
        this.activeUsernames = new ConcurrentHashMap<>();
        this.broadcastManager = new BroadcastManager();
    }

    public ConcurrentHashMap<String, Boolean> getActiveUsernames() {
        return activeUsernames;
    }

    public BroadcastManager getBroadcastManager() {
        return broadcastManager;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        System.out.println("=".repeat(70));
        System.out.println("Chat Server");
        System.out.println("=".repeat(70));
        System.out.println("Listening on port " + port);
        System.out.println("Press Ctrl+C to stop.");
        System.out.println("=".repeat(70));

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                int clientId = clientCount.incrementAndGet();
                System.out.println("\n[Client " + clientId + "] Connected from " + 
                    clientSocket.getRemoteSocketAddress());

                // Handle each client in a separate thread
                ClientHandler handler = new ClientHandler(clientSocket, clientId, this);
                executor.execute(handler);
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error accepting connection: " + e.getMessage());
                }
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing server: " + e.getMessage());
        }
        executor.shutdown();
        System.out.println("Server stopped.");
    }

    public static void main(String[] args) {
        int port = 8000;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0]);
                System.exit(1);
            }
        }

        ChatServer server = new ChatServer(port);

        // Handle Ctrl+C gracefully
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n\nShutting down server...");
            server.stop();
        }));

        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            System.exit(1);
        }
    }
}
