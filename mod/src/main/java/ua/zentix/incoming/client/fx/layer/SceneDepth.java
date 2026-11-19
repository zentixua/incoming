package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Uniform;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.render.LodDepth;

import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * World distance behind each frame pixel in one texture (metres along the view axis, 32-bit), from
 * Minecraft depth and LOD mod depth ({@link LodDepth}: Distant Horizons or Voxy; nearest of the two; sky — 10⁹). The effects
 * layer reads it for billows to fade softly into ground or walls, and disappear behind LOD that
 * hardware depth cannot see.
 * <p>
 * Separate pass to a custom texture, rather than reading depth directly in the particle shader: Minecraft depth is attached
 * to the layer's target framebuffer, and OpenGL forbids reading a texture being rendered into.
 */
public final class SceneDepth {
    @Nullable
    static ShaderInstance shader;
    /**
     * Two distance textures, this frame's and the previous one ({@link #previous}): the pall accumulation checks that history it
     * reprojects came from the same place ({@code fx_pall_resolve}).
     */
    private static int[] FBOS = {-1, -1}, TEXTURES = {-1, -1};
    private static int fbo = -1, texture = -1, width, height, current;
    /**
     * See-through surfaces ({@link SeeThrough}): distance to the nearer translucent surface (R32F), its own light and
     * transmittance (RGBA8, {@code see.glsl}), and the layer's hardware depth (the opaque world and the surfaces it must not
     * look through); one each, attached to both framebuffers.
     */
    private static int surface = -1, glass = -1, hard = -1;
    /** Framebuffer the layer draws through: the frame's colour with {@link #hard}; the colour texture it was made for. */
    private static int layerFbo = -1, layerColour = -1, layerDraw = -1, layerRead = -1;
    /** This frame looks through see-through surfaces. */
    private static boolean see;
    private static final Vector4f UP = new Vector4f();
    /** The other texture holds the frame before this one: the last two updates both succeeded at this size. */
    private static boolean previousValid, lastOk;
    private static LodDepth.View LOD = new LodDepth.View();
    private static Matrix4f INVERSE = new Matrix4f();
    private static int[] VIEWPORT = new int[4];
    /** Last frame's layer occlusion: 0 — none, 1 — world, 2 — world and LOD. */
    private static int last;
    /** Explicit readback diagnostics ({@code -Dincoming.nukeDiag=true}): distance columns and LOD coverage. */
    private static final boolean DIAG = Boolean.getBoolean("incoming.nukeDiag");
    private static final float[] DIAG_ROWS = {0.1f, 0.3f, 0.45f, 0.55f, 0.65f, 0.75f, 0.9f};
    private static long nextDiag;

    private SceneDepth() {}

