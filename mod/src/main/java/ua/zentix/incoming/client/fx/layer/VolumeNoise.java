package ua.zentix.incoming.client.fx.layer;

import java.util.Random;

/**
 * Seamless 3D noise tile for pall volume ({@link PallPass}, {@code pall.glsl} shader): R channel — billows
 * (Perlin-Worley, like Schneider's Horizon Zero Dawn clouds, 2015: Perlin fBm eroded by Worley fBm — round
 * billows with gaps, rather than blurry blotches), G — fine edges (Worley fBm at triple frequency), B — mushroom billows
 * ({@code mushLobes} in {@code fx_pall}): the Worley fBm eroding Perlin in R — inverted F1, a hump at every
 * cell point. An edge displaced by a hump forms a round dome (edge at height h above the cell point where h = a·(c − √(ρ² + h²))
 * is a paraboloid); large domes span 16 voxels, with smaller ones on top. Perlin in R cannot produce such domes: its blotches
 * are elongated and octave gradients alike, so billow shading becomes fine ripples. Perlin lattices and Worley cells repeat
 * at the tile period — wrapping texture has no seams. Custom code, rather than FastNoiseLite: it lacks periodic noise.
 * <p>
 * Pure function of seed: identical tile for everyone, computed once in the background ({@link PallPass}).
 */
public final class VolumeNoise {
    /** Tile side, voxels. */
    public static final int SIZE = 64;
    /** Channels per voxel (R, G, B). */
    public static final int CHANNELS = 3;
    /** Perlin and Worley cells per tile at the first octave. */
    private static final int BASE_CELLS = 4;
    private VolumeNoise() {}

