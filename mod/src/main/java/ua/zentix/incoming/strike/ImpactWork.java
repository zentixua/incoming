package ua.zentix.incoming.strike;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rounds whose conventional impact work still runs in this world ({@link OrderStatus}): a round's impact holds its blast
 * area ({@code warhead.BlastArea}), and every job of that impact (main explosion, crater, collapse, fuel, debris,
 * fragments, glass) retains the area until it is done; the last release ends the round's impact work. Unsaved, like the
 * work itself: a stopping server finishes it ({@link StrikeWorld#onServerStopping}).
 */
public final class ImpactWork {
    /** Round {@code round}'s impact, for {@code owner} (null: unowned), by {@code weapon}. */
    public record Impact(RoundRef round, @Nullable UUID owner, WeaponType weapon) {}

    /** Round id → its impact and the blast areas it still holds. */
    private final Map<UUID, Held> held = new LinkedHashMap<>();

    private static final class Held {
        final Impact impact;
        int areas;

        Held(Impact impact) {
            this.impact = impact;
        }
    }

    ImpactWork() {}

    /** A blast area of {@code impact} is held. */
    public void hold(Impact impact) {
        held.computeIfAbsent(impact.round().round(), k -> new Held(impact)).areas++;
    }

    /** A blast area of {@code impact} is released: the last one ends the round's impact work. */
    public void release(Impact impact) {
        Held h = held.get(impact.round().round());
        if (h != null && --h.areas <= 0) held.remove(impact.round().round());
    }

    /** Impacts whose work runs, in the order they began. */
    public List<Impact> running() {
        List<Impact> out = new ArrayList<>(held.size());
        for (Held h : held.values()) out.add(h.impact);
        return out;
    }
}
