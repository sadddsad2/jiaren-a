package com.randomnpcs.listener;

import com.randomnpcs.RandomBotsPlugin;
import com.randomnpcs.bot.BotManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 监听假人的死亡 / 踢出 / 离线事件，确保 BotManager 收到通知并安排重连。
 * BotClient 读包线程也会捕获断线，这里只作为安全兜底，防止信号丢失。
 */
public class BotDeathListener implements Listener {

    private final RandomBotsPlugin plugin;
    private final BotManager botManager;

    public BotDeathListener(RandomBotsPlugin plugin, BotManager botManager) {
        this.plugin     = plugin;
        this.botManager = botManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        String playerName = event.getEntity().getName();
        if (botManager.getActiveBots().containsKey(playerName)) {
            plugin.getLogger().fine("[BotListener] bot " + playerName + " died in-game");
            // BotClient 读包线程在断线时会自行调用 onBotDied，此处不重复触发
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerKick(PlayerKickEvent event) {
        String playerName = event.getPlayer().getName();
        if (botManager.getActiveBots().containsKey(playerName)) {
            plugin.getLogger().fine("[BotListener] bot " + playerName + " was kicked: " + event.getReason());
            // TCP 断开后 BotClient 线程会触发 handleDisconnect()，这里不重复
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        String playerName = event.getPlayer().getName();
        if (botManager.getActiveBots().containsKey(playerName)) {
            plugin.getLogger().fine("[BotListener] bot " + playerName + " quit (safety net)");
        }
    }
}
