package com.randomnpcs.bot;

import com.randomnpcs.RandomBotsPlugin;
import org.bukkit.Bukkit;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 管理假人的生命周期与串行登录队列
 */
public class BotManager {

    private final RandomBotsPlugin plugin;
    private final Map<String, BotClient> activeBots = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private int targetBotCount;
    private boolean running = false;
    private final Set<String> usedNames = new HashSet<>();

    // 串行登录队列
    private final Queue<String> loginQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean isLoginProcessing = false;

    public BotManager(RandomBotsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 启动假人管理器，生成目标数量并加入队列
     */
    public void startBots() {
        if (running) {
            plugin.getLogger().warning("BotManager 已经在运行中。");
            return;
        }
        running = true;

        int min = plugin.getConfig().getInt("bot-count-min", 3);
        int max = plugin.getConfig().getInt("bot-count-max", 10);
        targetBotCount = min + random.nextInt(max - min + 1);

        plugin.getLogger().info("[队列] 正在准备串行生成 " + targetBotCount + " 个假人...");

        for (int i = 0; i < targetBotCount; i++) {
            String name = generateUniqueName();
            usedNames.add(name);
            loginQueue.add(name);
        }

        // 开始处理队列
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
            plugin.getLogger().info("[队列] 所有预设的假人已全部成功进入游戏，队列结束。");
            isLoginProcessing = false;
            return;
        }

        isLoginProcessing = true;

        // 异步建立连接，给服务器留出 1 秒（20 ticks）的包处理喘息时间
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!running) return;
            connectSpecificBot(nextBotName);
        }, 20L);
    }

    /**
     * 连接一个特定的假人
     */
    private void connectSpecificBot(String name) {
        String host = plugin.getServerHost();
        int port = plugin.getServerPort();

        plugin.getLogger().info("[队列] 正在启动假人: " + name + " -> " + host + ":" + port);

        BotClient bot = new BotClient(plugin, this, name, host, port);
        activeBots.put(name, bot);

        try {
            bot.connect();
            // 注意：此处不主动推进队列！必须等待协议线程成功 Verified 之后回调。
        } catch (Exception e) {
            plugin.getLogger().warning("[队列] 假人 " + name + " Socket 连接失败: " + e.getMessage());
            activeBots.remove(name);
            usedNames.remove(name);
            // 物理连不上，直接跳过处理下一个
            processNextInQueue();
        }
    }

    /**
     * 回调：假人完全成功进入游戏（通过探测或直接使用正确记录值通过 Play 认证）
     */
    public void onBotLoginSuccess(String botName) {
        plugin.getLogger().info("✔ [队列] " + botName + " 已成功登入并稳定运行。放行下一个假人。");
        processNextInQueue();
    }

    /**
     * 回调：假人在登录/探测阶段被踢或断开（未完成 Verified）
     */
    public void onBotLoginFailed(String botName) {
        plugin.getLogger().warning("❌ [队列] " + botName + " 在登录/探测阶段断开。释放队列槽位。");
        activeBots.remove(botName);
        usedNames.remove(botName);
        processNextInQueue();
    }

    /**
     * 停止所有假人
     */
    public void stopAllBots() {
        running = false;
        loginQueue.clear();
        isLoginProcessing = false;
        List<BotClient> toStop = new ArrayList<>(activeBots.values());
        for (BotClient bot : toStop) {
            bot.disconnect("插件卸载");
        }
        activeBots.clear();
        usedNames.clear();
    }

    /**
     * 在游戏内正常存活后死掉或掉线的假人，进行常规重生重试
     */
    public void onBotDied(String botName) {
        activeBots.remove(botName);
        usedNames.remove(botName);

        if (!running) return;

        int respawnDelay = plugin.getConfig().getInt("respawn-delay", 5) * 20;
        plugin.getLogger().info("假人 " + botName + " 掉线，将在 " + (respawnDelay / 20) + " 秒后重新加入队列排队...");

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!running) return;
            String newName = generateUniqueName();
            usedNames.add(newName);
            
            synchronized (this) {
                loginQueue.add(newName);
                if (!isLoginProcessing) {
                    processNextInQueue();
                }
            }
        }, respawnDelay);
    }

    /**
     * 探针重连：当前正在负责探测 ID 的假人由于猜测错误被踢
     * 此时拥有最高优先级：必须立刻插队重连，直到把正确的 ID 探测出来！
     */
    public void onBotProbeReconnect(String botName) {
        activeBots.remove(botName);
        if (!running) return;

        plugin.getLogger().info("[探针] " + botName + " 猜测 ID 失败被踢，正在使用更新后的候选值发起即时重连...");

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!running) return;
            // 探针重连复用相同的名字，继续担当开路先锋
            connectSpecificBot(botName);
        }, 15L); // 错开 0.75 秒后重新建立 Socket
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
