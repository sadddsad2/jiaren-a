package com.example.fakeplayer.nms;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A Connection subclass that bypasses Paper 1.21.x's two-layer injection guard:
 *
 *  1. validateListener() — checks listener.flow() == connection.receiving.
 *     Would throw: "connection is CLIENTBOUND, but listener is SERVERBOUND".
 *
 *  2. syncAfterConfigurationChange() — calls channel.syncUninterruptibly() which
 *     blocks the main thread waiting for Netty I/O, causing a server deadlock with
 *     a dummy channel that has no real I/O thread.
 *
 * Solution: override setupInboundProtocol() to do nothing — exactly what
 * Carpet Mod's FakeClientConnection does. The packet listener will be set by
 * placeNewPlayer through the ServerGamePacketListenerImpl constructor anyway.
 *
 * Compiled with the paper mojang-mapped artifact (provided scope, not shaded).
 * At runtime the server classloader provides net.minecraft.network.Connection.
 */
public class FakeConnection extends Connection {

    public FakeConnection(PacketFlow flow) {
        super(flow);
    }

    /**
     * Intentional no-op.
     *
     * The real implementation:
     *   1. validateListener()        — direction check we must skip
     *   2. syncAfterConfigurationChange() — channel.sync() that deadlocks main thread
     *   3. this.packetListener = listener — the only thing we actually need
     *
     * placeNewPlayer creates the ServerGamePacketListenerImpl and passes it here.
     * We skip steps 1 and 2; the listener assignment happens inside
     * ServerGamePacketListenerImpl's own constructor which stores itself on the
     * Connection via connection.setPacketListener(), so we do not need to do it here.
     */
    @Override
    public void setupInboundProtocol(ProtocolInfo<?> info, PacketListener listener) {
        // Intentionally empty — skip validateListener() and syncAfterConfigurationChange()
    }
}
