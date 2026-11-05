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
    private transient java.util.function.LongSupplier demandRoom = () -> Long.MAX_VALUE;
    private static final long READ_BYTES = 4L << 20;
    private static final int IO_LIMIT = 64, READ_LIMIT = 16;
    private final long residentTarget;
    /** Current loaded/launch cohort only; historical page keys never acquire this ownership. */
    private transient Pins protectedKeys;
    private static final class Pins extends it.unimi.dsi.fastutil.longs.LongOpenHashSet {
        private static final long serialVersionUID = 1L;
        long bytes() { return 64L + 8L * (n + 1); }
    }
    private final transient it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet toWrite = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    private final transient it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet hot = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    // Demand reads belong to the scar/front consumer, independently of the preparation cursor.
    private final transient it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet demanded = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
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
        spill(store, ctx, charge, headroom, headroom);
    }

    /** Existing consumer work needs resident space, not the reserve for future speculative solvers. */
    void spill(RuinPages store, RuinContext ctx, java.util.function.LongConsumer charge,
               java.util.function.LongSupplier headroom, java.util.function.LongSupplier consumerRoom) {
        pages = store;
        context = ctx;
        accounting = charge;
        room = headroom;
        demandRoom = consumerRoom;
    }

    int pendingWrites() { return writes.size() + toWrite.size(); }

    /** A launch-critical receipt cannot be substituted by unrelated distant writes. */
    boolean pendingWrite(long key) { return writes.containsKey(key) || toWrite.contains(key); }
    int pendingReads() { return reads.size(); }

    void protect(long key) {
        if (protectedKeys == null) protectedKeys = new Pins();
        protectedKeys.add(key);
    }

    void unprotect(long key) {
        if (protectedKeys == null) return;
        protectedKeys.remove(key);
        if (protectedKeys.isEmpty()) protectedKeys = null;
    }

    boolean protectedPlan(long key) { return protectedKeys != null && protectedKeys.contains(key); }
    boolean resident(long key) { return super.get(key) != null; }

    /** Funded reads keep a collector even when their scar is behind capacity-blocked visible
     * work or has unloaded. Rotate refused publications too; one large page cannot hide a
     * smaller completed page. A successful decode enters the normal spillable cache. */
    boolean collectReady(java.util.function.BiConsumer<Long, RuinPlan> consumer) {
        int remaining = demanded.size(); // at most READ_LIMIT
        while (remaining-- > 0) {
            long key = demanded.removeFirstLong();
            var read = reads.get(key);
            if (read == null) continue;
            demanded.addAndMoveToLast(key);
            if (!read.isDone()) continue;
            RuinPlan plan = load(key, true);
            if (plan != null) consumer.accept(key, plan);
            return true;
        }
        return false;
    }

    long bytes() { return bytes + writeBytes + 48L * (super.size() + reads.size() + writes.size())
            + (protectedKeys == null ? 0 : protectedKeys.bytes()); }

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
        int candidates = hot.size();
        while (bytes > residentTarget && candidates-- > 0 && !hot.isEmpty()) {
            long key = hot.removeFirstLong();
            if (protectedPlan(key)) { hot.addAndMoveToLast(key); continue; }
            RuinPlan plan = super.put(key, null);
            if (plan != null) bytes -= plan.bytes();
        }
        while (writes.size() < IO_LIMIT && !toWrite.isEmpty()) {
            long key = toWrite.firstLong();
            RuinPlan plan = super.get(key);
            if (plan == null) { toWrite.removeFirstLong(); continue; }
            if (writes.containsKey(key)) break;
            long reserve = Math.max(4096, plan.bytes() * 3);
            // The encoder allowance, retained source and receipt entry are separate counted owners.
            long ownership = reserve + plan.bytes() + 48;
            if (ownership > room) break;
            room -= ownership;
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
    int probe(long key) { return probe(key, false); }

    private int probe(long key, boolean demand) {
        if (pages == null || key == lastMiss || misses.contains(key)) return -1;
        Boolean existed = pages.existing();
        if (existed == null) return 0;
        if (!existed) return -1;
        if (super.containsKey(key)) return 1;
        if (!reads.containsKey(key) && (reads.size() >= READ_LIMIT || available(demand) < READ_BYTES + 48)) return 0;
        RuinPlan plan = load(key, demand);
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
            return probe(key, true) > 0 ? super.get(key) : null;
        }
        return load(key, true);
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
            if (reads.size() >= READ_LIMIT || room.getAsLong() < READ_BYTES + 48) break;
            accounting.accept(READ_BYTES);
            reads.put(key, pages.read(key));
        }
    }

    private long available(boolean demand) { return (demand ? demandRoom : room).getAsLong(); }

    private RuinPlan load(long key, boolean demand) {
        var read = reads.get(key);
        if (read == null) {
            if (reads.size() >= READ_LIMIT || available(demand) < READ_BYTES + 48) return null;
            accounting.accept(READ_BYTES);
            read = pages.read(key);
            reads.put(key, read);
        }
        if (demand) demanded.add(key);
        if (!read.isDone()) return null;
        // The owned source stays charged until decoding and counted installation finish. A
        // completed page may be larger than its I/O allowance; refusal keeps the same future.
        java.util.Optional<net.minecraft.nbt.CompoundTag> stored;
        long reservation;
        try {
            stored = read.join(); // already complete; no server-thread I/O
            reservation = Math.max(READ_BYTES, stored.isPresent() ? RuinPlan.restorePublicationBytes(stored.get()) + 48 : 0);
        } catch (RuntimeException e) {
            discardRead(key);
            return missing(key, e);
        }
        if (reservation - READ_BYTES > available(demand)) return null;
        accounting.accept(reservation - READ_BYTES);
        reads.remove(key);
        demanded.remove(key);
        try {
            if (stored.isPresent()) {
                RuinPlan plan = RuinPlan.restore(stored.get(), context);
                super.put(key, plan);
                bytes += plan.bytes();
                hot.addAndMoveToLast(key);
                return plan;
            }
        } catch (RuntimeException e) {
            return missing(key, e);
        } finally {
            accounting.accept(-reservation);
        }
        return missing(key, null);
    }

    private RuinPlan missing(long key, RuntimeException error) {
        if (error != null) ua.zentix.incoming.Incoming.LOG.error(
                "Could not restore prepared ruin plan at {}", new net.minecraft.world.level.ChunkPos(key), error);
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
        value.releaseResultToCountedOwner();
        hot.remove(key);
        if (pages != null) toWrite.add(key);
        return old;
    }

    @Override
    public RuinPlan remove(long key) {
        unprotect(key);
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
        protectedKeys = null;
        closeStorage();
        pages = null;
        misses.clear();
    }
}
