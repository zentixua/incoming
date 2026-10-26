package ua.zentix.incoming.warhead;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.model.Fragility;
import ua.zentix.incoming.strike.Article;
import ua.zentix.incoming.strike.WeaponSpec;

import java.util.SplittableRandom;

/**
 * Warhead casing fragments ({@code formulas.md} §7). Without Minecraft, shared by server ({@link FragmentStrike}:
 * damage, glass, chips) and client (dust/sparks at hits): same law/seed.
 * <ul>
 * <li><b>Casing</b>: explosive cylinder in steel shell of outer {@link Article.Charge#diameter}; masses/densities
 * give wall thickness/length (shape estimate: real warheads have endcaps, cones, preformed grooves).</li>
 * <li><b>Speed</b>: cylindrical Gurney, V₀ = √(2E)·√(β/(1 + β/2)), β = C/M (Cooper, “Explosives Engineering”,
 * 1996, ch. 27). TNT constant √(2E) = 2440 m/s, Composition B 2700 m/s (Cooper table 27.1; Ahmed et al. table
 * 2499/2774); other explosives interpolate by relative strength (spec TNT/explosive mass; Composition B 1.33):
 * estimate, greater accuracy requires explosive composition. Large β (Shahed 4) makes Gurney upper estimate: thin
 * casing ruptures before products fully accelerate it.</li>
 * <li><b>Masses</b>: Mott, N(&gt;m) = N₀·exp(−√(m/μ)), N₀ = M/(2μ), mean mass 2μ; √μ =
 * B·t<sup>5/6</sup>·d<sup>1/3</sup>·(1 + t/d), t/d wall thickness/inner diameter, m; B = 3.31
 * kg<sup>½</sup>·m<sup>−7/6</sup> for TNT, 2.70 for Composition B (Mott 1943; table Ahmed et al., Eng. Math.
 * Lett. 2021), interpolating likewise by strength.</li>
 * <li><b>Directions</b>: belt across axis, wall normal tilted by Taylor angle θ = V₀/(2·D) (D detonation speed:
 * TNT 6900 m/s, Composition B 7980) along detonation travel, nose (impact fuze) to tail, normal spread ±{@link
 * #BELT_SIGMA} (estimate: endcaps/uneven front). Add spec warhead impact velocity ({@link Article.Impact#speed},
 * real at any scale), shifting belt forward.</li>
 * <li><b>Deceleration</b>: v(x) = V₀·e<sup>−x/L</sup>, L = 2m/(ρ·C<sub>d</sub>·A), A = {@link
 * #AREA_SHAPE}·(m/ρ<sub>steel</sub>)<sup>2/3</sup>, mean tumbling projected area, quarter surface (Cauchy's
 * theorem; 1:2:4 cuboid 1.75, cube 1.5); C<sub>d</sub> = 1.21 for irregular supersonic fragments (Ahmed et al.);
 * real subsonic C<sub>d</sub> lower, slow fragments travel slightly farther. Ignore gravity: dangerous fragments
 * arrive within fractions of a second, falling under a meter.</li>
 * <li><b>Heating</b>: detonation shock heats casing steel (residual heating after 20–30 GPa, 100–200 K), plus
 * plastic stretching to rupture (0.5–1 GPa × strain ~0.6, 80–170 K): fragment ~500–700 K, below Draper point (798
 * K), so no in-flight glow (estimate; only thin rupture surfaces glow for microseconds). Visible hits: sparks
 * from stone/metal, soil dust.</li>
 * </ul>
 * Damage from impact energy: {@link #injury}, {@link #death}. Use {@link StrictMath}: host/friend on different
 * CPUs get bit-identical fragments.
 *
 * @param count fragment count N₀
 * @param mu Mott mass μ, kg (mean 2μ)
 * @param speed initial Gurney V₀ relative to warhead, m/s
 * @param taylor Taylor angle, rad
 * @param impactSpeed warhead impact speed, m/s
 * @param wall wall thickness, m
 * @param inner inner diameter, m
 * @param length explosive cylinder length, m
 */
