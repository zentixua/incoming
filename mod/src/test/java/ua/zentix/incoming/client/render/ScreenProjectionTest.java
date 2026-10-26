package ua.zentix.incoming.client.render;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Target frame - angular size of the target in the frame (complaint 23.10: when zooming Ok Zoomer marks “float” and are large relative to the target).
 * Ok Zoomer changes the field of view at the output {@code GameRenderer.getFov}, from which it is built the frame matrix is the same as the markers.
 */
class ScreenProjectionTest {
    /** Frame matrix: perspective with a field of view fov vertically (16:9), the eye in 0 looks along −z. */
    private static Matrix4f frame(double fovDeg) {
        return new Matrix4f().perspective((float) Math.toRadians(fovDeg), 16f / 9, 0.05f, 1000);
    }

    /**
     * Player (0,6 × 1,8 m) in 200 m: the height of the frame is its angular height in fractions of the screen, 1,8/200 / (2·tg(fov/2)); at field
     *  of vision is k times narrower (zoom ×k: tg (fov/2) smaller by k times) frame is k times higher - the same angular size as at the target.
     */
    @Test
    void frameHasTheTargetsAngularSizeAtAnyZoom() {
        AABB player = new AABB(-0.3, -0.9, -200.3, 0.3, 0.9, -199.7);
        double tan70 = Math.tan(Math.toRadians(35));
        for (int k : new int[]{1, 4, 16, 64}) {
            double fov = 2 * Math.toDegrees(Math.atan(tan70 / k));
            float[] r = ScreenProjection.rect(frame(fov), Vec3.ZERO, player);
            assertNotNull(r);
            double tall = r[3] - r[1], expected = 1.8 / 199.7 / (2 * tan70 / k);
            assertEquals(expected, tall, expected * 0.01, "zoom ×" + k);
            // middle of frame - middle of target: in the middle of frame
            assertEquals(0.5, 0.5 * (r[1] + r[3]), 1e-4, "zoom ×" + k);
        }
    }

    /**
     * Shahed (largest size 3,6 m) in 700 m - a ball of radius 1,8: frame diameter - its angular diameter 3,6/700 in fractions of
     *  frame height; with a field of view k times narrower - exactly k times larger; the middle is its middle.
     */
    @Test
    void bodyFrameHasTheBodysAngularSizeAtAnyZoom() {
        double tan70 = Math.tan(Math.toRadians(35));
        Vec3 shahed = new Vec3(30, 40, -700);
        for (int k : new int[]{1, 4, 16, 64}) {
            double fov = 2 * Math.toDegrees(Math.atan(tan70 / k));
            Matrix4f proj = frame(fov);
            float[] r = ScreenProjection.rect(proj, new Matrix4f(), Vec3.ZERO, shahed, 1.8);
            assertNotNull(r);
            double tall = r[3] - r[1], expected = 3.6 / 700 / (2 * tan70 / k);
            assertEquals(expected, tall, expected * 1e-3, "zoom ×" + k);
            float[] c = ScreenProjection.rect(proj, Vec3.ZERO, new net.minecraft.world.phys.AABB(shahed, shahed));
            assertNotNull(c);
            assertEquals(c[0], 0.5 * (r[0] + r[2]), 1e-5, "zoom ×" + k);
            assertEquals(c[1], 0.5 * (r[1] + r[3]), 1e-5, "zoom ×" + k);
        }
        assertNull(ScreenProjection.rect(frame(70), new Matrix4f(), Vec3.ZERO, new Vec3(0, 0, 50), 1.8), "behind my back");
    }

    /** The corner of the box is behind the back - there is no frame. */
    @Test
    void behindTheEyeNoFrame() {
        assertNull(ScreenProjection.rect(frame(70), Vec3.ZERO, new AABB(-1, -1, -1, 1, 1, 5)));
    }
}
