package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The dust wall's hump at the front ({@link BlastWall#hump}). */
class DustWallHumpTest {
    /**
     * The hump changes the head's shape, not its matter: top × shape (the column's height integral) is the law's at every wedge fraction; the
     * hump's peak (near a third of the wedge) is {@link BlastWall#PEAK_SHARE} of the law's top and the young layer under it 1 / (1 + {@link BlastWall#HUMP})
     * of that (Artem 21.11: denser and much lower — at the 4.5 km ring of 1 Mt a ~62 m layer under a ~85 m peak, against the law's 137 m); past
     * the hump only the core's settle on that scale; the law's again once the layer has mixed.
     */
    @Test
    void humpKeepsTheColumnAndSinksBack() {
        double peak = 0, peakAt = 0;
        for (double u = 0.001; u <= 1.6; u += 0.001) {
            double law = Math.pow(Math.min(u, 1), 5 / 6.0), lawShape = DustModel(u) / Math.pow(Math.max(Math.min(u, 1), 1e-4), 5 / 6.0);
            assertEquals(law * lawShape, BlastWall.top(u) * BlastWall.shape(u), 1e-9 * (1 + law * lawShape), "column at u " + u);
            double ratio = BlastWall.top(u) / law;
            double young = BlastWall.PEAK_SHARE / (1 + BlastWall.HUMP);
            assertTrue(ratio >= (1 - BlastWall.CORE_SINK) * young - 1e-12 && ratio <= BlastWall.PEAK_SHARE + 1e-12, "the top within the young dust's range: " + ratio);
            if (ratio > peak) {
                peak = ratio;
                peakAt = u;
            }
            if (u >= 1.2 && u <= 1.6) assertEquals((1 - BlastWall.CORE_SINK * BlastWall.smooth(0.5, 3, u)) * young, ratio, 1e-12, "past the hump only the core's settle");
        }
        assertEquals(BlastWall.PEAK_SHARE, peak, 0.03 * BlastWall.PEAK_SHARE, "the hump's peak is PEAK_SHARE of the law's top");
        assertEquals(0.3, peakAt, 0.05);
        assertEquals((1 - BlastWall.CORE_SINK) * BlastWall.PEAK_SHARE / (1 + BlastWall.HUMP), BlastWall.hump(5), 1e-9, "the heavy core has settled to half of the young layer");
        assertEquals(1, BlastWall.hump(60), 1e-9, "back to the law once the layer has mixed");
    }

    private static double DustModel(double u) {
        return ua.zentix.incoming.nuclear.model.DustModel.lifted(Math.min(u, 1));
    }
}
