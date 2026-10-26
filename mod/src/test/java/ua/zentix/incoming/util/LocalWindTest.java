package ua.zentix.incoming.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wind near the ground of the place ({@link LocalWind}): field - without correction, slope - not building, block - roughness and street according to
 *  morphometry Macdonald 1998, hill and lowland - according to Taylor, Lee 1984.
 */
class LocalWindTest {
    /** Quarter: houses 10×10 m height 12 m, streets along 6 m, land at 64. */
    static double town(int x, int z) {
        return Math.floorMod(x, 16) < 10 && Math.floorMod(z, 16) < 10 ? 76 : 64;
    }

    /** Flat field - no correction at all heights; earth - field. */
    @Test
    void openFieldIsTheWorldWind() {
        LocalWind w = LocalWind.of((x, z) -> 70, 0, 0, 1, 0);
        assertEquals(70, w.ground(), 1e-9);
        assertEquals(0, w.canopy(), 1e-9);
        for (double z = 1; z <= 1000; z *= 2) assertEquals(1, w.factor(z), 1e-9, z + " m");
    }

    /** The flat slope 20° is land, not buildings: there is no building, the slope without a peak is almost not changed by the wind. */
    @Test
    void slopeIsGroundNotBuildings() {
        LocalWind w = LocalWind.of((x, z) -> 100 + Math.floor(0.36 * x), 0.5, 0.5, 0, 1);
        assertEquals(0, w.canopy(), 1e-9, "slope removed with earth filter");
        assertEquals(100, w.ground(), 1.01);
        assertEquals(1, w.factor(2), 0.1);
    }

    /**
     * Quarter (λp = 0,39, λf ≈ 0,47): displacement height and roughness - in the scatter of measurements of dense buildings (d/H 0,5–0,8,
     * z₀/H 0,05–0,15; Grimmond, Oke 1999); weaker than the field, grows with height and above the height of
     *  mixing (3H) - as in a field.
     */
    @Test
    void streetIsShelteredBelowTheRoofs() {
        // place - street intersection
        LocalWind w = LocalWind.of(LocalWindTest::town, 13, 13, 1, 0);
        assertEquals(64, w.ground(), 1e-9);
        assertEquals(12, w.canopy(), 0.5);
        assertTrue(w.displacement() / w.canopy() > 0.5 && w.displacement() / w.canopy() < 0.8, "d/H " + w.displacement() / w.canopy());
        assertTrue(w.roughness() / w.canopy() > 0.05 && w.roughness() / w.canopy() < 0.15, "z₀/H " + w.roughness() / w.canopy());
        double eye = w.factor(1.6);
        assertTrue(eye > 0.03 && eye < 0.3, "at the eye " + eye);
        double last = 0;
        for (double z = 1; z <= 36; z += 1) {
            double f = w.factor(z);
            assertTrue(f >= last - 1e-9, z + " m: " + f + " < " + last);
            last = f;
        }
        assertEquals(1, w.factor(36), 1e-9);
        assertEquals(1, w.factor(100), 1e-9);
    }

    /** An explosion on the roof of a house: the wind above the roof is as high as it is above the street, and not at the ground. */
    @Test
    void roofIsMeasuredFromTheStreet() {
        LocalWind street = LocalWind.of(LocalWindTest::town, 13, 13, 1, 0), roof = LocalWind.of(LocalWindTest::town, 5, 5, 1, 0);
        assertEquals(12, roof.base(), 1e-9);
        assertTrue(roof.factor(2) > street.factor(2), "above the roof is stronger than in the street");
        assertEquals(street.overGround(14), roof.overGround(14), 0.1);
    }

    /**
     * The height is measured from the source, and not from the top of the column of the first point of the cache cell (review R468: the roof and the street nearby divided the
     *  cell 4 m, and the street took the wind of the roof): the same place from the street - the wind of the street, from the roof - the wind of the roof.
     */
    @Test
    void baseIsTheSourceNotTheCell() {
        LocalWind street = LocalWind.of(LocalWindTest::town, 13, 13, 1, 0), roof = LocalWind.of(LocalWindTest::town, 9, 5, 1, 0);
        assertEquals(street.factor(2), roof.from(64).factor(2), 0.05);
        assertEquals(roof.factor(2), street.from(76).factor(2), 0.1);
        assertTrue(roof.from(64).factor(2) < 0.3 * roof.factor(2), "street " + roof.from(64).factor(2) + ", roof " + roof.factor(2));
        assertEquals(LocalWind.OPEN, LocalWind.OPEN.from(100));
    }

    /**
     * A hill with a height of 30 m with a half width of 40 m: at the top near the ground the wind is stronger, the acceleration decreases towards the height; in the same
     *  depression - weaker.
     */
    @Test
    void hillTopIsWindierAndHollowIsCalmer() {
        LocalWind top = LocalWind.of((x, z) -> 64 + Math.floor(30 * Math.exp(-Math.log(2) * (x * x + z * z) / (40.0 * 40))), 0, 0, 1, 0);
        double f2 = top.factor(2), f50 = top.factor(50);
        assertTrue(f2 > 1.3 && f2 < 1.9, "at the top of the earth " + f2);
        assertTrue(f50 < f2 && f50 > 1, "in 50 m " + f50);
        LocalWind hollow = LocalWind.of((x, z) -> 94 - Math.floor(30 * Math.exp(-Math.log(2) * (x * x + z * z) / (40.0 * 40))), 0, 0, 1, 0);
        assertTrue(hollow.factor(2) < 0.9 && hollow.factor(2) >= 0.5, "in the lowlands " + hollow.factor(2));
    }

    /** Unknown terrain (uncongested area) - field. */
    @Test
    void unknownIsOpen() {
        assertEquals(LocalWind.OPEN, LocalWind.of((x, z) -> Double.NaN, 0, 0, 1, 0));
    }
    /** A moving minimum and maximum are like direct search of a window, at the edges and with the unknown. */
    @Test
    void runningWindowIsTheWindow() {
        java.util.Random rnd = new java.util.Random(7);
        for (int trial = 0; trial < 200; trial++) {
            int m = 1 + rnd.nextInt(60), w = rnd.nextInt(8);
            double[] v = new double[m], out = new double[m];
            for (int i = 0; i < m; i++) v[i] = rnd.nextInt(10) == 0 ? Double.NaN : rnd.nextInt(100);
            for (boolean min : new boolean[]{true, false}) {
                LocalWind.run(v, out, w, min);
                for (int i = 0; i < m; i++) {
                    double r = Double.NaN;
                    for (int p = Math.max(0, i - w); p <= Math.min(m - 1, i + w); p++) {
                        if (Double.isNaN(v[p])) continue;
                        if (Double.isNaN(r) || (min ? v[p] < r : v[p] > r)) r = v[p];
                    }
                    assertEquals(r, out[i], 0, "m " + m + " w " + w + " i " + i);
                }
            }
        }
    }
}
