package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.MushroomModel;

/**
 * Dust lifted by wind behind a conventional blast front: the same law as the nuke ({@link DustModel}), without
 * Minecraft. Meters, seconds, kilograms; heights relative to explosion point.
 * <ul>
 * <li><b>Origin.</b> Each surrounding surface where present (sectors {@link #SECTORS} and radii
 * {@link #BANDS}: top block, {@code FarBlasts.ground}), without “ground or roof”: each ring has a
 * {@link DustModel#chemical} layer (pressure, phase and surface-parallel dynamic-pressure share from {@link HobBlast} at burst height above
 * that surface, yield is nuclear with equal pressure, {@link CloudModel#equivalentKt}). Soil becomes airborne from
 * front arrival ({@link HobBlast#arrivalSeconds}) by share {@link DustModel#lifted} over local positive phase. No dust before
 * the front; none at all for a high burst (pressure below entrainment threshold).</li>
 * <li><b>Surface.</b> Dust loading and ease of entrainment follow material: bare loose soil (dirt,
 * sand, gravel, snow) entrains at u* 0.34 m/s; soil under turf and leaves from 3 m/s (Gaj &amp; Small, §2.2); on
 * hard material (stone, brick, planks, metal, ice), only deposited dust is loose ({@code DustModel.Surface.HARD}:
 * 0.6 g/m², AP-42 §13.2.1). Stone roof and rock are identical; the charge crushes the floor at impact, supplying
 * crater dust in the cloud ({@link CloudModel}). Water makes no dust.</li>
 * <li><b>Under the ball.</b> Dust lifted beneath the product fireball (touching the surface) is in its hot gas and
 * rises with the cloud rather than staying at ground (as in nuke: no ground wave dust in the ball, {@code BlastWall.underBall});
 * returned by {@link #underBall}.</li>
 * <li><b>Height.</b> During wave phase, Kuhl dust layer (zero at front, {@link DustModel.Column#first} at phase end,
 * tens of centimeters for tens-of-kg charges); afterward HE has no ground-heating flash, and air turbulence grows
 * the layer: vertical σ is Briggs by Pasquill class over wind travel, in quadrature with Kuhl top (σ = top / 2.5,
 * ⚠ layer shape). Layer rests on its surface: reflected Gaussian, mass center 0.8σ above it.</li>
 * <li><b>Gravity.</b> Cold dusty air is heavier than clean air (mixture density increases with dust mass per volume): layer is
 * a gravity current. It spreads along the surface (constant-volume slumping, Huppert &amp; Simpson 1980:
 * dR/dt = Fr·√(g′h), Fr = {@value #FR}, g′V = g·M/ρ gives R² = R₀² + 2Fr·√(g·M/(πρ))·t; M is airborne dust), and
 * over a drop (roof edge, cliff, slope) descends toward the surface below as an MTT heavy thermal of the same mass
 * (Boussinesq: warm-thermal law with reversed sign; {@link #fall}) or edge-lee wind, whichever is faster; along
 * slopes up to 45°, follows the surface up and down; cannot climb steeper walls ({@code level}).</li>
 * <li><b>Settling.</b> {@link DustModel} bins fall from the layer at size-based Stokes speed: reflected Gaussian has
 * surface concentration mass / (1.25σ), downward flux v·mass / (1.25σ); ∫dt/(1.25σ) gives extinction share
 * still airborne ({@link DustModel#extinctionAloft}).</li>
 * <li><b>Stem.</b> Axial entrainment uses the nuke's term ({@link DustModel.Column#stem}, {@link DustModel#STEM_S}):
 * from rings within {@code STEM_M}·∛(Y/15), it takes stem/STEM_S of dust per second while the layer is over them; wind removes
 * it from under the cloud over half the inflow radius. Conventional ball touches ground, so dust immediately fills the stem to cloud
 * bottom ({@code StemDust}, ball-trace case, ⚠ est.), with stem air moving by share
 * {@link MushroomModel#STEM_INFLOW} of cloud rise; dust reaching cloud bottom is already in the cloud.</li>
 * <li><b>Drift.</b> Layer follows wind at its center height (at least 2 m) and spreads by Briggs; outward wave flow
 * does not advect dust: it stays where lifted.</li>
 * </ul>
 * Hot explosive and fuel products rise, carrying their contents: the cloud ({@link CloudModel}), not the layer.
 * For rendering, the layer supplies display rings ({@link #ANNULI}: by surface radius) by sectors: each has its own mass,
 * mass-mean radius and source surface; visible edge never outruns the current front.
 */
public final class GroundDust {
    /** Directional sectors from impact point: each has its own surface ({@link Ground}). */
    public static final int SECTORS = 8;

    /** Known surface radii ({@link Ground}), m: ring uses nearest in log space. */
    public static final double[] BANDS = {3, 6, 12, 24, 48};

    /**
     * Surrounding relief: surface height (first air above top block) above explosion point at relative (x, z), m;
     * {@link Double#NaN} means unknown.
     */
    @FunctionalInterface
    public interface Relief {
        Relief UNKNOWN = (x, z) -> Double.NaN;

        double at(double x, double z);
    }

    /**
     * Surroundings by sectors and radii ({@code [sector][BANDS radius]}; direction k is angle
     * 2π(k + ½)/{@link #SECTORS} from x toward z): shares of bare loose soil, soil under turf or leaves, and hard surface;
     * rest is water (no dust). {@code height} is surface height above explosion point, m ({@link Double#NaN}
     * means unknown: flat surface one burst height below); {@code relief} gives terrain anywhere (where layer drains).
     */
    public record Ground(double[][] loose, double[][] covered, double[][] hard, double[][] height, Relief relief) {
        /** Bare soil everywhere, as in nuke ({@code Surface.REFERENCE}); also where client knows nothing. */
        public static final Ground BARE = uniform(1, 0);

        /** Loose soil and turf by sectors, flat surface one burst height below, no relief. */
        public Ground(double[][] loose, double[][] covered) {
            this(loose, covered, filled(0), filled(Double.NaN), Relief.UNKNOWN);
        }

        public static Ground uniform(double loose, double covered) {
            return new Ground(filled(loose), filled(covered));
        }

        /** Table [sector][radius] filled with one value. */
        public static double[][] filled(double v) {
            double[][] a = new double[SECTORS][BANDS.length];
            for (double[] row : a) java.util.Arrays.fill(row, v);
            return a;
        }
    }

    /** Surface radius for ring of radius r: nearest in log space from {@link #BANDS}. */
    static int band(double r) {
        int best = 0;
        double d = Double.MAX_VALUE, lr = Math.log(Math.max(r, 1e-3));
        for (int b = 0; b < BANDS.length; b++) {
            double e = Math.abs(Math.log(BANDS[b]) - lr);
            if (e < d) {
                d = e;
                best = b;
            }
        }
        return best;
    }

    /**
     * Calculation rings (log radius) and display rings: a display ring groups calculation rings sharing a surface radius
     * ({@link #band}); its sector clump has one surface, the one it rose from.
     */
    static final int RINGS = 24, ANNULI = BANDS.length;
    /** Table time grid: t_k = T0·(GROW^k − 1), s. */
    static final int STEPS = 112;
    static final double T0 = 0.01, GROW = 1.12;
    /** Inner calculation edge and outer edge: surface pressure below which soil no longer entrains (psi; entrainment ~0). */
    static final double INNER = 0.1, OUTER_PSI = 0.3;
    /**
     * Froude number of gravity-current head shallow relative to air depth (Huppert &amp; Simpson 1980, “The slumping
     * of gravity currents”, JFM 99: 785; experiments give 1.19).
     */
    static final double FR = 1.19;
    /**
     * Surface height change per meter of travel defining an edge (down: layer over void, falls) or wall (up: cannot
     * climb), m: follows block-step slopes up to 45° along the surface.
     */
    static final double DROP = 1;

