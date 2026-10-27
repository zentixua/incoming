package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.nuclear.model.WaterModel;

/**
 * Conventional explosion on water (projectile bursts at surface or below), without Minecraft: spray column (plume), curtain fall,
 * mist base surge, foam and ring wave on water. Meters, seconds, kilograms TNT.
 * <ul>
 * <li><b>Size</b>: Swisdak table (“Explosion Effects and Properties, Part II: Explosion Effects in Water”,
 * NSWC/WOL TR 76-116, 1978, ch. 14, table 14): maximum TNT column height and radius, H/W^⅓ and R/W^⅓ against scaled
 * charge depth Y/W^¼ (W in kg TNT, Y in m below surface). Table is for safe distances: “actual column may be
 * up to 50% smaller” (same source caveat); use the share added to jet height for safety in ch. 13 of same report:
 * height ÷ {@link #MARGIN_H} (1.4), radius ÷ {@link #MARGIN_R} (1.3) (⚠ est. within source
 * range). Cross-check: 500 kg at 30 m gives 56 m (report example), 9.1 kg at 10 m gives 23 m; shallow-water bottom-charge jet
 * from ch. 13 (Milligan, Young 1954) at Y/W^¼ = 0.74 gives 31·W^⅓ versus 38.6/1.4 = 28·W^⅓ here. Surface impact
 * uses Y = 0 row (⚠ half contact charge above water; table starts with charge just at surface). Shahed (50 kg):
 * column ~78 m, radius ~24 m; missile (150 kg): ~113 m; Grad (6.4 kg): ~39 m.</li>
 * <li><b>Shape and timing</b>: ballistics. Dome over bubble tears into jets flying without drag (water mass
 * per cross-section is tons per square meter; little air deceleration over tens of meters). Table H and R are visible
 * (top and maximum plume radius in photos), so visible spray edge lies there, not jet centers: dense-jet
 * edge is where transverse optical depth falls to visibility threshold ({@link CloudModel#SEEN}), at
 * {@link #halo} σ from axis. One-direction jet is a segment: water emitted at one instant at differing speed lies on
 * a straight line (position linear in speed), round cross-section σ {@link #jet}. Fastest vertical jet center
 * rises to H − halo (speed √(2g(H − halo)), Shahed apex ~3.7 s); jet fan is a cone of half-angle θ.
 * Table R is maximum visible radius over entire flight; spray travels farthest while falling: outer fast jets
 * land at 2H'·sin2θ from center, plus halo gives R (at apex column is narrower, ~¾R). Speeds uniform from
 * {@link #SPEED_MIN} to full: column filled from water to top. Jets fall as curtain: slower ones earlier and nearer.</li>
 * <li><b>Water</b>: same charge-energy share lifts column water as at Baker (Glasstone and Dolan, §2.52: ~1 million t
 * to 1.8 km at 23 kt): m·g·H/2 ∝ W, m = m_B·(W/W_B)·(H_B/H) (⚠ assumption: same share for nuclear and conventional bursts),
 * ~50 t for Shahed;
 * spray extinction from drop radius {@link #DROP_R} m: 3/(2ρr) m²/kg (geometric optics); delivered to clump as grid
 * drops ({@link CloudModel#DROP_K}, 5 µm), mass converted to equal extinction. Falling column
 * leaves mist, share {@link #MIST} of fine drops ({@link #MIST_DROP_R}), as base-surge ring: visible
 * edge at base-surge radius ({@link #surgeRadius}, nuke law), layer top at its height; drifts
 * and spreads like cloud, drops settle into water through layer bottom ({@link #mistLeft}).</li>
 * <li><b>On water</b>: foam where water landed (bubbly water, ⚠ decays over {@link #FOAM_S} s, est.), and wave from
 * explosion cavity: ring travels at group velocity for wavelength equal to column-base diameter, in shallow water no
 * faster than √(gD) ({@link #waveRadius}).</li>
 * </ul>
 */
