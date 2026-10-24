package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/** Standard sRGB framebuffer blending: Beer–Lambert attenuates radiance, never encoded screen values. */
final class LinearStage {
    private static int framebuffer = -1, texture = -1, width, height;
    private static int draw, read;
    private static final int[] VIEW = new int[4], SCISSOR = new int[4];
    private static boolean active, srgb, scissor;

    private LinearStage() {}

    static boolean active() { return active; }

    static void begin(int scene) {
        active = false;
        if (scene < 0) return; // With no depth attachment the layer needs the existing world-distance texture.
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEW);
        if (!ensure(VIEW[2], VIEW[3])) return;
        draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        srgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
        scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, SCISSOR);
        RenderSystem.disableScissor();
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, draw);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        // Disabled sRGB conversion copies the encoded world's bytes, not its linear radiance.
        GlStateManager._glBlitFrameBuffer(VIEW[0], VIEW[1], VIEW[0] + width, VIEW[1] + height,
                0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        RenderSystem.viewport(0, 0, width, height);
        if (scissor) RenderSystem.enableScissor(SCISSOR[0] - VIEW[0], SCISSOR[1] - VIEW[1], SCISSOR[2], SCISSOR[3]);
        GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
        active = true;
    }

    static void end() {
        if (!active) return;
        RenderSystem.disableScissor();
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        GlStateManager._glBlitFrameBuffer(0, 0, width, height,
                VIEW[0], VIEW[1], VIEW[0] + width, VIEW[1] + height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        RenderSystem.viewport(VIEW[0], VIEW[1], VIEW[2], VIEW[3]);
        if (scissor) RenderSystem.enableScissor(SCISSOR[0], SCISSOR[1], SCISSOR[2], SCISSOR[3]);
        if (srgb) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
        active = false;
    }

    private static boolean ensure(int w, int h) {
        if (framebuffer >= 0 && width == w && height == h) return true;
        if (texture < 0) texture = GlStateManager._genTexture();
        if (framebuffer < 0) framebuffer = GlStateManager.glGenFramebuffers();
        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL21.GL_SRGB8_ALPHA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null);
        int bound = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        boolean valid = GlStateManager.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, bound);
        if (valid) { width = w; height = h; }
        return valid;
    }
}
