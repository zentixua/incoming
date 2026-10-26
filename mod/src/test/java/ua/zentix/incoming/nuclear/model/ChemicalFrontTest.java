package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChemicalFrontTest {
    /** Arrival according to the law: ∫dr/U(p(r)) in small steps directly. */
    private static double law(double rangeM, double kg, boolean surface) {
        int n = 200_000;
        double sum = 0, r0 = 1e-3;
        for (int i = 0; i < n; i++) {
            double a = r0 * Math.pow(rangeM / r0, (double) i / n), b = r0 * Math.pow(rangeM / r0, (i + 1.0) / n), m = Math.sqrt(a * b);
            sum += (b - a) / BlastModel.frontSpeed(BlastModel.overpressureKpa(m, kg, surface));
        }
        return sum + r0 / BlastModel.frontSpeed(BlastModel.overpressureKpa(r0 / 2, kg, surface));
    }

    /**
     * The front does not overtake the law and does not lag behind it: the arrival of the table is the integral 1/U according to Kinney–Graham and Rankine–Hugoniot (within
     *  within 0,5 % and one and a half milliseconds), the radius is its inverse; the front is always no slower than sound and in the distance goes at
     *  the speed of sound.
     */
    @Test
    void frontFollowsTheLaw() {
        for (double kg : new double[]{3, 50, 450}) {
            for (boolean surface : new boolean[]{true, false}) {
                ChemicalFront f = ChemicalFront.of(kg, surface, 2500);
                for (double r : new double[]{5, 20, 50, 100, 300, 1000}) {
                    double law = law(r, kg, surface), got = f.arrivalSeconds(r);
                    assertEquals(law, got, Math.max(0.005 * law, 1.5e-3), kg + " kg, " + r + " m");
                    assertTrue(got <= r / BlastModel.SOUND_SPEED + 1e-9, "front slower than sound: " + kg + " kg, " + r + " m");
                    assertEquals(r, f.radiusAt(got), 1e-6 * r + 1e-9);
                }
                // radius in time - the same law: the front is at the radius of the law, neither ahead nor behind
                for (double r : new double[]{3, 10, 40, 150, 600}) {
                    double law = law(r, kg, surface);
                    assertEquals(r, f.radiusAt(law), 0.005 * r, kg + " kg, " + r + " m: radius per instant of law");
                }
                double far = (f.radiusAt(8) - f.radiusAt(7)) / 1;
                assertEquals(BlastModel.SOUND_SPEED, far, 0.01 * BlastModel.SOUND_SPEED, kg + " kg: distant - sound");
            }
        }
    }

    /** The compression thickness behind the front is meters for Shahed (t₊ × U): for 1 psi ~6 m phase, for 0,2 psi ~6–7 m; the shell is thinner than the radius. */
    @Test
    void shellIsThinAgainstTheRadius() {
        ChemicalFront f = ChemicalFront.of(50, true, 2500);
        for (double r : new double[]{20, 50, 100, 300}) {
            double l = ShockOptics.DECAY_SHARE * f.phaseLength(r);
            assertTrue(l > 0.5 && l < 0.15 * r, r + " m: thickness " + l);
        }
    }
}
