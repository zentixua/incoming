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