    final CloudModel.Air air;
    final double yieldKt, hob;
    /**
     * Near-ground wind (at 2 m), m/s: wind travel is needed for each clump every frame, but layer air is constant over its lifetime (root of
     * sum of squares is expensive {@code Math.hypot}: took one eighth of smoke CPU for a 100-strike raid).
     */
    private final double groundWind;
    /** Time of computed {@link #nodeAt} grid node (one per frame for all layer tables). */
    private double nodeT = Double.NaN, nodeAt;
    /** Calculation rings: center and area (shared by all sectors). */
    private final double[] r, area;
    /**
     * By sectors and rings: front arrival, phase, lifted mass (kg, sector share), phase-end top, inflow, its start; whether
     * ring is under ball (its dust goes into cloud).
     */
    private final double[][] arrival, positive, mass, first, stem, stemFrom;
    private final boolean[][] ball;
    /** Surface height above explosion point by sectors and radii, m (unknown: flat at {@link #hob} below). */
    private final double[][] surface;
    private final Relief relief;
    /** Destroyed-material skirt: born at impact height, drains over edges rather than appearing complete below. */
    private final boolean skirt;
    /** Skirt radius at {@link #SKIRT_S}, m (0 means not a skirt). */
    private final double skirtR;
    private final int[] annulus;
    /** Sector clump direction in display ring (angle, rad) and open share of sector arc there: skirt follows streets. */
    private final double[][] heading = new double[SECTORS][ANNULI], openArc = new double[SECTORS][ANNULI];
    /** How much farther than open field the sector skirt travels through open space (street), 1 is field. */
    private final double[] reach = new double[SECTORS];
    /** Walls redirected skirt ({@link #channel}); wall reference height (m relative to explosion point). */
    private boolean walled;
    private double wallFrom;
    /** Wall height above impact, m: below it, wind follows street ({@link #canyon}). */
    private double wallTop;
    /** Sector clump path through open space ({@link #open}): {angle, radius, is open} at each half-meter radius. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private final java.util.List<double[]>[][] chain = new java.util.List[SECTORS][ANNULI];
    /** Time-grid tables: vertical σ and ∫dt/(1.25σ) per display ring; inflow as time share. */
    private final double[][] sv = new double[ANNULI][STEPS], settled = new double[ANNULI][STEPS];
    private final double[] stemTime = new double[STEPS];
    /** Dust entering stem (extinction in kg of {@link DustModel#K_MIX} mixture), cumulative on grid. */
    private final double[] drawn = new double[STEPS];
    /** Layer slumping: R² − R₀² on grid, m²; R₀² is mass-mean squared source radius. */
    private final double[] slump = new double[STEPS];
    final double slumpFrom;
    /** Lifted surface mass (excluding under-ball mass), kg; inflow radius, m; layer visibility duration, s. */
    public final double total, stemReach;
    public final double life;
    /** Age when stem inflow starts removing dust, s (infinity means never). */
    public final double drawnFrom;
    private double boundInitialV;
    private final double[] boundRadius = new double[STEPS], boundWidth = new double[STEPS],
            windForward = new double[STEPS], windBackward = new double[STEPS];

    /**
     * Dust layer for {@code tntKg} kg TNT charge at height {@code hobM} above bare soil in {@code air};
     * {@code ballM} is fireball radius (whether it touches ground).
     */
    GroundDust(double tntKg, double hobM, double ballM, CloudModel.Air air) {
        this(tntKg, hobM, ballM, Ground.BARE, air);
    }

    /**
     * Same above {@code ground} by sectors: each has its material (loose soil {@code Surface.FIELD},
     * turf {@code COVERED}, hard {@code HARD}, water has no dust) and height; local entrainment law uses burst height
     * above each surface (unknown height uses {@code hobM}).
     */
    GroundDust(double tntKg, double hobM, double ballM, Ground ground, CloudModel.Air air) {
        this(tntKg, hobM, ballM, ground, air, 0);
    }

    /**
     * Ground dust skirt (base surge): {@code kg} kg destroyed fines carried by explosion gas from breach or
     * crater along surrounding surfaces, rather than a complete layer at impact. Ebersole, Vaglio-Laurin, Dvore, ASL-CR-80-32
     * (1980): after {@value #SKIRT_S} s, skirt spans tens of meters (3×6.8 kg: 44×17 m, 3×55 kg: 54×30 m); radius is
     * {@value #SKIRT_R0}·R₀, R₀ = 2·W^⅓ m (DIRTRAN approximations there). Ring gets mass by area and rises when
     * skirt reaches it; afterward follows wave-dust law (wind, settling, draining over edges). ⚠ Est.: skirt top is
     * R₀ (measurements cover whole cloud including rising part), uniform area distribution.
     */
    static GroundDust skirt(double tntKg, double hobM, double kg, Ground ground, CloudModel.Air air) {
        // skirt starts at impact: each ring is born at its height, draining over edges (roof, cliff) by layer law
        Ground source = new Ground(ground.loose(), ground.covered(), ground.hard(), Ground.filled(-Math.max(0, hobM)), ground.relief());
        return new GroundDust(kg > 0 ? tntKg : 0, hobM, 0, source, air, kg);
    }

    /** When skirt reaches its radius, s, and radius in R₀ (Ebersole et al. 1980, DIRTRAN). */
    static final double SKIRT_S = 0.5, SKIRT_R0 = 2;

    private GroundDust(double tntKg, double hobM, double ballM, Ground ground, CloudModel.Air air, double skirtKg) {
        this.air = air;
        this.groundWind = Math.sqrt(air.windX() * air.windX() + air.windZ() * air.windZ()) * air.wind(2);
        this.yieldKt = CloudModel.equivalentKt(tntKg);
        this.hob = Math.max(0, hobM);
        this.relief = ground.relief();
        this.skirt = skirtKg > 0;
        // burst height over each surface: below (roof, street) or above (wall, slope above point), same pressure by slant
        // range (⚠ reflection from surface above point treated as from surface below)
        surface = new double[SECTORS][BANDS.length];
        double[][] over = new double[SECTORS][BANDS.length];
        double outer = 0;
        for (int k = 0; k < SECTORS; k++) {
            for (int b = 0; b < BANDS.length; b++) {
                double h = ground.height()[k][b];
                surface[k][b] = Double.isNaN(h) ? -hob : h;
                over[k][b] = rounded(Math.abs(surface[k][b]));
                if (tntKg > 0) outer = Math.max(outer, HobBlast.rangeFor(OUTER_PSI, over[k][b], yieldKt));
            }
        }
        double[] rr = new double[RINGS], aa = new double[RINGS];
        double r0 = 2 * Math.cbrt(Math.max(tntKg, 0)), skirtR = skirtKg > 0 ? SKIRT_R0 * r0 : 0;
        this.skirtR = skirtR;
        if (outer > INNER) {
            for (int i = 0; i < RINGS; i++) {
                double lo = i == 0 ? 0 : INNER * Math.pow(outer / INNER, (double) i / RINGS), r1 = INNER * Math.pow(outer / INNER, (i + 1.0) / RINGS);
                rr[i] = i == 0 ? 0.5 * r1 : Math.sqrt(lo * r1);
                // skirt ring is at area center of its part inside skirt: mass is uniform by area; otherwise the edge ring
                // would stand at the whole ring's center, beyond skirt edge
                double hi = Math.min(r1, skirtR);
                if (lo < skirtR) rr[i] = 2.0 / 3 * (hi * hi * hi - lo * lo * lo) / (hi * hi - lo * lo);
                aa[i] = Math.PI * (r1 * r1 - lo * lo);
            }
        }
        r = rr;
        area = aa;
        arrival = new double[SECTORS][RINGS];
        positive = new double[SECTORS][RINGS];
        mass = new double[SECTORS][RINGS];
        first = new double[SECTORS][RINGS];
        stem = new double[SECTORS][RINGS];
        stemFrom = new double[SECTORS][RINGS];
        ball = new boolean[SECTORS][RINGS];
        double hardSupply = DustModel.Surface.HARD.supply();
        for (int k = 0; k < SECTORS && outer > INNER; k++) {
            for (int i = 0; i < RINGS; i++) {
                int b = band(r[i]);
                Rings f = rings(tntKg, over[k][b], ballM, DustModel.Surface.FIELD, outer), c = rings(tntKg, over[k][b], ballM, DustModel.Surface.COVERED, outer);
                // on hard surfaces only deposited dust is loose: same entrainment law, capped by inventory (threshold as soil);
                // on turf too, only dust on grass: roots hold soil; soil fines come from crater (its
                // skirt); torn grass crumbs are FrontDust (coarse, settle in seconds). ⚠ Est.: grass dust loading equals
                // quiet road (HARD inventory); previously turf supplied a centimeter of soil like bare ground, producing a brown wall at
                // 6 s from 40 m at a lawn impact
                double hard = Math.min(hardSupply * area[i], f.mass[i]), turf = Math.min(hardSupply * area[i], c.mass[i]);
                mass[k][i] = (ground.loose()[k][b] * f.mass[i] + ground.covered()[k][b] * turf + ground.hard()[k][b] * hard) / SECTORS;
                arrival[k][i] = f.arrival[i];
                positive[k][i] = f.positive[i];
                first[k][i] = f.first[i];
                stem[k][i] = f.stem[i];
                stemFrom[k][i] = f.stemFrom[i];
                ball[k][i] = f.ball[i];
                if (skirtKg > 0) {
                    // ring edges (log calculation rings): skirt-disc area share in ring
                    double lo = i == 0 ? 0 : INNER * Math.pow(outer / INNER, (double) i / RINGS), hi = INNER * Math.pow(outer / INNER, (i + 1.0) / RINGS);
                    double in = Math.max(0, Math.min(hi, skirtR) * Math.min(hi, skirtR) - Math.min(lo, skirtR) * Math.min(lo, skirtR));
                    mass[k][i] = skirtR > 0 ? skirtKg * in / (skirtR * skirtR) / SECTORS : 0;
                    // skirt reaches ring at same speed even beyond its edge: front does not jump to ring end beyond edge
                    arrival[k][i] = SKIRT_S * lo / Math.max(skirtR, 1e-9);
                    positive[k][i] = Math.max(SKIRT_S * (Math.min(hi, skirtR) - Math.min(lo, skirtR)) / Math.max(skirtR, 1e-9), 0.05);
                    first[k][i] = r0;
                    // breach fines are neither ball-entrained nor stem-drawn: do not count twice
                    stem[k][i] = 0;
                    ball[k][i] = false;
                }
            }
        }
        for (int k = 0; k < SECTORS; k++) {
            java.util.Arrays.fill(heading[k], 2 * Math.PI * (k + 0.5) / SECTORS);
            java.util.Arrays.fill(openArc[k], 1);
            reach[k] = 1;
        }
        // gas carries skirt along surfaces: it avoids walls above impact (house beside street), redirects toward open
        // arcs of the same ring, into streets; otherwise at a block intersection most dust would lie inside houses where
        // it is invisible (⚠ est.: uniform distribution over open arc, walls WALL above impact block flow)
        if (skirtKg > 0 && outer > INNER) channel(-hob);
        double sum = 0;
        for (int k = 0; k < SECTORS; k++) for (int i = 0; i < RINGS; i++) if (!ball[k][i]) sum += mass[k][i];
        total = sum;
        stemReach = DustModel.STEM_M * Math.cbrt(yieldKt / 15);
        // display rings follow surface radii
        annulus = new int[RINGS];
        for (int i = 0; i < RINGS; i++) annulus[i] = band(r[i]);
        // tables: vertical σ (Kuhl top at phase end and air turbulence), settling, inflow
        double[] top = new double[ANNULI], weight = new double[ANNULI];
        double from = Double.MAX_VALUE, r2 = 0;
        for (int k = 0; k < SECTORS; k++) {
            if (RINGS > 0) from = Math.min(from, stemFrom[k][0]);
            for (int i = 0; i < RINGS; i++) {
                if (ball[k][i]) continue;
                top[annulus[i]] += mass[k][i] * first[k][i];
                weight[annulus[i]] += mass[k][i];
                r2 += mass[k][i] * r[i] * r[i];
            }
        }
        for (int q = 0; q < ANNULI; q++) top[q] = weight[q] > 0 ? top[q] / weight[q] : 0.1;
        slumpFrom = sum > 0 ? Math.max(r2 / sum, 1) : 1;
        for (int s = 1; s < STEPS; s++) {
            double t0 = time(s - 1), t1 = time(s), dt = t1 - t0, mid = 0.5 * (t0 + t1);
            for (int q = 0; q < ANNULI; q++) {
                sv[q][s] = sigmaV(top[q], t1);
                settled[q][s] = settled[q][s - 1] + dt / (1.25 * sigmaV(top[q], mid));
            }
            stemTime[s] = stemTime[s - 1] + (mid > from ? dt * under(mid) : 0);
        }
        for (int q = 0; q < ANNULI; q++) sv[q][0] = sigmaV(top[q], 0);
        // stem: cumulative inflow (extinction in mixture kg); layer slumping follows still-airborne dust
        for (int s = 1; s < STEPS; s++) {
            double t = 0.5 * (time(s - 1) + time(s)), flux = 0, aloft = 0;
            for (int k = 0; k < SECTORS; k++) {
                for (int i = 0; i < RINGS; i++) {
                    if (ball[k][i] || t <= arrival[k][i]) continue;
                    if (t > stemFrom[k][i]) flux += stem[k][i] / DustModel.STEM_S * under(t) * ringNow(k, i, t);
                    aloft += massNow(k, i, t);
                }
            }
            double dt = time(s) - time(s - 1);
            drawn[s] = drawn[s - 1] + flux * dt;
            slump[s] = slump[s - 1] + 2 * FR * Math.sqrt(CloudModel.G * aloft / (Math.PI * CloudModel.RHO)) * dt;
        }
        int s0 = 1;
        while (s0 < STEPS && drawn[s0] <= 0) s0++;
        drawnFrom = s0 < STEPS ? time(s0 - 1) : Double.POSITIVE_INFINITY;
        life = lifeSeconds();
        for (int k = 0; k < SECTORS; k++) for (int i = 0; i < RINGS; i++) if (!ball[k][i] && mass[k][i] > 0) {
            boundInitialV = Math.max(boundInitialV, first[k][i] / 2.5);
        }
        support();
    }

