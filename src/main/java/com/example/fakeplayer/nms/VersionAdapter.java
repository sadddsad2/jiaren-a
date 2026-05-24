package com.example.fakeplayer.nms;

import com.mojang.authlib.GameProfile;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reflective NMS adapter that injects a fake ServerPlayer into Paper's PlayerList.
 *
 * Supports Paper 1.20.x and 1.21.x by detecting the actual class names at runtime.
 *
 * Class name mapping (Mojang-mapped, used by Paper since 1.20.5):
 *   MinecraftServer          → net.minecraft.server.MinecraftServer
 *   ServerLevel              → net.minecraft.server.level.ServerLevel
 *   ServerPlayer             → net.minecraft.server.level.ServerPlayer
 *   PlayerList               → net.minecraft.server.players.PlayerList
 *   CommonListenerCookie     → net.minecraft.server.network.CommonListenerCookie
 *   GameProfile (Authlib)    → com.mojang.authlib.GameProfile  (unchanged)
 *
 * Pre-1.20.5 Paper still uses the obfuscated names via CraftBukkit bridge, but
 * the reflection paths below handle both via a two-pass fallback.
 */
public class VersionAdapter {

    private static final Logger LOG = Logger.getLogger("FakePlayer/NMS");

    // Cached reflected members (initialised once in init())
    private static Class<?> clsMinecraftServer;
    private static Class<?> clsServerLevel;
    private static Class<?> clsServerPlayer;
    private static Class<?> clsPlayerList;
    private static Class<?> clsNetworkManager;      // may differ by version
    private static Class<?> clsCommonListenerCookie; // 1.20.5+
    private static Class<?> clsClientInformation;    // 1.20.5+

    private static Method methodGetServer;           // CraftServer#getServer()
    private static Method methodGetLevel;            // MinecraftServer#getLevel(ResourceKey)
    private static Method methodGetPlayerList;       // MinecraftServer#getPlayerList()
    private static Method methodPlaceNewPlayer;      // PlayerList#placeNewPlayer(...)
    private static Method methodRemove;              // PlayerList#remove(ServerPlayer)

    private static Constructor<?> ctorServerPlayer;
    private static Constructor<?> ctorNetworkManager;
    private static Constructor<?> ctorCommonListenerCookie;

    private static boolean initialised = false;
    private static boolean supported   = false;

    // ------------------------------------------------------------------ //

    public static boolean init() {
        if (initialised) return supported;
        initialised = true;
        try {
            resolve();
            supported = true;
            LOG.info("NMS reflection initialised successfully.");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to initialise NMS reflection – bots will not work: " + e.getMessage(), e);
        }
        return supported;
    }

    // ------------------------------------------------------------------ //
    //  Public: inject / eject
    // ------------------------------------------------------------------ //

