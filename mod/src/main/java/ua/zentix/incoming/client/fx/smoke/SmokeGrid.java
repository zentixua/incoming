package ua.zentix.incoming.client.fx.smoke;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Client clouds and eye-local smoke grid. Cloud source ({@link Source}: explosion cloud, launch cloud, fire smoke,
 * {@link #add}) emits Gaussian matter clumps by its law; scale decides rendering. Grid volume is one
 * 3D extinction texture traversed by the pall pass ({@code PallPass}, {@code fx_pall}) in its ray march,
 * only for sources at scales its cell resolves ({@link Source#volume}: clouds hundreds of meters to
 * kilometers wide), and only if every clump is at least two cells of its cascade wide ({@link #RESOLVE}). Conventional weapon clumps
 * (meters to tens of meters) would be smeared into cubes and blur by 8–32 m cells (3.5.0); effects layer renders them as baked
 * clumps ({@code far/SmokePuffs}, {@link #quads}). Without Minecraft or OpenGL: here are frame selection, cascades and layout;
 * {@code fx/layer/SmokePass} uploads to GPU memory.
 * <p>
 * <b>Cascades.</b> {@link #CASCADES} cubes of {@link #N}³ cells around eye, cell size {@link #CELL} m (2, 8, 32: near eye
 * 128 m, then 512 m and 2 km), cube corner aligned to cell (grid fixed in world, not moving with eye). Clump
 * (Gaussian of matter mass) occupies exactly one cascade: finest fully containing it (three σ), with σ no wider than
 * {@link #SPAN} cells; each cascade thus has its own clumps, and point density sums cascades containing
 * matter there. No cascade-edge seam: clump near edge already belongs to next. Finer cells do not fragment Gaussian: cascade clump σ
 * is √(σ² + (cell/2)²), same mass.
 * <p>
 * <b>Segment.</b> A clump may be spread over segment c − a to c + a ({@link Sink#segment}): density averages Gaussian over
 * its centers on segment, ρ = m·G₀·e^(−q⊥²/2)·√(π/2)/(2A)·[erf((A − q∥)/√2) + erf((A + q∥)/√2)] in axes where σ is one
 * (A is a's length there, q∥ and q⊥ along and across; {@link #line}). Trail and smoke column use several segments, not hundreds of
 * chained clumps: chain steps exceeding σ would create beads.
 * <p>
 * <b>Texture</b> is RGBA16F (2N × N × N·cascades): x has two pages, y (layers) is height, z is world z. Page 0:
 * soot, dust and drop extinction (1/m, σ' after delta-Eddington) and Σ extinction × ΔT (kK); page 1: Σ dust extinction
 * × albedo ω' (r, g, b) and Σ extinction × rise speed (m/s), making clump noise follow matter.
 * Filled each frame, layer by layer, clump as cell rectangle containing it, additively (shader
 * {@code fx_smoke_fill}); empty layers are not drawn.
 * <p>
 * <b>Matter</b> uses {@link CloudModel} optics: soot (albedo 0.25, asymmetry 0.5), dust (albedo from soil color,
 * asymmetry 0.6), drops (albedo ~1, asymmetry 0.85); shader has same numbers ({@code smoke.glsl}, checked by
 * {@code SmokeGlslTest}).
 */
public final class SmokeGrid {
    /** Cells per cascade side. */
    public static final int N = 64;
    /** Cascades and cell sizes, m. */
    public static final int CASCADES = 3;
    public static final float[] CELL = {2, 8, 32};
    /** Clump σ wider than this many cells moves to coarser cascade (last keeps it as is). */
    public static final float SPAN = 4;
    /** Clump extends this many σ from center in cascade. */
    public static final float REACH = 3;
    /**
     * Maximum frame clumps (clump table width). Overflow removes sources farthest from eye, whole
     * ({@link Source#placed}): they render themselves ({@code FarBlasts} quads), not half a cloud.
     */
    public static final int MAX_BLOBS = 512;
    /** Maximum clumps sources can emit per frame before layout (overflow excludes source from grid). */
    static final int STAGED = 8 * MAX_BLOBS;
    /** Numbers per staged clump: 16 {@link Sink#segment} parameters and source number. */
    private static final int STAGE = 17;
    /**
     * Clump table rows: center and horizontal σ; vertical σ and peak extinctions; dust albedo and half-segment x;
     * ΔT, rise, half-segment y and z.
     */
    public static final int BLOB_ROWS = 4;
    /** Vertices per clump per layer: two rectangles (pages), two triangles each. */
    static final int VERTICES = 12;
    /** Air temperature, K (as cloud model). */
    public static final float AIR_K = (float) CloudModel.T_AIR;

    /** Soot, dust, drops: albedo ω' and asymmetry g' after delta-Eddington (f = g²), mass extinction σ', m²/kg. */
    public static final float SOOT_W, SOOT_G, DUST_G, DROP_W, DROP_G;
    public static final double SOOT_K, DROP_K;

    static {
        double f = CloudModel.SOOT_G * CloudModel.SOOT_G;
        SOOT_W = (float) ((1 - f) * CloudModel.SOOT_W / (1 - f * CloudModel.SOOT_W));
        SOOT_G = (float) (CloudModel.SOOT_G / (1 + CloudModel.SOOT_G));
        SOOT_K = CloudModel.SOOT_K * (1 - CloudModel.SOOT_W * f);
        DUST_G = (float) (0.6 / 1.6);
        // 5 µm drops barely absorb: ω = 0.9999 (water in visible spectrum), f = g²
        double fw = CloudModel.DROP_G * CloudModel.DROP_G, ww = 0.9999;
        DROP_W = (float) ((1 - fw) * ww / (1 - fw * ww));
        DROP_G = (float) (CloudModel.DROP_G / (1 + CloudModel.DROP_G));
        DROP_K = CloudModel.DROP_K * (1 - ww * fw);
    }

    /**
     * Matter source: explosion cloud (here), launch cloud, trail, fire smoke (added by other client parts).
     * Emits clumps in world coordinates each frame; false means exhausted, leaves grid.
     */
    public interface Source {
        boolean emit(double seconds, Sink out);

        /** Cheap support at absolute time. False leaves legacy/volume sources on their existing path. */
        default boolean bounds(double seconds, SmokeBounds out) { return false; }
        /** Absolute source age, used only to prioritize rapidly changing young clouds. */
        default double age(double seconds) { return Double.POSITIVE_INFINITY; }

        /** Incremental hidden history only, on the client thread under the shared preparation budget. */
        default void maintain(double seconds) {}

        /** Conventional source also uses the preparation budget when its short-lived support is not bounded. */
        default boolean budgeted() { return false; }

        /**
         * Source at scale grid volume resolves (cloud hundreds of meters or more): only these enter grid.
         * Conventional weapon clouds do not: effects-layer quads render them ({@link #quads}).
         */
        default boolean volume() {
            return false;
        }

        /**
         * Source has spray: water thrown upward and falling back (water explosion); clumps with more drops than
         * soot and dust use spray frames ({@code FxAtlas.spray6}). Otherwise all use smoke frames: launch-cloud water
         * and condensation clouds are mist rising with gas, not falling.
         */
        default boolean sprayClubs() {
            return false;
        }

        /** At age seconds: after water-explosion jets fall, remaining mist needs clumps without falling streaks. */
        default boolean sprayClubs(double seconds) {
            return sprayClubs();
        }

        /**
         * Source draws glowing gas itself as fire body (explosion cloud as fireball); effects layer does not draw hot clumps
         * as smoke; smoke appears by same share as body fades ({@code CloudModel.glowShare}). Otherwise
         * clumps are smoke at any temperature (launch cloud, fire smoke).
         */
        default boolean drawsGlow() {
            return false;
        }

        /** Clump seed: effects-layer child quad positions and frames. */
        default long seed() {
            return 0;
        }

        /**
         * Growth factor since emission (current σ against initial σ): baked effects-layer clump frame
         * where simulation grew by same factor ({@code FxAtlas.puffFrame}).
         */
        default double puffGrowth(double seconds) {
            return 2;
        }

        /**
         * Visible share of clump centered at world height y with radius r above terrain occluding distant source
         * (within render distance world depth clips clump): 1 means open.
         */
        default double visible(double y, double r) {
            return 1;
        }

        /**
         * Current frame layout result: true means source clumps in grid; false means none (table full, source farther than
         * others); cloud then renders itself.
         */
        default void placed(boolean inGrid) {}

        /** Source exhausted even without frames (grid tick removes it even with no frames). */
        default boolean ended() {
            return false;
        }
    }

    /**
     * Effects-layer clump appearance (baked simulation frames, {@code FxAtlas}): shape is presentation, matter and
     * size follow model. {@link #SMOKE}: explosion, fire, launch cloud (cloud-cap frames; source with spray renders drop
     * clumps as spray, {@link Source#sprayClubs}); {@link #EJECTA}: soil ejecta curtain ({@link Ejecta}: dust-impact
     * frames, JangaFX “Grenade Dust Impact”); {@link #CRUSH}: cohesive crushed dust (concrete, stone, masonry, slab:
     * building-collapse dust frames, “Building Implosion”); {@link #AIRBURST}: airburst cloud (airburst
     * frames, “Mid-Air Explosion”).
     */
    public enum Look { SMOKE, EJECTA, CRUSH, AIRBURST }

    /** Where source deposits clumps. */
    public interface Sink {
        /**
         * Following source clumps use {@code look} and {@code frame} (fractional; {@link Look#SMOKE} frame
         * follows source growth, {@link Source#puffGrowth}, ignoring {@code frame}). Grid ignores look. Before first
         * call, each source uses {@link Look#SMOKE}.
         */
        default void look(Look look, double frame) {}


        /**
         * Gaussian clump spread over segment center − (ax, ay, az) to center + (ax, ay, az) (see {@link SmokeGrid}):
         * world center (x, y, z), half-segment, m; horizontal and vertical σ, m; whole-segment soot, dust and drop masses, kg;
         * dust albedo ω' by channel (after delta-Eddington, {@link #dustAlbedo}); gas temperature, K; rise, m/s.
         */
        void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot, double dust,
                     double water, float dustR, float dustG, float dustB, double kelvin, double rise);

        /**
         * Smoke tube piece between ends a and b (world cross-section centers; fire plume, explosion-cloud stem): each end has
         * horizontal and vertical σ, m, soot and dust per piece-axis meter (not downwind path), kg/m; dust albedo ω' by
         * channel; matter-free piece is empty (retains piece number at sink); gas temperature, K; rise, m/s. Grid uses segment with averaged ends; effects layer renders
         * a tube like projectile trail ({@code FarFlightView.line}): σ and matter vary smoothly along axis; distant view uses
         * ribbon. sigma0 is cross-section σ at source itself (clump frame follows σ growth from it).
         */
        default void tube(double ax, double ay, double az, double sha, double sva, double sootA, double dustA, double bx, double by, double bz,
                     double shb, double svb, double sootB, double dustB, float albedoR, float albedoG, float albedoB, double kelvin, double rise,
                     double sigma0) {
            double hx = 0.5 * (bx - ax), hy = 0.5 * (by - ay), hz = 0.5 * (bz - az), len = 2 * Math.sqrt(hx * hx + hy * hy + hz * hz);
            if (sootA + sootB + dustA + dustB <= 0) return;
            segment(ax + hx, ay + hy, az + hz, hx, hy, hz, 0.5 * (sha + shb), 0.5 * (sva + svb), 0.5 * (sootA + sootB) * len,
                    0.5 * (dustA + dustB) * len, 0, albedoR, albedoG, albedoB, kelvin, rise);
        }

        /** Gaussian clump at point (zero-length segment). */
        default void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water,
                          float dustR, float dustG, float dustB, double kelvin, double rise) {
            segment(x, y, z, 0, 0, 0, sh, sv, soot, dust, water, dustR, dustG, dustB, kelvin, rise);
        }
    }

    /** Grid frame: clumps, cascades and layers, uploaded by {@code SmokePass}. */
    public static final class Frame {
        /** Clump table: {@link #BLOB_ROWS} rows of {@link #MAX_BLOBS} RGBA (row 0: eye-relative center and σh…). */
        public final float[] blobs = new float[BLOB_ROWS * MAX_BLOBS * 4];
        /** Table clump count. */
        public int count;
        /** Clump cascade. */
        final int[] cascade = new int[MAX_BLOBS];
        /** Eye-relative cascade corner (x, y, z) and cell, by cascade; world corner (for cell alignment). */
        public final float[] corner = new float[CASCADES * 4];
        /** All-smoke eye-relative bounds; min > max means no smoke. */
        public final float[] min = new float[3], max = new float[3];
        /** Eye-relative clump box per cascade (x, y, z by cascade), within cascade; lo > hi means empty cascade. */
        public final float[] boxLo = new float[CASCADES * 3], boxHi = new float[CASCADES * 3];
        /** Layer clump rectangle vertices (x, y NDC, z clump number) and layer starts (vertex offsets). */
        public float[] vertices = new float[4096 * 3];
        public final int[] layerStart = new int[CASCADES * N + 1];

        public boolean empty() {
            return count == 0;
        }
    }

    /**
     * Grid refuses clumps narrower than this many cascade cells (σ): cell broadens to √(σ² + (cell/2)²), drawing
     * cell shape instead of cloud. Grid gives whole source with such clump to quads.
     */
    public static final double RESOLVE = 2;

    private static final List<Source> SOURCES = new ArrayList<>();
    private static final SmokePreparation PREPARATION = new SmokePreparation();
    /** Sources whose clumps entered this frame's grid (quads render others, {@link #quads}). */
    private static final java.util.Set<Source> IN_GRID = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final Frame FRAME = new Frame();
    /** Grid clock: unpaused client ticks. */
    private static long ticks;

    private SmokeGrid() {}

    /** Volume rendering available (shaders/grid ready, {@code SmokeFrame}); otherwise quads render near and far explosion clouds. */
    private static boolean enabled;
    /** Pall pass drew grid last frame ({@code FxLayer}); otherwise quads render clouds as without volume. */
    private static boolean drawn = true;

    public static void enable(boolean on) {
        enabled = on;
    }

    /** Volume available and drawn last frame. */
    public static boolean on() {
        return enabled && drawn;
    }

    /** Effects-layer frame result: pall pass drew grid (or no smoke, so nothing obstructs volume). */
    public static void drawn(boolean yes) {
        drawn = yes;
    }

    /**
     * Share of cloud centered at eye-relative (dx, dy, dz) rendered by grid volume: full within 0.55 last-cascade half-side on each
     * axis, smoothly zero at 0.75 (cloud clumps up to three σ, hundreds of meters, remain in grid); rest uses
     * clump quads of same model ({@code FarBlasts}).
     */
    public static double volumeShare(double dx, double dy, double dz) {
        double half = 0.5 * N * CELL[CASCADES - 1], d = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        double t = Math.max(0, Math.min(1, (d - 0.55 * half) / (0.2 * half)));
        return 1 - t * t * (3 - 2 * t);
    }

    /** New matter source (explosion cloud, launch cloud, trail…). */
    public static void add(Source s) {
        SOURCES.add(s);
    }

    /** Grid clock, s: unpaused client ticks and tick fraction. */
    public static double seconds(float partial) {
        return (ticks + partial) / 20.0;
    }

    /** Client tick (unpaused); exhausted sources leave even without frames (effects layer might not draw at all). */
    public static void tick() {
        ticks++;
        SOURCES.removeIf(Source::ended);
        IN_GRID.removeIf(Source::ended);
    }

    /** World change, replay seek: no sources. */
    public static void reset() {
        SOURCES.clear();
        PREPARATION.reset();
        IN_GRID.clear();
        FRAME.count = 0;
    }

    public static int sources() {
        return SOURCES.size();
    }

    /** Dust forward-scattering share f = g² (asymmetry 0.6), removed from extinction by delta-Eddington. */
    static final double DUST_F = 0.36;

    /**
     * Dust mass extinction σ' after delta-Eddington, m²/kg: {@code DustModel.K_MIX}·(1 − ωf), ω is single-scattering
     * albedo inverted from ω' ({@link #dustAlbedo}) of mean channel (one clump extinction for all
     * channels).
     */
    public static double dustK(float wr, float wg, float wb) {
        double wp = (wr + wg + wb) / 3.0, w = wp / (1 - DUST_F + DUST_F * wp);
        return ua.zentix.incoming.nuclear.model.DustModel.K_MIX * (1 - w * DUST_F);
    }

    /**
     * Dust albedo ω' from soil color (r, g, b on screen): inversion of thick-layer two-stream reflectance R∞ = (1 − s)/(1 + s),
     * s² = (1 − ω')/(1 − ω'g') (hemispheric-mean closure, as effects-layer clump color, {@code SmokePuffs.thick}).
     */
    public static float dustAlbedo(float display) {
        double r = Math.pow(Math.max(display, 0), 2.2);
        double u = (1 - r) / (1 + r), v = u * u;
        return (float) ((1 - v) / (1 - DUST_G * v));
    }

    /**
     * Grid frame at eye (eyeX, eyeY, eyeZ), time {@code seconds}: sources emit clumps, assigned to cascades and layers.
     * Exhausted sources leave. Empty frame means no smoke (pall pass skips it).
     */
    public static Frame frame(double eyeX, double eyeY, double eyeZ, double seconds) {
        Frame f = FRAME;
        build(SOURCES, f, eyeX, eyeY, eyeZ, seconds);
        return f;
    }

    /**
     * Sources not rendered by this frame's volume (no {@link Source#volume}, cell cannot resolve, full table,
     * no volume) use effects-layer quads: each receives source and returns whether alive (false means exhausted, leaves).
     */
    public static void quads(java.util.function.Predicate<Source> each) {
        for (var it = SOURCES.iterator(); it.hasNext(); ) {
            Source s = it.next();
            if (on() && IN_GRID.contains(s)) continue;
            if (!each.test(s)) {
                IN_GRID.remove(s);
                it.remove();
            }
        }
    }

    public static void quads(double seconds, double x, double y, double z, java.util.function.Predicate<SmokeBounds> visible,
                             SmokePreparation.Draw draw) {
        PREPARATION.draw(SOURCES, on() ? IN_GRID : java.util.Set.of(), seconds, x, y, z, visible, draw);
    }

    /** Staged frame clumps: {@link #STAGE} numbers per clump; sources in order and their nearest edge. */
    private static final double[] STAGE_BLOBS = new double[STAGED * STAGE];
    private static int staged;

    /** Eye of frame currently being laid out (sources read it in {@link Source#emit}). */
    private static double frameX, frameY, frameZ;

    /** Grid-frame eye (x, y, z), available to sources in {@link Source#emit} and afterward. */
    public static double eyeX() {
        return frameX;
    }

    public static double eyeY() {
        return frameY;
    }

    public static double eyeZ() {
        return frameZ;
    }

    /** Same for supplied source list and frame (unit test). */
    static void build(List<Source> sources, Frame f, double eyeX, double eyeY, double eyeZ, double seconds) {
        frameX = eyeX;
        frameY = eyeY;
        frameZ = eyeZ;
        f.count = 0;
        Arrays.fill(f.min, Float.MAX_VALUE);
        Arrays.fill(f.max, -Float.MAX_VALUE);
        Arrays.fill(f.boxLo, Float.MAX_VALUE);
        Arrays.fill(f.boxHi, -Float.MAX_VALUE);
        double[] corner = new double[CASCADES * 3];
        for (int c = 0; c < CASCADES; c++) {
            double cell = CELL[c];
            corner[3 * c] = Math.floor(eyeX / cell - N / 2.0) * cell;
            corner[3 * c + 1] = Math.floor(eyeY / cell - N / 2.0) * cell;
            corner[3 * c + 2] = Math.floor(eyeZ / cell - N / 2.0) * cell;
            f.corner[4 * c] = (float) (corner[3 * c] - eyeX);
            f.corner[4 * c + 1] = (float) (corner[3 * c + 1] - eyeY);
            f.corner[4 * c + 2] = (float) (corner[3 * c + 2] - eyeZ);
            f.corner[4 * c + 3] = (float) cell;
        }
        // stage clumps: nearest edge per source and emitted clump count
        staged = 0;
        int n = sources.size();
        double[] near = new double[n];
        int[] first = new int[n + 1], count = new int[n];
        boolean[] over = new boolean[n];
        for (int k = 0; k < n; k++) {
            int src = k;
            near[k] = Double.MAX_VALUE;
            first[k] = staged;
            Sink sink = (x, y, z, ax, ay, az, sh, sv, soot, dust, water, dr, dg, db, kelvin, rise) -> {
                if (soot + dust + water <= 0 || !(sh > 0) || !(sv > 0)) return;
                if (staged >= STAGED) {
                    over[src] = true;
                    return;
                }
                double[] b = STAGE_BLOBS;
                int at = staged++ * STAGE;
                b[at] = x;
                b[at + 1] = y;
                b[at + 2] = z;
                b[at + 3] = sh;
                b[at + 4] = sv;
                b[at + 5] = soot;
                b[at + 6] = dust;
                b[at + 7] = water;
                b[at + 8] = dr;
                b[at + 9] = dg;
                b[at + 10] = db;
                b[at + 11] = kelvin;
                b[at + 12] = rise;
                b[at + 13] = ax;
                b[at + 14] = ay;
                b[at + 15] = az;
                b[at + 16] = src;
                double dx = x - eyeX, dy = y - eyeY, dz = z - eyeZ;
                double reach = REACH * Math.max(sh, sv) + Math.sqrt(ax * ax + ay * ay + az * az);
                near[src] = Math.min(near[src], Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - reach));
            };
            // wrong-scale source does not enter grid: quads render its clumps, calling emit themselves
            if (!sources.get(k).volume()) {
                count[k] = 0;
                over[k] = true;
                continue;
            }
            boolean alive = sources.get(k).emit(seconds, sink);
            count[k] = alive ? staged - first[k] : -1;
        }
        // fill table with whole sources, nearest to farthest
        Integer[] order = new Integer[n];
        for (int k = 0; k < n; k++) order[k] = k;
        Arrays.sort(order, (a, b) -> Double.compare(near[a], near[b]));
        boolean[] in = new boolean[n];
        int room = MAX_BLOBS;
        for (int k : order) {
            if (count[k] <= 0 || over[k] || count[k] > room) continue;
            // clump beyond all cascades excludes entire source (cloud renders itself whole, not cut off)
            // also clump narrower than two cells of its cascade: grid would draw cell, not cloud
            boolean all = true;
            for (int i = first[k]; i < first[k] + count[k] && all; i++) all = resolves(corner, STAGE_BLOBS, i * STAGE);
            if (!all) continue;
            room -= count[k];
            in[k] = true;
            for (int i = first[k]; i < first[k] + count[k]; i++) {
                double[] b = STAGE_BLOBS;
                int at = i * STAGE;
                put(f, corner, eyeX, eyeY, eyeZ, b[at], b[at + 1], b[at + 2], b[at + 13], b[at + 14], b[at + 15], b[at + 3], b[at + 4],
                        b[at + 5], b[at + 6], b[at + 7], (float) b[at + 8], (float) b[at + 9], (float) b[at + 10], b[at + 11], b[at + 12]);
            }
        }
        for (int k = 0; k < n; k++) {
            if (count[k] < 0) continue;
            boolean placed = in[k] || count[k] == 0 && !over[k];
            sources.get(k).placed(placed);
            if (placed) IN_GRID.add(sources.get(k));
            else IN_GRID.remove(sources.get(k));
        }
        for (int k = n - 1; k >= 0; k--) if (count[k] < 0) IN_GRID.remove(sources.get(k));
        for (int k = n - 1; k >= 0; k--) if (count[k] < 0) sources.remove(k);
        layers(f);
    }

    /**
     * Staged clump at {@code at} in {@code b} fits a cascade and its σ (smaller of two) is at least
     * {@link #RESOLVE} cells of that cascade.
     */
    private static boolean resolves(double[] corner, double[] b, int at) {
        int k = cascade(corner, b, at);
        return k >= 0 && Math.min(b[at + 3], b[at + 4]) >= RESOLVE * CELL[k];
    }

    /** Cascade of staged clump at {@code at} in {@code b} ({@link #cascade(double[], double, double, double, double, double, double, double, double)}). */
    private static int cascade(double[] corner, double[] b, int at) {
        return cascade(corner, b[at], b[at + 1], b[at + 2], b[at + 13], b[at + 14], b[at + 15], b[at + 3], b[at + 4]);
    }

    /**
     * Cascade for clump centered at (x, y, z), half-segment (ax, ay, az), σ (sh, sv): finest containing whole clump (three σ, blurred
     * by half-cell, plus segment) with σ no wider than {@link #SPAN} cells; last if center lies within it (texture clips outer edges);
     * −1 if beyond all.
     */
    private static int cascade(double[] corner, double x, double y, double z, double ax, double ay, double az, double sh, double sv) {
        ax = Math.abs(ax);
        ay = Math.abs(ay);
        az = Math.abs(az);
        for (int k = 0; k < CASCADES; k++) {
            double cell = CELL[k], half = 0.5 * cell;
            double h = Math.sqrt(sh * sh + half * half), v = Math.sqrt(sv * sv + half * half);
            double span = N * cell;
            if (k == CASCADES - 1) {
                boolean inside = x >= corner[3 * k] && x < corner[3 * k] + span && z >= corner[3 * k + 2] && z < corner[3 * k + 2] + span
                        && y >= corner[3 * k + 1] && y < corner[3 * k + 1] + span;
                return inside ? k : -1;
            }
            double rx = REACH * h + ax, ry = REACH * v + ay, rz = REACH * h + az;
            boolean inside = x - rx >= corner[3 * k] && x + rx <= corner[3 * k] + span
                    && z - rz >= corner[3 * k + 2] && z + rz <= corner[3 * k + 2] + span
                    && y - ry >= corner[3 * k + 1] && y + ry <= corner[3 * k + 1] + span;
            if (inside && Math.max(h, v) <= SPAN * cell) return k;
        }
        return -1;
    }

    /** World cascade corners at eye (as frame, {@link #build}) into out (3 numbers per cascade). */
    public static void corners(double eyeX, double eyeY, double eyeZ, double[] out) {
        for (int c = 0; c < CASCADES; c++) {
            double cell = CELL[c];
            out[3 * c] = Math.floor(eyeX / cell - N / 2.0) * cell;
            out[3 * c + 1] = Math.floor(eyeY / cell - N / 2.0) * cell;
            out[3 * c + 2] = Math.floor(eyeZ / cell - N / 2.0) * cell;
        }
    }

    /**
     * Cell size of cascade receiving clump σ (sh, sv), world center (x, y, z), half-segment (ax, ay, az), m, with cascade
     * corners corner ({@link #corners}); 0 if none.
     */
    public static float cellFor(double[] corner, double x, double y, double z, double ax, double ay, double az, double sh, double sv) {
        int k = cascade(corner, x, y, z, ax, ay, az, sh, sv);
        return k < 0 ? 0 : CELL[k];
    }

    /**
     * Cross-section σ share correctly shown by volume in cascade of cell size cell: cell broadens it to
     * √(σ² + (cell/2)²); σ at least cell gives ≤ 12% widening, full share; σ at most half-cell gives ≥ 41%, no
     * share (thin matter uses trail ribbon); smooth between. Cascade resolution limit, not a visual setting.
     */
    public static double resolved(double sigma, double cell) {
        if (cell <= 0) return 0;
        double t = Math.max(0, Math.min(1, (sigma / cell - 0.5) / 0.5));
        return t * t * (3 - 2 * t);
    }

    /**
     * Segment-Gaussian multiplier relative to point Gaussian (same mass and σ) at axial distance q∥ from center,
     * in axes where σ is one; A is half-segment length there: √(π/2)/(2A)·[erf((A − q∥)/√2) + erf((A + q∥)/√2)], as A → 0,
     * e^(−q∥²/2). Separate transverse multiplier e^(−q⊥²/2). Same formula in {@code fx_smoke_fill} ({@code SmokeGlslTest}).
     */
    public static double line(double a, double q) {
        if (a < LINE_POINT) return Math.exp(-0.5 * q * q);
        return Math.sqrt(Math.PI / 2) / (2 * a) * Math.max(0, erf((a - q) / Math.sqrt(2)) + erf((a + q) / Math.sqrt(2)));
    }

    /** Shorter segments (in σ) become points: erf difference loses precision for short segments in shader half precision. */
    public static final double LINE_POINT = 0.05;

    /** Abramowitz and Stegun 7.1.26 erf (error ≤ 1.5·10⁻⁷), as shader. */
    public static double erf(double x) {
        double ax = Math.abs(x), t = 1 / (1 + 0.3275911 * ax);
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-ax * ax);
        return Math.signum(x) * y;
    }

    /** Add clump to table and cascade; reject beyond all cascades, empty or table overflow. */
    private static void put(Frame f, double[] corner, double ex, double ey, double ez, double x, double y, double z, double ax, double ay,
                            double az, double sh, double sv, double soot, double dust, double water, float dr, float dg, float db,
                            double kelvin, double rise) {
        if (f.count >= MAX_BLOBS) return;
        int c = cascade(corner, x, y, z, ax, ay, az, sh, sv);
        if (c < 0) return;
        double half = 0.5 * CELL[c], h = Math.sqrt(sh * sh + half * half), v = Math.sqrt(sv * sv + half * half);
        int i = f.count++;
        f.cascade[i] = c;
        double peak = 1 / (Math.pow(2 * Math.PI, 1.5) * h * h * v);
        float[] b = f.blobs;
        set(b, 0, i, (float) (x - ex), (float) (y - ey), (float) (z - ez), (float) h);
        set(b, 1, i, (float) v, (float) (SOOT_K * soot * peak), (float) (dustK(dr, dg, db) * dust * peak),
                (float) (DROP_K * water * peak));
        set(b, 2, i, dr, dg, db, (float) ax);
        set(b, 3, i, (float) (Math.max(0, kelvin - AIR_K) / 1000), (float) rise, (float) ay, (float) az);
        float rx = (float) (REACH * h + Math.abs(ax)), ry = (float) (REACH * v + Math.abs(ay)), rz = (float) (REACH * h + Math.abs(az));
        float px = (float) (x - ex), py = (float) (y - ey), pz = (float) (z - ez);
        f.min[0] = Math.min(f.min[0], px - rx);
        f.min[1] = Math.min(f.min[1], py - ry);
        f.min[2] = Math.min(f.min[2], pz - rz);
        f.max[0] = Math.max(f.max[0], px + rx);
        f.max[1] = Math.max(f.max[1], py + ry);
        f.max[2] = Math.max(f.max[2], pz + rz);
        // cascade box within bounds (last-cascade clump may exceed edge)
        double span = N * CELL[c];
        double[] lo = {x - rx, y - ry, z - rz}, hi = {x + rx, y + ry, z + rz};
        double[] eye = {ex, ey, ez};
        for (int a = 0; a < 3; a++) {
            double from = corner[3 * c + a];
            f.boxLo[3 * c + a] = Math.min(f.boxLo[3 * c + a], (float) (Math.max(lo[a], from) - eye[a]));
            f.boxHi[3 * c + a] = Math.max(f.boxHi[3 * c + a], (float) (Math.min(hi[a], from + span) - eye[a]));
        }
    }

    /** Frame cascade {@code c} contains clumps. */
    public static boolean occupied(Frame f, int c) {
        return f.boxLo[3 * c] <= f.boxHi[3 * c];
    }

    private static void set(float[] b, int row, int i, float x, float y, float z, float w) {
        int at = (row * MAX_BLOBS + i) * 4;
        b[at] = x;
        b[at + 1] = y;
        b[at + 2] = z;
        b[at + 3] = w;
    }

    /**
     * Layer clump rectangles: layer L = cascade·N + vertical layer number; clump occupies cascade layers containing it
     * (three vertical σ), as rectangle of horizontal cells containing it, on both pages.
     */
    static void layers(Frame f) {
        int layers = CASCADES * N;
        int[] count = new int[layers];
        int[] lo = new int[f.count], hi = new int[f.count];
        for (int i = 0; i < f.count; i++) {
            int c = f.cascade[i];
            float cell = f.corner[4 * c + 3];
            float y = f.blobs[(i) * 4 + 1], v = f.blobs[(MAX_BLOBS + i) * 4], ay = Math.abs(f.blobs[(3 * MAX_BLOBS + i) * 4 + 2]);
            lo[i] = Math.max(0, (int) Math.floor((y - REACH * v - ay - f.corner[4 * c + 1]) / cell));
            hi[i] = Math.min(N - 1, (int) Math.floor((y + REACH * v + ay - f.corner[4 * c + 1]) / cell));
            for (int s = lo[i]; s <= hi[i]; s++) count[c * N + s]++;
        }
        int total = 0;
        for (int l = 0; l < layers; l++) {
            f.layerStart[l] = total;
            total += count[l] * VERTICES;
        }
        f.layerStart[layers] = total;
        if (f.vertices.length < total * 3) f.vertices = new float[Math.max(total * 3, f.vertices.length * 2)];
        int[] at = new int[layers];
        for (int l = 0; l < layers; l++) at[l] = f.layerStart[l];
        for (int i = 0; i < f.count; i++) {
            int c = f.cascade[i];
            float cell = f.corner[4 * c + 3];
            float x = f.blobs[i * 4], z = f.blobs[i * 4 + 2], h = f.blobs[i * 4 + 3];
            float sx = Math.abs(f.blobs[(2 * MAX_BLOBS + i) * 4 + 3]), sz = Math.abs(f.blobs[(3 * MAX_BLOBS + i) * 4 + 3]);
            float x0 = clampCell((x - REACH * h - sx - f.corner[4 * c]) / cell), x1 = clampCell((x + REACH * h + sx - f.corner[4 * c]) / cell + 1);
            float z0 = clampCell((z - REACH * h - sz - f.corner[4 * c + 2]) / cell), z1 = clampCell((z + REACH * h + sz - f.corner[4 * c + 2]) / cell + 1);
            if (x1 <= x0 || z1 <= z0) continue;
            for (int s = lo[i]; s <= hi[i]; s++) {
                int l = c * N + s;
                for (int page = 0; page < 2; page++) {
                    // NDC: page 0 is left half of width 2N, page 1 is right
                    float ax = (page * N + x0) / N - 1, bx = (page * N + x1) / N - 1;
                    float ay = z0 / N * 2 - 1, by = z1 / N * 2 - 1;
                    at[l] = quad(f.vertices, at[l], ax, ay, bx, by, i);
                }
            }
        }
    }

    private static float clampCell(float c) {
        return Math.max(0, Math.min(N, (float) Math.floor(c)));
    }

    private static int quad(float[] v, int at, float ax, float ay, float bx, float by, int blob) {
        at = vertex(v, at, ax, ay, blob);
        at = vertex(v, at, bx, ay, blob);
        at = vertex(v, at, bx, by, blob);
        at = vertex(v, at, ax, ay, blob);
        at = vertex(v, at, bx, by, blob);
        return vertex(v, at, ax, by, blob);
    }

    private static int vertex(float[] v, int at, float x, float y, int blob) {
        v[3 * at] = x;
        v[3 * at + 1] = y;
        v[3 * at + 2] = blob;
        return at + 1;
    }
}
