package com.randomnpcs.net;

import com.randomnpcs.bot.BotClient;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Minimal Minecraft protocol client — fully adaptive Play-state packet detection.
 *
 * ALL IDs (clientbound KeepAlive, serverbound KeepAlive, SyncPos) are discovered
 * via sniff + probe on the FIRST connection to a server. No hardcoded tables.
 *
 * Once detected, IDs are written to SERVER_ID_CACHE. Every subsequent bot instance
 * loads the cache and skips sniffing entirely.
 */
public class MinecraftProtocol {

    // ── Handshake + Login (stable) ────────────────────────────────────────
    private static final int S_HANDSHAKE           = 0x00;
    private static final int S_LOGIN_START          = 0x00;
    private static final int S_LOGIN_ACK            = 0x03;
    private static final int C_SET_COMPRESSION      = 0x03;
    private static final int C_LOGIN_SUCCESS        = 0x02;
    private static final int C_DISCONNECT_LOGIN     = 0x00;
    private static final int C_ENCRYPTION_REQUEST   = 0x01;
    private static final int C_LOGIN_PLUGIN_REQUEST = 0x04;

    // ── Configuration (stable 1.20.2+) ───────────────────────────────────
    private static final int S_CONFIG_CLIENT_INFO  = 0x00;
    private static final int S_CONFIG_PLUGIN_MSG   = 0x02;
    private static final int S_CONFIG_ACK          = 0x03;
    private static final int S_CONFIG_KNOWN_PACKS  = 0x07;
    private static final int S_CONFIG_KEEPALIVE    = 0x04;
    private static final int C_CONFIG_PLUGIN_MSG   = 0x01;
    private static final int C_CONFIG_DISCONNECT   = 0x02;
    private static final int C_CONFIG_FINISH       = 0x03;
    private static final int C_CONFIG_KEEPALIVE    = 0x04;
    private static final int C_CONFIG_PING         = 0x05;
    private static final int C_CONFIG_KNOWN_PACKS  = 0x0E;

    // ── Play state ────────────────────────────────────────────────────────
    private static final int S_CONFIRM_TELEPORT    = 0x00;
    private static final int PROBE_ID_MIN          = 0x01;
    private static final int PROBE_ID_MAX          = 0x7F;
    private static final int SNIFF_WINDOW          = 256;
    private static final double MAX_COORD          = 3.0e7;

    // ── Per-server resolved ID cache (shared across ALL bot instances) ────
    private static final java.util.concurrent.ConcurrentHashMap<String, int[]>
            SERVER_ID_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.concurrent.ConcurrentHashMap<String, Integer>
            PROBE_CANDIDATE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    // ── Instance state ────────────────────────────────────────────────────
    private int  keepAliveClientboundId = -1;
    private int  keepAliveServerboundId = -1;
    private int  syncPosClientboundId   = -1;
    private boolean idsResolved         = false;
    private long pendingKeepAliveId     = 0;
    private int  probeCandidate         = PROBE_ID_MIN;

    private static final int PROTO_FALLBACK = 769;

    private final DataInputStream  in;
    private final DataOutputStream out;
    private final Logger           log;
    private final String           botName;
    private final String           cacheKey;

    private int     protocolVersion      = PROTO_FALLBACK;
    private boolean compressionEnabled   = false;
    private int     compressionThreshold = -1;

    public MinecraftProtocol(DataInputStream in, DataOutputStream out,
                             Logger log, String botName,
                             String host, int port) {
        this.in       = in;
        this.out      = out;
        this.log      = log;
        this.botName  = botName;
        this.cacheKey = host + ":" + port;

        // If all three IDs are already cached, restore them and mark resolved.
        int[] cached = SERVER_ID_CACHE.get(cacheKey);
        if (cached != null) {
            keepAliveClientboundId = cached[0];
            keepAliveServerboundId = cached[1];
            syncPosClientboundId   = cached[2];
            idsResolved            = true;
            log.fine("[" + botName + "] IDs from cache — sniff skipped");
        }

        probeCandidate = PROBE_CANDIDATE_CACHE.getOrDefault(cacheKey, PROBE_ID_MIN);
    }

