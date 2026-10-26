package ua.zentix.incoming.util;

/**
 * ISO 2533 standard atmosphere (also ICAO and US 1976 up to 20 km): troposphere to 11 km — temperature drops 6.5 K/km,
 * pressure p = 101 325·(T/288.15)^5.25588; 11–20 km — 216.65 K, pressure e-folding height 6 341.6 m.
 * Altitude in meters above sea level (world blocks above Minecraft sea level). Projectile flight reads density (drag)
 * and sound speed (Mach number) here.
 */
public final class StandardAtmosphere {
    /** Sea-level temperature, K, and pressure, Pa. */
    public static final double T0 = 288.15, P0 = 101_325;
    /** Tropospheric temperature lapse rate, K/m. */
    static final double LAPSE = 0.0065;
    /** Exponent g₀·M/(R·L) = 9.80665·0.0289644/(8.31447·0.0065). */
    static final double EXPONENT = 5.25588;
    /** Tropopause: altitude, m, temperature, K, pressure, Pa. */
    static final double TROPOPAUSE = 11_000, T11 = 216.65, P11 = 22_632.06;
    /** Height scale above tropopause R·T/g₀ = 287.053·216.65/9.80665, m. */
    static final double SCALE11 = 6_341.62;
    /** Dry-air gas constant, J/(kg·K). */
    public static final double R_AIR = 287.053;
    /** Air adiabatic index. */
    static final double GAMMA = 1.4;

    private StandardAtmosphere() {}

    /** Temperature at altitude h meters, K (same law below sea level, 20 km value above 20 km). */
    public static double temperature(double h) {
        return h < TROPOPAUSE ? T0 - LAPSE * h : T11;
    }

    /** Pressure at altitude h meters, Pa. */
    public static double pressure(double h) {
        if (h < TROPOPAUSE) return P0 * Math.pow(temperature(h) / T0, EXPONENT);
        return P11 * Math.exp(-(Math.min(h, 20_000) - TROPOPAUSE) / SCALE11);
    }

    /** Air density at altitude h meters, kg/m³. */
    public static double density(double h) {
        return pressure(h) / (R_AIR * temperature(h));
    }

    /** Sound speed at altitude h meters, m/s: a = √(γ·R·T) (340.3 at sea level, 295.1 above tropopause). */
    public static double soundSpeed(double h) {
        return Math.sqrt(GAMMA * R_AIR * temperature(h));
    }
}
