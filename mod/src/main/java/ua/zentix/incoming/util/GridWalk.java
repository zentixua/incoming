package ua.zentix.incoming.util;

/**
 * Square-grid cells intersected by an XZ-plane segment (Amanatides–Woo, 2D): skips no intersected cell, even when the
 * segment clips a corner. Pure code without Minecraft world: cell is a block column (size 1) or chunk column (16).
 */
public final class GridWalk {
    private GridWalk() {}

    /** Cell (x, z): whether to continue traversal. */
    @FunctionalInterface
    public interface Visitor {
        boolean visit(int x, int z);
    }

    /** Cell (x, z) and segment fractions {@code t0..t1} inside it: whether to continue traversal. */
    @FunctionalInterface
    public interface SpanVisitor {
        boolean visit(int x, int z, double t0, double t1);
    }

    /**
     * Visits cells of size {@code cell} from segment start to end.
     *
     * @return segment fraction (0..1) entering the cell where {@code visitor} stopped; 1 — all visited
     */
    public static double walk(double fromX, double fromZ, double toX, double toZ, double cell, Visitor visitor) {
        return walk(fromX, fromZ, toX, toZ, cell, (SpanVisitor) (x, z, t0, t1) -> visitor.visit(x, z));
    }

    /**
     * As {@link #walk(double, double, double, double, double, Visitor)}, but each cell receives its segment interval:
     * entry to exit (last cell exits at 1).
     */
    public static double walk(double fromX, double fromZ, double toX, double toZ, double cell, SpanVisitor visitor) {
        double dx = toX - fromX, dz = toZ - fromZ;
        int cx = cell(fromX, cell), cz = cell(fromZ, cell);
        int endX = cell(toX, cell), endZ = cell(toZ, cell);
        int stepX = dx > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        // path fraction to next cell boundary in x and z, and its increment per cell
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? cx + 1 : cx) * cell - fromX) / dx;
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? cz + 1 : cz) * cell - fromZ) / dz;
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : cell / Math.abs(dx);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : cell / Math.abs(dz);
        double t = 0;
        while (true) {
            boolean last = cx == endX && cz == endZ;
            if (!visitor.visit(cx, cz, t, last ? 1 : Math.min(1, Math.min(tMaxX, tMaxZ)))) return t;
            if (last) return 1;
            if (tMaxX < tMaxZ) {
                t = tMaxX;
                tMaxX += tDeltaX;
                cx += stepX;
            } else {
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                cz += stepZ;
            }
            if (t >= 1) return 1;
        }
    }

    private static int cell(double v, double cell) {
        return (int) Math.floor(v / cell);
    }
}
