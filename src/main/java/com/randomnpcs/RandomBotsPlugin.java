package com.randomnpcs;

import com.randomnpcs.bot.BotManager;
import com.randomnpcs.command.BotsCommand;
import com.randomnpcs.listener.BotDeathListener;
import org.bukkit.plugin.java.JavaPlugin;

public class RandomBotsPlugin extends JavaPlugin {

    private static RandomBotsPlugin instance;
    private BotManager botManager;
    private int serverPort;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // 解析端口：环境变量 > config.yml
        String envPort = System.getenv("SERVER_PORT");
        if (envPort != null && !envPort.isEmpty()) {
            try {
                serverPort = Integer.parseInt(envPort.trim());
                getLogger().info("SERVER_PORT from env: " + serverPort);
            } catch (NumberFormatException e) {
                getLogger().warning("Invalid SERVER_PORT '" + envPort + "', using config.");
                serverPort = getConfig().getInt("server-port", 25565);
            }
        } else {
            serverPort = getConfig().getInt("server-port", 25565);
        }

        botManager = new BotManager(this);

        // 注册事件监听器
        getServer().getPluginManager().registerEvents(
                new BotDeathListener(this, botManager), this);

        // 注册命令
        BotsCommand botsCommand = new BotsCommand(this, botManager);
        getCommand("randombots").setExecutor(botsCommand);
        getCommand("randombots").setTabCompleter(botsCommand);

        // 服务器完全启动后 2 秒再连接，避免端口未就绪
        getServer().getScheduler().runTaskLater(this, () -> {
            botManager.startBots();
            getLogger().info("RandomBots 单假人驻留模式已启动，目标服务器: "
                    + getServerHost() + ":" + serverPort);
        }, 40L);

        getLogger().info("=== RandomBots v" + getDescription().getVersion() + " enabled ===");
    }

    @Override
    public void onDisable() {
        if (botManager != null) botManager.stopAllBots();
        getLogger().info("=== RandomBots disabled ===");
    }

    public static RandomBotsPlugin getInstance() { return instance; }
    public BotManager getBotManager()            { return botManager; }
    public int        getServerPort()            { return serverPort; }
    public String     getServerHost()            { return getConfig().getString("server-host", "localhost"); }
}
