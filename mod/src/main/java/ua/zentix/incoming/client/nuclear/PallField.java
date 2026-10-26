package ua.zentix.incoming.client.nuclear;

import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

/**
 * Pall after the shock wave ("cinema-level nuke" plan, step 2): dust and smoke density field of one detonation by radius
 * from ground zero and time. Drawn by the effects-layer volume ({@code fx/layer/PallPass}: rays through the field multiplied by
 * 3D noise); also drives camera visibility, sound, ash and shadow ({@link NukePall}). Pure math from the detonation
 * model, without world access or a new packet: the client knows what the wave lofts from ground pressure
 * ({@link Detonation#groundOverpressureKpa}) and front arrival ({@link Detonation#arrivalTicks}), like the server.
 * <p>
 * <b>Dust</b> is the same layer as the wall head at the front ({@link BlastWall}), using the same laws ({@link DustModel}): what
 * the wave lofted (soil in wind behind the front and debris dust where houses collapse), layer top (tens of meters near the front,
 * then raised by flash-heated ground), settling of five size bins and loss of fines. Dust age is model seconds
 * since game-front arrival: head dust is younger than the positive phase, pall dust older; each location transitions from head
 * to pall over half a phase ({@link BlastWall#handoff}). Ground extinction is layer optical depth divided by its profile integral
 * ({@link #profile}: column = 0.548 of top). Detonation wind drifts dust ({@link #DRIFT}) from the moment it was lofted:
 * dust at the front is still in place ({@link #source}). Height starts at local ground where above ground zero
 * (hill: wind lofts dust from its slope); dust flows into valleys, where the layer starts at ground-zero elevation.
 * <p>
 * <b>Haze.</b> The city burns over the ignition zone (light pulse from {@link ThermalModel#IGNITE}): smoky haze
 * (visibility {@link #HAZE_VISIBILITY_M} m) rises over a couple of minutes, stays until morning ({@link #hazeEndTicks}; morning follows world
 * time, {@link #daylight}: sleep and {@code /time} bring it closer), trails downwind, lit by fires from below.
 * <p>
 * <b>Water</b> (plan, step 6). No dust over water: wind behind the front tears spray from crests ({@link WaterModel#spray}) —
 * a separate layer with its own height (crest stripping height, {@link WaterModel#sprayHeight}; large drops fall out, lowering
 * the top, {@link #sprayTop}) and density (drops: white, almost nonabsorbing, forward-scattering, settling and evaporating over a couple of
 * minutes, {@link #SPRAY_S}). A burst on water also produces a base surge — fog from the fallen water column
 * ({@link #surge}, {@link WaterModel} laws and clock, the same as the column in the mushroom volume). The {@link PallGround} map knows whether land or
 * water is beneath the pall (cell water fraction from client knowledge); material follows the place where
 * it was lofted: dust drifted onto water stays dust. Thus the shader samples water fraction at the same location as dust
 * and spray tables — point minus drift; haze at its source (no fires over water).
 * <p>
 * Time: dust and spray use the model clock (ticks / (20·scale)), like front and head; haze, fires and paper use real
 * seconds (client ticks / 20). Lengths in model meters and blocks through detonation scale. The radius table
 * ({@link #row}) is what the shader receives: all expensive work (dust model, front arrival) is done here.
 */
public final class PallField {
    /**
     * Dust-layer top unevenness: top above a point is (1 ± this) of table top (shader: {@code PALL_BUMPS}, tile noise).
     * Thermals make the mixed-layer top over heated ground bumpy — a convective entrainment zone 0.2–0.4
     * of layer height (Stull 1988, ch. 11; Deardorff, Willis and Stockton 1980), half of it — 0.3 (est.). Same layer profile
     * up to its own top, so optical depth above a point for light matches density: a hollow between bumps lies at layer top,
     * not deep within mean pall.
     */
    public static final double BUMPS = 0.3;
    /**
     * Mixed layer: internal density differs from the mean by this fraction of its previous variation (young layer near front has patches with
     * gaps, Kuhl §3.5: density jumps by a factor of 7). Scalar fluctuations in a mixed convective layer are 10–20 % of the mean
     * (Willis and Deardorff 1976, tank; (est.) — 0.2): dense continuous pall from above is one blanket, not patches with holes
     * down to ground.
     */
    public static final double MIXED = 0.2;

