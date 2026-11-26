package ua.zentix.incoming.compat;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.LodBatchFlow;
import ua.zentix.incoming.net.S2C;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review probe of PR #774 (round 2), the regression test: the window on Artem's LTE line (4.5 MB/s, 54 ms base RTT)
 * with (a) the round-trip jitter measured on that line (ICMP under play, 26.11.2026, minus its minimum) and (b) a second
 * sender on the same connection that keeps a standing queue in the line (LOD Server Support re-sending dirty columns
 * after a strike or filling its radius: 190-760 KB in the server socket). Each case runs the adaptive window and, for
 * comparison, a fixed 1.5 MiB window, which never yields. A delay-based window gave 0.06 of the line beside a 100 ms
 * queue and 0.16 with the jitter alone.
 */
class VoxyLodsSharedLineTest {
    /** RTT samples in ms (ping-under-play.txt). */
    private static final double[] PING = {61.9, 59.1, 79.6, 68.3, 61.8, 61.1, 65.4, 59.0, 63.5, 63.6, 61.2, 60.1, 73.9, 164, 62.7, 94.3,
            63.3, 84.4, 61.5, 61.2, 61.2, 54.9, 58.9, 62.2, 68.4, 66.6, 77.4, 71.9, 65.8, 192, 67.2, 70.1, 60.4, 65.0, 63.4, 60.9, 63.7,
            65.2, 74.3, 119, 69.0, 63.6, 67.4, 63.6, 65.7, 81.9, 64.6, 61.4, 60.8, 59.8, 68.7, 58.6, 61.4, 56.6, 59.8, 63.7, 67.7, 71.6,
            60.1, 93.4, 70.4, 67.1, 56.6, 65.6, 59.1, 166, 63.2, 62.9, 61.5, 68.3, 62.6, 64.2, 120, 54.8, 58.2, 72.3, 67.3, 65.2, 59.8,
            63.4, 88.2, 71.4, 66.0, 59.7, 73.1, 77.7, 67.4, 55.2, 70.1, 59.3, 62.9, 57.3, 56.0, 60.8, 54.8, 57.9, 113, 117, 168, 61.8,
            69.2, 68.7, 82.2, 61.1, 55.0, 54.7, 69.0, 67.5, 76.1, 60.8, 69.9, 75.3, 61.7, 64.4, 65.0, 71.2, 113, 94.2, 148, 78.4, 77.7,
            56.6, 64.7, 71.8, 63.1, 66.3, 65.8, 56.0, 66.2, 63.5, 62.7, 61.8, 61.3, 61.2, 59.2, 68.0, 67.2, 57.1, 66.5, 74.5, 68.6,
            92.8, 61.5, 76.8, 83.0, 65.3, 69.7, 79.6, 68.1, 62.5};

    private static S2C.VoxyChunk packet(int x, byte[] data) {
        return new S2C.VoxyChunk(ResourceLocation.withDefaultNamespace("overworld"), x, 0, data);
    }

