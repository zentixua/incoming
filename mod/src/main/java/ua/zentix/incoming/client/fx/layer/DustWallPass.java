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
import org.joml.Vector4f;
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
 * small uneven hump, settling, billows, their motion and light; the physics enters only as its inputs ({@link Frame}: the game
 * front, where and how much dust is lofted, the pall's layer height, wind, fireball, sun and sky light). Older dust is the
 * pall's ({@link PallPass}), which takes the wall over across the last {@link #FADE} seconds ({@link PallPass.Frame#young}), a crossfade.
 * Only in the primary view, on land, with one detonation in the pall's slots ({@code NukePall.youngWall}): elsewhere the pall
 * draws the whole wall as before.
 * <p>
 * Drawn at 1/{@code div} resolution per axis ({@link #div}) into its own RGBA16F target (light premultiplied, alpha
 * transmittance) with a sample offset along each ray that moves every frame, then averaged over frames at that resolution
 * ({@code fx_dustwall_resolve}: reprojected history clamped to the current neighbourhood), then laid on the frame with
 * depth-aware upsampling against the world distance ({@code fx_dustwall_composite}). The flicker of a fixed per-pixel offset
 * (sparkle along the far top, where 9–18 m steps cross a moving billowed surface) is a time problem: averaged in time it is
 * gone, with no spatial blur. Samples sit on a grid fixed along each ray from the eye, and the texture is fixed in the air
 * mass, so the pattern does not slide as the front grows.
 */
public final class DustWallPass {
    /**
     * Seconds of young wall behind the front this pass draws (copy: {@code DW_SPAN}): the model owns the whole visible wall in
     * the judging view (front passes a camera 4.5 km out, then 12 s), where the pall's law top would rise over a 120 m camera.
     */
    public static final double SPAN = 24;
    /** Seconds over which the pall takes the wall over at its back, a crossfade (copy: {@code DW_FADE}). */
    public static final double FADE = 6;
    /**
     * Highest the young dust reaches over its settled layer height (copies: the bound over the largest hump 1.8, {@code DW_BULGE},
     * {@code DW_HAZE}; the ray's slab top, {@code DwRing.z}).
     */
    public static final double TOP_OVER_LAYER = 1.8 * 1.35 * 1.2;
    /** Weight of the current frame in the wall's history ({@code fx_dustwall_resolve}): about ten frames averaged. */
    private static final float HISTORY = 0.1f;
    /** An eye that moved farther than this between frames (teleport) restarts the history, blocks. */
    private static final double JUMP = 64;
    /** Diagnostics: {@code -Dincoming.dustwall.history=false} lays each frame's march without the history. */
    private static final boolean HISTORY_ON = !"false".equals(System.getProperty("incoming.dustwall.history"));
    /**
     * Table nodes ({@code DW_NODES}): arrival is read between nodes linearly, and 1 Mt reaches 26 km; at 128 nodes the wall
     * starts within 0.03 s of the game front's arrival anywhere (DustWallInputsTest), well inside its 0.12 s rise.
     */
    public static final int NODES = 128;
    /**
     * Resolution divisor per axis and the march's step cap by dust quality ({@link PallPass.Quality}): at HIGH billows of
     * 15–100 m stay several low-resolution pixels wide in the judging view; lower settings trade detail for frame time.
     */
    static int div(PallPass.Quality q) {
        Integer forced = Integer.getInteger("incoming.dustwall.div");
        if (forced != null) return Math.max(1, Math.min(16, forced));
        return switch (q) {
            case HIGH -> 4;
            case MEDIUM -> 5;
            default -> 6;
        };
    }

    static int steps(PallPass.Quality q) {
        return switch (q) {
            case HIGH -> 128;
            case MEDIUM -> 104;
            default -> 96;
        };
    }
    /** Value-noise lattice side ({@code DW_LATTICE}). */
    private static final int LATTICE = 128;
    private static final int NOISE_UNIT = 12;

    public static ShaderInstance march, resolve, composite;
    private static int lattice = -1, target = -1, fbo = -1, table = -1, width, height, div, fullW, fullH;
    /** History pair ({@code fx_dustwall_resolve}): the latest is {@link #latest}. */
    private static final int[] HISTORY_TEX = {-1, -1}, HISTORY_FBO = {-1, -1};
    private static int latest;
    private static boolean failed, history;
    private static long frame;
    private static final float[] PREVIOUS_EYE = new float[3];
    /** The detonation and its clock in the history: another detonation or a jump of the clock (replay seek) restarts it. */
    private static Object previousSource;
    private static float previousTime;
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f(), INVERSE = new Matrix4f(), PREVIOUS = new Matrix4f();
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
        /** Per node: front arrival (model s since the burst), settled layer height (blocks), dust extinction (1/block), paved share. */
        public final float[] table = new float[NODES * 4];
        /** Fireball centre from ground zero's ground point (blocks), its radius (blocks). */
        public final float[] ball = new float[4];
        /** Linear light (white wall at noon — 1): fireball black body at its surface, sun, sky, ground bounce; dust albedo, its bulk colour. */
        public final Vector3f ballLight = new Vector3f(), sunLight = new Vector3f(), sunDir = new Vector3f(), skyLight = new Vector3f(),
                groundLight = new Vector3f(), albedo = new Vector3f(), bulk = new Vector3f();
        /** World wind x, z (blocks/s). */
        public float windX, windZ;
        /** The settled layer top at the eye's radius from ground zero's ground point, blocks (history reprojection). */
        public float layerTop;
        /** The detonation this wall belongs to (history continuity). */
        public Object source;
        /** Detonation-clock seconds in ten game ticks at its scale: a longer step between frames is a jump (replay seek). */
        public float clockJump = 0.5f;
        /** Ground radius of the fireball now, blocks: no dust under it. */
        public float cut;
        /** Sand's dust albedo and bulk colour (the pall's). */
        public final Vector3f sandAlbedo = new Vector3f(), sandBulk = new Vector3f();
    }

    /**
     * All three programs loaded and no target failure: the pall may hand its young wall over this frame ({@code
     * NukePall.youngWall} asks before it sets {@code PallYoung}).
     */
    public static boolean ready() {
        return march != null && resolve != null && composite != null && !failed;
    }

    /**
     * {@link #ready} with its targets in place at this view's size for {@code quality} (allocated here, before the pall hands
     * the wall over: a target failing only in {@link #draw} left the frame without either wall). Render thread.
     */
    public static boolean prepare(PallPass.Quality quality) {
        if (!ready()) return false;
        Minecraft mc = Minecraft.getInstance();
        return targets(FxViews.width(mc.getMainRenderTarget().width), FxViews.height(mc.getMainRenderTarget().height), div(quality));
    }

    /**
     * Lays the young wall of {@code pall} over the frame; false — not drawn. {@code scene}: the world-distance texture
     * ({@link SceneDepth}). Primary view only (NukePall hands the wall over only there).
     */
    static boolean draw(PallPass.Frame pall, int scene, Matrix4f view, Matrix4f projection) {
        Frame f = pall.dustWall;
        ShaderInstance m = march, r = resolve, c = composite;
        if (!f.active || !ready() || scene < 0 || FxViews.secondary()) return false;
        Minecraft mc = Minecraft.getInstance();
        int w = FxViews.width(mc.getMainRenderTarget().width), h = FxViews.height(mc.getMainRenderTarget().height);
        int d = div(pall.quality);
        if (!targets(w, h, d)) return false;
        float dx = f.eye[0] - PREVIOUS_EYE[0], dy = f.eye[1] - PREVIOUS_EYE[1], dz = f.eye[2] - PREVIOUS_EYE[2];
        // the same detonation, its clock running on (not a replay seek), the eye not teleported
        float since = f.eye[3] - previousTime;
        boolean past = HISTORY_ON && history && f.source == previousSource && since >= 0 && since < f.clockJump
                && dx * dx + dy * dy + dz * dz < JUMP * JUMP;
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
            m.safeGetUniform("DwDiv").set((float) d);
            m.safeGetUniform("DwFrame").set((float) (frame % 4096), (float) steps(pall.quality));
            // the slot's terrain map and the outdoor-air window: the dust lies on the local ground, never indoors
            int ground = PallPass.groundMap(0);
            Vector4f g = PallPass.groundParams(pall, 0);
            RenderSystem.setShaderTexture(3, ground >= 0 ? ground : table);
            m.safeGetUniform("PallSlot0").set(pall.slot0);
            m.safeGetUniform("PallGround0").set(g.x, g.y, g.z, g.w);
            m.safeGetUniform("DwRelief").set(g.x > 0 ? g.y : 0f, g.x > 0 ? g.z : 0f);
            OpenAir.bind(m, true);
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
            m.safeGetUniform("DwBulk").set(f.bulk.x, f.bulk.y, f.bulk.z);
            m.safeGetUniform("DwSandAlbedo").set(f.sandAlbedo.x, f.sandAlbedo.y, f.sandAlbedo.z);
            m.safeGetUniform("DwSandBulk").set(f.sandBulk.x, f.sandBulk.y, f.sandBulk.z);
            m.safeGetUniform("DwCut").set(f.cut);
            m.safeGetUniform("DwWind").set(f.windX, f.windZ, 0f, 0f);
            m.safeGetUniform("PallAir").set(pall.air.x, pall.air.y, pall.air.z, pall.air.w);
            m.safeGetUniform("PallFloorFrom").set(pall.floorFrom);
            m.safeGetUniform("PallAirSky").set(pall.airSky.x, pall.airSky.y, pall.airSky.z);
            m.safeGetUniform("PallAirFog").set(pall.airFog.x, pall.airFog.y, pall.airFog.z, pall.airFog.w);
            fullScreen(m, () -> Uniform.uploadInteger(Uniform.glGetUniformLocation(m.getId(), "DwNoise"), NOISE_UNIT), true);
            // averaged over frames at the same resolution into the other history target
            int next = 1 - latest;
            {
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, HISTORY_FBO[next]);
                RenderSystem.setShaderTexture(0, target);
                RenderSystem.setShaderTexture(1, HISTORY_TEX[latest]);
                RenderSystem.setShaderTexture(2, scene);
                // the previous frame's world distances: history only where it saw the same place
                int before = SceneDepth.previous();
                RenderSystem.setShaderTexture(3, before >= 0 ? before : scene);
                r.safeGetUniform("DwPrevScene").set(before >= 0 ? 1f : 0f);
                r.safeGetUniform("InvViewProj").set(INVERSE);
                r.safeGetUniform("PrevViewProj").set(PREVIOUS);
                r.safeGetUniform("PallForward").set(FORWARD.x, FORWARD.y, FORWARD.z);
                r.safeGetUniform("DwDiv").set((float) d);
                r.safeGetUniform("DwReproject").set(dx, dy, dz, past ? 1f : 0f);
                r.safeGetUniform("DwHistory").set(HISTORY, f.layerTop - f.eye[1]);
                fullScreen(r, null, false);
                latest = next;
                PREVIOUS.set(VIEW_PROJECTION);
                System.arraycopy(f.eye, 0, PREVIOUS_EYE, 0, 3);
                previousSource = f.source;
                previousTime = f.eye[3];
                history = true;
                frame++;
            }
            // laid on the frame: light adds, the frame is multiplied by the transmittance
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main);
            GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.SRC_ALPHA,
                    GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
            RenderSystem.setShaderTexture(0, HISTORY_TEX[next]);
            RenderSystem.setShaderTexture(1, scene);
            c.safeGetUniform("DwDiv").set((float) d);
            SceneDepth.bindSee(c);
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

    private static boolean targets(int w, int h, int d) {
        int lw = Math.max(1, (w + d - 1) / d), lh = Math.max(1, (h + d - 1) / d);
        if (lattice < 0) lattice = lattice();
        if (target >= 0 && w == fullW && h == fullH && d == div) return true;
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (target < 0) {
            target = GlStateManager._genTexture();
            fbo = GlStateManager.glGenFramebuffers();
            for (int i = 0; i < 2; i++) {
                HISTORY_TEX[i] = GlStateManager._genTexture();
                HISTORY_FBO[i] = GlStateManager.glGenFramebuffers();
            }
        }
        boolean ok = attach(fbo, target, lw, lh, GlConst.GL_NEAREST);
        // history is read between its pixels when the view turns: linear
        for (int i = 0; i < 2; i++) ok &= attach(HISTORY_FBO[i], HISTORY_TEX[i], lw, lh, GlConst.GL_LINEAR);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        if (!ok) {
            Incoming.LOG.error("Nuclear dust wall: targets {}×{} not created, no young wall pass", lw, lh);
            failed = true;
            return false;
        }
        width = lw;
        height = lh;
        fullW = w;
        fullH = h;
        div = d;
        history = false;
        return true;
    }

    private static boolean attach(int framebuffer, int texture, int w, int h, int filter) {
        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, filter);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, filter);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, texture, 0);
        return GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
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

    /** Forget the failure and the history (shaders reloaded; GL objects stay, the context outlives them). */
    public static void reset() {
        failed = false;
        history = false;
    }

    /** The pass's state for the timing log: resolution, step cap, history. */
    public static String describe() {
        return String.format(java.util.Locale.ROOT, "1/%d, history %s, frame %d", div, history && HISTORY_ON ? "on" : "off", frame);
    }

    /** No wall this frame in the primary view: the next one starts its history afresh. */
    static void idle() {
        if (!FxViews.secondary()) history = false;
    }
}
