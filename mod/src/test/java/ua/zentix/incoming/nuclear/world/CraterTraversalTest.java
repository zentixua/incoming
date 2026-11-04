package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

class CraterTraversalTest {
    private static double distance(int x, int z, int cx, int cz) {
        return Math.hypot(Math.max(cx * 16, Math.min(x, cx * 16 + 15)) - x,
                Math.max(cz * 16, Math.min(z, cz * 16 + 15)) - z);
    }

    @Test
    void boundedTraversalPreservesEveryLegacyCraterCursorIncludingTies() {
        for (int[] centre : new int[][]{{0, 0}, {-33, 49}, {15, -16}, {300000, -400001}}) {
            int x = centre[0], z = centre[1];
            for (int radius : new int[]{1, 15, 16, 17, 157, 1100}) {
                var old = new ArrayList<Long>();
                int homeX = Math.floorDiv(x, 16), homeZ = Math.floorDiv(z, 16), rc = (radius >> 4) + 1;
                for (int cx = homeX - rc; cx <= homeX + rc; cx++) for (int cz = homeZ - rc; cz <= homeZ + rc; cz++)
                    if (distance(x, z, cx, cz) <= radius) old.add((cx & 0xffffffffL) | (long) cz << 32);
                old.sort(Comparator.comparingDouble(key -> distance(x, z, (int) (long) key, (int) (key >> 32))));
                var scan = new RadialChunks(x, z, -1, radius, 15);
                int previous = 0;
                while (!scan.prepared()) {
                    scan.prepare(4);
                    assertTrue(scan.headers() - previous <= 8, "preparation exceeded its row slice");
                    previous = scan.headers();
                }
                long maximum = scan.bytes();
                for (long key : old) {
                    assertFalse(scan.done(), "missing crater chunk");
                    assertEquals(key, scan.peek(), "legacy restart cursor changed");
                    scan.advance(); maximum = Math.max(maximum, scan.bytes());
                }
                assertTrue(scan.done(), "extra crater chunk");
                assertTrue(maximum <= 128L + (2L * rc + 3) * 2 * 64, "resident traversal grew with area");
            }
        }
    }
}
