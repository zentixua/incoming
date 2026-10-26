package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Light through the shock wave front ({@link ShockOptics}): thin shell versus direct integral, growth with pressure, edge. */
class ShockOpticsTest {
    private static final double N0 = 2.77e-4;

    /** Path of one intersection by direct integral: ∫ δn·e^(−(R − r)/L) ds along half chord, r = √(b² + s²). */
    private static double integral(double b, double r, double l, double dn) {
        if (b >= r) return 0;
        double end = Math.sqrt(r * r - b * b);
        // shell - at the end of the chord: steps are thicker there
        int n = 400_000;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double u0 = (double) i / n, u1 = (double) (i + 1) / n;
            double s0 = end * (1 - (1 - u0) * (1 - u0)), s1 = end * (1 - (1 - u1) * (1 - u1)), s = 0.5 * (s0 + s1);
            sum += dn * Math.exp(-(r - Math.sqrt(b * b + s * s)) / l) * (s1 - s0);
        }
        return sum;
    }

    /** Approximation of a thin shell: error is of the order of L/R (from r·dr R is taken, from r² − b² (R − r)² is discarded). */
    @Test
    void thinShellPathIsTheIntegral() {
        for (double l : new double[]{5, 20, 100}) {
            double r = 2000, dn = 6e-5, top = dn * Math.sqrt(2 * r * l) * 0.5410442;
            for (double k : new double[]{0, 0.5, 0.9, 0.98, 0.99, 0.995, 0.999, 0.9999, 1}) {
                double b = k * r;
                assertEquals(integral(b, r, l, dn), ShockOptics.path(b, r, l, dn), (0.01 + 1.5 * l / r) * top, "L = " + l + ", b = " + b);
            }
        }
    }

    @Test
    void dawsonIsItsIntegral() {
        for (double x = 0; x <= 12; x += 0.05) {
            int n = 20_000;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                double t = (i + 0.5) / n * x;
                sum += Math.exp(t * t - x * x) * x / n;
            }
            assertEquals(sum, ShockOptics.dawson(x), 2e-5 * Math.max(sum, 1e-3), "x = " + x);
        }
        // for 10 - asymptote, without a jump at the seam
        assertEquals(ShockOptics.dawson(10 - 1e-9), ShockOptics.dawson(10 + 1e-9), 1e-5 * ShockOptics.dawson(10));
    }

    @Test
    void densityJumpIsRankineHugoniot() {
        assertEquals(1.048, HobBlast.densityRatio(1000 * BlastModel.kpa(1)), 0.001);
        assertEquals(1.232, HobBlast.densityRatio(1000 * BlastModel.kpa(5)), 0.002);
        assertEquals(1.443, HobBlast.densityRatio(1000 * BlastModel.kpa(10)), 0.003);
        // without pressure there is no jump (for γ = 1,402 table HobBlast - 2·10⁻⁵ compression: 5·10⁻⁹ indicator)
        assertEquals(0, ShockOptics.indexJump(0, N0), 1e-8);
    }

    /** The refractive power increases with the pressure difference for the same geometry, at any place near the edge and inside. */
    @Test
    void bendGrowsWithPressure() {
        double r = 2000, l = 20;
        for (double w : new double[]{0.05, 2, 30}) {
            for (double k : new double[]{0.9, 0.99, 0.999, 0.99995}) {
                double was = 0;
                for (double kpa = 0.5; kpa < 5000; kpa *= 1.2) {
                    double bend = ShockOptics.bend(k * r, r, l, ShockOptics.indexJump(kpa, N0), w);
                    assertTrue(Math.abs(bend) >= was - 1e-15, "b = " + k * r + ", w = " + w + ", " + kpa + " kPa");
                    was = Math.abs(bend);
                }
                assertTrue(was > 0);
            }
        }
    }

    /** Behind the front (the ray is past the sphere and the entire pixel is past) - nothing; at the edge - towards the middle. */
    @Test
    void nothingBeyondTheFront() {
        double r = 2000, l = 20, dn = ShockOptics.indexJump(BlastModel.kpa(5), N0);
        for (double w : new double[]{0.01, 1, 10}) {
            assertEquals(0, ShockOptics.bend(r + 0.5 * w, r, l, dn, w));
            assertEquals(0, ShockOptics.bend(r + 100, r, l, dn, w));
            assertTrue(ShockOptics.bend(r - 0.5 * w, r, l, dn, w) > 0);
        }
    }

    /** The upper estimate of {@link ShockOptics#most} is not less than the deviation of any pixel (by two intersections). */
    @Test
    void mostBoundsEveryPixel() {
        double r = 3000, pixel = 1e-3;
        for (double kpa : new double[]{2, 30, 300}) {
            double dn = ShockOptics.indexJump(kpa, N0);
            for (double l : new double[]{2, 40, 600}) {
                for (double near : new double[]{0.5, 20, 900}) {
                    double most = ShockOptics.most(r, dn, near, pixel), w = pixel * near;
                    for (double u = 0; u < 3 * l + 3 * w; u += Math.max(w, l) / 50) {
                        double bend = 2 * Math.abs(ShockOptics.bend(r - u, r, l, dn, w));
                        assertTrue(bend <= most * (1 + 1e-9), kpa + " kPa, L " + l + ", w " + w + ", u " + u + ": " + bend + " > " + most);
                    }
                }
            }
        }
    }

    /** Example: 15 kt, front 5 psi in 2 km, eye in 3 km, pixel 1 mrad - ring in pixels. */
    @Test
    void frontAtFivePsiIsSeenAsPixels() {
        double r = 2000, dn = ShockOptics.indexJump(BlastModel.kpa(5), N0), w = 1e-3 * Math.sqrt(3000 * 3000 - r * r);
        double px = 2 * ShockOptics.bend(r - 0.5 * w, r, 20, dn, w) / 1e-3;
        assertTrue(px > 2 && px < 10, "edge shift " + px + " px");
    }
}
