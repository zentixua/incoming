package ua.zentix.incoming.client.far;

/** Secondary-view ray allowance: no catch-up burst after a slow or skipped feed refresh. */
final class ViewRays {
    private final int traceLimit, clearLimit;
    private long tick = Long.MIN_VALUE;
    private int traces, clears;
    int cursor;

    ViewRays(int traceLimit, int clearLimit) {
        this.traceLimit = traceLimit;
        this.clearLimit = clearLimit;
    }

    boolean begin(long now) {
        if (tick == now) return false;
        tick = now;
        traces = clears = 0;
        return true;
    }

    boolean trace(long now, long previous, int period) {
        if (now - previous < period || traces >= traceLimit) return false;
        traces++;
        return true;
    }

    boolean clear() {
        if (clears >= clearLimit) return false;
        clears++;
        return true;
    }
}
