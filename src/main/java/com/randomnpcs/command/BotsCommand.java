package com.randomnpcs.command;

import com.randomnpcs.RandomBotsPlugin;
import com.randomnpcs.bot.BotClient;
import com.randomnpcs.bot.BotManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * /randombots <start|stop|status|reload>
 */
public class BotsCommand implements CommandExecutor, TabCompleter {

    private final RandomBotsPlugin plugin;
    private final BotManager botManager;

    private static final List<String> SUB_COMMANDS = Arrays.asList("start", "stop", "status", "reload");

    public BotsCommand(RandomBotsPlugin plugin, BotManager botManager) {
        this.plugin     = plugin;
        this.botManager = botManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("randombots.admin")) {
            sender.sendMessage(Component.text("❌ 你没有权限使用此命令！", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (botManager.isRunning()) {
                    sender.sendMessage(Component.text("⚠ 机器人已经在运行中！", NamedTextColor.YELLOW));
                } else {
                    botManager.startBots();
                    sender.sendMessage(Component.text("✅ 已启动随机机器人！", NamedTextColor.GREEN));
                }
            }
            case "stop" -> {
                if (!botManager.isRunning()) {
                    sender.sendMessage(Component.text("⚠ 机器人未运行！", NamedTextColor.YELLOW));
                } else {
                    botManager.stopAllBots();
                    sender.sendMessage(Component.text("🛑 已停止所有机器人！", NamedTextColor.RED));
                }
            }
            case "status" -> {
                sender.sendMessage(Component.text("═══ RandomBots 状态 ═══", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("运行状态: " + (botManager.isRunning() ? "§a运行中" : "§c已停止")));
                sender.sendMessage(Component.text("目标机器人数: §e" + botManager.getBotCount()));
                sender.sendMessage(Component.text("活跃机器人数: §e" + botManager.getActiveBotCount()));
                sender.sendMessage(Component.text("服务器端口: §e" + plugin.getServerPort()));

                Map<String, BotClient> bots = botManager.getActiveBots();
                if (!bots.isEmpty()) {
                    sender.sendMessage(Component.text("机器人列表:", NamedTextColor.AQUA));
                    for (Map.Entry<String, BotClient> entry : bots.entrySet()) {
                        String status = entry.getValue().isConnected() ? "§a已连接" : "§c断开";
                        sender.sendMessage(Component.text("  - " + entry.getKey() + " " + status));
                    }
                }
            }
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(Component.text("✅ 配置已重新加载！", NamedTextColor.GREEN));
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("═══ RandomBots 命令 ═══", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/randombots start  §7- 启动随机机器人"));
        sender.sendMessage(Component.text("/randombots stop   §7- 停止所有机器人"));
        sender.sendMessage(Component.text("/randombots status §7- 查看运行状态"));
        sender.sendMessage(Component.text("/randombots reload §7- 重载配置文件"));
        sender.sendMessage(Component.text("别名: /rbots, /rb", NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUB_COMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
