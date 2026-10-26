package ua.zentix.incoming.nuclear.model;

/**
 * Blast injuries (Glasstone & Dolan 1977, ch. 12): lung pressure, body throw — impact with solid objects or tumbling over
 * ground, glass fragments, burial by a collapsed house. Probabilities are tabulated lognormal curves ({@link Fragility});
 * their game health cost is {@link #lostShare}.
 *
 * <p>Two outcomes for each: death, and severe injury (or death). Each blast-exposed subject has its own random
 * {@code fate} 0..1: dies if below death probability; otherwise loses a health share equal to severe injury probability
 * divided by {@code fate}, capped at {@link #CASUALTY_SHARE}: closer to their threshold means worse injury, and a severely
 * injured subject is left barely alive.
 */
public final class BlastInjury {
    /** Lung-pressure lethality, psi effective pressure: 1 % at 40, 50 % at 62, ~100 % at 92 (table 12.38). */
    public static final double LUNG_DEATH_PSI = 62, LUNG_DEATH_BETA = 0.19;
    /** Severe lung injury, psi: threshold 12, severe at 25 (table 12.38). */
    public static final double LUNG_INJURY_PSI = 25, LUNG_INJURY_BETA = 0.32;
    /** Death from body impact with a solid object, m/s: threshold 21 ft/s (6.4), 50 % — 54 (16.5), ~100 % — 138 (42) (table 12.49). */
    public static final double IMPACT_DEATH = 16.5, IMPACT_DEATH_BETA = 0.40;
    /** Skull fracture on impact, m/s: threshold 13 ft/s (4.0), 50 % — 18 (5.5), ~100 % — 23 (7.0) (table 12.49). */
    public static final double IMPACT_INJURY = 5.5, IMPACT_INJURY_BETA = 0.12;
    /** Injury from tumbling over open ground, m/s initial speed: 1 % at 30 ft/s (9.1), 50 % — 75 (22.9) (ch. 12, estimate). */
    public static final double TUMBLE_INJURY = 22.9, TUMBLE_INJURY_BETA = 0.40;
    /**
     * Death from tumbling, m/s: no data (ch. 12); estimate — the same factor above solid impact as for injuries
     * (22.9 versus 5.5): 69 m/s.
     */
    public static final double TUMBLE_DEATH = IMPACT_DEATH * TUMBLE_INJURY / IMPACT_INJURY;
    /**
     * Window glass fragment speed, m/s per √psi: 1.9 psi — 108 ft/s, 3.8 — 168 and 140, 5.0 — 170 (table 12.43,
     * 2–4 m from the window); fit 80·√psi ft/s.
     */
    public static final double GLASS_SPEED = 24.4;
    /** Severe injury from a glass fragment of about a gram (penetrates abdominal wall), m/s: 1 % — 140 ft/s, 50 % — 245 (table 12.46). */
    public static final double GLASS_INJURY = 74.7, GLASS_INJURY_BETA = 0.24;
    /**
     * Burial by a collapsed masonry house: 20 % die immediately, another 30 % have life-threatening injuries (Coburn & Spence
     * 2002, M4 for masonry; earthquake-based estimate — no such nuclear strike data). Survivors remain buried:
     * suffocating until they escape.
     */
    public static final double CRUSH_DEATH = 0.2, CRUSH_INJURY = 0.5;
    /** A severely injured subject loses this much health. */
    public static final double CASUALTY_SHARE = 0.95;

    /**
     * Body drag: human Cd·A/m — 0.6 m² per 70 kg. Check — G&D §12.44: a standing 165 lb dummy, back to burst, at 5.3 psi —
     * maximum speed 21 ft/s (6.4 m/s), total 22 ft (6.7 m) with tumbling. Yield is unspecified there; the ground-level model
     * at 20–40 kt gives 5.6–6.8 m/s and 6.2–9.1 m.
     */
    public static final double HUMAN_DRAG_PER_KG = 0.6 / 70;
    /** Human body width, m: a wider body has more mass per area and is thrown less. */
    public static final double HUMAN_WIDTH = 0.6;
    /** Ground-tumbling deceleration after acceleration, fraction of g. */
    private static final double TUMBLE_FRICTION = 0.5;
    private static final double G = 9.81, RHO0 = 1.225;
    private static final int STEPS_PER_PHASE = 400;

    private BlastInjury() {}

    /**
     * Effective lung pressure, kPa: reflected with a wall behind the body; stagnation pressure (pressure plus dynamic
     * pressure) when facing the burst; incident pressure sideways or back-on (table 12.38).
     *
     * @param qKpa   dynamic pressure along the ground, kPa
     * @param facing how much the body faces the burst: 1 — front, 0 — side and back
     */
    public static double effectiveKpa(double kpa, double qKpa, boolean backedByWall, double facing) {
        if (backedByWall) return reflectedKpa(kpa);
        return kpa + qKpa * Math.max(0, Math.min(1, facing));
    }

