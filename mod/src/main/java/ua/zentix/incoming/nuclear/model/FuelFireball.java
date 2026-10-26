package ua.zentix.incoming.nuclear.model;

/**
 * Fuel-tank fireball at impact (CCPS 1994, “Guidelines for Evaluating the Characteristics of Vapor Cloud Explosions,
 * Flash Fires and BLEVEs”): diameter D = 5.8·M^⅓ m, burn time t = 0.45·M^⅓ s (M — kg fuel, up to 30 t), surface emission
 * 200–350 kW/m² — midpoint {@link #SURFACE_POWER}. Not all fuel burns in the ball: {@link #POOL_SHARE} remains in the
 * crater. One law for the server (what the ball ignites) and client visuals and sound ({@code CloudModel}).
 */
public final class FuelFireball {
    /** Ball surface emission, W/m²: midpoint of 200–350 kW/m² (CCPS). */
    public static final double SURFACE_POWER = 275e3;
    /** Joules per calorie: ignition fluence uses cal/cm², as for nuclear ({@link ThermalModel#IGNITE}). */
    private static final double J_PER_CAL_CM2 = 4.1868e4;
    /**
     * Share of impact fuel remaining in the crater and burning there instead of spraying into the fireball: Sandia
     * experiments with a ~1/42-scale C-141 wing hitting sand and clay (Tieszen, Attaway, “Fuel dispersal in high-speed
     * aircraft/soil impact scenarios”, SAND95-2529, 1996, OSTI 188568) — from nearly zero retained at grazing impact to 25–50 %
     * at steep impact. Shahed hits at 60°, missile at 45° — steep; use the lower edge: experiments had no charge, whereas a
     * warhead explosion beside the tank ejects fuel from the crater (⚠ estimate within the range). The rest forms the ball
     * ({@link #ballKg}).
     */
    public static final double POOL_SHARE = 0.25;

    private FuelFireball() {}

    /** Fuel from {@code fuelKg} kg at impact burning in the ball, kg: all except crater share ({@link #POOL_SHARE}). */
    public static double ballKg(double fuelKg) {
        return Math.max(0, fuelKg) * (1 - POOL_SHARE);
    }

    /** Ball diameter from {@code fuelKg} kg of fuel in the ball ({@link #ballKg}), m. */
    public static double diameter(double fuelKg) {
        return fuelKg <= 0 ? 0 : 5.8 * Math.cbrt(fuelKg);
    }

    /** Burn duration for a ball of {@code fuelKg} kg fuel in the ball, s. */
    public static double seconds(double fuelKg) {
        return fuelKg <= 0 ? 0 : 0.45 * Math.cbrt(fuelKg);
    }

    /**
     * Thermal fluence on a surface inside a ball of {@code fuelKg} kg fuel in the ball, cal/cm²: surface emission × burn time
     * (a surface inside sees flame on all sides — upper estimate for faces turned away from center).
     * A world position covered for only part of the burn receives less — {@link Sweep#exposure}.
     */
    public static double exposure(double fuelKg) {
        return SURFACE_POWER * seconds(fuelKg) / J_PER_CAL_CM2;
    }

    /**
     * Radius of a ball of diameter {@code d}, m, burning for {@code s} s, at age t, s (0 — extinguished or not yet present):
     * grows to D/2 over the first fifth of the burn (⚠ shape estimate).
     */
    public static double radius(double d, double s, double t) {
        return t >= 0 && t < s ? 0.5 * d * Math.min(1, Math.sqrt(t / (0.2 * s))) : 0;
    }

    /**
     * Center of a ball of radius r growing from a blast point {@code hob} m above the surface, above that point, m:
     * surface burst — hemisphere (visible center 0.45 radius above ground); free airburst — center at blast point;
     * between them, by radius fraction below ground (⚠ shape estimate: ground-clipped sphere to hemisphere linear in burst
     * height). Same law for the detonation-product ball.
     */
    public static double onGround(double r, double hob) {
        return r <= 0 ? 0 : 0.45 * r * Math.clamp(1 - hob / r, 0, 1);
    }