    /** Radius table nodes (width of the layer texture row). */
    public static final int BINS = 128;
    /**
     * Visibility in the former dense pall dust, m (Koschmieder extinction 3.912 / V): "dense dust" measure for billow shape
     * and ash ({@link #SIGMA_DENSE}); the front layer is tens of times denser.
     */
    static final double DENSE_VISIBILITY_M = 40;
    /** Visibility in smoky haze over the burning city, m. */
    static final double HAZE_VISIBILITY_M = 900;
    /**
     * No dust thinner than this vertical optical depth in the table (95 % sun transmission): sets table radius and when pall
     * has settled ({@link #dustEnd}).
     */
    static final double GONE_DEPTH = 0.05;
    /** Spray and base surge below this density fraction are not rendered. */
    static final double GONE = 0.0015;
    /** Haze rises over fires from this many seconds after the front to this many. */
    static final double HAZE_FROM_S = 15, HAZE_FULL_S = 150;
    /** Haze lasts at least this many game ticks (5 minutes), fading over the final minute. */
    static final long HAZE_MIN_TICKS = 6000, HAZE_FADE_TICKS = 1200;
    /** Ticks per game day; morning (6:00) is a multiple of this time of day, daytime is its first half. */
    static final long DAY = 24000;
    /**
     * Time of day advances beyond game time by more than this (sleep, {@code /time add}) — a jump. Server corrections
     * ({@code ClientNuclear.ClockLead}: when lagging, it moves the client clock back by hundreds of ticks) move both
     * together and do not count as jumps.
     */
    static final long SKIP_TICKS = 100;
    /** Backward by more than this ({@code /time set day}). */
    static final long SET_TICKS = 1200;
    /** Dust drifts at a fraction of detonation wind: wind is weaker near ground, heavy material settles. */
    static final double DRIFT = 0.5;
    /** Maximum downwind haze-plume length, in fire-zone radii. */
    static final double PLUME_MAX = 2;
    /** Haze height over fires, m (at 15 kt): smoke rises above dust. */
    static final double HAZE_TOP_M = 260;
    /**
     * Full ash rate when dust settles at this rate, kg/(m²·s) (half a gram per m² per second: coarse material in the first
     * minutes, {@link DustModel.Column#fallout}).
     */
    static final double ASH_FULL = 5e-4;

    /**
     * Visibility in the densest spray behind the front over water, m ({@link WaterModel#spray} = 1, wind above 45 m/s): like a
     * hurricane with air full of foam and spray (Beaufort scale, force 12: "visibility very poor").
     */
    static final double SPRAY_VISIBILITY_M = 60;
    /**
     * Spray behind the front thins e-fold over this many seconds: large foam drops (hundreds of microns, falling at fractions of
     * a meter to a meter per second from a layer tens of meters high) settle in tens of seconds, fine drops evaporate — clear above
     * water after two to three minutes ({@link #GONE}).
     */
    static final double SPRAY_S = 30;
    /**
     * Spray top falls to this fraction of stripping height while large drops settle (over {@link #SPRAY_S}): fine mist
     * remains lower down (est.) (drops hundreds of microns across fall a meter per second; fine drops, centimeters).
     */
    static final double SPRAY_SINK = 0.6;
    /**
     * Visibility in the densest base surge, m ({@link WaterModel#surgeDensity} = 1): fog of fallen water-column drops,
     * a solid white wall at Baker (Glasstone &amp; Dolan, §2.47–2.65; (est.), like the fractions in {@link WaterModel}).
     */
    static final double SURGE_VISIBILITY_M = 50;

    /** Light extinction in the former dense pall dust (density measure) and in haze, 1/m. */
    static final double SIGMA_DENSE = Sight.KOSCHMIEDER / DENSE_VISIBILITY_M, SIGMA_HAZE = Sight.KOSCHMIEDER / HAZE_VISIBILITY_M;
    /** Extinction in the densest spray and base surge, 1/m. */
    static final double SIGMA_SPRAY = Sight.KOSCHMIEDER / SPRAY_VISIBILITY_M, SIGMA_SURGE = Sight.KOSCHMIEDER / SURGE_VISIBILITY_M;

    /** Water fraction below a point 0..1 (dx, dz — ground blocks from ground zero): {@link PallGround} map. */
    @FunctionalInterface
    public interface Water {
        /** Land everywhere beneath pall. */
        Water DRY = (dx, dz) -> 0;

        double at(double dx, double dz);
    }

    private final Detonation d;
    /** Ground table reach (dust thinner than {@link #GONE_DEPTH} and fire zone ends), blocks. */
    private final double reach;
    /** Ground fire-zone radius, blocks (0 — no fires). */
    private final double fireReach;
    /** Dust layer per node ({@link DustModel}). */
    private final DustModel.Column[] columns = new DustModel.Column[BINS];
    /**
     * Per node: front arrival (ticks), destruction 0..1 (paper), eventual dust top (blocks; haze beneath it),
     * fire-zone share 0..1, water-spray stripping 0..1 and stripping height (blocks).
     */
    private final double[] arrival = new double[BINS], destroyed = new double[BINS], ceiling = new double[BINS],
            fire = new double[BINS], spray = new double[BINS], sprayFrom = new double[BINS];
    /**
     * Water beneath ground zero 0..1 ({@code NukeWater.under}: chunk, map tile or sea-level guess), and how much of a
     * "water burst" it is ({@link WaterModel#wetBurst} × that fraction), written by client tick ({@link #water}); land until resolved.
     */
    private double under, wet;
    /** When dust has settled everywhere (optical depth below {@link #GONE_DEPTH}), ticks since detonation. */
    private final double dustEnd;
    /** When haze fades by elapsed ticks — ticks since detonation (morning if world clocks run steadily). */
    private final long hazeEnd;
    /**
     * When haze actually fades, ticks since detonation: no later than {@link #hazeEnd} or morning by world time
     * ({@link #daylight}). Written by client tick, also read by frame — same thread.
     */
    private double end;
    /** World clocks at detonation: game time and time of day. */
    private final long burstGame, burstDay;
    /** Previous world-clock sample ({@link #daylight}): game time and time of day; before the first — detonation time. */
    private long lastGame, lastDay;
    /** Previous sample was one tick ago while the client watched, not detonation time (world entry, replay seek). */
    private boolean watched;

