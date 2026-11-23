package ua.zentix.incoming.nuclear.material;

import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.DustModel;

/** World-aligned rows of cells intersecting the two dust footprints. Coordinates and radii are blocks. */
public final class DustGrid {
    public static final int FINE = 64, COARSE = 256;
    public static final double GONE_DEPTH = 0.05, SETTLE_SECONDS = 1.0;
    private static final int MAX_RADIUS = 65536;

    public record Bounds(int x, int z, int dense, int reach) {
        public Bounds {
            if (dense < 0 || reach < dense || reach > MAX_RADIUS) throw new IllegalArgumentException("Dust radii");
        }

        public static Bounds of(Detonation d) {
            double hob = Math.max(0, d.hobMetres());
            double reach = DustModel.reach(hob, d.yieldKt(), d.surface(), d.visibility(), DustModel.Surface.REFERENCE, GONE_DEPTH);
            double lo = 0, hi = reach;
            while (hi - lo > 1) {
                double r = (lo + hi) / 2;
                double depth = DustModel.column(r, hob, d.yieldKt(), d.surface(), d.visibility(),
                        DustModel.Surface.REFERENCE).depth(SETTLE_SECONDS);
                if (depth >= 1) lo = r;
                else hi = r;
            }
            return new Bounds((int) Math.floor(d.burst().x), (int) Math.floor(d.burst().z),
                    (int) Math.ceil(d.blocks(hi)), (int) Math.ceil(d.blocks(reach)));
        }
    }

    @FunctionalInterface
    public interface Visitor { void cell(int index, int x, int z, int size); }

    private record Row(int from, int end, int omit, int resume, int offset) {
        int count() { return end - from - (resume - omit); }
        int index(int x) {
            if (x < from || x >= end || x >= omit && x < resume) return -1;
            return offset + x - from - (x >= resume ? resume - omit : 0);
        }
    }

    private final class Band {
        final int size, minZ;
        final Row[] rows;
        Band(int size, int radius, int inner) {
            this.size = size;
            minZ = Math.floorDiv(bounds.z - radius, size);
            rows = new Row[Math.floorDiv(bounds.z + radius, size) - minZ + 1];
            for (int i = 0; i < rows.length; i++) {
                int z = (minZ + i) * size;
                int dz = Math.max(0, Math.max(z - bounds.z, bounds.z - z - size + 1));
                int dx = extent(radius, dz);
                int from = Math.floorDiv(bounds.x - dx, size), end = Math.floorDiv(bounds.x + dx, size) + 1;
                int omit = from, resume = from;
                int farZ = Math.max(Math.abs(bounds.z - z), Math.abs(bounds.z - z - size + 1));
                if (inner > 0 && farZ <= inner) {
                    int width = extent(inner, farZ);
                    omit = Math.ceilDiv(bounds.x - width, size);
                    resume = Math.max(omit, Math.floorDiv(bounds.x + width + 1, size));
                }
                Row row = new Row(from, end, omit, resume, count);
                rows[i] = row;
                count += row.count();
            }
        }
        int index(int x, int z) {
            int i = Math.floorDiv(z, size) - minZ;
            return i >= 0 && i < rows.length ? rows[i].index(Math.floorDiv(x, size)) : -1;
        }
        void visit(Visitor visitor) {
            for (int i = 0; i < rows.length; i++) {
                Row r = rows[i];
                for (int x = r.from; x < r.end; x++) {
                    int index = r.index(x);
                    if (index >= 0) visitor.cell(index, x * size + size / 2, (minZ + i) * size + size / 2, size);
                }
            }
        }
    }

    private static int extent(int radius, int dz) {
        return (int) Math.sqrt((long) radius * radius - (long) dz * dz);
    }

    public final Bounds bounds;
    private final Band fine, coarse;
    private int count;
    public DustGrid(Bounds bounds) {
        this.bounds = bounds;
        fine = new Band(FINE, bounds.dense, 0);
        coarse = new Band(COARSE, bounds.reach, bounds.dense);
    }
    public int count() { return count; }
    public int index(int x, int z) {
        int i = fine.index(x, z);
        return i >= 0 ? i : coarse.index(x, z);
    }
    public void visit(Visitor visitor) {
        fine.visit(visitor);
        coarse.visit(visitor);
    }
}