    /**
     * Inject a fake player into the server's PlayerList.
     * Returns the Bukkit Player view, or null on failure.
     */
    public static Player injectFakePlayer(String name, UUID uuid, World world) {
        if (!supported) return null;
        try {
            return doInject(name, uuid, world);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to inject fake player '" + name + "': " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Remove / disconnect a fake player cleanly.
     */
    public static void ejectFakePlayer(Player player) {
        if (!supported || player == null) return;
        try {
            Object nmsPlayer = getNmsPlayer(player);
            methodRemove.invoke(getPlayerList(), nmsPlayer);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to eject fake player '" + player.getName() + "': " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ //
    //  Internal: reflection logic
    // ------------------------------------------------------------------ //

    private static void resolve() throws Exception {
        String cbPkg = Bukkit.getServer().getClass().getPackage().getName(); // e.g. org.bukkit.craftbukkit.v1_20_R3 or org.bukkit.craftbukkit

        // CraftServer → MinecraftServer
        Class<?> clsCraftServer = Bukkit.getServer().getClass();
        methodGetServer = clsCraftServer.getMethod("getServer");
        clsMinecraftServer = methodGetServer.getReturnType();

        // Resolve NMS classes – try Mojang-mapped names first (1.20.5+), then obfuscated
        clsServerPlayer  = resolveClass(
                "net.minecraft.server.level.ServerPlayer",
                cbPkg + ".entity.CraftPlayer"   // fallback placeholder; real class found via server
        );
        clsServerLevel   = resolveClass("net.minecraft.server.level.ServerLevel");
        clsPlayerList    = resolveClass("net.minecraft.server.players.PlayerList");

        // PlayerList#getPlayerList from MinecraftServer
        methodGetPlayerList = findMethod(clsMinecraftServer, clsPlayerList, "getPlayerList", "ah", "ao");

        // PlayerList#remove(ServerPlayer)
        methodRemove = clsPlayerList.getMethod("remove", clsServerPlayer);

        // Network manager / connection classes vary by version
        resolveNetworkClasses(cbPkg);

        // ServerPlayer constructor
        ctorServerPlayer = resolveServerPlayerConstructor();

        // MinecraftServer#getLevel
        methodGetLevel = findMethodByReturnType(clsMinecraftServer, clsServerLevel);

        // PlayerList#placeNewPlayer
        methodPlaceNewPlayer = resolveNewPlayerMethod();
    }

    private static Class<?> resolveClass(String... candidates) throws ClassNotFoundException {
        for (String name : candidates) {
            try { return Class.forName(name); } catch (ClassNotFoundException ignored) {}
        }
        throw new ClassNotFoundException("None of " + java.util.Arrays.toString(candidates));
    }

    private static void resolveNetworkClasses(String cbPkg) throws Exception {
        // CommonListenerCookie exists in 1.20.5+
        try {
            clsCommonListenerCookie = Class.forName("net.minecraft.server.network.CommonListenerCookie");
            clsClientInformation    = Class.forName("net.minecraft.server.level.ClientInformation");
            ctorCommonListenerCookie = clsCommonListenerCookie.getConstructors()[0];
        } catch (ClassNotFoundException e) {
            // Pre-1.20.5 – no CommonListenerCookie
            clsCommonListenerCookie = null;
        }

        // NetworkManager / Connection
        try {
            clsNetworkManager = Class.forName("net.minecraft.network.Connection");
        } catch (ClassNotFoundException e) {
            clsNetworkManager = Class.forName("net.minecraft.network.NetworkManager");
        }

        // Constructor: Connection(PacketFlow)
        ctorNetworkManager = clsNetworkManager.getDeclaredConstructors()[0];
        ctorNetworkManager.setAccessible(true);
    }

    private static Constructor<?> resolveServerPlayerConstructor() throws Exception {
        // Try every constructor; pick the one whose params include GameProfile.
        // If multiple constructors match (Paper 1.21.x has several), prefer the one
        // with the most parameters – it is the most complete and always the right one.
        Constructor<?> best = null;
        for (Constructor<?> c : clsServerPlayer.getDeclaredConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            for (Class<?> p : params) {
                if (p.equals(GameProfile.class)) {
                    if (best == null || c.getParameterCount() > best.getParameterCount()) {
                        best = c;
                    }
                    break;
                }
            }
        }
        if (best == null) throw new NoSuchMethodException("No ServerPlayer constructor with GameProfile found");
        best.setAccessible(true);
        return best;
    }

    private static Method resolveNewPlayerMethod() throws Exception {
        // Signature changed across versions:
        //   1.20.1-4:  placeNewPlayer(Connection, ServerPlayer)
        //   1.20.5+:   placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)
        for (Method m : clsPlayerList.getMethods()) {
            if (m.getName().equals("placeNewPlayer") || m.getName().equals("a")) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length >= 2 && params[params.length - 1].equals(clsServerPlayer)
                        || (params.length >= 2 && isAssignableFromServerPlayer(params))) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        // Brute-force: find by parameter count
        for (Method m : clsPlayerList.getDeclaredMethods()) {
            if (m.getParameterCount() == 2 || m.getParameterCount() == 3) {
                Class<?>[] params = m.getParameterTypes();
                boolean hasConn   = params[0].isAssignableFrom(clsNetworkManager) || clsNetworkManager.isAssignableFrom(params[0]);
                boolean hasPlayer = isAssignableFromServerPlayer(params);
                if (hasConn && hasPlayer) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NoSuchMethodException("placeNewPlayer not found in PlayerList");
    }

    private static boolean isAssignableFromServerPlayer(Class<?>[] params) {
        for (Class<?> p : params) {
            if (p.isAssignableFrom(clsServerPlayer) || clsServerPlayer.isAssignableFrom(p)) return true;
        }
        return false;
    }

    private static Method findMethod(Class<?> clazz, Class<?> returnType, String... names) throws NoSuchMethodException {
        for (Method m : clazz.getMethods()) {
            for (String name : names) {
                if (m.getName().equals(name) && m.getReturnType().equals(returnType)) return m;
            }
        }
        // fallback: search by return type only
        return findMethodByReturnType(clazz, returnType);
    }

    private static Method findMethodByReturnType(Class<?> clazz, Class<?> returnType) throws NoSuchMethodException {
        for (Method m : clazz.getMethods()) {
            if (m.getReturnType().equals(returnType)) return m;
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getReturnType().equals(returnType)) { m.setAccessible(true); return m; }
        }
        throw new NoSuchMethodException("No method returning " + returnType + " in " + clazz);
    }

    // ------------------------------------------------------------------ //
    //  Injection
    // ------------------------------------------------------------------ //

    private static Player doInject(String name, UUID uuid, World world) throws Exception {
        Object nmsServer  = methodGetServer.invoke(Bukkit.getServer());
        Object playerList = methodGetPlayerList.invoke(nmsServer);

        // Get ServerLevel for the target world
        Object serverLevel = getServerLevel(nmsServer, world);

        // Build GameProfile (offline – UUID derived from name, same as offline-mode servers)
        GameProfile profile = new GameProfile(uuid, name);

        // Build a dummy Connection (no real socket needed for NMS injection)
        Object connection = buildDummyConnection();

        // Build ServerPlayer – constructor signature varies by version
        Object serverPlayer = buildServerPlayer(nmsServer, serverLevel, profile);

        // Inject into PlayerList
        if (clsCommonListenerCookie != null) {
            // 1.20.5+: placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)
            Object cookie = buildCookie(profile);
            methodPlaceNewPlayer.invoke(playerList, connection, serverPlayer, cookie);
        } else {
            // 1.20.1-4: placeNewPlayer(Connection, ServerPlayer)
            methodPlaceNewPlayer.invoke(playerList, connection, serverPlayer);
        }

        // Return the Bukkit Player view
        return Bukkit.getPlayer(uuid);
    }

    private static Object getServerLevel(Object nmsServer, World world) throws Exception {
        // MinecraftServer#getLevel(ResourceKey<Level>) – find by iterating all levels
        // Easier: CraftWorld#getHandle()
        Class<?> clsCraftWorld = world.getClass();
        Method getHandle = clsCraftWorld.getMethod("getHandle");
        return getHandle.invoke(world);
    }

    private static Object buildDummyConnection() throws Exception {
        // Must use PacketFlow.SERVERBOUND — same as Carpet Mod's FakeClientConnection.
        // Connection(SERVERBOUND) sets sending=SERVERBOUND, receiving=CLIENTBOUND internally.
        //
        // Paper 1.21.x added Connection.validateListener() which checks that the listener's
        // flow() matches connection.getReceiving(). Since placeNewPlayer installs a SERVERBOUND
        // ServerGamePacketListenerImpl, and receiving=CLIENTBOUND, this would normally throw:
        //   "connection is CLIENTBOUND, but listener is SERVERBOUND"
        //
        // Carpet Mod's fix: subclass Connection and override setupInboundProtocol() to skip
        // validateListener(). We cannot subclass at compile time (no NMS on classpath), so we
        // instead patch the Connection instance via reflection AFTER placeNewPlayer calls
        // setupInboundProtocol — but that's too late. Instead we intercept the call by
        // temporarily replacing the connection's "receiving" field with SERVERBOUND so the
        // validation passes, then restore it. This is equivalent in effect.
        Class<?> clsPacketFlow = Class.forName("net.minecraft.network.protocol.PacketFlow");
        Object[] flowValues = clsPacketFlow.getEnumConstants();
        Object serverbound = null;
        for (Object v : flowValues) {
            if (v.toString().equalsIgnoreCase("SERVERBOUND")) { serverbound = v; break; }
        }
        if (serverbound == null) serverbound = flowValues[0];

        // Build Connection(SERVERBOUND)
        Object connection = ctorNetworkManager.newInstance(serverbound);

        // Patch: find the "receiving" field inside Connection and set it to SERVERBOUND so
        // validateListener() sees receiving==SERVERBOUND == listener.flow()==SERVERBOUND → passes.
        // Field is named "receiving" in Mojmap; search common obfuscated names as fallback.
        try {
            Field receivingField = findField(clsNetworkManager, "receiving", "h", "c", "d");
            receivingField.setAccessible(true);
            receivingField.set(connection, serverbound);
            LOG.fine("Patched Connection.receiving to SERVERBOUND for validateListener bypass.");
        } catch (Exception e) {
            LOG.warning("Could not patch Connection.receiving field — validateListener may fail: " + e.getMessage());
        }

        // Set a dummy channel so the server doesn't NPE on flush
        try {
            Field channelField = findField(clsNetworkManager, "channel", "k", "f");
            channelField.setAccessible(true);
            channelField.set(connection, new DummyChannel());
        } catch (Exception e) {
            LOG.fine("Could not set dummy channel: " + e.getMessage());
        }

        // Set remote address field
        try {
            Field addrField = findField(clsNetworkManager, "address", "m", "l");
            addrField.setAccessible(true);
            addrField.set(connection, new InetSocketAddress("127.0.0.1", 0));
        } catch (Exception ignored) {}

        return connection;
    }

    private static Object buildServerPlayer(Object nmsServer, Object serverLevel, GameProfile profile) throws Exception {
        // Constructor params differ by version; we detect by parameter count and types
        Class<?>[] params = ctorServerPlayer.getParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            // params[i].isAssignableFrom(X) means "can X be passed where params[i] is expected"
            if (params[i].isAssignableFrom(clsMinecraftServer)) {
                args[i] = nmsServer;
            } else if (params[i].isAssignableFrom(clsServerLevel)) {
                args[i] = serverLevel;
            } else if (params[i].equals(GameProfile.class)) {
                args[i] = profile;
            } else if (clsClientInformation != null && params[i].equals(clsClientInformation)) {
                // ClientInformation.createDefault() in 1.20.5+
                Method createDefault = clsClientInformation.getMethod("createDefault");
                args[i] = createDefault.invoke(null);
            } else {
                args[i] = null; // other params default to null
            }
        }

        // Safety check: MinecraftServer must never be null – a null here causes the exact
        // "server is null" NullPointerException seen in ServerPlayer.<init>
        for (int i = 0; i < params.length; i++) {
            if (params[i].isAssignableFrom(clsMinecraftServer) && args[i] == null) {
                throw new IllegalStateException(
                    "Could not map MinecraftServer to ServerPlayer constructor param [" + i + "]: " + params[i].getName());
            }
        }

        return ctorServerPlayer.newInstance(args);
    }

    private static Object buildCookie(GameProfile profile) throws Exception {
        // CommonListenerCookie constructor signature changed across versions:
        //   1.20.5-1.20.6: CommonListenerCookie(GameProfile, int latency, ClientInformation)
        //   1.21+:          CommonListenerCookie(GameProfile, int latency, ClientInformation, boolean transferred)
        //
        // The 'transferred' boolean (Paper 1.21+) indicates a cross-server transfer; always false for bots.
        // Passing null for any primitive type causes a NullPointerException on unboxing – every primitive
        // must be given an explicit zero-value instead.
        Class<?>[] params = ctorCommonListenerCookie.getParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            if (params[i].equals(GameProfile.class)) {
                args[i] = profile;
            } else if (clsClientInformation != null && params[i].equals(clsClientInformation)) {
                Method createDefault = clsClientInformation.getMethod("createDefault");
                args[i] = createDefault.invoke(null);
            } else if (params[i].equals(boolean.class) || params[i].equals(Boolean.class)) {
                args[i] = false;          // 'transferred' flag – always false for injected bots
            } else if (params[i].equals(int.class) || params[i].equals(Integer.class)) {
                args[i] = 0;             // latency
            } else if (params[i].equals(long.class) || params[i].equals(Long.class)) {
                args[i] = 0L;
            } else if (params[i].equals(double.class) || params[i].equals(Double.class)) {
                args[i] = 0.0;
            } else if (params[i].equals(float.class) || params[i].equals(Float.class)) {
                args[i] = 0.0f;
            } else if (params[i].equals(byte.class) || params[i].equals(Byte.class)) {
                args[i] = (byte) 0;
            } else if (params[i].equals(short.class) || params[i].equals(Short.class)) {
                args[i] = (short) 0;
            } else {
                args[i] = null;           // safe for any remaining reference types
            }
        }
        return ctorCommonListenerCookie.newInstance(args);
    }

    private static Object getPlayerList() throws Exception {
        Object nmsServer = methodGetServer.invoke(Bukkit.getServer());
        return methodGetPlayerList.invoke(nmsServer);
    }

    private static Object getNmsPlayer(Player player) throws Exception {
        Method getHandle = player.getClass().getMethod("getHandle");
        return getHandle.invoke(player);
    }

    private static Field findField(Class<?> clazz, String... names) throws NoSuchFieldException {
        for (String name : names) {
            try { return clazz.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
        }
        // search superclasses
        Class<?> sup = clazz.getSuperclass();
        if (sup != null) return findField(sup, names);
        throw new NoSuchFieldException("Field " + java.util.Arrays.toString(names) + " not found");
    }
}
