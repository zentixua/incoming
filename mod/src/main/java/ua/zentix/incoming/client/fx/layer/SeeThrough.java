package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import ua.zentix.incoming.Incoming;

/**
 * See-through blocks in front of the effects layer: stained and tinted glass, ice, water, slime, honey, a nether portal hide
 * nothing behind them, they tint it. The layer draws after the world ({@code AFTER_LEVEL}), when translucent terrain has
 * already written its depth: tested against that depth, the mushroom, smoke and fire behind a window would vanish. So the
 * opaque world's depth is copied before translucent terrain and the frame's translucent surface after it. {@link SceneDepth}
 * turns them into the distance the layer is hidden by and the nearest translucent surface with how it lays the world under
 * it: the frame there is {@code X + T·world}, the surface's own light X and the share T it lets through. A layer fragment
 * behind the surface is laid as {@code T·light + opacity·X} ({@code see.glsl}): exactly the surface laid over the layer laid
 * over the world. One surface per pixel, as vanilla Fabulous: a second pane behind the first tints the layer as if it stood
 * in front of it.
 * <p>
 * Where X and T come from depends on who draws translucent terrain:
 * <ul>
 *   <li>{@link Mode#BLEND} (vanilla Fast/Fancy, Sodium): at {@code AFTER_BLOCK_ENTITIES} the opaque depth and the frame's
 *       colour are copied and its alpha cleared to 0; translucent blending (SRC_ALPHA, ONE_MINUS_SRC_ALPHA; alpha ONE,
 *       ONE_MINUS_SRC_ALPHA) then leaves its coverage 1 − T in alpha. At {@code AFTER_TRANSLUCENT_BLOCKS} the depth and
 *       colour are copied again: X = after − T·before. What is drawn later (lines, translucent entities, particles, clouds,
 *       weather) blends alpha its own way and is not in these copies: where it wrote depth it hides the layer as before;</li>
 *   <li>{@link Mode#FABULOUS}: vanilla draws translucent terrain into its own target from zero, which holds X (rgb),
 *       1 − T (alpha) and the surface depth; the main depth at {@code AFTER_TRANSLUCENT_BLOCKS} is the opaque world.</li>
 * </ul>
 * A shader pack composites translucent geometry in its own passes, and a camera mod's view draws into its own target: there
 * the layer keeps the world's depth ({@link Mode#NONE}), hidden behind translucent blocks as before. The copies are made
 * only while the layer drew in the previous frame: without effects the frame costs nothing, and the first frame of an
 * effect is tested against the world's depth.
 */
public final class SeeThrough {
    /** Who lays translucent terrain on the frame, and so where its transmittance is read. */
    public enum Mode {
        NONE, BLEND, FABULOUS;

        /** For {@code fx_depth}: the mode number. */
        float code() {
            return ordinal();
        }
    }

    /** Depth copies: the opaque world, and (BLEND) with translucent terrain; in the source's own internal format. */
    private static final Copy OPAQUE = new Copy(), SURFACE = new Copy();
    /** Frame colour before and after translucent terrain ({@link Mode#BLEND}). */
    private static final Copy BEFORE = new Copy(), AFTER = new Copy();
    /** Read framebuffer with the world's depth or colour attached for the copies. */
    private static int source = -1;
    /** The layer drew in the last primary frame: copy for this one. */
    private static boolean wanted;
    /** This frame's copies: started (the first stage of BLEND), complete. */
    private static boolean started, captured;
    private static Mode mode = Mode.NONE;
    /** Copies failed (format or driver): the layer keeps the world's depth for the session. */
    private static boolean broken;
    /** A copy was (re)made at a new size or format this stage: its first blit is checked for GL errors (the only sync). */
    private static boolean fresh;
    /** The world depth's internal format, and the texture and size it was read for. */
    private static int worldFormat, formatOf = -1, formatWidth, formatHeight;
    private static final float[] CLEAR = new float[4];
    private static final java.nio.ByteBuffer MASK = org.lwjgl.BufferUtils.createByteBuffer(16);

    private SeeThrough() {}

