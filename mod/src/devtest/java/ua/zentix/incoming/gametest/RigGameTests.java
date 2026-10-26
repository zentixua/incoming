package ua.zentix.incoming.gametest;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.rig.LoopbackGuard;
import ua.zentix.incoming.scenario.ScenarioCommands;
import ua.zentix.incoming.scenario.StrikeWatch;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Test stand and servers (devtest). */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RigGameTests {
    private RigGameTests() {}

    /**
     * The loop guard sees the UDP channel Sable: the Sable mixin puts its channel (Netty, datagrams) in the same channel list of the
     *  server as the TCP game, at the same address. Here such a channel is lifted on a loop and placed in the server channels
     * GameTest, as Sable does - the watchman is silent; then a datagram channel is placed nearby, which reports the address
     * 0.0.0.0 or :: (stub: the test should not open such a socket), - the guard calls it.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "rig_loopback")
    public static void loopbackGuardSeesUdpChannel(GameTestHelper h) throws UnknownHostException {
        MinecraftServer server = h.getLevel().getServer();
        List<ChannelFuture> live = LoopbackGuard.channels(server.getConnection());
        ChannelFuture udp = new Bootstrap().channel(NioDatagramChannel.class).group(ServerConnectionListener.SERVER_EVENT_GROUP.get())
                .handler(new ChannelInboundHandlerAdapter()).localAddress(InetAddress.getLoopbackAddress(), 0).bind().syncUninterruptibly();
        try {
            live.add(udp);
            SocketAddress bound = udp.channel().localAddress();
            h.assertTrue(bound instanceof InetSocketAddress, "non-network channel address UDP: " + bound);
            h.assertTrue(LoopbackGuard.publicAddress(server) == null, "the watchman raised the alarm on the loop: " + LoopbackGuard.publicAddress(server));
            int port = ((InetSocketAddress) bound).getPort();
            for (byte[] any : List.of(new byte[4], new byte[16])) {
                InetSocketAddress wide = new InetSocketAddress(InetAddress.getByAddress(any), port);
                ChannelFuture open = stub(DatagramChannel.class, wide);
                live.add(open);
                try {
                    h.assertTrue(LoopbackGuard.publicAddress(server) != null, "the watchman did not notice UDP on " + wide);
                } finally {
                    live.remove(open);
                }
            }
        } finally {
            live.remove(udp);
            udp.channel().close().syncUninterruptibly();
        }
        h.succeed();
    }

    /** {@code commands} script commands: ";" in the NBT array and in quotes - part of the command, empty items are discarded. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "rig_tools")
    public static void scenarioCommandsSplitTopLevel(GameTestHelper h) {
        String cow = "summon cow -272 110 -1142 {NoAI:1b,UUID:[I;7001,7002,7003,7004],Tags:[\"drone_target\"]}";
        List<String> got = ScenarioCommands.split("gamemode spectator; " + cow + ";wait:200;;say \"a;b\";tp @s 0 100 0 ;");
        List<String> want = List.of("gamemode spectator", cow, "wait:200", "say \"a;b\"", "tp @s 0 100 0");
        h.assertTrue(got.equals(want), "parsing: " + got);
        h.assertTrue(ScenarioCommands.split(" ; ").isEmpty(), "empty items are not discarded");
        String quoted = "summon armor_stand 0 64 0 {CustomName:'a;b',Tags:['x;y']}";
        List<String> mixed = ScenarioCommands.split("say it's here; " + quoted + "; time set day");
        h.assertTrue(mixed.equals(List.of("say it's here", quoted, "time set day")), "apostrophe and lines SNBT: " + mixed);
        h.succeed();
    }

    /**
     * Impact of the scenario {@code fx}: the projectile is the only new UUID after the command, the impact is the first explosion with it at the source.
     * Another's explosion, an explosion without a projectile and the second explosion of one's own projectile (secondary explosion) are not considered a blow; without
     *  an unambiguous launch (there are no new ones or two) nothing is considered.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "rig_tools")
    public static void strikeWatchCountsOwnBlastOnly(GameTestHelper h) {
        UUID old = new UUID(1, 1), mine = new UUID(2, 2), other = new UUID(3, 3);
        Vec3 at = new Vec3(10, 64, 10);
        StrikeWatch w = new StrikeWatch();
        h.assertTrue(w.launched(Set.of(old), Set.of(old, mine)), "launch not found");
        h.assertTrue(mine.equals(w.projectile()), "wrong shell: " + w.projectile());
        h.assertFalse(w.onBlast(other, at, 5, 105), "someone else's explosion counts");
        h.assertFalse(w.onBlast(null, at, 6, 106), "explosion without a projectile is counted");
        h.assertTrue(w.impactTick() < 0, "blow before it explodes");
        h.assertTrue(w.onBlast(mine, at, 7, 107), "your explosion is not counted");
        h.assertFalse(w.onBlast(mine, at.add(3, 0, 0), 9, 109), "secondary explosion is counted as a blow");
        h.assertTrue(w.impactTick() == 7 && w.impactGameTime() == 107 && at.equals(w.impactAt()),
                "blow: " + w.impactTick() + " (server " + w.impactGameTime() + ") y " + w.impactAt());
        StrikeWatch two = new StrikeWatch();
        h.assertFalse(two.launched(Set.of(), Set.of(mine, other)), "two new shells - launch counted");
        h.assertFalse(two.onBlast(mine, at, 1, 101), "without launch the hit is counted");
        StrikeWatch none = new StrikeWatch();
        h.assertFalse(none.launched(Set.of(old), Set.of(old)), "there is no new projectile - launch is counted");
        h.succeed();
    }

    /**
     * Channel (stub: only {@code localAddress}), which is already “listening” on {@code address}, as the guard
     *  sees it in the list of server channels. The real socket does not open.
     */
    private static ChannelFuture stub(Class<? extends Channel> type, InetSocketAddress address) {
        Channel channel = type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, m, args) -> switch (m.getName()) {
            case "localAddress" -> address;
            case "toString" -> "stub " + type.getSimpleName() + " " + address;
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            default -> throw new UnsupportedOperationException(m.getName());
        }));
        return (ChannelFuture) Proxy.newProxyInstance(ChannelFuture.class.getClassLoader(), new Class<?>[]{ChannelFuture.class}, (self, m, args) -> switch (m.getName()) {
            case "channel" -> channel;
            case "isDone", "isSuccess" -> true;
            case "toString" -> "stub " + channel;
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }
}