    /**
     * @param dayTime world time of day at detonation (ticks, as in {@code Level.getDayTime}): haze lasts until morning
     */
    public PallField(Detonation d, long dayTime) {
        this.d = d;
        this.hazeEnd = hazeEndTicks(dayTime);
        this.end = hazeEnd;
        this.burstGame = d.gameTime();
        this.burstDay = dayTime;
        this.lastGame = burstGame;
        this.lastDay = burstDay;
        double hob = Math.max(0, d.hobMetres()), y = d.yieldKt();
        DustModel.Surface surface = DustModel.Surface.REFERENCE;
        double dustM = DustModel.reach(hob, y, d.surface(), d.visibility(), surface, GONE_DEPTH);
        // server does not ignite (Detonation.fires) — no fire zone: no haze above it, fire glow or storm
        double ignite = !d.fires() ? 0 : groundRange(ThermalModel.rangeForFluence(ThermalModel.IGNITE, y, d.surface(), d.visibility()), hob);
        this.fireReach = d.blocks(ignite);
        this.reach = Math.max(1, d.blocks(Math.max(dustM, ignite)) * 1.02);
        double last = 0;
        for (int i = 0; i < BINS; i++) {
            double rm = d.metres(radius(i)), slant = Math.hypot(rm, hob);
            arrival[i] = d.arrivalTicks(d.blocks(slant));
            double wind = d.groundWind(radius(i));
            destroyed[i] = DustModel.destroyed(BlastModel.psi(d.groundOverpressureKpa(radius(i))));
            spray[i] = WaterModel.spray(wind);
            sprayFrom[i] = Math.max(1, d.blocks(WaterModel.sprayHeight(wind)));
            columns[i] = DustModel.column(rm, hob, y, d.surface(), d.visibility(), surface);
            ceiling[i] = Math.max(MIN_TOP_BLOCKS, d.blocks(columns[i].ceiling()));
            fire[i] = ignite <= 0 ? 0 : 1 - smooth(ignite * 0.85, ignite * 1.05, rm);
            last = Math.max(last, arrival[i] + 20 * d.scale() * settledBy(columns[i]));
        }
        this.dustEnd = last;
    }

    /** Minimum pall height in blocks (small strike scale): otherwise invisible. */
    static final double MIN_TOP_BLOCKS = 6;

    private static double groundRange(double slant, double hob) {
        return Math.sqrt(Math.max(0, slant * slant - hob * hob));
    }

    /**
     * Model seconds after the front until the layer is thinner than {@link #GONE_DEPTH} (optical depth only decreases after wave phase), to the
     * nearest second: the frame thread builds pall at detonation.
     */
    private static double settledBy(DustModel.Column c) {
        double positive = c.positive();
        if (c.depth(positive) < GONE_DEPTH) return positive;
        double lo = positive, hi = 6 * 3600;
        if (c.depth(hi) >= GONE_DEPTH) return hi;
        while (hi - lo > 1) {
            double m = 0.5 * (lo + hi);
            if (c.depth(m) >= GONE_DEPTH) lo = m;
            else hi = m;
        }
        return hi;
    }

    /** Field detonation. */
    public Detonation detonation() {
        return d;
    }

    /** Ground reach from ground zero (before wind drift) of dust or haze, blocks. */
    public double reach() {
        return reach;
    }

    /** Ground ignition-zone radius, blocks (0 — no fires): haze above it, firestorm inside ({@link FireStorm}). */
    double fireReach() {
        return fireReach;
    }

    /** When haze fades (morning), ticks since detonation; the firestorm fades by then too. */
    double end() {
        return end;
    }

    /** Haze top over fires, blocks (260 m at 15 kt): storm smoke columns rise to it. */
    double hazeTop() {
        return d.blocks(HAZE_TOP_M * Math.cbrt(d.yieldKt() / 15));
    }

    /**
     * Water beneath ground zero resolved ({@code NukeWater}): {@code under} — water fraction 0..1 (also used by {@link PallGround}
     * where its map knows nothing); {@code wet} — how much of a "water burst" it is ({@link WaterModel#wetBurst} × that fraction), which drives
     * the base surge.
     */
    void water(double under, double wet) {
        this.under = under;
        this.wet = wet;
    }

    /** Water beneath ground zero 0..1 ({@link #water}). */
    double under() {
        return under;
    }

    /** Node i radius, blocks (node centers from 0 to {@link #reach}). */
    double radius(int i) {
        return (i + 0.5) / BINS * reach;
    }

    /** Node i dust layer ({@link DustModel}). */
    DustModel.Column layer(int i) {
        return columns[i];
    }