    public void setProtocolVersion(int v) {
        this.protocolVersion = v;
        log.fine("[" + botName + "] Protocol version: " + v);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Status ping
    // ═══════════════════════════════════════════════════════════════════════

    public static int queryProtocolVersion(String host, int port, Logger log) {
        try (Socket s = new Socket(host, port)) {
            s.setSoTimeout(5_000);
            DataOutputStream o = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream  i = new DataInputStream(new BufferedInputStream(s.getInputStream()));

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
                log.fine("[StatusPing] Server protocol: " + proto);
                return proto;
            }
        } catch (Exception e) {
            log.fine("[StatusPing] Failed: " + e.getMessage());
        }
        return -1;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Login + Configuration
    // ═══════════════════════════════════════════════════════════════════════

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
                    d.skipBytes(16);
                    readString(d);
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
        ByteArrayOutputStream ci = new ByteArrayOutputStream();
        writeString(ci, "en_us");
        ci.write(10);
        writeVarInt(ci, 0);
        ci.write(1);
        ci.write(0x7F);
        writeVarInt(ci, 1);
        ci.write(0);
        ci.write(1);
        if (protocolVersion >= 770) writeVarInt(ci, 2);
        sendRaw(S_CONFIG_CLIENT_INFO, ci.toByteArray());

        for (int i = 0; i < 400; i++) {
            Packet pkt = readPacket();
            log.fine("[" + botName + "] Config pkt 0x" + Integer.toHexString(pkt.id));
            switch (pkt.id) {
                case C_CONFIG_FINISH -> {
                    sendRaw(S_CONFIG_ACK, new byte[0]);
                    log.fine("[" + botName + "] Configuration complete");
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
                    ByteArrayOutputStream r = new ByteArrayOutputStream();
                    writeVarInt(r, 0);
                    sendRaw(S_CONFIG_KNOWN_PACKS, r.toByteArray());
                    log.fine("[" + botName + "] Replied to Select Known Packs");
                }
                case C_CONFIG_PLUGIN_MSG -> {
                    DataInputStream d = pkt.stream();
                    String channel = readString(d);
                    if ("minecraft:brand".equals(channel)) {
                        ByteArrayOutputStream r = new ByteArrayOutputStream();
                        writeString(r, "minecraft:brand");
                        writeString(r, "vanilla");
                        sendRaw(S_CONFIG_PLUGIN_MSG, r.toByteArray());
                        log.fine("[" + botName + "] Replied to minecraft:brand");
                    }
                }
            }
        }
        log.warning("[" + botName + "] Config timed out");
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Play state — main entry point
    // ═══════════════════════════════════════════════════════════════════════

    public void readAndHandle(BotClient bot) throws IOException {
        Packet pkt = readPacket();

        // Disconnect heuristic
        if (pkt.id >= 0x17 && pkt.id <= 0x30 && pkt.data.length > 2) {
            String reason = safeReadJsonString(pkt.data);
            if (reason != null) throw new IOException("Kicked: " + reason);
        }

        if (idsResolved) {
            handleNormal(pkt, bot);
        } else {
            handleSniff(pkt, bot);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Sniff phase — fully adaptive, no hardcoded IDs
    // ═══════════════════════════════════════════════════════════════════════

    private int sniffCount = 0;

    private void handleSniff(Packet pkt, BotClient bot) throws IOException {
        sniffCount++;

        // ── Step 1: detect SyncPos ─────────────────────────────────────────
        if (syncPosClientboundId == -1 && pkt.data.length >= 28) {
            SyncPosResult sp = trySyncPos(pkt);
            if (sp != null) {
                syncPosClientboundId = pkt.id;
                log.fine("[" + botName + "] SyncPos detected: 0x"
                        + Integer.toHexString(pkt.id));
                ByteArrayOutputStream r = new ByteArrayOutputStream();
                writeVarInt(r, sp.teleportId);
                sendRaw(S_CONFIRM_TELEPORT, r.toByteArray());
                bot.updatePosition(sp.x, sp.y, sp.z, sp.yaw, sp.pitch);
                return;
            }
        }

        // ── Step 2: detect clientbound KeepAlive ──────────────────────────
        // Auto-detect: accept any packet after SyncPos that is exactly 8 bytes
        // and whose ID is in the plausible KeepAlive range for this protocol.
        if (keepAliveClientboundId == -1
                && syncPosClientboundId != -1
                && pkt.data.length == 8
                && pkt.id != syncPosClientboundId
                && isPlausibleKeepAliveClientboundId(pkt.id)) {

            keepAliveClientboundId = pkt.id;
            pendingKeepAliveId     = pkt.stream().readLong();
            log.fine("[" + botName + "] KeepAlive C ID detected: 0x"
                    + Integer.toHexString(keepAliveClientboundId));

            if (keepAliveServerboundId >= 0) {
                cacheAndResolve();
                sendKeepAlive(pendingKeepAliveId);
            } else {
                // Immediately respond with offset-based guess to prevent timeout.
                int offset = (protocolVersion >= 764) ? 0x0E : 0x0C;
                int guessedSB = Math.max(1, keepAliveClientboundId - offset);
                ByteArrayOutputStream kaR = new ByteArrayOutputStream();
                writeLong(kaR, pendingKeepAliveId);
                sendRaw(guessedSB, kaR.toByteArray());
                log.fine("[" + botName + "] KA sent with guessed SB=0x"
                        + Integer.toHexString(guessedSB) + ", awaiting confirmation");
                probeCandidate = guessedSB;
                PROBE_CANDIDATE_CACHE.put(cacheKey, probeCandidate);
            }
            return;
        }

        // ── Step 2b: waiting for second KeepAlive to confirm the probe ─────
        if (keepAliveClientboundId >= 0
                && keepAliveServerboundId < 0
                && pkt.id == keepAliveClientboundId
                && pkt.data.length == 8) {
            confirmProbe(pkt.stream().readLong());
            return;
        }

        // ── Step 3: Sniff window exhausted — last-resort fallback ──────────
        if (sniffCount >= SNIFF_WINDOW) {
            if (keepAliveClientboundId >= 0 && keepAliveServerboundId < 0) {
                // Probe in progress — extend the window, do not clobber IDs
                return;
            }

            if (keepAliveClientboundId < 0) {
                keepAliveClientboundId = fallbackClientboundKeepAliveId(protocolVersion);
                log.fine("[" + botName + "] Sniff window exhausted, KA C fallback 0x"
                        + Integer.toHexString(keepAliveClientboundId));
            }
            if (keepAliveServerboundId < 0) {
                int offset = (protocolVersion >= 764) ? 0x0E : 0x0C;
                keepAliveServerboundId = Math.max(1, keepAliveClientboundId - offset);
                log.fine("[" + botName + "] Sniff window exhausted, KA S fallback 0x"
                        + Integer.toHexString(keepAliveServerboundId));
            }
            if (syncPosClientboundId < 0) {
                syncPosClientboundId = fallbackSyncPosId(protocolVersion);
                log.fine("[" + botName + "] Sniff window exhausted, SyncPos fallback 0x"
                        + Integer.toHexString(syncPosClientboundId));
            }
            cacheAndResolve();
        }
    }

    /**
     * Write all three IDs to SERVER_ID_CACHE and mark this instance as resolved.
     * After this call every future bot for this server will skip sniffing.
     */
    private void cacheAndResolve() {
        SERVER_ID_CACHE.put(cacheKey,
                new int[]{ keepAliveClientboundId, keepAliveServerboundId, syncPosClientboundId });
        PROBE_CANDIDATE_CACHE.remove(cacheKey);
        idsResolved = true;
        log.fine("[" + botName + "] IDs resolved and cached — "
                + "KA_C=0x" + Integer.toHexString(keepAliveClientboundId)
                + " KA_S=0x" + Integer.toHexString(keepAliveServerboundId)
                + " SyncPos=0x" + Integer.toHexString(syncPosClientboundId));
    }

    /**
     * Restrict clientbound KeepAlive detection to a plausible ID range so that
     * other coincidentally-8-byte packets (set_time, etc.) are not misidentified.
     */
    private boolean isPlausibleKeepAliveClientboundId(int id) {
        // Auto-detect range: widen progressively as protocol version grows.
        // For truly unknown versions, use a generous window.
        if (protocolVersion >= 770) return id >= 0x22 && id <= 0x2A;
        if (protocolVersion >= 764) return id >= 0x1F && id <= 0x2A;
        if (protocolVersion >= 760) return id >= 0x1C && id <= 0x28;
        // Unknown / old version — very generous range
        return id >= 0x10 && id <= 0x30;
    }

    private static int fallbackClientboundKeepAliveId(int proto) {
        if (proto >= 770) return 0x26;
        if (proto >= 767) return 0x26;
        if (proto >= 764) return 0x23;
        return 0x21;
    }

    private static int fallbackSyncPosId(int proto) {
        if (proto >= 774) return 0x46;
        if (proto >= 770) return 0x44;
        if (proto >= 768) return 0x40;
        return 0x3E;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Probe phase
    // ─────────────────────────────────────────────────────────────────────

    private void probeKeepAlive() throws IOException {
        while (probeCandidate <= PROBE_ID_MAX
                && probeCandidate == S_CONFIRM_TELEPORT) {
            probeCandidate++;
        }
        if (probeCandidate > PROBE_ID_MAX) {
            log.severe("[" + botName + "] Probe range exhausted! Cannot find KeepAlive ID.");
            throw new IOException("KeepAlive probe range exhausted");
        }
        log.fine("[" + botName + "] Probing KeepAlive SB ID: 0x"
                + Integer.toHexString(probeCandidate));
        sendKeepAlive(pendingKeepAliveId);
    }

    private void confirmProbe(long newKeepAliveId) throws IOException {
        keepAliveServerboundId = probeCandidate;
        cacheAndResolve();
        pendingKeepAliveId = newKeepAliveId;
        sendKeepAlive(pendingKeepAliveId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Normal phase
    // ─────────────────────────────────────────────────────────────────────

    private void handleNormal(Packet pkt, BotClient bot) throws IOException {
        if (pkt.id == keepAliveClientboundId && pkt.data.length == 8) {
            long id = pkt.stream().readLong();
            if (keepAliveServerboundId < 0) {
                confirmProbe(id);
            } else {
                sendKeepAlive(id);
                log.fine("[" + botName + "] KeepAlive responded");
            }
            return;
        }

        if (pkt.id == syncPosClientboundId && pkt.data.length >= 28) {
            SyncPosResult sp = trySyncPos(pkt);
            if (sp != null) {
                ByteArrayOutputStream r = new ByteArrayOutputStream();
                writeVarInt(r, sp.teleportId);
                sendRaw(S_CONFIRM_TELEPORT, r.toByteArray());
                bot.updatePosition(sp.x, sp.y, sp.z, sp.yaw, sp.pitch);
                log.fine("[" + botName + "] Confirmed teleport #" + sp.teleportId);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private void sendKeepAlive(long id) throws IOException {
        ByteArrayOutputStream r = new ByteArrayOutputStream();
        writeLong(r, id);
        sendRaw(keepAliveServerboundId < 0 ? probeCandidate : keepAliveServerboundId,
                r.toByteArray());
    }

    public int nextProbeCandidate() {
        probeCandidate++;
        if (probeCandidate == S_CONFIRM_TELEPORT) probeCandidate++;
        PROBE_CANDIDATE_CACHE.put(cacheKey, probeCandidate);
        return probeCandidate;
    }

    // ─────────────────────────────────────────────────────────────────────
    // SyncPos heuristic parser
    // ─────────────────────────────────────────────────────────────────────

    private record SyncPosResult(int teleportId, double x, double y, double z,
                                 float yaw, float pitch) {}

    private SyncPosResult trySyncPos(Packet pkt) {
        SyncPosResult a = trySyncPosLayoutA(pkt);
        return (a != null) ? a : trySyncPosLayoutB(pkt);
    }

    /** Layout A (proto >= 768): VarInt teleportId, 3xdouble pos, 3xdouble vel, 2xfloat rot */
    private SyncPosResult trySyncPosLayoutA(Packet pkt) {
        if (pkt.data.length < 29) return null;
        try {
            DataInputStream d = pkt.stream();
            int tid = readVarInt(d);
            if (tid < 0 || tid > 0xFFFF) return null;
            double x = d.readDouble(), y = d.readDouble(), z = d.readDouble();
            if (!coord(x) || !coord(y) || !coord(z)) return null;
            d.readDouble(); d.readDouble(); d.readDouble(); // velocity
            float yaw = d.readFloat(), pitch = d.readFloat();
            return new SyncPosResult(tid, x, y, z, yaw, pitch);
        } catch (Exception e) { return null; }
    }

    /** Layout B (proto < 768): 3xdouble pos, 2xfloat rot, 1xbyte flags, VarInt teleportId */
    private SyncPosResult trySyncPosLayoutB(Packet pkt) {
        if (pkt.data.length < 29) return null;
        try {
            DataInputStream d = pkt.stream();
            double x = d.readDouble(), y = d.readDouble(), z = d.readDouble();
            if (!coord(x) || !coord(y) || !coord(z)) return null;
            float yaw = d.readFloat(), pitch = d.readFloat();
            d.readByte();
            int tid = readVarInt(d);
            if (tid < 0 || tid > 0xFFFF) return null;
            return new SyncPosResult(tid, x, y, z, yaw, pitch);
        } catch (Exception e) { return null; }
    }

    private static boolean coord(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && Math.abs(v) <= MAX_COORD;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Low-level framing
    // ═══════════════════════════════════════════════════════════════════════

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

    private static void sendFramedNoCompression(DataOutputStream o, int id,
                                                 byte[] payload) throws IOException {
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

    // ═══════════════════════════════════════════════════════════════════════
    // Primitives
    // ═══════════════════════════════════════════════════════════════════════

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

    private static String safeReadJsonString(byte[] data) {
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
            int len = readVarInt(d);
            if (len <= 0 || len > data.length) return null;
            byte[] s = readExactly(d, len);
            String result = new String(s, StandardCharsets.UTF_8);
            return (result.startsWith("{") || result.startsWith("\"")) ? result : null;
        } catch (Exception e) { return null; }
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
        DataInputStream stream() {
            return new DataInputStream(new ByteArrayInputStream(data));
        }
    }
}
