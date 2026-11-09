package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThermalModelTest {
    private static final double V = ThermalModel.VISIBILITY_CLEAR;

    /** 8 cal/cm² at 15 kt in the air - about 2 km (NUKEMAP according to the same curves Glasstone: burns 3 degree up to 1,9 km). */
    @Test
    void thirdDegreeBurns15kt() {
        double r = ThermalModel.rangeForFluence(ThermalModel.BURN_3, 15, false, V);
        assertTrue(r >= 1900 && r <= 2100, "8 cal/cm² per " + r);
        assertEquals(ThermalModel.BURN_3, ThermalModel.fluenceCalPerCm2(r, 15, false, V), 1e-6);
    }

    @Test
    void firstDegreeBurns() {
        assertEquals(3100, ThermalModel.rangeForFluence(ThermalModel.BURN_1, 15, false, V), 3100 * 0.05);
        assertEquals(12600, ThermalModel.rangeForFluence(ThermalModel.BURN_3, 1000, false, V), 12600 * 0.05);
        assertEquals(18800, ThermalModel.rangeForFluence(ThermalModel.BURN_1, 1000, false, V), 18800 * 0.05);
    }

    /** Transmission along curves Glasstone 1964, fig. 7.104: visibility 10 miles - 0,765 at 1,6 km and 0,56 at 6,4 km; 50 miles - 0,82 at 3,2 km. */
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
    /**
     * Ignition threshold grows with yield (Glasstone &amp; Dolan 1977, tables 7.35 and 7.40): foliage 6.0 cal/cm² at 1 Mt,
     * thin-fabric scaling below. Comment values in {@link ThermalModel#ignite}.
     */
    @Test
    void ignitionThresholdGrowsWithYield() {
        assertEquals(3.63, ThermalModel.ignite(15), 0.02);
        assertEquals(4.30, ThermalModel.ignite(50), 0.02);
        assertEquals(5.59, ThermalModel.ignite(500), 0.02);
        assertEquals(PulseIgnition.Fuel.LEAVES.threshold, ThermalModel.ignite(1000), 1e-9);
        for (double y = 1; y < 20_000; y *= 1.3) assertTrue(ThermalModel.ignite(y * 1.3) >= ThermalModel.ignite(y), "monotonic at " + y);
        assertEquals(4 * ThermalModel.ignite(500), ThermalModel.igniteAll(500), 1e-9);
    }

    /**
     * Artem's 09.11.2026 bursts (surface, clear air, distances from Flashback replays): nothing ignites there, since exposure
     * facing the fireball is below the threshold (the mod lit fires there with a ×2.5 slope gain and a fixed 5 cal/cm²).
     */
    @Test
    void noIgnitionAtTheDistancesOf0911() {
        assertTrue(ThermalModel.fluenceCalPerCm2(3791, 50, true, V) < ThermalModel.ignite(50));
        for (double r : new double[] {12_059, 12_705, 14_302}) {
            assertTrue(ThermalModel.fluenceCalPerCm2(r, 500, true, V) < ThermalModel.ignite(500), "500 kt at " + r);
        }
        // the same burst ignites at 3.8 km
        assertTrue(ThermalModel.fluenceCalPerCm2(3784, 500, true, V) > ThermalModel.igniteAll(500));
    }
}
