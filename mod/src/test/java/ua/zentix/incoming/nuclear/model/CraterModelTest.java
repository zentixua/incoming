package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ua.zentix.incoming.nuclear.model.CraterModel.Soil;

class CraterModelTest {
    @Test
    void sizes() {
        assertEquals(52, CraterModel.radius(15, Soil.DRY), 1);
        assertEquals(17, CraterModel.depth(15, Soil.DRY), 0.5);
        assertEquals(183, CraterModel.radius(1000, Soil.DRY), 2);
        assertEquals(61, CraterModel.depth(1000, Soil.DRY), 1);
        assertEquals(0.7 * CraterModel.radius(15, Soil.DRY), CraterModel.radius(15, Soil.ROCK), 1e-9);
        assertEquals(1.3 * CraterModel.depth(15, Soil.DRY), CraterModel.depth(15, Soil.WET), 1e-9);
    }

    /**
     * charge crater on dry ground - within the limits of open data on ground explosions (complaint 23.10: a missile across the field
     *  left a hole 2×2). Ambrosini, Luccioni, Danesi, "Craters produced by explosions on the soil surface", Mecánica
     * Computacional XXII (2003): experiment EMRTC - 250 kg TNT on the ground, Ø 3,8 m; calculation with the charge center at ground level
     *  (table 5, b) - 50–500 kg; statistics ~200 of ground explosions of Kinney and Graham - 0,8·∛W with a spread of 30%.
     */
    @Test
    void chargeCraterWithinData() {
        assertEquals(3.8, 2 * CraterModel.chargeRadius(250, Soil.DRY), 0.15 * 3.8, "EMRTC 250 kg");
        double[][] table = {{50, 2.76}, {100, 2.92}, {150, 3.40}, {200, 3.58}, {250, 3.78}, {300, 3.94}, {400, 4.50}, {500, 4.60}};
        for (double[] row : table) {
            assertEquals(row[1], 2 * CraterModel.chargeRadius(row[0], Soil.DRY), 0.15 * row[1], row[0] + " kg");
        }
        for (double kg : new double[] {3, 6.4, 50, 150, 2423}) {
            double d = 2 * CraterModel.chargeRadius(kg, Soil.DRY) / Math.cbrt(kg);
            assertTrue(d >= 0.8 * 0.7 && d <= 0.8 * 1.3, kg + " kg: D/∛W " + d);
            assertEquals(0.25, CraterModel.chargeDepth(kg, Soil.DRY) / (2 * CraterModel.chargeRadius(kg, Soil.DRY)), 1e-9);
        }
    }

    @Test
    void profile() {
        double r = CraterModel.radius(15, Soil.DRY), d = CraterModel.depth(15, Soil.DRY);
        assertEquals(d, CraterModel.profileDepth(0, 15, Soil.DRY), 1e-9);
        assertEquals(0, CraterModel.profileDepth(r, 15, Soil.DRY), 1e-9);
        assertEquals(0, CraterModel.profileDepth(2 * r, 15, Soil.DRY), 0);
        assertEquals(0.25 * d, CraterModel.rimHeight(r, 15, Soil.DRY), 1e-9);
        assertEquals(0, CraterModel.rimHeight(0, 15, Soil.DRY), 0);
        assertEquals(0, CraterModel.rimHeight(2 * r, 15, Soil.DRY), 1e-9);
        assertTrue(CraterModel.rimHeight(1.5 * r, 15, Soil.DRY) > 0);
        // surface without jumps: step 0.1 m changes height less than 0.2 m
        double prev = -d;
        for (double x = 0; x < 2.5 * r; x += 0.1) {
            double h = CraterModel.rimHeight(x, 15, Soil.DRY) - CraterModel.profileDepth(x, 15, Soil.DRY);
            assertTrue(Math.abs(h - prev) < 0.2, "jump to " + x);
            prev = h;
        }
    }

    @Test
    void craterOnlyForLowBursts() {
        assertTrue(CraterModel.formsCrater(0, 15));
        assertTrue(CraterModel.formsCrater(20, 15));
        assertFalse(CraterModel.formsCrater(30, 15));
        assertFalse(CraterModel.formsCrater(Yield.optimalBurstHeight(15), 15));
    }
}
