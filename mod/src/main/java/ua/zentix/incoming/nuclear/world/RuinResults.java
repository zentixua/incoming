package ua.zentix.incoming.nuclear.world;

import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.Nullable;

/** Numeric producer ownership between solver admission and an already-counted consumer. */
final class RuinResults {
    private static final AtomicLong BYTES = new AtomicLong();
    /** Scoped solver context only; restored/removed in finally, never a world-indexed registry. */
    private static final ThreadLocal<Lease> PRODUCER = new ThreadLocal<>();
    /** Fixed estimate for the lease, its future binding and the owner's map entry; no payload multiplier. */
    static final long OWNER_BYTES = 128;

    private RuinResults() {}

    static long bytes() { return BYTES.get(); }

    /** Server thread supplies free shared room, with existing result ownership already subtracted. */
    @Nullable
    static Lease tryReserve(long maxBytes, long room) {
        return tryReserve(maxBytes, 0, room);
    }

    /** Plan and separately transferable fracture-cache output are reserved before either can be produced. */
    @Nullable
    static Lease tryReserve(long maxPlan, long maxFragments, long room) {
        if (maxPlan < 0 || maxFragments < 0) throw new IllegalArgumentException("Negative result bound");
        if (maxPlan > Long.MAX_VALUE - OWNER_BYTES || maxFragments > Long.MAX_VALUE - OWNER_BYTES - maxPlan) return null;
        long amount = maxPlan + maxFragments + OWNER_BYTES;
        if (room < amount) return null;
        // Admission is server-thread-only. Asynchronous changes to this counter only release bytes.
        long current;
        do {
            current = BYTES.get();
            if (current > Long.MAX_VALUE - amount) return null;
        } while (!BYTES.compareAndSet(current, current + amount));
        return new Lease(maxPlan, maxFragments, amount);
    }

    static <T> T producing(Lease lease, Callable<T> task) throws Exception {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(task);
        Lease previous = PRODUCER.get();
        PRODUCER.set(lease);
        try { return task.call(); }
        finally {
            if (previous == null) PRODUCER.remove();
            else PRODUCER.set(previous);
        }
    }

    @Nullable
    static Lease currentProducer() { return PRODUCER.get(); }

    /** Numeric cache ownership carved from a producer; neither context, payload nor world retained. */
    static final class Cache {
        private long charged;

        private Cache(long charged) { this.charged = charged; }

        synchronized long bytes() { return charged; }

        synchronized void release() {
            BYTES.addAndGet(-charged);
            charged = 0;
        }
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

        /** Transfer only after the context will own the cache; global bytes do not change at this seam. */
        synchronized Cache splitCache(long bytes) {
            if (bytes < 0) throw new IllegalArgumentException("Negative fracture bytes");
            if (marked || exited || released || bytes > fragments)
                throw new IllegalStateException("Fracture cache exceeds producer allowance or production ended");
            Cache cache = new Cache(bytes);
            fragments -= bytes;
            charged -= bytes;
            return cache;
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
            fragments = 0;
            empty = plan == RuinPlan.EMPTY;
            changeCharge(actual + OWNER_BYTES);
        }

        /** Pool callback: actual callable returned, or a queued callable was removed without execution. */
        synchronized void actualExit() {
            exited = true;
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
            fragments = 0;
            future = null;
            changeCharge(0);
        }

        private void changeCharge(long amount) {
            BYTES.addAndGet(amount - charged);
            charged = amount;
        }
    }
}