public final class WaterBlast {
    /** Scaled charge depth Y/W^¼, m/kg^¼ (Swisdak 1978, table 14). */
    static final double[] DEPTH = {0, 0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5, 5.5, 6, 6.5, 7, 7.5, 8, 8.5, 9};
    /** Maximum column radius R/W^⅓, m/kg^⅓ (same source). */
    static final double[] SCALED_R = {8.3, 8.3, 8.3, 10, 12.8, 12.8, 12.8, 12.8, 12.8, 11.6, 10.6, 9.5, 8.5, 7.4, 6.4, 5.4, 4.3, 3.3, 2.3};
    /** Maximum column height H/W^⅓, m/kg^⅓ (same source; scan at 0.5 unclear: 38.6 as at 1.0). */
    static final double[] SCALED_H = {29.7, 38.6, 38.6, 29, 20.6, 19.7, 18.9, 18, 17.1, 16.2, 15.4, 13.6, 9.5, 5.9, 5.9, 5.9, 5.9, 5.9, 5.9};
    /** Table safety margin for height and radius (same report ch. 13: +40% height, +30% diameter). */
    static final double MARGIN_H = 1.4, MARGIN_R = 1.3;
    /** Baker charge, kg TNT. */
    static final double BAKER_KG = 23e6;
    /** Baker column water mass, kg (Glasstone, Dolan §2.52: about a million tons; ⚠ from memory) and height, m (§2.47). */
    static final double BAKER_WATER_KG = 1e9, BAKER_TOP = 1800;
    /** Column drop radius, m (est.: millimeter spray). */
    static final double DROP_R = 0.5e-3;
    /** Column water share remaining as mist when falling (est.: 1–5%). */
    static final double MIST = 0.02;
    /**
     * Mist drop radius, m. Only drops settling slower than surge lifetime over water remain airborne after column fall:
     * Stokes v = 2ρgr²/(9μ) gives 0.3 m/s for 50 µm (3 m over 10 s with meter-scale surge height;
     * at Re ≈ 2 Stokes overestimates ~10%: Gunn, Kinzer 1949 measured 0.27 m/s for 0.1 mm drop); millimeter column drops
     * fall at ~4 m/s and immediately enter water (size is est., by settling speed).
     */
    static final double MIST_DROP_R = 50e-6;
    /** Near-ground air viscosity, Pa·s (15 °C). */
    static final double AIR_MU = 1.81e-5;
    /**
     * Wind drag on water: u* = √C_D·U₁₀, C_D = 1.2·10⁻³ at 4–11 m/s wind (Large, Pond, “Open ocean momentum flux
     * measurements in moderate to strong winds”, J. Phys. Oceanogr. 11, 1981); Kármán constant.
     */
    static final double DRAG_WATER = 1.2e-3, KARMAN = 0.4;
    /**
     * Rouse number P = v/(κu*) above which surface turbulence cannot suspend drops: no suspension, layer settles by itself
     * (van Rijn, “Sediment transport, part II: suspended load transport”, J. Hydraul. Eng. 110, 1984: suspension at
     * P ≲ 2.5). Mist drops (0.3 m/s) at 6 m/s wind give P ≈ 3.6: layer does not grow upward as Briggs gas.
     */
    static final double ROUSE_SUSPENDED = 2.5;
    static final double G = 9.80665;
    /**
     * Column jets: minimum maximum-speed share (water uniform in speed from it to full; slower is dome
     * over bubble, immediately falls), cone half-angle shares and jet counts around each (⚠ est.: real
     * column has thin jets at top, dome below).
     */
    static final double SPEED_MIN = 0.35;
    static final double[] TILTS = {0, 0.5, 1};
    static final int[] AROUND = {1, 5, 7};
    /**
     * Visible spray halo of one jet (cross-section edge) as column-radius share; rest is jet-center spread
     * (⚠ shape est.: table gives only outer column edge, R and H, without partition between jet spread and each
     * halo).
     */
    static final double JET_HALO = 0.5;
    /** Water-foam decay time, s (e-folding; ⚠ est.: wind whitecaps last seconds, explosion-aerated water is deeper). */
    static final double FOAM_S = 15;
    /** Base-surge ring clumps. */
    static final int RING = 8;

