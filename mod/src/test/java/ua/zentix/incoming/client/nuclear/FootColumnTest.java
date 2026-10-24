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
            float[] packed = new float[FootColumn.NODES + FootColumn.EDGE_NODES];
            column.pack(merge, packed, 0);
            for (int i = 1; i < FootColumn.NODES; i++) assertTrue(packed[i] <= packed[i - 1] && packed[i] >= 0);
            for (int i = 0; i < 1000; i++) {
                double h = merge * i / 1000, u = h / merge * (FootColumn.NODES - 1);
                int a = Math.min((int) u, FootColumn.NODES - 2);
                double sampled = packed[a] + (packed[a + 1] - packed[a]) * (u - a);
                assertEquals(column.above(h), sampled, 0.002 * sigma * Math.max(top, 1));
            }
        }
    }

    @Test
    void narrowingBandLimitsBothOverburdenAndTotalColumnOnEachRadius() {
        double top = 320, sigma = 0.03, share = 0.8;
        for (double rise : new double[]{0.8, 1.2, 2, 5}) {
            for (double merge : new double[]{0.5, 2.5, 4}) {
                double end = merge * top;
                FootColumn column = new FootColumn(top, sigma, share, rise * top, end);
                for (int radius = 0; radius <= 20; radius++) {
                    double offset = share * top * radius / 20, sum = 0, step = end / 20_000;
                    for (int i = 19_999; i >= 0; i--) {
                        double h = (i + 0.5) * step;
                        if (offset < MushroomField.footBand(h, top, share, end)) {
                            sum += step * MushroomField.footDensity(h, top, sigma, share, rise * top);
                        }
                        if (i % 2000 == 0) assertEquals(sum, column.above(i * step, offset), 0.0003 * sigma * top);
                    }
                    assertEquals(sum, column.above(0, offset), 0.0003 * sigma * top, "total slab at this radius");
                }
                for (int i = 0; i <= 100; i++) {
                    double h = end * i / 100, surface = MushroomField.footBand(h, top, share, end);
                    assertEquals(0, column.above(h, surface), 1e-10 * sigma * top, "no phantom dust above the surface");
                    double previous = column.above(h, 0);
                    for (int j = 1; j <= 20; j++) {
                        double next = column.above(h, share * top * j / 20);
                        assertTrue(next <= previous, "overburden falls with distance from the wall");
                        previous = next;
                    }
                }
            }
        }
    }

    @Test
    void packedTablesLeaveTheSurfaceUnshadowed() {
        for (double top : new double[]{0.5, 320, 800}) {
            for (double mergeRatio : new double[]{0.5, 2.5, 4}) {
                double t = Math.max(top, 1), merge = mergeRatio * t, sigma = 0.03, share = 0.8;
                FootColumn column = new FootColumn(top, sigma, share, 3.1 * t, merge);
                float[] packed = new float[FootColumn.NODES + FootColumn.EDGE_NODES];
                column.pack(merge, packed, 0);
                for (int i = 1; i < FootColumn.EDGE_NODES; i++) {
                    assertTrue(packed[FootColumn.NODES + i] >= packed[FootColumn.NODES + i - 1]);
                }
                for (int i = 0; i <= 1000; i++) {
                    double h = merge * i / 1000;
                    double radius = MushroomField.footBand(h, t, share, merge) / (share * t);
                    double up = Math.max(sample(packed, 0, FootColumn.NODES, h / merge)
                            - sample(packed, FootColumn.NODES, FootColumn.EDGE_NODES, radius), 0);
                    assertEquals(0, up, 0.004 * sigma * t, "interpolated surface overburden, h=" + h);
                }
            }
        }
    }

    private static double sample(float[] packed, int start, int nodes, double fraction) {
        double u = Math.max(0, Math.min(fraction, 1)) * (nodes - 1);
        int i = Math.min((int) u, nodes - 2);
        return packed[start + i] + (packed[start + i + 1] - packed[start + i]) * (u - i);
    }
}
