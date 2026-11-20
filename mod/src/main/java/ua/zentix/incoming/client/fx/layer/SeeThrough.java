package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import ua.zentix.incoming.Incoming;

/**
 * See-through blocks in front of the effects layer: stained and tinted glass, ice, water, slime, honey, a nether portal,
 * translucent entities, particles and clouds hide nothing behind them, they tint it. The layer draws after the world
 * ({@code AFTER_LEVEL}), when translucent geometry has already written its depth: tested against that depth, the
 * mushroom, smoke and fire behind a window vanished (vanilla, Sodium, Iris). Here, at {@code AFTER_BLOCK_ENTITIES} (the
 * opaque world is drawn, translucent geometry not yet), the opaque world's depth is copied. {@link SceneDepth} turns it
 * into the distance the layer is hidden by, and keeps the nearer translucent surface with how it lays the world under it:
 * the frame there is {@code X + T·world}, the surface's own light X and the share T it lets through. A layer fragment
 * behind the surface is laid as {@code T·light + opacity·X} ({@code see.glsl}), which is exactly the surface laid over
 * the layer laid over the world.
 * <p>
 * Where X and T come from depends on who draws translucent geometry:
 * <ul>
 *   <li>{@link Mode#BLEND} (vanilla Fast/Fancy, Sodium, Iris without a shader pack): the frame's colour before translucent
 *       geometry is copied and its alpha cleared to 0; translucent blending (SRC_ALPHA, ONE_MINUS_SRC_ALPHA; alpha ONE,
 *       ONE_MINUS_SRC_ALPHA) then leaves its coverage 1 − T in alpha, and X = frame − T·before;</li>
 *   <li>{@link Mode#FABULOUS}: vanilla draws translucent terrain into its own target from zero, which holds X (rgb), 1 − T
 *       (alpha) and the surface depth;</li>
 *   <li>{@link Mode#PACK} (Iris with a shader pack): the pack composites its own translucent buffers; their transmittance
 *       is not reachable, so the layer lies over the surface (T = 1, X = 0): the effect is seen through it, untinted.</li>
 * </ul>
 * The copy is made only while the layer drew in the previous frame: without effects the frame costs nothing.
 */
public final class SeeThrough {
    /** Who lays translucent geometry on the frame, and so where its transmittance is read. */
    public enum Mode {
        NONE, BLEND, FABULOUS, PACK;

        /** For {@code fx_depth}: the mode number. */
        float code() {
            return ordinal();
        }
    }

    /** Copy of the opaque world's depth (the source's own internal format: a depth blit needs the same one). */
    private static int depth = -1, depthFbo = -1, depthFormat, sourceDepth = -1;
    /** Frame colour before translucent geometry ({@link Mode#BLEND}), and the size it was made at. */
    private static int before = -1, beforeFbo = -1, beforeWidth, beforeHeight;
    /** Read framebuffer with the world's depth or colour attached for the copies. */
    private static int source = -1;
    private static int width, height;
    /** The layer drew in the last frame: copy for this one. */
    private static boolean wanted;
    /** This frame's copy was made, in this mode. */
    private static boolean captured;
    private static Mode mode = Mode.NONE;
    /** The next copy is the first at a new size or format: check it. */
    private static boolean check;
    /** Copies failed (format or driver): the layer keeps the world's depth for the session (every view). */
    private static boolean broken;
    private static final float[] CLEAR = new float[4];
    private static final java.nio.ByteBuffer MASK = org.lwjgl.BufferUtils.createByteBuffer(16);

    private SeeThrough() {}

