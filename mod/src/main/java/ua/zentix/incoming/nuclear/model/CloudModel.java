package ua.zentix.incoming.nuclear.model;

/**
 * Nuclear mushroom: cap height and size, stabilization time, rise. Meters, seconds, kilotons.
 * <p>
 * Rise uses one law for fireball and cloud: cloud top moves from the top of the maximum-sized fireball to the stabilized top
 * exponentially with time constant τ, h(t) = h₀ + (h∞ − h₀)·(1 − e^(−t/τ)); the fireball center rises with it
 * ({@link FireballModel#centreHeight}): the fireball is the same cap before rollup.
 * <p>
 * <b>Measurements</b>: Nevada test clouds (Hawthorne 1979, DNA 1251-1-EX; top and bottom are red lines in fig. 2 of Blakeley
 * et al., “Real time simulations of nuclear cloud rise with the ERF model”, LLNL-JRNL-2012579, digitized from a 300 dpi plot):
 * Grable, 15 kt at 160 m: top 10.0 km above ground (9.97 km from minute 9), bottom 6.1–6.2 km; Wasp, 1 kt at 232 m:
 * top 5.27 km at 8 minutes, still rising (~4 m/s), bottom 3.2 km. Heights are above ground: the figure axis is “km AGL”, and initial
 * points (Wasp top 0.68 km at 15 s, bottom 0.82 km at 30 s) lie below the sites' own elevations (Yucca Flat
 * ~1.28 km, Frenchman Flat ~0.94 km); interpreting them as sea-level heights would put the cloud underground. Uncertainty is plot digitization (±0.1 km) and
 * the unchecked primary source (DNA 1251). Megaton: Glasstone and Dolan, top at 0.3, 0.7, 1.1, 2.5 and 3.8 minutes is
 * 2, 4, 6, 10 and 12 miles above burst (table 2.12, “rough averages”); stabilized 1 Mt ≈ 21.5 km and 10 Mt ≈
 * 30 km (fig. 2.16: the cloud meets the tropopause, while 10 Mt penetrates it).
 * <p>
 * Fitted ({@code CloudModelTest}): Grable τ = 121 s with top at 10 km (RMS 4.0 % over 13 points, worst
 * 11 % at 15 s); Wasp τ = 227 s and top 5.66 km above burst (0.9 %, worst 2.6 %); table
 * 2.12 τ = 144 s with top 21.5 km (11 %, worst 18 %: rise there is nearly uniform, stopping at the tropopause, while the exponential
 * smooths it).
 * Between 1 and 15 kt, top grows as Y^0.20, close to the ¼ law for a thermal in stable air (Morton, Taylor, Turner
 * 1956: height ∝ buoyancy^¼·N^−½). Their rise time ∝ 1/N, independent of yield: Wasp τ twice that of
 * Grable and the megaton reflects atmospheric stability at that place and day, not yield. The τ nodes therefore retain the measurements as given.
 * <p>
 * <b>(est.)</b>: top and τ interpolate linearly in log space; τ above 1 Mt stays at the 1 Mt value (no measurements); cap radius
 * ({@link #capRadius}) has sparse data (sources give 1.5–2.3 km for 15 kt).
 */
public final class CloudModel {
    /**
     * Reference points (kt): stabilized cloud-top height above burst ({@link #TOP_M}, m) and rise time constant
     * ({@link #TAU_S}, s). Linear interpolation in log space; top extrapolates the end segments, τ stays
     * at the end-node value. 1 kt is Wasp (top and τ fitted), 15 kt is Grable (top 10 km AGL minus 160 m, τ
     * fitted), 1 Mt is Glasstone and Dolan (top from fig. 2.16, τ fitted to table 2.12), 10 Mt from fig. 2.16.
     */
    private static final double[] TOP_Y = {1, 15, 1000, 10000};
    private static final double[] TOP_M = {5660, 9840, 21500, 30000};
    private static final double[] TAU_S = {227, 121, 144, 144};
    /** 15 kt cap radius, m, and growth exponent: 1 Mt → ~10 km ((est.): no radius measurements, sources give 1.5–2.3 km). */
    private static final double CAP_RADIUS_15KT = 2000;
    private static final double CAP_RADIUS_EXP = 0.38;
    /** Cap bottom is this fraction of the path from burst to top: Grable 6.1 of 10 km, Wasp 3.2 of 5.3 km. */
    private static final double CAP_BOTTOM = 0.6;
    /** Biome humidity threshold for a visible Wilson cloud. */
    private static final double WILSON_HUMIDITY = 0.3;

