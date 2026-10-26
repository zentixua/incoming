package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeOcclusionTest {
    /** The optimized deposit must retain the normalized ray/plane field, including cube seams and overlapping puffs. */
    @Test
    void depositMatchesNormalizedRayPlaneIntersection() throws ReflectiveOperationException {
        Class<?> type = Class.forName(SmokePuffs.class.getName() + "$Draw");
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object draw = constructor.newInstance();
        var conceal = type.getDeclaredMethod("conceal", double.class);
        conceal.setAccessible(true);
        double[] plan = (double[]) field(type, "plan").get(draw);
        double[] opacity = (double[]) field(type, "planOpacity").get(draw);
        double[] actual = (double[]) field(type, "occlusion").get(draw);
        assertEquals(6 * 25 * 25, actual.length, "The direction grid retains 24 cells per face");
        Random random = new Random(0x5ead1L);
        double[][] directions = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
                {1, 1, 1}, {-1, 1, -1}, {1, -1, -1}, {-1, -1, 1}, {1, 1, 0}, {0, -1, 1}};
        double[] radii = {0.01, 0.1, 0.5, 2}, aspects = {0.05, 0.5, 1, 3};
        double worst = 0;
        int comparisons = 0;
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
            for (int face = 0; face < 6; face++) for (int j = 0; j <= 24; j++) for (int i = 0; i <= 24; i++) {
                double u = i / 12.0 - 1, v = j / 12.0 - 1, sign = (face & 1) == 0 ? 1 : -1;
                int axis = face / 2;
                double length = Math.sqrt(1 + u * u + v * v);
                double rx = (axis == 0 ? sign : u) / length;
                double ry = (axis == 0 ? u : axis == 1 ? sign : v) / length;
                double rz = (axis == 2 ? sign : v) / length;
                double expected = 0;
                for (int p = 0; p < count; p++) expected += reference(plan, p * 6, opacity[p], rx, ry, rz);
                expected = Math.min(12, expected);
                int q = face * 625 + j * 25 + i;
                double error = Math.abs(expected - actual[q]);
                assertTrue(Double.isFinite(error) && error < 1e-10,
                        "Scene " + scene + ", node " + q + ": expected " + expected + ", actual " + actual[q]);
                worst = Math.max(worst, error);
                comparisons++;
            }
        }
        System.out.printf(Locale.ROOT, "OCCLUSION_EQUIVALENCE nodes=%d maxAbsTau=%.17g%n", comparisons, worst);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** Direct normalized ray/plane intersection, before hoisting axes or rejecting by squared numerators. */
    private static double reference(double[] plan, int o, double opacity, double rx, double ry, double rz) {
        double x = plan[o], y = plan[o + 1], z = plan[o + 2];
        double h = Math.hypot(x, z), d = Math.hypot(h, y);
        double fx = x / d, fy = y / d, fz = z / d;
        double hx = h > 1e-9 ? z / h : 1, hz = h > 1e-9 ? -x / h : 0;
        double ux = fy * hz, uy = fz * hx - fx * hz, uz = -fy * hx;
        double denominator = fx * rx + fy * ry + fz * rz;
        if (!(denominator > 0)) return 0;
        double distance = d / denominator;
        double a = distance * (hx * rx + hz * rz) / (0.6 * plan[o + 3]);
        double b = distance * (ux * rx + uy * ry + uz * rz) / (0.6 * plan[o + 4]);
        double edge = Math.clamp((a * a + b * b - 0.8 * 0.8) / (1 - 0.8 * 0.8), 0, 1);
        double weight = 1 - edge * edge * (3 - 2 * edge);
        return -Math.log1p(-Math.min(opacity, 0.999)) * weight;
    }
}
