package ua.zentix.incoming.nuclear.model;

/**
 * Blast-lofted dust (DESIGN-nuke §6): one layer from front to pall, rather than separate wall and pall.
 * Laws from the “Blast-lofted dust” literature review (“cinema-level nuke” design, §8); meters, model seconds, kilograms.
 * <p>
 * <b>Entrainment.</b> Ground pressure, local positive phase t₊ and ground-aligned dynamic pressure share depend on burst
 * height ({@link HobBlast}). Post-front ground wind is Rankine–Hugoniot ({@link BlastModel#windSpeed}) × √share (under
 * regular reflection beneath the burst, air moves toward the ground obliquely), decaying over the phase as
 * (1 − τ/t₊)·e^(−τ/t₊). Ground friction velocity uses the Schlichting rough plate x meters behind the front
 * (Gaj &amp; Small 1991, eq. 7): u* = U/√2·B^(−1.25), B = 2.87 + 1.58·lg(x/K_s).
 * At u* ≥ 10 entrainment thresholds, soil enters suspension — flux F = 1.80·ρ·U^1.24 / (u*ₜₕ^0.24·B^2.8)
 * (ibid., eq. 8, after Mirels 1984; ~1 kg/(m²·s) at 43 m/s over loose sand); from threshold to 10 thresholds — saltation,
 * F ∝ u*³(1 − u*ₜₕ²/u*²) (§3.1), with the constant ensuring continuity at 10 thresholds. Phase entrainment is capped by
 * soil supply ({@link Surface#supply}). Where houses collapse, add debris dust (wall clay, plaster): no data, fitted to
 * Hiroshima ({@link Surface#debris}, using destruction {@link #destroyed}).
 * <p>
 * <b>Height.</b> During the blast phase the layer grows as a turbulent dusty post-front boundary layer
 * (Kuhl et al. 1992, eq. 17): h = c·R^(1/6)·(U_s·τ)^(5/6) — zero at the leading edge, thicker behind;
 * c = {@link #TOP_C} gives the visible top (Kuhl's dense core — 0.0256; GRABLE — 6–25 m near the front in the first tenths
 * of a second). U_s·τ is the front's own normal path beyond the point (Kuhl uses constant front speed, as does Mirels),
 * at dust age ({@link #run}, {@link #waveTop}): the front slows, so it is not arrival speed × time — at a surface burst's
 * epicenter that would give thousands of kilometers over the phase and an 18 km top; under an airburst, it is not the
 * ground trace path, which moves faster than the front. Then flash-heated ground lifts the layer (entraining convective
 * layer, Deardorff/Tennekes): toward h_b = √(2(1 + 2A)·f_h·E / (ρ c_p γ)) over {@link #RISE_S}; E is flash fluence on flat
 * ground ({@link ThermalModel}).
 * <p>
 * <b>Settling and removal.</b> Five size bins (from MET and MISERS BLUFF II): each settles from the mixed layer as
 * dm/dt = −v·m/h; fines leave by dilution and transport over {@link #FINE_S} (fitted: Hiroshima, 1.42 km — dark for
 * ~20 minutes, lighter by 30); near the epicenter, dust is also drawn into the stem ({@link #STEM_S}). Geometric optics:
 * k = 3Q/(2ρD), optical depth above ground τ = Σ k·m.
 */
public final class DustModel {
    /** Bin grain sizes, µm (MET: MMD 15 µm, MISERS BLUFF II: 25–30 µm and 15 % of mass above 47 µm). */
    static final double[] SIZE_UM = {3, 15, 40, 120, 400};
    /**
     * PM10 share of the mixture (aerodynamic size up to 10 µm) — first bin's share: for a 2.65 g/cm³ grain, aerodynamic
     * size ≈ geometric size × √2.65 (DOE-HDBK-3010-94, eq. 1-2): 3 µm → ~5, 15 µm → ~24.
     */
    public static final double PM10_SHARE = 0.2;
    /** Bin mass shares. */
    static final double[] SHARE = {PM10_SHARE, 0.3, 0.3, 0.15, 0.05};
    /**
     * Bin settling speeds, m/s: quartz 2.65 g/cm³ in air (Stokes with Cunningham correction, above Re ≈ 0.1 — Clift–Gauvin
     * drag), review table (§4), logarithmic interpolation between nodes.
     */
    static final double[] SETTLE = {7.3e-4, 0.018, 0.118, 0.745, 3.03};
    /** Coarse-grain extinction efficiency (geometric optics) and grain density, kg/m³. */
    static final double EXTINCTION_Q = 2, GRAIN_DENSITY = 2650;
    /** Bin mass extinction, m²/kg: k = 3Q / (2ρD). */
    static final double[] K = new double[SIZE_UM.length];
    /** Mixture extinction, m²/kg: Σ k·share (≈ 108; MET distribution in the review — 130). */
    public static final double K_MIX;

