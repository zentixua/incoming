package ua.zentix.incoming.nuclear.model;

/**
 * Fallout trace of one burst: a function of (x, z, time), with no per-block storage.
 * x, z are meters from the epicenter along world axes; wind blows toward angle {@code windDirRad}
 * (vector (cos, sin) in the x–z plane).
 *
 * <p>Dose rate at H+1 (R/h) is the sum of two Gaussian patches:
 * <ul>
 *   <li>epicenter: {@code 0.5·B·exp(−r²/(2·(0.6·R_ball)²))};</li>
 *   <li>downwind trace: axial {@code B·(1 + x/(x0·S))^−n}, crosswind Gaussian with σ = 0.1·x + R_ball,
 *       upwind Gaussian with σ = R_ball.</li>
 * </ul>
 * R_ball is the maximum surface-burst fireball radius; S = (Y/15)^0.45·(wind/wind₀) stretches the trace,
 * the wind₀/wind factor dilutes it. Fitting B = 10⁵ R/h, x0 = 500 m, n = 2.1 gives axial contours of 1000 / 100 / 10 R/h
 * at 4 / 13 / 40 km for a 15 kt surface burst in 24 km/h wind — the order of NUKEMAP (Miller model) contours for this case.
 * Everything is multiplied by the surface-burst share {@link #surfaceFraction()}.
 *
 * <p>Time is game hours (1000 ticks; day — 20 minutes): fallout arrives at a point after downwind distance / wind speed,
 * but no earlier than the largest particles fall from the cloud ({@link #firstArrivalHours}); decays by Way–Wigner
 * t^−1.2 (7–10 rule) — see {@link #doseRate}.
 */
public record FalloutModel(double yieldKt, double hobM, double windDirRad, double windSpeedMps) {
    /** Axial fallout dose rate near the epicenter at H+1, R/h. */
    public static final double PEAK_H1 = 1e5;
    /** Axial decay length, m (at 15 kt and reference wind). */
    public static final double TRAIL_SCALE_M = 500;
    /** Axial decay exponent. */
    public static final double TRAIL_EXP = 2.1;
    /** Reference wind for the fit: 24 km/h. */
    public static final double REFERENCE_WIND = 24 / 3.6;
    /** Epicenter patch: fraction of PEAK_H1 and radius in fractions of R_ball. */
    private static final double GZ_PEAK = 0.5;
    private static final double GZ_RADIUS = 0.6;
    /** No decay before this time (hours): Way–Wigner is valid from half an hour, while t^−1.2 is infinite at zero. */
    public static final double MIN_DECAY_HOURS = 0.5;
    /** Way–Wigner decay law. */
    public static final double DECAY_EXP = 1.2;
    private static final double MIN_WIND = 0.5;
    /** Earliest fallout: {@code FIRST_ARRIVAL_MIN · Y^FIRST_ARRIVAL_EXP} minutes (Miller model). */
    private static final double FIRST_ARRIVAL_MIN = 4;
    private static final double FIRST_ARRIVAL_EXP = 0.2;

    /**
     * Surface-burst share 0..1: 0 if the ball does not touch ground (HOB ≥ maximum airburst ball radius),
     * growing toward contact as (1 − HOB/R)².
     */
    public double surfaceFraction() {
        double u = Solve.clamp(1 - hobM / FireballModel.maxRadius(yieldKt, false), 0, 1);
        return u * u;
    }

    /** Dose rate at H+1 at (x, z), R/h — without arrival time. */
    public double doseRateAtH1(double x, double z) {
        double frac = surfaceFraction();
        if (frac <= 0) return 0;
        double down = x * Math.cos(windDirRad) + z * Math.sin(windDirRad);
        double cross = -x * Math.sin(windDirRad) + z * Math.cos(windDirRad);
        double rf = FireballModel.maxRadius(yieldKt, true);
        double wind = wind();
        double stretch = Math.pow(yieldKt / 15, 0.45) * wind / REFERENCE_WIND;
        double amp = PEAK_H1 * frac * REFERENCE_WIND / wind;

        double along = down >= 0
                ? Math.pow(1 + down / (TRAIL_SCALE_M * stretch), -TRAIL_EXP)
                : Math.exp(-down * down / (2 * rf * rf));
        double sigma = 0.1 * Math.max(down, 0) + rf;
        double trail = along * Math.exp(-cross * cross / (2 * sigma * sigma));

        double gzSigma = GZ_RADIUS * rf;
        double gz = GZ_PEAK * Math.exp(-(x * x + z * z) / (2 * gzSigma * gzSigma));
        return amp * (trail + gz);
    }

    /**
     * Earliest fallout, hours: 4·Y^0.2 min (15 kt — 7 min, 1 Mt — 16 min), the largest particles' fall time from cloud and stem
     * (Miller model, 1963; Glasstone 9.50, 9.64). No earlier fallout beneath the cloud or upwind.
     */
    public double firstArrivalHours() {
        return FIRST_ARRIVAL_MIN / 60 * Math.pow(yieldKt, FIRST_ARRIVAL_EXP);
    }

    /**
     * Hours after burst for fallout to deposit at (x, z): downwind distance / speed, but no earlier than
     * {@link #firstArrivalHours}.
     */
    public double arrivalHours(double x, double z) {
        double down = x * Math.cos(windDirRad) + z * Math.sin(windDirRad);
        return Math.max(firstArrivalHours(), Math.max(down, 0) / wind() / 3600);
    }

    /**
     * Dose rate after {@code hoursSinceDetonation} hours, R/h: Ḋ = Ḋ₁·t^−1.2, t at least {@link #MIN_DECAY_HOURS}
     * (law valid from half an hour); 0 before fallout arrival.
     */
    public double doseRate(double x, double z, double hoursSinceDetonation) {
        if (hoursSinceDetonation < arrivalHours(x, z)) return 0;
        double t = Math.max(hoursSinceDetonation, MIN_DECAY_HOURS);
        return doseRateAtH1(x, z) * Math.pow(t, -DECAY_EXP);
    }

    private double wind() {
        return Math.max(windSpeedMps, MIN_WIND);
    }
}
