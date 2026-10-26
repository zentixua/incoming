package ua.zentix.incoming.util;

/**
 * Local ground wind: correction to world wind ({@link Wind}, {@link Air}) from surroundings — {@link #factor} of speed
 * at height above ground relative to the same height over flat field. World wind is weather above the surface layer
 * and remains shared; correction applies only below urban blending height and near hills, using surrounding heightmaps,
 * once per position: server and client compute identically from the same maps, no packet.
 * <ul>
 *   <li><b>Ground and buildings.</b> Heightmap ({@code MOTION_BLOCKING}: houses, crowns, posts) separates ground from
 *   objects by morphological opening over a {@value #GROUND_WINDOW} m window — lidar ground filtering (Zhang K. et al.,
 *   IEEE TGRS 41:872, 2003): anything narrower than the window (house, tree) is removed, slope remains slope.
 *   ⚠ A narrow ridge loses its top — low “buildings” appear on the crest.</li>
 *   <li><b>Roughness.</b> Plan area fraction λp, mean building height H and wind-facing frontal area fraction λf give
 *   displacement height d and roughness z₀ (Macdonald, Griffiths, Hall 1998 morphometry, Atmos. Environ. 32:1857:
 *   A = 4.43, C_D = 1.2, β = 1). Above H, logarithmic profile over d up to blending height {@value #BLEND}·H
 *   (Grimmond, Oke 1999, J. Appl. Meteor. 38:1262: 2–5 H), matching field wind there (grass z₀ {@value #OPEN_Z0} m,
 *   Davenport/WMO “open terrain” class). Below H, in streets, exponential u(z) = u(H)·exp(a(z/H − 1)), a = 9.6·λf
 *   (Macdonald 2000, Bound.-Layer Meteor. 97:25; fit only to λf 0.33, no extrapolation). ⚠ Castro 2017 (BLM 162:207)
 *   shows dense urban mean profiles are not always exponential; street direction (along it) is unchanged.</li>
 *   <li><b>Hill.</b> Above a summit of height H and half-width L (at half-height), speed-up ΔS = B·H/L,
 *   B = {@value #HILL_B} (3D hill), decaying with height as exp(−{@value #HILL_A}·z/L); opposite in a hollow
 *   (Jackson, Hunt 1975 linear theory; Taylor, Lee 1984 guidance, Bound.-Layer Meteor. 30:415). Beyond slope
 *   H/L = {@value #HILL_SLOPE}, separation stops further speed-up. ⚠ L uses the surrounding ring with ground lowest
 *   relative to the summit; lee-side shelter is not modeled.</li>
 * </ul>
 *
 * @param ground       local ground, world y ({@link Double#NaN} — unknown: use height above sea level)
 * @param base         caller's reference surface height (roof under blast), above ground, m
 * @param canopy       mean building and crown height H, m (0 — field)
 * @param displacement displacement height d, m
 * @param roughness    roughness z₀, m
 * @param attenuation  urban profile exponent a
 * @param hill         ground speed-up ΔS (negative — hollow)
 * @param hillLength   hill half-width L, m
 */
