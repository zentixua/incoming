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
    private final long tick;

    private MaterialMap(DustGrid grid, byte[] cells, boolean wet, long tick) {
        this.grid = grid;
        this.cells = cells;
        this.wet = wet;
        this.tick = tick;
    }

    public DustMaterial material(int x, int z) {
        int i = grid.index(x, z);
        return i < 0 ? DustMaterial.SOIL : DustMaterial.of(cells[i / 4] >>> (2 * (i % 4)) & 3);
    }
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
        grid.visit((i, x, z, size) -> {
            int value = counts[i] == 0 ? tree.nearest(x, z, tree.site).material : winner(counts[i]);
            cells[i / 4] |= (byte) (value << (2 * (i % 4)));
        });
        int origin = grid.index(bounds.x(), bounds.z());
        int under = epicenter >= 0 ? epicenter : (cells[origin / 4] >>> (2 * (origin % 4)) & 3);
        return new MaterialMap(grid, cells, under == DustMaterial.WATER.ordinal(), tick);
    }

    private static int winner(long counts) {
        int best = 0, most = -1;
        for (int k = 0; k < 4; k++) {
            int n = (int) (counts >>> (k * 16)) & 65535;
            if (n > most) { best = k; most = n; }
        }
        return best;
    }
    private record Site(int x, int z, int material) {
        long distance(int bx, int bz) {
            long dx = (long) bx - x, dz = (long) bz - z;
            return dx * dx + dz * dz;
        }
    }
    /** Exact squared distance between cell centres. Equal distances use world X then Z. */
    private record Tree(Site site, boolean splitX, Tree left, Tree right) {
        static final Comparator<Site> X = Comparator.comparingInt(Site::x).thenComparingInt(Site::z);
        static final Comparator<Site> Z = Comparator.comparingInt(Site::z).thenComparingInt(Site::x);
        static Tree build(Site[] sites, int from, int end, boolean splitX) {
            if (from == end) return null;
            Arrays.sort(sites, from, end, splitX ? X : Z);
            int mid = (from + end) >>> 1;
            return new Tree(sites[mid], splitX, build(sites, from, mid, !splitX), build(sites, mid + 1, end, !splitX));
        }
        Site nearest(int x, int z, Site best) {
            long d = site.distance(x, z), b = best.distance(x, z);
            if (d < b || d == b && X.compare(site, best) < 0) best = site;
            long delta = splitX ? x - site.x : z - site.z;
            Tree near = delta < 0 ? left : right, far = delta < 0 ? right : left;
            if (near != null) best = near.nearest(x, z, best);
            if (far != null && delta * delta <= best.distance(x, z)) best = far.nearest(x, z, best);
            return best;
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
        return ByteBuffer.allocate(HEADER + body.length).put((byte) VERSION).putInt(b.x()).putInt(b.z())
                .putInt(b.dense()).putInt(b.reach()).putLong(tick).put((byte) (wet ? 1 : 0)).put((byte) (rle ? 1 : 0))
                .put(body).array();
    }
    public static MaterialMap decode(byte[] data) {
        ByteBuffer in = ByteBuffer.wrap(data);
        if (data.length < HEADER || in.get() != VERSION) throw new IllegalArgumentException("Dust map header");
        var grid = new DustGrid(new DustGrid.Bounds(in.getInt(), in.getInt(), in.getInt(), in.getInt()));
        long tick = in.getLong();
        int wet = in.get(), format = in.get(), size = (grid.count() + 3) / 4;
        if (wet < 0 || wet > 1 || format < 0 || format > 1) throw new IllegalArgumentException("Dust map flags");
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
        return new MaterialMap(grid, cells, wet == 1, tick);
    }
}
