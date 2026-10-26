package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldNoiseTest {
    /** The field in volume is also about N(0, 1): the spread of 3D noise is within a third of flat (enough for burning spots). */
    @Test
    void volumeRoughlyNormalised() {
        WorldNoise n = new WorldNoise(12345L, 1);
        double sum = 0, sq = 0;
        int count = 0;
        for (int x = -6000; x < 6000; x += 53) {
            for (int y = -64; y < 320; y += 29) {
                for (int z = -6000; z < 6000; z += 59) {
                    double v = n.at(x, y, z, 6);
                    sum += v;
                    sq += v * v;
                    count++;
                }
            }
        }
        double mean = sum / count, std = Math.sqrt(sq / count - mean * mean);
        assertEquals(0, mean, 0.1, "average");
        assertEquals(1, std, 0.35, "spread");
    }

    /** The field is reduced to N(0, 1): average and spread over a large area, tails - like a normal value. */
    @Test
    void normalised() {
        WorldNoise n = new WorldNoise(12345L, 1);
        double sum = 0, sq = 0;
        int count = 0, beyond1 = 0;
        for (int x = -20000; x < 20000; x += 37) {
            for (int z = -20000; z < 20000; z += 41) {
                double v = n.at(x, z, 0);
                sum += v;
                sq += v * v;
                if (Math.abs(v) > 1) beyond1++;
                count++;
            }
        }
        double mean = sum / count, std = Math.sqrt(sq / count - mean * mean);
        assertEquals(0, mean, 0.05, "average");
        assertEquals(1, std, 0.05, "spread");
        // at N(0, 1) beyond ±1 - 31.7 %
        assertEquals(0.317, beyond1 / (double) count, 0.06, "tails");
    }

    /** Neighboring places are almost identical (spots), distant places are independent; another seed and another layer - another field. */
    @Test
    void patchesAndIndependence() {
        WorldNoise a = new WorldNoise(1L, 1), b = new WorldNoise(2L, 1);
        double near = 0, far = 0, seeds = 0, layers = 0;
        int count = 0;
        for (int i = 0; i < 4000; i++) {
            double x = i * 997.0 % 50000, z = i * 613.0 % 50000;
            double v = a.at(x, z, 0);
            near += v * a.at(x + 1, z, 0);
            far += v * a.at(x + 400, z + 300, 0);
            seeds += v * b.at(x, z, 0);
            layers += v * a.at(x, z, 1);
            count++;
        }
        assertTrue(near / count > 0.9, "neighbors " + near / count);
        assertTrue(Math.abs(far / count) < 0.1, "distant " + far / count);
        assertTrue(Math.abs(seeds / count) < 0.1, "seed " + seeds / count);
        assertTrue(Math.abs(layers / count) < 0.1, "layers " + layers / count);
    }

    /** Model scale: with 0.5 block per meter, the same spots are half as large in blocks. */
    @Test
    void scaleInBlocks() {
        WorldNoise m = new WorldNoise(5L, 1), half = new WorldNoise(5L, 0.5);
        assertEquals(m.at(100, 200, 0), half.at(50, 100, 0), 1e-9);
    }
}
