package server;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class BroadcastManager {
    private final List<ClientHandler> clients;

    public BroadcastManager() {
        this.clients = new CopyOnWriteArrayList<>();
    }

    public void addClient(ClientHandler client) {
        clients.add(client);
    }

    public void removeClient(ClientHandler client) {
        clients.remove(client);
    }

    /**
     * Broadcast a message to all connected clients except the sender.
     * @param message The KLV message bytes to broadcast
     * @param sender The client that sent the message (excluded from broadcast)
     */
    public void broadcast(byte[] message, ClientHandler sender) {
        for (ClientHandler client : clients) {
            if (client != sender) {
                try {
                    client.getOutput().write(message);
                    client.getOutput().flush();
                } catch (IOException e) {
                    // Client disconnected, will be removed by cleanup
                    System.err.println("Error broadcasting to client: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Broadcast a message to all connected clients (including sender).
     * @param message The KLV message bytes to broadcast
     */
    public void broadcastToAll(byte[] message) {
        for (ClientHandler client : clients) {
            try {
                client.getOutput().write(message);
                client.getOutput().flush();
            } catch (IOException e) {
                // Client disconnected, will be removed by cleanup
                System.err.println("Error broadcasting to client: " + e.getMessage());
            }
        }
    }

    public List<ClientHandler> getClients() {
        return clients;
    }
}
