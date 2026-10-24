package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FootColumnTest {
    /** A separate midpoint integral of the density used to render the foot, including an arriving front inside the carpet. */
    @Test
    void closedPrimitiveMatchesPositiveDensityIntegral() {
        for (double rise : new double[]{0, 0.5, 0.99, 1, 1.001, 1.05, 1.2, 1.5, 1.99, 2, 3, 5}) {
            for (double merge : new double[]{0.4, 1, 2.5, 4}) {
                double top = 320, sigma = 0.03, end = merge * top;
                FootColumn column = new FootColumn(top, sigma, 0.8, rise * top, end);
                double sum = 0, step = end / 20_000;
                for (int i = 19_999; i >= 0; i--) {
                    sum += step * MushroomField.footDensity((i + 0.5) * step, top, sigma, 0.8, rise * top);
                    if (i % 200 == 0) assertEquals(sum, column.above(i * step), 2e-6 * sigma * top,
                            "rise=" + rise + ", merge=" + merge + ", h=" + i * step);
                }
                assertEquals(0, column.above(end), 1e-12);
            }
        }
        assertEquals(0, new FootColumn(320, 0.03, 0, 1000, 900).above(0));
    }

    @Test
    void packedColumnIsMonotoneAndResolvesItsHeightVariation() {
        for (double top : new double[]{0.5, 320, 800}) {
            double merge = 4 * Math.max(top, 1), sigma = 0.03;
            FootColumn column = new FootColumn(top, sigma, 0.8, 3.1 * Math.max(top, 1), merge);
            float[] packed = new float[FootColumn.NODES];
            column.pack(merge, packed, 0);
            for (int i = 1; i < packed.length; i++) assertTrue(packed[i] <= packed[i - 1] && packed[i] >= 0);
            for (int i = 0; i < 1000; i++) {
                double h = merge * i / 1000, u = h / merge * (FootColumn.NODES - 1);
                int a = Math.min((int) u, FootColumn.NODES - 2);
                double sampled = packed[a] + (packed[a + 1] - packed[a]) * (u - a);
                assertEquals(column.above(h), sampled, 0.002 * sigma * Math.max(top, 1));
            }
        }
    }
}
