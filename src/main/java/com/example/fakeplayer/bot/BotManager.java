package com.example.fakeplayer.bot;

import com.example.fakeplayer.FakePlayerPlugin;
import com.example.fakeplayer.nms.VersionAdapter;
import com.example.fakeplayer.util.NameGenerator;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class BotManager {

    private final FakePlayerPlugin plugin;
    /** name → FakeBot */
    private final Map<String, FakeBot> bots = new ConcurrentHashMap<>();

    public BotManager(FakePlayerPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ //

    /** Spawn the startup batch (random 3-6 bots). */
    public void spawnInitialBots() {
        int min   = plugin.getConfig().getInt("bot.min-count", 3);
        int max   = plugin.getConfig().getInt("bot.max-count", 6);
        int count = min + new Random().nextInt(max - min + 1);
        plugin.getLogger().info("Spawning " + count + " fake players...");
        for (int i = 0; i < count; i++) {
            // Stagger spawns by 10 ticks to avoid PlayerList race conditions
            Bukkit.getScheduler().runTaskLater(plugin, this::spawnBot, i * 10L);
        }
    }

    /** Spawn one bot with a random name. */
    public void spawnBot() {
        String name = NameGenerator.generate(bots.keySet());
        UUID   uuid = offlineUUID(name);

        FakeBot bot = new FakeBot(plugin, name, uuid);
        bots.put(name, bot);

        World world = resolveWorld();
        plugin.getLogger().info("Injecting bot '" + name + "'...");

        // NMS injection must happen on the main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Player player = VersionAdapter.injectFakePlayer(name, uuid, world);
                if (player == null) {
                    plugin.getLogger().warning("Bot '" + name + "' injection returned null player.");
                    bots.remove(name);
                    return;
                }
                bot.attach(player);
                plugin.getLogger().info("Bot '" + name + "' is now online.");
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to inject bot '" + name + "'", e);
                bots.remove(name);
            }
        });
    }

    /** Remove one bot cleanly. */
    public void removeBot(String name) {
        FakeBot bot = bots.remove(name);
        if (bot == null) return;
        bot.stopAI();
        Player p = bot.getPlayer();
        if (p != null && p.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                VersionAdapter.ejectFakePlayer(p);
            });
        }
    }

    /** Remove all bots (plugin disable). */
    public void removeAllBots() {
        new HashSet<>(bots.keySet()).forEach(this::removeBot);
    }

    /** Called by the death listener: remove dead bot and schedule replacement. */
    public void handleBotDeath(String name) {
        FakeBot bot = bots.remove(name);
        if (bot != null) bot.stopAI();

        long delay = plugin.getConfig().getLong("bot.respawn-delay", 200L);
        plugin.getLogger().info("Bot '" + name + "' died – replacement in " + (delay / 20) + "s.");
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnBot, delay);
    }

    /** Wired up from join event to attach Player entity. */
    public void onBotJoin(Player player) {
        FakeBot bot = bots.get(player.getName());
        if (bot == null) return;
        bot.attach(player);
    }

    /** Wired up from quit event. */
    public void onBotQuit(String name) {
        FakeBot bot = bots.get(name);
        if (bot != null) bot.stopAI();
    }

    public boolean isBot(String name)              { return bots.containsKey(name); }
    public Map<String, FakeBot> getBots()           { return Collections.unmodifiableMap(bots); }

    // ------------------------------------------------------------------ //

    private World resolveWorld() {
        String name = plugin.getConfig().getString("bot.world", "");
        if (name != null && !name.isEmpty()) {
            World w = Bukkit.getWorld(name);
            if (w != null) return w;
            plugin.getLogger().warning("World '" + name + "' not found, using default.");
        }
        return Bukkit.getWorlds().get(0);
    }

    /** Offline-mode UUID = UUID v3 of "OfflinePlayer:<name>" */
    private static UUID offlineUUID(String name) {
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
