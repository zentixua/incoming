package ua.zentix.incoming.nuclear.model;

import java.util.Arrays;

/**
 * Ground a surface-burst crater is cut into, and the shape of its edge.
 * <p>
 * The crater is a cavity blown into the ground around the burst; buildings and roof gardens standing on that ground are
 * thrown out with it. A column's top ground-tagged block is not always the ground: a city builds with the same blocks
 * (terracotta floors, stone, grass on a roof), so a bowl measured from it was cut from the roofs and left the lower
 * storeys standing in the crater as slanted slabs (Zearth 08.11.2026, 200 kt: 30 % of bowl columns kept blocks up to 65
 * above the floor). Two steps find the ground under a building:
 * <ol>
 * <li>a plane through the low surface of every chunk around the bowl ({@link #lowColumn}: streets, yards, water),
 * refitted without the chunks standing above it ({@link #fit}), says how deep below a column the ground can be;</li>
 * <li>{@link #base} walks a column up from just under that plane: the first built block (not ground, air or liquid), or
 * the first air under a thin ground layer (a terracotta floor, a roof garden over a room), marks a building, and the
 * column's ground is the ground under it. A column of ground alone (a hill, a knoll, a bank) keeps its own surface, so
 * natural relief keeps its shape as before.</li>
 * </ol>
 */
public final class CraterGround {
    /** Share of a chunk's columns at or below its ground sample: streets and yards among buildings (est.). */
    public static final double LOW_QUANTILE = 0.1;
    /**
     * Samples standing higher above the fitted plane are buildings, not ground; {@link #base} also starts this far below
     * the plane, blocks (est.).
     */
    static final double BAND = 3;
    /** Fewer samples give a level plane: a tilt from a handful of chunks is noise. */
    static final int MIN_FIT = 6;
    /** A ground layer thinner than this over air is a floor or a roof garden, not ground over a cave, blocks (est.). */
    static final int FLOOR = 6;
    /** Refits at most; a city settles in two or three. */
    private static final int ROUNDS = 8;
    /**
     * Edge irregularity: relative change of the bowl radius with direction, standard deviation (est.). Real crater
     * edges vary with the ground and the ejecta, without a law; the former single harmonic {@code 0.08·sin 7θ} read as a
     * regular heptagon.
     */
    static final double EDGE_RMS = 0.03;
    /** Harmonics of the edge: lobes from three to twelve around the crater, amplitude falling as 1/k. */
    private static final int EDGE_FROM = 3, EDGE_TO = 12;

    /** What a block of a column is to {@link #base}. */
    public enum Layer { GROUND, AIR, LIQUID, BUILT }

    /** A column's blocks by height. */
    @FunctionalInterface
    public interface Column {
        Layer at(int y);
    }

    private CraterGround() {}

