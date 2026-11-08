package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraterGroundTest {
    /**
     * A city on a slope: most chunks are dominated by buildings 10–60 blocks tall, a few are streets and yards. The plane
     * follows the slope under the city; the buildings do not lift it (Zearth 08.11.2026: the bowl was cut from roof
     * gardens 57 blocks above the ground).
     */
    @Test
    void groundUnderACityKeepsTheSlope() {
        Random random = new Random(5);
        int n = 200;
        double[] x = new double[n], z = new double[n], h = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = random.nextDouble() * 300 - 150;
            z[i] = random.nextDouble() * 300 - 150;
            double ground = 64 + 0.1 * x[i] - 0.05 * z[i] + random.nextDouble();
            // two thirds of the chunks have no street at their low tenth: their sample is a roof
            h[i] = i % 3 == 0 ? ground : ground + 10 + random.nextDouble() * 50;
        }
        double[] p = CraterGround.fit(x, z, h, n);
        assertEquals(0.1, p[0], 0.01, "slope along x");
        assertEquals(-0.05, p[1], 0.01, "slope along z");
        assertEquals(64.5, p[2], 1, "height at the epicentre");
    }

    @Test
    void fewSamplesGiveALevelPlane() {
        double[] p = CraterGround.fit(new double[] {0, 5}, new double[] {0, 5}, new double[] {70, 64}, 2);
        assertEquals(0, p[0], 0);
        assertEquals(0, p[1], 0);
        assertEquals(64, p[2], 0, "lowest median of two");
        assertEquals(64, CraterGround.low(new int[] {90, 63, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64}, 13), 0, "one ditch is not the ground");
    }

    /** The edge is irregular around the crater: no repeating lobes like the former {@code sin 7θ}, within ±12 % (the former was ±8 %). */
    @Test
    void edgeIsIrregular() {
        double phase = 11;
        int m = 3600;
        double sum = 0, sq = 0, max = 0, sevenfold = 0;
        for (int i = 0; i < m; i++) {
            double t = 2 * Math.PI * i / m, e = CraterGround.edge(t, phase) - 1;
            sum += e;
            sq += e * e;
            max = Math.max(max, Math.abs(e));
            sevenfold += Math.abs(e - (CraterGround.edge(t + 2 * Math.PI / 7, phase) - 1));
        }
        assertEquals(0, sum / m, 1e-3, "mean radius kept");
        assertEquals(CraterGround.EDGE_RMS, Math.sqrt(sq / m), 0.002);
        assertTrue(max < 0.12, "edge within 12 %: " + max);
        assertTrue(sevenfold / m > CraterGround.EDGE_RMS, "repeats every seventh of a turn");
        assertTrue(Math.abs(CraterGround.edge(1, 3) - CraterGround.edge(1, 4)) > 1e-4, "same edge for different craters");
        assertEquals(CraterGround.edge(1, 3), CraterGround.edge(1, 3), 0, "edge not deterministic");
    }
}
