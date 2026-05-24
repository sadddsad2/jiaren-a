package com.randomnpcs.bot;

import com.randomnpcs.RandomBotsPlugin;
import com.randomnpcs.net.MinecraftProtocol;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/**
 * One fake-player bot.
 * A single MinecraftProtocol instance is shared for the entire connection
 * so compression state is not lost between sends.
 */
public class BotClient {

    private final RandomBotsPlugin plugin;
    private final BotManager manager;
    private final String name;
    private final String host;
    private final int port;
    private final UUID uuid;
    private final Random random = new Random();

    private Socket socket;
    private MinecraftProtocol proto; // one instance, lives as long as the socket
    private volatile boolean connected = false;
    private volatile boolean alive     = true;

    private BukkitTask chatTask;
    private BukkitTask actionTask;

    // Current position (updated by server's synchronize-position packets)
    private double x, y, z;
    private float  yaw, pitch;

    public BotClient(RandomBotsPlugin plugin, BotManager manager,
                     String name, String host, int port) {
        this.plugin  = plugin;
        this.manager = manager;
        this.name    = name;
        this.host    = host;
        this.port    = port;
        this.uuid    = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
    }

    /** Blocking connect + login. Throws on any failure so BotManager can log the real cause. */
    public void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(60_000); // 60 s read timeout

        DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream(), 8192));
        DataInputStream in = new DataInputStream(
                new BufferedInputStream(socket.getInputStream(), 8192));

        // One proto instance shared for the whole connection
        proto = new MinecraftProtocol(in, out, plugin.getLogger());

        // Auto-detect the server's protocol version before logging in
        int detectedProto = MinecraftProtocol.queryProtocolVersion(host, port, plugin.getLogger());
        if (detectedProto > 0) {
            proto.setProtocolVersion(detectedProto);
        } else {
            plugin.getLogger().warning("[" + name + "] Could not detect server protocol, using fallback 769");
        }

        proto.initiateLogin(host, port, name, uuid);

        boolean ok = proto.completeLogin(name);
        if (!ok) {
            socket.close();
            throw new IOException("Login rejected (online-mode or banned) for: " + name);
        }

        connected = true;
        plugin.getLogger().info("Bot connected: " + name);

        startReaderThread();
        scheduleChatTask();
        scheduleActionTask();
    }

    // ── Async reader ────────────────────────────────────────────────────

    private void startReaderThread() {
        Thread t = new Thread(() -> {
            try {
                while (connected && alive && !socket.isClosed()) {
                    proto.readAndHandle(this);
                }
            } catch (Exception e) {
                if (connected) {
                    plugin.getLogger().info("Bot " + name + " read error: " + e.getMessage());
                }
            } finally {
                handleDisconnect();
            }
        }, "RandomBot-" + name);
        t.setDaemon(true);
        t.start();
    }

    // ── Scheduled behaviours ────────────────────────────────────────────

    private void scheduleChatTask() {
        int min = plugin.getConfig().getInt("chat-interval-min", 15);
        int max = plugin.getConfig().getInt("chat-interval-max", 45);
        long ticks = (min + random.nextInt(max - min + 1)) * 20L;

        chatTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!connected || !alive) return;
            sendRandomChat();
            scheduleChatTask();
        }, ticks);
    }

    private void scheduleActionTask() {
        int min = plugin.getConfig().getInt("action-interval-min", 5);
        int max = plugin.getConfig().getInt("action-interval-max", 15);
        long ticks = (min + random.nextInt(max - min + 1)) * 20L;

        actionTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!connected || !alive) return;
            performRandomAction();
            scheduleActionTask();
        }, ticks);
    }

    private void sendRandomChat() {
        List<String> messages = plugin.getConfig().getStringList("chat-messages");
        if (messages.isEmpty()) return;
        String msg = messages.get(random.nextInt(messages.size()));
        try {
            proto.sendChatMessage(msg);
            plugin.getLogger().info("[Bot:" + name + "] " + msg);
        } catch (Exception e) {
            plugin.getLogger().fine("[Bot:" + name + "] chat error: " + e.getMessage());
        }
    }

    private void performRandomAction() {
        int action = random.nextInt(5);
        try {
            switch (action) {
                case 0 -> { // Walk
                    double dx = (random.nextDouble() - 0.5) * 4;
                    double dz = (random.nextDouble() - 0.5) * 4;
                    x += dx; z += dz;
                    yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    proto.sendPositionAndLook(x, y, z, yaw, pitch, true);
                }
                case 1 -> { // Look around
                    yaw   = random.nextFloat() * 360f - 180f;
                    pitch = random.nextFloat() * 60f  - 30f;
                    proto.sendLook(yaw, pitch, true);
                }
                case 2 -> { // Jump
                    proto.sendPositionAndLook(x, y + 0.5, z, yaw, pitch, false);
                    Thread.sleep(200);
                    proto.sendPositionAndLook(x, y, z, yaw, pitch, true);
                }
                case 3 -> { // Swing arm
                    proto.sendSwingArm();
                }
                case 4 -> { // Sneak
                    proto.sendEntityAction(MinecraftProtocol.EntityAction.START_SNEAKING);
                    Thread.sleep(600);
                    proto.sendEntityAction(MinecraftProtocol.EntityAction.STOP_SNEAKING);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("[Bot:" + name + "] action error: " + e.getMessage());
        }
    }

    // ── Callbacks from proto ────────────────────────────────────────────

    /** Called when server synchronizes the bot's position. We must confirm it. */
    public void updatePosition(double nx, double ny, double nz, float ny2, float np) {
        this.x = nx; this.y = ny; this.z = nz;
        this.yaw = ny2; this.pitch = np;
        try {
            proto.sendPositionAndLook(x, y, z, yaw, pitch, true);
        } catch (Exception ignored) {}
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    public void handleDisconnect() {
        if (!alive) return;
        alive     = false;
        connected = false;
        cancelTasks();
        closeSocket();
        plugin.getLogger().info("Bot " + name + " disconnected — scheduling respawn.");
        manager.onBotDied(name);
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

    public String getBotName()   { return name;      }
    public boolean isConnected() { return connected; }
}
