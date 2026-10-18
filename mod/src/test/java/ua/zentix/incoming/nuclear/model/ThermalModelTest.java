package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThermalModelTest {
    private static final double V = ThermalModel.VISIBILITY_CLEAR;

    /** 8 кал/см² у 15 кт в воздухе — около 2 км (NUKEMAP по тем же кривым Glasstone: ожоги 3-й степени до 1,9 км). */
    @Test
    void thirdDegreeBurns15kt() {
        double r = ThermalModel.rangeForFluence(ThermalModel.BURN_3, 15, false, V);
        assertTrue(r >= 1900 && r <= 2100, "8 кал/см² на " + r);
        assertEquals(ThermalModel.BURN_3, ThermalModel.fluenceCalPerCm2(r, 15, false, V), 1e-6);
    }

    @Test
    void firstDegreeBurns() {
        assertEquals(3100, ThermalModel.rangeForFluence(ThermalModel.BURN_1, 15, false, V), 3100 * 0.05);
        assertEquals(12600, ThermalModel.rangeForFluence(ThermalModel.BURN_3, 1000, false, V), 12600 * 0.05);
        assertEquals(18800, ThermalModel.rangeForFluence(ThermalModel.BURN_1, 1000, false, V), 18800 * 0.05);
    }

    /** Пропускание по кривым Glasstone 1964, рис. 7.104: видимость 10 миль — 0,765 на 1,6 км и 0,56 на 6,4 км; 50 миль — 0,82 на 3,2 км. */
    @Test
    void transmittanceMatchesGlasstoneCurves() {
        assertEquals(0.765, ThermalModel.transmittance(1600, 16_000), 0.03);
        assertEquals(0.56, ThermalModel.transmittance(6400, 16_000), 0.03);
        assertEquals(0.82, ThermalModel.transmittance(3200, 80_000), 0.03);
        assertEquals(0.635, ThermalModel.transmittance(16_000, 80_000), 0.03);
    }

    @Test
    void groundBurstAndWeatherShorten() {
        double air = ThermalModel.rangeForFluence(ThermalModel.BURN_3, 15, false, V);
        assertTrue(ThermalModel.rangeForFluence(ThermalModel.BURN_3, 15, true, V) < air);
        assertTrue(ThermalModel.rangeForFluence(ThermalModel.BURN_3, 15, false, ThermalModel.VISIBILITY_RAIN) < air);
    }
}
