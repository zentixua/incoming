package ua.zentix.incoming.util;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shared state's recovery clock ({@code docs/design/time.md} §8.3 rule 8): real rate while any cause is in its show, then
 * the world's rate through the rate blend, every jump counting; a body ages only while present.
 */
class RecoveryClockTest {
    /** Vanilla day: the world's clock moves one world-clock tick per game tick (72× real). */
    private static final double VANILLA = 1;
    private static final double REAL = WorldClock.REAL_RATE;
    private static final String OVERWORLD = "overworld";

    /** A world: game time, the clock without jumps and the jump counters, stepping a recovery clock once a second. */
    private static final class World {
        long game = 1000;
        double rate, counted, unseen;
        double k = VANILLA;
        Object dim = OVERWORLD;
        final Map<Integer, Long> shows = new HashMap<>();

        void run(RecoveryClock c, long ticks) {
            for (long t = 0; t < ticks; t += 20) {
                game += 20;
                rate += 20 * Math.max(REAL, k);
                step(c);
            }
        }

        void step(RecoveryClock c) {
            c.step(game, rate, counted, unseen, dim, id -> shows.getOrDefault(id, RecoveryClock.NONE));
        }
    }

    private static double runFor(World w, RecoveryClock c, long ticks) {
        double before = c.clock();
        w.run(c, ticks);
        return c.clock() - before;
    }

    @Test
    void realDuringTheShowThenTheWorldsRateThroughTheBlend() {
        World w = new World();
        RecoveryClock c = new RecoveryClock(0);
        w.step(c);
        c.strike(3);
        w.shows.put(3, RecoveryClock.RUNNING);
        // 10 real minutes in the show: 10 minutes of recovery, not 12 world hours
        assertEquals(12_000 * REAL, runFor(w, c, 12_000), 1e-6);
        // the show ends: the blend's 60 s average half the world's excess, then the world's rate
        w.shows.put(3, w.game);
        double blend = runFor(w, c, 1200);
        assertEquals(1200 * REAL + 1200 * (VANILLA - REAL) * 0.5, blend, 1e-6);
        assertEquals(1200 * VANILLA, runFor(w, c, 1200), 1e-6);
    }

    /** Never faster than the world: at every step the rate is between real and k. */
    @Test
    void neverFasterThanTheWorld() {
        World w = new World();
        RecoveryClock c = new RecoveryClock(0);
        w.step(c);
        c.until(w.game + 600);
        for (int i = 0; i < 200; i++) {
            double d = runFor(w, c, 20);
            assertTrue(d >= 20 * REAL - 1e-9 && d <= 20 * VANILLA + 1e-9, "step " + i + ": " + d);
        }
    }

    /** Two strikes in different phases: one after its show, one in it — real rate until both shows are over. */
    @Test
    void twoStrikesInDifferentPhases() {
        World w = new World();
        RecoveryClock c = new RecoveryClock(0);
        w.step(c);
        c.strike(1);
        w.shows.put(1, w.game - 6000);
        // the first strike's show ended 5 minutes ago: world rate
        assertEquals(200 * VANILLA, runFor(w, c, 200), 1e-6);
        // the second strike's show runs: real again, although the first one's is long over
        c.strike(2);
        w.shows.put(2, RecoveryClock.RUNNING);
        assertEquals(2400 * REAL, runFor(w, c, 2400), 1e-6);
        // the second ends: the blend from its end
        w.shows.put(2, w.game);
        assertEquals(1200 * REAL + 1200 * (VANILLA - REAL) * 0.5, runFor(w, c, 1200), 1e-6);
        // an older cause does not replace the youngest
        c.strike(1);
        assertEquals(2, c.strike());
    }

    /** A conventional cause holds until its show ends; a jump nobody was awake for ends it at once. */
    @Test
    void conventionalShowAndCountedJump() {
        World w = new World();
        RecoveryClock c = new RecoveryClock(0);
        w.step(c);
        c.until(w.game + 6000);
        assertEquals(200 * REAL, runFor(w, c, 200), 1e-9);
        // nobody awake sleeps 8 hours: the jump counts, and the show is over
        w.counted += 8000;
        double d = runFor(w, c, 20);
        assertEquals(8000 + 20 * REAL, d, 1e-6);
        assertTrue(c.until() <= w.game);
        // the blend from that moment, not real time until the planned end
        assertTrue(runFor(w, c, 1200) > 1200 * REAL * 10);
    }

