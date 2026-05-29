package com.randomnpcs.bot;

import com.randomnpcs.RandomBotsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages all active bot connections and lifecycle.
 * Each bot connects via a fake player client over TCP to the local server.
 */
public class BotManager {

    private final RandomBotsPlugin plugin;
    private final Map<String, BotClient> activeBots = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private int targetBotCount;
    private boolean running = false;

    // Bot name pool tracking (to avoid duplicates)
    private final Set<String> usedNames = new HashSet<>();

    public BotManager(RandomBotsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Start bots: pick a random count between min and max, then connect them all.
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

        plugin.getLogger().info("Spawning " + targetBotCount + " random bots...");

        for (int i = 0; i < targetBotCount; i++) {
            final int delay = i * 10; // stagger connections by 0.5s each
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::connectNewBot, delay);
        }
    }

    /**
     * Stop and disconnect all bots.
     */
    public void stopAllBots() {
        running = false;
        plugin.getLogger().info("Stopping all " + activeBots.size() + " bots...");
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
        plugin.getLogger().info("Bot " + botName + " died. Respawning in " + (respawnDelay / 20) + "s...");

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::connectNewBot, respawnDelay);
    }


    /**
     * Called when a bot's KeepAlive probe was rejected by the server.
     * Reconnects immediately with the next candidate ID, without respawn delay.
     * Same name is reused so the probe state in MinecraftProtocol's static cache carries over.
     */
    public void onBotProbeReconnect(String botName) {
        activeBots.remove(botName);
        // Keep name in usedNames — we reuse the same name intentionally

        if (!running) return;

        // 1-tick delay to let the socket fully close
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!running) return;
            String host = plugin.getServerHost();
            int port    = plugin.getServerPort();
            plugin.getLogger().info("Probe reconnect: " + botName + " -> " + host + ":" + port);

            BotClient bot = new BotClient(plugin, this, botName, host, port);
            activeBots.put(botName, bot);
            try {
                bot.connect();
            } catch (Exception e) {
                plugin.getLogger().warning("Probe reconnect failed for " + botName + ": " + e.getMessage());
                activeBots.remove(botName);
                usedNames.remove(botName);
                onBotDied(botName);
            }
        }, 1L);
    }

    /**
     * Connect a single new bot with a random unused name.
     */
    private void connectNewBot() {
        if (!running) return;

        String name = generateUniqueName();
        usedNames.add(name);

        String host = plugin.getServerHost();
        int port = plugin.getServerPort();

        plugin.getLogger().info("Connecting bot: " + name + " -> " + host + ":" + port);

        BotClient bot = new BotClient(plugin, this, name, host, port);
        activeBots.put(name, bot);

        try {
            bot.connect();
        } catch (Exception e) {
            // Log full stack so protocol errors are visible in console
            plugin.getLogger().log(Level.WARNING, "Failed to connect bot " + name, e);
            activeBots.remove(name);
            usedNames.remove(name);
        }
    }

    /**
     * Generate a random name not currently in use.
     */
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
            // Minecraft names max 16 chars
            name = (prefix + suffix);
            if (name.length() > 16) name = name.substring(0, 16);
            attempts++;
        } while (usedNames.contains(name) && attempts < 100);

        return name;
    }

    public void removeBot(String name) {
        activeBots.remove(name);
    }

    public int getBotCount() {
        return targetBotCount;
    }

    public int getActiveBotCount() {
        return activeBots.size();
    }

    public Map<String, BotClient> getActiveBots() {
        return Collections.unmodifiableMap(activeBots);
    }

    public boolean isRunning() {
        return running;
    }
}
