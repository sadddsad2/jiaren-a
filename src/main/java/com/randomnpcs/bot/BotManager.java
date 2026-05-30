package com.randomnpcs.bot;

import com.randomnpcs.RandomBotsPlugin;
import org.bukkit.Bukkit;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多假人并发管理器：
 * - 同时维持 bot-count 个假人在线
 * - 每个假人独立断线/死亡后自动重连
 * - debug-log: false 时只输出关键日志
 */
public class BotManager {

    private final RandomBotsPlugin plugin;

    /** 当前所有活跃 bot，key = botName */
    private final Map<String, BotClient> activeBots = new ConcurrentHashMap<>();

    /** 正在登录中的 bot 数量（防止并发超额创建） */
    private final AtomicInteger loginInProgress = new AtomicInteger(0);

    private volatile boolean running = false;
    private final Set<String> usedNames = Collections.synchronizedSet(new HashSet<>());
    private final Random random = new Random();

    public BotManager(RandomBotsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── 启动 / 停止 ──────────────────────────────────────────────────────

    public synchronized void startBots() {
        if (running) return;
        running = true;
        int target = getTargetCount();
        log("启动多假人模式，目标数量: " + target);
        for (int i = 0; i < target; i++) {
            scheduleNextBot(i * 20L); // 每个间隔1秒错开登录，避免同时连接
        }
    }

    public synchronized void stopAllBots() {
        running = false;
        loginInProgress.set(0);
        for (BotClient bot : activeBots.values()) {
            bot.disconnect("Plugin shutting down");
        }
        activeBots.clear();
        usedNames.clear();
    }

    // ── 核心调度 ─────────────────────────────────────────────────────────

    private void scheduleNextBot(long delayTicks) {
        if (!running) return;
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!running) return;
            // 检查当前在线+登录中是否已达目标
            int current = activeBots.size() + loginInProgress.get();
            if (current >= getTargetCount()) {
                debugLog("已达目标数量 " + getTargetCount() + "，跳过本次调度");
                return;
            }
            loginInProgress.incrementAndGet();
            connectNewBot();
        }, Math.max(1L, delayTicks));
    }

    private void connectNewBot() {
        String name = generateUniqueName();
        String host = plugin.getServerHost();
        int port = plugin.getServerPort();

        BotClient bot = new BotClient(plugin, this, name, host, port);

        try {
            bot.connect();
        } catch (Exception e) {
            debugLog("[BotManager] connect() 失败 " + name + ": " + e.getMessage());
            usedNames.remove(name);
            loginInProgress.decrementAndGet();
            long retryDelay = plugin.getConfig().getInt("respawn-delay", 5) * 20L;
            scheduleNextBot(retryDelay);
        }
    }

    // ── BotClient 回调 ───────────────────────────────────────────────────

    public void onBotLoginSuccess(String botName, BotClient bot) {
        activeBots.put(botName, bot);
        loginInProgress.decrementAndGet();
        log("[" + botName + "] 登录成功，当前在线: " + activeBots.size() + "/" + getTargetCount());
    }

    public void onBotLoginFailed(String botName) {
        debugLog("[" + botName + "] 登录失败，准备重试");
        activeBots.remove(botName);
        usedNames.remove(botName);
        loginInProgress.decrementAndGet();
        if (!running) return;
        long retryDelay = plugin.getConfig().getInt("respawn-delay", 5) * 20L;
        scheduleNextBot(retryDelay);
    }

    public void onBotDied(String botName) {
        activeBots.remove(botName);
        usedNames.remove(botName);
        loginInProgress.decrementAndGet();
        log("[" + botName + "] 断线/死亡，当前在线: " + activeBots.size() + "/" + getTargetCount() + "，等待重连");
        if (!running) return;
        long respawnDelay = plugin.getConfig().getInt("respawn-delay", 5) * 20L;
        scheduleNextBot(respawnDelay);
    }

    public void onBotProbeReconnect(String botName) {
        activeBots.remove(botName);
        usedNames.remove(botName);
        loginInProgress.decrementAndGet();
        if (!running) return;
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!running) return;
            loginInProgress.incrementAndGet();
            connectNewBot();
        }, 15L);
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    private int getTargetCount() {
        return plugin.getConfig().getInt("bot-count", 1);
    }

    /** 所有日志统一受 debug-log 控制 */
    private void log(String msg) {
        if (plugin.getConfig().getBoolean("debug-log", false)) {
            plugin.getLogger().info(msg);
        }
    }

    public void debugLog(String msg) {
        if (plugin.getConfig().getBoolean("debug-log", false)) {
            plugin.getLogger().info(msg);
        }
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
            name = prefix + suffix;
            if (name.length() > 16) name = name.substring(0, 16);
            attempts++;
        } while (usedNames.contains(name) && attempts < 200);

        usedNames.add(name);
        return name;
    }

    public void removeBot(String name) {
        activeBots.remove(name);
        usedNames.remove(name);
    }

    // ── 状态查询 ─────────────────────────────────────────────────────────

    public boolean isRunning()                    { return running; }
    public int getBotCount()                      { return getTargetCount(); }
    public int getActiveBotCount()                { return activeBots.size(); }
    public Map<String, BotClient> getActiveBots() { return Collections.unmodifiableMap(activeBots); }
}
