package com.randomnpcs.bot;

import com.randomnpcs.RandomBotsPlugin;
import com.randomnpcs.net.MinecraftProtocol;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个假人客户端实例
 */
public class BotClient {

    private final RandomBotsPlugin plugin;
    private final BotManager       manager;
    private final String           name;
    private final String           host;
    private final int              port;
    private final UUID             uuid;

    private Socket            socket;
    private MinecraftProtocol proto;
    private volatile boolean  connected = false;
    private volatile boolean  alive     = true;

    private volatile boolean probeFailedReconnect = false;
    private final AtomicBoolean loginNotified = new AtomicBoolean(false);

    private BukkitTask chatTask;
    private BukkitTask actionTask;

    public BotClient(RandomBotsPlugin plugin, BotManager manager, String name, String host, int port) {
        this.plugin  = plugin;
        this.manager = manager;
        this.name    = name;
        this.host    = host;
        this.port    = port;
        this.uuid    = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void connect() throws Exception {
        this.socket = new Socket(host, port);
        this.socket.setTcpNoDelay(true);

        BufferedInputStream  bin = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream bou = new BufferedOutputStream(socket.getOutputStream());

        this.proto = new MinecraftProtocol(plugin, this, bin, bou);
        
        // 阻塞执行 Handshake, Login 阶段，直至 completeLogin() 握手完毕
        this.proto.loginAndConfig(host, port, name, uuid);

        connected = true;
        // 开启数据接收监听线程
        startReaderThread();
    }

    private void startReaderThread() {
        Thread thread = new Thread(() -> {
            try {
                // 持续挂载接收 Play 包，内部处理 KeepAlive
                proto.readLoop();
            } catch (Exception e) {
                if (alive && connected) {
                    plugin.getLogger().fine("[" + name + "] 协议流断开: " + e.getMessage());
                }
            } finally {
                handleDisconnect();
            }
        }, "BotReader-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 核心逻辑：被 MinecraftProtocol 内部在确认成功接收到 Play 关键包，
     * 且确定 ID 正确或探测值被正确记录之后调用。
     */
    public void onProtocolLoginVerified() {
        if (loginNotified.compareAndSet(false, true)) {
            plugin.getLogger().info("[" + name + "] 通过服务器 Play 状态安全验证。正在激活行为调度。");

            // 激活游戏内高层 API 定时聊天与动作
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                if (connected && alive) {
                    scheduleChatTask();
                    scheduleActionTask();
                }
            }, 60L);

            // 核心放行通知：通知 Manager 可以允许下一个假人进来了
            manager.onBotLoginSuccess(name);
        }
    }

    public void handleDisconnect() {
        if (!alive) return;
        alive     = false;
        connected = false;
        cancelTasks();
        closeSocket();

        if (probeFailedReconnect) {
            plugin.getLogger().info("[" + name + "] 准备进行下一轮探针重连尝试...");
            probeFailedReconnect = false;
            manager.onBotProbeReconnect(name);
        } else {
            // 防御拦截：如果到断开为止，该假人仍未通过 Verified 验证（如因 ID 猜错或握手被拒绝）
            if (!loginNotified.get()) {
                manager.onBotLoginFailed(name);
            } else {
                plugin.getLogger().info("[" + name + "] 正常游戏中掉线。");
                manager.onBotDied(name);
            }
        }
    }

    public void disconnect(String reason) {
        alive     = false;
        connected = false;
        cancelTasks();
        closeSocket();
        manager.removeBot(name);
    }

    private void cancelTasks() {
        if (chatTask   != null) { chatTask.cancel();   chatTask   = null; }
        if (actionTask != null) { actionTask.cancel(); actionTask = null; }
    }

    private void closeSocket() {
        try { if (socket != null && !socket.isClosed()) socket.close(); }
        catch (Exception ignored) {}
    }

    private void scheduleChatTask() {
        // 游戏层逻辑，保持你的原有实现即可
    }

    private void scheduleActionTask() {
        // 游戏层逻辑，保持你的原有实现即可
    }

    public void setProbeFailedReconnect(boolean val) { this.probeFailedReconnect = val; }
    public String getBotName() { return name; }
    public UUID getBotUUID() { return uuid; }
    public boolean isConnected() { return connected; }
}
