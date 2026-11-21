package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.Incoming;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * Young nuclear dust wall, from the front to {@link #SPAN} seconds behind it, as its own ray-marched pass
 * ({@code include/dustwall.glsl}): one presentation model (presentation, not physical) of the wall's birth at the contact line,
 * rise, small uneven hump, settling, billows and light; the physics enters only as its inputs ({@link Frame}: the game front,
 * where and how much dust is lofted, the pall's layer height at the handoff, wind, fireball and sun light). Older dust is the
 * pall's ({@link PallPass}), which hands its young dust over across the last {@link #FADE} seconds ({@link Frame#young}).
 * <p>
 * Drawn at 1/{@link #DIV} resolution per axis into its own RGBA16F target (light premultiplied, alpha transmittance), with a
 * fixed per-pixel sample offset and no history (no temporal shimmer), then laid on the frame with depth-aware upsampling
 * against the world distance ({@code fx_dustwall_composite}). Samples sit on a grid fixed along each ray from the eye, and the
 * texture is fixed in the air mass, so the pattern does not slide as the front grows.
 */
public final class DustWallPass {
    /** Seconds of young wall behind the front this pass draws (copy: {@code DW_SPAN}). */
    public static final double SPAN = 10;
    /** Seconds over which the pall takes the wall over at its back (copy: {@code DW_FADE}). */
    public static final double FADE = 2.5;
    /** Table nodes ({@code DW_NODES}). */
    public static final int NODES = 32;
    /** Resolution divisor per axis: billows of 15–100 m stay several low-resolution pixels wide in the judging view. */
    static final int DIV = Integer.getInteger("incoming.dustwall.div", 4);
    /** Value-noise lattice side ({@code DW_LATTICE}). */
    private static final int LATTICE = 128;
    private static final int NOISE_UNIT = 12;

    public static ShaderInstance march, composite;
    private static int lattice = -1, target = -1, fbo = -1, table = -1, width, height;
    private static boolean failed;
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f(), INVERSE = new Matrix4f();
    private static final Vector3f FORWARD = new Vector3f();
    private static final FloatBuffer TABLE = MemoryUtil.memAllocFloat(NODES * 4);
    private static final int[] VIEWPORT = new int[4];

    private DustWallPass() {}

    /** Inputs of one young wall for a frame (eye-relative unless noted). */
    public static final class Frame {
        public boolean active;
        /** Eye from ground zero's ground point, blocks; detonation clock, model seconds since the burst. */
        public final float[] eye = new float[4];
        /** Front radius now; inner radius (the front {@link #SPAN} s ago); envelope top; table reach — blocks. */
        public final float[] ring = new float[4];
        /** Per node: front arrival (model s since the burst), settled layer height (blocks), dust extinction (1/block), 0. */
        public final float[] table = new float[NODES * 4];
        /** Fireball centre from ground zero's ground point (blocks), its radius (blocks). */
        public final float[] ball = new float[4];
        /** Linear light (white wall at noon — 1): fireball black body at its surface, sun, sky, ground bounce; dust albedo. */
        public final Vector3f ballLight = new Vector3f(), sunLight = new Vector3f(), sunDir = new Vector3f(), skyLight = new Vector3f(),
                groundLight = new Vector3f(), albedo = new Vector3f();
        /** World wind x, z (blocks/s). */
        public float windX, windZ;
    }

    /** Lays the young wall over the frame; false — not drawn. {@code scene}: the world-distance texture ({@link SceneDepth}). */
    static boolean draw(Frame f, int scene, Matrix4f view, Matrix4f projection) {
        ShaderInstance m = march, c = composite;
        if (!f.active || m == null || c == null || scene < 0 || failed) return false;
        Minecraft mc = Minecraft.getInstance();
        int w = FxViews.width(mc.getMainRenderTarget().width), h = FxViews.height(mc.getMainRenderTarget().height);
        if (!targets(w, h)) return false;
        VIEW_PROJECTION.set(projection).mul(view);
        VIEW_PROJECTION.invert(INVERSE);
        view.positiveZ(FORWARD).negate();
        int main = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        try {
            // the march at low resolution into its own target
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            GlStateManager._viewport(0, 0, width, height);
            GlStateManager._colorMask(true, true, true, true);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.setShaderTexture(1, scene);
            m.safeGetUniform("InvViewProj").set(INVERSE);
            m.safeGetUniform("PallForward").set(FORWARD.x, FORWARD.y, FORWARD.z);
            m.safeGetUniform("DwDiv").set((float) DIV);
            upload(f.table);
            RenderSystem.setShaderTexture(2, table);
            m.safeGetUniform("DwEye").set(f.eye[0], f.eye[1], f.eye[2], f.eye[3]);
            m.safeGetUniform("DwRing").set(f.ring[0], f.ring[1], f.ring[2], f.ring[3]);
            m.safeGetUniform("DwBall").set(f.ball[0], f.ball[1], f.ball[2], f.ball[3]);
            m.safeGetUniform("DwBallLight").set(f.ballLight.x, f.ballLight.y, f.ballLight.z);
            m.safeGetUniform("DwSunLight").set(f.sunLight.x, f.sunLight.y, f.sunLight.z);
            m.safeGetUniform("DwSunDir").set(f.sunDir.x, f.sunDir.y, f.sunDir.z);
            m.safeGetUniform("DwSkyLight").set(f.skyLight.x, f.skyLight.y, f.skyLight.z);
            m.safeGetUniform("DwGroundLight").set(f.groundLight.x, f.groundLight.y, f.groundLight.z);
            m.safeGetUniform("DwAlbedo").set(f.albedo.x, f.albedo.y, f.albedo.z);
            m.safeGetUniform("DwWind").set(f.windX, f.windZ, 0f, 0f);
            fullScreen(m, () -> Uniform.uploadInteger(Uniform.glGetUniformLocation(m.getId(), "DwNoise"), NOISE_UNIT), true);
            // laid on the frame: light adds, the frame is multiplied by the transmittance
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main);
            GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.SRC_ALPHA,
                    GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
            RenderSystem.setShaderTexture(0, target);
            RenderSystem.setShaderTexture(1, scene);
            c.safeGetUniform("DwDiv").set((float) DIV);
            fullScreen(c, null, false);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main);
            GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
        }
        return true;
    }

    /** The per-node table (DW_NODES × 1, RGBA32F) for this frame. */
    private static void upload(float[] values) {
        if (table < 0) {
            table = GlStateManager._genTexture();
            GlStateManager._bindTexture(table);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, NODES, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
        }
        TABLE.clear();
        TABLE.put(values).flip();
        GlStateManager._bindTexture(table);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, NODES, 1, GL11.GL_RGBA, GL11.GL_FLOAT, TABLE);
    }

    private static boolean targets(int w, int h) {
        int lw = Math.max(1, (w + DIV - 1) / DIV), lh = Math.max(1, (h + DIV - 1) / DIV);
        if (lattice < 0) lattice = lattice();
        if (target >= 0 && lw == width && lh == height) return true;
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (target < 0) {
            target = GlStateManager._genTexture();
            fbo = GlStateManager.glGenFramebuffers();
        }
        GlStateManager._bindTexture(target);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, lw, lh, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, target, 0);
        boolean ok = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        if (!ok) {
            Incoming.LOG.error("Nuclear dust wall: target {}×{} not created, no young wall pass", lw, lh);
            failed = true;
            return false;
        }
        width = lw;
        height = lh;
        return true;
    }

    /** The 128³ lattice of random values the wall's value noise reads ({@code dwNoise3}); fixed seed: the same for everyone. */
    private static int lattice() {
        int n = LATTICE;
        byte[] values = new byte[n * n * n];
        new Random(0xD057L).nextBytes(values);
        ByteBuffer buf = MemoryUtil.memAlloc(values.length);
        try {
            buf.put(values).flip();
            int id = GlStateManager._genTexture();
            int unit = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, id);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, n, n, n, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GlStateManager._activeTexture(unit);
            return id;
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static void fullScreen(ShaderInstance s, Runnable uniforms, boolean noise) {
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        MeshData mesh = b.build();
        if (mesh == null) return;
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(),
                Minecraft.getInstance().getWindow());
        FxViews.screenSize(s);
        s.apply();
        if (uniforms != null) uniforms.run();
        int unit = GlStateManager._getActiveTexture();
        if (noise) {
            // ShaderInstance binds 2D textures only: the 3D lattice on its own unit
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, lattice);
            GlStateManager._activeTexture(unit);
        }
        BufferUploader.draw(mesh);
        s.clear();
        if (noise) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GlStateManager._activeTexture(unit);
        }
    }

    /** Forget GL objects (resource reload keeps them; the context outlives them). */
    static void reset() {
        failed = false;
    }
}