    /**
     * {@code AFTER_BLOCK_ENTITIES} and {@code AFTER_TRANSLUCENT_BLOCKS} of the primary view: the opaque world is complete
     * before the first, translucent terrain after the second.
     */
    static void capture(RenderLevelStageEvent e) {
        if (FxViews.secondary(e.getCamera()) || FxViews.secondary()) return;
        boolean first = e.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES;
        if (first) {
            started = captured = false;
            mode = Mode.NONE;
        }
        if (!wanted || broken || WaterDecal.shaderPack()) return;
        boolean fabulous = Minecraft.useShaderTransparency();
        // BLEND copies at both stages, Fabulous only after translucent terrain (it lies in its own target)
        if (!fabulous && !first && !started) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int world = main.getDepthTextureId(), colour = main.getColorTextureId(), w = main.width, h = main.height;
        if (world <= 0 || colour <= 0 || w <= 0 || h <= 0) return;
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        fresh = false;
        try {
            if (fabulous) {
                if (first) return;
                if (!copyDepth(OPAQUE, world, w, h)) return;
                mode = Mode.FABULOUS;
                captured = check("Fabulous depth copy");
            } else if (first) {
                if (!copyDepth(OPAQUE, world, w, h) || !copyColour(BEFORE, colour, w, h)) return;
                // alpha 0 under translucent terrain: its blending leaves the coverage there (vanilla and Sodium keep alpha
                // writes on; the alpha of the world frame is not read by anyone before the layer)
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
                GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, CLEAR);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, MASK);
                GlStateManager._colorMask(false, false, false, true);
                GL11.glClearColor(0, 0, 0, 0);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                GL11.glClearColor(CLEAR[0], CLEAR[1], CLEAR[2], CLEAR[3]);
                GlStateManager._colorMask(MASK.get(0) != 0, MASK.get(1) != 0, MASK.get(2) != 0, MASK.get(3) != 0);
                started = check("copy before translucent terrain");
            } else {
                if (!copyDepth(SURFACE, world, w, h) || !copyColour(AFTER, colour, w, h)) return;
                mode = Mode.BLEND;
                captured = check("copy after translucent terrain");
            }
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        }
    }

    /**
     * After a stage's copies: a copy made at a new size or format gave no GL error (a driver refusing the depth format);
     * otherwise the layer keeps the world's depth for the session. Steady frames do not query (no driver sync).
     */
    private static boolean check(String what) {
        if (!fresh) return true;
        int error = GL11.glGetError();
        return error == GL11.GL_NO_ERROR || fail(what + " failed, GL error 0x" + Integer.toHexString(error));
    }

    /** The world's depth into {@code to} (its own format; recreated at a new size or format). */
    private static boolean copyDepth(Copy to, int world, int w, int h) {
        int format = format(world, w, h);
        if (!to.ensure(format, w, h)) return fail("depth copy framebuffer incomplete, format 0x" + Integer.toHexString(format));
        blit(world, GL30.GL_DEPTH_ATTACHMENT, to.fbo, w, h, GL11.GL_DEPTH_BUFFER_BIT);
        return true;
    }

    /** The world depth's internal format, queried only for a new texture or size (the bound texture is restored). */
    private static int format(int world, int w, int h) {
        if (world != formatOf || w != formatWidth || h != formatHeight) {
            int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GlStateManager._bindTexture(world);
            worldFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL30.GL_TEXTURE_INTERNAL_FORMAT);
            GlStateManager._bindTexture(bound);
            formatOf = world;
            formatWidth = w;
            formatHeight = h;
        }
        return worldFormat;
    }

    /** The frame's colour into {@code to}. */
    private static boolean copyColour(Copy to, int colour, int w, int h) {
        if (!to.ensure(GL11.GL_RGBA8, w, h)) return fail("colour copy framebuffer incomplete");
        blit(colour, GL30.GL_COLOR_ATTACHMENT0, to.fbo, w, h, GL11.GL_COLOR_BUFFER_BIT);
        return true;
    }

    private static void blit(int texture, int attachment, int to, int w, int h, int bits) {
        if (source < 0) {
            source = GlStateManager.glGenFramebuffers();
            // read-only framebuffer: no draw buffer, or it is incomplete without a colour attachment (GL 3.3)
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, source);
            GL11.glDrawBuffer(GL11.GL_NONE);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glReadBuffer(attachment == GL30.GL_COLOR_ATTACHMENT0 ? GL30.GL_COLOR_ATTACHMENT0 : GL11.GL_NONE);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, bits, GL11.GL_NEAREST);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, 0, 0);
        GL11.glReadBuffer(GL11.GL_NONE);
    }

    /** The primary layer drew this frame ({@code true}) or had nothing to draw: whether the next frame copies. */
    static void drew(boolean drew) {
        if (!FxViews.secondary()) wanted = drew;
    }

    /** How this frame's translucent terrain was laid ({@link Mode#NONE}: no copies, or a secondary view). */
    static Mode mode() {
        return captured && !FxViews.secondary() ? mode : Mode.NONE;
    }

    /** The opaque world's depth (both modes). */
    static int opaque() {
        return OPAQUE.texture;
    }

    /** {@link Mode#BLEND}: the depth with translucent terrain, the frame before and after it. */
    static int surface() {
        return SURFACE.texture;
    }

    static int before() {
        return BEFORE.texture;
    }

    static int after() {
        return AFTER.texture;
    }

    /** {@link Mode#FABULOUS}: vanilla's translucent target (colour X, alpha 1 − T, depth); null — none. */
    @Nullable
    static RenderTarget translucent() {
        return Minecraft.getInstance().levelRenderer.getTranslucentTarget();
    }

    /** End of the primary frame's layer: the next frame copies again only if wanted. */
    static void endFrame() {
        if (!FxViews.secondary()) started = captured = false;
    }

    private static boolean fail(String why) {
        broken = true;
        captured = started = false;
        Incoming.LOG.warn("Effects layer: {}; effects behind see-through blocks stay hidden by them", why);
        return false;
    }

    /** {@code describe} line part: the mode of the last frame. */
    static String describe() {
        return broken ? "broken" : mode.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** A frame-sized copy: texture and its draw framebuffer. */
    private static final class Copy {
        /** Texture name, its draw framebuffer, internal format and size; −1 — not made yet. */
        int texture = -1, fbo = -1, format, width, height;

        /** Texture of this format and size with its framebuffer; false — incomplete. */
        boolean ensure(int internal, int w, int h) {
            if (texture >= 0 && internal == format && w == width && h == height) return true;
            // earlier errors are not this stage's: drained once, before its first new copy, so the stage's check sees its own
            if (!fresh) for (int i = 0; i < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {}
            fresh = true;
            if (texture < 0) texture = GlStateManager._genTexture();
            if (fbo < 0) fbo = GlStateManager.glGenFramebuffers();
            format = internal;
            width = w;
            height = h;
            boolean depth = internal != GL11.GL_RGBA8;
            boolean stencil = internal == GL30.GL_DEPTH24_STENCIL8 || internal == GL30.GL_DEPTH32F_STENCIL8 || internal == GL30.GL_DEPTH_STENCIL;
            int type = !depth ? GL11.GL_UNSIGNED_BYTE : internal == GL30.GL_DEPTH24_STENCIL8 || internal == GL30.GL_DEPTH_STENCIL ? GL30.GL_UNSIGNED_INT_24_8
                    : internal == GL30.GL_DEPTH32F_STENCIL8 ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL11.GL_FLOAT;
            int pixel = !depth ? GL11.GL_RGBA : stencil ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT;
            GlStateManager._bindTexture(texture);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, internal, w, h, 0, pixel, type, null);
            int read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
            GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, depth ? GL30.GL_DEPTH_ATTACHMENT : GL30.GL_COLOR_ATTACHMENT0,
                    GlConst.GL_TEXTURE_2D, texture, 0);
            if (depth) {
                GL11.glDrawBuffer(GL11.GL_NONE);
                GL11.glReadBuffer(GL11.GL_NONE);
            }
            boolean ok = GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            return ok;
        }
    }
}
