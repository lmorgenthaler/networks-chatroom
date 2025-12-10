# Multi-Client Chatroom: Binary Protocol-Based Chat Server & Client

A multi-client chatroom application implementing a binary KLV (Key-Length-Value) protocol for communication between a Java server and clients. The server supports multiple simultaneous connections, message broadcasting, persistent message history, and graceful client disconnection.

## Partners

- **Lauren Morgenthaler**
- **Alicia Mache**

## Programming Language

**Java** (JDK 8 or higher recommended)

## Dependencies

- **SQLite JDBC Driver**: `sqlite-jdbc-3.51.0.0.jar`
  - Location: `lib/sqlite-jdbc-3.51.0.0.jar`
  - Required for server-side message persistence
  - Download from: https://github.com/xerial/sqlite-jdbc/releases

## Compilation Instructions

### Server Compilation

The server requires the SQLite JDBC driver in the classpath during compilation:

```bash
cd server
javac -cp ".:../lib/sqlite-jdbc-3.51.0.0.jar" *.java db/*.java
```

This compiles all server classes including:
- `ChatServer.java` - Main server class
- `ClientHandler.java` - Per-client connection handler
- `BroadcastManager.java` - Message broadcasting manager
- `ChatKLV.java` - KLV protocol utilities
- `db/SQLiteMessageStore.java` - Database operations
- `db/Message.java` - Message data class

### Client Compilation

#### GUI Client

```bash
cd client
javac *.java
```

This compiles:
- `ClientUI.java` - Swing-based GUI client
- `ChatClient.java` - Core client logic
- `ServerListener.java` - Background message listener thread
- `ChatKLV.java` - KLV protocol utilities

#### CLI Client (TestClient)

```bash
javac TestClient.java client/*.java
```

This compiles the command-line test client that uses the same client libraries.

## How to Run

### Running the Server

The server must be run with the SQLite JDBC driver in the classpath:

```bash
java -cp ".:lib/sqlite-jdbc-3.51.0.0.jar" server.ChatServer [port]
```

**Example:**
```bash
java -cp ".:lib/sqlite-jdbc-3.51.0.0.jar" server.ChatServer 8000
```

**Parameters:**
- `port` (optional): Port number to listen on (default: 8000)

**Notes:**
- The server creates a SQLite database file (`chat.db`) in the current directory for message persistence
- The server binds to all network interfaces (0.0.0.0), allowing connections from Tailscale and localhost
- Press `Ctrl+C` to stop the server gracefully

### Running the Client

#### GUI Client (Recommended)

```bash
java client.ClientUI
```

The GUI client provides:
- Connection dialog for server address, port, and username
- Real-time message display
- Message input field
- "Read History" button to load last 20 messages
- "Exit" button for graceful disconnection

**Usage:**
1. Enter server address (e.g., `localhost` or Tailscale DNS name)
2. Enter port number (default: `8000`)
3. Enter your username
4. Click "Join" to connect
5. Type messages and press Enter or click "Send"
6. Click "Read History" to view previous messages
7. Click "Exit" or close window to disconnect

#### CLI Client (TestClient)

```bash
java TestClient <host> <port> <username>
```

**Example:**
```bash
java TestClient localhost 8000 alice
```

**Commands:**
- `msg <text>` - Send a message
- `read` - Request message history
- `exit` - Disconnect from server

## Connecting Over Tailscale

This chatroom application can be used over Tailscale to connect with friends from anywhere! Here's how to set it up:

### Prerequisites