    /**
     * Client tick: world clocks — game time {@code gameTime}, time of day {@code dayTime}; {@code ticks} — ticks since
     * detonation. While clocks run steadily, haze waits for the morning chosen at detonation ({@link #hazeEndTicks}: near-dawn detonation waits
     * until the next). A jump (sleep, {@code /time add}, {@code /time set}) recalculates morning from time of day after the jump:
     * already daytime after night, or clocks crossed sunrise — haze fades; otherwise next sunrise, but no earlier than
     * 5 minutes after detonation ({@link #HAZE_MIN_TICKS}). Haze never fades later than expected before the jump. Stopped clocks
     * ({@code doDaylightCycle false}) use elapsed ticks.
     * <p>
     * A jump is a time-of-day shift relative to game time: both arrive in one time packet and advance together through client
     * ticks. The first sample after world entry or replay seek is compared with detonation time (time of day at detonation comes
     * from the server, {@link Detonation#dayTime}): an unseen jump acts the same as for everyone who saw it, but without the
     * fade minute (it happened earlier). Except {@code /time set} backward to a time of day later than detonation: for a joiner it
     * lags game time like stopped clocks, indistinguishable — their haze waits for morning from detonation, longer than
     * for those who saw the jump.
     */
    public void daylight(double ticks, long gameTime, long dayTime) {
        long shift = (dayTime - gameTime) - (lastDay - lastGame);
        // backward only if time of day also moved backward: with stopped clocks it lags game time without jumping
        if (shift > SKIP_TICKS || shift < -SET_TICKS && dayTime - lastDay < -SET_TICKS) {
            // by time of day rather than clock count: /time set assigns them directly, and in a world beyond its first day
            // "backward" can mean a later night than before (85000 → 18000 — evening to midnight)
            boolean dawn = day(dayTime) && (!day(lastDay) || Math.floorDiv(dayTime, DAY) > Math.floorDiv(lastDay, DAY));
            double by = dawn ? ticks : Math.max(ticks + Math.floorMod(-dayTime, DAY), HAZE_MIN_TICKS);
            // when witnessed, an earlier end must not jump: fade over at least a minute (if fading already, keep the fade)
            if (by < end) end = Math.min(end, watched ? Math.max(by, ticks + HAZE_FADE_TICKS) : by);
        }
        lastGame = gameTime;
        lastDay = dayTime;
        watched = true;
    }

    /** Daytime is the first half of a day (6:00 to 18:00). */
    private static boolean day(long dayTime) {
        return Math.floorMod(dayTime, DAY) < DAY / 2;
    }

    /**
     * Replay seek: world clocks come from another recording position; recalculate morning from detonation time and compare the first sample
     * against it (a clock jump before this recording position behaves as for a world joiner).
     */
    public void rewound() {
        end = hazeEnd;
        lastGame = burstGame;
        lastDay = burstDay;
        watched = false;
    }

    // ---------------------------------------------------------------- laws (shared with fx_pall shader)

    /**
     * Vertical profile: fraction of ground density at pall height {@code x} above ground zero (0 — ground; 1 — top):
     * denser near ground, softly fading toward the top. Below (valley), same as ground; below 0.4 pall heights it fades to zero (absent one full
     * height below ground): dust flows into valleys but not caves below ground zero. Where there is no world behind a pixel
     * (empty space beyond render distance and Distant Horizons LOD edge), the pall ray ends at underlying terrain
     * ({@link PallGround}, {@code pallFloor} in {@code pall.glsl}). Also the wall-head profile ({@link BlastWall#mean}).
     */
    static double profile(double x) {
        if (x <= 0) return smooth(-1, -0.4, x);
        return (1 - smooth(0.45, 1.0, x)) * (0.55 + 0.45 * Math.exp(-2.5 * x));
    }

    /**
     * Column above a point at pall height {@code x} — integral of {@link #profile} from x to top, in height fractions: used by the shader
     * for shadow and sky light inside pall. Approximation (test checks against its integral: error below 0.01).
     */
    static double column(double x) {
        double xa = Math.min(1, Math.max(0, x));
        double above = 0.548 * Math.pow(1 - xa, 2.45) * (1 + 0.83 * xa);
        if (x >= 0) return above;
        // below ground: profile 1 down to −0.4, then fades toward −1 (exact smoothstep integral)
        double f = Math.max(0, Math.min(1, (-x - 0.4) / 0.6));
        return above + Math.min(-x, 0.4) + 0.6 * (f - f * f * f + 0.5 * f * f * f * f);
    }

    /**
     * Spray density by age (model seconds after the front): rises behind the spray wall lagging the front by
     * {@link WaterModel#SPRAY_LAG}, then thins e-fold over {@link #SPRAY_S}; 0 before that and after {@link #GONE}.
     */
    static double sprayEnvelope(double ageS) {
        if (ageS <= WaterModel.SPRAY_LAG) return 0;
        double e = smooth(WaterModel.SPRAY_LAG, WaterModel.SPRAY_LAG + 2.5, ageS) * Math.exp(-ageS / SPRAY_S);
        return e < GONE ? 0 : e;
    }

    /**
     * Spray top by age (model seconds), in units of stripping height {@code from}: torn crests rise to stripping
     * height ({@link WaterModel#sprayHeight}); large drops settle, lowering the top to {@link #SPRAY_SINK} of it. Its own
     * height, not the dust layer's: spray is lower than dust and does not rise over heated ground.
     */
    static double sprayTop(double from, double ageS) {
        return from * (SPRAY_SINK + (1 - SPRAY_SINK) * Math.exp(-Math.max(ageS, 0) / SPRAY_S));
    }

    /**
     * Base surge by radius ({@code u} — fraction of its radius): denser roller at the edge, thinner toward the center, fading beyond the edge
     * (like its billows: a ring from half-radius to edge, denser at the edge). Same as {@code pallSurge} in {@code pall.glsl}.
     */
    static double surgeProfile(double u) {
        return (0.35 + 0.65 * smooth(0.2, 0.85, u)) * (1 - smooth(0.9, 1.05, u));
    }

    /** Base-surge top by radius, fraction of its height: roller higher at edge, lower toward center ({@code pallSurgeTop}). */
    static double surgeTop(double u) {
        return 0.55 + 0.45 * smooth(0.3, 0.95, u);
    }

    /** Haze over time: rises over fires within a couple of minutes after the front, fades toward morning ({@link #end}). */
    double hazeEnvelope(double ageS, double ticks) {
        if (ageS <= HAZE_FROM_S) return 0;
        return smooth(HAZE_FROM_S, HAZE_FULL_S, ageS) * (1 - smooth(end - HAZE_FADE_TICKS, end, ticks));
    }

