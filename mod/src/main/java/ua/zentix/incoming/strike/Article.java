package ua.zentix.incoming.strike;

import org.jetbrains.annotations.Nullable;

/**
 * Real article specifications: mass, dimensions, drag, engines, real flight speeds and altitudes, impact
 * conditions, and warhead of one flight entity ({@link WeaponSpec.Airframe#article}). One record per article,
 * shared by server and client: sound ({@code sound.Noise}) and plumes ({@code client.ClientWeaponSpec}) take
 * engine values here. SI units (kg, m, s, N); each value's source is in article spec Javadoc ({@link
 * WeaponSpec}); “E” means a law-derived estimate from other values, with a range.
 * <p>
 * Game speeds and altitudes are in {@link WeaponSpec.Airframe} (blocks/tick, blocks): not physical, slower and
 * lower so approach is visible over game distances of hundreds of blocks; real ones are here ({@link #real}).
 * Everything at the target, charge, warhead casing, impact speed and angle, is real under any setting. Flight
 * does not read these values yet (except engine data for sound and visuals): flight and explosion physics steps
 * use them; tests check against laws ({@code WeaponSpecTest}).
 *
 * @param dryMass mass without fuel or jettisoned booster, kg
 * @param launchMass launch mass without jettisoned booster ({@link SolidMotor#jettison}), kg: includes fuel (MLRS
 * includes motor propellant)
 * @param length body length, m
 * @param diameter body diameter (B-2 height), m
 * @param span wingspan (diameter if wingless), m
 * @param cda subsonic drag area C<sub>d</sub>·S, m²: D = ½·ρ·v²·CdA·{@link #machFactor}(M)
 * @param sustainer cruise engine; null if absent
 * @param booster solid booster (jettisoned) or rocket motor; null if absent
 * @param real real flight speeds and altitude
 * @param impact real impact conditions (ICBM warhead atmospheric entry); null if no direct impact (B-2)
 * @param warhead conventional warhead; null if absent (B-2 carrier, ICBM nuclear)
 * @param fuelAtImpact tank fuel at impact, kg: burns in explosion fireball (client visuals and sound)
 * @param penetrator body penetrating ground and concrete ({@code warhead.Penetration}); null if article does not
 * penetrate
 */
