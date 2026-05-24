package com.example.fakeplayer.bot;

import com.example.fakeplayer.FakePlayerPlugin;
import com.example.fakeplayer.ai.BotAI;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public class FakeBot {

    private final FakePlayerPlugin plugin;
    private final String name;
    private final UUID uuid;
    private final BotAI ai;

    private Player player;
    private BukkitTask aiTask;

    public FakeBot(FakePlayerPlugin plugin, String name, UUID uuid) {
        this.plugin = plugin;
        this.name   = name;
        this.uuid   = uuid;
        this.ai     = new BotAI(plugin);
    }

    public void attach(Player player) {
        this.player = player;
        startAI();
    }

    public void startAI() {
        stopAI();
        long interval = plugin.getConfig().getLong("bot.action-interval", 60L);
        aiTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (player != null && player.isOnline()) ai.tick(player);
        }, interval, interval);
    }

    public void stopAI() {
        if (aiTask != null) { aiTask.cancel(); aiTask = null; }
    }

    public String getName()   { return name; }
    public UUID   getUuid()   { return uuid; }
    public Player getPlayer() { return player; }

    public boolean isAlive() {
        return player != null && player.isOnline() && !player.isDead();
    }
}
