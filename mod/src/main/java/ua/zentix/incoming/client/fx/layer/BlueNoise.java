package ua.zentix.incoming.client.fx.layer;

import java.util.Random;

/**
 * Blue noise for shifting pall ray steps ({@link PallPass}): neighbouring pixels have distinct uniformly distributed offsets,
 * without white-noise clusters — temporal accumulation removes grain sooner, and the residual is fine and
 * blotch-free. Ulichney's void-and-cluster (1993) on a torus: each pixel has a distinct rank 0..N−1 (uniform), with higher
 * ranks placed in the largest voids.
 */
public final class BlueNoise {
    /** Tile side. */
    public static final int SIZE = 64;
    /** Gaussian repulsion width, pixels (Ulichney — 1.5). */
    private static final double SIGMA = 1.5;

    private BlueNoise() {}

    /** Tile {@code size}×{@code size}: byte per pixel, rank × 256 / size² (each value equally frequent). */
    public static byte[] generate(int size, long seed) {
        int n = size * size;
        double[] kernel = new double[n];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int dx = Math.min(x, size - x), dy = Math.min(y, size - y);
                kernel[y * size + x] = Math.exp(-(dx * dx + dy * dy) / (2 * SIGMA * SIGMA));
            }
        }
        boolean[] on = new boolean[n];
        double[] energy = new double[n];
        Random random = new Random(seed);
        int ones = 0;
        // initial pattern: one tenth of pixels random, then move clusters into voids until the pattern stabilizes
        while (ones < n / 10) {
            int p = random.nextInt(n);
            if (on[p]) continue;
            on[p] = true;
            ones += 1;
            splat(energy, kernel, size, p, 1);
        }
        for (int guard = 0; guard < 4 * n; guard++) {
            int cluster = extreme(energy, on, true, true);
            on[cluster] = false;
            splat(energy, kernel, size, cluster, -1);
            int voidAt = extreme(energy, on, false, false);
            on[voidAt] = true;
            splat(energy, kernel, size, voidAt, 1);
            if (voidAt == cluster) break;
        }
        int[] rank = new int[n];
        // initial pattern ranks: remove the tightest clusters; rank is the remaining count
        boolean[] work = on.clone();
        double[] e = energy.clone();
        for (int r = ones - 1; r >= 0; r--) {
            int cluster = extreme(e, work, true, true);
            work[cluster] = false;
            splat(e, kernel, size, cluster, -1);
            rank[cluster] = r;
        }
        // then into the largest voids (the tightest zero cluster is the largest void among ones)
        for (int r = ones; r < n; r++) {
            int voidAt = extreme(energy, on, false, false);
            on[voidAt] = true;
            splat(energy, kernel, size, voidAt, 1);
            rank[voidAt] = r;
        }
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) ((long) rank[i] * 256 / n);
        return out;
    }

    /** Add a kernel centred at p (signed) to the entire energy map, on a torus. */
    private static void splat(double[] energy, double[] kernel, int size, int p, int sign) {
        int px = p % size, py = p / size;
        for (int y = 0; y < size; y++) {
            int ky = Math.floorMod(y - py, size) * size;
            for (int x = 0; x < size; x++) energy[y * size + x] += sign * kernel[ky + Math.floorMod(x - px, size)];
        }
    }

    /** Pixel with highest (max) or lowest energy among those with {@code on[i] == state}. */
    private static int extreme(double[] energy, boolean[] on, boolean state, boolean max) {
        int best = -1;
        for (int i = 0; i < energy.length; i++) {
            if (on[i] != state) continue;
            if (best < 0 || (max ? energy[i] > energy[best] : energy[i] < energy[best])) best = i;
        }
        return best;
    }
}
