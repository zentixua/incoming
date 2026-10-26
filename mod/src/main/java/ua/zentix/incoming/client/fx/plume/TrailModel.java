package ua.zentix.incoming.client.fx.plume;

import net.minecraft.util.Mth;
import ua.zentix.incoming.util.Stability;

import ua.zentix.incoming.util.Air;

import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.util.StandardAtmosphere;

/**
 * Engine trail — one law for all ({@link Trail} — trail spec, {@link Motor} — smoke source).
 * <ul>
 * <li><b>Mass per metre</b>: condensed products ṁ_s = fuel flow × product fraction ({@link Motor#smokeFlow}); the projectile
 * lays the trail at its speed v (entity or track), λ = ṁ_s/v. This is a line-source trail while the projectile
 * outruns wind ({@link #laid}); below wind speed, wind carries products from an almost stationary source — a point-source plume,
 * launcher cloud, rather than a trail (drawn by {@code Exhaust} launch billows): ribbons from such a point plume made
 * hundred-metre planes. Plume formulas fail below wind {@link #CALM} — use that value. Speed is the projectile's
 * world speed: engine burns for its real duration, trail follows its path over that time. At the game flight
 * scale (not physical, {@code FlightScale}) the path is short (Grad ~160 blocks, real 9M22U accelerating to
 * ~690 m/s — ~650 m); the same smoke over a short path is denser and persists longer. Length follows speed, as all flight lengths
 * do ({@code Airframe.stretch}); extending the trail without extending burn time would lay smoke where the engine was unlit.
 * Grad exits the tube immediately at full speed (not physical, {@code RocketEntity}): at scale 1 its trail is ~1.4 km.</li>
 * <li><b>Optics</b>: extinction per metre E = σ_m·λ (m); Gaussian cross-section of width σ, centre transverse optical depth
 * τ = E/(√(2π)·σ), transverse opacity o(y) = 1 − e^(−τ·e^(−y²/2σ²)). Ribbon and billows have soft edges, so they
 * are drawn at half-centre-opacity width (thin smoke — 2.355σ, dense — where τ(y) = ln 2), with
 * centre opacity; trail lives while centre depth exceeds the Koschmieder threshold ({@link #THRESHOLD}, contrast 2 %).
 * Drawing width out to that threshold (up to 13σ for dense smoke) is invalid: a flat ribbon that wide becomes an opaque lump rather than a soft
 * edge.</li>
 * <li><b>Spreading</b>: σ² = σ₀² + σ_r² + (α·z/2)²: σ₀ — jet at plume end; third term — rising warm-trail
 * billow; σ_r — trail's own turbulent spread. Trail is laid all at once — a puff rather than a continuous-source
 * plume: Briggs σ_y, σ_z ({@link Stability}) are plume widths averaged over minutes of measurement (Hanna, Briggs, Hosker
 * 1982: 3–10 min), including whole-plume meandering by eddies larger than itself. Instantaneous trail spreads more slowly —
 * Richardson–Obukhov relative dispersion: particle pairs separate as ⟨r²⟩ = g·ε·t³ (same law dispersing the nuclear
 * mushroom, {@code MushroomModel}), per-axis σ_r² = g·ε·t³/6 — capped by the axis-mean averaged width:
 * σ_r² ≤ σ_y·σ_z (in stable air the trail may be vertically wider than σ_z — then no vertical axis meander). Using σ_y·σ_z
 * here (previously) diluted a daytime SAM trail to invisibility by ~15 s, although real trails hang for tens of seconds to minutes
 * (FX-report 23.10). Remaining averaged width becomes axis bending by eddies larger than the trail ({@link #wander}):
 * σ_y² − σ_r² sideways, σ_z² − σ_r² vertically (Gifford meandering plume model).</li>
 * <li><b>Rise</b>: trail is a line thermal (Turner, “Buoyancy Effects in Fluids”, 1973, ch. 6): buoyancy per metre
 * B = g·H/(v·ρ·c_p·T), H — engine heat left in air ({@link Motor#heatFlow}); billow radius α·z,
 * momentum π·α²·z²·ż grows by B, while stable air (frequency N) consumes B through lifted ambient air:
 * d(z²ż)/dt = B/(πα²) − N²z³/3. One nondimensional equation for all ({@link #rise}); after its peak, the billow stays
 * there (equilibrium oscillations damp out — not drawn). α = 0.5 (est.) (line thermals 0.3–0.6).</li>
 * <li><b>Contrail</b>: ice from exhaust vapour if the mixture exceeds saturation ({@link Contrail}); same law
 * with ice extinction. In ice-supersaturated air, excess vapour from entrained air also deposits on trail ice —
 * ρ_excess·2πσ² per metre: trail thickens as it spreads, lasting while supersaturation persists (up to {@link #LONGEST}); otherwise ice
 * sublimates when dilution removes excess exhaust vapour ({@link Contrail#dryAt}).</li>
 * </ul>
 * Time in seconds, lengths in metres.
 */