    /**
     * {@code AFTER_BLOCK_ENTITIES}: the opaque world is complete, translucent geometry follows. A secondary view (a camera
     * mod's screen, {@link FxViews}) copies into its own state, only in {@link Mode#BLEND}: Fabulous and shader-pack
     * targets belong to the primary frame.
     */
    static void capture(RenderLevelStageEvent e) {
        boolean secondary = FxViews.secondary(e.getCamera());
        // a secondary camera before its scope began, or skipped (unsupported target): its copy is not this view's
        if (secondary && (!FxViews.secondary() || FxViews.skipped())) return;
        captured = false;
        if (!wanted || broken) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        int world = FxViews.depth(main.getDepthTextureId());
        int w = FxViews.width(main.width), h = FxViews.height(main.height);
        if (world <= 0 || w <= 0 || h <= 0) return;
        Mode m = WaterDecal.shaderPack() ? Mode.PACK : Minecraft.useShaderTransparency() ? Mode.FABULOUS : Mode.BLEND;
        if (secondary && m != Mode.BLEND) return;
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        // BLEND: the colour of the frame being drawn (the bound framebuffer's: the main target's, or a secondary view's)
        int colour = m == Mode.BLEND ? colour(draw) : 0;
        if (m == Mode.BLEND && colour <= 0) return;
        if (!ensure(world, w, h, m == Mode.BLEND)) {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            return;
        }
        if (check) {
            // earlier errors are not this copy's
            for (int i = 0; i < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {}
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, world, 0);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depthFbo);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
        if (m == Mode.BLEND) {
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colour, 0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, beforeFbo);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            GL11.glReadBuffer(GL11.GL_NONE);
            // alpha 0 under translucent geometry: its blending leaves the coverage there (vanilla and Sodium keep alpha
            // writes on; the alpha of the world frame is not read by anyone before the layer)
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, CLEAR);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, MASK);
            GlStateManager._colorMask(false, false, false, true);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glClearColor(CLEAR[0], CLEAR[1], CLEAR[2], CLEAR[3]);
            GlStateManager._colorMask(MASK.get(0) != 0, MASK.get(1) != 0, MASK.get(2) != 0, MASK.get(3) != 0);
        }
        if (check) {
            // first copy at this size: a driver that refuses the depth blit (format) leaves the world's depth for the session
            check = false;
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) fail("depth copy failed, GL error 0x" + Integer.toHexString(error));
        }
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        if (broken) return;
        mode = m;
        captured = true;
    }

    /** Colour texture of draw framebuffer {@code fbo} (attachment 0); 0 — none or not a texture (the window framebuffer). */
    static int colour(int fbo) {
        if (fbo == 0) return 0;
        int bound = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (bound != fbo) GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        int name = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) == GL11.GL_TEXTURE
                ? GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)
                : 0;
        if (bound != fbo) GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, bound);
        return name;
    }

    /**
     * The layer drew this frame ({@code true}) or had nothing to draw: whether the next frame copies. One frame of a newly
     * started effect is tested against the world's depth, as before.
     */
    static void drew(boolean drew) {
        wanted = drew;
    }

    /** This frame's opaque depth copy for {@link SceneDepth} (this view's); −1 — none. */
    static int depth() {
        return captured ? depth : -1;
    }

    /** How this frame's translucent geometry was laid ({@link Mode#NONE} without a copy). */
    static Mode mode() {
        return depth() > 0 ? mode : Mode.NONE;
    }

    /** {@link Mode#BLEND}: the frame before translucent geometry. */
    static int before() {
        return before;
    }

    /** {@link Mode#FABULOUS}: vanilla's translucent target (colour X, alpha 1 − T, depth); null — none. */
    @org.jetbrains.annotations.Nullable
    static RenderTarget translucent() {
        return Minecraft.getInstance().levelRenderer.getTranslucentTarget();
    }

    /** End of the frame's layer: next frame copies again only if wanted. */
    static void endFrame() {
        captured = false;
    }

    /** Textures and framebuffers at the frame size; the depth copy in the world depth's own format. */
    private static boolean ensure(int world, int w, int h, boolean colour) {
        GlStateManager._bindTexture(world);
        int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL30.GL_TEXTURE_INTERNAL_FORMAT);
        if (source < 0) {
            source = GlStateManager.glGenFramebuffers();
            // read-only framebuffer: no colour read or draw buffer, or it is incomplete without a colour attachment (GL 3.3)
            int read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, source);
            GL11.glReadBuffer(GL11.GL_NONE);
            GL11.glDrawBuffer(GL11.GL_NONE);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        }
        if (depth < 0 || w != width || h != height || world != sourceDepth || format != depthFormat) {
            check = true;
            width = w;
            height = h;
            sourceDepth = world;
            depthFormat = format;
            if (depth < 0) depth = GlStateManager._genTexture();
            if (depthFbo < 0) depthFbo = GlStateManager.glGenFramebuffers();
            boolean stencil = format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8 || format == GL30.GL_DEPTH_STENCIL;
            int type = format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH_STENCIL ? GL30.GL_UNSIGNED_INT_24_8
                    : format == GL30.GL_DEPTH32F_STENCIL8 ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL11.GL_FLOAT;
            parameters(depth);
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, format, w, h, 0, stencil ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT, type, null);
            if (!complete(depthFbo, GL30.GL_DEPTH_ATTACHMENT, depth)) return fail("depth copy framebuffer incomplete, format 0x" + Integer.toHexString(format));
        }
        // its own size: a copy made in another mode (a shader pack) at another window size must not be read as this one
        if (colour && (before < 0 || beforeWidth != w || beforeHeight != h)) {
            check = true;
            if (before < 0) before = GlStateManager._genTexture();
            if (beforeFbo < 0) beforeFbo = GlStateManager.glGenFramebuffers();
            parameters(before);
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null);
            beforeWidth = w;
            beforeHeight = h;
            if (!complete(beforeFbo, GL30.GL_COLOR_ATTACHMENT0, before)) return fail("colour copy framebuffer incomplete");
        }
        return true;
    }

    private static void parameters(int texture) {
        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
    }

    private static boolean complete(int fbo, int attachment, int texture) {
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, attachment, GlConst.GL_TEXTURE_2D, texture, 0);
        if (attachment == GL30.GL_DEPTH_ATTACHMENT) {
            GL11.glDrawBuffer(GL11.GL_NONE);
            GL11.glReadBuffer(GL11.GL_NONE);
        }
        boolean ok = GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        return ok;
    }

    private static boolean fail(String why) {
        broken = true;
        Incoming.LOG.warn("Effects layer: {}; effects behind see-through blocks stay hidden by them", why);
        return false;
    }

    /** Saved only for secondary views; swapping leaves the primary path unchanged. */
    static final class ViewState {
        private int depth = -1, depthFbo = -1, depthFormat, sourceDepth = -1, before = -1, beforeFbo = -1, beforeWidth, beforeHeight;
        private int source = -1, width, height;
        private boolean wanted, captured, check;
        private Mode mode = Mode.NONE;

        void close() {
            FxViews.textures(depth, before);
            FxViews.framebuffers(depthFbo, beforeFbo, source);
        }

        void swap() {
            int t;
            t = SeeThrough.depth; SeeThrough.depth = depth; depth = t;
            t = SeeThrough.depthFbo; SeeThrough.depthFbo = depthFbo; depthFbo = t;
            t = SeeThrough.depthFormat; SeeThrough.depthFormat = depthFormat; depthFormat = t;
            t = SeeThrough.sourceDepth; SeeThrough.sourceDepth = sourceDepth; sourceDepth = t;
            t = SeeThrough.before; SeeThrough.before = before; before = t;
            t = SeeThrough.beforeFbo; SeeThrough.beforeFbo = beforeFbo; beforeFbo = t;
            t = SeeThrough.beforeWidth; SeeThrough.beforeWidth = beforeWidth; beforeWidth = t;
            t = SeeThrough.beforeHeight; SeeThrough.beforeHeight = beforeHeight; beforeHeight = t;
            t = SeeThrough.source; SeeThrough.source = source; source = t;
            t = SeeThrough.width; SeeThrough.width = width; width = t;
            t = SeeThrough.height; SeeThrough.height = height; height = t;
            boolean b;
            b = SeeThrough.wanted; SeeThrough.wanted = wanted; wanted = b;
            b = SeeThrough.captured; SeeThrough.captured = captured; captured = b;
            b = SeeThrough.check; SeeThrough.check = check; check = b;
            Mode m = SeeThrough.mode; SeeThrough.mode = mode; mode = m;
        }
    }

    /** {@code describe} line part: the mode of the last frame. */
    static String describe() {
        return broken ? "broken" : mode.name().toLowerCase(java.util.Locale.ROOT);
    }
}