    /**
     * Game ticks after detonation until morning (time of day 0 = 6:00), but at least {@link #HAZE_MIN_TICKS}: the city
     * burns until morning; near-dawn detonation until the next.
     */
    static long hazeEndTicks(long dayTime) {
        long toMorning = Math.floorMod(-dayTime, 24000L);
        while (toMorning < HAZE_MIN_TICKS) toMorning += 24000;
        return toMorning;
    }

    // ---------------------------------------------------------------- shader table

    /** Extinction 1/m → 1/block (one block = 1/scale meter). */
    private double perBlock(double sigmaM) {
        return sigmaM / d.scale();
    }

    /** Node i real seconds at {@code ticks} since detonation (client ticks): from front arrival, for haze and fires. */
    private double age(int i, double ticks) {
        return (ticks - arrival[i]) / 20.0;
    }

    /** Node i model seconds at {@code ticks}: from game-front arrival, dust and spray age like wall head. */
    private double seconds(int i, double ticks) {
        return (ticks - arrival[i]) / (20.0 * d.scale());
    }

    /** Table rows per detonation: dust, spray, haze, in order. */
    public static final int ROWS = 3;
    static final int DUST_ROW = 0, SPRAY_ROW = 1, HAZE_ROW = 2;

    /**
     * Table row at {@code ticks} since detonation (client ticks): 12 numbers per {@link #BINS} nodes — dust
     * ({@link #dust}), spray ({@link #spray}) and haze ({@link #haze}). Writes into {@code out} (length ≥ 12·BINS): all dust
     * nodes first, then spray, then haze. {@code land}, {@code water} indicate land and water beneath pall
     * ({@link PallGround}): absent material is also absent from the table — dust and haze over open sea, spray over land (the shader would
     * remove them by map anyway, but the slot is empty without them, so a sea burst spends no passes on invisible dust, and
     * over land the shader skips the water map).
     */
    public void row(double ticks, boolean land, boolean water, float[] out) {
        double[] v = new double[4];
        for (int i = 0; i < BINS; i++) {
            dust(i, ticks, v);
            if (!land) v[0] = 0;
            for (int k = 0; k < 4; k++) out[4 * (DUST_ROW * BINS + i) + k] = (float) v[k];
            spray(i, ticks, v);
            if (!water) v[0] = 0;
            for (int k = 0; k < 4; k++) out[4 * (SPRAY_ROW * BINS + i) + k] = (float) v[k];
            haze(i, ticks, v);
            // no haze or underlying fires over open sea (dust drift share is for spray)
            if (!land) v[0] = v[2] = 0;
            for (int k = 0; k < 4; k++) out[4 * (HAZE_ROW * BINS + i) + k] = (float) v[k];
        }
    }

    /**
     * Share of node i dust in the table (pall) versus wall head at {@code ticks}: dust younger than the local positive
     * phase is head; hand over to pall over half a phase ({@link BlastWall#handoff}). No dust where the fireball touches ground
     * ({@link FireballModel#groundRadius}, as for head — {@link BlastWall#underBall}): the ball is there; pall appears
     * from beneath as it rises.
     */
    private double pallShare(int i, double ticks) {
        double t = ticks / (20.0 * d.scale());
        if (d.metres(radius(i)) < FireballModel.groundRadius(t, Math.max(0, d.hobMetres()), d.yieldKt(), d.surface())) return 0;
        return 1 - BlastWall.handoff(seconds(i, ticks) / columns[i].positive());
    }

    /**
     * Node i dust: ground extinction (1/block) is layer optical depth ({@link DustModel.Column#depth}) divided by its column (0.548 of top),
     * excluding dust still in the wall head; layer top (blocks); smoke fraction 0..1 (soot darkens dust over fires:
     * fires develop over minutes — pall is dust-brown for the first half-minute, gray over fire by minute five); degree of layer
     * mixing ({@link #mixed}).
     */
    void dust(int i, double ticks, double[] out) {
        double age = seconds(i, ticks), depth = columns[i].depth(age);
        out[1] = Math.max(d.blocks(columns[i].height(age)), MIN_TOP_BLOCKS);
        // profile column is column(0) of top: vertical optical depth exactly matches the layer, even at minimum top
        out[0] = depth < GONE_DEPTH ? 0 : depth / (column(0) * out[1]) * pallShare(i, ticks);
        out[2] = fire[i] * smooth(30, 300, age(i, ticks));
        out[3] = mixed(age - columns[i].positive());
    }

    /**
     * Layer mixing {@code afterS} model seconds after wave phase, 0..1: patches and gaps in the young layer
     * (torn by boundary-layer eddies near the front) disperse over a layer-eddy turnover h/w*. The layer rises over heated ground at
     * speed w* over {@link DustModel#RISE_S} to height h — this is the eddy turnover time (est.) (w* = 1.5–4 m/s,
     * h = 70–400 m for 15 kt, review §5), e-folding.
     */
    static double mixed(double afterS) {
        return afterS <= 0 ? 0 : 1 - Math.exp(-afterS / DustModel.RISE_S);
    }

    /**
     * Pall billows in the mixed layer: density multiplier from noise {@code shape} (mean 1); fluctuations decay from
     * patches toward {@link #MIXED} according to {@link #mixed}; mean stays 1. Copy — {@code pallMixed}.
     */
    static double clumps(double shape, double mixed) {
        return 1 + (shape - 1) * (1 - (1 - MIXED) * mixed);
    }

