package ua.zentix.incoming.nuclear.model;

/**
 * Volume mushroom (DESIGN-nuke, steps M1, M2): fireball and cap shape, dome, stem and skirt, their matter and temperature over
 * time. Meters, seconds, kilotons; heights above ground beneath epicenter. Cap is the fireball itself ({@link FireballModel}:
 * radius, rise), rolling into a vortex ring beneath a dome ({@link #rollUpSeconds}) as one shape; its
 * subsequent top, bottom and radius follow {@link CloudModel}, spreading after stabilization and wind drift follow the old mushroom
 * billows. Client (shader field) reads these numbers once per frame.
 */
public final class MushroomModel {
    /** Ambient air temperature, K. */
    public static final double AMBIENT_K = FireballModel.AMBIENT_K;
    /**
     * Ring-core cooling exponent from mixing after rollup: ΔT ∝ (t/t_roll)^−0.27; at night the 15 kt core glows
     * for about two and a half minutes, the 1 kt core for about half a minute.
     */
    static final double CORE_COOLING = 0.27;
    /** Ring rollup time in units of √(R/g): 2–3 (Turner 1957, Morton–Taylor–Turner 1956). */
    static final double ROLL_UP = 2.5;
    static final double G = 9.81;
    /** Cap-ring major radius as a fraction of cap radius (as for the old cap billows). */
    public static final double RING = 0.55;
    /**
     * Cap matter is droplets and NO₂ (G&D §2.08: cloud initially red or reddish brown from nitrogen oxides, whitening as
     * water condenses; Taylor and Minge 1963, AFSC 61-142-1, pp. 201–202: reflected cloud light is white from droplets,
     * transmitted light reddish brown from NO₂, interior light brown). Color and optical depth come from these two, without a clock: cloud reflection
     * is droplet scattering versus NO₂ absorption along the light path, and NO₂ dilutes as the cap grows. Physics gives:
     * rolled ring is ochre in reflected light (15 kt on screen ~0.60 0.48 0.31, hue 35°), transmitted light
     * reddish brown (17°); after a minute or two the exterior is almost white. Reflected ring light is never reddish brown at
     * any NO₂ share: with strong absorption, reflection ∝ ω' ∝ 1/σ per channel, hue 29°, darker but not redder.
     * <p>
     * Droplets: extinction σ = 3·LWC/(2·ρw·r_e), cloud optical depth τ = 3·LWP/(2·ρw·r_e) (Stephens 1978) per unit path,
     * with droplet cross-section twice its area (droplets much larger than wavelength). <b>(est.)</b> (no cap water-content measurements):
     * water content {@link #WATER} 0.5 g/m³ is the middle of cumulus 0.1–1 g/m³; effective radius {@link #DROP_RADIUS} 10
     * µm matches cumulus. Stabilized 15 kt cap (1.7 km across ring cross-section) has τ ≈ 130, 1 kt (0.6 km) ≈ 47.
     * Droplets arrive with air entrained as the cap rolls into a ring: fireball is hot gas without droplets, droplet
     * share follows ring share {@link #rollUp} ((est.): sources give no condensation time).
     */
    static final double WATER = 0.5e-3, DROP_RADIUS = 10e-6, WATER_DENSITY = 1000;
    /** Cap-droplet extinction, 1/m (0.075): {@code 3·WATER/(2·WATER_DENSITY·DROP_RADIUS)}. */
    public static final double DROP_SIGMA = 1.5 * WATER / (WATER_DENSITY * DROP_RADIUS);
    /**
     * NOx molecules per kiloton: 10²⁹, NRC 1985 (“The Effects on the Atmosphere of a Major Nuclear Exchange”,
     * ch. 6, pp. 107–126: 10³² NO molecules per megaton); Gilmore 1975 (JGR 80, 4553), 0.8·10³² from shock heating,
     * up to 1.5·10³² with mixing. (est.): no low-yield measurements; law is proportional to yield.
     */
    static final double NOX_PER_KT = 1e29;
    /**
     * NO₂ fraction of cap NOx, (est.): no measurements. Oxidation 2NO + O₂ during 15 kt cap rise gives about 2 % (rate
     * ∝ [NO]², while NO dilutes as cap grows); ozone and oxygen atoms near fireball (G&D §2.123) give more. 0.1 is checked against
     * observations ({@code MushroomFieldTest}): stabilized 15 kt cap is nearly white in reflected light, transmitted light
     * brown (Taylor and Minge), light through rolled ring reddish brown (G&D). At 0.3 the stabilized cap would remain
     * beige (blue 0.72 of red); at 0.03 light through ring would be orange-brown (hue 31°), not reddish brown.
     */
    static final double NO2_SHARE = 0.1;
    /**
     * NO₂ absorption cross-section per frame channel R, G, B, m² per molecule: Bogumil et al. 2003 (J. Photochem. Photobiol. A 157,
     * 167), 243 K (air at cap height), averaged across channel band, positive part of sRGB function (CIE 1931 → sRGB)
     * × sunlight (Planck 5 800 K). Vandaele et al. 1998 (294 K) agrees within 2 %. NO₂ absorbs eight times more strongly in blue
     * than red, hence brown.
     */
    public static final double[] NO2_CROSS = {5.74e-24, 1.33e-23, 4.64e-23};
    /** Skirt optical depth across a low burst (stem uses dust mass, {@link StemDust}). */
    static final double TAU_SKIRT = 2;
    /** Ground-dust settling e-folding time, s: airburst, surface burst (as for dust wall). */
    public static final double DUST_SETTLE_AIR = 90, DUST_SETTLE_SURFACE = 240;
    /**
     * Stem radius as a fraction of cap radius by yield (Glasstone and Dolan 2.17): about half up to 20 kt, decreasing
     * with yield; at megatons the stem is 5–10 times narrower than cloud. Linear interpolation in log yield between nodes.
     */
    private static final double[] STEM_Y = {20, 1000, 10000};
    private static final double[] STEM_SHARE = {0.5, 0.2, 0.1};
    /**
     * Stem beneath fireball before ring rollup is this fraction of fireball radius: the rising fireball's trail; no ring yet
     * to pull it wider; rollup transitions toward {@link #stemShare}.
     */
    static final double BALL_STEM = 0.16;
    /** Stem bottom before ground dust settles is at least this fraction of cap radius: dust flare ({@link #dustFlare}). */
    static final double DUST_BASE = 0.376;

