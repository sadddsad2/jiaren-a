package com.example.fakeplayer.command;

import com.example.fakeplayer.FakePlayerPlugin;
import com.example.fakeplayer.bot.BotManager;
import com.example.fakeplayer.bot.FakeBot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FakePlayerCommand implements CommandExecutor, TabCompleter {

    private final FakePlayerPlugin plugin;
    private final BotManager botManager;

    public FakePlayerCommand(FakePlayerPlugin plugin, BotManager botManager) {
        this.plugin     = plugin;
        this.botManager = botManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("fakeplayer.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) { help(sender, label); return true; }

        switch (args[0].toLowerCase()) {
            case "spawn" -> {
                botManager.spawnBot();
                sender.sendMessage(Component.text("Spawning a new bot...", NamedTextColor.GREEN));
            }
            case "remove" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("Usage: /" + label + " remove <name>", NamedTextColor.YELLOW)); return true; }
                if (!botManager.isBot(args[1])) { sender.sendMessage(Component.text("No bot named '" + args[1] + "'.", NamedTextColor.RED)); return true; }
                botManager.removeBot(args[1]);
                sender.sendMessage(Component.text("Removed bot '" + args[1] + "'.", NamedTextColor.GREEN));
            }
            case "removeall" -> {
                int n = botManager.getBots().size();
                botManager.removeAllBots();
                sender.sendMessage(Component.text("Removed " + n + " bots.", NamedTextColor.GREEN));
            }
            case "list" -> {
                var bots = botManager.getBots();
                if (bots.isEmpty()) { sender.sendMessage(Component.text("No active bots.", NamedTextColor.YELLOW)); return true; }
                sender.sendMessage(Component.text("Active bots (" + bots.size() + "):", NamedTextColor.AQUA));
                bots.forEach((name, bot) -> sender.sendMessage(
                        Component.text("  • " + name + " [" + (bot.isAlive() ? "§aonline" : "§coffline") + "§r]")));
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        s.sendMessage(Component.text("=== FakePlayer ===", NamedTextColor.GOLD));
        s.sendMessage(Component.text("/" + label + " spawn|remove <name>|removeall|list"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        if (args.length == 1) return Arrays.asList("spawn", "remove", "removeall", "list");
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) return new ArrayList<>(botManager.getBots().keySet());
        return List.of();
    }
}
