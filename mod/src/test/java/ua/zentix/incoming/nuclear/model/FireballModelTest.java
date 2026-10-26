package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FireballModelTest {
    @Test
    void maxRadius() {
        assertEquals(207, FireballModel.maxRadius(15, false), 3);
        assertEquals(261, FireballModel.maxRadius(15, true), 3);
        assertEquals(1400, FireballModel.maxRadius(1000, true), 20);
    }

    @Test
    void doubleFlashTimes() {
        assertEquals(0.12, FireballModel.secondMaximumSeconds(15), 0.01);
        assertEquals(1.0, FireballModel.secondMaximumSeconds(1000), 0.02);
        assertEquals(0.0097, FireballModel.firstMinimumSeconds(15), 0.0005);
    }

    /**
     * Light pulse - 10·t_max (for 15 kt 1,24 s): the emitted fraction increases from 0 to 1, to the maximum brightness - about the fifth
     *  part; the inverse function (when the light has gained a fraction) is the same curve.
     */
    @Test
    void thermalPulseFractionAndItsInverse() {
        assertEquals(1.24, FireballModel.pulseSeconds(15), 0.01);
        assertEquals(0, FireballModel.thermalFraction(0), 0);
        assertEquals(1, FireballModel.thermalFraction(10), 0);
        assertEquals(0, FireballModel.thermalTau(0), 0);
        assertEquals(10, FireballModel.thermalTau(1), 0);
        double last = 0;
        for (double tau = 0.01; tau < 10; tau += 0.01) {
            double f = FireballModel.thermalFraction(tau);
            assertTrue(f > last, "growing: " + tau);
            last = f;
            assertEquals(tau, FireballModel.thermalTau(f), 1e-6, "reverse");
        }
        double atMax = FireballModel.thermalFraction(1), half = FireballModel.thermalTau(0.5);
        assertTrue(atMax > 0.1 && atMax < 0.3, "to maximum: " + atMax);
        assertTrue(half > 1.5 && half < 2.5, "half - to τ " + half);
    }

    /**
     * Visible temperature: at the second maximum - T₂, after - it cools down to the air according to the chronicle (15 kt - about 4 000 K after
     * second, about 1 950 K in nine); before it - Stefan-Boltzmann in brightness and radius: the first pulse is hotter than T₂,
     *  at the minimum is colder than the first pulse, to the second maximum - without a jump.
     */
    @Test
    void temperatureFollowsTheFlashAndTheFootage() {
        double y = 15, tMin = FireballModel.firstMinimumSeconds(y), tMax = FireballModel.secondMaximumSeconds(y);
        assertEquals(FireballModel.PEAK_K, FireballModel.temperature(tMax, y), 1e-9);
        assertEquals(FireballModel.temperature(tMax * (1 - 1e-9), y), FireballModel.temperature(tMax * (1 + 1e-9), y), 0.01);
        double t1 = FireballModel.temperature(1, y), t9 = FireballModel.temperature(9, y);
        assertTrue(t1 > 3_600 && t1 < 4_400, "1 s: " + t1);
        assertTrue(t9 > 1_750 && t9 < 2_150, "9 s: " + t9);
        double first = 0;
        for (double t = 1e-6; t < tMin / 3; t *= 1.1) first = Math.max(first, FireballModel.temperature(t, y));
        assertTrue(first > FireballModel.PEAK_K, "first impulse " + first);
        assertTrue(FireballModel.temperature(tMin, y) < first, "minimum " + FireballModel.temperature(tMin, y));
        assertEquals(FireballModel.AMBIENT_K, FireballModel.temperature(0, y), 0);
        double last = Double.MAX_VALUE;
        for (double t = tMax; t < 1e4; t *= 1.05) {
            double k = FireballModel.temperature(t, y);
            assertTrue(k < last && k > FireballModel.AMBIENT_K, t + " from: " + k);
            last = k;
        }
    }

    @Test
    void doubleFlashShape() {
        double y = 15;
        double tMin = FireballModel.firstMinimumSeconds(y), tMax = FireballModel.secondMaximumSeconds(y);
        double first = 0;
        for (double t = 1e-6; t < tMin / 3; t *= 1.1) first = Math.max(first, FireballModel.brightness(t, y));
        double min = FireballModel.brightness(tMin, y);
        double second = FireballModel.brightness(tMax, y);
        assertTrue(first > 0.9, "first impulse " + first);
        assertTrue(min < 0.15, "minimum " + min);
        assertTrue(second > 0.7 && second < first, "second maximum " + second);
        assertTrue(FireballModel.brightness(1000 * tMax, y) < 0.01);
        assertEquals(0, FireballModel.brightness(0, y), 0);
    }

    @Test
    void mostLightWithinTenMaxima() {
        double y = 15, tMax = FireballModel.secondMaximumSeconds(y);
        double within = integral(y, tMin(y), 10 * tMax), total = integral(y, tMin(y), 1e5 * tMax);
        assertEquals(0.8, within / total, 0.06);
    }

    @Test
    void radiusGrowsThenCools() {
        double y = 15, tMax = FireballModel.secondMaximumSeconds(y), max = FireballModel.maxRadius(y, false);
        assertEquals(max, FireballModel.radius(tMax, y, false), 1e-6);
        assertTrue(FireballModel.radius(tMax / 10, y, false) < max);
        assertTrue(FireballModel.radius(10, y, false) > max);
        assertTrue(FireballModel.radius(1e4, y, false) <= 1.3 * max + 1e-9);
    }

    /**
     * The ball floats up like the top of a cloud (one law): from the height of the explosion with the speed of the top - for 15 kt ~79 m/s, for 1 Mt ~139
     * (Glasstone and Dolan, table. 2.12: in the first minute on average ~134), - and slows down: to stabilization (3τ) - up to a twentieth;
     *  its top is not higher than the top of the cloud.
     */
    @Test
    void risesAndSlowsDown() {
        for (double y : new double[]{1, 15, 1000}) {
            for (boolean surface : new boolean[]{false, true}) {
                double hob = surface ? 0 : 240 * Math.cbrt(y);
                assertEquals(hob, FireballModel.centreHeight(0, hob, y, surface), 0);
                double v0 = FireballModel.centreHeight(1, hob, y, surface) - hob;
                assertEquals(FireballModel.riseSpeed(y, surface), v0, 0.01 * v0);
                assertEquals(CloudModel.riseSpeed(0, hob, y, surface), FireballModel.riseSpeed(y, surface), 1e-9);
                double stab = CloudModel.stabilizationSeconds(y);
                double late = FireballModel.centreHeight(stab, hob, y, surface) - FireballModel.centreHeight(stab - 1, hob, y, surface);
                assertTrue(late < v0 / 15, y + " kt");
                for (double t = 0.01; t < 1e4; t *= 1.1) {
                    double ballTop = FireballModel.centreHeight(t, hob, y, surface) + FireballModel.radius(t, y, surface);
                    assertTrue(ballTop <= CloudModel.top(t, hob, y, surface) + 1e-6, y + " cT, " + t + " c: ball above the cloud");
                }
            }
        }
        double v15 = FireballModel.riseSpeed(15, false);
        assertTrue(v15 > 70 && v15 < 90, "15 kt: " + v15);
        assertEquals(134, FireballModel.riseSpeed(1000, false), 134 * 0.1);
    }

    private static double tMin(double y) {
        return FireballModel.firstMinimumSeconds(y);
    }

    /** Integral of brightness over a logarithmic grid (without the first pulse - from the minimum). */
    private static double integral(double y, double from, double to) {
        int n = 20000;
        double step = Math.log(to / from) / n, s = 0;
        for (int i = 0; i < n; i++) {
            double t = from * Math.exp(step * (i + 0.5));
            s += FireballModel.brightness(t, y) * t * step;
        }
        return s;
    }

    /**
     * Front separation (G&D 2.120, 2.125): for 20 the minimum brightness is 11 ms, separation is 15 ms. To a minimum the pattern of the middle is not
     *  visible, to the separation - all of it; between them it grows without jumps.
     */
    @Test
    void breakawayUnmasksTheInterior() {
        assertEquals(0.011, FireballModel.firstMinimumSeconds(20), 0.0005);
        assertEquals(0.015, FireballModel.breakawaySeconds(20), 0.0007);
        for (double y : new double[]{0.1, 1, 15, 1000}) {
            double from = FireballModel.firstMinimumSeconds(y), to = FireballModel.breakawaySeconds(y);
            assertEquals(0, FireballModel.unmasked(0.5 * from, y), 0);
            assertEquals(0, FireballModel.unmasked(from, y), 1e-12);
            assertEquals(1, FireballModel.unmasked(to, y), 1e-12);
            assertEquals(1, FireballModel.unmasked(10, y), 0);
            double last = 0;
            for (double t = from; t <= to; t += (to - from) / 50) {
                double u = FireballModel.unmasked(t, y);
                assertTrue(u >= last && u - last < 0.05, "without jumps: " + t);
                last = u;
            }
        }
    }

    /**
     * Clock surface pattern - the path of the center ascent in the radii of the ball: up to the second maximum - 0, then increases; by the end of
     *  folding into a ring, the game powers have 2–5 radii (baking counts 5: the pattern does not freeze while it is visible).
     */
    @Test
    void surfaceRiseIsTheRiseInRadii() {
        for (double y : new double[]{1, 15}) {
            for (boolean surface : new boolean[]{false, true}) {
                double hob = surface ? 0 : 40 * Math.cbrt(y) * 10;
                double tm = FireballModel.secondMaximumSeconds(y);
                assertEquals(0, FireballModel.surfaceRise(0.5 * tm, hob, y, surface), 0);
                assertEquals(0, FireballModel.surfaceRise(tm, hob, y, surface), 1e-12);
                double last = 0;
                for (double t = tm * 1.01; t < 30; t *= 1.05) {
                    double r = FireballModel.surfaceRise(t, hob, y, surface);
                    assertTrue(r > last, "growing: " + t);
                    last = r;
                }
                double end = FireballModel.surfaceRise(MushroomModel.rollUpSeconds(y, surface), hob, y, surface);
                assertTrue(end > 2 && end < 5, y + " kt: " + end);
            }
        }
    }
}