    private MushroomModel() {}

    /**
     * Mushroom at time t.
     *
     * @param life      mushroom fraction, 0..1: full until halfway through post-stabilization dispersion, zero by its end
     *                   ({@link #lifeSeconds})
     * @param top       cloud top (fireball top while spherical)
     * @param capBottom cap bottom (fireball bottom while spherical)
     * @param capY      cap height midpoint (fireball center while spherical)
     * @param capR      horizontal cap radius to ring edge (fireball radius while spherical)
     * @param ringR     ring major radius (0 while still a fireball)
     * @param ringA     horizontal semiaxis of ring cross-section
     * @param ringB     vertical semiaxis of cross-section
     * @param domeY     center of dome above ring
     * @param domeR     horizontal dome semiaxis
     * @param domeH     vertical dome semiaxis
     * @param capDrops  cap-droplet extinction, 1/m ({@link #DROP_SIGMA} × ring share × mushroom fraction, diluting with
     *                   area as it spreads)
     * @param capNo2    NO₂ molecules per cubic meter of cap (all detonation NO₂ in its volume, {@link #capVolume}, × mushroom fraction)
     * @param stemTop   stem top (catches up with cap bottom)
     * @param stemR     height-averaged stem radius ({@link #stemShare} of cap radius; wider near cap, dust flare at ground)
     * @param stemTau   stem optical depth across (including visibility and settling, excluding flare)
     * @param lowDust   ground-dust fraction, 0..1 (settling): stem flare and skirt
     * @param skirtR    skirt midpoint radius along ground
     * @param skirtW    skirt half-width along ground
     * @param skirtH    skirt height
     * @param skirtTau  skirt optical depth across (including settling)
     * @param endTop    stabilized cloud top: wind drift uses fraction of this height
     */
    public record Shape(double t, double life, double top, double capBottom, double capY, double capR, double ringR, double ringA,
                        double ringB, double domeY, double domeR, double domeH, double capDrops, double capNo2, double stemTop, double stemR,
                        double stemTau, double lowDust, double skirtR, double skirtW, double skirtH, double skirtTau, double endTop) {}