    /**
     * Node i spray over water: water-level extinction (1/block; where water lies beneath pall, excluding spray still in wall head),
     * spray top (blocks, {@link #sprayTop}); 0, 0. Separate layer: spray does not mix with dust and is lower.
     */
    void spray(int i, double ticks, double[] out) {
        double age = seconds(i, ticks);
        out[0] = spray[i] <= 0 ? 0 : perBlock(SIGMA_SPRAY) * spray[i] * sprayEnvelope(age) * pallShare(i, ticks);
        out[1] = Math.max(sprayTop(sprayFrom[i], age), 1);
        out[2] = out[3] = 0;
    }

    /**
     * Base surge at {@code ticks} since detonation: into {@code out}, radius and top (blocks), extinction in its
     * densest part (1/block; radial profile — {@link #surgeProfile}, top — {@link #surgeTop}). Same laws and clock as the
     * column it falls from (model seconds, {@link WaterModel}); density × degree of "water burst"; absent below
     * {@link #GONE}. Underlying surface does not matter: water lofted at ground zero. False — no surge ({@code out}
     * zeroed).
     */
    public boolean surge(double ticks, double[] out) {
        out[0] = out[1] = out[2] = 0;
        if (wet <= 0 || ticks <= 0) return false;
        double t = ticks / (20 * d.scale()), y = d.yieldKt();
        double a = WaterModel.surgeDensity(t, y) * wet, r = WaterModel.surgeRadius(t, y);
        if (a < GONE || r <= 0) return false;
        out[0] = d.blocks(r);
        out[1] = Math.max(MIN_TOP_BLOCKS, d.blocks(WaterModel.surgeHeight(t, y)));
        out[2] = perBlock(SIGMA_SURGE) * a;
        return true;
    }

    /**
     * Fraction of {@link #drift} for node i dust at {@code ticks}: dust age divided by time since detonation — wind carries it
     * from front arrival. At the front, 0: newly lofted dust stays where it was lofted — pall edge and dust-wall head
     * follow the server front (sound, shake, knockback, debris) in every direction from ground zero, including downwind.
     */
    double share(int i, double ticks) {
        return ticks <= 0 ? 0 : Math.max(0, Math.min(1, (ticks - arrival[i]) / ticks));
    }

    /**
     * Source of dust and spray at point (dx, dz) from ground zero (blocks) at {@code ticks}: point minus their
     * drift ({@link #share} uses the point's own node: fraction varies over the front travel distance; drift is orders of magnitude shorter).
     * {@code out} receives x, z. Same as {@code pallField} in {@code pall.glsl}.
     */
    public void source(double dx, double dz, double ticks, double[] out) {
        drift(ticks, out);
        double k = share(bin(Math.hypot(dx, dz)), ticks);
        out[0] = dx - out[0] * k;
        out[1] = dz - out[1] * k;
    }

    /**
     * Node i haze (by distance from plume axis): extinction (1/block), height (blocks; no lower than eventual dust
     * top), fire light from below 0..1; fourth value — node i dust drift fraction ({@link #share}: shader reads the haze
     * row at the point before drift).
     */
    void haze(int i, double ticks, double[] out) {
        double age = age(i, ticks);
        out[0] = perBlock(SIGMA_HAZE) * fire[i] * hazeEnvelope(age, ticks);
        out[1] = Math.max(ceiling[i], hazeTop() * (0.5 + 0.5 * smooth(HAZE_FROM_S, HAZE_FULL_S, age)));
        out[2] = fire[i] * fireLight(age, ticks);
        out[3] = share(i, ticks);
    }

    /** Fire light from below 0..1: develops over a minute after the wave, fades toward morning. */
    double fireLight(double ageS, double ticks) {
        if (ageS <= 0) return 0;
        return smooth(5, 60, ageS) * (1 - smooth(end - HAZE_FADE_TICKS, end, ticks));
    }

    /** Fire light beneath mushroom 0..1 (at ground zero; {@link #fireLight}): 0 if no fire zone. */
    double fireBelow(double ticks) {
        return fireReach > 0 ? fireLight(age(0, ticks), ticks) : 0;
    }

    /**
     * Whether anything remains of pall at {@code ticks}: haze (only above fires), dust while the front crosses the
     * table and until settled everywhere (spray settles before dust), base surge.
     */
    public boolean visible(double ticks) {
        return ticks > 0 && (fireReach > 0 && ticks < end || ticks <= arrival[BINS - 1] || ticks < dustEnd || surge(ticks, new double[3]));
    }

    /**
     * Wind drift at {@code ticks} of material lofted at detonation, blocks (x, z), into {@code out}: full for base
     * surge, age fraction for dust and spray ({@link #share}).
     */
    public void drift(double ticks, double[] out) {
        double s = d.blocks(d.windSpeed()) * DRIFT * ticks / 20.0;
        out[0] = Math.cos(d.windDir()) * s;
        out[1] = Math.sin(d.windDir()) * s;
    }

    /**
     * Maximum ground reach from ground zero of pall at {@code ticks}, blocks: table (or base surge if
     * wider), dust drift (until dust settles or while surge rolls — nothing to drift afterward), and haze plume in any direction.
     * Slot bounds ({@link NukePall}) and terrain map beneath pall ({@link PallGround}).
     */
    public double extent(double ticks) {
        double[] s = new double[3];
        boolean surge = surge(ticks, s);
        return atSource(ticks, surge, s) + d.blocks(d.windSpeed()) * DRIFT * (surge ? ticks : Math.min(ticks, dustEnd)) / 20.0;
    }

