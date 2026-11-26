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
import java.util.PriorityQueue;
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
        final List<S2C.LodBatchFinished> marks = new ArrayList<>();
        boolean completeWrites = true;
        PacketSendListener pending;

        @Override
        public void accept(CustomPacketPayload p, PacketSendListener done) {
            if (p instanceof S2C.LodBatchFinished mark) marks.add(mark);
            if (p instanceof S2C.VoxyChunk chunk) {
                chunks.add(chunk);
                if (completeWrites) done.onSuccess();
                else pending = done;
            }
        }

        long bytes() {
            return chunks.stream().mapToLong(c -> c.sections().length).sum();
        }

        void ackAll(VoxyLods.Viewer viewer) {
            viewer.acknowledged(marks.getLast().id());
        }
    }

    private static void tick(VoxyLods.Viewer viewer) {
        viewer.stockUp();
        viewer.pump();
    }

    @Test
    void unwritableConnectionKeeps48MiBWaitingFor1200Ticks() {
        SlowChannel channel = new SlowChannel();
        try {
            channel.writeAndFlush(Unpooled.buffer(64 * 1024).writeZero(64 * 1024));
            assertFalse(channel.isWritable());
            Receiver client = new Receiver();
            VoxyLods.Viewer viewer = new VoxyLods.Viewer(channel, client);
            byte[] data = new byte[64 * 1024];
            for (int i = 0; i < 768; i++) viewer.offer(i, packet(i, data));
            assertEquals(48L << 20, viewer.bytes);
            for (int t = 0; t < 1200; t++) tick(viewer);
            assertTrue(client.chunks.isEmpty(), "blocked connection accepted a payload");
            assertEquals(48L << 20, viewer.bytes + viewer.stockBytes(), "nothing lost while waiting");
            assertEquals(VoxyLods.STOCK_BYTES, viewer.stockBytes(), "the network thread holds at most its stock");
            channel.resume();
            tick(viewer);
            assertEquals(VoxyLods.WINDOW_BYTES, client.bytes(), "a whole window goes once the connection drains");
            assertFalse(client.marks.isEmpty());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void fastProxyHoldsAtMostOneWindowBeforeItsSlowClientAcknowledges() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            Receiver proxy = new Receiver(); // Every local write completes immediately, as with a buffering proxy.
            VoxyLods.Viewer viewer = new VoxyLods.Viewer(channel, proxy);
            byte[] data = new byte[40 * 1024];
            for (int i = 0; i < 200; i++) viewer.offer(i, packet(i, data));
            for (int t = 0; t < 1200; t++) tick(viewer);
            assertTrue(channel.isWritable());
            assertTrue(proxy.bytes() >= VoxyLods.WINDOW_BYTES && proxy.bytes() < VoxyLods.WINDOW_BYTES + data.length,
                    "no acknowledgement, no more than one window ahead of keepalive: " + proxy.bytes());
            assertTrue(proxy.marks.size() > 1, "several batches in flight, each acknowledgeable");
            int sent = proxy.chunks.size();
            viewer.acknowledged(proxy.marks.getFirst().id());
            assertTrue(proxy.chunks.size() > sent, "an acknowledgement sends at once, without a server tick");
            assertEquals(proxy.chunks.size(), viewer.sent.get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * The LTE case measured on the rig (26.11.2026): 4.5 MB/s, 54 ms round trip, ruin chunks of 40 KiB compressing 5.7×
     * on the wire. A tick-paced sender with one acknowledged batch at a time delivered 0.08 MB/s of the line there.
     */
    @Test
    void acknowledgementsKeepAnLteLineFullWithoutAStandingQueue() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            double wireBytesPerMs = 4500, oneWayMs = 27, compression = 5.7;
            record Event(double at, int ack) {}
            PriorityQueue<Event> events = new PriorityQueue<>((a, b) -> Double.compare(a.at, b.at));
            double[] now = {0}, lineFree = {0}, maxQueueMs = {0}, steadyQueueMs = {0};
            long[] delivered = {0};
            double measureFrom = 2000, measureTo = 12000;
            BiConsumer<CustomPacketPayload, PacketSendListener> link = (p, done) -> {
                double wire = p instanceof S2C.VoxyChunk c ? c.sections().length / compression : 8;
                maxQueueMs[0] = Math.max(maxQueueMs[0], lineFree[0] - now[0]);
                if (now[0] >= measureFrom) steadyQueueMs[0] = Math.max(steadyQueueMs[0], lineFree[0] - now[0]);
                lineFree[0] = Math.max(lineFree[0], now[0]) + wire / wireBytesPerMs;
                double arrives = lineFree[0] + oneWayMs;
                if (p instanceof S2C.VoxyChunk c && arrives >= measureFrom && arrives < measureTo) delivered[0] += c.sections().length;
                if (p instanceof S2C.LodBatchFinished mark) events.add(new Event(arrives + oneWayMs, mark.id()));
                if (done != null) done.onSuccess(); // the kernel buffer takes the write at once
            };
            VoxyLods.Viewer viewer = new VoxyLods.Viewer(channel, link);
            byte[] data = new byte[40 * 1024];
            int next = 0;
            for (double t = 0; t < measureTo; t += 50) events.add(new Event(t, -1));
            while (!events.isEmpty()) {
                Event e = events.poll();
                now[0] = e.at;
                if (e.ack < 0) {
                    while (viewer.bytes < 8L << 20) viewer.offer(next, packet(next++, data));
                    tick(viewer);
                } else viewer.acknowledged(e.ack);
            }
            double payloadMBps = delivered[0] / (measureTo - measureFrom) / 1000;
            double wireShare = payloadMBps * 1000 / compression / wireBytesPerMs;
            assertTrue(wireShare > 0.9, "line share " + wireShare + " (" + payloadMBps + " MB/s of sections)");
            double windowMs = (VoxyLods.WINDOW_BYTES + data.length) / compression / wireBytesPerMs;
            assertTrue(maxQueueMs[0] <= windowMs, "first window's burst " + maxQueueMs[0] + " ms, window " + windowMs + " ms");
            assertTrue(steadyQueueMs[0] < 15, "standing queue ahead of game packets " + steadyQueueMs[0] + " ms");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void oneChunkWriteOutstandingAndFailedWritesReleaseIt() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            Receiver client = new Receiver();
            client.completeWrites = false;
            VoxyLods.Viewer viewer = new VoxyLods.Viewer(channel, client);
            for (int i = 0; i < 20; i++) viewer.offer(i, packet(i, new byte[40 * 1024]));
            for (int t = 0; t < 1200; t++) tick(viewer);
            assertTrue(channel.isWritable(), "event-loop dispatch has not encoded anything yet");
            assertEquals(1, client.chunks.size());
            client.pending.onSuccess();
            assertEquals(2, client.chunks.size(), "the completed write sends the next chunk itself");
            client.pending.onFailure();
            assertEquals(3, client.chunks.size(), "a failed write also releases its reservation");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void chunksLargerThanTheWindowGoAloneAndDoNotStall() {
        SlowChannel channel = new SlowChannel();
        try {
            Receiver client = new Receiver();
            BiConsumer<CustomPacketPayload, PacketSendListener> transport = (p, done) -> {
                if (p instanceof S2C.VoxyChunk chunk) {
                    client.chunks.add(chunk);
                    channel.writeAndFlush(Unpooled.wrappedBuffer(chunk.sections())).addListener(future -> {
                        if (future.isSuccess()) done.onSuccess();
                        else done.onFailure();
                    });
                } else client.accept(p, done);
            };
            VoxyLods.Viewer viewer = new VoxyLods.Viewer(channel, transport);
            byte[] large = new byte[VoxyLods.WINDOW_BYTES + 1024];
            viewer.offer(1, packet(1, large));
            viewer.offer(2, packet(2, large));
            for (int t = 0; t < 1200; t++) tick(viewer);
            assertFalse(channel.isWritable());
            assertEquals(1, client.chunks.size());
            channel.resume();
            assertEquals(1, client.marks.size(), "completed write marks the full window");
            tick(viewer);
            assertEquals(1, client.chunks.size(), "no acknowledgement, no second window");
            client.ackAll(viewer);
            assertEquals(2, client.chunks.size());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void normalTrafficHeadroomAndAbsolutePendingLimitBothApply() {
        SlowChannel channel = new SlowChannel();
        try {
            Receiver client = new Receiver();
            VoxyLods.Viewer viewer = new VoxyLods.Viewer(channel, client);
            viewer.offer(1, packet(1, new byte[1024]));
            channel.writeAndFlush(Unpooled.buffer(50 * 1024).writeZero(50 * 1024));
            assertTrue(channel.isWritable());
            tick(viewer);
            assertTrue(client.chunks.isEmpty(), "insufficient game-packet headroom");
            channel.config().setWriteBufferHighWaterMark(4 * 1024 * 1024);
            assertTrue(channel.bytesBeforeUnwritable() > VoxyLods.WRITE_HEADROOM);
            tick(viewer);
            assertTrue(client.chunks.isEmpty(), "raised watermark bypassed absolute pending limit");
            channel.resume();
            tick(viewer);
            assertEquals(1, client.chunks.size());
            channel.close();
            viewer.offer(2, packet(2, new byte[1024]));
            tick(viewer);
            assertEquals(1, client.chunks.size(), "closed channel accepted a LOD");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void freshCopyReplacesWaitingCopyAtCapacityAndEveryChunkEventuallyArrives() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            Receiver client = new Receiver();
            VoxyLods.Viewer viewer = new VoxyLods.Viewer(channel, client);
            byte[] data = new byte[64 * 1024];
            for (int i = 0; i < 768; i++) viewer.offer(i, packet(i, data));
            S2C.VoxyChunk fresh = packet(3, new byte[64 * 1024]);
            viewer.offer(3, fresh);
            viewer.offer(900, packet(900, data));
            assertEquals(1, viewer.dropped);
            assertEquals(768, viewer.queue.size());
            assertEquals(48L << 20, viewer.bytes);
            for (int t = 0; t < 1000 && client.chunks.size() < 768; t++) {
                tick(viewer);
                client.ackAll(viewer);
            }
            assertEquals(768, client.chunks.size());
            assertSame(fresh, client.chunks.stream().filter(p -> p.x() == 3).findFirst().orElseThrow());
            assertEquals(0, viewer.bytes + viewer.stockBytes());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void dimensionChangeDropsWaitingChunksButKeepsTheWindowAndBatchIds() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            Receiver client = new Receiver();
            VoxyLods.Viewer viewer = new VoxyLods.Viewer(channel, client);
            byte[] data = new byte[40 * 1024];
            for (int i = 0; i < 100; i++) viewer.offer(i, packet(i, data));
            tick(viewer);
            int first = client.marks.getLast().id();
            int sent = client.chunks.size();
            viewer.clear();
            assertEquals(0, viewer.bytes + viewer.stockBytes());
            viewer.pump();
            assertEquals(sent, client.chunks.size(), "the previous world's stock is gone");
            viewer.offer(1000, packet(1000, data));
            tick(viewer);
            assertEquals(sent, client.chunks.size(), "the window still holds the previous world's unacknowledged chunks");
            viewer.acknowledged(first);
            assertEquals(sent + 1, client.chunks.size());
            assertTrue(client.marks.getLast().id() > first, "batch IDs continue across the change");
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