public final class TrailModel {
    /** Point appearance value count ({@link #look}). */
    public static final int LOOK = 7;
    /** Optical-depth visibility threshold. */
    public static final double THRESHOLD = Sight.THRESHOLD;
    /** Below this wind speed (m/s), Gaussian plume formulas fail (calm): use this value. */
    static final double CALM = 1;
    /** Thermal billow spreading coefficient. */
    static final double ALPHA = 0.5;
    /** Air heat capacity, J/(kg·K). */
    static final double CP_AIR = 1004;
    /** Buoyancy per trail metre per watt-second of heat: g/(ρ·c_p·T) at ground level, m³/(s²·J/m). */
    private static final double BUOYANCY = 9.80665 / (StandardAtmosphere.density(0) * CP_AIR * StandardAtmosphere.T0);
    /** Maximum trail lifetime, s: no longer than an in-game day. */
    static final double LONGEST = 1200;
    /**
     * Richardson–Obukhov constant g: ⟨r²⟩ = g·ε·t³ (Ott and Mann 2000, JFM 422, 207; Sawford 2001, Annu. Rev. Fluid
     * Mech. 33, 289) — same as for the nuclear mushroom ({@code MushroomModel.RICHARDSON}).
     */
    static final double RICHARDSON = 0.5;
    /**
     * Near-surface turbulent energy dissipation rate by Pasquill class A–F, m²/s³. (est.) from AMS Glossary orders of magnitude
     * (“dissipation rate”: daytime convection 10⁻³–10⁻², night 10⁻⁶–10⁻⁴): A 10⁻², B 5·10⁻³, C 2·10⁻³,
     * D 10⁻³ (neutral: u*³/(κz) at u* ≈ 0.3 m/s around 30–70 m), E 10⁻⁴, F 10⁻⁵; logarithmic between classes. No distinction between height
     * within the layer and free troposphere above it (10⁻⁵–10⁻⁴).
     */
    private static final double[] DISSIPATION = {1e-2, 5e-3, 2e-3, 1e-3, 1e-4, 1e-5};
    /** Trail bend wavelengths ({@link #wander}): eddies 4·2^k m, k = 0…8 — up to 1 km, mixing-layer depth. */
    static final int OCTAVES = 9;
    static final double SMALLEST_EDDY = 4;
    /** Octave eddy lengths, m, and their cube roots. */
    private static final double[] EDDY = new double[OCTAVES], CBRT_EDDY = new double[OCTAVES];
    private static final double TAU = 2 * Math.PI;

    static {
        for (int k = 0; k < OCTAVES; k++) {
            EDDY[k] = SMALLEST_EDDY * (1 << k);
            CBRT_EDDY[k] = Math.cbrt(EDDY[k]);
        }
    }
    /** Minimum rendered bend spans this many ribbon-point steps: smaller bends turn inter-point segments into zigzags. */
    static final double POINTS_PER_EDDY = 4;
    /** Nondimensional rise s(τ) to peak, step τ. */
    private static final double RISE_STEP = 0.01;
    private static final double[] RISE = riseTable();

    private TrailModel() {}

    /**
     * Trail spec: engine and ribbon-point step.
     *
     * @param motor  smoke source
     * @param step   ribbon point every this many ticks (ribbon resolution, not physics)
     * @param sigma0 smoke σ on leaving the jet (jet half-width at visible plume end — 2σ), m
     * @param reach  trail laid over the real motor burn path, m ({@code Article.burnPath}): until the projectile travels this far
     *               from path start, no farther at any flight speed; 0 — while the phase burns
     * @param burn   spec motor burn time, s: with {@code reach}, determines matter per trail metre (real flow
     *               over real path), rather than by projectile speed
     */
    public record Trail(Motor motor, int step, double sigma0, double reach, double burn) {
        /** Engine trail: initial width from its ground-level plume (no plume — jet at core end, twice exit width). */
        public static Trail of(Motor motor, int step) {
            Flame f = FlameModel.of(motor, 0);
            return new Trail(motor, step, f != null ? f.spread() / 2 : 2 * motor.exitRadius() * Math.sqrt(motor.nozzles()), 0, 0);
        }