    /** Column height and radius, m; rise time to apex, s; water, kg; charge depth and water depth below it, m. */
    public final double height, radius, rise, water, depth, waterDepth;
    /** Maximum jet speed (at centers), m/s; jet-cone half-angle, rad. */
    public final double speed, cone;
    /** Round jet cross-section σ, m; visible halo edge at apex, in σ (transverse optical depth down to {@link CloudModel#SEEN}). */
    public final double jet, halo;
    private final CloudModel.Air air;
    /** Charge, kg TNT. */
    private final double kg;
    /** Mist lifetime, s (computed once). */
    private double mistLife = -1;

    /**
     * @param tntKg      charge, kg TNT
     * @param depthM     charge below surface, m (water impact is 0)
     * @param waterDepth water depth below explosion, m (unknown is infinity: deep)
     */
    public WaterBlast(double tntKg, double depthM, double waterDepth, CloudModel.Air air) {
        this.air = air;
        kg = tntKg;
        depth = Math.max(0, depthM);
        this.waterDepth = waterDepth > 0 ? waterDepth : Double.POSITIVE_INFINITY;
        double cube = Math.cbrt(Math.max(tntKg, 0)), scaled = tntKg > 0 ? depth / Math.pow(tntKg, 0.25) : 0;
        height = table(SCALED_H, scaled) * cube / MARGIN_H;
        radius = table(SCALED_R, scaled) * cube / MARGIN_R;
        water = height > 0 ? BAKER_WATER_KG * tntKg / BAKER_KG * BAKER_TOP / height : 0;
        // jet halo: cross-section chosen so visible edge (transverse optical depth down to visibility threshold) at apex is column-radius
        // share; optical depth for vertical jet at apex (airborne speeds from ½ to full, segment from surface to top)
        double sigma = 0, edge = 1, centre = height, tilt = 0, landing = 1;
        if (height > 0) {
            double ext = water * 3 / (2 * 1000 * DROP_R) / directions();
            sigma = JET_HALO * radius / 3;
            for (int i = 0; i < 30; i++) {
                centre = Math.max(0.5 * height, height - edge * sigma);
                edge = visible(ext / ((1 - SPEED_MIN) * 2 * centre) / (Math.sqrt(2 * Math.PI) * sigma));
                sigma = JET_HALO * radius / edge;
            }
            centre = Math.max(0.5 * height, height - edge * sigma);
            // cone follows whole-flight envelope: table R is maximum visible plume radius; spray travels farthest when outer
            // fast jets land, center at 2H'·sin2θ, halo transverse to jet (horizontal
            // ÷cos θ); jet water per segment meter is ext/((1 − s₀)·v·t); at landing (t = 2·rise·cos θ), transverse optical depth
            // is lower by twice/cos θ than at apex
            for (int i = 0; i < 30; i++) {
                double cos = Math.cos(tilt);
                landing = visible(ext / ((1 - SPEED_MIN) * 2 * centre * 2 * cos) / (Math.sqrt(2 * Math.PI) * sigma));
                tilt = 0.5 * Math.asin(Math.clamp((radius - landing * sigma / cos) / (2 * centre), 0, 1));
            }
        }
        jet = sigma;
        halo = edge;
        speed = Math.sqrt(2 * G * centre);
        rise = speed / G;
        cone = tilt;
    }

    /** Visible Gaussian edge with transverse center optical depth tau, in σ: where optical depth drops to {@link CloudModel#SEEN}. */
    static double visible(double tau) {
        return Math.sqrt(2 * Math.log(Math.max(tau, CloudModel.SEEN * Math.E) / CloudModel.SEEN));
    }

