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
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
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
    private static int fbo = -1, texture = -1, width, height;
    private static final LodDepth.View LOD = new LodDepth.View();
    private static final Matrix4f INVERSE = new Matrix4f();
    private static final int[] VIEWPORT = new int[4];
    /** Last frame's layer occlusion: 0 — none, 1 — world, 2 — world and LOD. */
    private static int last;
    /** Run diagnostics ({@code -Dincoming.nukeDiag=true}): distance column once per second, see {@link #diag}. */
    private static final boolean DIAG = Boolean.getBoolean("incoming.nukeDiag");
    private static final float[] DIAG_ROWS = {0.1f, 0.3f, 0.45f, 0.55f, 0.65f, 0.75f, 0.9f};
    private static long nextDiag;

    private SceneDepth() {}

    /**
     * Fill this frame's distance texture from the world projection; −1 — failed (shader not loaded, framebuffer
     * lacks depth): draw the layer without soft edges then.
     */
    static int update(Matrix4f projection) {
        ShaderInstance s = shader;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int depth = main.getDepthTextureId();
        last = 0;
        if (s == null || depth <= 0) return -1;
        if (!ensure(main.width, main.height)) return -1;
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        boolean lod = LodDepth.current(LOD);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        RenderSystem.viewport(0, 0, width, height);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShader(() -> s);
        RenderSystem.setShaderTexture(0, depth);
        RenderSystem.setShaderTexture(1, lod ? LOD.texture : depth);
        s.safeGetUniform("InvProj").set(projection.invert(INVERSE));
        s.safeGetUniform("LodInvProj").set(lod ? LOD.inverseProjection : INVERSE);
        s.safeGetUniform("LodDepth").set(lod ? 1f : 0f, LOD.negativeOneToOne ? 1f : 0f, LOD.empty, lod && LOD.pinsWorldDepth ? 1f : 0f);
        s.safeGetUniform("LodWorld").set(lod ? LOD.world : -1f);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        RenderSystem.viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        RenderSystem.enableDepthTest();
        last = lod ? 2 : 1;
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

    /** Last frame's layer occlusion — for the scenario line. */
    static String describe() {
        return switch (last) {
            case 2 -> "world and LOD (" + LOD.describe() + lodShare() + ")";
            case 1 -> "world";
            default -> "none";
        };
    }

    /**
     * Frame fraction with LOD beyond Minecraft render distance behind the pixel, and their nearest distance — from the read-back
     * distance texture (waits for GPU: only for the scenario line).
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

    /** Custom texture and framebuffer sized to the game frame (recreated on window resize). */
    private static boolean ensure(int w, int h) {
        if (fbo >= 0 && w == width && h == height) return true;
        if (texture < 0) texture = GlStateManager._genTexture();
        if (fbo < 0) fbo = GlStateManager.glGenFramebuffers();
        width = w;
        height = h;
        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_R32F, w, h, 0, GL11.GL_RED, GL11.GL_FLOAT, null);
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, texture, 0);
        boolean ok = GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        if (!ok) {
            Incoming.LOG.error("Effects layer: depth texture {}×{} not created, no soft edges", w, h);
            width = height = 0;
            return false;
        }
        return true;
    }
}
