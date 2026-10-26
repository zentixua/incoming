package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.util.Stability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** trail over the Briggs fire: ascent along the way, final ascent in two winds, steady air and calm. */
class FirePlumeTest {
    /**
     * 20 fire blocks: 3,5 MW, convection 2,45 MW - F = g·Q/(π·c_p·ρ·T) ≈ 21,6 m⁴/s³. In neutral air (D) the final
     *  rise 21,425·F^¾/u: at 2 m/s - ~107 m, at 8 m/s - four times lower (~27 m); lifting path - 1,6·F^⅓·x^⅔/u.
     */
    @Test
    void briggsRiseInTwoWinds() {
        double f = 9.80665 * 0.7 * 20 * FirePlume.BLOCK_WATTS / (Math.PI * 1005 * 1.225 * 288.15);
        for (double u : new double[]{2, 8}) {
            FirePlume p = new FirePlume(20, u, Stability.D);
            assertEquals(f, p.flux, 1e-9 * f);
            assertEquals(21.425 * Math.pow(f, 0.75) / u, p.finalRise, 1e-9);
            double x = 0.25 * p.finalX;
            assertEquals(1.6 * Math.cbrt(f) * Math.pow(x, 2.0 / 3) / u, p.rise(x), 1e-9);
            assertEquals(p.finalRise, p.rise(10 * p.finalX), 1e-9);
            assertEquals(p.finalRise, p.rise(p.finalX), 1e-6 * p.finalRise, "the rise is continuous at the end");
        }
        assertEquals(4, new FirePlume(20, 2, Stability.D).finalRise / new FirePlume(20, 8, Stability.D).finalRise, 1e-9);
        assertEquals(26.8, new FirePlume(20, 8, Stability.D).finalRise, 0.5);
    }

    /** At night (F, N = 0,03) - 2,6·(F/(u·s))^⅓, but not above calm 5·F^¼·s^(−⅜); trail below daytime. */
    @Test
    void stableAirHoldsThePlumeLow() {
        FirePlume night = new FirePlume(20, 2, Stability.F), day = new FirePlume(20, 2, Stability.D);
        double s = Stability.brunt(Stability.F) * Stability.brunt(Stability.F);
        double expected = Math.min(2.6 * Math.cbrt(night.flux / (2 * s)), 5.0 * Math.pow(night.flux, 0.25) * Math.pow(s, -0.375));
        assertEquals(expected, night.finalRise, 1e-9);
        assertTrue(night.finalRise < day.finalRise, night.finalRise + " < " + day.finalRise);
        // calm: wind not less than 1 m/s
        assertEquals(1, new FirePlume(20, 0, Stability.F).wind, 0);
    }

    /** The smoke is thick: trail 20 blocks of fire are visible hundreds of meters; soot per meter - heat × output / heat / wind. */
    @Test
    void plumeIsSeenFar() {
        FirePlume p = new FirePlume(20, 3, Stability.D);
        assertEquals(20 * FirePlume.BLOCK_WATTS * 0.015 / 12.4e6 / 3, p.sootPerMetre(), 1e-12);
        double seen = p.visible(2000);
        assertTrue(seen > 300, "visible on " + seen + " m");
        assertTrue(p.tau(seen) >= 0.02 - 1e-6);
        // it’s hot at the hearth, there’s air above
        assertTrue(p.excess(1) > 100 && p.excess(50) < 10, p.excess(1) + " " + p.excess(50));
    }

    /**
     * The smoke in the air carries the power of the hearth at the moment of exit: history is the value between the records in a straight line, at the weakened hearth there is smoke
     *  on the path x - the former (x/u seconds ago), and not the current one; and after the buffer has gone around the circle.
     */
    @Test
    void smokeKeepsTheStrengthItLeftWith() {
        History h = new History(8);
        assertEquals(0, h.at(5), 0);
        for (int t = 0; t < 20; t++) h.record(t, t < 10 ? 200 : 10);
        assertEquals(10, h.at(19), 0);
        assertEquals(10, h.at(30), 0, "later than the last one - the last one");
        assertEquals(10, h.at(3), 0, "the buffer remembers the last 8 records: before the first of them - the first");
        // hearth 200 blocks 10 s, then 10: in the wind 4 m/s smoke in 30 m from the hearth through 12 s - more from 200 blocks
        History fire = new History(64);
        for (int t = 0; t <= 12; t++) fire.record(t, t < 10 ? 200 : 10);
        assertEquals(105, fire.at(9.5), 1e-9, "between records - in a straight line");
        double u = 4;
        assertEquals(200, fire.at(12 - 30 / u), 1e-9);
        assertEquals(10, fire.at(12 - 4 / u), 1e-9);
    }

    /**
     * Visible flame ({@link FirePlume#flameLight}) - luminous soot, not the average radiation flux: ~0,1 of a white wall at
     * noon (10³–10⁴ cd/m² - order of brightness of an open flame; one {@link FirePlume#FLAME_K} came out 3·10⁻⁴ —
     *  tongues are black during the day), color is orange (red is the largest, there is almost no blue).
     */
    @org.junit.jupiter.api.Test
    void visibleFlameIsTheLuminousSoot() {
        double[] l = new double[4];
        FirePlume.flameLight(l);
        org.junit.jupiter.api.Assertions.assertTrue(l[3] > 0.03 && l[3] < 0.3, "flame brightness during the day: " + l[3]);
        org.junit.jupiter.api.Assertions.assertEquals(l[3], FirePlume.FLAME_LUMINANCE, 1e-12);
        int c = FirePlume.FLAME_COLOR;
        org.junit.jupiter.api.Assertions.assertEquals(255, c >> 16 & 255);
        org.junit.jupiter.api.Assertions.assertTrue((c & 255) < 64 && (c >> 8 & 255) > 64, "orange: " + Integer.toHexString(c));
    }
}
