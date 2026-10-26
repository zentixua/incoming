package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Water explosion: column from the Swisdak table (charge and depth), ballistic jets, falling curtain, spray, foam,
 * and water wave.
 */
class WaterBlastTest {
    private static final double DEEP = Double.POSITIVE_INFINITY;

    /** Spray top (center of the highest billow) at age t, m. */
    private static double topBlob(WaterBlast w, double t) {
        double[] top = {0};
        w.blobs(t, (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> top[0] = Math.max(top[0], y), 0, 0, 0);
        return top[0];
    }

    /**
     * Jet billows at age t (excluding the flat spray ring), as points: each jet segment is 64 cross-section Gaussians
     * along its axis; position, σh, σv, extinction (m²).
     */
    private static List<double[]> spray(WaterBlast w, double t) {
        List<double[]> p = new ArrayList<>();
        w.blobs(t, (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> {
            // jets are round segments; the flat spray ring follows its own law (base-surge radius)
            if (sh != sv) return;
            int n = 64;
            for (int i = 0; i < n; i++) {
                double f = (i + 0.5) / n * 2 - 1;
                p.add(new double[]{x + ax * f, y + ay * f, z + az * f, sh, sv, CloudModel.DROP_K * water / n});
            }
        }, 0, 0, 0);
        return p;
    }

    /** Spray optical depth along a horizontal z ray at height y, offset x from the axis (Gaussian integral along the ray). */
    private static double sight(List<double[]> p, double x, double y) {
        double tau = 0;
        for (double[] q : p) {
            double dx = x - q[0], dy = y - q[1];
            tau += q[5] / (2 * Math.PI * q[3] * q[4]) * Math.exp(-dx * dx / (2 * q[3] * q[3]) - dy * dy / (2 * q[4] * q[4]));
        }
        return tau;
    }

    /** The original spatial grid, including its floating-point step accumulation. */
    private static double[] grid(double end, double step) {
        List<Double> points = new ArrayList<>();
        for (double p = 0; p < end; p += step) points.add(p);
        return points.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * The same 64-point quadrature as sight(), with exp(-dx²/2σh²) cached for the whole x grid. Points at the same
     * height and vertical width share their y factor, including jets at different azimuths. All terms are positive:
     * the sum of each group's maximum to the right bounds every ray there, so it can safely skip invisible rows.
     */
    private static final class SightGrid {
        private record Vertical(double y, double sigma) {}

        private final double[] x;
        private final Vertical[] groups;
        private final double[][] horizontal, maximum;

        SightGrid(List<double[]> points, double[] x) {
            this.x = x;
            Map<Vertical, double[]> sums = new LinkedHashMap<>();
            for (double[] q : points) {
                double[] row = sums.computeIfAbsent(new Vertical(q[1], q[4]), key -> new double[x.length]);
                double weight = q[5] / (2 * Math.PI * q[3] * q[4]);
                for (int i = 0; i < x.length; i++) {
                    double dx = x[i] - q[0];
                    row[i] += weight * Math.exp(-dx * dx / (2 * q[3] * q[3]));
                }
            }
            groups = sums.keySet().toArray(Vertical[]::new);
            horizontal = sums.values().toArray(double[][]::new);
            maximum = new double[groups.length][x.length];
            for (int g = 0; g < groups.length; g++) {
                double high = 0;
                for (int i = x.length - 1; i >= 0; i--) maximum[g][i] = high = Math.max(high, horizontal[g][i]);
            }
        }

        double[] vertical(double y) {
            double[] weights = new double[groups.length];
            for (int g = 0; g < groups.length; g++) {
                double dy = y - groups[g].y();
                weights[g] = Math.exp(-dy * dy / (2 * groups[g].sigma() * groups[g].sigma()));
            }
            return weights;
        }

        double sight(int i, double[] vertical) {
            double tau = 0;
            for (int g = 0; g < groups.length; g++) tau += horizontal[g][i] * vertical[g];
            return tau;
        }

        int rightmost(double y, int first) {
            if (first == x.length) return -1;
            double[] weights = vertical(y);
            double bound = 0;
            for (int g = 0; g < groups.length; g++) bound += maximum[g][first] * weights[g];
            if (bound < CloudModel.SEEN) return -1;
            // A mixture can have separate lobes: scan from the outside, never assume monotonicity for a bisection.
            for (int i = x.length - 1; i >= first; i--) {
                if (sight(i, weights) >= CloudModel.SEEN) return i;
            }
            return -1;
        }
    }

    /** Check the cached, factored sum against the old independent ray integral, including faint edges and landing. */
    @Test
    void cachedSightMatchesTheDirectQuadrature() {
        for (WeaponType wt : WeaponType.values()) {
            if (wt.spec().charge() <= 0) continue;
            WaterBlast w = new WaterBlast(wt.spec().charge(), 0, DEEP, CloudModel.Air.STANDARD);
            double[] x = {0, 0.5 * w.radius, 0.95 * w.radius, w.radius, 1.5 * w.radius};
            for (double age : new double[]{0.025, 0.5, 1, 1.5, 1.975, 2}) {
                List<double[]> points = spray(w, age * w.rise);
                SightGrid cached = new SightGrid(points, x);
                for (double y : new double[]{0, 0.1 * w.height, 0.5 * w.height, 0.98 * w.height, w.height, 1.5 * w.height}) {
                    double[] vertical = cached.vertical(y);
                    int rightmost = -1;
                    for (int i = 0; i < x.length; i++) {
                        double direct = sight(points, x[i], y);
                        assertEquals(direct, cached.sight(i, vertical), Math.max(1e-14, 1e-12 * direct), wt.name());
                        if (direct >= CloudModel.SEEN) rightmost = i;
                    }
                    assertEquals(rightmost, cached.rightmost(y, 0), wt.name());
                    assertEquals(-1, cached.rightmost(y, rightmost + 1), wt.name());
                }
            }
        }
    }

    /**
     * Covers the "water hit without a column" complaint and its opposite, "column fills the frame with a veil"
     * (final 3.7.0 pass: a Grad salvo near shore covered the frame uniformly gray): Swisdak table H and R describe
     * the visible plume, whose edge is where line-of-sight optical depth falls to {@link CloudModel#SEEN}, not jet centers.
     * Previously, centers lay at H and R, while dense jets remained visible another 3–4 σ out: the Grad column was
     * three times wider than the law and put the camera 30 m from the salvo inside it. R is the maximum radius over
     * the whole event; falling spray travels farthest (review #445: with radius based on the apex, the falling curtain
     * reached 1.41 R). Every weapon: visible top H (−3…0 %), visible jet radius over the full flight to 2·rise time
     * at any height R (−8…0 %).
     */
    @Test
    void everyWeaponThrowsAColumnOfTheLawSize() {
        for (WeaponType wt : WeaponType.values()) {
            double kg = wt.spec().charge();
            if (kg <= 0) continue;
            WaterBlast w = new WaterBlast(kg, 0, DEEP, CloudModel.Air.STANDARD);
            double law = 29.7 * Math.cbrt(kg) / 1.4, wide = 8.3 * Math.cbrt(kg) / 1.3;
            assertEquals(law, w.height, 1e-9 * law, wt.name());
            assertEquals(wide, w.radius, 1e-9 * wide, wt.name());
            double[] xs = grid(2 * wide, wide / 150), ys = grid(1.5 * law, law / 300);
            List<Double> times = new ArrayList<>();
            for (double t = w.rise / 40; t <= 2 * w.rise; t += w.rise / 40) {
                times.add(t);
            }
            // Visit the apex and outer-jet landing first, then every remaining original time sample. Finding the
            // extrema early lets subsequent rows skip rays that cannot increase either measured dimension.
            times.sort(Comparator.comparingDouble(t -> Math.min(Math.abs(t / w.rise - 1), Math.abs(t / w.rise - 2 * Math.cos(w.cone)))));
            double top = 0;
            int sideIndex = -1;
            for (double t : times) {
                SightGrid rays = new SightGrid(spray(w, t), xs);
                for (int iy = ys.length - 1; iy >= 0; iy--) {
                    double y = ys[iy];
                    int edge = rays.rightmost(y, y > top ? 0 : sideIndex + 1);
                    if (edge < 0) continue;
                    top = Math.max(top, y);
                    sideIndex = Math.max(sideIndex, edge);
                }
            }
            double side = sideIndex < 0 ? 0 : xs[sideIndex];
            assertTrue(top <= law && top >= 0.97 * law, wt.name() + ": visible top " + top + " versus " + law);
            assertTrue(side <= wide && side >= 0.92 * wide, wt.name() + ": visible radius over the flight " + side + " versus " + wide);
        }
        // Shahed order of magnitude: tens of meters (report example, 500 kg at 30 m: table height 56 m)
        WaterBlast shahed = new WaterBlast(50, 0, DEEP, CloudModel.Air.STANDARD);
        assertTrue(shahed.height > 60 && shahed.height < 100, "Shahed column " + shahed.height);
    }

    /**
     * A jet is a segment of round billows rather than a stretched Gaussian: water ejected in one direction at one
     * instant with different speeds lies on a straight line. Previously, vertical Gaussian σ reached five transverse σ
     * (to close the gaps between five speeds), and the layer drew a stretched billow: a flat stripe with straight sides
     * (salvo frames at 6 and 20 s). No stretched spray billows at any age; jets contain only water still airborne.
     */
    @Test
    void jetsAreRoundSegments() {
        for (WeaponType wt : WeaponType.values()) {
            double kg = wt.spec().charge();
            if (kg <= 0) continue;
            WaterBlast w = new WaterBlast(kg, 0, DEEP, CloudModel.Air.STANDARD);
            for (double t = 0.05; t < w.mistLife(); t *= 1.3) {
                double time = t;
                double[] water = {0};
                w.blobs(t, (x, y, z, ax, ay, az, sh, sv, soot, dust, wet, r, g, b, k, rise) -> {
                    if (sh == sv) water[0] += wet;
                    else assertTrue(sh > 3 * sv, wt.name() + " at " + time + " s: stretched spray billow σh " + sh + ", σv " + sv);
                }, 0, 0, 0);
                assertEquals(WaterBlast.asGridDrops(w.water) * w.aloft(t), water[0], 1e-6 * WaterBlast.asGridDrops(w.water), wt.name());
            }
        }
    }

    /** The table matches the report: chapter 14 example (500 kg at 30 m: radius 61.5 m, height 56 m, before margin). */
    @Test
    void tableMatchesTheReportExample() {
        double scaled = 30 / Math.pow(500, 0.25);
        assertEquals(7.75, WaterBlast.table(WaterBlast.SCALED_R, scaled), 0.03);
        assertEquals(7.05, WaterBlast.table(WaterBlast.SCALED_H, scaled), 0.05);
        WaterBlast w = new WaterBlast(500, 30, DEEP, CloudModel.Air.STANDARD);
        assertEquals(56.0 / 1.4, w.height, 0.5);
    }

    /** Charge ×8 gives column ×2 (cube-root similarity at the same scaled depth, at the surface). */
    @Test
    void sizeScalesWithTheCubeRoot() {
        WaterBlast a = new WaterBlast(50, 0, DEEP, CloudModel.Air.STANDARD), b = new WaterBlast(400, 0, DEEP, CloudModel.Air.STANDARD);
        assertEquals(2, b.height / a.height, 1e-9);
        assertEquals(2, b.radius / a.radius, 1e-9);
    }

    /** Beyond the optimum depth the column is lower: at 9 m/kg^¼, 5.9 versus 29.7 at the surface. */
    @Test
    void deepChargeThrowsLess() {
        double kg = 50, deep = 9 * Math.pow(kg, 0.25);
        assertEquals(5.9 / 29.7, new WaterBlast(kg, deep, DEEP, CloudModel.Air.STANDARD).height / new WaterBlast(kg, 0, DEEP, CloudModel.Air.STANDARD).height,
                1e-9);
    }

    /**
     * Ballistic jets: at the apex, the fast end of the outer jet is H'·sin2θ from the axis, landing at 2H'·sin2θ,
     * inside R (R with the halo); by 2·rise time, water has returned to water, with no airborne spray; slower jets land earlier.
     */
    @Test
    void jetsFlyAndFall() {
        WaterBlast w = new WaterBlast(50, 0, DEEP, CloudModel.Air.STANDARD);
        double[] wide = {0};
        // center of the outer jet segment at its apex is H'·sin2θ·(1 + slow share)/2 to the side: position is linear in speed
        w.blobs(w.rise * Math.cos(w.cone), (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> {
            if (sh == sv) wide[0] = Math.max(wide[0], Math.hypot(x + ax, z + az));
        }, 0, 0, 0);
        double apex = w.speed * w.speed * Math.sin(2 * w.cone) / (2 * 9.80665);
        assertEquals(apex, wide[0], 0.02 * w.radius, "fast end of the outer jet at its apex: H'·sin2θ");
        assertEquals(1, w.aloft(0.5 * w.rise), 0);
        assertTrue(w.aloft(1.2 * w.rise) < 1 && w.aloft(1.2 * w.rise) > 0, "the curtain falls in parts");
        assertEquals(0, w.aloft(2 * w.rise + 1e-6), 0);
        assertTrue(topBlob(w, 2 * w.rise + 1e-6) < 0.1 * w.height, "no column: water has landed, only spray remains above water");
        // water lands up to 2H'·sin2θ from the center: inside R, reaching R with the falling-jet halo
        assertEquals(2 * apex, w.fallen(2 * w.rise), 0.02 * w.radius);
        assertTrue(w.fallen(2 * w.rise) < w.radius && w.fallen(2 * w.rise) > 0.4 * w.radius, "centers land at " + w.fallen(2 * w.rise));
    }

    /** Spray: base-surge ring after landing, visible for seconds to tens of seconds, then no spray. */
    @Test
    void mistAfterTheColumn() {
        WaterBlast w = new WaterBlast(50, 0, DEEP, CloudModel.Air.STANDARD);
        double t = 2 * w.rise;
        List<Double> taus = new ArrayList<>();
        w.blobs(t, (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> taus.add(CloudModel.DROP_K * water / (2 * Math.PI * sh * sv)), 0, 0,
                0);
        assertEquals(WaterBlast.RING, taus.size(), "after landing, only the spray ring remains");
        assertTrue(taus.getFirst() > 3 * CloudModel.SEEN, "spray ring visible: optical depth " + taus.getFirst());
        double life = w.mistLife() - t;
        assertTrue(life > 3 && life < 120, "spray after column landing " + life + " s");
        assertFalse(w.blobs(w.mistLife() + 1, (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> {}, 0, 0, 0));
    }

    /**
     * Spray settles from a thin layer: 0.1 mm drops fall at 0.3 m/s, and surface eddies cannot suspend them while
     * the Rouse number exceeds the suspension threshold (over water at 6 m/s wind, P ≈ 3.6). Previously the layer rose
     * like gas under Briggs, reaching 28 m in 20 s (above the salvo camera), but lost drops as a 2.5 m layer: a lasting
     * fog wall. At this wind, the visible layer top now stays below the base surge, with e^(−v·t/h) remaining airborne;
     * h is the layer thickness at the surface (√(2πe)·σv for a billow centered at σv). Strong wind (P < 2.5) lifts the layer.
     */
    @Test
    void mistSettlesFromAThinLayer() {
        CloudModel.Air breeze = new CloudModel.Air(0.012, 0, -6, 0.14, 2, 1), gale = new CloudModel.Air(0.012, 0, -20, 0.14, 2, 1);
        double rouse = WaterBlast.mistSettling() / (WaterBlast.KARMAN * Math.sqrt(WaterBlast.DRAG_WATER) * 6);
        assertTrue(rouse > 3 && rouse < 4.5, "Rouse number at 6 m/s " + rouse);
        WaterBlast w = new WaterBlast(6.4, 0.5, 4, breeze);
        assertEquals(0, w.suspended(), 0);
        for (double since = 0.25; since < w.mistLife() - w.landing(); since *= 1.5) {
            double t = w.landing() + since;
            double[] top = {0};
            w.blobs(t, (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> {
                if (sh > 3 * sv) top[0] = Math.max(top[0], y + WaterBlast.visible(CloudModel.DROP_K * water / (2 * Math.PI * sh * sv)) * sv);
            }, 0, 0, 0);
            assertTrue(top[0] <= 1.001 * w.surgeTop(), "after " + since + " s, visible spray top " + top[0] + " above wave " + w.surgeTop());
        }
        double h = Math.sqrt(2 * Math.PI * Math.E) * w.mistSigmaV(0);
        assertEquals(Math.exp(-WaterBlast.mistSettling() * 10 / h), w.mistLeft(10), 1e-9);
        assertTrue(h > 0.7 * w.surgeTop() && h < 1.1 * w.surgeTop(), "layer thickness " + h + " versus wave height " + w.surgeTop());
        WaterBlast windy = new WaterBlast(6.4, 0.5, 4, gale);
        assertTrue(windy.suspended() > 0, "20 m/s wind suspends the drops");
        assertTrue(windy.mistSigmaV(20) > 2 * windy.mistSigmaV(0), "and the layer grows upward");
    }

    /** Foam: immediate at the cavity, elsewhere when water lands; fades. Wave: shallow-water limit and dω/dk at finite depth. */
    @Test
    void foamAndWaveOnTheWater() {
        WaterBlast w = new WaterBlast(50, 0, DEEP, CloudModel.Air.STANDARD);
        assertEquals(1, w.foam(0, 0.01), 0.01);
        // the curtain lands to 2H'·sin2θ, the range of outer jet centers; beyond it, follow the jet cross-section
        double reach = w.fallen(2 * w.rise), fall = reach + 0.5 * w.jet;
        assertTrue(fall > w.radius / 2, "the curtain lands beyond the cavity");
        assertEquals(0, w.foam(fall, w.rise), 0, "the curtain has not landed yet");
        assertTrue(w.foam(fall, 2 * w.rise) > 0.5, "landed: foam");
        assertTrue(w.foam(fall, 2 * w.rise + 60) < 0.05, "after a minute, almost gone");
        assertTrue(w.foam(reach + w.jet, 2 * w.rise) > 0.3 && w.foam(reach + w.jet, 2 * w.rise) < 0.7, "beyond the centers' range, follow the jet cross-section");
        assertEquals(0, w.foam(reach + 1.01 * w.halo * w.jet, 2 * w.rise), 0, "no foam beyond the visible curtain edge");
        WaterBlast pond = new WaterBlast(50, 0, 1, CloudModel.Air.STANDARD);
        assertEquals(Math.sqrt(9.80665), pond.waveSpeed(), 0.01 * Math.sqrt(9.80665));
        assertTrue(pond.waveSpeed() < w.waveSpeed());
        // Independent comparison: numerical derivative of dispersion, rather than another copy of the group-speed formula.
        double k = 2 * Math.PI / w.waveLength(), dk = k * 1e-5;
        for (double depth : new double[]{0.01, 1, 4, 20, 1000}) {
            WaterBlast finite = new WaterBlast(50, 0, depth, CloudModel.Air.STANDARD);
            double hi = Math.sqrt(9.80665 * (k + dk) * Math.tanh((k + dk) * depth));
            double lo = Math.sqrt(9.80665 * (k - dk) * Math.tanh((k - dk) * depth));
            assertEquals((hi - lo) / (2 * dk), finite.waveSpeed(), 1e-7, "depth " + depth);
        }
        assertTrue(w.waveRadius(10) > w.waveRadius(1));
    }
}
