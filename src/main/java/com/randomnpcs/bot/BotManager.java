package com.randomnpcs.bot;

import com.randomnpcs.RandomBotsPlugin;
import org.bukkit.Bukkit;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 严格串行登录管理器：
 * - 同一时刻只允许一个假人处于活跃或登录中状态
 * - 上一个假人死亡/断线后，等待 respawn-delay 秒再连下一个
 * - 支持无限循环驻留模式（running 为 true 时永不停止）
 */
public class BotManager {

    private final RandomBotsPlugin plugin;

    /** 当前唯一活跃 bot（null = 无） */
    private volatile BotClient activeBot = null;

    /** 是否正在登录中（防止并发触发两次 connect） */
    private final AtomicBoolean loginInProgress = new AtomicBoolean(false);

    private volatile boolean running = false;

    /** 名字轮换池，避免重复 */
    private final Set<String> usedNames = new HashSet<>();
    private final Random random = new Random();

    public BotManager(RandomBotsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── 启动 / 停止 ──────────────────────────────────────────────────────

    public synchronized void startBots() {
        if (running) return;
        running = true;
        scheduleNextBot(0L);
    }

    public synchronized void stopAllBots() {
        running = false;
        loginInProgress.set(false);
        if (activeBot != null) {
            activeBot.disconnect("Plugin shutting down");
            activeBot = null;
        }
        usedNames.clear();
    }

    // ── 核心调度：在指定延迟后尝试连接下一个 bot ─────────────────────────

    private void scheduleNextBot(long delayTicks) {
        if (!running) return;
        // 防止双重触发
        if (!loginInProgress.compareAndSet(false, true)) return;

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!running) {
                loginInProgress.set(false);
                return;
            }
            connectNewBot();
        }, Math.max(1L, delayTicks));
    }

    private void connectNewBot() {
        String name = generateUniqueName();
        String host = plugin.getServerHost();
        int port = plugin.getServerPort();

        BotClient bot = new BotClient(plugin, this, name, host, port);
        activeBot = bot;

        try {
            bot.connect();
        } catch (Exception e) {
            // 连接建立失败（网络层）→ 忽略错误，稍后重试
            plugin.getLogger().fine("[BotManager] connect() failed for " + name + ": " + e.getMessage());
            activeBot = null;
            usedNames.remove(name);
            loginInProgress.set(false);
            long retryDelay = plugin.getConfig().getInt("respawn-delay", 5) * 20L;
            scheduleNextBot(retryDelay);
        }
    }

    // ── BotClient 回调 ───────────────────────────────────────────────────

    /** 登录成功进入 Play 状态 — 保持 loginInProgress=true 以防止第二个 bot 被创建 */
    public void onBotLoginSuccess(String botName) {
        // bot 已在线，不再 loginInProgress，但 activeBot 保持
        // 重置标志：当前 bot 活跃期间不允许新建
        loginInProgress.set(false);
        plugin.getLogger().fine("[BotManager] " + botName + " 成功登录并驻留中");
    }

    /** 登录阶段失败（Login Rejected / 超时等） */
    public void onBotLoginFailed(String botName) {
        plugin.getLogger().fine("[BotManager] " + botName + " 登录失败，准备重试");
        activeBot = null;
        usedNames.remove(botName);
        loginInProgress.set(false);
        if (!running) return;
        long retryDelay = plugin.getConfig().getInt("respawn-delay", 5) * 20L;
        scheduleNextBot(retryDelay);
    }

    /** bot 在 Play 状态断线/死亡 — 等待后重连 */
    public void onBotDied(String botName) {
        plugin.getLogger().fine("[BotManager] " + botName + " 断线/死亡，等待重连");
        activeBot = null;
        usedNames.remove(botName);
        loginInProgress.set(false);
        if (!running) return;
        long respawnDelay = plugin.getConfig().getInt("respawn-delay", 5) * 20L;
        scheduleNextBot(respawnDelay);
    }

    /** 包探测失败，快速重连（复用同名，不换名字，因为还未真正入服） */
    public void onBotProbeReconnect(String botName) {
        activeBot = null;
        loginInProgress.set(false);
        if (!running) return;
        // 短暂延迟后重试同名（换新名也行，这里保持复用）
        usedNames.remove(botName);
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!running) return;
            connectNewBot();
        }, 15L);
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

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
        if (activeBot != null && activeBot.getBotName().equals(name)) {
            activeBot = null;
        }
        usedNames.remove(name);
    }

    // ── 状态查询 ─────────────────────────────────────────────────────────

    public boolean isRunning() { return running; }

    /** 目标数固定为 1（单假人驻留模式） */
    public int getBotCount() { return 1; }

    public int getActiveBotCount() { return activeBot != null ? 1 : 0; }

    public Map<String, BotClient> getActiveBots() {
        if (activeBot == null) return Collections.emptyMap();
        Map<String, BotClient> m = new HashMap<>();
        m.put(activeBot.getBotName(), activeBot);
        return Collections.unmodifiableMap(m);
    }
}