    static {
        double sum = 0;
        for (int i = 0; i < K.length; i++) {
            K[i] = 3 * EXTINCTION_Q / (2 * GRAIN_DENSITY * SIZE_UM[i] * 1e-6);
            sum += K[i] * SHARE[i];
        }
        K_MIX = sum;
    }

    /**
     * Dust-bin mixture share still airborne after {@code seconds} in a layer {@code height} m tall: each bin settles from
     * the mixed layer with e-folding time height / settling speed ({@link #SETTLE}). Conventional explosion clouds
     * ({@code client/fx/smoke/CloudModel}).
     */
    public static double aloft(double seconds, double height) {
        return airborne(Math.max(seconds, 0) / Math.max(height, 1));
    }

    /**
     * Mass share of the bin mixture still airborne when ∫dt/h (s/m, h — mixed-layer height) reaches {@code settled}:
     * Σ share·e^(−v·∫dt/h).
     */
    public static double airborne(double settled) {
        double sum = 0;
        for (int i = 0; i < SHARE.length; i++) sum += SHARE[i] * Math.exp(-SETTLE[i] * Math.max(settled, 0));
        return sum;
    }

    /**
     * Extinction share of the mixture still airborne at the same ∫dt/h: Σ k·share·e^(−v·∫dt/h) / {@link #K_MIX}.
     * Coarse bins settle first but contribute little extinction (k ∝ 1/D): after a minute, a meters-tall layer retains half
     * the mass but nearly all extinction. Dust mass supplied by a billow to an image with {@link #K_MIX} extinction uses
     * this share, not the mass share.
     */
    public static double extinctionAloft(double settled) {
        double sum = 0;
        for (int i = 0; i < SHARE.length; i++) sum += K[i] * SHARE[i] * Math.exp(-SETTLE[i] * Math.max(settled, 0));
        return sum / K_MIX;
    }

    /** Schlichting plate roughness K_s, m (Gaj &amp; Small, eq. 7). */
    static final double ROUGHNESS = 0.05;
    /** Soil enters suspension at u* this many entrainment thresholds (Owen 1964, via Gaj &amp; Small §3.2). */
    static final double SUSPEND = 10;
    /** Pre-front air density, kg/m³. */
    static final double AIR_DENSITY = 1.2;
    /** Entrainment integral nodes over the positive phase. */
    private static final int LIFT_STEPS = 200;
    /** Debris dust: starts at this ground pressure, psi, and reaches full at this one (house collapse). */
    static final double DEBRIS_FROM_PSI = 0.6, DEBRIS_FULL_PSI = 6;
    /** Visible layer top during blast phase, fraction of Kuhl's law (his dense core — 0.0256). */
    public static final double TOP_C = 0.1;
    /**
     * Layer above heated ground: entrainment A, flash energy fraction heating air f_h (review: 0.1–0.3), temperature increase
     * with height in stable morning air γ, K/m (3.5–9.8 K/km), air ρ·c_p, J/(m³·K).
     */
    static final double ENTRAIN = 0.2, HEAT_SHARE = 0.2, LAPSE = 0.005, RHO_CP = 1206;
    /**
     * Seconds for the layer to reach h_b (estimate: convective speed w* — 1.5 m/s at 1.4 km and ~4 m/s near the epicenter
     * for 15 kt; review §5 — 100–250 m per minute).
     */
    public static final double RISE_S = 60;
    /**
     * Layer above heated ground rises no higher than this (× ∛(Y/15)), m: near the epicenter flash fluence on ground is tens
     * of MJ/m² and the law would give kilometers; above it are the mushroom stem and cap. Estimate: the review gives no
     * epicenter top, at 1 km it gives 120–350 m — the cap is slightly above that estimate, with epicenter layer no lower than
     * at 1 km. For 15 kt it applies within ~530 m of an airburst (592 m) and ~420 m of a surface burst (at 1 km the law gives
     * 222 and 127 m); dust there is already drawn into the stem ({@link #STEM_M}).
     */
    static final double TOP_MAX = 400;
    /**
     * Fines leave with this many seconds of e-folding — dilution, wind transport, inflow to fires; fitted to Hiroshima
     * (Hersey: Fujii at 1.42 km — “about twenty minutes in the darkened morning”); fine settling takes hours.
     */
    static final double FINE_S = 800;
    /**
     * Stem inflow: within {@link #STEM_M}·∛(Y/15) m of the epicenter (weaker beyond halfway), from
     * {@link #STEM_FROM_S}·∛(Y/15) s for an airburst (immediate for a ground-touching ball; smooth between one and two ball
     * radii), e-folding over {@link #STEM_S} s. Estimate: Takakura at 300 m — “the sky began to lighten” after minutes.
     * The stem receives this dust ({@link StemDust}).
     */
    public static final double STEM_M = 1000, STEM_FROM_S = 10, STEM_S = 90;
    /** cal/cm² → J/m². */
    private static final double CAL_PER_CM2 = 4.184e4;

