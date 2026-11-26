package ua.zentix.incoming.nuclear;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.FalloutModel;
import ua.zentix.incoming.util.StrikeClock;
import ua.zentix.incoming.util.WorldClock;

import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The time rules (AGENTS.md “Time”, {@code docs/design/time.md} §8.3): a strike's two ages, the rate blend after its show,
 * the world's rate and the jumps.
 */
class NukeClockTest {
    private static final long SHOW = NukeClock.SHOW_TICKS;
    private static final double H = WorldClock.HOUR;

    /**
     * A detonation's strike clock S over real ticks since the burst, under a day of world rate {@code k} (world-clock ticks
     * per real tick: k · {@link WorldClock#REAL_RATE}), its show ending at its length.
     */
    private static double strike(double k, long age) {
        StrikeClock c = StrikeClock.start(0);
        if (age < SHOW) return c.strike(age, 0, 0);
        c = c.end(SHOW, 0, 0);
        if (age >= SHOW + StrikeClock.RAMP_TICKS) c = c.ramp(SHOW + StrikeClock.RAMP_TICKS, StrikeClock.RAMP_TICKS * k * WorldClock.REAL_RATE);
        return c.strike(age, (age - SHOW) * k * WorldClock.REAL_RATE, 0);
    }

    @Test
    void theShowIsRealTimeWhateverTheDay() {
        for (double k : new double[]{1, 12, 72, 1440}) {
            for (long t = 0; t <= SHOW; t += 7) assertEquals(t / 20.0, strike(k, t), 1e-9, "k " + k + ", " + t + " ticks");
        }
    }

    /**
     * Rule 5: after the show dS/dt = 1 + (k − 1)·smoothstep(r / 60 s). It never runs faster than the world (the old join that
     * caught up the ramp peaked at 120.8× under a vanilla day), never catches up the show (after the blend S trails the
     * world by (k − 1)·30 s), and is monotone and never behind real time.
     */
    @Test
    void theBlendNeverOvershootsAndNeverCatchesUpTheShow() {
        for (double k : new double[]{1, 1.5, 12, 72, 1440}) {
            double last = strike(k, SHOW), peak = 0;
            for (long t = SHOW + 1; t <= SHOW + 4 * StrikeClock.RAMP_TICKS; t++) {
                double s = strike(k, t), rate = (s - last) * 20;
                assertTrue(rate >= 1 - 1e-6, "slower than real at " + t + ": " + rate);
                assertTrue(rate <= k + 1e-6, "overshoots k " + k + " at " + t + ": " + rate);
                assertTrue(s >= t / 20.0 - 1e-9, "behind real time at " + t);
                double r = (t - SHOW) / 20.0;
                double u = Math.min(1, r / StrikeClock.RAMP_S), expected = 1 + (k - 1) * u * u * (3 - 2 * u);
                assertEquals(expected, rate, (k - 1) * 0.002 + 1e-6, "the law dS/dt at r " + r);
                peak = Math.max(peak, rate);
                last = s;
            }
            assertEquals(k, peak, (k - 1) * 0.002 + 1e-6, "the blend reaches k and stops there");
            long after = SHOW + 2 * StrikeClock.RAMP_TICKS;
            double world = (after - SHOW) / 20.0 * k;
            assertEquals(NukeClock.SHOW_S + world - (k - 1) * StrikeClock.RAMP_S / 2, strike(k, after), 1e-6,
                    "after the blend S trails the world by (k − 1)·30 s: the show is never caught up");
        }
        // under a vanilla day an hour of the world after the blend takes 50 real seconds
        double a = strike(72, SHOW + 2 * StrikeClock.RAMP_TICKS), b = strike(72, SHOW + 2 * StrikeClock.RAMP_TICKS + 1000);
        assertEquals(3600, b - a, 1e-6);
    }

