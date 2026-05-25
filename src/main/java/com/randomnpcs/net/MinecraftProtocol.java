package com.randomnpcs.net;

import com.randomnpcs.bot.BotClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Minecraft Java Edition protocol client (auto-detects server version).
 *
 * Key design decisions:
 *  - One instance per BotClient, shared for entire connection lifetime
 *    (preserves compression state).
 *  - Packet IDs are resolved at runtime from the detected protocol version.
 *  - MUST send Confirm Teleport (0x00) in response to Synchronize Player
 *    Position, or the server will disconnect after ~20 ticks.
 */
public class MinecraftProtocol {

    // ── Serverbound: state-independent ───────────────────────────────
    private static final int S_HANDSHAKE        = 0x00;
    private static final int S_LOGIN_START       = 0x00;
    private static final int S_LOGIN_ACK         = 0x03;
    private static final int S_CONFIG_PLUGIN_MSG = 0x02;
    private static final int S_CONFIG_ACK        = 0x03; // Acknowledge Finish Configuration
    private static final int S_CONFIG_KEEPALIVE  = 0x04;
    private static final int S_CLIENT_INFO_CFG   = 0x00; // Configuration: Client Information

    // ── Clientbound Login ─────────────────────────────────────────────
    private static final int C_DISCONNECT_LOGIN     = 0x00;
    private static final int C_ENCRYPTION_REQUEST   = 0x01;
    private static final int C_LOGIN_SUCCESS        = 0x02;
    private static final int C_SET_COMPRESSION      = 0x03;
    private static final int C_LOGIN_PLUGIN_REQUEST = 0x04;

    // ── Clientbound Configuration ─────────────────────────────────────
    // IDs valid for protocol 764–774 (1.20.3 – 1.21.x)
    private static final int C_CONFIG_COOKIE_REQ  = 0x00;
    private static final int C_CONFIG_PLUGIN_MSG  = 0x01;
    private static final int C_CONFIG_DISCONNECT  = 0x02;
    private static final int C_CONFIG_FINISH      = 0x03;
    private static final int C_CONFIG_KEEPALIVE   = 0x04;
    private static final int C_CONFIG_PING        = 0x05;
    private static final int C_CONFIG_KNOWN_PACKS = 0x0E;

    // ── Play packet IDs — version-dependent, resolved in resolvePlayIds() ──
    private int P_S_CONFIRM_TELEPORT; // Serverbound: Confirm Teleport
    private int P_S_CHAT;             // Serverbound: Chat Message
    private int P_S_CLIENT_INFO;      // Serverbound: Client Information (Play)
    private int P_S_PLUGIN_MSG;       // Serverbound: Plugin Message (Play)
    private int P_S_KEEPALIVE;        // Serverbound: Keep Alive
    private int P_S_POS_ROT;          // Serverbound: Set Player Position and Rotation
    private int P_S_ROT;              // Serverbound: Set Player Rotation
    private int P_S_ENTITY_ACTION;    // Serverbound: Player Command (sneak/sprint)
    private int P_S_SWING_ARM;        // Serverbound: Swing Arm
    private int P_C_LOGIN_PLAY;       // Clientbound: Login (Play)
    private int P_C_PLUGIN_MSG;       // Clientbound: Plugin Message
    private int P_C_KEEPALIVE;        // Clientbound: Keep Alive
    private int P_C_SYNC_POS;         // Clientbound: Synchronize Player Position
    private int P_C_DISCONNECT;       // Clientbound: Disconnect (Play)

    private static final int PROTO_FALLBACK = 770; // 1.21.4

    private final DataInputStream  in;
    private final DataOutputStream out;
    private final Logger           log;

    private int     protocolVersion   = PROTO_FALLBACK;
    private boolean compressionEnabled = false;
    private int     compressionThreshold = -1;

    public MinecraftProtocol(DataInputStream in, DataOutputStream out, Logger log) {
        this.in  = in;
        this.out = out;
        this.log = log;
        resolvePlayIds(PROTO_FALLBACK);
    }

    public void setProtocolVersion(int v) {
        this.protocolVersion = v;
        resolvePlayIds(v);
    }

