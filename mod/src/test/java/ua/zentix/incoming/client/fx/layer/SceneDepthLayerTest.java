package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

/**
 * The effects layer's framebuffer ({@link SceneDepth#bindLayer}) must draw into the frame's current colour texture even
 * when that texture keeps its name. A GUI-scale or Force Unicode change recreates the main target at the same size
 * ({@code Minecraft.resizeDisplay}); the driver hands the freed colour name back (Mesa llvmpipe and NVIDIA do; Minecraft's
 * randomized names in the IDE hide it). An attachment kept by name then points at the deleted texture, and nothing the
 * layer draws reaches the screen behind water, glass, ice or portals. GL is mocked: the same name both frames.
 */
class SceneDepthLayerTest {
    private static final int FRAME = 9, COLOUR = 7, HARD = 3, LAYER = 11;
    private static final String[] FIELDS = {"see", "hard", "layerFbo", "layerDraw", "layerRead"};
    private final Object[] saved = new Object[FIELDS.length];
    private MockedStatic<GlStateManager> gsm;
    private MockedStatic<GL30> gl30;

    @BeforeEach
    void mockGl() throws ReflectiveOperationException {
        for (int i = 0; i < FIELDS.length; i++) saved[i] = field(FIELDS[i]).get(null);
        field("see").setBoolean(null, true);
        field("hard").setInt(null, HARD);
        field("layerFbo").setInt(null, -1);
        gsm = mockStatic(GlStateManager.class);
        gsm.when(() -> GlStateManager._getInteger(anyInt())).thenReturn(FRAME);
        gsm.when(GlStateManager::glGenFramebuffers).thenReturn(LAYER);
        gsm.when(() -> GlStateManager.glCheckFramebufferStatus(anyInt())).thenReturn(GlConst.GL_FRAMEBUFFER_COMPLETE);
        gl30 = mockStatic(GL30.class);
        gl30.when(() -> GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)).thenReturn(GL11.GL_TEXTURE);
        gl30.when(() -> GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)).thenReturn(COLOUR);
    }

    @AfterEach
    void restore() throws ReflectiveOperationException {
        gl30.close();
        gsm.close();
        for (int i = 0; i < FIELDS.length; i++) field(FIELDS[i]).set(null, saved[i]);
    }

    @Test
    void colourIsAttachedOnEveryBindEvenUnderTheSameName() {
        assertTrue(SceneDepth.bindLayer(), "first frame binds the layer");
        SceneDepth.unbindLayer();
        // GUI scale changed: the main target's colour texture was deleted and recreated under the same name
        assertTrue(SceneDepth.bindLayer(), "next frame binds the layer");
        SceneDepth.unbindLayer();
        gsm.verify(() -> GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0,
                GlConst.GL_TEXTURE_2D, COLOUR, 0), times(2));
        gsm.verify(() -> GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_DEPTH_ATTACHMENT,
                GlConst.GL_TEXTURE_2D, HARD, 0), times(2));
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field f = SceneDepth.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
