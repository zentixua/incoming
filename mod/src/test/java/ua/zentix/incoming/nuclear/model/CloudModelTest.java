package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudModelTest {
    /**
     * “Grable”, 15 kt on 160 m: top above the ground (Hawthorne 1979, red lines Fig. 2 Blakeley et al., taken from graphics
     * 300 dpi, ±0,1 km). The law is one exponential: at the 15 second it is below the measurement by 11%, with the 30 second it is within 6%.
     */
    @Test
    void grableRise() {
        double hob = 159.7, y = 15;
        double[][] seen = {{15, 1730}, {30, 2650}, {45, 3440}, {60, 4140}, {90, 5270}, {120, 6150}, {180, 7530}, {240, 8410},
                {300, 9030}, {360, 9480}, {420, 9770}, {480, 9920}, {570, 9970}};
        for (double[] p : seen) {
            double top = CloudModel.top(p[0], hob, y, false);
            assertEquals(p[1], top, p[1] * (p[0] < 30 ? 0.12 : 0.06), () -> p[0] + " from: " + top);
        }
        double end = CloudModel.top(1e5, hob, y, false);
        assertEquals(10_000, end, 100, "top after stabilization");
        // bottom cap from 5-th to 6,5-th minute - 6,1–6,2 km
        assertEquals(6_150, CloudModel.capBottom(1e5, hob, y, false), 300, "bottom cap");
        assertEquals(6_090, CloudModel.capBottom(390, hob, y, false), 6_090 * 0.06, "bottom cap at 6,5 minute");
    }

    /**
     * “Wasp”, 1 kt on 232 m: top above the ground according to the same measurements - within 5% from 15 second to 8 minute; after 8
     *  minutes it is still growing - in the last minute by ~4 m/s (5,02 → 5,27 km), and the bottom of the cap at the 6,5 minute is 3,2 km.
     */
    @Test
    void waspStillRisingAtEightMinutes() {
        double hob = 232.3, y = 1;
        double[][] seen = {{15, 680}, {30, 1040}, {60, 1600}, {90, 2150}, {120, 2600}, {150, 3010}, {180, 3370}, {240, 3940},
                {300, 4380}, {360, 4740}, {420, 5020}, {480, 5270}};
        for (double[] p : seen) {
            double top = CloudModel.top(p[0], hob, y, false);
            assertEquals(p[1], top, p[1] * 0.05, () -> p[0] + " from: " + top);
        }
        double v = CloudModel.riseSpeed(480, hob, y, false);
        assertTrue(v > 2 && v < 6, "after 8 minutes it still grows: " + v + " m/s");
        assertEquals(3_190, CloudModel.capBottom(390, hob, y, false), 3_190 * 0.08, "bottom cap");
        assertTrue(CloudModel.stabilizationSeconds(y) > 480, "by 8 minute it did not stabilize");
    }

    /**
     * 1 Mt (Glasstone and Dolan, table 2.12, “rough average”): top through 0,3; 0,7; 1,1; 2,5 and 3,8 minutes - 2, 4, 6, 10 and
     * 12 miles above the detonation point. There the rise is almost flat and comes up at the tropopause - the exponential diverges from it to 18%.
     */
    @Test
    void megatonRise() {
        double mile = 1609.344;
        double[][] table = {{18, 2}, {42, 4}, {66, 6}, {150, 10}, {228, 12}};
        for (double[] p : table) {
            double top = CloudModel.top(p[0], 0, 1000, false), seen = p[1] * mile;
            assertEquals(seen, top, seen * 0.2, () -> p[0] + " from: " + top);
        }
        // in the first minute - on average ~134 m/s (300 miles per hour)
        assertEquals(134, (CloudModel.top(60, 0, 1000, false) - CloudModel.top(0, 0, 1000, false)) / 60, 134 * 0.2);
    }

    @Test
    void stabilizedTop() {
        // above the detonation point: “Grable” and “Wasp” minus the detonation height, megatons - Glasstone and Dolan (Fig. 2.16)
        assertEquals(9_840, CloudModel.stabilizedTop(15), 1);
        assertEquals(5_660, CloudModel.stabilizedTop(1), 1);
        double mt = CloudModel.stabilizedTop(1000);
        assertTrue(mt >= 20_000 && mt <= 22_000, "1 Mt: " + mt);
        double tenMt = CloudModel.stabilizedTop(10_000);
        assertTrue(tenMt >= 28_000 && tenMt <= 32_000, "10 Mt: " + tenMt);
        double prev = 0;
        for (double y = 0.01; y < 1e5; y *= 1.2) {
            double h = CloudModel.stabilizedTop(y);
            assertTrue(h > prev, y + " kt");
            prev = h;
        }
    }

    @Test
    void capRadius() {
        double r15 = CloudModel.capRadius(15);
        assertTrue(r15 >= 1500 && r15 <= 2500);
        assertEquals(10000, CloudModel.capRadius(1000), 1000);
    }

    /**
     * 15 kt - ~6 minutes (to 7 minutes "Grable" within 3 % from the last top), 1 MT to 10 minutes - within 2 %
     * ("about 10 minutes", Glasstone and Dolan, §2.15).
     */
    @Test
    void stabilizationTime() {
        double s15 = CloudModel.stabilizationSeconds(15);
        assertTrue(s15 >= 330 && s15 <= 450, "15 kt: " + s15);
        assertTrue(CloudModel.top(600, 0, 1000, false) > 0.98 * CloudModel.top(1e5, 0, 1000, false), "1 Mt to 10 minutes");
        for (double y = 0.01; y < 1e5; y *= 1.2) {
            double ts = CloudModel.stabilizationSeconds(y);
            assertTrue(ts >= 3 * 121 - 1e-9 && ts <= 3 * 227 + 1e-9, y + " kt: " + ts);
        }
    }

    @Test
    void cloudRisesToTop() {
        for (double y : new double[] {0.1, 1, 15, 100, 1000, 10_000}) {
            for (double hob : new double[] {0, 240 * Math.cbrt(y)}) {
                boolean surface = hob == 0;
                double ts = CloudModel.stabilizationSeconds(y), top = hob + CloudModel.stabilizedTop(y);
                // at the moment of detonation - the top of the largest ball
                assertEquals(hob + 1.3 * FireballModel.maxRadius(y, surface), CloudModel.top(0, hob, y, surface), 1e-9);
                assertEquals(top, CloudModel.top(ts, hob, y, surface), top * 0.06, () -> y + " kt");
                double prev = -1;
                for (double t = 0; t < 4 * ts; t += 5) {
                    double h = CloudModel.top(t, hob, y, surface);
                    assertTrue(h > prev, y + " cT, " + t + " s");
                    assertTrue(CloudModel.capBottom(t, hob, y, surface) < h);
                    prev = h;
                }
            }
        }
        assertEquals(0.6, CloudModel.capBottom(1e5, 0, 15, false) / CloudModel.top(1e5, 0, 15, false), 1e-6);
    }

    @Test
    void wilson() {
        assertFalse(CloudModel.wilsonCloud(0));
        assertTrue(CloudModel.wilsonCloud(0.4));
        assertTrue(CloudModel.wilsonCloud(1));
    }
}
