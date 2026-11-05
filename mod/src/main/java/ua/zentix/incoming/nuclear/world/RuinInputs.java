package ua.zentix.incoming.nuclear.world;

import java.util.concurrent.atomic.AtomicLong;

/** Numeric ownership of inputs still reachable by actual workers; never retains a context or world. */
final class RuinInputs {
    private static final AtomicLong DETACHED = new AtomicLong();
    private int workers;
    private long retired, detachedBytes;
    private boolean detached;

    synchronized void acquire() {
        if (detached) throw new IllegalStateException("Ruin inputs already detached");
        workers++;
    }

    /** Called exactly once by the pool after execution, or after removing an unstarted callable. */
    synchronized void release() {
        if (--workers < 0) throw new IllegalStateException("Ruin input owner released twice");
        if (workers == 0) {
            retired = 0;
            DETACHED.addAndGet(-detachedBytes);
            detachedBytes = 0;
        }
    }

    /** Removed/replaced snapshots may still be in a Grid even while this context remains attached. */
    synchronized void retire(long bytes) {
        if (workers > 0) retired += bytes;
    }

    synchronized long retained(long current) {
        return detached ? 0 : current + retired;
    }

    /** Transfer the existing estimate before clearing server-owned containers or removing the context. */
    synchronized void detach(long current) {
        if (detached) return;
        detached = true;
        if (workers > 0) {
            detachedBytes = current + retired;
            DETACHED.addAndGet(detachedBytes);
        }
    }

    static long detachedBytes() {
        return DETACHED.get();
    }
}