    /** Table value by scaled depth (linear between rows, last row beyond edge). */
    static double table(double[] values, double scaled) {
        if (scaled <= DEPTH[0]) return values[0];
        for (int i = 1; i < DEPTH.length; i++) {
            if (scaled <= DEPTH[i]) {
                double f = (scaled - DEPTH[i - 1]) / (DEPTH[i] - DEPTH[i - 1]);
                return values[i - 1] + f * (values[i] - values[i - 1]);
            }
        }
        return values[values.length - 1];
    }

    /** Jet flight time for speed share s at angle a from vertical, s: out and back to surface. */
    double flight(double s, double a) {
        return 2 * s * speed * Math.cos(a) / G;
    }

    /** Column center top at age t, m: fastest vertical jet (up to apex, then back toward water). */
    public double top(double t) {
        if (t <= 0 || t >= 2 * rise) return 0;
        return speed * t - 0.5 * G * t * t;
    }

    /**
     * Speed share below which jet water at angle a from vertical has landed by age t (at least
     * {@link #SPEED_MIN}): share s flight is 2·s·v·cos a / g.
     */
    double slowest(double t, double a) {
        return Math.max(SPEED_MIN, G * Math.max(t, 0) / (2 * Math.max(speed, 1e-9) * Math.cos(a)));
    }

    /** Jet water share at angle a still airborne at age t (speeds uniform from {@link #SPEED_MIN} to full). */
    double share(double t, double a) {
        return Math.clamp((1 - slowest(t, a)) / (1 - SPEED_MIN), 0, 1);
    }

    /** Water share still airborne at age t: jets not yet landed. */
    public double aloft(double t) {
        if (t <= 0) return 1;
        double up = 0, all = 0;
        for (int k = 0; k < TILTS.length; k++) {
            all += AROUND[k];
            up += AROUND[k] * share(t, TILTS[k] * cone);
        }
        return up / all;
    }

    /** Water-surface radius reached by fallen column water by age t, m (0 means none yet): farthest landed jet. */
    public double fallen(double t) {
        double r = 0;
        for (double tilt : TILTS) {
            double a = tilt * cone, s = Math.min(1, G * Math.max(t, 0) / (2 * Math.max(speed, 1e-9) * Math.cos(a)));
            if (s >= SPEED_MIN) r = Math.max(r, s * s * speed * speed * Math.sin(2 * a) / G);
        }
        return r;
    }

    /** Grid drop mass (5 µm) with same extinction as {@code kg} column spray. */
    static double asGridDrops(double kg) {
        return asGridDrops(kg, DROP_R);
    }

    /** Same for drops of radius {@code r}, m: extinction 3/(2ρr) m²/kg. */
    static double asGridDrops(double kg, double r) {
        return kg * 3 / (2 * 1000 * r) / CloudModel.DROP_K;
    }

    /** Stokes mist-drop settling speed, m/s: 2ρgr²/(9μ). */
    static double mistSettling() {
        return 2 * 1000 * G * MIST_DROP_R * MIST_DROP_R / (9 * AIR_MU);
    }

    /**
     * Mist share still airborne since seconds after fall begins: drops settle through layer bottom,
     * flux v·c(0), c(0) at water surface; ring clump (Gaussian σv centered at height σv, {@link #blobs}) has water layer
     * per unit area c(0)·√(2π)·e^½·σv, hence dM/dt = −v·M / (√(2π)·e^½·σv(t)); initially (layer top at
     * base-surge height), like mixed layer of that height, e^(−v·t/h); upward spreading slows drop loss.
     * Evaporation not modeled: it only shortens lifetime.
     */
    double mistLeft(double since) {
        if (since <= 0) return 1;
        // midpoint integral over 24 equal steps: σv(t) is smooth (√(σ0² + (k·travel)²))
        int n = 24;
        double sum = 0, v = mistSettling(), depth = Math.sqrt(2 * Math.PI * Math.E);
        for (int i = 0; i < n; i++) {
            double t = since * (i + 0.5) / n;
            sum += v / (depth * mistSigmaV(t)) * since / n;
        }
        return Math.exp(-sum);
    }

