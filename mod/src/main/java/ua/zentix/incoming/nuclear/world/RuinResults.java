package ua.zentix.incoming.nuclear.world;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.Nullable;

/** Numeric producer ownership between solver admission and an already-counted consumer. */
final class RuinResults {
    private static final AtomicLong BYTES = new AtomicLong();
    /** Fixed estimate for the lease, its future binding and the owner's map entry; no payload multiplier. */
    static final long OWNER_BYTES = 128;

    private RuinResults() {}

    static long bytes() { return BYTES.get(); }

    /** Server thread supplies free shared room, with existing result ownership already subtracted. */
    @Nullable
    static Lease tryReserve(long maxBytes, long room) {
        return tryReserve(maxBytes, 0, room);
    }

    /** Temporary fractures stay charged through actual exit, independently of a completed plan transfer. */
    @Nullable
    static Lease tryReserve(long maxPlan, long maxFragments, long room) {
        if (maxPlan < 0 || maxFragments < 0) throw new IllegalArgumentException("Negative result bound");
        if (maxPlan > Long.MAX_VALUE - OWNER_BYTES || maxFragments > Long.MAX_VALUE - OWNER_BYTES - maxPlan) return null;
        long amount = maxPlan + maxFragments + OWNER_BYTES;
        if (room < amount) return null;
        if (!claim(amount)) return null;
        return new Lease(maxPlan, maxFragments, amount);
    }

    /** Fixed cache capacity remains fully owned, including when its bounded map is empty. */
    @Nullable
    static CacheBudget tryReserveCache(long limit, long room) {
        if (limit < 0) throw new IllegalArgumentException("Negative cache limit");
        if (limit > Long.MAX_VALUE - OWNER_BYTES) return null;
        long amount = limit + OWNER_BYTES;
        if (room < amount || !claim(amount)) return null;
        return new CacheBudget(amount);
    }

    private static boolean claim(long amount) {
        // Admission is server-thread-only. Asynchronous changes to this counter only release bytes.
        long current;
        do {
            current = BYTES.get();
            if (current > Long.MAX_VALUE - amount) return false;
        } while (!BYTES.compareAndSet(current, current + amount));
        return true;
    }

    /** Initialized only when a solver asks for a producer scope; no world-indexed registry. */
    private static final class ProducerScope {
        private static final ThreadLocal<Lease> CURRENT = new ThreadLocal<>();
    }

    static <T> T producing(Lease lease, Callable<T> task) throws Exception {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(task);
        Lease previous = ProducerScope.CURRENT.get();
        ProducerScope.CURRENT.set(lease);
        try { return task.call(); }
        finally {
            if (previous == null) ProducerScope.CURRENT.remove();
            else ProducerScope.CURRENT.set(previous);
        }
    }

    @Nullable
    static Lease currentProducer() { return ProducerScope.CURRENT.get(); }

    /** Numeric capacity ownership only. The caller clears its map before closing the budget. */
    static final class CacheBudget implements AutoCloseable {
        private long charged;

        private CacheBudget(long charged) { this.charged = charged; }

        synchronized long bytes() { return charged; }

        synchronized void release() {
            BYTES.addAndGet(-charged);
            charged = 0;
        }

        @Override
        public void close() { release(); }
    }

    static final class Lease {
        private final long maximum;
        private long charged, fragments;
        @Nullable private Future<?> future;
        private boolean marked, empty, exited, cancelled, released;

        private Lease(long maximum, long fragments, long charged) {
            this.maximum = maximum;
            this.fragments = fragments;
            this.charged = charged;
        }

        /** Called inside the solver, before publishing its result into the Future. */
        synchronized void markResult(RuinPlan plan) {
            Objects.requireNonNull(plan);
            if (marked || exited || released) throw new IllegalStateException("Result owner already completed");
            long actual = plan.bytes();
            if (actual > maximum) throw new IllegalStateException("Ruin result exceeds source bound: " + actual + " > " + maximum);
            // Attach before shrinking, so an invalid second owner cannot silently lose its reservation.
            if (plan != RuinPlan.EMPTY) plan.attachResult(this);
            marked = true;
            empty = plan == RuinPlan.EMPTY;
            changeCharge(actual + OWNER_BYTES + fragments);
        }

        /** Pool callback: actual callable returned, or a queued callable was removed without execution. */
        synchronized void actualExit() {
            if (exited) return;
            exited = true;
            changeCharge(charged - fragments);
            fragments = 0;
            if (future != null && failedOrCancelled(future)) cancelled = true;
            if (cancelled || !marked || empty) release();
        }

        /** Must precede Future.cancel: cancellation does not establish actual callable exit. */
        synchronized void cancel() {
            cancelled = true;
            if (exited) release();
        }

        /** Admission failed before pool submission; there is no callable that can still allocate a result. */
        synchronized void abortBeforeSubmit() {
            if (marked || future != null) throw new IllegalStateException("Result owner already submitted");
            cancelled = exited = true;
            release();
        }

        /** Handles both ordinary binding and a solver/cancellation that finished before submit returned. */
        synchronized void bindFuture(Future<?> bound) {
            Objects.requireNonNull(bound);
            if (future != null && future != bound) throw new IllegalStateException("Result future already bound");
            if (!released) future = bound;
            if (failedOrCancelled(bound)) {
                cancelled = true;
                if (exited) release();
            }
        }

        /** Unbind while queued promotion terminates the old future and installs its replacement. */
        synchronized void beginTransfer() { future = null; }

        /** Consumer has already added plan.bytes() to its counted owner, including local handoff. */
        synchronized void releaseToCountedOwner() { release(); }

        /** Collection/discard breaks the future/result/lease cycle without changing ownership. */
        synchronized void detachFuture() { future = null; }

        private static boolean failedOrCancelled(Future<?> bound) {
            return bound.isDone() && bound.state() != Future.State.SUCCESS;
        }

        private void release() {
            if (released) return;
            released = true;
            future = null;
            if (exited) fragments = 0;
            changeCharge(fragments);
        }

        private void changeCharge(long amount) {
            BYTES.addAndGet(amount - charged);
            charged = amount;
        }
    }
}
