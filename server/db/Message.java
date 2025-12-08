package server.db;

/**
 * Simple POJO representing a chat message.
 */
public class Message {
    private final String sender;
    private final String body;
    private final String timestamp;

    public Message(String sender, String body, String timestamp) {
        this.sender = sender;
        this.body = body;
        this.timestamp = timestamp;
    }

    public String getSender() {
        return sender;
    }

    public String getBody() {
        return body;
    }

    public String getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "Message{sender='" + sender + "', body='" + body + "', timestamp='" + timestamp + "'}";
    }
}