    private DustModel() {
    }

    /**
     * What lies on the ground: entrainment threshold u*ₜₕ (m/s, dry soil — Gillette 1988 in Gaj &amp; Small, table 1: loose
     * sand 0.28, loamy sand 0.34, clay 0.54, loam 0.78; grass and crops — from 3, §2.2), entrainable soil supply (kg/m²),
     * and debris dust where everything collapses (kg/m²).
     */
    public record Surface(double threshold, double supply, double debris) {
        /**
         * Dry loamy sand, supply — one centimeter of soil (15 kg/m²), city of wood, clay and plaster — 0.1 kg/m² debris dust
         * (fitted with {@link HobBlast} ground pressure and {@link DustModel#FINE_S}: 15 kt at 592 m gives darkness for ~20 minutes
         * at 1.4 km, twilight at 3.2 km, as in Hiroshima).
         */
        public static final Surface REFERENCE = new Surface(0.34, 15, 0.1);
        /** Same soil without debris dust — a field under a conventional strike. */
        public static final Surface FIELD = new Surface(REFERENCE.threshold, REFERENCE.supply, 0);
        /**
         * Soil beneath grass (turf): entrainment threshold from 3 m/s (Gaj &amp; Small, §2.2: grass and crops from 3), same
         * centimeter of supply. Hard material without soil is {@link #HARD}.
         */
        public static final Surface COVERED = new Surface(3.0, REFERENCE.supply, 0);
        /**
         * Hard material without soil on top (stone, brick, concrete, planks, metal, ice — roof or rock, no distinction): only
         * deposited dust is loose, 0.6 g/m² (EPA AP-42 §13.2.1, 2011, table 13.2.1-2: baseline pavement “silt loading” — 0.6 g/m²
         * for roads with fewer than 500 vehicles daily, 0.03–0.2 for busy roads; ⚠ roofs and squares treated like a quiet road).
         * It entrains easily — threshold as for loose soil (⚠ cohesion raises it for fine dust, but post-front wind is still
         * tens of times stronger).
         */
        public static final Surface HARD = new Surface(REFERENCE.threshold, 6e-4, 0);
    }

    /** Smooth 0..1 step from e0 to e1. */
    static double smooth(double e0, double e1, double x) {
        double f = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return f * f * (3 - 2 * f);
    }

    /** Destroyed share 0..1 by ground pressure, psi: smooth in log pressure from 0.6 psi to 6 (houses collapse). */
    public static double destroyed(double psi) {
        if (psi <= DEBRIS_FROM_PSI) return 0;
        return smooth(0, 1, Math.log(psi / DEBRIS_FROM_PSI) / Math.log(DEBRIS_FULL_PSI / DEBRIS_FROM_PSI));
    }

