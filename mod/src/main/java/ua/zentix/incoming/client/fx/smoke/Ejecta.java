package ua.zentix.incoming.client.fx.smoke;

import net.minecraft.world.phys.Vec3;

/**
 * Ballistic ejecta from a loose-soil crater: jet curtain rising from crater and falling back, without Minecraft.
 * Metres, seconds, kilograms. Shared law with nearby clod particles ({@code BlastEffects.ejecta}): each element's
 * exit speed and position, angle, flight time; one mass shared by both.
 * <ul>
 * <li><b>Speed</b>: Housen, Schmidt, Holsapple, "Crater ejecta scaling laws", J. Geophys. Res. 88(B3), 1983: element
 * ejected at distance x from centre has v = C₂·√(g·R)·(x/R)^(−1/μ); dry porous soil μ ≈ {@value #MU} (same target class,
 * Housen, Holsapple, Icarus 211, 2011, physics.md [S07]); (est.) C₂ = {@value #C2} estimated from their sand plots;
 * (est.) no ejecta within {@value #FROM} R from centre (fast tail sensitive to cutoff, mass a few percent, made almost
 * transparent by the law itself). Mass uniform by hemisphere volume at radius x: share u = (x/R)³.</li>
 * <li><b>Angle</b>: {@value #ANGLE_DEG}° ± {@value #SPREAD_DEG}° above horizontal, uniform (est.) (code estimate: sources lack
 * contact-charge angular distribution, physics.md), uniform azimuth; instantaneous ejection at explosion (excavation
 * lasts milliseconds to tens of milliseconds versus seconds of flight).</li>
 * <li><b>Flight</b>: g = 9.81; curtain grains (diameter d, {@link BlastTarget.Matter#grain}) with quadratic air drag, terminal
 * speed v_t = √(4ρ_g·d·g/(3C_d·ρ_a)), C_d {@value #GRAIN_CD} (est.: sphere at Re 10²–10³): a mm grain falls at 7.5 m/s. A grain
 * flies the range of a ballistic element at {@link #curtain} speed v_t·√(ln(1 + (v/v_t)²)) (⚠ approximation, exact in both
 * limits: v ≪ v_t no drag, v ≫ v_t the logarithmic drag range): a Grad crater's 70 m/s centre grains slow within metres;
 * without drag they fly a straight thin chord for 11 s and half a kilometre, a rigid rod over the frame. Clods (nearby
 * particles, {@link #pieces}) keep the exit speed: tens of centimetres, little drag. An element returning to ejection
 * height is on ground.</li>
 * <li><b>Light</b>: curtain visible through grains ({@link BlastTarget.Matter#extinction}); tens-of-centimetre clods
 * attenuate light hundreds of times less per kilogram; their share ({@link BlastTarget.Matter#clods}) is nearby
 * particles, rather than curtain.</li>
 * </ul>
 * Frame curtain has {@value #JETS} jets around circle (each with own azimuth and angle within
 * ±{@value #SPREAD_DEG}°), each with {@value #BANDS} speed segments: matter ejected at one instant at different speeds
 * lies on a curve, segments are its chords (⚠ presentation: real curtain is continuous in azimuth, jets show its
 * unevenness as in footage of ground bursts). Jet cross-section follows angular spread ±{@value #SPREAD_DEG}° over
 * travelled path, no thinner than a quarter crater radius.
 */
public final class Ejecta {
    /** Housen et al. 1983 law for dry soil: μ and C₂ (est.). */
    public static final double MU = 0.41, C2 = 0.55;
    /** No ejecta inside this fraction of crater radius from centre (est.). */
    public static final double FROM = 0.2;
    /** Ejection angle above horizontal and spread to either side, degrees (est.). */
    public static final double ANGLE_DEG = 45, SPREAD_DEG = 10;
    static final double G = 9.80665;
    /** Curtain jets around circle and segments per jet. */
    public static final int JETS = 8, BANDS = 3;

    /** Grain drag coefficient (est.: sphere at Re 10²–10³) and air density, kg/m³. */
    static final double GRAIN_CD = 0.5, AIR = 1.225;
    /** Crater radius, m; ballistic ejecta, kg (excluding airborne dust); curtain grains within it, kg. */
    public final double radius, mass, grains;
    /** Curtain grain terminal speed, m/s (infinite: no drag). */
    public final double terminal;
    /** Speed at crater rim and maximum speed, m/s. */
    public final double vRim, vMax;
    /** Volume share at cutoff: (FROM)³. */
    final double uMin;
    /** When nine tenths and all ejecta are on ground, s. */
    public final double settled, life;

