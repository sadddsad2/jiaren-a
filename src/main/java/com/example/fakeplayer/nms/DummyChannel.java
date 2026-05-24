package com.example.fakeplayer.nms;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Minimal Netty Channel stub used as the backing channel for the fake player's
 * Connection object. All operations are no-ops; the channel reports itself as
 * active and open so Paper doesn't immediately disconnect the bot.
 */
public class DummyChannel extends AbstractChannel {

    private static final DummyEventLoop EVENT_LOOP = new DummyEventLoop();
    private final DummyChannelConfig config = new DummyChannelConfig(this);
    private boolean open = true;

    public DummyChannel() {
        super(null);
    }

    @Override protected AbstractUnsafe newUnsafe() { return new AbstractUnsafe() {
        @Override public void connect(SocketAddress remote, SocketAddress local, ChannelPromise p) { p.setSuccess(); }
    }; }

    @Override protected boolean isCompatible(EventLoop loop) { return true; }
    @Override protected SocketAddress localAddress0()  { return new InetSocketAddress("127.0.0.1", 0); }
    @Override protected SocketAddress remoteAddress0() { return new InetSocketAddress("127.0.0.1", 0); }
    @Override protected void doBind(SocketAddress addr) {}
    @Override protected void doDisconnect() { open = false; }
    @Override protected void doClose()      { open = false; }
    @Override protected void doBeginRead()  {}
    @Override protected void doWrite(ChannelOutboundBuffer buf) { /* no-op for dummy channel */ }
    @Override public boolean isOpen()   { return open; }
    @Override public boolean isActive() { return open; }
    @Override public ChannelConfig config() { return config; }
    @Override public EventLoop eventLoop() { return EVENT_LOOP; }
    @Override public ChannelMetadata metadata() { return new ChannelMetadata(false); }

    // -- Simple config --
    private static class DummyChannelConfig extends DefaultChannelConfig {
        DummyChannelConfig(Channel channel) { super(channel); }
    }

    // -- Minimal single-threaded event loop --
    private static class DummyEventLoop extends io.netty.channel.SingleThreadEventLoop {
        DummyEventLoop() {
            super(null, java.util.concurrent.Executors.defaultThreadFactory(), true);
        }
        @Override protected void run() {
            while (!confirmShutdown()) {
                Runnable task = takeTask();
                if (task != null) task.run();
            }
        }
        @Override public ChannelFuture register(Channel channel) {
            return register(new DefaultChannelPromise(channel, this));
        }
        @Override public ChannelFuture register(ChannelPromise promise) {
            promise.setSuccess();
            return promise;
        }
    }
}
