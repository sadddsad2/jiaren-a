package com.randomnpcs.net;

import com.randomnpcs.bot.BotClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Minimal Minecraft Java Edition protocol implementation (1.21.x).
 *
 * Packet format:
 *   VarInt(length) | VarInt(packetId) | payload
 *
 * This implements only the packets needed for a passive bot:
 *  - Handshake, Login Start, Login Success
 *  - Set Compression, Keepalive response
 *  - Chat message, Player Position, Look, Swing Arm, Entity Action
 */
public class MinecraftProtocol {

    // ── Packet IDs (Serverbound, 1.21.4 / protocol 769) ──────────────────────
    // Handshake state
    private static final int PKT_HANDSHAKE          = 0x00;
    // Login state
    private static final int PKT_LOGIN_START         = 0x00;
    private static final int PKT_LOGIN_ACK           = 0x03;
    // Play state (serverbound)
    private static final int PKT_CHAT_MESSAGE        = 0x06;
    private static final int PKT_CLIENT_SETTINGS     = 0x0F;
    private static final int PKT_KEEPALIVE_RESPONSE  = 0x1A;
    private static final int PKT_PLAYER_POSITION     = 0x1C;
    private static final int PKT_PLAYER_POS_ROT      = 0x1D;
    private static final int PKT_PLAYER_ROT          = 0x1E;
    private static final int PKT_PLAYER_ON_GROUND    = 0x1F;
    private static final int PKT_ENTITY_ACTION       = 0x2C;
    private static final int PKT_SWING_ARM           = 0x36;

    // ── Packet IDs (Clientbound) ───────────────────────────────────────────────
    private static final int PKT_C_DISCONNECT_LOGIN  = 0x00;
    private static final int PKT_C_ENCRYPTION_REQ    = 0x01;
    private static final int PKT_C_LOGIN_SUCCESS     = 0x02;
    private static final int PKT_C_SET_COMPRESSION   = 0x03;
    // Play
    private static final int PKT_C_KEEPALIVE         = 0x26;
    private static final int PKT_C_DISCONNECT_PLAY   = 0x1D;
    private static final int PKT_C_PLAYER_POS_LOOK   = 0x40;
    private static final int PKT_C_RESPAWN           = 0x47;

    private static final int PROTOCOL_VERSION = 769; // 1.21.4

    private final DataInputStream  in;
    private final DataOutputStream out;

    private boolean compressionEnabled = false;
    private int compressionThreshold   = -1;
    private boolean inPlayState        = false;

    public MinecraftProtocol(DataInputStream in, DataOutputStream out) {
        this.in  = in;
        this.out = out;
    }

    // ═══════════════════════════════════════════════════════════════
    //  SEND helpers
    // ═══════════════════════════════════════════════════════════════

