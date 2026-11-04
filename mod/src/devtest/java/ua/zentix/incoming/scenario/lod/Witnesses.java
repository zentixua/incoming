package ua.zentix.incoming.scenario.lod;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Blocking external reads never hold the monitor used by the producer/reset path. Values carry their owner epoch. */
public final class Witnesses {
    private Witnesses() {}

    public static <T> void check(List<T> pending, Predicate<T> read, Consumer<T> confirmed) {
        List<T> snapshot;
        synchronized (pending) { snapshot = List.copyOf(pending); }
        for (T value : snapshot) {
            if (!read.test(value)) continue;
            synchronized (pending) {
                if (pending.remove(value)) confirmed.accept(value);
            }
        }
    }
}