    /** A change of the world's rate after the blend never moves the past: the blend's end is an anchor. */
    @Test
    void aLaterChangeOfRateKeepsThePast() {
        StrikeClock c = StrikeClock.start(0).end(SHOW, 0, 0).ramp(SHOW + StrikeClock.RAMP_TICKS, StrikeClock.RAMP_TICKS);
        long t = SHOW + 3 * StrikeClock.RAMP_TICKS;
        double vanilla = c.strike(t, 3 * StrikeClock.RAMP_TICKS, 0);
        // the day slows to 1×: from here the clock without jumps grows at the real floor
        double later = c.strike(t + 20, 3 * StrikeClock.RAMP_TICKS + 20 * WorldClock.REAL_RATE, 0);
        assertEquals(1, later - vanilla, 1e-6, "one real second at 1×, no step back");
        // unknown blend end (a save from before it): an even rate since the show's end gives the same as on time
        StrikeClock old = StrikeClock.start(0).end(SHOW, 0, 0);
        assertEquals(vanilla, old.strike(t, 3 * StrikeClock.RAMP_TICKS, 0), 1e-6);
        assertEquals(vanilla, old.ramp(t, 3 * StrikeClock.RAMP_TICKS).strike(t, 3 * StrikeClock.RAMP_TICKS, 0), 1e-6,
                "a late blend anchor is recorded as an even rate");
    }

    /**
     * Rule 7: a counted jump adds its hours once to M and S and ends a running show; two strikes in different phases (one
     * in its show, one after) both move by the jump, and the one in its show joins the world's rate from there.
     */
    @Test
    void aCountedJumpMovesBothAgesOfEveryStrikeAndEndsShows() {
        double k = 72, perTick = k * WorldClock.REAL_RATE, night = 11 * H;
        // A: burst at game 0, in its show at the jump (minute 2); B: burst 30 min before, after its show
        long jumpAt = 2400;
        StrikeClock a = StrikeClock.start(0);
        StrikeClock b = StrikeClock.start(0).end(SHOW, 0, 0).ramp(SHOW + StrikeClock.RAMP_TICKS, StrikeClock.RAMP_TICKS * perTick);
        long bOffset = 36_000;
        double rateAtJump = (jumpAt + bOffset - SHOW) * perTick;
        double bBefore = b.strike(jumpAt + bOffset, rateAtJump, 0), aBefore = a.strike(jumpAt, 0, 0);
        // the jump is counted; A's show ends at once (NuclearEvents.endShows), its hours already in M
        a = a.end(jumpAt, rateAtJump, night);
        assertEquals(aBefore + night * WorldClock.SECONDS_PER_TICK, a.motion(jumpAt, night), 1e-9, "M of A moves by the night");
        assertEquals(aBefore + night * WorldClock.SECONDS_PER_TICK, a.strike(jumpAt, rateAtJump, night), 1e-9, "S of A moves by the night");
        assertEquals(bBefore + night * WorldClock.SECONDS_PER_TICK, b.strike(jumpAt + bOffset, rateAtJump, night), 1e-9, "S of B moves by the night");
        assertTrue(a.ended(), "the jump ends A's show");
        // from there A blends into the world's rate like any show's end: real at first, k after a minute
        double s0 = a.strike(jumpAt, rateAtJump, night), s1 = a.strike(jumpAt + 1, rateAtJump + perTick, night);
        assertEquals(1, (s1 - s0) * 20, 0.01, "real rate just after the jump");
        long later = jumpAt + 3 * StrikeClock.RAMP_TICKS;
        double d0 = a.strike(later, rateAtJump + 3 * StrikeClock.RAMP_TICKS * perTick, night);
        double d1 = a.strike(later + 1, rateAtJump + (3 * StrikeClock.RAMP_TICKS + 1) * perTick, night);
        assertEquals(k, (d1 - d0) * 20, 1e-6, "the world's rate after the blend");
    }

    /** An unseen jump (someone awake) is not counted: neither age moves, the show keeps running. */
    @Test
    void anUnseenJumpMovesNeitherAge() {
        StrikeClock a = StrikeClock.start(0);
        assertEquals(120, a.strike(2400, 0, 0), 1e-9);
        assertEquals(120, a.motion(2400, 0), 1e-9, "the counted jumps did not change");
        assertFalse(a.ended());
    }