    /**
     * Fill this frame's distance texture from the world projection; −1 — failed (shader not loaded, framebuffer
     * lacks depth): draw the layer without soft edges then.
     */
    static int update(Matrix4f projection, Matrix4f modelView) {
        ShaderInstance s = shader;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int depth = FxViews.depth(main.getDepthTextureId());
        int w = FxViews.width(main.width), h = FxViews.height(main.height);
        last = 0;
        see = false;
        boolean was = lastOk;
        lastOk = false;
        previousValid = false;
        if (s == null || depth <= 0) return -1;
        // see-through surfaces: the opaque world's depth copied before translucent geometry, the depth and colour after it
        int opaque = SeeThrough.depth();
        SeeThrough.Mode mode = SeeThrough.mode();
        RenderTarget translucent = mode == SeeThrough.Mode.FABULOUS ? SeeThrough.translucent() : null;
        if (mode == SeeThrough.Mode.FABULOUS && (translucent == null || translucent.getDepthTextureId() <= 0)) mode = SeeThrough.Mode.NONE;
        if (mode == SeeThrough.Mode.NONE) opaque = -1;
        int surfaceDepth = translucent != null ? translucent.getDepthTextureId() : depth;
        int now = main.getColorTextureId();
        int other = mode == SeeThrough.Mode.BLEND ? SeeThrough.before() : translucent != null ? translucent.getColorTextureId() : now;
        boolean sized = FBOS[0] >= 0 && w == width && h == height;
        if (!ensure(w, h)) return -1;
        current = 1 - current;
        fbo = FBOS[current];
        texture = TEXTURES[current];
        previousValid = was && sized;
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        boolean lod = !FxViews.secondary() && LodDepth.current(LOD);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        RenderSystem.viewport(0, 0, width, height);
        // every pixel writes the layer's hardware depth
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.setShader(() -> s);
        RenderSystem.setShaderTexture(0, opaque > 0 ? opaque : depth);
        RenderSystem.setShaderTexture(1, lod ? LOD.texture : depth);
        RenderSystem.setShaderTexture(2, surfaceDepth);
        RenderSystem.setShaderTexture(3, now > 0 ? now : depth);
        RenderSystem.setShaderTexture(4, other > 0 ? other : depth);
        s.safeGetUniform("InvProj").set(projection.invert(INVERSE));
        s.safeGetUniform("LodInvProj").set(lod ? LOD.inverseProjection : INVERSE);
        s.safeGetUniform("LodDepth").set(lod ? 1f : 0f, LOD.negativeOneToOne ? 1f : 0f, LOD.empty, lod && LOD.pinsWorldDepth ? 1f : 0f);
        s.safeGetUniform("LodWorld").set(lod ? LOD.world : -1f);
        s.safeGetUniform("See").set(opaque > 0 ? mode.code() : 0f, 0f, 0f, 0f);
        modelView.transform(UP.set(0, 1, 0, 0));
        s.safeGetUniform("ViewUp").set(UP.x, UP.y, UP.z);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        RenderSystem.viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        last = lod ? 2 : 1;
        lastOk = true;
        see = opaque > 0;
        if (DIAG) diag(depth, main.width, main.height, previous, main.frameBufferId);
        return texture;
    }