    /** When water starts falling into water, s: slowest jet. */
    double landing() {
        return flight(SPEED_MIN, cone);
    }

    /**
     * Base surge: falling column water spreads as spray ring, same nuke law ({@link WaterModel#surgeReach},
     * {@link WaterModel#surgeTop}: cube-root-scaled Baker share); starts when water begins falling
     * ({@link #landing}), spreads at fall speed √(2gH) (time constant is radius limit divided by speed; ⚠ est.: Baker
     * surge travels ~25–30 m/s), from curtain landing position (column radius); spray is share {@link #MIST} of column
     * water, spreads by Briggs and drifts with wind. Law radius and height define visible ring edge.
     */
    public double surgeRadius(double since) {
        if (since <= 0) return 0;
        double reach = surgeReach(), tau = reach / Math.max(speed, 1e-9);
        return radius + reach * (1 - Math.exp(-since / Math.max(tau, 1e-3)));
    }

    double surgeReach() {
        return WaterModel.surgeReach(kg / 1e6);
    }

    double surgeTop() {
        return Math.max(1, WaterModel.surgeTop(kg / 1e6));
    }

    /** Mist lifetime, s since explosion: while ring-clump transverse optical depth exceeds threshold (with settling). */
    public double mistLife() {
        if (mistLife >= 0) return mistLife;
        double lo = 0, hi = CloudModel.LIFE_MAX;
        for (int i = 0; i < 30; i++) {
            double mid = 0.5 * (lo + hi);
            if (mistTau(mid) >= CloudModel.SEEN) lo = mid;
            else hi = mid;
        }
        mistLife = Math.max(landing() + lo, 2 * rise);
        return mistLife;
    }

    /** Transverse center optical depth of ring clump since seconds after fall begins (all column water already landed). */
    double mistTau(double since) {
        double ext = CloudModel.DROP_K * asGridDrops(MIST * water, MIST_DROP_R) / RING * mistLeft(since);
        double sv = mistSigmaV(since);
        return ext / (2 * Math.PI * mistSigmaH(since, ringCentre(since, ext, sv)) * sv);
    }

    /**
     * Ring clump center since seconds after fall begins, m from axis: visible ring edge (transverse optical depth to
     * visibility threshold) is at base-surge radius; centers inward by that many σh; ext is clump extinction, m².
     */
    double ringCentre(double since, double ext, double sv) {
        double outer = surgeRadius(since), r = 0.5 * outer;
        for (int i = 0; i < 8; i++) {
            double sh = mistSigmaH(since, r);
            r = Math.max(0, outer - visible(ext / (2 * Math.PI * sh * sv)) * sh);
        }
        return r;
    }

    /** Horizontal σh of ring clump centered at r since seconds after fall begins: ring spacing and Briggs spread. */
    double mistSigmaH(double since, double r) {
        double s = CloudModel.spreadH(air.spread(), path(since)), ring = Math.PI * Math.max(r, radius / 2) / RING;
        return Math.sqrt(ring * ring + s * s);
    }

    /**
     * Ring-clump σv: center at height σv, initial visible layer top at base-surge height
     * (σ0·(1 + edge) equals its height, edge from initial clump optical depth); afterward Briggs spread as far as surface
     * turbulence suspends drops ({@link #suspended}).
     */
    double mistSigmaV(double since) {
        double s = suspended() * CloudModel.spreadV(air.spread(), path(since)), s0 = surgeTop() / (1 + mistEdge0());
        return Math.sqrt(s0 * s0 + s * s);
    }

