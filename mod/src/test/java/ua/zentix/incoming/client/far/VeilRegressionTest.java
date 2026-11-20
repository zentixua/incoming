package ua.zentix.incoming.client.far;

import java.lang.reflect.Method;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.FxQuads;

import static org.junit.jupiter.api.Assertions.*;

/** Review #716 probes (Codex, rounds 1–4): veil words, cell interpolation, depth bytes and telemetry against the law. */
final class VeilRegressionTest {
    private static final int CENTER = SmokePuffs.Veil.index(SmokePuffs.Veil.N / 2, SmokePuffs.Veil.N / 2);

    private static FarView view() {
        return SmokeFillTest.view(new Vec3(0, 100, 0), new Vector3f(0, 0, 1));
    }

    private static double[] gaussian(double center, double width, double depth, double share) {
        return new double[]{0, 0, center, width, width, 2 * Math.PI * width * width * depth, 0.5, 0.5, 0.5, share};
    }

    private static double integrated(double[] data, double wall) {
        double opticalDepth = 0;
        for (int off = 0; off < data.length; off += SmokePuffs.VEIL_FIELDS) {
            double m = data[off + 2], w = data[off + 3], ext = data[off + 5];
            double norm = ext / (Math.pow(2 * Math.PI, 1.5) * w * w * w);
            double depth = 0;
            int steps = (int) (wall * 200);
            for (int i = 0; i < steps; i++) {
                double x = (i + 0.5) * wall / steps;
                depth += norm * Math.exp(-0.5 * Math.pow((x - m) / w, 2)) * wall / steps;
            }
            opticalDepth -= Math.log1p(-data[off + 9] * -Math.expm1(-depth));
        }
        return Math.exp(-opticalDepth);
    }

    private static void verifyWall(double[] data, double wall) {
        verifyWall(data, wall, 0.25);
    }

    private static void verifyWall(double[] data, double wall, double tolerance) {
        SmokePuffs.Veil veil = new SmokePuffs.Veil();
        veil.fill(view(), data, data.length / SmokePuffs.VEIL_FIELDS);
        double law = integrated(data, wall);
        double after = 1 - veil.before(CENTER, wall) * SmokePuffs.Veil.byteAlpha(veil.alpha(CENTER));
        System.out.printf("Wall %.2f m: T law=%.6f fixed=%.6f, words=%s%n", wall, law, after, words(veil, CENTER));
        assertEquals(law, after, law * tolerance, "The wall must keep its contrast to within " + tolerance);
    }

    private static String words(SmokePuffs.Veil veil, int k) {
        return String.format("(%d, %d, %d, %d, %d, %d)", veil.packed(k) & 65535,
                veil.packed(k) >>> 16, Math.round(255 * SmokePuffs.Veil.byteAlpha(veil.alpha(k))),
                Math.round(127 * veil.near[k]), SmokePuffs.Veil.sixteenths(veil.nearWidth[k], 1.0 / 16),
                Math.round(127 * SmokePuffs.Veil.deeperByte(veil.deeper[k])));
    }

    @Test
    void continuousBlendDoesNotPullDenseDetachedClumpAcrossTheWall() {
        double[] data = new double[2 * SmokePuffs.VEIL_FIELDS];
        System.arraycopy(gaussian(-5, 3, 0.8, 1), 0, data, 0, SmokePuffs.VEIL_FIELDS);
        System.arraycopy(gaussian(40, 2, 4, 1), 0, data, SmokePuffs.VEIL_FIELDS, SmokePuffs.VEIL_FIELDS);
        verifyWall(data, 30);
    }

    @Test
    void packedMiddleDoesNotMoveLodClumpsInFrontOfTerrain() {
        // A budget-veiled cloud at 2.5 km, with atmospheric/drawn opacity share 0.6 and Voxy terrain at 2.25 km.
        verifyWall(gaussian(2500, 40, 2.3, 0.6), 2250);
    }

    @Test
    void adjacentRaysProvideTheMixedCellGlFixture() {
        double[] data = new double[2 * SmokePuffs.VEIL_FIELDS];
        System.arraycopy(gaussian(0, 10, 1.4, 1), 0, data, 0, SmokePuffs.VEIL_FIELDS);
        System.arraycopy(gaussian(90, 1.75, 3.23, 1), 0, data, SmokePuffs.VEIL_FIELDS, SmokePuffs.VEIL_FIELDS);
        SmokePuffs.Veil veil = new SmokePuffs.Veil();
        veil.fill(view(), data, 2);
        assertAll(
                () -> assertEquals(360, veil.packed(CENTER) & 65535),
                () -> assertEquals(127, Math.round(127 * veil.near[CENTER + 1])),
                () -> assertEquals(0, veil.packed(CENTER + 1) & 65535));
        for (int k : new int[]{CENTER, CENTER + 1}) {
            double alpha = SmokePuffs.Veil.byteAlpha(veil.alpha(k));
            System.out.printf("Mixed vertex words=%s, T=%.6f%n", words(veil, k), 1 - alpha * veil.before(k, 70));
        }
    }