    /**
     * Log line for the frame's centre column, top to bottom: Minecraft depth and distance, LOD depth and distance,
     * resulting distance texture. Reads back three textures (waits for GPU) — only for run diagnostics.
     */
    private static void diag(int worldDepth, int w, int h, int drawn, int mainFbo) {
        long now = System.currentTimeMillis();
        if (now < nextDiag) return;
        nextDiag = now + 1000;
        // actual world render target: framebuffer bound at AFTER_LEVEL and its depth versus the Minecraft framebuffer
        int[] drawnDepth = attachment(drawn), mainDepth = attachment(mainFbo);
        Incoming.LOG.info(String.format(Locale.ROOT,
                "Effects layer depth source: main target depth texture %d, drawn framebuffer %d depth %s %d, main framebuffer %d depth %s %d",
                worldDepth, drawn, kind(drawnDepth[0]), drawnDepth[1], mainFbo, kind(mainDepth[0]), mainDepth[1]));
        if (drawnDepth[0] == GL11.GL_TEXTURE && drawnDepth[1] != worldDepth) {
            float[] other = read(drawnDepth[1], GL11.GL_DEPTH_COMPONENT);
            int ow = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int oh = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "Effects layer drawn depth column %dx%d", ow, oh));
            for (float row : DIAG_ROWS) {
                int y = Math.round((1 - row) * (oh - 1));
                float raw = other != null && ow > 0 ? other[y * ow + ow / 2] : Float.NaN;
                b.append(String.format(Locale.ROOT, "; %.2f: %.6f→%.1f", row, raw, along(INVERSE, 1 - 2 * row, raw * 2 - 1)));
            }
            Incoming.LOG.info(b.toString());
        }
        float[] scene = read(texture, GL11.GL_RED);
        float[] world = read(worldDepth, GL11.GL_DEPTH_COMPONENT);
        float[] lod = last == 2 ? read(LOD.texture, GL11.GL_DEPTH_COMPONENT) : null;
        int lodW = last == 2 ? GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) : 0;
        int lodH = last == 2 ? GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) : 0;
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT,
                "Effects layer depth column: frame %dx%d, layer %dx%d, LOD %dx%d, inverse projection z row %.6f %.6f, w row %.6f %.6f",
                w, h, width, height, lodW, lodH, INVERSE.m22(), INVERSE.m32(), INVERSE.m23(), INVERSE.m33()));
        for (float row : DIAG_ROWS) {
            float ny = 1 - 2 * row;
            int x = width / 2, y = Math.round((1 - row) * (height - 1));
            float raw = world != null ? world[y * width + x] : Float.NaN;
            b.append(String.format(Locale.ROOT, "; %.2f: world %.6f→%.1f", row, raw, along(INVERSE, ny, raw * 2 - 1)));
            if (lod != null && lodW > 0) {
                int lx = lodW / 2, ly = Math.round((1 - row) * (lodH - 1));
                float l = lod[ly * lodW + lx];
                b.append(String.format(Locale.ROOT, " lod %.6f→%.1f", l,
                        along(LOD.inverseProjection, ny, LOD.negativeOneToOne ? l * 2 - 1 : l)));
            }
            b.append(String.format(Locale.ROOT, " scene %.1f", scene != null ? scene[y * width + x] : Float.NaN));
        }
        Incoming.LOG.info(b.toString());
    }

    /** Window framebuffer type in {@link #attachment}: it has no attachments; querying them is an OpenGL error. */
    private static final int WINDOW = -1;

    /**
     * Depth of framebuffer {@code fbo}: attachment type (texture, renderbuffer, absent) and name; window framebuffer (0) — {@link #WINDOW}, with no
     * OpenGL query: it has no {@code GL_DEPTH_ATTACHMENT} (the {@code GL_INVALID_OPERATION} error would leak to the
     * next caller).
     */
    private static int[] attachment(int fbo) {
        if (fbo == 0) return new int[]{WINDOW, 0};
        int bound = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int name = type == GL11.GL_NONE ? 0
                : GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, bound);
        return new int[]{type, name};
    }

    private static String kind(int type) {
        return switch (type) {
            case GL11.GL_TEXTURE -> "texture";
            case GL30.GL_RENDERBUFFER -> "renderbuffer";
            case GL11.GL_NONE -> "none";
            case WINDOW -> "window";
            default -> "0x" + Integer.toHexString(type);
        };
    }

    /** Entire texture to memory (its own size); null — failed. */
    private static float @Nullable [] read(int id, int format) {
        if (id <= 0) return null;
        GlStateManager._bindTexture(id);
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (w <= 0 || h <= 0) return null;
        FloatBuffer f = MemoryUtil.memAllocFloat(w * h);
        try {
            texImage(format, f);
            float[] out = new float[w * h];
            f.get(out);
            return out;
        } finally {
            MemoryUtil.memFree(f);
        }
    }

    /**
     * Bound texture (level 0) into {@code out} as numbers. If other code left {@code GL_PIXEL_PACK_BUFFER} bound,
     * OpenGL would write there (treating {@code out} as an offset), rather than to memory: unbind it during readback,
     * then restore it.
     */
    private static void texImage(int format, FloatBuffer out) {
        int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        if (pack != 0) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        try {
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, format, GL11.GL_FLOAT, out);
        } finally {
            if (pack != 0) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
        }
    }

    /** View-axis distance at horizontal frame centre, height ny (NDC) — like {@code along} in {@code fx_depth}. */
    private static float along(Matrix4f inverse, float ny, float z) {
        Vector4f v = inverse.transform(new Vector4f(0, ny, z, 1));
        return -v.z / v.w;
    }

    /** Last frame's layer occlusion. Ordinary timing probes must not synchronize with the GPU. */
    static String describe() {
        String occlusion = switch (last) {
            case 2 -> "world and LOD (" + LOD.describe() + (DIAG ? lodShare() : "") + ")";
            case 1 -> "world";
            default -> "none";
        };
        return occlusion + ", see-through " + (see ? SeeThrough.describe() : "off");
    }

    /**
     * Frame fraction with LOD beyond Minecraft render distance behind the pixel, and their nearest distance — from the read-back
     * distance texture (waits for GPU: only for explicit readback diagnostics).
     */
    private static String lodShare() {
        if (texture < 0 || width <= 0) return "";
        int n = width * height;
        FloatBuffer b = MemoryUtil.memAllocFloat(n);
        try {
            GlStateManager._bindTexture(texture);
            texImage(GL11.GL_RED, b);
            float beyond = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
            int lod = 0;
            float nearest = Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                float d = b.get(i);
                if (d > beyond && d < 1e8f) {
                    lod++;
                    nearest = Math.min(nearest, d);
                }
            }
            if (lod == 0) return ", no LOD beyond the render distance";
            return String.format(Locale.ROOT, ", LOD beyond the render distance in %.1f%% of the frame, nearest %.0f blocks",
                    100.0 * lod / n, nearest);
        } finally {
            MemoryUtil.memFree(b);
        }
    }

    /** This frame's see-through surfaces ({@link #surface}, {@link #glass}) are filled. */
    static boolean see() {
        return see;
    }

    /** Distance to the nearer see-through surface ({@code see.glsl}: FxSurface). */
    static int surface() {
        return surface;
    }

    /** The see-through surface's own light and transmittance ({@code see.glsl}: FxGlass). */
    static int glass() {
        return glass;
    }

    /** Own texture units of {@code FxSurface} and {@code FxGlass} (Minecraft's 12 named samplers and units 12–15 are taken). */
    static final int SURFACE_UNIT = 16, GLASS_UNIT = 17;

    /**
     * {@code see.glsl} inputs into program {@code s}: both textures on their own units, the units into the program on every
     * bind (Veil recompiles programs, {@link OpenAir#bind}), {@code FxSee} — this frame has them. Without see-through the
     * units hold the frame's colour (a sampler is never left on texture 0: an incomplete texture floods driver debug output).
     */
    static void bindSee(ShaderInstance s) {
        int idle = Minecraft.getInstance().getMainRenderTarget().getColorTextureId();
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + SURFACE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, see ? surface : idle);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + GLASS_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, see ? glass : idle);
        GlStateManager._activeTexture(unit);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        ProgramManager.glUseProgram(s.getId());
        Uniform.uploadInteger(Uniform.glGetUniformLocation(s.getId(), "FxSurface"), SURFACE_UNIT);
        Uniform.uploadInteger(Uniform.glGetUniformLocation(s.getId(), "FxGlass"), GLASS_UNIT);
        ProgramManager.glUseProgram(program);
        s.safeGetUniform("FxSee").set(see ? 1f : 0f);
    }

    /**
     * The layer draws through its own framebuffer from here to {@link #unbindLayer}: the bound frame's colour texture with the
     * hardware depth of this frame ({@link #hard}: the opaque world, not the see-through surfaces in front of it). Passes that
     * save and restore the bound framebuffer return to it. False — not bound (no see-through this frame, or the frame's colour
     * is not a texture): the layer keeps the world's depth.
     */
    static boolean bindLayer() {
        layerDraw = -1;
        if (!see || hard < 0) return false;
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (draw == 0) return false;
        if (GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL11.GL_TEXTURE) return false;
        int colour = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        if (layerFbo < 0) layerFbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, layerFbo);
        if (colour != layerColour) {
            GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, colour, 0);
            GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_DEPTH_ATTACHMENT, GlConst.GL_TEXTURE_2D, hard, 0);
            layerColour = colour;
            if (GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) != GlConst.GL_FRAMEBUFFER_COMPLETE) {
                layerColour = -1;
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
                return false;
            }
        }
        layerDraw = draw;
        layerRead = read;
        return true;
    }

    /** Back to the frame's own framebuffer after the layer ({@link #bindLayer}). */
    static void unbindLayer() {
        if (layerDraw < 0) return;
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, layerDraw);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, layerRead);
        layerDraw = -1;
    }

    /**
     * The previous frame's distance texture (as {@link #update} returned it then); −1 — none: the previous update failed, or the
     * frame size changed since.
     */
    static int previous() {
        return previousValid ? TEXTURES[1 - current] : -1;
    }

    /** Both textures and framebuffers sized to the game frame (recreated on window resize). */
    private static boolean ensure(int w, int h) {
        if (FBOS[0] >= 0 && w == width && h == height) return true;
        width = w;
        height = h;
        // the colour the layer framebuffer was made for no longer matches the new depth size
        layerColour = -1;
        surface = image(surface, GL30.GL_R32F, GL11.GL_RED, GL11.GL_FLOAT, w, h);
        glass = image(glass, GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, w, h);
        hard = image(hard, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, w, h);
        for (int i = 0; i < 2; i++) {
            if (!ensureOne(i, w, h)) {
                Incoming.LOG.error("Effects layer: depth texture {}×{} not created, no soft edges", w, h);
                width = height = 0;
                return false;
            }
        }
        return true;
    }

    /** A frame-sized texture (created once, resized after): nearest, clamped. */
    private static int image(int tex, int internal, int format, int type, int w, int h) {
        if (tex < 0) tex = GlStateManager._genTexture();
        GlStateManager._bindTexture(tex);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, null);
        return tex;
    }

    private static boolean ensureOne(int i, int w, int h) {
        if (FBOS[i] < 0) FBOS[i] = GlStateManager.glGenFramebuffers();
        TEXTURES[i] = image(TEXTURES[i], GL30.GL_R32F, GL11.GL_RED, GL11.GL_FLOAT, w, h);
        int tex = TEXTURES[i], fb = FBOS[i];
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fb);
        GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, tex, 0);
        GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GlConst.GL_TEXTURE_2D, surface, 0);
        GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT2, GlConst.GL_TEXTURE_2D, glass, 0);
        GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_DEPTH_ATTACHMENT, GlConst.GL_TEXTURE_2D, hard, 0);
        GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1, GL30.GL_COLOR_ATTACHMENT2});
        boolean ok = GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        return ok;
    }

    /** Saved only for secondary views; swapping leaves the primary draw path and operation order unchanged. */
    public static final class ViewState {
        private int[] FBOS = {-1, -1};
        private int[] TEXTURES = {-1, -1};
        private int surface = -1, glass = -1, hard = -1, layerFbo = -1, layerColour = -1;
        private boolean see;
        private int fbo = -1;
        private int texture = -1;
        private int width;
        private int height;
        private int current;
        private boolean previousValid;
        private boolean lastOk;
        private LodDepth.View LOD = new LodDepth.View();
        private Matrix4f INVERSE = new Matrix4f();
        private int[] VIEWPORT = new int[4];
        private int last;
        private long nextDiag;

        void close() {
            FxViews.textures(TEXTURES); FxViews.framebuffers(FBOS);
            FxViews.textures(surface, glass, hard); FxViews.framebuffers(layerFbo);
        }

        public void swap() {
            int t = SceneDepth.surface; SceneDepth.surface = surface; surface = t;
            t = SceneDepth.glass; SceneDepth.glass = glass; glass = t;
            t = SceneDepth.hard; SceneDepth.hard = hard; hard = t;
            t = SceneDepth.layerFbo; SceneDepth.layerFbo = layerFbo; layerFbo = t;
            t = SceneDepth.layerColour; SceneDepth.layerColour = layerColour; layerColour = t;
            boolean b = SceneDepth.see; SceneDepth.see = see; see = b;
            int[] oldFBOS = SceneDepth.FBOS;
            SceneDepth.FBOS = FBOS;
            FBOS = oldFBOS;
            int[] oldTEXTURES = SceneDepth.TEXTURES;
            SceneDepth.TEXTURES = TEXTURES;
            TEXTURES = oldTEXTURES;
            int oldfbo = SceneDepth.fbo;
            SceneDepth.fbo = fbo;
            fbo = oldfbo;
            int oldtexture = SceneDepth.texture;
            SceneDepth.texture = texture;
            texture = oldtexture;
            int oldwidth = SceneDepth.width;
            SceneDepth.width = width;
            width = oldwidth;
            int oldheight = SceneDepth.height;
            SceneDepth.height = height;
            height = oldheight;
            int oldcurrent = SceneDepth.current;
            SceneDepth.current = current;
            current = oldcurrent;
            boolean oldpreviousValid = SceneDepth.previousValid;
            SceneDepth.previousValid = previousValid;
            previousValid = oldpreviousValid;
            boolean oldlastOk = SceneDepth.lastOk;
            SceneDepth.lastOk = lastOk;
            lastOk = oldlastOk;
            LodDepth.View oldLOD = SceneDepth.LOD;
            SceneDepth.LOD = LOD;
            LOD = oldLOD;
            Matrix4f oldINVERSE = SceneDepth.INVERSE;
            SceneDepth.INVERSE = INVERSE;
            INVERSE = oldINVERSE;
            int[] oldVIEWPORT = SceneDepth.VIEWPORT;
            SceneDepth.VIEWPORT = VIEWPORT;
            VIEWPORT = oldVIEWPORT;
            int oldlast = SceneDepth.last;
            SceneDepth.last = last;
            last = oldlast;
            long oldnextDiag = SceneDepth.nextDiag;
            SceneDepth.nextDiag = nextDiag;
            nextDiag = oldnextDiag;
        }
    }
}
