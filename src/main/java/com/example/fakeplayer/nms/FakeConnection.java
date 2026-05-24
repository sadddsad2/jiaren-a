package com.example.fakeplayer.nms;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A Connection subclass that bypasses Paper 1.21.x's strict listener-direction validation.
 *
 * In Paper 1.21.x, Connection.validateListener() throws an IllegalStateException when the
 * connection's "receiving" direction does not match the listener's flow(). Since Connection
 * is constructed with the SENDING direction, Connection(SERVERBOUND) means receiving=CLIENTBOUND,
 * which conflicts with the SERVERBOUND ServerGamePacketListenerImpl installed by placeNewPlayer.
 *
 * Carpet Mod solves this by subclassing Connection and overriding setupInboundProtocol() to
 * skip the validateListener() call entirely. We do the same here via reflection-compatible
 * runtime subclassing on the Paper server classpath.
 *
 * Usage: new FakeConnection(PacketFlow.SERVERBOUND)
 * Mirrors: carpet.patches.FakeClientConnection (Fabric Carpet Mod)
 */
public class FakeConnection extends Connection {

    public FakeConnection(PacketFlow flow) {
        super(flow);
    }

    /**
     * Override to skip validateListener() — the call that throws:
     *   "Trying to set listener for wrong side: connection is CLIENTBOUND, but listener is SERVERBOUND"
     *
     * We directly invoke the parent's field assignment path. Since we cannot call
     * super.setupInboundProtocol() without triggering the validation, we replicate
     * the one useful side-effect: storing the packet listener so the server does not NPE.
     */
    @Override
    public void setupInboundProtocol(ProtocolInfo<?> info, PacketListener listener) {
        // Intentionally skip validateListener() — direction mismatch is expected for fake players.
        // We must still store the listener so Connection.tick() and disconnect() work correctly.
        this.packetListener = listener;
    }
}