    /**
     * Richardson–Obukhov constant: particle pairs in turbulence separate as ⟨r²⟩ = g·ε·t³, g ≈ 0.5 (Ott and Mann 2000,
     * JFM 422, 207; Sawford 2001, Annu. Rev. Fluid Mech. 33, 289).
     */
    static final double RICHARDSON = 0.5;
    /**
     * Turbulent-energy dissipation rate at cloud height, m²/s³. <b>(est.)</b>: no test-cloud measurements.
     * Chosen for a 15 kt mushroom lifetime near the old one (27 minutes, formerly 24): largest round value for which
     * lifetime increases with yield everywhere. Above ε = 4.2·10⁻³, just above 1 kt stabilization shortens (Wasp τ twice
     * Grable's) faster than dispersion lengthens, so a 1.2 kt mushroom would live seconds less than a 1 kt mushroom (the
     * old 24 minutes for 15 kt gave ε = 6.2·10⁻³). Calm free troposphere ε is 10⁻⁵–10⁻⁴; cumulus clouds and
     * light turbulence 10⁻³–10⁻²: the stabilized cloud remains turbulent itself. Check: 1 Mt cloud visible 68 minutes,
     * “about an hour or more” (Glasstone and Dolan, §2.15).
     */
    static final double DISSIPATION = 4e-3;

    /**
     * Mushroom visibility duration, s: rises until stabilization ({@link CloudModel#stabilizationSeconds}), then atmospheric
     * turbulence disperses it ({@link #dispersalSeconds}): 0.1 kt 17 minutes, 1 kt 22, 15 kt 27, 1 Mt 68. Dispersion
     * increases with cloud size, not with how slowly it rose.
     */
    public static double lifeSeconds(double yieldKt) {
        return CloudModel.stabilizationSeconds(yieldKt) + dispersalSeconds(yieldKt);
    }

    /**
     * Time for turbulence to disperse the stabilized cloud, s: particle pairs separate by stabilized cap radius
     * R ({@link CloudModel#capRadius}), Richardson–Obukhov relative dispersion, ⟨r²⟩ = g·ε·t³, giving
     * t = (R²/(g·ε))^⅓ ({@link #RICHARDSON}, {@link #DISSIPATION}): 1 kt 10.6 minutes, 15 kt 21, 1 Mt 61.
     */
    public static double dispersalSeconds(double yieldKt) {
        double r = CloudModel.capRadius(yieldKt);
        return Math.cbrt(r * r / (RICHARDSON * DISSIPATION));
    }

    /**
     * Time for hot bubble to roll into a vortex ring, s: 2.5·√(R/g), R is fireball radius. A bubble with a density difference
     * of order its own density becomes a ring after traveling one or two radii: buoyancy time, not
     * fireball luminosity time; 15 kt ~12 s, 1 kt ~7 s.
     */
    public static double rollUpSeconds(double yieldKt, boolean surface) {
        return ROLL_UP * Math.sqrt(FireballModel.maxRadius(yieldKt, surface) / G);
    }

    /** Ring share, 0..1: 0 fireball, 1 ring (smoothly from a quarter of rollup time to its end). */
    public static double rollUp(double t, double yieldKt, boolean surface) {
        double tr = rollUpSeconds(yieldKt, surface);
        return smooth(0.25 * tr, tr, t);
    }

    /**
     * Heating at rolled cap edge as fraction of core temperature difference: edge mixes with entrained air (Morton–Taylor–Turner
     * thermal: ring core is hottest).
     */
    public static final double EDGE_HEAT = 0.35;
    /**
     * Billow tongues at edge: edge heating ± this fraction of itself, per billow amplitude ({@code MUSH_SKIN} in {@code mushroom.glsl}):
     * billow protrusion is fresh hot gas from inside, trough already mixed. Ring is ±9 % of core temperature difference; fireball heat
     * pattern is baked ({@code client/nuclear/NukeBallSurface}), billow tongues only its fraction during rollup
     * (or full when pattern cannot be read).
     */
    public static final double SKIN = 0.09 / EDGE_HEAT;
    /**
     * Fireball edge-billow amplitude as fraction of ring amplitude: while growing, edge is a smooth sphere (radiation
     * and shock front); billows grow with entrained air as it rises and rolls up.
     */
    static final double BALL_BILLOWS = 0.3;

    /**
     * Core temperature-difference share at visible edge, 0..1: full for fireball (nearly isothermal: internal light equalizes
     * temperature), mixing with air during rollup down to {@link #EDGE_HEAT}.
     */
    public static double edgeShare(double t, double yieldKt, boolean surface) {
        return 1 - (1 - EDGE_HEAT) * rollUp(t, yieldKt, surface);
    }

