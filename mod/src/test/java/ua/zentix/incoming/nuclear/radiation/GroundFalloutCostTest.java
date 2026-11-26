package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.FalloutModel;
import ua.zentix.incoming.util.StrikeClock;
import ua.zentix.incoming.util.StrikeHistory;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a ground field costs and what its reach leaves out (change 8 of time.md §9; review of PR #785 round 1).
 * 15 kt surface burst, wind 6.7 m/s toward +x.
 */
class GroundFalloutCostTest {
    private static final Detonation D = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0f, 6.7f, 20_000, 7, 1f, true, 0.35f);

    /**
     * A mob unloaded for a long gap gets the dose of the whole gap from the landing, so its account reads every point that
     * was above the cut at the gap's start (GroundFallout.reach over the account's span): beyond that reach the whole gap
     * adds less than the cut times the gap (a tenth of what MobFallout's background rule already drops). The review's
     * points just beyond today's reach (0.495 Gy over a world year at 35 km) are inside it.
     */
    @Test
    void aLongAccountReadsWhatWasAboveTheCutAtItsStart() {
        double cut = FalloutModel.FLOOR * GroundFallout.NEGLIGIBLE;
        for (double hours : new double[]{7 * 24, 30 * 24, 8760}) {
            double today = D.groundReach(hours, cut);
            // an account from the burst to now: youngest ground age now is at least the gap, its span the whole gap
            double account = GroundFallout.reach(D, hours * 3600, hours * 3600);
            assertTrue(account >= today * 3, String.format(Locale.ROOT, "gap %.0f h: account reach %.0f, today %.0f", hours, account, today));
            for (double f = 1; f <= 3; f += 0.05) {
                double x = account * f, arrival = D.falloutArrivalSeconds(x, 0) / 3600;
                if (arrival >= hours) continue;
                double r = FalloutModel.groundDose(D.falloutH1(x, 0), arrival, hours);
                assertTrue(r <= cut * hours, String.format(Locale.ROOT, "gap %.0f h at %.0f km: %.3g R beyond the reach", hours, x / 1000, r));
            }
        }
        // a short step reads only today's reach
        assertTrue(GroundFallout.reach(D, 8760 * 3600, 72) <= D.groundReach(8759, cut));
    }

    /**
     * The per-second work of a source at a player: an H+1 evaluation, a landing from a full history (730 minute samples,
     * the most an uncompacted one keeps) and the ground lead; and of a source per step, its reach. Measured here, bounded
     * at 50 µs each (the laptop measures 0.4 and 0.9 µs): beyond its reach a source costs one distance check, within it
     * this, so 20 players within reach of 100 fields cost under a millisecond a second.
     */
    @Test
    void aSourceCostsMicroseconds() {
        StrikeHistory h = new StrikeHistory();
        StrikeClock c = StrikeClock.start(0);
        for (long age = 0; age <= 12L * 72_000; age += 20) {
            if (!c.ended() && age >= 24_000) c = c.end(age, age, 0);
            if (c.rampDue(age)) c = c.ramp(age, age);
            h.sample(age, age, 0, 0, c.motion(age, 0), age == 24_000 || age == 25_200);
        }
        long now = 12L * 72_000;
        java.util.Random rnd = new java.util.Random(3);
        double sink = 0;
        int n = 200_000;
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                double x = rnd.nextDouble() * 300_000, z = rnd.nextDouble() * 2000 - 1000;
                double h1 = D.falloutH1(x, z) + D.rainH1(x, z);
                double age = h.ageAtMotion(c, D.falloutArrivalSeconds(x, z), now, 0);
                if (!Double.isNaN(age)) sink += h1 + h.groundLead(c, age, now, now, 0, 0);
            }
            best = Math.min(best, System.nanoTime() - t0);
        }
        double perPoint = best / 1e3 / n;
        long t0 = System.nanoTime();
        int m = 2000;
        for (int i = 0; i < m; i++) sink += GroundFallout.reach(D, (10 + i) * 3600.0, 72);
        double perReach = (System.nanoTime() - t0) / 1e3 / m;
        System.out.printf(Locale.ROOT, "ground fallout cost: %.2f µs a source at a player, %.2f µs a reach (uncached); %.0f sources a player in 1 ms%n",
                perPoint, perReach, 1000 / perPoint);
        assertTrue(sink != 0);
        assertTrue(perPoint < 50, perPoint + " µs a source at a player");
        assertTrue(perReach < 50, perReach + " µs a reach");
    }
}
