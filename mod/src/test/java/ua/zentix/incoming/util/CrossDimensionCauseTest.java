package ua.zentix.incoming.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Detonation ids are per dimension ({@code NuclearEvents} is per-level SavedData, {@code nextId} starts at 1 in each). A
 * body dosed by overworld detonation 3 that walks into the Nether and is dosed there by Nether detonation 1 is held by the
 * Nether show at real rate (time.md §8.3 rule 8): the overworld cause is dropped at the portal, never read as a Nether id.
 */
class CrossDimensionCauseTest {
    private static final double REAL = WorldClock.REAL_RATE;

    @Test
    void aShowInAnotherDimensionHoldsTheBody() {
        Map<Object, Map<Integer, Long>> shows = new HashMap<>();
        shows.put("overworld", new HashMap<>(Map.of(3, RecoveryClock.RUNNING)));
        shows.put("nether", new HashMap<>(Map.of(1, RecoveryClock.RUNNING)));
        long[] game = {1000};
        double[] rate = {0};
        Object[] dim = {"overworld"};
        RecoveryClock c = new RecoveryClock(0);
        Runnable step = () -> c.step(game[0], rate[0], 0, 0, dim[0], id -> shows.get(dim[0]).getOrDefault(id, RecoveryClock.NONE));
        step.run();
        c.strike(3);
        // a portal: the first Nether step counts nothing; the Nether detonation 1 doses the body
        dim[0] = "nether";
        game[0] += 20;
        rate[0] += 20;
        step.run();
        c.strike(1);
        double before = c.clock();
        for (int i = 0; i < 60; i++) {
            game[0] += 20;
            rate[0] += 20; // vanilla day: one world-clock tick per game tick
            step.run();
        }
        // one real minute inside the Nether detonation's show: 1200 game ticks of real time
        assertEquals(1200 * REAL, c.clock() - before, 1e-6, "the body healed at the world's rate during its cause's show");
    }
}