        /**
         * Same trail over real burn path reach in time burn: at game speed the projectile takes longer to travel it than the
         * in-game engine burns, so trail continues after the plume — not physical (Grad at game speed ~80 m/s has
         * a 2 s burn, ~160 m, but a ~650 m trail like the real rocket); at real speed it ends at the same path distance.
         */
        public Trail alongBurn(double reach, double burn) {
            return new Trail(motor, step, sigma0, reach, burn);
        }

        /** Seconds per trail metre: by real burn path, or without it by projectile speed in wind wind, m/s. */
        public double inverseSpeed(double speed, double wind) {
            return reach > 0 ? burn / reach : TrailModel.inverseSpeed(speed, wind);
        }
    }

    /**
     * Trail-point matter fraction already out of the flame as smoke when the point is behind metres aft of the nozzle along the projectile path
     * (projectile lays the point at the nozzle and moves on), with current bright flame length flame metres — core and afterburning to stoichiometry
     * ({@link Flame#flame}; no flame — 0): inside it, products are the flame itself (incandescent particles shine tens of times
     * brighter than white, {@code FlameModel}); drawing them as smoke on top counts twice: at night — a dark lump where fire should be.
     * Cooling tail beyond it to the Draper point barely lights the screen — already smoke there. Smooth from flame end to
     * 1.5 flame lengths. (est.) Flame assumed straight along the path.
     */
    public static double emerged(double behind, double flame) {
        if (flame <= 0) return 1;
        double u = Math.clamp((behind / flame - 1) / 0.5, 0, 1);
        return u * u * (3 - 2 * u);
    }

    /** Projectile at speed speed lays a trail (outruns wind wind), rather than remaining in its launch cloud, m/s. */
    public static boolean laid(double speed, double wind) {
        return speed >= Math.max(wind, CALM);
    }

    /** Seconds per trail metre for a projectile at speed speed in wind wind, m/s. */
    public static double inverseSpeed(double speed, double wind) {
        return 1 / Math.max(speed, Math.max(wind, CALM));
    }

    /** Extinction per smoke metre, m (λ·σ_m). */
    public static double smoke(Trail t, double inverseSpeed) {
        Motor m = t.motor();
        return m.smoke() == null ? 0 : m.smoke().extinction * m.smokeFlow() * inverseSpeed;
    }

    /**
     * Extinction per ice metre, m, if a contrail forms: ice from exhaust vapour and, in ice-supersaturated air
     * (excess — vapour excess, kg/m³), from entrained-air vapour over cross-section 2πσ².
     */
    public static double ice(Trail t, double inverseSpeed, double excess, double sigma) {
        return Smoke.ICE.extinction * (t.motor().waterFlow() * inverseSpeed + Math.max(0, excess) * 2 * Math.PI * sigma * sigma);
    }

    /** Vapour excess above ice saturation at world height y, kg/m³ (0 — not supersaturated). */
    public static double excess(double y, Air air) {
        return Math.max(0, -Contrail.iceDeficit(StandardAtmosphere.temperature(y - Air.SEA), air.humidity()));
    }

    /**
     * Whether a contrail forms at world height y: temperature and pressure from the standard atmosphere above world sea level.
     */
    public static boolean contrail(Trail t, double y, Air air) {
        double h = y - Air.SEA;
        return Contrail.forms(t.motor(), StandardAtmosphere.temperature(h), StandardAtmosphere.pressure(h), air.humidity());
    }

    /**
     * Ribbon point born: out[0] — visibility duration, s; out[1] — ice persistence, s (0 — no contrail).
     *
     * @param inverseSpeed {@link #inverseSpeed}
     * @param y            world height
     * @param wind         local wind, m/s
     */
    public static void birth(Trail t, double inverseSpeed, double y, double wind, Air air, double[] out) {
        double n = air.brunt(), c = air.stability();
        double smoke = smoke(t, inverseSpeed);
        double life = smoke > 0 ? until(t, sigmaFor(smoke), inverseSpeed, c, wind, n) : 0;
        double ice = 0;
        if (contrail(t, y, air)) {
            double h = y - Air.SEA, deficit = Contrail.iceDeficit(StandardAtmosphere.temperature(h), air.humidity());
            // supersaturated air: trail thickens while spreading — lasts as long as supersaturation
            ice = deficit <= 0 ? LONGEST : until(t, Math.min(sigmaFor(ice(t, inverseSpeed, 0, 0)),
                    Contrail.dryAt(t.motor().waterFlow() * inverseSpeed, deficit)), inverseSpeed, c, wind, n);
        }
        out[0] = Math.max(life, ice);
        out[1] = ice;
    }