public record LocalWind(double ground, double base, double canopy, double displacement, double roughness, double attenuation,
                        double hill, double hillLength) {
    /** Open-terrain roughness (short grass), m: reference for world surface wind. */
    public static final double OPEN_Z0 = 0.03;
    /** Flat field: no correction. */
    public static final LocalWind OPEN = new LocalWind(Double.NaN, 0, 0, 0, OPEN_Z0, 0, 0, 1);
    /** Heightmap sample spacing and urban square half-size, m. */
    static final int STEP = 2, RADIUS = 32;
    /** Ground filter window (square side), m: narrower houses and crowns are removed. */
    static final int GROUND_WINDOW = 25;
    /** Below this ground-relative height, not a building (fence, shrub, terrain step), m. */
    static final double OBSTACLE = 2;
    /** Blending height in H. */
    static final double BLEND = 3;
    /** Macdonald 1998 morphometry and Macdonald 2000 exponential profile. */
    static final double A = 4.43, CD = 1.2, KAPPA = 0.4, ATTENUATION = 9.6, FRONTAL_MAX = 0.33;
    /** Hill: foot-search rings, m, rays per ring; Taylor, Lee 1984. */
    static final double[] HILL_RINGS = {16, 32, 64, 128};
    static final int SPOKES = 16;
    static final double HILL_B = 1.6, HILL_A = 4, HILL_SLOPE = 0.5, HILL_LOW = -0.5;

    /** Column top (first air above heightmap), world y; {@link Double#NaN} — unknown. */
    @FunctionalInterface
    public interface Heights {
        double at(int x, int z);
    }

    /**
     * Ground wind at (x, z) with world wind unit direction (ux, uz), which sets building frontal area.
     * Caller reference surface ({@link #base}) is the column top at that position.
     */
    public static LocalWind of(Heights heights, double x, double z, double ux, double uz) {
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        int w = GROUND_WINDOW / 2 / STEP, n = 2 * (RADIUS / STEP) + 1, m = n + 4 * w;
        double[][] top = new double[m][m];
        for (int i = 0; i < m; i++) for (int j = 0; j < m; j++) top[i][j] = heights.at(cx + (i - m / 2) * STEP, cz + (j - m / 2) * STEP);
        double[][] ground = opening(top, w);
        int c = m / 2;
        double g0 = ground[c][c];
        if (Double.isNaN(g0)) return OPEN;
        // buildings in the local square: plan area fraction, height and upwind steps
        int di = (int) Math.round(ux), dj = (int) Math.round(uz);
        int known = 0, built = 0;
        double sum = 0, frontal = 0;
        for (int i = 2 * w; i < 2 * w + n; i++) {
            for (int j = 2 * w; j < 2 * w + n; j++) {
                double o = obstacle(top, ground, i, j);
                if (Double.isNaN(o)) continue;
                known++;
                if (o > 0) {
                    built++;
                    sum += o;
                }
                double up = obstacle(top, ground, i - di, j - dj);
                if (!Double.isNaN(up) && o > up) frontal += o - up;
            }
        }
        double canopy = 0, d = 0, z0 = OPEN_Z0, a = 0;
        if (known >= n * n / 4 && built > 0) {
            double lp = (double) built / known, lf = frontal / (known * (double) STEP), h = sum / built;
            double dh = 1 + Math.pow(A, -lp) * (lp - 1);
            double z0h = (1 - dh) * Math.exp(-1 / Math.sqrt(0.5 * CD / (KAPPA * KAPPA) * (1 - dh) * Math.max(lf, 1e-6)));
            if (z0h * h > OPEN_Z0) {
                canopy = h;
                d = dh * h;
                z0 = z0h * h;
                a = ATTENUATION * Math.min(lf, FRONTAL_MAX);
            }
        }
        double here = top[c][c];
        double base = Double.isNaN(here) ? 0 : Math.max(0, here - g0);
        // hill: ground on rings relative to local ground (same coarse minimum window for consistent slope displacement)
        double g = coarse(heights, cx, cz), hill = 0, length = 1, best = 0;
        if (!Double.isNaN(g)) {
            for (double ring : HILL_RINGS) {
                double s = 0;
                int k = 0;
                for (int q = 0; q < SPOKES; q++) {
                    double ang = 2 * Math.PI * q / SPOKES, v = coarse(heights, (int) Math.floor(x + ring * Math.cos(ang)), (int) Math.floor(z + ring * Math.sin(ang)));
                    if (Double.isNaN(v)) continue;
                    s += v;
                    k++;
                }
                if (k < SPOKES / 2) continue;
                double l = ring / 2, ds = Math.max(HILL_LOW, HILL_B * Math.clamp((g - s / k) / l, -HILL_SLOPE, HILL_SLOPE));
                double at10 = Math.abs(ds) * Math.exp(-HILL_A * 10 / l);
                if (at10 > best) {
                    best = at10;
                    hill = ds;
                    length = l;
                }
            }
        }
        return new LocalWind(g0, base, canopy, d, z0, a, hill, length);
    }

    /** Cell building height above ground, m: below {@link #OBSTACLE} — 0; unknown — NaN. */
    private static double obstacle(double[][] top, double[][] ground, int i, int j) {
        if (i < 0 || j < 0 || i >= top.length || j >= top.length) return Double.NaN;
        double o = top[i][j] - ground[i][j];
        if (Double.isNaN(o)) return Double.NaN;
        return o >= OBSTACLE ? o : 0;
    }

    /** Morphological opening by square (2w+1)²: minimum, then maximum; unknowns excluded. */
    static double[][] opening(double[][] v, int w) {
        return filter(filter(v, w, true), w, false);
    }

    private static double[][] filter(double[][] v, int w, boolean min) {
        int m = v.length;
        double[][] a = new double[m][m], b = new double[m][m];
        double[] line = new double[m], out = new double[m];
        for (int i = 0; i < m; i++) {
            run(v[i], a[i], w, min);
        }
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < m; i++) line[i] = a[i][j];
            run(line, out, w, min);
            for (int i = 0; i < m; i++) b[i][j] = out[i];
        }
        return b;
    }

    /**
     * Minimum (maximum) in row window ±w in constant time per point (van Herk 1992, Gil, Werman 1993): left/right prefix
     * accumulations in blocks of 2w + 1. Unknowns excluded; no known value in window — NaN.
     */
    static void run(double[] v, double[] out, int w, boolean min) {
        int m = v.length, k = 2 * w + 1;
        double none = min ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        double[] left = new double[m], right = new double[m];
        for (int i = 0; i < m; i++) {
            double x = Double.isNaN(v[i]) ? none : v[i];
            left[i] = i % k == 0 ? x : min ? Math.min(left[i - 1], x) : Math.max(left[i - 1], x);
        }
        for (int i = m - 1; i >= 0; i--) {
            double x = Double.isNaN(v[i]) ? none : v[i];
            right[i] = i == m - 1 || (i + 1) % k == 0 ? x : min ? Math.min(right[i + 1], x) : Math.max(right[i + 1], x);
        }
        for (int i = 0; i < m; i++) {
            int lo = Math.max(0, i - w), hi = Math.min(m - 1, i + w);
            // window in two neighboring blocks: right accumulation from lo and left to hi; in one (shorter window at row edges),
            // accumulation from block start or to row end
            double r;
            if (lo / k != hi / k) r = min ? Math.min(right[lo], left[hi]) : Math.max(right[lo], left[hi]);
            else r = lo % k == 0 ? left[hi] : right[lo];
            out[i] = Double.isInfinite(r) ? Double.NaN : r;
        }
    }

    /** Hill ground: minimum of five columns in a ±4 m cross (house or tree at the exact point does not raise it). */
    private static double coarse(Heights h, int x, int z) {
        double r = Double.NaN;
        int[][] cross = {{0, 0}, {4, 0}, {-4, 0}, {0, 4}, {0, -4}};
        for (int[] d : cross) {
            double v = h.at(x + d[0], z + d[1]);
            if (!Double.isNaN(v) && (Double.isNaN(r) || v < r)) r = v;
        }
        return r;
    }

    /**
     * Wind fraction at z m above caller surface ({@link #base} above ground) relative to the same height above field:
     * 1 above surface blending height and far from a hill; over a roof, also higher above ground.
     */
    public double factor(double z) {
        return factor(z, base);
    }

    /** Wind fraction at h m above local ground relative to the same height over field. */
    public double overGround(double h) {
        return factor(h, 0);
    }

    private double factor(double z, double base) {
        double zz = Math.max(Wind.FLOOR, z), h = Math.max(Wind.FLOOR, z + base);
        // speeds relative to field wind at blending height (field uses logarithm over grass z₀)
        double here = Math.log(h / OPEN_Z0), zb = BLEND * canopy;
        if (canopy > 0 && h < zb) {
            double above = Math.max(h, canopy);
            here = Math.log(zb / OPEN_Z0) * Math.log((above - displacement) / roughness) / Math.log((zb - displacement) / roughness);
            if (h < canopy) here *= Math.exp(attenuation * (h / canopy - 1));
        }
        double f = here / Math.log(zz / OPEN_Z0);
        if (hill != 0) f *= 1 + hill * Math.exp(-HILL_A * h / hillLength);
        return f;
    }

    /**
     * Same position, measured from world y ({@link #base} is its height above local ground): surface beneath a blast,
     * not column top (crown above it, neighboring roof in cache cell).
     */
    public LocalWind from(double y) {
        return new LocalWind(ground, Double.isNaN(ground) ? 0 : Math.max(0, y - ground), canopy, displacement, roughness, attenuation,
                hill, hillLength);
    }

    /** Height of world y above local ground, m (unknown ground — above sea level, as world wind). */
    public double above(double y) {
        return y - (Double.isNaN(ground) ? Air.SEA : ground);
    }

    /** Cloud cache key: correction to 2 % precision at surface-layer heights. */
    public String key() {
        return Math.round(50 * factor(2)) + ":" + Math.round(50 * factor(10)) + ":" + Math.round(50 * factor(40));
    }
}