    /**
     * Resolve Play-state packet IDs for the given protocol version.
     *
     * Sources:
     *   https://minecraft.wiki/w/Java_Edition_protocol/Packets
     *   Protocol history diffs between versions.
     *
     * Tested ranges:
     *   764–767  (1.20.3–1.21.1)
     *   768–769  (1.21.2–1.21.4)
     *   770–774  (1.21.5–1.21.x)
     */
    private void resolvePlayIds(int proto) {
        if (proto >= 770) {
            // ── 1.21.5+ (protocol 770+) ─────────────────────────────
            P_S_CONFIRM_TELEPORT = 0x00;
            P_S_CHAT             = 0x07;
            P_S_CLIENT_INFO      = 0x0A;
            P_S_PLUGIN_MSG       = 0x14;
            P_S_KEEPALIVE        = 0x1B;
            P_S_POS_ROT          = 0x1E;
            P_S_ROT              = 0x1F;
            P_S_ENTITY_ACTION    = 0x2D;
            P_S_SWING_ARM        = 0x37;
            P_C_LOGIN_PLAY       = 0x2C;
            P_C_PLUGIN_MSG       = 0x1A;
            P_C_KEEPALIVE        = 0x27;
            P_C_SYNC_POS         = 0x41;
            P_C_DISCONNECT       = 0x1E;
        } else if (proto >= 768) {
            // ── 1.21.2–1.21.4 (protocol 768–769) ───────────────────
            P_S_CONFIRM_TELEPORT = 0x00;
            P_S_CHAT             = 0x06;
            P_S_CLIENT_INFO      = 0x0A;
            P_S_PLUGIN_MSG       = 0x13;
            P_S_KEEPALIVE        = 0x1A;
            P_S_POS_ROT          = 0x1D;
            P_S_ROT              = 0x1E;
            P_S_ENTITY_ACTION    = 0x2C;
            P_S_SWING_ARM        = 0x36;
            P_C_LOGIN_PLAY       = 0x2B;
            P_C_PLUGIN_MSG       = 0x19;
            P_C_KEEPALIVE        = 0x26;
            P_C_SYNC_POS         = 0x40;
            P_C_DISCONNECT       = 0x1D;
        } else {
            // ── 1.20.3–1.21.1 (protocol 764–767) ───────────────────
            P_S_CONFIRM_TELEPORT = 0x00;
            P_S_CHAT             = 0x06;
            P_S_CLIENT_INFO      = 0x09;
            P_S_PLUGIN_MSG       = 0x12;
            P_S_KEEPALIVE        = 0x18;
            P_S_POS_ROT          = 0x1A;
            P_S_ROT              = 0x1B;
            P_S_ENTITY_ACTION    = 0x27;
            P_S_SWING_ARM        = 0x33;
            P_C_LOGIN_PLAY       = 0x29;
            P_C_PLUGIN_MSG       = 0x17;
            P_C_KEEPALIVE        = 0x24;
            P_C_SYNC_POS         = 0x3E;
            P_C_DISCONNECT       = 0x1B;
        }
        log.fine("[Protocol] Resolved Play IDs for protocol " + proto
                + ": SYNC_POS=0x" + Integer.toHexString(P_C_SYNC_POS)
                + " KEEPALIVE=0x" + Integer.toHexString(P_C_KEEPALIVE)
                + " LOGIN_PLAY=0x" + Integer.toHexString(P_C_LOGIN_PLAY));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Status ping — detect server protocol version
    // ═══════════════════════════════════════════════════════════════════

    public static int queryProtocolVersion(String host, int port, Logger log) {
        try (java.net.Socket s = new java.net.Socket(host, port)) {
            s.setSoTimeout(5_000);
            DataOutputStream o = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream  i = new DataInputStream(new BufferedInputStream(s.getInputStream()));

            // Handshake (next state = 1, Status)
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            writeVarInt(buf, PROTO_FALLBACK);
            writeString(buf, host);
            writeShort(buf, port);
            writeVarInt(buf, 1);
            sendFramed(o, 0x00, buf.toByteArray());

            // Status Request
            sendFramed(o, 0x00, new byte[0]);
            o.flush();

            // Status Response
            int pktLen = readVarInt(i);
            byte[] raw = readExactly(i, pktLen);
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(raw));
            if (readVarInt(d) != 0x00) return -1;
            String json = readString(d);

            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"protocol\"\\s*:\\s*(-?\\d+)").matcher(json);
            if (m.find()) {
                int proto = Integer.parseInt(m.group(1));
                log.info("[StatusPing] Server protocol version: " + proto);
                return proto;
            }
        } catch (Exception e) {
            log.warning("[StatusPing] Failed: " + e.getMessage());
        }
        return -1;
    }

    private static void sendFramed(DataOutputStream o, int id, byte[] payload) throws IOException {
        ByteArrayOutputStream idBuf = new ByteArrayOutputStream();
        writeVarInt(idBuf, id);
        idBuf.write(payload);
        byte[] data = idBuf.toByteArray();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarInt(frame, data.length);
        frame.write(data);
        o.write(frame.toByteArray());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Login + Configuration sequence
    // ═══════════════════════════════════════════════════════════════════

    public void initiateLogin(String host, int port, String name, UUID uuid) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarInt(buf, protocolVersion);
        writeString(buf, host);
        writeShort(buf, port);
        writeVarInt(buf, 2);
        sendRaw(S_HANDSHAKE, buf.toByteArray());

        buf.reset();
        writeString(buf, name);
        writeLong(buf, uuid.getMostSignificantBits());
        writeLong(buf, uuid.getLeastSignificantBits());
        sendRaw(S_LOGIN_START, buf.toByteArray());
    }

    public boolean completeLogin(String botName) throws IOException {
        for (int i = 0; i < 50; i++) {
            Packet pkt = readPacket();
            log.fine("[" + botName + "] Login pkt 0x" + Integer.toHexString(pkt.id));

            switch (pkt.id) {
                case C_SET_COMPRESSION -> {
                    compressionThreshold = readVarInt(pkt.stream());
                    compressionEnabled   = compressionThreshold >= 0;
                    log.fine("[" + botName + "] Compression=" + compressionThreshold);
                }
                case C_LOGIN_SUCCESS -> {
                    DataInputStream d = pkt.stream();
                    d.skipBytes(16); // UUID
                    readString(d);   // username echo
                    int props = readVarInt(d);
                    for (int p = 0; p < props; p++) {
                        readString(d); readString(d);
                        if (d.readBoolean()) readString(d);
                    }
                    sendRaw(S_LOGIN_ACK, new byte[0]);
                    return completeConfiguration(botName);
                }
                case C_DISCONNECT_LOGIN -> {
                    log.warning("[" + botName + "] Login kick: " + readString(pkt.stream()));
                    return false;
                }
                case C_ENCRYPTION_REQUEST -> {
                    log.warning("[" + botName + "] Online-mode server — not supported");
                    return false;
                }
                case C_LOGIN_PLUGIN_REQUEST -> {
                    int msgId = readVarInt(pkt.stream());
                    ByteArrayOutputStream resp = new ByteArrayOutputStream();
                    writeVarInt(resp, msgId);
                    resp.write(0);
                    sendRaw(0x02, resp.toByteArray());
                }
            }
        }
        return false;
    }

    private boolean completeConfiguration(String botName) throws IOException {
        // Send Client Information (Configuration state)
        sendRaw(S_CLIENT_INFO_CFG, buildClientInfo());

        for (int i = 0; i < 300; i++) {
            Packet pkt = readPacket();
            log.info("[" + botName + "] Config packet 0x" + Integer.toHexString(pkt.id)
                    + " (" + pkt.data.length + " bytes)");

            switch (pkt.id) {
                case C_CONFIG_FINISH -> {
                    sendRaw(S_CONFIG_ACK, new byte[0]);
                    log.info("[" + botName + "] Configuration complete, entering Play state");
                    return true;
                }
                case C_CONFIG_KEEPALIVE -> {
                    long id = pkt.stream().readLong();
                    ByteArrayOutputStream r = new ByteArrayOutputStream();
                    writeLong(r, id);
                    sendRaw(S_CONFIG_KEEPALIVE, r.toByteArray());
                }
                case C_CONFIG_PING -> {
                    int pingId = pkt.stream().readInt();
                    ByteArrayOutputStream r = new ByteArrayOutputStream();
                    r.write((pingId >> 24) & 0xFF); r.write((pingId >> 16) & 0xFF);
                    r.write((pingId >>  8) & 0xFF); r.write(pingId & 0xFF);
                    sendRaw(0x04, r.toByteArray());
                }
                case C_CONFIG_DISCONNECT -> {
                    log.warning("[" + botName + "] Config kick: " + readString(pkt.stream()));
                    return false;
                }
                case C_CONFIG_KNOWN_PACKS -> {
                    // Reply with empty known-packs list
                    ByteArrayOutputStream r = new ByteArrayOutputStream();
                    writeVarInt(r, 0);
                    sendRaw(0x07, r.toByteArray());
                    log.info("[" + botName + "] Replied to Select Known Packs");
                }
                case C_CONFIG_PLUGIN_MSG -> {
                    DataInputStream d = pkt.stream();
                    String channel = readString(d);
                    if ("minecraft:brand".equals(channel)) {
                        ByteArrayOutputStream r = new ByteArrayOutputStream();
                        writeString(r, "minecraft:brand");
                        writeString(r, "vanilla");
                        sendRaw(S_CONFIG_PLUGIN_MSG, r.toByteArray());
                        log.info("[" + botName + "] Replied to minecraft:brand");
                    }
                }
                // 0x00 cookie request, 0x07 registry data, 0x0C/0x0D tags — all silently ignored
            }
        }
        log.warning("[" + botName + "] Config phase timed out");
        return false;
    }

    private byte[] buildClientInfo() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeString(b, "zh_cn");
        b.write(10);          // view distance
        writeVarInt(b, 0);    // chat mode: enabled
        b.write(1);           // chat colors
        b.write(0b01111111);  // skin parts
        writeVarInt(b, 1);    // main hand: right
        b.write(0);           // text filtering
        b.write(1);           // server listings
        // particle status added in 1.21.5 (protocol 770+)
        if (protocolVersion >= 770) writeVarInt(b, 2);
        return b.toByteArray();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Play state packet handler
    // ═══════════════════════════════════════════════════════════════════

    public void readAndHandle(BotClient bot) throws IOException {
        Packet pkt = readPacket();
        int id = pkt.id;

        if (id == P_C_KEEPALIVE) {
            // ── Keep Alive: must respond within 30s or get kicked ──────
            long keepAliveId = pkt.stream().readLong();
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            writeLong(r, keepAliveId);
            sendRaw(P_S_KEEPALIVE, r.toByteArray());

        } else if (id == P_C_SYNC_POS) {
            // ── Synchronize Player Position ───────────────────────────
            // CRITICAL: must send Confirm Teleport (0x00) first, then
            // Set Player Position and Rotation to acknowledge.
            DataInputStream d = pkt.stream();
            double x, y, z;
            float yaw, pitch;

            if (protocolVersion >= 768) {
                // 1.21.2+: teleportId is first (VarInt), then position
                int teleportId = readVarInt(d);
                x = d.readDouble(); y = d.readDouble(); z = d.readDouble();
                // velocity (dx, dy, dz) — 3 doubles, skip
                d.readDouble(); d.readDouble(); d.readDouble();
                yaw   = d.readFloat();
                pitch = d.readFloat();
                // flags byte (which fields are relative)
                d.readInt(); // flags (int in 1.21.2+)
                sendConfirmTeleport(teleportId);
            } else {
                // 1.20.3–1.21.1: position first, then teleportId
                x = d.readDouble(); y = d.readDouble(); z = d.readDouble();
                yaw   = d.readFloat();
                pitch = d.readFloat();
                d.readByte(); // flags
                int teleportId = readVarInt(d);
                sendConfirmTeleport(teleportId);
            }
            bot.updatePosition(x, y, z, yaw, pitch);

        } else if (id == P_C_LOGIN_PLAY) {
            // ── Login (Play): first packet in Play state ───────────────
            // Parse just enough to not desync; respond with client info + brand
            sendRaw(P_S_CLIENT_INFO, buildClientInfo());
            ByteArrayOutputStream brand = new ByteArrayOutputStream();
            writeString(brand, "minecraft:brand");
            writeString(brand, "vanilla");
            sendRaw(P_S_PLUGIN_MSG, brand.toByteArray());
            log.fine("[Play] Sent Client Information + brand");

        } else if (id == P_C_DISCONNECT) {
            DataInputStream d = pkt.stream();
            throw new IOException("Kicked from Play: " + readString(d));
        }
        // All other packets silently consumed (chunk data, entity updates, etc.)
    }

    /** Confirm Teleport (0x00 serverbound, Play state) — must match teleport ID from server. */
    private void sendConfirmTeleport(int teleportId) throws IOException {
        ByteArrayOutputStream r = new ByteArrayOutputStream();
        writeVarInt(r, teleportId);
        sendRaw(P_S_CONFIRM_TELEPORT, r.toByteArray());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Play-state send helpers
    // ═══════════════════════════════════════════════════════════════════

    public void sendChatMessage(String msg) throws IOException {
        if (msg.length() > 256) msg = msg.substring(0, 256);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeString(b, msg);
        writeLong(b, System.currentTimeMillis());
        writeLong(b, 0L);   // salt
        b.write(0);          // no signature
        writeVarInt(b, 0);   // message count
        b.write(new byte[3]);// acknowledged bitset (empty, 20 bits → 3 bytes)
        sendRaw(P_S_CHAT, b.toByteArray());
    }

    public void sendPositionAndLook(double x, double y, double z,
                                    float yaw, float pitch, boolean onGround) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeDouble(b, x); writeDouble(b, y); writeDouble(b, z);
        writeFloat(b, yaw); writeFloat(b, pitch);
        b.write(onGround ? 1 : 0);
        sendRaw(P_S_POS_ROT, b.toByteArray());
    }

    public void sendLook(float yaw, float pitch, boolean onGround) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeFloat(b, yaw); writeFloat(b, pitch);
        b.write(onGround ? 1 : 0);
        sendRaw(P_S_ROT, b.toByteArray());
    }

    public void sendSwingArm() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, 0); // main hand
        sendRaw(P_S_SWING_ARM, b.toByteArray());
    }

    public void sendEntityAction(EntityAction action) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, 0);
        writeVarInt(b, action.id);
        writeVarInt(b, 0);
        sendRaw(P_S_ENTITY_ACTION, b.toByteArray());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Low-level framing
    // ═══════════════════════════════════════════════════════════════════

    private synchronized void sendRaw(int packetId, byte[] payload) throws IOException {
        ByteArrayOutputStream idBuf = new ByteArrayOutputStream();
        writeVarInt(idBuf, packetId);
        idBuf.write(payload);
        byte[] data = idBuf.toByteArray();

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        if (compressionEnabled) {
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            if (data.length >= compressionThreshold) {
                writeVarInt(inner, data.length);
                inner.write(compress(data));
            } else {
                writeVarInt(inner, 0);
                inner.write(data);
            }
            byte[] innerBytes = inner.toByteArray();
            writeVarInt(frame, innerBytes.length);
            frame.write(innerBytes);
        } else {
            writeVarInt(frame, data.length);
            frame.write(data);
        }
        out.write(frame.toByteArray());
        out.flush();
    }

    private Packet readPacket() throws IOException {
        int pktLen = readVarInt(in);

        if (!compressionEnabled) {
            byte[] raw = readExactly(in, pktLen);
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(raw));
            int id = readVarInt(d);
            return new Packet(id, d.readAllBytes());
        } else {
            byte[] outer = readExactly(in, pktLen);
            DataInputStream od = new DataInputStream(new ByteArrayInputStream(outer));
            int dataLen = readVarInt(od);
            byte[] inner = od.readAllBytes();
            byte[] uncompressed = (dataLen == 0) ? inner : decompress(inner);
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(uncompressed));
            int id = readVarInt(d);
            return new Packet(id, d.readAllBytes());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Primitives
    // ═══════════════════════════════════════════════════════════════════

    /** Read exactly n bytes — safer than readNBytes which can return fewer on slow streams. */
    private static byte[] readExactly(DataInputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r < 0) throw new EOFException("Stream ended after " + read + " of " + n + " bytes");
            read += r;
        }
        return buf;
    }

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

    private static void writeVarInt(OutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) { out.write((v & 0x7F) | 0x80); v >>>= 7; }
        out.write(v);
    }

    private static String readString(DataInputStream in) throws IOException {
        return new String(readExactly(in, readVarInt(in)), StandardCharsets.UTF_8);
    }

    private static void writeString(OutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    private static void writeLong(OutputStream out, long v) throws IOException {
        for (int i = 7; i >= 0; i--) out.write((int)((v >> (i * 8)) & 0xFF));
    }

    private static void writeShort(OutputStream out, int v) throws IOException {
        out.write((v >> 8) & 0xFF); out.write(v & 0xFF);
    }

    private static void writeDouble(OutputStream out, double v) throws IOException {
        writeLong(out, Double.doubleToRawLongBits(v));
    }

    private static void writeFloat(OutputStream out, float v) throws IOException {
        int b = Float.floatToRawIntBits(v);
        out.write((b>>24)&0xFF); out.write((b>>16)&0xFF);
        out.write((b>>8)&0xFF);  out.write(b&0xFF);
    }

    private static byte[] compress(byte[] data) throws IOException {
        java.util.zip.Deflater df = new java.util.zip.Deflater();
        df.setInput(data); df.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!df.finished()) out.write(buf, 0, df.deflate(buf));
        df.end();
        return out.toByteArray();
    }

    private static byte[] decompress(byte[] data) throws IOException {
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        inf.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try { while (!inf.finished()) out.write(buf, 0, inf.inflate(buf)); }
        catch (java.util.zip.DataFormatException e) { throw new IOException("Decompress fail", e); }
        inf.end();
        return out.toByteArray();
    }

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
