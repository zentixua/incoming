package ua.zentix.incoming.client.nuclear;

import ua.zentix.incoming.net.S2C;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.ToDoubleFunction;

/** One detonation in one viewing epoch. Ordered anchors delimit the server's batches of chunk removals. */
final class WaveEvents {
    record Receipt(long tick, int ordinal, S2C.NukeDebris packet, List<S2C.NukeDebris.Piece> eligible) {}
    private record Crossing(Receipt batch, S2C.NukeDebris.Piece piece, double due) {}
    private final PriorityQueue<Crossing> crossings = new PriorityQueue<>(Comparator.comparingDouble(Crossing::due));
    private final Map<S2C.NukeDebris, Integer> seen = new HashMap<>();
    private long latest = Long.MIN_VALUE;

    Receipt receive(S2C.NukeDebris packet, long confirmed, ToDoubleFunction<S2C.NukeDebris.Piece> arrival) {
        if (confirmed < 0) return null;
        if (confirmed != latest) {
            latest = confirmed;
            seen.clear();
        }
        int ordinal = seen.size();
        if (seen.putIfAbsent(packet, ordinal) != null) return null;
        List<S2C.NukeDebris.Piece> eligible = new ArrayList<>();
        Receipt receipt = new Receipt(confirmed, ordinal, packet, java.util.Collections.unmodifiableList(eligible));
        for (var piece : packet.pieces()) {
            double due = arrival.applyAsDouble(piece);
            if (confirmed - due > NukeDust.DEBRIS_LATE) continue;
            eligible.add(piece);
            crossings.add(new Crossing(receipt, piece, due));
        }
        return receipt;
    }

    int ordinal(S2C.NukeDebris packet) {
        return seen.getOrDefault(packet, -1);
    }

    void clear() {
        crossings.clear();
        seen.clear();
        latest = Long.MIN_VALUE;
    }

    boolean pending() {
        return !crossings.isEmpty();
    }

    void drain(double shown, BiConsumer<Receipt, List<S2C.NukeDebris.Piece>> apply) {
        if (crossings.isEmpty() || crossings.peek().due > shown) return;
        var ready = new LinkedHashMap<Receipt, List<S2C.NukeDebris.Piece>>();
        do {
            Crossing c = crossings.remove();
            ready.computeIfAbsent(c.batch, ignored -> new ArrayList<>()).add(c.piece);
        } while (!crossings.isEmpty() && crossings.peek().due <= shown);
        ready.forEach(apply);
    }
}
