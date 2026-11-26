package ua.zentix.incoming.net;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Per-connection acknowledged byte window for Voxy LOD delivery, owned by the connection's network event loop. Chunks
 * go out while fewer than {@link #window()} bytes await the client's acknowledgement; a batch mark follows every
 * {@code batch} bytes, and the client acknowledges marks cumulatively once Voxy has room. Several batches are in
 * flight at once, so delivery follows the line rather than one round trip per batch; the window bounds what can wait
 * ahead of game packets, whatever buffers sit between server and client (a proxy completes writes at once).
 * <p>
 * The window is the line's bandwidth-delay product plus {@code target} of queue, estimated as BBR does (Cardwell et
 * al., "BBR: Congestion-Based Congestion Control", ACM Queue 14(5), 2016): the highest delivery rate of the last one to
 * two minutes times the lowest mark-to-ack time of the last nine to ten (LEDBAT's base history, RFC 6817 section
 * 2.4.2). Both filters ignore what is not ours: another sender's queue on the same connection and the jitter of a
 * mobile line lower single rate samples and raise single delays, never the maximum and the minimum, so the window
 * does not yield to them. Each eighth round trip the window probes at 1.25× the estimate and the next drains at 0.75×
 * (BBR's ProbeBW gain cycle): in a queue shared with another sender the larger window raises our delivery rate and the
 * estimate ratchets up; at a line that is ours alone it does not. A slow line keeps about {@code target} of our own
 * queue. The window stays within one batch of
 * what is really in flight (RFC 6817 max_allowed_cwnd), so a trickle between strikes does not open it for the next
 * burst, which starts small and doubles each round trip. Batch IDs survive dimension changes; stale, duplicate and
 * early acknowledgements open nothing.
 */
public final class LodBatchFlow {
    /** Filter bucket: rate maximum over two buckets, base delay minimum over {@link #BASE_HISTORY}. */
    static final long BUCKET_NANOS = 60_000_000_000L;
    /** RFC 6817 BASE_HISTORY: minutes of base delay. */
    static final int BASE_HISTORY = 10;
    /** RFC 6817 ALLOWED_INCREASE: the window stays within this many batches above the bytes in flight. */
    static final int ALLOWED_INCREASE = 1;
    /** BBR ProbeBW gains, one per round trip (base delay). */
    static final double[] GAIN_CYCLE = {1.25, 0.75, 1, 1, 1, 1, 1, 1};

    private final long min, max, targetNanos;
    private final int batch;
    /** Marked batches awaiting acknowledgement: id, bytes, mark time, bytes delivered at mark time. */
    private final ArrayDeque<long[]> marked = new ArrayDeque<>();
    private int sequence, acknowledged, openChunks, markedChunks;
    private long inFlight, open, delivered;
    private long window;
    /** Per bucket, newest first: lowest delay (ns) and highest delivery rate (bytes per ms). */
    private final long[] baseByBucket = new long[BASE_HISTORY];
    private final double[] rateByBucket = new double[2];
    private long bucketStart = Long.MIN_VALUE, cycleStart = Long.MIN_VALUE;
    private long lastDelayNanos = -1;

    public LodBatchFlow(long min, long max, int batch, long targetNanos) {
        this.min = min;
        this.max = max;
        this.batch = batch;
        this.targetNanos = targetNanos;
        this.window = min;
        Arrays.fill(baseByBucket, Long.MAX_VALUE);
    }

    /** Another chunk may go now: the window has room (with nothing in flight, one chunk larger than it stands alone). */
    public boolean hasRoom() {
        return inFlight < window;
    }

    public void chunkSent(int bytes) {
        inFlight += bytes;
        open += bytes;
        openChunks++;
    }

    /** The open batch should be marked: it is full, nothing more is waiting, or the window is closed. */
    public boolean markDue(boolean moreWaiting) {
        return openChunks > 0 && (open >= batch || !moreWaiting || !hasRoom());
    }

    /** Close the open batch; its mark carries the returned ID and chunk count ({@link #markedChunks}). */
    public int mark(long nowNanos) {
        marked.add(new long[]{++sequence, open, nowNanos, delivered});
        open = 0;
        markedChunks = openChunks;
        openChunks = 0;
        return sequence;
    }

    public int markedChunks() {
        return markedChunks;
    }

    /** Cumulative: every marked batch up to {@code id}. Only a marked, not yet acknowledged ID is accepted. */
    public boolean acknowledge(int id, long nowNanos) {
        if (id <= acknowledged || id > sequence) return false;
        long flight = inFlight;
        long[] newest = null;
        while (!marked.isEmpty() && marked.peek()[0] <= id) {
            newest = marked.poll();
            inFlight -= newest[1];
            delivered += newest[1];
        }
        acknowledged = id;
        long delay = Math.max(1, nowNanos - newest[2]);
        // Everything acknowledged since this mark went out, over the time it took: the delivery rate (BBR's sample).
        adapt(delay, (delivered - newest[3]) * 1e6 / delay, flight, nowNanos);
        return true;
    }

    private void adapt(long delay, double bytesPerMs, long flight, long now) {
        if (bucketStart == Long.MIN_VALUE || now - bucketStart >= BUCKET_NANOS) {
            System.arraycopy(baseByBucket, 0, baseByBucket, 1, BASE_HISTORY - 1);
            baseByBucket[0] = Long.MAX_VALUE;
            rateByBucket[1] = rateByBucket[0];
            rateByBucket[0] = 0;
            bucketStart = now;
        }
        baseByBucket[0] = Math.min(baseByBucket[0], delay);
        rateByBucket[0] = Math.max(rateByBucket[0], bytesPerMs);
        long base = Long.MAX_VALUE;
        for (long b : baseByBucket) base = Math.min(base, b);
        double rate = Math.max(rateByBucket[0], rateByBucket[1]);
        if (cycleStart == Long.MIN_VALUE) cycleStart = now;
        double gain = GAIN_CYCLE[(int) ((now - cycleStart) / base % GAIN_CYCLE.length)];
        long bdp = (long) (gain * rate * (base + targetNanos) / 1e6);
        window = Math.clamp(Math.min(bdp, flight + (long) ALLOWED_INCREASE * batch), min, max);
        lastDelayNanos = delay;
    }

    public long inFlight() {
        return inFlight;
    }

    public long window() {
        return window;
    }

    /** Mark-to-acknowledgement time of the last acknowledgement, -1 before the first. */
    public long lastDelayNanos() {
        return lastDelayNanos;
    }
}
