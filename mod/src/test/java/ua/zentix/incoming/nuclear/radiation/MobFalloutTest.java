package ua.zentix.incoming.nuclear.radiation;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.FalloutModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobFalloutTest {
    /** Exact ∫ t^-k dt over [a, b]. */
    private static double powerLaw(double k, double a, double b) {
        return (Math.pow(a, 1 - k) - Math.pow(b, 1 - k)) / (k - 1);
    }

    /**
     * A player who sleeps through the plume's arrival (one long dose step, the promise in {@code RadiationTicker}) gets the
     * dose from the moment the dust landed, not the decay law extrapolated back to when they lay down; a mob unloaded for
     * days gets the decaying dose of those days, not the rate now times the gap ({@link GroundFallout.Point#dose}).
     */
    @Test
    void longStepsCountFromTheLanding() {
        // 15 kt surface burst, no fires (no rain), wind 2.5 m/s along +x: 18 km downwind the plume lands 2 h after the burst
        var d = new ua.zentix.incoming.nuclear.Detonation(1, new net.minecraft.world.phys.Vec3(0, 64, 0), 64, 15, true, 0, 0f, 2.5f,
                20_000, 0, 1f, true, 0.25f, 0L, false);
        double x = 18_000, arrival = d.falloutArrivalSeconds(x, 0) / 3600, h0 = 0.4, h1 = 10.4;
        assertEquals(2, arrival, 0.01);
        assertEquals(0, d.rainH1(x, 0), 0);
        // a 24-hour day without jumps: the ground clock is the time since the burst
        var p = new GroundFallout.Point(d.falloutH1(x, 0), new FalloutModel.Landing(arrival * 3600, 0), 0, null);
        double rate = p.rate(h1 * 3600);
        double truth = rate * FalloutModel.decayHours(arrival, h1);
        assertEquals(truth, p.dose(h0 * 3600, h1 * 3600), truth * 1e-9);
        assertTrue(rate * FalloutModel.decayHours(h0, h1) > 2 * truth, "the old extrapolation it replaces");
        // a short step is the rate × time
        assertEquals(rate / 3600, p.dose(h1 * 3600 - 1, h1 * 3600), rate / 3600 * 1e-4);
        // a mob unloaded for a week after it landed: the t^-1.2 integral, far below the rate now × the week
        double week = 7 * 24 + 2;
        double weekDose = p.falloutH1() * (Math.pow(arrival, -0.2) - Math.pow(week, -0.2)) / 0.2;
        assertEquals(weekDose, p.dose(0, week * 3600), weekDose * 1e-9);
        assertTrue(p.dose(0, week * 3600) < p.rate(2.1 * 3600) * (week - 2));
        assertEquals(0, GroundFallout.Point.NONE.dose(0, 1e9), 0);
    }

    /**
     * A mob loaded hours after the detonation gets sick from the hour its dose really passed 1 Gy (the promise in
     * {@code MobFallout}): bisection finds that tick of a decaying t^-1.2 dose to one tick, and the end when it never passes.
     */
    @Test
    void crossingFindsTheTickTheDosePassed() {
        double k = FalloutModel.DECAY_EXP;
        java.util.function.LongToDoubleFunction dose = t -> t <= 500 ? 0 : 50 * powerLaw(k, 500, t);
        long at = MobFallout.crossing(dose, 0, 60_000, 1);
        assertTrue(dose.applyAsDouble(at) >= 1 && dose.applyAsDouble(at - 1) < 1, "tick " + at);
        assertEquals(60_000, MobFallout.crossing(t -> 0.5, 0, 60_000, 1));
    }

    /**
     * Contamination on the body fades by Way–Wigner like the ground (the promise in {@code RadiationTicker}): second steps add
     * up to the t^-1.2 law, nothing fades in the first half hour, and 7× the age leaves about a tenth (7–10 rule).
     */
    @Test
    void contaminationDecaysLikeFallout() {
        double c = 100, age = 1;
        for (int i = 0; i < 300; i++) c = RadiationTicker.decay(c, age += 0.02, 0.02);
        assertEquals(100 * Math.pow(1 / age, k()), c, 1e-6);
        assertEquals(100, RadiationTicker.decay(100, 0.3, 0.02), 1e-12);
        assertEquals(10, RadiationTicker.decay(100, 7, 6), 0.5);
    }

    private static double k() {
        return FalloutModel.DECAY_EXP;
    }
}
