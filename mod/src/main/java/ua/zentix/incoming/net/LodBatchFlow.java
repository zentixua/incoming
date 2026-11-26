package ua.zentix.incoming.net;

import java.util.ArrayDeque;

/**
 * Per-connection acknowledged byte window for Voxy LOD delivery, owned by the connection's network event loop. Chunks
 * go out while fewer than {@link #window()} bytes await the client's acknowledgement; a batch mark follows every
 * {@code batch} bytes, and the client acknowledges marks cumulatively once Voxy has room. Several batches are in
 * flight at once, so delivery follows the line rather than one round trip per batch; the window bounds what can wait
 * ahead of game packets, whatever buffers sit between server and client (a proxy completes writes at once).
 * <p>
 * The window follows the queue it builds, by LEDBAT's law (RFC 6817, section 2.4.2): each acknowledgement's mark-to-ack
 * time minus the lowest one seen in the last one to two minutes is the queueing delay; below {@code target} the window
 * grows, above it shrinks, in proportion to the distance, within {@code [min, max]}. A fast line keeps the whole
 * window; a slow line holds a queue of about {@code target}. Delay that is not ours (a full Voxy ingest queue, other
 * mods filling the same connection) also closes it, down to {@code min}. Batch IDs survive dimension changes; stale,
 * duplicate and early acknowledgements open nothing.
 */
public final class LodBatchFlow {
    /** Base delay history (RFC 6817 keeps minutes): the lowest delay of this and the previous minute. */
    static final long BASE_BUCKET_NANOS = 60_000_000_000L;
    /** Growth per round trip at zero queueing delay, in batches (RFC 6817 GAIN, here per batch instead of per MSS). */
    static final double GAIN = 4;

    private final long min, max, targetNanos;
    private final int batch;
    /** Marked batches awaiting acknowledgement: id, bytes, mark time. */
    private final ArrayDeque<long[]> marked = new ArrayDeque<>();
    private int sequence, acknowledged, openChunks, markedChunks;
    private long inFlight, open;
    private double window;
    private long baseNanos = Long.MAX_VALUE, previousBaseNanos = Long.MAX_VALUE, bucketStart = Long.MIN_VALUE;
    private long lastDelayNanos = -1;

    public LodBatchFlow(long min, long max, int batch, long targetNanos) {
        this.min = min;
        this.max = max;
        this.batch = batch;
        this.targetNanos = targetNanos;
        this.window = min;
    }

    /** Another chunk may go now: the window has room (with nothing in flight, one chunk larger than it stands alone). */
    public boolean hasRoom() {
        return inFlight < (long) window;
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
        marked.add(new long[]{++sequence, open, nowNanos});
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
        long bytes = 0, markedAt = nowNanos;
        while (!marked.isEmpty() && marked.peek()[0] <= id) {
            long[] m = marked.poll();
            bytes += m[1];
            markedAt = m[2];
        }
        inFlight -= bytes;
        acknowledged = id;
        adapt(Math.max(0, nowNanos - markedAt), bytes, nowNanos);
        return true;
    }

    private void adapt(long delay, long bytes, long now) {
        if (bucketStart == Long.MIN_VALUE || now - bucketStart >= BASE_BUCKET_NANOS) {
            previousBaseNanos = baseNanos;
            baseNanos = Long.MAX_VALUE;
            bucketStart = now;
        }
        baseNanos = Math.min(baseNanos, delay);
        long base = Math.min(baseNanos, previousBaseNanos);
        double offTarget = Math.clamp((targetNanos - (double) (delay - base)) / targetNanos, -1, 1);
        window = Math.clamp(window + GAIN * offTarget * bytes * batch / window, min, max);
        lastDelayNanos = delay;
    }

    public long inFlight() {
        return inFlight;
    }

    public long window() {
        return (long) window;
    }

    /** Mark-to-acknowledgement time of the last acknowledgement, -1 before the first. */
    public long lastDelayNanos() {
        return lastDelayNanos;
    }
}