    /** C→S Handshake: nextState=2 (Login) */
    public void sendHandshake(String host, int port) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarInt(buf, PROTOCOL_VERSION);
        writeString(buf, host);
        buf.write((port >> 8) & 0xFF);
        buf.write(port & 0xFF);
        writeVarInt(buf, 2); // nextState: Login
        sendPacket(PKT_HANDSHAKE, buf.toByteArray());
    }

    /** C→S Login Start */
    public void sendLoginStart(String name, UUID uuid) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeString(buf, name);
        // Write UUID as two longs
        writeLong(buf, uuid.getMostSignificantBits());
        writeLong(buf, uuid.getLeastSignificantBits());
        sendPacket(PKT_LOGIN_START, buf.toByteArray());
    }

    /** C→S Login Acknowledged (must send after Login Success) */
    public void sendLoginAck() throws IOException {
        sendPacket(PKT_LOGIN_ACK, new byte[0]);
        inPlayState = true;
    }

    /** C→S Client Settings */
    public void sendClientSettings() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeString(buf, "zh_cn");  // locale
        buf.write(10);              // view distance
        writeVarInt(buf, 0);        // chat mode: enabled
        buf.write(1);               // chat colors: true
        buf.write(0b01111111);      // displayed skin parts
        writeVarInt(buf, 1);        // main hand: right
        buf.write(0);               // enable text filtering
        buf.write(1);               // allow server listings
        sendPacket(PKT_CLIENT_SETTINGS, buf.toByteArray());
    }

    /** C→S Chat Message */
    public void sendChatMessage(String message) throws IOException {
        // Truncate to 256 chars (server limit)
        if (message.length() > 256) message = message.substring(0, 256);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeString(buf, message);
        writeLong(buf, System.currentTimeMillis()); // timestamp
        writeLong(buf, 0L);                          // salt
        buf.write(0);                                // no signature
        writeVarInt(buf, 0);                         // message count
        buf.write(new byte[3]);                      // acknowledged bitset (empty)
        sendPacket(PKT_CHAT_MESSAGE, buf.toByteArray());
    }

    /** C→S Keepalive response */
    public void sendKeepalive(long id) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeLong(buf, id);
        sendPacket(PKT_KEEPALIVE_RESPONSE, buf.toByteArray());
    }

    /** C→S Player Position + Look */
    public void sendPositionAndLook(double x, double y, double z,
                                    float yaw, float pitch, boolean onGround) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeDouble(buf, x);
        writeDouble(buf, y);
        writeDouble(buf, z);
        writeFloat(buf, yaw);
        writeFloat(buf, pitch);
        buf.write(onGround ? 1 : 0);
        sendPacket(PKT_PLAYER_POS_ROT, buf.toByteArray());
    }

    /** C→S Player Rotation only */
    public void sendLook(float yaw, float pitch, boolean onGround) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeFloat(buf, yaw);
        writeFloat(buf, pitch);
        buf.write(onGround ? 1 : 0);
        sendPacket(PKT_PLAYER_ROT, buf.toByteArray());
    }

    /** C→S Swing main hand */
    public void sendSwingArm() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarInt(buf, 0); // main hand
        sendPacket(PKT_SWING_ARM, buf.toByteArray());
    }

    /** C→S Entity Action (sneak, sprint, etc.) */
    public void sendEntityAction(EntityAction action) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarInt(buf, 0);                  // entity id placeholder (server uses player's)
        writeVarInt(buf, action.id);
        writeVarInt(buf, 0);                  // jump boost (unused)
        sendPacket(PKT_ENTITY_ACTION, buf.toByteArray());
    }

    // ═══════════════════════════════════════════════════════════════
    //  READ helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Block-wait for Login Success packet.
     * Handles Set Compression and skips Login Disconnect gracefully.
     */
    public boolean waitForLoginSuccess(String botName) throws IOException {
        for (int attempts = 0; attempts < 20; attempts++) {
            int[] result = readPacketIdAndData();
            int packetId  = result[0];
            // full packet bytes are in a temp buf — re-wrap
            byte[] raw = readRawForId(result);

            DataInputStream pIn = new DataInputStream(new ByteArrayInputStream(raw));

            if (packetId == PKT_C_SET_COMPRESSION) {
                compressionThreshold = readVarInt(pIn);
                compressionEnabled   = compressionThreshold >= 0;
                continue;
            }
            if (packetId == PKT_C_LOGIN_SUCCESS) {
                // Skip UUID (16 bytes) + name string
                pIn.skipBytes(16);
                String serverName = readString(pIn); // username echo
                // number of properties
                int props = readVarInt(pIn);
                for (int i = 0; i < props; i++) {
                    readString(pIn); // name
                    readString(pIn); // value
                    if (pIn.readBoolean()) readString(pIn); // optional signature
                }
                // strict error handling flag
                sendLoginAck();
                return true;
            }
            if (packetId == PKT_C_DISCONNECT_LOGIN || packetId == PKT_C_ENCRYPTION_REQ) {
                return false; // Online-mode server or kicked
            }
        }
        return false;
    }

    /**
     * Read a single Play-state packet and handle it:
     * - Keepalive → respond
     * - Player Position + Look → confirm
     * - Disconnect → throw exception
     */
    public void readAndHandlePacket(BotClient bot) throws IOException {
        byte[] raw = readNextPacketRaw();
        DataInputStream pIn = new DataInputStream(new ByteArrayInputStream(raw));
        int packetId = readVarInt(pIn);

        switch (packetId) {
            case PKT_C_KEEPALIVE -> {
                long id = pIn.readLong();
                bot.respondToKeepalive(id);
            }
            case PKT_C_PLAYER_POS_LOOK -> {
                // Server is telling us where we are
                double x     = pIn.readDouble();
                double y     = pIn.readDouble();
                double z     = pIn.readDouble();
                float  yaw   = pIn.readFloat();
                float  pitch = pIn.readFloat();
                bot.updatePosition(x, y, z, yaw, pitch);
            }
            case PKT_C_DISCONNECT_PLAY -> throw new IOException("Disconnected by server");
            // All other packets are silently ignored
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Low-level packet I/O
    // ═══════════════════════════════════════════════════════════════

    private void sendPacket(int packetId, byte[] data) throws IOException {
        ByteArrayOutputStream packetBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream idAndData = new ByteArrayOutputStream();
        writeVarInt(idAndData, packetId);
        idAndData.write(data);
        byte[] payload = idAndData.toByteArray();

        if (compressionEnabled) {
            if (payload.length >= compressionThreshold) {
                // Compress
                byte[] compressed = compress(payload);
                ByteArrayOutputStream compBuf = new ByteArrayOutputStream();
                writeVarInt(compBuf, payload.length); // data length (uncompressed)
                compBuf.write(compressed);
                payload = compBuf.toByteArray();
            } else {
                // Under threshold: data length = 0 (uncompressed)
                ByteArrayOutputStream compBuf = new ByteArrayOutputStream();
                writeVarInt(compBuf, 0);
                compBuf.write(payload);
                payload = compBuf.toByteArray();
            }
        }

        writeVarInt(packetBuf, payload.length);
        packetBuf.write(payload);

        synchronized (out) {
            out.write(packetBuf.toByteArray());
            out.flush();
        }
    }

    /** Read next full packet as raw bytes (packet-id included). */
    private byte[] readNextPacketRaw() throws IOException {
        int length = readVarInt(in);
        if (length <= 0) return new byte[0];

        if (compressionEnabled) {
            int dataLength = readVarInt(in);
            int remaining  = length - varIntSize(dataLength);
            byte[] compData = in.readNBytes(remaining);
            if (dataLength == 0) {
                return compData; // uncompressed
            } else {
                return decompress(compData);
            }
        } else {
            return in.readNBytes(length);
        }
    }

    // ── Temp hack: for login phase before compression negotiated ──
    private int[] readPacketIdAndData() throws IOException {
        return new int[]{readVarInt(in)};
    }

    private byte[] readRawForId(int[] ignored) throws IOException {
        int length = readVarInt(in);
        return in.readNBytes(length);
    }

    // ═══════════════════════════════════════════════════════════════
    //  VarInt / String encoding
    // ═══════════════════════════════════════════════════════════════

    public static int readVarInt(DataInputStream in) throws IOException {
        int value = 0, position = 0;
        byte currentByte;
        do {
            currentByte = in.readByte();
            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new IOException("VarInt too big");
        } while (true);
        return value;
    }

    private static int readVarInt(ByteArrayInputStream in) throws IOException {
        return readVarInt(new DataInputStream(in));
    }

    public static void writeVarInt(OutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) { out.write(value); return; }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static int varIntSize(int value) {
        int size = 0;
        do { size++; value >>>= 7; } while (value != 0);
        return size;
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void writeString(OutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static void writeLong(OutputStream out, long value) throws IOException {
        for (int i = 7; i >= 0; i--) out.write((int)((value >> (i * 8)) & 0xFF));
    }

    private static void writeDouble(OutputStream out, double value) throws IOException {
        writeLong(out, Double.doubleToRawLongBits(value));
    }

    private static void writeFloat(OutputStream out, float value) throws IOException {
        int bits = Float.floatToRawIntBits(value);
        out.write((bits >> 24) & 0xFF);
        out.write((bits >> 16) & 0xFF);
        out.write((bits >>  8) & 0xFF);
        out.write(bits & 0xFF);
    }

    // ── Compression (zlib) ───────────────────────────────────────────
    private static byte[] compress(byte[] data) throws IOException {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buf);
            baos.write(buf, 0, count);
        }
        deflater.end();
        return baos.toByteArray();
    }

    private static byte[] decompress(byte[] data) throws IOException {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buf);
                baos.write(buf, 0, count);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("Decompression failed", e);
        }
        inflater.end();
        return baos.toByteArray();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Enums
    // ═══════════════════════════════════════════════════════════════

    public enum EntityAction {
        START_SNEAKING(5),
        STOP_SNEAKING(6),
        START_SPRINTING(3),
        STOP_SPRINTING(4);

        public final int id;
        EntityAction(int id) { this.id = id; }
    }
}
