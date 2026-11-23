package ua.zentix.incoming.compat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.material.DustGrid;
import ua.zentix.incoming.nuclear.material.MaterialInbox;
import ua.zentix.incoming.nuclear.material.MaterialMap;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReplayMaterialGameTests {
    private ReplayMaterialGameTests() {}

    @GameTest(template = "range", batch = "nuke_material_seek", timeoutTicks = 100)
    public static void rawSeekSnapshotPacesSeventeenSourcesNearestFirst(GameTestHelper h) {
        var codec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(h.getLevel().registryAccess(), ConnectionType.NEOFORGE)).codec();
        EmbeddedChannel channel = new EmbeddedChannel(new ReplayMaterialDelivery(Vec3.ZERO));
        channel.freezeTime();
        var views = new HashMap<UUID, MaterialInbox>();
        try {
            // Flashback's replay server flushes complete encoded snapshot batches together; there are no live deltas.
            for (int i = 40; i >= 1; i--) {
                var id = new UUID(0, i);
                var map = MaterialMap.build(new DustGrid.Bounds(256 * i, 0, 64, 256), new Long2IntOpenHashMap(), i % 4, 100);
                var src = new S2C.NukeMaterial(id, 0, 0, map.encode());
                var bytes = Unpooled.buffer();
                codec.encode(bytes, new ClientboundCustomPayloadPacket(src));
                channel.writeOutbound(bytes);
            }
            var ids = java.util.stream.IntStream.rangeClosed(1, 17).mapToObj(i -> new UUID(0, i)).toList();
            var manifest = Unpooled.buffer();
            codec.encode(manifest, new ClientboundCustomPayloadPacket(new S2C.NukeMaterialSources(ids)));
            channel.writeOutbound(manifest);
            ((ByteBuf) channel.readOutbound()).release();
            h.assertTrue(channel.readOutbound() == null, "seek burst bypassed the paced path");
            for (int i = 1; i <= 17; i++) {
                channel.advanceTimeBy(50, TimeUnit.MILLISECONDS);
                channel.runScheduledPendingTasks();
                ByteBuf bytes = channel.readOutbound();
                h.assertTrue(bytes != null && channel.readOutbound() == null, "seek batch count at slot " + i);
                try {
                    var packet = (ClientboundCustomPayloadPacket) codec.decode(bytes);
                    var p = (S2C.NukeMaterial) packet.payload();
                    h.assertTrue(p.id().getLeastSignificantBits() == i, "seek order");
                    var in = views.computeIfAbsent(p.id(), unused -> new MaterialInbox());
                    in.receive(p);
                    h.assertTrue(in.map().material(i * 256, 0).ordinal() == i % 4, "snapshot without previous packets");
                } finally { bytes.release(); }
            }
            channel.advanceTimeBy(2, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            h.assertTrue(channel.readOutbound() == null, "archived source used another seek slot");
        } finally { channel.finishAndReleaseAll(); }
        h.succeed();
    }
}
