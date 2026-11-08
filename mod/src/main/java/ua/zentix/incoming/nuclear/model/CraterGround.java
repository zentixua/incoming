package ua.zentix.incoming.nuclear.model;

import java.util.Arrays;

/**
 * Ground a surface-burst crater is cut into, and the shape of its edge.
 * <p>
 * The crater is a cavity blown into the ground around the burst; buildings, roof gardens and small knolls above that
 * ground are thrown out with it. The column's own top ground-tagged block is not the ground: a city builds with the
 * same blocks (terracotta floors, stone, grass on a roof), so a bowl measured from it was cut from the roofs and left
 * the lower storeys standing in the crater as slanted slabs (Zearth 08.11.2026, 200 kt: 30 % of bowl columns kept
 * blocks up to 65 above the floor). The ground here is a plane through the low surface of each chunk of the bowl
 * ({@link #LOW_QUANTILE}: streets, yards, water), refitted without chunks standing above it ({@link #fit}): a slope
 * keeps its tilt, while buildings and knolls never move the bowl.
 */
public final class CraterGround {
    /** Share of a chunk's columns at or below its ground sample: streets and yards among buildings (est.). */
    public static final double LOW_QUANTILE = 0.1;
    /** Samples standing higher above the fitted plane are buildings, not ground, blocks (est.). */
    static final double ABOVE = 3;
    /** Refits dropping such samples; a city settles in two or three. */
    private static final int ROUNDS = 8;
    /**
     * Edge irregularity: relative change of the bowl radius with direction, standard deviation (est.). Real crater
     * edges vary with the ground and the ejecta, without a law; the former single harmonic {@code 0.08·sin 7θ} read as a
     * regular heptagon.
     */
    static final double EDGE_RMS = 0.03;
    /** Harmonics of the edge: lobes from three to twelve around the crater, amplitude falling as 1/k. */
    private static final int EDGE_FROM = 3, EDGE_TO = 12;

    private CraterGround() {}

    /**
     * Low surface of a chunk: the height at {@link #LOW_QUANTILE} of {@code n} column heights (sorted in place).
     */
    public static double low(int[] heights, int n) {
        Arrays.sort(heights, 0, n);
        return heights[Math.min(n - 1, (int) Math.floor(LOW_QUANTILE * n))];
    }

    /**
     * Ground plane through samples ({@code x}, {@code z} relative to the epicentre, {@code h} heights): least squares,
     * refitted without samples more than {@link #ABOVE} above it. Fewer than three samples or a degenerate layout give a
     * level plane at the lowest median of what remains.
     *
     * @return {@code {a, b, c}}: height = c + a·x + b·z
     */
    public static double[] fit(double[] x, double[] z, double[] h, int n) {
        boolean[] keep = new boolean[n];
        Arrays.fill(keep, true);
        double[] p = level(h, keep, n);
        for (int round = 0; round < ROUNDS; round++) {
            double[] q = leastSquares(x, z, h, keep, n);
            if (q == null) q = level(h, keep, n);
            p = q;
            boolean changed = false;
            int kept = 0;
            for (int i = 0; i < n; i++) {
                boolean k = h[i] - at(p, x[i], z[i]) < ABOVE;
                changed |= k != keep[i];
                keep[i] = k;
                if (k) kept++;
            }
            if (!changed || kept == 0) break;
        }
        return p;
    }

    /** Plane height at ({@code x}, {@code z}) relative to the epicentre. */
    public static double at(double[] plane, double x, double z) {
        return plane[2] + plane[0] * x + plane[1] * z;
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