    /** B = 2.87 + 1.58·lg(x/K_s) at x m behind the front (no closer than 6 cm: plate law invalid closer in). */
    static double plate(double behindM) {
        return 2.87 + 1.58 * Math.log10(Math.max(behindM, 0.06) / ROUGHNESS);
    }

    /** Ground friction velocity under wind U, m/s, at x m behind the front. */
    static double friction(double windMs, double behindM) {
        return windMs / Math.sqrt(2) * Math.pow(plate(behindM), -1.25);
    }

    /** Suspension flux (u* ≥ 10 thresholds), kg/(m²·s): Gaj &amp; Small, eq. 8. */
    private static double suspended(double rho, double windMs, double b, double threshold) {
        return 1.80 * rho * Math.pow(windMs, 1.24) / (Math.pow(threshold, 0.24) * Math.pow(b, 2.80));
    }

    /**
     * Ground entrainment, kg/(m²·s): wind U (m/s) at x m behind the front, air density rho (kg/m³), entrainment threshold u*ₜₕ
     * (m/s). Zero below threshold; to 10 thresholds, saltation ∝ u*³(1 − u*ₜₕ²/u*²), continuous with suspension at 10 thresholds.
     */
    public static double flux(double rho, double windMs, double behindM, double threshold) {
        double b = plate(behindM), us = friction(windMs, behindM);
        if (us <= threshold) return 0;
        if (us >= SUSPEND * threshold) return suspended(rho, windMs, b, threshold);
        // wind giving u* exactly 10 thresholds here: saltation becomes suspension there
        double edge = SUSPEND * threshold, windAt = edge * Math.sqrt(2) * Math.pow(b, 1.25);
        double c = suspended(rho, windAt, b, threshold) / (edge * edge * edge * (1 - 1 / (SUSPEND * SUSPEND)));
        return c * us * us * us * (1 - threshold * threshold / (us * us));
    }

    /** Air density behind a front of pressure {@code kpa}, kg/m³ (Rankine–Hugoniot, γ = 1.4). */
    static double shockedAir(double kpa) {
        double p = 1 + Math.max(0, kpa) / BlastModel.P0;
        return AIR_DENSITY * (6 * p + 1) / (p + 6);
    }

    /**
     * Soil lofted over the positive phase (kg/m²) under front pressure {@code kpa}: entrainment by decaying ground-aligned
     * wind (dynamic pressure share {@code along}), at x = U_s·τ meters behind the front (midpoints of {@value #LIFT_STEPS}
     * steps); no supply limit. Estimate: wind blows over the pressure positive phase {@code positiveS}, decaying with its
     * shape (1 − x)·e^(−x), while the review (§8.1) links entrainment to the usually somewhat longer dynamic-pressure phase;
     * the model has no separate dynamic-pressure phase duration by burst height.
     */
    static double soil(double kpa, double along, double positiveS, double threshold) {
        return soil(kpa, along, positiveS, threshold, 0);
    }

    /**
     * The same with saltation developing over {@code develop} s (0 — immediate, as for a steady layer): saltation flux at t
     * behind the front is × (1 − e^(−t/develop)). Suspension (from 10 thresholds, Mirels — dust injection into the shock
     * boundary layer) has no delay.
     */
    static double soil(double kpa, double along, double positiveS, double threshold, double develop) {
        double u0 = BlastModel.windSpeed(kpa) * Math.sqrt(Math.max(0, along)), front = BlastModel.frontSpeed(kpa), rho = shockedAir(kpa);
        // flux law, but step-independent terms outside the loop: the client builds hundreds of layers at detonation.
        // Saltation c·u*³(1 − u*ₜₕ²/u*²), with c from suspension at 10 thresholds:
        // c·u*³ = salt·u*³/b^1.25 (b^1.24·1.25 / b^2.8 = 1/b^1.25)
        double edge = SUSPEND * threshold, low = Math.pow(threshold, 0.24);
        double salt = 1.80 * rho * Math.pow(edge * Math.sqrt(2), 1.24) / (low * edge * edge * edge * (1 - 1 / (SUSPEND * SUSPEND)));
        double m = 0, dt = positiveS / LIFT_STEPS;
        for (int i = 0; i < LIFT_STEPS; i++) {
            double t = (i + 0.5) * dt, x = t / positiveS, wind = u0 * (1 - x) * Math.exp(-x);
            double b = plate(front * t), bq = Math.pow(b, 1.25), us = wind / (Math.sqrt(2) * bq);
            // wind decays over the phase while the ground layer grows: once below threshold, stays so until phase end
            if (us <= threshold) break;
            double f = us >= edge ? 1.80 * rho * Math.pow(wind, 1.24) / (low * Math.pow(b, 2.80))
                    : salt * us * us * us * (1 - threshold * threshold / (us * us)) / bq * (develop > 0 ? 1 - Math.exp(-t / develop) : 1);
            m += f * dt;
        }
        return m;
    }

