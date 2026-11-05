package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class RadialChunksTest {
    @Test
    void incrementalTraversalMatchesWholeDiskWithoutSortingItsArea() {
        for (double x : new double[]{0, -7.5, 16}) for (double inner : new double[]{-1, 0, 81, 299}) {
            double z = 23.25, outer = 300;
            RadialChunks cursor = new RadialChunks(x, z, inner, outer);
            assertFalse(cursor.prepare(1));
            while (!cursor.prepare(3)) assertTrue(cursor.headers() < 100);
            var expected = new HashSet<Long>();
            for (int cx = -22; cx <= 22; cx++) for (int cz = -22; cz <= 22; cz++) {
                double distance = Math.hypot(gap(x, cx), gap(z, cz));
                if (distance > inner && distance <= outer) expected.add((cx & 0xffffffffL) | (long) cz << 32);
            }
            double previous = -1;
            while (!cursor.done()) {
                long key = cursor.peek();
                assertTrue(expected.remove(key), "duplicate or outside ring");
                double distance = Math.hypot(gap(x, (int) key), gap(z, (int) (key >> 32)));
                assertTrue(distance >= previous);
                previous = distance;
                cursor.advance();
            }
            assertTrue(expected.isEmpty(), "every real-scale chunk is retained");
        }
    }

    @Test
    void megatonTraceKeepsOnlyRowHeads() {
        RadialChunks cursor = new RadialChunks(2897, 394, 6509, 38000);
        int calls = 0;
        while (!cursor.prepare(64)) calls++;
        assertTrue(calls > 50, "yields between bounded preparation units");
        assertTrue(cursor.bytes() < (1 << 20), "18 million chunks need no area-sized array");
        for (int i = 0; i < 10000; i++) { cursor.peek(); cursor.advance(); }
        assertTrue(cursor.bytes() < (1 << 20));
    }

    private static double gap(double x, int chunk) {
        return Math.max(0, Math.max(chunk * 16.0 - x, x - (chunk * 16.0 + 16)));
    }
}
