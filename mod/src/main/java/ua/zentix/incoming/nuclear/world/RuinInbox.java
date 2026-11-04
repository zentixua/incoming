package ua.zentix.incoming.nuclear.world;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Detached asynchronous results. Invalidating an owner rejects late results without forgetting live I/O. */
final class RuinInbox<T> {
    private final ArrayDeque<T> ready = new ArrayDeque<>();
    private long generation;
    private int pending;
    private final java.util.function.LongConsumer accounting;
    private final long reservation;

    RuinInbox() { this(n -> {}, 0); }

    RuinInbox(java.util.function.LongConsumer accounting, long reservation) {
        this.accounting = accounting;
        this.reservation = reservation;
    }

    private void release(int count) {
        pending -= count;
        accounting.accept(-reservation * count);
    }

    /** Reserve before submitting work. The completion owns only this inbox, not a world/preparation. */
    synchronized Consumer<T> expect() {
        long revision = generation;
        pending++;
        accounting.accept(reservation);
        AtomicBoolean delivered = new AtomicBoolean();
        return value -> {
            Objects.requireNonNull(value);
            if (!delivered.compareAndSet(false, true)) return;
            synchronized (this) {
                if (revision == generation) ready.addLast(value);
                else release(1);
            }
        };
    }

    @Nullable
    synchronized T peek() { return ready.peekFirst(); }

    @Nullable
    synchronized T poll() {
        T value = ready.pollFirst();
        if (value != null) release(1);
        return value;
    }

    /**
     * Transfer a completed head into a synchronous publication owner. Its existing slot funds the
     * known source/copy/output bound; only positive growth needs new room. No running callback is reclaimed.
     * A refused transition leaves the identical head and its original reservation untouched.
     */
    @Nullable
    synchronized Publication<T> takeForPublication(T expected, long knownBytes, long room) {
        if (knownBytes < 0) throw new IllegalArgumentException("Negative publication bound");
        if (ready.peekFirst() != expected || knownBytes - reservation > room) return null;
        ready.removeFirst();
        accounting.accept(knownBytes - reservation);
        return new Publication<>(this, expected, knownBytes);
    }

    /** Completed detached input stays counted until publication/discard, including invalidation meanwhile. */
    static final class Publication<T> implements AutoCloseable {
        private RuinInbox<T> owner;
        private final T value;
        private long bytes;

        private Publication(RuinInbox<T> owner, T value, long bytes) {
            this.owner = owner; this.value = value; this.bytes = bytes;
        }

        T value() { return value; }

        /** Consumer has charged these bytes before the producer relinquishes them. Server thread only. */
        void releaseToCountedOwner(long transferred) {
            if (transferred < 0 || transferred > bytes || owner == null)
                throw new IllegalArgumentException("Invalid publication transfer");
            synchronized (owner) { owner.accounting.accept(-transferred); bytes -= transferred; }
        }

        @Override public void close() {
            RuinInbox<T> inbox = owner;
            if (inbox == null) return;
            synchronized (inbox) {
                inbox.pending--;
                inbox.accounting.accept(-bytes);
                owner = null;
                bytes = 0;
            }
        }
    }

    /** Counts both running and ready-uncollected work, including invalidated work until it completes. */
    synchronized int pending() {
        return pending;
    }

    synchronized void invalidate() {
        generation++;
        release(ready.size());
        ready.clear();
    }
}
