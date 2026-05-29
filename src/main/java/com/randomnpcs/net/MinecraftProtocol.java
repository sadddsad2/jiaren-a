package com.randomnpcs.net;

import com.randomnpcs.RandomBotsPlugin;
import com.randomnpcs.bot.BotClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 具有自适应 Play 状态 Packet ID 自动探测并共享的 Minecraft 极简协议层
 */
public class MinecraftProtocol {

    private final RandomBotsPlugin plugin;
    private final BotClient        bot;
    private final InputStream      in;
    private final OutputStream     out;

    // ──────────────────────────────────────────────────────────────────────═
    // 全局静态缓存：[服务器地址 -> int[]{客户端收到的KeepAliveID, 回应的KeepAliveID, 坐标同步ID}]
    private static final Map<String, int[]> SERVER_ID_CACHE = new ConcurrentHashMap<>();
    
    // 全局探测步长记录器，用于在猜测被踢时进行递增累加
    private static final Map<String, Integer> PROBE_CANDIDATE_CACHE = new ConcurrentHashMap<>();
    // ──────────────────────────────────────────────────────────────────────═

    private int cbKeepAliveId;
    private int sbKeepAliveId;
    private int syncPosId;
    private boolean idsResolved = false;

    private int protocolVersion = 767; // 默认 1.20.5+，可在配置加载
    private int compressionThreshold = -1;
    private int state = 1; // 1:Handshake, 2:Status, 3:Login, 4:Transfer, 5:Config, 6:Play

    private String cacheKey;
    private int currentProbeCandidate = 0x00;
    private boolean loginVerifiedTriggered = false;

    public MinecraftProtocol(RandomBotsPlugin plugin, BotClient bot, InputStream in, OutputStream out) {
        this.plugin = plugin;
        this.bot    = bot;
        this.in     = in;
        this.out    = out;
    }

    /**
     * 执行 Handshake -> Login -> Configuration 直至完成
     */
    public void loginAndConfig(String host, int port, String name, UUID uuid) throws Exception {
        this.cacheKey = host + ":" + port;
        this.protocolVersion = plugin.getConfig().getInt("protocol-version", 774); // 774 代表 1.21.4

        // 1. 初始化检查全局缓存：如果之前已经探测成功过正确的记录值，直接取出使用！
        int[] cached = SERVER_ID_CACHE.get(cacheKey);
        if (cached != null) {
            this.cbKeepAliveId = cached[0];
            this.sbKeepAliveId = cached[1];
            this.syncPosId     = cached[2];
            this.idsResolved   = true;
        } else {
            // 如果尚未探测出正确值，获取当前这台服务器探测到了哪个步长候选值
            this.currentProbeCandidate = PROBE_CANDIDATE_CACHE.computeIfAbsent(cacheKey, k -> 0x00);
        }

        // 2. Handshake
        ByteArrayOutputStream hba = new ByteArrayOutputStream();
        writeVarInt(hba, 0x00); // Packet ID
        writeVarInt(hba, protocolVersion);
        writeString(hba, host);
        writeShort(hba, port);
        writeVarInt(hba, 3); // Next State = Login
        writePacket(hba.toByteArray());
        this.state = 3;

        // 3. Login Start
        ByteArrayOutputStream lba = new ByteArrayOutputStream();
        writeVarInt(lba, 0x00); // Login Start ID
        writeString(lba, name);
        writeLong(lba, uuid.getMostSignificantBits());
        writeLong(lba, uuid.getLeastSignificantBits());
        writePacket(lba.toByteArray());

        // 4. 处理 Login & Config 数据包循环
        while (this.state != 6) { // 循环直到切入 Play 状态
            DataPacket p = readPacket();
            if (p == null) throw new IOException("在握手或配置阶段遭遇流断开");

            if (this.state == 3) {
                handleLoginState(p);
            } else if (this.state == 5) {
                handleConfigState(p);
            }
        }
    }

    private void handleLoginState(DataPacket p) throws Exception {
        int id = p.id;
        if (id == 0x03) { // Set Compression
            this.compressionThreshold = readVarInt(p.payload);
        } else if (id == 0x02) { // Login Success
            this.state = 5; // 进入 Configuration 阶段 (1.20.2+)
        } else if (id == 0x00) { // Login Disconnect
            String reason = readString(p.payload);
            throw new IOException("被服务器拒绝登录: " + reason);
        }
    }

