package com.randomnpcs.net;

import com.randomnpcs.bot.BotClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Minecraft Java Edition protocol client — auto-detects server version via
 * status ping and selects the correct Play-state packet IDs.
 *
 * Verified packet ID tables (sourced from ProtocolLib master + PrismarineJS
 * minecraft-data + wiki.vg protocol history):
 *
 *   Protocol 764–767  →  1.20.3–1.21.1
 *   Protocol 768–769  →  1.21.2–1.21.4
 *   Protocol 770–773  →  1.21.5–1.21.4 (minor patch range)
 *   Protocol 774      →  1.21.5  (verified from ProtocolLib master)
 *   Protocol 775+     →  future / 26.x (not yet supported)
 *
 * The only packets we actually SEND are:
 *   Confirm Teleport, Keep Alive, Chat, Client Information,
 *   Plugin Message (brand), Position+Rotation, Rotation,
 *   Swing Arm, Player Command (sneak/sprint).
 *
 * The only packets we READ are:
 *   Keep Alive, Synchronize Player Position, Login (Play), Disconnect.
 */
public class MinecraftProtocol {

    // ── Serverbound: state-independent ───────────────────────────────
    private static final int S_HANDSHAKE        = 0x00;
    private static final int S_LOGIN_START       = 0x00;
    private static final int S_LOGIN_ACK         = 0x03; // Login → Configuration
    private static final int S_CONFIG_PLUGIN_MSG = 0x02; // Configuration: Plugin Message
    private static final int S_CONFIG_ACK        = 0x03; // Configuration → Play (Acknowledge Finish Configuration)
    private static final int S_CONFIG_KEEPALIVE  = 0x04; // Configuration: Keep Alive
    private static final int S_CLIENT_INFO_CFG   = 0x00; // Configuration: Client Information

    // ── Clientbound Login ─────────────────────────────────────────────
    private static final int C_DISCONNECT_LOGIN     = 0x00;
    private static final int C_ENCRYPTION_REQUEST   = 0x01;
    private static final int C_LOGIN_SUCCESS        = 0x02;
    private static final int C_SET_COMPRESSION      = 0x03;
    private static final int C_LOGIN_PLUGIN_REQUEST = 0x04;

    // ── Clientbound Configuration ─────────────────────────────────────
    // These IDs are stable across 764–774
    private static final int C_CONFIG_COOKIE_REQ  = 0x00;
    private static final int C_CONFIG_PLUGIN_MSG  = 0x01;
    private static final int C_CONFIG_DISCONNECT  = 0x02;
    private static final int C_CONFIG_FINISH      = 0x03;
    private static final int C_CONFIG_KEEPALIVE   = 0x04;
    private static final int C_CONFIG_PING        = 0x05;
    private static final int C_CONFIG_KNOWN_PACKS = 0x0E;

    // ── Play-state packet IDs — set by resolvePlayIds() ───────────────
    // Serverbound (S_ = we send to server)
    private int S_CONFIRM_TELEPORT;  // 0x00 across all versions
    private int S_CHAT;
    private int S_CLIENT_INFO_PLAY;
    private int S_PLUGIN_MSG_PLAY;
    private int S_KEEPALIVE;
    private int S_POS_ROT;
    private int S_ROT;
    private int S_PLAYER_COMMAND;    // sneak/sprint (entity_action)
    private int S_SWING_ARM;
    // Clientbound (C_ = we receive from server)
    private int C_LOGIN_PLAY;
    private int C_PLUGIN_MSG_PLAY;
    private int C_KEEPALIVE;
    private int C_SYNC_POS;          // Synchronize Player Position
    private int C_DISCONNECT_PLAY;

    private static final int PROTO_FALLBACK = 769; // 1.21.4

    private final DataInputStream  in;
    private final DataOutputStream out;
    private final Logger           log;