    /** Edge-billow amplitude relative to ring, 0..1: fireball {@link #BALL_BILLOWS}, growing with rollup. */
    public static double billows(double t, double yieldKt, boolean surface) {
        return BALL_BILLOWS + (1 - BALL_BILLOWS) * rollUp(t, yieldKt, surface);
    }

    /**
     * Apparent exterior fireball/cap temperature, K: core difference × {@link #edgeShare}. While spherical it uses fireball temperature
     * ({@link FireballModel#temperature}); after rollup, exterior darkens: edge mixes with air, core remains deep inside
     * cap. Optical depth across ring cross-section (σ' in green, {@code MushroomField.capMatter}) from rollup to
     * stabilization: 1 kt 5–13, 15 kt 11–36, 1 Mt 37–140; depth to core is half: 1 kt core shines through at night,
     * megaton core does not. Shader attenuates core glow toward eye along the direct path, e^(−τ'); two-stream transport would
     * also release scattered light as e^(−kτ') ({@code PallLight.k}): in red at 30 s, 1 kt would be three times brighter, 15 kt
     * ~200 times (not implemented, DESIGN-nuke). 15 kt reaches ~1 250 K at 9 s, ~830 K at 12 s.
     */
    public static double edgeTemperature(double t, double yieldKt, boolean surface) {
        return AMBIENT_K + (coreTemperature(t, yieldKt, surface) - AMBIENT_K) * edgeShare(t, yieldKt, surface);
    }

    /**
     * Cap-core temperature, K: until rollup ends the cap is the fireball itself ({@link FireballModel#temperature}); then the ring
     * cools by mixing with air: T_ambient + ΔT_roll·(t/t_roll)^−0.27. Continuous through rollup.
     */
    public static double coreTemperature(double t, double yieldKt, boolean surface) {
        double tr = rollUpSeconds(yieldKt, surface);
        if (t <= tr) return FireballModel.temperature(t, yieldKt);
        double rolled = FireballModel.temperature(tr, yieldKt) - AMBIENT_K;
        return AMBIENT_K + rolled * Math.pow(t / tr, -CORE_COOLING);
    }

    /**
     * Fireball/cap light on a white wall {@code distanceM} meters from cap center, in fractions of a white wall at noon (without
     * eye adaptation): a Lambertian sphere of cap radius at black-body brightness for its apparent temperature illuminates as
     * L·R²/d² (L at surface). Light source for surroundings: stem, skirt, dust, smoke, sky.
     */
    public static double light(Shape s, double yieldKt, boolean surface, double distanceM) {
        double r = s.capR();
        return BlackBody.luminance(edgeTemperature(s.t(), yieldKt, surface)) * r * r / Math.max(distanceM * distanceM, r * r);
    }

    /**
     * Cap volume, m³: spheroid of its radius and height, (2/3)·π·R²·h; fireball gives its own volume, rolled ring with dome
     * is 5 % less than the sum of torus and half-dome.
     */
    public static double capVolume(Shape s) {
        return volume(s.capR(), s.top() - s.capBottom());
    }

    private static double volume(double r, double h) {
        return 2.0 / 3 * Math.PI * r * r * h;
    }

    /** Cap NO₂ absorption in frame channel c (0 R, 1 G, 2 B), 1/m. */
    public static double capAbsorption(Shape s, int c) {
        return s.capNo2() * NO2_CROSS[c];
    }

    /**
     * Soil fraction in cap matter, 0..1. Fireball touching ground (surface burst; airburst no higher than fireball radius) means flows
     * beneath cap draw lofted ground into it, making cooled cloud dust-gray; above two radii cap has only
     * condensate and bomb vapor, whitening like an ordinary cloud (G&D 2.08); smooth between, like stem catching up with
     * cap ({@link #shape}).
     */
    public static double soil(double yieldKt, double hobM, boolean surface) {
        if (surface) return 1;
        double rf = FireballModel.maxRadius(yieldKt, false);
        return 1 - smooth(rf, 2 * rf, Math.max(0, hobM));
    }

