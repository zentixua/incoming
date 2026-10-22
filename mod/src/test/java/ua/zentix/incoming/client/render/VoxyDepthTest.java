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
 * Источник глубины Voxy читает его классы отражением (подделки тех же имён — в тестовых исходниках
 * {@code me.cortex.voxy.client.core}): текстура и проекция кадра, метка пикселей Minecraft, кадр, который Voxy не
 * рисовал, не берётся, а без классов Voxy источника нет — игра идёт как без него. Числа — как у Артёма: кадр
 * 2048×1272, угол 70°, прорисовка 12 чанков.
 */
class VoxyDepthTest {
    private static final float FOV = (float) Math.toRadians(70), ASPECT = 2048f / 1272f;
    /** Плоскости Voxy 0.2.16 ({@code VoxyRenderSystem.computeProjectionMat}): ближняя 16 блоков (прорисовка > 2 чанков), дальняя 48 км. */
    private static final float VOXY_NEAR = 16, VOXY_FAR = 48_000;
    /** Дальняя плоскость Minecraft при прорисовке 12 чанков: 4 × 192 блока ({@code GameRenderer.getDepthFar}). */
    private static final float MC_FAR = 768;

    @AfterEach
    void clear() {
        VoxyRenderSystem.current = null;
    }

    /** Глубина в текстуре (0..1) точки на оси взгляда в d блоках, записанная проекцией p (NDC от −1 до 1). */
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
        assertTrue(out.negativeOneToOne, "без glClipControl глубина Voxy — NDC от −1 до 1");
        assertEquals(1f, out.empty, "небо — очистка");
        assertEquals(0f, out.world, "пиксель Minecraft Voxy метит ближней плоскостью");
        assertTrue(out.pinsWorldDepth);
        for (float d : new float[]{300, 1500, 3000, 12_000, 30_000}) {
            assertEquals(d, along(out.inverseProjection, depth(view.projection, d)), d * 2e-3f, "LOD в " + d + " блоках");
        }
        voxy.endFrame();
        assertFalse(voxy.current(out), "Voxy не рисовал с прошлого кадра — глубина не этого кадра");
        view.frameId++;
        assertTrue(voxy.current(out));
    }

    @Test
    void minecraftPixelsAreNotLodAtTheNearPlane() throws ReflectiveOperationException, IOException, URISyntaxException {
        VoxyRenderSystem system = new VoxyRenderSystem();
        VoxyRenderSystem.current = system;
        system.getViewport().projection = new Matrix4f().setPerspective(FOV, ASPECT, VOXY_NEAR, VOXY_FAR);
        LodDepth.View out = new LodDepth.View();
        assertTrue(new VoxyDepth(VoxyDepthTest.class.getClassLoader()).current(out));
        // метка, прочитанная как LOD, ставит «землю» в 16 блоках за каждым пикселем мира: дым в 30 блоках перед
        // стеной в 100 пропал бы
        assertEquals(VOXY_NEAR, along(out.inverseProjection, out.world), 0.1f);
        // поэтому fx_depth пропускает и очистку, и метку
        assertTrue(fxDepth().contains("abs(lod - LodWorld) > 1e-7"), "fx_depth не пропускает метку мира");
    }

    @Test
    void pinnedLodIsTellableFromBlocks() throws IOException, URISyntaxException {
        // Voxy без шейдерпака кладёт LOD дальше дальней плоскости Minecraft в её глубину на 2 шага 24 бит NDC ближе
        // плоскости (blit_texture_depth_cutout: min(FAR − 2/(2²⁴−1), z)), в текстуре — шаг ниже 1
        Matcher m = Pattern.compile("PINNED = 1\\.0 - (\\d+)\\.0 / 16777215\\.0").matcher(fxDepth());
        assertTrue(m.find(), "в fx_depth нет порога прижатого LOD");
        double pinned = 1 - Integer.parseInt(m.group(1)) / 16777215.0;
        double unit = 1 / 16777215.0;
        double written = Math.round((1 - 2 * unit + 1) / 2 / unit) * unit;
        assertTrue(written >= pinned, "прижатый LOD " + written + " ниже порога " + pinned);
        // блок у самого края прорисовки (её дальняя плоскость вчетверо дальше) — далеко ниже порога
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
