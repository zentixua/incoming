package ua.zentix.incoming.rig;

import io.netty.channel.ChannelFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.NeoForgeConfig;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Check servers (load stand, windowless multiplayer, scenario clients and combat client) listen only to the loop:
 * server from {@code online-mode=false} to 0.0.0.0:25565 are found by scanners from the Internet. The scripts write
 * {@code server-ip=127.0.0.1}, and this guard drops the server if the socket is still open not on a loop: the selected one is
 *  before loading the world ({@link ServerAboutToStartEvent} comes immediately after bind), the world opened later (LAN) - by tick.
 * Sockets - all {@link ServerConnectionListener} channels: TCP games and UDP Sable (its mixin puts its channel in the same
 *  list, at the same address). The dedicated server NeoForge also advertises itself in LAN ({@code advertiseDedicatedServerToLan}:
 * {@code LanServerPinger} with a UDP socket on 0.0.0.0, once in 1,5 s sends MOTD and port on 224.0.2.60:4445) - for servers
 *  checks this is turned off, turning it on also crashes the server. jar does not include mod (devtest).
 */
@Mod(Incoming.MOD_ID)
public final class LoopbackGuard {
    private static final Field CHANNELS = ObfuscationReflectionHelper.findField(ServerConnectionListener.class, "channels");
    private static final int PERIOD = 20;

    public LoopbackGuard() {
        NeoForge.EVENT_BUS.addListener(LoopbackGuard::onAboutToStart);
        NeoForge.EVENT_BUS.addListener(LoopbackGuard::onTick);
    }

    private static void onAboutToStart(ServerAboutToStartEvent e) {
        MinecraftServer server = e.getServer();
        String bad = publicAddress(server);
        if (bad == null && server.isDedicatedServer() && NeoForgeConfig.SERVER.advertiseDedicatedServerToLan.get()) {
            // server configs are already loaded (before the event), and the advertisement in LAN starts after it
            bad = "ad in LAN (advertiseDedicatedServerToLan = true in config/neoforge-server.toml)";
        }
        if (bad == null) return;
        Incoming.LOG.error("RIG the verification server is open to the network: {} - the server is stopped", bad);
        server.getConnection().stop();
        throw new IllegalStateException("the verification server is open to the network: " + bad);
    }

    private static void onTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        if (server.getTickCount() % PERIOD != 0) return;
        String bad = publicAddress(server);
        if (bad == null) return;
        Incoming.LOG.error("RIG the verification server is not listening to the loop: {} - the world is open to the network, the server is stopped", bad);
        server.getConnection().stop();
        server.halt(false);
    }

    /** The first address of the server's listening socket (TCP game, UDP Sable) that is not a loopback, or null. */
    @Nullable
    public static String publicAddress(MinecraftServer server) {
        ServerConnectionListener connection = server.getConnection();
        if (connection == null) return null;
        List<ChannelFuture> live = channels(connection);
        List<SocketAddress> addresses = new ArrayList<>();
        synchronized (live) {
            for (ChannelFuture f : live) addresses.add(f.channel().localAddress());
        }
        return firstPublic(addresses);
    }

    /** The first address that is not a loop (0.0.0.0 and :: too), or null; channel in memory (LocalAddress) is not a network. */
    @Nullable
    public static String firstPublic(List<SocketAddress> addresses) {
        for (SocketAddress address : addresses) {
            if (address instanceof InetSocketAddress inet && (inet.getAddress() == null || !inet.getAddress().isLoopbackAddress())) {
                return inet.toString();
            }
        }
        return null;
    }

    /** Live list of server channels (synchronized, like vanilla): puts your channel and Sable in it. */
    @SuppressWarnings("unchecked")
    public static List<ChannelFuture> channels(ServerConnectionListener connection) {
        try {
            return (List<ChannelFuture>) CHANNELS.get(connection);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
