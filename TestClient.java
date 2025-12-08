import java.util.List;
import java.util.Scanner;
import client.ChatClient;
import client.ChatClient.BroadcastHandler;
import client.ChatClient.Message;

/**
 * Interactive test client for testing JOIN, MSG, and READ messages.
 * Usage: java TestClient <host> <port> <username>
 * 
 * Commands:
 *   msg <text>  - Send a message
 *   read        - Request message history
 *   exit        - Disconnect
 */
public class TestClient {
    private static ChatClient client;

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java TestClient <host> <port> <username>");
            System.exit(1);
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String username = args[2];

        try {
            System.out.println("Connecting to " + host + ":" + port + "...");
            client = new ChatClient();
            
            // Set up broadcast handler for CLI output
            client.setBroadcastHandler(new BroadcastHandler() {
                @Override
                public void onJoin(String username) {
                    System.out.println("\n[Broadcast] " + username + " joined the chat");
                }

                @Override
                public void onMessage(String from, String body) {
                    System.out.println("\n[Broadcast] " + from + ": " + body);
                }

                @Override
                public void onExit(String username) {
                    System.out.println("\n[Broadcast] " + username + " left the chat");
                }
            });

            if (!client.connect(host, port, username)) {
                System.err.println("Failed to join chat server");
                System.exit(1);
            }

            System.out.println("\n✓ Successfully joined as " + username + "!");

            // Interactive command loop
            System.out.println("\n" + "=".repeat(70));
            System.out.println("Interactive Mode - Available commands:");
            System.out.println("  msg <text>  - Send a message");
            System.out.println("  read        - Request message history");
            System.out.println("  exit        - Disconnect");
            System.out.println("=".repeat(70) + "\n");

            Scanner scanner = new Scanner(System.in);
            while (client.isConnected()) {
                System.out.print("> ");
                String line = scanner.nextLine().trim();
                
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split("\\s+", 2);
                String command = parts[0].toLowerCase();

                switch (command) {
                    case "msg":
                        if (parts.length < 2) {
                            System.out.println("Usage: msg <text>");
                            continue;
                        }
                        sendMessage(parts[1]);
                        break;
                    case "read":
                        sendReadRequest();
                        break;
                    case "exit":
                        client.disconnect();
                        break;
                    default:
                        System.out.println("Unknown command: " + command);
                        System.out.println("Available: msg, read, exit");
                }
            }

            scanner.close();
            System.out.println("Disconnected.");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void sendMessage(String body) {
        try {
            System.out.println("Sending MSG: " + body);
            String code = client.sendMessage(body);
            if (code != null && code.equals("200")) {
                System.out.println("✓ Message sent successfully");
            } else if (code != null) {
                System.out.println("✗ Error: " + code);
            } else {
                System.out.println("✗ No response received");
            }
        } catch (Exception e) {
            System.err.println("Error sending message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void sendReadRequest() {
        try {
            System.out.println("Sending READ request...");
            List<Message> messages = client.readHistory();
            
            if (messages == null) {
                System.out.println("✗ Error reading history");
                return;
            }
            
            System.out.println("\nReceived RESP with history:");
            System.out.println("  MSGS contains " + messages.size() + " message(s):");
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                System.out.println("    Message " + (i + 1) + ":");
                System.out.println("      FROM: " + msg.from());
                System.out.println("      BODY: " + msg.body());
            }
            System.out.println();
        } catch (Exception e) {
            System.err.println("Error reading history: " + e.getMessage());
            e.printStackTrace();
        }
    }

}

