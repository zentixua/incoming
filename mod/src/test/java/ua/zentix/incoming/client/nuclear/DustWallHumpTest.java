package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The dust wall's hump at the front ({@link BlastWall#hump}). */
class DustWallHumpTest {
    /**
     * The hump changes the head's shape, not its matter: top × shape (the column's height integral) is the law's at every wedge fraction; the
     * top stands at most {@link BlastWall#HUMP} above the law's, peaking near a third of the wedge, and is the law's again by 1.2 wedges.
     */
    @Test
    void humpKeepsTheColumnAndSinksBack() {
        double peak = 0, peakAt = 0;
        for (double u = 0.001; u <= 1.6; u += 0.001) {
            double law = Math.pow(Math.min(u, 1), 5 / 6.0), lawShape = DustModel(u) / Math.pow(Math.max(Math.min(u, 1), 1e-4), 5 / 6.0);
            assertEquals(law * lawShape, BlastWall.top(u) * BlastWall.shape(u), 1e-9 * (1 + law * lawShape), "column at u " + u);
            double ratio = BlastWall.top(u) / law;
            assertTrue(ratio >= 1 - BlastWall.CORE_SINK - 1e-12 && ratio <= 1 + BlastWall.HUMP + 1e-12);
            if (ratio > peak) {
                peak = ratio;
                peakAt = u;
            }
            if (u >= 1.2 && u <= 1.6) assertEquals(1 - BlastWall.CORE_SINK * BlastWall.smooth(0.5, 3, u), ratio, 1e-12, "past the hump only the core's settle");
        }
        assertTrue(peak > 1.25 && peak <= 1 + BlastWall.HUMP + 1e-9, "hump " + peak);
        assertEquals(0.3, peakAt, 0.05);
        assertEquals(1 - BlastWall.CORE_SINK, BlastWall.hump(5), 1e-9, "the heavy core has settled to half");
        assertEquals(1, BlastWall.hump(60), 1e-9, "back to the law once the layer has mixed");
    }

    private static double DustModel(double u) {
        return ua.zentix.incoming.nuclear.model.DustModel.lifted(Math.min(u, 1));
    }
}