public record FragmentModel(double count, double mu, double speed, double taylor, double impactSpeed, double wall, double inner,
                            double length) {
    /** Casing steel density, kg/m³. */
    public static final double STEEL = 7850;
    /** Mean tumbling fragment area as multiple of (m/ρ)<sup>2/3</sup>: 1:2:4 cuboid (shape estimate). */
    public static final double AREA_SHAPE = 1.75;
    /** Irregular supersonic fragment drag (Ahmed et al. 2021). */
    public static final double CD = 1.21;
    /** Sea-level air density, kg/m³ (ISA). */
    public static final double AIR = 1.225;
    /** Across-belt direction spread (σ), rad: estimate, cylindrical warhead belt 20–30° wide. */
    public static final double BELT_SIGMA = Math.toRadians(10);

    /** Gurney constants √(2E), m/s: TNT, Composition B (Cooper 1996, table 27.1). */
    static final double GURNEY_TNT = 2440, GURNEY_COMP_B = 2700;
    /** Mott constants B, kg<sup>½</sup>·m<sup>−7/6</sup> (Mott 1943; Ahmed et al. 2021). */
    static final double MOTT_TNT = 3.31, MOTT_COMP_B = 2.70;
    /** Detonation speed, m/s: cast TNT, Composition B. */
    static final double DETONATION_TNT = 6900, DETONATION_COMP_B = 7980;
    /** Explosive density, kg/m³: cast TNT 1.65, Composition B 1.72 g/cm³. */
    static final double DENSITY_TNT = 1650, DENSITY_COMP_B = 1720;
    /** Composition B (RDX/TNT 60/40) strength relative to TNT. */
    static final double RE_COMP_B = 1.33;

    /** Below this energy fragments injure nobody/break no glass; stop tracing, J. */
    public static final double STOP_J = 3;
    /**
     * Window glass breaks at this fragment energy, J (estimate: steel ball of a few joules breaks 4 mm annealed
     * glass).
     */
    public static final double GLASS_J = 3;
    /**
     * Hazardous fragment ≥79 J (58 ft·lb, DoD 6055.09-M, DDESB): median severe injury on hit (estimate: DDESB
     * uses threshold, β 0.5 spread estimated).
     */
    public static final double HAZARD_J = 79, HAZARD_BETA = 0.5;
    /**
     * Single-hit death: vital silhouette share (head, chest, abdomen, ~45%, estimate) × penetration probability
     * (median 150 J, β 0.5 estimated).
     */
    public static final double VITAL_SHARE = 0.45, DEEP_J = 150, DEEP_BETA = 0.5;
    /**
     * Masonry chip: ≥1 cm pit if energy per area exceeds compressive strength × 1 cm (50 MPa: brick 10–50, stone
     * 100–200, estimate), J/m².
     */
    public static final double CHIP_J_PER_M2 = 50e6 * 0.01;
    /** Maximum ray-traced fragments per blast; remainder represented by weight ({@link #weight}). */
    public static final int MAX_TRACED = 8192;
    /** Maximum fragment tracing distance, m. */
    public static final double MAX_RANGE = 1000;

    /** Striking weapon's warhead fragments; null if no ground explosion/casing (underground bomb, the nuke). */
    public static @Nullable FragmentModel of(WeaponSpec spec) {
        if (spec.charge() <= 0) return null;
        Article a = spec.striker().article();
        Article.Charge w = a.warhead();
        if (w == null || w.caseMass() <= 0 || w.explosive() <= 0) return null;
        return of(w, a.impact() == null ? 0 : a.impact().speed());
    }

    /** Fragments of charge {@code w} at {@code impactSpeed}, m/s. */
    public static FragmentModel of(Article.Charge w, double impactSpeed) {
        // Interpolation from TNT to Composition B by relative explosive strength.
        double k = Math.clamp((w.tnt() / w.explosive() - 1) / (RE_COMP_B - 1), 0, 1);
        double gurney = lerp(k, GURNEY_TNT, GURNEY_COMP_B), mott = lerp(k, MOTT_TNT, MOTT_COMP_B);
        double detonation = lerp(k, DETONATION_TNT, DETONATION_COMP_B), rhoHe = lerp(k, DENSITY_TNT, DENSITY_COMP_B);
        // Cylinder: casing/explosive volume ratio = (R/r)² − 1.
        double vHe = w.explosive() / rhoHe, vCase = w.caseMass() / STEEL;
        double outer = w.diameter() / 2, inner = outer / Math.sqrt(1 + vCase / vHe);
        double t = outer - inner, d = 2 * inner;
        double length = vHe / (Math.PI * inner * inner);
        double beta = w.explosive() / w.caseMass();
        double v0 = gurney * Math.sqrt(beta / (1 + beta / 2));
        double sqrtMu = mott * StrictMath.pow(t, 5.0 / 6) * StrictMath.cbrt(d) * (1 + t / d);
        double mu = sqrtMu * sqrtMu;
        return new FragmentModel(w.caseMass() / (2 * mu), mu, v0, v0 / (2 * detonation), impactSpeed, t, d, length);
    }

    private static double lerp(double k, double a, double b) {
        return a + (b - a) * k;
    }

    /** Mean fragment mass, kg. */
    public double meanMass() {
        return 2 * mu;
    }

    /** Fraction heavier than {@code m} kg. */
    public double shareAbove(double m) {
        return StrictMath.exp(-Math.sqrt(Math.max(0, m) / mu));
    }

    /** Mean projected area of {@code m} fragment, m². */
    public static double area(double m) {
        return AREA_SHAPE * StrictMath.pow(m / STEEL, 2.0 / 3);
    }

    /** Distance reducing {@code m} fragment speed by factor e, m. */
    public static double dragLength(double m) {
        return 2 * m / (AIR * CD * area(m));
    }

    /** Speed of {@code m} fragment starting at {@code v} after {@code s} m, m/s. */
    public static double speedAt(double v, double m, double s) {
        return v * StrictMath.exp(-s / dragLength(m));
    }

    /** Time to travel {@code s} m, s: v = V·e^(−x/L) ⇒ t = L/V·(e^(s/L) − 1). */
    public static double timeTo(double v, double m, double s) {
        double l = dragLength(m);
        return l / v * StrictMath.expm1(s / l);
    }

    /** Distance until fragment energy falls to {@code joules}, m (0 already below). */
    public static double rangeTo(double v, double m, double joules) {
        double ve = Math.sqrt(2 * joules / m);
        return v <= ve ? 0 : dragLength(m) * StrictMath.log(v / ve);
    }

    /** Fragment energy, J. */
    public static double energy(double m, double v) {
        return 0.5 * m * v * v;
    }

    /** Whether fragment chips masonry ({@link #CHIP_J_PER_M2}). */
    public static boolean chips(double m, double v) {
        return energy(m, v) / area(m) >= CHIP_J_PER_M2;
    }

    /** Severe injury (or death) probability at {@code joules} impact energy. */
    public static double injury(double joules) {
        return Fragility.probability(joules, HAZARD_J, HAZARD_BETA);
    }

    /** Death probability at {@code joules} impact energy. */
    public static double death(double joules) {
        return VITAL_SHARE * Fragility.probability(joules, DEEP_J, DEEP_BETA);
    }

    /** Ray-traced fragment count. */
    public int traced() {
        return (int) Math.min(MAX_TRACED, Math.ceil(count));
    }

    /** Real fragments represented by one ray (≥ 1). */
    public double weight() {
        return count / traced();
    }

    /**
     * Heaviest fragment reach at {@link #STOP_J}, m (one per blast on average: Mott fraction 1/N₀); no fragments
     * beyond.
     */
    public double reach() {
        double lnN = StrictMath.log(Math.max(2, count));
        double m = mu * lnN * lnN;
        return Math.min(MAX_RANGE, rangeTo(speed + impactSpeed, m, STOP_J));
    }

    /**
     * DDESB hazard density: one fragment ≥{@link #HAZARD_J} per 600 square feet (55.7 m²), determining hazard
     * distance (DoD 6055.09-M).
     */
    public static final double HAZARD_DENSITY = 1 / 55.7;

    /**
     * Fragments ≥{@code joules} per m² across ray at belt center {@code r} m away (ignoring warhead velocity):
     * N·P(m ≥ m*)·g(0)/(2π·r²), g(0) angular belt density at center.
     */
    public double peakDensity(double r, double joules) {
        double peak = 1 / (BELT_SIGMA * Math.sqrt(2 * Math.PI)) / (2 * Math.PI);
        return count * shareAbove(lightest(r, joules)) * peak / (r * r);
    }

    /** Lightest fragment retaining ≥{@code joules} at {@code r} m (energy increases with mass), kg. */
    double lightest(double r, double joules) {
        double lo = 1e-9, hi = 1e4 * mu;
        if (energy(hi, speedAt(speed, hi, r)) < joules) return hi;
        for (int i = 0; i < 80; i++) {
            double m = Math.sqrt(lo * hi);
            if (energy(m, speedAt(speed, m, r)) >= joules) hi = m;
            else lo = m;
        }
        return hi;
    }

    /** DDESB fragment hazard distance (density {@link #HAZARD_DENSITY} at belt center), m. */
    public double hazardDistance() {
        double lo = 1, hi = MAX_RANGE;
        if (peakDensity(hi, HAZARD_J) >= HAZARD_DENSITY) return hi;
        for (int i = 0; i < 60; i++) {
            double r = (lo + hi) / 2;
            if (peakDensity(r, HAZARD_J) >= HAZARD_DENSITY) lo = r;
            else hi = r;
        }
        return lo;
    }

    /**
     * Fragment {@code i} of blast {@code seed} with warhead {@code axis} (unit vector, nose along heading): unit
     * direction, mass, world speed. Identical server/client.
     */
    public Fragment fragment(long seed, int i, Vec3 axis) {
        SplittableRandom r = new SplittableRandom(seed ^ (0x9E3779B97F4A7C15L * (i + 1)));
        double u = 1 - r.nextDouble();
        double ln = -StrictMath.log(u);
        double m = mu * ln * ln;
        // Wall normal tilted tailward by Taylor angle, with belt spread.
        double polar = Math.PI / 2 + taylor + BELT_SIGMA * gaussian(r);
        double az = r.nextDouble() * 2 * Math.PI;
        Vec3 a = axis.lengthSqr() < 1e-12 ? new Vec3(0, -1, 0) : axis.normalize();
        Vec3 p = Math.abs(a.y) < 0.9 ? a.cross(new Vec3(0, 1, 0)).normalize() : a.cross(new Vec3(1, 0, 0)).normalize();
        Vec3 q = a.cross(p);
        Vec3 side = p.scale(StrictMath.cos(az)).add(q.scale(StrictMath.sin(az)));
        Vec3 v = a.scale(speed * StrictMath.cos(polar) + impactSpeed).add(side.scale(speed * StrictMath.sin(polar)));
        double s = v.length();
        return new Fragment(v.scale(1 / s), m, s);
    }

    private static double gaussian(SplittableRandom r) {
        double u1 = 1 - r.nextDouble(), u2 = r.nextDouble();
        return Math.sqrt(-2 * StrictMath.log(u1)) * StrictMath.cos(2 * Math.PI * u2);
    }

    /**
     * Fragment.
     *
     * @param dir unit flight direction
     * @param mass mass, kg
     * @param speed world speed, m/s
     */
    public record Fragment(Vec3 dir, double mass, double speed) {
        /** Distance until {@link #STOP_J}, m (capped at {@link #MAX_RANGE}). */
        public double range() {
            return Math.min(MAX_RANGE, rangeTo(speed, mass, STOP_J));
        }

        public double speedAt(double s) {
            return FragmentModel.speedAt(speed, mass, s);
        }

        public double energyAt(double s) {
            return energy(mass, speedAt(s));
        }
    }
}
