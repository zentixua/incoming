package ua.zentix.incoming.scenario.lod;

import java.util.PriorityQueue;

/** 90-degree near-side panorama, outer 20% of the 5 psi radius. No area-sized array or pre-scan. */
public final class LodPanorama {
    public record Column(int x, int z) { public double radius() { return 16 * Math.hypot(x, z); } }
    private static final class Row {
        final int z, end;
        int x;
        boolean negative;
        Row(int z, int start, int end) { this.z = z; this.x = start; this.end = end; }
        long squared() { return (long) x * x + (long) z * z; }
        Column next() {
            Column c = new Column(negative ? -x : x, z);
            if (x == 0 || negative) { x++; negative = false; } else negative = true;
            return c;
        }
    }
    private final double outer, inner;
    private final PriorityQueue<Row> rows = new PriorityQueue<>((a, b) -> {
        int d = Long.compare(a.squared(), b.squared());
        return d == 0 ? Integer.compare(a.z, b.z) : d;
    });
    private int nextZ;
    private long columns;
    private boolean initialized;

    public LodPanorama(double radiusMeters) {
        outer = radiusMeters / 16;
        inner = outer * 0.8;
        nextZ = (int) Math.ceil(inner / Math.sqrt(2));
    }

    /** At most maxRows row headers per work slice; at 1 Mt fewer than 200 headers, not 520k chunks. */
    public boolean prepare(int maxRows) {
        if (maxRows <= 0) throw new IllegalArgumentException("positive row budget required");
        for (int n = 0; n < maxRows && nextZ <= Math.floor(outer); n++, nextZ++) {
            int start = (int) Math.ceil(Math.sqrt(Math.max(0, inner * inner - (double) nextZ * nextZ)));
            int end = (int) Math.floor(Math.min(nextZ, Math.sqrt(Math.max(0, outer * outer - (double) nextZ * nextZ))));
            if (start <= end) {
                rows.add(new Row(nextZ, start, end));
                columns += 2L * (end - start + 1) - (start == 0 ? 1 : 0);
            }
        }
        return initialized = nextZ > Math.floor(outer);
    }

    public Column next() {
        if (!initialized) throw new IllegalStateException("row preparation unfinished");
        Row row = rows.poll();
        if (row == null) return null;
        Column c = row.next();
        if (row.x <= row.end) rows.add(row);
        return c;
    }

    public long columns() { return columns; }
    public int headers() { return rows.size(); }
}
