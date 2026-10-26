package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.MushroomModel;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fireball surface pattern ({@code textures/fx/nukeball.bin}, {@code tools/bake_fx}): what baking and
 *  shader promise - holds the file.
 */
class NukeBallSurfaceTest {
    private static NukeBallSurface.Data read() {
        try (InputStream in = NukeBallSurfaceTest.class.getResourceAsStream("/assets/incoming/textures/fx/nukeball.bin")) {
            assertNotNull(in, "nukeball.bin");
            return NukeBallSurface.parse(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Direction of the mid-texel (u, v) of the octahedral map (as {@code octa_decode} to {@code pack.py}). */
    private static double[] decode(double u, double v) {
        double px = 2 * u - 1, pz = 2 * v - 1, y = 1 - Math.abs(px) - Math.abs(pz), x = px, z = pz;
        if (y < 0) {
            x = (1 - Math.abs(pz)) * Math.signum(px);
            z = (1 - Math.abs(px)) * Math.signum(pz);
        }
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    /** Texel solid angle - in the direction of the middle: dΩ ∝ |n|³_1 / |n|³_2 (reverse to the mapping to the octahedron). */
    private static double weight(double[] n) {
        double l1 = Math.abs(n[0]) + Math.abs(n[1]) + Math.abs(n[2]);
        return l1 * l1 * l1;
    }

    /** The frames are on the way up from 0 straight up and are grabbed to roll into a ring at the game yields. */
    @Test
    void framesCoverTheRiseUntilTheRingRollsUp() {
        NukeBallSurface.Data d = read();
        assertEquals(0, d.rises()[0], 1e-6);
        for (int i = 1; i < d.rises().length; i++) assertTrue(d.rises()[i] > d.rises()[i - 1], "frame " + i);
        for (double y : new double[]{1, 15}) {
            for (boolean surface : new boolean[]{false, true}) {
                double end = FireballModel.surfaceRise(MushroomModel.rollUpSeconds(y, surface), surface ? 0 : 400, y, surface);
                assertTrue(end <= d.rises()[d.rises().length - 1], y + " kt: " + end);
            }
        }
        double last = -1;
        for (double r = -1; r < 6; r += 0.01) {
            double f = NukeBallSurface.frame(d.rises(), r);
            assertTrue(f >= last, "the frame location does not decrease");
            last = f;
        }
    }

    /**
     * A sphere with a pattern shines with the power of the model: the average p⁴ over the sphere in each frame is 1 (Stefan–Boltzmann); pattern - within the
     *  file; in the first frame - clumps of charge vapor with a temperature spread of 3–6,5 % (estimated from the image Trinity 25 ms:
     * fingerprint ∝ L or ∝ L^(1/2,2)).
     */
    @Test
    void patternKeepsTheModelPower() {
        NukeBallSurface.Data d = read();
        int n = d.side();
        for (int k = 0; k < d.rises().length; k++) {
            double sum = 0, sum4 = 0, sum1 = 0, sum2 = 0;
            for (int v = 0; v < n; v++) {
                for (int u = 0; u < n; u++) {
                    double w = weight(decode((u + 0.5) / n, (v + 0.5) / n)), p = d.at(k, u, v);
                    assertTrue(p >= 0 && p < NukeBallSurface.P_MAX * 0.999, "pattern within the file: " + p);
                    sum += w;
                    sum4 += w * p * p * p * p;
                    sum1 += w * p;
                    sum2 += w * p * p;
                }
            }
            assertEquals(1, sum4 / sum, 0.02, "frame " + k);
            if (k == 0) {
                double mean = sum1 / sum, std = Math.sqrt(sum2 / sum - mean * mean) / mean;
                assertTrue(std > 0.03 && std < 0.065, "scattering of clots " + std);
            }
        }
    }

    /** Octahedral map: the direction of the middle of the texel falls into its texel (u, v - as in the shader). */
    @Test
    void octahedralMapRoundTrips() {
        int n = 64;
        for (int v = 0; v < n; v++) {
            for (int u = 0; u < n; u++) {
                double[] d = decode((u + 0.5) / n, (v + 0.5) / n);
                assertEquals((u + 0.5) / n, NukeBallSurface.octU(d[0], d[1], d[2]), 1e-9, u + "," + v);
                assertEquals((v + 0.5) / n, NukeBallSurface.octV(d[0], d[1], d[2]), 1e-9, u + "," + v);
            }
        }
    }
}
