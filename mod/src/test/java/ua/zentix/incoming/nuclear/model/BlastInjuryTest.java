package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastInjuryTest {
    private static final double FT = 0.3048;

    /** G&D tab. 12.38: death from pressure - 1% with 40 psi, 50% with 62, almost all with 92; the lungs suffer with 12. */
    @Test
    void lungsByTable() {
        assertEquals(0.01, BlastInjury.lungDeath(BlastModel.kpa(40)), 0.01);
        assertEquals(0.5, BlastInjury.lungDeath(BlastModel.kpa(62)), 1e-9);
        assertTrue(BlastInjury.lungDeath(BlastModel.kpa(92)) > 0.98);
        assertTrue(BlastInjury.lungInjury(BlastModel.kpa(12)) < 0.02);
        assertEquals(0.5, BlastInjury.lungInjury(BlastModel.kpa(25)), 1e-9);
        assertEquals(0, BlastInjury.lungDeath(BlastModel.kpa(20)), 1e-6);
    }

    /** Reflected pressure: for a weak wave - twice, for a strong one - up to eight times. */
    @Test
    void wallDoublesPressure() {
        assertEquals(2, BlastInjury.reflectedKpa(1) / 1, 0.01);
        assertTrue(BlastInjury.reflectedKpa(BlastModel.kpa(60)) / BlastModel.kpa(60) > 4);
        double p = BlastModel.kpa(10);
        double q = BlastModel.dynamicPressureKpa(p);
        assertEquals(p, BlastInjury.effectiveKpa(p, q, false, 0), 0);
        assertEquals(p + q, BlastInjury.effectiveKpa(p, q, false, 1), 1e-9);
        assertEquals(BlastInjury.reflectedKpa(p), BlastInjury.effectiveKpa(p, q, true, 1), 0);
    }

    /** G&D tab. 12.49: impact with a solid - death threshold 21 ft/s, 50 % with 54, almost all with 138; skull - 13 / 18 / 23. */
    @Test
    void impactByTable() {
        assertEquals(0.01, BlastInjury.impactDeath(21 * FT), 0.01);
        assertEquals(0.5, BlastInjury.impactDeath(54 * FT), 0.01);
        assertTrue(BlastInjury.impactDeath(138 * FT) > 0.98);
        assertTrue(BlastInjury.impactInjury(13 * FT) < 0.01);
        assertEquals(0.5, BlastInjury.impactInjury(18 * FT), 0.01);
        assertTrue(BlastInjury.impactInjury(23 * FT) > 0.97);
        assertTrue(BlastInjury.impactDeath(10 * FT) < 1e-3, "10 ft/s - almost safe");
        assertEquals(0.01, BlastInjury.tumbleInjury(30 * FT), 0.01);
        assertEquals(0.5, BlastInjury.tumbleInjury(75 * FT), 0.01);
        assertTrue(BlastInjury.tumbleDeath(30 * FT) < BlastInjury.impactDeath(30 * FT));
    }

    /**
     * G&D §12.44: dummy at 165 lbs standing with back to explosion at 5,3 psi - highest velocity 21 ft/s through 9 ft.,
     *  total 13 ft. in the air and 9 tumbling (22 ft.). The power is not named there (tests in Nevada - tens of kt on the
     *  ground): reconciliation against 20 kt on the ground, 30 kt on 150 m and 40 kt on 210 m.
     */
    @Test
    void standingDummyAt53Psi() {
        for (double[] c : new double[][]{{20, 0}, {30, 150}, {40, 210}}) {
            BlastInjury.Throw t = throwAt(5.3, c[0], c[1]);
            assertEquals(21 * FT, t.peak(), 21 * FT * 0.15, c[0] + " kt");
            assertTrue(t.stop() > 0.8 * 22 * FT && t.stop() < 1.4 * 22 * FT, c[0] + " kt: " + t.stop() / FT + " feet");
        }
    }

    /** The stronger and longer the wave, the faster the throw; the body slows down somersaults and stops. */
    @Test
    void throwGrowsWithPressureAndYield() {
        double prev = 0;
        for (double psi : new double[]{1, 2, 3, 5, 8, 12, 20}) {
            double v = throwAt(psi, 15, 0).peak();
            assertTrue(v > prev, psi + " psi");
            assertTrue(v < BlastModel.windSpeed(BlastModel.kpa(psi)), "no faster than the wind");
            prev = v;
        }
        double small = throwAt(5, 1, 0).peak();
        double big = throwAt(5, 1000, 0).peak();
        assertTrue(big > 3 * small, small + " → " + big);
        BlastInjury.Throw t = throwAt(8, 15, 0);
        assertEquals(0, t.speedAfter(0), 0);
        assertEquals(0, t.speedAfter(t.stop() + 1), 0);
        assertTrue(t.speedAfter(t.path()[t.path().length - 1] + 1) < t.peak());
        assertEquals(0, BlastInjury.thrown(BlastModel.kpa(8), 50, 1, 0).peak(), 0);
    }

    /** Throwing a person on the ground, where the pressure is {@code psi}: the wind along the ground and the positive phase along the height of the explosion. */
    private static BlastInjury.Throw throwAt(double psi, double yieldKt, double hobM) {
        double g = HobBlast.rangeFor(psi, hobM, yieldKt), kpa = BlastModel.kpa(psi);
        return BlastInjury.thrown(kpa, BlastModel.windSpeed(kpa) * Math.sqrt(HobBlast.alongGround(g, hobM, yieldKt)),
                HobBlast.positivePhaseSeconds(g, hobM, yieldKt), BlastInjury.HUMAN_DRAG_PER_KG);
    }

    /** G&D tab. 12.43: window fragments - 108 fps at 1,9 psi, 140–168 at 3,8, 170 at 5,0. */
    @Test
    void glassSpeedByTable() {
        assertEquals(108 * FT, BlastInjury.glassSpeed(BlastModel.kpa(1.9)), 108 * FT * 0.1);
        assertEquals(154 * FT, BlastInjury.glassSpeed(BlastModel.kpa(3.8)), 154 * FT * 0.1);
        assertEquals(170 * FT, BlastInjury.glassSpeed(BlastModel.kpa(5.0)), 170 * FT * 0.1);
        assertTrue(BlastInjury.glassInjury(BlastModel.kpa(1)) < 0.001);
    }

    /** Closer to your threshold - more seriously wounded; beyond the threshold - died; the seriously wounded remains barely alive. */
    @Test
    void harmByFate() {
        assertEquals(1, BlastInjury.lostShare(0.3, 0.6, 0.2), 0);
        assertEquals(BlastInjury.CASUALTY_SHARE, BlastInjury.lostShare(0.3, 0.6, 0.5), 0);
        assertEquals(0.1, BlastInjury.lostShare(0, 0.05, 0.5), 1e-9);
        assertEquals(0, BlastInjury.lostShare(0, 0, 0.5), 0);
        assertEquals(0.5, BlastInjury.any(0.5, 0), 0);
        assertEquals(0.75, BlastInjury.any(0.5, 0.5), 1e-12);
    }
}
