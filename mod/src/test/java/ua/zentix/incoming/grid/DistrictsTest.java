package ua.zentix.incoming.grid;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistrictsTest {
    private static final int AREA = 200;

    /** The chunk belongs to the block with the nearest center - among all, not just neighboring cells. */
    @Test
    void nearestSeedWins() {
        for (int x = -AREA; x < AREA; x += 3) {
            for (int z = -AREA; z < AREA; z += 3) {
                double px = x * 16 + 8, pz = z * 16 + 8;
                int cx = Math.floorDiv(x, Districts.CELL), cz = Math.floorDiv(z, Districts.CELL);
                double best = Double.MAX_VALUE;
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        long d = Districts.key(cx + dx, cz + dz);
                        best = Math.min(best, Math.hypot(Districts.seedX(d) - px, Districts.seedZ(d) - pz));
                    }
                }
                long got = Districts.of(x, z);
                double mine = Math.hypot(Districts.seedX(got) - px, Districts.seedZ(got) - pz);
                assertEquals(best, mine, 1e-9, "chunk " + x + " " + z);
            }
        }
    }

    /** Quarters - about 80 blocks (25 chunks), without tiny and huge ones. */
    @Test
    void districtsAreCityBlockSized() {
        Long2IntOpenHashMap size = new Long2IntOpenHashMap();
        for (int x = -AREA; x < AREA; x++) {
            for (int z = -AREA; z < AREA; z++) size.addTo(Districts.of(x, z), 1);
        }
        int min = Integer.MAX_VALUE, max = 0;
        long sum = 0, n = 0;
        for (var e : size.long2IntEntrySet()) {
            // the block at the edge of the area is cut off
            int cx = Districts.cellX(e.getLongKey()), cz = Districts.cellZ(e.getLongKey());
            if (Math.abs(cx) * Districts.CELL > AREA - 12 || Math.abs(cz) * Districts.CELL > AREA - 12) continue;
            min = Math.min(min, e.getIntValue());
            max = Math.max(max, e.getIntValue());
            sum += e.getIntValue();
            n++;
        }
        assertEquals(25, sum / (double) n, 1, "middle quarter, chunks");
        assertTrue(min >= 6, "smallest quarter: " + min);
        assertTrue(max <= 60, "largest quarter: " + max);
    }

    /**
     * The quarter is one whole: you can go from any of its chunks to any of them without leaving the quarter (with steps and diagonally:
     *  the border of the crater cell at the centers of the chunks goes obliquely and in some places leaves a junction at the corners).
     */
    @Test
    void districtsAreConnected() {
        LongOpenHashSet seen = new LongOpenHashSet();
        for (int x = -60; x < 60; x++) {
            for (int z = -60; z < 60; z++) {
                long d = Districts.of(x, z);
                if (!seen.add(d)) continue;
                // all the chunks of the block in the vicinity and how many of them are reachable from this
                int total = 0;
                LongOpenHashSet members = new LongOpenHashSet();
                for (int a = x - 12; a <= x + 12; a++) {
                    for (int b = z - 12; b <= z + 12; b++) {
                        if (Districts.of(a, b) == d) {
                            members.add(pack(a, b));
                            total++;
                        }
                    }
                }
                LongOpenHashSet reached = new LongOpenHashSet();
                ArrayDeque<long[]> queue = new ArrayDeque<>();
                queue.add(new long[]{x, z});
                reached.add(pack(x, z));
                while (!queue.isEmpty()) {
                    long[] c = queue.poll();
                    for (int[] s : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                        long k = pack((int) c[0] + s[0], (int) c[1] + s[1]);
                        if (members.contains(k) && reached.add(k)) queue.add(new long[]{c[0] + s[0], c[1] + s[1]});
                    }
                }
                assertEquals(total, reached.size(), "block with chunk " + x + " " + z + " torn");
            }
        }
    }

    private static long pack(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    @Test
    void unitIsStableAndUniform() {
        double sum = 0;
        int n = 0;
        for (int x = -50; x < 50; x++) {
            for (int z = -50; z < 50; z++) {
                double u = Districts.unit(Districts.key(x, z), 3);
                assertTrue(u >= 0 && u < 1);
                assertEquals(u, Districts.unit(Districts.key(x, z), 3));
                sum += u;
                n++;
            }
        }
        assertEquals(0.5, sum / n, 0.02);
    }
}
