package ua.zentix.incoming.client.fx;

import ua.zentix.incoming.util.StandardAtmosphere;

/**
 * Body vapour collar near sound speed — no Minecraft dependencies. Transonic flow above the body has a low-pressure zone:
 * air expands and cools there, and in humid air vapour condenses into a cloud (Prandtl–Glauert
 * singularity in photos — around M 0.9–1.1). Nothing in dry air or at lower speed. Bounds are estimates (est.): accurate values
 * need a flow solution for the specific body and dew point, which we do not have.
 */
public final class VapourCollar {
    /** Transonic: lower and upper Mach bounds (est.) (from photos). */
    static final double MACH_MIN = 0.9, MACH_MAX = 1.1;
    /**
     * Above this humidity, cooling in the low-pressure zone is sufficient for vapour condensation (est.) (near ground it cools by degrees; dew point
     * is within a few degrees at relative humidity above ~0.7).
     */
    static final double HUMIDITY = 0.7;

    private VapourCollar() {}

    /** Mach number: speed {@code speed} m/s at {@code altitude} m in the standard atmosphere. */
    public static double mach(double speed, double altitude) {
        return speed / Math.sqrt(1.4 * StandardAtmosphere.R_AIR * StandardAtmosphere.temperature(altitude));
    }

    /** Whether a collar exists at Mach {@code mach} and relative humidity {@code humidity} (0..1). */
    public static boolean forms(double mach, double humidity) {
        return mach >= MACH_MIN && mach <= MACH_MAX && humidity >= HUMIDITY;
    }
}
