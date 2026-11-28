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
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.Incoming;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * The nuclear dust wave, all of it, as its own ray-marched pass ({@code include/dustwall.glsl}): one presentation model
 * (presentation, not physical) of the dust from its birth at the contact line through the hump, settling, billows, their
 * motion and light, to the thin haze hours later, for every detonation of the frame, in every view (each view with its own
 * targets and history, {@link ViewState}); the physics enters only as its inputs ({@link Frame}, {@link Source}: the game
 * front, where and how much dust is lofted and how it settles, the ground's material, wind, fireball, sun and sky light).
 * The pall draws no ground dust.
 * <p>
 * The low-resolution march returns premultiplied light, transmission and the depth of the contributing dust.
 * The three thirds of its atlas (light, depth span, interval split: {@code wall_layer.glsl}) share one temporal resolve. The pall composite samples that atlas at the world
 * pixel and places the wall among its other volumes. The flicker of a fixed per-pixel offset
 * (sparkle along the far top, where 9–18 m steps cross a moving billowed surface) is a time problem: averaged in time it is
 * gone, with no spatial blur. Samples sit on a grid fixed along each ray from the eye, and the texture is fixed in the air
 * mass, so the pattern does not slide as the front grows.
 */
public final class DustWallPass {
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
    private static final Integer FORCED_DIV = Integer.getInteger("incoming.dustwall.div");
    static final GpuTimer GPU = new GpuTimer();
    /** The pass by part: the march, the atlas pack, the temporal resolve. */
    static final GpuTimer GPU_MARCH = new GpuTimer(), GPU_PACK = new GpuTimer(), GPU_RESOLVE = new GpuTimer();

    /** Value-noise lattice side ({@code DW_LATTICE}). */
    private static final int LATTICE = ua.zentix.incoming.client.nuclear.DustNoise.SIDE;
    private static final int NOISE_UNIT = 12;

    public static ShaderInstance march, resolve, pack, limit;
    private static int lattice = -1, target = -1, fbo = -1, table = -1, width, height, div, fullW, fullH;
    /** History pair ({@code fx_dustwall_resolve}): the latest is {@link #latest}. */
    private static final int[] HISTORY_TEX = {-1, -1}, HISTORY_FBO = {-1, -1};
    private static int bounds = -1, split = -1, packed = -1, packedFbo = -1;
    private static int latest, marchId = -1;
    private static final int[] historyDiv = new int[2];
    private static boolean failed, history;
    private static long frame;
    private static final float[] PREVIOUS_EYE = new float[3];
    /** The detonation and its clock in the history: another detonation or a jump of the clock (replay seek) restarts it. */
    private static Object previousSource;
    private static float previousTime;
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f(), INVERSE = new Matrix4f(), PREVIOUS = new Matrix4f();
    private static final Vector3f FORWARD = new Vector3f();
    /** Table rows: arrival and paved, then the law's top and depth per log age ({@code PallField.lifetime}). */
    public static final int ROWS = 1 + ua.zentix.incoming.client.nuclear.PallField.AGES;
    /** A detonation's table: those rows, the spray row and the inside sight's rows ({@code PallField.TABLE_ROWS}). */
    public static final int TABLE_ROWS = ua.zentix.incoming.client.nuclear.PallField.TABLE_ROWS;
    private static final FloatBuffer TABLE = MemoryUtil.memAllocFloat(NODES * TABLE_ROWS * 4);
    /**
     * Interleaved rays: each frame marches half of the march pixels, a checkerboard alternating by frame, and the resolve fills
     * the rest from history (the pall's {@code Step.interleave}, at 2: 3–4 burnt a lattice into a still view). Judged the same
     * wave as full rays in motion, about 40 % less march.
     */
    private static boolean builtInterleave;
    /** Width of the march target: the march lattice's, halved while interleaved. */
    private static int marchW;

    /**
     * Only the haze level is left in this view (every source it drew last is past the textured level's window,
     * {@code DustSources.frame}): the march takes a quarter of the rays.
     */
    public static boolean hazeOnly;
    private static final Matrix4f SWAP = new Matrix4f();
    /** {@link #hazeOnly} at the last {@link #prepare}. */
    private static boolean preparedHazeOnly;

    /** The preset's own march divisor (the light's detail level reads it, not the scaled one). */
    private static int baseDiv = 4;
    private static final int[] VIEWPORT = new int[4];

    private DustWallPass() {}

    /** The pall rays' world distance with the dust wave's opaque depth ({@link #limit}): R32F at the frame's size, per view. */
    private static int limitTex = -1, limitFbo = -1, limitW, limitH;

    /**
     * The pall rays' end at the dust wave ({@code fx_dustwall_limit}): at the pixels the pall's rays read this frame (its ray
     * frame's factor, interleave n and offset), the world distance or, nearer, the view depth where this frame's resolved dust
     * has let less than the rays' exit share (2 %) through. Behind it the dust hides the pall, so its light and alpha stay exact
     * within that share; the march (2.6 to 4.6 ms at HIGH, 2f) and the rays it saves then ran behind opaque dust. Returns the
     * texture the rays read as their world distance; {@code scene} when the pass is not ready.
     */
    static int limit(int scene, int factor, int n, float ox, float oy, boolean warming) {
        if (limit == null || failed || target < 0) return scene;
        int w = fullW, h = fullH;
        if (w <= 0 || h <= 0) return scene;
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (limitTex < 0) {
            limitTex = GlStateManager._genTexture();
            limitFbo = GlStateManager.glGenFramebuffers();
        }
        if (limitW != w || limitH != h) {
            RenderSystem.bindTexture(limitTex);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RG32F, w, h, 0, GL30.GL_RG, GL11.GL_FLOAT, null);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, limitFbo);
            GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, limitTex, 0);
            limitW = w;
            limitH = h;
        }
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, limitFbo);
            GlStateManager._viewport(0, 0, w, h);
            RenderSystem.disableBlend();
            RenderSystem.setShaderTexture(0, atlas());
            RenderSystem.setShaderTexture(1, scene);
            limit.safeGetUniform("DwDiv").set((float) div);
            limit.safeGetUniform("PallLimit").set((float) factor, (float) n, ox, oy);
            fullScreen(limit, null, false, warming);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        }
        return limitTex;
    }

    static int atlas() {
        return HISTORY_TEX[latest];
    }

    static int divisor() {
        return div;
    }

    /**
     * Most sources a frame draws (the source table's rows, the terrain and material atlases' tiles; {@code DW_FRAME_SOURCES}); a ray
     * draws its eight nearest crossings in full and further ones by their noise-free column ({@code DW_RAY_SOURCES}).
     */
    public static final int MAX_SOURCES = 16;
    /**
     * Presentation (chosen): the eye height over the drawn world's edge up to which a march ray with no world behind it is
     * drawn as from the ground ({@code dustwall.glsl} {@code dwRayFloor}; fading out by twice that): a standing eye (1.6 m)
     * with room for a step or a low rise under it; 0 when an LOD mod drew the far ground this frame.
     */
    static final float HORIZON_LIFT = 4f;
    /**
     * Rows per source in the source table ({@code DW_ROWS}): row 0, the law by age, the spray row, the inside sight by age, the
     * frame inputs row.
     */
    public static final int SOURCE_ROWS = TABLE_ROWS + 1;
    /** Frame inputs per source, texels of its last row ({@code dwLoad}). */
    public static final int PARAMS = 11;
    /** Terrain tile side ({@code DW_GROUND_SIDE}: {@code PallGround.SIDE}). */
    public static final int GROUND_SIDE = 128;

    /** One detonation's dust this frame ({@code dwLoad}). */
    public static final class Source {
        /** The detonation (its atlas slot and table stay while it is drawn). */
        public Object id;
        /** Row 0, the law by age and the spray row ({@code PallField.lifetime}, {@code lifetimeSpray}): built once. */
        public float[] table;
        /**
         * The frame inputs ({@code dwLoad}, texels 0..10): eye from ground zero and clock; ring (front, share still to fade
         * in, envelope top, reach); life (last age, age scale, drift x, z); fireball (centre, radius); its light and the
         * fireball's ground footprint; fire light from below and the camera's inside sight share ({@code DwSight}); relief
         * (low, high); base surge (radius, top, extinction); terrain map (half side, bottom, top, cell); material tiles (fine,
         * coarse).
         */
        public final float[] params = new float[PARAMS * 4];
        /** Its terrain map ({@code PallGround}). */
        public final PallPass.Ground ground = new PallPass.Ground();
        /** Its committed ground material ({@code DustMaterialTiles}); null — soil. */
        public ua.zentix.incoming.client.nuclear.DustMaterialTiles material;
    }

    /** Inputs of the dust wave for a frame (eye-relative unless noted). */
    public static final class Frame {
        public boolean active;
        /** Sources this frame, nearest first ({@code DustSources}). */
        public int count;
        public final Source[] sources = new Source[MAX_SOURCES];
        /** The nearest source's eye from its ground zero and its clock (history reprojection), and its layer top at the eye. */
        public final float[] eye = new float[4];
        public float layerTop;
        /**
         * The dust's eye-centred ground tiles ({@code DustGround}): fine, coarse — origin from the eye (x, z), cell (0: off), the
         * tiles' height reference minus the eye's height ({@code DwTerrain0/1}).
         */
        public final float[] terrain0 = new float[4], terrain1 = new float[4];
        /** The nearest source (history continuity). */
        public Object source;
        /** The dust's presentation exposure ({@code DwAdapt}). */
        public float adapt = 1;
        /** Linear light (white wall at noon — 1): sun, sky, ground bounce; dust albedo, its bulk colour. */
        public final Vector3f sunLight = new Vector3f(), sunDir = new Vector3f(), skyLight = new Vector3f(),
                groundLight = new Vector3f(), albedo = new Vector3f(), bulk = new Vector3f();
        /** Detonation-clock seconds in ten game ticks at its scale: a longer step between frames is a jump (replay seek). */
        public float clockJump = 0.5f;
        /** Sand's and water droplets' albedo and bulk colour. */
        public final Vector3f sandAlbedo = new Vector3f(), sandBulk = new Vector3f(), mistAlbedo = new Vector3f(), mistBulk = new Vector3f();

        public Frame() {
            for (int i = 0; i < MAX_SOURCES; i++) sources[i] = new Source();
        }
    }

    /**
     * A secondary view under a quarter of the primary output's area (a camera screen, a map tile) draws the transient level:
     * every frame on its own, no history and no interleave. Such a feed renders at irregular intervals, so ten frames of history
     * span seconds there and smear the moving wave; at its size the full lattice costs less than a quarter of the primary's.
     */
    static boolean transientView() {
        if (!FxViews.secondary()) return false;
        var window = Minecraft.getInstance().getWindow();
        var main = Minecraft.getInstance().getMainRenderTarget();
        if (window == null || main == null) return false;
        long primary = (long) window.getWidth() * window.getHeight();
        long own = (long) FxViews.width(main.width) * FxViews.height(main.height);
        return own * 4 < primary;
    }

    private static boolean supported() {
        return march != null && resolve != null && pack != null && !failed;
    }

    /** Allocate the real march and history targets before the pall can hand over its wall. */
    static boolean prepare(PallPass.Detail detail) {
        if (!supported()) return false;
        Minecraft mc = Minecraft.getInstance();
        int divisor = PallPass.LADDER[detail.level].wallDiv();
        baseDiv = divisor;
        // once only the haze level is left (no dust younger than its fade's end anywhere on the table), a quarter of the rays:
        // the haze's light and shape are smooth at that scale (no visible difference), the young level's not
        if (hazeOnly) divisor *= 2;
        if (DustWallDiag.div > 0) divisor = DustWallDiag.div;
        // the ladder's window must not mix frames at both divisors (its estimate scales by this rung's samples)
        if (hazeOnly != preparedHazeOnly) {
            preparedHazeOnly = hazeOnly;
            detail.restart();
        }
        if (FORCED_DIV != null) {
            // A diagnostic override can request finer sampling only while the controller is at the chosen setting.
            int minimum = detail.floored || detail.level > detail.ceiling ? divisor : 1;
            divisor = Math.clamp(FORCED_DIV, minimum, 16);
        }
        return targets(FxViews.width(mc.getMainRenderTarget().width), FxViews.height(mc.getMainRenderTarget().height), divisor);
    }

    /** The dust wave can draw in this view: its programs, prepared targets and the volume's shared programs. */
    public static boolean ready() {
        return supported() && target >= 0 && PallPass.available();
    }

    /** Why {@link #ready} is false, for the undrawn-dust WARN ({@code DustSources}). */
    public static String notReady() {
        if (failed) return "its targets could not be created";
        if (march == null || resolve == null || pack == null) return "its programs are not compiled yet";
        if (target < 0) return "its targets are not prepared (volume programs pending, cold start or the GPU watch latched off)";
        if (!PallPass.available()) return "the nuclear volume programs are not ready or the GPU watch switched the volume off";
        return "ready";
    }

    /**
     * Builds the current wall atlas before the pall reads it. {@code scene}: the world-distance texture
     * ({@link SceneDepth}), in the view being drawn (its own targets and history: {@link ViewState}).
     */
    static void draw(PallPass.Frame pall, int scene, Matrix4f view, Matrix4f projection, PallPass.Step step, boolean warming, int timing) {
        assert scene >= 0 && width > 0 && height > 0;
        if (warming) latest = 0;
        Frame f = pall.dustWall;
        ShaderInstance m = march, r = resolve;
        int timed = warming ? -1 : GPU.begin();
        float dx = f.eye[0] - PREVIOUS_EYE[0], dy = f.eye[1] - PREVIOUS_EYE[1], dz = f.eye[2] - PREVIOUS_EYE[2];
        // the same detonation, its clock running on (not a replay seek), the eye not teleported
        float since = f.eye[3] - previousTime;
        boolean past = !warming && HISTORY_ON && DustWallDiag.history && history && !transientView() && f.source == previousSource && since >= 0 && since < f.clockJump
                && dx * dx + dy * dy + dz * dz < JUMP * JUMP;
        VIEW_PROJECTION.set(projection).mul(view);
        VIEW_PROJECTION.invert(INVERSE);
        view.positiveZ(FORWARD).negate();
        int main = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        try {
            // the march at low resolution into its own target
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            GlStateManager._viewport(0, 0, marchW, height);
            GlStateManager._colorMask(true, true, true, true);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.setShaderTexture(1, scene);
            m.safeGetUniform("InvViewProj").set(INVERSE);
            m.safeGetUniform("PallForward").set(FORWARD.x, FORWARD.y, FORWARD.z);
            m.safeGetUniform("DwDiv").set((float) div);
            m.safeGetUniform("DwInterleave").set(builtInterleave ? 1f : 0f, (float) (frame & 1), (float) width, 0f);
            m.safeGetUniform("DwProbe").set(DustWallDiag.probe == -1 && !DustWallDiag.split ? -9f : (float) (DustWallDiag.probe + 1));
            m.safeGetUniform("DwFrame").set(DustWallDiag.freeze ? 0f : (float) (frame % 4096), (float) step.wallSteps());
            // every source's table, terrain and material tiles and frame inputs; the outdoor-air window: the dust lies on the
            // local ground, never indoors
            uploadSources(f, warming);
            RenderSystem.setShaderTexture(2, table);
            RenderSystem.setShaderTexture(3, groundAtlas);
            RenderSystem.setShaderTexture(4, materialAtlas);
            if (PallPass.submits(m, warming)) OpenAir.bind(m, true, warming);
            if (!DustWallDiag.openAir) m.safeGetUniform("PallOpenAt").set(0f, 0f, 0f, 0f);
            m.safeGetUniform("DwSourceCount").set((float) (warming ? 1 : f.count));
            boolean terrain = !warming && terrainUploaded && DustWallDiag.terrain;
            m.safeGetUniform("DwTerrain0").set(f.terrain0[0], f.terrain0[1], terrain ? f.terrain0[2] : 0f, f.terrain0[3]);
            m.safeGetUniform("DwTerrain1").set(f.terrain1[0], f.terrain1[1], terrain ? f.terrain1[2] : 0f, f.terrain1[3]);
            m.safeGetUniform("DwSlotsA").set(SLOT_OF[0], SLOT_OF[1], SLOT_OF[2], SLOT_OF[3]);
            m.safeGetUniform("DwSlotsB").set(SLOT_OF[4], SLOT_OF[5], SLOT_OF[6], SLOT_OF[7]);
            m.safeGetUniform("DwSlotsC").set(SLOT_OF[8], SLOT_OF[9], SLOT_OF[10], SLOT_OF[11]);
            m.safeGetUniform("DwSlotsD").set(SLOT_OF[12], SLOT_OF[13], SLOT_OF[14], SLOT_OF[15]);
            m.safeGetUniform("DwAdapt").set(f.adapt);
            // the light's detail by the march divisor of the preset's rung (1/4 HIGH, 1/5 MEDIUM, 1/6 and coarser LOW)
            m.safeGetUniform("DwDetail").set(baseDiv <= 4 ? 0f : baseDiv == 5 ? 1f : 2f);
            m.safeGetUniform("DwSunLight").set(f.sunLight.x, f.sunLight.y, f.sunLight.z);
            m.safeGetUniform("DwSunDir").set(f.sunDir.x, f.sunDir.y, f.sunDir.z);
            m.safeGetUniform("DwSkyLight").set(f.skyLight.x, f.skyLight.y, f.skyLight.z);
            m.safeGetUniform("DwGroundLight").set(f.groundLight.x, f.groundLight.y, f.groundLight.z);
            m.safeGetUniform("DwAlbedo").set(f.albedo.x, f.albedo.y, f.albedo.z);
            m.safeGetUniform("DwBulk").set(f.bulk.x, f.bulk.y, f.bulk.z);
            m.safeGetUniform("DwSandAlbedo").set(f.sandAlbedo.x, f.sandAlbedo.y, f.sandAlbedo.z);
            m.safeGetUniform("DwSandBulk").set(f.sandBulk.x, f.sandBulk.y, f.sandBulk.z);
            m.safeGetUniform("DwMistAlbedo").set(f.mistAlbedo.x, f.mistAlbedo.y, f.mistAlbedo.z);
            m.safeGetUniform("DwMistBulk").set(f.mistBulk.x, f.mistBulk.y, f.mistBulk.z);
            m.safeGetUniform("PallAir").set(pall.air.x, pall.air.y, pall.air.z, pall.air.w);
            m.safeGetUniform("PallFloorFrom").set(pall.floorFrom);
            m.safeGetUniform("DwHorizonLift").set(SceneDepth.lod() ? 0f : HORIZON_LIFT);
            m.safeGetUniform("PallAirSky").set(pall.airSky.x, pall.airSky.y, pall.airSky.z);
            m.safeGetUniform("PallAirFog").set(pall.airFog.x, pall.airFog.y, pall.airFog.z, pall.airFog.w);
            int part = warming ? -1 : GPU_MARCH.begin();
            fullScreen(m, () -> Uniform.uploadInteger(Uniform.glGetUniformLocation(m.getId(), "DwNoise"), NOISE_UNIT), true, warming);
            GPU_MARCH.end(part);
            String sweepTo = DustWallDiag.sweep;
            if (sweepTo != null && !warming) {
                DustWallDiag.sweep = null;
                sweep(m, sweepTo);
            }
            PallPass.stamp(timing, 6);
            // Pack the three simultaneous outputs into one atlas. The thirds share
            // temporal weights, and all readers need only one texture unit.
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, packedFbo);
            GlStateManager._viewport(0, 0, 3 * marchW, height);
            RenderSystem.setShaderTexture(0, target);
            RenderSystem.setShaderTexture(1, bounds);
            RenderSystem.setShaderTexture(2, split);
            part = warming ? -1 : GPU_PACK.begin();
            fullScreen(pack, null, false, warming);
            GPU_PACK.end(part);
            PallPass.stamp(timing, 7);
            // averaged over frames at the same resolution into the other history target
            int next = 1 - latest;
            {
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, HISTORY_FBO[next]);
                RenderSystem.setShaderTexture(0, packed);
                RenderSystem.setShaderTexture(1, HISTORY_TEX[latest]);
                RenderSystem.setShaderTexture(2, scene);
                // the previous frame's world distances: history only where it saw the same place
                int before = SceneDepth.previous();
                RenderSystem.setShaderTexture(3, before >= 0 ? before : scene);
                r.safeGetUniform("DwPrevScene").set(before >= 0 ? 1f : 0f);
                r.safeGetUniform("InvViewProj").set(INVERSE);
                r.safeGetUniform("PrevViewProj").set(PREVIOUS);
                r.safeGetUniform("PallForward").set(FORWARD.x, FORWARD.y, FORWARD.z);
                GlStateManager._viewport(0, 0, 3 * width, height);
                r.safeGetUniform("DwDiv").set((float) div);
                r.safeGetUniform("DwInterleave").set(builtInterleave ? 1f : 0f, (float) (frame & 1), 0f, 0f);
                r.safeGetUniform("DwLow").set((float) width, (float) height);
                r.safeGetUniform("DwDiag").set(DustWallDiag.clampWidth, DustWallDiag.reprojection ? 0f : 1f);
                r.safeGetUniform("DwPrevDiv").set((float) historyDiv[latest]);
                r.safeGetUniform("DwReproject").set(dx, dy, dz, past ? 1f : 0f);
                r.safeGetUniform("DwHistory").set(HISTORY, f.layerTop - f.eye[1]);
                part = warming ? -1 : GPU_RESOLVE.begin();
                fullScreen(r, null, false, warming);
                GPU_RESOLVE.end(part);
                latest = next;
                PREVIOUS.set(VIEW_PROJECTION);
                System.arraycopy(f.eye, 0, PREVIOUS_EYE, 0, 3);
                previousSource = f.source;
                previousTime = f.eye[3];
                history = !warming;
                if (!warming) frame++;
                if (DIAG && !warming) readBack();
                String dumpTo = DustWallDiag.dump;
                if (dumpTo != null && !warming) {
                    DustWallDiag.dump = null;
                    dump(dumpTo);
                }
            }
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main);
            GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
        }
        PallPass.stamp(timing, 8);
        GPU.end(timed);
    }

    /** Source-table slot of each frame source ({@code DwSlotsA/B}), and the detonation in each slot with its last use. */
    private static final float[] SLOT_OF = new float[MAX_SOURCES];
    private static final Object[] SLOT_ID = new Object[MAX_SOURCES];
    private static final long[] SLOT_USED = new long[MAX_SOURCES];
    /** What each slot's GPU tiles hold: table (its detonation), terrain (map owner, fill generation), material (tiles object). */
    private static final Object[] TABLE_IN = new Object[MAX_SOURCES], GROUND_IN = new Object[MAX_SOURCES], MATERIAL_IN = new Object[MAX_SOURCES];
    private static final int[] GROUND_VERSION = new int[MAX_SOURCES];
    private static int groundAtlas = -1, materialAtlas = -1;
    private static FloatBuffer groundBuffer;
    private static final ByteBuffer MATERIAL = MemoryUtil.memAlloc(2 * ua.zentix.incoming.client.nuclear.DustMaterialTiles.SIDE
            * ua.zentix.incoming.client.nuclear.DustMaterialTiles.SIDE * 4);
    /** A slot unused this many frames is free for another detonation. */
    private static final int SLOT_IDLE = 120;

    /** Slot of detonation {@code id}: its own while it is drawn; else the slot idle longest. */
    private static int slot(Object id) {
        int free = -1;
        for (int i = 0; i < MAX_SOURCES; i++) {
            if (SLOT_ID[i] == id) return i;
            if (SLOT_ID[i] == null || frame - SLOT_USED[i] > SLOT_IDLE) {
                if (free < 0 || SLOT_ID[free] != null && (SLOT_ID[i] == null || SLOT_USED[i] < SLOT_USED[free])) free = i;
            }
        }
        if (free < 0) {
            // every slot drawn within the idle window: the one used longest ago (a frame holds at most MAX_SOURCES)
            free = 0;
            for (int i = 1; i < MAX_SOURCES; i++) if (SLOT_USED[i] < SLOT_USED[free]) free = i;
        }
        SLOT_ID[free] = id;
        TABLE_IN[free] = GROUND_IN[free] = MATERIAL_IN[free] = null;
        return free;
    }

    private static int texture(int filter) {
        int id = GlStateManager._genTexture();
        GlStateManager._bindTexture(id);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, filter);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, filter);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        return id;
    }

    private static void unpack(int alignment) {
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, alignment);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
    }

    /**
     * The frame's sources into the source table (each detonation's table once, its frame inputs every frame), the terrain
     * atlas (a map when its fill generation changes) and the material atlas (when cells were learned); {@link #SLOT_OF} maps
     * the frame's order to slots.
     */
    private static void uploadSources(Frame f, boolean warming) {
        int sl = SOURCE_ROWS;
        if (table < 0) {
            table = texture(GlConst.GL_NEAREST);
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, NODES, sl * MAX_SOURCES, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
            groundAtlas = texture(GlConst.GL_LINEAR);
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, GROUND_SIDE * (MAX_SOURCES + 2), GROUND_SIDE, 0, GL11.GL_RGBA,
                    GL11.GL_FLOAT, null);
            int side = ua.zentix.incoming.client.nuclear.DustMaterialTiles.SIDE;
            materialAtlas = texture(GlConst.GL_LINEAR);
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2 * side, side * MAX_SOURCES, 0, GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, null);
        }
        int count = warming ? 1 : f.count;
        for (int k = 0; k < count; k++) {
            Source src = f.sources[k];
            int s = warming ? 0 : slot(src.id);
            SLOT_OF[k] = s;
            SLOT_USED[s] = frame;
            if (src.table != null && (TABLE_IN[s] != src.id || warming)) {
                TABLE.clear();
                TABLE.put(src.table, 0, NODES * TABLE_ROWS * 4).flip();
                GlStateManager._bindTexture(table);
                unpack(4);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, s * sl, NODES, TABLE_ROWS, GL11.GL_RGBA, GL11.GL_FLOAT, TABLE);
                PallPass.note("dust table " + s);
                TABLE_IN[s] = warming ? null : src.id;
            }
            TABLE.clear();
            TABLE.put(src.params).flip();
            GlStateManager._bindTexture(table);
            unpack(4);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, s * sl + sl - 1, PARAMS, 1, GL11.GL_RGBA, GL11.GL_FLOAT, TABLE);
            PallPass.Ground g = src.ground;
            if (g.map != null && g.side == GROUND_SIDE && (GROUND_IN[s] != g.source || GROUND_VERSION[s] != g.version)) {
                int n = GROUND_SIDE * GROUND_SIDE * 4;
                if (groundBuffer == null) groundBuffer = MemoryUtil.memAllocFloat(n);
                groundBuffer.clear();
                groundBuffer.put(g.map, 0, n).flip();
                GlStateManager._bindTexture(groundAtlas);
                unpack(4);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, s * GROUND_SIDE, 0, GROUND_SIDE, GROUND_SIDE, GL11.GL_RGBA, GL11.GL_FLOAT, groundBuffer);
                PallPass.note("dust ground " + s);
                GROUND_IN[s] = g.source;
                GROUND_VERSION[s] = g.version;
            }
            var mat = src.material;
            if (mat != null && (MATERIAL_IN[s] != mat || mat.stale())) {
                int side = ua.zentix.incoming.client.nuclear.DustMaterialTiles.SIDE;
                GlStateManager._bindTexture(materialAtlas);
                unpack(1);
                for (int t = 0; t < 2; t++) {
                    MATERIAL.clear();
                    MATERIAL.put(mat.rgba, t * side * side * 4, side * side * 4).flip();
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, t * side, s * side, side, side, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MATERIAL);
                }
                mat.uploaded();
                MATERIAL_IN[s] = mat;
            }
        }
        if (!warming) uploadTerrain(f);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    /** The eye-centred ground tiles' generation in the atlas ({@code DustGround}); whether both are there. */
    private static long terrainVersion = -1;
    private static boolean terrainUploaded;
    private static final float[] TERRAIN_RG = new float[2 * GROUND_SIDE * GROUND_SIDE];

    /** The eye's fine and coarse ground tiles into the atlas's last two tiles when they changed. */
    private static void uploadTerrain(Frame f) {
        long v = ua.zentix.incoming.client.nuclear.DustGround.version();
        if (v == terrainVersion) return;
        boolean all = true;
        int n = GROUND_SIDE * GROUND_SIDE * 4;
        if (groundBuffer == null) groundBuffer = MemoryUtil.memAllocFloat(n);
        GlStateManager._bindTexture(groundAtlas);
        unpack(4);
        for (int k = 0; k < 2; k++) {
            if (!ua.zentix.incoming.client.nuclear.DustGround.pack(k, TERRAIN_RG)) {
                all = false;
                continue;
            }
            groundBuffer.clear();
            for (int i = 0; i < GROUND_SIDE * GROUND_SIDE; i++) groundBuffer.put(TERRAIN_RG[2 * i]).put(TERRAIN_RG[2 * i + 1]).put(0f).put(0f);
            groundBuffer.flip();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, (MAX_SOURCES + k) * GROUND_SIDE, 0, GROUND_SIDE, GROUND_SIDE, GL11.GL_RGBA,
                    GL11.GL_FLOAT, groundBuffer);
        }
        PallPass.note("dust eye ground");
        // a source's map upload compares its own owner and generation: the atlas still holds it
        terrainVersion = v;
        terrainUploaded = all;
    }

    private static boolean targets(int w, int h, int d) {
        int lw = Math.max(1, (w + d - 1) / d), lh = Math.max(1, (h + d - 1) / d);
        if (lattice < 0) lattice = lattice();
        int program = march.getId();
        boolean sameFrame = w == fullW && h == fullH && program == marchId;
        boolean il = !transientView() && DustWallDiag.interleave;
        boolean sameMarch = target >= 0 && sameFrame && d == div && il == builtInterleave;
        if (sameMarch && historyDiv[1 - latest] == d) return true;
        PallPass.note(String.format(java.util.Locale.ROOT, "dust targets %dx%d div %d (was %d)", lw, lh, d, div));
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (target < 0) {
            target = GlStateManager._genTexture();
            fbo = GlStateManager.glGenFramebuffers();
            bounds = GlStateManager._genTexture();
            split = GlStateManager._genTexture();
            packed = GlStateManager._genTexture();
            packedFbo = GlStateManager.glGenFramebuffers();
            for (int i = 0; i < 2; i++) {
                HISTORY_TEX[i] = GlStateManager._genTexture();
                HISTORY_FBO[i] = GlStateManager.glGenFramebuffers();
            }
        }
        boolean ok = true;
        int mw = il ? (lw + 1) / 2 : lw;
        if (!sameMarch) {
            attach(fbo, bounds, mw, lh, GlConst.GL_NEAREST, GL30.GL_COLOR_ATTACHMENT1);
            attach(fbo, split, mw, lh, GlConst.GL_NEAREST, GL30.GL_COLOR_ATTACHMENT2);
            attach(fbo, target, mw, lh, GlConst.GL_NEAREST, GL30.GL_COLOR_ATTACHMENT0);
            // GLSL 150 assigns the output locations at link time.
            // each output to its own attachment by the location the link gave it (light 0, span 1, split 2)
            int[] buffers = new int[3];
            String[] outputs = {"fragColor", "wallDepth", "wallSplit"};
            for (int o = 0; o < 3; o++) {
                int location = GL30.glGetFragDataLocation(program, outputs[o]);
                if (location >= 0 && location < 3) buffers[location] = GL30.GL_COLOR_ATTACHMENT0 + o;
            }
            GL20.glDrawBuffers(buffers);
            ok = complete();
            attach(packedFbo, packed, 3 * mw, lh, GlConst.GL_NEAREST, GL30.GL_COLOR_ATTACHMENT0);
            ok &= complete();
        }
        // history is read between its pixels when the view turns: linear
        for (int i = 0; i < 2; i++) {
            // Reproject the last image on its own lattice. Resize its storage only after the other image replaces it.
            if (sameFrame && (historyDiv[i] == d || history && i == latest)) continue;
            attach(HISTORY_FBO[i], HISTORY_TEX[i], 3 * lw, lh, GlConst.GL_LINEAR, GL30.GL_COLOR_ATTACHMENT0);
            ok &= complete();
            historyDiv[i] = d;
        }
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        if (!ok) {
            Incoming.LOG.error("Nuclear dust wave: targets {}×{} not created, no dust wave pass", lw, lh);
            fullW = fullH = 0;
            failed = true;
            return false;
        }
        marchId = program;
        marchW = mw;
        builtInterleave = il;
        width = lw;
        height = lh;
        fullW = w;
        fullH = h;
        div = d;
        history &= sameFrame;
        return true;
    }

    private static void attach(int framebuffer, int texture, int w, int h, int filter, int attachment) {
        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, filter);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, filter);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, attachment, GlConst.GL_TEXTURE_2D, texture, 0);
    }

    private static boolean complete() {
        return GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
    }

    /** The 128³ lattice of random values the wall's value noise reads ({@code dwNoise3}, {@code DustNoise}). */
    private static int lattice() {
        int n = LATTICE;
        byte[] values = ua.zentix.incoming.client.nuclear.DustNoise.values();
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

    private static void fullScreen(ShaderInstance s, Runnable uniforms, boolean noise, boolean warming) {
        if (!PallPass.submits(s, warming)) return;
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

    /**
     * A secondary view's own march targets and history (a view's preparation never touches another's targets or
     * matrices), swapped with the statics while that view draws ({@code PallPass.ViewState}); the source tables, terrain and
     * material atlases are shared (uploaded by whichever view draws first in a frame, each detonation's table once).
     */
    public static final class ViewState {
        private int limitTex = -1, limitFbo = -1, limitW, limitH;
        private int target = -1, fbo = -1, bounds = -1, split = -1, packed = -1, packedFbo = -1, width, height, div, fullW, fullH, marchW, latest,
                marchId = -1, baseDiv = 4;
        private boolean builtInterleave, history, hazeOnly, preparedHazeOnly;
        /** This view's frame counter (interleave parity and jitter run per view). */
        private long frame;
        private final int[] historyTex = {-1, -1}, historyFbo = {-1, -1}, historyDivs = new int[2];
        private final float[] previousEye = new float[3];
        private Object previousSource;
        private float previousTime;
        private final Matrix4f previous = new Matrix4f();

        public void swap() {
            int t;
            t = DustWallPass.target; DustWallPass.target = target; target = t;
            t = DustWallPass.fbo; DustWallPass.fbo = fbo; fbo = t;
            t = DustWallPass.bounds; DustWallPass.bounds = bounds; bounds = t;
            t = DustWallPass.split; DustWallPass.split = split; split = t;
            t = DustWallPass.packed; DustWallPass.packed = packed; packed = t;
            t = DustWallPass.packedFbo; DustWallPass.packedFbo = packedFbo; packedFbo = t;
            t = DustWallPass.width; DustWallPass.width = width; width = t;
            t = DustWallPass.height; DustWallPass.height = height; height = t;
            t = DustWallPass.div; DustWallPass.div = div; div = t;
            t = DustWallPass.fullW; DustWallPass.fullW = fullW; fullW = t;
            t = DustWallPass.fullH; DustWallPass.fullH = fullH; fullH = t;
            t = DustWallPass.marchW; DustWallPass.marchW = marchW; marchW = t;
            t = DustWallPass.latest; DustWallPass.latest = latest; latest = t;
            t = DustWallPass.marchId; DustWallPass.marchId = marchId; marchId = t;
            t = DustWallPass.baseDiv; DustWallPass.baseDiv = baseDiv; baseDiv = t;
            t = DustWallPass.limitTex; DustWallPass.limitTex = limitTex; limitTex = t;
            t = DustWallPass.limitFbo; DustWallPass.limitFbo = limitFbo; limitFbo = t;
            t = DustWallPass.limitW; DustWallPass.limitW = limitW; limitW = t;
            t = DustWallPass.limitH; DustWallPass.limitH = limitH; limitH = t;
            boolean b = DustWallPass.builtInterleave; DustWallPass.builtInterleave = builtInterleave; builtInterleave = b;
            b = DustWallPass.history; DustWallPass.history = history; history = b;
            b = DustWallPass.hazeOnly; DustWallPass.hazeOnly = hazeOnly; hazeOnly = b;
            b = DustWallPass.preparedHazeOnly; DustWallPass.preparedHazeOnly = preparedHazeOnly; preparedHazeOnly = b;
            long fr = DustWallPass.frame; DustWallPass.frame = frame; frame = fr;
            for (int i = 0; i < 2; i++) {
                t = HISTORY_TEX[i]; HISTORY_TEX[i] = historyTex[i]; historyTex[i] = t;
                t = HISTORY_FBO[i]; HISTORY_FBO[i] = historyFbo[i]; historyFbo[i] = t;
                t = historyDiv[i]; historyDiv[i] = historyDivs[i]; historyDivs[i] = t;
            }
            for (int i = 0; i < 3; i++) {
                float v = PREVIOUS_EYE[i]; PREVIOUS_EYE[i] = previousEye[i]; previousEye[i] = v;
            }
            Object o = DustWallPass.previousSource; DustWallPass.previousSource = previousSource; previousSource = o;
            float v = DustWallPass.previousTime; DustWallPass.previousTime = previousTime; previousTime = v;
            SWAP.set(PREVIOUS);
            PREVIOUS.set(previous);
            previous.set(SWAP);
        }

        public void close() {
            FxViews.textures(target, bounds, split, packed, historyTex[0], historyTex[1], limitTex);
            FxViews.framebuffers(fbo, packedFbo, historyFbo[0], historyFbo[1], limitFbo);
        }
    }

    private static final boolean DIAG = Boolean.getBoolean("incoming.nukeDiag");
    private static long lastRead;
    private static String channel = "none yet";

    /**
     * A1 readback (only under {@code incoming.nukeDiag}, at most once a second: a synchronous read): the ground-dust channel of
     * the wave's last resolved image, its first third (light, transmittance): the share of its pixels with opacity above 0.01,
     * their mean and the largest opacity.
     */
    private static void readBack() {
        long now = net.minecraft.Util.getMillis();
        if (now - lastRead < 1000) return;
        lastRead = now;
        int read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        java.nio.FloatBuffer px = MemoryUtil.memAllocFloat(width * height * 4);
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, HISTORY_FBO[latest]);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_FLOAT, px);
            int covered = 0;
            double sum = 0, max = 0;
            for (int i = 0; i < width * height; i++) {
                double a = 1 - px.get(4 * i + 3);
                if (!(a > 0.01)) continue;
                covered++;
                sum += a;
                max = Math.max(max, a);
            }
            channel = String.format(java.util.Locale.ROOT, "cover %.2f %% (opacity > 0.01), mean %.3f, max %.3f",
                    100.0 * covered / Math.max(width * height, 1), covered > 0 ? sum / covered : 0, max);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            MemoryUtil.memFree(px);
        }
    }

    /**
     * Diagnostics ({@link DustWallDiag#dump}): this frame's march outputs (light, depth span, interval split at the march
     * lattice, packed two to a column pair while interleaved) and the resolved atlas (three thirds), as little-endian RGBA
     * float32 files {@code <prefix>_<name>.f32} with their sizes and the frame's lattice in {@code <prefix>_dw.json}.
     */
    private static void dump(String prefix) {
        write(prefix, new int[][] {{target, marchW, height}, {bounds, marchW, height}, {split, marchW, height}, {HISTORY_TEX[latest], 3 * width, height}},
                new String[] {"march", "span", "split", "atlas"});
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(prefix + "_dw.json"), String.format(java.util.Locale.ROOT,
                    "{\"march\": [%d, %d], \"atlas\": [%d, %d], \"div\": %d, \"interleave\": %b, \"parity\": %d, \"frame\": %d, \"history\": %b, \"full\": [%d, %d]}",
                    marchW, height, 3 * width, height, div, builtInterleave, (frame - 1) & 1, frame - 1, history && DustWallDiag.history, fullW, fullH));
            Incoming.LOG.info("Nuclear dust wave: diagnostic targets written to {}_*.f32 (div {}, interleave {})", prefix, div, builtInterleave);
        } catch (java.io.IOException e) {
            Incoming.LOG.warn("Nuclear dust wave: diagnostic targets not written to {}", prefix, e);
        }
    }

    /**
     * Diagnostics ({@link DustWallDiag#sweep}): this frame's march again once per occupied sample k with the probe on, each pass's
     * three targets written as {@code <prefix>_p<k>_{march,span,split}.f32}, then the march once more as drawn (the frame's
     * picture). All passes see the same inputs, so every record walks the same ray.
     */
    private static void sweep(ShaderInstance m, String prefix) {
        int n = Math.max(1, DustWallDiag.sweepSamples);
        for (int k = 0; k <= n; k++) {
            m.safeGetUniform("DwProbe").set(k == n ? (float) (DustWallDiag.probe + 1) : (float) (k + 1));
            fullScreen(m, () -> Uniform.uploadInteger(Uniform.glGetUniformLocation(m.getId(), "DwNoise"), NOISE_UNIT), true, false);
            if (k < n) {
                write(prefix + "_p" + k, new int[][] {{target, marchW, height}, {bounds, marchW, height}, {split, marchW, height}},
                        new String[] {"march", "span", "split"});
            }
        }
        Incoming.LOG.info("Nuclear dust wave: probe sweep of {} samples written to {}_p*_*.f32", n, prefix);
    }

    private static void write(String prefix, int[][] parts, String[] names) {
        try {
            java.nio.file.Path base = java.nio.file.Path.of(prefix);
            java.nio.file.Files.createDirectories(base.toAbsolutePath().getParent());
            for (int i = 0; i < parts.length; i++) {
                int w = parts[i][1], h = parts[i][2];
                java.nio.FloatBuffer px = MemoryUtil.memAllocFloat(w * h * 4);
                try {
                    GlStateManager._bindTexture(parts[i][0]);
                    GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, px);
                    java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(w * h * 16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    out.asFloatBuffer().put(px);
                    java.nio.file.Files.write(java.nio.file.Path.of(prefix + "_" + names[i] + ".f32"), out.array());
                } finally {
                    MemoryUtil.memFree(px);
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            Incoming.LOG.warn("Nuclear dust wave: diagnostic targets not written to {}", prefix, e);
        }
    }

    /** The A1 readback's last result ({@link #readBack}). */
    public static String channel() {
        return channel;
    }

    /** Forget the failure and the history (shaders reloaded; GL objects stay, the context outlives them). */
    public static void reset() {
        failed = false;
        fullW = 0; // relink can change the output locations
        clearHistory();
    }

    public static void clearHistory() {
        previousSource = null;
        history = false;
    }

    /** The slots' detonations (their fields, terrain and material tiles) are not kept past a clear, a seek or a world exit. */
    public static void clearSlots() {
        java.util.Arrays.fill(SLOT_ID, null);
        java.util.Arrays.fill(TABLE_IN, null);
        java.util.Arrays.fill(GROUND_IN, null);
        java.util.Arrays.fill(MATERIAL_IN, null);
        terrainVersion = -1;
        terrainUploaded = false;
    }

    /** The pass's state for the timing log: resolution, step cap, history. */
    public static String describe() {
        return String.format(java.util.Locale.ROOT, "1/%d (preset 1/%d), march %.3f pack %.3f resolve %.3f ms, history %s, frame %d", div, baseDiv,
                GPU_MARCH.lastMean, GPU_PACK.lastMean, GPU_RESOLVE.lastMean, history && HISTORY_ON ? "on" : "off", frame);
    }

    /** No dust wave this frame in this view: its next one starts its history afresh. */
    static void idle() {
        clearHistory();
    }
}
