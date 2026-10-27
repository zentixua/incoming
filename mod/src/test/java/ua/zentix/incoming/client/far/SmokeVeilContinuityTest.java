package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeVeilContinuityTest {
    private static final double VISIBLE = 4.0 / 255;
    private static final double[] RAY = {-0.25, -0.25, 1};

    /** Removing two weak, off-axis puffs must not refine every visible veil clump at the 48-record boundary. */
    @Test
    void smallCountChangeKeepsTheVisibleVeilStable() throws ReflectiveOperationException {
        SmokePuffs.reset();
        try {
            Frame before = frame(50), after = frame(48);
            assertEquals(50, before.rawCount);
            assertEquals(48, after.rawCount);
            assertTrue(before.groups <= 48 && after.groups <= 48, "The Gaussian work cap must hold");
            assertEquals(before.rawT, after.rawT, 1e-12, "The removed puffs must not change material on the observed ray");
            assertTrue(Math.abs(before.analyticT - after.analyticT) < VISIBLE,
                    "A small count change rebuilt the merged material on a fixed world ray");
            assertTrue(Math.abs(before.meshT - after.meshT) < VISIBLE,
                    "A small count change rebuilt the drawn veil on a fixed world ray");
            // Resetting the budget deliberately starts a fresh selection. This also verifies that the
            // fixture distinguishes the coarse and fine representations instead of observing empty space.
            SmokePuffs.resetBudget();
            assertEquals(0, level());
            Frame fresh = frame(48);
            assertEquals(48, fresh.groups, "A fresh selection must retain all puffs that fit");
            assertTrue(Math.abs(after.analyticT - fresh.analyticT) > VISIBLE,
                    "The fixture must expose the visible consequence of an abrupt refinement");
            System.out.printf(Locale.ROOT,
                    "VEIL_BOUNDARY raw=%d/%d groups=%d/%d fresh=%d rawT=%.12f/%.12f mergedT=%.12f/%.12f freshT=%.12f meshT=%.12f/%.12f%n",
                    before.rawCount, after.rawCount, before.groups, after.groups, fresh.groups,
                    before.rawT, after.rawT, before.analyticT, after.analyticT, fresh.analyticT, before.meshT, after.meshT);
        } finally {
            SmokePuffs.reset();
        }
    }

    @Test
    void emptyFrameAndWorldResetForgetTheVeilSelection() throws ReflectiveOperationException {
        SmokePuffs.reset();
        try {
            frame(50);
            assertTrue(level() > 0);
            invoke("veilFlush"); // The preceding frame cleared its entries; this frame has no veil.
            assertEquals(0, level(), "An empty frame retained stale veil coarsening");
            assertEquals(48, frame(48).groups);
            frame(50);
            assertTrue(level() > 0);
            SmokePuffs.reset();
            assertEquals(0, level(), "Changing worlds retained stale veil coarsening");
            assertEquals(48, frame(48).groups);
        } finally {
            SmokePuffs.reset();
        }
    }

    private record Frame(int rawCount, int groups, double rawT, double analyticT, double meshT) {}

    private static Frame frame(int count) throws ReflectiveOperationException {
        Object draw = draw();
        Class<?> type = draw.getClass();
        FarView view = SmokeFillTest.view(Vec3.ZERO, new Vector3f(0, 0, 1));
        SmokeFillTest.Screen screen = new SmokeFillTest.Screen(view);
        Method add = type.getDeclaredMethod("veilAdd", SmokePuffs.Out.class, FarView.class,
                double.class, double.class, double.class, double.class, double.class, double.class,
                float.class, float.class, float.class, double.class);
        add.setAccessible(true);
        // Separate world directions and equal physical sizes give 50 fine clumps. The last two
        // carry little opacity and lie on another cube face, away from the observed +Z ray.
        int[] faces = {4, 5, 0, 1};
        for (int e = 0; e < count; e++) {
            int face = faces[e / 16], axis = face / 2;
            double u = 0.5 * (e % 4) - 0.75, v = 0.5 * (e / 4 % 4) - 0.75, sign = (face & 1) == 0 ? 1 : -1;
            double x = 100 * (axis == 0 ? sign : u), y = 100 * (axis == 0 ? u : v), z = 100 * (axis == 2 ? sign : v);
            assertTrue((boolean) add.invoke(draw, screen, view, x, y, z, 2.0, 2.0, 16 * Math.PI,
                    0.6f, 0.55f, 0.45f, e >= 48 ? 0.01 : 1.0), "The physical veil entry must pass the visibility cutoff");
        }
        int raw = field(type, "veilCount").getInt(draw);
        double rawT = transmittance((double[]) field(type, "veils").get(draw), raw);
        invoke("veilFlush");
        int merged = field(type, "lastVeils").getInt(draw);
        double analytic = transmittance((double[]) field(type, "veils").get(draw), merged);
        return new Frame(raw, merged, rawT, analytic, screen.veil == null ? 1 : screen.veil.through(RAY[0], RAY[1]));
    }

    private static double transmittance(double[] g, int count) {
        double n = Math.sqrt(RAY[0] * RAY[0] + RAY[1] * RAY[1] + RAY[2] * RAY[2]), tau = 0;
        for (int i = 0; i < count; i++) {
            int o = i * SmokePuffs.VEIL_FIELDS;
            double along = SmokePuffs.sightDepth(g[o], g[o + 1], g[o + 2], g[o + 3], g[o + 4], g[o + 5], RAY[0] / n, RAY[1] / n, RAY[2] / n);
            double alpha = g[o + 9] * -Math.expm1(-along);
            if (alpha > 1e-6) tau -= Math.log1p(-Math.min(alpha, 0.999));
        }
        return Math.exp(-tau);
    }

    private static Object draw() throws ReflectiveOperationException { return field(SmokePuffs.class, "fill").get(null); }
    private static int level() throws ReflectiveOperationException { Object draw = draw(); return field(draw.getClass(), "veilLevel").getInt(draw); }
    private static void invoke(String name) throws ReflectiveOperationException {
        Object draw = draw();
        Method method = draw.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(draw);
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