    /**
     * Mushroom at t (seconds after detonation) for yield {@code yieldKt} at height {@code hobM} above ground;
     * {@code groundFrontM} is ground-front reach (client uses its front profile). Cap is the fireball
     * ({@link FireballModel#radius}, {@link FireballModel#centreHeight}), rolling by {@link #rollUp} into cap
     * ring: shape numbers move from fireball to cap by one fraction, without a jump. Cap, stem and skirt laws are those of the old mushroom billows.
     * null before mushroom exists or after it has dispersed.
     */
    public static Shape shape(double t, double yieldKt, double hobM, boolean surface, double groundFrontM) {
        return shape(t, yieldKt, hobM, surface, groundFrontM, StemDust.of(yieldKt, hobM, surface, StemDust.CLEAR));
    }

    /**
     * Same with detonation stem dust ({@link StemDust}: visibility controls flash ground heating); null while table is still
     * being calculated, stem has no dust.
     */
    public static Shape shape(double t, double yieldKt, double hobM, boolean surface, double groundFrontM, @org.jetbrains.annotations.Nullable StemDust dust) {
        if (t <= 0) return null;
        double stab = CloudModel.stabilizationSeconds(yieldKt);
        // dispersion: cloud fully visible for first half, fading during second ((est.): same look as the old lifetime
        // of 4 stabilizations, fully visible to 2.5)
        double life = clamp(2 * (1 - Math.max(0, t - stab) / dispersalSeconds(yieldKt)), 0, 1);
        if (life <= 0) return null;
        double hob = Math.max(0, hobM);
        double rf = FireballModel.maxRadius(yieldKt, surface);
        double start = CloudModel.start(hob, yieldKt, surface), endTop = CloudModel.end(hob, yieldKt, surface);
        double top = CloudModel.top(t, hob, yieldKt, surface);
        double capBot = Math.max(hob, CloudModel.capBottom(t, hob, yieldKt, surface));
        double rise = clamp((top - start) / Math.max(1, endTop - start), 0, 1);
        double spread = 1 + 0.25 * clamp((t - stab) / stab, 0, 2);
        double capR = (rf * 1.1 + (CloudModel.capRadius(yieldKt) - rf * 1.1) * Math.pow(rise, 0.8)) * spread;
        // cap no lower than fireball: while CloudModel top is lower, cap top follows its own height
        double capH = Math.max(rf, top - capBot);
        top = capBot + capH;
        // Cap is thermal's vortex ring under dome. Ring: major radius grows from zero (fireball) to fraction of cap radius,
        // edge at cap radius; vertically from cap bottom nearly to top (center capY). Dome is above ring hole,
        // up to cap top; recess underneath near axis receives stem (ring draws air upward through hole).
        // Billows extend beyond these edges by fraction of smaller ring-cross-section semiaxis (MushroomField.REACH_CAP)
        // Cap is fireball rolled into a ring: until quarter of rollup time it is the fireball sphere (no ring, dome
        // inside), then every shape number moves from fireball to cap by same roll share: one shape, no image swap.
        // Surface-burst fireball is a hemisphere: center at ground, bottom below it
        double roll = rollUp(t, yieldKt, surface);
        double ballR = FireballModel.radius(t, yieldKt, surface), ballY = FireballModel.centreHeight(t, hob, yieldKt, surface);
        capR = lerp(ballR, capR, roll);
        capBot = lerp(ballY - ballR, capBot, roll);
        capH = lerp(2 * ballR, capH, roll);
        top = capBot + capH;
        double capY = lerp(ballY, capBot + 0.45 * capH, roll);
        double ringR = RING * capR * roll, ringA = capR - ringR, ringB = lerp(ballR, 0.4 * capH, roll);
        double domeR = 0.6 * capR * Math.sqrt(Math.max(roll, 0.05)), domeH = 0.35 * capH, domeY = lerp(ballY, capBot + 0.6 * capH, roll);
        double tau = t / FireballModel.secondMaximumSeconds(yieldKt);
        // droplets arrive with entrained air as fireball rolls into ring; spreading into an anvil, cap dilutes with
        // area. Detonation NO₂ fills its entire volume: fireball (green τ across at one second ≈ 8 for 1 kt, 15 for 15 kt, 52 for
        // 1 Mt) is opaque from it
        double capDrops = DROP_SIGMA * roll * life / (spread * spread);
        double capNo2 = NO2_SHARE * NOX_PER_KT * yieldKt * life / Math.max(volume(capR, capH), 1);
        // stem enters recess beneath dome (air rises through ring; at kilotons stem is wider than dome, occupying entire
        // cap bottom inside ring). Beneath fireball it is the trail, widening to G&D fraction during rollup. Matter is dust
        // drawn from the near-epicenter layer by inflow (StemDust): for surface burst fireball touches ground, stem is its trail without
        // a gap beneath cap; for airburst dust rises with stem air from inflow start and may never catch cap,
        // leaving a dust column suspended beneath it (NRC 1985, p. 22)
        double stemR = capR * lerp(BALL_STEM, stemShare(yieldKt), roll);
        double stemTop = hob <= rf ? capBot + 0.3 * capH : dust == null ? 0 : Math.min(capBot + 0.3 * capH, dust.top(t));
        double stemTau = dust == null ? 0 : dust.tau(t, stemR) * smooth(4, 20, tau) * life;
        double lowDust = Math.exp(-Math.max(0, t - stab * 0.1) / (hob <= rf ? DUST_SETTLE_SURFACE : DUST_SETTLE_AIR));
        // skirt is dust at stem base lofted behind front: edge without billows at 0.85 ground-front path (billows
        // reach it), no farther than 0.7 cap radius (outside flared stem bottom, DUST_BASE, also 0.43 at kilotons). Before front
        // reaches ground (airburst) nothing can lift it, so no skirt; cross-section at least a meter (skirt billow unit divides by it)
        double outer = Math.min(Math.max(0, groundFrontM), capR * 0.7);
        double skirtR = 0.6 * outer, skirtW = Math.max(1, 0.25 * outer);
        double skirtH = Math.max(1, Math.min(capBot * 0.12, 350 * Math.cbrt(yieldKt / 15)) * smooth(0, 30, t));
        double skirtTau = outer <= 0 ? 0 : TAU_SKIRT * smooth(0.5, 3, t) * (hob <= rf * 4 ? 1 : 0.5) * lowDust * life;
        return new Shape(t, life, top, capBot, capY, capR, ringR, ringA, ringB, domeY, domeR, domeH, capDrops, capNo2, stemTop,
                stemR, stemTau, lowDust, skirtR, skirtW, skirtH, skirtTau, endTop);
    }

