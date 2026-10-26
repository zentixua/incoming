package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FireWatchTest {
    /** A burst beyond the render-distance disc needs no scan: previously the client visited every chunk in the disc each tick (review #482). */
    @Test
    void farBurstHasNoFrame() {
        assertNull(FireWatch.box(new double[]{5000}, new double[]{0}, new double[]{600}, 0, 0, 32));
    }

    /** The bounds are the burst disc in chunks, clipped by the render-distance square, and contain every chunk touching the disc. */
    @Test
    void frameHoldsDiscWithinSquare() {
        double[] bx = {100}, bz = {-40}, reach = {300};
        FireWatch.Box box = FireWatch.box(bx, bz, reach, 0, 0, 10);
        assertEquals(new FireWatch.Box(-10, -10, 10, 10), box);
        FireWatch.Box wide = FireWatch.box(bx, bz, reach, 0, 0, 64);
        assertEquals(new FireWatch.Box(Math.floorDiv(-200, 16), Math.floorDiv(-340, 16), Math.floorDiv(400, 16), Math.floorDiv(260, 16)), wide);
        for (int x = -64; x <= 64; x++) {
            for (int z = -64; z <= 64; z++) {
                if (FireWatch.nuclear(bx, bz, reach, x, z)) assertTrue(wide.has(x, z), x + "," + z);
            }
        }
    }

    /** Two bursts share one bounding box; a chunk between them, outside both discs, is skipped. */
    @Test
    void twoBurstsShareFrame() {
        double[] bx = {-800, 800}, bz = {0, 0}, reach = {100, 100};
        FireWatch.Box box = FireWatch.box(bx, bz, reach, 0, 0, 64);
        assertNotNull(box);
        assertTrue(box.has(-55, 0) && box.has(56, 0));
        assertFalse(FireWatch.nuclear(bx, bz, reach, 0, 0));
        assertTrue(FireWatch.nuclear(bx, bz, reach, 50, 0));
    }
}