    private void handleConfigState(DataPacket p) throws Exception {
        int id = p.id;
        if (id == 0x00) { // Cookie Request / KeepAlive
            // 响应配置阶段的应答，保持通道畅通
            ByteArrayOutputStream ba = new ByteArrayOutputStream();
            writeVarInt(ba, 0x00);
            byte[] rem = readRemaining(p.payload);
            ba.write(rem);
            writePacket(ba.toByteArray());
        } else if (id == 0x03) { // Registry Data 或 Finish Configuration
            // 自动对部分常见包做盲答或等待 Finish Config
        } else if (id == 0x02) { // Finish Configuration (服务器发送)
            // 回应 Finish Configuration 到服务器
            ByteArrayOutputStream ba = new ByteArrayOutputStream();
            writeVarInt(ba, 0x02);
            writePacket(ba.toByteArray());
            
            // 彻底切入 Play 状态
            this.state = 6; 
        }
    }

    /**
     * 运行于 BotReader 线程中的主 Play 状态包监听循环
     */
    public void readLoop() throws Exception {
        while (bot.isConnected()) {
            DataPacket p = readPacket();
            if (p == null) break;

            if (idsResolved) {
                // ─── 核心情况 A：已经存在正确的记录值，直接使用 ───
                if (p.id == cbKeepAliveId) {
                    sendKeepAliveResponse(p.payload, sbKeepAliveId);
                } else if (p.id == syncPosId) {
                    // 接收到坐标包说明成功下落进世界
                    triggerLoginVerifiedOnce();
                }
            } else {
                // ─── 核心情况 B：没有缓存，进行全自动 ID 嗅探与自适应测试 ───
                // 1. 如果长度是 8 字节（Long），它是服务器的 KeepAlive 探测包
                if (p.payload.available() == 8) {
                    this.cbKeepAliveId = p.id;
                    // 使用当前独占的候选值，向服务器回应并测试是否正确
                    this.sbKeepAliveId = currentProbeCandidate;
                    
                    plugin.getLogger().info("[探针] 捕获客户端 KeepAlive 包 ID: 0x" + Integer.toHexString(cbKeepAliveId) 
                            + "，正在使用候选 Serverbound ID: 0x" + Integer.toHexString(sbKeepAliveId) + " 进行回放测试...");
                    
                    sendKeepAliveResponse(p.payload, this.sbKeepAliveId);
                } 
                // 2. 捕获同步包：坐标同步包通常包含 3 个 Double 加上标志，长度通常在 30 ~ 40 字节之间
                else if (p.payload.available() >= 30 && p.payload.available() <= 50) {
                    this.syncPosId = p.id;
                    plugin.getLogger().info("[探针] 捕获世界坐标同步包 ID: 0x" + Integer.toHexString(syncPosId));
                    
                    // 只要能稳定收到坐标同步且未被服务器因 KeepAlive 猜错而断开，基本说明当前猜测的 ID 组合完美正确！
                    // 将正确值记录并锁死进静态全局缓存，后续所有假人直接享用成果！
                    SERVER_ID_CACHE.put(cacheKey, new int[]{cbKeepAliveId, sbKeepAliveId, syncPosId});
                    this.idsResolved = true;
                    
                    triggerLoginVerifiedOnce();
                }
            }
        }

        // 退出循环，说明断开了。如果是探测阶段挂掉，判定是 ID 猜错了
        if (!idsResolved) {
            // 累加步长，为下一个插队重连的假人铺路
            currentProbeCandidate++;
            PROBE_CANDIDATE_CACHE.put(cacheKey, currentProbeCandidate);
            // 标记 BotClient 进行无缝插队重连
            bot.setProbeFailedReconnect(true);
        }
    }

    private void sendKeepAliveResponse(DataInputStream payload, int responseId) throws Exception {
        long challenge = payload.readLong();
        ByteArrayOutputStream ba = new ByteArrayOutputStream();
        writeVarInt(ba, responseId);
        writeLong(ba, challenge);
        writePacket(ba.toByteArray());
    }

