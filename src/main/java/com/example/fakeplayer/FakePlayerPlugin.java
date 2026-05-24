package com.example.fakeplayer;

import com.example.fakeplayer.bot.BotManager;
import com.example.fakeplayer.command.FakePlayerCommand;
import com.example.fakeplayer.listener.BotEventListener;
import com.example.fakeplayer.nms.VersionAdapter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class FakePlayerPlugin extends JavaPlugin {

    private static FakePlayerPlugin instance;
    private BotManager botManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Detect Paper version and initialise NMS reflection
        String version = getServer().getVersion();
        getLogger().info("Detected server version: " + version);

        if (!VersionAdapter.init()) {
            getLogger().severe("NMS reflection failed – disabling FakePlayer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        botManager = new BotManager(this);

        getServer().getPluginManager().registerEvents(
                new BotEventListener(this, botManager), this);

        Objects.requireNonNull(getCommand("fakeplayer"))
                .setExecutor(new FakePlayerCommand(this, botManager));

        // Spawn bots after the server is fully loaded (2 second delay)
        getServer().getScheduler().runTaskLater(this, botManager::spawnInitialBots, 40L);

        getLogger().info("FakePlayer enabled. Bots will spawn shortly.");
    }

    @Override
    public void onDisable() {
        if (botManager != null) botManager.removeAllBots();
        getLogger().info("FakePlayer disabled – all bots removed.");
    }

    public static FakePlayerPlugin getInstance() { return instance; }
    public BotManager getBotManager()            { return botManager; }
}
