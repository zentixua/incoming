package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Noise pall: tiles without seams, channels are uniform, billows shape on average - 1; blue noise without clots. */
class PallNoiseTest {
    private static final int N = 32;
    private static final byte[] VOLUME = VolumeNoise.generate(N, 0x9A11L);

    private static double at(int x, int y, int z, int channel) {
        return (VOLUME[VolumeNoise.CHANNELS * ((Math.floorMod(z, N) * N + Math.floorMod(y, N)) * N + Math.floorMod(x, N)) + channel] & 255) / 255.0;
    }

    /** Across the edge of the tile, neighboring voxels differ no more than inside them: a repeat of the seam texture does not result. */
    @Test
    void tileHasNoSeams() {
        for (int c = 0; c < VolumeNoise.CHANNELS; c++) {
            double inside = 0, seam = 0;
            for (int z = 0; z < N; z++) {
                for (int y = 0; y < N; y++) {
                    inside += Math.abs(at(N / 2, y, z, c) - at(N / 2 - 1, y, z, c));
                    seam += Math.abs(at(0, y, z, c) - at(N - 1, y, z, c));
                }
            }
            assertTrue(seam < 1.6 * inside + 1e-9, "channel " + c + ": seam " + seam + " against " + inside);
        }
    }

    /** Channels - by rank: uniform, average - half. */
    @Test
    void channelsAreUniform() {
        for (int c = 0; c < VolumeNoise.CHANNELS; c++) {
            int[] bins = new int[8];
            double sum = 0;
            for (int i = 0; i < N * N * N; i++) {
                double v = (VOLUME[VolumeNoise.CHANNELS * i + c] & 255) / 256.0;
                sum += v;
                bins[(int) (v * 8)]++;
            }
            assertEquals(0.5, sum / (N * N * N), 0.01);
            for (int b : bins) assertEquals(N * N * N / 8.0, b, N * N * N / 8.0 * 0.02);
        }
    }

    /**
     * The average shape of billows on a tile is 1 with any continuity (the thickness of the pall on average is the density of the field), the edge is in shreds
     *  with gaps, the middle is almost solid.
     */
    @Test
    void shapeKeepsMeanDensity() {
        for (double fill = 0; fill <= 1; fill += 0.25) {
            double sum = 0, empty = 0;
            int n = N * N * N;
            for (int i = 0; i < n; i++) {
                int j = (int) (((long) i * 7919 + 12345) % n);
                double s = VolumeNoise.shape((VOLUME[VolumeNoise.CHANNELS * i] & 255) / 255.0, (VOLUME[VolumeNoise.CHANNELS * j + 1] & 255) / 255.0, fill);
                sum += s;
                if (s < 0.05) empty++;
            }
            assertEquals(1, sum / n, 0.04, "continuity " + fill);
            if (fill == 0) assertTrue(empty / n > 0.45, "edge without gaps");
            if (fill == 1) assertTrue(empty / n < 0.2, "the middle is in holes");
        }
    }

    /**
     * Small billows ({@link VolumeNoise#detail}) on average - 1, and a cascade of “large × small” (small ones - tiles
     *  are four times smaller, like {@code coarseAt} and {@code fineAt} in {@code fx_pall.glsl}) - too: density, and therefore visibility, - fields
     * veil.
     */
    @Test
    void detailKeepsMeanDensity() {
        int n = N * N * N;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += VolumeNoise.detail((VOLUME[VolumeNoise.CHANNELS * i] & 255) / 255.0);
        assertEquals(1, sum / n, 0.01);
        double cascade = 0;
        for (int i = 0; i < n; i++) cascade += density(i % N + 0.5, i / N % N + 0.5, i / (N * N) + 0.5, 1, true);
        assertEquals(1, cascade / n, 0.04);
    }

