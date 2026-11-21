package ua.zentix.incoming.client.far;

import java.util.Locale;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review #716 probe (Codex): the veil's matter split moves continuously and keeps its slopes. */
final class VeilSplitTest {
    private static final int CENTER = SmokePuffs.Veil.index(SmokePuffs.Veil.N / 2, SmokePuffs.Veil.N / 2);

    @Test
    void partiallyJoinedCloudKeepsItsEyeSlope() {
        // A budget cloud about 2.5 sigma ahead. Its real tail at the eye is weak.
        double[] data = gaussian(0, 10, 4, 4);
        Sample result = atWall(data, 0, 3);
        System.out.printf(Locale.ROOT,
                "3 m wall: law T=%.6f shown T=%.6f, eye extinction %.6f vs drawn %.6f /m, near share=%.6f, packed width=%.4f m%n",
                result.law, result.shown, result.eyeSigma, result.drawnSigma, result.share, result.width);
        assertEquals(result.law, result.shown, result.law * 0.25, "The former reach clamp must not front-load the tail");
    }

    @Test
    void crossingTwoAlmostEqualStartsDoesNotJumpTheVeil() {
        // A local broad haze, then two clumps of different widths on either side of the gaze.
        // Their moment intervals start at the same distance (11.21 m) at yaw zero.
        double[] data = new double[3 * SmokePuffs.VEIL_FIELDS];
        System.arraycopy(gaussian(0, 0, 5, 0.4), 0, data, 0, SmokePuffs.VEIL_FIELDS);
        System.arraycopy(gaussian(1, 14.674014537168407, 2, 6), 0, data, SmokePuffs.VEIL_FIELDS, SmokePuffs.VEIL_FIELDS);
        System.arraycopy(gaussian(-1, 16.406063144264124, 3, 1), 0, data, 2 * SmokePuffs.VEIL_FIELDS, SmokePuffs.VEIL_FIELDS);
        Sample a = atWall(data, -0.001, 8);
        Sample b = atWall(data, 0.001, 8);
        System.out.printf(Locale.ROOT,
                "8 m wall, yaw %.3f degrees apart: law T=%.6f -> %.6f, shown T=%.6f -> %.6f, near share %.6f -> %.6f%n",
                Math.toDegrees(0.002), a.law, b.law, a.shown, b.shown, a.share, b.share);
        assertTrue(Math.abs(a.law - b.law) < 0.001);
        assertEquals(a.shown, b.shown, 0.02, "Two equal-start clumps cannot exchange a discontinuous amount of near matter");
    }

    private record Sample(double law, double shown, double eyeSigma, double drawnSigma, double share, double width) {}

    private static double[] gaussian(double x, double z, double w, double depth) {
        return new double[]{x, 0, z, w, w, 2 * Math.PI * w * w * depth, 0.5, 0.5, 0.5, 1};
    }

    private static Sample atWall(double[] data, double yaw, double wall) {
        Vector3f forward = new Vector3f((float) Math.sin(yaw), 0, (float) Math.cos(yaw));
        FarView view = SmokeFillTest.view(new Vec3(0, 100, 0), forward);
        SmokePuffs.Veil veil = new SmokePuffs.Veil();
        veil.fill(view, data, data.length / SmokePuffs.VEIL_FIELDS);

        double tau = 0, eyeSigma = 0;
        int steps = (int) (wall * 1000);
        for (int off = 0; off < data.length; off += SmokePuffs.VEIL_FIELDS) {
            double gx = data[off], gz = data[off + 2], w = data[off + 3], ext = data[off + 5];
            double density = ext / (Math.pow(2 * Math.PI, 1.5) * w * w * w);
            eyeSigma += density * Math.exp(-0.5 * (gx * gx + gz * gz) / (w * w));
            for (int i = 0; i < steps; i++) {
                double distance = wall * (i + 0.5) / steps;
                double dx = forward.x * distance - gx, dz = forward.z * distance - gz;
                tau += density * Math.exp(-0.5 * (dx * dx + dz * dz) / (w * w)) * wall / steps;
            }
        }
        double alpha = SmokePuffs.Veil.byteAlpha(veil.alpha(CENTER));
        double share = SmokePuffs.Veil.byteShare(veil.near[CENTER]);
        double width = SmokePuffs.Veil.sixteenths(veil.nearWidth[CENTER], 1.0 / 16) / 16.0;
        double slope = SmokePuffs.Veil.shaderTau(alpha) * SmokePuffs.Veil.byteDeeper(veil.deeper[CENTER]) * share / width;
        return new Sample(Math.exp(-tau), 1 - alpha * veil.before(CENTER, wall), eyeSigma, slope, share, width);
    }
}