    /**
     * Lofted share by fraction s of the positive phase 0..1: dynamic-pressure impulse ∫(1 − x)²e^(−2x)dx (dynamic pressure
     * follows wind squared), divided by full phase impulse. Zero just behind the front, 1 at phase end; near the front grows
     * as 4.6·s. Estimate: this is the impulse shape, not the entrainment law used by {@link #soil} for total mass
     * (saltation ∝ u*³, suspension ∝ U^1.24, zero below threshold): that law decays faster at phase end, so actual lofted mass
     * near the wedge edge is greater. One shape for every range (head is a {@code BlastWall} wedge); the entrainment law
     * would give a different shape at each range.
     */
    public static double lifted(double s) {
        double x = Math.max(0, Math.min(1, s)), y = 1 - x;
        return (0.25 - Math.exp(-2 * x) * (y * y / 2 - y / 2 + 0.25)) / ((1 - Math.exp(-2)) / 4);
    }

    /**
     * Ground dust layer {@code groundM} m from the epicenter: burst of {@code yieldKt} at height {@code hobM}
     * (surface burst — {@code surfaceBurst}), air visibility {@code visibilityM} (flash light), surface {@code surface}.
     */
    public static Column column(double groundM, double hobM, double yieldKt, boolean surfaceBurst, double visibilityM, Surface surface) {
        double hob = Math.max(0, hobM), ground = Math.max(0, groundM);
        // flash fluence on flat ground: from the ball (no closer than its radius), with vertical cosine
        double ball = FireballModel.maxRadius(yieldKt, surfaceBurst), high = Math.max(hob, ball);
        double lit = Math.max(Math.hypot(ground, high), ball);
        double heat = ThermalModel.fluenceCalPerCm2(lit, yieldKt, surfaceBurst, visibilityM) * CAL_PER_CM2 * high / lit;
        return column(ground, hob, yieldKt, heat, ball, surface);
    }

    /**
     * Conventional charge (chemical HE) blast dust layer: same entrainment, Kuhl layer during blast phase, inflow and bins
     * as nuclear ({@link #column}), with nuclear yield giving the same ground pressure ({@code yieldKt}, see
     * {@code client.fx.smoke.CloudModel#equivalentKt}). HE has no ground-heating flash (fireball — milliseconds):
     * heated-ground top {@code buoyant} is zero; after the phase, air turbulence grows the layer — handled by the conventional
     * blast cloud. {@code ballM} is charge fireball radius, m (stem inflow, {@link Column#stemFrom}).
     * <p>
     * Law outside its data range: Gaj &amp; Small entrainment was derived for a steady boundary layer, whereas charges of
     * tens of kilograms have a milliseconds-long blast phase (nuclear and MISERS BLUFF experiments — tenths of a second);
     * at such times entrainment is probably overestimated. Surface is {@link Surface#REFERENCE} soil, as for nuclear blasts:
     * grass holds soil (entrainment threshold from 3 m/s) and gives less dust.
     */
    public static Column chemical(double groundM, double hobM, double yieldKt, double ballM) {
        return chemical(groundM, hobM, yieldKt, ballM, Surface.FIELD);
    }

    /** The same over {@code surface} ({@link Surface#FIELD} — bare soil, {@link Surface#COVERED} — grass, stone). */
    public static Column chemical(double groundM, double hobM, double yieldKt, double ballM, Surface surface) {
        return column(Math.max(0, groundM), Math.max(0, hobM), yieldKt, 0, ballM, surface, SALTATION_S);
    }

