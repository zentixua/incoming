package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** Shared preparation/scar ownership: applying or replacing a plan releases its actual retained bytes. */
final class RetainedPlans extends Long2ObjectOpenHashMap<RuinPlan> {
    private static final long serialVersionUID = 1L;
    private long bytes, writeBytes;
    private transient RuinPages pages;
    private transient RuinContext context;
    private final transient it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<Write> writes =
            new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
    private final transient it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<java.util.concurrent.CompletableFuture<java.util.Optional<net.minecraft.nbt.CompoundTag>>> reads =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private transient java.util.function.LongConsumer accounting = n -> {};
    private transient java.util.function.LongSupplier room = () -> Long.MAX_VALUE;
    private static final long READ_BYTES = 4L << 20;
    private static final int IO_LIMIT = 64, READ_LIMIT = 16;
    private final long residentTarget;
    private final transient it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet toWrite = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    private final transient it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet hot = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    // Demand reads belong to the scar/front consumer, independently of the preparation cursor.
    private final transient it.unimi.dsi.fastutil.longs.LongOpenHashSet demanded = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private long lastMiss = Long.MIN_VALUE;
    private boolean discover;
    private final transient it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet misses = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    private java.util.concurrent.CompletableFuture<Void> closing = java.util.concurrent.CompletableFuture.completedFuture(null);

    RetainedPlans() { this(16L << 20); }

    /** Smaller targets let storage regressions exercise real write/evict/read ownership. */
    RetainedPlans(long residentTarget) {
        if (residentTarget < 0) throw new IllegalArgumentException("Negative resident target");
        this.residentTarget = residentTarget;
    }

    void discover() { discover = true; }
    java.util.concurrent.CompletableFuture<Void> closeStorage() {
        if (pages != null) closing = pages.close();
        return closing;
    }
    private record Write(RuinPlan plan, java.util.concurrent.CompletableFuture<Void> receipt) {}

    void spill(RuinPages store, RuinContext ctx, java.util.function.LongConsumer charge, java.util.function.LongSupplier headroom) {
        pages = store;
        context = ctx;
        accounting = charge;
        room = headroom;
    }

    int pendingWrites() { return writes.size() + toWrite.size(); }
    int pendingReads() { return reads.size(); }

    long bytes() { return bytes + writeBytes + 48L * (super.size() + reads.size() + writes.size()); }