public record Article(double dryMass, double launchMass, double length, double diameter, double span, double cda,
                      @Nullable Propulsion sustainer, @Nullable SolidMotor booster, Envelope real, @Nullable Impact impact,
                      @Nullable Charge warhead, double fuelAtImpact, @Nullable Penetrator penetrator) {

    /** Gravitational acceleration, m/s². */
    public static final double G = 9.80665;

    /** Cruise engine. */
    public sealed interface Propulsion permits Propeller, Turbofan {
        /** Nominal fuel flow (at cruise), kg/s; 0 means electric motor. */
        double fuelFlow();
    }

    /**
     * Pusher propeller motor: thrust T = η·P/v ({@code formulas.md} §2); at rest, limited by power.
     *
     * @param power nominal shaft power, W
     * @param rpm nominal propeller speed, rpm
     * @param blades propeller blades
     * @param efficiency propeller cruise efficiency
     * @param fuelFlow nominal fuel flow, kg/s (0 means battery electric motor)
     */
    public record Propeller(double power, double rpm, int blades, double efficiency, double fuelFlow) implements Propulsion {
        /** Thrust at airspeed v, N. */
        public double thrust(double v) {
            return efficiency * power / v;
        }
    }

    /**
     * Turbofan engine.
     *
     * @param count engine count
     * @param thrust one engine's static sea-level thrust, N
     * @param airflow one engine's static airflow, kg/s
     * @param jet static mixed-jet speed, m/s (thrust ≈ flow × jet speed)
     * @param fuelFlow one engine's cruise fuel flow, kg/s
     */
    public record Turbofan(int count, double thrust, double airflow, double jet, double fuelFlow) implements Propulsion {}

    /**
     * Solid motor: constant thrust throughout burn (real thrust curves depend on grain geometry; no public
     * curves, so use average), flow ṁ = T / v<sub>e</sub>, propellant ṁ·t.
     *
     * @param thrust average thrust, N
     * @param exhaust effective exhaust velocity, m/s (specific impulse × g₀)
     * @param burn burn time, s
     * @param jettison entire jettisoned booster mass (propellant and casing), kg; 0 means motor within projectile
     * body (MLRS, SAM): propellant burns out of projectile mass
     */
    public record SolidMotor(double thrust, double exhaust, double burn, double jettison) {
        /** Fuel flow, kg/s. */
        public double flow() {
            return thrust / exhaust;
        }

        /** Propellant mass, kg. */
        public double propellant() {
            return flow() * burn;
        }

        /** Total impulse, N·s. */
        public double impulse() {
            return thrust * burn;
        }
    }

    /**
     * Real flight speeds and altitude (game values in {@link WeaponSpec.Airframe}, not physical).
     *
     * @param cruise cruise speed, m/s (MLRS at burnout, ICBM at first-stage end)
     * @param dive maximum dive or terminal speed, m/s
     * @param altitude route height above terrain, m (B-2 release height; 0 for unspecified ballistic altitude)
     */
    public record Envelope(double cruise, double dive, double altitude) {}

    /**
     * Real impact conditions: release or launch from real height and range (law checks, {@code WeaponSpecTest}).
     * In game, flight uses its own model with game altitudes and speeds: penetration takes entity impact speed
     * and angle at world flight end, rather than these.
     *
     * @param speed speed, m/s
     * @param angle trajectory angle to horizon, ° (90 vertical)
     */
    public record Impact(double speed, double angle) {}

    /**
     * Conventional warhead.
     *
     * @param tnt TNT-equivalent charge, kg: blast wave, glass, crater
     * @param explosive explosive mass, kg (TNT equivalent is this × relative explosive strength)
     * @param caseMass warhead casing mass, kg: fragments ({@code warhead.FragmentModel})
     * @param diameter outer warhead casing diameter, m: explosive cylinder in steel shell; wall thickness and
     * length follow from explosive and casing masses ({@code FragmentModel})
     */
    public record Charge(double tnt, double explosive, double caseMass, double diameter) {}

    /**
     * Penetrating body: mass, diameter, and length belong to the article; here only the nose.
     *
     * @param crh ogive arc radius in calibers (CRH): Young nose coefficient via {@code warhead.Penetration#ogive}
     */
    public record Penetrator(double crh) {}

    /**
     * Distance traveled while a body-mounted motor burns (MLRS: {@link SolidMotor#jettison} 0, {@link
     * Envelope#cruise} speed at burnout), m: accelerate from launch rail exit to it over {@link SolidMotor#burn},
     * v·t/2 (⚠ constant thrust, declining mass: actual late acceleration is steeper, distance ~10% shorter; tube
     * launch rail exit speed, ~50 m/s for MLRS, ignored). Grad: 683 · 1.9 / 2 ≈ 650 m. 0 means no body-mounted
     * motor or it is jettisoned.
     */
    public double burnPath() {
        return booster == null || booster.jettison() > 0 ? 0 : real.cruise() * booster.burn() / 2;
    }

    /**
     * Mach drag multiplier f(M) for finned slender bodies without published wind-tunnel data (E, typical curve
     * shape, {@code formulas.md} §2): 1 through M0.8; rises to 1.8 at M1.05; 1.8 through M1.2; then falls by 0.45
     * per Mach, no lower than 1.1. Approximate law: every real body has its own curve.
     */
    public static double machFactor(double mach) {
        if (mach < 0.8) return 1;
        if (mach < 1.05) return 1 + 0.8 * (mach - 0.8) / 0.25;
        if (mach < 1.2) return 1.8;
        return Math.max(1.1, 1.8 - 0.45 * (mach - 1.2));
    }
}

