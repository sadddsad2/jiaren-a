package com.randomnpcs;

import com.randomnpcs.bot.BotManager;
import com.randomnpcs.command.BotsCommand;
import com.randomnpcs.listener.BotDeathListener;
import com.randomnpcs.net.MinecraftProtocol;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class RandomBotsPlugin extends JavaPlugin {

    private static RandomBotsPlugin instance;
    private BotManager botManager;
    private int serverPort;

    /** 持久化 keepalive ID 缓存文件 */
    private File keepaliveCacheFile;

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

        // 加载持久化 keepalive 缓存
        keepaliveCacheFile = new File(getDataFolder(), "keepalive-cache.yml");
        loadKeepaliveCache();

        botManager = new BotManager(this);

        getServer().getPluginManager().registerEvents(
                new BotDeathListener(this, botManager), this);

        BotsCommand botsCommand = new BotsCommand(this, botManager);
        getCommand("randombots").setExecutor(botsCommand);
        getCommand("randombots").setTabCompleter(botsCommand);

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

    /** 从磁盘加载 keepalive ID 缓存，注入到 MinecraftProtocol 静态缓存 */
    private void loadKeepaliveCache() {
        if (!keepaliveCacheFile.exists()) return;
        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(keepaliveCacheFile);
            int loaded = 0;
            for (String key : yml.getKeys(false)) {
                int cb = yml.getInt(key + ".cb", -1);
                int sb = yml.getInt(key + ".sb", -1);
                int sp = yml.getInt(key + ".sp", -1);
                if (cb > 0 && sb > 0) {
                    MinecraftProtocol.injectCache(key, cb, sb, sp > 0 ? sp : 0x3E);
                    loaded++;
                }
            }
            if (loaded > 0)
                getLogger().info("[KeepAliveCache] 已从磁盘加载 " + loaded + " 条记录，跳过探测阶段");
        } catch (Exception e) {
            getLogger().warning("[KeepAliveCache] 加载失败: " + e.getMessage());
        }
    }

    /** 由 MinecraftProtocol 探测成功后回调，将结果持久化到磁盘 */
    public void saveKeepaliveCache(String cacheKey, int cb, int sb, int sp) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                YamlConfiguration yml = keepaliveCacheFile.exists()
                        ? YamlConfiguration.loadConfiguration(keepaliveCacheFile)
                        : new YamlConfiguration();
                yml.set(cacheKey + ".cb", cb);
                yml.set(cacheKey + ".sb", sb);
                yml.set(cacheKey + ".sp", sp);
                yml.save(keepaliveCacheFile);
                getLogger().info("[KeepAliveCache] 已保存到磁盘: " + cacheKey
                        + " cb=0x" + Integer.toHexString(cb)
                        + " sb=0x" + Integer.toHexString(sb));
            } catch (Exception e) {
                getLogger().warning("[KeepAliveCache] 保存失败: " + e.getMessage());
            }
        });
    }

    public static RandomBotsPlugin getInstance() { return instance; }
    public BotManager getBotManager()            { return botManager; }
    public int        getServerPort()            { return serverPort; }
    public String     getServerHost()            { return getConfig().getString("server-host", "localhost"); }
}