    /**
     * @param jitter     add the measured RTT excess to each acknowledgement's return trip (FIFO kept)
     * @param otherQueue standing queue (ms of line) another sender keeps on the same connection, 0 = none
     * @param fixed      the first round's fixed 1.5 MiB window instead of the head's adaptive one
     * @return {our chunks per minute, our line share, max queue ahead of a game packet in ms, mean window KiB}
     */
    private static double[] run(boolean jitter, double otherQueue, boolean fixed) throws Exception {
        double rate = 4500, oneWay = 27, compression = 5.7;
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            record Event(double at, int ack) {}
            PriorityQueue<Event> events = new PriorityQueue<>((a, b) -> Double.compare(a.at, b.at));
            double[] now = {0}, lineFree = {0}, maxQueue = {0}, lastAck = {0};
            long[] delivered = {0};
            double from = 10000, to = 60000;
            Random random = new Random(1);
            double minPing = 54.7;
            BiConsumer<CustomPacketPayload, PacketSendListener> link = (p, done) -> {
                double wire = p instanceof S2C.VoxyChunk c ? c.sections().length / compression : 8;
                lineFree[0] = Math.max(lineFree[0], now[0]) + wire / rate;
                double arrives = lineFree[0] + oneWay;
                if (p instanceof S2C.VoxyChunk c && arrives >= from && arrives < to) delivered[0] += c.sections().length;
                if (p instanceof S2C.LodBatchFinished mark) {
                    double back = arrives + oneWay + (jitter ? PING[random.nextInt(PING.length)] - minPing : 0);
                    lastAck[0] = Math.max(lastAck[0], back);
                    events.add(new Event(lastAck[0], mark.id()));
                }
                if (done != null) done.onSuccess();
            };
            VoxyLods.Viewer viewer = new VoxyLods.Viewer(channel, link, () -> (long) (now[0] * 1e6));
            if (fixed) {
                Field f = VoxyLods.Viewer.class.getDeclaredField("flow");
                f.setAccessible(true);
                f.set(viewer, new LodBatchFlow(VoxyLods.WINDOW_MAX, VoxyLods.WINDOW_MAX, VoxyLods.BATCH_BYTES, VoxyLods.TARGET_NANOS));
            }
            byte[] data = new byte[40 * 1024];
            int next = 0;
            double windowSum = 0;
            int windowSamples = 0;
            for (double t = 0; t < to; t += 50) events.add(new Event(t, -1));
            for (double t = 0; t < to; t += 1) events.add(new Event(t, -2));
            while (!events.isEmpty()) {
                Event e = events.poll();
                now[0] = e.at;
                if (e.ack == -2) {
                    // the other sender tops the line queue up to otherQueue ms, every millisecond
                    if (otherQueue > 0) lineFree[0] = Math.max(lineFree[0], now[0] + otherQueue);
                    if (now[0] >= from) maxQueue[0] = Math.max(maxQueue[0], lineFree[0] - now[0]);
                } else if (e.ack < 0) {
                    while (viewer.bytes < 8L << 20) viewer.offer(next, packet(next++, data));
                    viewer.stockUp();
                    viewer.pump();
                    channel.runPendingTasks();
                    if (now[0] >= from) {
                        windowSum += viewer.flow.window();
                        windowSamples++;
                    }
                } else {
                    viewer.acknowledged(e.ack);
                    channel.runPendingTasks();
                }
            }
            double seconds = (to - from) / 1000;
            return new double[]{delivered[0] / (double) data.length / seconds * 60, delivered[0] / (to - from) / compression / rate,
                    maxQueue[0], windowSum / windowSamples / 1024};
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void lteWithMeasuredJitterAndACompetingSender() throws Exception {
        StringBuilder report = new StringBuilder();
        double[][] cases = {{0, 0}, {1, 0}, {0, 50}, {0, 100}, {1, 100}, {0, 200}};
        double[] headJitter = null, fixedJitter = null, headOther = null, fixedOther = null;
        for (double[] c : cases) {
            for (boolean fixed : new boolean[]{false, true}) {
                double[] r = run(c[0] > 0, c[1], fixed);
                report.append(String.format(Locale.ROOT, "%n  jitter=%s otherQueue=%3.0f ms %-8s: %6.0f chunks/min, our line share %.2f, max queue %4.0f ms, mean window %5.0f KiB",
                        c[0] > 0, c[1], fixed ? "fixed" : "adaptive", r[0], r[1], r[2], r[3]));
                if (!fixed && c[0] > 0 && c[1] == 0) headJitter = r;
                if (fixed && c[0] > 0 && c[1] == 0) fixedJitter = r;
                if (!fixed && c[0] == 0 && c[1] == 100) headOther = r;
                if (fixed && c[0] == 0 && c[1] == 100) fixedOther = r;
            }
        }
        System.out.println("shared line:" + report);
        assertTrue(headJitter[1] > 0.9 * fixedJitter[1], "LTE line share with measured jitter, adaptive vs fixed window" + report);
        assertTrue(headOther[0] > 0.5 * fixedOther[0], "ruin chunks/min beside a 100 ms foreign queue, head vs fixed window" + report);
    }
}
