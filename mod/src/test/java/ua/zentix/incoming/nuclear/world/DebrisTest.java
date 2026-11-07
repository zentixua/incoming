package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlastModel;

import static org.junit.jupiter.api.Assertions.*;

class DebrisTest {
    /**
     * 5 psi wind ≈ 72 m/s: masonry from a 10 m storey lands a few metres downwind (15 kt phase 0.7 s), wood farther;
     * 20 psi with a megaton's 2.5 s phase carries it out of the chunk ring; 1 psi barely moves it.
     */
    @Test
    void driftGrowsWithWindHeightAndLightness() {
        double u5 = BlastModel.windSpeed(BlastModel.kpa(5)), u20 = BlastModel.windSpeed(BlastModel.kpa(20)), u1 = BlastModel.windSpeed(BlastModel.kpa(1));
        double low = Debris.driftMetres(0, u5, 0.7, Debris.MASONRY), storey = Debris.driftMetres(10, u5, 0.7, Debris.MASONRY);
        assertTrue(low > 0 && low < storey, low + " vs " + storey);
        assertTrue(storey > 2 && storey < Debris.REACH, "10 m masonry at 5 psi drifts " + storey + " m");
        assertTrue(Debris.driftMetres(10, u5, 0.7, Debris.WOOD) > 2 * storey, "wood drifts farther");
        assertTrue(Debris.driftMetres(10, u20, 2.5, Debris.MASONRY) > Debris.REACH, "20 psi exports");
        assertTrue(Debris.driftMetres(10, u1, 2.5, Debris.MASONRY) < 1, "1 psi barely moves masonry");
        assertEquals(0, Debris.driftMetres(10, 0, 2, Debris.MASONRY));
    }

    /** The fragment never outruns the peak wind: drift at most peak wind × fall time. */
    @Test
    void driftNeverExceedsWindRun() {
        for (double u = 10; u < 600; u *= 1.7) {
            for (double h = 0; h < 200; h += 13) {
                double fall = Math.sqrt(2 * h / 9.81) + Debris.SKID_S;
                assertTrue(Debris.driftMetres(h, u, 10, Debris.WOOD) <= u * fall + 1e-9);
            }
        }
    }

    /** Swept near the epicentre, balanced far out. */
    @Test
    void importShareFollowsRingGeometry() {
        assertEquals(0, Debris.importFraction(50, 80));
        assertEquals(0.5, Debris.importFraction(100, 50), 1e-12);
        assertTrue(Debris.importFraction(3000, 10) > 0.99);
        assertTrue(Debris.spread(0) >= 0.5 && Debris.spread(1000) == Debris.SPREAD_MAX);
    }

    /**
     * Thirty chests from a block of flats, one per floor at the same spot, land on one column of flat ground: they rest
     * as a low heap (no step over one block between neighboring columns), none is lost, and the heap holds at a chunk
     * edge without leaving the chunk. Ground-less columns take nothing new.
     */
    @Test
    void landedMaterialRestsAsAHeapNotATower() {
        int[] ground = new int[256];
        java.util.Arrays.fill(ground, 64);
        ground[7 << 4 | 6] = Integer.MIN_VALUE;
        int at = 7 << 4 | 7, edge = 0;
        var landed = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (int k = 0; k < 30; k++) landed.add((long) at << 40 | (long) k << 16);
        for (int k = 0; k < 10; k++) landed.add((long) edge << 40 | (long) k << 16);
        landed.sort(null);
        int[] from = new int[257];
        int[] order = Debris.repose(ground, landed, from);
        assertEquals(landed.size(), order.length);
        assertEquals(landed.size(), from[256]);
        int[] top = ground.clone();
        boolean[] seen = new boolean[order.length];
        for (int c = 0; c < 256; c++) {
            for (int i = from[c]; i < from[c + 1]; i++) {
                assertFalse(seen[order[i]]);
                seen[order[i]] = true;
                assertNotEquals(Integer.MIN_VALUE, ground[c], "material rolled into a column without ground");
                top[c]++;
            }
        }
        int highest = 0;
        for (int c = 0; c < 256; c++) {
            if (top[c] == Integer.MIN_VALUE) continue;
            highest = Math.max(highest, top[c] - 64);
            int lx = c & 15, lz = c >> 4;
            if (lx < 15 && top[c + 1] != Integer.MIN_VALUE) assertTrue(Math.abs(top[c] - top[c + 1]) <= 1, "step at " + lx + "," + lz);
            if (lz < 15 && top[c + 16] != Integer.MIN_VALUE) assertTrue(Math.abs(top[c] - top[c + 16]) <= 1, "step at " + lx + "," + lz);
        }
        assertTrue(highest <= 4, "a 30-chest tower should rest as a heap, highest " + highest);
        // the same input rests the same way (any thread, any time)
        int[] again = Debris.repose(ground, landed, new int[257]);
        assertArrayEquals(order, again);
    }
}
