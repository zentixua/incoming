package ua.zentix.incoming.nuclear.model;

/**
 * Black rain from fires: a firestorm lifts moist, sooty air into the detonation cloud, which produces rain — for any
 * burst above a burning city. Hiroshima (airburst; Uda 1953 via RERF): rain began after ~20 minutes; in the heavy-rain
 * zone (19 × 11 km northwest of the city, fire zone about 2 km) it poured for 1–2 hours, with light rain reaching 29 km.
 * Here, a downwind band from the fire zone: over fires, rain runs from {@link #START_S} to {@link #END_S} after detonation;
 * wind carries the cloud, which rains out over {@link #DRIFT_S} of travel; the band widens beyond the fire zone by
 * {@link #SPREAD} of distance traveled (Hiroshima — 5.5 km half-width 17 km beyond the zone). At a downwind point, rain
 * starts later by the cloud's travel time and lasts just as long. x, z are meters from the epicenter; wind blows toward
 * (cos, sin).
 *
 * <p>The clock is the firestorm's: real seconds compressed by {@link #COMPRESSION}, as for the client storm (peak at 80 s,
 * decline from 360 s versus 20 minutes and an hour and a half in reality; Artem's decision) — rain comes from visible smoke.
 *
 * <p>Radioactivity: in an airburst, fission products remain in the cloud, and rain washes some onto the ground — hundreds
 * of times weaker than surface-burst fallout (DS86, ch. 6: Hiroshima, Koi–Takasu — 1–3 R total; Nagasaki, Nishiyama —
 * 20–40 R). Ground dose rate under rain at H+1 reaches {@link #RAIN_H1} R/h per 15 kt (estimate between the two cities),
 * ∝ Y and the airburst share; decay follows Way–Wigner, as for fallout ({@link FalloutModel}). For a surface burst,
 * deposited material is its fallout.
 *
 * @param fireRadiusM ground radius of the fire zone, m; 0 — no fires, no rain
 * @param airShare    share of fission products remaining in the cloud (1 − surface-burst share)
 */
public record BlackRainModel(double yieldKt, double fireRadiusM, double windDirRad, double windSpeedMps, double airShare) {
    /** How many times faster the storm clock runs than real time. */
    public static final double COMPRESSION = 15;
    /** Rain starts and ends above the fires, real seconds after detonation. */
    public static final double START_S = 20 * 60, END_S = START_S + 90 * 60;
    /** How long the cloud rains while moving downwind, real seconds. */
    public static final double DRIFT_S = 2 * 3600;
    /** Band widening per meter traveled downwind. */
    public static final double SPREAD = 0.2;
    /** Ground dose rate under rain at H+1 near the fire zone, R/h, per 15 kt airburst. */
    public static final double RAIN_H1 = 2;
    private static final double MIN_WIND = 0.5;

    /**
     * When rain starts at a point, storm-clock seconds after detonation; infinity — outside the band.
     * It lasts {@link #durationSeconds} seconds.
     */
    public double startSeconds(double x, double z) {
        if (fireRadiusM <= 0) return Double.POSITIVE_INFINITY;
        double down = x * Math.cos(windDirRad) + z * Math.sin(windDirRad);
        double cross = -x * Math.sin(windDirRad) + z * Math.cos(windDirRad);
        double past = Math.max(0, down - fireRadiusM);
        boolean inside = down < 0 ? down * down + cross * cross <= fireRadiusM * fireRadiusM
                : Math.abs(cross) <= fireRadiusM + SPREAD * down;
        if (!inside || past > wind() * DRIFT_S) return Double.POSITIVE_INFINITY;
        return (START_S + past / wind()) / COMPRESSION;
    }

    /** Storm-clock seconds of rain at a point. */
    public static double durationSeconds() {
        return (END_S - START_S) / COMPRESSION;
    }

    /** Whether rain is falling at the point after {@code seconds} storm-clock seconds. */
    public boolean raining(double x, double z, double seconds) {
        double start = startSeconds(x, z);
        return seconds >= start && seconds < start + durationSeconds();
    }

    /** When rain ends at the band's farthest point, storm-clock seconds. */
    public double endSeconds() {
        return fireRadiusM <= 0 ? 0 : (END_S + DRIFT_S) / COMPRESSION;
    }

    /**
     * Dose rate from rain-washed ground deposits at H+1, R/h ({@code seconds} — storm clock): from rain onset at the point;
     * full near the fire zone, decaying e-fold over a third of the band downwind, Gaussian across it with half the half-width.
     */
    public double depositH1(double x, double z, double seconds) {
        if (airShare <= 0 || seconds < startSeconds(x, z)) return 0;
        double down = x * Math.cos(windDirRad) + z * Math.sin(windDirRad);
        double cross = -x * Math.sin(windDirRad) + z * Math.cos(windDirRad);
        double past = Math.max(0, down - fireRadiusM);
        double sigma = 0.5 * (fireRadiusM + SPREAD * Math.max(0, down));
        return RAIN_H1 * yieldKt / 15 * airShare
                * Math.exp(-past / (wind() * DRIFT_S / 3)) * Math.exp(-cross * cross / (2 * sigma * sigma));
    }

    private double wind() {
        return Math.max(windSpeedMps, MIN_WIND);
    }
}
