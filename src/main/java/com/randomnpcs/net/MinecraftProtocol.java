package com.randomnpcs.net;

import com.randomnpcs.RandomBotsPlugin;
import com.randomnpcs.bot.BotClient;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Minimal Minecraft protocol client — fully adaptive Play-state packet detection.
 * v2.4.0: 持久化 keepalive ID 缓存（磁盘），重启无需重新探测。
 */
public class MinecraftProtocol {

    private static final int S_HANDSHAKE           = 0x00;
    private static final int S_LOGIN_START          = 0x00;
    private static final int S_LOGIN_ACK            = 0x03;
    private static final int C_SET_COMPRESSION      = 0x03;
    private static final int C_LOGIN_SUCCESS        = 0x02;
    private static final int C_DISCONNECT_LOGIN     = 0x00;
    private static final int C_ENCRYPTION_REQUEST   = 0x01;
    private static final int C_LOGIN_PLUGIN_REQUEST = 0x04;

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

    private static final int S_CONFIRM_TELEPORT    = 0x00;
    private static final int PROBE_ID_MIN          = 0x01;
    private static final int PROBE_ID_MAX          = 0x7F;
    private static final int SNIFF_WINDOW          = 256;
    private static final double MAX_COORD          = 3.0e7;

    // ── 内存缓存（进程级别）────────────────────────────────────────────────
    private static final java.util.concurrent.ConcurrentHashMap<String, int[]> SERVER_ID_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> PROBE_CANDIDATE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ── 实例字段 ──────────────────────────────────────────────────────────
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
    private boolean loginVerifiedTriggered = false;

    /** 外部注入磁盘缓存（由 RandomBotsPlugin.loadKeepaliveCache 调用） */
    public static void injectCache(String key, int cb, int sb, int sp) {
        SERVER_ID_CACHE.put(key, new int[]{ cb, sb, sp });
        PROBE_CANDIDATE_CACHE.remove(key);
    }

    public MinecraftProtocol(DataInputStream in, DataOutputStream out, Logger log,
                             String botName, String host, int port) {
        this.in       = in;
        this.out      = out;
        this.log      = log;
        this.botName  = botName;
        this.cacheKey = host + ":" + port;

        int[] cached = SERVER_ID_CACHE.get(cacheKey);
        if (cached != null) {
            keepAliveClientboundId = cached[0];
            keepAliveServerboundId = cached[1];
            syncPosClientboundId   = cached[2];
            idsResolved            = true;
            log.info("[" + botName + "] 使用缓存 ID — cb=0x" + Integer.toHexString(cached[0])
                    + " sb=0x" + Integer.toHexString(cached[1]) + "，跳过探测");
        }
        probeCandidate = PROBE_CANDIDATE_CACHE.getOrDefault(cacheKey, PROBE_ID_MIN);
    }

    private void seedKnownIds() {
        if (SERVER_ID_CACHE.containsKey(cacheKey)) return;
        int[] ids = knownIdsForProtocol(protocolVersion);
        if (ids != null) {
            SERVER_ID_CACHE.put(cacheKey, ids);
            keepAliveClientboundId = ids[0];
            keepAliveServerboundId = ids[1];
            syncPosClientboundId   = ids[2];
            idsResolved            = true;
            log.info("[" + botName + "] 已知协议 " + protocolVersion + " 预填 ID");
        }
    }

    private static int[] knownIdsForProtocol(int proto) {
        if (proto == 774) return new int[]{ 0x26, 0x18, 0x46 }; // 1.21.4
        if (proto == 770) return new int[]{ 0x26, 0x18, 0x44 }; // 1.21.2/1.21.3
        if (proto == 769) return new int[]{ 0x26, 0x18, 0x40 }; // 1.21.1
        if (proto == 767) return new int[]{ 0x24, 0x18, 0x40 }; // 1.21
        if (proto == 765) return new int[]{ 0x24, 0x18, 0x3E }; // 1.20.4
        if (proto == 764) return new int[]{ 0x23, 0x18, 0x3C }; // 1.20.2
        if (proto == 763) return new int[]{ 0x23, 0x17, 0x3A }; // 1.20/1.20.1
        return null;
    }

