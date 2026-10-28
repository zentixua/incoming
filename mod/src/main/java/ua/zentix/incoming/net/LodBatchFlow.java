package ua.zentix.incoming.net;

/**
 * Per-connection LOD flow control, following vanilla 1.21.1 PlayerChunkSender's client-reported chunks/tick and
 * fractional quota. A single bootstrap chunk measures the connection; thereafter only acknowledged receive rates
 * size batches. One unacknowledged batch bounds work even when a proxy accepts writes faster than its downstream.
 * Server thread only. Batch IDs survive dimension changes; stale/duplicate/early acknowledgements cannot open a window.
 */
public final class LodBatchFlow {
    // Same receive-rate bounds as vanilla PlayerChunkSender, not an assumed network byte rate.
    static final float MIN_RATE = 0.01f, MAX_RATE = 64;
    private float rate = 1, quota;
    private int sequence, batch, limit, sent;
    private boolean active, finished;

    /** Start at most one batch, if the fractional quota permits. Call at most once per server tick. */
    public boolean begin() {
        if (active) return false;
        quota = Math.min(quota + rate, Math.max(1, rate));
        if (quota < 1) return false;
        batch = ++sequence;
        limit = (int) quota;
        sent = 0;
        active = true;
        finished = false;
        return true;
    }

    public int id() { return batch; }
    public int sent() { return sent; }
    public boolean active() { return active; }
    public boolean sending() { return active && !finished; }
    public boolean hasRoom() { return sending() && sent < limit; }

    public void chunkSent() {
        sent++;
    }

    public void finish() {
        finished = true;
        quota -= sent;
    }

    /** Only the matching, fully sent batch can be acknowledged. Invalid rates select vanilla's minimum. */
    public boolean acknowledge(int id, float desiredChunksPerTick) {
        if (!active || !finished || id != batch) return false;
        rate = Float.isFinite(desiredChunksPerTick) ? Math.clamp(desiredChunksPerTick, MIN_RATE, MAX_RATE) : MIN_RATE;
        active = false;
        return true;
    }
}
