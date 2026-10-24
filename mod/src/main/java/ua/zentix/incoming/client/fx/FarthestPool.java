package ua.zentix.incoming.client.fx;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Пул с пределом мест ({@link Fragments}): когда мест нет, новый ближе к глазу вытесняет самого дальнего из тех, кто в
 * пуле, — и кого добавили в этом же тике; иначе не входит. Правило то же, что у перебора всех на каждый новый, но
 * дальних держит куча (O(log n) на новый): залп из восьми взрывов рождал тысячи клочков травы в одном тике, и перебор
 * 1200 мест на каждый стоил десятки мс. Кучу строит первый, кому в тике не хватило места (глаз в тике стоит, куски
 * движутся только тиком), новые этого тика встают в неё сами. Расстояние — квадрат расстояния до глаза.
 *
 * @param <T> кусок
 */
final class FarthestPool<T> {
    private record Ranked<T>(T item, double d) {}

    private final List<T> live = new ArrayList<>();
    private final PriorityQueue<Ranked<T>> far = new PriorityQueue<>((a, b) -> Double.compare(b.d, a.d));
    private final Predicate<T> alive;
    private final Consumer<T> remove;
    /** Тик, в котором построена куча; вытесненные в нём (уже не в куче, в списке — до {@link #purge}). */
    private long farTick = Long.MIN_VALUE;
    private int evicted;

    /** {@code alive} — кусок ещё жив, {@code remove} — убрать его (вытесненный). */
    FarthestPool(Predicate<T> alive, Consumer<T> remove) {
        this.alive = alive;
        this.remove = remove;
    }

    /** Сколько мест занято. */
    int size() {
        return live.size() - evicted;
    }

    /**
     * Место новому на квадрате расстояния {@code d} в тике {@code tick} при пределе {@code cap}: есть — true (если мест
     * не было, самый дальний, кто дальше {@code d}, вытеснен); нет — false. {@code distance} — квадрат расстояния куска.
     */
    boolean admit(double d, int cap, long tick, ToDoubleFunction<T> distance) {
        if (size() < cap) return true;
        if (farTick != tick) {
            far.clear();
            for (T t : live) far.add(new Ranked<>(t, distance.applyAsDouble(t)));
            farTick = tick;
        }
        // в куче — все, кто в списке и ещё не вытеснен (список чистит только purge, и он же сбрасывает кучу); как у
        // перебора, дальним может быть и ушедший сам, но ещё не вычищенный кусок: его место освобождается без remove
        Ranked<T> top = far.peek();
        if (top == null || top.d <= d) return false;
        far.poll();
        if (alive.test(top.item)) remove.accept(top.item);
        evicted++;
        return true;
    }

    /** Новый кусок на квадрате расстояния {@code d} (после {@link #admit}). */
    void add(T item, double d, long tick) {
        live.add(item);
        if (farTick == tick) far.add(new Ranked<>(item, d));
    }

    /** Тик: в списке остаются те, кого {@code keep} оставляет (вытесненные уже не живы). */
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
