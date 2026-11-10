package ua.zentix.incoming.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.FalloutModel;
import ua.zentix.incoming.util.WorldClock;

import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The time system (AGENTS.md, “Time”): what moves in front of the player runs in real seconds; slow consequences follow
 * the world's day; an effect never comes before its cause, under any day speed, sleep or time command.
 */
class NukeClockTest {
    /** World-clock seconds since the show's end at {@code r} real seconds after it, for a day running {@code speed} times real time. */
    private static double world(double speed, double r) {
        return r * Math.max(speed, 1);
    }

    /** Detonation-clock seconds over real seconds since the burst, for a world clock {@code worldAfterShow}. */
    private static double clock(DoubleUnaryOperator worldAfterShow, double realS) {
        return NukeClock.seconds(realS, realS <= NukeClock.SHOW_S ? 0 : worldAfterShow.applyAsDouble(realS - NukeClock.SHOW_S));
    }

    @Test
    void theShowIsRealTimeWhateverTheDay() {
        for (double speed : new double[]{0, 1, 72, 1000}) {
            for (double s = 0; s <= NukeClock.SHOW_S; s += 7) {
                double t = s;
                assertEquals(t, clock(r -> world(speed, r) + 50_000, t), 0, "speed " + speed + ", " + t + " s");
            }
        }
    }

    /**
     * Never behind real time and never backwards: for a stopped day, a vanilla day, a day-length mod, a sleep at any moment.
     * So fire, rain and fallout that follow the clock never come before what causes them on real time.
     */
    @Test
    void monotonicAndNeverBehindRealTime() {
        double[] sleeps = {-1, NukeClock.SHOW_S + 10, NukeClock.SHOW_S + 100, NukeClock.SHOW_S + 5000};
        for (double speed : new double[]{0, 0.5, 1, 72}) {
            for (double sleepAt : sleeps) {
                DoubleUnaryOperator w = r -> world(speed, r) + (sleepAt >= 0 && NukeClock.SHOW_S + r >= sleepAt ? 13 * 3600 : 0);
                double last = -1;
                for (double s = 0; s <= 6 * 3600; s += 3) {
                    double c = clock(w, s);
                    assertTrue(c >= s - 1e-9, "behind real time: " + c + " at " + s);
                    assertTrue(c >= last, "backwards at " + s + " (speed " + speed + ", sleep " + sleepAt + ")");
                    last = c;
                }
            }
        }
    }

    @Test
    void stoppedDayIsRealTimeAndVanillaDayRunsSeventyTwoTimes() {
        for (double s = NukeClock.SHOW_S; s < 5 * 3600; s += 60) {
            double t = s;
            assertEquals(t, clock(r -> world(1, r), t), 1e-9, "a stopped day (the world clock at real rate) is real time");
        }
        double r = NukeClock.RAMP_S + 100;
        assertEquals(NukeClock.SHOW_S + 72 * r, clock(rr -> world(72, rr), NukeClock.SHOW_S + r), 1e-6, "after the ramp: the world's hours");
        assertTrue(Double.isFinite(NukeClock.seconds(NukeClock.SHOW_S + 10, Double.NaN)));
        assertEquals(NukeClock.SHOW_S + 10, NukeClock.seconds(NukeClock.SHOW_S + 10, Double.NaN), 0, "unknown: real time");
    }

    /** The join is smooth: no jump in fire or smoke when the clock leaves real time. */
    @Test
    void noJumpWhenJoiningTheWorld() {
        double step = 0.05, maxRate = 0;
        for (double s = NukeClock.SHOW_S; s < NukeClock.SHOW_S + 2 * NukeClock.RAMP_S; s += step) {
            double t = s;
            maxRate = Math.max(maxRate, (clock(r -> world(72, r), t + step) - clock(r -> world(72, r), t)) / step);
        }
        assertTrue(maxRate < 72 * 2.5, "rate " + maxRate);
    }

    /**
     * World clock: real time at least, a /time set backward counts as real time, sleep jumps forward; a day that steps in
     * rare whole ticks (a day-length mod's 24-hour day: one tick every 72) runs 1:1 with real time, not twice as fast.
     */
    @Test
    void worldClockSteps() {
        assertEquals(1000, WorldClock.REAL_RATE * 72_000, 1e-9, "a real hour is a world hour");
        assertEquals(72_000 * WorldClock.REAL_RATE, run(t -> 0, 72_000), 1e-6, "stopped day: real time");
        assertEquals(72_000, run(t -> 1, 72_000), 1e-6, "vanilla day: game time");
        assertEquals(1000, run(t -> t % 72 == 71 ? 1 : 0, 72_000), 2, "24-hour day: an hour per real hour");
        assertEquals(36_000, run(t -> t % 2, 72_000), 1, "half-speed day");
        assertEquals(72_000 * WorldClock.REAL_RATE, run(t -> t % 1200 == 0 ? -24_000 : 0, 72_000), 1e-6, "never backwards");
        WorldClock.Steps steps = new WorldClock.Steps();
        assertEquals(13_000, steps.step(13_000), 0, "a night's sleep");
        double after = 0;
        for (int t = 0; t < 7200; t++) after += steps.step(0);
        assertEquals(100, after, 1e-6, "after the sleep, real time again at once");
    }

    private static double run(java.util.function.IntUnaryOperator dayDelta, int ticks) {
        WorldClock.Steps steps = new WorldClock.Steps();
        double clock = 0;
        for (int t = 0; t < ticks; t++) clock += steps.step(dayDelta.applyAsInt(t));
        return clock;
    }

    /** Fallout over a long step (sleep) counts the higher rate at its start: dose = rate at the end × decay-weighted hours. */
    @Test
    void decayHoursWeighTheStep() {
        assertEquals(0.01, FalloutModel.decayHours(5, 5.01), 1e-4, "short step: plain hours");
        assertEquals(0, FalloutModel.decayHours(5, 5), 0);
        double h0 = 2, h1 = 14, exact = 0;
        int n = 200_000;
        for (int i = 0; i < n; i++) {
            double t = h0 + (i + 0.5) * (h1 - h0) / n;
            exact += Math.pow(t / h1, -FalloutModel.DECAY_EXP) * (h1 - h0) / n;
        }
        assertEquals(exact, FalloutModel.decayHours(h0, h1), 1e-6 * exact);
        assertTrue(FalloutModel.decayHours(h0, h1) > h1 - h0);
    }
}