    /**
     * Saltation development e-folding time for a conventional charge, s: Gaj &amp; Small describes steady-layer flux, whereas
     * grains need to accelerate and start ejecting others (Anderson, Haff, “Simulation of eolian saltation”, Science 241,
     * 1988: flux approaches steady state in about a second; saturation length — meters, Andreotti et al., 2010).
     * ⚠ Estimate: e-folding in a quarter second. For charges of tens of kg, blast phase is milliseconds and saltation barely
     * develops (without this, the dust layer at 0.5 s was an opaque floor hundreds of meters across); suspension near the
     * charge (post-shock injection, Mirels) has no delay. Nuclear phase lasts 0.45–1.4 s, giving a 0.85–1 factor; this change
     * does not affect nuclear blasts (decision: nuclear unchanged), whose law remains steady-state.
     */
    public static final double SALTATION_S = 0.25;

    /** Dust layer by position and burst: {@code heat} — flash fluence on ground, J/m²; {@code ball} — ball radius, m. */
    private static Column column(double ground, double hob, double yieldKt, double heat, double ball, Surface surface) {
        return column(ground, hob, yieldKt, heat, ball, surface, 0);
    }

    /** The same with saltation developing over {@code develop} s ({@link #soil}). */
    private static Column column(double ground, double hob, double yieldKt, double heat, double ball, Surface surface, double develop) {
        double kpa = BlastModel.kpa(HobBlast.psi(ground, hob, yieldKt)), ys = Math.cbrt(yieldKt / 15);
        // entrainment wind uses the pressure positive phase, not dynamic pressure (estimate, soil)
        double positive = HobBlast.positivePhaseSeconds(ground, hob, yieldKt), along = HobBlast.alongGround(ground, hob, yieldKt);
        double soil = Math.min(surface.supply(), soil(kpa, along, positive, surface.threshold(), develop));
        double debris = surface.debris() * destroyed(BlastModel.psi(kpa));
        double first = waveTop(ground, hob, yieldKt, positive);
        double buoyant = Math.min(TOP_MAX * ys, Math.sqrt(2 * (1 + 2 * ENTRAIN) * HEAT_SHARE * heat / (RHO_CP * LAPSE)));
        return new Column(positive, soil, debris, first, buoyant, stemShare(ground, yieldKt), stemFrom(hob, yieldKt, ball), ground, hob, yieldKt);
    }

    /** Stem inflow share at {@code groundM} m from the epicenter ({@link Column#stem}): to {@link #STEM_M}·∛(Y/15), weaker beyond halfway. */
    public static double stemShare(double groundM, double yieldKt) {
        double ys = Math.cbrt(yieldKt / 15);
        return 1 - smooth(0.5 * STEM_M * ys, STEM_M * ys, groundM);
    }

    /**
     * Dust age (seconds since front arrival) when stem inflow starts ({@link Column#stemFrom}): axial ball downdraft
     * suppresses inflow in the first seconds for a burst whose ball (radius {@code ballM}) does not touch ground;
     * a ground-touching ball (surface and low burst) carries dust up immediately ({@link StemDust}).
     */
    public static double stemFrom(double hobM, double yieldKt, double ballM) {
        return STEM_FROM_S * Math.cbrt(yieldKt / 15) * smooth(ballM, 2 * ballM, Math.max(0, hobM));
    }

    /**
     * Blast-phase layer top at ground range {@code groundM} m from the epicenter and dust age {@code seconds} s (since front
     * arrival), m: Kuhl dusty boundary layer, h = c·R^(1/6)·x^(5/6), R — slant range (at least a meter), x — front's normal
     * path beyond the position ({@link #run}). Zero behind a fresh front.
     */
    public static double waveTop(double groundM, double hobM, double yieldKt, double seconds) {
        double slant = Math.max(1, Math.hypot(Math.max(0, groundM), Math.max(0, hobM)));
        return TOP_C * Math.pow(slant, 1 / 6.0) * Math.pow(run(groundM, hobM, yieldKt, seconds), 5 / 6.0);
    }

