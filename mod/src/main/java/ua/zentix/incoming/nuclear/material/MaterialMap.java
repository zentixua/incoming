package ua.zentix.incoming.nuclear.material;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.world.level.ChunkPos;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/** A birth revision. Its packed cells, wire encoding and disk encoding are independent of sampling order. */
public final class MaterialMap {
    public static final int VERSION = 1;
    private static final int HEADER = 27;
    private final DustGrid grid;
    private final byte[] cells;
    private final boolean wet;
    private final double through;
    private final long tick;

    private MaterialMap(DustGrid grid, byte[] cells, boolean wet, long tick) {
        this(grid, cells, wet, tick, Double.POSITIVE_INFINITY);
    }
    MaterialMap(DustGrid grid, byte[] cells, boolean wet, long tick, double through) {
        this.through = through;
        this.grid = grid;
        this.cells = cells;
        this.wet = wet;
        this.tick = tick;
    }

    public @org.jetbrains.annotations.Nullable DustMaterial material(int x, int z) {
        int i = grid.index(x, z);
        return i < 0 || !grid.covered(x, z, through) ? null : DustMaterial.of(cells[i / 4] >>> (2 * (i % 4)) & 3);
    }
    static MaterialMap uniform(DustGrid.Bounds bounds, int seed) {
        var grid = new DustGrid(bounds);
        var cells = new byte[(grid.count() + 3) / 4];
        Arrays.fill(cells, (byte) (seed * 0x55));
        return new MaterialMap(grid, cells, seed == DustMaterial.WATER.ordinal(), 0);
    }
    public boolean contains(int x, int z) { return grid.index(x, z) >= 0; }
    public boolean committed(int x, int z) { return grid.covered(x, z, through); }
    public boolean complete() { return through == Double.POSITIVE_INFINITY; }
    public boolean wet() { return wet; }
    public long tick() { return tick; }
    public DustGrid.Bounds bounds() { return grid.bounds; }

    /** Four 4-bit counts per sampled chunk. Aggregation is by world cell, with one vote per sampled column. */
    public static MaterialMap build(DustGrid.Bounds bounds, Long2IntMap chunks, int epicenter, long tick) {
        DustGrid grid = new DustGrid(bounds);
        long[] counts = new long[grid.count()];
        for (var entry : chunks.long2IntEntrySet()) {
            int x = ChunkPos.getX(entry.getLongKey()) * 16 + 8, z = ChunkPos.getZ(entry.getLongKey()) * 16 + 8;
            int index = grid.index(x, z);
            if (index < 0) continue;
            int votes = entry.getIntValue();
            for (int k = 0; k < 4; k++) counts[index] += (long) (votes >>> (k * 4) & 15) << (k * 16);
        }
        var sites = new ArrayList<Site>();
        grid.visit((i, x, z, size) -> {
            if (counts[i] != 0) sites.add(new Site(x, z, winner(counts[i])));
        });
        if (sites.isEmpty()) sites.add(new Site(bounds.x(), bounds.z(), epicenter >= 0 ? epicenter : DustMaterial.SOIL.ordinal()));
        Site[] measured = sites.toArray(Site[]::new);
        Tree tree = Tree.build(measured, 0, measured.length, true);
        byte[] cells = new byte[(counts.length + 3) / 4];
        var vote = new Nearest(0, 0);
        grid.visit((i, x, z, size) -> {
            int value;
            if (counts[i] != 0) value = winner(counts[i]);
            else {
                tree.nearest(vote.at(x, z));
                value = vote.material();
            }
            cells[i / 4] |= (byte) (value << (2 * (i % 4)));
        });
        int origin = grid.index(bounds.x(), bounds.z());
        int under = epicenter >= 0 ? epicenter : (cells[origin / 4] >>> (2 * (origin % 4)) & 3);
        return new MaterialMap(grid, cells, under == DustMaterial.WATER.ordinal(), tick);
    }

    static int winner(long counts) {
        int best = 0, most = -1;
        for (int k = 0; k < 4; k++) {
            int n = (int) (counts >>> (k * 16)) & 65535;
            if (n > most) { best = k; most = n; }
        }
        return best;
    }
    record Site(int x, int z, int material) {
        long distance(int bx, int bz) {
            long dx = (long) bx - x, dz = (long) bz - z;
            return dx * dx + dz * dz;
        }
    }
    /**
     * The material an unread cell takes: the class of its {@link #K} nearest measured centres with the most votes weighted
     * by inverse squared distance (Shepard's interpolation for classes), a tie to the nearest centre's class (ties in
     * distance by world X, then Z). One nearest centre draws the measured area's edge cells out along straight rays across
     * all unread ground: one pond cell at the edge of ground zero's loaded chunks becomes a 64-block strip of water
     * kilometres long, the dust over it a thin spray trench drifting with the wind. A water body wider than a few cells
     * at the edge (a shore) keeps its class out along its own side. K (est.): a measured edge cell's ring of neighbours, so
     * one odd cell is outvoted by its own ring.
     */
    static final int K = 8;
    static final class Nearest {
        final Site[] sites = new Site[K];
        final long[] dist = new long[K];
        int n;
        int x, z;
        Nearest(int x, int z) { at(x, z); }
        /** Reused for another cell (no garbage per cell on a 330,000-cell map). */
        Nearest at(int x, int z) { this.x = x; this.z = z; n = 0; return this; }
        /** The worst kept squared distance, or infinity while fewer than K are kept. */
        long bound() { return n < K ? Long.MAX_VALUE : dist[K - 1]; }
        void offer(Site s) {
            long d = s.distance(x, z);
            if (n == K && (d > dist[K - 1] || d == dist[K - 1] && Tree.X.compare(s, sites[K - 1]) >= 0)) return;
            int at = n < K ? n++ : K - 1;
            while (at > 0 && (dist[at - 1] > d || dist[at - 1] == d && Tree.X.compare(sites[at - 1], s) > 0)) {
                sites[at] = sites[at - 1];
                dist[at] = dist[at - 1];
                at--;
            }
            sites[at] = s;
            dist[at] = d;
        }
        private final double[] votes = new double[4];
        int material() {
            java.util.Arrays.fill(votes, 0);
            for (int i = 0; i < n; i++) votes[sites[i].material] += 1.0 / Math.max(dist[i], 1);
            int best = n == 0 ? DustMaterial.SOIL.ordinal() : sites[0].material;
            for (int k = 0; k < 4; k++) if (votes[k] > votes[best]) best = k;
            return best;
        }
    }

