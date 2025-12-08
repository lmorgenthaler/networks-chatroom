package server.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQLiteMessageStore requires the SQLite JDBC driver.
 * Download from: https://github.com/xerial/sqlite-jdbc/releases
 * Add to classpath: java -cp ".:sqlite-jdbc-3.x.x.jar" server.ChatServer
 */

public class SQLiteMessageStore {

    private final String url;

    public SQLiteMessageStore(String dbFilePath) {
        this.url = "jdbc:sqlite:" + dbFilePath;
        // Try to load SQLite driver
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.err.println("ERROR: SQLite JDBC driver not found!");
            System.err.println("Please download sqlite-jdbc from: https://github.com/xerial/sqlite-jdbc/releases");
            System.err.println("Then run with: java -cp \".:sqlite-jdbc-3.x.x.jar\" server.ChatServer");
            System.err.println("Or add it to your CLASSPATH environment variable.");
        }
        init();
    }

    private void init() {
        // Initialize the database schema
        String sql = """
                CREATE TABLE IF NOT EXISTS messages (
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     sender TEXT NOT NULL,
                     body TEXT NOT NULL,
                     timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
                );
                """;
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            System.err.println("Error initializing database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Add a message to the database.
     * @param sender The username of the message sender
     * @param body The message body text
     */
    public synchronized void addMessage(String sender, String body) {
        String sql = "INSERT INTO messages (sender, body) VALUES (?, ?)";
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sender);
            pstmt.setString(2, body);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error adding message to database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Get the last N messages from the database in chronological order (oldest first).
     * @param count Number of messages to retrieve
     * @return List of Message objects in chronological order
     */
    public List<Message> getLastMessages(int count) {
        // Get last N messages ordered by id DESC, then reverse to get chronological order
        String sql = "SELECT sender, body, timestamp FROM messages ORDER BY id DESC LIMIT ?";
        List<Message> messages = new ArrayList<>();
        
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, count);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String sender = rs.getString("sender");
                    String body = rs.getString("body");
                    String timestamp = rs.getString("timestamp");
                    messages.add(new Message(sender, body, timestamp));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error retrieving messages from database: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
        
        // Reverse to get chronological order (oldest first)
        Collections.reverse(messages);
        return messages;
    }

    /**
     * Optional: Prune old messages, keeping only the last N messages.
     * @param keepCount Number of messages to keep
     */
    public synchronized void pruneMessages(int keepCount) {
        String sql = """
            DELETE FROM messages 
            WHERE id NOT IN (
                SELECT id FROM messages 
                ORDER BY id DESC 
                LIMIT ?
            )
            """;
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, keepCount);
            int deleted = pstmt.executeUpdate();
            if (deleted > 0) {
                System.out.println("Pruned " + deleted + " old messages from database");
            }
        } catch (SQLException e) {
            System.err.println("Error pruning messages: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