    /**
     * Appearance of a point born at world height y, age seconds old: out[0] — half-centre-opacity width, m;
     * out[1] — centre opacity; out[2] — rise, m; out[3] — dense smoke reflectance (linear,
     * {@link Smoke#reflectance}); tube itself — out[4] cross-section σ, m, out[5] extinction per metre (smoke and ice), m, out[6] mixture
     * single-scattering albedo ({@link #LOOK} numbers).
     */
    public static void look(Trail t, double age, double inverseSpeed, double iceLife, double wind, double y, Air air, double[] out) {
        double n = air.brunt();
        look(t, age, inverseSpeed, iceLife, wind, excess(y, air), riseScale(t, inverseSpeed, n), n, air.stability(), out);
    }

    /**
     * Same using values assigned at point birth (frame traverses thousands of points: no cube roots
     * or saturation pressure here): vapour excess excess ({@link #excess}), rise scale riseScale ({@link #riseScale}) at
     * frequency brunt, stability class stability.
     */
    public static void look(Trail t, double age, double inverseSpeed, double iceLife, double wind, double excess, double riseScale, double brunt,
                            double stability, double[] out) {
        look(t, age, inverseSpeed, iceLife, wind, excess, riseScale, brunt, stability, 1, out);
    }

    /**
     * Same for fraction mass of point matter (remainder drawn by eye-local smoke grid volume): depth — mass × smoke and ice depths
     * (including ice from entrained-air vapour); same cross-section and rise.
     */
    public static void look(Trail t, double age, double inverseSpeed, double iceLife, double wind, double excess, double riseScale, double brunt,
                            double stability, double mass, double[] out) {
        double lift = riseScale * risen(brunt * age);
        double sigma = sigma(t, age, stability, wind, lift);
        double smoke = mass * smoke(t, inverseSpeed), ice = age < iceLife ? mass * ice(t, inverseSpeed, excess, sigma) : 0, e = smoke + ice;
        double tau = e / (Math.sqrt(2 * Math.PI) * sigma);
        out[2] = lift;
        Smoke s = t.motor().smoke();
        double albedo = e <= 0 ? 1 : ((s == null ? 0 : smoke * s.albedo) + ice * Smoke.ICE.albedo) / e;
        out[3] = Smoke.reflectance(albedo);
        out[4] = sigma;
        out[5] = e;
        out[6] = albedo;
        if (tau <= THRESHOLD) {
            out[0] = out[1] = 0;
            return;
        }
        out[0] = width(tau, sigma);
        out[1] = 1 - Math.exp(-tau);
    }

    /**
     * Half-centre-opacity width of a Gaussian tube of cross-section σ and centre transverse depth tau, m:
     * e^(−τg) = (1 + e^(−τ))/2, width — 2σ·√(−2·ln g). Below visibility threshold — 0.
     */
    public static double width(double tau, double sigma) {
        if (!(tau > THRESHOLD)) return 0;
        double clear = Math.exp(-tau), g = -Math.log((1 + clear) / 2) / tau;
        return 2 * sigma * Math.sqrt(-2 * Math.log(g));
    }

    /** Cross-section σ at age seconds and lift metres rise, m. */
    public static double sigma(Trail t, double age, double stability, double wind, double lift) {
        double th = ALPHA * lift / 2;
        return Math.sqrt(t.sigma0() * t.sigma0() + spread(age, stability, wind) + th * th);
    }

    /**
     * Trail's own spread σ_r² at age seconds, m²: Richardson–Obukhov g·ε·t³/6 per axis, no wider than Briggs plume
     * σ_y·σ_z at distance x = u·t.
     */
    static double spread(double age, double stability, double wind) {
        double x = Math.max(0, wind) * age;
        return Math.min(RICHARDSON * dissipation(stability) * age * age * age / 6, Stability.sigmaY(stability, x) * Stability.sigmaZ(stability, x));
    }

    /** Dissipation rate in class stability, m²/s³ ({@link #DISSIPATION}). */
    static double dissipation(double stability) {
        double c = Math.clamp(stability, Stability.A, Stability.F);
        int k = (int) Math.min(4, Math.floor(c));
        return DISSIPATION[k] * Math.pow(DISSIPATION[k + 1] / DISSIPATION[k], c - k);
    }