    @Test
    void centeredHazeKeepsWallContrastWithinShapeLimit() {
        // A Gaussian centered at the eye, sigma 5 m, forward optical depth 2: the even part around the eye is the
        // two-segment representation's worst case (SmokePuffs.Veil.split), T 0.137 against the law's 0.206.
        verifyWall(gaussian(0, 5, 4, 1), 6.25, 0.35);
    }

    @Test
    void zeroAlphaSuppliesNoExtraDepth() {
        SmokePuffs.Veil veil = new SmokePuffs.Veil();
        double[] gs = gaussian(90, 5.5, 2.3, 1);
        veil.fill(view(), gs, 1);
        int k = CENTER + 1;
        double tau = veil.tau[k];
        assertTrue(tau > 1e-6 && SmokePuffs.Veil.byteAlpha(veil.alpha(k)) == 0);
        System.out.printf("Fog edge: left=%s, right=%s, right tau=%.9f, depth ratio=%s%n",
                words(veil, CENTER), words(veil, k), tau, veil.deeper[k]);
        assertEquals(0.0, SmokePuffs.Veil.deeperByte(veil.deeper[k]), "A vertex with no opacity cannot supply extra depth");
    }

    @Test
    void denseClumpAndHazeProvideExtraDepthInterpolationFixture() {
        // Budget veil, one local haze and a dense group of narrower Gaussians at 84–95 m.
        double[] gs = new double[13 * SmokePuffs.VEIL_FIELDS];
        System.arraycopy(gaussian(0, 10, 1.4, 1), 0, gs, 0, SmokePuffs.VEIL_FIELDS);
        for (int i = 1; i <= 12; i++) {
            System.arraycopy(gaussian(83 + i, 2, 8, 0.98), 0, gs, i * SmokePuffs.VEIL_FIELDS, SmokePuffs.VEIL_FIELDS);
        }
        SmokePuffs.Veil veil = new SmokePuffs.Veil();
        veil.fill(view(), gs, 13);
        for (int k : new int[]{CENTER, CENTER + 1}) {
            double alpha = SmokePuffs.Veil.byteAlpha(veil.alpha(k));
            System.out.printf("Dense cell: words=%s, true tau=%.6f, 70 m wall T=%.6f%n",
                    words(veil, k), veil.tau[k], 1 - alpha * veil.before(k, 70));
        }
        assertEquals(1, SmokePuffs.Veil.byteAlpha(veil.alpha(CENTER)));
        assertEquals(127, Math.round(127 * veil.near[CENTER + 1]));
    }

    @Test
    void javaUsesTheWritersAlphaByte() throws Exception {
        Method writer = FxQuads.class.getDeclaredMethod("unorm", float.class);
        writer.setAccessible(true);
        float alpha = 0.5039215684f;
        int actual = ((Byte) writer.invoke(null, alpha)) & 255;
        int mirror = (int) Math.round(SmokePuffs.Veil.byteAlpha(alpha) * 255);
        System.out.printf("Opacity %.10f: writer=%d mirror=%d%n", alpha, actual, mirror);
        assertEquals(actual, mirror);
    }

    @Test
    void primaryShotTelemetrySurvivesAnEmptySecondaryViewAndClearsOnReset() {
        SmokePuffs.noVeilGaussians();
        try {
            double[] data = gaussian(40, 2, 4, 1);
            new SmokePuffs.Veil().fill(view(), data, 1);
            String primary = SmokePuffs.lastVeilGaussians();
            data[2] = 50;
            assertEquals(primary, SmokePuffs.lastVeilGaussians(), "Published data must be copied");
            SmokePuffs.ViewState secondary = new SmokePuffs.ViewState();
            secondary.swap();
            try {
                SmokePuffs.flush();
            } finally {
                secondary.swap();
            }
            String afterSecondary = SmokePuffs.lastVeilGaussians();
            new SmokePuffs.Veil().fill(view(), data, 1);
            SmokePuffs.reset();
            String afterReset = SmokePuffs.lastVeilGaussians();
            System.out.printf("Primary=%s; after secondary=%s; after reset=%s%n", primary, afterSecondary, afterReset);
            assertAll(() -> assertEquals(primary, afterSecondary), () -> assertEquals("0", afterReset));
        } finally {
            SmokePuffs.noVeilGaussians();
        }
    }
}
