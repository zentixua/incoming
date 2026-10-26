package ua.zentix.incoming.client.fx.plume;

import org.jetbrains.annotations.Nullable;

/**
 * Engine as a fire/smoke source — spec data ({@code ClientWeaponSpec}) used by {@link FlameModel} for the
 * plume and {@link TrailModel} for the trail. Flight speed is not stored here: supplied by entity or projectile track.
 * SI units. Identical nozzles; multi-nozzle stage jets merge into one (drawn as one jet with the same area).
 *
 * @param name        name for scenario lines and tests
 * @param nozzles     nozzle count
 * @param exhaust     flow through all nozzles, kg/s (rocket — propellant, air-breathing/piston — air and fuel)
 * @param fuel        fuel burn rate, kg/s: source of smoke, water and heat
 * @param velocity    exit exhaust velocity, m/s (rocket — effective: specific impulse × g₀)
 * @param temperature gas stagnation temperature (rocket — chamber), K
 * @param gamma       product heat-capacity ratio (aluminized solid rocket — effective two-phase)
 * @param molarMass   gas molar mass, kg/mol
 * @param exitRadius  exit radius of one nozzle, m
 * @param smoke       condensed products; null — none
 * @param smokeIndex  their mass per kilogram of fuel
 * @param monoxide    CO fraction in exit products (afterburns in air)
 * @param hydrogen    H₂ fraction in exit products (afterburns in air)
 * @param water       water vapour per kilogram of fuel (for contrails)
 * @param heat        heat of combustion per kilogram of fuel, J
 * @param efficiency  heat fraction converted to thrust work rather than the trail (overall η in Schmidt–Appleman)
 */
public record Motor(String name, int nozzles, double exhaust, double fuel, double velocity, double temperature, double gamma, double molarMass,
                    double exitRadius, @Nullable Smoke smoke, double smokeIndex, double monoxide, double hydrogen, double water, double heat,
                    double efficiency) {
    /** Universal gas constant, J/(mol·K). */
    public static final double R = 8.31446;
    /** g₀, m/s². */
    public static final double G0 = 9.80665;

    /** Gas specific heat at constant pressure, J/(kg·K). */
    public double cp() {
        return gamma * R / ((gamma - 1) * molarMass);
    }

    /** Exit temperature: stagnation minus kinetic, K. */
    public double exitTemperature() {
        return Math.max(1, temperature - velocity * velocity / (2 * cp()));
    }

    /** Total nozzle exit area, m². */
    public double exitArea() {
        return nozzles * Math.PI * exitRadius * exitRadius;
    }

    /** Exit gas density, kg/m³. */
    public double exitDensity() {
        return exhaust / (velocity * exitArea());
    }

    /** Exit pressure, Pa. */
    public double exitPressure() {
        return exitDensity() * R / molarMass * exitTemperature();
    }

    /** Chamber pressure (isentropic from exit to stagnation temperature), Pa: data plausibility check. */
    public double chamberPressure() {
        return exitPressure() * Math.pow(temperature / exitTemperature(), gamma / (gamma - 1));
    }

    /** Thrust at design exit pressure, N. */
    public double thrust() {
        return exhaust * velocity;
    }

    /** Condensed product mass rate, kg/s. */
    public double smokeFlow() {
        return smoke == null ? 0 : fuel * smokeIndex;
    }

    /** Water vapour mass rate, kg/s. */
    public double waterFlow() {
        return fuel * water;
    }

    /**
     * Same motor at fraction share of plateau flow — solid rocket thrust decay ({@code strike.ThrustCurve}): nozzle choked,
     * flow ∝ chamber pressure; chamber temperature and exhaust velocity unchanged (⚠ falling pressure overexpands the nozzle,
     * reducing exhaust velocity slightly); same exit — exit and chamber pressures fall with flow.
     */
    public Motor throttled(double share) {
        return new Motor(name, nozzles, exhaust * share, fuel * share, velocity, temperature, gamma, molarMass, exitRadius, smoke, smokeIndex,
                monoxide, hydrogen, water, heat, efficiency);
    }

    /** Heat the engine leaves in the air per second, W. */
    public double heatFlow() {
        return fuel * heat * (1 - efficiency);
    }

    /**
     * Nozzle exit radius yielding pressure p at these flow, velocity and temperatures
     * (design condition — nozzle designed for altitude at this pressure).
     */
    public static double matchedRadius(int nozzles, double exhaust, double velocity, double temperature, double gamma, double molarMass, double p) {
        double cp = gamma * R / ((gamma - 1) * molarMass), te = temperature - velocity * velocity / (2 * cp);
        double rho = p * molarMass / (R * te);
        return Math.sqrt(exhaust / (nozzles * rho * velocity * Math.PI));
    }
}