    public void invalidateCache() {
        SERVER_ID_CACHE.remove(cacheKey);
        PROBE_CANDIDATE_CACHE.remove(cacheKey);
        idsResolved            = false;
        keepAliveServerboundId = -1;
        syncPosClientboundId   = -1;
        log.info("[" + botName + "] 缓存已失效，下次重新探测");
    }

    public int getProbeCandidate() { return probeCandidate; }

    public void setProtocolVersion(int v) {
        this.protocolVersion = v;
        if (!idsResolved) seedKnownIds();
    }

    // ── 协议版本探测 ──────────────────────────────────────────────────────

    public static int queryProtocolVersion(String host, int port, Logger log) {
        try (Socket s = new Socket(host, port)) {
            s.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream  in  = new DataInputStream(new BufferedInputStream(s.getInputStream()));

            // Handshake (status)
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            writeVarInt(buf, 0); writeString(buf, host); writeShort(buf, port); writeVarInt(buf, 1);
            sendFramedNoCompression(out, 0x00, buf.toByteArray());
            sendFramedNoCompression(out, 0x00, new byte[0]);

            int len = readVarInt(in); if (len <= 0) return -1;
            readVarInt(in); // packet id
            String json = readString(in);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"protocol\"\\s*:\\s*(\\d+)").matcher(json);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {}
        return -1;
    }

    // ── 登录 ─────────────────────────────────────────────────────────────

    public void initiateLogin(String host, int port, String name, UUID uuid) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarInt(buf, protocolVersion); writeString(buf, host); writeShort(buf, port); writeVarInt(buf, 2);
        sendRaw(S_HANDSHAKE, buf.toByteArray());

