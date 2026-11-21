package ua.zentix.incoming.client.nuclear;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The young wall's own look model (presentation, LOOK.md 21.11): birth, hump, settling; tier cross-fade. */
class WallPuffsTest {
    @Test
    void dustIsBornLowAtTheContactLineThenHumpsAndSettles() {
        for (double hump : new double[]{WallPuffs.HUMP - WallPuffs.HUMP_SPREAD, WallPuffs.HUMP + WallPuffs.HUMP_SPREAD}) {
            assertTrue(WallPuffs.top(0.05, 1, hump) < 0.1, "torn up low at the front");
            assertTrue(WallPuffs.top(0.15, 1, hump) < 0.5, "still under half the layer at 0.15 s");
            double peak = 0;
            for (double a = 0; a < 2; a += 0.005) peak = Math.max(peak, WallPuffs.top(a, 1, hump));
            assertTrue(peak >= 1.3 && peak <= 1.5, "hump 1.3-1.5x the layer: " + peak);
            assertTrue(WallPuffs.top(1.0, 1, hump) <= 1.05, "settled by about 1 s");
        }
    }

    @Test
    void neighbourTiersShareMassAndOnlyNearTheirBoundary() {
        for (double x = -0.5; x <= 0.5; x += 0.01) {
            // tier k at x (its own octave) and tier k+1 at x - 1
            double sum = WallPuffs.tierWeight(x, false, false) + WallPuffs.tierWeight(x - 1, false, false)
                    + WallPuffs.tierWeight(x + 1, false, false);
            assertEquals(1, sum, 1e-9);
        }
        // a place is drawn by two tiers only within the narrow blend band: count stays about one billow per place
        assertEquals(0, WallPuffs.tierWeight(0.5 + WallPuffs.BLEND + 1e-6, false, false), 1e-9);
        assertEquals(1, WallPuffs.tierWeight(0.5 - WallPuffs.BLEND - 1e-6, false, false), 1e-9);
    }
}
