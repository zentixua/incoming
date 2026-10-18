package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastModelTest {
    @Test
    void frontSlowsToSoundSpeed() {
        assertEquals(343, BlastModel.frontSpeed(0), 1e-9);
        assertEquals(343, BlastModel.frontSpeed(BlastModel.kpa(1e-4)), 0.01);
        assertTrue(BlastModel.frontSpeed(BlastModel.kpa(20)) > 450);
    }

    @Test
    void dynamicPressure() {
        // q при 5 psi ≈ 0.6 psi (Glasstone, табл. 3.07)
        double q = BlastModel.psi(BlastModel.dynamicPressureKpa(BlastModel.kpa(5)));
        assertEquals(0.58, q, 0.1);
        assertEquals(0, BlastModel.dynamicPressureKpa(0), 0);
    }

    @Test
    void windBehindFront() {
        // наибольший ветер за фронтом (Glasstone, табл. 3.07): 2 psi — 70 миль/ч, 5 psi — 163, 10 psi — 294
        assertEquals(31.3, BlastModel.windSpeed(BlastModel.kpa(2)), 1.5);
        assertEquals(72.9, BlastModel.windSpeed(BlastModel.kpa(5)), 2);
        assertEquals(131.4, BlastModel.windSpeed(BlastModel.kpa(10)), 3);
        assertEquals(0, BlastModel.windSpeed(0), 0);
        // воздух за фронтом всегда медленнее самого фронта
        for (double psi = 0.1; psi < 200; psi *= 1.5) {
            assertTrue(BlastModel.windSpeed(BlastModel.kpa(psi)) < BlastModel.frontSpeed(BlastModel.kpa(psi)));
        }
    }

    @Test
    void noSuchPressureGivesZeroRange() {
        assertEquals(0, BlastModel.rangeForSurfaceOverpressure(1e9, 50), 0);
        assertEquals(0, BlastModel.rangeForSurfaceOverpressure(BlastModel.kpa(1), 0), 0);
    }

    /** Стекло (0,8 psi) у наземного взрыва шахеда (50 кг) вылетает до ~73 м, ракеты (450 кг) — до ~150 м. */
    @Test
    void surfaceRangeForGlass() {
        double kpa = BlastModel.kpa(0.8);
        double drone = BlastModel.rangeForSurfaceOverpressure(kpa, 50);
        assertEquals(73, drone, 2);
        assertEquals(0.8, BlastModel.psi(BlastModel.surfaceOverpressureKpa(drone, 50)), 1e-6);
        assertEquals(152, BlastModel.rangeForSurfaceOverpressure(kpa, 450), 3);
    }
}
