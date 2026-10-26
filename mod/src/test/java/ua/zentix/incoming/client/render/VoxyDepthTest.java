package ua.zentix.incoming.client.render;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The depth source Voxy reads its classes by reflection (fakes of the same names - in the test sources
 * {@code me.cortex.voxy.client.core}): texture and projection of the frame, pixel label Minecraft, frame that Voxy did not draw, is not taken, but without classes Voxy there is no source - the game runs as if without it. The numbers are the same as Artem’s: frame
 * 2048×1272, angle 70°, drawing 12 chunks.
 */
class VoxyDepthTest {
    private static final float FOV = (float) Math.toRadians(70), ASPECT = 2048f / 1272f;
    /** Planes Voxy 0.2.16 ({@code VoxyRenderSystem.computeProjectionMat}): near 16 blocks (drawing > 2 chunks), distant 48 km. */
    private static final float VOXY_NEAR = 16, VOXY_FAR = 48_000;
    /** Far plane Minecraft when drawing 12 chunks: 4 × 192 block ({@code GameRenderer.getDepthFar}). */
    private static final float MC_FAR = 768;

    @AfterEach
    void clear() {
        VoxyRenderSystem.current = null;
    }

    /** The depth in texture (0..1) of a point on the visual axis in d blocks, recorded by the projection p (NDC from −1 to 1). */
    private static float depth(Matrix4f p, float d) {
        Vector4f clip = p.transform(new Vector4f(0, 0, -d, 1));
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    private static float along(Matrix4f inverse, float depth) {
        Vector4f v = inverse.transform(new Vector4f(0, 0, depth * 2 - 1, 1));
        return -v.z / v.w;
    }

    @Test
    void readsTextureAndProjectionOfTheFrame() throws ReflectiveOperationException {
        VoxyRenderSystem system = new VoxyRenderSystem();
        VoxyRenderSystem.current = system;
        Viewport view = system.getViewport();
        view.projection = new Matrix4f().setPerspective(FOV, ASPECT, VOXY_NEAR, VOXY_FAR);
        view.frameId = 5;
        VoxyDepth voxy = new VoxyDepth(VoxyDepthTest.class.getClassLoader());
        LodDepth.View out = new LodDepth.View();
        assertTrue(voxy.current(out));
        assertEquals(17, out.texture);
        assertTrue(out.negativeOneToOne, "without glClipControl depth Voxy - NDC from −1 to 1");
        assertEquals(1f, out.empty, "sky - clearing");
        assertEquals(0f, out.world, "pixel Minecraft Voxy marks the near plane");
        assertTrue(out.pinsWorldDepth);
        for (float d : new float[]{300, 1500, 3000, 12_000, 30_000}) {
            assertEquals(d, along(out.inverseProjection, depth(view.projection, d)), d * 2e-3f, "LOD in " + d + " blocks");
        }
        voxy.endFrame();
        assertFalse(voxy.current(out), "Voxy has not drawn since the last frame - the depth is not this frame");
        view.frameId++;
        assertTrue(voxy.current(out));
        voxy.endFrame();
        // frame without effects: the layer did not take depth, Voxy painted; in the next Voxy did not draw - its depth is past
        view.frameId++;
        voxy.endFrame();
        assertFalse(voxy.current(out), "depth of the frame without effects - not this frame");
    }

    @Test
    void minecraftPixelsAreNotLodAtTheNearPlane() throws ReflectiveOperationException, IOException, URISyntaxException {
        VoxyRenderSystem system = new VoxyRenderSystem();
        VoxyRenderSystem.current = system;
        system.getViewport().projection = new Matrix4f().setPerspective(FOV, ASPECT, VOXY_NEAR, VOXY_FAR);
        LodDepth.View out = new LodDepth.View();
        assertTrue(new VoxyDepth(VoxyDepthTest.class.getClassLoader()).current(out));
        // a label read as LOD puts a "ground" in 16 blocks behind each world pixel: the smoke in 30 blocks in front of the
        //  wall in 100 would disappear
        assertEquals(VOXY_NEAR, along(out.inverseProjection, out.world), 0.1f);
        // therefore fx_depth skips both cleaning and marking
        assertTrue(fxDepth().contains("abs(lod - LodWorld) > 1e-7"), "fx_depth does not skip the peace mark");
    }

    @Test
    void pinnedLodIsTellableFromBlocks() throws IOException, URISyntaxException {
        // Voxy without a shaderpack places LOD further than the far plane Minecraft into its depth by 2 step 24 bit NDC is closer to the
        //  plane (blit_texture_depth_cutout: min(FAR − 2/(2²⁴−1), z)), in texture - step below 1
        Matcher m = Pattern.compile("PINNED = 1\\.0 - (\\d+)\\.0 / 16777215\\.0").matcher(fxDepth());
        assertTrue(m.find(), "in fx_depth there is no threshold pressed LOD");
        double pinned = 1 - Integer.parseInt(m.group(1)) / 16777215.0;
        double unit = 1 / 16777215.0;
        double written = Math.round((1 - 2 * unit + 1) / 2 / unit) * unit;
        assertTrue(written >= pinned, "pressed LOD " + written + " below threshold " + pinned);
        // the block at the very edge of the drawing (its farthest plane is four times further) - far below the threshold
        Matrix4f minecraft = new Matrix4f().setPerspective(FOV, ASPECT, 0.05f, MC_FAR);
        assertTrue(depth(minecraft, MC_FAR / 4 * 1.2f) < pinned);
        assertTrue(depth(minecraft, MC_FAR * 0.99f) < pinned);
    }

    @Test
    void noRendererNoDepth() throws ReflectiveOperationException {
        VoxyDepth voxy = new VoxyDepth(VoxyDepthTest.class.getClassLoader());
        assertFalse(voxy.current(new LodDepth.View()));
    }

    @Test
    void withoutVoxyClassesThereIsNoSource() {
        ClassLoader empty = new ClassLoader(null) {};
        assertThrows(ClassNotFoundException.class, () -> new VoxyDepth(empty));
    }

    private static String fxDepth() throws IOException, URISyntaxException {
        return Files.readString(Path.of(VoxyDepthTest.class.getResource("/assets/incoming/shaders/core/fx_depth.fsh").toURI()));
    }
}
