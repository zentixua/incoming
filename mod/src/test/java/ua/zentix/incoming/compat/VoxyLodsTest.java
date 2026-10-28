package ua.zentix.incoming.compat;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.S2C;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class VoxyLodsTest {
    private static S2C.VoxyChunk packet(int x, byte[] data) {
        return new S2C.VoxyChunk(ResourceLocation.withDefaultNamespace("overworld"), x, 0, data);
    }

    /** A real Netty outbound buffer whose transport cannot drain until explicitly released. */
    private static final class SlowChannel extends EmbeddedChannel {
        private boolean draining;

        @Override
        protected void doWrite(ChannelOutboundBuffer buffer) throws Exception {
            if (draining) super.doWrite(buffer);
        }

        void resume() {
            draining = true;
            flush();
        }
    }

    private static final class Receiver implements BiConsumer<CustomPacketPayload, PacketSendListener> {
        final List<S2C.VoxyChunk> chunks = new ArrayList<>();
        final List<S2C.LodBatchFinished> batches = new ArrayList<>();
        boolean completeWrites = true;
        PacketSendListener pending;
        int starts;

        @Override
        public void accept(CustomPacketPayload p, PacketSendListener done) {
            if (p instanceof S2C.LodBatchStart) starts++;
            if (p instanceof S2C.LodBatchFinished batch) batches.add(batch);
            if (p instanceof S2C.VoxyChunk chunk) {
                chunks.add(chunk);
                if (completeWrites) done.onSuccess();
                else pending = done;
            }
        }

        void ack(VoxyLods.Viewer viewer, float rate) {
            assertTrue(viewer.flow.acknowledge(batches.getLast().id(), rate));
        }
    }

    @Test
    void unwritableConnectionKeeps48MiBInOurQueueFor1200Ticks() {
        SlowChannel channel = new SlowChannel();
        try {
            channel.writeAndFlush(Unpooled.buffer(64 * 1024).writeZero(64 * 1024));
            assertFalse(channel.isWritable());
            VoxyLods.Viewer viewer = new VoxyLods.Viewer();
            byte[] data = new byte[64 * 1024];
            for (int i = 0; i < 768; i++) viewer.offer(i, packet(i, data));
            assertEquals(48L << 20, viewer.bytes);
            for (int tick = 0; tick < 1200; tick++) viewer.drain(channel, (p, done) -> fail("blocked connection accepted a payload"));
            assertEquals(768, viewer.queue.size());
            assertEquals(48L << 20, viewer.bytes);
            assertEquals(0, viewer.sent);
            channel.resume();
            Receiver client = new Receiver();
            viewer.drain(channel, client);
            assertEquals(1, viewer.sent, "one bootstrap chunk measures the connection");
            assertEquals(1, client.batches.size());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void noAckMeansNoNewBatchEvenWhenTransportWritesComplete() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            VoxyLods.Viewer viewer = new VoxyLods.Viewer();
            for (int i = 0; i < 100; i++) viewer.offer(i, packet(i, new byte[1024]));
            Receiver client = new Receiver();
            for (int tick = 0; tick < 1200; tick++) viewer.drain(channel, client);
            assertEquals(1, client.starts);
            assertEquals(1, client.chunks.size());
            assertEquals(99, viewer.queue.size());
            assertEquals(1, client.batches.getFirst().chunks());
            client.ack(viewer, 32);
            viewer.drain(channel, client);
            assertEquals(33, client.chunks.size(), "acknowledged rate sizes the next batch");
            assertEquals(32, client.batches.getLast().chunks());
            client.ack(viewer, 0.25f);
            for (int tick = 0; tick < 3; tick++) viewer.drain(channel, client);
            assertEquals(2, client.starts, "fractional quota must accumulate");
            viewer.drain(channel, client);
            assertEquals(3, client.starts);
            assertEquals(1, client.batches.getLast().chunks());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void fastProxyCannotAccumulateAMultiMiBBatchBeforeItsSlowClientAcknowledges() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            VoxyLods.Viewer viewer = new VoxyLods.Viewer();
            Receiver proxy = new Receiver(); // Every local write completes immediately, as with a buffering proxy.
            viewer.offer(0, packet(0, new byte[1024]));
            viewer.drain(channel, proxy);
            proxy.ack(viewer, 64);
            byte[] data = new byte[32 * 1024];
            for (int i = 1; i <= 64; i++) viewer.offer(i, packet(i, data));
            for (int tick = 0; tick < 1200; tick++) viewer.drain(channel, proxy);
            assertTrue(channel.isWritable());
            assertEquals(8, proxy.batches.getLast().chunks(), "64 chunks would leave 2 MiB ahead of keepalive");
            assertEquals(256 * 1024, proxy.chunks.stream().skip(1).mapToInt(p -> p.sections().length).sum());
            assertEquals(56, viewer.queue.size());
            proxy.ack(viewer, 64);
            viewer.drain(channel, proxy);
            assertEquals(17, viewer.sent, "another bounded batch follows the client acknowledgement");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void eventLoopDispatchAndFailedWritesRemainBoundedInsideABatch() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            VoxyLods.Viewer viewer = new VoxyLods.Viewer();
            for (int i = 0; i < 20; i++) viewer.offer(i, packet(i, new byte[40 * 1024]));
            Receiver client = new Receiver();
            viewer.drain(channel, client);
            client.ack(viewer, 8);
            client.completeWrites = false;
            for (int tick = 0; tick < 1200; tick++) viewer.drain(channel, client);
            assertTrue(channel.isWritable(), "event-loop dispatch has not encoded anything yet");
            assertEquals(2, client.chunks.size());
            assertEquals(18, viewer.queue.size());
            assertFalse(viewer.flow.acknowledge(viewer.flow.id(), 64), "unfinished batch cannot be acknowledged");
            client.pending.onSuccess();
            viewer.drain(channel, client);
            assertEquals(3, client.chunks.size());
            client.pending.onFailure();
            viewer.drain(channel, client);
            assertEquals(4, client.chunks.size(), "failed write also releases its reservation");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void largeChunksDoNotStallPermanentlyOrBuildAnOutboundBacklog() {
        SlowChannel channel = new SlowChannel();
        try {
            VoxyLods.Viewer viewer = new VoxyLods.Viewer();
            byte[] large = new byte[250 * 1024];
            viewer.offer(1, packet(1, large));
            viewer.offer(2, packet(2, large));
            Receiver client = new Receiver();
            BiConsumer<CustomPacketPayload, PacketSendListener> transport = (p, done) -> {
                if (p instanceof S2C.VoxyChunk chunk) {
                    channel.writeAndFlush(Unpooled.wrappedBuffer(chunk.sections())).addListener(future -> {
                        if (future.isSuccess()) done.onSuccess();
                        else done.onFailure();
                    });
                } else client.accept(p, done);
            };
            for (int tick = 0; tick < 1200; tick++) viewer.drain(channel, transport);
            assertFalse(channel.isWritable());
            assertEquals(1, viewer.sent);
            assertEquals(250 * 1024L, viewer.bytes);
            channel.resume();
            viewer.drain(channel, transport); // finish the first batch after its stalled write
            client.ack(viewer, 1);
            viewer.drain(channel, transport);
            assertEquals(2, viewer.sent);
            assertEquals(0, viewer.bytes);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void normalTrafficHeadroomAndAbsolutePendingLimitBothApply() {
        SlowChannel channel = new SlowChannel();
        try {
            VoxyLods.Viewer viewer = new VoxyLods.Viewer();
            viewer.offer(1, packet(1, new byte[1024]));
            channel.writeAndFlush(Unpooled.buffer(50 * 1024).writeZero(50 * 1024));
            assertTrue(channel.isWritable());
            viewer.drain(channel, (p, done) -> fail("insufficient game-packet headroom"));
            channel.config().setWriteBufferHighWaterMark(4 * 1024 * 1024);
            assertTrue(channel.bytesBeforeUnwritable() > VoxyLods.WRITE_HEADROOM);
            viewer.drain(channel, (p, done) -> fail("raised watermark bypassed absolute pending limit"));
            channel.resume();
            viewer.drain(channel, new Receiver());
            assertEquals(1, viewer.sent);
            channel.close();
            viewer.offer(2, packet(2, new byte[1024]));
            viewer.drain(channel, (p, done) -> fail("closed channel accepted a LOD"));
            assertEquals(1, viewer.queue.size());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void freshCopyReplacesWaitingCopyAtCapacityAndEveryChunkEventuallyArrives() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            VoxyLods.Viewer viewer = new VoxyLods.Viewer();
            byte[] data = new byte[64 * 1024];
            for (int i = 0; i < 768; i++) viewer.offer(i, packet(i, data));
            S2C.VoxyChunk fresh = packet(3, new byte[64 * 1024]);
            viewer.offer(3, fresh);
            viewer.offer(900, packet(900, data));
            assertEquals(1, viewer.dropped);
            assertEquals(768, viewer.queue.size());
            assertEquals(48L << 20, viewer.bytes);
            Receiver client = new Receiver();
            for (int tick = 0; tick < 1000 && !viewer.queue.isEmpty(); tick++) {
                viewer.drain(channel, client);
                client.ack(viewer, 64);
            }
            assertEquals(768, client.chunks.size());
            assertSame(fresh, client.chunks.stream().filter(p -> p.x() == 3).findFirst().orElseThrow());
            assertEquals(0, viewer.bytes);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void dimensionQueueResetDoesNotResetBatchIdsOrAcceptOldAcks() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            VoxyLods.Viewer viewer = new VoxyLods.Viewer();
            Receiver client = new Receiver();
            viewer.offer(1, packet(1, new byte[1024]));
            viewer.drain(channel, client);
            int first = viewer.flow.id();
            viewer.queue.clear();
            viewer.bytes = 0;
            viewer.offer(2, packet(2, new byte[1024]));
            viewer.drain(channel, client);
            assertEquals(1, viewer.sent, "dimension change must still wait for old batch acknowledgement");
            client.ack(viewer, 1);
            assertFalse(viewer.flow.acknowledge(first, 64), "duplicate acknowledgement");
            viewer.drain(channel, client);
            assertNotEquals(first, viewer.flow.id());
            assertFalse(viewer.flow.acknowledge(first, 64), "old dimension acknowledgement");
            assertEquals(2, viewer.sent);
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
