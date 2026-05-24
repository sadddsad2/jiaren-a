package com.randomnpcs.listener;

import com.randomnpcs.RandomBotsPlugin;
import com.randomnpcs.bot.BotManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listens for bot player deaths on the server side.
 * When a bot (fake player) dies, notifies BotManager to schedule a respawn.
 */
public class BotDeathListener implements Listener {

    private final RandomBotsPlugin plugin;
    private final BotManager botManager;

    public BotDeathListener(RandomBotsPlugin plugin, BotManager botManager) {
        this.plugin    = plugin;
        this.botManager = botManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        String playerName = event.getEntity().getName();

        // Check if this is one of our bots
        if (botManager.getActiveBots().containsKey(playerName)) {
            plugin.getLogger().info("Bot " + playerName + " was killed! Scheduling respawn...");
            // Bot TCP connection will also die; BotClient.handleDisconnect() triggers onBotDied.
            // This listener handles the case where the bot is still connected but dead.
            botManager.onBotDied(playerName);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        String playerName = event.getPlayer().getName();
        if (botManager.getActiveBots().containsKey(playerName)) {
            plugin.getLogger().info("Bot " + playerName + " quit (connection lost). Respawning...");
            // BotClient already handles reconnect; this is a safety net
        }
    }
}