    /** Normally reflected pressure (air, γ = 1.4), kPa. */
    public static double reflectedKpa(double kpa) {
        double p0 = BlastModel.P0;
        return 2 * kpa * (7 * p0 + 4 * kpa) / (7 * p0 + kpa);
    }

    public static double lungDeath(double effectiveKpa) {
        return Fragility.probability(BlastModel.psi(effectiveKpa), LUNG_DEATH_PSI, LUNG_DEATH_BETA);
    }

    public static double lungInjury(double effectiveKpa) {
        return Fragility.probability(BlastModel.psi(effectiveKpa), LUNG_INJURY_PSI, LUNG_INJURY_BETA);
    }

    public static double impactDeath(double speed) {
        return Fragility.probability(speed, IMPACT_DEATH, IMPACT_DEATH_BETA);
    }

    public static double impactInjury(double speed) {
        return Fragility.probability(speed, IMPACT_INJURY, IMPACT_INJURY_BETA);
    }

    public static double tumbleDeath(double speed) {
        return Fragility.probability(speed, TUMBLE_DEATH, TUMBLE_INJURY_BETA);
    }

    public static double tumbleInjury(double speed) {
        return Fragility.probability(speed, TUMBLE_INJURY, TUMBLE_INJURY_BETA);
    }

    /** Fragment speed of a window broken by pressure {@code kpa}, m/s. */
    public static double glassSpeed(double kpa) {
        return GLASS_SPEED * Math.sqrt(Math.max(0, BlastModel.psi(kpa)));
    }

    public static double glassInjury(double kpa) {
        return Fragility.probability(glassSpeed(kpa), GLASS_INJURY, GLASS_INJURY_BETA);
    }

    /** Probability of at least one independent outcome. */
    public static double any(double... p) {
        double none = 1;
        for (double x : p) none *= 1 - x;
        return 1 - none;
    }

    /**
     * Health share lost by a blast survivor: {@code injury / fate}, capped at {@link #CASUALTY_SHARE}
     * (1 — dead: {@code fate < death}).
     */
    public static double lostShare(double death, double injury, double fate) {
        if (fate < death) return 1;
        return Math.min(CASUALTY_SHARE, Math.max(death, injury) / Math.max(fate, 1e-9));
    }

    /**
     * Body throw by dynamic pressure behind the front: dv/dt = ρ(u − v)²·Cd·A/(2m), wind u and post-front air density from
     * Rankine–Hugoniot, decaying as (1 − t/T)·e^(−t/T) over positive phase T. The body accelerates while wind is faster,
     * then decelerates by ground tumbling ({@link #TUMBLE_FRICTION}·g).
     *
     * @param kpa       front overpressure (post-front air density)
     * @param u0        post-front wind speed, m/s
     * @param phase     positive phase, s
     * @param dragPerKg body Cd·A/m, m²/kg ({@link #HUMAN_DRAG_PER_KG} for a human); 0 — no throw
     */
    public static Throw thrown(double kpa, double u0, double phase, double dragPerKg) {
        if (u0 <= 0 || phase <= 0 || dragPerKg <= 0) return Throw.NONE;
        double rhoFront = RHO0 * (7 * BlastModel.P0 + 6 * kpa) / (7 * BlastModel.P0 + kpa);
        double dt = phase / STEPS_PER_PHASE;
        double[] path = new double[STEPS_PER_PHASE + 1], speed = new double[STEPS_PER_PHASE + 1];
        double v = 0, s = 0;
        int n = 1;
        for (int i = 0; i < STEPS_PER_PHASE; i++) {
            double t = (i + 0.5) * dt, f = (1 - t / phase) * Math.exp(-t / phase);
            double rel = u0 * f - v;
            if (rel <= 0) break;
            double a = 0.5 * (RHO0 + (rhoFront - RHO0) * f) * dragPerKg * rel * rel;
            s += (v + 0.5 * a * dt) * dt;
            v += a * dt;
            path[n] = s;
            speed[n] = v;
            n++;
        }
        return new Throw(v, s + v * v / (2 * TUMBLE_FRICTION * G), java.util.Arrays.copyOf(path, n), java.util.Arrays.copyOf(speed, n));
    }

    /**
     * Throw: maximum speed, total stopping distance (m), and acceleration — speed by distance traveled.
     */
    public record Throw(double peak, double stop, double[] path, double[] speed) {
        public static final Throw NONE = new Throw(0, 0, new double[]{0}, new double[]{0});

        /** Speed of a body that traveled {@code metres}, m/s (0 — beyond its throw distance). */
        public double speedAfter(double metres) {
            if (metres <= 0) return 0;
            double accel = path[path.length - 1];
            if (metres >= accel) {
                double v2 = peak * peak - 2 * TUMBLE_FRICTION * G * (metres - accel);
                return v2 > 0 ? Math.sqrt(v2) : 0;
            }
            int i = java.util.Arrays.binarySearch(path, metres);
            if (i >= 0) return speed[i];
            i = -i - 1;
            double k = (metres - path[i - 1]) / (path[i] - path[i - 1]);
            return speed[i - 1] + k * (speed[i] - speed[i - 1]);
        }
    }
}
