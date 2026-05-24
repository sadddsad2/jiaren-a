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
 * A single bot that connects to the Minecraft server over TCP using the
 * Minecraft protocol, then performs random chat and movement actions.
 *
 * Connection flow:
 *   Handshake (state=2 login) → Login Start → Login Success → Play
 *
 * Note: For offline-mode servers (online-mode=false) no encryption is needed.
 *       For online-mode servers, proper authentication would be required.
 *       This plugin targets offline/LAN servers as implied by SERVER_PORT usage.
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
    private DataOutputStream out;
    private DataInputStream in;
    private volatile boolean connected = false;
    private volatile boolean alive = true;

    // Scheduler tasks
    private BukkitTask chatTask;
    private BukkitTask actionTask;
    private BukkitTask readTask;

    // Bot state
    private double x, y, z;
    private float yaw, pitch;
    private boolean onGround = true;

    public BotClient(RandomBotsPlugin plugin, BotManager manager,
                     String name, String host, int port) {
        this.plugin = plugin;
        this.manager = manager;
        this.name = name;
        this.host = host;
        this.port = port;
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
    }

    /**
     * Connect to the server and begin bot behavior.
     */
    public void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(30000);

        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

        // Perform Minecraft handshake + login
        MinecraftProtocol proto = new MinecraftProtocol(in, out);
        proto.sendHandshake(host, port);
        proto.sendLoginStart(name, uuid);

        // Wait for Login Success / Set Compression
        boolean loggedIn = proto.waitForLoginSuccess(name);
        if (!loggedIn) {
            socket.close();
            throw new IOException("Login failed for bot: " + name);
        }

        // Send Client Settings
        proto.sendClientSettings();

        connected = true;
        plugin.getLogger().info("Bot connected: " + name);

        // Start async packet reader
        startPacketReader(proto);

        // Schedule chat and action tasks on main thread (offloading I/O back to async)
        scheduleChatTask();
        scheduleActionTask();
    }

    /** Async loop: read incoming packets to keep connection alive (handle keepalive). */
    private void startPacketReader(MinecraftProtocol proto) {
        Thread readerThread = new Thread(() -> {
            try {
                while (connected && alive && !socket.isClosed()) {
                    proto.readAndHandlePacket(this);
                }
            } catch (Exception e) {
                if (connected) {
                    plugin.getLogger().info("Bot " + name + " disconnected: " + e.getMessage());
                }
            } finally {
                handleDisconnect();
            }
        }, "RandomBot-Reader-" + name);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Periodically send a random chat message. */
    private void scheduleChatTask() {
        int minSec = plugin.getConfig().getInt("chat-interval-min", 15);
        int maxSec = plugin.getConfig().getInt("chat-interval-max", 45);
        long delayTicks = (minSec + random.nextInt(maxSec - minSec + 1)) * 20L;

        chatTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!connected) return;
            sendChat();
            scheduleChatTask(); // reschedule with new random delay
        }, delayTicks);
    }

    /** Periodically perform a random movement action. */
    private void scheduleActionTask() {
        int minSec = plugin.getConfig().getInt("action-interval-min", 5);
        int maxSec = plugin.getConfig().getInt("action-interval-max", 15);
        long delayTicks = (minSec + random.nextInt(maxSec - minSec + 1)) * 20L;

        actionTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!connected) return;
            performRandomAction();
            scheduleActionTask();
        }, delayTicks);
    }

    /** Pick and send a random chat message from config. */
    private void sendChat() {
        List<String> messages = plugin.getConfig().getStringList("chat-messages");
        if (messages.isEmpty()) return;

        String msg = messages.get(random.nextInt(messages.size()));
        try {
            MinecraftProtocol proto = new MinecraftProtocol(in, out);
            proto.sendChatMessage(msg);
            plugin.getLogger().info("[Bot:" + name + "] says: " + msg);
        } catch (Exception e) {
            plugin.getLogger().fine("Bot " + name + " chat error: " + e.getMessage());
        }
    }

    /** Perform a random action: walk, look around, jump, sneak. */
    private void performRandomAction() {
        int action = random.nextInt(5);
        try {
            MinecraftProtocol proto = new MinecraftProtocol(in, out);
            switch (action) {
                case 0 -> {
                    // Walk a random direction
                    double dx = (random.nextDouble() - 0.5) * 4;
                    double dz = (random.nextDouble() - 0.5) * 4;
                    x += dx;
                    z += dz;
                    yaw = (float)(Math.toDegrees(Math.atan2(-dx, dz)));
                    proto.sendPositionAndLook(x, y, z, yaw, pitch, onGround);
                    plugin.getLogger().fine("[Bot:" + name + "] walking to " + String.format("%.1f,%.1f,%.1f", x, y, z));
                }
                case 1 -> {
                    // Look around
                    yaw   = random.nextFloat() * 360f - 180f;
                    pitch = random.nextFloat() * 60f - 30f;
                    proto.sendLook(yaw, pitch, onGround);
                    plugin.getLogger().fine("[Bot:" + name + "] looking yaw=" + String.format("%.1f", yaw));
                }
                case 2 -> {
                    // Jump (move up then back)
                    proto.sendPositionAndLook(x, y + 1.2, z, yaw, pitch, false);
                    Thread.sleep(300);
                    proto.sendPositionAndLook(x, y, z, yaw, pitch, true);
                    plugin.getLogger().fine("[Bot:" + name + "] jumped");
                }
                case 3 -> {
                    // Swing arm (attack animation)
                    proto.sendSwingArm();
                    plugin.getLogger().fine("[Bot:" + name + "] swung arm");
                }
                case 4 -> {
                    // Sneak toggle
                    proto.sendEntityAction(MinecraftProtocol.EntityAction.START_SNEAKING);
                    Thread.sleep(500);
                    proto.sendEntityAction(MinecraftProtocol.EntityAction.STOP_SNEAKING);
                    plugin.getLogger().fine("[Bot:" + name + "] sneaked");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Bot " + name + " action error: " + e.getMessage());
        }
    }

    /** Called when server sends a Respawn or Position packet — update bot coords. */
    public void updatePosition(double x, double y, double z, float yaw, float pitch) {
        this.x = x; this.y = y; this.z = z;
        this.yaw = yaw; this.pitch = pitch;
        // Confirm position to server
        try {
            MinecraftProtocol proto = new MinecraftProtocol(in, out);
            proto.sendPositionAndLook(x, y, z, yaw, pitch, true);
        } catch (Exception ignored) {}
    }

    /** Called when server sends a Keepalive — must respond or get kicked. */
    public void respondToKeepalive(long keepaliveId) {
        try {
            MinecraftProtocol proto = new MinecraftProtocol(in, out);
            proto.sendKeepalive(keepaliveId);
        } catch (Exception e) {
            plugin.getLogger().fine("Keepalive response failed for " + name);
        }
    }

    /** Handle graceful or forced disconnect. */
    public void handleDisconnect() {
        if (!alive) return; // already handled
        alive = false;
        connected = false;

        if (chatTask != null) chatTask.cancel();
        if (actionTask != null) actionTask.cancel();

        try { if (socket != null) socket.close(); } catch (Exception ignored) {}

        plugin.getLogger().info("Bot " + name + " disconnected/died.");
        manager.onBotDied(name);
    }

    public void disconnect(String reason) {
        plugin.getLogger().info("Disconnecting bot " + name + ": " + reason);
        alive = false;
        connected = false;
        if (chatTask != null) chatTask.cancel();
        if (actionTask != null) actionTask.cancel();
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        manager.removeBot(name);
    }

    public String getBotName() { return name; }
    public boolean isConnected() { return connected; }
}
