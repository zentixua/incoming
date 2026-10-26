package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave near the ground according to the height of the explosion: numbers - Brode 1987 (level 62, subroutine PT), Glasstone &amp; Dolan 1977 (Fig. 3.73,
 * 3.75) and standard DNA (glasstone, MIT); summary - docs/DESIGN-nuke.md, §1.1.
 */
class HobBlastTest {
    /** 15 kt on 592 m (like Hiroshima: 240 m/kt^⅓). */
    private static final double W = 15, H = 592;

    /**
     * Equation 62 to 2 psi: ground ranges for 15 kt per 592 m (numbers of the formula itself, verified with glasstone, ±1%) and y
     * 2 kt per 302 m (× ∛(2/15)). Independent verification - with curves Glasstone &amp; Dolan below.
     */
    @ParameterizedTest
    @CsvSource({"20, 358", "10, 998", "5, 1603", "2, 2833"})
    void brodeRanges(double psi, double metres) {
        assertEquals(metres, HobBlast.rangeFor(psi, H, W), metres * 0.01);
        double small = Math.cbrt(2 / 15.0);
        assertEquals(metres * small, HobBlast.rangeFor(psi, H * small, 2), metres * small * 0.01);
    }

    /** With curves Glasstone &amp; Dolan (Fig. 3.73b, c, reduced to 15 kt) - within 10%; they are detonation ~30 psi. */
    @ParameterizedTest
    @CsvSource({"10, 1080", "5, 1620", "2, 2930", "1, 4550"})
    void matchesGlasstoneCurves(double psi, double metres) {
        assertEquals(metres, HobBlast.rangeFor(psi, H, W), metres * 0.1);
    }

    @Test
    void underTheBurst() {
        assertEquals(28.5, HobBlast.psi(0, H, W), 0.5);
    }

    /**
     * Far zone - standard DNA: 0.3 psi at 15 kt at 592 m - at 10.4 km (Broad's adjustment would give 8.8 km and then go to zero). Further - a weak spherical wave: the pressure decreases as 1/(r·√ln r) - faster 1/r, slower 1/r^1.5.
     */
    @Test
    void farFieldIsTheWeakShockStandard() {
        assertEquals(10_380, HobBlast.rangeFor(0.3, H, W), 200);
        double prev = 0;
        for (double g = 20_000; g < 200_000; g *= 1.3) {
            double p = HobBlast.psi(g, H, W);
            assertTrue(p > 0);
            if (prev > 0) {
                assertTrue(p * g < prev * g / 1.3, "faster 1/r: " + g);
                assertTrue(p * Math.pow(g, 1.5) > prev * Math.pow(g / 1.3, 1.5), "slower 1/r^1.5: " + g);
            }
            prev = p;
        }
    }

    /**
     * Ground burst: within 10% of Kinney–Graham with ×1.8 reflection (previous mod model, which matched ground-burst
     * data); for 15 kt, DESIGN-nuke table 1.1 now gives 545 m and 1.15 km versus 570 m and 1.09 km here.
     */
    @ParameterizedTest
    @CsvSource({
            "1, 20, 230", "1, 5, 440", "1, 3, 590", "1, 1, 1300", "1, 0.5, 2400",
            "15, 20, 570", "15, 5, 1090", "15, 3, 1450", "15, 1, 3200", "15, 0.5, 6000",
            "1000, 20, 2300", "1000, 5, 4400", "1000, 3, 5900", "1000, 1, 13000", "1000, 0.5, 24000",
    })
    void surfaceBurst(double yieldKt, double psi, double metres) {
        assertEquals(metres, HobBlast.rangeFor(psi, 0, yieldKt), metres * 0.1, () -> yieldKt + " cT, " + psi + " psi");
    }

    /** The pressure drops with range at any detonation height, and Broad’s junction with the DNA standard is without a step. */
    @Test
    void fallsWithRangeAtEveryHeight() {
        for (double h : new double[]{0, 50, 150, 300, 592, 1000, 2000, 4000, 8000}) {
            double prev = Double.MAX_VALUE;
            for (double g = 1; g < 100_000; g *= 1.01) {
                double p = HobBlast.psi(g, h, W);
                double at = g;
                // near the axis itself, the angle table gives ripples in hundred thousandths (the exact formula falls off)
                assertTrue(p <= prev * (1 + 1e-6), () -> h + " m, " + at + " m");
                assertTrue(p >= prev * 0.97 || prev > 100, () -> "step: " + h + " m, " + at + " m");
                prev = p;
            }
        }
    }

