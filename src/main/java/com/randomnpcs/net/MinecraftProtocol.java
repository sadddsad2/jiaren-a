package com.randomnpcs.net;

import com.randomnpcs.bot.BotClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Minimal Minecraft Java Edition protocol with auto-detected protocol version.
 *
 * Before logging in, call queryProtocolVersion(host, port) to ping the server
 * and retrieve its protocol number automatically — no hardcoded version needed.
 *
 * One instance is created per BotClient and shared for the lifetime of the
 * connection so that compression state is preserved across all calls.
 *
 * Packet wire format (no compression):
 *   VarInt(packetLength) | VarInt(packetId) | payload
 *
 * Packet wire format (compression active):
 *   VarInt(packetLength) | VarInt(dataLength) | data
 *   where dataLength=0 means uncompressed, >0 means zlib-compressed and
 *   dataLength is the uncompressed size.
 */
public class MinecraftProtocol {

    // ── Serverbound packet IDs ────────────────────────────────────────
    private static final int S_HANDSHAKE           = 0x00; // Handshake state
    private static final int S_LOGIN_START          = 0x00; // Login state
    private static final int S_LOGIN_ACK            = 0x03; // Login → Configuration
    private static final int S_CONFIG_ACK           = 0x03; // Configuration → Play (Acknowledge Finish Configuration)
    private static final int S_CLIENT_INFO          = 0x00; // Configuration state: Client Information
    private static final int S_KEEPALIVE_CONFIG     = 0x04; // Configuration state keepalive
    private static final int S_CHAT_MESSAGE         = 0x06; // Play state
    private static final int S_CLIENT_SETTINGS      = 0x0F; // Play state (legacy, keep for safety)
    private static final int S_KEEPALIVE_PLAY       = 0x1A; // Play state
    private static final int S_PLAYER_POS_ROT       = 0x1D; // Play state
    private static final int S_PLAYER_ROT           = 0x1E; // Play state
    private static final int S_ENTITY_ACTION        = 0x2C; // Play state
    private static final int S_SWING_ARM            = 0x36; // Play state

    // ── Clientbound packet IDs ────────────────────────────────────────
    // Login state
    private static final int C_DISCONNECT_LOGIN     = 0x00;
    private static final int C_ENCRYPTION_REQUEST   = 0x01;
    private static final int C_LOGIN_SUCCESS        = 0x02;
    private static final int C_SET_COMPRESSION      = 0x03;
    private static final int C_LOGIN_PLUGIN_REQUEST = 0x04;
    // Configuration state (1.20.2+)
    private static final int C_CONFIG_PLUGIN_MSG    = 0x00;
    private static final int C_CONFIG_DISCONNECT    = 0x01;
    private static final int C_CONFIG_FINISH        = 0x02;
    private static final int C_CONFIG_KEEPALIVE     = 0x03;
    private static final int C_CONFIG_PING          = 0x05;
    // Play state
    private static final int C_KEEPALIVE_PLAY       = 0x26;
    private static final int C_DISCONNECT_PLAY      = 0x1D;
    private static final int C_PLAYER_POS_LOOK      = 0x40;

    private static final int PROTOCOL_VERSION_FALLBACK = 769; // MC 1.21.4 fallback

    private final DataInputStream  in;
    private final DataOutputStream out;
    private final Logger log;

    // Resolved at connect time via status ping; falls back to 769 if ping fails
    private int protocolVersion = PROTOCOL_VERSION_FALLBACK;

    // Compression state — must survive the lifetime of the connection
    private boolean compressionEnabled    = false;
    private int     compressionThreshold  = -1;

    public MinecraftProtocol(DataInputStream in, DataOutputStream out, Logger log) {
        this.in  = in;
        this.out = out;
        this.log = log;
    }

    /**
     * Ping the server's Status endpoint, parse the JSON response and return
     * the server's protocol version number. Opens and closes its own socket
     * so it does not interfere with the login connection.
     *
     * @return protocol version reported by the server, or -1 on failure
     */
    public static int queryProtocolVersion(String host, int port, Logger log) {
        try (java.net.Socket s = new java.net.Socket(host, port)) {
            s.setSoTimeout(5_000);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(s.getOutputStream()));
            DataInputStream  in  = new DataInputStream(
                    new BufferedInputStream(s.getInputStream()));

            // Handshake — next state = 1 (Status)
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            writeVarInt(buf, PROTOCOL_VERSION_FALLBACK); // version doesn't matter for status ping
            writeString(buf, host);
            writeShort(buf, port);
            writeVarInt(buf, 1); // next state = Status
            ByteArrayOutputStream idBuf = new ByteArrayOutputStream();
            writeVarInt(idBuf, 0x00);
            idBuf.write(buf.toByteArray());
            byte[] data = idBuf.toByteArray();
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            writeVarInt(frame, data.length);
            frame.write(data);
            out.write(frame.toByteArray());

            // Status Request (0x00, empty payload)
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            writeVarInt(req, 1); // length = 1 (just the packet id varint)
            writeVarInt(req, 0x00);
            out.write(req.toByteArray());
            out.flush();

            // Read Status Response
            int pktLen = readVarInt(in);
            byte[] raw = in.readNBytes(pktLen);
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(raw));
            int pktId = readVarInt(d);
            if (pktId != 0x00) {
                log.warning("[StatusPing] Unexpected packet id: 0x" + Integer.toHexString(pktId));
                return -1;
            }
            String json = readString(d);