        buf.reset(); writeString(buf, name);
        writeLong(buf, uuid.getMostSignificantBits()); writeLong(buf, uuid.getLeastSignificantBits());
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
                    DataInputStream d = pkt.stream(); d.skipBytes(16); readString(d);
                    int props = readVarInt(d);
                    for (int p = 0; p < props; p++) {
                        readString(d); readString(d); if (d.readBoolean()) readString(d);
                    }
                    sendRaw(S_LOGIN_ACK, new byte[0]);
                    return completeConfiguration();
                }
                case C_DISCONNECT_LOGIN   -> { return false; }
                case C_ENCRYPTION_REQUEST, C_LOGIN_PLUGIN_REQUEST -> { return false; }
            }
        }
        return false;
    }

    private boolean completeConfiguration() throws IOException {
        ByteArrayOutputStream ci = new ByteArrayOutputStream();
        writeString(ci, "en_us"); ci.write(10); writeVarInt(ci, 0); ci.write(1); ci.write(0x7F);
        writeVarInt(ci, 1); ci.write(0); ci.write(1);
        if (protocolVersion >= 770) writeVarInt(ci, 2);
        sendRaw(S_CONFIG_CLIENT_INFO, ci.toByteArray());

        for (int i = 0; i < 400; i++) {
            Packet pkt = readPacket();
            switch (pkt.id) {
                case C_CONFIG_FINISH -> {
                    sendRaw(S_CONFIG_ACK, new byte[0]);
                    return true;
                }
                case C_CONFIG_KEEPALIVE -> {
                    long id = pkt.stream().readLong();
                    ByteArrayOutputStream r = new ByteArrayOutputStream(); writeLong(r, id);
                    sendRaw(S_CONFIG_KEEPALIVE, r.toByteArray());
                }
                case C_CONFIG_PING -> {
                    int pingId = pkt.stream().readInt();
                    ByteArrayOutputStream r = new ByteArrayOutputStream();
                    r.write((pingId >> 24) & 0xFF); r.write((pingId >> 16) & 0xFF);
                    r.write((pingId >>  8) & 0xFF); r.write(pingId & 0xFF);
                    sendRaw(0x04, r.toByteArray());
                }
                case C_CONFIG_DISCONNECT -> { return false; }
                case C_CONFIG_KNOWN_PACKS -> {
                    ByteArrayOutputStream r = new ByteArrayOutputStream(); writeVarInt(r, 0);
                    sendRaw(S_CONFIG_KNOWN_PACKS, r.toByteArray());
                }
                case C_CONFIG_PLUGIN_MSG -> {
                    DataInputStream d = pkt.stream();
                    if ("minecraft:brand".equals(readString(d))) {
                        ByteArrayOutputStream r = new ByteArrayOutputStream();
                        writeString(r, "minecraft:brand"); writeString(r, "vanilla");
                        sendRaw(S_CONFIG_PLUGIN_MSG, r.toByteArray());
                    }
                }
            }
        }
        return false;
    }

    // ── Play 状态读包 ─────────────────────────────────────────────────────

    public void readAndHandle(BotClient bot) throws IOException {
        Packet pkt = readPacket();

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

    private int sniffCount = 0;

    private void handleSniff(Packet pkt, BotClient bot) throws IOException {
        sniffCount++;

        // Step 1: SyncPos 探测
        if (syncPosClientboundId == -1 && pkt.data.length >= 28) {
            SyncPosResult sp = trySyncPos(pkt);
            if (sp != null) {
                syncPosClientboundId = pkt.id;
                ByteArrayOutputStream r = new ByteArrayOutputStream();
                writeVarInt(r, sp.teleportId);
                sendRaw(S_CONFIRM_TELEPORT, r.toByteArray());
                bot.updatePosition(sp.x, sp.y, sp.z, sp.yaw, sp.pitch);
                if (idsResolved) triggerLoginVerifiedOnce(bot);
                return;
            }
        }

        // Step 2: KeepAlive clientbound 探测
        if (keepAliveClientboundId == -1
                && syncPosClientboundId != -1
                && pkt.data.length == 8
                && pkt.id != syncPosClientboundId
                && isPlausibleKeepAliveClientboundId(pkt.id)) {

            keepAliveClientboundId = pkt.id;
            pendingKeepAliveId     = pkt.stream().readLong();
            log.info("[" + botName + "] 检测到 KeepAlive CB id=0x" + Integer.toHexString(pkt.id)
                    + "，开始探测 SB...");

            if (keepAliveServerboundId >= 0) {
                cacheAndResolve();
                sendKeepAlive(pendingKeepAliveId);
                triggerLoginVerifiedOnce(bot);
            } else {
                int offset = (protocolVersion >= 764) ? 0x0E : 0x0C;
                int guessedSB = Math.max(1, keepAliveClientboundId - offset);
                ByteArrayOutputStream kaR = new ByteArrayOutputStream();
                writeLong(kaR, pendingKeepAliveId);
                sendRaw(guessedSB, kaR.toByteArray());
                probeCandidate = guessedSB;
                PROBE_CANDIDATE_CACHE.put(cacheKey, probeCandidate);
                log.info("[" + botName + "] 尝试 SB id=0x" + Integer.toHexString(guessedSB) + " 等待服务器确认...");
            }
            return;
        }

        // Step 2b: 第二个 KeepAlive 到来 → 探测成功
        if (keepAliveClientboundId >= 0
                && keepAliveServerboundId < 0
                && pkt.id == keepAliveClientboundId
                && pkt.data.length == 8) {
            confirmProbe(pkt.stream().readLong(), bot);
            return;
        }

        // Step 3: 超过嗅探窗口 → 强制 fallback
        if (sniffCount >= SNIFF_WINDOW) {
            if (keepAliveClientboundId >= 0 && keepAliveServerboundId < 0) return;
            if (keepAliveClientboundId < 0) keepAliveClientboundId = fallbackClientboundKeepAliveId(protocolVersion);
            if (keepAliveServerboundId < 0) {
                int offset = (protocolVersion >= 764) ? 0x0E : 0x0C;
                keepAliveServerboundId = Math.max(1, keepAliveClientboundId - offset);
            }
            if (syncPosClientboundId < 0) syncPosClientboundId = fallbackSyncPosId(protocolVersion);
            log.warning("[" + botName + "] 探测超时，使用 fallback ID");
            cacheAndResolve();
            triggerLoginVerifiedOnce(bot);
        }
    }

    /** 探测成功：保存到内存缓存 + 触发磁盘持久化 */
    private void cacheAndResolve() {
        SERVER_ID_CACHE.put(cacheKey, new int[]{ keepAliveClientboundId, keepAliveServerboundId, syncPosClientboundId });
        PROBE_CANDIDATE_CACHE.remove(cacheKey);
        idsResolved = true;
        log.info("[" + botName + "] KeepAlive ID 确认 — cb=0x" + Integer.toHexString(keepAliveClientboundId)
                + " sb=0x" + Integer.toHexString(keepAliveServerboundId) + " 写入磁盘缓存");
        // 异步持久化到磁盘
        RandomBotsPlugin plugin = RandomBotsPlugin.getInstance();
        if (plugin != null) {
            plugin.saveKeepaliveCache(cacheKey, keepAliveClientboundId, keepAliveServerboundId,
                    syncPosClientboundId > 0 ? syncPosClientboundId : fallbackSyncPosId(protocolVersion));
        }
    }

    private boolean isPlausibleKeepAliveClientboundId(int id) {
        // 扩大范围覆盖模组服务器
        if (protocolVersion >= 770) return id >= 0x1E && id <= 0x35;
        if (protocolVersion >= 764) return id >= 0x1C && id <= 0x30;
        return id >= 0x18 && id <= 0x2C;
    }

    private static int fallbackClientboundKeepAliveId(int proto) {
        return (proto >= 764) ? 0x23 : 0x21;
    }

    private static int fallbackSyncPosId(int proto) {
        if (proto >= 774) return 0x46;
        if (proto >= 770) return 0x44;
        return 0x3E;
    }

    private void confirmProbe(long newKeepAliveId, BotClient bot) throws IOException {
        keepAliveServerboundId = probeCandidate;
        log.info("[" + botName + "] 探测成功！SB id=0x" + Integer.toHexString(probeCandidate));
        cacheAndResolve();
        pendingKeepAliveId = newKeepAliveId;
        sendKeepAlive(pendingKeepAliveId);
        triggerLoginVerifiedOnce(bot);
    }

    private void handleNormal(Packet pkt, BotClient bot) throws IOException {
        if (pkt.id == keepAliveClientboundId && pkt.data.length == 8) {
            long id = pkt.stream().readLong();
            if (keepAliveServerboundId < 0) {
                confirmProbe(id, bot);
            } else {
                sendKeepAlive(id);
                triggerLoginVerifiedOnce(bot);
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
                triggerLoginVerifiedOnce(bot);
            }
        }
    }

    private void sendKeepAlive(long id) throws IOException {
        ByteArrayOutputStream r = new ByteArrayOutputStream(); writeLong(r, id);
        sendRaw(keepAliveServerboundId < 0 ? probeCandidate : keepAliveServerboundId, r.toByteArray());
    }

    private void triggerLoginVerifiedOnce(BotClient bot) {
        if (!loginVerifiedTriggered) {
            loginVerifiedTriggered = true;
            bot.onProtocolLoginVerified();
        }
    }

    public int nextProbeCandidate() {
        probeCandidate++;
        if (probeCandidate == S_CONFIRM_TELEPORT) probeCandidate++;
        if (probeCandidate > PROBE_ID_MAX) probeCandidate = PROBE_ID_MIN + 1;
        PROBE_CANDIDATE_CACHE.put(cacheKey, probeCandidate);
        log.info("[" + botName + "] 探测失败，换下一个候选 SB id=0x" + Integer.toHexString(probeCandidate));
        return probeCandidate;
    }

    // ── SyncPos 解析 ──────────────────────────────────────────────────────

    private record SyncPosResult(int teleportId, double x, double y, double z, float yaw, float pitch) {}

    private SyncPosResult trySyncPos(Packet pkt) {
        // 优先尝试 1.21.1 格式（Layout C），再fallback旧格式
        SyncPosResult c = trySyncPosLayoutC(pkt);
        if (c != null) return c;
        SyncPosResult a = trySyncPosLayoutA(pkt);
        return (a != null) ? a : trySyncPosLayoutB(pkt);
    }

    /**
     * Layout C — 1.21.1 格式：
     * VarInt teleportId, Double x,y,z, Double velX,velY,velZ, Float yaw,pitch, VarInt flags
     * 总长度 >= 1+24+24+8+4 = 61 bytes
     */
    private SyncPosResult trySyncPosLayoutC(Packet pkt) {
        if (pkt.data.length < 49) return null;
        try {
            DataInputStream d = pkt.stream();
            int tid = readVarInt(d);
            if (tid < 0 || tid > 0xFFFF) return null;
            double x = d.readDouble(), y = d.readDouble(), z = d.readDouble();
            if (!coord(x) || !coord(y) || !coord(z)) return null;
            // 速度字段（1.21新增，各占8字节）
            double vx = d.readDouble(), vy = d.readDouble(), vz = d.readDouble();
            // 速度值范围合理性检查（过滤误匹配）
            if (Math.abs(vx) > 100 || Math.abs(vy) > 100 || Math.abs(vz) > 100) return null;
            float yaw = d.readFloat(), pitch = d.readFloat();
            // flags 是 VarInt，必须能读到，否则说明格式不对
            readVarInt(d);
            return new SyncPosResult(tid, x, y, z, yaw, pitch);
        } catch (Exception e) { return null; }
    }

    private SyncPosResult trySyncPosLayoutA(Packet pkt) {
        if (pkt.data.length < 29) return null;
        try {
            DataInputStream d = pkt.stream(); int tid = readVarInt(d);
            if (tid < 0 || tid > 0xFFFF) return null;
            double x = d.readDouble(), y = d.readDouble(), z = d.readDouble();
            if (!coord(x) || !coord(y) || !coord(z)) return null;
            d.readDouble(); d.readDouble(); d.readDouble();
            float yaw = d.readFloat(), pitch = d.readFloat();
            return new SyncPosResult(tid, x, y, z, yaw, pitch);
        } catch (Exception e) { return null; }
    }

    private SyncPosResult trySyncPosLayoutB(Packet pkt) {
        if (pkt.data.length < 29) return null;
        try {
            DataInputStream d = pkt.stream();
            double x = d.readDouble(), y = d.readDouble(), z = d.readDouble();
            if (!coord(x) || !coord(y) || !coord(z)) return null;
            float yaw = d.readFloat(), pitch = d.readFloat(); d.readByte();
            int tid = readVarInt(d); if (tid < 0 || tid > 0xFFFF) return null;
            return new SyncPosResult(tid, x, y, z, yaw, pitch);
        } catch (Exception e) { return null; }
    }

    private static boolean coord(double v) { return !Double.isNaN(v) && !Double.isInfinite(v) && Math.abs(v) <= MAX_COORD; }

    // ── IO 工具 ──────────────────────────────────────────────────────────

    private synchronized void sendRaw(int packetId, byte[] payload) throws IOException {
        ByteArrayOutputStream idBuf = new ByteArrayOutputStream(); writeVarInt(idBuf, packetId); idBuf.write(payload);
        byte[] data = idBuf.toByteArray(); ByteArrayOutputStream frame = new ByteArrayOutputStream();
        if (compressionEnabled) {
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            if (data.length >= compressionThreshold) {
                writeVarInt(inner, data.length); inner.write(compress(data));
            } else {
                writeVarInt(inner, 0); inner.write(data);
            }
            byte[] ib = inner.toByteArray(); writeVarInt(frame, ib.length); frame.write(ib);
        } else {
            writeVarInt(frame, data.length); frame.write(data);
        }
        out.write(frame.toByteArray()); out.flush();
    }

    private static void sendFramedNoCompression(DataOutputStream o, int id, byte[] payload) throws IOException {
        ByteArrayOutputStream idBuf = new ByteArrayOutputStream(); writeVarInt(idBuf, id); idBuf.write(payload);
        byte[] data = idBuf.toByteArray(); ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarInt(frame, data.length); frame.write(data); o.write(frame.toByteArray()); o.flush();
    }

    private Packet readPacket() throws IOException {
        int pktLen = readVarInt(in);
        if (!compressionEnabled) {
            byte[] raw = readExactly(in, pktLen);
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(raw));
            return new Packet(readVarInt(d), d.readAllBytes());
        } else {
            byte[] outer = readExactly(in, pktLen);
            DataInputStream od = new DataInputStream(new ByteArrayInputStream(outer));
            int dataLen = readVarInt(od); byte[] inner = od.readAllBytes();
            byte[] unc = (dataLen == 0) ? inner : decompress(inner);
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(unc));
            return new Packet(readVarInt(d), d.readAllBytes());
        }
    }

    private static byte[] readExactly(DataInputStream in, int n) throws IOException {
        if (n <= 0) return new byte[0]; byte[] buf = new byte[n]; int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read); if (r < 0) throw new EOFException();
            read += r;
        }
        return buf;
    }

    public static int readVarInt(DataInputStream in) throws IOException {
        int value = 0, shift = 0; byte b;
        do {
            b = in.readByte(); value |= (b & 0x7F) << shift; shift += 7;
            if (shift > 35) throw new IOException("VarInt too large");
        } while ((b & 0x80) != 0);
        return value;
    }

    private static void writeVarInt(OutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) { out.write((v & 0x7F) | 0x80); v >>>= 7; } out.write(v);
    }

    private static String readString(DataInputStream in) throws IOException {
        return new String(readExactly(in, readVarInt(in)), StandardCharsets.UTF_8);
    }

    private static String safeReadJsonString(byte[] data) {
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(data)); int len = readVarInt(d);
            if (len <= 0 || len > data.length) return null;
            String result = new String(readExactly(d, len), StandardCharsets.UTF_8);
            return (result.startsWith("{") || result.startsWith("\"")) ? result : null;
        } catch (Exception e) { return null; }
    }

    private static void writeString(OutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8); writeVarInt(out, b.length); out.write(b);
    }

    private static void writeLong(OutputStream out, long v) throws IOException {
        for (int i = 7; i >= 0; i--) out.write((int)((v >> (i * 8)) & 0xFF));
    }

    private static void writeShort(OutputStream out, int v) throws IOException {
        out.write((v >> 8) & 0xFF); out.write(v & 0xFF);
    }

    private static byte[] compress(byte[] data) throws IOException {
        java.util.zip.Deflater df = new java.util.zip.Deflater(); df.setInput(data); df.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192];
        while (!df.finished()) out.write(buf, 0, df.deflate(buf)); df.end(); return out.toByteArray();
    }

    private static byte[] decompress(byte[] data) throws IOException {
        java.util.zip.Inflater inf = new java.util.zip.Inflater(); inf.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192];
        try { while (!inf.finished()) out.write(buf, 0, inf.inflate(buf)); }
        catch (java.util.zip.DataFormatException e) { throw new IOException("Decompress", e); }
        inf.end(); return out.toByteArray();
    }

    private record Packet(int id, byte[] data) {
        DataInputStream stream() { return new DataInputStream(new ByteArrayInputStream(data)); }
    }
}