    /**
     * Interval bounds for the moments used by emit. Settling is common to a display annulus and cancels
     * from its normalized moments. Lift only increases and stem survival only decreases: their endpoint
     * products bound each ring's weight throughout an interval, including between table samples.
     * This is built once, without terrain. Using the outermost ring as every puff's width made the old
     * envelope several kilometres wider than the cloud after a raid.
     */
    private void support() {
        double[] upper = new double[RINGS];
        for (int s = 0; s < STEPS; s++) {
            double t0 = time(s), t1 = s + 1 < STEPS ? time(s + 1) : Math.max(t0, CloudModel.LIFE_MAX);
            double stem0 = at(stemTime, t0), stem1 = at(stemTime, t1);
            boundWidth[s] = -1;
            for (int k = 0; k < SECTORS; k++) for (int a = 0; a < ANNULI; a++) {
                double lower = 0, all = 0, moment = 0, lo = Double.POSITIVE_INFINITY, hi = 0;
                for (int i = 0; i < RINGS; i++) {
                    upper[i] = 0;
                    if (annulus[i] != a || ball[k][i] || mass[k][i] <= 0) continue;
                    double m = mass[k][i];
                    lower += m * liftedShare(k, i, t0) * Math.exp(-stem[k][i] * stem1 / DustModel.STEM_S);
                    double u = m * liftedShare(k, i, t1) * Math.exp(-stem[k][i] * stem0 / DustModel.STEM_S);
                    if (u <= 0) continue;
                    upper[i] = u; all += u; moment += u * r[i];
                    lo = Math.min(lo, r[i]); hi = Math.max(hi, r[i]);
                }
                // Same cutoff as emit, with an upper bound on mass over the entire interval.
                if (all * DustModel.extinctionAloft(at(settled[a], t0)) <= 1e-9) continue;
                double mean = lower > 0 ? Math.min(hi, moment / lower) : hi;
                double anchor = moment / all, variance = 0;
                for (int i = 0; i < RINGS; i++) variance += upper[i] * (r[i] - anchor) * (r[i] - anchor);
                double limit = 0.25 * (hi - lo) * (hi - lo);
                variance = lower > 0 ? Math.min(limit, variance / lower) : limit;
                double own = Math.max(Math.sqrt(variance) * reach[k], Math.PI * mean / SECTORS / 1.25 * openArc[k][a]);
                boundRadius[s] = Math.max(boundRadius[s], mean * reach[k]);
                boundWidth[s] = Math.max(boundWidth[s], own);
            }
            // A cached terrain path can have been prepared with an earlier distribution of mass.
            // Its accumulated radial delay is bounded by the largest earlier mean times total growth.
            if (s > 0) boundRadius[s] = Math.max(boundRadius[s], boundRadius[s - 1]);
        }
        // The terrain integrator can retain any fraction of a wind increment. On an open surface it
        // cannot rotate that increment: bound its scalar forward/backward travel on the very same PATH.
        // Include the next path node, because the displayed state interpolates towards that node.
        for (int a = 0; a < ANNULI; a++) {
            int p = 1; double previous = 0, forward = 0, backward = 0;
            for (int s = 0; s < STEPS; s++) {
                double end = s + 1 < STEPS ? time(s + 1) : CloudModel.LIFE_MAX;
                while (p < PATH.length && PATH[p - 1] <= end) {
                    double t = PATH[p++], value = air.wind(Math.max(2, 0.8 * at(sv[a], t))) * t, change = value - previous;
                    forward += Math.max(0, change); backward += Math.max(0, -change); previous = value;
                }
                windForward[s] = Math.max(windForward[s], forward);
                windBackward[s] = Math.max(windBackward[s], backward);
            }
        }
    }

    /** Calculation-ring i center, m (checks). */
    double ring(int i) {
        return r[i];
    }

    /** Lifted mass of ring i, sector k, kg (checks). */
    double mass(int k, int i) {
        return mass[k][i];
    }

    /** Wall this far above impact, m, blocks skirt (lower is curb or step, gas flows over). */
    static final double WALL = 2;

    /**
     * Wall base level for impact at height {@code source}, m relative to explosion: lower quarter of relief in
     * ring from skirt edge to twice its radius (streets, not impact pit), no lower than impact itself. Impact in an earlier crater,
     * ditch or pit: walls measured from surrounding field, pit edges do not block as walls (otherwise dust would stay in pit until clump
     * grew past its edge); street impact uses street level (houses cover less than three quarters of ring); roof impact uses
     * roof. Unknown relief uses impact itself.
     */
    private double floor(double source) {
        if (skirtR <= 0) return source;
        double[] h = new double[4 * SECTORS * ARC];
        int n = 0;
        for (int i = 0; i < 4; i++) {
            double rho = skirtR * (1 + i / 3.0);
            for (int j = 0; j < SECTORS * ARC; j++) {
                double phi = 2 * Math.PI * (j + 0.5) / (SECTORS * ARC), v = relief.at(rho * Math.cos(phi), rho * Math.sin(phi));
                if (!Double.isNaN(v)) h[n++] = v;
            }
        }
        if (n == 0) return source;
        java.util.Arrays.sort(h, 0, n);
        return Math.max(source, h[n / 4]);
    }

    /** Relief samples over sector arc in ring: a 6 m street 10 m from impact spans 35°, 4 samples of a 45° sector. */
    static final int ARC = 9;

