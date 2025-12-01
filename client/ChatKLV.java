package client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ChatKLV {
    

    /** Protocol keys for the chat system. */
    public static final String KEY_JOIN = "JOIN";// user authentication
    public static final String KEY_MSG = "MSG"; // chat message (padded to MSG\0)
    public static final String KEY_READ = "READ"; // retrieve message history
    public static final String KEY_EXIT = "EXIT"; // user disconnect
    public static final String KEY_RESP = "RESP"; // server response

    /** Message keys for chat operations (nested). */
    public static final  String KEY_FROM = "FROM"; // message sender
    public static final String KEY_BODY = "BODY"; // message content
    public static final String KEY_CODE = "CODE"; // status code
    public static final String KEY_TYPE = "TYPE"; // error description
    public static final String KEY_MSGS = "MSGS"; // message array

    /** Represents a decoded KLV structure. */
    public static final class KLVMessage {
        public final String key;
        public final byte[] value;

        public KLVMessage(String key, byte[] value) {
            this.key = key;
            this.value = value;
        }

        public String getValueAsString() {
            return new String(value, StandardCharsets.UTF_8);
        }
        @Override
        public String toString() {
            return "KLVMessage [key=" + key + ", value=" + getValueAsString() + "]";
        }
    }

    /**
     * Encodes a KLV message into binary format.
     * @param key The protocol key.
     * @param value The message value.
     * @return The encoded KLV message as bytes.
     */
    public static byte[] encodeKLV(String key, byte[] value) throws Exception {
        // Use US_ASCII for keys per protocol specification
        byte[] keyBytes = key.getBytes(StandardCharsets.US_ASCII);
        // Ensure key is 4 bytes or less
        if (keyBytes.length > 4) {
            throw new IllegalArgumentException("Key '" + key + "' is too long (max 4 bytes)");
        }
        // Pad key to 4 bytes
        byte[] paddedKey = new byte[4];
        System.arraycopy(keyBytes, 0, paddedKey, 0, keyBytes.length);
        
        // Encode length as 4-byte big-endian integer
        ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
        lengthBuffer.putInt(value.length);
        byte[] lengthBytes = lengthBuffer.array();

        // Concatenate: Key + Length + Value
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(paddedKey);
        output.write(lengthBytes);
        output.write(value);

        return output.toByteArray();
    }

    /**
     * Decodes a KLV message from binary format.
     * @param data The binary data to decode.
     * @return The decoded KLV message.
     */
    public static KLVMessage decodeKLV(byte[] data) throws Exception {
        if (data.length < 8) {
            throw new IllegalArgumentException("Data is too short to be a valid KLV message");
        }
        return decodeKLV(data, 0);
    }

    /**
     * Decodes a KLV message starting at a specific offset.
     * @param data Binary data containing a KLV structure
     * @param offset Starting position in the data array
     * @return Decoded KLV message
     */
    private static KLVMessage decodeKLV(byte[] data, int offset) throws Exception {
        if (data.length - offset < 8) {
            throw new IllegalArgumentException(
                "Data too short for KLV structure (need at least 8 bytes)");
        }

        // Read key (4 bytes)
        byte[] keyBytes = Arrays.copyOfRange(data, offset, offset + 4);
        
        // Remove null padding and convert to string
        int keyLength = 4;
        for (int i = 0; i < 4; i++) {
            if (keyBytes[i] == 0) {
                keyLength = i;
                break;
            }
        }
        String key = new String(keyBytes, 0, keyLength, StandardCharsets.US_ASCII);

        // Read length (4 bytes, big-endian)
        ByteBuffer lengthBuffer = ByteBuffer.wrap(data, offset + 4, 4);
        int length = lengthBuffer.getInt();

        // Read value
        if (data.length - offset < 8 + length) {
            throw new IllegalArgumentException(
                String.format("Data too short: expected %d bytes, got %d",
                    8 + length, data.length - offset));
        }
        byte[] value = Arrays.copyOfRange(data, offset + 8, offset + 8 + length);

        return new KLVMessage(key, value);
    }

    /**
     * Encode a KLV structure containing nested KLV items.
     * @param key Outer key
     * @param nestedItems List of KLV messages to nest inside
     * @return Complete nested KLV structure
     */
    public static byte[] encodeNestedKLV(String key, List<KLVMessage> nestedItems)
            throws Exception {
        ByteArrayOutputStream nestedData = new ByteArrayOutputStream();
        // Encode each nested item
        for (KLVMessage item : nestedItems) {
            byte[] itemBytes = encodeKLV(item.key, item.value);
            nestedData.write(itemBytes);
        }
        // Wrap in outer KLV
        return encodeKLV(key, nestedData.toByteArray());
    }

    /**
     * Decode a KLV structure that contains nested KLV items.
     * @param data Binary data containing nested KLV structure
     * @return List of nested KLV messages
     */
    public static List<KLVMessage> decodeNestedKLV(byte[] data) throws Exception {
        // Decode outer structure
        KLVMessage outer = decodeKLV(data);
        // Parse nested items from the value
        List<KLVMessage> nestedItems = new ArrayList<>();
        int offset = 0;
        byte[] innerData = outer.value;
        while (offset + 8 <= innerData.length) {
            try {
                KLVMessage nested = decodeKLV(innerData, offset);
                nestedItems.add(nested);
                offset += 8 + nested.value.length;
            } catch (Exception e) {
                break; // No more complete KLV structures
            }
        }
        return nestedItems;
    }

    /**
     * Read a complete KLV message from an input stream.
     * @param input The input stream to read from
     * @return Decoded KLV message
     * @throws IOException If reading fails
     */
    public static KLVMessage readKLVFromStream(InputStream input) throws IOException {
        // Step 1: Read 4 bytes for key
        byte[] keyBytes = recvExact(input, 4);
        if (keyBytes == null) {
            throw new IOException("Could not read key (expected 4 bytes)");
        }

        // Convert key bytes to string (strip null padding)
        int keyLength = 4;
        for (int i = 0; i < 4; i++) {
            if (keyBytes[i] == 0) {
                keyLength = i;
                break;
            }
        }
        String key = new String(keyBytes, 0, keyLength, StandardCharsets.US_ASCII);

        // Step 2: Read 4 bytes for length
        byte[] lengthBytes = recvExact(input, 4);
        if (lengthBytes == null) {
            throw new IOException("Could not read length (expected 4 bytes)");
        }

        // Convert to integer (big-endian)
        ByteBuffer lengthBuffer = ByteBuffer.wrap(lengthBytes);
        int length = lengthBuffer.getInt();

        // Step 3: Read exactly 'length' bytes for value
        byte[] value = recvExact(input, length);
        if (value == null) {
            throw new IOException("Unexpected end of stream");
        }

        return new KLVMessage(key, value);
    }

    /**
     * Receive exactly numBytes from input stream.
     * CRITICAL: InputStream.read() may return fewer bytes than requested!
     * You MUST loop until you have all bytes.
     * @param input The input stream
     * @param numBytes Number of bytes to read
     * @return Byte array with exactly numBytes, or null if connection closed
     * @throws IOException If reading fails
     */
    public static byte[] recvExact(InputStream input, int numBytes) throws IOException {
        byte[] data = new byte[numBytes];
        int totalRead = 0;
        while (totalRead < numBytes) {
            int bytesRead = input.read(data, totalRead, numBytes - totalRead);
            if (bytesRead == -1) {
                return null; // Connection closed
            }
            totalRead += bytesRead;
        }
        return data;
    }

    /**
     * Convert byte array to hex string with spaces between bytes.
     * Example: [0x48, 0x45, 0x4C, 0x4F] -> "48 45 4c 4f"
     * @param bytes The byte array to convert
     * @return Hex string representation
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }
}