    /**
     * How much surface eddies lift mist drops, 0..1: 1 − P/{@link #ROUSE_SUSPENDED} (P is Rouse number of world
     * wind above water; ⚠ suspension-to-settling transition is linear est. between P = 0 and threshold). No wind gives 0.
     */
    double suspended() {
        double ustar = Math.sqrt(DRAG_WATER) * Math.hypot(air.windX(), air.windZ());
        if (ustar <= 0) return 0;
        return Math.clamp(1 - mistSettling() / (KARMAN * ustar) / ROUSE_SUSPENDED, 0, 1);
    }

    /** Visible initial ring-clump edge at fall start, in σ (optical depth from all mist at ring σ and surge height). */
    private double mistEdge0() {
        if (mistEdge0 < 0) {
            double ext = CloudModel.DROP_K * asGridDrops(MIST * water, MIST_DROP_R) / RING, sh = Math.PI * Math.max(radius, 1e-3) / RING;
            double sv = surgeTop() / 4;
            for (int i = 0; i < 8; i++) sv = surgeTop() / (1 + visible(ext / (2 * Math.PI * sh * sv)));
            mistEdge0 = surgeTop() / sv - 1;
        }
        return mistEdge0;
    }

    private double mistEdge0 = -1;

    private double path(double since) {
        return Math.max(1, Math.hypot(air.windX(), air.windZ()) * air.wind(surgeTop())) * Math.max(0, since);
    }

    /** Ballistic spray and wind-driven mist support, without building individual jets or puffs. */
    public void bounds(double t, SmokeBounds out, double x, double y, double z) {
        if (t < 0) return;
        // Ballistic endpoints are inside speed*t; use the same landing and mist laws as blobs.
        if (t < 2 * speed / G) {
            double r = speed * t + SmokeBounds.PUFF * jet;
            out.box(x - r, y - SmokeBounds.PUFF * jet, z - r, x + r, y + r, z + r);
        }
        double since = t - landing();
        if (since > 0 && t <= mistLife()) {
            double r = surgeRadius(since), sv = mistSigmaV(since);
            double pad = SmokeBounds.PUFF * Math.max(mistSigmaH(since, r), sv), lean = air.wind(surgeTop()) * since;
            double cx = x + air.windX() * lean, cz = z + air.windZ() * lean;
            out.box(cx - r - pad, y + sv - pad, cz - r - pad, cx + r + pad, y + sv + pad, cz + r + pad);
        }
    }

    /**
     * Spray clumps at age t relative to explosion at water surface (center relative to explosion, m): each directional jet is a segment
     * from slowest still-airborne water to fastest, round cross-section σ {@link #jet}; landed water is no longer
     * airborne. Mist forms base-surge ring during and after fall, near water, drifting with wind. false means no spray
     * left.
     */
    public boolean blobs(double t, SmokeGrid.Sink out, double x, double y, double z) {
        if (t < 0) return true;
        double each = asGridDrops(water) / directions(), drop = 0.5 * G * t * t;
        for (int k = 0; k < TILTS.length; k++) {
            double a = TILTS[k] * cone, lo = slowest(t, a);
            if (lo >= 1) continue;
            double share = (1 - lo) / (1 - SPEED_MIN), up = Math.cos(a), side = Math.sin(a);
            // segment center and half-segment: water of share s is at s·v·t along jet and −gt²/2 vertically
            double mid = 0.5 * (1 + lo) * speed * t, half = 0.5 * (1 - lo) * speed * t;
            double lift = Math.max(0, 0.5 * (1 + lo) * speed * up - G * t);
            for (int i = 0; i < AROUND[k]; i++) {
                // jets around circle: each angle triplet has own rotation (not a line); no seed needed, shape is shared
                double az = 2 * Math.PI * (i + 0.5 * k) / AROUND[k], ox = side * Math.cos(az), oz = side * Math.sin(az);
                out.segment(x + mid * ox, y + mid * up - drop, z + mid * oz, half * ox, half * up, half * oz, jet, jet, 0, 0, each * share, 1, 1, 1,
                        CloudModel.T_AIR_K, lift);
            }
        }
        double since = t - landing();
        if (since > 0) {
            if (t > mistLife()) return false;
            // ring collects water while column falls
            double mist = asGridDrops(MIST * water, MIST_DROP_R) * (1 - aloft(t)) * mistLeft(since) / RING;
            double sv = mistSigmaV(since), lean = air.wind(surgeTop()) * since;
            double r = ringCentre(since, CloudModel.DROP_K * mist, sv), sh = mistSigmaH(since, r);
            if (mist > 0) {
                for (int i = 0; i < RING; i++) {
                    double a = 2 * Math.PI * i / RING;
                    out.blob(x + r * Math.cos(a) + air.windX() * lean, y + sv, z + r * Math.sin(a) + air.windZ() * lean, sh, sv, 0, 0, mist, 1, 1, 1,
                            CloudModel.T_AIR_K, 0);
                }
            }
        }
        return true;
    }

