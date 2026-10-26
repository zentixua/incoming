package ua.zentix.incoming.client.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The eye of the frame ({@link FarDraw#eye}) is the center of the projection and when the view is swayed: the point transferred to it along the ray (the highlight
 *  in the 0,25 block in front of the eye, the far one at the far plane) remains on the screen in the same place as the source. From the camera position
 * , the glare of fire when walking would follow the arc of a step, and the world would stand behind it.
 */
class FarDrawTest {
    private static final Matrix4f PROJECTION = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 10f, 0.05f, 1024f);
    /** Camera view: turned along the course and slightly downward (view - reverse rotation, like the Minecraft). */
    private static final Matrix4f ROTATION = new Matrix4f().rotationXYZ(0.35f, -2.2f, 0f);
    /** Points of the world from the camera, in the frame: in front, just below the gaze, on the side and below, far away (set in the gaze axes). */
    private static final Vector3f[] POINTS = {ahead(0, -12, -100), ahead(30, -40, -90), ahead(-60, 5, -400)};

    /** The point from the camera along the axes of view (x to the right, y up, −z forward) - in the axes of the world. */
    private static Vector3f ahead(float x, float y, float z) {
        return new Matrix4f(ROTATION).invert().transformDirection(new Vector3f(x, y, z));
    }

    /** Swaying appearance when walking, like {@code GameRenderer.bobView}: step phase and strength (for someone walking on the ground 0,1). */
    private static Matrix4f bobbing(float phase, float bob) {
        float s = (float) Math.sin(phase * Math.PI), c = (float) Math.cos(phase * Math.PI);
        return new Matrix4f().translate(s * bob * 0.5f, -Math.abs(c * bob), 0)
                .rotateZ((float) Math.toRadians(s * bob * 3))
                .rotateX((float) Math.toRadians(Math.abs((float) Math.cos(phase * Math.PI - 0.2f) * bob) * 5));
    }

    /** Dot on screen (NDC); it's in front of your eyes. */
    private static Vector3f screen(Matrix4f projection, Matrix4f view, Vector3f p) {
        Vector4f v = new Matrix4f(projection).mul(view).transform(new Vector4f(p, 1));
        assertTrue(v.w > 0, "point behind the eye");
        return new Vector3f(v.x / v.w, v.y / v.w, v.z / v.w);
    }

    /** Point p, transferred to eye along the ray so that at blocks remain before the eye. */
    private static Vector3f toward(Vector3f eye, Vector3f p, float at) {
        Vector3f ray = new Vector3f(p).sub(eye);
        return ray.mul(at / ray.length()).add(eye);
    }

    @Test
    void foldedPointStaysOnScreenWhileWalking() {
        for (int i = 0; i < 8; i++) {
            Matrix4f bob = bobbing(i / 8f, 0.1f);
            // vanilla: swaying in projection; Iris with shaderpack: in the view matrix
            Matrix4f[][] layouts = {{new Matrix4f(PROJECTION).mul(bob), ROTATION}, {PROJECTION, new Matrix4f(bob).mul(ROTATION)}};
            for (Matrix4f[] m : layouts) {
                Vector3f eye = FarDraw.eyeOffset(m[0], m[1], new Vector3f());
                for (Vector3f p : POINTS) {
                    Vector3f source = screen(m[0], m[1], p);
                    assertTrue(Math.abs(source.x) <= 1 && Math.abs(source.y) <= 1, "source in frame");
                    Vector3f glare = screen(m[0], m[1], toward(eye, p, 0.25f));
                    assertEquals(source.x, glare.x, 1e-4, "horizontal flare of the eye, phase " + i);
                    assertEquals(source.y, glare.y, 1e-4, "glare near the eye vertically, phase " + i);
                    // transfer to the camera position: the glare moves away from the fire to a fraction of the screen
                    Vector3f old = screen(m[0], m[1], toward(new Vector3f(), p, 0.25f));
                    assertTrue(Math.hypot(old.x - source.x, old.y - source.y) > 0.05, "transfer to camera, phase " + i);
                }
            }
        }
    }

    @Test
    void orthographicEyeIsCamera() {
        Vector3f eye = FarDraw.eyeOffset(new Matrix4f().ortho(-10, 10, -6, 6, 0.05f, 1024f), ROTATION, new Vector3f());
        assertEquals(0, eye.length(), 0);
    }

    @Test
    void eyeIsCameraWithoutBobbing() {
        Vector3f eye = FarDraw.eyeOffset(PROJECTION, ROTATION, new Vector3f());
        assertEquals(0, eye.length(), 1e-5);
    }
}