    /**
     * {@link #extent} without drift: table (or base surge if wider) and haze plume — pall at its own source, where
     * it was lofted. Terrain map beneath pall ({@link PallGround}) covers this and the camera, not the full drift.
     */
    public double sourceExtent(double ticks) {
        double[] s = new double[3];
        return atSource(ticks, surge(ticks, s), s);
    }

    private double atSource(double ticks, boolean surge, double[] s) {
        return (surge ? Math.max(reach, 1.05 * s[0]) : reach) + plume(ticks);
    }

    /** Downwind haze-plume length at {@code ticks}, blocks: grows with wind, capped at {@link #PLUME_MAX} fire zones. */
    public double plume(double ticks) {
        return Math.min(PLUME_MAX * fireReach, d.blocks(d.windSpeed()) * ticks / 20.0);
    }

    /** Haze thins along the plume: halved at the end of length {@code len} ({@code along} — downwind from ground zero). */
    static double plumeThin(double along, double len) {
        return 1 - 0.5 * along / Math.max(len, 1);
    }

    /** Table edge (u — fraction of {@link #reach}): field fades toward it, zero beyond. */
    static double edge(double u) {
        return 1 - smooth(0.96, 1, u);
    }

    /**
     * Extinction at a point without noise (noise mean = 1), 1/block: dust and spray (wind-drifted since
     * lofting, {@link #source}; material from water where lofted), haze (downwind plume; no fires over water at its source) and base surge — same as
     * {@code pallField} in {@code pall.glsl} from the table. Point is ground distance from ground zero (dx, dz, blocks), height above ground
     * zero (blocks); {@code floorUp} — underlying terrain above ground zero (blocks, {@link PallGround} map):
     * above hills, layer starts at their slope; in valleys, at ground zero ({@link #profile}); {@code water} — water fraction beneath
     * pall ({@link PallGround}). Excludes wall head ({@link BlastWall#sigma}). For camera effects: sound, ash,
     * pall-ray reach.
     */
    public double sigma(double dx, double dz, double above, double floorUp, double ticks, Water water) {
        double[] l = new double[LAYERS];
        layers(dx, dz, ticks, water, l);
        double h = above - Math.max(floorUp, 0);
        return l[0] * profile(h / l[2]) + l[1] * profile(h / l[7]) + l[3] * profile(h / l[4]) + l[5] * profile(h / l[6]);
    }

    /** Mist component of {@link #sigma}: spray and base surge, 1/block. */
    public double mist(double dx, double dz, double above, double floorUp, double ticks, Water water) {
        double[] l = new double[LAYERS];
        layers(dx, dz, ticks, water, l);
        double h = above - Math.max(floorUp, 0);
        return l[1] * profile(h / l[7]) + l[5] * profile(h / l[6]);
    }

    /**
     * Vertical pall optical depth above point (dx, dz), from {@code from} to {@code to} (blocks above ground zero,
     * {@code to} may be infinite): height integral of {@link #sigma}. Dust above eye and below it down to ground sets
     * pall-ray reach ({@link NukePall}).
     */
    public double tau(double dx, double dz, double from, double to, double floorUp, double ticks, Water water) {
        if (!(to > from)) return 0;
        double[] l = new double[LAYERS];
        layers(dx, dz, ticks, water, l);
        double lift = Math.max(floorUp, 0), a = from - lift, b = to - lift;
        return l[0] * between(a, b, l[2]) + l[1] * between(a, b, l[7]) + l[3] * between(a, b, l[4]) + l[5] * between(a, b, l[6]);
    }

    /**
     * Wave dust-layer vertical optical depth above (dx, dz), ground to top — dust and spray, without fire haze or base surge:
     * what blankets the ground ({@code MushroomField.groundUnder}).
     */
    public double dustTau(double dx, double dz, double ticks, Water water) {
        double[] l = new double[LAYERS];
        layers(dx, dz, ticks, water, l);
        double inf = Double.POSITIVE_INFINITY;
        return l[0] * between(0, inf, l[2]) + l[1] * between(0, inf, l[7]);
    }

    /** {@link #layers} parameters for {@link #footLayer} (frame runs on render thread): no new array per mushroom per frame. */
    private final double[] footScratch = new double[LAYERS];

    /**
     * Wave dust layer at the stem base, {@code r} blocks from ground zero, averaged over four sides (wind drift
     * shifts the layer): ground dust and spray extinction, 1/block ({@code out[0]}; at height × {@link #profile}), and their top,
     * blocks ({@code out[1]}; weighted by extinction fractions). No layer — 0 and 1. Inflow turns it into the stem
     * ({@code MushroomField.foot}).
     */
    public void footLayer(double r, double ticks, Water water, double[] out) {
        double[] l = footScratch;
        double sigma = 0, top = 0;
        for (int k = 0; k < 4; k++) {
            layers(k == 0 ? r : k == 1 ? -r : 0, k == 2 ? r : k == 3 ? -r : 0, ticks, water, l);
            sigma += l[0] + l[1];
            top += l[0] * l[2] + l[1] * l[7];
        }
        out[0] = sigma / 4;
        out[1] = sigma > 0 ? top / sigma : 1;
    }

