package ua.zentix.incoming.client.render;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Источник глубины Voxy читает его классы отражением (подделки тех же имён — в тестовых исходниках
 * {@code me.cortex.voxy.client.core}): текстура и проекция кадра, кадр, который Voxy не рисовал, не берётся, а без
 * классов Voxy источника нет — игра идёт как без него.
 */
class VoxyDepthTest {
    private static final float FOV = (float) Math.toRadians(70), ASPECT = 2048f / 1272f;

    @AfterEach
    void clear() {
        VoxyRenderSystem.current = null;
    }

    @Test
    void readsTextureAndProjectionOfTheFrame() throws ReflectiveOperationException {
        VoxyRenderSystem system = new VoxyRenderSystem();
        VoxyRenderSystem.current = system;
        Viewport view = system.getViewport();
        // проекция Voxy: угол и стороны кадра Minecraft, свои плоскости (дальняя — на десятки километров)
        view.projection = new Matrix4f().setPerspective(FOV, ASPECT, 16, 60_000);
        view.frameId = 5;
        VoxyDepth voxy = new VoxyDepth(VoxyDepthTest.class.getClassLoader());
        LodDepth.View out = new LodDepth.View();
        assertTrue(voxy.current(out));
        assertEquals(17, out.texture);
        assertTrue(out.negativeOneToOne, "без glClipControl глубина Voxy — NDC от −1 до 1");
        assertEquals(1f, out.empty);
        assertTrue(out.pinsWorldDepth);
        for (float d : new float[]{300, 1500, 3000, 12_000}) {
            Vector4f clip = view.projection.transform(new Vector4f(0, 0, -d, 1));
            float depth = clip.z / clip.w * 0.5f + 0.5f;
            Vector4f v = out.inverseProjection.transform(new Vector4f(0, 0, depth * 2 - 1, 1));
            assertEquals(d, -v.z / v.w, d * 2e-3f, "LOD в " + d + " блоках");
        }
        voxy.endFrame();
        assertFalse(voxy.current(out), "Voxy не рисовал с прошлого кадра — глубина не этого кадра");
        view.frameId++;
        assertTrue(voxy.current(out));
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
}
