package ua.zentix.incoming.nuclear.model;

/**
 * What a blast tears from the ground at arrival and how hard it throws it — without Minecraft (meters, seconds).
 * This makes a conventional blast front visible on grass: a ring of torn grass, turf and leaves moves at front speed
 * (photos show dust, grass and leaves torn loose at arrival).
 * <ul>
 * <li><b>Entrainment</b> — {@link DustModel} dust entrainment law: boundary-layer friction velocity u* immediately behind
 * the front (Schlichting plate, Gaj &amp; Small 1991, eq. 7, {@link DustModel#friction} at the nearest 6 cm where the plate
 * law still applies) exceeds the surface threshold: turf and everything on it — {@link DustModel.Surface#COVERED}
 * (3 m/s, Gaj &amp; Small §2.2). Wind is Rankine–Hugoniot behind the front × √ground-aligned dynamic pressure share.
 * ⚠ The threshold is for soil under grass, not blades and leaves themselves: no separate data for when a blast tears
 * grass loose; the result is about 1 psi.</li>
 * <li><b>Throw</b> — a light plate (grass clump, leaf) accelerates by quadratic drag, as do fragments
 * ({@code Fragments}): dv/dt = (g/v_t²)·|u − v|·(u − v), post-front wind follows Friedlander u = U(1 − x)e^(−x).
 * A conventional blast's phase lasts milliseconds, less than a game tick, so throw is integrated here with small steps,
 * and the piece launches at that speed into still air. No negative phase (reverse wind) afterward: Friedlander
 * overestimates it for a conventional charge (real rarefaction is weaker and longer); for a clump thrown nearly at wind
 * speed, Friedlander's reverse wind would turn it toward the crater (⚠ approximation).</li>
 * </ul>
 */
public final class BlastLitter {
    /**
     * Settling speed of coarse particles from torn cover, m/s: the 120 µm quartz dust bin from DustModel (Clift–Gauvin).
     * Size is an estimate for visible entrainment detail; fine persistent dust remains in the physical GroundDust layer.
     */
    public static final double COVER_DUST_SETTLE = DustModel.SETTLE[3];
    /** Closer to the front, the plate law is invalid, m ({@link DustModel#plate}). */
    static final double NEAREST_M = 0.06;
    /**
     * Throw integration steps per positive phase: exact step at midpoint wind, second-order error — under 0.2 % with
     * 32 steps (previous 400 semi-implicit steps — up to 0.6 %, with 900 pieces per explosion costing 4–8 ms in one tick).
     */
    static final int STEPS = 32;
    static final double G = 9.81;

    private BlastLitter() {}

    /** Ground friction velocity immediately behind a front of pressure {@code kpa}, m/s ({@code along} — ground-aligned dynamic pressure share). */
    public static double friction(double kpa, double along) {
        return DustModel.friction(BlastModel.windSpeed(kpa) * Math.sqrt(Math.max(0, along)), NEAREST_M);
    }

    /** Whether a front of pressure {@code kpa} tears loose what lies and grows on a surface of threshold {@code threshold} m/s. */
    public static boolean tears(double kpa, double along, double threshold) {
        return friction(kpa, along) > threshold;
    }

    /** Whether the front tears loose grass, turf and leaves ({@link DustModel.Surface#COVERED}). */
    public static boolean tearsCover(double kpa, double along) {
        return tears(kpa, along, DustModel.Surface.COVERED.threshold());
    }

    /**
     * Speed at which post-front wind throws a light plate of terminal velocity {@code terminal} m/s: immediate post-front
     * wind {@code windMs}, positive phase {@code tPlus} s. Plate starts at rest, m/s along the wind.
     */
    public static double kick(double windMs, double tPlus, double terminal) {
        if (!(windMs > 0) || !(tPlus > 0) || !(terminal > 0)) return 0;
        double k = G / (terminal * terminal), dt = tPlus / STEPS, v = 0;
        for (int i = 0; i < STEPS; i++) {
            double x = (i + 0.5) / STEPS, u = windMs * (1 - x) * Math.exp(-x), rel = u - v;
            // with step wind u, lag decays exactly: d(u − v)/dt = −k|u − v|(u − v) → rel / (1 + k|rel|·dt)
            v = u - rel / (1 + k * Math.abs(rel) * dt);
        }
        return v;
    }
}
