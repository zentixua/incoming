package ua.zentix.incoming.client.fx.layer;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * All effects layer textures on one 1024×8448 sheet with smaller copies (mip levels): smoke and flame billows,
 * spark, flash, ring, fireball frames (256×256) and their motion vectors (128×128), baked billows of smoke, spray,
 * soil ejecta, crushing dust and airbursts ({@link #puff6}, {@link #club}: 16 frames each with three maps),
 * distant disc, halo and billows. One sheet — one texture for the entire sorted layer pass
 * (particles mixed with distant visuals); smaller copies prevent a distant ten-pixel billow from shimmering. Sheet colour
 * is premultiplied by opacity, matching layer blending.
 * <p>
 * Each image retains its own size (billows 64×64): nearby particles use nearest texels, like the Minecraft atlas
 * ({@link FxQuads#nearestTexels}), and a sheet pixel must be an image pixel. Copies average four pixels
 * with premultiplied alpha: edges fade without dark rims. Neither the Minecraft atlas nor its {@code MipmapGenerator}: that clears
 * alpha below 96 out of 255 in mip levels (for foliage), which covers most of a smoke billow — smoke behind a wall
 * a hundred blocks away would vanish entirely. Sheet slots are fixed and multiples of 16 — neighbouring slot mips do not mix;
 * the sheet resizes differently sized images (resource packs) to the slot.
 */
public final class FxAtlas extends SimplePreparableReloadListener<NativeImage[]> {
    public static final FxAtlas INSTANCE = new FxAtlas();

    private static final int CELL = 64, COLS = 16, ROWS = 132;
    /** Sheet size, pixels. */
    static final int WIDTH = COLS * CELL, HEIGHT = ROWS * CELL;
    /**
     * Fireball frames ({@code fx/particle/fireball_00…47}, 256×256): {@value #FIREBALL_BURN} burning, the remainder —
     * cooling into soot. Frames blend along motion vectors ({@code fireballv_00…47}): fire flows instead of
     * appearing doubled; 10 frames over 2–3 s of burning left the silhouette static (review #426).
     */
    public static final int FIREBALL_FRAMES = 48, FIREBALL_BURN = 32;
    /** Previous procedural ball ({@link #ball}) and its light table — 16 frames. */
    public static final int BALL_FRAMES = 16;
    public static final int FIREBALL_SIZE = 4 * CELL;
    private static final int PUFF_SIZE = 2 * CELL, FIREBALL_ROW = 36, FIREBALL_FLOW_ROW = FIREBALL_ROW + 12 * 4;
    public static final int FIREBALL_COLUMNS = 4;
    public static final float FIREBALL_U = (float) FIREBALL_SIZE / WIDTH, FIREBALL_V = (float) FIREBALL_SIZE / HEIGHT,
            FIREBALL_TOP = (float) (FIREBALL_ROW * CELL) / HEIGHT;
    /**
     * Motion vectors from each fireball frame to the next: 128×128 map ({@value #FIREBALL_FLOW_COLUMNS} per row from
     * {@link #FIREBALL_FLOW_TOP}), r and g — displacement ±{@link #FIREBALL_FLOW} pixels of a 256 frame (written by
     * {@code tools/bake_fx/pack.py}).
     */
    public static final int FIREBALL_FLOW_COLUMNS = 8, FIREBALL_FLOW_SIZE = 2 * CELL;
    public static final float FIREBALL_FLOW = 16, FIREBALL_FLOW_U = (float) FIREBALL_FLOW_SIZE / WIDTH,
            FIREBALL_FLOW_V = (float) FIREBALL_FLOW_SIZE / HEIGHT, FIREBALL_FLOW_TOP = (float) (FIREBALL_FLOW_ROW * CELL) / HEIGHT;
    /**
     * Mip levels: billow 64 → 4 pixels (all slots are multiples of 16 — neighbouring slots do not mix). Sampling near a slot edge
     * insets by half a texel of the sampled mip (`frameInset` in {@code fx.fsh}, with {@code FRAME_LEVELS} there too).
     */
    public static final int MIPS = 4;

    /** Texture slot on the sheet (sheet fractions). */
    public record Sprite(float u0, float v0, float u1, float v1) {
        /** Vertical midpoint (ribbon — halo middle row). */
        public float vMid() {
            return (v0 + v1) * 0.5f;
        }
    }

    private record Slot(ResourceLocation texture, int x, int y, int w, int h) {}

    private static final List<Slot> SLOTS = new ArrayList<>();
    private static final Sprite[] SMOKE = new Sprite[16], FIRE = new Sprite[8], PUFFS = new Sprite[8], FIREBALL = new Sprite[FIREBALL_FRAMES],
            FIREBALL_MIRRORED = new Sprite[FIREBALL_FRAMES];
    private static final Sprite RING, FLASH, GLOW, DISC, SPARK, FLAKE, PAPER;
    /**
     * Baked smoke billow ({@code tools/bake_fx}: Blender Mantaflow gas), {@value #PUFF6_FRAMES} expansion frames, each with
     * three 128×128 maps stacked vertically at sheet step {@link #PUFF6_STEP}: A — sunlight from right, top, back (r, g, b) and
     * opacity; B — left, bottom, front and the same opacity; V — displacement to the next frame (motion vectors,
     * r and g; ±{@link #PUFF6_FLOW} frame pixels). Frame i at row i / 8, column i % 8, rows start at {@link #PUFF6_TOP}.
     */
    public static final int PUFF6_FRAMES = 16;
    /** Frames per row, sheet frame start (height fraction), map step (height fraction), frame size (sheet fractions). */
    public static final int PUFF6_COLUMNS = 8;
    public static final float PUFF6_TOP = (float) (8 * CELL) / HEIGHT, PUFF6_STEP = (float) (4 * CELL) / HEIGHT,
            PUFF6_U = (float) (2 * CELL) / WIDTH, PUFF6_V = (float) (2 * CELL) / HEIGHT;
    /** Maximum V-map displacement, pixels of a 128 frame (as written by {@code tools/bake_fx/pack.py}). */
    public static final float PUFF6_FLOW = 32;
    private static final Sprite[] PUFF6 = new Sprite[PUFF6_FRAMES], PUFF6_MIRRORED = new Sprite[PUFF6_FRAMES];
    /**
     * Previous procedural fireball ({@code fx/particle/ball_00…15}, {@code tools/gen_particles.py}: grows and cools in
     * its frames by life fraction) — used by fireball particles ({@code Fx.fireball}: anti-aircraft missile burst).
     */
    private static final Sprite[] BALL = new Sprite[BALL_FRAMES];
    private static final ResourceLocation BALL_LIGHT = Incoming.id("textures/fx/particle/ball.json");
    private static volatile float[] ballShown = linear(1, 1);
    private static final ResourceLocation FIREBALL_LIGHT = Incoming.id("textures/fx/particle/fireball.json"),
            PUFF6_DATA = Incoming.id("textures/fx/particle/puff6.json");
    /** Fireball frames: screen fire ({@link #fireLightAt}), time in burn fractions and temperature; fallbacks until loaded. */
    private static volatile Fireball fireball = Fireball.FALLBACK;
    /** Billow growth per frame (matter edge size relative to the first); fallback until loaded. */
    private static volatile float[] puffGrowth = linear(1, 3);
    /**
     * Billow map light ({@code puff6.json}): maximum motion-vector displacement (frame fraction), dense-region light
     * from the front and six-side mean (linear) — shader normalizes maps to these.
     */
    private static volatile float[] puffLight = {PUFF6_FLOW / 128f, 0.79f, 0.5f};

    /**
     * Fireball frames ({@code tools/bake_fx/pack.py} → {@code fireball.json}): screen frame fire (white fraction),
     * frame time in simulation burn fractions, flame colour temperature, K (0 when extinguished), frame half-size in
     * fireball radii (frame holds all matter: cooling soot is not clipped to a circle).
     */
    record Fireball(float[] fire, float[] burn, float[] kelvin, float[] half) {
        static final Fireball FALLBACK = new Fireball(linear(1, 1, FIREBALL_FRAMES), linear(0, 2, FIREBALL_FRAMES),
                linear(1500, 600, FIREBALL_FRAMES), linear(1.3f, 1.3f, FIREBALL_FRAMES));
    }

    static {
        for (int i = 0; i < SMOKE.length; i++) SMOKE[i] = slot(String.format(Locale.ROOT, "fx/particle/smoke_%02d", i), i * CELL, 0, CELL, CELL);
        for (int i = 0; i < FIRE.length; i++) FIRE[i] = slot(String.format(Locale.ROOT, "fx/particle/fire_%02d", i), i * CELL, CELL, CELL, CELL);
        int x = FIRE.length * CELL;
        FLASH = slot("fx/particle/flash", x, CELL, CELL, CELL);
        GLOW = slot("far/glow", x + CELL, CELL, CELL, CELL);
        DISC = slot("far/disc", x + 2 * CELL, CELL, 32, 32);
        SPARK = slot("fx/particle/spark", x + 2 * CELL + 32, CELL, 16, 16);
        // ash and paper scrap (nuclear pall)
        FLAKE = slot("fx/particle/flake", x + 3 * CELL, CELL, 16, 16);
        PAPER = slot("fx/particle/paper", x + 3 * CELL + 16, CELL, 16, 16);
        // distant smoke billows: 4×2 frames in one image
        Sprite puffs = slot("nuke/puffs", 0, 2 * CELL, 4 * CELL, 2 * CELL);
        float du = (puffs.u1 - puffs.u0) / 4, dv = (puffs.v1 - puffs.v0) / 2;
        for (int i = 0; i < PUFFS.length; i++) {
            float u = puffs.u0 + i % 4 * du, v = puffs.v0 + i / 4 * dv;
            PUFFS[i] = new Sprite(u, v, u + du, v + dv);
        }
        RING = slot("fx/particle/ring", 4 * CELL, 2 * CELL, 2 * CELL, 2 * CELL);
        // fireball: 12 rows of four 256² frames (4 cells high each) below the other maps, followed by their 128² motion
        // vectors, 8 per row
        for (int i = 0; i < FIREBALL_FRAMES; i++) {
            FIREBALL[i] = slot(String.format(Locale.ROOT, "fx/particle/fireball_%02d", i), i % FIREBALL_COLUMNS * FIREBALL_SIZE,
                    FIREBALL_ROW * CELL + i / FIREBALL_COLUMNS * FIREBALL_SIZE, FIREBALL_SIZE, FIREBALL_SIZE);
            slot(String.format(Locale.ROOT, "fx/particle/fireballv_%02d", i), i % FIREBALL_FLOW_COLUMNS * FIREBALL_FLOW_SIZE,
                    FIREBALL_FLOW_ROW * CELL + i / FIREBALL_FLOW_COLUMNS * FIREBALL_FLOW_SIZE, FIREBALL_FLOW_SIZE, FIREBALL_FLOW_SIZE);
            Sprite s = FIREBALL[i];
            FIREBALL_MIRRORED[i] = new Sprite(s.u1, s.v0, s.u0, s.v1);
        }
    }

    static {
        for (int i = 0; i < PUFF6_FRAMES; i++) {
            int x = i % PUFF6_COLUMNS * PUFF_SIZE, y = 8 * CELL + i / PUFF6_COLUMNS * PUFF_SIZE;
            PUFF6[i] = slot(String.format(Locale.ROOT, "fx/particle/puff6a_%02d", i), x, y, PUFF_SIZE, PUFF_SIZE);
            PUFF6_MIRRORED[i] = new Sprite(PUFF6[i].u1, PUFF6[i].v0, PUFF6[i].u0, PUFF6[i].v1);
            slot(String.format(Locale.ROOT, "fx/particle/puff6b_%02d", i), x, y + 4 * CELL, PUFF_SIZE, PUFF_SIZE);
            slot(String.format(Locale.ROOT, "fx/particle/puff6v_%02d", i), x, y + 8 * CELL, PUFF_SIZE, PUFF_SIZE);
        }
    }

    /**
     * Baked billow frame i (map A, {@link #PUFF6_FRAMES}); {@code mirror} — horizontal reflection: right and left
     * maps swap in the shader ({@code fx.fsh} — by the sign of the u derivative).
     */
    public static Sprite puff6(int i, boolean mirror) {
        return (mirror ? PUFF6_MIRRORED : PUFF6)[Math.clamp(i, 0, PUFF6_FRAMES - 1)];
    }

    /**
     * Baked spray billow ({@code tools/bake_fx}: heavy droplet gas thrown upward falls back; albedo 0.99,
     * droplet asymmetry 0.85) — same 16 frames with three maps and same sheet steps as smoke, from row {@link #SPRAY6_ROW}:
     * six-way lighting shader ({@code fx.fsh}: {@code sixFrames}) reads them without its own numbers; maps use smoke normalization
     * ({@code pack.py}: smoke “diffuse”).
     */
    public static final int SPRAY6_ROW = 24;
    private static final Sprite[] SPRAY6 = new Sprite[PUFF6_FRAMES], SPRAY6_MIRRORED = new Sprite[PUFF6_FRAMES];
    private static final ResourceLocation SPRAY6_DATA = Incoming.id("textures/fx/particle/spray6.json");
    private static volatile float[] sprayGrowth = linear(1, 3);

    static {
        for (int i = 0; i < PUFF6_FRAMES; i++) {
            int x = i % PUFF6_COLUMNS * PUFF_SIZE, y = SPRAY6_ROW * CELL + i / PUFF6_COLUMNS * PUFF_SIZE;
            SPRAY6[i] = slot(String.format(Locale.ROOT, "fx/particle/spray6a_%02d", i), x, y, PUFF_SIZE, PUFF_SIZE);
            SPRAY6_MIRRORED[i] = new Sprite(SPRAY6[i].u1, SPRAY6[i].v0, SPRAY6[i].u0, SPRAY6[i].v1);
            slot(String.format(Locale.ROOT, "fx/particle/spray6b_%02d", i), x, y + 4 * CELL, PUFF_SIZE, PUFF_SIZE);
            slot(String.format(Locale.ROOT, "fx/particle/spray6v_%02d", i), x, y + 8 * CELL, PUFF_SIZE, PUFF_SIZE);
        }
    }

    /** Baked spray billow frame i ({@link #puff6}). */
    public static Sprite spray6(int i, boolean mirror) {
        return (mirror ? SPRAY6_MIRRORED : SPRAY6)[Math.clamp(i, 0, PUFF6_FRAMES - 1)];
    }

    /**
     * Billows from other ready-made simulations ({@code tools/bake_fx/bake.py clubs-vdb}, {@code SOURCES.md}) — same 16 frames with three
     * maps and same sheet steps as smoke; each set spans 12 cell rows from {@link Clubs#row}: the six-way lighting
     * shader reads them without its own numbers (frame slots from {@link #PUFF6_TOP} at frame steps); maps use smoke normalization
     * ({@code pack.py}: smoke “diffuse”). Frame follows its phenomenon's life fraction ({@code <name>6.json} “life”: simulation life fraction at
     * the frame), no later than the last turbulent frame (“detail”).
     */
    static final class Clubs {
        final String prefix;
        final int row;
        final Sprite[] frames = new Sprite[PUFF6_FRAMES], mirrored = new Sprite[PUFF6_FRAMES];
        final ResourceLocation data;
        volatile float[] life = linear(0, 1, PUFF6_FRAMES), growth = linear(1, 3, PUFF6_FRAMES);
        volatile int last = PUFF6_FRAMES - 1;

        Clubs(String prefix, int row) {
            this.prefix = prefix;
            this.row = row;
            this.data = Incoming.id("textures/fx/particle/" + prefix + "6.json");
            for (int i = 0; i < PUFF6_FRAMES; i++) {
                int x = i % PUFF6_COLUMNS * PUFF_SIZE, y = row * CELL + i / PUFF6_COLUMNS * PUFF_SIZE;
                frames[i] = slot(String.format(Locale.ROOT, "fx/particle/%s6a_%02d", prefix, i), x, y, PUFF_SIZE, PUFF_SIZE);
                mirrored[i] = new Sprite(frames[i].u1, frames[i].v0, frames[i].u0, frames[i].v1);
                slot(String.format(Locale.ROOT, "fx/particle/%s6b_%02d", prefix, i), x, y + 4 * CELL, PUFF_SIZE, PUFF_SIZE);
                slot(String.format(Locale.ROOT, "fx/particle/%s6v_%02d", prefix, i), x, y + 8 * CELL, PUFF_SIZE, PUFF_SIZE);
            }
        }
    }

    /** Soil ejecta (Grenade Dust Impact from above), crushing dust (Building Implosion from above), airburst (Mid-Air from the side). */
    static final Clubs EJECT = new Clubs("eject", 96), CRUSH = new Clubs("crush", 108), AIR = new Clubs("air", 120);

    private static Clubs clubs(ua.zentix.incoming.client.fx.smoke.SmokeGrid.Look look) {
        return switch (look) {
            case EJECTA -> EJECT;
            case CRUSH -> CRUSH;
            case AIRBURST -> AIR;
            case SMOKE -> null;
        };
    }

    /** Baked billow frame i for {@code look} ({@link #puff6} for smoke). */
    public static Sprite club(ua.zentix.incoming.client.fx.smoke.SmokeGrid.Look look, int i, boolean mirror) {
        Clubs c = clubs(look);
        if (c == null) return puff6(i, mirror);
        return (mirror ? c.mirrored : c.frames)[Math.clamp(i, 0, PUFF6_FRAMES - 1)];
    }

    /**
     * Fractional frame of billow kind {@code look} at life fraction {@code life} (0..1) of its phenomenon — by simulation life fraction at
     * frames ({@code <name>6.json} “life”). All frames: for a phenomenon with its own lifespan (ejecta curtain: clods around a dust billow,
     * then dust), changing fine structure is the phenomenon itself, rather than dispersal of a finite simulated puff, as for a growing
     * cloud ({@link #growthFrame}).
     */
    public static double lifeFrame(ua.zentix.incoming.client.fx.smoke.SmokeGrid.Look look, double life) {
        Clubs c = clubs(look);
        if (c == null) return 0;
        return at(c.life, Math.clamp(life, 0, 1), 0, PUFF6_FRAMES - 1);
    }

    /**
     * Fractional frame of billow kind {@code look} whose matter edge grew by this factor from the first frame ({@code <name>6.json}
     * “growth”) — a growing cloud, like smoke ({@link #puffFrame}); no later than the last turbulent frame.
     */
    public static double growthFrame(ua.zentix.incoming.client.fx.smoke.SmokeGrid.Look look, double growth) {
        Clubs c = clubs(look);
        if (c == null) return puffFrame(growth);
        return Math.min(at(c.growth, growth, 0, PUFF6_FRAMES - 1), c.last);
    }

    /** Frame simulation life fractions from a set's {@code <name>6.json}: {@code "life"} (16 nondecreasing numbers). */
    static float[] parseLife(Reader in) {
        return numbers(JsonParser.parseReader(in).getAsJsonObject().getAsJsonArray("life"), PUFF6_FRAMES);
    }

    /** Fractional spray billow frame whose matter edge grew by this factor from the first frame — up to the last turbulent one. */
    public static double sprayFrame(double growth) {
        return Math.min(at(sprayGrowth, growth, 0, PUFF6_FRAMES - 1), sprayLast);
    }

    /** Last turbulent spray frame ({@link #turbulentLast}). */
    private static volatile int sprayLast = PUFF6_FRAMES - 1;

    static {
        for (int i = 0; i < BALL_FRAMES; i++) {
            BALL[i] = slot(String.format(Locale.ROOT, "fx/particle/ball_%02d", i), i % 8 * PUFF_SIZE, 20 * CELL + i / 8 * PUFF_SIZE,
                    PUFF_SIZE, PUFF_SIZE);
        }
    }

    /** Previous ball frame ({@link #BALL}) at life fraction f (0..1): ball grows in the frame from 0.3 to 0.7 half-size. */
    public static Sprite ball(float f) {
        return BALL[Math.clamp((int) (f * BALL_FRAMES), 0, BALL_FRAMES - 1)];
    }

    /** Previous ball screen fire at life fraction f ({@code ball.json}). */
    public static float ballLight(float f) {
        return ballShown[Math.clamp((int) (f * BALL_FRAMES), 0, BALL_FRAMES - 1)];
    }

    private int id = -1;

    private FxAtlas() {}

    private static Sprite slot(String path, int x, int y, int w, int h) {
        SLOTS.add(new Slot(Incoming.id("textures/" + path + ".png"), x, y, w, h));
        return new Sprite((float) x / WIDTH, (float) y / HEIGHT, (float) (x + w) / WIDTH, (float) (y + h) / HEIGHT);
    }

    /** Smoke billow: 4 billows × 4 dispersal stages. */
    public static Sprite smoke(int i) {
        return SMOKE[i];
    }

    public static Sprite fire(int i) {
        return FIRE[i];
    }

    public static Sprite spark() {
        return SPARK;
    }

    /** Ash flakes. */
    public static Sprite flake() {
        return FLAKE;
    }

    /** Charred paper scrap. */
    public static Sprite paper() {
        return PAPER;
    }

    public static Sprite flash() {
        return FLASH;
    }

    public static Sprite ring() {
        return RING;
    }

    /**
     * Fractional fireball frame by model fireball state: while burning (burn — burn time fraction below 1), frame at the
     * same simulation burn time; while cooling — frame at the same cooling fraction: model ball temperature from its burn
     * end ({@code endKelvin}) to the Draper point matches frame flame cooling from simulation burn end to that point. Direct
     * temperature matching would freeze the frame at burn end until the model ball (1484–1500 K) cooled to simulation flame at burn end
     * (~1360 K): ~0.35 s freeze for missile and bomb.
     */
    public static double fireballFrame(double burn, double kelvin, double endKelvin) {
        return fireballFrameOf(fireball, burn, kelvin, endKelvin);
    }

    /** Same with custom frames (unit test). */
    static double fireballFrameOf(Fireball f, double burn, double kelvin, double endKelvin) {
        int n = FIREBALL_FRAMES;
        // burn end — fractional frame at the simulation burn end, and its flame temperature: cooling moves from
        // that frame toward cooling frames at the same cooling fraction, without a jump
        double end = at(f.burn, 1, 0, n - 1);
        if (burn < 1) return Math.min(at(f.burn, burn, 0, n - 1), end);
        int from = (int) Math.floor(end);
        double k0 = at(f.kelvin, end), draper = ua.zentix.incoming.client.fx.smoke.CloudModel.DRAPER_K;
        if (endKelvin > draper && k0 > draper) kelvin = draper + (kelvin - draper) * (k0 - draper) / (endKelvin - draper);
        if (kelvin >= k0) return end;
        int next = from + 1;
        if (next > n - 1) return n - 1;
        while (next < n - 1 && f.kelvin[next] > kelvin) next++;
        if (next == from + 1) return end + (next - end) * (k0 - kelvin) / Math.max(k0 - f.kelvin[next], 1e-6);
        return Math.min(n - 1, next - 1 + (f.kelvin[next - 1] - kelvin) / Math.max(f.kelvin[next - 1] - f.kelvin[next], 1e-6));
    }

    /** Lower blended frame: top always up; variation only by horizontal reflection. */
    public static Sprite fireballAt(double frame, boolean mirror) {
        return (mirror ? FIREBALL_MIRRORED : FIREBALL)[Math.clamp((int) frame, 0, FIREBALL_FRAMES - 1)];
    }

    /** Next-frame weight; last frame never blends with the start or a neighbouring sheet slot. */
    public static float fireballMix(double frame) {
        double f = Math.clamp(frame, 0, FIREBALL_FRAMES - 1);
        return (float) (f - Math.floor(f));
    }

    /** Screen fireball fire at {@code frame} ({@link #fireLightAt}), smooth between frames. */
    public static float fireLightAt(double frame) {
        return (float) at(fireball.fire, frame);
    }

    /** Fractional {@code frame} half-size in fireball radii: holds all matter without an edge circle. */
    public static double fireballHalf(double frame) {
        return at(fireball.half, frame);
    }

    /**
     * Fractional baked billow frame whose matter edge grew by this factor from the first — no later than the last
     * turbulent frame ({@link #turbulentLast}): after that the cloud grows but the frame stays fixed.
     */
    public static double puffFrame(double growth) {
        return Math.min(at(puffGrowth, growth, 0, PUFF6_FRAMES - 1), puffLast);
    }

    /**
     * Fine frame structure (puff6.json “detail”, {@code tools/bake_fx/pack.py}) in a turbulent frame is at least this fraction
     * of its maximum (est.) (later frames look like a smooth blurry ball).
     */
    static final double TURBULENT = 2.0 / 3;

    /**
     * Last turbulent frame: the simulation is a finite puff that disperses and loses fine structure toward its end (puff6 frame
     * 15 has 0.42 of maximum), while a real growing cloud remains turbulent — the thermal is self-similar, billows
     * grow with it (Scorer 1957; Morton, Taylor, Turner 1956). Clouds at 150–300 m in 10–30 s used late frames by
     * growth — smooth blurry balls without billows (look gate RV2). Last frame after peak structure that retains at least
     * {@link #TURBULENT}; no data — all frames.
     */
    static int turbulentLast(float[] detail) {
        if (detail == null || detail.length == 0) return PUFF6_FRAMES - 1;
        int peak = 0;
        for (int i = 1; i < detail.length; i++) if (detail[i] > detail[peak]) peak = i;
        for (int i = peak + 1; i < detail.length; i++) if (detail[i] < TURBULENT * detail[peak]) return i - 1;
        return detail.length - 1;
    }

    /** Last billow frame used by {@link #puffFrame}. */
    private static volatile int puffLast = PUFF6_FRAMES - 1;

    /** Table a value at fractional index x (edge value outside bounds). */
    private static double at(float[] a, double x) {
        double c = Math.max(0, Math.min(a.length - 1, x));
        int i = Math.min(a.length - 2, (int) c);
        return a[i] + (a[i + 1] - a[i]) * (c - i);
    }

    /** Fractional index in increasing table a where it equals v (lo or hi outside bounds). */
    private static double at(float[] a, double v, int lo, int hi) {
        if (v <= a[0]) return lo;
        for (int i = 0; i < a.length - 1; i++) {
            if (v <= a[i + 1]) return i + (v - a[i]) / Math.max(a[i + 1] - a[i], 1e-9);
        }
        return hi;
    }

    /**
     * Fireball frames from {@code fireball.json}: {@code "fire"}, {@code "burn"}, {@code "kelvin"}, {@code "half"}, each with
     * {@value #FIREBALL_FRAMES} numbers; motion-vector bound {@code "flow"} matches the shader expectation.
     */
    static Fireball parseFire(Reader in) {
        var o = JsonParser.parseReader(in).getAsJsonObject();
        if (!o.has("flow") || o.get("flow").getAsFloat() != FIREBALL_FLOW) throw new IllegalArgumentException("fireball flow " + o.get("flow"));
        return new Fireball(numbers(o.getAsJsonArray("fire"), FIREBALL_FRAMES), numbers(o.getAsJsonArray("burn"), FIREBALL_FRAMES),
                numbers(o.getAsJsonArray("kelvin"), FIREBALL_FRAMES), numbers(o.getAsJsonArray("half"), FIREBALL_FRAMES));
    }

    /** Fine frame structure from {@code puff6.json}: {@code "detail"}; null if absent. */
    static float[] parseDetail(Reader in) {
        var o = JsonParser.parseReader(in).getAsJsonObject();
        return o.has("detail") ? numbers(o.getAsJsonArray("detail"), PUFF6_FRAMES) : null;
    }

    /** Billow growth from {@code puff6.json}: {@code {"growth": [16 numbers]}}. */
    static float[] parseGrowth(Reader in) {
        return numbers(JsonParser.parseReader(in).getAsJsonObject().getAsJsonArray("growth"), PUFF6_FRAMES);
    }

    /** Billow map light from {@code puff6.json}: {@code "flow"} (pixels of frame {@code "tile"}), {@code "front"}, {@code "diffuse"}. */
    static float[] parseLight(Reader in) {
        var o = JsonParser.parseReader(in).getAsJsonObject();
        return new float[]{o.get("flow").getAsFloat() / o.get("tile").getAsFloat(), o.get("front").getAsFloat(), o.get("diffuse").getAsFloat()};
    }

    /** Billow map light: motion-vector displacement (frame fraction), dense front light and side mean (linear). */
    public static float[] puff6Light() {
        return puffLight;
    }

    private static float[] numbers(JsonArray a, int n) {
        if (a == null || a.size() != n) throw new IllegalArgumentException("frames " + (a == null ? 0 : a.size()) + ", expected " + n);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    /** 16 numbers from a to b. */
    private static float[] linear(float a, float b) {
        return linear(a, b, BALL_FRAMES);
    }

    private static float[] linear(float a, float b, int n) {
        float[] out = new float[n];
        for (int i = 0; i < out.length; i++) out[i] = a + (b - a) * i / (out.length - 1);
        return out;
    }

    /** Distant billow frame (4×2, {@code nuke/puffs}). */
    public static Sprite puff(int i) {
        return PUFFS[i];
    }

    /** Solid disc with a soft edge (body, fireball, distant flash core). */
    public static Sprite disc() {
        return DISC;
    }

    /** Flat white centre of {@code far/disc} (cloud veil around the eye): two pixels around its centre. */
    public static Sprite white() {
        return WHITE;
    }

    private static final Sprite WHITE = new Sprite((DISC.u0 + DISC.u1) / 2 - 1f / WIDTH, (DISC.v0 + DISC.v1) / 2 - 1f / HEIGHT,
            (DISC.u0 + DISC.u1) / 2 + 1f / WIDTH, (DISC.v0 + DISC.v1) / 2 + 1f / HEIGHT);

    /** Gaussian halo without a core (glare, veil, glow; ribbon — trail). */
    public static Sprite glow() {
        return GLOW;
    }

    /** OpenGL sheet texture; −1 — not loaded yet. */
    public int id() {
        return id;
    }

    /** Background thread: images into slots with their own mip levels, premultiplied alpha. */
    @Override
    protected NativeImage[] prepare(ResourceManager resources, ProfilerFiller profiler) {
        int[][] sheets = new int[MIPS + 1][];
        for (int l = 0; l <= MIPS; l++) sheets[l] = new int[(WIDTH >> l) * (HEIGHT >> l)];
        for (Slot s : SLOTS) place(resources, s, sheets);
        fireball = read(resources, FIREBALL_LIGHT, FxAtlas::parseFire, Fireball.FALLBACK);
        puffGrowth = read(resources, PUFF6_DATA, FxAtlas::parseGrowth, linear(1, 3));
        puffLast = turbulentLast(read(resources, PUFF6_DATA, FxAtlas::parseDetail, null));
        sprayGrowth = read(resources, SPRAY6_DATA, FxAtlas::parseGrowth, linear(1, 3));
        sprayLast = turbulentLast(read(resources, SPRAY6_DATA, FxAtlas::parseDetail, null));
        for (Clubs c : new Clubs[]{EJECT, CRUSH, AIR}) {
            c.life = read(resources, c.data, FxAtlas::parseLife, linear(0, 1, PUFF6_FRAMES));
            c.growth = read(resources, c.data, FxAtlas::parseGrowth, linear(1, 3, PUFF6_FRAMES));
            c.last = turbulentLast(read(resources, c.data, FxAtlas::parseDetail, null));
        }
        ballShown = read(resources, BALL_LIGHT, r -> numbers(JsonParser.parseReader(r).getAsJsonObject().getAsJsonArray("fire"), BALL_FRAMES),
                linear(1, 1));
        puffLight = read(resources, PUFF6_DATA, FxAtlas::parseLight, new float[]{PUFF6_FLOW / 128f, 0.79f, 0.5f});
        emission = emissionTable(sheets[0]);
        NativeImage[] levels = new NativeImage[MIPS + 1];
        for (int l = 0; l <= MIPS; l++) levels[l] = image(sheets[l], WIDTH >> l, HEIGHT >> l);
        return levels;
    }

    /**
     * Sheet slot means for small-emitter bloom ({@code FxQuads}: a spot replacing a quad smaller than a bloom pass pixel
     * carries its energy): sums over {@value #CELL4}×{@value #CELL4}-pixel cells accumulated along rows; {@value #MEANS}
     * numbers per cell — particles and fire frames (premultiplied colour) a·lin(rgb/a), others lin(rgb), and a;
     * lin — power 2.2, as in {@code fx.fsh}.
     */
    private static volatile float[] emission;
    static final int CELL4 = 4, MEANS = 7;
    private static final int TW = WIDTH / CELL4, TH = HEIGHT / CELL4;

    private static float[] emissionTable(int[] base) {
        float[] lin = new float[256];
        for (int i = 0; i < 256; i++) lin[i] = (float) Math.pow(i / 255.0, 2.2);
        float[] cells = new float[TW * TH * MEANS];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int p = base[y * WIDTH + x], a = p >>> 24 & 255;
                if (a == 0) continue;
                int r = p & 255, g = p >> 8 & 255, b = p >> 16 & 255, c = ((y / CELL4) * TW + x / CELL4) * MEANS;
                float af = a / 255f;
                cells[c] += af * lin[Math.min(255, Math.round(255f * r / a))];
                cells[c + 1] += af * lin[Math.min(255, Math.round(255f * g / a))];
                cells[c + 2] += af * lin[Math.min(255, Math.round(255f * b / a))];
                cells[c + 3] += lin[r];
                cells[c + 4] += lin[g];
                cells[c + 5] += lin[b];
                cells[c + 6] += af;
            }
        }
        // cumulative cell rows: row[(y·(TW + 1) + x)·MEANS + k] is the sum of cells in row y left of x (across the entire sheet,
        // float would lose precision: sums reach millions versus single-digit cell values)
        float[] row = new float[TH * (TW + 1) * MEANS];
        for (int y = 0; y < TH; y++) {
            for (int x = 0; x < TW; x++) {
                int at = (y * (TW + 1) + x + 1) * MEANS;
                for (int k = 0; k < MEANS; k++) row[at + k] = row[at - MEANS + k] + cells[(y * TW + x) * MEANS + k];
            }
        }
        return row;
    }

    /** Bloom means from the prepared sheet base ({@link #emissionTable}) — no resource loading (checks). */
    static void emissionFrom(int[] base) {
        emission = emissionTable(base);
    }

    /**
     * Means over sheet slot (u0, v0)–(u1, v1) ({@link #emissionTable}): 4-pixel cells, at least one (ribbon row —
     * cell around it); out receives {@value #MEANS} numbers per slot pixel. Sheet not loaded yet — false.
     */
    public static boolean means(float u0, float v0, float u1, float v1, float[] out) {
        float[] sat = emission;
        if (sat == null) return false;
        int x0 = Math.clamp((int) Math.floor(Math.min(u0, u1) * TW + 1e-3), 0, TW - 1), y0 = Math.clamp((int) Math.floor(Math.min(v0, v1) * TH + 1e-3), 0, TH - 1);
        int x1 = Math.clamp((int) Math.ceil(Math.max(u0, u1) * TW - 1e-3), x0 + 1, TW), y1 = Math.clamp((int) Math.ceil(Math.max(v0, v1) * TH - 1e-3), y0 + 1, TH);
        float area = (x1 - x0) * (y1 - y0) * (float) (CELL4 * CELL4);
        for (int k = 0; k < MEANS; k++) out[k] = 0;
        for (int y = y0; y < y1; y++) {
            for (int k = 0; k < MEANS; k++) out[k] += sat[(y * (TW + 1) + x1) * MEANS + k] - sat[(y * (TW + 1) + x0) * MEANS + k];
        }
        for (int k = 0; k < MEANS; k++) out[k] /= area;
        return true;
    }

    /** Render thread: upload to VRAM (same texture on resource reload). */
    @Override
    protected void apply(NativeImage[] levels, ResourceManager resources, ProfilerFiller profiler) {
        if (id < 0) id = TextureUtil.generateTextureId();
        TextureUtil.prepareImage(id, MIPS, WIDTH, HEIGHT);
        for (int l = 0; l < levels.length; l++) {
            NativeImage img = levels[l];
            img.upload(l, 0, 0, 0, 0, img.getWidth(), img.getHeight(), true, true, true, true);
        }
    }

    private static <T> T read(ResourceManager resources, ResourceLocation at, java.util.function.Function<Reader, T> parse, T fallback) {
        Optional<Resource> res = resources.getResource(at);
        if (res.isPresent()) {
            try (Reader in = new InputStreamReader(res.get().open(), StandardCharsets.UTF_8)) {
                return parse.apply(in);
            } catch (IOException | RuntimeException e) {
                Incoming.LOG.warn("{} could not be read: flipbook timing falls back to defaults", at, e);
                return fallback;
            }
        }
        Incoming.LOG.warn("{} not found: flipbook timing falls back to defaults", at);
        return fallback;
    }

    /** Image and its mip levels into their sheet slots (opacity-premultiplied colour); resize if dimensions differ. */
    private static void place(ResourceManager resources, Slot s, int[][] sheets) {
        Optional<Resource> res = resources.getResource(s.texture);
        if (res.isEmpty()) {
            Incoming.LOG.warn("Effect texture {} not found: its slot stays empty", s.texture);
            return;
        }
        int[] px;
        try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
            px = fit(img, s.w, s.h);
        } catch (IOException | RuntimeException e) {
            Incoming.LOG.warn("Effect texture {} could not be read: its slot stays empty", s.texture, e);
            return;
        }
        int w = s.w, h = s.h;
        for (int l = 0; ; l++) {
            put(sheets[l], WIDTH >> l, s, l, px);
            if (l == MIPS) break;
            px = half(px, w, h);
            w >>= 1;
            h >>= 1;
        }
    }

    /** Image pixels (ABGR, opacity-premultiplied colour) at slot size: bilinear if resized. */
    private static int[] fit(NativeImage img, int w, int h) {
        int sw = img.getWidth(), sh = img.getHeight();
        int[] src = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) src[y * sw + x] = premultiply(img.getPixelRGBA(x, y));
        }
        if (sw == w && sh == h) return src;
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out[y * w + x] = sample(src, sw, sh, (x + 0.5f) * sw / w - 0.5f, (y + 0.5f) * sh / h - 0.5f);
        }
        return out;
    }

    /** Level l copy of slot s into that level's sheet (sheet width — sheetW). */
    private static void put(int[] sheet, int sheetW, Slot s, int l, int[] px) {
        int w = Math.max(1, s.w >> l), h = Math.max(1, s.h >> l), x0 = s.x >> l, y0 = s.y >> l;
        for (int y = 0; y < h; y++) System.arraycopy(px, y * w, sheet, (y0 + y) * sheetW + x0, w);
    }

    /** ABGR → ABGR with opacity-premultiplied colour. */
    private static int premultiply(int abgr) {
        int a = abgr >>> 24;
        int r = (abgr & 0xFF) * a / 255, g = (abgr >> 8 & 0xFF) * a / 255, b = (abgr >> 16 & 0xFF) * a / 255;
        return a << 24 | b << 16 | g << 8 | r;
    }

    /** Interpolate pixels (bilinear, premultiplied-alpha channels), clamp to edge outside bounds. */
    private static int sample(int[] src, int w, int h, float fx, float fy) {
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        int c00 = at(src, w, h, x0, y0), c10 = at(src, w, h, x0 + 1, y0), c01 = at(src, w, h, x0, y0 + 1), c11 = at(src, w, h, x0 + 1, y0 + 1);
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            float v0 = (c00 >>> shift & 0xFF) * (1 - tx) + (c10 >>> shift & 0xFF) * tx;
            float v1 = (c01 >>> shift & 0xFF) * (1 - tx) + (c11 >>> shift & 0xFF) * tx;
            out |= Math.round(v0 * (1 - ty) + v1 * ty) << shift;
        }
        return out;
    }

    private static int at(int[] src, int w, int h, int x, int y) {
        return src[Math.clamp(y, 0, h - 1) * w + Math.clamp(x, 0, w - 1)];
    }

    /** Half-size copy: four-pixel average per channel. */
    private static int[] half(int[] src, int w, int h) {
        int hw = w >> 1, hh = h >> 1;
        int[] out = new int[hw * hh];
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < hw; x++) {
                int i = 2 * y * w + 2 * x;
                int a = src[i], b = src[i + 1], c = src[i + w], d = src[i + w + 1], v = 0;
                for (int shift = 0; shift < 32; shift += 8) {
                    int sum = (a >>> shift & 0xFF) + (b >>> shift & 0xFF) + (c >>> shift & 0xFF) + (d >>> shift & 0xFF);
                    v |= (sum + 2) / 4 << shift;
                }
                out[y * hw + x] = v;
            }
        }
        return out;
    }

    private static NativeImage image(int[] px, int w, int h) {
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) img.setPixelRGBA(x, y, px[y * w + x]);
        }
        return img;
    }
}