    /**
     * Rule 6 and mismatch 16: the world's rate is the day's measured advance per real time, at least 1; a day that steps
     * +N every M ticks is counted once; a stopped day keeps its last rate (vanilla 72 if it never ran).
     */
    @Test
    void theWorldsRate() {
        assertEquals(1000, WorldClock.REAL_RATE * 72_000, 1e-9, "a real hour is a world hour");
        assertEquals(72_000, run(t -> 1, 72_000), 1e-6, "vanilla day: an hour every 50 s");
        assertEquals(72_000, run(t -> 0, 72_000), 1e-6, "a day that never ran: the vanilla pace");
        assertEquals(36_000, run(t -> t % 2, 72_000), 2, "half-speed day");
        assertEquals(12_000, run(t -> t % 6 == 5 ? 1 : 0, 72_000), 5, "2-hour day: k 12, twelve hours a real hour (the first gap at the vanilla pace)");
        // a 24-hour day as +10 every 720 ticks runs 1:1, not 2× (mismatch 16); the first gap is credited at the vanilla
        // pace, before the day's own steps measured it
        WorldClock.Steps steps = new WorldClock.Steps();
        double first = 0, rest = 0;
        for (int t = 0; t < 720; t++) {
            steps.step(t % 720 == 719 ? 10 : 0, 1, false);
            first += steps.rate();
        }
        assertEquals(1, steps.pace(), 1e-9, "k measured from its steps");
        for (int t = 720; t < 72_000; t++) {
            steps.step(t % 720 == 719 ? 10 : 0, 1, false);
            rest += steps.rate();
        }
        assertEquals((72_000 - 720) * WorldClock.REAL_RATE, rest, 0.02, "24-hour day in +10 steps: an hour per real hour");
        assertEquals(1000, run(t -> t % 72 == 71 ? 1 : 0, 72_000), 75, "24-hour day in +1 steps");
        // a stopped day keeps the last running rate: vanilla, then the sun stopped for a real hour
        WorldClock.Steps stop = new WorldClock.Steps();
        double sum = 0;
        for (int t = 0; t < 1000; t++) { stop.step(1, 1, false); }
        for (int t = 0; t < 72_000; t++) { stop.step(0, 1, false); sum += stop.rate(); }
        assertEquals(72_000, sum, 1e-6, "stopped after a vanilla day: still 72×");
        assertFalse(stop.running(), "the day counts as stopped");
        // a 2-hour day stopped keeps 12×
        WorldClock.Steps slow = new WorldClock.Steps();
        for (int t = 0; t < 6000; t++) slow.step(t % 6 == 5 ? 1 : 0, 1, false);
        sum = 0;
        for (int t = 0; t < 72_000; t++) { slow.step(0, 1, false); sum += slow.rate(); }
        assertEquals(12_000, sum, 1, "stopped after a 2-hour day: 12×");
        // a mod that turns the vanilla cycle off but advances the time itself is measured as running
        WorldClock.Steps mod = new WorldClock.Steps();
        for (int t = 0; t < 2000; t++) mod.step(t % 3 == 2 ? 1 : 0, 1, false);
        assertTrue(mod.running());
        assertEquals(24, mod.pace(), 1e-9);
    }

