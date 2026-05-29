package com.randomnpcs.bot;

import com.randomnpcs.RandomBotsPlugin;
import org.bukkit.Bukkit;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages all active bot connections and lifecycle.
 * Modified to support serialized/sequential login queue.
 */
public class BotManager {

    private final RandomBotsPlugin plugin;
    private final Map<String, BotClient> activeBots = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private int targetBotCount;
    private boolean running = false;

    private final Set<String> usedNames = new HashSet<>();

    // ── 串行登录队列 ──────────────────────────────────────────────────────
    private final Queue<String> loginQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean isLoginProcessing = false;

    public BotManager(RandomBotsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Start bots: Calculate total target, generate their names, and push to queue.
     */
    public void startBots() {
        if (running) {
            plugin.getLogger().warning("BotManager is already running.");
            return;
        }
        running = true;

        int min = plugin.getConfig().getInt("bot-count-min", 3);
        int max = plugin.getConfig().getInt("bot-count-max", 10);
        targetBotCount = min + random.nextInt(max - min + 1);

        plugin.getLogger().info("Preparing to spawn " + targetBotCount + " bots sequentially...");

        // 1. 预先生成所有目标假人的名字并入队
        for (int i = 0; i < targetBotCount; i++) {
            String name = generateUniqueName();
            usedNames.add(name);
            loginQueue.add(name);
        }

        // 2. 触发队列执行
        processNextInQueue();
    }

    /**
     * 提取队列中的下一个假人进行连接
     */
    private synchronized void processNextInQueue() {
        if (!running) {
            isLoginProcessing = false;
            return;
        }

        String nextBotName = loginQueue.poll();
        if (nextBotName == null) {
            plugin.getLogger().info("All scheduled bots have completed the login queue.");
            isLoginProcessing = false;
            return;
        }

        isLoginProcessing = true;
        
        // 异步执行网络连接，避免阻塞主线程
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!running) return;
            connectSpecificBot(nextBotName);
        }, 5L); // 错开 0.25 秒再建立 Socket，给系统留出喘息时间
    }

    /**
     * 连接一个指定名字的假人
     */
    private void connectSpecificBot(String name) {
        String host = plugin.getServerHost();
        int port = plugin.getServerPort();

        plugin.getLogger().fine("[Queue] Connecting bot: " + name + " -> " + host + ":" + port);

        BotClient bot = new BotClient(plugin, this, name, host, port);
        activeBots.put(name, bot);

        try {
            bot.connect();
            // 注意：在这里我们不主动触发下一个！
            // 因为 connect() 完成只代表握手成功，我们要等 ReaderThread 收到 Play 包或者完成完整的初始化。
            // 具体的推进移交给了 onBotLoginSuccess 回调。
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to connect bot " + name + ": " + e.getMessage());
            activeBots.remove(name);
            usedNames.remove(name);
            
            // 当前假人连接失败，立刻推进下一个，不能让队列卡死
            processNextInQueue();
        }
    }

    /**
     * 【新回调】当假人完全成功进入游戏（Login+Config阶段完成，进入Play状态）时触发
     */
    public void onBotLoginSuccess(String botName) {
        plugin.getLogger().fine("[Queue] " + botName + " logged in successfully. Processing next...");
        processNextInQueue();
    }

    /**
     * Stop and disconnect all bots.
     */
    public void stopAllBots() {
        running = false;
        loginQueue.clear();
        isLoginProcessing = false;
        plugin.getLogger().fine("Stopping all " + activeBots.size() + " bots...");
        List<BotClient> toStop = new ArrayList<>(activeBots.values());
        for (BotClient bot : toStop) {
            bot.disconnect("Plugin shutting down");
        }
        activeBots.clear();
        usedNames.clear();
    }

    /**
     * Called when a bot dies — respawns after configured delay.
     */
    public void onBotDied(String botName) {
        activeBots.remove(botName);
        usedNames.remove(botName);

        if (!running) return;

        int respawnDelay = plugin.getConfig().getInt("respawn-delay", 5) * 20; // ticks
        plugin.getLogger().fine("Bot " + botName + " died, scheduling single respawn in " + (respawnDelay / 20) + "s...");

        // 死掉的假人作为独立事件重连，或者你也可以选择放回 loginQueue。这里采用独立延迟重连：
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!running) return;
            String newName = generateUniqueName();
            usedNames.add(newName);
            synchronized (this) {
                if (isLoginProcessing) {
                    // 如果当前有队列正在跑，直接塞入队列尾部排队，保持串行
                    loginQueue.add(newName);
                } else {
                    // 如果队列闲置，直接开始跑
                    loginQueue.add(newName);
                    processNextInQueue();
                }
            }
        }, respawnDelay);
    }

    /**
     * Called when a bot's KeepAlive probe was rejected by the server.
     */
    public void onBotProbeReconnect(String botName) {
        activeBots.remove(botName);

        if (!running) return;

        // 探针重连拥有最高优先级，直接独立即时执行（因为它的协议探测状态在 Protocol 中缓存了）
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!running) return;
            String host = plugin.getServerHost();
            int port    = plugin.getServerPort();
            plugin.getLogger().fine("Probe immediate reconnect: " + botName);

            BotClient bot = new BotClient(plugin, this, botName, host, port);
            activeBots.put(botName, bot);
            try {
                bot.connect();
                // 探针重连的假人成功后，也会触发 onBotLoginSuccess，如果主队列当时挂起，会顺便恢复推进
            } catch (Exception e) {
                plugin.getLogger().fine("Probe reconnect failed for " + botName + ": " + e.getMessage());
                activeBots.remove(botName);
                usedNames.remove(botName);
                onBotDied(botName);
            }
        }, 1L);
    }

    private String generateUniqueName() {
        List<String> prefixes = plugin.getConfig().getStringList("name-prefixes");
        List<String> suffixes = plugin.getConfig().getStringList("name-suffixes");

        if (prefixes.isEmpty()) prefixes = Arrays.asList("Bot", "NPC", "Auto");
        if (suffixes.isEmpty()) suffixes = Arrays.asList("01", "02", "03");

        String name;
        int attempts = 0;
        do {
            String prefix = prefixes.get(random.nextInt(prefixes.size()));
            String suffix = suffixes.get(random.nextInt(suffixes.size()));
            name = (prefix + suffix);
            if (name.length() > 16) name = name.substring(0, 16);
            attempts++;
        } while (usedNames.contains(name) && attempts < 100);

        return name;
    }

    public void removeBot(String name) { activeBots.remove(name); }
    public int getBotCount() { return targetBotCount; }
    public int getActiveBotCount() { return activeBots.size(); }
    public Map<String, BotClient> getActiveBots() { return Collections.unmodifiableMap(activeBots); }
    public boolean isRunning() { return running; }
}
