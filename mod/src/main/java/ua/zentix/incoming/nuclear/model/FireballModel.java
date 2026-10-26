package ua.zentix.incoming.nuclear.model;

/**
 * Fireball: size, double flash, color, rise. Time t is seconds after detonation,
 * yield Y is kilotons, distances are meters.
 */
public final class FireballModel {
    /** How many times dimmer the second maximum is than the first pulse (for normalized brightness). */
    private static final double SECOND_PEAK = 0.85;
    /** Second-pulse decay exponent: 3.75 emits ~80% of its energy by 10·t_max. */
    private static final double PULSE_EXP = 3.75;
    /** Fireball grows to this fraction of maximum, then grows slowly while cooling. */
    static final double LATE_GROWTH_CAP = 1.3;

    /** Apparent fireball temperature at the second maximum, K (G&D 2.110: ~7 500–8 000 K, almost independent of yield). */
    public static final double PEAK_K = 7700;
    /** Ambient air temperature, K. */
    public static final double AMBIENT_K = 288;
    /** Cooling exponent after the second maximum: ΔT ∝ (t/t_max)^−0.35 (chronology keys in {@link #temperature}). */
    static final double COOLING = 0.35;
    /** Fireball is never shown hotter (first pulse is shorter than a frame): end of the client's black-body light row. */
    static final double HOTTEST_K = 30_000;

    private FireballModel() {
    }

    /** Maximum luminous fireball radius, m (Glasstone 2.12): 0.07·Y^0.4 km airburst, 0.088·Y^0.4 km surface burst. */
    public static double maxRadius(double yieldKt, boolean surfaceBurst) {
        return (surfaceBurst ? 88 : 70) * Math.pow(yieldKt, 0.4);
    }

    /** Brightness minimum between pulses, s. */
    public static double firstMinimumSeconds(double yieldKt) {
        return 0.0025 * Math.sqrt(yieldKt);
    }

    /**
     * Breakaway, s: the shock front, hitherto the fireball surface (hot compressed air
     * is opaque, smooth “glassy” sphere, G&D 2.117–2.119), has cooled to ~3000 °C and become transparent, revealing
     * the hot uneven interior (G&D 2.120, 2.124–2.125: at 20 kt, brightness minimum at 11 ms, breakaway at 15 ms, “shortly
     * after the minimum”). Here minimum × 15/11 at every yield (both follow front time; ⚠ ratio is for 20 kt).
     */
    public static double breakawaySeconds(double yieldKt) {
        return firstMinimumSeconds(yieldKt) * BREAKAWAY;
    }

    /** Breakaway relative to brightness minimum ({@link #breakawaySeconds}): 15 ms to 11 ms for 20 kt, G&D 2.120 and 2.125. */
    static final double BREAKAWAY = 15.0 / 11.0;

    /**
     * Visibility of uneven fireball interior through the front, 0..1: none before brightness minimum (surface is the smooth front),
     * front clears from minimum to breakaway (linear, ⚠ (est.)), then fully visible.
     */
    public static double unmasked(double t, double yieldKt) {
        double from = firstMinimumSeconds(yieldKt), to = breakawaySeconds(yieldKt);
        return Solve.clamp((t - from) / (to - from), 0, 1);
    }

    /** Second (main thermal) maximum, s. */
    public static double secondMaximumSeconds(double yieldKt) {
        return 0.032 * Math.sqrt(yieldKt);
    }

    /**
     * Light pulse, s: 10·t_max emits ~80 % of the second pulse's energy (15 kt: 1.24 s), after which
     * the fireball is dim. Client flash, ground light trace and server ignition ({@code FlashScorch}) occur during this interval.
     */
    public static double pulseSeconds(double yieldKt) {
        return 10 * secondMaximumSeconds(yieldKt);
    }

    /** Emitted-fraction table steps over τ from 0 to 10. */
    private static final int THERMAL_STEPS = 2000;
    /** Second-pulse energy emitted by τ = 10·i/{@link #THERMAL_STEPS}, as a fraction of energy emitted by pulse end (τ = 10). */
    private static final double[] THERMAL = thermalTable();

    private static double[] thermalTable() {
        double[] out = new double[THERMAL_STEPS + 1];
        double h = 10.0 / THERMAL_STEPS, last = 0;
        for (int i = 1; i <= THERMAL_STEPS; i++) {
            double now = second(i * h);
            out[i] = out[i - 1] + (last + now) * h / 2;
            last = now;
        }
        for (int i = 1; i <= THERMAL_STEPS; i++) out[i] /= out[THERMAL_STEPS];
        return out;
    }

    /** Second pulse over τ = t/t_max: (p/2)·τ² / (1 + (p−2)/2·τ^p), as in {@link #brightness}. */
    private static double second(double tau) {
        return PULSE_EXP / 2 * tau * tau / (1 + (PULSE_EXP - 2) / 2 * Math.pow(tau, PULSE_EXP));
    }

    /**
     * Fraction of light pulse emitted by τ = t/t_max: second-pulse energy (power is its brightness) by τ relative to
     * energy emitted over the entire pulse ({@link #pulseSeconds}, τ = 10). First pulse is fractions of a percent, excluded.
     */
    public static double thermalFraction(double tau) {
        if (tau <= 0) return 0;
        if (tau >= 10) return 1;
        double x = tau / 10 * THERMAL_STEPS;
        int i = (int) x;
        return THERMAL[i] + (THERMAL[i + 1] - THERMAL[i]) * (x - i);
    }

