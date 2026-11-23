package ua.zentix.incoming.client.nuclear;

import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.net.S2C;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BiConsumer;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;

/** One detonation in one viewing epoch. Ordered anchors delimit the server's batches of chunk removals. */
final class WaveEvents {
    record Receipt(long tick, int ordinal, S2C.NukeDebris packet, long receivedAt) {
        // Several pieces can cross together. Group by batch without rehashing the entire payload per piece.
        @Override
        public int hashCode() { return 31 * Long.hashCode(tick) + ordinal; }
    }
    private record Crossing(Receipt batch, S2C.NukeDebris.Piece piece, double due) {}
    private final PriorityQueue<Crossing> crossings = new PriorityQueue<>(Comparator.comparingDouble(Crossing::due));
    // A duplicate decoded under the same anchor can be a distinct packet object. The usual first batch
    // enters an empty list without hashing its pieces. Only other arrivals under this anchor need comparison.
    private final List<S2C.NukeDebris> seen = new ArrayList<>();
    private long latest = Long.MIN_VALUE;

    Receipt receive(S2C.NukeDebris packet, long confirmed, long receivedAt, ToDoubleFunction<S2C.NukeDebris.Piece> arrival) {
        if (confirmed < 0) return null;
        if (confirmed != latest) {
            latest = confirmed;
            seen.clear();
        }
        if (seen.contains(packet)) return null;
        var receipt = new Receipt(confirmed, seen.size(), packet, receivedAt);
        seen.add(packet);
        for (var piece : packet.pieces()) {
            double due = arrival.applyAsDouble(piece);
            if (confirmed - due > NukeDust.DEBRIS_LATE) continue;
            crossings.add(new Crossing(receipt, piece, due));
        }
        return receipt;
    }

    int ordinal(S2C.NukeDebris packet) {
        return seen.indexOf(packet);
    }

    void clear() {
        crossings.clear();
        seen.clear();
        latest = Long.MIN_VALUE;
    }

    boolean pending() {
        return !crossings.isEmpty();
    }

    void drain(double shown, BiConsumer<Receipt, List<S2C.NukeDebris.Piece>> apply,
               @Nullable ObjDoubleConsumer<Receipt> expired) {
        if (crossings.isEmpty() || crossings.peek().due > shown) return;
        var ready = new LinkedHashMap<Receipt, List<S2C.NukeDebris.Piece>>();
        do {
            Crossing c = crossings.remove();
            if (shown - c.due <= NukeDust.DEBRIS_LATE) {
                ready.computeIfAbsent(c.batch, ignored -> new ArrayList<>()).add(c.piece);
            } else if (expired != null) {
                expired.accept(c.batch, c.due);
            }
        } while (!crossings.isEmpty() && crossings.peek().due <= shown);
        ready.forEach(apply);
    }
}
