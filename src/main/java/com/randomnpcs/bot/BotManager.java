package com.randomnpcs.bot;

import com.randomnpcs.RandomBotsPlugin;
import org.bukkit.Bukkit;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all active bot connections and lifecycle.
 *
 * KEY BEHAVIOR — sequential staggered login:
 *   startBots() spawns the first bot. When it finishes (success or failure),
 *   the callback connectNextBot() is called to start the next one.
 *   This guarantees bots never connect in parallel.
 *
 *   All protocol IDs are auto-detected on the very first connection.
 *   The first bot may take a few extra seconds for sniff/probe.
 *   Every subsequent bot loads cached IDs and connects instantly.
 */
public class BotManager {

    private final RandomBotsPlugin plugin;
    private final Map<String, BotClient> activeBots = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private int targetBotCount;
    private boolean running = false;

    // Bot name pool tracking (to avoid duplicates)
    private final Set<String> usedNames = new HashSet<>();

    // ── Sequential login state ────────────────────────────────────────────
    // How many bots we still need to create this round
    private int botsRemaining = 0;

    public BotManager(RandomBotsPlugin plugin) {
        this.plugin = plugin;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Start — kick off the sequential chain
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start bots: pick a random count between min and max, then connect
     * them ONE BY ONE. The next bot only starts after the previous one
     * has finished logging in (or failed).
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
        botsRemaining = targetBotCount;

        plugin.getLogger().fine("Spawning " + targetBotCount
                + " bots sequentially...");

        // Start the chain on an async thread (first bot has no dependency)
        connectNextBot();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Sequential chain — each bot triggers the next on completion
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Called to attempt connecting the next bot in the sequence.
     * Runs entirely on an async thread so it can block on connect().
     */
    private void connectNextBot() {
        if (!running || botsRemaining <= 0) return;
        botsRemaining--;

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!running) return;
            connectBotSequential();
        }, 1L); // 1-tick delay between bots for cleanliness
    }

    /**
     * Connect one bot on the calling thread (blocking).
     * On success: schedule the next bot immediately.
     * On failure: schedule the next bot after a short delay.
     */
    private void connectBotSequential() {
        if (!running) return;

        String name = generateUniqueName();
        usedNames.add(name);

        String host = plugin.getServerHost();
        int port    = plugin.getServerPort();

        plugin.getLogger().fine("Connecting bot: " + name
                + " -> " + host + ":" + port);

        BotClient bot = new BotClient(plugin, this, name, host, port);
        activeBots.put(name, bot);

        boolean success = false;
        try {
            bot.connect();          // BLOCKS — login completes or throws
            success = true;
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to connect bot " + name
                    + ": " + e.getMessage());
            activeBots.remove(name);
            usedNames.remove(name);
        }

        // Chain to next bot regardless of success/failure
        if (running && botsRemaining > 0) {
            int delay = success ? 1 : 5; // fail gets a slightly longer gap
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin,
                    this::connectBotSequential, delay);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Stop
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Stop and disconnect all bots.
     */
    public void stopAllBots() {
        running = false;
        botsRemaining = 0;
        plugin.getLogger().fine("Stopping all " + activeBots.size() + " bots...");
        List<BotClient> toStop = new ArrayList<>(activeBots.values());
        for (BotClient bot : toStop) {
            bot.disconnect("Plugin shutting down");
        }
        activeBots.clear();
        usedNames.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Callbacks from BotClient
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Called when a bot dies — respawns after configured delay.
     * (Independent of the sequential startup chain.)
     */
    public void onBotDied(String botName) {
        activeBots.remove(botName);
        usedNames.remove(botName);

        if (!running) return;

        int respawnDelay = plugin.getConfig().getInt("respawn-delay", 5) * 20;
        plugin.getLogger().fine("Bot " + botName + " died, respawning in "
                + (respawnDelay / 20) + "s...");

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin,
                this::connectRespawnBot, respawnDelay);
    }

    /**
     * Reconnects a dead bot with a new name after respawn delay.
     */
    private void connectRespawnBot() {
        if (!running) return;

        String name = generateUniqueName();
        usedNames.add(name);

        String host = plugin.getServerHost();
        int port    = plugin.getServerPort();

        plugin.getLogger().fine("Respawning bot: " + name
                + " -> " + host + ":" + port);

        BotClient bot = new BotClient(plugin, this, name, host, port);
        activeBots.put(name, bot);

        try {
            bot.connect();
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to respawn bot " + name
                    + ": " + e.getMessage());
            activeBots.remove(name);
            usedNames.remove(name);
        }
    }

    /**
     * Called when a bot's KeepAlive probe was rejected by the server.
     * Reconnects immediately with the next candidate ID, without respawn delay.
     * Same name is reused so the probe state in MinecraftProtocol's static
     * cache carries over.
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
            plugin.getLogger().fine("Probe reconnect: " + botName
                    + " -> " + host + ":" + port);

            BotClient bot = new BotClient(plugin, this, botName, host, port);
            activeBots.put(botName, bot);
            try {
                bot.connect();
            } catch (Exception e) {
                plugin.getLogger().fine("Probe reconnect failed for " + botName
                        + ": " + e.getMessage());
                activeBots.remove(botName);
                usedNames.remove(botName);
                onBotDied(botName);
            }
        }, 1L);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

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
