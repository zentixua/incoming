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

    /** Reserve before submitting work. The completion owns only this inbox, not a world/preparation. */
    synchronized Consumer<T> expect() {
        long revision = generation;
        pending++;
        AtomicBoolean delivered = new AtomicBoolean();
        return value -> {
            Objects.requireNonNull(value);
            if (!delivered.compareAndSet(false, true)) return;
            synchronized (this) {
                if (revision == generation) ready.addLast(value);
                else pending--;
            }
        };
    }

    @Nullable
    synchronized T poll() {
        T value = ready.pollFirst();
        if (value != null) pending--;
        return value;
    }

    /** Counts both running and ready-uncollected work, including invalidated work until it completes. */
    synchronized int pending() {
        return pending;
    }

    synchronized void invalidate() {
        generation++;
        pending -= ready.size();
        ready.clear();
    }
}