    /** Tile {@code size}³: {@link #CHANNELS} bytes per voxel (R, G, B), x fastest, then y, then z. */
    public static byte[] generate(int size, long seed) {
        Random random = new Random(seed);
        int[] perm = new int[512];
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
        Cells[] worley = new Cells[5];
        for (int o = 0; o < worley.length; o++) worley[o] = new Cells(BASE_CELLS << o, random.nextLong());
        byte[] out = new byte[size * size * size * CHANNELS];
        double[] r = new double[size * size * size], g = new double[r.length], b = new double[r.length];
        for (int z = 0; z < size; z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double u = (double) x / size, v = (double) y / size, w = (double) z / size;
                    // Perlin fBm: 4, 8, 16 cells per tile; −1..1 → 0..1
                    double perlin = 0, amp = 1, norm = 0;
                    for (int o = 0; o < 3; o++) {
                        int cells = BASE_CELLS << o;
                        perlin += amp * perlin(u * cells, v * cells, w * cells, cells, perm);
                        norm += amp;
                        amp *= 0.5;
                    }
                    perlin = clamp01(0.5 + 0.5 * perlin / norm * 1.6);
                    // Worley fBm (inverted F1): 4, 8, 16 cells erode Perlin
                    double wfbm = 0.625 * worley[0].f1(u, v, w) + 0.25 * worley[1].f1(u, v, w) + 0.125 * worley[2].f1(u, v, w);
                    double pw = clamp01(remap(perlin, wfbm - 1, 1));
                    // fine edges: Worley 16, 32, 64 cells
                    double detail = 0.625 * worley[2].f1(u, v, w) + 0.25 * worley[3].f1(u, v, w) + 0.125 * worley[4].f1(u, v, w);
                    int i = (z * size + y) * size + x;
                    r[i] = pw;
                    g[i] = detail;
                    b[i] = wfbm;
                }
            }
        }
        // channels ranked uniformly over 0..1: billow shape ({@link #shape}) is independent of octave alignment
        equalize(r, out, 0);
        equalize(g, out, 1);
        equalize(b, out, 2);
        return out;
    }

    /** Values → rank 0..255 (uniform; same ordering and thus billow pattern), into bytes of {@code channel}. */
    private static void equalize(double[] values, byte[] out, int channel) {
        Integer[] order = new Integer[values.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(values[a], values[b]));
        for (int k = 0; k < order.length; k++) out[CHANNELS * order[k] + channel] = (byte) ((long) k * 256 / order.length);
    }

    /**
     * Billow shape — local noise density multiplier: {@code r}, {@code g} — tile channels 0..1 (billows and fine edges
     * sampled elsewhere, at triple frequency); {@code fill} — local pall continuity (0 — sparse edge; 1 — dense
     * centre): ragged edge (55 % empty), continuous centre, density varying smoothly from gaps (15 %) to twice
     * the mean. No binary present/absent step: in dense pall, neighbouring billow shadow ({@code fx_pall}) and light variation come
     * only from density differences; a shape nearly constant everywhere is flat. Measure {@code r − 0.3·(1 − g)}
     * is uniform over 0..0.7 (uniform independent channels), smoothing window is in that measure (for {@code fill} above 0.89 —
     * 0.05 lower, mean there lower by 10⁻⁴), so mean is 0.3 + 0.225·fill: gain inverts it, making average
     * noise density equal to field density ({@code PallField}; tested). Exact copy of {@code pallShape} from
     * {@code pall.glsl}.
     */
    public static double shape(double r, double g, double fill) {
        return smoothstep(0.4 - 0.45 * fill, 0.7, r - 0.3 * (1 - g)) / (0.3 + 0.225 * fill);
    }

    /**
     * Fine billows — density multiplier inside large ones ({@code pallDetail} in {@code pall.glsl} is identical): {@code r} — billow
     * tile channel sampled at quarter scale. From 0.3 to 1.7, mean exactly 1 for a uniform channel (smoothed threshold
     * midway through 0..1): “large × fine” cascade preserves mean field density and thus pall visibility.
     */
    public static double detail(double r) {
        return 0.3 + 1.4 * smoothstep(0.2, 0.8, r);
    }

    /** Perlin gradient noise with a period of {@code period} cells, −1..1. */
    static double perlin(double x, double y, double z, int period, int[] perm) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double xf = x - xi, yf = y - yi, zf = z - zi;
        double u = fade(xf), v = fade(yf), w = fade(zf);
        double sum = 0;
        for (int c = 0; c < 8; c++) {
            int dx = c & 1, dy = c >> 1 & 1, dz = c >> 2 & 1;
            int h = perm[perm[perm[Math.floorMod(xi + dx, period)] + Math.floorMod(yi + dy, period)] + Math.floorMod(zi + dz, period)];
            double g = grad(h, xf - dx, yf - dy, zf - dz);
            sum += g * (dx == 1 ? u : 1 - u) * (dy == 1 ? v : 1 - v) * (dz == 1 ? w : 1 - w);
        }
        return sum;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /** One of 12 gradients toward cube edges (Perlin, 2002). */
    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y, v = h < 4 ? y : h == 12 || h == 14 ? x : z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /** Worley cells: one point each, periodic over the tile. */
    private static final class Cells {
        final int n;
        final double[] px, py, pz;

        Cells(int n, long seed) {
            this.n = n;
            Random r = new Random(seed);
            px = new double[n * n * n];
            py = new double[n * n * n];
            pz = new double[n * n * n];
            for (int i = 0; i < px.length; i++) {
                px[i] = r.nextDouble();
                py[i] = r.nextDouble();
                pz[i] = r.nextDouble();
            }
        }

        /** Inverted nearest-point distance, 0..1 (1 at point, 0 one cell away or farther); u, v, w — tile fractions. */
        double f1(double u, double v, double w) {
            double x = u * n, y = v * n, z = w * n;
            int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
            double best = 9;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int cx = xi + dx, cy = yi + dy, cz = zi + dz;
                        int i = (Math.floorMod(cz, n) * n + Math.floorMod(cy, n)) * n + Math.floorMod(cx, n);
                        double ex = cx + px[i] - x, ey = cy + py[i] - y, ez = cz + pz[i] - z;
                        best = Math.min(best, ex * ex + ey * ey + ez * ez);
                    }
                }
            }
            return clamp01(1 - Math.sqrt(best));
        }
    }

    private static double remap(double x, double lo, double hi) {
        return (x - lo) / (hi - lo);
    }

    private static double clamp01(double x) {
        return Math.max(0, Math.min(1, x));
    }

    static double smoothstep(double e0, double e1, double x) {
        double t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }
}