    /**
     * Stem radius as fraction of cap radius (Glasstone and Dolan 2.17): up to 20 kt 0.5, by 1 Mt 0.2, by 10 Mt and above 0.1
     * (stem 5–10 times narrower than cloud).
     */
    public static double stemShare(double yieldKt) {
        if (yieldKt <= STEM_Y[0]) return STEM_SHARE[0];
        for (int i = 1; i < STEM_Y.length; i++) {
            if (yieldKt <= STEM_Y[i]) {
                double f = Math.log(yieldKt / STEM_Y[i - 1]) / Math.log(STEM_Y[i] / STEM_Y[i - 1]);
                return STEM_SHARE[i - 1] + (STEM_SHARE[i] - STEM_SHARE[i - 1]) * f;
            }
        }
        return STEM_SHARE[STEM_SHARE.length - 1];
    }

    /**
     * Ground dust flare beyond stem bottom, m: dust drawn by near-ground upflow comes from a circle of
     * {@link #DUST_BASE} cap radius (lofted by blast wave, not stem) and settles with {@code lowDust}. If stem is wider
     * than this circle (kilotons after rollup), no flare: otherwise it would cover skirt.
     */
    public static double dustFlare(Shape s) {
        return Math.max(0, DUST_BASE * s.capR() - stemRadius(0, s.stemR(), 0)) * s.lowDust();
    }

    /**
     * Cap-matter circulation rate around cross-section, rad/s: vortex-ring matter travels around cross-section at cloud
     * rise speed (Turner 1957: ring fluid moves at speed of order its rise), following an ellipse of cross-section
     * a × b, length about 2π·√((a² + b²)/2). For 15 kt, one turn takes about 25 s at 20 s and about three minutes by the second
     * minute; cap stops by stabilization. Before fireball rolls into ring there is no circulation axis: shader rotates pattern
     * by rollup share, while billows already churn by this clock (fireball bubble mixes just as fast).
     */
    public static double churnRate(Shape s, double riseSpeed) {
        double mean = Math.sqrt(0.5 * (s.ringA() * s.ringA() + s.ringB() * s.ringB()));
        return riseSpeed / Math.max(mean, 1);
    }