    /**
     * @param radius crater radius, m
     * @param mass   ballistic ejecta, kg: crater mass excluding airborne dust
     * @param clods  clod ejecta share (not curtain)
     */
    public Ejecta(double radius, double mass, double clods) {
        this(radius, mass, clods, Double.POSITIVE_INFINITY);
    }

    /** The same with curtain grains of terminal speed {@code terminal} m/s ({@link #terminal(double, double)}). */
    public Ejecta(double radius, double mass, double clods, double terminal) {
        this.terminal = terminal > 0 ? terminal : Double.POSITIVE_INFINITY;
        this.radius = Math.max(0, radius);
        this.mass = Math.max(0, mass);
        this.grains = this.mass * (1 - Math.clamp(clods, 0, 1));
        vRim = C2 * Math.sqrt(G * this.radius);
        uMin = FROM * FROM * FROM;
        vMax = curtain(speed(uMin));
        settled = flight(curtain(speed(uMin + 0.1 * (1 - uMin))), Math.toRadians(ANGLE_DEG));
        life = flight(vMax, Math.toRadians(ANGLE_DEG + SPREAD_DEG));
    }

    /** All ballistic jet endpoints and their billboard margins, without constructing any jets. */
    public void bounds(double t, SmokeBounds out) {
        if (t <= 0 || t >= life || grains <= 0) return;
        double sigma = Math.max(Math.toRadians(SPREAD_DEG) / Math.sqrt(3) * vMax * t, 0.25 * radius);
        double r = radius + vMax * t + SmokeBounds.PUFF * sigma;
        out.box(-r, -r, -r, r, r, r);
    }

    /** Piece receiver: ejection position (m, world) and velocity, m/s. */
    public interface Pieces {
        void piece(Vec3 at, Vec3 velocity);
    }

    /**
     * {@code n} pieces of this ejecta from face at {@code origin}, outward {@code up} (up for crater, opposite hole axis
     * for breached slab front): same law as curtain, uniform volume share u, ejection position R·u^⅓ on face, speed
     * {@link #speed}, face-relative angle {@value #ANGLE_DEG}° ± {@value #SPREAD_DEG}°, uniform azimuth; piece starts
     * half a metre above face (outside its block). Particle handles gravity (world down).
     */
    public void pieces(Vec3 origin, Vec3 up, int n, java.util.function.DoubleSupplier random, Pieces out) {
        Vec3 u0 = up.normalize();
        Vec3[] across = across(u0);
        for (int i = 0; i < n; i++) {
            double u = uMin + (1 - uMin) * random.getAsDouble();
            double v = speed(u);
            double el = Math.toRadians(ANGLE_DEG + (random.getAsDouble() * 2 - 1) * SPREAD_DEG), az = random.getAsDouble() * Math.PI * 2;
            Vec3 side = across[0].scale(Math.cos(az)).add(across[1].scale(Math.sin(az)));
            out.piece(origin.add(side.scale(from(u))).add(u0.scale(0.5)), side.scale(Math.cos(el) * v).add(u0.scale(Math.sin(el) * v)));
        }
    }

