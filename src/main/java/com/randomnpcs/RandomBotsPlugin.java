package com.randomnpcs;

import com.randomnpcs.bot.BotManager;
import com.randomnpcs.command.BotsCommand;
import com.randomnpcs.listener.BotDeathListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class RandomBotsPlugin extends JavaPlugin {

    private static RandomBotsPlugin instance;
    private BotManager botManager;
    private int serverPort;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config
        saveDefaultConfig();

        // Resolve server port: env var > config
        String envPort = System.getenv("SERVER_PORT");
        if (envPort != null && !envPort.isEmpty()) {
            try {
                serverPort = Integer.parseInt(envPort.trim());
                getLogger().info("Using SERVER_PORT from environment: " + serverPort);
            } catch (NumberFormatException e) {
                getLogger().warning("Invalid SERVER_PORT env var: '" + envPort + "', falling back to config.");
                serverPort = getConfig().getInt("server-port", 25565);
            }
        } else {
            serverPort = getConfig().getInt("server-port", 25565);
            getLogger().info("SERVER_PORT env not set, using config port: " + serverPort);
        }

        // Initialize bot manager
        botManager = new BotManager(this);

        // Register listeners
        getServer().getPluginManager().registerEvents(new BotDeathListener(this, botManager), this);

        // Register commands
        BotsCommand botsCommand = new BotsCommand(this, botManager);
        getCommand("randombots").setExecutor(botsCommand);
        getCommand("randombots").setTabCompleter(botsCommand);

        // Auto-start bots
        getServer().getScheduler().runTaskLater(this, () -> {
            botManager.startBots();
            getLogger().info("RandomBots started on port " + serverPort + " with " + botManager.getBotCount() + " bots.");
        }, 40L); // 2 second delay after server fully starts

        getLogger().info("=== RandomBots v" + getDescription().getVersion() + " enabled ===");
    }

    @Override
    public void onDisable() {
        if (botManager != null) {
            botManager.stopAllBots();
        }
        getLogger().info("=== RandomBots disabled ===");
    }

    public static RandomBotsPlugin getInstance() {
        return instance;
    }

    public BotManager getBotManager() {
        return botManager;
    }

    public int getServerPort() {
        return serverPort;
    }

    public String getServerHost() {
        return getConfig().getString("server-host", "localhost");
    }
}