    /**
     * Skirt redirects from ring arc where surface (relief at ring radius, m relative to explosion; unknown is open) exceeds
     * {@code source} by {@link #WALL}, to open arc of same ring, uniform by length; sector clump follows its open
     * arc center, no wider than arc. Entire ring closed (yard, pit): dust stays as lifted. Wall base is surrounding
     * ground ({@link #floor}) if impact is lower: crater edges and field around pit are not walls; skirt exits pit as in
     * open field.
     */
    private void channel(double source) {
        source = floor(source);
        double[][] open = new double[SECTORS][RINGS], angle = new double[SECTORS][RINGS];
        for (int i = 0; i < RINGS; i++) {
            double ring = 0, all = 0;
            for (int k = 0; k < SECTORS; k++) {
                ring += mass[k][i];
                int n = 0;
                double sum = 0;
                for (int j = 0; j < ARC; j++) {
                    double phi = 2 * Math.PI * (k + (j + 0.5) / ARC) / SECTORS, h = relief.at(r[i] * Math.cos(phi), r[i] * Math.sin(phi));
                    if (h > source + WALL) continue;
                    n++;
                    sum += phi;
                }
                open[k][i] = (double) n / ARC;
                angle[k][i] = n == ARC || n == 0 ? 2 * Math.PI * (k + 0.5) / SECTORS : sum / n;
                all += open[k][i];
            }
            if (all <= 0 || all >= SECTORS || ring <= 0) {
                for (int k = 0; k < SECTORS; k++) {
                    open[k][i] = 1;
                    angle[k][i] = 2 * Math.PI * (k + 0.5) / SECTORS;
                }
                continue;
            }
            for (int k = 0; k < SECTORS; k++) mass[k][i] = ring * open[k][i] / all;
            walled = true;
        }
        if (!walled) return;
        wallFrom = source + WALL;
        // wall height averaged over blocked arc samples
        double top = 0;
        int tops = 0;
        for (int i = 0; i < RINGS; i++) {
            for (int k = 0; k < SECTORS; k++) {
                for (int j = 0; j < ARC; j++) {
                    double phi = 2 * Math.PI * (k + (j + 0.5) / ARC) / SECTORS, h = relief.at(r[i] * Math.cos(phi), r[i] * Math.sin(phi));
                    if (!(h > wallFrom)) continue;
                    top += h - source;
                    tops++;
                }
            }
        }
        wallTop = tops > 0 ? top / tops : 0;
        for (java.util.List<double[]>[] row : chain) java.util.Arrays.fill(row, null);
        axes.clear();
        // sector clump in display ring: mass-weighted center of its rings' open arcs
        for (int k = 0; k < SECTORS; k++) {
            double[] m = new double[ANNULI], a = new double[ANNULI], o = new double[ANNULI];
            for (int i = 0; i < RINGS; i++) {
                int q = band(r[i]);
                m[q] += mass[k][i];
                a[q] += mass[k][i] * angle[k][i];
                o[q] += mass[k][i] * open[k][i];
            }
            for (int q = 0; q < ANNULI; q++) {
                if (m[q] <= 0) continue;
                heading[k][q] = a[q] / m[q];
                openArc[k][q] = o[q] / m[q];
            }
        }
        // skirt gas occupies same area as in field: constrained spreading (street between houses) travels farther,
        // like dense channel flow. Sector mass share m/M occupies open area (m/M)·πR²; reach is
        // radius accumulating this open sector area (⚠ est.: flow depth same as in field; no farther than
        // REACH_MAX·R)
        double whole = 0;
        double[] sector = new double[SECTORS];
        for (int k = 0; k < SECTORS; k++) for (int i = 0; i < RINGS; i++) sector[k] += mass[k][i];
        for (double v : sector) whole += v;
        if (whole <= 0 || skirtR <= 0) return;
        for (int k = 0; k < SECTORS; k++) {
            double need = sector[k] / whole * Math.PI * skirtR * skirtR, got = 0, rho = 0, dr = Math.max(0.5, skirtR / 40);
            while (got < need && rho < REACH_MAX * skirtR) {
                double mid = rho + dr / 2;
                int n = 0;
                for (int j = 0; j < ARC; j++) {
                    double phi = 2 * Math.PI * (k + (j + 0.5) / ARC) / SECTORS;
                    if (!(relief.at(mid * Math.cos(phi), mid * Math.sin(phi)) > source + WALL)) n++;
                }
                double add = 2 * Math.PI / SECTORS * mid * dr * n / ARC;
                if (got + add >= need && add > 0) {
                    rho += dr * (need - got) / add;
                    got = need;
                } else {
                    got += add;
                    rho += dr;
                }
            }
            reach[k] = Math.max(1, Math.min(REACH_MAX, rho / skirtR));
        }
    }

    /**
     * Sector k skirt clump position in display ring q stretched to rad (m from explosion): clump travels from impact
     * through open space, choosing at each half-meter radius the open sector-arc angle nearest its previous
     * half-meter angle (initially rings' open-arc center); no open arc means clump stops at previous half-meter:
     * skirt cannot pass that wall. Clump stretched by reach and slumping thus stays in street, not house,
     * and for an off-center intersection impact follows the street it entered (choosing angle nearest arc center at
     * each radius would jump into adjacent street, moving clump ten meters); chain step never exceeds
     * {@value #CHAIN_STEP} m from previous position, so clump cannot appear behind wall. One chain for all
     * frames (independent of frame rate); {angle, radius}. Clump path constrains below-roof wind drift ({@link #step}).
     */
    private double[] open(int k, int q, double rad) {
        int at = (int) Math.round(2 * rad);
        java.util.List<double[]> c = chain[k][q];
        if (c == null) {
            c = chain[k][q] = new java.util.ArrayList<>();
            c.add(new double[]{heading[k][q], 0, 1});
        }
        double from = 2 * Math.PI * k / SECTORS;
        while (c.size() <= at) {
            SmokePreparation.checkpoint();
            double[] last = c.get(c.size() - 1);
            double r1 = 0.5 * c.size(), gap = Double.MAX_VALUE;
            double[] next = {last[0], last[1], 0};
            for (int j = 0; j < 2 * ARC; j++) {
                double phi = from + 2 * Math.PI / SECTORS * (j + 0.5) / (2 * ARC);
                double x = r1 * Math.cos(phi), z = r1 * Math.sin(phi);
                // path step stays open and near previous position: no jump through wall into adjacent street
                double far = Math.hypot(x - last[1] * Math.cos(last[0]), z - last[1] * Math.sin(last[0]));
                if (far > CHAIN_STEP || relief.at(x, z) > wallFrom) continue;
                if (Math.abs(phi - last[0]) < gap) {
                    gap = Math.abs(phi - last[0]);
                    next = new double[]{phi, r1, 1};
                }
            }
            c.add(next);
        }
        double[] here = c.get(at);
        // open at this half-meter: at actual radius, otherwise where clump stopped
        return new double[]{here[0], here[2] > 0 ? rad : here[1]};
    }

    /** Maximum open-space clump-path step ({@link #open}), m. */
    static final double CHAIN_STEP = 1.5;

    /** Angle between axis m (π/8 steps) and direction a, rad, ignoring axis direction sign. */
    private static double off(int m, double a) {
        return Math.abs(Math.IEEEremainder(a - Math.PI * m / 8, Math.PI));
    }

    /**
     * How much wind drift (sx, sz) over a skirt clump path sample from (x, z) at age tau and level
     * {@code level} is held by street (x, z). Below roofs, canyon wind follows street, walls resist crosswind (Dobre et
     * al. 2005, Atmos. Environ. 39:4647: along-canyon component projects above-roof wind onto its axis); transverse
     * share relative to local street axis ({@link #axis}) is fully held below half wall height (canyon vortex), less toward top.
     * Remaining drift into a wall above both wall base ({@link #wallFrom}) and clump center (lower walls are below clump) is
     * fully held: clump stops by wall. Delay accumulates by samples, as at a dam, so changing axis along path does not
     * shift position.
     */
    private double[] canyon(int show, double tau, double x, double z, double sx, double sz, double level) {
        double zc = 0.8 * at(sv[show], tau), w = wallTop > 0 ? Math.clamp(2 - 2 * zc / wallTop, 0, 1) : 0, hx = 0, hz = 0;
        if (w > 0) {
            double a = axis(x, z, Math.atan2(sz, sx));
            if (!Double.isNaN(a)) {
                double ex = Math.cos(a), ez = Math.sin(a), par = sx * ex + sz * ez;
                hx = w * (sx - par * ex);
                hz = w * (sz - par * ez);
            }
        }
        if (relief.at(x + sx - hx, z + sz - hz) > Math.max(wallFrom, level + zc)) return new double[]{sx, sz};
        return new double[]{hx, hz};
    }

    /**
     * Skirt clump at (x, z), center height {@code middle}, inside a wall above both wall base ({@link #wallFrom}) and center:
     * nearest open spot (rings every {@value #OUT_STEP} m to {@value #OUT_MAX} m, 16 directions). Clump path itself constrains
     * drift and growth into wall ({@link #step}), but by samples: between path nodes delay is linear while drift follows
     * nonlinearly, so near street edge the center can be a little over a meter inside a wall (test-city measurement); here it moves out to
     * the wall. No open spot farther out: leave as is.
     */
    private double[] out(double x, double z, double middle) {
        double top = Math.max(wallFrom, middle);
        if (!(relief.at(x, z) > top)) return new double[]{x, z};
        for (double r = OUT_STEP; r <= OUT_MAX + 1e-9; r += OUT_STEP) {
            for (int q = 0; q < 16; q++) {
                double a = Math.PI * q / 8, px = x + r * Math.cos(a), pz = z + r * Math.sin(a);
                if (!(relief.at(px, pz) > top)) return new double[]{px, pz};
            }
        }
        return new double[]{x, z};
    }