    /**
     * Trail axis displacement by larger eddies into out (world x, y, z, m): trail point seed, s metres along projectile path from
     * start, age seconds old. Magnitude follows the law: averaged Briggs plume = trail spread + axis meander,
     * so sideways displacement RMS is √(σ_y² − σ_r²) (both horizontal axes), vertical — √(max(0, σ_z² − σ_r²)); vertical RMS at
     * most one third of clearance (point height above ground at birth), downward shift at most 0.8 clearance. Shape follows eddies: eddy size λ has speed
     * v = (ελ)^⅓ and lifetime T = λ/v (Kolmogorov); over time t it shifts the axis by v·t until decay, then as a random
     * walk; variance contribution v²·t·min(t, T). Octave λ = 4·2^k m — smooth noise along path (s/λ) and time (t/T):
     * eddy-length bends; small eddies change quickly, large ones over minutes. Eddies smaller than the trail itself (σ_r) or
     * {@link #POINTS_PER_EDDY} point steps spacing (m) are not rendered — their variance goes to larger eddies (same total).
     * (est.) Octave decomposition estimates shape; only total variance follows the law.
     */
    public static void wander(long seed, double s, double age, double stability, double wind, double spacing, double clearance, double[] out) {
        out[0] = out[1] = out[2] = 0;
        if (age <= 0) return;
        // called for every live point per frame: point-independent values in tables, roots and logarithms computed once
        double x = Math.max(0, wind) * age, sy = Stability.sigmaY(stability, x), sz = Stability.sigmaZ(stability, x);
        double eps = dissipation(stability), rel = Math.min(RICHARDSON * eps * age * age * age / 6, sy * sz);
        double side = Math.sqrt(Math.max(0, sy * sy - rel)), up = Math.min(Math.sqrt(Math.max(0, sz * sz - rel)), Math.max(0, clearance) / 3);
        if (side <= 0 && up <= 0) return;
        double cut = Math.max(Math.sqrt(rel), POINTS_PER_EDDY * spacing);
        // octave threshold: λ_k = 4·2^k; octave share smooth over two octaves above threshold (axis gradually moves into trail width)
        double below = Math.log(cut / SMALLEST_EDDY) / Math.log(2), ce = Math.cbrt(eps);
        double total = 0, ax = 0, ay = 0, az = 0;
        for (int k = Math.max(0, (int) Math.floor(below)); k < OCTAVES; k++) {
            double fade = Math.clamp((k - below) / 2, 0, 1);
            if (fade <= 0) continue;
            double v = ce * CBRT_EDDY[k], turnover = EDDY[k] / v;
            fade = fade * fade * (3 - 2 * fade);
            // share scales amplitude, rather than variance: square root of variance share has infinite derivative at zero — axis would jerk
            double w2 = fade * fade * v * v * Math.min(age, turnover);
            double w = Math.sqrt(w2), u = s / EDDY[k], tt = age / turnover;
            long o = seed + k * 0x9E3779B97F4A7C15L;
            ax += w * noise(o, u, tt);
            az += w * noise(o ^ 0x5DEECE66DL, u, tt);
            ay += w * noise(o ^ 0x2545F4914F6CDD1DL, u, tt);
            total += w2;
        }
        if (total <= 0) return;
        double norm = 1 / Math.sqrt(total);
        out[0] = side * ax * norm;
        out[1] = up * ay * norm;
        out[2] = side * az * norm;
        // downward softly limited by ground (smoke spreads along it rather than entering it)
        double floor = 0.8 * clearance;
        if (out[1] < 0 && floor < Double.POSITIVE_INFINITY) out[1] = -floor * Math.tanh(-out[1] / Math.max(floor, 1e-6));
    }

    /**
     * Octave noise of variance 1 at every point (u — path in eddy lengths, t — time in eddy lifetimes): two opposing travelling
     * harmonics with seeded random wavenumber (0.75–1.25 waves per eddy length), frequency (0.75–1.25 radians per
     * lifetime) and phase — random Fourier modes of synthetic turbulence (Kraichnan 1970). Each has variance ½; axis
     * moves at most 1.25·√2 amplitudes per eddy lifetime — at eddy speed, without jerks. Opposing waves keep bends
     * from snaking along the trail.
     */
    static double noise(long seed, double u, double t) {
        long h1 = mix(seed), h2 = mix(h1), h3 = mix(h2);
        // path waves in period fractions before cosine: a path of thousands of eddy lengths would lose float phase precision
        double a = u * jitter(h1) + unit(h1 >>> 42), b = u * jitter(h2) + unit(h3);
        a -= Math.floor(a);
        b -= Math.floor(b);
        return Mth.cos((float) (TAU * a + t * jitter(h1 >>> 21))) + Mth.cos((float) (TAU * b - t * jitter(h2 >>> 21)));
    }

