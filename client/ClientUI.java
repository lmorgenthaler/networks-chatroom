package client;
import client.ChatKLV;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;

public class ClientUI extends JFrame {

    private JTextArea chatArea;
    private JTextField inputField;
    private JTextField nameField;
    private JButton joinButton;
    private JButton sendButton;

    private Socket socket;
    private DataOutputStream out;
    private DataInputStream in;

    private volatile boolean connected = false;
    private String username;

    public ClientUI() {
        setTitle("Chat Client");
        setSize(500, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        chatArea = new JTextArea();
        chatArea.setEditable(false);

        JScrollPane scrollPane = new JScrollPane(chatArea);
        add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        sendButton = new JButton("Send");
        sendButton.setEnabled(false);

        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(sendButton, BorderLayout.EAST);

        JPanel topPanel = new JPanel(new BorderLayout());
        nameField = new JTextField();
        joinButton = new JButton("Join");

        topPanel.add(new JLabel("Username: "), BorderLayout.WEST);
        topPanel.add(nameField, BorderLayout.CENTER);
        topPanel.add(joinButton, BorderLayout.EAST);

        add(topPanel, BorderLayout.NORTH);
        add(bottomPanel, BorderLayout.SOUTH);

        joinButton.addActionListener(e -> joinServer());
        sendButton.addActionListener(e -> sendChatMessage());
        inputField.addActionListener(e -> sendChatMessage());

        setVisible(true);
    }

    private void joinServer() {
        try {
            username = nameField.getText().trim();
            if (username.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Enter a username.");
                return;
            }

            socket = new Socket("localhost", 8000);
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());

            // Send JOIN
            sendKLV("JOIN", username.getBytes());

            // Read server response
            ChatKLV.KLVMessage resp = readKLV();

            if (!resp.key.equals("RESP")) {
                chatArea.append("Invalid server response.\n");
                return;
            }

            if (resp.value[0] == 0) {
                chatArea.append("Joined as " + username + "\n");
                connected = true;
                joinButton.setEnabled(false);
                nameField.setEnabled(false);
                sendButton.setEnabled(true);
                startReceiverThread();
            } else {
                chatArea.append("Username already taken.\n");
            }

        } catch (IOException e) {
            chatArea.append("Error connecting: " + e.getMessage() + "\n");
        }
    }

    private void sendChatMessage() {
        if (!connected) return;

        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        try {
            sendKLV("MSG", text.getBytes());
        } catch (IOException e) {
            chatArea.append("Error sending message.\n");
        }

        inputField.setText("");
    }

    private void startReceiverThread() {
        new Thread(() -> {
            while (connected) {
                try {
                    ChatKLV.KLVMessage msg = readKLV();
                    handleIncoming(msg);
                } catch (IOException e) {
                    chatArea.append("Disconnected from server.\n");
                    connected = false;
                    break;
                }
            }
        }).start();
    }

    /** Handles any incoming KLV **/
private void handleIncoming(ChatKLV.KLVMessage msg) {
    try {
        switch (msg.key) {
            case "MSG": {
                // Expect nested FROM + BODY payload
                java.util.List<ChatKLV.KLVMessage> nested = ChatKLV.decodeNestedKLV(msg.value);
                String from = null, body = null;

                for (ChatKLV.KLVMessage k : nested) {
                    if (k.key.equals("FROM")) from = new String(k.value);
                    if (k.key.equals("BODY")) body = new String(k.value);
                }

                if (from != null && body != null) {
                    chatArea.append(from + ": " + body + "\n");
                }
                break;
            }

            case "RESP":
                chatArea.append("[Server response]\n");
                break;

            case "ERR":
                chatArea.append("SERVER ERROR: " + new String(msg.value) + "\n");
                break;

            default:
                chatArea.append("[Unknown KLV: " + msg.key + "]\n");
                break;
        }
    } catch (Exception e) {
        e.printStackTrace();
        chatArea.append("[Error processing incoming KLV message]\n");
    }
}


    /** KLV encoding **/
    private void sendKLV(String key, byte[] value) throws IOException {
        byte[] keyBytes = key.getBytes();
        out.writeByte(keyBytes.length);
        out.write(keyBytes);
        out.writeInt(value.length);
        out.write(value);
        out.flush();
    }

    /** KLV decoding **/
    private ChatKLV.KLVMessage readKLV() throws IOException {
        int keyLen = in.readByte() & 0xFF;
        byte[] keyBytes = in.readNBytes(keyLen);
        String key = new String(keyBytes);

        int valueLen = in.readInt();
        byte[] value = in.readNBytes(valueLen);

        return new ChatKLV.KLVMessage(key, value);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ClientUI::new);
    }
}