    /** Step and search limit for open spot around clump in wall ({@link #out}), m. */
    static final double OUT_STEP = 0.25, OUT_MAX = 1.5;

    /** Drift held by street before path start (age t, clump position {@code at}) ({@link #canyon}, single sample). */
    private double[] young(int show, double t, double[] at, double level) {
        if (!walled) return new double[]{0, 0};
        double[] d = drift(t, 0.8 * at(sv[show], t));
        return canyon(show, t, at[0] - d[0], at[1] - d[1], d[0], d[1], level);
    }

    /** Street axes ({@link #axis}) by {@value #AXIS_CELL} m cells and direction (π/8 step). */
    private final java.util.HashMap<Long, Double> axes = new java.util.HashMap<>();
    /** Street-axis cache cell, m. */
    static final int AXIS_CELL = 2;

    /** Street-axis probe half-length, m. */
    static final int AXIS_RUN = 8;

    /**
     * Street axis at (x, z) relative to explosion, rad: of 8 directions (π/8 step), greatest difference between open reach along both directions (up to
     * {@value #AXIS_RUN} m, 3 m strip) and across (intersection diagonal is open as far along as
     * street, but also across); ties favor nearest {@code toward}. Nearly equal crosswise openness
     * (intersection, square) or nowhere open means {@link Double#NaN}: wind does not turn there.
     */
    private double axis(double x, double z, double toward) {
        long cx = Math.floorDiv((long) Math.floor(x), AXIS_CELL), cz = Math.floorDiv((long) Math.floor(z), AXIS_CELL);
        int bucket = Math.floorMod(Math.round(toward * 8 / Math.PI), 8);
        long key = ((cx & 0xFFFFF) << 24) | ((cz & 0xFFFFF) << 4) | bucket;
        Double known = axes.get(key);
        if (known != null) return known;
        // cell axis uses cell center and direction-bin center, not first query position; otherwise answer depends on
        // which frame asked first (frame rate, clump order)
        x = (cx + 0.5) * AXIS_CELL;
        z = (cz + 0.5) * AXIS_CELL;
        toward = Math.PI * bucket / 8;
        // three-line strip at ±1.5 m: a single line passes diagonally through intersection corner, a strip does not
        double[] run = new double[8];
        for (int m = 0; m < 8; m++) {
            double c = Math.cos(Math.PI * m / 8), sn = Math.sin(Math.PI * m / 8);
            for (double lane = -1.5; lane <= 1.5; lane += 1.5) {
                double x0 = x - lane * sn, z0 = z + lane * c;
                for (int side = -1; side <= 1; side += 2) {
                    for (int step = 1; step <= AXIS_RUN; step++) {
                        if (relief.at(x0 + side * step * c, z0 + side * step * sn) > wallFrom) break;
                        run[m]++;
                    }
                }
            }
        }
        // street: far open along, not across; greatest difference, ties nearest toward
        int best = 0;
        for (int m = 1; m < 8; m++) {
            double gain = run[m] - run[(m + 4) % 8], was = run[best] - run[(best + 4) % 8];
            if (gain > was || gain == was && off(m, toward) < off(best, toward)) best = m;
        }
        double a = run[best] - run[(best + 4) % 8] < 0.3 * run[best] ? Double.NaN : Math.PI * best / 8;
        axes.put(key, a);
        return a;
    }

    /** Maximum skirt reach along street in field radii (⚠ est.: block length and gas reserve). */
    static final double REACH_MAX = 3;

    /** Sector k skirt reach relative to field (checks). */
    double reach(int k) {
        return reach[k];
    }

    /** Sector k clump angle in display ring q, rad (checks). */
    double heading(int k, int q) {
        return heading[k][q];
    }

    /**
     * Burst height above surface rounded for ring cache: half-meter below 4 m, 10% steps above (surface pressure
     * varies smoothly with it).
     */
    static double rounded(double h) {
        if (h <= 4) return Math.round(2 * h) / 2.0;
        double step = Math.log(1.1);
        return Math.exp(Math.round(Math.log(h) / step) * step);
    }

    /** Calculation rings for one charge and height: independent of air (Grad salvo computes once). */
    private record Rings(double[] mass, double[] arrival, double[] positive, double[] first, double[] stem, double[] stemFrom, boolean[] ball) {
    }

