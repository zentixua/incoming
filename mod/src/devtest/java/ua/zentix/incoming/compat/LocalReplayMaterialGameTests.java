package ua.zentix.incoming.compat;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.local.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.material.DustGrid;
import ua.zentix.incoming.nuclear.material.MaterialBirth;
import ua.zentix.incoming.nuclear.material.MaterialInbox;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LocalReplayMaterialGameTests {
    private LocalReplayMaterialGameTests() {}

    @GameTest(template = "range", batch = "nuke_material_local_seek", timeoutTicks = 200)
    public static void archivedBirthsAndPalettesAreFilteredOnThePlayConnection(GameTestHelper h) {
        var info = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(h.getLevel().registryAccess(), ConnectionType.NEOFORGE));
        var loop = new DefaultEventLoopGroup(1);
        var server = new Connection(PacketFlow.SERVERBOUND);
        var listener = new ServerBootstrap().group(loop).channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override protected void initChannel(LocalChannel ch) {
                        Connection.configureInMemoryPipeline(ch.pipeline(), PacketFlow.SERVERBOUND);
                        server.configurePacketHandler(ch.pipeline());
                    }
                }).bind(LocalAddress.ANY).syncUninterruptibly().channel();
        var received = new ConcurrentLinkedQueue<CustomPacketPayload>();
        var receivedBytes = new java.util.concurrent.atomic.AtomicLong();
        var client = new Bootstrap().group(loop).channel(LocalChannel.class).handler(new ChannelInitializer<LocalChannel>() {
            @Override protected void initChannel(LocalChannel ch) {
                ch.pipeline().addLast(new CompressionDecoder(256, true), new SimpleChannelInboundHandler<ByteBuf>() {
                    @Override protected void channelRead0(ChannelHandlerContext ctx, ByteBuf in) {
                        receivedBytes.addAndGet(in.readableBytes() + net.minecraft.network.VarInt.getByteSize(in.readableBytes()));
                        received.add(((ClientboundCustomPayloadPacket) info.codec().decode(in)).payload());
                    }
                });
            }
        }).connect(listener.localAddress()).syncUninterruptibly().channel();
        StrikeGameTests.afterTest(h, () -> {
            client.close();
            listener.close();
            loop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        });
        loop.submit(() -> {}).syncUninterruptibly();
        net.neoforged.neoforge.network.registration.ChannelAttributes.setConnectionType(server, ConnectionType.NEOFORGE);
        net.neoforged.neoforge.network.registration.ChannelAttributes.setPayloadSetup(server,
                net.neoforged.neoforge.network.registration.NetworkPayloadSetup.empty());
        server.setupOutboundProtocol(info);
        server.setupCompression(256, true);
        ReplayMaterialDelivery.attach(server, Vec3.ZERO);
        var names = server.channel().pipeline().names();
        h.assertTrue(names.indexOf("compress") < names.indexOf("incoming_replay_material")
                && names.indexOf("incoming_replay_material") < names.indexOf("encoder"), "play pipeline " + names);
        var ids = IntStream.rangeClosed(1, 17).mapToObj(i -> new UUID(0, i)).toList();
        double height = ua.zentix.incoming.nuclear.model.Yield.optimalBurstHeight(1000);
        var base = new Detonation(0, new Vec3(.5, 64 + height, .5), 64, 1000, false, 0, 0, 0, 20000, 0, 1f, false, .35f);
        var bounds = DustGrid.Bounds.of(base);
        byte[] literal = new byte[27 + (new DustGrid(bounds).count() + 3) / 4];
        new java.util.Random(743).nextBytes(literal);
        java.nio.ByteBuffer.wrap(literal).put((byte) 1).putInt(0).putInt(0).putInt(bounds.dense()).putInt(bounds.reach())
                .putLong(20).put((byte) 0).put((byte) 0);
        // One replay snapshot contains stale geometry as well as material. Its manifest comes last.
        server.channel().eventLoop().submit(() -> {
            // A second seek starts with membership from the previous playhead position.
            server.channel().write(new ClientboundCustomPayloadPacket(new S2C.NukeMaterialSources(
                    IntStream.rangeClosed(1, 40).mapToObj(i -> new UUID(0, i)).toList())));
            var all = new java.util.ArrayList<Detonation>();
            var refs = new java.util.HashMap<Integer, UUID>();
            for (int i = 40; i >= 1; i--) {
                var id = new UUID(0, i);
                var d = new Detonation(i, new Vec3(256 * i + .5, 64 + height, .5), 64, 1000, false, 0, 0, 0, 20000, i, 1f, false, .35f);
                all.add(d);
                refs.put(i, id);
                var bytes = literal.clone();
                java.nio.ByteBuffer.wrap(bytes).putInt(1, 256 * i);
                server.channel().write(new ClientboundCustomPayloadPacket(new S2C.NukeDetonation(d, 0, 0, id, (byte) 2)));
                for (var part : MaterialBirth.Encoded.of(id, bytes).packets())
                    server.channel().write(new ClientboundCustomPayloadPacket(part));
            }
            server.channel().write(new ClientboundCustomPayloadPacket(new S2C.NukeSync(all, List.of(), refs)));
            server.channel().write(new ClientboundCustomPayloadPacket(new S2C.NukeMaterialSources(ids)));
            server.channel().writeAndFlush(new ClientboundCustomPayloadPacket(new S2C.ReplayTick(1200, true)));
        }).syncUninterruptibly();
        StrikeGameTests.gameSpeed(h);
        h.succeedWhen(() -> {
            List<S2C.NukeMaterial> parts = received.stream().filter(S2C.NukeMaterial.class::isInstance).map(S2C.NukeMaterial.class::cast)
                    .toList();
            List<UUID> got = parts.stream().filter(p -> p.remainingBatches() == 0).map(S2C.NukeMaterial::id).toList();
            h.assertTrue(got.equals(ids), "local snapshot palettes " + got);
            var geometry = received.stream().filter(S2C.NukeDetonation.class::isInstance).map(S2C.NukeDetonation.class::cast)
                    .map(S2C.NukeDetonation::id).toList();
            h.assertTrue(geometry.size() == ids.size() && geometry.containsAll(ids), "archived geometry revived " + geometry);
            var sequence = List.copyOf(received);
            int marker = IntStream.range(0, sequence.size()).filter(i -> sequence.get(i) instanceof S2C.ReplayTick).findFirst().orElseThrow();
            h.assertTrue(sequence.subList(0, marker).stream().filter(S2C.NukeDetonation.class::isInstance).count() == 17,
                    "geometry crossed the replay tick marker");
            var sync = received.stream().filter(S2C.NukeSync.class::isInstance).map(S2C.NukeSync.class::cast).findFirst().orElseThrow();
            h.assertTrue(sync.detonations().size() == 17 && sync.materials().values().containsAll(ids), "cold sync recreated archived sources");
            var inboxes = new java.util.HashMap<UUID, MaterialInbox>();
            for (var part : parts) inboxes.computeIfAbsent(part.id(), unused -> new MaterialInbox()).receive(part);
            h.assertTrue(inboxes.values().stream().allMatch(i -> i.map() != null && i.map().complete()), "incomplete local replay checkpoint");
            h.assertTrue(receivedBytes.get() <= 1_500_000, "oversized snapshot " + receivedBytes.get());
            Incoming.LOG.info("MATERIALREPLAY 40 recorded sources, {} active births, {} palettes, {} framed bytes", geometry.size(), got.size(), receivedBytes.get());
        });
    }
}
