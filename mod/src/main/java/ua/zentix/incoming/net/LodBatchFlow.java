package ua.zentix.incoming.net;

import java.util.ArrayDeque;

/**
 * Per-connection acknowledged byte window for Voxy LOD delivery, owned by the connection's network event loop. Chunks
 * go out while fewer than {@code window} bytes await the client's acknowledgement; a batch mark follows every
 * {@code batch} bytes, and the client acknowledges marks cumulatively once Voxy has room. Several batches are in
 * flight at once, so delivery follows the line rather than one round trip per batch; the window still bounds what can
 * wait ahead of game packets, whatever buffers sit between server and client (a proxy completes writes at once).
 * Batch IDs survive dimension changes; stale, duplicate and early acknowledgements open nothing.
 */
public final class LodBatchFlow {
    private final long window;
    private final int batch;
    /** Marked batches awaiting acknowledgement: id, bytes. */
    private final ArrayDeque<long[]> marked = new ArrayDeque<>();
    private int sequence, acknowledged, openChunks, markedChunks;
    private long inFlight, open;

    public LodBatchFlow(long window, int batch) {
        this.window = window;
        this.batch = batch;
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
    public int mark() {
        marked.add(new long[]{++sequence, open});
        open = 0;
        markedChunks = openChunks;
        openChunks = 0;
        return sequence;
    }

    public int markedChunks() {
        return markedChunks;
    }

    /** Cumulative: every marked batch up to {@code id}. Only a marked, not yet acknowledged ID is accepted. */
    public boolean acknowledge(int id) {
        if (id <= acknowledged || id > sequence) return false;
        while (!marked.isEmpty() && marked.peek()[0] <= id) inFlight -= marked.poll()[1];
        acknowledged = id;
        return true;
    }

    public long inFlight() {
        return inFlight;
    }

    public int lastMark() {
        return sequence;
    }
}
