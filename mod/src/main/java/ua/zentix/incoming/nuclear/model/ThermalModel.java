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
    /** Fluence over the ignition threshold ({@link #ignite}) at which every ground combustible ignites ({@code RuinPlanner}). */
    public static final double IGNITE_ALL_SHARE = 4;
    /** Visibility, m: clear, rain, thunderstorm — same as visuals ({@link Visibility}). */
    public static final double VISIBILITY_CLEAR = Visibility.CLEAR;
    public static final double VISIBILITY_RAIN = Visibility.RAIN;
    public static final double VISIBILITY_THUNDER = Visibility.THUNDER;
    /** Transmittance exponent from Glasstone 1964, fig. 7.104. */
    private static final double TRANSMITTANCE_POWER = 0.544;

    private ThermalModel() {
    }

    /**
     * Ignition threshold of ground fuel, cal/cm²: where the easiest kindling (deciduous foliage, 0.25 MJ/m² = 6.0 cal/cm² at
     * 1 Mt, {@link PulseIgnition.Fuel#LEAVES}) starts to ignite. The threshold grows with yield: a longer pulse lets the fuel
     * shed more heat (Glasstone &amp; Dolan 1977, §7.35 and table 7.40). Scaled like table 7.35 thin-fabric ignition
     * ({@link BurnInjury#thinFabric}): 15 kt 3.6, 50 kt 4.3, 500 kt 5.6, 1 Mt 6.0 (⚠ approximation: one scaling for all
     * thin kindling; table 7.40 rows grow by a similar factor from 35 kt to 1.4 Mt).
     * <p>
     * Compare it with {@link #fluenceCalPerCm2} itself: that is exposure on a surface facing the fireball, the most any
     * surface receives (the threshold is defined the same way), so slopes do not raise it.
     */
    public static double ignite(double yieldKt) {
        return PulseIgnition.Fuel.LEAVES.threshold * BurnInjury.thinFabric(yieldKt) / BurnInjury.thinFabric(1000);
    }

    /** Fluence at which every ground combustible ignites, cal/cm². */
    public static double igniteAll(double yieldKt) {
        return IGNITE_ALL_SHARE * ignite(yieldKt);
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
