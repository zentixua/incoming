package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BurnInjury.Cover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BurnInjuryTest {
    /** Thresholds - fig. 12.64 Glasstone &amp; Dolan at the reference points, between them grow with power. */
    @Test
    void thresholdsFollowFigure1264() {
        assertEquals(2.38, BurnInjury.median(1, 10), 1e-9);
        assertEquals(5.12, BurnInjury.median(2, 100), 1e-9);
        assertEquals(10.0, BurnInjury.median(3, 1000), 1e-9);
        for (int k = 1; k <= 3; k++) {
            double prev = 0;
            for (double y = 1; y <= 10_000; y *= 1.5) {
                double m = BurnInjury.median(k, y);
                assertTrue(m > prev, "degree " + k + " at " + y + " kt");
                prev = m;
                if (k > 1) assertTrue(m > BurnInjury.median(k - 1, y));
            }
        }
    }

    /** §12.65: in 1 Mt among people with 4,5–6 cal/cm² burn of 2 degree in 18%. */
    @Test
    void spreadMatchesExample1265() {
        int n = 1000;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double q = 4.5 + 1.5 * (i + 0.5) / n;
            sum += Fragility.probability(q, BurnInjury.median(2, 1000), BurnInjury.BETA);
        }
        assertEquals(0.18, sum / n, 0.02);
    }

    /** The degree of randomness of the skin: the median is exactly the threshold, sensitive skin burns more. */
    @Test
    void degreeBySkin() {
        double y = 15;
        assertEquals(0, BurnInjury.degree(BurnInjury.median(1, y) * 0.99, y, 0));
        assertEquals(1, BurnInjury.degree(BurnInjury.median(1, y), y, 0));
        assertEquals(2, BurnInjury.degree(BurnInjury.median(2, y), y, 0));
        assertEquals(3, BurnInjury.degree(BurnInjury.median(3, y), y, 0));
        assertEquals(3, BurnInjury.degree(BurnInjury.median(3, y) * 0.85, y, 1));
        assertEquals(1, BurnInjury.degree(BurnInjury.median(2, y), y, -2));
        // below the lowest threshold - no one
        assertEquals(0, BurnInjury.degree(BurnInjury.lowest(y) * 0.99, y, 10));
        assertEquals(1, BurnInjury.degree(BurnInjury.lowest(y), y, 10));
    }

    /** Table 7.35: Cotton Shirt (3 oz.) and Thick Cotton (8 oz.) light up in power. */
    @Test
    void clothingIgnitesByTable735() {
        assertFalse(BurnInjury.ignites(Cover.THIN, 13.9, 35));
        assertTrue(BurnInjury.ignites(Cover.THIN, 14, 35));
        assertTrue(BurnInjury.ignites(Cover.THIN, 21, 1400));
        assertFalse(BurnInjury.ignites(Cover.HEAVY, 29.9, 1400));
        assertTrue(BurnInjury.ignites(Cover.HEAVY, 39, 20_000));
        assertFalse(BurnInjury.ignites(Cover.SKIN, 1000, 15));
        assertFalse(BurnInjury.ignites(Cover.SOLID, 1000, 15));
        // 15 CT pulse is shorter, the tissue lights up earlier than 35
        assertTrue(BurnInjury.ignites(Cover.THIN, 13, 15));
    }

    /** §12.62: shock from 3 degree at 25 % of the body or 2 degree at 30 %; occurs in 0,5–12 hours. */
    @Test
    void shockBySection1262() {
        assertEquals(1, BurnInjury.shock(0, 25), 1e-9);
        assertEquals(1, BurnInjury.shock(30, 0), 1e-9);
        assertTrue(BurnInjury.shock(15, 13) > 1);
        assertTrue(BurnInjury.shock(0, 9.9) < 0.5);
        assertEquals(0.5, BurnInjury.shockHours(0), 1e-9);
        assertEquals(12, BurnInjury.shockHours(1), 1e-9);
        assertEquals(Math.sqrt(0.5 * 12), BurnInjury.shockHours(0.5), 1e-9);
    }

    /** Levels of health loss: tenths upward, from 0,9 to shock - 0,9. */
    @Test
    void levelRoundsUpToTenthsAndHoldsNinetyUntilShock() {
        assertEquals(0, BurnInjury.level(0));
        assertEquals(4, BurnInjury.level(0.396));
        assertEquals(4, BurnInjury.level(0.4));
        assertEquals(9, BurnInjury.level(0.95));
        assertEquals(BurnInjury.SHOCK_LEVEL, BurnInjury.level(1));
    }

    /** The wound is just an addition to the previous burns. */
    @Test
    void woundIsOnlyTheRiseOfTheLevel() {
        assertEquals(0.4, BurnInjury.wound(-1, 4), 1e-12, "first burns");
        assertEquals(0.9, BurnInjury.wound(-1, BurnInjury.level(0.95)), 1e-12, "from 0,9 to shock - 0,9");
        assertEquals(0.2, BurnInjury.wound(4, 6), 1e-12, "heavier than before - increase");
        assertEquals(0.05, BurnInjury.wound(9, BurnInjury.SHOCK_LEVEL), 1e-12, "shock after 0,9 - before 5 %");
        assertEquals(1 - BurnInjury.SHOCK_LEFT, BurnInjury.wound(-1, BurnInjury.SHOCK_LEVEL), 1e-12, "shock immediately");
        assertEquals(0, BurnInjury.wound(6, 4), "weaker than before");
        assertEquals(0, BurnInjury.wound(4, 4), "the same");
    }
}