1. Both you and your friend must be on the same Tailscale network (e.g., the class network)
2. Make sure Tailscale is installed and running on both machines
3. Verify you can ping each other: `ping friend-name.low` (replace with your friend's Tailscale DNS name)

### Setting Up the Server (Friend hosting the chatroom)

1. Start the chat server:
   ```bash
   java -cp ".:lib/sqlite-jdbc-3.51.0.0.jar" server.ChatServer 8000
   ```
   
   The server will bind to all network interfaces, so it will accept connections from Tailscale.

2. Share your Tailscale address with your friend. You can find it by:
   - Clicking the Tailscale icon in your menu bar (your DNS name like `yourname.low` or IP like `100.x.x.x`)
   - Or running: `tailscale ip -4` (for IPv4) or `tailscale ip` (for all IPs)

### Setting Up the Client (Connecting to friend's server)

1. Launch the client UI:
   ```bash
   java client.ClientUI
   ```

2. In the client window:
   - **Server**: Enter your friend's Tailscale DNS name (e.g., `alicia.low`) or IP address (e.g., `100.87.224.111`)
   - **Port**: Enter `8000` (or whatever port the server is using)
   - **Username**: Enter your desired username
   - Click **Join**

3. You should now be connected and able to chat!

### Example Connection

If your friend's Tailscale DNS is `alicia.low`:
- Server field: `alicia.low`
- Port field: `8000`
- Username field: `YourName`

### Troubleshooting

- **Can't connect?** 
  - Make sure both machines are on the same Tailscale network
  - Verify the server is running: Check the server terminal for "Listening on port 8000"
  - Test connectivity: `ping friend-name.low` should work
  - Check firewall: macOS may need to allow Java network access

- **Connection refused?**
  - Verify the server is actually running
  - Make sure you're using the correct port number
  - Try using the Tailscale IP address instead of DNS name

- **Username already taken?**
  - Choose a different username
  - Someone else might already be using that name on the server

### Class Network Directory

Use the DNS names from the class network (like `alicia.low`, `drake.low`, etc.) to connect to your classmates' servers!

## Known Issues and Limitations

1. **SQLite JDBC Driver**: The server requires the SQLite JDBC driver JAR file to be present in the `lib/` directory. If missing, download it from the GitHub releases page.

2. **Message History Limit**: The server stores and returns the last 20 messages per READ request. Older messages remain in the database but are not returned.

3. **Username Validation**: Usernames are case-sensitive and must be unique. No special character validation is performed.

4. **Network Timeouts**: If a client disconnects abruptly (without sending EXIT), the server will detect the disconnection and clean up resources, but there may be a brief delay.

5. **Database File**: The SQLite database file (`chat.db`) is created in the server's current working directory. Ensure the server has write permissions in that directory.

6. **Concurrent Connections**: The server uses a thread pool to handle multiple clients. Very high numbers of simultaneous connections (>100) may require tuning the thread pool size.

7. **Message Size**: There is no explicit limit on message body size, but extremely large messages may cause performance issues.

## Project Structure

```
networks-chatroom/
├── server/
│   ├── ChatServer.java          # Main server class
│   ├── ClientHandler.java       # Per-client connection handler
│   ├── BroadcastManager.java    # Message broadcasting manager
│   ├── ChatKLV.java             # KLV protocol utilities
│   └── db/
│       ├── SQLiteMessageStore.java  # Database operations
│       └── Message.java             # Message data class
├── client/
│   ├── ClientUI.java            # Swing GUI client
│   ├── ChatClient.java          # Core client logic
│   ├── ServerListener.java      # Background message listener
│   └── ChatKLV.java             # KLV protocol utilities
├── lib/
│   └── sqlite-jdbc-3.51.0.0.jar # SQLite JDBC driver
├── TestClient.java              # CLI test client
├── chat.db                      # SQLite database (created at runtime)
└── README.md                    # This file
```

## Protocol Implementation

This implementation follows the KLV (Key-Length-Value) binary protocol specification:

- **JOIN**: User authentication with username validation
- **MSG**: Message broadcasting with nested FROM and BODY fields
- **READ**: Message history retrieval (last 20 messages)
- **EXIT**: Graceful client disconnection

All messages use 4-byte ASCII keys, 4-byte big-endian length fields, and variable-length values. Nested KLV structures are used for complex messages (MSG, RESP with MSGS).
