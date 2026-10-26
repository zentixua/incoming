package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastModelTest {
    @Test
    void chemicalFrontArrivalIsCausalAndConverged() {
        for (double charge : new double[]{3, 6.4, 50, 150, 2423}) {
            double previous = 0;
            for (double r : new double[]{0.1, 1, 3, 10, 30, 100, 300}) {
                double actual = BlastModel.chemicalArrivalSeconds(r, charge, true);
                assertTrue(actual > previous && actual < r / BlastModel.SOUND_SPEED);
                double reference = 0, dr = r / 16384;
                for (int i = 0; i < 16384; i++) reference += dr / BlastModel.frontSpeed(BlastModel.surfaceOverpressureKpa((i + 0.5) * dr, charge));
                // Much tighter than the server's 50 ms tick; no zero-time body hits in front of the wave.
                assertEquals(reference, actual, Math.max(1e-6, reference * 0.002));
                previous = actual;
            }
        }
    }

    @Test
    void frontSlowsToSoundSpeed() {
        assertEquals(343, BlastModel.frontSpeed(0), 1e-9);
        assertEquals(343, BlastModel.frontSpeed(BlastModel.kpa(1e-4)), 0.01);
        assertTrue(BlastModel.frontSpeed(BlastModel.kpa(20)) > 450);
    }

    @Test
    void dynamicPressure() {
        // q at 5 psi ≈ 0.6 psi (Glasstone, table 3.07)
        double q = BlastModel.psi(BlastModel.dynamicPressureKpa(BlastModel.kpa(5)));
        assertEquals(0.58, q, 0.1);
        assertEquals(0, BlastModel.dynamicPressureKpa(0), 0);
    }

    @Test
    void windBehindFront() {
        // highest wind behind the front (Glasstone, table 3.07): 2 psi - 70 mph, 5 psi - 163, 10 psi — 294
        assertEquals(31.3, BlastModel.windSpeed(BlastModel.kpa(2)), 1.5);
        assertEquals(72.9, BlastModel.windSpeed(BlastModel.kpa(5)), 2);
        assertEquals(131.4, BlastModel.windSpeed(BlastModel.kpa(10)), 3);
        assertEquals(0, BlastModel.windSpeed(0), 0);
        // the air behind the front is always slower than the front itself
        for (double psi = 0.1; psi < 200; psi *= 1.5) {
            assertTrue(BlastModel.windSpeed(BlastModel.kpa(psi)) < BlastModel.frontSpeed(BlastModel.kpa(psi)));
        }
    }

    /**
     * KG85 NE at the listener: in free air (W = Y / 1,8 - the formula adds reflection from the ground itself) - values
     *  tables 8.1 at Garcés (2 kt on 8 km - 0,67 s; 2 Mt on 80 km - 6,7 s) and tables 8.6 (1 t on 1 km - 0,0568 s); ANSI
     * S2.20 for 1 kt (for 1, 10, 100 km - 0,34, 0,52, 0,93 s) - within 25 %.
     */
    @ParameterizedTest
    @CsvSource({"2, 8000, 0.67, 0.01", "2000, 80000, 6.7, 0.1", "0.001, 1000, 0.0568, 0.0005",
            "1, 1000, 0.34, 0.085", "1, 10000, 0.52, 0.13", "1, 100000, 0.93, 0.23"})
    void positivePhaseByRange(double freeAirKt, double rangeM, double seconds, double tolerance) {
        assertEquals(seconds, BlastModel.positivePhaseSeconds(rangeM, freeAirKt / 1.8), tolerance);
    }

    /**
     * Phase duration grows with range and levels off: for 15 kt at 3 km ~1.2 s, at 30 km ~1.7 s, farther no more than 2.3.
     * From 200 m (Z ≈ 65 m/kt^⅓): closer in, the KG85 fit dips by 7%; this is inside the fireball, with no sound there.
     */
    @Test
    void positivePhaseLengthensAndLevels() {
        double prev = 0;
        for (double r = 200; r < 1e6; r *= 1.5) {
            double t = BlastModel.positivePhaseSeconds(r, 15), at = r;
            assertTrue(t >= prev * 0.999, () -> at + " m");
            prev = t;
        }
        assertEquals(1.17, BlastModel.positivePhaseSeconds(3000, 15), 0.05);
        assertEquals(1.71, BlastModel.positivePhaseSeconds(30_000, 15), 0.05);
        assertTrue(prev < 0.77 * Math.cbrt(27) * 1.001);
    }

    @Test
    void noSuchPressureGivesZeroRange() {
        assertEquals(0, BlastModel.rangeForSurfaceOverpressure(1e9, 50), 0);
        assertEquals(0, BlastModel.rangeForSurfaceOverpressure(BlastModel.kpa(1), 0), 0);
    }

    /** Glass (0,8 psi) from a ground explosion Shahed (50 kg) flies up to ~73 m, rockets (~150 kg TNT) - up to ~105 m. */
    @Test
    void surfaceRangeForGlass() {
        double kpa = BlastModel.kpa(0.8);
        double drone = BlastModel.rangeForSurfaceOverpressure(kpa, 50);
        assertEquals(73, drone, 2);
        assertEquals(0.8, BlastModel.psi(BlastModel.surfaceOverpressureKpa(drone, 50)), 1e-6);
        assertEquals(105, BlastModel.rangeForSurfaceOverpressure(kpa, 150), 3);
    }
}
