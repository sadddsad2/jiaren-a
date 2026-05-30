package com.randomnpcs.bot;

import com.randomnpcs.RandomBotsPlugin;
import com.randomnpcs.net.MinecraftProtocol;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单假人客户端 v2.4.0
 * - 协议层自动响应 KeepAlive（防 Timed out）
 * - 探测成功后持久化到磁盘，重启无需重探
 * - 断线/死亡后通知 BotManager 重连，实现永久驻留
 *
 * 【移除】scheduleKeepActiveTask —— 原实现依赖 Bukkit.getPlayerExact()，
 *   对协议层假人无效（Bukkit 找不到对象），真正的 keepalive 已在
 *   MinecraftProtocol.readAndHandle → handleNormal 中通过协议层发送，无需额外心跳。
 */
public class BotClient {

    private final RandomBotsPlugin plugin;
    private final BotManager       manager;
    private final String           name;
    private final String           host;
    private final int              port;
    private final UUID             uuid;
    private final Random           random = new Random();

    private Socket            socket;
    private MinecraftProtocol proto;
    private volatile boolean  connected = false;
    private volatile boolean  alive     = true;

    private volatile boolean probeFailedReconnect = false;
    private final AtomicBoolean loginNotified = new AtomicBoolean(false);

    private BukkitTask chatTask;
    private BukkitTask actionTask;

    private double x, y, z;
    private float  yaw, pitch;

    private static final List<List<String>> TOPIC_POOL   = buildTopicPool();
    private final Deque<Integer> recentTopics = new ArrayDeque<>();
    private static final int RECENT_TOPIC_MEMORY = 4;

