package ua.zentix.incoming.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The world's rate k comes from the day's running steps only (review of PR #778, round 1). A single step after a stop, or
 * the first step after a load, spans a gap the day did not run (or only part of one) and must not set k.
 */
class WorldRateProbeTest {
    /**
     * A stopped vanilla day (an hour of real time) nudged by {@code /time add 100}: k must stay 72 (rule 6), so the next real
     * hour still counts 72 world hours. Head fa9a78e70: k = max(1, 100 / (72 001 / 72)) = 1, the hour counts 1 000 ticks:
     * a stopped day runs real time again (mismatch 6: the firestorm burns 12 real hours).
     */
    @Test
    void aNudgedStoppedDayKeepsItsPace() {
        WorldClock.Steps s = new WorldClock.Steps();
        for (int t = 0; t < 100; t++) s.step(1, 1, false);
        for (int t = 0; t < 72_000; t++) s.step(0, 1, false);
        s.step(100, 1, false);
        double hour = 0;
        for (int t = 0; t < 72_000; t++) {
            s.step(0, 1, false);
            hour += s.rate();
        }
        assertEquals(72_000, hour, 100, "pace after the nudge " + s.pace());
    }

    /**
     * A 24-hour day stepping +10 every 720 ticks, the server restarted one tick before the next step. With the counting state
     * saved, the two steps of day over the next 1 440 real ticks count about 20 clock ticks (1 440 real ticks at 1×). The
     * unmeasured state of a restart (head fa9a78e70) gave 7 210: the first step after the load was measured over a 1-tick gap,
     * k = 720.
     */
    @Test
    void theFirstStepAfterALoadDoesNotMeasureThePace() {
        WorldClock.Steps before = new WorldClock.Steps();
        for (int t = 0; t < 10 * 720 - 1; t++) before.step(t % 720 == 719 ? 10 : 0, 1, false);
        net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
        before.save(saved);
        WorldClock.Steps s = new WorldClock.Steps();
        s.load(saved);
        double sum = 0;
        for (int t = 0; t < 1440; t++) {
            s.step(t % 720 == 0 ? 10 : 0, 1, false);
            sum += s.rate();
        }
        assertEquals(20, sum, 1, "clock ticks after the restart, pace " + s.pace());
        assertEquals(1, s.pace(), 1e-9);
    }

    /**
     * The same restart with no counting state (the probe as the review wrote it): the first step is not measured, it adds its
     * whole 10 (the real time before the load is not known as credited), then the day runs 1×: 10 + 1 439 real ticks ≈ 30,
     * never the hours of a 1-tick measurement.
     */
    @Test
    void aRestartWithoutStateCostsAtMostOneStep() {
        WorldClock.Steps s = new WorldClock.Steps();
        s.pace(1);
        double sum = 0;
        for (int t = 0; t < 1440; t++) {
            s.step(t % 720 == 0 ? 10 : 0, 1, false);
            sum += s.rate();
        }
        assertEquals(10 + 1439 * WorldClock.REAL_RATE, sum, 0.1, "clock ticks after the restart");
        assertEquals(1, s.pace(), 1e-9);
    }

    /** A stopped day nudged within a minute of stopping is not measured either: the nudge breaks the day's cadence. */
    @Test
    void aNudgeSoonAfterStoppingKeepsThePace() {
        WorldClock.Steps s = new WorldClock.Steps();
        for (int t = 0; t < 100; t++) s.step(1, 1, false);
        for (int t = 0; t < 600; t++) s.step(0, 1, false);
        s.step(100, 1, false);
        assertEquals(72, s.pace(), 1e-9);
    }
}
