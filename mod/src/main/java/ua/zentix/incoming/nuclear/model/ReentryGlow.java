package ua.zentix.incoming.nuclear.model;

import ua.zentix.incoming.util.StandardAtmosphere;

/**
 * Light of a reentering ICBM warhead seen from the ground: the air ahead of the cone is shocked to plasma, and a
 * small share of the drag power leaves as visible light, like a bolide. Minecraft-free.
 * <ul>
 * <li>Speed at altitude follows Allen–Eggers ballistic entry (NACA TR-1381, 1958): dv/v = −ρ ds/(2β) along a straight
 * path at angle γ, and ∫ρ dh from h to space is the column p(h)/g, so v = v₀·exp(−p(h) / (2β·g·sin γ)). A
 * high-β warhead keeps most of its speed to the ground (≈0.7 v₀ at sea level for a steep entry).</li>
 * <li>Drag power ½ρv³·C<sub>d</sub>A; luminous intensity {@link #EFFICACY}·power/(4π).</li>
 * <li>The hot wake behind the cone quenches by collisions, so it glows for a time inversely proportional to density
 * ({@link #QUENCH}): hundreds of meters long near the ground, kilometers high up.</li>
 * </ul>
 * Speed and density are real for the head's height above sea level (block = meter); the in-game approach speed is a
 * game value ({@code FlightScale}), so the glow and wake length belong to the real entry, laid along the game path:
 * not physical, like the Grad's trail over its real burn path.
 */
public final class ReentryGlow {
    /** Gravitational acceleration, m/s². */
    static final double G = 9.80665;
    /**
     * Reentry vehicle (est.): Mk21-class cone, ~360 kg, base Ø 0.55 m (public figures for the Mk21 and Mk12A vary by
     * tens of percent), hypersonic C<sub>d</sub> ~0.1 for a slender sharp cone (est.): ballistic coefficient
     * β = m / (C<sub>d</sub>A) ≈ 15 t/m².
     */
    public static final double MASS = 360, BASE = 0.55, CD = 0.1;
    static final double CDA = CD * Math.PI * BASE * BASE / 4, BETA = MASS / CDA;
    /**
     * Luminous efficacy of the drag power, lm/W (est.): meteor luminous efficiency at ~7 km/s is ~0.1 % of the
     * energy loss (Ceplecha &amp; McCrosky 1976 extrapolated to low speeds), at ~100 lm/W of visible radiation.
     * Gives magnitude −11…−14 from 5–10 km away: a bolide, bright enough to see by day.
     */
    public static final double EFFICACY = 0.1;
    /**
     * Luminous region radius, m (est.): shock layer and recirculation zone, a few base radii. Only sets the disc
     * luminance while the head is resolved; unresolved, its light is conserved ({@code Sight.light}).
     */
    public static final double RADIUS = 1.5;
    /** Wake glow e-folding time at sea-level density, s (est.): collisional quench, ∝ 1/ρ. */
    public static final double QUENCH = 0.05;
    /** Wake luminance next to the head as a share of the head's (est.): the gas behind the cone expands and cools at once, so the wake is far dimmer than the stagnation shock layer: by day a short tail, at night a long streak. */
    public static final double WAKE_SHARE = 0.05;
    /** Visible colors (est.): orange-white shock layer, orange cooling wake (ablation sodium lines and air bands). */
    public static final double HEAD_KELVIN = 5_000, WAKE_KELVIN = 3_000;
    /** A noon white wall, cd/m² ({@link BlackBody#SUN_DISK}: 1.6·10⁹ cd/m² solar disk over it). */
    static final double WALL = 1.6e9 / BlackBody.SUN_DISK;
    private static final double RHO0 = StandardAtmosphere.density(0);

    private ReentryGlow() {}

    /** Speed at {@code h} m above sea level, m/s, for entry speed {@code entry} and path angle sine {@code sin}. */
    public static double speed(double entry, double h, double sin) {
        double column = StandardAtmosphere.pressure(Math.max(0, h)) / G;
        return entry * Math.exp(-column / (2 * BETA * Math.max(0.05, sin)));
    }

    /** Drag power at {@code h}, W. */
    public static double power(double entry, double h, double sin) {
        double v = speed(entry, h, sin);
        return 0.5 * StandardAtmosphere.density(Math.max(0, h)) * v * v * v * CDA;
    }

    /** Luminous intensity, cd. */
    public static double intensity(double entry, double h, double sin) {
        return EFFICACY * power(entry, h, sin) / (4 * Math.PI);
    }

    /** Head disc luminance ({@link #RADIUS}) in noon white walls, without eye adaptation and air. */
    public static double luminance(double entry, double h, double sin) {
        return intensity(entry, h, sin) / (Math.PI * RADIUS * RADIUS) / WALL;
    }

    /** Wake glow e-folding time at {@code h}, s. */
    public static double quench(double h) {
        return QUENCH * RHO0 / StandardAtmosphere.density(Math.max(0, h));
    }

    /** Wake glow e-folding length behind the head at {@code h}, m: real speed × quench time. */
    public static double wakeLength(double entry, double h, double sin) {
        return speed(entry, h, sin) * quench(h);
    }
}
