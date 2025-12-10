package client;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.List;

public class ClientUI extends JFrame {

    private JTextArea chatArea;
    private JTextField inputField;
    private JTextField nameField;
    private JTextField serverField;
    private JTextField portField;
    private JButton joinButton;
    private JButton sendButton;
    private JButton exitButton;
    private JButton readButton;

    private ChatClient client;
    private volatile boolean connected = false;

    public ClientUI() {
        setTitle("Chat Client");
        setSize(600, 600);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());

        // Handle window close - send EXIT before closing
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                disconnect();
                System.exit(0);
            }
        });

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(chatArea);
        add(scrollPane, BorderLayout.CENTER);

        // Bottom panel with input and buttons
        JPanel bottomPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        sendButton = new JButton("Send");
        sendButton.setEnabled(false);
        readButton = new JButton("Read History");
        readButton.setEnabled(false);
        exitButton = new JButton("Exit");
        exitButton.setEnabled(false);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(sendButton);
        buttonPanel.add(readButton);
        buttonPanel.add(exitButton);

        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);

        // Top panel with connection fields
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        
        JPanel leftPanel = new JPanel(new GridLayout(3, 2, 5, 5));
        leftPanel.add(new JLabel("Server: "));
        serverField = new JTextField("localhost");
        leftPanel.add(serverField);
        
        leftPanel.add(new JLabel("Port: "));
        portField = new JTextField("8000");
        leftPanel.add(portField);
        
        leftPanel.add(new JLabel("Username: "));
        nameField = new JTextField();
        leftPanel.add(nameField);
        
        topPanel.add(leftPanel, BorderLayout.CENTER);
        
        joinButton = new JButton("Join");
        topPanel.add(joinButton, BorderLayout.EAST);

        add(topPanel, BorderLayout.NORTH);
        add(bottomPanel, BorderLayout.SOUTH);

        // Event handlers
        joinButton.addActionListener(e -> joinServer());
        sendButton.addActionListener(e -> sendChatMessage());
        inputField.addActionListener(e -> sendChatMessage());
        readButton.addActionListener(e -> readHistory());
        exitButton.addActionListener(e -> disconnect());

        setVisible(true);
    }

    private void joinServer() {
        String host = null;
        int port = 8000;
        
        try {
            String username = nameField.getText().trim();
            if (username.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter a username.");
                return;
            }
            
            host = serverField.getText().trim();
            if (host.isEmpty()) {
                host = "localhost";
            }
            
            try {
                String portStr = portField.getText().trim();
                if (!portStr.isEmpty()) {
                    port = Integer.parseInt(portStr);
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "Invalid port number.");
                return;
            }

            // Create and connect ChatClient
            client = new ChatClient();
            
            // Set up broadcast handler
            client.setBroadcastHandler(new ChatClient.BroadcastHandler() {
                @Override
                public void onJoin(String username) {
                    SwingUtilities.invokeLater(() -> {
                        chatArea.append("[" + username + " joined the chat]\n");
                        chatArea.setCaretPosition(chatArea.getDocument().getLength());
                    });
                }

                @Override
                public void onMessage(String from, String body) {
                    SwingUtilities.invokeLater(() -> {
                        chatArea.append(from + ": " + body + "\n");
                        chatArea.setCaretPosition(chatArea.getDocument().getLength());
                    });
                }

                @Override
                public void onExit(String username) {
                    SwingUtilities.invokeLater(() -> {
                        chatArea.append("[" + username + " left the chat]\n");
                        chatArea.setCaretPosition(chatArea.getDocument().getLength());
                    });
                }
            });

            // Connect to server
            boolean success = client.connect(host, port, username);
            
            if (success) {
                chatArea.append("Connected to " + host + ":" + port + "\n");
                chatArea.append("Joined as " + username + "\n");
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
                connected = true;
                
                // Update UI state
                joinButton.setEnabled(false);
                nameField.setEnabled(false);
                serverField.setEnabled(false);
                portField.setEnabled(false);
                sendButton.setEnabled(true);
                readButton.setEnabled(true);
                exitButton.setEnabled(true);
            } else {
                chatArea.append("Join failed: Username may be taken or server error\n");
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
                client = null;
            }

        } catch (java.net.ConnectException e) {
            chatArea.append("Connection error: Could not connect to " + 
                (host != null ? host : "server") + ":" + port + "\n");
            chatArea.append("Make sure the server is running and the address is correct.\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
            client = null;
        } catch (java.net.UnknownHostException e) {
            chatArea.append("Connection error: Unknown host '" + 
                (host != null ? host : "unknown") + "'\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
            client = null;
        } catch (java.io.IOException e) {
            chatArea.append("Network error: " + e.getMessage() + "\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
            client = null;
        } catch (Exception e) {
            chatArea.append("Error connecting: " + e.getMessage() + "\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
            e.printStackTrace();
            client = null;
        }
    }

    private void sendChatMessage() {
        if (!connected || client == null) return;

        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        try {
            String code = client.sendMessage(text);
            if (code != null && code.equals("200")) {
                // Message sent successfully - it will appear via broadcast
                inputField.setText("");
            } else if (code != null) {
                chatArea.append("[Error: " + code + "]\n");
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
            } else {
                chatArea.append("[Error: No response from server]\n");
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
            }
        } catch (Exception e) {
            chatArea.append("[Error sending message: " + e.getMessage() + "]\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
            e.printStackTrace();
        }
    }

    private void readHistory() {
        if (!connected || client == null) return;

        try {
            List<ChatClient.Message> messages = client.readHistory();
            
            if (messages == null) {
                chatArea.append("[Error reading history]\n");
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
                return;
            }
            
            if (messages.isEmpty()) {
                chatArea.append("[No message history]\n");
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
                return;
            }
            
            chatArea.append("\n--- Message History (" + messages.size() + " messages) ---\n");
            for (ChatClient.Message msg : messages) {
                chatArea.append(msg.from() + ": " + msg.body() + "\n");
            }
            chatArea.append("--- End of History ---\n\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        } catch (Exception e) {
            chatArea.append("[Error reading history: " + e.getMessage() + "]\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
            e.printStackTrace();
        }
    }

    private void disconnect() {
        if (client != null && connected) {
            try {
                client.disconnect();
                chatArea.append("[Disconnected from server]\n");
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
            } catch (IOException e) {
                chatArea.append("[Error disconnecting: " + e.getMessage() + "]\n");
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
            }
        }
        
        connected = false;
        client = null;
        
        // Update UI state
        joinButton.setEnabled(true);
        nameField.setEnabled(true);
        serverField.setEnabled(true);
        portField.setEnabled(true);
        sendButton.setEnabled(false);
        readButton.setEnabled(false);
        exitButton.setEnabled(false);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // Use default look and feel
            }
            new ClientUI();
        });
    }
}
