package ua.zentix.incoming.client.far;

/**
 * One source-to-eye ray over height map: whether terrain (hill, houses) occludes source, and by how much, for
 * visuals (blast site visible or only smoke above hill) and sound (edge diffraction, ISO 9613-2). At most
 * {@link #MAX_SAMPLES} points with spacing at least {@link #STEP} blocks; caller supplies heights
 * ({@link Heights}): client chunks, map terrain tiles beyond; unknown column is not a barrier.
 */
public final class Sightline {
    /** Maximum points per ray. */
    public static final int MAX_SAMPLES = 64;
    /** Minimum horizontal step, blocks. */
    public static final double STEP = 16;
    /** Height map is block-accurate: less than this above ray is not a barrier. */
    private static final double TOLERANCE = 0.5;

    private Sightline() {}

    /** Ground top (including buildings and canopies) in column; {@link Double#NaN} — unknown. */
    @FunctionalInterface
    public interface Heights {
        double at(int x, int z);
    }

    /**
     * @param pathDifference path difference over most significant edge (a + b − d), blocks; 0 — line of sight
     * @param hidden         blocks above source where eye-visible region starts (0 — source visible)
     */
    public record Result(double pathDifference, double hidden) {
        public static final Result OPEN = new Result(0, 0);
    }

    /** Smallest march step along {@link #ground}, blocks. */
    public static final double GROUND_STEP = 2;
    /**
     * March step grows with distance: one step spans this angle seen from the eye, rad (1/512 ≈ 0.11°, a few
     * pixels in binoculars), so a 20 km ray takes about 2 000 samples (2 below 1 km, then 512·ln(d/1 024)).
     */
    public static final double GROUND_ANGLE = 1.0 / 512;

    /**
     * Distance along a unit ray from the eye to the first column whose top reaches the ray, refined to a block;
     * {@link Double#NaN} if none between {@code from} and {@code to}. Unknown columns are not ground.
     */
    public static double ground(Heights heights, double ex, double ey, double ez, double dx, double dy, double dz, double from, double to) {
        double prev = from;
        for (double s = from; s <= to; s += Math.max(GROUND_STEP, s * GROUND_ANGLE)) {
            if (reaches(heights, ex, ey, ez, dx, dy, dz, s)) {
                double lo = prev, hi = s;
                while (hi - lo > 0.5) {
                    double mid = (lo + hi) / 2;
                    if (reaches(heights, ex, ey, ez, dx, dy, dz, mid)) hi = mid;
                    else lo = mid;
                }
                return hi;
            }
            prev = s;
        }
        return Double.NaN;
    }

    private static boolean reaches(Heights heights, double ex, double ey, double ez, double dx, double dy, double dz, double s) {
        double top = heights.at((int) Math.floor(ex + dx * s), (int) Math.floor(ez + dz * s));
        return !Double.isNaN(top) && top >= ey + dy * s;
    }

    public static Result trace(Heights heights, double sx, double sy, double sz, double ex, double ey, double ez) {
        double dx = ex - sx, dy = ey - sy, dz = ez - sz;
        double flat = Math.sqrt(dx * dx + dz * dz);
        int n = (int) Math.min(MAX_SAMPLES, Math.ceil(flat / STEP));
        if (n < 2) return Result.OPEN;
        double direct = Math.sqrt(flat * flat + dy * dy);
        double z = 0, hidden = 0;
        for (int i = 1; i < n; i++) {
            double f = (double) i / n;
            double top = heights.at((int) Math.floor(sx + dx * f), (int) Math.floor(sz + dz * f));
            if (Double.isNaN(top)) continue;
            if (top > sy + dy * f + TOLERANCE) {
                double a = Math.sqrt(f * flat * f * flat + (top - sy) * (top - sy));
                double b = Math.sqrt((1 - f) * flat * (1 - f) * flat + (ey - top) * (ey - top));
                z = Math.max(z, a + b - direct);
            }
            // line from eye through top: height above source is (top − ey)/u + ey − sy, u — path fraction from eye
            hidden = Math.max(hidden, (top - ey) / (1 - f) + ey - sy);
        }
        return new Result(z, hidden);
    }
}
