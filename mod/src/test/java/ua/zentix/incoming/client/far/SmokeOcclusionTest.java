package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeOcclusionTest {
    /** Dense contributions remain available to the conservative estimate; its final sampled result retains the old tau cap. */
    @Test
    void denseDepthIsClampedAfterSampling() throws ReflectiveOperationException {
        Planner planner = new Planner(1);
        for (int i = 0; i < 3; i++) planner.puff(0, 0, 100 + 10 * i, 20, 0.999);
        planner.invoke("conceal", 2 * planner.raw());
        int cells = planner.cells(), side = cells + 1, node = 4 * side * side + (cells / 2) * side + cells / 2;
        double stored = ((double[]) planner.buffer("occlusion"))[node];
        assertEquals(-3 * Math.log1p(-0.999), stored, 1e-10, "Dense matter was clipped before the conservative estimate");
        Method query = planner.type.getDeclaredMethod("occlusion", double.class, double.class, double.class);
        query.setAccessible(true);
        assertEquals(12, ((Number) query.invoke(planner.draw, 0.0, 0.0, 1.0)).doubleValue(), 1e-12);
    }

    /** R481 P2b: a nearby dense core on a grid node must not erase a physically exposed distant core. */
    @Test
    void exposedDistantPuffSurvivesAtNormalAndBinocularZoom() throws ReflectiveOperationException {
        for (double zoom : new double[]{1, 4}) {
            double leastKeep = 1;
            int cases = 0, cells = 0, reservedNodes = 0;
            for (double nearRadiusDegrees : new double[]{0.5, 1, 1.7}) for (int stack : new int[]{1, 2}) {
                for (double offsetDegrees : new double[]{2, 2.5}) {
                    Planner planner = new Planner(zoom);
                    for (int p = 0; p < stack; p++) {
                        double distance = 100 + 10 * p, radius = distance * Math.tan(Math.toRadians(nearRadiusDegrees));
                        planner.puff(0, 0, distance, radius, 0.999);
                    }
                    double theta = Math.toRadians(offsetDegrees), radius = 300 * Math.tan(Math.toRadians(1.15));
                    int target = planner.puff(300 * Math.sin(theta), 0, 300 * Math.cos(theta), radius, 0.5);
                    double exactLeast = planner.leastForegroundDepth(target);
                    assertEquals(0, exactLeast, 1e-12, "The review counterexample must have an exposed core ray");
                    // Use the actual projected floor and a constrained budget: concealment is fully active without fake raw fill.
                    planner.conceal(planner.raw() / 2);
                    cells = planner.cells();
                    if (zoom > 1) {
                        int nodes = planner.reservedNodes();
                        assertTrue(nodes > 0 && nodes <= 36 * (stack + 1), "Zoom work must be bounded by the queried core rays");
                        reservedNodes = Math.max(reservedNodes, nodes);
                    }
                    double keep = planner.visibility(target);
                    String context = "zoom " + zoom + ", near radius " + nearRadiusDegrees + ", stack " + stack + ", offset " + offsetDegrees;
                    assertTrue(!planner.dead(target) && keep >= 1 - 1e-10, "An exposed distant puff was concealed: " + context + ", keep " + keep);
                    leastKeep = Math.min(leastKeep, keep);
                    cases++;
                }
            }
            Planner hidden = new Planner(zoom);
            int hiddenCells = hidden.configure(zoom);
            // Half a cell from both axes exercises interpolation, rather than a grid node's exact value.
            hidden.puff(100.0 / hiddenCells, 100.0 / hiddenCells, 100, 20, 0.999);
            int target = hidden.puff(300.0 / hiddenCells, 300.0 / hiddenCells, 300, 300 * Math.tan(Math.toRadians(0.1)), 0.5);
            assertTrue(hidden.leastForegroundDepth(target) > 3, "The positive control must be fully behind the opaque core");
            hidden.conceal(hidden.raw() / 2);
            assertTrue(hidden.dead(target), "Concealment must still remove a fully covered distant core at zoom " + zoom);
            System.out.printf(Locale.ROOT, "OCCLUSION_EXPOSED zoom=%.0f cells=%d cases=%d reservedNodesMax=%d exactMinTau=0 leastVisibility=%.12f coveredControlHalfCellDropped=true%n", zoom, cells, cases, reservedNodes, leastKeep);
        }
    }

    /** Animated FOV may cross a tier boundary repeatedly; it must not resize/rebuild the grid on every tiny change. */
    @Test
    void zoomGridHoldsItsTierAndReusesItsBuffers() throws ReflectiveOperationException {
        Planner planner = new Planner(1);
        int normal = planner.configure(1), zoomed = planner.configure(4);
        assertTrue(zoomed > normal, "Binocular zoom must refine the direction field");
        Object depth = planner.buffer("occlusion"), rays = planner.buffer("occlusionRays");
        for (double zoom : new double[]{1, 4, 1, 4}) {
            int cells = planner.configure(zoom);
            assertEquals(zoom == 1 ? normal : zoomed, cells);
            assertSame(depth, planner.buffer("occlusion"), "Repeated zoom allocated the depth field again");
            assertSame(rays, planner.buffer("occlusionRays"), "Repeated zoom allocated ray storage again");
        }
        // Locate the first refinement by observable resolution, without copying the production tier formula.
        double low = 1, high = 4;
        for (int i = 0; i < 24; i++) {
            double mid = 0.5 * (low + high);
            if (new Planner(mid).configure(mid) > normal) high = mid;
            else low = mid;
        }
        Planner jitter = new Planner(1);
        int tier = jitter.configure(high * (1 + 1e-4));
        assertTrue(tier > normal);
        for (int i = 0; i < 32; i++) assertEquals(tier, jitter.configure(high * (1 + (i % 2 == 0 ? -1e-4 : 1e-4))),
                "Small animated FOV changes thrashed the grid tier");
        System.out.printf(Locale.ROOT, "OCCLUSION_ZOOM normalCells=%d binocularCells=%d firstBoundaryZoom=%.9f jitterTier=%d buffersReused=true%n", normal, zoomed, high, tier);
    }

    /** Crossing the raw-floor budget must not transfer a finite amount of textured matter between distinct puffs. */
    @Test
    void fitPriorityIsContinuousWhenConcealmentBegins() throws ReflectiveOperationException {
        Planner below = priorityFixture(), above = priorityFixture();
        double raw = below.raw();
        assertEquals(raw, above.raw(), 0);
        // The far core really lies behind opaque material; a third core is physically separate.
        assertTrue(below.leastForegroundDepth(1) > 3, "The far puff must exercise occlusion-based priority");
        double budgetBelow = raw * (1 - 1e-7), budgetAbove = raw * (1 + 1e-7);
        below.conceal(budgetBelow);
        above.conceal(budgetAbove);
        below.fit(budgetBelow);
        above.fit(budgetAbove);
        assertTrue(above.texturedShare(1) > 0.1 && above.texturedShare(1) < 0.9,
                "The fixture must exercise the partially selected fit band");
        double worstDepthChange = 0;
        for (int puff = 0; puff < 3; puff++) {
            double opticalDepth = -Math.log1p(-below.opacity(puff));
            worstDepthChange = Math.max(worstDepthChange,
                    opticalDepth * Math.abs(below.texturedShare(puff) - above.texturedShare(puff)));
        }
        assertTrue(worstDepthChange < 4.0 / 255, "Budget activation abruptly reassigned textured optical depth: " + worstDepthChange);
        System.out.printf(Locale.ROOT, "OCCLUSION_BUDGET raw=%.12f budgets=%.12f/%.12f maxTexturedTauChange=%.12f%n",
                raw, budgetBelow, budgetAbove, worstDepthChange);
    }

    private static Planner priorityFixture() throws ReflectiveOperationException {
        Planner planner = new Planner(1);
        planner.puff(0, 0, 100, 20, 0.999);
        planner.puff(0, 0, 300, 15, 0.9);
        planner.puff(100, 0, 300, 0.05 * Math.hypot(100, 300), 0.8);
        return planner;
    }

    /** Physical planner inputs, using production projected area; assertions inspect visibility/fit output, not planSeen's formula. */
    private static final class Planner {
        private final Class<?> type = Class.forName(SmokePuffs.class.getName() + "$Draw");
        private final Object draw;
        private final FarView view;
        private final Method cluster;
        private int count;

        Planner(double zoom) throws ReflectiveOperationException {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            draw = constructor.newInstance();
            view = zoomedView(zoom);
            field(type, "view").set(draw, view);
            cluster = type.getDeclaredMethod("planCluster", FarView.class, double.class, double.class, double.class, double.class,
                    double.class, double.class, boolean.class, double.class, double.class, long.class, double.class,
                    double.class, double.class, boolean.class);
            cluster.setAccessible(true);
        }

        int puff(double x, double y, double z, double radius, double opacity) throws ReflectiveOperationException {
            int entry = count++;
            field(type, "current").setInt(draw, entry);
            ((double[]) field(type, "held").get(draw))[entry * SmokePuffs.FIELDS + 13] = 1;
            cluster.invoke(draw, view, x, y, z, radius, radius, 1.0, false, opacity, opacity, 100L + entry, 1.0, 0.0, 0.0, false);
            return entry;
        }

        double raw() throws ReflectiveOperationException { return field(type, "floor").getDouble(draw); }
        int cells() throws ReflectiveOperationException { return field(type, "occlusionCells").getInt(draw); }
        int reservedNodes() throws ReflectiveOperationException { return BitSet.valueOf((long[]) field(type, "occlusionNodes").get(draw)).cardinality(); }
        Object buffer(String name) throws ReflectiveOperationException { return field(type, name).get(draw); }
        int configure(double zoom) throws ReflectiveOperationException {
            field(type, "view").set(draw, zoomedView(zoom));
            Method configure = type.getDeclaredMethod("configureOcclusion");
            configure.setAccessible(true);
            configure.invoke(draw);
            return cells();
        }
        double opacity(int e) throws ReflectiveOperationException { return ((double[]) field(type, "planOpacity").get(draw))[e]; }
        double visibility(int e) throws ReflectiveOperationException { return ((double[]) field(type, "held").get(draw))[e * SmokePuffs.FIELDS + 13]; }
        boolean dead(int e) throws ReflectiveOperationException { return ((boolean[]) field(type, "planDead").get(draw))[e]; }
        double texturedShare(int e) throws ReflectiveOperationException {
            return visibility(e) * (1 - ((double[]) field(type, "planVeil").get(draw))[e]);
        }

        void conceal(double budget) throws ReflectiveOperationException {
            double raw = raw();
            field(type, "lastRaw").setDouble(draw, raw);
            if (raw > budget) invoke("conceal", budget);
        }

        void fit(double budget) throws ReflectiveOperationException { invoke("fit", budget); }
        private void invoke(String method, double budget) throws ReflectiveOperationException {
            Method call = type.getDeclaredMethod(method, double.class);
            call.setAccessible(true);
            call.invoke(draw, budget);
        }

        double leastForegroundDepth(int target) throws ReflectiveOperationException {
            double[] p = (double[]) field(type, "plan").get(draw);
            int o = 6 * target;
            double x = p[o], y = p[o + 1], z = p[o + 2], h = Math.hypot(x, z), d = Math.hypot(h, y);
            double fx = x / d, fy = y / d, fz = z / d, hx = z / h, hz = -x / h;
            double ux = fy * hz, uy = fz * hx - fx * hz, uz = -fy * hx, radius = 0.6 * p[o + 3];
            double[] rx = {0, 1, 0.7071, 0, -0.7071, -1, -0.7071, 0, 0.7071};
            double[] ry = {0, 0, 0.7071, 1, 0.7071, 0, -0.7071, -1, -0.7071};
            double least = Double.POSITIVE_INFINITY;
            for (int ray = 0; ray < 9; ray++) {
                double dx = x + radius * rx[ray] * hx + radius * ry[ray] * ux;
                double dy = y + radius * ry[ray] * uy;
                double dz = z + radius * rx[ray] * hz + radius * ry[ray] * uz;
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz), tau = 0;
                for (int near = 0; near < target; near++) tau += reference(p, 6 * near, opacity(near), dx / length, dy / length, dz / length);
                least = Math.min(least, tau);
            }
            return least;
        }
    }

    private static FarView zoomedView(double zoom) {
        double pixel = SmokeFillTest.PIXEL / zoom;
        return new FarView(Vec3.ZERO, new Vector3f(0, 0, 1), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0),
                0, 1000, pixel, Math.atan(0.5 * pixel * Math.hypot(1920, 1080)), 1, 1, new Vector3f(1, 1, 1),
                32000, 192, 192, 0, Sight.Air.at(0, 20000));
    }

    /** Sparse eroded deposits must match direct plane geometry and never exceed the original physical core between nodes. */
    @Test
    void depositMatchesNormalizedRayPlaneIntersection() throws ReflectiveOperationException {
        Class<?> type = Class.forName(SmokePuffs.class.getName() + "$Draw");
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object draw = constructor.newInstance();
        field(type, "view").set(draw, SmokeFillTest.view(Vec3.ZERO, new Vector3f(0, 0, 1)));
        var configure = type.getDeclaredMethod("configureOcclusion");
        configure.setAccessible(true);
        configure.invoke(draw);
        var conceal = type.getDeclaredMethod("conceal", double.class);
        conceal.setAccessible(true);
        var query = type.getDeclaredMethod("occlusion", double.class, double.class, double.class);
        query.setAccessible(true);
        double[] plan = (double[]) field(type, "plan").get(draw);
        double[] opacity = (double[]) field(type, "planOpacity").get(draw);
        double[] actual = (double[]) field(type, "occlusion").get(draw);
        int cells = field(type, "occlusionCells").getInt(draw), side = field(type, "occlusionSide").getInt(draw), faceSize = field(type, "occlusionFace").getInt(draw);
        assertEquals(cells + 1, side);
        assertEquals(side * side, faceSize);
        assertEquals(6 * faceSize, actual.length);
        Random random = new Random(0x5ead1L);
        double[][] directions = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
                {1, 1, 1}, {-1, 1, -1}, {1, -1, -1}, {-1, -1, 1}, {1, 1, 0}, {0, -1, 1}};
        double[] radii = {0.01, 0.1, 0.5, 2}, aspects = {0.05, 0.5, 1, 3};
        double worst = 0, worstExcess = 0;
        int comparisons = 0, queryComparisons = 0, positiveQueries = 0;
        for (int scene = 0; scene < 64; scene++) {
            int count = scene < 48 ? 1 : 12;
            field(type, "plans").setInt(draw, count);
            for (int p = 0; p < count; p++) {
                int o = p * 6;
                double[] direction = directions[(scene + p) % directions.length];
                double distance = Math.pow(10, random.nextDouble() * 6 - 3);
                for (int axis = 0; axis < 3; axis++) plan[o + axis] = direction[axis] * distance;
                plan[o + 3] = distance * radii[(scene / 12 + p) % radii.length] / 0.6;
                plan[o + 4] = plan[o + 3] * aspects[(scene + p) % aspects.length];
                opacity[p] = 0.05 + random.nextDouble() * 0.9;
            }
            // With zero raw fill conceal only deposits: no budget-driven changes to puff visibility.
            conceal.invoke(draw, 16.0);
            BitSet reserved = BitSet.valueOf((long[]) field(type, "occlusionNodes").get(draw));
            assertTrue(reserved.cardinality() <= 36 * count, "Only planned core queries should reserve nodes");
            for (int face = 0; face < 6; face++) for (int j = 0; j <= cells; j++) for (int i = 0; i <= cells; i++) {
                double u = 2.0 * i / cells - 1, v = 2.0 * j / cells - 1, sign = (face & 1) == 0 ? 1 : -1;
                int axis = face / 2;
                double length = Math.sqrt(1 + u * u + v * v);
                double rx = (axis == 0 ? sign : u) / length;
                double ry = (axis == 0 ? u : axis == 1 ? sign : v) / length;
                double rz = (axis == 2 ? sign : v) / length;
                int q = face * faceSize + j * side + i;
                double expected = 0;
                if (reserved.get(q)) for (int p = 0; p < count; p++)
                    expected += reference(plan, p * 6, opacity[p], rx, ry, rz, 2 * Math.sqrt(2) / cells);
                double error = Math.abs(expected - actual[q]);
                assertTrue(Double.isFinite(error) && error < 1e-10,
                        "Scene " + scene + ", node " + q + ": expected " + expected + ", actual " + actual[q]);
                worst = Math.max(worst, error);
                comparisons++;
            }
            // Sample both sides of each reserved node. This checks the interpolation footprint against
            // unmodified physical cores, independently of the erosion used by the arithmetic oracle.
            for (int q = reserved.nextSetBit(0); q >= 0; q = reserved.nextSetBit(q + 1)) {
                int face = q / faceSize, j = q % faceSize / side, i = q % side;
                double sign = (face & 1) == 0 ? 1 : -1;
                int axis = face / 2;
                for (double di : new double[]{-0.75, -0.25, 0.25, 0.75}) for (double dj : new double[]{-0.75, -0.25, 0.25, 0.75}) {
                    double u = Math.clamp(2 * (i + di) / cells - 1, -1, 1), v = Math.clamp(2 * (j + dj) / cells - 1, -1, 1);
                    double rx = axis == 0 ? sign : u, ry = axis == 0 ? u : axis == 1 ? sign : v, rz = axis == 2 ? sign : v;
                    double length = Math.sqrt(rx * rx + ry * ry + rz * rz), exact = 0;
                    for (int p = 0; p < count; p++) exact += reference(plan, p * 6, opacity[p], rx / length, ry / length, rz / length);
                    double sampled = ((Number) query.invoke(draw, rx, ry, rz)).doubleValue();
                    assertTrue(Double.isFinite(sampled) && sampled >= 0 && sampled <= exact + 1e-10,
                            "Interpolated depth exceeds physical cores: scene " + scene + ", node " + q + ", exact " + exact + ", sampled " + sampled);
                    worstExcess = Math.max(worstExcess, sampled - exact);
                    queryComparisons++;
                    if (sampled > 1e-10) positiveQueries++;
                }
            }
        }
        assertTrue(positiveQueries > 0, "The physical lower-bound check must include nonzero concealment");
        System.out.printf(Locale.ROOT, "OCCLUSION_EQUIVALENCE nodes=%d maxAbsTau=%.17g lowerBoundQueries=%d positiveQueries=%d maxExcessTau=%.17g%n",
                comparisons, worst, queryComparisons, positiveQueries, worstExcess);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** Direct normalized ray/plane intersection, before hoisting axes or rejecting by squared numerators. */
    private static double reference(double[] plan, int o, double opacity, double rx, double ry, double rz) {
        return reference(plan, o, opacity, rx, ry, rz, 0);
    }

    private static double reference(double[] plan, int o, double opacity, double rx, double ry, double rz, double footprintRadians) {
        double x = plan[o], y = plan[o + 1], z = plan[o + 2];
        double h = Math.hypot(x, z), d = Math.hypot(h, y);
        double fx = x / d, fy = y / d, fz = z / d;
        double hx = h > 1e-9 ? z / h : 1, hz = h > 1e-9 ? -x / h : 0;
        double ux = fy * hz, uy = fz * hx - fx * hz, uz = -fy * hx;
        double denominator = fx * rx + fy * ry + fz * rz;
        if (!(denominator > 0)) return 0;
        double distance = d / denominator;
        double ra = 0.6 * plan[o + 3], rb = 0.6 * plan[o + 4], radius = Math.max(ra, rb);
        double a = distance * (hx * rx + hz * rz) / ra;
        double b = distance * (ux * rx + uy * ry + uz * rz) / rb;
        double rho = Math.hypot(a, b) + footprintRadians * (d + radius * radius / d) / Math.min(ra, rb);
        double edge = Math.clamp((rho * rho - 0.8 * 0.8) / (1 - 0.8 * 0.8), 0, 1);
        double weight = 1 - edge * edge * (3 - 2 * edge);
        return -Math.log1p(-Math.min(opacity, 0.999)) * weight;
    }
}
