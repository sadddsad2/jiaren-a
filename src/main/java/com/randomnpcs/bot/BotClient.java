package com.randomnpcs.bot;

import com.randomnpcs.RandomBotsPlugin;
import com.randomnpcs.net.MinecraftProtocol;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * One fake-player bot.
 *
 * TCP connection: handles ONLY KeepAlive + Confirm Teleport (passive).
 * Chat + actions: executed via Paper API on the server-side Player object.
 *
 * KeepAlive probe logic:
 *   When MinecraftProtocol detects the clientbound KeepAlive ID but hasn't
 *   found the serverbound ID yet, it probes by sending the KeepAlive payload
 *   with incrementing candidate IDs. If the server returns a decode error,
 *   the reader thread catches it, increments the probe candidate, and signals
 *   BotManager to reconnect this bot (not a full "death" — no respawn delay).
 */
public class BotClient {

    private final RandomBotsPlugin plugin;
    private final BotManager       manager;
    private final String           name;
    private final String           host;
    private final int              port;
    private final UUID             uuid;
    private final Random           random = new Random();

    private Socket            socket;
    private MinecraftProtocol proto;
    private volatile boolean  connected = false;
    private volatile boolean  alive     = true;

    // Set to true when a probe attempt failed — triggers silent reconnect
    // (no respawn delay, no "died" log) with the next candidate ID
    private volatile boolean probeFailedReconnect = false;

    private BukkitTask chatTask;
    private BukkitTask actionTask;

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

    // ═══════════════════════════════════════════════════════════════════════
    // Connect
    // ═══════════════════════════════════════════════════════════════════════

    public void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(60_000);

        DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream(), 8192));
        DataInputStream in = new DataInputStream(
                new BufferedInputStream(socket.getInputStream(), 8192));

        proto = new MinecraftProtocol(in, out, plugin.getLogger(), name, host, port);

        int detectedProto = MinecraftProtocol.queryProtocolVersion(host, port, plugin.getLogger());
        if (detectedProto > 0) {
            proto.setProtocolVersion(detectedProto);
        } else {
            plugin.getLogger().warning("[" + name + "] Could not detect protocol, using fallback");
        }

        proto.initiateLogin(host, port, name, uuid);

        if (!proto.completeLogin(name)) {
            socket.close();
            throw new IOException("Login rejected for: " + name);
        }

        connected = true;
        plugin.getLogger().info("Bot connected: " + name);

        startReaderThread();
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (connected && alive) {
                scheduleChatTask();
                scheduleActionTask();
            }
        }, 60L);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Reader thread
    // ═══════════════════════════════════════════════════════════════════════

    private void startReaderThread() {
        Thread t = new Thread(() -> {
            try {
                while (connected && alive && !socket.isClosed()) {
                    proto.readAndHandle(this);
                }
            } catch (IOException e) {
                if (!connected) return;
                String msg = e.getMessage() != null ? e.getMessage() : "";

                if (msg.startsWith("Kicked:")) {
                    plugin.getLogger().info("Bot " + name + " kicked: " + msg.substring(7).trim());

                } else if (isProbeDecodeError(msg)) {
                    // The current probe candidate was rejected by the server.
                    // Advance to the next candidate and reconnect silently.
                    int nextId = proto.nextProbeCandidate();
                    plugin.getLogger().info("[" + name + "] Probe 0x"
                            + Integer.toHexString(proto.getProbeCandidate() - 1)
                            + " rejected → trying 0x" + Integer.toHexString(nextId));
                    probeFailedReconnect = true;

                } else {
                    plugin.getLogger().info("Bot " + name + " disconnected: " + msg);
                }
            } finally {
                handleDisconnect();
            }
        }, "RandomBot-" + name);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Detect a server-side decode error caused by a wrong probe ID.
     * These look like:
     *   "Failed to decode packet 'serverbound/minecraft:...'"
     *   "Received unknown packet id NN"
     */
    private static boolean isProbeDecodeError(String msg) {
        return msg.contains("Failed to decode packet")
                || msg.contains("Received unknown packet id")
                || msg.contains("was larger than I expected");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Behaviours — via Paper API, not via TCP
    // ═══════════════════════════════════════════════════════════════════════

    private void scheduleChatTask() {
        int min = plugin.getConfig().getInt("chat-interval-min", 15);
        int max = plugin.getConfig().getInt("chat-interval-max", 45);
        long ticks = (min + random.nextInt(max - min + 1)) * 20L;
        chatTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!connected || !alive) return;
            sendChatViaApi();
            scheduleChatTask();
        }, ticks);
    }

    private void scheduleActionTask() {
        int min = plugin.getConfig().getInt("action-interval-min", 5);
        int max = plugin.getConfig().getInt("action-interval-max", 15);
        long ticks = (min + random.nextInt(max - min + 1)) * 20L;
        actionTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!connected || !alive) return;
            performActionViaApi();
            scheduleActionTask();
        }, ticks);
    }

    private void sendChatViaApi() {
        List<String> messages = plugin.getConfig().getStringList("chat-messages");
        if (messages.isEmpty()) return;
        String msg = messages.get(random.nextInt(messages.size()));
        Player player = Bukkit.getPlayerExact(name);
        if (player == null || !player.isOnline()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(name);
            if (p != null && p.isOnline()) {
                p.chat(msg);
                plugin.getLogger().info("[Bot:" + name + "] " + msg);
            }
        });
    }

    private void performActionViaApi() {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null || !player.isOnline()) return;
        int action = random.nextInt(4);
        switch (action) {
            case 0 -> {
                Location loc = player.getLocation();
                double dx = (random.nextDouble() - 0.5) * 3;
                double dz = (random.nextDouble() - 0.5) * 3;
                Location target = loc.clone().add(dx, 0, dz);
                target.setWorld(loc.getWorld());
                if (target.getWorld() != null && target.getChunk().isLoaded()) {
                    target.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    target.setPitch(0);
                    player.teleport(target);
                }
            }
            case 1 -> {
                Location loc = player.getLocation();
                loc.setYaw(random.nextFloat() * 360f - 180f);
                loc.setPitch(random.nextFloat() * 60f - 30f);
                player.teleport(loc);
            }
            case 2 -> player.swingMainHand();
            case 3 -> {
                player.setSneaking(!player.isSneaking());
                if (player.isSneaking()) {
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> { Player p = Bukkit.getPlayerExact(name);
                                    if (p != null) p.setSneaking(false); }, 20L);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Callbacks
    // ═══════════════════════════════════════════════════════════════════════

    public void updatePosition(double nx, double ny, double nz, float nyaw, float npitch) {
        this.x = nx; this.y = ny; this.z = nz;
        this.yaw = nyaw; this.pitch = npitch;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    public void handleDisconnect() {
        if (!alive) return;
        alive     = false;
        connected = false;
        cancelTasks();
        closeSocket();

        if (probeFailedReconnect) {
            // Silent reconnect — just re-queue the connect, no "died" message
            plugin.getLogger().info("Bot " + name + " reconnecting for probe...");
            probeFailedReconnect = false;
            manager.onBotProbeReconnect(name);
        } else {
            plugin.getLogger().info("Bot " + name + " disconnected — scheduling respawn.");
            manager.onBotDied(name);
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

    public String  getBotName()  { return name;      }
    public boolean isConnected() { return connected; }
}
