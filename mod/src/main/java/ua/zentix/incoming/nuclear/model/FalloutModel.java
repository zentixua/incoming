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
 * <p>Time ({@code docs/design/time.md} §8.3 rule 4): fallout arrives at a point on the strike's motion age (real time, the
 * air's clock) after downwind distance / wind speed, but no earlier than the largest particles fall from the cloud
 * ({@link #firstArrivalHours}), also after the cloud is too thin to see. On the ground it decays by Way–Wigner t^−1.2
 * (7–10 rule), t its ground age: the age it landed at plus the ground clock since ({@link #groundRate}). It lands until
 * {@link #lastLandingHours} above the radiation model's floor {@link #FLOOR}.
 */
public record FalloutModel(double yieldKt, double hobM, double windDirRad, double windSpeedMps) {
    /**
     * Where a deposit (plume fallout, black-rain dust) lies on the ground at a point, by time (time.md §8.3 rule 4): it
     * landed at motion age {@code arrival}, seconds, when the ground clock (the strike clock plus jumps someone was awake
     * for) led the motion age by {@code shift} seconds. Its ground age at ground clock G is G − shift, which is
     * {@code arrival} at the landing: the dose rate it lands with is the law's for its age in the air, on any day.
     */
    public record Landing(double arrival, double shift) {
        /** Ground age at ground clock {@code ground}, hours; below {@link #arrival} — not landed yet then. */
        public double groundHours(double ground) {
            return (ground - shift) / 3600;
        }

        /** Dose rate, R/h, at ground clock {@code ground} of a deposit with H+1 rate {@code h1}; 0 before it landed. */
        public double rate(double h1, double ground) {
            return landed(ground) ? groundRate(h1, groundHours(ground)) : 0;
        }

        /** Whether it has landed by ground clock {@code ground} (to a microsecond: the landing moment itself counts). */
        public boolean landed(double ground) {
            return ground - shift >= arrival - 1e-6;
        }

        /** Dose, R, over ground clock {@code g0} to {@code g1} of a deposit with H+1 rate {@code h1}: none before it landed. */
        public double dose(double h1, double g0, double g1) {
            return groundDose(h1, Math.max(g0 - shift, arrival) / 3600, (g1 - shift) / 3600);
        }
    }

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
    /**
     * The radiation model's floor, R/h: a dose rate below it is background (about 100 µSv/h, ten times the highest natural
     * background on inhabited land, est.); it adds no dose, and fallout ends where and when it falls below.
     */
    public static final double FLOOR = 0.01;
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
     * (law valid from half an hour); 0 before fallout arrival. The ground age is the time since the burst only where the
     * ground's clock has run with the air's (a 24-hour day, no jumps); otherwise {@link Landing}.
     */
    public double doseRate(double x, double z, double hoursSinceDetonation) {
        if (hoursSinceDetonation < arrivalHours(x, z)) return 0;
        double t = Math.max(hoursSinceDetonation, MIN_DECAY_HOURS);
        return doseRateAtH1(x, z) * Math.pow(t, -DECAY_EXP);
    }

    /**
     * Hours of dose at the rate measured at {@code h1} that a decaying source gives from {@code h0} to {@code h1} hours after the
     * burst: ∫ (t/h1)^−1.2 dt with the {@link #MIN_DECAY_HOURS} floor. Equal to {@code h1 − h0} for a short step; a long one
     * (a night's sleep) counts the higher rate at its start.
     */
    public static double decayHours(double h0, double h1) {
        if (h1 <= h0) return 0;
        double a = Math.max(h1, MIN_DECAY_HOURS);
        double flat = Math.max(0, Math.min(h1, MIN_DECAY_HOURS) - h0) * Math.pow(MIN_DECAY_HOURS / a, -DECAY_EXP);
        double lo = Math.max(h0, MIN_DECAY_HOURS);
        double tail = h1 <= lo ? 0 : Math.pow(a, DECAY_EXP) * (Math.pow(lo, 1 - DECAY_EXP) - Math.pow(h1, 1 - DECAY_EXP)) / (DECAY_EXP - 1);
        return flat + tail;
    }

    /** Strongest H+1 dose rate of the field, R/h: at ground zero, the trace's start and the epicenter patch. */
    public double peakH1() {
        return PEAK_H1 * surfaceFraction() * REFERENCE_WIND / wind() * (1 + GZ_PEAK);
    }

    /**
     * Motion age, hours, of the trace's last landing above {@link #FLOOR}: along the axis the landing dose rate (H+1 rate
     * decayed to its arrival age) falls with distance; the farthest point still above the floor. 15 kt surface burst in the
     * reference wind: 270 km, 11.24 h. No fallout: 0.
     */
    public double lastLandingHours() {
        if (surfaceFraction() <= 0) return 0;
        double lo = 0, hi = 1000;
        while (landingRate(hi) >= FLOOR) hi *= 2;
        if (landingRate(lo) < FLOOR) return 0;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (landingRate(mid) >= FLOOR) lo = mid;
            else hi = mid;
        }
        return arrivalHours(lo * Math.cos(windDirRad), lo * Math.sin(windDirRad));
    }

    /** Dose rate on the axis {@code down} metres downwind as it lands, R/h. */
    private double landingRate(double down) {
        double x = down * Math.cos(windDirRad), z = down * Math.sin(windDirRad);
        return groundRate(doseRateAtH1(x, z), arrivalHours(x, z));
    }

    /**
     * Upper bound of the H+1 dose rate anywhere at least {@code r} metres from the epicenter, R/h; non-increasing in r.
     * A point at distance r is either at least r/2 downwind (the axial law at r/2 bounds it), less than r/2 downwind and so
     * at least r·√3/2 across with σ under 0.05·r + R_ball, or upwind (a Gaussian of r with σ = R_ball); plus the epicenter
     * patch at r.
     */
    public double boundH1(double r) {
        double frac = surfaceFraction();
        if (frac <= 0) return 0;
        double rf = FireballModel.maxRadius(yieldKt, true), wind = wind();
        double stretch = Math.pow(yieldKt / 15, 0.45) * wind / REFERENCE_WIND;
        double along = Math.pow(1 + r / 2 / (TRAIL_SCALE_M * stretch), -TRAIL_EXP);
        double s = 0.05 * r + rf;
        double across = Math.exp(-0.375 * r * r / (s * s));
        double upwind = Math.exp(-r * r / (8 * rf * rf));
        double gz = GZ_PEAK * Math.exp(-r * r / (2 * (GZ_RADIUS * rf) * (GZ_RADIUS * rf)));
        return PEAK_H1 * frac * REFERENCE_WIND / wind * (Math.max(along, Math.max(across, upwind)) + gz);
    }

    /**
     * Distance, metres, beyond which the dose rate stays under {@code rate} R/h for ground ages of at least
     * {@code groundHours} ({@link #boundH1}); 0 — nowhere above it.
     */
    public double reachMetres(double groundHours, double rate) {
        double decay = Math.pow(Math.max(groundHours, MIN_DECAY_HOURS), -DECAY_EXP);
        if (boundH1(0) * decay < rate) return 0;
        double lo = 0, hi = 1000;
        while (boundH1(hi) * decay >= rate) hi *= 2;
        for (int i = 0; i < 50; i++) {
            double mid = (lo + hi) / 2;
            if (boundH1(mid) * decay >= rate) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    /** Dose rate of fallout on the ground, R/h: H+1 rate {@code h1} at ground age {@code groundHours} (Way–Wigner, half-hour floor). */
    public static double groundRate(double h1, double groundHours) {
        return h1 * Math.pow(Math.max(groundHours, MIN_DECAY_HOURS), -DECAY_EXP);
    }

    /** Dose, R, of fallout with H+1 rate {@code h1} over ground ages {@code g0} to {@code g1} hours: ∫ {@link #groundRate}. */
    public static double groundDose(double h1, double g0, double g1) {
        if (g1 <= g0 || h1 <= 0) return 0;
        double flat = Math.max(0, Math.min(g1, MIN_DECAY_HOURS) - g0) * Math.pow(MIN_DECAY_HOURS, -DECAY_EXP);
        double lo = Math.max(g0, MIN_DECAY_HOURS);
        double tail = g1 <= lo ? 0 : (Math.pow(lo, 1 - DECAY_EXP) - Math.pow(g1, 1 - DECAY_EXP)) / (DECAY_EXP - 1);
        return h1 * (flat + tail);
    }

    private double wind() {
        return Math.max(windSpeedMps, MIN_WIND);
    }
}
