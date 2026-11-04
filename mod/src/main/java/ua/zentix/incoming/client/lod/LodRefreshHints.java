package ua.zentix.incoming.client.lod;

import java.util.LinkedHashSet;

/** Bounded best-effort recovery hints, never terrain authority or a foreign-ingestion fence. */
public final class LodRefreshHints {
    private final int recentLimit, pendingLimit;
    private final LinkedHashSet<Long> recent = new LinkedHashSet<>(), pending = new LinkedHashSet<>();
    private Object session;

    public LodRefreshHints(int recentLimit, int pendingLimit) {
        if (recentLimit <= 0 || pendingLimit <= 0) throw new IllegalArgumentException("positive limits required");
        this.recentLimit = recentLimit;
        this.pendingLimit = pendingLimit;
    }

    public synchronized void begin(Object owner) {
        if (owner == null) throw new IllegalArgumentException("session required");
        if (session == owner) return;
        reset();
        session = owner;
    }

    public synchronized void remember(Object owner, long chunk) {
        if (session != owner || session == null) return;
        recent.remove(chunk);
        recent.add(chunk);
        trim(recent, recentLimit);
        // A foreign delivery can cross an Incoming submission. Finishing ours cannot cancel its refresh hint.
    }

    public synchronized void overwritten(Object owner, long chunk) {
        if (session == owner && session != null && recent.contains(chunk)) request(owner, chunk);
    }

    /** Includes a rejected Incoming column even if it never made it into the renderer. */
    public synchronized void request(Object owner, long chunk) {
        if (session != owner || session == null) return;
        pending.add(chunk);
        trim(pending, pendingLimit);
    }

    public synchronized long[] take(Object owner, int limit) {
        if (limit < 0) throw new IllegalArgumentException("negative limit");
        if (session != owner || session == null) return new long[0];
        long[] result = new long[Math.min(limit, pending.size())];
        var it = pending.iterator();
        for (int i = 0; i < result.length; i++) {
            result[i] = it.next();
            it.remove();
        }
        return result;
    }

    public synchronized void reset() {
        session = null;
        recent.clear();
        pending.clear();
    }

    public synchronized int recentSize() { return recent.size(); }
    public synchronized int pendingSize() { return pending.size(); }

    private static void trim(LinkedHashSet<Long> set, int limit) {
        if (set.size() > limit) set.removeFirst();
    }
}