    /** 0.75–1.25 from 21 bits. */
    private static double jitter(long h) {
        return 0.75 + 0.5 * unit(h);
    }

    /** 0…1 from the low 21 bits. */
    private static double unit(long h) {
        return (h & 0x1FFFFF) * 0x1.0p-21;
    }

    /** SplitMix64 mixing. */
    private static long mix(long h) {
        h += 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ h >>> 31;
    }

    /** Warm trail rise after age seconds in air of Brunt–Väisälä frequency brunt, m. */
    public static double rise(Trail t, double age, double inverseSpeed, double brunt) {
        return age <= 0 ? 0 : riseScale(t, inverseSpeed, brunt) * risen(brunt * age);
    }

    /** Rise scale L = (3b/N²)^⅓, b = B/(πα²): rise — L·s(N·t), m. */
    public static double riseScale(Trail t, double inverseSpeed, double brunt) {
        double heat = t.motor().heatFlow() * inverseSpeed;
        if (heat <= 0 || brunt <= 0) return 0;
        return Math.cbrt(3 * BUOYANCY * heat / (Math.PI * ALPHA * ALPHA) / (brunt * brunt));
    }

    /** σ up to which the trail centre with extinction e per metre remains visible. */
    static double sigmaFor(double e) {
        return e / (Math.sqrt(2 * Math.PI) * THRESHOLD);
    }

    /** Seconds for σ to reach target (at most {@link #LONGEST}). */
    static double until(Trail t, double target, double inverseSpeed, double stability, double wind, double brunt) {
        if (sigma(t, 0, stability, wind, 0) >= target) return 0;
        double lo = 0, hi = LONGEST;
        if (sigma(t, hi, stability, wind, rise(t, hi, inverseSpeed, brunt)) < target) return LONGEST;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if (sigma(t, mid, stability, wind, rise(t, mid, inverseSpeed, brunt)) < target) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    /** Nondimensional rise s(τ) for d(s²s′)/dτ = (1 − s³)/3; stays at peak afterwards. */
    static double risen(double tau) {
        double i = tau / RISE_STEP;
        int k = (int) i;
        if (k >= RISE.length - 1) return RISE[RISE.length - 1];
        return RISE[k] + (RISE[k + 1] - RISE[k]) * (i - k);
    }

    /** Table s(τ) to peak: Runge–Kutta from initial s = (τ²/2)^⅓, s²s′ = τ/3. */
    private static double[] riseTable() {
        java.util.ArrayList<Double> out = new java.util.ArrayList<>();
        out.add(0.0);
        double tau = RISE_STEP, s = Math.cbrt(tau * tau / 2), q = tau / 3;
        out.add(s);
        while (q > 0 && out.size() < 100_000) {
            double h = RISE_STEP;
            double k1s = q / (s * s), k1q = (1 - s * s * s) / 3;
            double s2 = s + h / 2 * k1s, q2 = q + h / 2 * k1q;
            double k2s = q2 / (s2 * s2), k2q = (1 - s2 * s2 * s2) / 3;
            double s3 = s + h / 2 * k2s, q3 = q + h / 2 * k2q;
            double k3s = q3 / (s3 * s3), k3q = (1 - s3 * s3 * s3) / 3;
            double s4 = s + h * k3s, q4 = q + h * k3q;
            double k4s = q4 / (s4 * s4), k4q = (1 - s4 * s4 * s4) / 3;
            s += h / 6 * (k1s + 2 * k2s + 2 * k3s + k4s);
            q += h / 6 * (k1q + 2 * k2q + 2 * k3q + k4q);
            if (q > 0) out.add(s);
        }
        double[] table = new double[out.size()];
        for (int i = 0; i < table.length; i++) table[i] = out.get(i);
        return table;
    }

    /** This engine has no trail at all (neither smoke nor water). */
    public static boolean none(@Nullable Trail t) {
        return t == null || t.motor().smokeFlow() <= 0 && t.motor().waterFlow() <= 0;
    }
}