    /**
     * Front's normal path over {@code seconds} s after passing ground range {@code groundM} m from the epicenter, m:
     * Kuhl and Mirels's U_s·τ (constant front speed in their work) — how far the front itself traveled beyond the point;
     * the boundary layer grows from this length. Front decelerates: at a 15 kt surface burst's epicenter, 440 m over the
     * phase, not arrival speed × phase (2·10⁶ m). Under regular reflection from an airburst, the ground trace
     * ({@link #trace}) moves faster than the front (infinitely fast at the epicenter), while post-front air moves toward
     * the ground, with only cos α along it: trace path is not flow path. Normal path is ∫cos α over trace path
     * ({@link #normalPath}): in regular reflection, the incident wave's slant-range increase (15 kt at 592 m, beneath the
     * burst over the phase — 189 m versus 509 m trace); in the Mach region, the stem's ground path; between them,
     * {@link HobBlast#alongGround} transition. cos α is the root of the ground-aligned dynamic pressure share, as for
     * entrainment wind ({@link #soil}).
     */
    static double run(double groundM, double hobM, double yieldKt, double seconds) {
        if (seconds <= 0) return 0;
        double from = Math.max(0, groundM);
        return normalPath(from, from + trace(from, hobM, yieldKt, seconds), hobM, yieldKt);
    }

    /**
     * ∫cos α dx along the ground from {@code fromM} to {@code toM} m from the epicenter, m: cos α =
     * √{@link HobBlast#alongGround} — normal front advance per ground-trace step. In regular reflection (closer than 0.8 of
     * Mach region onset), cos α = x/√(x² + H²) and the integral is slant-range increase; in the Mach region cos α = 1;
     * in transition, Simpson's rule. For a surface burst, the path itself.
     */
    static double normalPath(double fromM, double toM, double hobM, double yieldKt) {
        double hob = Math.max(0, hobM);
        if (hob <= 0 || toM <= fromM) return Math.max(0, toM - fromM);
        double xm = HobBlast.machStem(hob, yieldKt), regular = 0.8 * xm, path = 0;
        if (fromM < regular) path += Math.hypot(Math.min(toM, regular), hob) - Math.hypot(fromM, hob);
        if (toM > xm) path += toM - Math.max(fromM, xm);
        double a = Math.max(fromM, regular), b = Math.min(toM, xm);
        if (b > a) {
            int n = 8;
            double h = (b - a) / n, sum = Math.sqrt(HobBlast.alongGround(a, hob, yieldKt)) + Math.sqrt(HobBlast.alongGround(b, hob, yieldKt));
            for (int k = 1; k < n; k++) sum += (k % 2 == 1 ? 4 : 2) * Math.sqrt(HobBlast.alongGround(a + k * h, hob, yieldKt));
            path += sum * h / 3;
        }
        return path;
    }

    /**
     * Ground-trace path over {@code seconds} s after passing {@code fromM} m from the epicenter, m (arrival —
     * {@link HobBlast#arrivalSeconds}). False position (Illinois), to 0.1 %.
     */
    static double trace(double fromM, double hobM, double yieldKt, double seconds) {
        if (seconds <= 0) return 0;
        double from = Math.max(0, fromM), target = HobBlast.arrivalSeconds(from, hobM, yieldKt) + seconds;
        // distant front travels at sound speed: path is no smaller; nearer, faster, so find the end by doubling
        double lo = 0, flo = -seconds, hi = BlastModel.SOUND_SPEED * seconds;
        double fhi = HobBlast.arrivalSeconds(from + hi, hobM, yieldKt) - target;
        while (fhi < 0) {
            lo = hi;
            flo = fhi;
            hi *= 2;
            fhi = HobBlast.arrivalSeconds(from + hi, hobM, yieldKt) - target;
        }
        double x = hi;
        int kept = 0;
        for (int i = 0; i < 60 && hi - lo > 1e-3 * hi; i++) {
            x = hi - fhi * (hi - lo) / (fhi - flo);
            double fx = HobBlast.arrivalSeconds(from + x, hobM, yieldKt) - target;
            if (fx == 0) return x;
            // endpoint retained two steps in succession has its value halved (otherwise false position crawls toward the root from
            // one side); kept: 1 — hi retained, −1 — lo
            if (fx > 0) {
                hi = x;
                fhi = fx;
                if (kept < 0) flo *= 0.5;
                kept = -1;
            } else {
                lo = x;
                flo = fx;
                if (kept > 0) fhi *= 0.5;
                kept = 1;
            }
        }
        return x;
    }

