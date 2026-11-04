package ua.zentix.incoming.nuclear.world;

import java.util.LinkedHashMap;
import org.jetbrains.annotations.Nullable;

/** Optional fixed-budget reuse. Each borrowing worker separately owns all nine fragments through actual exit. */
final class RuinFragmentCache implements AutoCloseable {
    private final long limit;
    private final RuinResults.CacheBudget budget;
    private final LinkedHashMap<Long, Blast> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes, hits;
    private boolean closed;

    RuinFragmentCache(long limit, RuinResults.CacheBudget budget) {
        if (limit < 0) throw new IllegalArgumentException("Negative fracture cache limit");
        this.limit = limit;
        this.budget = budget;
    }

    @Nullable
    synchronized Blast get(long key, RuinWindow.Stamp stamp) {
        if (closed) return null;
        Blast value = entries.get(key);
        if (value == null || !value.stamp().same(stamp)) return null;
        hits++;
        return value;
    }

    synchronized void put(long key, Blast value) {
        if (closed) return;
        long amount = RuinPlan.fragmentBytes(value);
        if (amount > limit) return; // The task still owns an oversized fragment; caching is optional.
        Blast old = entries.remove(key);
        if (old != null) bytes -= RuinPlan.fragmentBytes(old);
        while (amount > limit - bytes && !entries.isEmpty()) {
            var oldest = entries.entrySet().iterator();
            bytes -= RuinPlan.fragmentBytes(oldest.next().getValue());
            oldest.remove();
        }
        entries.put(key, value);
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