    private int     protocolVersion    = PROTO_FALLBACK;
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
     * Sources used (in order of authority):
     *  1. ProtocolLib master (github.com/dmulloy2/ProtocolLib) — reflects
     *     the IDs used by the most recent Paper version at build time.
     *  2. PrismarineJS minecraft-data + mineflayer issue #3888 — provides
     *     the full sequential ordering derived from the server jar via javap.
     *  3. wiki.vg / minecraft.wiki Protocol History — version-to-version diffs.
     *
     * ── Protocol 774 (1.21.5) — from ProtocolLib master ───────────────
     *  Serverbound Play (we use):
     *    0x00 ACCEPT_TELEPORTATION  (teleport_confirm)
     *    0x05 CHAT                  (chat_message)
     *    0x08 CLIENT_INFORMATION    (settings)
     *    0x12 CUSTOM_PAYLOAD        (custom_payload / brand)
     *    0x18 KEEP_ALIVE            (keep_alive)
     *    0x1A MOVE_PLAYER_POS_ROT   (position_look)
     *    0x1B MOVE_PLAYER_ROT       (look)
     *    0x22 PLAYER_COMMAND        (entity_action / sneak)
     *    0x2E SWING                 (arm_animation)
     *
     *  Clientbound Play (we read):
     *    0x18 CUSTOM_PAYLOAD        (custom_payload)
     *    0x2C KEEP_ALIVE            (keep_alive)
     *    0x31 LOGIN                 (login)
     *    0x20 DISCONNECT            (kick_disconnect)
     *    0x48 PLAYER_POSITION       (position / sync_pos)
     *
     * ── Protocol 768–769 (1.21.2–1.21.4) — from wiki.vg ──────────────
     *  Serverbound:
     *    0x00 teleport_confirm
     *    0x06 chat_message
     *    0x0A client_information
     *    0x13 custom_payload
     *    0x1A keep_alive
     *    0x1D position_look
     *    0x1E look
     *    0x2C entity_action
     *    0x36 arm_animation
     *  Clientbound:
     *    0x19 custom_payload
     *    0x26 keep_alive
     *    0x2B login
     *    0x1D disconnect
     *    0x40 player_position (sync)
     *
     * ── Protocol 764–767 (1.20.3–1.21.1) — from wiki.vg ──────────────
     *  Serverbound:
     *    0x00 teleport_confirm
     *    0x06 chat_message
     *    0x09 client_information
     *    0x12 custom_payload
     *    0x18 keep_alive
     *    0x1A position_look
     *    0x1B look
     *    0x27 entity_action
     *    0x33 arm_animation
     *  Clientbound:
     *    0x17 custom_payload
     *    0x24 keep_alive
     *    0x29 login
     *    0x1B disconnect
     *    0x3E player_position (sync)
     */
    private void resolvePlayIds(int proto) {
        if (proto >= 774) {
            // ── 1.21.5 (protocol 774) ────────────────────────────────
            // Source: ProtocolLib master (dmulloy2/ProtocolLib)
            S_CONFIRM_TELEPORT  = 0x00;
            S_CHAT              = 0x05;
            S_CLIENT_INFO_PLAY  = 0x08;
            S_PLUGIN_MSG_PLAY   = 0x12;
            S_KEEPALIVE         = 0x18;
            S_POS_ROT           = 0x1A;
            S_ROT               = 0x1B;
            S_PLAYER_COMMAND    = 0x22;
            S_SWING_ARM         = 0x2E;
            C_LOGIN_PLAY        = 0x31;
            C_PLUGIN_MSG_PLAY   = 0x18;
            C_KEEPALIVE         = 0x2C;
            C_SYNC_POS          = 0x48;
            C_DISCONNECT_PLAY   = 0x20;
        } else if (proto >= 768) {
            // ── 1.21.2–1.21.4 (protocol 768–769) ────────────────────
            S_CONFIRM_TELEPORT  = 0x00;
            S_CHAT              = 0x06;
            S_CLIENT_INFO_PLAY  = 0x0A;
            S_PLUGIN_MSG_PLAY   = 0x13;
            S_KEEPALIVE         = 0x1A;
            S_POS_ROT           = 0x1D;
            S_ROT               = 0x1E;
            S_PLAYER_COMMAND    = 0x2C;
            S_SWING_ARM         = 0x36;
            C_LOGIN_PLAY        = 0x2B;
            C_PLUGIN_MSG_PLAY   = 0x19;
            C_KEEPALIVE         = 0x26;
            C_SYNC_POS          = 0x40;
            C_DISCONNECT_PLAY   = 0x1D;
        } else {
            // ── 1.20.3–1.21.1 (protocol 764–767) ────────────────────
            S_CONFIRM_TELEPORT  = 0x00;
            S_CHAT              = 0x06;
            S_CLIENT_INFO_PLAY  = 0x09;
            S_PLUGIN_MSG_PLAY   = 0x12;
            S_KEEPALIVE         = 0x18;
            S_POS_ROT           = 0x1A;
            S_ROT               = 0x1B;
            S_PLAYER_COMMAND    = 0x27;
            S_SWING_ARM         = 0x33;
            C_LOGIN_PLAY        = 0x29;
            C_PLUGIN_MSG_PLAY   = 0x17;
            C_KEEPALIVE         = 0x24;
            C_SYNC_POS          = 0x3E;
            C_DISCONNECT_PLAY   = 0x1B;
        }
        log.info("[Protocol] v" + proto + " → S_KEEPALIVE=0x" + hex(S_KEEPALIVE)
                + " S_CHAT=0x" + hex(S_CHAT)
                + " S_POS_ROT=0x" + hex(S_POS_ROT)
                + " S_SWING=0x" + hex(S_SWING_ARM)
                + " C_SYNC_POS=0x" + hex(C_SYNC_POS)
                + " C_KEEPALIVE=0x" + hex(C_KEEPALIVE));
    }

