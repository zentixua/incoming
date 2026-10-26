package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.util.noise.FastNoiseLite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Firestorm above the burning city ("cinema-level nuke" plan, step 4): what burns at distance and how — a client model without
 * world access or a packet, like the pall ({@link PallField}). Drawn by the pall volume ({@code fx/layer/PallPass}: smoke columns,
 * glow from below, fire whirls) and surface flame ({@code fx_pall_composite}).
 * <p>
 * <b>Sources.</b> Ignition sites are grouped into sources on a {@link #CELL}-block grid. Burning cell fraction uses the same
 * law as the server and sound ({@link FireField}, {@code RuinPlanner.fire}): light ignites min(1, q/20), fuel exists over
 * {@link FireField#SHARE} of the area, and the wave blows out part of the fire on arrival (half at 2 psi and above): cell strength
 * before the wave is {@link Grid#before}, after it {@link Grid#strength}; light uses district flammability rather than a threshold:
 * districts (world noise, {@link #DISTRICT_BLOCKS}-sized patches, detonation seed) burn differently, as do cells within them
 * (lognormal spread {@link #SIGMA_SPOT}), producing a ragged ignition-zone edge. Cell strength is the fraction of its area burning
 * (0..several); ignition follows the flash (when local light reaches threshold, {@link Detonation#flashTicks}: like the server flash layer,
 * {@code FlashScorch}) with ignition spread up to {@link #IGNITE_SPREAD_S}. The server has at most
 * 20 000 real fire blocks per detonation after the wave (before it, also those it will blow out; {@link ua.zentix.incoming.client.fx.FireWatch} sees them), while visually the whole ignition zone burns: the plan does not make "live vanilla fire across the whole
 * map".
 * <p>
 * <b>Time.</b> Real seconds since cell ignition, like the pall: catches over {@link #FLAME_RISE_S} s at fraction
 * {@link #START} of full strength, grows into a storm e-fold over {@link #GROW_S} s, then from {@link #PEAK_S} s (cell-specific)
 * decays e-fold over {@link #DECAY_S} s to smoldering {@link #EMBERS}; toward morning, like pall haze ({@link PallField#end}),
 * fades over the final minute. Smoke outlives flame ({@link #SMOKE_EMBERS}). All follows detonation age: a replay after
 * seeking and a late joiner see the same thing.
 * <p>
 * <b>Map.</b> A grid texture for the GPU ({@link #fill}): R — flame, G — column smoke (strong cells stronger:
 * {@link #COLUMN_POWER}), B — smoke × column-rise fraction ({@link #COLUMN_RISE_S}), with mipmaps. Mipmaps are
 * source clusters: light and smoke at height h use the level whose cell width matches column or glow width at that
 * height (like light-source trees); regardless of burning cell count, one sample per ray step. Lower down, while columns are narrower than
 * cells ({@link #PILLAR}), each cell has its own column in place (four nearest cells per ray step): near ground columns
 * are separate and merge higher up. Water, ice and snow known to the client do not burn ({@link #survey}).
 * <p>
 * <b>Whirls.</b> A few fire whirls ({@link Whirl}) at the strongest sources, rare and brief: an event rather than
 * wallpaper. All from the detonation seed.
 * <p>
 * The grid is calculated in the background (tens of thousands of cells with noise); no storm until ready.
 */
public final class FireStorm {
    /** Source cell width, blocks. */
    static final double CELL = 32;
    /** Maximum map side in cells (about 170 for a 15 kt airburst). */
    static final int MAX_SIDE = 256;
    /** Districts burn differently: patches of this size, blocks (city blocks, parks, industrial areas). */
    static final double DISTRICT_BLOCKS = 360;
    /** Spread of district flammability (log σ) and cells within a district. */
    static final double SIGMA_DISTRICT = 0.75, SIGMA_SPOT = 0.65;
    /** Ground light with district flammability: log σ makes the ignition-zone edge ragged rather than circular. */
    static final double SIGMA_EDGE = 0.35;
    /** Ignition threshold ({@code RuinPlanner.fire}). */
    static final double FULL_Q = 20;
    /** Cells ignite over this many seconds after the flash (most immediately). */
    static final double IGNITE_SPREAD_S = 30;
    /** The wave blows out cell fire over this many ticks from arrival. */
    static final double BLOW_TICKS = 20;
    /** Cell flame catches over this many seconds, up to fraction {@link #START} of full strength. */
    static final double FLAME_RISE_S = 12, START = 0.3;
    /** Grows into a storm e-fold over this many seconds. */
    static final double GROW_S = 80;
    /** From this second after ignition (±30 % per cell), decays e-fold over {@link #DECAY_S} to smoldering {@link #EMBERS}. */
    static final double PEAK_S = 360, DECAY_S = 540, EMBERS = 0.2;
    /** Smoke decays less than flame: smoldering material emits smoke. */
    static final double SMOKE_EMBERS = 0.45;
    /** Smoke emission into columns — cell strength raised to this power: strong sources produce columns, weak ones low haze. */
    static final double COLUMN_POWER = 1.4;
    /** Column rises to its top over this many seconds from ignition. */
    static final double COLUMN_RISE_S = 25;
    /** Channels per map cell (RGBA). */
    static final int CHANNELS = 4;
    /** Refill map every this many ticks while fire is catching anywhere, and every this many afterward. */
    static final int FILL_FAST = 4, FILL_SLOW = 20;

    // ---------------------------------------------------------------- laws (shared with shader, pall.glsl)

    /** Wind drift of the column grows with height: at height h, h·(1 + h/hb), in units of wind speed relative to rise speed. */
    static final double BEND_HEIGHT = 200;
    /**
     * Ground-level column half-width is this cell fraction, then grows by {@link #SPREAD} blocks per block of rise (air
     * entrainment): cell columns stand separately at ground level, merging into common haze higher up.
     */
    static final double PILLAR = 0.3, SPREAD = 0.22;
    /** Smoke rise speed in the column, m/s (tens in a firestorm). */
    static final double UPDRAFT_MS = 25;

    /** Column drift at height {@code h} above ground, blocks per unit wind-to-rise ratio ({@code fireBend}). */
    static double bend(double h, double hb) {
        double x = Math.max(h, 0);
        return x * (1 + x / hb);
    }

    /** Column half-width at height {@code h}, blocks ({@code fireWidth}). */
    static double width(double h, double cell) {
        return PILLAR * cell + SPREAD * Math.max(h, 0);
    }

    /** Map level where cell width is {@code w} ({@code fireLod}). */
    static double lod(double w, double cell) {
        return log2(Math.max(2 * w / cell, 1));
    }

    /** Map level for glow at height {@code h}: light from the entire area of width ~2h ({@code fireGlowLod}). */
    static double glowLod(double h, double cell) {
        return log2(Math.max(2 * Math.max(h, 6) / cell, 1));
    }

    /** Vertical column profile: from lowlands (up to 40 blocks below ground zero) to the top, spreading into haze ({@code fireColumn}). */
    static double column(double h, double top) {
        return smooth(-40, 0, h) * (1 - smooth(0.65 * top, top, h));
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    // ---------------------------------------------------------------- time

    /** Cell flame {@code ageS} seconds after ignition, fraction of full strength ({@code peakS} is its decay start). */
    static double flame(double ageS, double peakS) {
        if (ageS <= 0) return 0;
        double grow = START + (1 - START) * (1 - Math.exp(-ageS / GROW_S));
        return smooth(0, FLAME_RISE_S, ageS) * grow * decay(ageS, peakS, EMBERS);
    }

    /** Cell smoke (emission into a column), fraction of full strength. */
    static double smoke(double ageS, double peakS) {
        if (ageS <= 0) return 0;
        double grow = START + (1 - START) * (1 - Math.exp(-ageS / GROW_S));
        return smooth(0, 1.5 * FLAME_RISE_S, ageS) * grow * decay(ageS, peakS, SMOKE_EMBERS);
    }

    private static double decay(double ageS, double peakS, double floor) {
        return ageS <= peakS ? 1 : floor + (1 - floor) * Math.exp(-(ageS - peakS) / DECAY_S);
    }

    // ---------------------------------------------------------------- grid

    /** Fire whirl: where, when, what (all blocks and seconds since detonation). */
    public record Whirl(double x, double z, double startS, double lengthS, double radius, double height, int spin, double driftDir, double phase) {
        /** Strength 0..1 at {@code seconds} after detonation: rises over 8 s, holds, subsides over 12 s. */
        public double strength(double seconds) {
            double age = seconds - startS;
            if (age <= 0 || age >= lengthS) return 0;
            return smooth(0, 8, age) * (1 - smooth(lengthS - 12, lengthS, age));
        }

        /** Base position at {@code seconds} after detonation: the whirl wanders across the fire (1.5 blocks/s). */
        public double baseX(double seconds) {
            return x + Math.cos(driftDir) * DRIFT * Math.max(0, seconds - startS);
        }

        public double baseZ(double seconds) {
            return z + Math.sin(driftDir) * DRIFT * Math.max(0, seconds - startS);
        }

        static final double DRIFT = 1.5;
    }

    /**
     * Source grid: zone cells (map index), post- and pre-wave strength, ignition and wave arrival (ticks since
     * detonation), decay start (s).
     */
    record Grid(int side, double cell, int[] index, float[] strength, float[] column, float[] before, float[] start, float[] arrival, float[] peak,
                double total, List<Whirl> whirls) {
        /** Last ignition, ticks since detonation. */
        double lastStart() {
            double last = 0;
            for (float s : start) last = Math.max(last, s);
            return last;
        }

        /** Last wave arrival at a cell where it blows out fire, ticks since detonation. */
        double lastBlow() {
            double last = 0;
            for (int k = 0; k < arrival.length; k++) if (before[k] != strength[k]) last = Math.max(last, arrival[k]);
            return last;
        }

        /** Cell {@code k} strength at {@code ticks} since detonation: before wave — {@link #before}; over {@link #BLOW_TICKS}, toward {@link #strength}. */
        double strengthAt(int k, double ticks) {
            if (ticks <= arrival[k]) return before[k];
            if (ticks >= arrival[k] + BLOW_TICKS) return strength[k];
            return before[k] + (strength[k] - before[k]) * smooth(arrival[k], arrival[k] + BLOW_TICKS, ticks);
        }
    }

    final Detonation d;
    private final PallField pall;
    @Nullable
    private CompletableFuture<Grid> building;
    @Nullable
    private Grid grid;
    private boolean failed;
    /** Map from latest {@link #fill}: RGBA per cell, x fastest; version increases on each fill. */
    private float[] map = new float[0];
    private int version;
    /** Burning amount in the latest fill (sum of cell flames). */
    private double burning;
    /** Time of last {@link #fill}; until when the storm is young ({@link #young}), ticks since detonation. */
    private double filledAt = Double.NaN, youngUntil = Double.NaN;
    /** Fire-model trust (server fires enabled): 0..1, written smoothly by client tick ({@link #trust}). */
    private double gate;
    /**
     * Local-chunk burning relative to the model there while fire is young ({@code NukeAfter}): 1 — matches model; 0 —
     * server does not burn, or extinguished. Lives with the storm even after replay seek: fire checked while young cannot be
     * checked later — vanilla burns out its own way, while the storm burns until morning.
     */
    private double seen = 1;
    /** Terrain top under whirls, blocks (NaN — not sampled: use ground zero); written by client tick. */
    private double[] whirlGround = new double[0];
    /**
     * Fraction of cell with fuel, from client terrain ({@link #survey}): water, ice and snow do not burn; unknown areas
     * (beyond render distance without a terrain map) follow ground zero. By zone-cell index, like the grid.
     */
    private float[] dry = new float[0];
    /** Cell seen at least once ({@link #survey}): player leaves, chunks unload — retain what was seen. */
    private boolean[] surveyed = new boolean[0];
    private int surveyAt;
    /** Dryness changed since last map fill: refill within {@link #FILL_FAST} ticks. */
    private boolean dirty;

    FireStorm(Detonation d, PallField pall) {
        this.d = d;
        this.pall = pall;
        this.gate = d.fires() ? 1 : 0;
    }

    /** Grid ready (first call starts background calculation); null — not yet, or no storm (nothing burns). */
    @Nullable
    Grid grid() {
        if (grid != null || failed) return grid;
        if (building == null) building = CompletableFuture.supplyAsync(() -> build(d, pall.fireReach()));
        if (!building.isDone()) return null;
        try {
            grid = building.join();
        } catch (RuntimeException ex) {
            failed = true;
            Incoming.LOG.error("Nuclear firestorm: fire sources of strike #{} failed, no firestorm", d.id(), ex);
            return null;
        }
        if (grid.index.length == 0) failed = true;
        return grid;
    }

    /** Total burning (sum of cell strengths) for the scenario line; 0 — grid not ready. */
    double total() {
        Grid g = grid;
        return g == null ? 0 : g.total;
    }

    /** Source cells (0 — grid not ready or nothing burns). */
    int cells() {
        Grid g = grid;
        return g == null ? 0 : g.index.length;
    }

    /** Whether the storm burns at {@code ticks} since detonation (refill map if due, {@link #refresh}). */
    boolean burningAt(double ticks) {
        refresh(ticks);
        return burning > 0;
    }

    /** Current burning (sum of cell flames in the latest map) for the scenario line. */
    double burning() {
        return burning;
    }

    /** Mean flame over the fire zone in the latest map, fraction of full strength (0 — grid not ready or nothing burns). */
    double meanFlame() {
        int n = cells();
        return n == 0 ? 0 : burning / n;
    }

    /** Detonation whirls (empty — grid not ready). */
    public List<Whirl> whirls() {
        Grid g = grid;
        return g == null ? List.of() : g.whirls;
    }

    /** Compare against local-chunk fire while young (written by {@code NukeAfter}). */
    void seen(double trust) {
        seen = trust;
    }

    /**
     * Whether server fires actually burn, 0..1: no if the server does not ignite them ({@link Detonation#fires}), or local
     * chunks had no fire where the model expected it while young.
     */
    double trust() {
        return d.fires() ? Math.min(1, seen / 0.3) : 0;
    }

    /** Model trust approached smoothly by client tick ({@link #trust}). */
    void gate(double g) {
        gate = g;
    }

    double gate() {
        return gate;
    }

    /**
     * Client tick: terrain top under active or imminent whirls ({@code seconds} since detonation), at five
     * points across the core, like ground dust ({@code CloudPuffs.track}): a whirl wanders across roofs and slopes, not at ground-zero
     * elevation. Unknown terrain retains the previous height.
     */
    void track(double seconds, Sightline.Heights heights) {
        Grid g = grid;
        if (g == null) return;
        if (whirlGround.length != g.whirls.size()) {
            whirlGround = new double[g.whirls.size()];
            Arrays.fill(whirlGround, Double.NaN);
        }
        for (int i = 0; i < whirlGround.length; i++) {
            Whirl w = g.whirls.get(i);
            if (seconds < w.startS() - 10 || seconds > w.startS() + w.lengthS()) continue;
            double x = w.baseX(seconds), z = w.baseZ(seconds), sum = 0;
            int n = 0;
            for (int k = 0; k < 5; k++) {
                double ox = k == 1 ? w.radius() : k == 2 ? -w.radius() : 0, oz = k == 3 ? w.radius() : k == 4 ? -w.radius() : 0;
                double h = heights.at(Mth.floor(x + ox), Mth.floor(z + oz));
                if (Double.isNaN(h)) continue;
                sum += h;
                n++;
            }
            if (n == 0) continue;
            double h = sum / n;
            // smoothly: the whirl does not jump from roof to street
            whirlGround[i] = Double.isNaN(whirlGround[i]) ? h : whirlGround[i] + 0.2 * (h - whirlGround[i]);
        }
    }

    /** Client knowledge of column surface. */
    @FunctionalInterface
    interface Surface {
        /** −1 — unknown; 0 — fuel present; 1 — water, ice or snow. */
        int wet(int x, int z);
    }

    /** Zone cells per {@link #survey} tick: full 15 kt zone in 7 seconds. */
    static final int SURVEY_CELLS = 128;
    /** Points per cell side (3 × 3). */
    static final int SURVEY_POINTS = 3;

    /**
     * Client tick: dry-ground fraction in the next {@link #SURVEY_CELLS} zone cells, cycling through them — 3 × 3 points per cell,
     * using client knowledge ({@code surface}: local chunks and terrain-map tiles). The fire model has no terrain knowledge, while burning sea
     * or glacier is immediately visible. Where nothing is or was known, the cell follows ground zero ({@code unknownWet}: water fraction there,
     * {@code NukeWater.under}, including a ground-height guess for a sea burst): otherwise a distant sea burst would burn the sea.
     */
    void survey(Surface surface, double unknownWet) {
        Grid g = grid;
        if (g == null || g.index.length == 0) return;
        if (dry.length != g.index.length) {
            dry = new float[g.index.length];
            Arrays.fill(dry, (float) (1 - unknownWet));
            surveyed = new boolean[g.index.length];
            surveyAt = 0;
            // a map filled before the first survey treated everything as land: refill immediately
            filledAt = Double.NaN;
        }
        double half = g.side * g.cell / 2, step = g.cell / SURVEY_POINTS;
        Vec3 b = d.burst();
        int n = Math.min(SURVEY_CELLS, g.index.length);
        for (int s = 0; s < n; s++) {
            int k = surveyAt;
            surveyAt = (surveyAt + 1) % g.index.length;
            int c = g.index[k];
            double x0 = b.x + (c % g.side) * g.cell - half, z0 = b.z + (double) (c / g.side) * g.cell - half;
            int known = 0, wet = 0;
            for (int j = 0; j < SURVEY_POINTS; j++) {
                for (int i = 0; i < SURVEY_POINTS; i++) {
                    int w = surface.wet(Mth.floor(x0 + (i + 0.5) * step), Mth.floor(z0 + (j + 0.5) * step));
                    if (w < 0) continue;
                    known++;
                    wet += w;
                }
            }
            if (known == 0 && surveyed[k]) continue;
            surveyed[k] |= known > 0;
            float v = known == 0 ? (float) (1 - unknownWet) : 1 - (float) wet / known;
            if (v != dry[k]) {
                dry[k] = v;
                dirty = true;
            }
        }
    }

    /** Dry-ground fraction in the cell beneath a point (world blocks); 1 outside the fire zone. */
    double dryAt(double x, double z) {
        Grid g = grid;
        if (g == null || dry.length != g.index.length) return 1;
        double half = g.side * g.cell / 2;
        int i = Mth.floor((x - d.burst().x + half) / g.cell), j = Mth.floor((z - d.burst().z + half) / g.cell);
        if (i < 0 || j < 0 || i >= g.side || j >= g.side) return 1;
        int k = Arrays.binarySearch(g.index, j * g.side + i);
        return k < 0 ? 1 : dry[k];
    }

    /** Ground beneath whirl {@code i}, blocks (not sampled — ground zero). */
    double whirlGround(int i) {
        return i < whirlGround.length && !Double.isNaN(whirlGround[i]) ? whirlGround[i] : d.groundY();
    }

    /** Map half-side, blocks (0 — no grid). */
    double half() {
        Grid g = grid;
        return g == null ? 0 : g.side * g.cell / 2;
    }

    int side() {
        Grid g = grid;
        return g == null ? 0 : g.side;
    }

    double cell() {
        Grid g = grid;
        return g == null ? CELL : g.cell;
    }

    float[] map() {
        return map;
    }

    int version() {
        return version;
    }

    /**
     * Map at {@code ticks} since detonation (client ticks), if due: every {@link #FILL_FAST} ticks while fire catches and columns rise
     * anywhere, then every {@link #FILL_SLOW}; immediately after a seek (time backward). true —
     * new map ({@link #map}, {@link #version}).
     */
    boolean refresh(double ticks) {
        Grid g = grid();
        if (g == null) return false;
        if (!Double.isNaN(filledAt) && ticks >= filledAt) {
            double every = young(g, ticks) || dirty ? FILL_FAST : FILL_SLOW;
            if (ticks - filledAt < every) return false;
        }
        if (map.length != g.side * g.side * CHANNELS) map = new float[g.side * g.side * CHANNELS];
        fill(g, ticks, pall.end(), dry.length == g.index.length ? dry : null, map);
        dirty = false;
        double sum = 0;
        for (int i = 0; i < map.length; i += CHANNELS) sum += map[i];
        burning = sum;
        filledAt = ticks;
        version++;
        return true;
    }

    /**
     * Somewhere fire is still catching, a column is rising, or the wave is blowing out fire: fewer than two column-rise times since
     * the last ignition, or the wave is still crossing the zone.
     */
    private boolean young(Grid g, double ticks) {
        if (Double.isNaN(youngUntil)) youngUntil = Math.max(g.lastStart() + 20 * 2 * Math.max(COLUMN_RISE_S, FLAME_RISE_S), g.lastBlow() + BLOW_TICKS);
        return ticks < youngUntil;
    }

    /** Morning: everything fades over the final minute before pall haze ends. */
    static double morning(double ticks, double end) {
        return 1 - smooth(end - PallField.HAZE_FADE_TICKS, end, ticks);
    }

    /**
     * Map at {@code ticks} (morning — {@code end}) into {@code out}: RGBA per cell, zero outside the zone; {@code dry} is the dry-ground
     * fraction per zone cell ({@link #survey}; null — all land).
     */
    static void fill(Grid g, double ticks, double end, @Nullable float[] dry, float[] out) {
        Arrays.fill(out, 0);
        double night = morning(ticks, end);
        if (night <= 0) return;
        for (int k = 0; k < g.index.length; k++) {
            double age = (ticks - g.start[k]) / 20.0;
            if (age <= 0) continue;
            double peak = g.peak[k];
            int i = g.index[k] * CHANNELS;
            double wet = dry == null ? 1 : dry[k];
            double s = g.strengthAt(k, ticks);
            double sm = (s == g.strength[k] ? g.column[k] : Math.pow(s, COLUMN_POWER)) * smoke(age, peak) * night * wet;
            out[i] = (float) (s * flame(age, peak) * night * wet);
            out[i + 1] = (float) sm;
            out[i + 2] = (float) (sm * Math.min(1, age / COLUMN_RISE_S));
        }
    }

    /**
     * Detonation source grid: cells within ignition zone {@code reach} (ground blocks from ground zero), strength from the ignition
     * law with district and cell flammability, ignition by flash, partial blowout by wave; whirls. Pure function of the detonation
     * (its seed).
     */
    static Grid build(Detonation d, double reach) {
        if (reach <= 0) return empty(CELL, 0);
        // cell width 32 blocks, smaller at small strike scale: at least 24 cells across the zone
        double cell = Math.max(2, Math.min(CELL, 2 * reach / 24));
        int side = Math.min(MAX_SIDE, 2 * (int) Math.ceil(reach * 1.05 / cell) + 2);
        double half = side * cell / 2;
        double inner = d.surface() ? d.fireballRadius() * 0.8 : 0;
        long seed = d.seed() ^ 0xF12E5702L;
        FastNoiseLite noise = new FastNoiseLite((int) (seed ^ seed >>> 32)), warp = new FastNoiseLite((int) (seed * 31 + 11));
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        noise.SetFrequency((float) (1 / DISTRICT_BLOCKS));
        noise.SetFractalType(FastNoiseLite.FractalType.FBm);
        noise.SetFractalOctaves(3);
        warp.SetDomainWarpType(FastNoiseLite.DomainWarpType.OpenSimplex2);
        warp.SetDomainWarpAmp((float) (DISTRICT_BLOCKS * 0.25));
        warp.SetFrequency((float) (1 / (DISTRICT_BLOCKS * 1.6)));
        Vec3 b = d.burst();
        int n = side * side;
        double[] raw = new double[n];
        boolean[] in = new boolean[n];
        double sum = 0, sum2 = 0;
        int count = 0;
        FastNoiseLite.Vector2 v = new FastNoiseLite.Vector2(0, 0);
        for (int j = 0; j < side; j++) {
            for (int i = 0; i < side; i++) {
                double x = (i + 0.5) * cell - half, z = (j + 0.5) * cell - half;
                double r = Math.hypot(x, z);
                if (r > reach * 1.05 || r < inner) continue;
                v.x = b.x + x;
                v.y = b.z + z;
                warp.DomainWarp(v);
                double nv = noise.GetNoise(v.x, v.y);
                raw[j * side + i] = nv;
                in[j * side + i] = true;
                sum += nv;
                sum2 += nv * nv;
                count++;
            }
        }
        if (count == 0) return empty(cell, side);
        // districts normalized to mean 0 and spread 1 over zone cells: z ~ N(0, 1)
        double mean = sum / count, std = Math.sqrt(Math.max(1e-9, sum2 / count - mean * mean));
        int[] index = new int[count];
        float[] strength = new float[count], column = new float[count], before = new float[count], start = new float[count], arrival = new float[count],
                peak = new float[count];
        int k = 0;
        double total = 0;
        for (int j = 0; j < side; j++) {
            for (int i = 0; i < side; i++) {
                int c = j * side + i;
                if (!in[c]) continue;
                double x = (i + 0.5) * cell - half, z = (j + 0.5) * cell - half;
                double r = Math.hypot(x, z);
                double district = (raw[c] - mean) / std;
                Vec3 p = new Vec3(b.x + x, d.groundY() + 1, b.z + z);
                double q = d.fluence(p) * Math.exp(SIGMA_EDGE * district - SIGMA_EDGE * SIGMA_EDGE / 2);
                if (q < ThermalModel.IGNITE) continue;
                // light ignites; the wave later blows out some fire (server: half at 2 psi and above), smoothly, no ring on the map
                double chance = Math.min(1, q / FULL_Q), keep = 1 - (1 - FireField.BLOWN_KEEP) * smooth(1.5, 2.5, d.psi(p));
                double fuel = Math.exp(SIGMA_DISTRICT * district - SIGMA_DISTRICT * SIGMA_DISTRICT / 2);
                long h = hash(seed, i, j);
                double spot = Math.exp(SIGMA_SPOT * gauss(h) - SIGMA_SPOT * SIGMA_SPOT / 2);
                double lit = FireField.SHARE * chance * fuel * spot, s = lit * keep;
                if (lit < 0.02) continue;
                index[k] = c;
                strength[k] = (float) s;
                column[k] = (float) Math.pow(s, COLUMN_POWER);
                before[k] = (float) lit;
                double u = unit(h >>> 17);
                start[k] = (float) (d.flashTicks(d.fluence(p), ThermalModel.IGNITE) + 20 * IGNITE_SPREAD_S * u * u);
                arrival[k] = (float) d.arrivalTicks(Math.hypot(r, b.y - d.groundY()));
                peak[k] = (float) (PEAK_S * (0.7 + 0.6 * unit(h >>> 34)));
                total += s;
                k++;
            }
        }
        index = Arrays.copyOf(index, k);
        strength = Arrays.copyOf(strength, k);
        column = Arrays.copyOf(column, k);
        before = Arrays.copyOf(before, k);
        start = Arrays.copyOf(start, k);
        arrival = Arrays.copyOf(arrival, k);
        peak = Arrays.copyOf(peak, k);
        return new Grid(side, cell, index, strength, column, before, start, arrival, peak, total,
                whirls(d, seed, side, cell, half, reach, index, strength, start, total));
    }

    private static Grid empty(double cell, int side) {
        return new Grid(side, cell, new int[0], new float[0], new float[0], new float[0], new float[0], new float[0], new float[0], 0, List.of());
    }

    /** Whirls per detonation: from summed strengths (three for 15 kt, one for 1 kt), capped at {@link #MAX_WHIRLS}. */
    static final int MAX_WHIRLS = 4;
    /** Minimum whirl spacing in blocks. */
    static final double WHIRL_GAP = 250;
    /** First whirl this many seconds after its cell ignites; later ones every {@link #WHIRL_EVERY_S}. */
    static final double WHIRL_FIRST_S = 150, WHIRL_EVERY_S = 140;

    /**
     * Whirls at the strongest cells (within 0.8 of the zone, at least {@link #WHIRL_GAP} apart): one per window
     * of a couple of minutes at storm strength, each lasting 45–90 s; core radius from cell strength, height 90–130 blocks.
     */
    private static List<Whirl> whirls(Detonation d, long seed, int side, double cell, double half, double reach, int[] index, float[] strength,
                                      float[] start, double total) {
        int n = (int) Math.max(1, Math.min(MAX_WHIRLS, Math.round(Math.sqrt(total) / 25)));
        Integer[] order = new Integer[index.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(strength[b], strength[a]));
        List<Whirl> out = new ArrayList<>();
        java.util.Random random = new java.util.Random(seed ^ 0x3A11L);
        for (int o = 0; o < order.length && out.size() < n; o++) {
            int k = order[o], c = index[k];
            double x = (c % side + 0.5) * cell - half, z = (c / side + 0.5) * cell - half;
            if (Math.hypot(x, z) > 0.8 * reach) continue;
            boolean near = false;
            for (Whirl w : out) near |= Math.hypot(w.x() - d.burst().x - x, w.z() - d.burst().z - z) < WHIRL_GAP * Math.min(1, reach / 1000);
            if (near) continue;
            int at = out.size();
            double startS = start[k] / 20.0 + WHIRL_FIRST_S + WHIRL_EVERY_S * at + 60 * random.nextDouble();
            double radius = Math.min(14, 6 + 3 * strength[k]) * Math.min(1, cell / CELL * 1.5);
            double height = (90 + 40 * random.nextDouble()) * Math.min(1, cell / CELL * 1.5);
            out.add(new Whirl(d.burst().x + x, d.burst().z + z, startS, 45 + 45 * random.nextDouble(), radius, height,
                    random.nextBoolean() ? 1 : -1, random.nextDouble() * 2 * Math.PI, random.nextDouble() * 100));
        }
        out.sort((a, b) -> Double.compare(a.startS(), b.startS()));
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- randomness

    private static long hash(long seed, int i, int j) {
        long h = seed ^ (i * 0x9E3779B97F4A7C15L) ^ (j * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    /** Number 0..1 from the low 17 bits. */
    private static double unit(long bits) {
        return ((bits & 0x1FFFF) + 0.5) / 0x20000;
    }

    /** Normal N(0, 1) from hash (Box–Muller), clamped to ±3. */
    private static double gauss(long h) {
        double u1 = unit(h), u2 = unit(h >>> 20);
        return Math.max(-3, Math.min(3, Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2)));
    }

    static double smooth(double e0, double e1, double x) {
        double f = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return f * f * (3 - 2 * f);
    }
}
