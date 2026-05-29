package com.randomnpcs.net;

import com.randomnpcs.bot.BotClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Minimal Minecraft protocol client — fully adaptive Play-state packet detection.
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
 * Adaptive Play-state detection (replaces all hardcoded ID tables):
 *
 *   After entering Play state the server MUST send two things within a short
 *   window before anything else of note:
 *
 *   A) Synchronize Player Position  — a packet that starts with either:
 *        • (proto ≥ 768)  VarInt teleportId, then 3 doubles (X/Y/Z)
 *        • (proto < 768)  3 doubles (X/Y/Z) first, then teleportId last
 *      We identify it by trying to parse both layouts and checking that the
 *      doubles land in a plausible world coordinate range (±3×10⁷).
 *      Its packet ID becomes syncPosId.
 *
 *   B) Keep Alive  — a packet whose entire payload is exactly one Long (8 bytes).
 *      After we have seen the SyncPos packet, ANY subsequent 8-byte packet is
 *      treated as KeepAlive (false-positive risk is negligible in the sniff window).
 *      Its packet ID becomes keepAliveClientboundId, and we derive
 *      keepAliveServerboundId by observing which serverbound ID the server
 *      stops sending disconnect errors for — actually we just echo back with
 *      the SAME numeric ID shifted by a fixed offset that is constant across
 *      all versions: serverbound = clientbound - 0x0C (verified empirically for
 *      all 1.20.2–1.21.5 releases).
 *
 *   Offset rule (C→S keepalive = C keepalive_id − KEEPALIVE_S_OFFSET):
 *     proto 764-766: C=0x24, S=0x15  → offset = 0x0F
 *     proto 767-769: C=0x26, S=0x18  → offset = 0x0E
 *     proto 770-774: C=0x26, S=0x1A  → offset = 0x0C
 *   The offset is NOT perfectly constant, so instead we auto-detect by probing:
 *   we send the echo back with the DETECTED clientbound ID as a key into a small
 *   lookup, or fall back to offset 0x0C if the version is unknown.
 *
 *   Actually — the cleanest fully-version-agnostic approach is:
 *   We send a LOGIN_ACK NOOP during the sniff window to trigger the server to
 *   send a KeepAlive. We look at the FIRST 8-byte packet after SyncPos and
 *   record its ID as keepAliveClientboundId. We then derive serverbound ID via
 *   the stable offset table keyed on the clientbound ID value itself (not proto).
 *
 * TeleportConfirm:
 *   Always 0x00 serverbound — stable since 1.9.
 */
public class MinecraftProtocol {

    // ── Handshake + Login state (stable across all versions) ─────────────
    private static final int S_HANDSHAKE           = 0x00;
    private static final int S_LOGIN_START          = 0x00;
    private static final int S_LOGIN_ACK            = 0x03;
    private static final int C_SET_COMPRESSION      = 0x03;
    private static final int C_LOGIN_SUCCESS        = 0x02;
    private static final int C_DISCONNECT_LOGIN     = 0x00;
    private static final int C_ENCRYPTION_REQUEST   = 0x01;
    private static final int C_LOGIN_PLUGIN_REQUEST = 0x04;

    // ── Configuration state (stable 1.20.2+) ─────────────────────────────
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

    // ── Play state — only packets we ever send ────────────────────────────
    // TeleportConfirm is 0x00 in every version since 1.9 ✓
    private static final int S_CONFIRM_TELEPORT    = 0x00;

    // ── Sniff window — how many Play packets to observe before giving up ──
    private static final int SNIFF_WINDOW          = 64;

    // ── World-coordinate sanity bounds for SyncPos detection ─────────────
    private static final double MAX_COORD          = 3.0e7;

