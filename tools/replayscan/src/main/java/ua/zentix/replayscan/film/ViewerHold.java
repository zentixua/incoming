package ua.zentix.replayscan.film;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import org.jetbrains.annotations.Nullable;

import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Replay-viewer packets after login wait until client has a world. Client network thread decodes on
 * arrival (even in-memory connections encode, {@code Connection.configureInMemoryPipeline}); client
 * thread creates world when it reaches login ({@code ClientPacketListener.handleLogin}). Same-process
 * replay server sends immediately afterward, before world creation; mod decoders reading client world
 * (Point Blank other-player held-weapon state, {@code ClientUtil.getClientLevel()} in {@code decode})
 * crash and disconnect. Hold all server-to-viewer post-login packets in that server channel, in order,
 * until client completes login on the same connection: client player belongs to it, world is its world.
 * Hold/release state is separate per connection.
 */
final class ViewerHold extends ChannelOutboundHandlerAdapter {
    private static final String NAME = "replayscan_film_viewer_hold";
    /** Holds whose clients have not completed login. */
    private static final Queue<ViewerHold> waiting = new ConcurrentLinkedQueue<>();
    /** Last released client address; distinct per in-memory connection. */
    private static volatile SocketAddress released;

    private record Pending(Object msg, ChannelPromise promise) {}

    // held, sent, mods are channel-thread only
    private final ArrayDeque<Pending> held = new ArrayDeque<>();
    private volatile ChannelHandlerContext ctx;
    private volatile boolean login;
    private boolean sent;
    private int mods;

    /**
     * Install holds on every connection configured by this server process (filming has only replay viewers),
     * release by client tick.
     */
    static void register(IEventBus modBus) {
        modBus.addListener(RegisterConfigurationTasksEvent.class, e -> install(e.getListener().getConnection()));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> releaseReady());
    }

    /** Replay-server viewer connection during setup, before login packet. */
    private static void install(Connection connection) {
        ChannelPipeline pipeline = connection.channel().pipeline();
        if (pipeline.get(NAME) != null) {
            // Same connection configured again before release: existing hold remains responsible
            Film.LOG.warn("Film: the replay viewer's connection is configured again before its held packets were sent");
            return;
        }
        ViewerHold hold = new ViewerHold();
        pipeline.addBefore(HandlerNames.PACKET_HANDLER, NAME, hold);
        waiting.add(hold);
    }

    /** Client completed login on held connection (its player uses that connection); release queued packets. */
    private static void releaseReady() {
        if (waiting.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener listener = mc.getConnection();
        if (mc.level == null || listener == null) return;
        SocketAddress client = listener.getConnection().channel().localAddress();
        for (ViewerHold hold : waiting) {
            ChannelHandlerContext c = hold.ctx;
            if (!hold.login || c == null || !client.equals(c.channel().remoteAddress())) continue;
            waiting.remove(hold);
            released = client;
            c.executor().execute(hold::drain);
        }
    }

    /** Client connection hold released; post-login packets can flow. */
    static boolean releasedFor(@Nullable ClientPacketListener listener) {
        return listener != null && listener.getConnection().channel().localAddress().equals(released);
    }

    /** Reason hold remains, for log when replay failed to open. */
    static String state() {
        if (waiting.isEmpty()) return "no holds";
        return waiting.stream().map(h -> h.login ? "logged in, client not on this connection" : "no login packet yet")
                .toList().toString();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (sent || !login) {
            if (msg instanceof ClientboundLoginPacket) login = true;
            ctx.write(msg, promise);
            return;
        }
        if (msg instanceof ClientboundCustomPayloadPacket) mods++;
        held.add(new Pending(msg, promise));
    }

    /**
     * Removed from channel: empty after release; if channel closes before login, queued packets remain
     * unsent. Do not complete promises: each {@code Connection} would log an error for an already-gone channel.
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        waiting.remove(this);
        for (Pending p; (p = held.poll()) != null; ) ReferenceCountUtil.release(p.msg);
    }

    private void drain() {
        if (ctx.isRemoved()) return;
        sent = true;
        int n = held.size();
        for (Pending p; (p = held.poll()) != null; ) ctx.write(p.msg, p.promise);
        ctx.flush();
        ctx.pipeline().remove(this);
        Film.LOG.info("Film: replay viewer got {} packets held after login ({} mod payloads) once the client had its level", n, mods);
    }
}
