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
     * A mob unloaded for days receives the decaying fallout of those days on load: the log-grid integral matches the exact
     * t^-1.2 integral to 0.1 % from minutes to a week (the promise in {@code MobFallout}).
     */
    @Test
    void longAccountIntegratesTheDecay() {
        double k = FalloutModel.DECAY_EXP;
        double[][] spans = {{400, 1000}, {1000, 24_000}, {200, 7 * 24_000}, {24_000, 7 * 24_000}};
        for (double[] s : spans) {
            double got = MobFallout.integral(t -> Math.pow(t, -k), s[0], s[1]);
            double exact = powerLaw(k, s[0], s[1]);
            assertEquals(exact, got, exact * 1e-3, "span " + s[0] + "–" + s[1]);
        }
    }

    /** Fallout arriving inside the span (step from zero): the step costs at most one grid step of the span. */
    @Test
    void arrivalStepInsideTheSpan() {
        double k = FalloutModel.DECAY_EXP, arrival = 5_000;
        double got = MobFallout.integral(t -> t < arrival ? 0 : Math.pow(t, -k), 1_000, 100_000);
        double exact = powerLaw(k, arrival, 100_000);
        double step = Math.pow(100.0, 1.0 / MobFallout.INTEGRAL_STEPS) - 1;
        assertEquals(exact, got, Math.pow(arrival, -k) * arrival * step);
    }

    /**
     * A player who sleeps through the plume's arrival (one long dose step, the promise in {@code RadiationTicker}) gets the
     * dose from the moment the dust landed, not the decay law extrapolated back to when they lay down.
     */
    @Test
    void sleepThroughArrivalCountsFromArrival() {
        // 15 kt surface burst, no fires (no rain), wind 2.5 m/s along +x: 18 km downwind the plume lands 2 h after the burst
        var d = new ua.zentix.incoming.nuclear.Detonation(1, new net.minecraft.world.phys.Vec3(0, 64, 0), 64, 15, true, 0, 0f, 2.5f,
                20_000, 0, 1f, true, 0.25f, 0L, false);
        double x = 18_000, arrival = d.falloutArrivalSeconds(x, 0) / 3600, h0 = 0.4, h1 = 10.4;
        assertEquals(2, arrival, 0.01);
        double rate = d.falloutRateAt(x, 0, h1 * 3600);
        double truth = rate * FalloutModel.decayHours(arrival, h1);
        // within the log grid's one step at the arrival (arrivalStepInsideTheSpan)
        assertEquals(truth, MobFallout.exposure(d, x, 0, h0 * 3600, h1 * 3600), truth * 0.03);
        assertTrue(rate * FalloutModel.decayHours(h0, h1) > 2 * truth, "the old extrapolation it replaces");
        // a short step is the rate × time
        assertEquals(rate / 3600, MobFallout.exposure(d, x, 0, h1 * 3600 - 1, h1 * 3600), 1e-12);
    }

    /** Nothing before the span start or for an empty span. */
    @Test
    void emptySpan() {
        assertEquals(0, MobFallout.integral(t -> 1, 500, 500));
        assertEquals(0, MobFallout.integral(t -> 1, 500, 100));
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
