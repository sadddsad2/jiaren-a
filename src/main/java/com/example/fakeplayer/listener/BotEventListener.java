package com.example.fakeplayer.listener;

import com.example.fakeplayer.FakePlayerPlugin;
import com.example.fakeplayer.bot.BotManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class BotEventListener implements Listener {

    private final FakePlayerPlugin plugin;
    private final BotManager botManager;

    public BotEventListener(FakePlayerPlugin plugin, BotManager botManager) {
        this.plugin     = plugin;
        this.botManager = botManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!botManager.isBot(e.getPlayer().getName())) return;
        botManager.onBotJoin(e.getPlayer());
        e.joinMessage(null); // suppress join message for bots
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        if (!botManager.isBot(e.getPlayer().getName())) return;
        botManager.onBotQuit(e.getPlayer().getName());
        e.quitMessage(null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (!botManager.isBot(e.getEntity().getName())) return;
        botManager.handleBotDeath(e.getEntity().getName());
        e.deathMessage(null);
    }
}
