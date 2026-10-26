package ua.zentix.incoming.client.fx;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Pool with a slot limit ({@link Fragments}): when full, a new piece closer to the eye evicts the farthest in the
 * pool, including pieces added in the same tick; otherwise it is rejected. Same rule as scanning all pieces for each new one, but
 * a heap holds the farthest (O(log n) per new piece): an eight-blast salvo spawned thousands of grass tufts in one tick, and scanning
 * 1200 slots each time cost tens of ms. The first admission to run out of slots builds the heap (eye fixed within the tick, pieces
 * move only on ticks); new pieces in that tick join it themselves. Distance is squared distance to the eye.
 *
 * @param <T> piece
 */
final class FarthestPool<T> {
    private record Ranked<T>(T item, double d) {}

    private final List<T> live = new ArrayList<>();
    private final PriorityQueue<Ranked<T>> far = new PriorityQueue<>((a, b) -> Double.compare(b.d, a.d));
    private final Predicate<T> alive;
    private final Consumer<T> remove;
    /** Tick the heap was built; pieces evicted that tick (already out of the heap, retained in the list until {@link #purge}). */
    private long farTick = Long.MIN_VALUE;
    private int evicted;

    /** {@code alive} — piece still alive, {@code remove} — remove it (evicted). */
    FarthestPool(Predicate<T> alive, Consumer<T> remove) {
        this.alive = alive;
        this.remove = remove;
    }

    /** Occupied slot count. */
    int size() {
        return live.size() - evicted;
    }

    /**
     * Admission for a new piece at squared distance {@code d} in tick {@code tick}, limit {@code cap}: true if admitted (if full,
     * the farthest piece beyond {@code d} is evicted); false otherwise. {@code distance} is a piece's squared distance.
     */
    boolean admit(double d, int cap, long tick, ToDoubleFunction<T> distance) {
        if (size() < cap) return true;
        if (farTick != tick) {
            far.clear();
            for (T t : live) far.add(new Ranked<>(t, distance.applyAsDouble(t)));
            farTick = tick;
        }
        // heap contains every listed piece not yet evicted (only purge cleans the list and resets the heap); as with
        // scanning, a self-expired piece not yet purged may be the farthest: its slot is freed without remove
        Ranked<T> top = far.peek();
        if (top == null || top.d <= d) return false;
        far.poll();
        if (alive.test(top.item)) remove.accept(top.item);
        evicted++;
        return true;
    }

    /** New piece at squared distance {@code d} (after {@link #admit}). */
    void add(T item, double d, long tick) {
        live.add(item);
        if (farTick == tick) far.add(new Ranked<>(item, d));
    }

    /** Tick: the list retains pieces accepted by {@code keep} (evicted ones are already dead). */
    void purge(Predicate<T> keep) {
        live.removeIf(keep.negate());
        evicted = 0;
        far.clear();
        farTick = Long.MIN_VALUE;
    }

    void clear() {
        live.clear();
        far.clear();
        farTick = Long.MIN_VALUE;
        evicted = 0;
    }
}
