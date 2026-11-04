package ua.zentix.incoming.nuclear.world;

import java.util.Objects;
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
        if (maxBytes < 0) throw new IllegalArgumentException("Negative result bound");
        if (maxBytes > Long.MAX_VALUE - OWNER_BYTES || room < maxBytes + OWNER_BYTES) return null;
        long amount = maxBytes + OWNER_BYTES;
        // Admission is server-thread-only. Asynchronous changes to this counter only release bytes.
        long current;
        do {
            current = BYTES.get();
            if (current > Long.MAX_VALUE - amount) return null;
        } while (!BYTES.compareAndSet(current, current + amount));
        return new Lease(maxBytes, amount);
    }

    static final class Lease {
        private final long maximum;
        private long charged;
        @Nullable private Future<?> future;
        private boolean marked, exited, cancelled, released;

        private Lease(long maximum, long charged) {
            this.maximum = maximum;
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
            changeCharge(actual + OWNER_BYTES);
        }

        /** Pool callback: actual callable returned, or a queued callable was removed without execution. */
        synchronized void actualExit() {
            exited = true;
            if (future != null && failedOrCancelled(future)) cancelled = true;
            if (cancelled || !marked) release();
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
            changeCharge(0);
        }

        private void changeCharge(long amount) {
            BYTES.addAndGet(amount - charged);
            charged = amount;
        }
    }
}