    /** Inverse of {@link #thermalFraction}: τ = t/t_max by which {@code fraction} of the pulse is emitted (0..10). */
    public static double thermalTau(double fraction) {
        if (fraction <= 0) return 0;
        if (fraction >= 1) return 10;
        int lo = 0, hi = THERMAL_STEPS;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (THERMAL[mid] < fraction) lo = mid;
            else hi = mid;
        }
        double span = THERMAL[hi] - THERMAL[lo];
        return 10.0 * (lo + (span <= 0 ? 0 : (fraction - THERMAL[lo]) / span)) / THERMAL_STEPS;
    }

    /**
     * Normalized brightness, 0..1: sharp first pulse (decay to minimum over t_min), then a broad second
     * maximum at t_max: f(τ) = (p/2)·τ² / (1 + (p−2)/2·τ^p), τ = t/t_max, then slow decay to zero.
     */
    public static double brightness(double t, double yieldKt) {
        if (t <= 0) return 0;
        double tMin = firstMinimumSeconds(yieldKt);
        double first = (1 - Math.exp(-t / (tMin * 0.001))) * Math.exp(-t / (tMin / 3));
        double tau = t / secondMaximumSeconds(yieldKt);
        double second = PULSE_EXP / 2 * tau * tau / (1 + (PULSE_EXP - 2) / 2 * Math.pow(tau, PULSE_EXP));
        return Math.min(1, first + SECOND_PEAK * second);
    }

    /**
     * Fireball radius, m: growth ~t^0.4 to maximum at the second brightness maximum,
     * then slow growth while cooling (+10% per e-fold of time, no more than ×1.3).
     */
    public static double radius(double t, double yieldKt, boolean surfaceBurst) {
        if (t <= 0) return 0;
        double max = maxRadius(yieldKt, surfaceBurst);
        double tg = secondMaximumSeconds(yieldKt);
        if (t <= tg) return max * Math.pow(t / tg, 0.4);
        return max * Math.min(LATE_GROWTH_CAP, 1 + 0.1 * Math.log(t / tg));
    }

    /**
     * Apparent surface temperature, K, while still a sphere. Before the second maximum, Stefan–Boltzmann from brightness
     * and radius: fireball light P ∝ T⁴·R², T = T₂·(P/P₂)^¼·(R₂/R)^½; first pulse (small hot sphere) is bluish white,
     * dimmer at minimum, reaching T₂ ({@link #PEAK_K}) at the second maximum. Then the fireball cools toward air:
     * T_ambient + (T₂ − T_ambient)·(t/t_max)^−0.35: 15 kt is white-yellow ~3 900 K at one second, ~1 950 K at nine (from
     * footage). This is core temperature: rolling into a ring (15 kt from ~3 s), the fireball cools faster outside; the edge
     * mixes with entrained air, ~1 250 K at nine seconds ({@link MushroomModel#edgeTemperature}).
     */
    public static double temperature(double t, double yieldKt) {
        if (t <= 0) return AMBIENT_K;
        double tm = secondMaximumSeconds(yieldKt);
        if (t >= tm) return AMBIENT_K + (PEAK_K - AMBIENT_K) * Math.pow(t / tm, -COOLING);
        // radius before maximum is max·(t/t_max)^0.4 ({@link #radius}): (R₂/R)^½ = (t_max/t)^0.2
        double k = PEAK_K * Math.pow(brightness(t, yieldKt) / brightness(tm, yieldKt), 0.25) * Math.pow(tm / t, 0.2);
        return Solve.clamp(k, AMBIENT_K, HOTTEST_K);
    }

    /**
     * Fireball ground reach from epicenter at time t, m ({@link #radius} at height {@link #centreHeight}); 0 if it does not
     * touch ground. No wave-lofted dust there, only fireball: dust wall and pall ({@code BlastWall}, {@code PallField})
     * start beyond it.
     */
    public static double groundRadius(double t, double hobM, double yieldKt, boolean surfaceBurst) {
        if (t <= 0) return 0;
        double r = radius(t, yieldKt, surfaceBurst), y = centreHeight(t, Math.max(0, hobM), yieldKt, surfaceBurst);
        return Math.sqrt(Math.max(0, r * r - y * y));
    }

    /**
     * Fireball rise speed immediately after burst, m/s: same as cloud top ({@link CloudModel#riseSpeed}), 1 kt
     * ~25, 15 kt ~79, 1 Mt ~139, at 10 s 130 (Glasstone and Dolan: 1 Mt fireball at 10 s is 76–107, §2.05; top
     * averages ~134 over the first minute, §2.12).
     */
    public static double riseSpeed(double yieldKt, boolean surface) {
        return CloudModel.riseSpeed(0, 0, yieldKt, surface);
    }

    /**
     * Fireball center height (later cap center), m: starts at burst height and rises like cloud top
     * ({@link CloudModel#top}: same exponential, same path); one rise law for fireball and cloud, and fireball top (no more than
     * {@link #LATE_GROWTH_CAP} radii above center) never overtakes cloud top.
     */
    public static double centreHeight(double t, double hobM, double yieldKt, boolean surface) {
        if (t <= 0) return hobM;
        return hobM + CloudModel.top(t, hobM, yieldKt, surface) - CloudModel.top(0, hobM, yieldKt, surface);
    }

    /**
     * Rise since second maximum in units of its own radius ({@link #maxRadius}): surface-pattern clock
     * (bake {@code tools/bake_fx}: hot-gas bubble, frames by rise path in radii). Rising bubble flow is
     * self-similar: its location and effect on the surface depend on path traveled in radii, not seconds,
     * so one pattern suits any yield. Before second maximum the fireball grows rather than rises: 0.
     */
    public static double surfaceRise(double t, double hobM, double yieldKt, boolean surface) {
        double tm = secondMaximumSeconds(yieldKt);
        if (t <= tm) return 0;
        return (centreHeight(t, hobM, yieldKt, surface) - centreHeight(tm, hobM, yieldKt, surface)) / maxRadius(yieldKt, surface);
    }
}
