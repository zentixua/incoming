package ua.zentix.incoming.client.nuclear;

import java.util.function.DoubleUnaryOperator;

import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

/**
 * Pall after the shock wave ("cinema-level nuke" plan, step 2): dust, spray and smoke density field of one detonation by radius
 * from ground zero and time. The ground dust and spray are drawn by the dust pass ({@code fx/layer/DustWallPass}); the pall volume
 * ({@code fx/layer/PallPass}) draws only the fire-zone haze row of {@link #row}. The whole field also drives camera visibility, sound,
 * ash and shadow ({@link NukePall}). Pure math from the detonation
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
 * <b>Haze.</b> The city burns over the ignition zone (light pulse from {@link ThermalModel#ignite}): smoky haze
 * (visibility {@link #HAZE_VISIBILITY_M} m) is the fires' smoke: it grows, holds and burns out with them ({@link FireStorm#smoke} on
 * the detonation's clock, {@link #clock}: hours of the world's day after the show), trails downwind, lit by fires from below.
 * <p>
 * <b>Water</b> (plan, step 6). No dust over water: wind behind the front tears spray from crests ({@link WaterModel#spray}) —
 * a separate layer with its own height (crest stripping height, {@link WaterModel#sprayHeight}; large drops fall out, lowering
 * the top, {@link #sprayTop}) and density (drops: white, almost nonabsorbing, forward-scattering, settling and evaporating over a couple of
 * minutes, {@link #SPRAY_S}). A burst on water also produces a base surge — fog from the fallen water column
 * ({@link #surge}, {@link WaterModel} laws and clock, the same as the column in the mushroom volume). The {@link PallGround} map knows whether land or
 * water is beneath the pall (cell water fraction from client knowledge); material follows the place where
 * it was lofted: dust drifted onto water stays dust (point minus drift); haze at its source (no fires over water).
 * <p>
 * Time: dust and spray use the model clock (ticks / (20·scale)), like front and head; paper falls in real seconds (client ticks
 * / 20); haze and fires use the detonation's clock ({@link #clock}). Lengths in model meters and blocks through detonation scale. The radius table
 * ({@link #row}) is what the pall shader receives: all expensive work is done here.
 */
