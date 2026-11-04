package ua.zentix.incoming.nuclear.world;

import java.util.Comparator;
import java.util.PriorityQueue;

/** Radial chunk traversal with O(radius) row heads, never an O(area) list/sort before the first result. */
final class RadialChunks {
    private final double x, z, inner, outer;
    private final int lastX, middleZ;
    private int nextX;
    private final PriorityQueue<Row> heads = new PriorityQueue<>(Comparator.comparingDouble(Row::distance)
            .thenComparingInt(Row::x).thenComparingInt(Row::z));
    private record Row(int x, int z, int step, double distance) {}

    RadialChunks(double x, double z, double inner, double outer) {
        if (!Double.isFinite(x + z + inner + outer) || outer < 0) throw new IllegalArgumentException("invalid ring");
        this.x = x;
        this.z = z;
        this.inner = inner;
        this.outer = outer;
        nextX = (int) Math.floor((x - outer) / 16) - 1;
        lastX = (int) Math.floor((x + outer) / 16);
        middleZ = (int) Math.floor(z / 16);
    }

    boolean prepare(int rows) {
        while (rows-- > 0 && nextX <= lastX) {
            int cx = nextX++;
            double dx = gap(x, cx);
            if (dx > outer) continue;
            double hole = inner >= dx ? Math.sqrt(Math.max(0, inner * inner - dx * dx)) : -1;
            int positive = hole < 0 ? middleZ : Math.max(middleZ, (int) Math.floor((z + hole) / 16));
            int negative = hole < 0 ? middleZ - 1 : Math.min(middleZ - 1, (int) Math.ceil((z - hole) / 16) - 1);
            add(cx, positive, 1);
            add(cx, negative, -1);
        }
        return prepared();
    }

    private void add(int cx, int cz, int step) {
        double distance = distance(cx, cz);
        // At most two boundary chunks; the inner disk was skipped analytically, not walked.
        while (distance <= inner) {
            cz += step;
            distance = distance(cx, cz);
        }
        if (distance <= outer) heads.add(new Row(cx, cz, step, distance));
    }

    private double distance(int cx, int cz) { return Math.hypot(gap(x, cx), gap(z, cz)); }
    private static double gap(double coordinate, int chunk) {
        return Math.max(0, Math.max(chunk * 16.0 - coordinate, coordinate - (chunk * 16.0 + 16)));
    }
    boolean prepared() { return nextX > lastX; }
    boolean done() { return prepared() && heads.isEmpty(); }
    int headers() { return heads.size(); }
    long bytes() { return 128L + heads.size() * 64L; }
    long peek() {
        if (!prepared() || heads.isEmpty()) throw new IllegalStateException("no ready chunk");
        Row head = heads.element();
        return (head.x & 0xffffffffL) | (long) head.z << 32;
    }
    void advance() {
        Row head = heads.remove();
        add(head.x, head.z + head.step, head.step);
    }
}