    private CloudModel() {
    }

    /** Stabilized cloud-top height above burst, m: 1 kt is 5.7 km, 15 kt 9.8 km, 1 Mt 21.5 km. */
    public static double stabilizedTop(double yieldKt) {
        return Math.exp(logLine(yieldKt, TOP_M, true));
    }

    /** Stabilized cap radius, m: 2 km for 15 kt, ~10 km for 1 Mt. */
    public static double capRadius(double yieldKt) {
        return CAP_RADIUS_15KT * Math.pow(yieldKt / 15, CAP_RADIUS_EXP);
    }

    /**
     * Stabilization time, s: three top-rise time constants (95 % of height): 1 kt ~11 min, 15 kt ~6 min, 1 Mt
     * ~7 min (98 % by 10 minutes, “about 10 minutes” in Glasstone and Dolan, §2.15).
     */
    public static double stabilizationSeconds(double yieldKt) {
        return 3 * riseTau(yieldKt);
    }

    /**
     * Cloud-top height at time t, m above ground beneath epicenter: from the top of the maximum-sized fireball ({@link #start})
     * exponentially toward stabilized height above burst ({@link #stabilizedTop}).
     */
    public static double top(double t, double hobM, double yieldKt, boolean surface) {
        double start = start(hobM, yieldKt, surface), end = end(hobM, yieldKt, surface);
        return start + (end - start) * (1 - Math.exp(-Math.max(0, t) / riseTau(yieldKt)));
    }

    /**
     * Cloud-top rise speed at time t, m/s: derivative of {@link #top}, dropping to zero at stabilization. Immediately after
     * burst, (h∞ − h₀)/τ: 1 kt 25 m/s, 15 kt 79, 1 Mt 139 (table 2.12: first-minute average ~134).
     */
    public static double riseSpeed(double t, double hobM, double yieldKt, boolean surface) {
        double start = start(hobM, yieldKt, surface), end = end(hobM, yieldKt, surface), tau = riseTau(yieldKt);
        return (end - start) / tau * Math.exp(-Math.max(0, t) / tau);
    }

    /** Cap lower edge at time t, m: fraction {@link #CAP_BOTTOM} of the path from burst to top. */
    public static double capBottom(double t, double hobM, double yieldKt, boolean surface) {
        double base = Math.max(0, hobM);
        return base + CAP_BOTTOM * (top(t, hobM, yieldKt, surface) - base);
    }

    /** Whether this humidity produces a Wilson cloud (0..1 from biome; 1 over water). */
    public static boolean wilsonCloud(double humidity01) {
        return humidity01 >= WILSON_HUMIDITY;
    }

    /** Top-rise time constant, s: 1 kt 227, 15 kt 121, 1 Mt and above 144 ({@link #TAU_S}). */
    static double riseTau(double yieldKt) {
        return Math.exp(logLine(yieldKt, TAU_S, false));
    }

    /**
     * Cloud top at detonation, m above ground: top of the maximum-sized fireball ({@link FireballModel#LATE_GROWTH_CAP}
     * radii), so the top of the fireball rising with the cloud never overtakes it. Fits to the measurements are no worse than with
     * the fireball top at the second maximum.
     */
    static double start(double hobM, double yieldKt, boolean surface) {
        return Math.max(0, hobM) + FireballModel.LATE_GROWTH_CAP * FireballModel.maxRadius(yieldKt, surface);
    }

    /** Stabilized top, m above ground: above burst, no lower than the start. */
    static double end(double hobM, double yieldKt, boolean surface) {
        return Math.max(start(hobM, yieldKt, surface), Math.max(0, hobM) + stabilizedTop(yieldKt));
    }

    /**
     * Logarithm of a quantity at {@link #TOP_Y} nodes: linear in log space between nodes; beyond the ends, extrapolate the end
     * segment ({@code extend}) or keep the end-node value.
     */
    private static double logLine(double yieldKt, double[] values, boolean extend) {
        double ly = Math.log(Math.max(yieldKt, 1e-3));
        if (!extend) ly = Solve.clamp(ly, Math.log(TOP_Y[0]), Math.log(TOP_Y[TOP_Y.length - 1]));
        int i = 1;
        while (i < TOP_Y.length - 1 && ly > Math.log(TOP_Y[i])) i++;
        double x0 = Math.log(TOP_Y[i - 1]), x1 = Math.log(TOP_Y[i]);
        double y0 = Math.log(values[i - 1]), y1 = Math.log(values[i]);
        return y0 + (ly - x0) * (y1 - y0) / (x1 - x0);
    }
}
