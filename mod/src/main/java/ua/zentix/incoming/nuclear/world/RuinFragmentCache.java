package ua.zentix.incoming.nuclear.world;

import java.util.LinkedHashMap;
import org.jetbrains.annotations.Nullable;

/** Optional fixed-budget reuse. Each borrowing worker separately owns all nine fragments through actual exit. */
final class RuinFragmentCache implements AutoCloseable {
    private final long limit;
    private final RuinResults.CacheBudget budget;
    private record Entry(Blast value, boolean blockDamage, boolean treeFall) {}
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes, hits;
    private boolean closed;

    RuinFragmentCache(long limit, RuinResults.CacheBudget budget) {
        if (limit < 0) throw new IllegalArgumentException("Negative fracture cache limit");
        this.limit = limit;
        this.budget = budget;
    }

    @Nullable
    synchronized Blast get(long key, RuinWindow.Stamp stamp, boolean blockDamage, boolean treeFall) {
        if (closed) return null;
        Entry entry = entries.get(key);
        if (entry == null || entry.blockDamage != blockDamage || entry.treeFall != treeFall
                || !entry.value.stamp().same(stamp)) return null;
        hits++;
        return entry.value;
    }

    /** Entry record in addition to the fragment's map/key estimate. */
    static long entryBytes(Blast value) { return RuinPlan.fragmentBytes(value) + 32; }

    synchronized void put(long key, Blast value, boolean blockDamage, boolean treeFall) {
        if (closed) return;
        long amount = entryBytes(value);
        if (amount > limit) return; // The task still owns an oversized fragment; caching is optional.
        Entry old = entries.remove(key);
        if (old != null) bytes -= entryBytes(old.value);
        while (amount > limit - bytes && !entries.isEmpty()) {
            var oldest = entries.entrySet().iterator();
            bytes -= entryBytes(oldest.next().getValue().value);
            oldest.remove();
        }
        entries.put(key, new Entry(value, blockDamage, treeFall));
        bytes += amount;
    }

    synchronized long retainedBytes() { return bytes; }
    synchronized long hits() { return hits; }
    synchronized int size() { return entries.size(); }
    synchronized long reservedBytes() { return closed ? 0 : budget.bytes(); }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        entries.clear();
        bytes = 0;
        budget.close();
    }
}
