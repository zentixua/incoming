package ua.zentix.incoming.nuclear.model;

import ua.zentix.incoming.util.Visibility;

/**
 * Thermal fluence: Q = f·Y·10¹²·τ / (4π·(100·R)²) cal/cm², f — thermal energy fraction: 0.35 airburst, 0.18 surface burst.
 * Air transmittance τ = exp(−(R/V)^0.544), direct and scattered light together, fitted to Glasstone 1964 fig. 7.104
 * (10 and 50 mile visibility): ±0.03 to half visibility; below 16 km visibility (rain, thunderstorm), extrapolated fit.
 * R, V in meters.
 */
public final class ThermalModel {
    /** First-degree burn, cal/cm². */
    public static final double BURN_1 = 3;
    /** Second-degree burn. */
    public static final double BURN_2 = 5;
    /** Third-degree burn. */
    public static final double BURN_3 = 8;
    /** Ignition of foliage, grass, wool. */
    public static final double IGNITE = 5;
    /** Thermal fluence, cal/cm², at which nuclear ruin combustibles ignite with certainty ({@code RuinPlanner}). */
    public static final double IGNITE_ALL = 20;
    /** Visibility, m: clear, rain, thunderstorm — same as visuals ({@link Visibility}). */
    public static final double VISIBILITY_CLEAR = Visibility.CLEAR;
    public static final double VISIBILITY_RAIN = Visibility.RAIN;
    public static final double VISIBILITY_THUNDER = Visibility.THUNDER;
    /** Transmittance exponent from Glasstone 1964, fig. 7.104. */
    private static final double TRANSMITTANCE_POWER = 0.544;

    private ThermalModel() {
    }

    /** Thermal energy density at slant range R, cal/cm². */
    public static double fluenceCalPerCm2(double slantRangeM, double yieldKt, boolean surfaceBurst, double visibilityM) {
        double r = Math.max(slantRangeM, 1);
        double f = surfaceBurst ? 0.18 : 0.35;
        double rCm = 100 * r;
        return f * yieldKt * 1e12 * transmittance(r, visibilityM) / (4 * Math.PI * rCm * rCm);
    }

    /** Thermal pulse fraction transmitted over slant range R at visibility V (direct and scattered light). */
    public static double transmittance(double slantRangeM, double visibilityM) {
        return Math.exp(-Math.pow(Math.max(slantRangeM, 0) / visibilityM, TRANSMITTANCE_POWER));
    }

    /** Range where energy density falls to q, m. */
    public static double rangeForFluence(double q, double yieldKt, boolean surfaceBurst, double visibilityM) {
        return Solve.decreasingRoot(r -> fluenceCalPerCm2(r, yieldKt, surfaceBurst, visibilityM), q, 1, 1e8);
    }
}
