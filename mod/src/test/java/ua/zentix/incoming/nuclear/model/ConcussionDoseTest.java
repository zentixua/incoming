package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConcussionDoseTest {
    private static ShockAtBody load(double pressure, double impulse) {
        return new ShockAtBody(0.06, pressure, 0.013, impulse, 0, 0, 0);
    }

    @Test
    void neitherHearingNorHeadMotionCanBypassPressureAndImpulseOnset() {
        for (double p : new double[]{0, 1000, 9999, 10_000}) assertEquals(0, ConcussionDose.dose(load(p, 1000), 100, 1));
        for (double i : new double[]{0, 1, 19.99, 20}) assertEquals(0, ConcussionDose.dose(load(100_000, i), 100, 1));
        assertTrue(ConcussionDose.dose(load(10_000 + 1e-5, 100), 40, 0.1) < 1e-7);
        assertTrue(ConcussionDose.dose(load(50_000, 20 + 1e-7), 40, 0.1) < 1e-7);
    }

    @Test
    void growsWithBothLoadsHearingAndMotionAndRemainsBounded() {
        double previous = 0;
        for (double x = 1; x < 10; x += 0.01) {
            double dose = ConcussionDose.dose(load(10_000 * x, 20 * x), 20, 0.02);
            assertTrue(dose >= previous && dose <= 1);
            previous = dose;
        }
        ShockAtBody s = load(15_000, 50);
        assertTrue(ConcussionDose.dose(s, 40, 0) > ConcussionDose.dose(s, 0, 0));
        assertTrue(ConcussionDose.dose(s, 0, 0.1) > ConcussionDose.dose(s, 0, 0));
    }

    @Test
    void briefHasShahedAndLancetResponseButNoDistantTomahawkOrGradResponse() {
        for (double[] c : new double[][]{{50, 30, 1}, {3, 10, 1}, {150, 100, 0}, {6.4, 1000, 0}}) {
            ShockAtBody s = ShockAtBody.chemical(c[0], true, c[1]);
            double shift = HearingModel.shift(HearingModel.exposure(s.pressurePa(), s.positiveSeconds()));
            assertEquals(c[2] > 0, ConcussionDose.dose(s, shift, Math.toRadians(2)) > 0);
        }
        double previous = 1;
        for (double r = 20; r < 1000; r += 1) {
            double dose = ConcussionDose.dose(ShockAtBody.chemical(50, true, r), 20, 0.02);
            assertTrue(dose <= previous);
            previous = dose;
        }
    }

    @Test
    void screenEnvelopeStartsAndEndsSmoothlyAndIsCleanAtTenAndSixtySeconds() {
        for (double dose : new double[]{0, 1e-9, 0.2, 0.8, 1}) {
            double end = ConcussionDose.durationSeconds(dose);
            assertTrue(end >= 0 && end <= 3);
            assertEquals(0, ConcussionDose.strength(dose, -0.01));
            assertEquals(0, ConcussionDose.strength(dose, 0));
            assertEquals(0, ConcussionDose.strength(dose, end));
            assertEquals(0, ConcussionDose.strength(dose, 10));
            assertEquals(0, ConcussionDose.strength(dose, 60));
            assertEquals(0, ConcussionDose.strength(dose, 1e-7), 1e-8);
            assertEquals(0, ConcussionDose.strength(dose, end - 1e-7), 1e-8);
            for (double t = 0; t <= 3; t += 0.01) assertTrue(ConcussionDose.strength(dose, t) <= dose);
        }
    }
}