    /** Bound both write ownership and read results; no blocking future wait on the tick thread. */
    void pump(long room) {
        if (pages == null) return;
        var it = writes.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            var entry = it.next();
            Write write = entry.getValue();
            if (!write.receipt.isDone()) continue;
            writeBytes -= write.plan.bytes();
            if (write.receipt.isCompletedExceptionally()) {
                ua.zentix.incoming.Incoming.LOG.error("Could not spill prepared ruin plan at {}", new net.minecraft.world.level.ChunkPos(entry.getLongKey()));
                // Keep the resident result. Admission stops safely if disk writes cannot make progress.
                toWrite.add(entry.getLongKey());
                it.remove();
                continue;
            }
            if (super.get(entry.getLongKey()) == write.plan) hot.addAndMoveToLast(entry.getLongKey());
            it.remove();
        }
        while (bytes > residentTarget && !hot.isEmpty()) {
            long key = hot.removeFirstLong();
            RuinPlan plan = super.put(key, null);
            if (plan != null) bytes -= plan.bytes();
        }
        while (writes.size() < IO_LIMIT && !toWrite.isEmpty()) {
            long key = toWrite.firstLong();
            RuinPlan plan = super.get(key);
            if (plan == null) { toWrite.removeFirstLong(); continue; }
            if (writes.containsKey(key)) break;
            long reserve = Math.max(4096, plan.bytes() * 3);
            if (reserve > room) break;
            room -= reserve;
            toWrite.removeFirstLong();
            accounting.accept(reserve);
            var charge = accounting;
            var detached = plan.detached();
            var receipt = pages.write(key, detached::store).whenComplete((unused, failure) -> charge.accept(-reserve));
            writes.put(key, new Write(plan, receipt));
            writeBytes += plan.bytes();
        }
    }

    /** Restart walk: 0 pending, 1 restored, -1 no compatible stored plan. One cursor, no whole-zone scan. */
    int probe(long key) {
        if (pages == null || key == lastMiss || misses.contains(key)) return -1;
        Boolean existed = pages.existing();
        if (existed == null) return 0;
        if (!existed) return -1;
        if (super.containsKey(key)) return 1;
        if (!reads.containsKey(key) && (reads.size() >= READ_LIMIT || room.getAsLong() < READ_BYTES)) return 0;
        RuinPlan plan = load(key);
        if (plan != null) return 1;
        if (reads.containsKey(key)) return 0;
        lastMiss = key;
        return -1;
    }

    /** Pending spill retrieval; callers must yield instead of treating a nonresident plan as absent. */
    static boolean waiting(Long2ObjectOpenHashMap<RuinPlan> plans, long key) {
        return plans instanceof RetainedPlans retained && (retained.containsKey(key) || retained.reads.containsKey(key));
    }

    @Override
    public RuinPlan get(long key) {
        RuinPlan plan = super.get(key);
        if (plan != null) {
            if (hot.contains(key)) hot.addAndMoveToLast(key);
            return plan;
        }
        if (pages == null) return null;
        if (!super.containsKey(key)) {
            if (!discover) return null;
            return probe(key) > 0 ? super.get(key) : null;
        }
        return load(key);
    }

    /** Bounded restart read-ahead; collecting the results still consumes the normal planning units. */
    void prefetch(long[] order, int from) {
        // A ready/live chunk can bypass probe(). Retire read-ahead it no longer needs.
        var queued = reads.keySet().toLongArray();
        for (long key : queued) {
            boolean needed = false;
            for (int i = from; i < Math.min(order.length, from + READ_LIMIT); i++)
                if (order[i] == key) { needed = true; break; }
            if (!demanded.contains(key) && (!needed || super.containsKey(key) && super.get(key) != null)) discardRead(key);
        }
        if (pages == null || !Boolean.TRUE.equals(pages.existing())) return;
        for (int i = from; i < Math.min(order.length, from + READ_LIMIT); i++) {
            long key = order[i];
            if (key == lastMiss || super.containsKey(key) || reads.containsKey(key)) continue;
            if (reads.size() >= READ_LIMIT || room.getAsLong() < READ_BYTES) break;
            accounting.accept(READ_BYTES);
            reads.put(key, pages.read(key));
        }
    }

    private RuinPlan load(long key) {
        var read = reads.get(key);
        if (read == null) {
            if (reads.size() >= READ_LIMIT || room.getAsLong() < READ_BYTES) return null;
            accounting.accept(READ_BYTES);
            read = pages.read(key);
            reads.put(key, read);
        }
        demanded.add(key);
        if (!read.isDone()) return null;
        reads.remove(key);
        demanded.remove(key);
        accounting.accept(-READ_BYTES);
        try {
            var stored = read.join(); // already complete; no server-thread I/O
            if (stored.isPresent()) {
                RuinPlan plan = RuinPlan.restore(stored.get(), context);
                super.put(key, plan);
                bytes += plan.bytes();
                hot.addAndMoveToLast(key);
                return plan;
            }
        } catch (RuntimeException e) {
            ua.zentix.incoming.Incoming.LOG.error("Could not restore prepared ruin plan at {}", new net.minecraft.world.level.ChunkPos(key), e);
        }
        // Failed cache data is not authoritative; the normal solver will reconstruct it.
        super.remove(key);
        misses.addAndMoveToLast(key);
        if (misses.size() > 1024) misses.removeFirstLong();
        return null;
    }

    @Override
    public RuinPlan put(long key, RuinPlan value) {
        discardRead(key);
        misses.remove(key);
        RuinPlan old = super.put(key, value);
        bytes += value.bytes() - (old == null ? 0 : old.bytes());
        hot.remove(key);
        if (pages != null) toWrite.add(key);
        return old;
    }

    @Override
    public RuinPlan remove(long key) {
        misses.addAndMoveToLast(key);
        if (misses.size() > 1024) misses.removeFirstLong();
        RuinPlan old = super.remove(key);
        if (old != null) bytes -= old.bytes();
        toWrite.remove(key);
        hot.remove(key);
        discardRead(key);
        return old;
    }

    private void discardRead(long key) {
        demanded.remove(key);
        var read = reads.remove(key);
        if (read != null) {
            var charge = accounting;
            read.whenComplete((unused, failure) -> charge.accept(-READ_BYTES));
        }
    }

    @Override
    public void clear() {
        super.clear();
        bytes = 0;
        for (var read : reads.values()) {
            var charge = accounting;
            read.whenComplete((unused, failure) -> charge.accept(-READ_BYTES));
        }
        reads.clear();
        demanded.clear();
        writes.clear();
        writeBytes = 0;
        toWrite.clear();
        hot.clear();
        closeStorage();
        pages = null;
        misses.clear();
    }
}