    /** The table is no further from the exact formula 0.6 % from 0.05 to 3000 psi. */
    @Test
    void tableMatchesFormula() {
        Random random = new Random(1);
        for (int n = 0; n < 20_000; n++) {
            double x = Math.exp(Math.log(0.003) + random.nextDouble() * Math.log(1e5)), y = switch (n % 4) {
                case 0 -> 0;
                case 1 -> random.nextDouble() * 0.2;
                case 2 -> random.nextDouble() * 3;
                default -> random.nextDouble() * 20;
            };
            double exact = HobBlast.exact(x, y);
            if (exact < 0.05 || exact > 3000) continue;
            // 1 kt: kft/kt^⅓ to meters
            double table = HobBlast.psi(x * 304.8, y * 304.8, 1);
            assertEquals(exact, table, exact * 0.006, () -> x + ", " + y);
        }
    }

    /** The beginning of the Mach region (Broad, adj. B; G&amp;D fig. 3.73b - ~710 m). */
    @Test
    void machStem() {
        assertEquals(694, HobBlast.machStem(H, W), 5);
        assertEquals(354, HobBlast.machStem(302, 2), 5);
        assertEquals(0, HobBlast.machStem(0, W), 1e-9);
    }

    /** Arrival of the front on the ground (Broad, PT): at 15 kt at 592 m; in the distance the front is no slower than the sound. */
    @ParameterizedTest
    @CsvSource({"0, 0.79", "600, 1.40", "1000, 2.17", "2000, 4.64", "5000, 13.05", "10000, 27.5"})
    void arrival(double groundM, double seconds) {
        assertEquals(seconds, HobBlast.arrivalSeconds(groundM, H, W), seconds * 0.01);
    }

    @Test
    void frontNeverSlowerThanSound() {
        for (double h : new double[]{0, 592, 2000}) {
            for (double g = 100; g < 100_000; g *= 1.2) {
                double speed = (g * 1.2 - g) / (HobBlast.arrivalSeconds(g * 1.2, h, W) - HobBlast.arrivalSeconds(g, h, W));
                assertTrue(speed > 0.99 * BlastModel.SOUND_SPEED, h + " m, " + g + " m: " + speed);
            }
        }
    }

    /** Free air under the explosion comes to the same place as it does to the ground below it. */
    @Test
    void freeAirMeetsTheGroundUnderTheBurst() {
        assertEquals(HobBlast.arrivalSeconds(0, H, W), HobBlast.freeAirArrivalSeconds(H, W), 0.01);
    }

    /**
     * Positive phase on the ground at 15 kt on 592 m - numbers of the model itself: up to 2 psi (2.8 km) - Broad (PT), below 1 psi
     * (4.5 km) - Kinney-Graham at the listener (checked with Garces and ANSI S2.20 in {@code BlastModelTest}).
     */
    @ParameterizedTest
    @CsvSource({"0, 0.45", "1000, 0.74", "2000, 0.93", "5000, 1.29", "10000, 1.44"})
    void positivePhase(double groundM, double seconds) {
        assertEquals(seconds, HobBlast.positivePhaseSeconds(groundM, H, W), 0.01);
    }

    /** The phase increases with range at any detonation height (with 300 m: closer - fireball), and the Broad-Kinney-Graham junction is without a step. */
    @Test
    void positivePhaseLengthensWithoutStep() {
        for (double h : new double[]{0, 150, 592, 2000}) {
            double prev = 0;
            for (double g = 300; g < 200_000; g *= 1.01) {
                double t = HobBlast.positivePhaseSeconds(g, h, W), was = prev, at = g;
                assertTrue(t >= was * 0.999, () -> h + " m, " + at + " m: " + t + " < " + was);
                assertTrue(was == 0 || t <= was * 1.03, () -> "step: " + h + " m, " + at + " m");
                prev = t;
            }
        }
    }

    /**
     * Pressure along the ground (G&amp;D Fig. 3.75, 15 kt on 592 m): under blast - zero, 1 psi on 170 m, 2 psi at 450 m, ~4 psi at
     *  the beginning of the Mach region; further - Rankine-Hugoniot for pressure.
     */
    @Test
    void dynamicPressureAlongTheGround() {
        assertEquals(0, along(0), 1e-6);
        assertEquals(1, along(170), 0.25);
        assertEquals(2, along(450), 0.4);
        assertEquals(4, along(HobBlast.machStem(H, W)), 0.6);
        assertEquals(BlastModel.psi(BlastModel.dynamicPressureKpa(BlastModel.kpa(HobBlast.psi(1500, H, W)))), along(1500), 1e-9);
        assertEquals(1, HobBlast.alongGround(300, 0, W), 0);
    }

    private static double along(double groundM) {
        double q = BlastModel.dynamicPressureKpa(BlastModel.kpa(HobBlast.psi(groundM, H, W)));
        return BlastModel.psi(q) * HobBlast.alongGround(groundM, H, W);
    }
}
