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
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import ua.zentix.incoming.client.nuclear.NukeBallSurface;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.client.nuclear.MushroomField;
import ua.zentix.incoming.compat.FlashbackReplay;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Post-shock pall as a volume, in three effects layer passes (before particle quads):
 * <ol>
 *     <li>{@code fx_pall} — one ray per reduced-resolution frame pixel (half or quarter, {@link Quality}) through the detonation
 *     field ({@code client/nuclear/PallField}: radial table) × 3D noise ({@link VolumeNoise}); step origin
 *     shifted by blue noise ({@link BlueNoise}) and between frames;</li>
 *     <li>{@code fx_pall_resolve} — temporal accumulation: reproject history by world depth behind the pixel, clamp to
 *     neighbouring values (grain fades, without motion trails);</li>
 *     <li>{@code fx_pall_composite} — composite: pall light plus the world behind it, attenuated and shadowed by the pall (shadow from the
 *     noiseless field at each pixel); upsample by world-distance similarity weights (sharp building
 *     edges).</li>
 * </ol>
 * Layer quads then draw with their share of pall in front ({@code fx.vsh}: same field and table, without
 * noise).
 * <p>
 * Above a burning city, the same passes draw the firestorm ({@code client/nuclear/FireStorm}): each detonation slot has a
 * fire map ({@link Fire}, RGBA16F source cells with mip levels — clusters of light and smoke sources):
 * rays draw smoke columns, upward glow on pall and smoke, fire whirls; composite draws flames on city surfaces
 * (by world distance behind the pixel, including Distant Horizons LOD); layer quads receive firelight from below.
 * <p>
 * Same rays draw frame mushrooms as volumes ({@code mushroom.glsl}, {@code client/nuclear/MushroomField}): mushroom table
 * (RGBA32F, one mushroom row and one black-body light row) shared by rays, accumulation (reprojection by mushroom distance) and layer
 * quads (mushroom occlusion in front of the particle). Mushroom draws even without pall or with pall disabled in settings (then at
 * LOW): no other mushroom renderer.
 * <p>
 * By day mushrooms shadow the world: shadow map before rays ({@code fx_mush_shadow}, tile
 * {@link MushroomField#SHADOW}² per mushroom in a plane perpendicular to the sun: sunlight fraction transmitted to three
 * heights, as direct beam and forward scattering); composite samples it at each world pixel ({@code fx_pall_composite}).
 * <p>
 * Custom textures — three reduced-resolution frames (RGBA16F), 128×4 table, 256×5 mushroom table (RGBA32F), 128×512 mushroom shadow
 * map (RGBA16F, 0.5 MB), noise 64³ (RG8) and 64² (R8): together under 2.5 MB at 1080p; fire maps up to 0.7 MB per slot (side up to 256 cells), terrain/water maps under the pall — 64 KB per slot. Noise computed in the background on the first
 * detonation (about one second); no pall until ready.
 * <p>
 * Detail holds its own budget ({@link Detail}): GPU pass times measured by OpenGL timestamps, read after
 * a few frames (no waiting); while above {@link Detail#BUDGET_MS} ms, ray frame resolution and step count decrease
 * ({@link #LADDER}, no lower than LOW); with headroom, detail returns toward the setting. In client scenarios
 * and Flashback video export, frames are not real-time: a slow frame costs nothing there, so detail always
 * follows the setting ({@link Detail#pin}). {@code SCENARIO pall} line — {@link #describe}.
 */
public final class PallPass {
    /** Radial table nodes (texture width). */
    public static final int WIDTH = 128;
    /** Table rows: dust, spray and haze for two detonations. */
    public static final int ROWS = 6;
    /**
     * 3D noise texture unit: after the twelve 2D units bound by {@code ShaderInstance}
     * ({@code Sampler0}–{@code Sampler11}).
     */
    private static final int NOISE_UNIT = 12;
    /** Maximum accumulation reprojection distance in blocks (sky behind the pall). */
    private static final float REPROJECT_MAX = 600;
    /** Eye displacement beyond this in one frame invalidates history (teleport, projectile camera). */
    private static final double JUMP = 30;

    /** Pall quality setting — finest {@link #LADDER} rung: frame downscale factor and steps per ray. */
    public enum Quality {
        OFF(0, 0), LOW(4, 20), MEDIUM(2, 28), HIGH(2, 48);

        final int factor, steps;

        Quality(int factor, int steps) {
            this.factor = factor;
            this.steps = steps;
        }

        /** {@link #LADDER} rung for this quality (OFF — −1). */
        int step() {
            for (int i = 0; i < LADDER.length; i++) if (LADDER[i].factor == factor && LADDER[i].steps == steps) return i;
            return -1;
        }
    }

    /** Detail rung: ray frame downscale factor and steps per ray. */
    record Step(int factor, int steps) {
        /** Relative frame cost: rays — pixels / factor², each with its steps. */
        double cost() {
            return steps / (double) (factor * factor);
        }
    }

    /**
     * Detail rungs from HIGH to LOW: each costs 0.5 to 0.85 of the previous one. Step count changes without a new
     * framebuffer; factor changes recreate reduced-resolution frames (history restarts).
     */
    static final Step[] LADDER = {new Step(2, 48), new Step(2, 36), new Step(2, 28), new Step(2, 22), new Step(3, 26),
            new Step(3, 20), new Step(4, 24), new Step(4, 20)};

    /**
     * Detail rung by GPU pass time: above budget over a half-second average (with at least
     * four measurements) — immediately switch to a rung estimated to fit; headroom — one rung up if its estimated cost
     * is at most {@link #UP_SHARE} of budget for two consecutive windows and {@link #HOLD_NS} has passed since the last change (four times
     * longer after an increase immediately followed by a decrease). No higher than the setting and no lower than LOW;
     * pinned ({@link #pin}) — setting rung, unaffected by measurements. No world or OpenGL — unit test.
     */
    static final class Detail {
        /** Per-frame GPU pall budget, ms. */
        static final double BUDGET_MS = 2;
        static final double UP_SHARE = 0.7;
        static final long WINDOW_NS = 500_000_000L, HOLD_NS = 3_000_000_000L;
        static final int MIN_SAMPLES = 4, CALM_WINDOWS = 2;

        /** Current and finest rung (from setting); −1 — not set yet. */
        int level = -1, ceiling = -1;
        /** Rung generation by change count: measurements before a change are ignored. */
        int generation;
        /** Rung changes caused by measurements. */
        int changes;
        /** Last window average, ms. */
        double lastMean;
        /** Rung pinned to the setting ({@link #pin}). */
        boolean pinned;
        private double sum;
        private int samples, calm;
        private long windowStart, holdUntil, raisedAt = Long.MIN_VALUE;
        private boolean skip;

        /** Setting ceiling: if changed, switch to it immediately. */
        void ceiling(int c) {
            if (c == ceiling) return;
            ceiling = c;
            set(c);
        }

        /**
         * Pin rung to setting or release it: non-real-time frames (client scenario, Flashback video
         * export) — a slow frame must not reduce detail. true — changed (rung at ceiling, measurements
         * reset).
         */
        boolean pin(boolean on) {
            if (on == pinned) return false;
            pinned = on;
            calm = 0;
            if (ceiling >= 0) set(ceiling);
            return true;
        }

        /**
         * Ignore earlier measurements (pall out of view, frame size changed): pending timestamp pairs belong
         * to the previous generation; first frame afterwards has cold caches.
         */
        void restart() {
            calm = 0;
            if (ceiling >= 0) set(level);
        }

        /** Frame measurement at rung generation {@code gen}, time {@code now} (ns); true — rung changed. */
        boolean sample(double ms, int gen, long now) {
            if (gen != generation || ceiling < 0 || pinned) return false;
            // first rung frame has new framebuffers and cold caches
            if (skip) {
                skip = false;
                return false;
            }
            if (samples == 0) windowStart = now;
            sum += ms;
            samples++;
            if (samples < MIN_SAMPLES || now - windowStart < WINDOW_NS) return false;
            double mean = sum / samples;
            lastMean = mean;
            sum = 0;
            samples = 0;
            int floor = LADDER.length - 1;
            if (mean > BUDGET_MS && level < floor) {
                int to = level + 1;
                while (to < floor && mean * LADDER[to].cost() / LADDER[level].cost() > BUDGET_MS) to++;
                // an increase exceeded capacity — delay the next increase
                holdUntil = now + (raisedAt != Long.MIN_VALUE && now - raisedAt < 2 * HOLD_NS ? 4 * HOLD_NS : HOLD_NS);
                calm = 0;
                set(to);
                changes++;
                return true;
            }
            if (level > ceiling && now >= holdUntil && mean * LADDER[level - 1].cost() / LADDER[level].cost() <= UP_SHARE * BUDGET_MS) {
                if (++calm >= CALM_WINDOWS) {
                    calm = 0;
                    holdUntil = now + HOLD_NS;
                    raisedAt = now;
                    set(level - 1);
                    changes++;
                    return true;
                }
            } else {
                calm = 0;
            }
            return false;
        }

        private void set(int to) {
            level = Math.max(ceiling, Math.min(LADDER.length - 1, to));
            generation++;
            skip = true;
            sum = 0;
            samples = 0;
        }
    }

    /** Slot firestorm ({@code client/nuclear/FireStorm}): shader parameters and fire map. */
    public static final class Fire {
        /** Fire slot ({@code pall.glsl}); zeroes — no storm for this slot. */
        public final Matrix4f params = new Matrix4f().zero();
        /** Fire map: RGBA per cell, side {@link #side}, x fastest; null — no storm. */
        @Nullable
        public float[] map;
        public int side;
        /** Map owner and fill generation: reload texture only when either changes. */
        @Nullable
        public Object source;
        public int version;

        public void clear() {
            params.zero();
            map = null;
            source = null;
            side = version = 0;
        }

        boolean on() {
            return map != null && side > 0 && params.m00() > 0;
        }
    }

    /**
     * Terrain and water beneath a slot's pall ({@code client/nuclear/PallGround}): endpoint for rays without a world hit, and
     * pall material (dust over land, spray over water). Map of height above epicentre ground (R) and water fraction (G), RG16F with
     * bilinear sampling, clamped edges.
     */
    public static final class Ground {
        /** Map half-side, bottom and top (above epicentre ground), cell size — blocks ({@code pall.glsl}); zeroes — no map. */
        public final Vector4f params = new Vector4f();
        /** Height and water fraction per cell (two numbers), side {@link #side}, x fastest; null — no map. */
        @Nullable
        public float[] map;
        public int side;
        /** Map owner and fill generation: reload texture only when either changes. */
        @Nullable
        public Object source;
        public int version;

        public void clear() {
            params.zero();
            map = null;
            source = null;
            side = version = 0;
        }

        boolean on() {
            return map != null && side > 0 && params.x > 0;
        }
    }

    /** Frame pall — renderer input ({@code client/nuclear/NukePall#frame}). */
    public static final class Frame {
        /** Detonation slots ({@code pall.glsl}): at most two most prominent. */
        public final Matrix4f slot0 = new Matrix4f().zero(), slot1 = new Matrix4f().zero();
        /** Field table: {@link #ROWS} rows of {@link #WIDTH} nodes, RGBA. */
        public final float[] table = new float[ROWS * WIDTH * 4];
        /** Eye-relative bounds of all slots, blocks. */
        public final Vector3f min = new Vector3f(), max = new Vector3f();
        /** Direction to sun and daytime strength 0..1. */
        public final Vector4f sun = new Vector4f();
        /**
         * Sun and sky light — linear (white wall at noon — 1, {@code client/nuclear/PallLight}); fire glow beneath
         * haze — on a screen white wall, added over {@link #base}.
         */
        public final Vector3f sunLight = new Vector3f(), skyLight = new Vector3f(), fireLight = new Vector3f();
        /** Screen frame skylight on a white wall: 1 by day, at night like dust wall billows ({@code FarView.ambient}). */
        public float base = 1;
        /**
         * Ball and fire light on slot pall ({@code client/nuclear/CloudPuffs#pallLight}): glow — cap radius, fire
         * glow, cap centre wind drift and fire zone edge (blocks); hot — black-body light at visible cap
         * temperature (rgb, linear, white wall at noon — 1), centre height above epicentre ground (blocks, w).
         * Zeroes — no glow.
         */
        public final Vector4f glow0 = new Vector4f(), glow1 = new Vector4f(), hot0 = new Vector4f(), hot1 = new Vector4f();
        /** Fire glow strength on dust under frame skylight ({@code CloudPuffs#glowOnSmoke}). */
        public float glowOnSmoke;
        /** Thin pall screen colour (rgb) — for layer quads before particles; w — 1 if pall exists. */
        public final Vector4f ambient = new Vector4f();
        /**
         * Eye dust: local extinction (1/block), vertical optical depths above the eye and below it to ground — ray range
         * ({@code pallClip} in {@code fx_pall}: nothing visible beyond ray depth 12). Zeroes — no ray cutoff.
         */
        public final Vector3f eyeDust = new Vector3f();
        public Quality quality = Quality.MEDIUM;
        /** Slot firestorms. */
        public final Fire fire0 = new Fire(), fire1 = new Fire();
        /** Eye-relative storm bounds (columns, glow, whirls), blocks: denser ray steps there; min > max — no storm. */
        public final Vector3f fireMin = new Vector3f(), fireMax = new Vector3f();
        /**
         * Frame fire whirls (up to four, one column each): base (eye-relative x, z, ground y) and core
         * radius; height, strength 0..1 (0 — no whirl), seconds since start, rotation ±1; wind lean (x, z per block of
         * height), flame, seed.
         */
        public final Matrix4f whirlPos = new Matrix4f().zero(), whirlShape = new Matrix4f().zero(), whirlLean = new Matrix4f().zero();
        /** Terrain and water beneath slot pall. */
        public final Ground ground0 = new Ground(), ground1 = new Ground();
        /**
         * Frame air ({@code pall.glsl}: {@code PallAir}; {@code MushroomField#air}): haze, clear air and weather at the
         * eye, 1/block, and 1 / haze height scale, 1/block — distant mushroom, pall and wall fade through intervening haze.
         */
        public final Vector4f air = new Vector4f();
        /**
         * Slot water ({@code pall.glsl}): base surge radius and top (blocks), extinction at its densest part
         * (1/block; zeroes — no surge); 1 — water beneath pall (shader reads its map), 0 — none.
         */
        public final Vector4f water0 = new Vector4f(), water1 = new Vector4f();
        /**
         * Slot dust wall ({@code client/nuclear/BlastWall#slot}, {@code pall.glsl}): rolling head (zeroes — no head)
         * and Wilson cloud (z = 0 — no cloud).
         */
        public final Matrix4f wall0 = new Matrix4f().zero(), wall1 = new Matrix4f().zero();
        public final Vector4f wilson0 = new Vector4f(), wilson1 = new Vector4f();
        /**
         * Frame world extends at least this many horizontal blocks from the eye (render distance): a ray into the void passed through it
         * without a hit — terrain beneath the pall ({@link Ground}) clips it only farther away.
         */
        public float floorFrom;
        /**
         * Frame mushrooms ({@code mushroom.glsl}): mushroom rows of the {@link MushroomField#WIDTH} × {@link MushroomField#ROWS} table
         * (black-body light row unneeded here — already in VRAM), and count.
         */
        public final float[] mushrooms = new float[MushroomField.WIDTH * MushroomField.ROWS * 4];
        public int mushroomCount;
        /** Apparent light brightness multiplier under frame skylight ({@code Sight.adapted}): mushroom glow. */
        public float exposure = 1;
        /**
         * Fireballs lighting the frame world ({@code MushroomField.balls}): centres/radii, light and air extinction by
         * column; whether any emits light (otherwise composite skips them and does not copy the frame).
         */
        public final Matrix4f balls = new Matrix4f().zero(), ballLight = new Matrix4f().zero();
        public boolean ballsLit;
        /** Open-sky light on a white block as Minecraft draws it (lightmap: sky 15, blocks 0), on screen. */
        public final Vector3f openSky = new Vector3f(1);
        /**
         * Minecraft block face shading ({@code ClientLevel.getShade}): top, bottom, north/south, west/east. Already
         * in the world pixel; composite removes it from the block colour under fireball light.
         */
        public final Vector4f faceShade = new Vector4f(1, 0.5f, 0.8f, 0.6f);
        /** This frame's conventional blast smoke grid ({@code client/fx/smoke}); null — no smoke. */
        @Nullable
        public SmokeGrid.Frame smoke;
        /**
         * Smoke billow noise ({@code fx_pall}: {@code SmokeNoise}, {@code SmokeWind}) and fireballs lighting smoke
         * ({@code SmokeBalls}, {@code SmokeBallLight}).
         */
        public final Vector4f smokeNoise = new Vector4f(), smokeWind = new Vector4f(0, 0, 0, 2);
        public final Matrix4f smokeBalls = new Matrix4f().zero(), smokeBallLight = new Matrix4f().zero();
    }

    @Nullable
    static ShaderInstance march, resolve, composite, shadow, footLighting;
    static final int FOOT_LIGHT_AZ = 64, FOOT_LIGHT_R = 16, FOOT_LIGHT_H = 32;
    private static final Frame EMPTY = new Frame();
    private static final Detail DETAIL = new Detail();
    /** Noise failed (background error): no pall until restart. */
    private static boolean failed;

    private static int fullW, fullH, lowW, lowH, factor;
    /** Ray frame and two history frames (alternating previous/current). */
    private static final int[] TEXTURES = {-1, -1, -1}, FBOS = {-1, -1, -1};
    private static int latest;
    private static int table = -1, noise = -1, blue = -1, mushrooms = -1;
    /** Mushroom sun shadow map ({@link MushroomField#SHADOW}² tile per mushroom) and framebuffer; −1 — failed, no shadow. */
    private static int shadowMap = -1, shadowFbo = -1;
    private static int footMap = -1, footFbo = -1;
    /** Frame copy for fireball world light ({@link #copyFrame}) and size; −1 — not needed yet. */
    private static int frameCopy = -1, copyW, copyH;
    /** Slot fire maps (texture, side, owner and fill generation) and this frame's maps (−1 — absent). */
    private static final int[] FIRE = {-1, -1}, FIRE_SIDE = new int[2], FIRE_VERSION = new int[2], FIRE_NOW = {-1, -1};
    private static final Object[] FIRE_SOURCE = new Object[2];
    /** Slot pall terrain maps (texture, side, owner and fill generation) and this frame's maps (−1 — absent). */
    private static final int[] GROUND = {-1, -1}, GROUND_SIDE = new int[2], GROUND_VERSION = new int[2], GROUND_NOW = {-1, -1};
    private static final Object[] GROUND_SOURCE = new Object[2];
    @Nullable
    private static FloatBuffer groundBuffer;
    private static final Vector4f NO_GROUND = new Vector4f();
    @Nullable
    private static FloatBuffer fireBuffer;
    private static final Matrix4f NO_FIRE = new Matrix4f().zero();
    @Nullable
    private static CompletableFuture<byte[][]> textures;
    private static FloatBuffer tableBuffer, mushroomBuffer;
    private static boolean history;
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f(), INVERSE = new Matrix4f(), PREVIOUS = new Matrix4f();
    private static final Vector3f FORWARD = new Vector3f();
    @Nullable
    private static Vec3 previousEye;
    private static long frame;
    private static final int[] VIEWPORT = new int[4];

    // GPU time: timestamps (glQueryCounter) before and after passes in a ring of RING pairs — read after
    // a few frames once ready, without waiting. Not GL_TIME_ELAPSED: OpenGL has one shared query, and Minecraft holds it for
    // the entire frame while F3 GPU load is displayed (TimerQuery) — a nested query would not start
    private static final int RING = 4;
    private static final int[] QUERIES = new int[2 * RING];
    /** Rung generation ({@link Detail#generation}) at pair recording; −1 — pair free. */
    private static final int[] ISSUED = new int[RING];
    private static int ring;
    /** Timestamps: 0 — unchecked, 1 — supported, −1 — unsupported (detail follows setting then). */
    private static int timer;
    /** Client scenario ({@code tools/client_scenario.sh}): frames are not real-time; detail follows setting. */
    private static final boolean SCENARIO = System.getProperty("incoming.scenario") != null;
    private static double gpuSum, gpuMax, lastGpuMean, lastGpuMax;
    private static int gpuFrames, drawn, lastDrawn;

    private PallPass() {}

    /** Pall without detonations: layer quads ignore it. */
    public static Frame empty() {
        return EMPTY;
    }

    /**
     * Pall can be drawn: shaders compiled and noise has not failed (also true before noise is ready: it will arrive). Otherwise —
     * screen veil shows the wall head at the eye ({@code NukeDust.veil}).
     */
    public static boolean available() {
        return !failed && march != null && resolve != null && composite != null;
    }

    /** Noise prepared in background on world entry ({@code IncomingClient}; and first detonation): no pall or mushroom until ready. */
    public static void prepare() {
        if (textures == null) {
            textures = CompletableFuture.supplyAsync(() -> new byte[][]{
                    VolumeNoise.generate(VolumeNoise.SIZE, 0x9A11L), BlueNoise.generate(BlueNoise.SIZE, 0xB1E5L)});
        }
    }

    /** Field table for layer quads ({@code Sampler5} in {@code fx}); −1 — no pall in frame. */
    static int table() {
        return table;
    }

    /** Mushroom table ({@code Sampler11} in {@code fx}): mushrooms of the frame where pall was drawn. */
    static int mushroomMap() {
        return mushrooms;
    }

    /** Frame slot {@code k} fire map ({@code Sampler7}/{@code Sampler8} in {@code fx}); −1 — absent. */
    static int fireMap(int k) {
        return FIRE_NOW[k];
    }

    /** Frame slot {@code k} terrain/water map ({@code Sampler9}/{@code Sampler10} in {@code fx}); −1 — absent. */
    static int groundMap(int k) {
        return GROUND_NOW[k];
    }

    /** Terrain map parameters for slot {@code k} in frame {@code f} ({@code pall.glsl}); zeroes if absent. */
    static Vector4f groundParams(Frame f, int k) {
        return GROUND_NOW[k] < 0 ? NO_GROUND : k == 0 ? f.ground0.params : f.ground1.params;
    }

    /**
     * Frame pall, dust wall and mushrooms in front of a point ({@code pall_front.glsl}) to shader {@code s}: maps on units 5, 9, 10,
     * 11 and frame {@code f} parameters. Pall undrawn ({@code drawn} false) — empty frame (shader skips it), maps use
     * {@code fallback}. Same parameters for layer quads and source probes ({@link SourceProbes}).
     */
    static void bindFront(ShaderInstance s, Frame f, boolean drawn, int fallback) {
        Frame front = drawn ? f : empty();
        int ground0 = drawn ? GROUND_NOW[0] : -1, ground1 = drawn ? GROUND_NOW[1] : -1;
        RenderSystem.setShaderTexture(5, drawn ? table : fallback);
        RenderSystem.setShaderTexture(9, ground0 >= 0 ? ground0 : fallback);
        RenderSystem.setShaderTexture(10, ground1 >= 0 ? ground1 : fallback);
        RenderSystem.setShaderTexture(11, drawn ? mushrooms : fallback);
        s.safeGetUniform("PallAir").set(front.air.x, front.air.y, front.air.z, front.air.w);
        s.safeGetUniform("PallSlot0").set(front.slot0);
        s.safeGetUniform("PallSlot1").set(front.slot1);
        s.safeGetUniform("PallMin").set(front.min.x, front.min.y, front.min.z);
        s.safeGetUniform("PallMax").set(front.max.x, front.max.y, front.max.z);
        s.safeGetUniform("PallAmbient").set(front.ambient.x, front.ambient.y, front.ambient.z, front.ambient.w);
        Vector4f g0 = groundParams(front, 0), g1 = groundParams(front, 1);
        s.safeGetUniform("PallGround0").set(g0.x, g0.y, g0.z, g0.w);
        s.safeGetUniform("PallGround1").set(g1.x, g1.y, g1.z, g1.w);
        s.safeGetUniform("PallWater0").set(front.water0.x, front.water0.y, front.water0.z, front.water0.w);
        s.safeGetUniform("PallWater1").set(front.water1.x, front.water1.y, front.water1.z, front.water1.w);
        s.safeGetUniform("PallWall0").set(front.wall0);
        s.safeGetUniform("PallWall1").set(front.wall1);
        s.safeGetUniform("PallWilson0").set(front.wilson0.x, front.wilson0.y, front.wilson0.z, front.wilson0.w);
        s.safeGetUniform("PallWilson1").set(front.wilson1.x, front.wilson1.y, front.wilson1.z, front.wilson1.w);
        OpenAir.bind(s, drawn);
        SmokePass.bind(s, drawn ? f.smoke : null);
    }

    /**
     * This frame's accumulated pall ({@code Sampler6} in {@code fx}: light and transmission to the world behind each pixel —
     * supplies pall light in front of a layer quad).
     */
    static int frame() {
        return TEXTURES[1 + latest];
    }

    /**
     * Draw frame pall into the current frame (after world, before layer quads); false — failed (no shaders, noise not
     * ready). {@code view} — frame view from {@code eye}, {@code scene} — world distances ({@link SceneDepth}),
     * {@code haze} — air haze and Minecraft fog ({@link FxFrame.Fog#haze}): world shadow fades through them.
     */
    static boolean draw(Frame f, int scene, Matrix4f view, Matrix4f projection, Vec3 eye, Vector4f haze) {
        ShaderInstance m = march, r = resolve, c = composite, sh = shadow, fl = footLighting;
        if (m == null || r == null || c == null || f.quality == Quality.OFF || !upload()) return false;
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getMainRenderTarget().width, h = mc.getMainRenderTarget().height;
        DETAIL.ceiling(f.quality.step());
        if (DETAIL.pin(SCENARIO || FlashbackReplay.exporting())) {
            Step at = LADDER[Math.max(0, DETAIL.level)];
            if (DETAIL.pinned) {
                Incoming.LOG.info("Nuclear dust pall: {}, detail pinned at the setting 1/{} × {} steps", SCENARIO ? "client scenario" : "Flashback video export",
                        at.factor, at.steps);
            } else {
                Incoming.LOG.info("Nuclear dust pall: Flashback video export over, detail follows the GPU budget again from 1/{} × {} steps", at.factor, at.steps);
            }
        }
        if (timer()) poll(System.nanoTime());
        Step step = LADDER[DETAIL.level];
        if (!ensure(w, h, step.factor)) return false;
        boolean open = mc.level != null && OpenAir.update(mc.level, eye);
        tableBuffer.clear();
        tableBuffer.put(f.table).flip();
        GlStateManager._bindTexture(table);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, WIDTH, ROWS, GL11.GL_RGBA, GL11.GL_FLOAT, tableBuffer);
        // mushroom shadow by day if map exists; otherwise no tiles (zero texels): composite skips the map
        boolean shade = sh != null && shadowMap >= 0 && f.sun.w > 0 && f.mushroomCount > 0;
        if (!shade) {
            for (int k = 0; k < MushroomField.MUSHROOMS; k++) {
                int at = (k * MushroomField.WIDTH + MushroomField.SHADOW_RECT) * 4;
                Arrays.fill(f.mushrooms, at, at + 4, 0f);
            }
        }
        // mushroom rows (black-body light below them already present)
        mushroomBuffer.clear();
        mushroomBuffer.put(f.mushrooms, 0, MushroomField.WIDTH * MushroomField.MUSHROOMS * 4).flip();
        GlStateManager._bindTexture(mushrooms);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, MushroomField.WIDTH, MushroomField.MUSHROOMS, GL11.GL_RGBA, GL11.GL_FLOAT, mushroomBuffer);
        FIRE_NOW[0] = fire(0, f.fire0);
        FIRE_NOW[1] = fire(1, f.fire1);
        int fire0 = FIRE_NOW[0] >= 0 ? FIRE_NOW[0] : table, fire1 = FIRE_NOW[1] >= 0 ? FIRE_NOW[1] : table;
        Matrix4f fireSlot0 = FIRE_NOW[0] >= 0 ? f.fire0.params : NO_FIRE, fireSlot1 = FIRE_NOW[1] >= 0 ? f.fire1.params : NO_FIRE;
        GROUND_NOW[0] = ground(0, f.ground0);
        GROUND_NOW[1] = ground(1, f.ground1);
        int ground0 = GROUND_NOW[0], ground1 = GROUND_NOW[1];
        Vector4f groundSlot0 = groundParams(f, 0), groundSlot1 = groundParams(f, 1);
        if (ground0 < 0) ground0 = table;
        if (ground1 < 0) ground1 = table;

        VIEW_PROJECTION.set(projection).mul(view);
        VIEW_PROJECTION.invert(INVERSE);
        view.positiveZ(FORWARD).negate();
        if (previousEye == null || previousEye.distanceToSqr(eye) > JUMP * JUMP) history = false;
        float dx = (float) (previousEye == null ? 0 : eye.x - previousEye.x), dy = (float) (previousEye == null ? 0 : eye.y - previousEye.y),
                dz = (float) (previousEye == null ? 0 : eye.z - previousEye.z);
        frame++;
        int timing = begin();

        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.colorMask(true, true, true, true);

        // conventional blast smoke grid before rays (read by every pass)
        SmokePass.begin();
        if (f.smoke != null) SmokePass.fill(f.smoke);

        // 0. mushroom sun shadow map — frame mushroom tiles stacked vertically
        if (shade) {
            GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, shadowFbo);
            RenderSystem.viewport(0, 0, MushroomField.SHADOW, MushroomField.SHADOW * f.mushroomCount);
            RenderSystem.setShaderTexture(0, mushrooms);
            sh.safeGetUniform("PallSun").set(f.sun.x, f.sun.y, f.sun.z, f.sun.w);
            quad(sh, false);
        }

        boolean litFoot = false;
        if (fl != null && footMap >= 0) {
            for (int k = 0; k < f.mushroomCount; k++) {
                int at = k * MushroomField.WIDTH * 4;
                if (f.mushrooms[at + MushroomField.FOOT * 4 + 2] > 0 && f.mushrooms[at + 11 * 4 + 3] > 0) litFoot = true;
            }
        }
        // base light in a separate small table; main pass only samples it
        // shared source textures and parameters: same pallSource, without a second inlined ray call
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 0 && !litFoot) continue;
            m = pass == 0 ? fl : march;
            if (pass == 0) {
                GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, footFbo);
                RenderSystem.viewport(0, 0, FOOT_LIGHT_AZ + 1, FOOT_LIGHT_H * FOOT_LIGHT_R * f.mushroomCount);
            } else {
                GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, FBOS[0]);
                RenderSystem.viewport(0, 0, lowW, lowH);
            }
            RenderSystem.setShaderTexture(9, pass == 1 && litFoot ? footMap : table);
            m.safeGetUniform("PallFootReady").set(litFoot ? 1 : 0);
            RenderSystem.setShaderTexture(0, scene);
            RenderSystem.setShaderTexture(1, table);
            RenderSystem.setShaderTexture(2, blue);
            RenderSystem.setShaderTexture(3, fire0);
            RenderSystem.setShaderTexture(4, fire1);
            RenderSystem.setShaderTexture(5, ground0);
            RenderSystem.setShaderTexture(6, ground1);
            RenderSystem.setShaderTexture(7, mushrooms);
            // mushroom sun shadow on pall uses the same map as on the world (without it — table: it contains no tiles)
            RenderSystem.setShaderTexture(8, shade ? shadowMap : table);
            m.safeGetUniform("InvViewProj").set(INVERSE);
            m.safeGetUniform("PallForward").set(FORWARD.x, FORWARD.y, FORWARD.z);
            m.safeGetUniform("PallAir").set(f.air.x, f.air.y, f.air.z, f.air.w);
            m.safeGetUniform("PallSlot0").set(f.slot0);
            m.safeGetUniform("PallSlot1").set(f.slot1);
            m.safeGetUniform("PallMin").set(f.min.x, f.min.y, f.min.z);
            m.safeGetUniform("PallMax").set(f.max.x, f.max.y, f.max.z);
            m.safeGetUniform("PallSun").set(f.sun.x, f.sun.y, f.sun.z, f.sun.w);
            m.safeGetUniform("PallSunLight").set(f.sunLight.x, f.sunLight.y, f.sunLight.z);
            m.safeGetUniform("PallSkyLight").set(f.skyLight.x, f.skyLight.y, f.skyLight.z);
            m.safeGetUniform("PallFireLight").set(f.fireLight.x, f.fireLight.y, f.fireLight.z);
            m.safeGetUniform("PallBase").set(f.base);
            m.safeGetUniform("PallGlow0").set(f.glow0.x, f.glow0.y, f.glow0.z, f.glow0.w);
            m.safeGetUniform("PallGlow1").set(f.glow1.x, f.glow1.y, f.glow1.z, f.glow1.w);
            m.safeGetUniform("PallHot0").set(f.hot0.x, f.hot0.y, f.hot0.z, f.hot0.w);
            m.safeGetUniform("PallHot1").set(f.hot1.x, f.hot1.y, f.hot1.z, f.hot1.w);
            m.safeGetUniform("PallGlowOnSmoke").set(f.glowOnSmoke);
            m.safeGetUniform("PallFrame").set((float) (frame % 4096), step.steps, factor, f.eyeDust.x);
            m.safeGetUniform("PallEyeTau").set(f.eyeDust.y, f.eyeDust.z);
            m.safeGetUniform("PallFire0").set(fireSlot0);
            m.safeGetUniform("PallFire1").set(fireSlot1);
            m.safeGetUniform("PallFireMin").set(f.fireMin.x, f.fireMin.y, f.fireMin.z);
            m.safeGetUniform("PallFireMax").set(f.fireMax.x, f.fireMax.y, f.fireMax.z);
            m.safeGetUniform("PallWhirlPos").set(f.whirlPos);
            m.safeGetUniform("PallWhirlShape").set(f.whirlShape);
            m.safeGetUniform("PallWhirlLean").set(f.whirlLean);
            m.safeGetUniform("PallGround0").set(groundSlot0.x, groundSlot0.y, groundSlot0.z, groundSlot0.w);
            m.safeGetUniform("PallGround1").set(groundSlot1.x, groundSlot1.y, groundSlot1.z, groundSlot1.w);
            m.safeGetUniform("PallWater0").set(f.water0.x, f.water0.y, f.water0.z, f.water0.w);
            m.safeGetUniform("PallWater1").set(f.water1.x, f.water1.y, f.water1.z, f.water1.w);
            m.safeGetUniform("PallWall0").set(f.wall0);
            m.safeGetUniform("PallWall1").set(f.wall1);
            m.safeGetUniform("PallWilson0").set(f.wilson0.x, f.wilson0.y, f.wilson0.z, f.wilson0.w);
            m.safeGetUniform("PallWilson1").set(f.wilson1.x, f.wilson1.y, f.wilson1.z, f.wilson1.w);
            m.safeGetUniform("PallFloorFrom").set(f.floorFrom);
            m.safeGetUniform("PallExposure").set(f.exposure);
            m.safeGetUniform("SmokeNoise").set(f.smokeNoise.x, f.smokeNoise.y, f.smokeNoise.z, f.smokeNoise.w);
            m.safeGetUniform("SmokeWind").set(f.smokeWind.x, f.smokeWind.y, f.smokeWind.z, f.smokeWind.w);
            m.safeGetUniform("SmokeBalls").set(f.smokeBalls);
            m.safeGetUniform("SmokeBallLight").set(f.smokeBallLight);
            OpenAir.bind(m, open);
            SmokePass.bind(m, f.smoke);
            quad(m, true);
        }

        // 2. accumulation: from previous history into the other frame
        int next = 1 + (1 - latest);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, FBOS[next]);
        RenderSystem.setShaderTexture(0, TEXTURES[0]);
        RenderSystem.setShaderTexture(1, TEXTURES[1 + latest]);
        RenderSystem.setShaderTexture(2, scene);
        RenderSystem.setShaderTexture(3, mushrooms);
        r.safeGetUniform("InvViewProj").set(INVERSE);
        r.safeGetUniform("PrevViewProj").set(PREVIOUS);
        r.safeGetUniform("PallForward").set(FORWARD.x, FORWARD.y, FORWARD.z);
        r.safeGetUniform("PallReproject").set(dx, dy, dz, history ? 1f : 0f);
        r.safeGetUniform("PallFrame").set(0f, 0f, factor, REPROJECT_MAX);
        SmokePass.bind(r, f.smoke);
        quad(r, false);
        latest = next - 1;

        // 3. composite
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        RenderSystem.viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        // fireball light brightens the world, but blending only attenuates: copy frame, composite writes the entire world pixel
        boolean balls = f.ballsLit && copyFrame();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        RenderSystem.setShaderTexture(0, TEXTURES[1 + latest]);
        RenderSystem.setShaderTexture(1, scene);
        RenderSystem.setShaderTexture(2, table);
        RenderSystem.setShaderTexture(3, fire0);
        RenderSystem.setShaderTexture(4, fire1);
        RenderSystem.setShaderTexture(5, ground0);
        RenderSystem.setShaderTexture(6, ground1);
        RenderSystem.setShaderTexture(7, mushrooms);
        RenderSystem.setShaderTexture(8, shade ? shadowMap : table);
        RenderSystem.setShaderTexture(9, balls ? frameCopy : table);
        c.safeGetUniform("InvViewProj").set(INVERSE);
        c.safeGetUniform("PallForward").set(FORWARD.x, FORWARD.y, FORWARD.z);
        c.safeGetUniform("PallSlot0").set(f.slot0);
        c.safeGetUniform("PallSlot1").set(f.slot1);
        c.safeGetUniform("PallSun").set(f.sun.x, f.sun.y, f.sun.z, f.sun.w);
        c.safeGetUniform("PallSunLight").set(f.sunLight.x, f.sunLight.y, f.sunLight.z);
        c.safeGetUniform("PallSkyLight").set(f.skyLight.x, f.skyLight.y, f.skyLight.z);
        c.safeGetUniform("PallFrame").set(0f, 0f, factor, 0f);
        c.safeGetUniform("PallFire0").set(fireSlot0);
        c.safeGetUniform("PallFire1").set(fireSlot1);
        c.safeGetUniform("PallGround0").set(groundSlot0.x, groundSlot0.y, groundSlot0.z, groundSlot0.w);
        c.safeGetUniform("PallGround1").set(groundSlot1.x, groundSlot1.y, groundSlot1.z, groundSlot1.w);
        c.safeGetUniform("PallWater0").set(f.water0.x, f.water0.y, f.water0.z, f.water0.w);
        c.safeGetUniform("PallWater1").set(f.water1.x, f.water1.y, f.water1.z, f.water1.w);
        c.safeGetUniform("PallWall0").set(f.wall0);
        c.safeGetUniform("PallWall1").set(f.wall1);
        c.safeGetUniform("PallFloorFrom").set(f.floorFrom);
        c.safeGetUniform("Haze").set(haze.x, haze.y, haze.z, haze.w);
        c.safeGetUniform("PallBalls").set(balls ? f.balls : NO_FIRE);
        c.safeGetUniform("PallBallLight").set(f.ballLight);
        c.safeGetUniform("PallOpenSky").set(f.openSky.x, f.openSky.y, f.openSky.z);
        c.safeGetUniform("PallFaceShade").set(f.faceShade.x, f.faceShade.y, f.faceShade.z, f.faceShade.w);
        OpenAir.bind(c, open);
        SmokePass.bind(c, f.smoke);
        // surface flame flickers by 3D noise
        quad(c, true);
        RenderSystem.enableDepthTest();
        end(timing);

        PREVIOUS.set(VIEW_PROJECTION);
        previousEye = eye;
        history = true;
        drawn++;
        return true;
    }

    /** Full-frame quad with custom shader; ray pass also binds 3D noise at its own texture unit. */
    private static void quad(ShaderInstance s, boolean withNoise) {
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        MeshData mesh = b.build();
        if (mesh == null) return;
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(),
                Minecraft.getInstance().getWindow());
        s.apply();
        int unit = GlStateManager._getActiveTexture();
        if (withNoise) {
            // ShaderInstance cannot bind a 3D texture (all its textures are 2D): custom unit and binding
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, noise);
            GlStateManager._activeTexture(unit);
            Uniform.uploadInteger(Uniform.glGetUniformLocation(s.getId(), "PallNoise"), NOISE_UNIT);
            // fireball surface pattern is also 3D, on its own unit
            NukeBallSurface.bind(s);
        }
        BufferUploader.draw(mesh);
        s.clear();
        if (withNoise) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GlStateManager._activeTexture(unit);
            NukeBallSurface.unbind();
        }
    }

    /**
     * Upload slot {@code k} fire map if changed (new detonation or fill generation), with mip levels — source
     * clusters; zeroes outside the map (transparent border). −1 — no storm in this slot.
     */
    private static int fire(int k, Fire f) {
        if (!f.on()) return -1;
        float[] map = f.map;
        if (FIRE[k] < 0) {
            FIRE[k] = GlStateManager._genTexture();
            GlStateManager._bindTexture(FIRE[k]);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
            FIRE_SIDE[k] = 0;
        }
        if (f.source == FIRE_SOURCE[k] && f.version == FIRE_VERSION[k] && f.side == FIRE_SIDE[k]) return FIRE[k];
        int n = f.side * f.side * 4;
        if (map == null || map.length < n) return -1;
        if (fireBuffer == null || fireBuffer.capacity() < n) {
            if (fireBuffer != null) MemoryUtil.memFree(fireBuffer);
            fireBuffer = MemoryUtil.memAllocFloat(n);
        }
        FloatBuffer buf = fireBuffer;
        buf.clear();
        buf.put(map, 0, n).flip();
        GlStateManager._bindTexture(FIRE[k]);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, f.side, f.side, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buf);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        FIRE_SOURCE[k] = f.source;
        FIRE_VERSION[k] = f.version;
        FIRE_SIDE[k] = f.side;
        return FIRE[k];
    }

    /** Upload slot {@code k} pall terrain/water map if changed (new detonation or fill generation); −1 — no map. */
    private static int ground(int k, Ground g) {
        if (!g.on()) return -1;
        float[] map = g.map;
        int n = g.side * g.side * 2;
        if (map == null || map.length < n) return -1;
        if (GROUND[k] < 0) {
            GROUND[k] = GlStateManager._genTexture();
            GlStateManager._bindTexture(GROUND[k]);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
            GROUND_SIDE[k] = 0;
        }
        if (g.source == GROUND_SOURCE[k] && g.version == GROUND_VERSION[k] && g.side == GROUND_SIDE[k]) return GROUND[k];
        if (groundBuffer == null || groundBuffer.capacity() < n) {
            if (groundBuffer != null) MemoryUtil.memFree(groundBuffer);
            groundBuffer = MemoryUtil.memAllocFloat(n);
        }
        FloatBuffer buf = groundBuffer;
        buf.clear();
        buf.put(map, 0, n).flip();
        GlStateManager._bindTexture(GROUND[k]);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG16F, g.side, g.side, 0, GL30.GL_RG, GL11.GL_FLOAT, buf);
        GROUND_SOURCE[k] = g.source;
        GROUND_VERSION[k] = g.version;
        GROUND_SIDE[k] = g.side;
        return GROUND[k];
    }

    /** Background noise to VRAM (once); false — not ready yet. */
    private static boolean upload() {
        if (noise >= 0) return true;
        CompletableFuture<byte[][]> ready = textures;
        if (ready == null) {
            prepare();
            return false;
        }
        if (!ready.isDone()) return false;
        if (ready.isCompletedExceptionally()) {
            if (!failed) {
                failed = true;
                Throwable cause = null;
                try {
                    ready.join();
                } catch (CompletionException | CancellationException ex) {
                    cause = ex.getCause() != null ? ex.getCause() : ex;
                }
                Incoming.LOG.error("Nuclear dust pall: noise textures failed, no dust pall", cause);
            }
            return false;
        }
        byte[][] data = ready.getNow(null);
        if (data == null) return false;
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        ByteBuffer buf = MemoryUtil.memAlloc(Math.max(data[0].length, data[1].length));
        try {
            int n = VolumeNoise.SIZE;
            noise = GlStateManager._genTexture();
            int unit = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, noise);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
            buf.clear();
            buf.put(data[0]).flip();
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGB8, n, n, n, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GlStateManager._activeTexture(unit);

            int b = BlueNoise.SIZE;
            blue = GlStateManager._genTexture();
            GlStateManager._bindTexture(blue);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            buf.clear();
            buf.put(data[1]).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, b, b, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, buf);

            table = GlStateManager._genTexture();
            GlStateManager._bindTexture(table);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, WIDTH, ROWS, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
            tableBuffer = MemoryUtil.memAllocFloat(ROWS * WIDTH * 4);

            // mushroom table: per-texel parameters without filtering (texelFetch), black-body light linear by temperature
            mushrooms = GlStateManager._genTexture();
            GlStateManager._bindTexture(mushrooms);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
            float[] start = new float[MushroomField.WIDTH * MushroomField.ROWS * 4];
            MushroomField.blackBodyRow(start);
            mushroomBuffer = MemoryUtil.memAllocFloat(start.length);
            mushroomBuffer.put(start).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, MushroomField.WIDTH, MushroomField.ROWS, 0, GL11.GL_RGBA, GL11.GL_FLOAT, mushroomBuffer);
            shadowMap();
            footMap();
        } finally {
            MemoryUtil.memFree(buf);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        }
        return true;
    }

    /**
     * Mushroom shadow map: {@link MushroomField#SHADOW}² mushroom tiles stacked vertically, linear between texels (smooth ground
     * shadow), clamped edges; failure — no shadow.
     */
    private static void shadowMap() {
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        shadowMap = GlStateManager._genTexture();
        GlStateManager._bindTexture(shadowMap);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, MushroomField.SHADOW, MushroomField.SHADOW * MushroomField.MUSHROOMS, 0,
                GL11.GL_RGBA, GL11.GL_FLOAT, null);
        shadowFbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, shadowFbo);
        GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, shadowMap, 0);
        boolean ok = GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        if (!ok) {
            Incoming.LOG.error("Mushroom shadow map not created: no mushroom shadow on the ground");
            GlStateManager._glDeleteFramebuffers(shadowFbo);
            GlStateManager._deleteTexture(shadowMap);
            shadowMap = shadowFbo = -1;
        }
    }

    /** 3D lighting table packed as azimuth × (radius, height, mushroom); two bilinear reads interpolate height. */
    private static void footMap() {
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        footMap = GlStateManager._genTexture();
        GlStateManager._bindTexture(footMap);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, FOOT_LIGHT_AZ + 1,
                FOOT_LIGHT_R * FOOT_LIGHT_H * MushroomField.MUSHROOMS, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
        footFbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, footFbo);
        GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, footMap, 0);
        boolean ok = GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        if (!ok) {
            Incoming.LOG.error("Stem foot lighting table not created: using stem lighting at the foot");
            GlStateManager._glDeleteFramebuffers(footFbo);
            GlStateManager._deleteTexture(footMap);
            footMap = footFbo = -1;
        }
    }

    /**
     * Copy the composite target frame (currently bound) before compositing, as a custom full-frame texture from its corner
     * (storage resized with the frame): fireball world light ({@code fx_pall_composite}). false — no copy.
     */
    private static boolean copyFrame() {
        int w = VIEWPORT[0] + VIEWPORT[2], h = VIEWPORT[1] + VIEWPORT[3];
        if (w <= 0 || h <= 0) return false;
        if (frameCopy < 0) {
            frameCopy = GlStateManager._genTexture();
            GlStateManager._bindTexture(frameCopy);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        }
        GlStateManager._bindTexture(frameCopy);
        if (w != copyW || h != copyH) {
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null);
            copyW = w;
            copyH = h;
        }
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        return true;
    }

    /** Reduced-resolution frames matching game frame size and quality (recreated on change). */
    private static boolean ensure(int w, int h, int f) {
        if (TEXTURES[0] >= 0 && w == fullW && h == fullH && f == factor) return true;
        fullW = w;
        fullH = h;
        factor = f;
        lowW = Math.max(1, (w + f - 1) / f);
        lowH = Math.max(1, (h + f - 1) / f);
        history = false;
        // measurements at the previous frame size represent a different cost
        DETAIL.restart();
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean ok = true;
        for (int i = 0; i < TEXTURES.length; i++) {
            if (TEXTURES[i] < 0) TEXTURES[i] = GlStateManager._genTexture();
            if (FBOS[i] < 0) FBOS[i] = GlStateManager.glGenFramebuffers();
            GlStateManager._bindTexture(TEXTURES[i]);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, lowW, lowH, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
            GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, FBOS[i]);
            GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, TEXTURES[i], 0);
            ok &= GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        }
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        if (!ok) {
            Incoming.LOG.error("Nuclear dust pall: render targets {}×{} not created, no dust pall", lowW, lowH);
            fullW = fullH = 0;
            return false;
        }
        return true;
    }

    /** OpenGL timestamps supported (GL 3.3 or ARB_timer_query, nonempty counter); pairs created on first check. */
    private static boolean timer() {
        if (timer == 0) {
            GLCapabilities caps = GL.getCapabilities();
            boolean ok = (caps.OpenGL33 || caps.GL_ARB_timer_query) && GL15.glGetQueryi(GL33.GL_TIMESTAMP, GL15.GL_QUERY_COUNTER_BITS) > 0;
            if (ok) {
                for (int i = 0; i < QUERIES.length; i++) QUERIES[i] = GL15.glGenQueries();
                Arrays.fill(ISSUED, -1);
            } else {
                Incoming.LOG.info("Nuclear dust pall: no GPU timer queries, detail stays as set");
            }
            timer = ok ? 1 : -1;
        }
        return timer > 0;
    }

    /** Read ready timestamp pairs (oldest first, no waiting) into the summary and {@link Detail}. */
    private static void poll(long now) {
        for (int k = 0; k < RING; k++) {
            int i = (ring + k) % RING;
            if (ISSUED[i] < 0 || GL15.glGetQueryObjecti(QUERIES[2 * i + 1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            long start = GL33.glGetQueryObjecti64(QUERIES[2 * i], GL15.GL_QUERY_RESULT), stop = GL33.glGetQueryObjecti64(QUERIES[2 * i + 1], GL15.GL_QUERY_RESULT);
            double ms = Math.max(0, stop - start) / 1e6;
            gpuSum += ms;
            gpuMax = Math.max(gpuMax, ms);
            gpuFrames++;
            int was = DETAIL.level;
            if (DETAIL.sample(ms, ISSUED[i], now)) {
                Step from = LADDER[was], to = LADDER[DETAIL.level];
                // in game — DEBUG: rung changes every few seconds; scenario rung is pinned (draw)
                Incoming.LOG.debug(String.format(Locale.ROOT, "Nuclear dust pall: %.2f ms on the GPU (budget %.1f), detail 1/%d × %d steps → 1/%d × %d",
                        DETAIL.lastMean, Detail.BUDGET_MS, from.factor, from.steps, to.factor, to.steps));
            }
            ISSUED[i] = -1;
        }
    }

    /** Timestamp before passes; −1 — unmeasured frame (unsupported or all pairs still pending). */
    private static int begin() {
        if (!timer() || ISSUED[ring] >= 0) return -1;
        GL33.glQueryCounter(QUERIES[2 * ring], GL33.GL_TIMESTAMP);
        return ring;
    }

    /** Timestamp after passes: pair pending, read after a few frames ({@link #poll}). */
    private static void end(int pair) {
        if (pair < 0) return;
        GL33.glQueryCounter(QUERIES[2 * pair + 1], GL33.GL_TIMESTAMP);
        ISSUED[pair] = DETAIL.generation;
        ring = (pair + 1) % RING;
    }

    /** Once per second (layer tick): timing summary. */
    static void second() {
        lastGpuMean = gpuFrames > 0 ? gpuSum / gpuFrames : 0;
        lastGpuMax = gpuMax;
        lastDrawn = drawn;
        gpuSum = gpuMax = 0;
        gpuFrames = drawn = 0;
    }

    /** Scenario line: pall frames per second, resolution, detail rung, GPU time. */
    public static String describe() {
        if (lastDrawn == 0) return failed ? "failed" : "off";
        Step step = LADDER[Math.max(0, DETAIL.level)];
        Step top = LADDER[Math.max(0, DETAIL.ceiling)];
        String gpu = timer < 0 ? "gpu n/a" : String.format(Locale.ROOT, "gpu %.2f ms (longest %.2f, budget %.1f)", lastGpuMean, lastGpuMax, Detail.BUDGET_MS);
        return String.format(Locale.ROOT, "%d frames, %d×%d, detail 1/%d × %d steps (step %d of %d, setting 1/%d × %d, %s, %d changes), %s",
                lastDrawn, lowW, lowH, step.factor, step.steps, DETAIL.level, LADDER.length - 1, top.factor, top.steps,
                DETAIL.pinned ? "pinned" : "by GPU budget", DETAIL.changes, gpu);
    }

    /**
     * Replay seek, world change, frame without pall: history belongs to a different frame; detail ignores timestamps recorded
     * before this (pall may return elsewhere, minutes later).
     */
    public static void reset() {
        if (previousEye != null) DETAIL.restart();
        history = false;
        previousEye = null;
        // release maps of previous detonation fires (textures remain for the next one)
        FIRE_SOURCE[0] = FIRE_SOURCE[1] = null;
        FIRE_NOW[0] = FIRE_NOW[1] = -1;
        GROUND_SOURCE[0] = GROUND_SOURCE[1] = null;
        GROUND_NOW[0] = GROUND_NOW[1] = -1;
    }
}
