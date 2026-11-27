package ua.zentix.incoming.nuclear.material;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.Arrays;

/** Worker-owned reducer. Each cell closes once, in order of its first ground contact with the game front. */
final class MaterialFrontier {
    static final int LIVE = 1 << 16;
    private static final int INDEX_BITS = 24;
    private final DustGrid grid;
    private final long[] order, counts;
    private final int[] x, z;
    private final double[] distance;
    private double through = -1;
    private final byte[] cells;
    private final Long2IntOpenHashMap chunks = new Long2IntOpenHashMap();
    private final Sites known = new Sites();
    private final int seed;
    private int next;
    private long own;

    MaterialFrontier(Detonation d, int seed) {
        this.seed = seed;
        grid = new DustGrid(DustGrid.Bounds.of(d));
        int n = grid.count();
        x = new int[n];
        z = new int[n];
        order = new long[n];
        distance = new double[n];
        counts = new long[n];
        cells = new byte[(n + 3) / 4];
        var front = d.front();
        double height = d.burst().y - d.groundY();
        grid.visit((i, cx, cz, size) -> {
            x[i] = cx;
            z[i] = cz;
            distance[i] = grid.nearSquared(cx, cz, size);
            order[i] = (long) DustGrid.deadline(front, height, distance[i]) << INDEX_BITS | i;
        });
        Arrays.sort(order);
    }

    void observe(Long2IntOpenHashMap observations) {
        for (var entry : observations.long2IntEntrySet()) {
            long key = entry.getLongKey();
            int value = entry.getIntValue(), before = chunks.get(key);
            if (before != 0 && ((value & LIVE) == 0 || before == value)) continue;
            int i = grid.index(ChunkPos.getX(key) * 16 + 8, ChunkPos.getZ(key) * 16 + 8);
            if (i < 0) continue;
            chunks.put(key, value);
            for (int k = 0; k < 4; k++) {
                int diff = ((value >>> (k * 4)) & 15) - ((before >>> (k * 4)) & 15);
                counts[i] += (long) diff << (k * 16);
            }
        }
    }

    private int index(int rank) { return (int) (order[rank] & ((1 << INDEX_BITS) - 1)); }

    void closeThrough(long relativeTick) {
        while (next < order.length) {
            long due = order[next] >> INDEX_BITS;
            if (due > relativeTick) break;
            int end = next;
            // Cells due on the same tick see each other's measured centres, independently of iteration order.
            while (end < order.length && (order[end] >> INDEX_BITS) == due) {
                int i = index(end++);
                if (counts[i] != 0) {
                    known.add(new MaterialMap.Site(x[i], z[i], MaterialMap.winner(counts[i])));
                    own++;
                }
            }
            while (next < end) {
                int i = index(next++);
                through = Math.max(through, distance[i]);
                int value = counts[i] == 0 ? known.vote(x[i], z[i], seed) : MaterialMap.winner(counts[i]);
                cells[i / 4] |= (byte) (value << (2 * (i % 4)));
            }
        }
    }

    MaterialMap snapshot(long tick) {
        return new MaterialMap(grid, cells.clone(), seed == DustMaterial.WATER.ordinal(), tick, next == order.length ? Double.POSITIVE_INFINITY : through);
    }
    long own() { return own; }

    /** A logarithmic forest of balanced k-d trees. Previously committed sites keep their class. */
    private static final class Sites {
        private record Block(MaterialMap.Site[] sites, MaterialMap.Tree tree) {}
        private final Block[] levels = new Block[25];
        void add(MaterialMap.Site p) {
            var sites = new MaterialMap.Site[]{p};
            int n = 0;
            while (levels[n] != null) {
                var other = levels[n].sites;
                levels[n++] = null;
                int len = sites.length;
                sites = Arrays.copyOf(sites, len * 2);
                System.arraycopy(other, 0, sites, len, len);
            }
            levels[n] = new Block(sites, MaterialMap.Tree.build(sites, 0, sites.length, true));
        }
        /** The weighted vote of the measured centres nearest (x, z) ({@link MaterialMap.Nearest}); the seed while none is measured. */
        int vote(int x, int z, int seed) {
            var q = new MaterialMap.Nearest(x, z);
            for (Block b : levels) if (b != null) b.tree.nearest(q);
            return q.n == 0 ? seed : q.material();
        }
    }
}
