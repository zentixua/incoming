package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackBodyTest {
    /** Black body temperature of the sun - the disk of the sun: its brightness against a white wall at noon. */
    @Test
    void solarTemperatureIsTheSunDisk() {
        assertEquals(BlackBody.SUN_DISK, BlackBody.luminance(BlackBody.SUN_K), 1e-6 * BlackBody.SUN_DISK);
        double[] c = new double[4];
        BlackBody.light(BlackBody.SUN_K, c);
        assertEquals(BlackBody.SUN_DISK, c[3], 1e-6 * BlackBody.SUN_DISK);
    }

    /** Hotter is brighter throughout the entire range of the table. */
    @Test
    void hotterIsBrighter() {
        double last = 0;
        for (double k = 500; k <= 30_000; k *= 1.05) {
            double l = BlackBody.luminance(k);
            assertTrue(l > last, k + " K: " + l + " no brighter " + last);
            last = l;
        }
    }

    /**
     * Color - from dark red to white and blue: 1 500 K - red-orange (almost no blue), 6 500 K - almost white
     *  (this is the white dot D65), 15 000 K - blue.
     */
    @Test
    void colourGoesFromRedToWhiteToBlue() {
        double[] c = new double[4];
        BlackBody.light(1500, c);
        assertTrue(c[0] > c[1] && c[1] > c[2] && c[2] < 0.05 * c[0], "1500 K: " + c[0] + " " + c[1] + " " + c[2]);
        BlackBody.light(6500, c);
        double hi = Math.max(c[0], Math.max(c[1], c[2])), lo = Math.min(c[0], Math.min(c[1], c[2]));
        assertTrue(hi < 1.15 * lo, "6500 K: " + c[0] + " " + c[1] + " " + c[2]);
        BlackBody.light(15_000, c);
        assertTrue(c[2] > c[0], "15000 K: " + c[0] + " " + c[1] + " " + c[2]);
    }
}
