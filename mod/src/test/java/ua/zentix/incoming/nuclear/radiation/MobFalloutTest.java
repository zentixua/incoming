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