    /**
     * Visibility in the thickest dust (according to the table - 40 blocks): direct rays through the billows pall (tiles 256 blocks in 15 kt,
     *  solid dust) transmit on average more than even dust of the same average density - you can see further through the gaps,
     *  to 70–100 blocks. Small billows inside large ones hardly change it (by less than a tenth): they are smaller than the ray
     *  and are averaged over it.
     */
    @Test
    void denseDustKeepsItsVisibility() {
        double coarse = visibility(false), cascade = visibility(true);
        assertTrue(coarse > 60 && coarse < 100, "large bills: visibility " + coarse);
        assertEquals(coarse, cascade, 0.1 * coarse, "with small billows");
    }

    /** Visibility (blocks) based on average ray transmission over 40 blocks in the thickest dust: 3,912 · 40 / −ln⟨T⟩. */
    private static double visibility(boolean fine) {
        double sigma = 3.912 / 40, tile = 256, length = 40;
        java.util.Random random = new java.util.Random(7);
        double sum = 0;
        int rays = 3000, steps = 60;
        for (int r = 0; r < rays; r++) {
            double x = random.nextDouble() * N, y = random.nextDouble() * N, z = random.nextDouble() * N;
            double a = random.nextDouble() * 2 * Math.PI, b = Math.acos(2 * random.nextDouble() - 1);
            double dx = Math.sin(b) * Math.cos(a), dy = Math.cos(b), dz = Math.sin(b) * Math.sin(a);
            double tau = 0, ds = length / steps, voxel = N / tile;
            for (int k = 0; k < steps; k++) {
                double t = (k + 0.5) * ds * voxel;
                tau += sigma * density(x + dx * t, y + dy * t, z + dz * t, 1, fine) * ds;
            }
            sum += Math.exp(-tau);
        }
        return length * 3.912 / -Math.log(sum / rays);
    }

    /** Density of the cascade at a tile point (in voxels): large billows with edges are three times more frequent and small ones are four times more frequent. */
    private static double density(double x, double y, double z, double fill, boolean fine) {
        double shape = VolumeNoise.shape(trilinear(x, y, z, 0), trilinear(3.1 * x + 11.8, 3.1 * y + 3.5, 3.1 * z + 22.7, 1), fill);
        return fine ? shape * VolumeNoise.detail(trilinear(4 * x + 17, 4 * y + 9.3, 4 * z + 5.4, 0)) : shape;
    }

    private static double trilinear(double x, double y, double z, int c) {
        x -= 0.5;
        y -= 0.5;
        z -= 0.5;
        int i = (int) Math.floor(x), j = (int) Math.floor(y), k = (int) Math.floor(z);
        double fx = x - i, fy = y - j, fz = z - k, v = 0;
        for (int d = 0; d < 8; d++) {
            int a = d & 1, b = d >> 1 & 1, e = d >> 2 & 1;
            v += at(i + a, j + b, k + e, c) * (a == 1 ? fx : 1 - fx) * (b == 1 ? fy : 1 - fy) * (e == 1 ? fz : 1 - fz);
        }
        return v;
    }

    /** Blue noise: each value is equally frequent, the average across blocks 4×4 is three times smoother than white noise. */
    @Test
    void blueNoiseIsEvenAndUnclumped() {
        int n = BlueNoise.SIZE;
        byte[] b = BlueNoise.generate(n, 0xB1E5L);
        int[] count = new int[256];
        for (byte v : b) count[v & 255]++;
        for (int c : count) assertEquals(n * n / 256, c);
        double var = 0;
        int blocks = 0;
        for (int by = 0; by < n / 4; by++) {
            for (int bx = 0; bx < n / 4; bx++) {
                double m = 0;
                for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) m += b[(by * 4 + y) * n + bx * 4 + x] & 255;
                m /= 16;
                var += (m - 127.5) * (m - 127.5);
                blocks++;
            }
        }
        double white = 255 / Math.sqrt(12) / 4;
        assertTrue(Math.sqrt(var / blocks) < white / 2, "spread " + Math.sqrt(var / blocks) + " against white " + white);
    }
}