    /** Every jump counts: the unseen ones too (a sleep with someone awake heals as a night does). */
    @Test
    void unseenJumpsCount() {
        World w = new World();
        RecoveryClock c = new RecoveryClock(0);
        w.step(c);
        c.strike(4);
        w.shows.put(4, RecoveryClock.RUNNING);
        w.unseen += 9000;
        assertEquals(9000 + 20 * REAL, runFor(w, c, 20), 1e-6);
        // the show still runs (nobody saw the jump end it)
        assertEquals(200 * REAL, runFor(w, c, 200), 1e-9);
        // event time counts real time and counted jumps only
        double event = c.event();
        w.unseen += 1000;
        w.counted += 500;
        w.run(c, 20);
        assertEquals(event + 500 + 20 * REAL, c.event(), 1e-6);
    }

    /** A body away for a night (offline, unloaded) ages nothing: the step after the gap counts no time and no jump. */
    @Test
    void anOfflineNightAgesNothing() {
        World w = new World();
        RecoveryClock c = new RecoveryClock(5000);
        w.step(c);
        w.run(c, 200);
        double left = c.clock();
        // logged out: the world runs 10 real minutes and a night nobody saw is slept through
        w.game += 12_000;
        w.rate += 12_000;
        w.counted += 10_000;
        w.step(c);
        assertEquals(left, c.clock(), 0);
        assertEquals(0, c.over(20), 0);
        // present again: the world's rate as before
        assertEquals(200 * VANILLA, runFor(w, c, 200), 1e-6);
        // a gap within PRESENT_GAP is presence
        w.game += RecoveryClock.PRESENT_GAP;
        w.rate += RecoveryClock.PRESENT_GAP;
        double before = c.clock();
        w.step(c);
        assertEquals(RecoveryClock.PRESENT_GAP, c.clock() - before, 1e-6);
    }

    /**
     * A new dimension's clocks are its own (the jump counters; on a client the first clocks a server tells): the step into
     * it counts nothing, not even a resynchronised clock, and the next one runs as usual.
     */
    @Test
    void aDimensionChangeIsNoJump() {
        World w = new World();
        RecoveryClock c = new RecoveryClock(0);
        w.step(c);
        w.counted = 50_000;
        w.rate += 1_000_000;
        w.dim = "the_nether";
        assertEquals(0, runFor(w, c, 20), 0);
        assertEquals(20 * VANILLA, runFor(w, c, 20), 1e-6);
        // the same tick in another dimension is no division by zero
        w.dim = OVERWORLD;
        w.step(c);
        assertEquals(0, c.over(20), 0);
    }

    /** A district never leaves: a long gap is time that passed. It falls behind the world only through its show and the blend. */
    @Test
    void districtFallsBehindOnlyDuringTheShowAndTheBlend() {
        World w = new World();
        RecoveryClock c = RecoveryClock.district();
        w.step(c);
        c.until(w.game + 600);
        w.run(c, 600);
        assertEquals(600 * (VANILLA - REAL), c.behind(), 1e-6);
        assertFalse(c.settled());
        w.run(c, 1200);
        double afterBlend = 600 * (VANILLA - REAL) + 1200 * (VANILLA - REAL) * 0.5;
        assertEquals(afterBlend, c.behind(), 1e-6);
        w.run(c, 20);
        assertTrue(c.settled());
        assertEquals(afterBlend, c.behind(), 1e-6);
        // a long gap (a server busy elsewhere) is time that passed, at the world's rate
        w.game += 400;
        w.rate += 400;
        double before = c.clock();
        w.step(c);
        assertEquals(400, c.clock() - before, 1e-6);
    }

    /** A saved clock comes back with its causes; a save with only the clock reads as one with no causes. */
    @Test
    void savesLoad() {
        RecoveryClock c = new RecoveryClock(1234.5);
        c.strike(7);
        c.until(99);
        var json = RecoveryClock.CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow();
        RecoveryClock back = RecoveryClock.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(1234.5, back.clock(), 0);
        assertEquals(7, back.strike());
        assertEquals(99, back.until());
        JsonObject old = new JsonObject();
        old.addProperty("clock", 42.0);
        RecoveryClock oldBack = RecoveryClock.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        assertEquals(42, oldBack.clock(), 0);
        assertEquals(42, oldBack.event(), 0);
        assertEquals(RecoveryClock.NO_STRIKE, oldBack.strike());
        assertEquals(RecoveryClock.NONE, oldBack.until());
    }

    /** The blend's integral is the strikes' blend ({@link StrikeClock#blend}) for a constant rate. */
    @Test
    void blendMatchesTheStrikeClock() {
        double k = 72;
        for (double r : new double[]{5, 30, 59, 60, 61, 300}) {
            double strike = StrikeClock.blend(r, k * r, Double.NaN);
            assertEquals(strike, r + (k - 1) * RecoveryClock.ramped(r), 1e-9, "r " + r);
        }
    }
}