    /**
     * Height integral of {@link #profile} from a to b (blocks) for a layer of height top (blocks): Simpson separately below and above
     * ground (a kink there), more accurate than {@link #column} approximation (error of tens of percent for a thin layer below eye).
     */
    private static double between(double a, double b, double top) {
        double lo = Math.max(a / top, -1), hi = Math.min(b / top, 1);
        return top * (simpson(lo, Math.min(hi, 0)) + simpson(Math.max(lo, 0), hi));
    }

    private static double simpson(double lo, double hi) {
        if (!(hi > lo)) return 0;
        int n = 32;
        double h = (hi - lo) / n, sum = profile(lo) + profile(hi);
        for (int k = 1; k < n; k++) sum += (k % 2 == 1 ? 4 : 2) * profile(lo + k * h);
        return sum * h / 3;
    }

    /** Number of values in {@link #layers}. */
    private static final int LAYERS = 8;

    /**
     * Pall layers over point (dx, dz) — ground extinction (1/block; at height × {@link #profile} of top fraction) and top
     * (blocks), into {@code out}: dust, spray, dust top, haze, haze top, base surge, surge top, spray top. Absent layer:
     * extinction 0, top 1.
     */
    private void layers(double dx, double dz, double ticks, Water water, double[] out) {
        out[0] = out[1] = out[3] = out[5] = 0;
        out[2] = out[4] = out[6] = out[7] = 1;
        double[] w = new double[2], v = new double[4];
        source(dx, dz, ticks, w);
        double lx = w[0], lz = w[1], ud = Math.hypot(lx, lz) / reach;
        if (ud < 1) {
            int i = bin(ud * reach);
            dust(i, ticks, v);
            double sd = v[0], topD = v[1];
            spray(i, ticks, v);
            double e = edge(ud), wet = sd + v[0] > 0 ? water.at(lx, lz) : 0;
            out[0] = sd * (1 - wet) * e;
            out[1] = v[0] * wet * e;
            out[2] = Math.max(topD, 1);
            out[7] = Math.max(v[1], 1);
        }
        double len = plume(ticks), ux = Math.cos(d.windDir()), uz = Math.sin(d.windDir());
        double along = Math.max(0, Math.min(len, dx * ux + dz * uz));
        double sx = dx - ux * along, sz = dz - uz * along, uh = Math.hypot(sx, sz) / reach;
        if (uh < 1) {
            haze(bin(uh * reach), ticks, v);
            if (v[0] > 0) {
                out[3] = v[0] * (1 - water.at(sx, sz)) * plumeThin(along, len) * edge(uh);
                out[4] = Math.max(v[1], 1);
            }
        }
        double[] s = new double[3];
        if (surge(ticks, s)) {
            drift(ticks, w);
            double u = Math.hypot(dx - w[0], dz - w[1]) / s[0];
            out[5] = s[2] * surgeProfile(u);
            out[6] = Math.max(s[1] * surgeTop(u), 1);
        }
    }

    /**
     * Ash falling to ground at a point, 0..1 ({@code NukeAsh}): from settling dust, proportional to what settles out
     * ({@link DustModel.Column#fallout}, full strength at {@link #ASH_FULL}), from 15–60 s after the wave; from fire haze
     * while the city burns (sparser along plume). No ash from spray or fog over water: material follows water where it was
     * lofted ({@code water}, as in {@link #sigma}).
     */
    public double ashRate(double dx, double dz, double ticks, Water water) {
        double[] w = new double[2];
        source(dx, dz, ticks, w);
        double ash = 0;
        double lx = w[0], lz = w[1], ud = Math.hypot(lx, lz) / reach;
        if (ud < 1) {
            int i = bin(ud * reach);
            double age = seconds(i, ticks);
            if (columns[i].depth(age) >= GONE_DEPTH) {
                ash = Math.min(1, columns[i].fallout(age) / ASH_FULL) * smooth(15, 60, age(i, ticks)) * edge(ud);
                if (ash > 0) ash *= 1 - water.at(lx, lz);
            }
        }
        double len = plume(ticks), ux = Math.cos(d.windDir()), uz = Math.sin(d.windDir());
        double along = Math.max(0, Math.min(len, dx * ux + dz * uz));
        double sx = dx - ux * along, sz = dz - uz * along, uh = Math.hypot(sx, sz) / reach;
        if (uh < 1) {
            int i = bin(uh * reach);
            double haze = 0.8 * fire[i] * hazeEnvelope(age(i, ticks), ticks) * plumeThin(along, len) * edge(uh);
            if (haze > ash) ash = Math.max(ash, haze * (1 - water.at(sx, sz)));
        }
        return Math.min(1, ash);
    }

    /**
     * Paper-scrap fraction in ash 0..1: where the wave collapsed houses (destruction from 0.3), in its first minutes (paper falls
     * near houses, without drift); no houses on water — water at the same point ({@code water}).
     */
    public double paperRate(double dx, double dz, double ticks, Water water) {
        double r = Math.hypot(dx, dz);
        if (r >= reach) return 0;
        int i = bin(r);
        double age = age(i, ticks);
        double paper = smooth(0.3, 0.7, destroyed[i]) * smooth(5, 20, age) * (1 - smooth(120, 300, age));
        return paper <= 0 ? 0 : paper * (1 - water.at(dx, dz));
    }

    private int bin(double r) {
        return Math.min(BINS - 1, Math.max(0, (int) (r / reach * BINS)));
    }

    static double smooth(double e0, double e1, double x) {
        double f = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return f * f * (3 - 2 * f);
    }
}