            // Parse "protocol":<number> without a JSON library
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"protocol\"\\s*:\\s*(-?\\d+)")
                    .matcher(json);
            if (m.find()) {
                int proto = Integer.parseInt(m.group(1));
                log.info("[StatusPing] Server protocol version: " + proto);
                return proto;
            }
            log.warning("[StatusPing] Could not find protocol version in response");
        } catch (Exception e) {
            log.warning("[StatusPing] Failed to query server protocol: " + e.getMessage());
        }
        return -1;
    }

    /** Set the protocol version to use for handshake packets. */
    public void setProtocolVersion(int version) {
        this.protocolVersion = version;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Public API called by BotClient
    // ═══════════════════════════════════════════════════════════════════

    /** Step 1 – send Handshake then Login Start. */
    public void initiateLogin(String host, int port, String name, UUID uuid) throws IOException {
        // Handshake
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarInt(buf, protocolVersion);
        writeString(buf, host);
        writeShort(buf, port);
        writeVarInt(buf, 2); // next state = Login
        sendRaw(S_HANDSHAKE, buf.toByteArray());

        // Login Start
        buf.reset();
        writeString(buf, name);
        writeLong(buf, uuid.getMostSignificantBits());
        writeLong(buf, uuid.getLeastSignificantBits());
        sendRaw(S_LOGIN_START, buf.toByteArray());
    }

    /**
     * Step 2 – read packets until we reach the Play state.
     * Handles: Set Compression, Login Success, Configuration state, Finish Configuration.
     * Returns false if the server rejected the login (encryption required or kicked).
     */
    public boolean completeLogin(String botName) throws IOException {
        // ── Login phase ──────────────────────────────────────────────
        for (int i = 0; i < 50; i++) {
            Packet pkt = readPacket();
            log.fine("[" + botName + "] Login packet 0x" + Integer.toHexString(pkt.id)
                    + " (" + pkt.data.length + " bytes)");

            switch (pkt.id) {

                case C_SET_COMPRESSION -> {
                    DataInputStream d = pkt.stream();
                    compressionThreshold = readVarInt(d);
                    compressionEnabled   = compressionThreshold >= 0;
                    log.fine("[" + botName + "] Compression threshold=" + compressionThreshold);
                }

                case C_LOGIN_SUCCESS -> {
                    // UUID (16 bytes) + username string + properties array
                    DataInputStream d = pkt.stream();
                    d.skipBytes(16); // UUID
                    readString(d);   // username
                    int props = readVarInt(d);
                    for (int p = 0; p < props; p++) {
                        readString(d); // name
                        readString(d); // value
                        if (d.readBoolean()) readString(d); // optional signature
                    }
                    // Login Acknowledged → enter Configuration state
                    sendRaw(S_LOGIN_ACK, new byte[0]);
                    log.fine("[" + botName + "] Login success, sent Login Ack");
                    // Fall through to Configuration phase below
                    return completeConfiguration(botName);
                }

                case C_DISCONNECT_LOGIN -> {
                    DataInputStream d = pkt.stream();
                    String reason = readString(d);
                    log.warning("[" + botName + "] Login disconnect: " + reason);
                    return false;
                }

                case C_ENCRYPTION_REQUEST -> {
                    log.warning("[" + botName + "] Server requires online-mode encryption — not supported");
                    return false;
                }

                case C_LOGIN_PLUGIN_REQUEST -> {
                    // Respond with: messageId (VarInt) + false (bool, not understood)
                    DataInputStream d = pkt.stream();
                    int msgId = readVarInt(d);
                    ByteArrayOutputStream resp = new ByteArrayOutputStream();
                    writeVarInt(resp, msgId);
                    resp.write(0); // not understood
                    sendRaw(0x02, resp.toByteArray()); // Login Plugin Response
                }
            }
        }
        log.warning("[" + botName + "] Login timed out (too many packets without Login Success)");
        return false;
    }

    /**
     * Configuration state (1.20.2+).
     * We must send Client Information and respond to any keepalives/pings,
     * then send Acknowledge Finish Configuration when the server says so.
     */
    private boolean completeConfiguration(String botName) throws IOException {
        // Send Client Information in Configuration state
        sendClientInformation();

        for (int i = 0; i < 200; i++) {
            Packet pkt = readPacket();
            log.fine("[" + botName + "] Config packet 0x" + Integer.toHexString(pkt.id)
                    + " (" + pkt.data.length + " bytes)");

            switch (pkt.id) {

                case C_CONFIG_FINISH -> {
                    // Acknowledge Finish Configuration → enter Play state
                    sendRaw(S_CONFIG_ACK, new byte[0]);
                    log.fine("[" + botName + "] Configuration complete, entering Play state");
                    return true;
                }

                case C_CONFIG_KEEPALIVE -> {
                    DataInputStream d = pkt.stream();
                    long id = d.readLong();
                    ByteArrayOutputStream resp = new ByteArrayOutputStream();
                    writeLong(resp, id);
                    sendRaw(S_KEEPALIVE_CONFIG, resp.toByteArray());
                }

                case C_CONFIG_PING -> {
                    DataInputStream d = pkt.stream();
                    int pingId = d.readInt();
                    ByteArrayOutputStream resp = new ByteArrayOutputStream();
                    resp.write((pingId >> 24) & 0xFF);
                    resp.write((pingId >> 16) & 0xFF);
                    resp.write((pingId >>  8) & 0xFF);
                    resp.write(pingId & 0xFF);
                    sendRaw(0x04, resp.toByteArray()); // Configuration Pong
                }

                case C_CONFIG_DISCONNECT -> {
                    DataInputStream d = pkt.stream();
                    log.warning("[" + botName + "] Config disconnect: " + readString(d));
                    return false;
                }

                // Plugin messages and registry data — silently ignored
            }
        }
        log.warning("[" + botName + "] Configuration phase timed out");
        return false;
    }

    /** Send Client Information (used during Configuration state). */
    private void sendClientInformation() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeString(buf, "zh_cn"); // locale
        buf.write(10);             // view distance
        writeVarInt(buf, 0);       // chat mode: enabled
        buf.write(1);              // chat colors
        buf.write(0b01111111);     // skin parts
        writeVarInt(buf, 1);       // main hand: right
        buf.write(0);              // text filtering
        buf.write(1);              // allow server listings
        sendRaw(S_CLIENT_INFO, buf.toByteArray());
    }

    /**
     * Play state – read one packet and dispatch.
     * Called in a tight loop from BotClient's reader thread.
     */
    public void readAndHandle(BotClient bot) throws IOException {
        Packet pkt = readPacket();

        switch (pkt.id) {
            case C_KEEPALIVE_PLAY -> {
                long id = pkt.stream().readLong();
                ByteArrayOutputStream resp = new ByteArrayOutputStream();
                writeLong(resp, id);
                sendRaw(S_KEEPALIVE_PLAY, resp.toByteArray());
            }
            case C_PLAYER_POS_LOOK -> {
                DataInputStream d = pkt.stream();
                double x = d.readDouble(), y = d.readDouble(), z = d.readDouble();
                float yaw = d.readFloat(), pitch = d.readFloat();
                bot.updatePosition(x, y, z, yaw, pitch);
            }
            case C_DISCONNECT_PLAY -> {
                DataInputStream d = pkt.stream();
                throw new IOException("Kicked: " + readString(d));
            }
            // All other play packets silently ignored
        }
    }

    // ── Play-state send helpers ──────────────────────────────────────

    public void sendChatMessage(String msg) throws IOException {
        if (msg.length() > 256) msg = msg.substring(0, 256);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeString(buf, msg);
        writeLong(buf, System.currentTimeMillis());
        writeLong(buf, 0L);   // salt
        buf.write(0);          // no signature
        writeVarInt(buf, 0);   // message count
        buf.write(new byte[3]);// acknowledged bitset
        sendRaw(S_CHAT_MESSAGE, buf.toByteArray());
    }

    public void sendPositionAndLook(double x, double y, double z,
                                    float yaw, float pitch, boolean onGround) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeDouble(buf, x); writeDouble(buf, y); writeDouble(buf, z);
        writeFloat(buf, yaw); writeFloat(buf, pitch);
        buf.write(onGround ? 1 : 0);
        sendRaw(S_PLAYER_POS_ROT, buf.toByteArray());
    }

    public void sendLook(float yaw, float pitch, boolean onGround) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeFloat(buf, yaw); writeFloat(buf, pitch);
        buf.write(onGround ? 1 : 0);
        sendRaw(S_PLAYER_ROT, buf.toByteArray());
    }

    public void sendSwingArm() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarInt(buf, 0); // main hand
        sendRaw(S_SWING_ARM, buf.toByteArray());
    }

    public void sendEntityAction(EntityAction action) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarInt(buf, 0);          // entity id (server ignores, uses player's)
        writeVarInt(buf, action.id);
        writeVarInt(buf, 0);          // jump boost
        sendRaw(S_ENTITY_ACTION, buf.toByteArray());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Low-level I/O
    // ═══════════════════════════════════════════════════════════════════

    /** Send one packet; applies compression framing if negotiated. */
    private synchronized void sendRaw(int packetId, byte[] payload) throws IOException {
        // Build id+payload
        ByteArrayOutputStream idBuf = new ByteArrayOutputStream();
        writeVarInt(idBuf, packetId);
        idBuf.write(payload);
        byte[] data = idBuf.toByteArray();

        ByteArrayOutputStream frame = new ByteArrayOutputStream();

        if (compressionEnabled) {
            if (data.length >= compressionThreshold) {
                byte[] compressed = compress(data);
                ByteArrayOutputStream inner = new ByteArrayOutputStream();
                writeVarInt(inner, data.length);    // uncompressed length
                inner.write(compressed);
                byte[] inner2 = inner.toByteArray();
                writeVarInt(frame, inner2.length);
                frame.write(inner2);
            } else {
                // Under threshold: send uncompressed with dataLength=0
                ByteArrayOutputStream inner = new ByteArrayOutputStream();
                writeVarInt(inner, 0);              // dataLength = 0
                inner.write(data);
                byte[] inner2 = inner.toByteArray();
                writeVarInt(frame, inner2.length);
                frame.write(inner2);
            }
        } else {
            writeVarInt(frame, data.length);
            frame.write(data);
        }

        out.write(frame.toByteArray());
        out.flush();
    }

    /** Read one complete packet from the stream. Returns a Packet with id + raw payload bytes. */
    private Packet readPacket() throws IOException {
        int packetLength = readVarInt(in);

        if (!compressionEnabled) {
            // Simple: read packetLength bytes, first VarInt is packet id
            byte[] raw = in.readNBytes(packetLength);
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(raw));
            int id = readVarInt(d);
            byte[] rest = d.readAllBytes();
            return new Packet(id, rest);
        } else {
            // Compressed framing
            byte[] outerRaw = in.readNBytes(packetLength);
            DataInputStream outerD = new DataInputStream(new ByteArrayInputStream(outerRaw));
            int dataLength = readVarInt(outerD);
            byte[] inner = outerD.readAllBytes();

            byte[] uncompressed;
            if (dataLength == 0) {
                // Not compressed
                uncompressed = inner;
            } else {
                uncompressed = decompress(inner);
            }

            DataInputStream d = new DataInputStream(new ByteArrayInputStream(uncompressed));
            int id = readVarInt(d);
            byte[] rest = d.readAllBytes();
            return new Packet(id, rest);
        }
    }

    // ── Primitive encoders ───────────────────────────────────────────

    public static int readVarInt(DataInputStream in) throws IOException {
        int value = 0, shift = 0;
        byte b;
        do {
            b = in.readByte();
            value |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt too large");
        } while ((b & 0x80) != 0);
        return value;
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        return new String(in.readNBytes(len), StandardCharsets.UTF_8);
    }

    private static void writeString(OutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    private static void writeLong(OutputStream out, long v) throws IOException {
        for (int i = 7; i >= 0; i--) out.write((int) ((v >> (i * 8)) & 0xFF));
    }

    private static void writeShort(OutputStream out, int v) throws IOException {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeDouble(OutputStream out, double v) throws IOException {
        writeLong(out, Double.doubleToRawLongBits(v));
    }

    private static void writeFloat(OutputStream out, float v) throws IOException {
        int b = Float.floatToRawIntBits(v);
        out.write((b >> 24) & 0xFF); out.write((b >> 16) & 0xFF);
        out.write((b >>  8) & 0xFF); out.write(b & 0xFF);
    }

    // ── zlib ────────────────────────────────────────────────────────

    private static byte[] compress(byte[] data) throws IOException {
        java.util.zip.Deflater d = new java.util.zip.Deflater();
        d.setInput(data); d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return out.toByteArray();
    }

    private static byte[] decompress(byte[] data) throws IOException {
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        inf.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            while (!inf.finished()) out.write(buf, 0, inf.inflate(buf));
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("Decompression error", e);
        }
        inf.end();
        return out.toByteArray();
    }

    // ── Inner types ──────────────────────────────────────────────────

    private record Packet(int id, byte[] data) {
        DataInputStream stream() { return new DataInputStream(new ByteArrayInputStream(data)); }
    }

    public enum EntityAction {
        START_SNEAKING(5), STOP_SNEAKING(6),
        START_SPRINTING(3), STOP_SPRINTING(4);
        public final int id;
        EntityAction(int id) { this.id = id; }
    }
}