    /** Column jet count (directions over all angles and circle). */
    static int directions() {
        int n = 0;
        for (int k : AROUND) n += k;
        return n;
    }

    /**
     * Explosion-cavity wave: leading-crest radius at age t, m. Cavity is half column radius, waves have
     * wavelength λ = 2R (⚠ size est.); group front travels at dω/dk, ω² = gk·tanh(kD), k = 2π/λ (linear gravity-wave
     * theory; WEC-Sim, Theory / Regular Wave Power,
     * https://wec-sim.github.io/WEC-Sim/main/theory/theory.html). Deep-water limit ½√(gλ/2π), shallow √(gD).
     * Model for small wave beyond cavity, not nonlinear initial ejection.
     */
    public double waveRadius(double t) {
        return radius / 2 + waveSpeed() * Math.max(0, t);
    }

    /** Water wave-front speed, m/s. */
    public double waveSpeed() {
        if (radius <= 0) return 0;
        double k = Math.PI / radius, kh = k * waterDepth;
        // Deep water: kh/sinh(2kh) → 0; separate branch avoids ∞/∞ with unknown bottom.
        if (kh > 20) return 0.5 * Math.sqrt(G / k);
        if (kh < 1e-6) return Math.sqrt(G * waterDepth);
        return 0.5 * Math.sqrt(G * Math.tanh(kh) / k) * (1 + 2 * kh / Math.sinh(2 * kh));
    }

    /** Ring wavelength, m. */
    public double waveLength() {
        return 2 * radius;
    }

    /**
     * Ring wave height at radius r relative to cavity, 0..1: cylindrical wave √(r₀/r) (front energy
     * per circumference), also decays by viscosity over one minute (⚠ est.).
     */
    public double waveAt(double r, double t) {
        double r0 = radius / 2;
        return Math.sqrt(r0 / Math.max(r, r0)) * Math.exp(-t / 60);
    }

    /**
     * Water foam at distance r from center, age t, 0..1: where column water landed (curtain lands outward from center,
     * slower jets nearer and earlier), from landing instant; at cavity (dome, ½R), immediately; beyond jet-center reach,
     * jet water lands across its cross-section (Gaussian σ {@link #jet}); decays over {@link #FOAM_S} s.
     */
    public double foam(double r, double t) {
        double since, share = 1;
        if (r <= radius / 2) since = t;
        else {
            // when curtain reached here: jet landing at r has speed share s with s² = r / (maximum reach)
            double reach = speed * speed * Math.sin(2 * cone) / G;
            if (r > reach) {
                // beyond center reach, cross-section water of fastest outer jets
                double off = (r - reach) / Math.max(jet, 1e-9);
                if (off > halo) return 0;
                share = Math.exp(-0.5 * off * off);
            }
            double s = Math.sqrt(Math.min(r, reach) / Math.max(reach, 1e-9));
            since = t - flight(Math.max(s, SPEED_MIN), cone);
        }
        return since < 0 ? 0 : share * Math.exp(-since / FOAM_S);
    }

    /** Water appearance lifetime (foam and wave), s. */
    public double waterLife() {
        return 2 * rise + 4 * FOAM_S;
    }
}