    /** Two unit vectors perpendicular to unit {@code n} and each other. */
    public static Vec3[] across(Vec3 n) {
        Vec3 ref = Math.abs(n.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 a = n.cross(ref).normalize();
        return new Vec3[]{a, n.cross(a).normalize()};
    }

    /** Speed of volume-share u element (from crater centre), m/s: v = v_rim·u^(−1/(3μ)). */
    public double speed(double u) {
        return vRim * Math.pow(Math.max(u, uMin), -1 / (3 * MU));
    }

    /** Terminal speed of a grain of diameter {@code d} m and density {@code density} kg/m³ in air, m/s. */
    public static double terminal(double d, double density) {
        return d > 0 && density > 0 ? Math.sqrt(4 * density * d * G / (3 * GRAIN_CD * AIR)) : Double.POSITIVE_INFINITY;
    }

    /** Curtain grain's ballistic-equivalent speed for exit speed v, m/s: v_t·√(ln(1 + (v/v_t)²)) (see the class). */
    public double curtain(double v) {
        if (Double.isInfinite(terminal)) return v;
        double q = v / terminal;
        return terminal * Math.sqrt(Math.log1p(q * q));
    }

    /** Exit speed whose curtain speed is {@code c}: the inverse of {@link #curtain}. */
    double exit(double c) {
        if (Double.isInfinite(terminal)) return c;
        double q = c / terminal;
        return q * q > 700 ? Double.POSITIVE_INFINITY : terminal * Math.sqrt(Math.expm1(q * q));
    }

    /** Ejection position, m from centre: x = R·u^⅓. */
    public double from(double u) {
        return Math.cbrt(Math.max(u, 0)) * radius;
    }

    /** Flight time for speed v at horizontal elevation el (rad), until return to ejection height, s. */
    static double flight(double v, double el) {
        return 2 * v * Math.sin(el) / G;
    }

    /** Volume share whose slower elements at angle el are on ground by age t (above 1 means all airborne). */
    double landed(double t, double el) {
        if (t <= 0 || vRim <= 0) return Double.POSITIVE_INFINITY;
        double v = exit(G * t / (2 * Math.sin(el)));
        return Math.pow(v / vRim, -3 * MU);
    }

    /** Ejecta share at angle el still airborne at age t (mass uniform by u from {@link #uMin} to 1). */
    public double aloft(double t, double el) {
        double u = Math.min(1, landed(t, el));
        return u <= uMin ? 0 : (u - uMin) / (1 - uMin);
    }

    /** Jet receiver: segment centre − a to centre + a (explosion-relative, m; y up), circular cross-section σ, grains kg. */
    public interface Jets {
        void jet(double x, double y, double z, double ax, double ay, double az, double sigma, double kg);
    }

    /** Jet k elevation (rad) from explosion seed: uniform within ±{@link #SPREAD_DEG}° about {@link #ANGLE_DEG}°. */
    static double elevation(long seed, int k) {
        return Math.toRadians(ANGLE_DEG + SPREAD_DEG * (2 * unit(mix(seed + 0x2545F4914F6CDD1DL * (k + 1))) - 1));
    }

    /** Jet k azimuth (rad): uniform around circle with seed rotation. */
    static double azimuth(long seed, int k) {
        return 2 * Math.PI * (k + unit(mix(seed ^ 0x9E3779B97F4A7C15L))) / JETS;
    }

    /** Segment boundaries in volume share: logarithmically equal from {@link #uMin} to 1 (speed is a power of share). */
    double bound(int b) {
        return Math.pow(uMin, 1 - (double) b / BANDS);
    }

    /**
     * Curtain jets at age t: each has speed segments of elements still airborne; segment grains are its volume share
     * ({@link #grains} / {@link #JETS} per jet). Grounded matter is outside curtain. Airborne grain sum is {@link #grainsAloft}.
     */
    public void jets(double t, long seed, Jets out) {
        if (t <= 0 || grains <= 0 || radius <= 0) return;
        double drop = 0.5 * G * t * t, spread = Math.toRadians(SPREAD_DEG) / Math.sqrt(3);
        for (int k = 0; k < JETS; k++) {
            double el = elevation(seed, k), az = azimuth(seed, k), up = Math.sin(el), side = Math.cos(el);
            double cx = Math.cos(az), cz = Math.sin(az), last = Math.min(1, landed(t, el));
            for (int b = 0; b < BANDS; b++) {
                double ua = bound(b), ub = Math.min(bound(b + 1), last);
                if (ub <= ua) continue;
                // Segment ends: volume-share ua (fast) and ub (slow) elements: ejection position + straight path − fall.
                double va = curtain(speed(ua)), vb = curtain(speed(ub));
                double ra = from(ua) + va * t * side, ya = va * t * up - drop, rb = from(ub) + vb * t * side, yb = vb * t * up - drop;
                double mx = 0.5 * (ra + rb), my = Math.max(0, 0.5 * (ya + yb)), hx = 0.5 * (ra - rb), hy = 0.5 * (ya - yb);
                double sigma = Math.max(spread * curtain(speed(Math.sqrt(ua * ub))) * t, 0.25 * radius);
                double kg = grains / JETS * (ub - ua) / (1 - uMin);
                out.jet(mx * cx, my, mx * cz, hx * cx, hy, hx * cz, sigma, kg);
            }
        }
    }

    /** Airborne grains at age t, kg: same as {@link #jets} emits (sum over jets). */
    public double grainsAloft(double t, long seed) {
        if (t <= 0) return grains;
        double sum = 0;
        for (int k = 0; k < JETS; k++) sum += grains / JETS * aloft(t, elevation(seed, k));
        return sum;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
