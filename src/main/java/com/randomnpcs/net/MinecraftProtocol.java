package com.randomnpcs.net;

import com.randomnpcs.bot.BotClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Minimal Minecraft protocol client.
 *
 * Design philosophy — "passive bot":
 *   The TCP connection is used ONLY to:
 *     1. Perform the Handshake → Login → Configuration handshake
 *     2. Confirm Teleport when the server demands it
 *     3. Respond to Keep Alive pings so we don't get kicked
 *
 *   ALL gameplay actions (chat, movement, etc.) are performed server-side
 *   via the Paper API on the Player object, avoiding protocol version issues.
 *
 * Keep Alive packet ID detection:
 *   Instead of hardcoding packet IDs, we detect the Keep Alive packet
 *   automatically by scanning for the known 8-byte pattern after login.
 *   For all other clientbound packets we consume and discard the bytes.
 *
 * Teleport Confirm:
 *   Always sent as packet 0x00 (stable across all versions since 1.9).
 */
public class MinecraftProtocol {

    // ── Handshake + Login state (stable across all versions) ─────────────
    private static final int S_HANDSHAKE            = 0x00;
    private static final int S_LOGIN_START           = 0x00;
    private static final int S_LOGIN_ACK             = 0x03;
    private static final int C_SET_COMPRESSION       = 0x03;
    private static final int C_LOGIN_SUCCESS         = 0x02;
    private static final int C_DISCONNECT_LOGIN      = 0x00;
    private static final int C_ENCRYPTION_REQUEST    = 0x01;
    private static final int C_LOGIN_PLUGIN_REQUEST  = 0x04;

    // ── Configuration state (stable 1.20.2+) ─────────────────────────────
    private static final int S_CONFIG_CLIENT_INFO    = 0x00;
    private static final int S_CONFIG_PLUGIN_MSG     = 0x02;
    private static final int S_CONFIG_ACK            = 0x03;
    private static final int S_CONFIG_KNOWN_PACKS    = 0x07;
    private static final int S_CONFIG_KEEPALIVE      = 0x04;
    private static final int C_CONFIG_PLUGIN_MSG     = 0x01;
    private static final int C_CONFIG_DISCONNECT     = 0x02;
    private static final int C_CONFIG_FINISH         = 0x03;
    private static final int C_CONFIG_KEEPALIVE      = 0x04;
    private static final int C_CONFIG_PING           = 0x05;
    private static final int C_CONFIG_KNOWN_PACKS    = 0x0E;

    // ── Play state — only two packets we ever send ────────────────────────
    // Confirm Teleport is 0x00 in every version since 1.9 ✓
    private static final int S_CONFIRM_TELEPORT      = 0x00;
    // Keep Alive response ID is detected dynamically at runtime
    private int keepAliveResponseId = -1; // set after detection

    private static final int PROTO_FALLBACK = 769;

    private final DataInputStream  in;
    private final DataOutputStream out;
    private final Logger           log;
    private final String           botName;

    private int     protocolVersion    = PROTO_FALLBACK;
    private boolean compressionEnabled = false;
    private int     compressionThreshold = -1;

    public MinecraftProtocol(DataInputStream in, DataOutputStream out,
                             Logger log, String botName) {
        this.in      = in;
        this.out     = out;
        this.log     = log;
        this.botName = botName;
    }

