package ua.zentix.incoming.nuclear.model;

/**
 * Black rain from fires: a firestorm lifts moist, sooty air into the detonation cloud, which produces rain — for any
 * burst above a burning city. Hiroshima (airburst; Uda 1953 via RERF): rain began after ~20 minutes; in the heavy-rain
 * zone (19 × 11 km northwest of the city, fire zone about 2 km) it poured for 1–2 hours, with light rain reaching 29 km.
 * <p>
 * The rain is the rain-out of the fires' smoke parcels ({@link SmokeParcels}), where they are, while they are drawn
 * ({@code docs/design/time.md} §8.3, rule 4). A parcel carries rain when it left the fires within the rain window
 * {@link #START_S}–{@link #END_S} of the motion age M (the storm cloud forms 20 minutes after the burst and rains for an hour and a
 * half), and rains out over the first {@link #DRIFT_S} of its travel. At a downwind point the rain starts later by the parcels'
 * travel time; at a 24-hour day (S = M) a point gets the whole window, 90 minutes. On a short day the fire burns out sooner on its
 * strike clock and stops feeding the parcels (a vanilla day: about 10 minutes after the show), so a point gets at most that.
 * The band widens with the parcels ({@link SmokeParcels#SPREAD}). x, z are meters from the epicenter; wind blows toward
 * (cos, sin).
 * <p>
 * Radioactivity: in an airburst, fission products remain in the cloud, and rain washes some onto the ground — hundreds
 * of times weaker than surface-burst fallout (DS86, ch. 6: Hiroshima, Koi–Takasu — 1–3 R total; Nagasaki, Nishiyama —
 * 20–40 R). Ground dose rate under rain at H+1 reaches {@link #RAIN_H1} R/h per 15 kt (estimate between the two cities),
 * ∝ Y and the airburst share; decay follows Way–Wigner, as for fallout ({@link FalloutModel}). The deposit does not depend on how
 * much smoke passed (a known limit: on a short day the rain is shorter, its deposit the same). For a surface burst, deposited
 * material is its fallout.
 *
 * @param fireRadiusM ground radius of the fire zone, m; 0 — no fires, no rain
 * @param airShare    share of fission products remaining in the cloud (1 − surface-burst share)
 */
public record BlackRainModel(double yieldKt, double fireRadiusM, double windDirRad, double windSpeedMps, double airShare) {
    /** The rain window: parcels leaving the fires between these motion ages, seconds after detonation, carry rain. */
    public static final double START_S = 20 * 60, END_S = START_S + 90 * 60;
    /** How long a parcel rains while moving downwind, seconds of its motion age. */
    public static final double DRIFT_S = 2 * 3600;
    /** Ground dose rate under rain at H+1 near the fire zone, R/h, per 15 kt airburst. */
    public static final double RAIN_H1 = 2;

    /** The smoke parcels that make the rain. */
    public SmokeParcels parcels() {
        return new SmokeParcels(yieldKt, fireRadiusM, windDirRad, windSpeedMps);
    }

    /**
     * Whether black rain falls at the point now: under its drawn parcel (its drawn column at least {@link SmokeParcels#GONE}),
     * which left the fires inside the rain window and has travelled less than {@link #DRIFT_S}.
     */
    public boolean raining(double x, double z, SmokeParcels.Clock c) {
        if (fireRadiusM <= 0) return false;
        SmokeParcels p = parcels();
        if (!p.under(x, z, c)) return false;
        double s = p.shift(x * Math.cos(windDirRad) + z * Math.sin(windDirRad), c), travel = s / p.wind();
        double left = c.motion() - travel;
        return travel <= DRIFT_S && left >= START_S && left < END_S && p.drawn(s, c) * p.depth() >= SmokeParcels.GONE;
    }

    /**
     * Motion age at which the first rain reaches the point, seconds after detonation (the first rain-bearing parcels leave the
     * fires at full strength); infinity — outside the band.
     */
    public double startSeconds(double x, double z) {
        if (fireRadiusM <= 0) return Double.POSITIVE_INFINITY;
        SmokeParcels p = parcels();
        double ux = Math.cos(windDirRad), uz = Math.sin(windDirRad);
        double down = x * ux + z * uz, s = Math.max(0, down), travel = s / p.wind();
        if (travel > DRIFT_S || s > p.reach() || Math.hypot(x - ux * s, z - uz * s) > fireRadiusM * p.widen(s))
            return Double.POSITIVE_INFINITY;
        return START_S + travel;
    }

    /** Length of the rain window over the fires, motion-age seconds: a point at a 24-hour day gets all of it. */
    public static double durationSeconds() {
        return END_S - START_S;
    }

    /** Motion age after which no rain falls anywhere, seconds; 0 — no fires. */
    public double endSeconds() {
        return fireRadiusM <= 0 ? 0 : END_S + DRIFT_S;
    }

    /**
     * Dose rate from rain-washed ground deposits at H+1, R/h, once the rain has reached the point ({@code seconds} ≥
     * {@link #startSeconds}): full near the fire zone, decaying e-fold over a third of the band downwind, Gaussian across it
     * with half the half-width.
     */
    public double depositH1(double x, double z, double seconds) {
        if (airShare <= 0 || seconds < startSeconds(x, z)) return 0;
        double down = x * Math.cos(windDirRad) + z * Math.sin(windDirRad);
        double cross = -x * Math.sin(windDirRad) + z * Math.cos(windDirRad);
        double past = Math.max(0, down - fireRadiusM), wind = parcels().wind();
        double sigma = 0.5 * (fireRadiusM + SmokeParcels.SPREAD * Math.max(0, down));
        return RAIN_H1 * yieldKt / 15 * airShare
                * Math.exp(-past / (wind * DRIFT_S / 3)) * Math.exp(-cross * cross / (2 * sigma * sigma));
    }
}