    private static String hex(int v) { return Integer.toHexString(v); }

    // ═══════════════════════════════════════════════════════════════════
    // Status ping — auto-detect server protocol version
    // ═══════════════════════════════════════════════════════════════════

    public static int queryProtocolVersion(String host, int port, Logger log) {
        try (java.net.Socket s = new java.net.Socket(host, port)) {
            s.setSoTimeout(5_000);
            DataOutputStream o = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream  i = new DataInputStream(new BufferedInputStream(s.getInputStream()));

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            writeVarInt(buf, PROTO_FALLBACK);
            writeString(buf, host);
            writeShort(buf, port);
            writeVarInt(buf, 1);
            sendFramed(o, 0x00, buf.toByteArray());
            sendFramed(o, 0x00, new byte[0]);
            o.flush();

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
            log.fine("[" + botName + "] Login pkt 0x" + hex(pkt.id));

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
                    log.warning("[" + botName + "] Online-mode — not supported");
                    return false;
                }
                case C_LOGIN_PLUGIN_REQUEST -> {
                    int msgId = readVarInt(pkt.stream());
                    ByteArrayOutputStream resp = new ByteArrayOutputStream();
                    writeVarInt(resp, msgId);
                    resp.write(0); // not understood
                    sendRaw(0x02, resp.toByteArray());
                }
            }
        }
        return false;
    }

    private boolean completeConfiguration(String botName) throws IOException {
        sendRaw(S_CLIENT_INFO_CFG, buildClientInfo());

        for (int i = 0; i < 300; i++) {
            Packet pkt = readPacket();
            log.info("[" + botName + "] Config packet 0x" + hex(pkt.id)
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
                    sendRaw(0x04, r.toByteArray()); // Pong
                }
                case C_CONFIG_DISCONNECT -> {
                    log.warning("[" + botName + "] Config kick: " + readString(pkt.stream()));
                    return false;
                }
                case C_CONFIG_KNOWN_PACKS -> {
                    ByteArrayOutputStream r = new ByteArrayOutputStream();
                    writeVarInt(r, 0); // empty known packs list
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
                // Registry data (0x07), tags (0x0D), etc. — silently ignored
            }
        }
        log.warning("[" + botName + "] Config phase timed out");
        return false;
    }

    private byte[] buildClientInfo() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeString(b, "zh_cn");   // locale
        b.write(10);               // view distance
        writeVarInt(b, 0);         // chat mode: enabled
        b.write(1);                // chat colors
        b.write(0b01111111);       // skin parts (all)
        writeVarInt(b, 1);         // main hand: right
        b.write(0);                // text filtering: disabled
        b.write(1);                // server listings: enabled
        // particle_status added in 1.21.5 (protocol 770+)
        if (protocolVersion >= 770) writeVarInt(b, 2); // all particles
        return b.toByteArray();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Play-state read handler (called in tight loop from reader thread)
    // ═══════════════════════════════════════════════════════════════════

    public void readAndHandle(BotClient bot) throws IOException {
        Packet pkt = readPacket();
        int id = pkt.id;

        if (id == C_KEEPALIVE) {
            // Must respond within 15s or get kicked
            long keepAliveId = pkt.stream().readLong();
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            writeLong(r, keepAliveId);
            sendRaw(S_KEEPALIVE, r.toByteArray());

        } else if (id == C_SYNC_POS) {
            // Synchronize Player Position — MUST send Confirm Teleport first
            // Packet layout differs by version:
            //   768+ (1.21.2+): VarInt teleportId, then Double x/y/z,
            //                   Double dx/dy/dz (velocity), Float yaw/pitch, Int flags
            //   764–767:        Double x/y/z, Float yaw/pitch, Byte flags,
            //                   VarInt teleportId
            DataInputStream d = pkt.stream();
            int teleportId;
            double x, y, z;
            float yaw, pitch;

            if (protocolVersion >= 768) {
                teleportId = readVarInt(d);
                x = d.readDouble(); y = d.readDouble(); z = d.readDouble();
                d.readDouble(); d.readDouble(); d.readDouble(); // velocity, skip
                yaw = d.readFloat(); pitch = d.readFloat();
                d.readInt(); // flags (relative bits)
            } else {
                x = d.readDouble(); y = d.readDouble(); z = d.readDouble();
                yaw = d.readFloat(); pitch = d.readFloat();
                d.readByte();  // flags
                teleportId = readVarInt(d);
            }

            // Send Confirm Teleport (must come before any position packet)
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            writeVarInt(r, teleportId);
            sendRaw(S_CONFIRM_TELEPORT, r.toByteArray());

            bot.updatePosition(x, y, z, yaw, pitch);

        } else if (id == C_LOGIN_PLAY) {
            // First packet after entering Play state — respond with Client Info + brand
            sendRaw(S_CLIENT_INFO_PLAY, buildClientInfo());
            ByteArrayOutputStream brand = new ByteArrayOutputStream();
            writeString(brand, "minecraft:brand");
            writeString(brand, "vanilla");
            sendRaw(S_PLUGIN_MSG_PLAY, brand.toByteArray());
            log.fine("[Play] Sent Client Information + brand");

        } else if (id == C_DISCONNECT_PLAY) {
            throw new IOException("Kicked: " + readString(pkt.stream()));
        }
        // All other play packets silently consumed
    }

    // ═══════════════════════════════════════════════════════════════════
    // Play-state send helpers
    // ═══════════════════════════════════════════════════════════════════

    public void sendChatMessage(String msg) throws IOException {
        if (msg.length() > 256) msg = msg.substring(0, 256);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeString(b, msg);
        writeLong(b, System.currentTimeMillis());
        writeLong(b, 0L);     // salt
        b.write(0);            // no signature
        writeVarInt(b, 0);     // message count
        b.write(new byte[3]);  // acknowledged bitset (20 bits = 3 bytes, all zero)
        sendRaw(S_CHAT, b.toByteArray());
    }

    public void sendPositionAndLook(double x, double y, double z,
                                    float yaw, float pitch, boolean onGround) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeDouble(b, x); writeDouble(b, y); writeDouble(b, z);
        writeFloat(b, yaw); writeFloat(b, pitch);
        b.write(onGround ? 1 : 0);
        sendRaw(S_POS_ROT, b.toByteArray());
    }

    public void sendLook(float yaw, float pitch, boolean onGround) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeFloat(b, yaw); writeFloat(b, pitch);
        b.write(onGround ? 1 : 0);
        sendRaw(S_ROT, b.toByteArray());
    }

    public void sendSwingArm() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, 0); // main hand
        sendRaw(S_SWING_ARM, b.toByteArray());
    }

    public void sendEntityAction(EntityAction action) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, 0);          // entity id (server fills in from session)
        writeVarInt(b, action.id);
        writeVarInt(b, 0);          // jump boost (unused for sneak/sprint)
        sendRaw(S_PLAYER_COMMAND, b.toByteArray());
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

    private static byte[] readExactly(DataInputStream in, int n) throws IOException {
        if (n == 0) return new byte[0];
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r < 0) throw new EOFException("Stream ended: got " + read + "/" + n);
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
        catch (java.util.zip.DataFormatException e) { throw new IOException("Decompress", e); }
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