    /**
     * Rule 7 and mismatch 4: what a jump is. A forward step of at least an hour (sleep, {@code /time add}); while the day
     * runs, a {@code /time set} whose forward distance modulo a day is under 12 hours (the op's “/time set day” at night); on
     * a stopped day a set only chooses the light; a backward set never invents days; a step under an hour is a rate.
     */
    @Test
    void whatIsAJump() {
        WorldClock.Steps s = running();
        s.step(13_000, 1, false);
        assertEquals(13_000, s.jump(), 0, "a night's sleep");
        s = running();
        s.step(5_000, 1, false);
        assertEquals(5_000, s.jump(), 0, "/time add 5000");
        s = running();
        s.step(500, 1, false);
        assertEquals(0, s.jump(), 0, "under an hour: no jump");
        assertEquals(500 + 72 * WorldClock.REAL_RATE, s.rate(), 1e-9, "a step the in-place clocks follow, added to the tick's rate");
        assertEquals(72, s.pace(), 1e-9, "and it does not set the world's rate");
        // /time set day (1000) at night (18 000) on day 5: dayTime moves back 5 days; forward distance 7 hours
        s = running();
        s.step(1000 - (5 * 24_000 + 18_000), 1, true);
        assertEquals(7_000, s.jump(), 0, "/time set day at night: a 7-hour night skip");
        // /time set night (13 000) at 1000 on day 0: forward 12 hours, not under 12
        s = running();
        s.step(12_000, 1, true);
        assertEquals(0, s.jump(), 0, "a set 12 hours ahead is not a night skip");
        // a backward set that does not land under 12 h ahead never invents days
        s = running();
        double before = s.pace();
        s.step(-500, 1, true);
        assertEquals(0, s.jump(), 0);
        assertEquals(before * WorldClock.REAL_RATE, s.rate(), 1e-9, "the tick counts at the world's rate only");
        // on a stopped day, a set chooses the light
        WorldClock.Steps stopped = new WorldClock.Steps();
        for (int t = 0; t < 10; t++) stopped.step(1, 1, false);
        for (int t = 0; t < 5000; t++) stopped.step(0, 1, false);
        stopped.step(5_000, 1, true);
        assertEquals(0, stopped.jump(), 0, "/time set noon on a stopped day: no jump");
        stopped.step(1000 - 18_000, 1, true);
        assertEquals(0, stopped.jump(), 0, "/time set day on a stopped day: no jump");
        // after a sleep, the world's rate goes on at once
        s = running();
        s.step(13_000, 1, false);
        double after = 0;
        for (int t = 0; t < 7200; t++) { s.step(1, 1, false); after += s.rate(); }
        assertEquals(7200, after, 1e-6);
    }

    private static WorldClock.Steps running() {
        WorldClock.Steps s = new WorldClock.Steps();
        for (int t = 0; t < 100; t++) s.step(1, 1, false);
        return s;
    }

    private static double run(IntUnaryOperator dayDelta, int ticks) {
        WorldClock.Steps steps = new WorldClock.Steps();
        double clock = 0;
        for (int t = 0; t < ticks; t++) {
            steps.step(dayDelta.applyAsInt(t), 1, false);
            clock += steps.rate() + steps.jump();
        }
        return clock;
    }

    /** Saves from before the two ages load as a 20-minute show, no jumps, the clock without jumps where the clock was. */
    @Test
    void oldAnchorsLoad() {
        CompoundTag old = new CompoundTag();
        old.putInt("id", 4);
        old.putDouble("burst", 1000);
        old.putDouble("show_end", 2200);
        NuclearEvents.Anchor a = NuclearEvents.Anchor.CODEC.parse(NbtOps.INSTANCE, old).getOrThrow();
        assertTrue(a.clock().ended());
        assertEquals(SHOW, a.clock().endAge());
        assertEquals(2200, a.clock().rateAtEnd(), 0);
        assertEquals(0, a.clock().jumpsAtStrike(), 0);
        assertEquals(0, a.clock().jumpsAtEnd(), 0);
        assertTrue(Double.isNaN(a.clock().rateAtRamp()));
        CompoundTag running = new CompoundTag();
        running.putInt("id", 5);
        running.putDouble("burst", 1000);
        NuclearEvents.Anchor r = NuclearEvents.Anchor.CODEC.parse(NbtOps.INSTANCE, running).getOrThrow();
        assertFalse(r.clock().ended());
        // the new fields round-trip
        NuclearEvents.Anchor full = new NuclearEvents.Anchor(6, 10, 20, new StrikeClock(3, 900, 30, 40, 50));
        var tag = NuclearEvents.Anchor.CODEC.encodeStart(NbtOps.INSTANCE, full).getOrThrow();
        assertEquals(full, NuclearEvents.Anchor.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow());
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