    private void triggerLoginVerifiedOnce() {
        if (!loginVerifiedTriggered) {
            loginVerifiedTriggered = true;
            bot.onProtocolLoginVerified();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 基础底层 Packet 读写封装（含 VarInt 与可选的 Zlib 解压机制）
    // ═══════════════════════════════════════════════════════════════════════

    private static class DataPacket {
        int id;
        DataInputStream payload;
        DataPacket(int id, byte[] data) {
            this.id = id;
            this.payload = new DataInputStream(new ByteArrayInputStream(data));
        }
    }

    private DataPacket readPacket() throws Exception {
        int length = readVarInt(in);
        if (length <= 0) return null;

        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int c = in.read(data, read, length - read);
            if (c == -1) return null;
            read += c;
        }

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        if (compressionThreshold >= 0) {
            int dataLength = readVarInt(dis);
            if (dataLength != 0) {
                byte[] compressed = readRemaining(dis);
                byte[] uncompressed = decompress(compressed);
                dis = new DataInputStream(new ByteArrayInputStream(uncompressed));
            }
        }

        int packetId = readVarInt(dis);
        byte[] payload = readRemaining(dis);
        return new DataPacket(packetId, payload);
    }

    private void writePacket(byte[] packetData) throws Exception {
        if (compressionThreshold >= 0) {
            if (packetData.length >= compressionThreshold) {
                ByteArrayOutputStream dataLengthBuf = new ByteArrayOutputStream();
                writeVarInt(dataLengthBuf, packetData.length);
                byte[] compressed = compress(packetData);
                
                ByteArrayOutputStream packetBuf = new ByteArrayOutputStream();
                writeVarInt(packetBuf, dataLengthBuf.size() + compressed.length);
                packetBuf.write(dataLengthBuf.toByteArray());
                packetBuf.write(compressed);
                out.write(packetBuf.toByteArray());
                out.flush();
                return;
            } else {
                ByteArrayOutputStream packetBuf = new ByteArrayOutputStream();
                ByteArrayOutputStream totalBuf = new ByteArrayOutputStream();
                writeVarInt(totalBuf, 0); // Uncompressed length = 0
                totalBuf.write(packetData);
                
                writeVarInt(packetBuf, totalBuf.size());
                packetBuf.write(totalBuf.toByteArray());
                out.write(packetBuf.toByteArray());
                out.flush();
                return;
            }
        }

        ByteArrayOutputStream packetBuf = new ByteArrayOutputStream();
        writeVarInt(packetBuf, packetData.length);
        packetBuf.write(packetData);
        out.write(packetBuf.toByteArray());
        out.flush();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 数据流辅助读写原生方法
    // ═══════════════════════════════════════════════════════════════════════

    public static int readVarInt(InputStream in) throws IOException {
        int value = 0, position = 0;
        while (true) {
            int b = in.read();
            if (b == -1) throw new EOFException();
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new IOException("VarInt 太长了");
        }
        return value;
    }

    public static void writeVarInt(OutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private String readString(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private void writeString(OutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    private void writeLong(OutputStream out, long v) throws IOException {
        for (int i = 7; i >= 0; i--) out.write((int)((v >> (i * 8)) & 0xFF));
    }

    private void writeShort(OutputStream out, int v) throws IOException {
        out.write((v >> 8) & 0xFF); out.write(v & 0xFF);
    }

    private byte[] readRemaining(DataInputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int read;
        while ((read = in.read(buf)) != -1) bos.write(buf, 0, read);
        return bos.toByteArray();
    }

    private static byte[] compress(byte[] data) throws IOException {
        java.util.zip.Deflater df = new java.util.zip.Deflater();
        df.setInput(data); df.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!df.finished()) out.write(buf, 0, df.deflate(buf));
        df.end();
        return out.toByteArray();
    }

    private static byte[] decompress(byte[] data) throws IOException {
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        inf.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        try { while (!inf.finished()) out.write(buf, 0, inf.inflate(buf)); }
        catch (java.util.zip.DataFormatException e) { throw new IOException("解压 Zlib 失败", e); }
        inf.end();
        return out.toByteArray();
    }
}