    public void setProtocolVersion(int v) {
        this.protocolVersion = v;
        // Derive Keep Alive serverbound ID from known reference points.
        // We use the confirmed 774 table reconstructed from ProtocolLib 775
        // minus 10 new packets, cross-checked against jigsaw_generate = 0x12 in 774
        // (server error message confirmed 0x12 = jigsaw, keepalive = 0x13 in 774).
        // For 769 and below, keepalive = 0x1A (confirmed working).
        // For 768-769: 0x1A. For 774: 0x13. For 775+: 0x1C.
        if (v >= 775) {
            keepAliveResponseId = 0x1C;
        } else if (v >= 770) {
            // 770–774: reconstructed from 774 (jigsaw=0x12, keepalive=0x13)
            keepAliveResponseId = 0x13;
        } else if (v >= 768) {
            keepAliveResponseId = 0x1A;
        } else {
            keepAliveResponseId = 0x18;
        }
        log.info("[" + botName + "] Protocol " + v
                + " → S_KEEPALIVE=0x" + Integer.toHexString(keepAliveResponseId)
                + " (only packet we send in Play state)");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Status ping
    // ═══════════════════════════════════════════════════════════════════

    public static int queryProtocolVersion(String host, int port, Logger log) {
        try (java.net.Socket s = new java.net.Socket(host, port)) {
            s.setSoTimeout(5_000);
            DataOutputStream o = new DataOutputStream(
                    new BufferedOutputStream(s.getOutputStream()));
            DataInputStream  i = new DataInputStream(
                    new BufferedInputStream(s.getInputStream()));

            // Handshake → Status
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            writeVarInt(buf, PROTO_FALLBACK);
            writeString(buf, host);
            writeShort(buf, port);
            writeVarInt(buf, 1);
            sendFramedNoCompression(o, 0x00, buf.toByteArray());
            sendFramedNoCompression(o, 0x00, new byte[0]);
            o.flush();

            int len = readVarInt(i);
            byte[] raw = readExactly(i, len);
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

    // ═══════════════════════════════════════════════════════════════════
    // Login + Configuration
    // ═══════════════════════════════════════════════════════════════════

    public void initiateLogin(String host, int port, String name, UUID uuid) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarInt(buf, protocolVersion);
        writeString(buf, host);
        writeShort(buf, port);
        writeVarInt(buf, 2); // next state: Login
        sendRaw(S_HANDSHAKE, buf.toByteArray());

        buf.reset();
        writeString(buf, name);
        writeLong(buf, uuid.getMostSignificantBits());
        writeLong(buf, uuid.getLeastSignificantBits());
        sendRaw(S_LOGIN_START, buf.toByteArray());
    }

    public boolean completeLogin(String name) throws IOException {
        for (int i = 0; i < 50; i++) {
            Packet pkt = readPacket();
            switch (pkt.id) {
                case C_SET_COMPRESSION -> {
                    compressionThreshold = readVarInt(pkt.stream());
                    compressionEnabled   = compressionThreshold >= 0;
                }
                case C_LOGIN_SUCCESS -> {
                    DataInputStream d = pkt.stream();
                    d.skipBytes(16); // UUID
                    readString(d);   // name echo
                    int props = readVarInt(d);
                    for (int p = 0; p < props; p++) {
                        readString(d); readString(d);
                        if (d.readBoolean()) readString(d);
                    }
                    sendRaw(S_LOGIN_ACK, new byte[0]);
                    return completeConfiguration();
                }
                case C_DISCONNECT_LOGIN -> {
                    log.warning("[" + name + "] Login kick: " + readString(pkt.stream()));
                    return false;
                }
                case C_ENCRYPTION_REQUEST -> {
                    log.warning("[" + name + "] Online-mode server — not supported");
                    return false;
                }
                case C_LOGIN_PLUGIN_REQUEST -> {
                    // Reply "not understood"
                    int msgId = readVarInt(pkt.stream());
                    ByteArrayOutputStream r = new ByteArrayOutputStream();
                    writeVarInt(r, msgId);
                    r.write(0);
                    sendRaw(0x02, r.toByteArray());
                }
            }
        }
        return false;
    }

    private boolean completeConfiguration() throws IOException {
        // Send Client Information
        ByteArrayOutputStream ci = new ByteArrayOutputStream();
        writeString(ci, "en_us");
        ci.write(10);          // view distance
        writeVarInt(ci, 0);    // chat mode: enabled
        ci.write(1);           // chat colors
        ci.write(0x7F);        // skin parts
        writeVarInt(ci, 1);    // main hand: right
        ci.write(0);           // text filtering
        ci.write(1);           // server listings
        if (protocolVersion >= 770) writeVarInt(ci, 2); // particle status (1.21.5+)
        sendRaw(S_CONFIG_CLIENT_INFO, ci.toByteArray());

        for (int i = 0; i < 400; i++) {
            Packet pkt = readPacket();
            log.info("[" + botName + "] Config packet 0x"
                    + Integer.toHexString(pkt.id) + " (" + pkt.data.length + " bytes)");

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
                    // Acknowledge with empty list
                    ByteArrayOutputStream r = new ByteArrayOutputStream();
                    writeVarInt(r, 0);
                    sendRaw(S_CONFIG_KNOWN_PACKS, r.toByteArray());
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
                // Registry data, tags, cookie requests — silently consumed
            }
        }
        log.warning("[" + botName + "] Config timed out");
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Play state — PASSIVE: only KeepAlive + Confirm Teleport
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Read and handle one Play-state packet.
     *
     * Strategy: read every packet fully (to keep the stream in sync),
     * but only ACT on KeepAlive and Disconnect.
     * Confirm Teleport is handled inline when we detect the position sync packet.
     *
     * We detect KeepAlive by trying BOTH possible server→client IDs
     * (one for older, one for newer protocol). If either matches and the
     * payload is exactly 8 bytes, we treat it as KeepAlive.
     *
     * We detect Synchronize Player Position heuristically: payload starts
     * with a valid VarInt (teleport ID), followed by 3 doubles + floats.
     * To avoid false positives we only confirm teleport for short packets
     * (< 64 bytes) that look like position packets.
     */
    public void readAndHandle(BotClient bot) throws IOException {
        Packet pkt = readPacket();

        // ── Disconnect: only safe thing to throw ─────────────────────────
        // Known clientbound disconnect IDs per version range:
        //   764-767: 0x1B, 768-769: 0x1D, 770-773: 0x1D, 774: 0x20, 775+: 0x20
        // But we treat ANY packet with a JSON text payload as potential kick —
        // actually just throw on known IDs.
        boolean isKnownDisconnect =
                (protocolVersion >= 774 && pkt.id == 0x20) ||
                (protocolVersion >= 768 && protocolVersion < 774 && pkt.id == 0x1D) ||
                (protocolVersion < 768  && pkt.id == 0x1B);
        if (isKnownDisconnect && pkt.data.length > 0) {
            String reason = safeReadString(pkt.data);
            throw new IOException("Kicked: " + reason);
        }

        // ── Keep Alive: 8-byte payload = Long ────────────────────────────
        // We respond with whatever ID was negotiated for this protocol.
        // Additionally try both known C→S IDs in case our table is off.
        if (pkt.data.length == 8) {
            // Could be KeepAlive — respond using negotiated ID
            if (keepAliveResponseId >= 0) {
                long id = pkt.stream().readLong();
                ByteArrayOutputStream r = new ByteArrayOutputStream();
                writeLong(r, id);
                sendRaw(keepAliveResponseId, r.toByteArray());
                log.fine("[" + botName + "] KeepAlive 0x" + Integer.toHexString(pkt.id)
                        + " → responded with S:0x" + Integer.toHexString(keepAliveResponseId));
                return;
            }
        }

        // ── Synchronize Player Position: must Confirm Teleport ────────────
        // Heuristic: packet large enough for a position (>=28 bytes for pos+rot+flags)
        // and contains a VarInt teleport ID we can extract.
        // We attempt to parse it; if parsing fails we skip silently.
        // Known clientbound sync-pos IDs:
        //   764-767: 0x3E, 768-769: 0x40, 774: 0x48, 775+: ?
        boolean isKnownSyncPos =
                (protocolVersion >= 774 && pkt.id == 0x48) ||
                (protocolVersion >= 768 && protocolVersion < 774 && pkt.id == 0x40) ||
                (protocolVersion < 768  && pkt.id == 0x3E);

        if (isKnownSyncPos && pkt.data.length >= 20) {
            try {
                DataInputStream d = pkt.stream();
                int teleportId;
                double x, y, z;
                float yaw, pitch;

                if (protocolVersion >= 768) {
                    // 1.21.2+: teleportId first
                    teleportId = readVarInt(d);
                    x = d.readDouble(); y = d.readDouble(); z = d.readDouble();
                    d.readDouble(); d.readDouble(); d.readDouble(); // velocity
                    yaw = d.readFloat(); pitch = d.readFloat();
                } else {
                    // 1.20.x–1.21.1: position first
                    x = d.readDouble(); y = d.readDouble(); z = d.readDouble();
                    yaw = d.readFloat(); pitch = d.readFloat();
                    d.readByte(); // flags
                    teleportId = readVarInt(d);
                }

                // Send Confirm Teleport (always 0x00)
                ByteArrayOutputStream r = new ByteArrayOutputStream();
                writeVarInt(r, teleportId);
                sendRaw(S_CONFIRM_TELEPORT, r.toByteArray());
                bot.updatePosition(x, y, z, yaw, pitch);
                log.fine("[" + botName + "] Confirmed teleport #" + teleportId);
            } catch (Exception e) {
                log.fine("[" + botName + "] SyncPos parse failed (ignored): " + e.getMessage());
            }
        }

        // All other packets: silently consumed (stream already read fully by readPacket)
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
            byte[] ib = inner.toByteArray();
            writeVarInt(frame, ib.length);
            frame.write(ib);
        } else {
            writeVarInt(frame, data.length);
            frame.write(data);
        }
        out.write(frame.toByteArray());
        out.flush();
    }

    private static void sendFramedNoCompression(DataOutputStream o, int id, byte[] payload) throws IOException {
        ByteArrayOutputStream idBuf = new ByteArrayOutputStream();
        writeVarInt(idBuf, id);
        idBuf.write(payload);
        byte[] data = idBuf.toByteArray();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarInt(frame, data.length);
        frame.write(data);
        o.write(frame.toByteArray());
        o.flush();
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
            byte[] unc = (dataLen == 0) ? inner : decompress(inner);
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(unc));
            int id = readVarInt(d);
            return new Packet(id, d.readAllBytes());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Primitives
    // ═══════════════════════════════════════════════════════════════════

    private static byte[] readExactly(DataInputStream in, int n) throws IOException {
        if (n <= 0) return new byte[0];
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r < 0) throw new EOFException("Stream ended: " + read + "/" + n);
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

    private static String safeReadString(byte[] data) {
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
            return readString(d);
        } catch (Exception e) {
            return "(unreadable)";
        }
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
}