    /** Exact squared distance between cell centres. Equal distances use world X then Z. */
    record Tree(Site site, boolean splitX, Tree left, Tree right) {
        static final Comparator<Site> X = Comparator.comparingInt(Site::x).thenComparingInt(Site::z);
        static final Comparator<Site> Z = Comparator.comparingInt(Site::z).thenComparingInt(Site::x);
        static Tree build(Site[] sites, int from, int end, boolean splitX) {
            if (from == end) return null;
            Arrays.sort(sites, from, end, splitX ? X : Z);
            int mid = (from + end) >>> 1;
            return new Tree(sites[mid], splitX, build(sites, from, mid, !splitX), build(sites, mid + 1, end, !splitX));
        }
        /** Offers this subtree's sites near (q.x, q.z) to q: every site nearer than q's K-th is offered. */
        void nearest(Nearest q) {
            q.offer(site);
            long delta = splitX ? q.x - site.x : q.z - site.z;
            Tree near = delta < 0 ? left : right, far = delta < 0 ? right : left;
            if (near != null) near.nearest(q);
            if (far != null && delta * delta <= q.bound()) far.nearest(q);
        }
    }

    /** Literal two-bit cells or runs of identical packed bytes, whichever occupies fewer bytes. */
    public byte[] encode() {
        var runs = new ByteArrayOutputStream();
        for (int i = 0; i < cells.length;) {
            int end = i + 1;
            while (end < cells.length && cells[end] == cells[i] && end - i < 256) end++;
            runs.write(end - i - 1);
            runs.write(cells[i]);
            i = end;
        }
        boolean rle = runs.size() < cells.length;
        byte[] body = rle ? runs.toByteArray() : cells;
        DustGrid.Bounds b = grid.bounds;
        ByteBuffer out = ByteBuffer.allocate(HEADER + (complete() ? 0 : 24) + body.length).put((byte) (complete() ? VERSION : 2)).putInt(b.x()).putInt(b.z())
                .putInt(b.dense()).putInt(b.reach()).putLong(tick).put((byte) (wet ? 1 : 0)).put((byte) (rle ? 1 : 0));
        if (!complete()) out.putDouble(b.offsetX()).putDouble(b.offsetZ()).putDouble(through);
        return out.put(body).array();
    }
    public static MaterialMap decode(byte[] data) {
        ByteBuffer in = ByteBuffer.wrap(data);
        int version = in.hasRemaining() ? in.get() : 0;
        if (data.length < HEADER || version != VERSION && version != 2) throw new IllegalArgumentException("Dust map header");
        int x = in.getInt(), z = in.getInt(), dense = in.getInt(), reach = in.getInt();
        long tick = in.getLong();
        int wet = in.get(), format = in.get();
        if (wet < 0 || wet > 1 || format < 0 || format > 1) throw new IllegalArgumentException("Dust map flags");
        var bounds = version == 2 ? new DustGrid.Bounds(x, z, dense, reach, in.getDouble(), in.getDouble())
                : new DustGrid.Bounds(x, z, dense, reach);
        double through = version == 2 ? in.getDouble() : Double.POSITIVE_INFINITY;
        if (version == 2 && (!Double.isFinite(through) || through < -1)) throw new IllegalArgumentException("Dust frontier");
        var grid = new DustGrid(bounds);
        int size = (grid.count() + 3) / 4;
        byte[] cells = new byte[size];
        if (format == 0) {
            if (in.remaining() != size) throw new IllegalArgumentException("Dust map literal length");
            in.get(cells);
        } else {
            int total = 0;
            while (in.remaining() >= 2) {
                int run = Byte.toUnsignedInt(in.get()) + 1;
                if (run > size - total) throw new IllegalArgumentException("Dust map run length");
                Arrays.fill(cells, total, total + run, in.get());
                total += run;
            }
            if (in.hasRemaining() || total != size) throw new IllegalArgumentException("Incomplete dust map");
        }
        return new MaterialMap(grid, cells, wet == 1, tick, through);
    }
}
