package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    /**
     * KG85 NE у слушателя: в свободном воздухе (W = Y / 1,8 — отражение от земли формула добавляет сама) — значения
     * таблицы 8.1 у Garcés (2 кт на 8 км — 0,67 с; 2 Мт на 80 км — 6,7 с) и таблицы 8.6 (1 т на 1 км — 0,0568 с); ANSI
     * S2.20 для 1 кт (на 1, 10, 100 км — 0,34, 0,52, 0,93 с) — в пределах 25 %.
     */
    @ParameterizedTest
    @CsvSource({"2, 8000, 0.67, 0.01", "2000, 80000, 6.7, 0.1", "0.001, 1000, 0.0568, 0.0005",
            "1, 1000, 0.34, 0.085", "1, 10000, 0.52, 0.13", "1, 100000, 0.93, 0.23"})
    void positivePhaseByRange(double freeAirKt, double rangeM, double seconds, double tolerance) {
        assertEquals(seconds, BlastModel.positivePhaseSeconds(rangeM, freeAirKt / 1.8), tolerance);
    }

    /**
     * Фаза растёт с дальностью и выходит на постоянную: у 15 кт на 3 км ~1,2 с, на 30 км ~1,7 с, дальше — не больше 2,3.
     * С 200 м (Z ≈ 65 м/кт^⅓): ближе подгонка KG85 проседает на 7 % — это внутри огненного шара, там звука нет.
     */
    @Test
    void positivePhaseLengthensAndLevels() {
        double prev = 0;
        for (double r = 200; r < 1e6; r *= 1.5) {
            double t = BlastModel.positivePhaseSeconds(r, 15), at = r;
            assertTrue(t >= prev * 0.999, () -> at + " м");
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

    /** Стекло (0,8 psi) у наземного взрыва шахеда (50 кг) вылетает до ~73 м, ракеты (~150 кг ТНТ) — до ~105 м. */
    @Test
    void surfaceRangeForGlass() {
        double kpa = BlastModel.kpa(0.8);
        double drone = BlastModel.rangeForSurfaceOverpressure(kpa, 50);
        assertEquals(73, drone, 2);
        assertEquals(0.8, BlastModel.psi(BlastModel.surfaceOverpressureKpa(drone, 50)), 1e-6);
        assertEquals(105, BlastModel.rangeForSurfaceOverpressure(kpa, 150), 3);
    }
}