    private static final java.util.Map<String, Rings> CACHE = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Rings> e) {
            return size() > 256;
        }
    };

    /**
     * Calculation rings for charge {@code tntKg} at height {@code hob} above {@code surface}, ball {@code ballM}, to
     * radius {@code outer} (cached; otherwise computed now).
     */
    private static Rings rings(double tntKg, double hob, double ballM, DustModel.Surface surface, double outer) {
        String key = tntKg + "/" + hob + "/" + ballM + "/" + surface.threshold() + "/" + outer;
        synchronized (CACHE) {
            Rings k = CACHE.get(key);
            if (k != null) return k;
        }
        double y = CloudModel.equivalentKt(tntKg);
        double[] mass = new double[RINGS], arrival = new double[RINGS], positive = new double[RINGS], first = new double[RINGS],
                stem = new double[RINGS], stemFrom = new double[RINGS];
        boolean[] ball = new boolean[RINGS];
        for (int i = 0; i < RINGS; i++) {
            double r0 = i == 0 ? 0 : INNER * Math.pow(outer / INNER, (double) i / RINGS), r1 = INNER * Math.pow(outer / INNER, (i + 1.0) / RINGS);
            double r = i == 0 ? 0.5 * r1 : Math.sqrt(r0 * r1);
            DustModel.Column c = DustModel.chemical(r, hob, y, ballM, surface);
            arrival[i] = HobBlast.arrivalSeconds(r, hob, y);
            positive[i] = c.positive();
            mass[i] = c.mass() * Math.PI * (r1 * r1 - r0 * r0);
            first[i] = c.first();
            stem[i] = c.stem();
            stemFrom[i] = c.stemFrom();
            ball[i] = r * r + hob * hob <= ballM * ballM;
        }
        Rings k = new Rings(mass, arrival, positive, first, stem, stemFrom, ball);
        synchronized (CACHE) {
            CACHE.put(key, k);
        }
        return k;
    }

    /** Grid node s time, s. */
    static double time(int s) {
        return T0 * (Math.pow(GROW, s) - 1);
    }

    /** Grid node (fractional) for time t. */
    private static double node(double t) {
        return Math.min(STEPS - 1.0, Math.log(1 + Math.max(t, 0) / T0) / Math.log(GROW));
    }

    /** Table a over grid nodes at age t, linear interpolation; one node per age (one logarithm per frame). */
    private double at(double[] a, double t) {
        if (t != nodeT) {
            nodeT = t;
            nodeAt = node(t);
        }
        double s = nodeAt;
        int i = (int) Math.min(a.length - 2, Math.floor(s));
        return a[i] + (a[i + 1] - a[i]) * (s - i);
    }

    /** Near-ground wind travel over t, m (at least 1 m/s equivalent: calm air mixes too). */
    double path(double t) {
        return Math.max(1, groundWind) * t;
    }

    /** Vertical layer σ at age t: phase-end Kuhl top / 2.5 and air turbulence (Briggs), m. */
    private double sigmaV(double top, double t) {
        double s0 = top / 2.5, s = CloudModel.spreadV(air.spread(), path(t));
        return Math.max(0.05, Math.sqrt(s0 * s0 + s * s));
    }

    /** Layer share still above inflow zone (wind removes it over half inflow radius, inflow fades). */
    private double under(double t) {
        double reach = Math.max(stemReach, 1e-3), d = groundWind * t;
        double u = Math.max(0, Math.min(1, (d - 0.5 * reach) / (0.5 * reach)));
        return 1 - u * u * (3 - 2 * u);
    }

    /** Lifted share of ring i, sector k, by age t (since explosion): local phase from front arrival. */
    private double liftedShare(int k, int i, double t) {
        double age = t - arrival[k][i];
        return age <= 0 ? 0 : DustModel.lifted(age / Math.max(positive[k][i], 1e-6));
    }

    /** Ring i, sector k, at age t: extinction in mixture kg after lifting, settling and inflow loss. */
    private double ringNow(int k, int i, double t) {
        double m = mass[k][i] * liftedShare(k, i, t);
        if (m <= 0) return 0;
        double left = Math.exp(-stem[k][i] * at(stemTime, t) / DustModel.STEM_S);
        return m * DustModel.extinctionAloft(at(settled[annulus[i]], t)) * left;
    }

    /** Same by mass, kg: layer gravity follows airborne dust mass (settled coarse dust no longer pulls layer). */
    private double massNow(int k, int i, double t) {
        double m = mass[k][i] * liftedShare(k, i, t);
        if (m <= 0) return 0;
        double left = Math.exp(-stem[k][i] * at(stemTime, t) / DustModel.STEM_S);
        return m * DustModel.airborne(at(settled[annulus[i]], t)) * left;
    }

    /** Layer top in ring i, sector k, at age t during wave phase: Kuhl by front travel (∝ age^⅚ toward {@code first}), m. */
    private double waveTop(int k, int i, double t) {
        double age = t - arrival[k][i];
        if (age <= 0) return 0;
        return first[k][i] * Math.pow(Math.min(1, age / Math.max(positive[k][i], 1e-6)), 5 / 6.0);
    }

    /** Dust lifted under fireball by age t, kg (no settling: it is in cloud). */
    public double underBall(double t) {
        double m = 0;
        for (int k = 0; k < SECTORS; k++) for (int i = 0; i < RINGS; i++) if (ball[k][i]) m += mass[k][i] * liftedShare(k, i, t);
        return m;
    }

    /** Dust entering inflow by age t (extinction in mixture kg). */
    public double drawn(double t) {
        return at(drawn, t);
    }

    /** Front over surrounding surfaces at age t, m (farthest sector): no dust beyond it. */
    public double front(double t) {
        double f = 0;
        for (int k = 0; k < SECTORS; k++) f = Math.max(f, front(k, t));
        return f;
    }

    /**
     * Front over sector k surface at age t, m. Between rings, follows arrival ({@link HobBlast#arrivalSeconds}); beyond
     * last, sound speed.
     */
    double front(int k, double t) {
        // skirt: edge travels smoothly from impact, R = 2R₀ at SKIRT_S (Ebersole et al. 1980), same speed beyond (layer
        // stretched farther by slumping, {@link #stretch}); area-centered rings cannot define front
        if (skirt) return skirtR * reach[k] * Math.max(0, t) / SKIRT_S;
        if (RINGS == 0 || r[RINGS - 1] <= 0 || t <= arrival[k][0]) return 0;
        for (int i = 1; i < RINGS; i++) {
            if (arrival[k][i] > t) return r[i - 1] + (r[i] - r[i - 1]) * (t - arrival[k][i - 1]) / Math.max(arrival[k][i] - arrival[k][i - 1], 1e-9);
        }
        return r[RINGS - 1] + ua.zentix.incoming.nuclear.model.BlastModel.SOUND_SPEED * (t - arrival[k][RINGS - 1]);
    }

    /** Layer slumping by age t: radius growth ratio R/R₀ (gravity current, {@link #FR}). */
    double stretch(double t) {
        return Math.sqrt(1 + at(slump, t) / slumpFrom);
    }

    /**
     * Descent over {@code dt} s above a drop for a clump with {@code dust} kg dust in Gaussian volume σh, σv: heavy
     * MTT thermal (warm-cloud law, {@link Thermal}, with reversed sign; Boussinesq: ⚠ at impact dust mass in
     * air exceeds air mass, so falls faster): R = R₀ + αz, d(R³W)/dt = F, F = 3B/(4π), B = g·M/ρ;
     * from rest (R₀ + αz)⁴ = R₀⁴ + 2αF·t². Neutral air (⚠ stability barely slows tens-of-meters descent over tens of seconds:
     * N·t ~ 0.2). R₀ is sphere of layer volume 2πσh²·1.25σv.
     */
    static double fall(double dust, double sh, double sv, double dt) {
        return dust <= 0 ? 0 : fall(radius(sh, sv), buoyancy(dust), dt);
    }

    /** Heavy-thermal R₀ for clump σh, σv: sphere of layer volume 2πσh²·1.25σv, m. */
    static double radius(double sh, double sv) {
        return Math.cbrt(3 * (2 * Math.PI * sh * sh * 1.25 * sv) / (4 * Math.PI));
    }

    /** F = 3B/(4π), B = g·M/ρ for {@code dust} kg dust, m⁴/s². */
    static double buoyancy(double dust) {
        return 3 * CloudModel.G * Math.max(dust, 0) / CloudModel.RHO / (4 * Math.PI);
    }

    /** Descent over dt for thermal R₀, F from rest: ((R₀⁴ + 2αF·t²)^¼ − R₀)/α, m. */
    static double fall(double r0, double f, double dt) {
        if (f <= 0 || dt <= 0) return 0;
        double a = CloudModel.ALPHA, r4 = r0 * r0 * r0 * r0;
        return (Math.sqrt(Math.sqrt(r4 + 2 * a * f * dt * dt)) - r0) / a;
    }

    /** Maximum visible clump edge in σ (optical depth in hundreds: edge at 0.05 threshold is up to ~4σ, {@code SmokePuffs.edge}). */
    static final double EDGE_SIGMAS = 4;

    /** Layer clump sink (center relative to explosion, m). */
    public interface Blobs {
        void blob(double x, double y, double z, double sh, double sv, double dust);
    }

    /**
     * Display rings at age t: sector clumps (mass by sector surface), σ in quadrature with Briggs
     * spread, visible edge no farther than front; layer slumps as gravity current and follows wind at layer
     * center height, descends over drops toward surface below. {@code fade} is rendered share.
     */
    public void blobs(double t, double fade, Blobs out) {
        emit(t, fade, true, (x, y, z, sh, sv, dust, mass, source) -> out.blob(x, y, z, sh, sv, dust));
        preparedNode = pathNode(t);
    }

    private int preparedNode = -1;
    private static final Probe DISCARD = (x, y, z, sh, sv, dust, mass, source) -> {};

    /** Keep the fine terrain path current without generating a rendered cloud. Called in a bounded maintenance slice. */
    public void maintain(double t) {
        if (t < PATH[PATH_FROM] || total <= 0 || pathNode(t) == preparedNode) return;
        emit(t, 1, true, DISCARD);
        preparedNode = pathNode(t);
    }

    private static int pathNode(double t) {
        int node = java.util.Arrays.binarySearch(PATH, t);
        return Math.min(PATH.length - 2, node < 0 ? -node - 2 : node);
    }

    /** Envelope of the same radial growth, spread and wind law, without visiting the terrain path. */
    public void bounds(double t, double floor, double ceiling, SmokeBounds out) {
        if (total <= 0 || t <= 0) return;
        int s = (int) node(t);
        if (boundWidth[s] < 0) return;
        double v = boundInitialV, lowWind = Double.POSITIVE_INFINITY, highWind = 0;
        for (double[] row : sv) {
            double sigma = at(row, t), drift = air.wind(Math.max(2, 0.8 * sigma)) * t;
            v = Math.max(v, sigma); lowWind = Math.min(lowWind, drift); highWind = Math.max(highWind, drift);
        }
        double radial = boundRadius[s] * stretch(t);
        double spread = CloudModel.spreadH(air.spread(), path(t));
        double width = Math.max(Math.hypot(boundWidth[s] * stretch(t), spread), v);
        // A street chain advances at most CHAIN_STEP per half-metre radius. Held motion can remove either
        // radial or wind increments. Allow both signs, plus the final correction out of a wall.
        double r = (walled ? 2 * CHAIN_STEP / 0.5 : 2) * radial + OUT_MAX + SmokeBounds.PUFF * width;
        double x0, x1, z0, z1;
        if (walled) {
            double wind = 2 * Math.hypot(air.windX(), air.windZ()) * (windForward[s] + windBackward[s]);
            x0 = z0 = -wind; x1 = z1 = wind;
        } else {
            double lo = lowWind - windForward[s], hi = highWind + windBackward[s];
            x0 = Math.min(air.windX() * lo, air.windX() * hi); x1 = Math.max(air.windX() * lo, air.windX() * hi);
            z0 = Math.min(air.windZ() * lo, air.windZ() * hi); z1 = Math.max(air.windZ() * lo, air.windZ() * hi);
        }
        out.box(x0 - r, floor - SmokeBounds.PUFF * width, z0 - r, x1 + r, ceiling + SmokeBounds.PUFF * width + 0.8 * v, z1 + r);
    }

    /** Layer clump for checks: both dust mass, kg (for layer gravity), and source surface, m. */
    interface Probe {
        void blob(double x, double y, double z, double sh, double sv, double dust, double mass, double source);
    }

    /** Clumps at age t with mass ({@link Probe}). */
    void probe(double t, Probe out) {
        emit(t, 1, true, out);
    }

    /** Clumps at age t; {@code levels} gives resting surfaces (absent: source surface for layer depth). */
    private void emit(double t, double fade, boolean levels, Probe out) {
        if (total <= 0 || t <= 0) return;
        // one ring pass: each display ring in sector gets mass, mass radius and second moment, share still in wave phase,
        // airborne dust mass (layer gravity, {@link #airborne}, same calculation; otherwise reread per display
        // clump, all rings for every display ring, one fifth of smoke CPU in 100-strike raid)
        double[][] m = new double[ANNULI][SECTORS], mr = new double[ANNULI][SECTORS], mr2 = new double[ANNULI][SECTORS],
                wt = new double[ANNULI][SECTORS], wtop = new double[ANNULI][SECTORS], aloft = new double[ANNULI][SECTORS];
        for (int k = 0; k < SECTORS; k++) {
            for (int i = 0; i < RINGS; i++) {
                if (ball[k][i]) continue;
                int a = annulus[i];
                if (levels) aloft[a][k] += massNow(k, i, t);
                double e = ringNow(k, i, t);
                if (e <= 0) continue;
                m[a][k] += e;
                mr[a][k] += e * r[i];
                mr2[a][k] += e * r[i] * r[i];
                if (t - arrival[k][i] < positive[k][i]) {
                    wt[a][k] += e;
                    wtop[a][k] += e * waveTop(k, i, t);
                }
            }
        }
        double spread = CloudModel.spreadH(air.spread(), path(t)), stretch = stretch(t);
        for (int a = 0; a < ANNULI; a++) {
            double table = at(sv[a], t);
            for (int k = 0; k < SECTORS; k++) {
                double mass = m[a][k];
                if (mass <= 1e-9) continue;
                // vertical σ: during wave phase, Kuhl top of share still in phase; afterward table
                double v = table;
                if (wt[a][k] > 0) v = wt[a][k] / mass * Math.max(0.05, wtop[a][k] / wt[a][k] / 2.5) + (1 - wt[a][k] / mass) * v;
                double zc = 0.8 * v;
                // sector display ring is a clump at mass-mean radius; σ is radial ring spread, at least sector arc width;
                // layer slumps (radius and width × R/R₀); visible edge (4σ) no farther than front
                double source = mr[a][k] / mass, var = Math.max(0, mr2[a][k] / mass - source * source);
                // skirt travels farther along street: ring position and radial spread stretched by sector reach; crosswise no wider than
                // open arc
                double own = Math.max(Math.sqrt(var) * reach[k], Math.PI * source / SECTORS / 1.25 * openArc[k][a]);
                source *= reach[k];
                double mean = source * stretch;
                double[] e = edge(mean, Math.max(Math.hypot(own * stretch, spread), v), own, v, front(k, t));
                mean = e[0];
                double sh = e[1];
                double ang = heading[k][a], path = source;
                if (walled && mean > 0) {
                    double[] o = open(k, a, mean);
                    ang = o[0];
                    source *= o[1] / mean;
                    mean = o[1];
                }
                double ux = Math.cos(ang), uz = Math.sin(ang);
                double[] d = drift(t, zc);
                // clump path along street starts from same open position ({@link #place}) as clump itself
                double[] at = levels ? level(k, a, t, walled ? path : source, ux, uz, own) : new double[]{surface[k][a], 0, 0};
                double x = d[0] + mean * ux - at[1], z = d[1] + mean * uz - at[2];
                if (walled) {
                    double[] p = out(x, z, at[0] + zc);
                    x = p[0];
                    z = p[1];
                }
                out.blob(x, at[0] + zc, z, sh, v, mass * fade, aloft[a][k], surface[k][a]);
            }
        }
    }

    /**
     * Clump radial center and σh when visible edge (4σ) would exceed front: stay within front ({@link #emit};
     * street clump path, {@link #place}, starts from same position).
     */
    private static double[] edge(double mean, double sh, double own, double v, double front) {
        if (mean + EDGE_SIGMAS * sh > front) {
            sh = Math.max((front - mean) / EDGE_SIGMAS, Math.min(sh, Math.max(own, v)));
            mean = Math.max(0, Math.min(mean, front - EDGE_SIGMAS * sh));
            // front still at crater: clumps near center, no wider than front
            if (mean + EDGE_SIGMAS * sh > front) sh = Math.max(front / EDGE_SIGMAS, v);
        }
        return new double[]{mean, sh};
    }

    /** Airborne dust mass in display-ring {@code show} clump of sector k at age t, kg. */
    private double airborne(int k, int show, double t) {
        double m = 0;
        for (int i = 0; i < RINGS; i++) if (annulus[i] == show && !ball[k][i]) m += massNow(k, i, t);
        return m;
    }

    /**
     * Clump path grid, s: 0.25 s steps initially (roof edge, landing), then 2% of age, at most 2 s, for clump
     * far from impact when already tens of meters wide.
     */
    static final double[] PATH;

    static {
        java.util.List<Double> g = new java.util.ArrayList<>();
        for (double t = 0; t <= CloudModel.LIFE_MAX; t += Math.min(2, Math.max(0.25, 0.02 * t))) g.add(t);
        PATH = g.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * Clump path state at {@link #PATH} node: resting surface level (m from explosion), and
     * falling: initial level and time, heavy-thermal R₀ and F ({@link #fall}).
     */
    private static final class Track {
        double level, from, since, r0, f;
        /** Last known surface below clump, m ({@link Double#NaN} if none yet). */
        double seen = Double.NaN;
        /** Drift and slumping held by a dam (obstacle wider than footprint and higher than layer), m in x and z. */
        double heldX, heldZ;
        boolean falling;

        Track copy() {
            Track c = new Track();
            c.heldX = heldX;
            c.heldZ = heldZ;
            c.seen = seen;
            c.level = level;
            c.from = from;
            c.since = since;
            c.r0 = r0;
            c.f = f;
            c.falling = falling;
            return c;
        }
    }

    /**
     * Clump path: grid node and states there and next; level is linear between nodes, so each node is
     * computed once (relief samples only on node transition).
     */
    private static final class Path {
        int node;
        Track now, next;
    }

    /** Clump paths [display ring][sector]: advance with frames; rebuild backward (seek). */
    private final Path[][] paths = new Path[ANNULI][SECTORS];

    /** Maximum path samples per {@link #PATH} step (sample step is clump half-width, at least one meter). */
    static final int PROBES = 16;
    /** {@link #PATH} node where path starts (0.5 s): by then front passed display rings, radii settled. */
    static final int PATH_FROM = 2;

    /**
     * Resting surface level for display ring {@code show}, sector {@code sector} clump at age t, m from
     * explosion. Clump path (layer slumping along {@code ux, uz} from radius {@code source} and wind drift at layer center
     * height then) follows {@link #PATH} grid from source surface. Heavy air drains to the lowest
     * place it reaches: clump surface is lower terrain under footprint ({@link #under}: street between
     * houses, slope bottom, street beyond roof edge), sampled every clump half-width (at least every meter). Downward layer follows it
     * while gradient is no steeper than {@link #DROP} per meter (up to 45°: Minecraft terrain has block steps); beyond a steeper edge,
     * descends by faster of: heavy thermal from edge-exit instant (airborne dust mass and clump
     * size then; buoyancy retained afterward, ⚠ settling coarse dust removes some), or wind in edge
     * lee ({@link #LEE}), until landing. Uphill no steeper than 45° traveled path and only rises no higher than
     * layer depth (2σv: gravity current deeper than obstacle overflows it, Rottman and Simpson 1983); before
     * a higher rise (obstacle wider than footprint: terrace, slope, large house), dam: waits until layer thickens (⚠
     * no flow around it). Unknown relief: resting clump keeps level, falling clump falls to the lowest
     * known surface beneath it (last known, otherwise explosion-observed sector surface).
     */
    private double[] level(int sector, int show, double t, double source, double ux, double uz, double own) {
        // before PATH_FROM, front still lifts ring, mass-mean clump radius moves outward; path starts later, meanwhile clump
        // rests where lifted
        if (t < PATH[PATH_FROM]) {
            paths[show][sector] = null;
            double[] at = place(sector, show, t, source, ux, uz, own);
            double start = under(at[0], at[1], 0.5 * width(show, t, own)), level = begin(sector, show, start, ux, uz, at);
            double[] held = young(show, t, at, level);
            return new double[]{level, held[0], held[1]};
        }
        Path p = paths[show][sector];
        if (p == null || PATH[p.node] > t) {
            // start: {@link #begin}
            p = new Path();
            p.node = PATH_FROM;
            p.now = new Track();
            double[] at = place(sector, show, PATH[PATH_FROM], source, ux, uz, own);
            double start = under(at[0], at[1], 0.5 * width(show, PATH[PATH_FROM], own));
            p.now.level = begin(sector, show, start, ux, uz, at);
            p.now.seen = start;
            double[] held = young(show, PATH[PATH_FROM], at, p.now.level);
            p.now.heldX = held[0];
            p.now.heldZ = held[1];
            p.next = p.now.copy();
            step(p.next, sector, show, PATH[PATH_FROM], PATH[PATH_FROM + 1], source, ux, uz, own);
            paths[show][sector] = p;
        }
        int node = pathNode(t);
        while (node > p.node) {
            SmokePreparation.checkpoint();
            Track next = p.next.copy();
            step(next, sector, show, PATH[p.node + 1], PATH[p.node + 2], source, ux, uz, own);
            p.node++;
            p.now = p.next;
            p.next = next;
        }
        double u = Math.max(0, Math.min(1, (t - PATH[p.node]) / (PATH[p.node + 1] - PATH[p.node])));
        return new double[]{p.now.level + u * (p.next.level - p.now.level), p.now.heldX + u * (p.next.heldX - p.now.heldX),
                p.now.heldZ + u * (p.next.heldZ - p.now.heldZ)};
    }

    /**
     * Clump path source surface: beneath footprint at its position ({@code under}, position {@code at} relative to explosion)
     * if radial path from band point with known source surface to it stays on
     * one surface (nowhere steeper than 45° per meter: slope, yard); if wall (house) lies between, no higher than
     * source surface: heavy dust cannot reach roof by itself (in first fractions of a second footprint is smaller than house, and street
     * ring center can lie on it). Unknown: band point.
     */
    private double begin(int sector, int show, double under, double ux, double uz, double[] at) {
        double source = surface[sector][show];
        if (Double.isNaN(under)) return source;
        if (under <= source) return skirt && under < source - DROP ? source : under;
        // skirt starts at impact itself and its height: wall between it and clump (house beside street) holds it below, even if
        // band point is already on roof
        double x0 = skirt ? 0 : BANDS[show] * ux, z0 = skirt ? 0 : BANDS[show] * uz, len = Math.hypot(at[0] - x0, at[1] - z0);
        int n = (int) Math.max(1, Math.min(32, Math.ceil(len)));
        double step = Math.max(1, len / n), last = skirt ? source : relief.at(x0, z0);
        for (int i = 1; i <= n; i++) {
            double h = relief.at(x0 + (at[0] - x0) * i / n, z0 + (at[1] - z0) * i / n);
            if (Double.isNaN(h)) continue;
            if (!Double.isNaN(last) && Math.abs(h - last) > DROP * step + DROP) return source;
            last = h;
        }
        return under;
    }

    /** Display ring {@code show} clump width {@code own} at age t (σh), m. */
    private double width(int show, double t, double own) {
        return Math.max(Math.hypot(own * stretch(t), CloudModel.spreadH(air.spread(), path(t))), at(sv[show], t));
    }

    /** Clump path position at age t (x, z relative to explosion). */
    private double[] place(int sector, int show, double t, double source, double ux, double uz, double own) {
        double v = at(sv[show], t);
        double[] d = drift(t, 0.8 * v);
        double mean = source * stretch(t);
        // in street, from same position as clump: within front ({@link #edge}; σ excludes wave-phase share, which lasts fractions of
        // a second near impact), through open space ({@link #open})
        if (walled) mean = edge(mean, width(show, t, own), own, v, front(sector, t))[0];
        if (walled && mean > 0) {
            double[] o = open(sector, show, mean);
            return new double[]{d[0] + o[1] * Math.cos(o[0]), d[1] + o[1] * Math.sin(o[0])};
        }
        return new double[]{d[0] + mean * ux, d[1] + mean * uz};
    }

    /**
     * Surface under clump centered at (x, z), half-width w, m relative to explosion: third lowest of 13 relief samples
     * (lower ~20% of footprint), at center and
     * ±w and ±0.62w along axes and diagonals ({@link #FOOTPRINT}: incommensurate steps prevent a footprint matching block spacing from
     * sampling only roofs). Heavy air drains to the low it reaches (posts, trunks, roofs beside street do not
     * lift it; house wider than clump holds it), but gap with one or two samples (ravine, ditch) does not take the entire clump;
     * all unknown: {@link Double#NaN}.
     */
    /** Clump footprint samples as half-width fractions. */
    static final double[][] FOOTPRINT = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0.62, 0}, {-0.62, 0}, {0, 0.62}, {0, -0.62},
            {0.71, 0.71}, {-0.71, 0.71}, {0.71, -0.71}, {-0.71, -0.71}};

    double under(double x, double z, double w) {
        double[] h = new double[FOOTPRINT.length];
        int n = 0;
        for (double[] d : FOOTPRINT) {
            double v = relief.at(x + w * d[0], z + w * d[1]);
            if (!Double.isNaN(v)) h[n++] = v;
        }
        if (n == 0) return Double.NaN;
        java.util.Arrays.sort(h, 0, n);
        return h[Math.min(n - 1, n / 6)];
    }

    /**
     * Beyond roof or cliff edge, wind lowers layer no steeper than this many meters per meter of drift: flow separates at sharp
     * edge and reattaches after ~2 obstacle heights (cube in boundary layer: 1.5–2.5 H, Castro &amp; Robins
     * 1977, JFM 79; ⚠ farther for long house or ridge).
     */
    static final double LEE = 0.5;

    /** Clump path step from t0 to t1 ({@link #level}). */
    private void step(Track tr, int sector, int show, double t0, double t1, double source, double ux, double uz, double own) {
        double[] p0 = place(sector, show, t0, source, ux, uz, own), p1 = place(sector, show, t1, source, ux, uz, own);
        double dx = p1[0] - p0[0], dz = p1[1] - p0[1], len = Math.hypot(dx, dz), w = 0.5 * width(show, t1, own);
        int n = (int) Math.max(1, Math.min(PROBES, Math.ceil(len / Math.max(1, w))));
        // in street, at least every meter traveled: each sample constrains crosswind drift and wall ({@link #canyon})
        if (walled) n = (int) Math.max(n, Math.min(PROBES, Math.ceil(len)));
        double[] w0 = drift(t0, 0.8 * at(sv[show], t0)), w1 = drift(t1, 0.8 * at(sv[show], t1));
        // slope no steeper than block per meter of travel and footprint half-width growth (growing footprint reaches farther downhill itself,
        // not an edge); beyond edge wind lowers layer by LEE meters per meter of drift
        double grow = Math.max(0, w - 0.5 * width(show, t0, own)) / n;
        double tol = DROP * Math.max(1, len / n + grow), lee = LEE * Math.hypot(w1[0] - w0[0], w1[1] - w0[1]) / n;
        for (int i = 1; i <= n; i++) {
            double u = (double) i / n, tau = t0 + u * (t1 - t0);
            if (walled) {
                double[] h = canyon(show, tau, p0[0] + (u - 1.0 / n) * dx - tr.heldX, p0[1] + (u - 1.0 / n) * dz - tr.heldZ,
                        (w1[0] - w0[0]) / n, (w1[1] - w0[1]) / n, tr.level);
                tr.heldX += h[0];
                tr.heldZ += h[1];
                // clump growth through open space ({@link #open}) carries only wind-displaced clump from another street into wall: hold it
                double top = Math.max(wallFrom, tr.level + 0.8 * at(sv[show], tau));
                if (relief.at(p0[0] + u * dx - tr.heldX, p0[1] + u * dz - tr.heldZ) > top) {
                    tr.heldX += (dx - (w1[0] - w0[0])) / n;
                    tr.heldZ += (dz - (w1[1] - w0[1])) / n;
                }
            }
            double below = under(p0[0] + u * dx - tr.heldX, p0[1] + u * dz - tr.heldZ, w), before = tr.seen;
            if (Double.isNaN(below)) {
                // unknown relief: resting keeps level, falling falls to lowest surface explosion observed
                if (tr.falling) fallTo(tr, Math.min(Double.isNaN(tr.seen) ? lowest(sector) : tr.seen, tr.level), tau, lee);
                continue;
            }
            if (below > tr.level) {
                // uphill: rise higher than layer and steeper than slope holds current level; lower rise, at most 45° path;
                // falling clump reaching surface lands
                if (below - tr.level > Math.max(tol, 2 * at(sv[show], tau))) {
                    // dam: heavy air stops before obstacle wider than footprint and higher than layer; remaining step drift and slumping
                    // wait until layer thickens
                    tr.heldX += (1 - (i - 1.0) / n) * dx;
                    tr.heldZ += (1 - (i - 1.0) / n) * dz;
                    // falling continues to surface where stopped (last known below)
                    if (tr.falling) fallTo(tr, Math.min(Double.isNaN(tr.seen) ? lowest(sector) : tr.seen, tr.level), t1, lee);
                    return;
                }
                tr.seen = below;
                tr.level = Math.min(below, tr.level + DROP * len / n);
                tr.falling = false;
                continue;
            }
            tr.seen = below;
            if (below >= tr.level - tol - DROP) {
                // downward slope (with block allowance for terrain steps), or falling clump reached surface: rests on it
                tr.level = below;
                tr.falling = false;
                continue;
            }
            if (tr.falling && !Double.isNaN(before) && before - below <= tol + DROP) {
                // falling over a slope layer could follow: descends with it too (lands on slope)
                tr.level -= Math.max(0, before - below);
            }
            if (!tr.falling) {
                // left edge: starts falling now, with mass and size at edge
                tr.falling = true;
                tr.from = tr.level;
                tr.since = t0 + (u - 0.5 / n) * (t1 - t0);
                tr.r0 = radius(width(show, tr.since, own), at(sv[show], tr.since));
                tr.f = buoyancy(airborne(sector, show, tr.since));
            }
            fallTo(tr, below, tau, lee);
        }
    }

    /**
     * Falling clump at tau above {@code floor}: descent is faster of heavy thermal and edge-lee wind
     * ({@code lee} is descent over this sample, {@link #LEE}); reaching surface lands it.
     */
    private static void fallTo(Track tr, double floor, double tau, double lee) {
        double level = Math.min(tr.from - fall(tr.r0, tr.f, tau - tr.since), tr.level - lee);
        if (level <= floor) {
            tr.level = floor;
            tr.falling = false;
        } else {
            tr.level = level;
        }
    }

    /** Lowest known sector surface, m relative to explosion (when relief below falling clump is unknown). */
    private double lowest(int sector) {
        double m = Double.POSITIVE_INFINITY;
        for (double h : surface[sector]) m = Math.min(m, h);
        return m;
    }

    /**
     * Layer wind drift over t, m (x and z): wind at layer-center height {@code zc} above its surface, at least 2 m (⚠
     * current center height for whole path: layer grows, initially traveled slightly slower).
     */
    public double[] drift(double t, double zc) {
        double lean = air.wind(Math.max(2, zc)) * Math.max(t, 0);
        return new double[]{air.windX() * lean, air.windZ() * lean};
    }

    /** Densest layer clump optical depth at age t (horizontal through center). */
    double tau(double t) {
        double[] max = {0};
        emit(t, 1, false, (x, y, z, sh, sv, dust, mass, source) -> max[0] = Math.max(max[0], DustModel.K_MIX * dust / (2 * Math.PI * sh * sv)));
        return max[0];
    }

    /** Layer visibility duration, s: while densest clump optical depth exceeds {@link CloudModel#SEEN} (at grid nodes). */
    private double lifeSeconds() {
        if (total <= 0) return 0;
        double last = 0;
        for (int s = 1; s < STEPS && time(s) <= CloudModel.LIFE_MAX; s++) if (tau(time(s)) >= CloudModel.SEEN) last = time(s);
        return last <= 0 ? 0 : Math.min(CloudModel.LIFE_MAX, last * GROW);
    }
}