public final class PallField {
    /**
     * Dust-layer top unevenness: top above a point is (1 ± this) of table top.
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

        /** Hard-ground fraction beneath pall at (dx, dz) from ground zero ({@link PallGround#hard}); soil by default. */
        default double hard(double dx, double dz) {
            return 0;
        }
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
     * Per node: dust over hard ground relative to soil ({@link DustModel.Surface#PAVED} against the reference; same bins, so the ratio
     * holds at every age): the pall over rock, roofs and snow holds only debris dust.
     */
    private final double[] paved = new double[BINS];
    /**
     * Water beneath ground zero 0..1 ({@code NukeWater.under}: chunk, map tile or sea-level guess), and how much of a
     * "water burst" it is ({@link WaterModel#wetBurst} × that fraction), written by client tick ({@link #water}); land until resolved.
     */
    private double under, wet;
    /** When dust has settled everywhere (optical depth below {@link #GONE_DEPTH}), ticks since detonation. */
    private final double dustEnd;
    /** Detonation clock from real ticks ({@link #clock}); real time until set. */
    private DoubleUnaryOperator clock = t -> t;

    public PallField(Detonation d) {
        this.d = d;
        double hob = Math.max(0, d.hobMetres()), y = d.yieldKt();
        DustModel.Surface surface = DustModel.Surface.REFERENCE;
        double dustM = DustModel.reach(hob, y, d.surface(), d.visibility(), surface, GONE_DEPTH);
        // server does not ignite (Detonation.fires) — no fire zone: no haze above it, fire glow or storm
        double ignite = !d.fires() ? 0 : groundRange(ThermalModel.rangeForFluence(ThermalModel.ignite(y), y, d.surface(), d.visibility()), hob);
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
            double soil = columns[i].mass();
            paved[i] = soil > 0 ? Math.min(1, DustModel.column(rm, hob, y, d.surface(), d.visibility(), DustModel.Surface.PAVED).mass() / soil) : 1;
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

    /**
     * Inputs of the young dust wall ({@code DustWallPass}, a presentation model; physics only as these inputs) at model second
     * {@code t}: for node j of n at ground radius j/(n − 1) of {@link #reach} (blocks), the game front's arrival (model seconds
     * since the burst), the dust layer top at age {@code settleS} model seconds, when the wall has settled (blocks; the slow
     * rise over the hot ground afterwards is the pall's), and the layer's vertical optical depth at age {@code handoffS}, where
     * the pall takes the wall over ({@link DustModel.Column#depth}; how much dust was lofted there) — none where the fireball
     * touches the ground at {@code t} ({@link FireballModel#groundRadius}, as the pall's {@link #pallShare}) — and the dust over
     * hard ground relative to soil ({@link #paved}). 4 numbers per node.
     */
    public void young(double t, double settleS, double handoffS, int n, float[] out) {
        double hob = Math.max(0, d.hobMetres());
        double ball = FireballModel.groundRadius(t, hob, d.yieldKt(), d.surface());
        for (int j = 0; j < n; j++) {
            double rb = j / (n - 1.0) * reach, slant = Math.hypot(d.metres(rb), hob);
            DustModel.Column c = columns[Math.min(BINS - 1, Math.max(0, (int) (rb / reach * BINS)))];
            out[4 * j] = (float) (d.arrivalTicks(d.blocks(slant)) / (20.0 * d.scale()));
            out[4 * j + 1] = (float) Math.max(d.blocks(c.height(settleS)), MIN_TOP_BLOCKS);
            out[4 * j + 2] = d.metres(rb) < ball ? 0f : (float) c.depth(handoffS);
            out[4 * j + 3] = (float) paved[Math.min(BINS - 1, Math.max(0, (int) (rb / reach * BINS)))];
        }
    }

    /** Log-spaced age nodes of the whole-lifetime dust table ({@link #lifetime}). */
    public static final int AGES = 32;
    /** Age scale of the table's log coordinate log(1 + age / AGE0), model seconds. */
    public static final double AGE0 = 0.05;

    /** Latest age at which any node's lofted layer is still thicker than {@link #GONE_DEPTH}, model seconds (the table's last age). */
    public double lifetimeEnd() {
        double end = lifetimeEnd;
        if (end < 0) {
            end = 60;
            for (int i = 0; i < BINS; i++) end = Math.max(end, settledBy(columns[i]));
            lifetimeEnd = end;
        }
        return end;
    }

    /** {@link #lifetimeEnd}, once computed (a bisection per node: read every frame by every reader of the drawn dust). */
    private volatile double lifetimeEnd = -1;

    /** Age of table node u (fractional), model seconds. */
    public static double lifetimeAge(double u, double end) {
        return AGE0 * (Math.pow(1 + end / AGE0, u / (AGES - 1)) - 1);
    }

    /**
     * Inputs of the whole-lifetime dust wave, the law's numbers by ground radius and log age.
     * Row 0, node j at radius j/(n − 1) of {@link #reach}: front arrival (model s), dust over hard ground relative to soil
     * ({@link #paved}), the fire zone's share (fire light from below), 0. Rows 1..{@link #AGES}, age node k ({@link #lifetimeAge}): the presentation layer top (blocks)
     * and the optical depth the transfer reads, then both slopes per age node (the shader's cubic Hermite in the age
     * coordinate: C1, no kink at a node).
     * <p>
     * The top (presentation, not physical): {@code present} × the law's top ({@link DustModel.Column#height}) at the settle age
     * (1 s; the birth, hump and settling are the presentation's), joining the law's rising top over {@link #LIFETIME_JOIN_S}
     * (a cubic-exponential onset: take 33's layer height held through its 24 s, the law's top from ~5 min on). The depth: the law's
     * extinction (its optical depth over its own top, {@link DustModel.Column#depth}: the only owner of settling and
     * dilution) over the presentation's top: once the top has joined the law's, the law's optical depth itself.
     */
    public void lifetime(int n, double present, float[] out) {
        double end = lifetimeEnd();
        double[][] v = new double[AGES][];
        for (int j = 0; j < n; j++) {
            double rb = j / (n - 1.0) * reach, slant = Math.hypot(d.metres(rb), Math.max(0, d.hobMetres()));
            int i = Math.min(BINS - 1, Math.max(0, (int) (rb / reach * BINS)));
            out[4 * j] = (float) (d.arrivalTicks(d.blocks(slant)) / (20.0 * d.scale()));
            out[4 * j + 1] = (float) paved[i];
            out[4 * j + 2] = (float) fire[i];
            out[4 * j + 3] = 0;
            DustModel.Column c = columns[i];
            for (int k = 0; k < AGES; k++) v[k] = lifetimeAt(c, lifetimeAge(k, end), present);
            for (int m = 0; m < 2; m++) {
                // monotone cubic slopes (Fritsch–Carlson): C1, and no overshoot between nodes (no negative depth)
                double[] slope = new double[AGES];
                for (int k = 0; k < AGES; k++) {
                    double dl = k > 0 ? v[k][m] - v[k - 1][m] : v[1][m] - v[0][m], dr = k < AGES - 1 ? v[k + 1][m] - v[k][m] : dl;
                    slope[k] = dl * dr <= 0 ? 0 : 0.5 * (dl + dr);
                }
                for (int k = 0; k < AGES - 1; k++) {
                    double dk = v[k + 1][m] - v[k][m];
                    if (dk == 0) {
                        slope[k] = slope[k + 1] = 0;
                        continue;
                    }
                    double al = slope[k] / dk, be = slope[k + 1] / dk, r = al * al + be * be;
                    if (r > 9) {
                        double t = 3 / Math.sqrt(r);
                        slope[k] = t * al * dk;
                        slope[k + 1] = t * be * dk;
                    }
                }
                for (int k = 0; k < AGES; k++) {
                    int o = 4 * ((1 + k) * n + j);
                    out[o + m] = (float) v[k][m];
                    out[o + 2 + m] = (float) slope[k];
                }
            }
        }
    }

    /**
     * The dust wave's water spray per node ({@code dustwall.glsl}: {@code dwSprayLaw}), into {@code out} from float {@code offset}:
     * node j at radius j/(n − 1) of {@link #reach}: the extinction where the blast wind strips the densest spray (1/block,
     * {@link WaterModel#spray} × {@link #SIGMA_SPRAY}) and the stripping height (blocks, {@link WaterModel#sprayHeight}); the
     * shader takes the age law ({@link #sprayEnvelope}, {@link #sprayTop}).
     */
    public void lifetimeSpray(int n, float[] out, int offset) {
        for (int j = 0; j < n; j++) {
            double rb = j / (n - 1.0) * reach;
            int i = Math.min(BINS - 1, Math.max(0, (int) (rb / reach * BINS)));
            out[offset + 4 * j] = (float) (spray[i] <= 0 ? 0 : perBlock(SIGMA_SPRAY) * spray[i]);
            out[offset + 4 * j + 1] = (float) sprayFrom[i];
            out[offset + 4 * j + 2] = 0;
            out[offset + 4 * j + 3] = 0;
        }
    }

    /** Settle age of the presentation's birth envelope, model s: the law's layer is read from here on (smoothly). */
    static final double LIFETIME_SETTLE_S = 1.0;
    /** The presentation top joins the law's rising top over this age (Gaussian onset), model s (presentation; est.). */
    public static final double LIFETIME_JOIN_S = 180;

    /**
     * The lofted dust fills the layer the law's top deepens (its growth over the positive phase and over hot ground) by
     * mixing, one eddy turnover behind it: h/w* with h ~100 m and w* ~25 m/s of the post-shock gusts, est. (model s).
     */
    public static final double LIFETIME_MIX_S = 4;

    /** (presentation top, blocks; optical depth for the transfer) of column c at age (model s). */
    private double[] lifetimeAt(DustModel.Column c, double age, double present) {
        double first = Math.max(d.blocks(c.height(LIFETIME_SETTLE_S)), MIN_TOP_BLOCKS);
        age = Math.max(age, 0);
        // the dust's own height: a first-order lag of the law's top (exponential integrator over growing steps)
        double mixed = first, s = 0, law = first;
        while (s < age) {
            double dt = Math.min(Math.max(0.02, 0.01 * s), age - s);
            s += dt;
            law = lifetimeLaw(c, s);
            mixed = law + (mixed - law) * Math.exp(-dt / LIFETIME_MIX_S);
        }
        double x = age / LIFETIME_JOIN_S, join = 1 - Math.exp(-x * x * x);
        double top = first + (law - first) * join;
        double a = lifetimeSettled(age);
        double tau = c.depth(a) * top / Math.max(mixed, top);
        return new double[] {top * present, visible(c, age, tau, top * present, present)};
    }

    /**
     * Presentation, not physical (owner call): inside an old, thick layer a camera sees about
     * {@link #SEE_FIRST_M} at the first minute and {@link #SEE_FIVE_M} by five minutes (the reference: Silo's haze behind
     * the wall, Nevada test dust: a see-through warm haze, not a wall). The law's τ (60–96 at 4.5 km from 1 Mt over 5–10 min)
     * would hold the camera in a 50-block fog for ten minutes. The transfer saturates the old column's drawn optical depth
     * at the extinction that gives that sight (Koschmieder, 3.912/V, over the drawn column), by age through the haze
     * level's fade (none while the textured young level owns the column: take 33 holds); from five minutes on the ceiling
     * falls with the law's own thinning (the drawn depth follows the law's τ through g). Returned as the τ the shader's
     * {@code dwTransfer} turns into that drawn depth (its inverse), so the shader keeps one transfer.
     */
    private double visible(DustModel.Column c, double age, double tau, double topBlocks, double present) {
        double drawn = transfer(tau), fade = 1 - textured(age);
        if (fade <= 0 || drawn <= 0) return tau;
        double ceiling = seeCeiling(c, age, topBlocks, present);
        double capped = drawn / Math.sqrt(1 + (drawn / ceiling) * (drawn / ceiling));
        return inverseTransfer(drawn + (capped - drawn) * fade);
    }

    /** Sight inside old dust at one minute and at five minutes, m (presentation; owner call). */
    static final double SEE_FIRST_M = 200, SEE_FIVE_M = 700;
    /**
     * Optical depth over the sight distance: the meteorological optical range's 5 % contrast (WMO), so the ground 370 m
     * below and ahead of a 120 m camera shows at five minutes (optical depth 1.6 over it); Koschmieder's 2 % hid it.
     */
    static final double SEE_TAU = 3.0;

    /**
     * The drawn column's ceiling (optical depth) at age: the sight's extinction over the drawn profile's integral. The sight
     * grows as (a/60)^p to the five-minute value, smoothly (no kink: C3); the ceiling then follows the law's own thinning
     * (its g ratio to the five-minute column), weighted in smoothly about 300 s.
     */
    private double seeCeiling(DustModel.Column c, double age, double topBlocks, double present) {
        double a = Math.max(age, 1), p = Math.log(SEE_FIVE_M / SEE_FIRST_M) / Math.log(5);
        double sight = d.blocks(SEE_FIRST_M) * Math.pow(a / 60.0, p) / Math.pow(1 + Math.pow(a / 300.0, 4), p / 4);
        double ceiling = SEE_TAU / sight * profileIntegral(0.95 * topBlocks);
        double refTau = c.depth(lifetimeSettled(300)), now = c.depth(lifetimeSettled(a));
        double follow = refTau > 0 && now > 0 ? ceiling * transfer(now) / transfer(refTau) : ceiling;
        double w = 1 / (1 + Math.pow(300 / a, 4));
        return Math.exp(Math.log(ceiling) + (Math.log(Math.max(follow, 1e-9)) - Math.log(ceiling)) * w);
    }

    /** {@code dwProfileInt}: the drawn profile's integral over a settled top h, blocks. */
    static double profileIntegral(double h) {
        return Math.max(0.9483 * h - 2.854, 1);
    }

    /** {@code dwTransfer}. */
    public static double transfer(double tau) {
        if (tau <= 1) return tau;
        double x = tau - 1;
        return 1 + 0.073 * x + (1 - 0.073) * 0.5 * (1 - Math.exp(-x / 0.5));
    }

    /** Inverse of {@link #transfer} (monotone; Newton from the affine branch). */
    static double inverseTransfer(double drawn) {
        if (drawn <= 1) return drawn;
        double x = Math.max((drawn - 1 - 0.4635) / 0.073, 0);
        for (int i = 0; i < 30; i++) {
            double f = 1 + 0.073 * x + (1 - 0.073) * 0.5 * (1 - Math.exp(-x / 0.5)) - drawn, df = 0.073 + (1 - 0.073) * Math.exp(-x / 0.5);
            x = Math.max(x - f / df, 0);
            if (Math.abs(f) < 1e-9) break;
        }
        return 1 + x;
    }

    /** {@code dwTextured}: the textured level's share at age (quintic step from 1 at 24 s to 0 at 60 s). */
    static double textured(double age) {
        double x = Math.min(1, Math.max(0, (age - 24) / 36.0));
        return 1 - x * x * x * (x * (6 * x - 15) + 10);
    }

    /** A smooth max of the age and {@link #LIFETIME_SETTLE_S} (softplus, width 0.3 s), model s. */
    private static double lifetimeSettled(double age) {
        double w = 0.3, x = (age - LIFETIME_SETTLE_S) / w;
        return LIFETIME_SETTLE_S + w * (x > 30 ? x : Math.log1p(Math.exp(x)));
    }

    /** The law's layer top read from the settle age on (a smooth max of the age and {@link #LIFETIME_SETTLE_S}), blocks. */
    private double lifetimeLaw(DustModel.Column c, double age) {
        double a = lifetimeSettled(age);
        return Math.max(d.blocks(c.height(a)), MIN_TOP_BLOCKS);
    }

    /** Ground ignition-zone radius, blocks (0 — no fires): haze above it, firestorm inside ({@link FireStorm}). */
    double fireReach() {
        return fireReach;
    }

    /** Haze top over fires, blocks (260 m at 15 kt): storm smoke columns rise to it. */
    double hazeTop() {
        return d.blocks(hazeTopM(d.yieldKt()));
    }

    /** Haze top over fires, m ({@link #HAZE_TOP_M} at 15 kt, ∛yield). */
    static double hazeTopM(double yieldKt) {
        return HAZE_TOP_M * Math.cbrt(yieldKt / 15);
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
     * The detonation's clock ({@code NukeClock}) for real client ticks since detonation, in ticks: real through the show,
     * the world clock after ({@code ClientNuclear.Active}). Fire, its smoke and its light follow it; dust and spray do not.
     */
    void clock(DoubleUnaryOperator clock) {
        this.clock = clock;
    }

    /** Detonation-clock seconds at {@code ticks} real client ticks since detonation. */
    double clockSeconds(double ticks) {
        return clock.applyAsDouble(ticks) / 20.0;
    }

    // ---------------------------------------------------------------- laws (profile and column shared with the pall shaders)

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
     * (like its billows: a ring from half-radius to edge, denser at the edge).
     */
    static double surgeProfile(double u) {
        return (0.35 + 0.65 * smooth(0.2, 0.85, u)) * (1 - smooth(0.9, 1.05, u));
    }

    /** Base-surge top by radius, fraction of its height: roller higher at edge, lower toward center. */
    static double surgeTop(double u) {
        return 0.55 + 0.45 * smooth(0.3, 0.95, u);
    }

    /**
     * Haze over time: smoke of the fires, so it grows, holds and burns out with them ({@link FireStorm#smoke} at the nominal peak,
     * on the detonation's clock; earlier it rose 15–150 s after the front, before the fires that make it). Only behind the front
     * ({@code ageS} &gt; 0): the wave sweeps what smoke there is into its dust; before it, a few per mille of full fire.
     */
    double hazeEnvelope(double ageS, double ticks) {
        if (ageS <= 0) return 0;
        return FireStorm.smoke(clockSeconds(ticks), FireStorm.PEAK_S);
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

    /** Table rows per detonation: the haze. */
    public static final int ROWS = 1;

    /** First float of detonation slot {@code slot}'s haze row in the frame table ({@link #ROWS} rows of {@code width} RGBA nodes per slot). */
    public static int hazeOffset(int slot, int width) {
        return slot * ROWS * width * 4;
    }

    /**
     * Table row at {@code ticks} since detonation (client ticks): 4 numbers per {@link #BINS} nodes — the haze ({@link #haze}),
     * all the pall volume draws of the field. Writes into {@code out} (length ≥ 4·BINS). {@code land} indicates land beneath the
     * pall ({@link PallGround}): no haze or fires over open sea (the shader would remove them by map anyway, but the slot is
     * empty without them).
     */
    public void row(double ticks, boolean land, float[] out) {
        double[] v = new double[4];
        for (int i = 0; i < BINS; i++) {
            haze(i, ticks, v);
            if (!land) v[0] = v[2] = 0;
            for (int k = 0; k < 4; k++) out[4 * i + k] = (float) v[k];
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
     * excluding dust still in the wall head; layer top (blocks); smoke fraction 0..1 (soot darkens dust over fires as they grow,
     * {@link FireStorm#growth}: dust-brown at first, gray over full fire); degree of layer
     * mixing ({@link #mixed}).
     */
    void dust(int i, double ticks, double[] out) {
        double age = seconds(i, ticks), depth = columns[i].depth(age);
        out[1] = Math.max(d.blocks(columns[i].height(age)), MIN_TOP_BLOCKS);
        // profile column is column(0) of top: vertical optical depth exactly matches the layer, even at minimum top
        out[0] = depth < GONE_DEPTH ? 0 : depth / (column(0) * out[1]) * pallShare(i, ticks);
        out[2] = fire[i] * FireStorm.growth(clockSeconds(ticks));
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
     * spray top (blocks, {@link #sprayTop}); node i dust over hard ground relative to soil ({@link #paved}), 0. Separate layer: spray
     * does not mix with dust and is lower.
     */
    void spray(int i, double ticks, double[] out) {
        double age = seconds(i, ticks);
        out[0] = spray[i] <= 0 ? 0 : perBlock(SIGMA_SPRAY) * spray[i] * sprayEnvelope(age) * pallShare(i, ticks);
        out[1] = Math.max(sprayTop(sprayFrom[i], age), 1);
        out[2] = paved[i];
        out[3] = 0;
    }

    /** Dust over ground with hard share {@code hard} relative to soil at node i. */
    double soil(int i, double hard) {
        return 1 + hard * (paved[i] - 1);
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
     * {@code out} receives x, z.
     */
    public void source(double dx, double dz, double ticks, double[] out) {
        drift(ticks, out);
        double k = share(bin(Math.hypot(dx, dz)), ticks);
        out[0] = dx - out[0] * k;
        out[1] = dz - out[1] * k;
    }

    /**
     * Node i haze (by distance from plume axis): extinction (1/block), height (blocks; no lower than eventual dust
     * top), fire light from below 0..1, 0.
     */
    void haze(int i, double ticks, double[] out) {
        out[0] = perBlock(SIGMA_HAZE) * fire[i] * hazeEnvelope(age(i, ticks), ticks);
        // the haze top climbs with the smoke columns that feed it (FireStorm.risen from the flash)
        out[1] = Math.max(ceiling[i], hazeTop() * FireStorm.risen(clockSeconds(ticks), hazeTopM(d.yieldKt())));
        out[2] = fire[i] * fireLight(ticks);
        out[3] = 0;
    }

    /**
     * Soot share in dust lofted at ground range {@code r} (blocks from ground zero) at {@code ticks}: fire zone × fire growth
     * ({@link FireStorm#growth} on the detonation clock), as {@link #dust}. Dust lofted
     * by the wave seconds to minutes after the flash carries next to no soot; it darkens only as the fires grow.
     */
    double smoke(double r, double ticks) {
        if (fireReach <= 0) return 0;
        return (1 - smooth(fireReach * 0.85, fireReach * 1.05, r)) * FireStorm.growth(clockSeconds(ticks));
    }

    /**
     * Sand's share of lofted dust mass over ground with sand share {@code sand} and hard share {@code hard} (both fractions of the ground, as the
     * map holds them: {@link PallGround#sand}, {@link PallGround#hard}; hard ground lofts only debris dust, {@code paved} of the soil's mass,
     * {@link #soil}): what colours the dust — desert sand or the reference loamy soil. Copy of {@code pallDustW} in {@code pall.glsl}.
     */
    static double sandShare(double sand, double hard, double paved) {
        return Math.min(1, Math.max(0, sand / Math.max(1 + hard * (paved - 1), 1e-6)));
    }

    /** Fire light from below 0..1: the fires' flame from the flash to burning out ({@link FireStorm#flame} at the nominal peak). */
    double fireLight(double ticks) {
        return FireStorm.flame(clockSeconds(ticks), FireStorm.PEAK_S);
    }

    /** Fire light beneath mushroom 0..1 (at ground zero; {@link #fireLight}): 0 if no fire zone. */
    double fireBelow(double ticks) {
        return fireReach > 0 ? fireLight(ticks) : 0;
    }

    /**
     * Whether anything remains of pall at {@code ticks}: haze (only above fires), dust while the front crosses the
     * table and until settled everywhere (spray settles before dust), base surge.
     */
    public boolean visible(double ticks) {
        return ticks > 0 && (fireReach > 0 && clockSeconds(ticks) < FireStorm.OUT_S || ticks <= arrival[BINS - 1] || ticks < dustEnd
                || surge(ticks, new double[3]));
    }

    /** Whether the fire-zone haze, the matter the pall volume draws ({@link #row}), can exist at {@code ticks}. */
    public boolean hazeVisible(double ticks) {
        return ticks > 0 && fireReach > 0 && clockSeconds(ticks) < FireStorm.OUT_S;
    }

    /** Ground reach from ground zero of the haze at {@code ticks}, blocks: fire zone (its soft edge) and downwind plume; 0 — no fire zone. */
    public double hazeExtent(double ticks) {
        return fireReach <= 0 ? 0 : Math.min(reach, 1.05 * fireReach) + plume(ticks);
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
     * the field the pall and dust passes draw. Point is ground distance from ground zero (dx, dz, blocks), height above ground
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

    /**
     * {@link #sigma} with the drawn dust wave's ground dust ({@link #waveDust}) in place of the law's: the carpet the screen shows
     * at a point (spray, haze and surge as {@link #sigma}), 1/block.
     */
    public double drawnSigma(double dx, double dz, double above, double floorUp, double ticks, Water water) {
        double[] l = new double[LAYERS];
        layers(dx, dz, ticks, water, l);
        waveDust(dx, dz, ticks, water, l);
        double h = above - Math.max(floorUp, 0);
        return l[0] * profile(h / l[2]) + l[1] * profile(h / l[7]) + l[3] * profile(h / l[4]) + l[5] * profile(h / l[6]);
    }

    /**
     * Add the haze alone (what the pall volume draws) to the eye extinction and its two vertical depths, as {@link #sigma} and
     * {@link #tau}: extinction at {@code eye}, depth above it and below it down to {@code floor} (blocks above ground zero).
     */
    void addHazeEye(double dx, double dz, double eye, double floor, double ticks, Water water, org.joml.Vector3f out) {
        double[] l = new double[LAYERS];
        layers(dx, dz, ticks, water, l);
        double base = Math.max(floor, 0), h = eye - base;
        out.add((float) (l[3] * profile(h / l[4])),
                (float) (l[3] * between(h, Double.POSITIVE_INFINITY, l[4])),
                (float) (l[3] * between(floor - base, h, l[4])));
    }

    /**
     * The dust wave's drawn dust at the eye ({@link #waveDust}: its noise-free column, as the pass draws it) added to {@code out}:
     * extinction at the eye (1/block) and the optical depths above and below it. The pall's rays end where the dust in front of
     * them is opaque ({@code pallClip}): behind it nothing shows, and without it every ray from inside the dust marched its whole
     * length through dust the pall does not draw (the pall pass took 71 ms at HIGH inside the dust at +60 s against v3.13.0's 25).
     */
    void addDrawnEye(double dx, double dz, double eye, double floor, double ticks, Water water, org.joml.Vector3f out) {
        double[] l = new double[LAYERS];
        layers(dx, dz, ticks, water, l);
        waveDust(dx, dz, ticks, water, l);
        double base = Math.max(floor, 0), h = eye - base;
        out.add((float) (l[0] * profile(h / l[2]) + l[1] * profile(h / l[7])),
                (float) (l[0] * between(h, Double.POSITIVE_INFINITY, l[2]) + l[1] * between(h, Double.POSITIVE_INFINITY, l[7])),
                (float) (l[0] * between(floor - base, h, l[2]) + l[1] * between(floor - base, h, l[7])));
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

    /**
     * Optical depth of the ground dust and spray above (dx, dz) as the dust wave draws it (the picture's reader: the ground the
     * mushroom sees, {@code MushroomField.groundUnder}): the wave's noise-free column ({@link #waveDust}) and the law's spray.
     */
    public double drawnTau(double dx, double dz, double ticks, Water water) {
        double[] l = new double[LAYERS];
        layers(dx, dz, ticks, water, l);
        waveDust(dx, dz, ticks, water, l);
        double inf = Double.POSITIVE_INFINITY;
        // (the wave's own column: its ground extinction over the drawn profile's integral)
        return l[0] / 1.25 * Math.max(0.9483 * l[2] - 2.854, 1) + l[1] * between(0, inf, l[7]);
    }

    /**
     * The dust wave's drawn noise-free top over (dx, dz) at {@code ticks}, blocks (1 where it has no dust): the layer breach dust
     * merges into ({@code BlastDust}).
     */
    public double drawnTop(double dx, double dz, double ticks) {
        double[] l = new double[LAYERS];
        waveDust(dx, dz, ticks, Water.DRY, l);
        return l[0] > 0 ? l[2] : 1;
    }

    /** {@link #layers} parameters for {@link #footLayer} (frame runs on render thread): no new array per mushroom per frame. */
    private final double[] footScratch = new double[LAYERS];

    /**
     * The dust layer the stem foot rises from, {@code r} blocks from ground zero, averaged over four sides (wind drift
     * shifts the layer): the drawn dust wave's ground dust ({@link #waveDust}: the carpet the screen shows) and the spray,
     * extinction 1/block ({@code out[0]}; at height × {@link #profile}), and their top, blocks ({@code out[1]}; weighted by
     * extinction fractions). No layer — 0 and 1. Inflow turns it into the stem ({@code MushroomField.foot}).
     */
    public void footLayer(double r, double ticks, Water water, double[] out) {
        double[] l = footScratch;
        double sigma = 0, top = 0;
        for (int k = 0; k < 4; k++) {
            double dx = k == 0 ? r : k == 1 ? -r : 0, dz = k == 2 ? r : k == 3 ? -r : 0;
            layers(dx, dz, ticks, water, l);
            waveDust(dx, dz, ticks, water, l);
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

    /**
     * The drawn dust wave's presentation layer, built once ({@link #lifetime} at {@link #PRESENT_HEIGHT} and {@link #lifetimeSpray}:
     * the dust pass's source table, {@code DustSources}).
     */
    private volatile float[] wave;
    private java.util.concurrent.CompletableFuture<Void> building;

    /**
     * The dust wave's table ({@code DustWallPass.NODES} nodes; rows: arrival, the law by age, spray); null until it is built.
     * The build (120–340 ms) runs off the render thread from the detonation's arrival ({@link #prepareWave}); until it is
     * ready no dust is drawn or read, and the drawn wave's one-second fade-in covers the start.
     */
    public float[] waveTable() {
        float[] w = wave;
        if (w == null) prepareWave();
        return w;
    }

    /** Start building {@link #waveTable} in the background, once. */
    public synchronized void prepareWave() {
        if (wave != null || building != null) return;
        building = java.util.concurrent.CompletableFuture.runAsync(() -> wave = buildWave(), net.minecraft.Util.backgroundExecutor());
    }

    /** {@link #waveTable}, built on this thread if it is not ready (tests, tools). */
    public float[] waveTableNow() {
        float[] w = wave;
        if (w == null) wave = w = buildWave();
        return w;
    }

    private float[] buildWave() {
        int n = ua.zentix.incoming.client.fx.layer.DustWallPass.NODES;
        float[] v = new float[n * (ua.zentix.incoming.client.fx.layer.DustWallPass.ROWS + 1) * 4];
        lifetime(n, PRESENT_HEIGHT, v);
        lifetimeSpray(n, v, n * ua.zentix.incoming.client.fx.layer.DustWallPass.ROWS * 4);
        return v;
    }

    /**
     * The wave's layer drawn taller than the dust law's at its settle age (presentation, not physical; owner's call 22.11 under
     * Artem's "crest up to about camera height"): ×68/58, so that at 4.5 km from a 1 Mt surface burst the settled layer stands
     * ~68 blocks high and its crest (1.4–1.6 of it) reads just under the horizon line of a 120-block camera.
     */
    public static final double PRESENT_HEIGHT = 68.0 / 58.0;

    /**
     * The picture's readers (the stem foot, the ground the mushroom sees) take the dust as the dust wave draws it, not the law's
     * layer: its noise-free column ({@code dustwall.glsl}: {@code dwHaze}'s column, the transfer, the young head's density, the
     * profile normalised over the drawn top) at the source point of (dx, dz) replaces {@code l[0]} (ground extinction, the
     * profile's 1.25 at the ground) and {@code l[2]} (top); the spray, haze and surge stay the law's.
     */
    private void waveDust(double dx, double dz, double ticks, Water water, double[] l) {
        double[] w = new double[2];
        source(dx, dz, ticks, w);
        float[] table = waveTable();
        l[0] = 0;
        l[2] = 1;
        if (table == null) return;
        DustMaterialTiles m = materials;
        double[] c = new double[2];
        drawnColumn(table, ua.zentix.incoming.client.fx.layer.DustWallPass.NODES, lifetimeEnd(), reach, ticks / (20.0 * d.scale()),
                w[0], w[1], m == null ? 0 : m.hard(w[0], w[1]), c);
        if (c[0] <= 0) return;
        double top = c[1];
        double wet = water.at(w[0], w[1]);
        l[0] = 1.25 * c[0] / Math.max(0.9483 * top - 2.854, 1) * (1 - wet);
        l[2] = Math.max(top, 1);
    }

    /** The material tiles the dust wave draws for this detonation ({@link #drawnColumn}'s hard ground); null until they exist. */
    private volatile DustMaterialTiles materials;

    void materials(DustMaterialTiles m) {
        materials = m;
    }

    /**
     * The drawn dust wave's noise-free column at source point (rx, rz) from ground zero, model time t ({@code dustwall.glsl}:
     * {@code dwHaze}'s column): out[0] its vertical optical depth (the law's through {@link #transfer}, the young head's density,
     * over hard ground only its debris dust — the paved share of table row 0 — and the last table spacing's fade), out[1] its
     * top, blocks (0.95 of the table's, as drawn); 0 where no dust. One copy for every Java reader of the drawn dust.
     */
    public static void drawnColumn(float[] table, int n, double end, double reach, double t, double rx, double rz, double hard,
            double[] out) {
        out[0] = 0;
        out[1] = 1;
        double r = Math.hypot(rx, rz), x = Math.min(r / Math.max(reach, 1), 1) * (n - 1);
        int j = (int) Math.min(Math.floor(x), n - 2);
        double fx = x - j;
        double age = t - (table[4 * j] + fx * (table[4 * (j + 1)] - table[4 * j]));
        if (age <= 0) return;
        double u = Math.log1p(age / AGE0) / Math.log1p(end / AGE0) * (AGES - 1);
        if (u >= AGES - 1) return;
        int k = (int) Math.floor(u);
        double f = u - k;
        int o0 = 4 * ((1 + k) * n + j), o1 = 4 * ((2 + k) * n + j);
        double top = 0.95 * (table[o0] + f * (table[o1] - table[o0])), tau = table[o0 + 1] + f * (table[o1 + 1] - table[o0 + 1]);
        double row = table[4 * j + 1] + fx * (table[4 * (j + 1) + 1] - table[4 * j + 1]);
        double paved = 1 + hard * (row - 1);
        double edge = reach * (1 - 1.0 / (n - 1)), fade = 1 - smoothstep(edge, reach, r);
        out[0] = transfer(tau) * (1 + 0.5 * Math.exp(-age / 1.1)) * paved * fade;
        out[1] = top;
    }

    private static double smoothstep(double a, double b, double x) {
        double v = Math.clamp((x - a) / (b - a), 0, 1);
        return v * v * (3 - 2 * v);
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
            out[0] = sd * (1 - wet) * e * (sd > 0 ? soil(i, water.hard(lx, lz)) : 1);
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