    /**
     * Ground range to which the blast lofts dust of depth {@code depth} or more (optical, at blast phase end), m; less dust
     * beyond. Burst and surface as in {@link #column}. To one meter: each step computes a full layer (entrainment integral),
     * and the client builds the pall at detonation ({@code PallField}).
     */
    public static double reach(double hobM, double yieldKt, boolean surfaceBurst, double visibilityM, Surface surface, double depth) {
        double hob = Math.max(0, hobM), far = HobBlast.rangeFor(0.1, hob, yieldKt);
        return Solve.decreasingRoot(r -> column(r, hob, yieldKt, surfaceBurst, visibilityM, surface).lifted(), depth, 1, Math.max(far, 2), 1);
    }

    /**
     * Dust layer at one position: local positive phase t₊ (s), lofted soil and debris dust (kg/m²), top at blast phase end
     * (m, {@link #waveTop}), top above heated ground h_b (m), stem inflow share 0..1 and onset (s); position itself — ground
     * range from epicenter and burst height (m), yield (kt): determine the blast-phase top. Age is model seconds since front
     * arrival.
     */
    public record Column(double positive, double soil, double debris, double first, double buoyant, double stem, double stemFrom,
                         double ground, double hob, double yieldKt) {
        /** All lofted dust, kg/m². */
        public double mass() {
            return soil + debris;
        }

        /** Top the layer grows to, m. */
        public double ceiling() {
            double h = first();
            return Math.sqrt(h * h + buoyant * buoyant);
        }

        /**
         * Layer top at age {@code age} s, m: during blast phase, Kuhl dust layer by front path beyond the point at this age
         * ({@link #waveTop}; at phase end, {@link #first}); afterward, above heated ground.
         */
        public double height(double age) {
            if (age <= 0) return 0;
            if (age <= positive) return waveTop(ground, hob, yieldKt, age);
            double h = first(), x = Math.min((age - positive) / RISE_S, 1);
            return Math.sqrt(h * h + buoyant * buoyant * x);
        }

        /**
         * ∫dt/h from blast phase end to age {@code age} (s/m): bin settling follows e^(−v·∫dt/h). While the layer grows,
         * h² = h₁² + b·x, integral 2(√(h₁² + b·x) − h₁)/b; afterward, constant height.
         */
        double settled(double age) {
            if (age <= positive) return 0;
            double h = first(), x = age - positive, b = buoyant * buoyant / RISE_S;
            if (b < 1e-9) return x / h;
            if (x <= RISE_S) return 2 * (Math.sqrt(h * h + b * x) - h) / b;
            double top = ceiling();
            return 2 * (top - h) / b + (x - RISE_S) / top;
        }

        /** Share not yet removed by dilution or stem inflow at {@code age}. */
        double left(double age) {
            return Math.exp(-Math.max(age - positive, 0) / FINE_S) * Math.exp(-stem * Math.max(age - stemFrom, 0) / STEM_S);
        }

        /** Mass of bin i above ground at {@code age}, kg/m². */
        double bin(int i, double age, double settled, double left) {
            return SHARE[i] * mass() * DustModel.lifted(age / positive) * Math.exp(-SETTLE[i] * settled) * left;
        }

        /** Layer optical depth above ground (vertical) at {@code age}: Σ k·m. */
        public double depth(double age) {
            if (age <= 0) return 0;
            double j = settled(age), left = left(age), tau = 0;
            for (int i = 0; i < K.length; i++) tau += K[i] * bin(i, age, j, left);
            return tau;
        }

        /** Depth of everything lofted (without settling or removal), the layer at blast phase end: K·M. */
        public double lifted() {
            return K_MIX * mass();
        }

        /** Ground deposition rate at {@code age}, kg/(m²·s): Σ v·m/h. */
        public double fallout(double age) {
            double h = height(age);
            if (h <= 0) return 0;
            double j = settled(age), left = left(age), f = 0;
            for (int i = 0; i < K.length; i++) f += SETTLE[i] * bin(i, age, j, left);
            return f / h;
        }

        /** Share of bin i still airborne at {@code age} (without fine removal): settling. */
        double airborne(int i, double age) {
            return Math.exp(-SETTLE[i] * settled(age));
        }
    }
}
