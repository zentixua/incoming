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
import java.util.Locale;
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

    /** A server tick, then the connection's event loop runs what it was given (pumps yield and reschedule). */
    private static void tick(VoxyLods.Viewer viewer, EmbeddedChannel channel) {
        viewer.stockUp();
        viewer.pump();
        channel.runPendingTasks();
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
            for (int t = 0; t < 1200; t++) tick(viewer, channel);
            assertTrue(client.chunks.isEmpty(), "blocked connection accepted a payload");
            assertEquals(48L << 20, viewer.bytes + viewer.stockBytes(), "nothing lost while waiting");
            assertEquals(VoxyLods.STOCK_BYTES, viewer.stockBytes(), "the network thread holds at most its stock");
            channel.resume();
            tick(viewer, channel);
            assertEquals(VoxyLods.WINDOW_MIN, client.bytes(), "a whole window goes once the connection drains");
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
            for (int t = 0; t < 1200; t++) tick(viewer, channel);
            assertTrue(channel.isWritable());
            long window = viewer.flow.window();
            assertTrue(proxy.bytes() >= window && proxy.bytes() < window + data.length,
                    "no acknowledgement, no more than one window ahead of keepalive: " + proxy.bytes());
            assertTrue(proxy.marks.size() > 1, "several batches in flight, each acknowledgeable");
            int sent = proxy.chunks.size();
            viewer.acknowledged(proxy.marks.getFirst().id());
            channel.runPendingTasks();
            assertTrue(proxy.chunks.size() > sent, "an acknowledgement sends at once, without a server tick");
            assertEquals(proxy.chunks.size(), viewer.sent.get());
            int more = 200;
            for (int round = 0; round < 50; round++) {
                while (viewer.bytes < 4L << 20) viewer.offer(more, packet(more++, data)); // a backlog that keeps the window full
                proxy.ackAll(viewer); // instant acknowledgements: no queueing delay, the window opens fully
                tick(viewer, channel);
            }
            for (int t = 0; t < 1200; t++) tick(viewer, channel);
            assertEquals(VoxyLods.WINDOW_MAX, viewer.flow.window());
            assertTrue(viewer.flow.inFlight() < VoxyLods.WINDOW_MAX + data.length, "the window never opens past its ceiling");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * A line of {@code wireBytesPerMs} with {@code oneWayMs} each way, chunks of 40 KiB compressing {@code compression}×,
     * a backlog that never runs out, acknowledgements as soon as marks arrive: the steady standing queue ahead of a game
     * packet (ms) and the share of the line the chunks use, after five seconds.
     */
    private static double[] line(double wireBytesPerMs, double oneWayMs, double compression) {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            record Event(double at, int ack) {}
            PriorityQueue<Event> events = new PriorityQueue<>((a, b) -> Double.compare(a.at, b.at));
            double[] now = {0}, lineFree = {0}, steadyQueueMs = {0};
            long[] delivered = {0};
            double measureFrom = 5000, measureTo = 20000;
            BiConsumer<CustomPacketPayload, PacketSendListener> link = (p, done) -> {
                double wire = p instanceof S2C.VoxyChunk c ? c.sections().length / compression : 8;
                if (now[0] >= measureFrom) steadyQueueMs[0] = Math.max(steadyQueueMs[0], lineFree[0] - now[0]);
                lineFree[0] = Math.max(lineFree[0], now[0]) + wire / wireBytesPerMs;
                double arrives = lineFree[0] + oneWayMs;
                if (p instanceof S2C.VoxyChunk c && arrives >= measureFrom && arrives < measureTo) delivered[0] += c.sections().length;
                if (p instanceof S2C.LodBatchFinished mark) events.add(new Event(arrives + oneWayMs, mark.id()));
                if (done != null) done.onSuccess(); // the kernel buffer takes the write at once
            };
            VoxyLods.Viewer viewer = new VoxyLods.Viewer(channel, link, () -> (long) (now[0] * 1e6));
            byte[] data = new byte[40 * 1024];
            int next = 0;
            for (double t = 0; t < measureTo; t += 50) events.add(new Event(t, -1));
            while (!events.isEmpty()) {
                Event e = events.poll();
                now[0] = e.at;
                if (e.ack < 0) {
                    while (viewer.bytes < 8L << 20) viewer.offer(next, packet(next++, data));
                    tick(viewer, channel);
                } else {
                    viewer.acknowledged(e.ack);
                    channel.runPendingTasks();
                }
            }
            return new double[]{steadyQueueMs[0], delivered[0] / (measureTo - measureFrom) / compression / wireBytesPerMs};
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * The LTE case measured on the rig (26.11.2026): 4.5 MB/s, 54 ms round trip, ruin chunks compressing 5.7× on the
     * wire. A tick-paced sender with one acknowledged batch at a time delivered 0.07 MB/s of that line.
     */
    @Test
    void acknowledgementsKeepAnLteLineFullWithoutAStandingQueue() {
        double[] lte = line(4500, 27, 5.7);
        assertTrue(lte[1] > 0.9, "line share " + lte[1]);
        assertTrue(lte[0] < 30, "standing queue ahead of game packets " + lte[0] + " ms");
    }

    /**
     * Review probe of PR #774: a window fixed for the LTE link queued 184–983 ms ahead of game packets on slower lines
     * and less compressible chunks (2.5×, est., detailed city chunks with global palettes). The adaptive window keeps
     * them full with 88–222 ms, its target of 100 ms plus probing; a socket with tcp_notsent_lowat keeps even that out
     * of the game packets' way.
     */
    @Test
    void slowerLinesKeepTheLineFullWithAShortQueue() {
        StringBuilder report = new StringBuilder();
        boolean ok = true;
        for (double[] l : new double[][]{{4500, 27, 2.5}, {1250, 20, 5.7}, {1250, 20, 2.5}, {625, 20, 5.7}, {625, 20, 2.5}}) {
            double[] r = line(l[0], l[1], l[2]);
            String row = String.format(Locale.ROOT, "%.2f MB/s, RTT %.0f ms, %.1fx: standing queue %.0f ms, line share %.2f",
                    l[0] / 1000, 2 * l[1], l[2], r[0], r[1]);
            report.append("\n  ").append(row);
            // Own queue: about the target plus one probing round trip (1.25×), never what a fixed window would hold.
            ok &= r[0] < 250 && r[1] > 0.9;
        }
        System.out.println("slower lines:" + report);
        assertTrue(ok, "slower lines:" + report);
    }

    @Test
    void oneChunkWriteOutstandingAndFailedWritesReleaseIt() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            Receiver client = new Receiver();
            client.completeWrites = false;
            VoxyLods.Viewer viewer = new VoxyLods.Viewer(channel, client);
            for (int i = 0; i < 20; i++) viewer.offer(i, packet(i, new byte[40 * 1024]));
            for (int t = 0; t < 1200; t++) tick(viewer, channel);
            assertTrue(channel.isWritable(), "event-loop dispatch has not encoded anything yet");
            assertEquals(1, client.chunks.size());
            client.pending.onSuccess();
            channel.runPendingTasks(); // the connection's event loop
            assertEquals(2, client.chunks.size(), "the completed write sends the next chunk without a server tick");
            client.pending.onFailure();
            channel.runPendingTasks();
            assertEquals(3, client.chunks.size(), "a failed write also releases its reservation");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Netty completes a write before it subtracts its bytes from the pending count: pumping inside the callback stalls. */
    @Test
    void asyncWriteCompletionContinuesWithoutAServerTick() {
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
            for (int i = 0; i < 3; i++) viewer.offer(i, packet(i, new byte[40 * 1024])); // within the first window
            tick(viewer, channel);
            assertEquals(1, client.chunks.size(), "one outstanding write");
            channel.resume();
            channel.runPendingTasks();
            assertEquals(3, client.chunks.size(), "completions chain the rest on the event loop");
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
            byte[] large = new byte[VoxyLods.WINDOW_MAX + 1024];
            viewer.offer(1, packet(1, large));
            viewer.offer(2, packet(2, large));
            for (int t = 0; t < 1200; t++) tick(viewer, channel);
            assertFalse(channel.isWritable());
            assertEquals(1, client.chunks.size());
            channel.resume();
            channel.runPendingTasks();
            assertEquals(1, client.marks.size(), "the full window is marked once");
            tick(viewer, channel);
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
            tick(viewer, channel);
            assertTrue(client.chunks.isEmpty(), "insufficient game-packet headroom");
            channel.config().setWriteBufferHighWaterMark(4 * 1024 * 1024);
            assertTrue(channel.bytesBeforeUnwritable() > VoxyLods.WRITE_HEADROOM);
            tick(viewer, channel);
            assertTrue(client.chunks.isEmpty(), "raised watermark bypassed absolute pending limit");
            channel.resume();
            tick(viewer, channel);
            assertEquals(1, client.chunks.size());
            channel.close();
            viewer.offer(2, packet(2, new byte[1024]));
            tick(viewer, channel);
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
            byte[] changed = new byte[64 * 1024];
            changed[0] = 1;
            S2C.VoxyChunk fresh = packet(3, changed);
            viewer.offer(3, fresh);
            viewer.offer(900, packet(900, data));
            assertEquals(1, viewer.dropped);
            assertEquals(768, viewer.queue.size());
            assertEquals(48L << 20, viewer.bytes);
            for (int t = 0; t < 1000 && client.chunks.size() < 768; t++) {
                tick(viewer, channel);
                client.ackAll(viewer);
            }
            assertEquals(768, client.chunks.size());
            assertSame(fresh, client.chunks.stream().filter(p -> p.x() == 3).findFirst().orElseThrow());
            assertEquals(0, viewer.bytes + viewer.stockBytes());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * A chunk with the bytes last queued for the player is not sent again (its Voxy has them; a Flashback recording would
     * keep a second copy), a changed one is; after LOD Server Support overwrote it on the client, or in another world,
     * the same bytes go again.
     */
    @Test
    void sameBytesAreNotSentTwiceUntilTheClientLosesThem() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            Receiver client = new Receiver();
            VoxyLods.Viewer viewer = new VoxyLods.Viewer(channel, client);
            byte[] ruins = new byte[30_000], more = ruins.clone();
            ruins[7] = 3;
            more[7] = 4;
            viewer.offer(1, packet(1, ruins));
            viewer.offer(1, packet(1, ruins.clone())); // waiting: no second copy queued
            assertEquals(1, viewer.queue.size());
            tick(viewer, channel);
            client.ackAll(viewer);
            viewer.offer(1, packet(1, ruins.clone())); // already sent
            assertEquals(0, viewer.queue.size());
            assertEquals(2, viewer.repeats);
            viewer.offer(1, packet(1, more)); // the ruins grew
            assertEquals(1, viewer.queue.size());
            tick(viewer, channel);
            client.ackAll(viewer);
            viewer.forget(1); // LSS wrote the disk chunk into the client's Voxy
            viewer.offer(1, packet(1, more.clone()));
            assertEquals(1, viewer.queue.size());
            tick(viewer, channel);
            client.ackAll(viewer);
            viewer.clear(); // another world
            viewer.offer(1, packet(1, more.clone()));
            assertEquals(1, viewer.queue.size());
            tick(viewer, channel);
            assertEquals(4, client.chunks.size());
            // a full queue drops a chunk: it is not remembered as sent
            VoxyLods.Viewer full = new VoxyLods.Viewer(null, (p, l) -> {});
            byte[] big = new byte[64 * 1024];
            for (int i = 0; i < 768; i++) full.offer(i, packet(i, big));
            full.offer(900, packet(900, ruins));
            assertEquals(1, full.dropped);
            full.queue.clear(); // as if sent
            full.bytes = 0;
            full.offer(900, packet(900, ruins));
            assertEquals(1, full.queue.size(), "a dropped chunk goes when there is room");
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
            tick(viewer, channel);
            int first = client.marks.getLast().id();
            int sent = client.chunks.size();
            viewer.clear();
            assertEquals(0, viewer.bytes + viewer.stockBytes());
            viewer.pump();
            assertEquals(sent, client.chunks.size(), "the previous world's stock is gone");
            viewer.offer(1000, packet(1000, data));
            tick(viewer, channel);
            assertEquals(sent, client.chunks.size(), "the window still holds the previous world's unacknowledged chunks");
            viewer.acknowledged(first);
            assertEquals(sent + 1, client.chunks.size());
            assertTrue(client.marks.getLast().id() > first, "batch IDs continue across the change");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** @return {window KiB at burst start, max queue ms in the burst's first 10 s, ms of those 10 s with queue > 100 ms} */
    private static double[] burstAfterTrickle(double rate, double oneWay, double compression) {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            record Event(double at, int ack) {}
            PriorityQueue<Event> events = new PriorityQueue<>((a, b) -> Double.compare(a.at, b.at));
            double[] now = {0}, lineFree = {0};
            BiConsumer<CustomPacketPayload, PacketSendListener> link = (p, done) -> {
                double wire = p instanceof S2C.VoxyChunk c ? c.sections().length / compression : 8;
                lineFree[0] = Math.max(lineFree[0], now[0]) + wire / rate;
                if (p instanceof S2C.LodBatchFinished mark) events.add(new Event(lineFree[0] + 2 * oneWay, mark.id()));
                if (done != null) done.onSuccess();
            };
            VoxyLods.Viewer viewer = new VoxyLods.Viewer(channel, link, () -> (long) (now[0] * 1e6));
            byte[] data = new byte[40 * 1024];
            int next = 0;
            double burst = 30000, end = burst + 10000, windowAtBurst = -1, maxQueue = 0, over = 0;
            for (double t = 0; t < end; t += 50) events.add(new Event(t, -1));
            for (double t = burst; t < end; t += 1) events.add(new Event(t, -2));
            while (!events.isEmpty()) {
                Event e = events.poll();
                now[0] = e.at;
                if (e.ack == -2) {
                    double q = Math.max(0, lineFree[0] - now[0]);
                    maxQueue = Math.max(maxQueue, q);
                    if (q > 100) over++;
                } else if (e.ack == -1) {
                    if (now[0] < burst) {
                        if (((long) now[0]) % 1000 == 0) viewer.offer(next, packet(next++, data)); // trickle: one chunk a second
                    } else {
                        if (windowAtBurst < 0) windowAtBurst = viewer.flow.window() / 1024.0;
                        while (viewer.bytes < 8L << 20) viewer.offer(next, packet(next++, data)); // a strike's ruins
                    }
                    viewer.stockUp();
                    viewer.pump();
                    channel.runPendingTasks();
                } else {
                    viewer.acknowledged(e.ack);
                    channel.runPendingTasks();
                }
            }
            return new double[]{windowAtBurst, maxQueue, over};
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Review probe (PR #774, round 2): a trickle between strikes must not open the window for the next burst. */
    @Test
    void aTrickleDoesNotOpenTheWindowForTheNextBurst() {
        StringBuilder report = new StringBuilder();
        boolean ok = true;
        for (double[] l : new double[][]{{4500, 27, 5.7}, {1250, 20, 5.7}, {1250, 20, 2.5}, {625, 20, 5.7}, {625, 20, 2.5}}) {
            double[] r = burstAfterTrickle(l[0], l[1], l[2]);
            report.append(String.format(Locale.ROOT, "%n  %.2f MB/s, RTT %.0f ms, %.1fx: window at burst %4.0f KiB, max queue %4.0f ms, %4.0f ms of the first 10 s above 100 ms",
                    l[0] / 1000, 2 * l[1], l[2], r[0], r[1], r[2]));
            ok &= r[1] < 200;
        }
        System.out.println("burst after trickle:" + report);
        assertTrue(ok, "burst after trickle:" + report);
    }
}