    /**
     * Rise of a burning ball of diameter {@code d}, burning {@code s} s, by age t, m: from ground to center at 0.75·D at burn
     * end (CCPS), over exactly the burn duration (⚠ shape estimate: CCPS gives only final height).
     */
    public static double lift(double d, double s, double t, double hob) {
        if (d <= 0) return 0;
        return (0.75 * d - onGround(0.5 * d, hob)) * Math.min(1, t / Math.max(s, 1e-6));
    }

    /** Ball center height above the blast point at age t, m (0 — no ball): {@link #onGround} at its radius plus {@link #lift}. */
    public static double height(double d, double s, double t, double hob) {
        double r = radius(d, s, t);
        return r <= 0 ? 0 : onGround(r, hob) + lift(d, s, t, hob);
    }

    /** Burn steps in {@link Sweep}. */
    static final int SWEEP_STEPS = 24;

    /**
     * Ball path over the burn — position and size per step ({@link #radius}, {@link #height}, wind drift at center height:
     * u(z) = u₁₀·(z/10)^α, as for the cloud) — and its effect on a nearby position. One ball for client visuals
     * ({@code CloudModel}: same growth and rise; drift there uses the cloud thermal centered on the ball, differing from this
     * by less than a meter) and server ignition ({@code Craters.ignite}). Meters from the blast point.
     *
     * @param x  ball center per step, x
     * @param y  center height
     * @param z  z coordinate
     * @param r  radius (0 — no step)
     * @param dt step, s
     */
    public record Sweep(double[] x, double[] y, double[] z, double[] r, double dt) {
        /**
         * Ball of {@code ballKg} kg fuel ({@link #ballKg}) at {@code hob} above the surface, 10 m wind
         * {@code windX}, {@code windZ} m/s, wind profile exponent {@code alpha}.
         */
        public static Sweep of(double ballKg, double hob, double windX, double windZ, double alpha) {
            double d = diameter(ballKg), s = seconds(ballKg), dt = s / SWEEP_STEPS;
            double[] x = new double[SWEEP_STEPS], y = new double[SWEEP_STEPS], z = new double[SWEEP_STEPS], r = new double[SWEEP_STEPS];
            double along = 0;
            for (int i = 0; i < SWEEP_STEPS && s > 0; i++) {
                double t = (i + 0.5) * dt, h = height(d, s, t, hob);
                // drift to step midpoint: wind at ball center height (same law at step start)
                along += Math.pow(Math.max(height(d, s, i * dt, hob) + hob, 1) / 10, alpha) * 0.5 * dt;
                x[i] = windX * along;
                z[i] = windZ * along;
                y[i] = h;
                r[i] = radius(d, s, t);
                along += Math.pow(Math.max(h + hob, 1) / 10, alpha) * 0.5 * dt;
            }
            return new Sweep(x, y, z, r, dt);
        }

        /**
         * Thermal fluence at (px, py, pz) m from the blast point, cal/cm²: inside the ball, surface emission
         * {@link #SURFACE_POWER} (as {@link FuelFireball#exposure}); outside, the fraction subtended by the ball, (r/d)²
         * (sphere before a face directed toward it; ⚠ upper estimate, as inside — face inclination ignored). A ball risen above
         * the position keeps illuminating it, more weakly; a position the ball never occupied receives almost no fluence.
         */
        public double exposure(double px, double py, double pz) {
            double sum = 0;
            for (int i = 0; i < r.length; i++) {
                if (r[i] <= 0) continue;
                double dx = px - x[i], dy = py - y[i], dz = pz - z[i], d2 = dx * dx + dy * dy + dz * dz, r2 = r[i] * r[i];
                sum += d2 <= r2 ? 1 : r2 / d2;
            }
            return SURFACE_POWER * sum * dt / J_PER_CAL_CM2;
        }
    }
}