    /**
     * Cap-churn clock: ψ(t) is integral of {@link #churnRate} from detonation, rad (radians of cross-section traversed by
     * cap matter). Function of detonation age: replay seek and late entry produce same cap. Table grid
     * t = T·(i/N)² (denser initially, where rise is faster), T is three stabilizations: beyond, rise is 10⁴ times weaker than initial,
     * so clock stops.
     */
    public static Churn churn(double yieldKt, double hobM, boolean surface) {
        double end = 3 * CloudModel.stabilizationSeconds(yieldKt), hob = Math.max(0, hobM);
        double[] turns = new double[CHURN_NODES + 1];
        double last = 0, prev = 0;
        for (int i = 1; i <= CHURN_NODES; i++) {
            double t = end * i * i / ((double) CHURN_NODES * CHURN_NODES);
            // churn clock needs only ring shape, no stem dust (its table is calculated in background)
            Shape s = shape(t, yieldKt, hob, surface, 0, null);
            double rate = s == null ? 0 : churnRate(s, CloudModel.riseSpeed(t, hob, yieldKt, surface));
            // at detonation (t = 0) cap does not yet exist; circulation follows first node
            if (i == 1) last = rate;
            turns[i] = turns[i - 1] + 0.5 * (last + rate) * (t - prev);
            last = rate;
            prev = t;
        }
        return new Churn(end, turns);
    }

    /** Churn-clock table node count. */
    static final int CHURN_NODES = 256;

    /** Churn clock for one detonation ({@link #churn}): {@code turns[i]} at time end·(i/N)². */
    public static final class Churn {
        private final double end;
        private final double[] turns;

        Churn(double end, double[] turns) {
            this.end = end;
            this.turns = turns;
        }

        /** Table end, s: clock stops beyond. */
        public double end() {
            return end;
        }

        /** Clock at time t, rad: linear between nodes, stationary after table end. */
        public double at(double t) {
            int n = turns.length - 1;
            if (t <= 0) return 0;
            if (t >= end) return turns[n];
            double x = n * Math.sqrt(t / end);
            int i = Math.min((int) x, n - 1);
            double t0 = end * i * i / ((double) n * n), t1 = end * (i + 1) * (i + 1) / ((double) n * n);
            return turns[i] + (turns[i + 1] - turns[i]) * (t - t0) / (t1 - t0);
        }
    }

    /**
     * Stem radius at height fraction w (0 ground, 1 cap bottom): height average {@code stemR}, one third wider near
     * cap (pulled by ring: at 2 kt cap edge extends beyond stem by a third of its radius), with ground dust flare
     * {@code flare} beyond stem bottom ({@link #dustFlare}). Same as {@code mushStemR} in {@code mushroom.glsl}.
     */
    public static double stemRadius(double w, double stemR, double flare) {
        double x = clamp(w, 0, 1), base = Math.pow(1 - x, 6);
        return stemR * (0.85 + 0.45 * x * x) + flare * base;
    }

    /**
     * Stem air speed is this fraction of cloud rise speed ({@link #stemRise}). <b>(est.)</b>: no stem-flow
     * measurements; number unchanged.
     */
    public static final double STEM_INFLOW = 0.3;

    /**
     * Stem air rise by time t, m: ring draws it up at {@link #STEM_INFLOW} fraction of cloud rise
     * speed ({@link CloudModel#riseSpeed}), path is its integral, same fraction of top rise. Stem jets rise along it
     * ({@code stemRise} in {@code mushroom.glsl}) and stop when cloud stabilizes: 15 kt rises
     * 1.1 km during first minute (initially 24 m/s), total 2.9 km; 1 kt 0.4 km (7 m/s), total 1.7 km. Previously jets used
     * speed at detonation throughout mushroom lifetime, even above the stabilized cloud.
     */
    public static double stemRise(double t, double hobM, double yieldKt, boolean surface) {
        return STEM_INFLOW * (CloudModel.top(t, hobM, yieldKt, surface) - CloudModel.top(0, hobM, yieldKt, surface));
    }

    /** Wind drift at height h as fraction of wind path over time t: top drifts farther than bottom (as with old billows). */
    public static double driftShare(double h, double endTop) {
        return clamp(h / Math.max(endTop, 1), 0, 1);
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    static double smooth(double e0, double e1, double x) {
        double f = clamp((x - e0) / (e1 - e0), 0, 1);
        return f * f * (3 - 2 * f);
    }

    static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