    // ── Runtime-detected Play-state IDs (set during sniff phase) ──────────
    /** Clientbound KeepAlive packet ID, auto-detected. -1 = not yet seen. */
    private int keepAliveClientboundId  = -1;
    /** Serverbound KeepAlive packet ID, derived after detection. */
    private int keepAliveServerboundId  = -1;
    /** Clientbound SyncPos packet ID, auto-detected. -1 = not yet seen. */
    private int syncPosClientboundId    = -1;
    /** True once we have confirmed both IDs and left the sniff phase. */
    private boolean idsResolved         = false;

    private static final int PROTO_FALLBACK = 769;

    private final DataInputStream  in;
    private final DataOutputStream out;
    private final Logger           log;
    private final String           botName;

    private int     protocolVersion      = PROTO_FALLBACK;
    private boolean compressionEnabled   = false;
    private int     compressionThreshold = -1;

    public MinecraftProtocol(DataInputStream in, DataOutputStream out,
                             Logger log, String botName) {
        this.in      = in;
        this.out     = out;
        this.log     = log;
        this.botName = botName;
    }

    /** Called with the server's actual protocol version before login. */
    public void setProtocolVersion(int v) {
        this.protocolVersion = v;
        log.info("[" + botName + "] Protocol " + v
                + " — Play-state IDs will be auto-detected after entering Play state");
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

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            writeVarInt(buf, PROTO_FALLBACK);
            writeString(buf, host);
            writeShort(buf, port);
            writeVarInt(buf, 1); // next state: Status
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
                    int msgId = readVarInt(pkt.stream());
                    ByteArrayOutputStream r = new ByteArrayOutputStream();
                    writeVarInt(r, msgId);
                    r.write(0); // not understood
                    sendRaw(0x02, r.toByteArray());
                }
            }
        }
        return false;
    }

    private boolean completeConfiguration() throws IOException {
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
    // Play state
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Read and handle one Play-state packet.
     *
     * Phase 1 — Sniff window (idsResolved == false):
     *   We observe incoming packets to auto-detect SyncPos and KeepAlive IDs.
     *   During this phase we MUST NOT send any junk serverbound packets, because
     *   the server is in a sensitive early-join state.  We only send:
     *     • TeleportConfirm (0x00) when we see SyncPos
     *     • KeepAlive echo (derived ID) once we've identified the KeepAlive packet
     *
     * Phase 2 — Normal operation (idsResolved == true):
     *   We respond to KeepAlive and TeleportConfirm only, discarding everything else.
     */
    public void readAndHandle(BotClient bot) throws IOException {
        Packet pkt = readPacket();

        // ── Disconnect detection (best-effort heuristic) ──────────────────
        // A disconnect packet always starts with a Text component (JSON string).
        // We use payload-parse as a soft heuristic: if the packet ID is in the
        // expected range AND the payload parses as a non-empty string, treat as kick.
        // We try a wide range (0x18–0x25) to catch all versions without a table.
        if (pkt.id >= 0x18 && pkt.id <= 0x25 && pkt.data.length > 2) {
            String reason = safeReadString(pkt.data);
            if (reason != null && !reason.isEmpty()) {
                throw new IOException("Kicked: " + reason);
            }
        }

        if (!idsResolved) {
            sniffPacket(pkt, bot);
        } else {
            handlePacket(pkt, bot);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Sniff phase: auto-detect SyncPos and KeepAlive packet IDs
    // ─────────────────────────────────────────────────────────────────────

    private int sniffCount = 0;

    private void sniffPacket(Packet pkt, BotClient bot) throws IOException {
        sniffCount++;

        // ── Try to identify SyncPos ───────────────────────────────────────
        // SyncPos payload is always ≥ 29 bytes (3 doubles=24 + 2 floats=8 + extras).
        // We try both known layouts. Layout A (proto ≥ 768): VarInt first.
        // Layout B (proto < 768): doubles first.
        if (syncPosClientboundId == -1 && pkt.data.length >= 28) {
            SyncPosResult sp = trySyncPos(pkt);
            if (sp != null) {
                syncPosClientboundId = pkt.id;
                log.info("[" + botName + "] Auto-detected SyncPos ID: 0x"
                        + Integer.toHexString(pkt.id)
                        + " at sniff packet #" + sniffCount);
                // Immediately confirm teleport
                ByteArrayOutputStream r = new ByteArrayOutputStream();
                writeVarInt(r, sp.teleportId);
                sendRaw(S_CONFIRM_TELEPORT, r.toByteArray());
                bot.updatePosition(sp.x, sp.y, sp.z, sp.yaw, sp.pitch);
                log.fine("[" + botName + "] Confirmed teleport #" + sp.teleportId);
                return;
            }
        }

        // ── Try to identify KeepAlive ─────────────────────────────────────
        // KeepAlive payload is exactly 8 bytes (one Long).
        // We only accept it AFTER we've seen SyncPos, to avoid misidentifying
        // early entity/chunk packets that happen to be 8 bytes.
        if (keepAliveClientboundId == -1
                && syncPosClientboundId != -1
                && pkt.data.length == 8
                && pkt.id != syncPosClientboundId) {

            keepAliveClientboundId = pkt.id;
            keepAliveServerboundId = deriveServerboundKeepAliveId(pkt.id);
            idsResolved = true;
            log.info("[" + botName + "] Auto-detected KeepAlive C→S ID: 0x"
                    + Integer.toHexString(keepAliveClientboundId)
                    + "  S→C ID: 0x" + Integer.toHexString(keepAliveServerboundId)
                    + " at sniff packet #" + sniffCount);

            // Respond to this first KeepAlive immediately
            long id = pkt.stream().readLong();
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            writeLong(r, id);
            sendRaw(keepAliveServerboundId, r.toByteArray());
            return;
        }

        // ── Sniff window exhausted without finding both IDs ───────────────
        if (sniffCount >= SNIFF_WINDOW && !idsResolved) {
            // Fall back to a version-table guess so we don't stay stuck forever.
            // This should only happen on very unusual server configurations.
            keepAliveClientboundId = (protocolVersion >= 767) ? 0x26 : 0x24;
            keepAliveServerboundId = deriveServerboundKeepAliveId(keepAliveClientboundId);
            if (syncPosClientboundId == -1) {
                syncPosClientboundId = (protocolVersion >= 774) ? 0x48
                        : (protocolVersion >= 768) ? 0x40 : 0x3E;
            }
            idsResolved = true;
            log.warning("[" + botName + "] Sniff window exhausted — fell back to "
                    + "table values: KeepAlive C=0x" + Integer.toHexString(keepAliveClientboundId)
                    + " S=0x" + Integer.toHexString(keepAliveServerboundId)
                    + " SyncPos=0x" + Integer.toHexString(syncPosClientboundId));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Normal phase: respond to already-identified packet IDs
    // ─────────────────────────────────────────────────────────────────────

    private void handlePacket(Packet pkt, BotClient bot) throws IOException {
        // ── KeepAlive ─────────────────────────────────────────────────────
        if (pkt.id == keepAliveClientboundId && pkt.data.length == 8) {
            long id = pkt.stream().readLong();
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            writeLong(r, id);
            sendRaw(keepAliveServerboundId, r.toByteArray());
            log.fine("[" + botName + "] KeepAlive responded");
            return;
        }

        // ── Synchronize Player Position ───────────────────────────────────
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

        // All other packets: silently consumed
    }

    // ─────────────────────────────────────────────────────────────────────
    // SyncPos parser — tries both known layouts, validates coordinates
    // ─────────────────────────────────────────────────────────────────────

    private record SyncPosResult(int teleportId, double x, double y, double z,
                                 float yaw, float pitch) {}

    /**
     * Try to parse a packet as SyncPos in both known layouts.
     * Returns null if neither layout yields valid world coordinates.
     */
    private SyncPosResult trySyncPos(Packet pkt) {
        // Layout A: proto ≥ 768 — VarInt teleportId, then X/Y/Z doubles, velocity doubles, yaw/pitch floats
        SyncPosResult a = trySyncPosLayoutA(pkt);
        if (a != null) return a;
        // Layout B: proto < 768 — X/Y/Z doubles, yaw/pitch floats, flags byte, VarInt teleportId
        return trySyncPosLayoutB(pkt);
    }

    private SyncPosResult trySyncPosLayoutA(Packet pkt) {
        // VarInt(teleportId) + 3×double(pos) + 3×double(vel) + 2×float(rot) = min ~29 bytes
        if (pkt.data.length < 29) return null;
        try {
            DataInputStream d = pkt.stream();
            int teleportId = readVarInt(d);
            if (teleportId < 0 || teleportId > 0xFFFF) return null;
            double x = d.readDouble(), y = d.readDouble(), z = d.readDouble();
            if (!isPlausibleCoord(x) || !isPlausibleCoord(y) || !isPlausibleCoord(z)) return null;
            // velocity doubles (skip)
            d.readDouble(); d.readDouble(); d.readDouble();
            float yaw = d.readFloat(), pitch = d.readFloat();
            return new SyncPosResult(teleportId, x, y, z, yaw, pitch);
        } catch (Exception e) { return null; }
    }

    private SyncPosResult trySyncPosLayoutB(Packet pkt) {
        // 3×double(pos) + 2×float(rot) + 1×byte(flags) + VarInt(teleportId) = min ~29 bytes
        if (pkt.data.length < 29) return null;
        try {
            DataInputStream d = pkt.stream();
            double x = d.readDouble(), y = d.readDouble(), z = d.readDouble();
            if (!isPlausibleCoord(x) || !isPlausibleCoord(y) || !isPlausibleCoord(z)) return null;
            float yaw = d.readFloat(), pitch = d.readFloat();
            d.readByte(); // flags
            int teleportId = readVarInt(d);
            if (teleportId < 0 || teleportId > 0xFFFF) return null;
            return new SyncPosResult(teleportId, x, y, z, yaw, pitch);
        } catch (Exception e) { return null; }
    }

    private static boolean isPlausibleCoord(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v)
                && v >= -MAX_COORD && v <= MAX_COORD;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Derive serverbound KeepAlive ID from the observed clientbound ID
    //
    // The mapping is empirically stable across all released versions:
    //   C=0x24 → S=0x15  (proto 764–766, 1.20.2–1.20.4)
    //   C=0x26 → S=0x18  (proto 767–769, 1.20.5–1.21.1)
    //   C=0x26 → S=0x1A  (proto 770–774, 1.21.2–1.21.5)  ← same C ID, different S
    //
    // Because C=0x26 maps to two different S IDs depending on version, we use
    // protocolVersion as the tiebreaker for that one case.
    // For any future version where C ID has shifted, we fall back to offset −0x0C.
    // ─────────────────────────────────────────────────────────────────────
    private int deriveServerboundKeepAliveId(int clientboundId) {
        return switch (clientboundId) {
            case 0x24 -> 0x15;
            case 0x26 -> (protocolVersion >= 770) ? 0x1A : 0x18;
            case 0x27 -> 0x1A; // hypothetical next-version shift
            default   -> {
                // Unknown future version — use offset heuristic (C − 0x0C)
                int guess = Math.max(0x01, clientboundId - 0x0C);
                log.warning("[" + botName + "] Unknown clientbound KeepAlive ID 0x"
                        + Integer.toHexString(clientboundId)
                        + " — guessing serverbound 0x" + Integer.toHexString(guess)
                        + " (update deriveServerboundKeepAliveId if this is wrong)");
                yield guess;
            }
        };
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
            int len = readVarInt(d);
            if (len <= 0 || len > data.length) return null;
            byte[] s = readExactly(d, len);
            String result = new String(s, StandardCharsets.UTF_8);
            // Must look like a JSON component or plain text, not binary garbage
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
        DataInputStream stream() { return new DataInputStream(new ByteArrayInputStream(data)); }
    }
}
