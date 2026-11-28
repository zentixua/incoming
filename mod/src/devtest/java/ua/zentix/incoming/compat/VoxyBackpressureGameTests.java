package ua.zentix.incoming.compat;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import ua.zentix.incoming.gametest.StrikeGameTests;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.concurrent.atomic.AtomicReference;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.nuclear.world.FarLods;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/** Exercises the player's send wrapper and vanilla Connection write-completion callback, without a client or GPU. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VoxyBackpressureGameTests {
    private VoxyBackpressureGameTests() {}

    @GameTest(template = "range", timeoutTicks = 100, batch = "voxy_backpressure", skyAccess = true)
    public static void playerConnectionPausesAndResumesLods(GameTestHelper h) {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        List<S2C.LodBatchFinished> batches = new ArrayList<>();
        // Encode only the bulk bytes here: the test is about transport completion, not payload registration/codec.
        MessageToByteEncoder<Packet<?>> encoder = new MessageToByteEncoder<>() {
            @Override
            protected void encode(ChannelHandlerContext context, Packet<?> packet, ByteBuf out) {
                if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof S2C.VoxyChunk lod) {
                    out.writeBytes(lod.sections());
                } else {
                    out.writeByte(0);
                    if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof S2C.LodBatchFinished end) {
                        batches.add(end);
                    }
                }
            }
        };
        class SlowChannel extends EmbeddedChannel {
            boolean draining;

            SlowChannel() {
                super(encoder, connection);
            }

            @Override
            protected void doWrite(ChannelOutboundBuffer buffer) throws Exception {
                if (draining) super.doWrite(buffer);
            }
        }
        SlowChannel channel = new SlowChannel();
        try {
            FakePlayer player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "lod-watcher"));
            player.connection = new ServerGamePacketListenerImpl(player.server, connection, player,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
                @Override
                public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
                    connection.send(packet, listener);
                }
            };
            VoxyLods.Viewer viewer = VoxyLods.Viewer.of(player);
            byte[] data = new byte[250 * 1024];
            var dimension = h.getLevel().dimension().location();
            int small = 32 * 1024;
            for (int i = 0; i < 8; i++) viewer.offer(i, new S2C.VoxyChunk(dimension, i, 0, new byte[i == 0 ? data.length : small]));
            for (int tick = 0; tick < 1200; tick++) {
                viewer.drain();
                channel.runPendingTasks(); // the connection's event loop
            }
            h.assertTrue(viewer.sent.get() == 1 && viewer.bytes + viewer.stockBytes() == 7L * small, "slow connection did not stop the sender");
            h.assertFalse(channel.isWritable(), "test transport did not retain the encoded LOD");
            channel.draining = true;
            channel.flush();
            channel.runPendingTasks();
            h.assertTrue(viewer.sent.get() == 1 && !batches.isEmpty(), "the oversized chunk did not hold the window until acknowledged");
            viewer.acknowledged(batches.getLast().id());
            channel.runPendingTasks();
            h.assertTrue(viewer.sent.get() == 8, "Connection write completion did not resume delivery within the window: sent " + viewer.sent.get()
                    + ", waiting " + (viewer.bytes + viewer.stockBytes()) + ", in flight " + viewer.flow.inFlight() + ", window " + viewer.flow.window());
            h.assertTrue(batches.stream().mapToInt(S2C.LodBatchFinished::chunks).sum() == 8, "sent chunks not all marked for acknowledgement");
            for (int i = 8; i < 68; i++) viewer.offer(i, new S2C.VoxyChunk(dimension, i, 0, new byte[small]));
            for (int tick = 0; tick < 1200; tick++) {
                viewer.drain();
                channel.runPendingTasks();
            }
            long window = viewer.flow.window();
            h.assertTrue(viewer.flow.inFlight() >= window && viewer.flow.inFlight() < window + small && viewer.sent.get() < 68,
                    "write completion was mistaken for client acknowledgement: " + viewer.flow.inFlight() + " bytes in flight, window " + window);
            for (int round = 0; round < 100 && viewer.sent.get() < 68; round++) {
                viewer.acknowledged(batches.getLast().id());
                viewer.drain();
                channel.runPendingTasks();
            }
            h.assertTrue(viewer.sent.get() == 68 && viewer.bytes + viewer.stockBytes() == 0, "acknowledgements did not send the rest");
            int received = 0;
            for (ByteBuf bytes; (bytes = channel.readOutbound()) != null; ) {
                if (bytes.readableBytes() > 1) received++;
                bytes.release();
            }
            h.assertTrue(received == 68, "not all chunks reached the transport");
            h.succeed();
        } finally {
            channel.finishAndReleaseAll();
        }
    }
    /** On the main thread an acknowledgement waits for the server tick: one window per tick, not per round trip. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "voxy_backpressure", skyAccess = true)
    public static void acknowledgementsAreHandledOnTheNetworkThread(GameTestHelper h) {
        java.util.Map<net.minecraft.network.ConnectionProtocol, java.util.Map<net.minecraft.resources.ResourceLocation,
                net.neoforged.neoforge.network.registration.PayloadRegistration<?>>> all = ObfuscationReflectionHelper.getPrivateValue(
                net.neoforged.neoforge.network.registration.NetworkRegistry.class, null, "PAYLOAD_REGISTRATIONS");
        var registration = all.get(net.minecraft.network.ConnectionProtocol.PLAY).get(ua.zentix.incoming.net.C2S.LodBatchReceived.TYPE.id());
        h.assertTrue(registration != null, "acknowledgement payload not registered");
        h.assertFalse(registration.handler() instanceof net.neoforged.neoforge.network.handling.MainThreadPayloadHandler,
                "acknowledgements are handled on the main thread");
        h.succeed();
    }

    /** The real mark-to-disk-to-queue path, with an old copy already waiting at the queue cap. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "voxy_saved_update", skyAccess = true)
    public static void pausedMarkSurvivesUnloadAndReplacesItsWaitingCopy(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        BlockPos changed = h.absolutePos(new BlockPos(32, 15, 32));
        level.setBlock(changed, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        var source = level.getChunkAt(changed);
        ChunkPos unloaded = new ChunkPos(source.getPos().x + 100, source.getPos().z + 100);
        var data = ChunkSerializer.write(level, source);
        data.putInt("xPos", unloaded.x);
        data.putInt("zPos", unloaded.z);
        data.putString("Status", ChunkStatus.FULL.getName());
        ua.zentix.incoming.util.RegionFiles.write(level, unloaded, data);
        VoxyLods.Viewer viewer = new VoxyLods.Viewer(null, (packet, listener) -> {});
        byte[] old = new byte[64 * 1024];
        long key = unloaded.toLong();
        for (int i = 0; i < 767; i++) viewer.offer(i, new S2C.VoxyChunk(level.dimension().location(), i, 0, old));
        viewer.offer(key, new S2C.VoxyChunk(level.dimension().location(), unloaded.x, unloaded.z, old));
        AtomicReference<ChunkAccess> delivered = new AtomicReference<>();
        DhUpdates.testSink(level, (world, copy) -> {
            if (!copy.getPos().equals(unloaded)) return;
            delivered.set(copy);
            byte[] fresh = LodSections.ofCopy(copy, true);
            viewer.offer(key, new S2C.VoxyChunk(level.dimension().location(), unloaded.x, unloaded.z, fresh));
        });
        StrikeGameTests.afterTest(h, () -> DhUpdates.testSink(level, null));
        DhUpdates.mark(level, unloaded, level.getGameTime() + 5);
        h.succeedWhen(() -> {
            h.assertTrue(delivered.get() != null, "unloaded mark was discarded instead of read from its save");
            h.assertTrue(delivered.get().getBlockState(new BlockPos(unloaded.getMinBlockX() + (changed.getX() & 15),
                    changed.getY(), unloaded.getMinBlockZ() + (changed.getZ() & 15))).is(Blocks.GOLD_BLOCK), "saved update is stale");
            h.assertTrue(viewer.queue.size() == 768 && viewer.queue.get(key).sections() != old, "fresh copy did not replace waiting copy at cap");
            h.assertTrue(viewer.bytes <= VoxyLods.QUEUE_BYTES && viewer.dropped == 0, "replacement exceeded cap or was dropped");
            h.assertTrue(level.getChunkSource().getChunkNow(unloaded.x, unloaded.z) == null, "LOD refresh loaded world chunks");
        });
    }

    /** A completed read held by backpressure must not outlive a later world change and save. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "voxy_saved_revision", skyAccess = true)
    public static void laterMarkInvalidatesAnOlderSavedCopy(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        BlockPos changed = h.absolutePos(new BlockPos(32, 15, 32));
        level.setBlock(changed, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        var source = level.getChunkAt(changed);
        ChunkPos target = new ChunkPos(source.getPos().x + 100, source.getPos().z + 100);
        var first = ChunkSerializer.write(level, source);
        first.putInt("xPos", target.x);
        first.putInt("zPos", target.z);
        first.putString("Status", ChunkStatus.FULL.getName());
        ua.zentix.incoming.util.RegionFiles.write(level, target, first);
        var oldRead = FarLods.savedCopy(level, target.toLong());
        AtomicReference<ChunkAccess> delivered = new AtomicReference<>();
        DhUpdates.testSink(level, (world, copy) -> {
            if (copy.getPos().equals(target)) delivered.set(copy);
        });
        StrikeGameTests.afterTest(h, () -> DhUpdates.testSink(level, null));
        boolean[] prepared = {false};
        h.onEachTick(() -> {
            if (prepared[0] || !oldRead.isDone()) return;
            h.assertTrue(oldRead.getNow(null) != null, "old disk snapshot missing");
            long due = level.getGameTime() + 5;
            DhUpdates.mark(level, target, due);
            Long2ObjectOpenHashMap<DhUpdates.SavedRead> reads = ObfuscationReflectionHelper.getPrivateValue(
                    DhUpdates.class, level.getData(ModAttachments.DH_UPDATES), "unloaded");
            reads.put(target.toLong(), new DhUpdates.SavedRead(oldRead));
            level.setBlock(changed, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            var latest = ChunkSerializer.write(level, source);
            latest.putInt("xPos", target.x);
            latest.putInt("zPos", target.z);
            latest.putString("Status", ChunkStatus.FULL.getName());
            ua.zentix.incoming.util.RegionFiles.write(level, target, latest);
            DhUpdates.mark(level, target, due);
            prepared[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(delivered.get() != null, "latest saved revision never delivered");
            h.assertTrue(delivered.get().getBlockState(new BlockPos(target.getMinBlockX() + (changed.getX() & 15),
                    changed.getY(), target.getMinBlockZ() + (changed.getZ() & 15))).is(Blocks.DIAMOND_BLOCK),
                    "later mark reused the previous save and lost the latest world change");
            h.assertTrue(level.getChunkSource().getChunkNow(target.x, target.z) == null, "revision refresh loaded world chunks");
        });
    }


    /**
     * A chunk the server sent to a player lands in that player's Voxy from the world, so a later copy with the bytes last
     * queued to them goes again: outage 1's dark copy, the player visits while the lights come back, outage 2's dark
     * copy is the same bytes (review 1 of the recording fix, S1). The hook is the registered {@code ChunkWatchEvent.Sent}
     * listener.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "voxy_seen")
    public static void aChunkThePlayerReceivedGoesAgainWithTheSameBytes(GameTestHelper h) {
        var level = h.getLevel();
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "lod-visitor"));
        VoxyLods.Viewer viewer = new VoxyLods.Viewer(null, (packet, listener) -> {});
        VoxyLods.testViewer(level.getServer(), player.getUUID(), viewer);
        try {
            var chunk = level.getChunkAt(h.absolutePos(new BlockPos(8, 1, 8)));
            long key = chunk.getPos().toLong();
            byte[] dark = new byte[30_000];
            dark[11] = 5;
            var dimension = level.dimension().location();
            viewer.offer(key, new S2C.VoxyChunk(dimension, chunk.getPos().x, chunk.getPos().z, dark));
            viewer.queue.clear(); // delivered
            viewer.bytes = 0;
            viewer.offer(key, new S2C.VoxyChunk(dimension, chunk.getPos().x, chunk.getPos().z, dark.clone()));
            h.assertTrue(viewer.queue.isEmpty(), "a repeat with nothing in between was sent again");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.level.ChunkWatchEvent.Sent(player, chunk, level));
            viewer.offer(key, new S2C.VoxyChunk(dimension, chunk.getPos().x, chunk.getPos().z, dark.clone()));
            h.assertTrue(viewer.queue.size() == 1, "the second outage's dark copy was not queued after the player received the chunk");
        } finally {
            VoxyLods.testViewer(level.getServer(), player.getUUID(), null);
        }
        h.succeed();
    }
}
