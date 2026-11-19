package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pyrolysis smoke bound (19.11 complaint: opaque cumulus billows at the crosshair 3–5 km from 1 Mt). The old law gasified
 * 0.7·(q − 5)·41 868 / 1.8 MJ/kg with no limit: 0.33 kg/m² of turf at 25 cal/cm², more than a meadow holds, and 30× more at 1 km.
 */
class FlashPyrolysisTest {
    private static final double PULSE = FireballModel.pulseSeconds(1000);

    @Test
    void noBedGivesMoreThanItHolds() {
        for (FlashPyrolysis.Bed bed : FlashPyrolysis.Bed.values()) {
            double cap = FlashPyrolysis.VOLATILE * FlashPyrolysis.load(bed, PULSE), last = 0;
            for (double q = 0; q < 2000; q *= 1.1, q += 0.1) {
                double v = FlashPyrolysis.volatiles(bed, q, PULSE);
                assertTrue(v <= cap + 1e-12, bed + " at " + q + ": " + v + " > " + cap);
                assertTrue(v >= last - 1e-12, bed + " grows with light");
                last = v;
            }
            assertEquals(cap, FlashPyrolysis.volatiles(bed, 2000, PULSE), 1e-12, bed + " saturates at its load");
        }
        // Artem's 19.11 case: top faces 3 km from 1 Mt got ~25 cal/cm²
        double old = 0.7 * (25 - 5) * FlashPyrolysis.CAL_CM2 / FlashPyrolysis.GASIFY;
        assertEquals(0.32, old, 0.01);
        assertTrue(FlashPyrolysis.volatiles(FlashPyrolysis.Bed.GREEN_GRASS, 25, PULSE) < 0.4 * old, "green turf at 25 cal/cm²");
    }

    /** Water goes first (Rothermel's heat of preignition): green turf must be dried before it smokes, cured grass barely. */
    @Test
    void moistureDelaysGreenTurf() {
        assertEquals(2.596e6, FlashPyrolysis.DRYING, 2e3);
        double dry = FlashPyrolysis.Bed.GREEN_GRASS.moisture * FlashPyrolysis.load(FlashPyrolysis.Bed.GREEN_GRASS, PULSE) * FlashPyrolysis.DRYING
                / (FlashPyrolysis.ABSORB * FlashPyrolysis.CAL_CM2);
        assertEquals(17.6, dry, 0.2, "cal/cm² to dry green turf");
        assertEquals(0, FlashPyrolysis.volatiles(FlashPyrolysis.Bed.GREEN_GRASS, dry - 0.1, PULSE), 0);
        assertTrue(FlashPyrolysis.volatiles(FlashPyrolysis.Bed.CURED_GRASS, 5, PULSE) > 0);
        for (double q = 1; q < 100; q += 1) {
            assertTrue(FlashPyrolysis.volatiles(FlashPyrolysis.Bed.CURED_GRASS, q, PULSE) >= FlashPyrolysis.volatiles(FlashPyrolysis.Bed.GREEN_GRASS, q, PULSE));
        }
    }

    /** Wood gives only the layer the pulse heats: √(a·t) — 0.7 mm in 3 s. */
    @Test
    void woodGivesItsHeatedLayer() {
        assertEquals(0.71e-3 * 500, FlashPyrolysis.woodLoad(3), 0.01);
        assertTrue(FlashPyrolysis.woodLoad(FireballModel.pulseSeconds(1000)) > FlashPyrolysis.woodLoad(FireballModel.pulseSeconds(15)));
    }
}
