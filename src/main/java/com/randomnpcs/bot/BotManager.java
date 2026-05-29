package com.randomnpcs.bot;

import com.randomnpcs.RandomBotsPlugin;
import org.bukkit.Bukkit;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages all active bot connections and lifecycle with a strict serial login queue.
 */
public class BotManager {

    private final RandomBotsPlugin plugin;
    private final Map<String, BotClient> activeBots = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private int targetBotCount;
    private boolean running = false;

    // Bot name pool tracking (to avoid duplicates)
    private final Set<String> usedNames = new HashSet<>();

    // ── Strict Serial Queue Implementation ─────────────────────────────────
    private final Queue<String> loginQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean isLoginProcessing = false;

    public BotManager(RandomBotsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Start bots: pick a random count, populate the serial queue, and process the first one.
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

        plugin.getLogger().info("[Queue] Preparing to spawn " + targetBotCount + " bots sequentially...");

        for (int i = 0; i < targetBotCount; i++) {
            String name = generateUniqueName();
            usedNames.add(name);
            loginQueue.add(name);
        }

        // Kick off the pipeline
        processNextInQueue();
    }

    /**
     * Polls the next name from the queue and initiates connection after a safety delay.
     */
    private synchronized void processNextInQueue() {
        if (!running) {
            isLoginProcessing = false;
            return;
        }

        String nextBotName = loginQueue.poll();
        if (nextBotName == null) {
            plugin.getLogger().info("[Queue] All scheduled bots have successfully entered the game. Queue closed.");
            isLoginProcessing = false;
            return;
        }

        isLoginProcessing = true;

        // Stagger connections by 20 ticks (1.0s) to let the server process the network handshake completely
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!running) return;
            connectSpecificBot(nextBotName);
        }, 20L);
    }

    /**
     * Establishes connection for a designated bot name.
     */
    private void connectSpecificBot(String name) {
        String host = plugin.getServerHost();
        int port = plugin.getServerPort();

        plugin.getLogger().info("[Queue] Connecting bot: " + name + " -> " + host + ":" + port);

        BotClient bot = new BotClient(plugin, this, name, host, port);
        activeBots.put(name, bot);

        try {
            bot.connect();
            // Note: We do NOT advance the queue here. We wait for the protocol thread to verify stability.
        } catch (Exception e) {
            plugin.getLogger().warning("[Queue] Failed to connect bot " + name + ": " + e.getMessage());
            activeBots.remove(name);
            usedNames.remove(name);
            
            // Current bot failed immediately at socket layer, safely leap-frog to the next slot
            processNextInQueue();
        }
    }

    /**
     * Callback: Triggered when a bot safely receives Play-state packets and locks IDs.
     */
    public void onBotLoginSuccess(String botName) {
        plugin.getLogger().info("✔ [Queue] " + botName + " is fully authenticated & stable. Advancing queue.");
        processNextInQueue();
    }

    /**
     * Callback: Triggered if a bot disconnects before completing the Play-state verification.
     */
    public void onBotLoginFailed(String botName) {
        plugin.getLogger().warning("❌ [Queue] " + botName + " dropped out during sniffing/verification. Releasing slot.");
        activeBots.remove(botName);
        usedNames.remove(botName);
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
     * Called when a verified bot dies in-game — queues up a fresh replacement at the end.
     */
    public void onBotDied(String botName) {
        activeBots.remove(botName);
        usedNames.remove(botName);

        if (!running) return;

        int respawnDelay = plugin.getConfig().getInt("respawn-delay", 5) * 20; // ticks
        plugin.getLogger().fine("Bot " + botName + " died, appending replacement to queue in " + (respawnDelay / 20) + "s...");

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
     * Probe reconnect: Fast-track priority. Reuses the same name instantly to increment
     * and narrow down probe search space without blocking or mixing up other bots.
     */
    public void onBotProbeReconnect(String botName) {
        activeBots.remove(botName);
        if (!running) return;

        // 15-tick safety margin to allow server-side connection teardown to settle down
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!running) return;
            connectSpecificBot(botName);
        }, 15L);
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