    public BotClient(RandomBotsPlugin plugin, BotManager manager,
                     String name, String host, int port) {
        this.plugin  = plugin;
        this.manager = manager;
        this.name    = name;
        this.host    = host;
        this.port    = port;
        this.uuid    = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ── 连接 ─────────────────────────────────────────────────────────────

    public void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(90_000); // 90s 无数据才超时（服务器 keepalive ≤30s）

        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 8192));
        DataInputStream  in  = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 8192));

        java.util.logging.Logger mcLogger = plugin.getConfig().getBoolean("debug-log", false) ? plugin.getLogger() : java.util.logging.Logger.getLogger("RandomBots.silent");
        mcLogger.setLevel(plugin.getConfig().getBoolean("debug-log", false) ? java.util.logging.Level.ALL : java.util.logging.Level.OFF);
        proto = new MinecraftProtocol(in, out, mcLogger, name, host, port);

        // 探测 / 缓存协议版本
        String cacheKey = host + ":" + port;
        int detectedProto = MinecraftProtocol.queryProtocolVersion(host, port, mcLogger);
        if (detectedProto > 0) {
            proto.setProtocolVersion(detectedProto);
            manager.debugLog("[" + name + "] 服务器协议版本: " + detectedProto);
        }

        proto.initiateLogin(host, port, name, uuid);

        if (!proto.completeLogin(name)) {
            socket.close();
            throw new IOException("Login rejected");
        }

        connected = true;
        manager.debugLog("[" + name + "] 已进入 Play 状态，启动读包线程");
        startReaderThread();
    }

    // ── 读包线程 ─────────────────────────────────────────────────────────

    private void startReaderThread() {
        Thread t = new Thread(() -> {
            while (connected && alive && !socket.isClosed()) {
                try {
                    proto.readAndHandle(this);
                } catch (IOException e) {
                    if (!connected || !alive) break;
                    String msg = e.getMessage() != null ? e.getMessage() : "";

                    if (isProbeDecodeError(msg)) {
                        manager.debugLog("[" + name + "] KeepAlive ID 不匹配，换下一个候选值重连...");
                        proto.nextProbeCandidate();
                        probeFailedReconnect = true;
                        break;
                    }

                    if (isIgnorableError(msg)) {
                        manager.debugLog("[" + name + "] 忽略噪音包: " + msg);
                        continue;
                    }

                    manager.debugLog("[" + name + "] 读包线程退出: " + msg);
                    break;
                } catch (Exception e) {
                    manager.debugLog("[" + name + "] 意外异常: " + e.toString());
                }
            }
            handleDisconnect();
        }, "RandomBot-" + name);
        t.setDaemon(true);
        t.start();
    }

    private static boolean isProbeDecodeError(String msg) {
        return msg.contains("Failed to decode packet")
                || msg.contains("Received unknown packet id")
                || msg.contains("was larger than I expected");
    }

    private static boolean isIgnorableError(String msg) {
        return msg.contains("VarInt too large")
                || msg.contains("Decompress")
                || msg.contains("skipped");
    }

    // ── 登录成功回调 ─────────────────────────────────────────────────────

    public void onProtocolLoginVerified() {
        if (!loginNotified.compareAndSet(false, true)) return;
        manager.debugLog("[" + name + "] KeepAlive 握手完成，假人稳定驻留中 ✓");

        // 延迟 3 秒后启动聊天 & 动作任务
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (connected && alive) {
                scheduleChatTask();
                scheduleActionTask();
                // 注意：无需 keepActiveTask，协议层 KeepAlive 响应已在读包线程处理
            }
        }, 60L);

        manager.onBotLoginSuccess(name, this);
    }

    // ── 断线处理 ─────────────────────────────────────────────────────────

    public void handleDisconnect() {
        if (!alive) return;
        alive     = false;
        connected = false;
        cancelTasks();
        closeSocket();

        if (probeFailedReconnect) {
            probeFailedReconnect = false;
            manager.debugLog("[" + name + "] 探测模式重连...");
            manager.onBotProbeReconnect(name);
        } else if (!loginNotified.get()) {
            manager.debugLog("[" + name + "] 登录阶段失败，准备重试...");
            manager.onBotLoginFailed(name);
        } else {
            manager.debugLog("[" + name + "] 断线/超时，准备重连...");
            manager.onBotDied(name);
        }
    }

    public void disconnect(String reason) {
        alive     = false;
        connected = false;
        cancelTasks();
        closeSocket();
        manager.removeBot(name);
    }

    private void cancelTasks() {
        if (chatTask   != null) { chatTask.cancel();   chatTask   = null; }
        if (actionTask != null) { actionTask.cancel(); actionTask = null; }
    }

    private void closeSocket() {
        try { if (socket != null && !socket.isClosed()) socket.close(); }
        catch (Exception ignored) {}
    }

    // ── Chat & Action ─────────────────────────────────────────────────────

    private void scheduleChatTask() {
        int min = plugin.getConfig().getInt("chat-interval-min", 15);
        int max = plugin.getConfig().getInt("chat-interval-max", 45);
        long ticks = (min + random.nextInt(Math.max(1, max - min + 1))) * 20L;
        chatTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!connected || !alive) return;
            sendChatViaApi();
            scheduleChatTask();
        }, ticks);
    }

    private void scheduleActionTask() {
        int min = plugin.getConfig().getInt("action-interval-min", 5);
        int max = plugin.getConfig().getInt("action-interval-max", 15);
        long ticks = (min + random.nextInt(Math.max(1, max - min + 1))) * 20L;
        actionTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!connected || !alive) return;
            performActionViaApi();
            scheduleActionTask();
        }, ticks);
    }

    private void sendChatViaApi() {
        List<String> configMessages = plugin.getConfig().getStringList("chat-messages");
        String msg = !configMessages.isEmpty()
                ? configMessages.get(random.nextInt(configMessages.size()))
                : generateChatMessage();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(name);
            if (p != null && p.isOnline()) p.chat(msg);
        });
    }

    private String generateChatMessage() {
        int topicIdx; int attempts = 0;
        do { topicIdx = random.nextInt(TOPIC_POOL.size()); attempts++; }
        while (recentTopics.contains(topicIdx) && attempts < 20);
        recentTopics.addLast(topicIdx);
        if (recentTopics.size() > RECENT_TOPIC_MEMORY) recentTopics.removeFirst();
        String line = TOPIC_POOL.get(topicIdx).get(random.nextInt(TOPIC_POOL.get(topicIdx).size()));
        if (line.contains("{other}")) line = line.replace("{other}", pickOtherPlayerName());
        return line;
    }

    private String pickOtherPlayerName() {
        List<String> others = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getName().equals(name)) others.add(p.getName());
        }
        return others.isEmpty() ? "大家" : others.get(random.nextInt(others.size()));
    }

    private void performActionViaApi() {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null || !player.isOnline()) return;
        switch (random.nextInt(4)) {
            case 0 -> {
                Location loc = player.getLocation();
                double dx = (random.nextDouble() - 0.5) * 3, dz = (random.nextDouble() - 0.5) * 3;
                Location target = loc.clone().add(dx, 0, dz);
                if (target.getWorld() != null && target.getChunk().isLoaded()) {
                    target.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    target.setPitch(0);
                    player.teleport(target);
                }
            }
            case 1 -> {
                Location loc = player.getLocation();
                loc.setYaw(random.nextFloat() * 360f - 180f);
                loc.setPitch(random.nextFloat() * 60f - 30f);
                player.teleport(loc);
            }
            case 2 -> player.swingMainHand();
            case 3 -> {
                player.setSneaking(!player.isSneaking());
                if (player.isSneaking()) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        Player p = Bukkit.getPlayerExact(name);
                        if (p != null) p.setSneaking(false);
                    }, 20L);
                }
            }
        }
    }

    // ── 话题池 ────────────────────────────────────────────────────────────

    private static List<List<String>> buildTopicPool() {
        List<List<String>> pool = new ArrayList<>();
        pool.add(Arrays.asList("刚挖到钻石！今天手气不错", "有人知道钻石层在y多少吗", "发现一个废弃矿井，箱子里有不少好东西"));
        pool.add(Arrays.asList("我在建一个中式大宅，感觉要建很久", "{other} 你那个房子外墙用的什么材料？", "圆形建筑好难搭，总是不对称"));
        pool.add(Arrays.asList("农场一定要早建，食物多了才不慌", "带水桶可以救命，掉进岩浆旁边直接泼", "睡觉跳过夜晚真的很重要"));
        pool.add(Arrays.asList("末影龙终于被我打败了！！", "凋零boss好难打，差点被我打死", "末地城探索完毕，鞘翅到手！"));
        pool.add(Arrays.asList("我做了一个全自动甘蔗农场", "红石真的学不会，看教程也搞不懂", "铁傀儡农场的效率真香"));
        pool.add(Arrays.asList("发现一个蘑菇岛！这里没有怪物真舒服", "我跑了好远才找到樱花树林", "沙漠神殿宝箱有tnt陷阱"));
        pool.add(Arrays.asList("我觉得MC最好玩的就是完全自由", "多人联机比单人有意思多了，热闹", "服务器人多就是好"));
        pool.add(Arrays.asList("新版本加了好多新东西", "铜灯泡加进来，建筑党狂喜", "风弹弓真的好玩"));
        pool.add(Arrays.asList("今天现实好热，来游戏里吹空调", "作业写完了终于可以玩了！", "下班后直接上线"));
        pool.add(Arrays.asList("我的小麦田今天大丰收", "甜浆果真的好烦，走路一直掉血", "烤猪排回血量很高"));
        pool.add(Arrays.asList("终于凑齐了全套钻石装备！", "精准采集保留原始方块", "经验修补和耐久3叠加"));
        pool.add(Arrays.asList("有人知道村庄在哪个方向吗", "{other} 能借我点石头吗？", "迷路了谁来救救我"));
        return Collections.unmodifiableList(pool);
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────

    public void updatePosition(double nx, double ny, double nz, float nyaw, float npitch) {
        this.x = nx; this.y = ny; this.z = nz; this.yaw = nyaw; this.pitch = npitch;
    }

    public String  getBotName()  { return name;      }
    public boolean isConnected() { return connected; }
    public void    setProbeFailedReconnect(boolean val) { this.probeFailedReconnect = val; }
}
