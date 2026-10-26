package ua.zentix.incoming.strike;

import ua.zentix.incoming.entity.FlightPhase;

/**
 * Launch solid-booster thrust over time, fraction of plateau thrust: one law for flame, light, launch cloud, and
 * booster sound (acceleration itself is game flight model, {@link WeaponSpec.LaunchProfile#boost}). Three
 * sections:
 * <ol>
 * <li><b>Rise</b> after ignition over {@link #RISE} ticks: solid-motor chamber pressure rises in 0.1–0.3 s (⚠
 * estimate).</li>
 * <li><b>Plateau</b>.</li>
 * <li><b>Tail-off</b>: grain web has burned through, remnants burn out, and burning area S falls to zero over
 * {@link #tail}. Equilibrium chamber pressure: gas supply S·ρ·a·pⁿ (Saint-Robert burn-rate law r = a·pⁿ) equals
 * throat flow p·S_throat/c*, hence p ∝ (S/S_throat)^(1/(1 − n)) (Sutton, Biblarz, “Rocket Propulsion Elements”,
 * ch. 12). Remnant area decreases linearly (⚠ estimate: real area follows grain geometry). Choked nozzle: flow
 * and thrust ∝ pressure (⚠ thrust coefficient assumed unchanged as pressure falls). Continuous: 1 at tail-off
 * start, 0 at end with zero slope (exponent > 1).</li>
 * </ol>
 * Booster separates at {@link FlightPhase#BOOST} end ({@code StrikeProjectile.launchTick}), after tail-off: flame
 * is gone when casing departs.
 *
 * @param rise thrust rise after ignition, ticks
 * @param burn {@link FlightPhase#BOOST} ticks until separation (game value, {@link
 * WeaponSpec.LaunchProfile#boostTicks})
 * @param tail tail-off at its end, ticks
 */
public record ThrustCurve(double rise, double burn, double tail) {
    /** Thrust rise after ignition, ticks. */
    public static final double RISE = 6;
    /**
     * Saint-Robert exponent n for ammonium-perchlorate composite propellant: 0.2–0.5 (Sutton, Biblarz, ch. 12); ⚠
     * 0.35.
     */
    public static final double EXPONENT = 0.35;
    /**
     * Tail-off fraction of real burn time ({@link Article.SolidMotor#burn}). ⚠ Estimate: web remnants burn at the
     * same rate as full web, so tail/burn ratio follows remnant/web thickness, a few percent for rocket grains;
     * Tomahawk booster (12 s) 0.6 s, Shahed (2.5 s) 0.13 s.
     */
    public static final double TAIL_SHARE = 0.05;

    /**
     * Curve for booster m with launch profile lp: real-duration tail-off ({@link #TAIL_SHARE} of real burn, at 20
     * ticks/s), plateau is remaining game boost phase (shorter than real, not physical, {@code WeaponSpec});
     * tail-off at most half the phase.
     */
    public static ThrustCurve of(WeaponSpec.LaunchProfile lp, Article.SolidMotor m) {
        return new ThrustCurve(RISE, lp.boostTicks(), Math.min(TAIL_SHARE * m.burn() * 20, lp.boostTicks() / 2.0));
    }

    /** Ignition thrust fraction: rise over {@link #RISE} ticks (all solid motors, including without curve). */
    public static double ignition(double age) {
        return Math.min(1, Math.max(age, 0.5) / RISE);
    }

    /**
     * Plateau-thrust fraction at age ticks (fractional) of phase ph: ignition rise, boost plateau/tail-off before
     * separation, no booster burn in other phases.
     */
    public double share(FlightPhase ph, double age) {
        if (ph == FlightPhase.IGNITION) return Math.min(1, Math.max(age, 0.5) / rise);
        if (ph != FlightPhase.BOOST) return 0;
        double left = burn - age;
        if (left >= tail) return 1;
        if (left <= 0) return 0;
        return Math.pow(left / tail, 1 / (1 - EXPONENT));
    }
}