    /**
     * Column of a chunk standing for its low surface: index of the column at {@link #LOW_QUANTILE} of {@code n} column
     * heights. The sample is that column's own height at its own position, so a slope does not bias it.
     */
    public static int lowColumn(int[] heights, int n) {
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) keys[i] = (long) heights[i] << 32 | i;
        Arrays.sort(keys);
        return (int) keys[Math.min(n - 1, (int) Math.floor(LOW_QUANTILE * n))];
    }

    /**
     * Ground plane through samples ({@code x}, {@code z} relative to the epicentre, {@code h} heights): least squares,
     * refitted without samples more than {@link #BAND} above it until none is dropped. Roofs do not hold it up; pits and
     * ditches can pull it down, which only makes {@link #base} start its walk deeper (the plane is where the ground can
     * be at the lowest, not where a column's ground is). Fewer than {@link #MIN_FIT} samples or a degenerate layout give
     * a level plane at the lowest median.
     *
     * @return {@code {a, b, c}}: height = c + a·x + b·z
     */
    public static double[] fit(double[] x, double[] z, double[] h, int n) {
        boolean[] keep = new boolean[n];
        Arrays.fill(keep, true);
        double[] p = keptFit(x, z, h, keep, n);
        for (int round = 0; round < ROUNDS && n >= MIN_FIT; round++) {
            boolean changed = false;
            int kept = 0;
            for (int i = 0; i < n; i++) {
                boolean k = h[i] - at(p, x[i], z[i]) < BAND;
                changed |= k != keep[i];
                keep[i] = k;
                if (k) kept++;
            }
            if (!changed || kept == 0) break;
            p = keptFit(x, z, h, keep, n);
        }
        return p;
    }

    private static double[] keptFit(double[] x, double[] z, double[] h, boolean[] keep, int n) {
        int kept = 0;
        for (int i = 0; i < n; i++) if (keep[i]) kept++;
        double[] q = kept < MIN_FIT ? null : leastSquares(x, z, h, keep, n);
        return q != null ? q : level(h, keep, n);
    }

    /** Plane height at ({@code x}, {@code z}) relative to the epicentre. */
    public static double at(double[] plane, double x, double z) {
        return plane[2] + plane[0] * x + plane[1] * z;
    }

    /**
     * Ground of a column under the crater, as a heightmap height (first air above). {@code natural} is the column's top
     * ground block plus one, {@code plane} the ground plane there. A column standing above the plane is walked up from
     * {@link #BAND} below it, stretch by stretch of non-ground blocks (air, built, liquid). A stretch is the bottom of a
     * building when the ground over it is thinner than {@link #FLOOR} (a terracotta floor or a roof garden over a room),
     * or, holding a built block, thinner than the stretch is tall (a storey stack under a thick roof garden), or when it
     * holds a built block within {@link #BAND} of the plane (a slab under a solid terracotta podium); its bottom is then
     * the column's ground. Otherwise the column keeps {@code natural}: a hill keeps its surface over a cave, a
     * mineshaft, a dungeon or a stray block deep inside it. A thin cave roof counts as a floor too; within the bowl it
     * does not survive the burst.
     */
    public static int base(Column column, int natural, double plane, int minY) {
        if (!(natural > plane + 0.5)) return natural;
        int from = Math.max(minY, (int) Math.floor(plane) - (int) BAND);
        for (int y = from; y < natural - 1; ) {
            if (column.at(y) == Layer.GROUND) {
                y++;
                continue;
            }
            int top = y;
            boolean built = false, low = false;
            for (Layer l; top < natural && (l = column.at(top)) != Layer.GROUND; top++) {
                if (l == Layer.BUILT) {
                    built = true;
                    low |= top <= plane + BAND;
                }
            }
            int limit = built ? Math.max(FLOOR, top - y) : FLOOR, run = 0;
            while (run < limit && top + run < natural && column.at(top + run) == Layer.GROUND) run++;
            if (low || run < limit && (built || run > 0)) return y;
            y = top + run;
        }
        return natural;
    }

    /**
     * Bowl radius factor in direction {@code theta}: 1 plus irregular harmonics whose phases come from {@code phase}, so
     * one crater keeps one edge.
     */
    public static double edge(double theta, double phase) {
        double sum = 0, power = 0;
        for (int k = EDGE_FROM; k <= EDGE_TO; k++) {
            double a = 1.0 / k;
            sum += a * Math.sin(k * theta + 2 * Math.PI * unit(phase, k));
            power += a * a / 2;
        }
        return 1 + EDGE_RMS * sum / Math.sqrt(power);
    }

    /** Fraction in [0, 1) from a phase and a harmonic number. */
    private static double unit(double phase, int k) {
        long bits = Double.doubleToLongBits(phase) * 0x9E3779B97F4A7C15L + k * 0xC2B2AE3D27D4EB4FL;
        bits ^= bits >>> 31;
        bits *= 0xBF58476D1CE4E5B9L;
        bits ^= bits >>> 29;
        return (bits >>> 11) * 0x1.0p-53;
    }

    /** Level plane at the lowest median of the kept samples (of all, if none is kept). */
    private static double[] level(double[] h, boolean[] keep, int n) {
        double[] v = new double[n];
        int m = 0;
        for (int i = 0; i < n; i++) if (keep[i]) v[m++] = h[i];
        if (m == 0) return new double[] {0, 0, n == 0 ? 0 : Arrays.stream(h, 0, n).min().orElse(0)};
        Arrays.sort(v, 0, m);
        return new double[] {0, 0, v[(m - 1) / 2]};
    }

    private static double[] leastSquares(double[] x, double[] z, double[] h, boolean[] keep, int n) {
        double sxx = 0, sxz = 0, szz = 0, sx = 0, sz = 0, s = 0, sxh = 0, szh = 0, sh = 0;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            sxx += x[i] * x[i];
            sxz += x[i] * z[i];
            szz += z[i] * z[i];
            sx += x[i];
            sz += z[i];
            s++;
            sxh += x[i] * h[i];
            szh += z[i] * h[i];
            sh += h[i];
        }
        if (s < 3) return null;
        // normal equations [sxx sxz sx; sxz szz sz; sx sz s]·[a b c] = [sxh szh sh], Cramer's rule
        double det = sxx * (szz * s - sz * sz) - sxz * (sxz * s - sz * sx) + sx * (sxz * sz - szz * sx);
        if (Math.abs(det) < 1e-9 * Math.max(1, sxx * szz * s)) return null;
        double a = (sxh * (szz * s - sz * sz) - sxz * (szh * s - sz * sh) + sx * (szh * sz - szz * sh)) / det;
        double b = (sxx * (szh * s - sh * sz) - sxh * (sxz * s - sz * sx) + sx * (sxz * sh - szh * sx)) / det;
        double c = (sxx * (szz * sh - sz * szh) - sxz * (sxz * sh - szh * sx) + sxh * (sxz * sz - szz * sx)) / det;
        return new double[] {a, b, c};
    }
}
