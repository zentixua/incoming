package ua.zentix.incoming.client.fx.layer;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first-person hand under Iris is told from the world by its distance at the layer ({@code pall.glsl}: {@code PALL_HAND}):
 * Iris draws the hand into world depth with the depth range × 0.125 ({@code HandRenderer.DEPTH}), and the layer turns that
 * depth into a distance with the world projection ({@code fx_depth}). The pall leaves those pixels alone, as vanilla does.
 */
class PallHandTest {
    /** Vanilla near plane ({@code GameRenderer.getProjectionMatrix}). */
    private static final float NEAR = 0.05f;
    /** Iris {@code HandRenderer.DEPTH}. */
    private static final float IRIS_HAND_DEPTH = 0.125f;

    private static float hand() throws IOException {
        String glsl = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/include/pall.glsl"));
        Matcher m = Pattern.compile("const float PALL_HAND = ([0-9.]+);").matcher(glsl);
        assertTrue(m.find(), "PALL_HAND in pall.glsl");
        return Float.parseFloat(m.group(1));
    }

    /** Distance along the view axis the layer reads for view-space point p drawn with projection {@code draw} (fx_depth: along). */
    private static float layerDistance(Matrix4f world, Matrix4f draw, Vector4f p) {
        Vector4f clip = draw.transform(new Vector4f(p));
        float ndcX = clip.x / clip.w, ndcY = clip.y / clip.w, ndcZ = clip.z / clip.w;
        Vector4f view = new Matrix4f(world).invert().transform(new Vector4f(ndcX, ndcY, ndcZ, 1));
        return -view.z / view.w;
    }

    /** Hand anywhere in view from the near plane to 4 m, any FOV and render distance: nearer than PALL_HAND, still in front. */
    @Test
    void irisHandReadsNearerThanTheBound() throws IOException {
        float bound = hand();
        for (float fov : new float[]{30, 70, 110, 140}) {
            for (float far : new float[]{32, 256, 1024, 4096}) {
                Matrix4f world = new Matrix4f().perspective((float) Math.toRadians(fov), 1.6f, NEAR, far);
                Matrix4f hand = new Matrix4f().scale(1, 1, IRIS_HAND_DEPTH).mul(world);
                for (float d = NEAR * 1.01f; d <= 4f; d *= 1.2f) {
                    for (float side : new float[]{0f, 0.5f, 1f}) {
                        Vector4f p = new Vector4f(side * d, -side * d * 0.5f, -d, 1);
                        float seen = layerDistance(world, hand, p);
                        assertTrue(seen > 0 && seen < bound, "hand at " + d + " m, fov " + fov + ", far " + far + ": " + seen);
                    }
                }
            }
        }
    }

    /** A wall at arm's length reads as itself, well beyond the bound: the bound only takes the hand. */
    @Test
    void worldAtArmsLengthIsNotTheHand() throws IOException {
        float bound = hand();
        Matrix4f world = new Matrix4f().perspective((float) Math.toRadians(70), 1.6f, NEAR, 256);
        float seen = layerDistance(world, world, new Vector4f(0, 0, -0.3f, 1));
        assertTrue(Math.abs(seen - 0.3f) < 1e-3 && seen > 2 * bound, "wall at 0.3 m: " + seen);
    }
}
