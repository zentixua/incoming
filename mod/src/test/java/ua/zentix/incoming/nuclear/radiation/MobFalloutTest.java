package ua.zentix.incoming.nuclear.radiation;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.FalloutModel;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
