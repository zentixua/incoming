package ua.zentix.incoming.nuclear.model;

/**
 * One conventional air front at a body, in SI units, independent of Minecraft. The incident field is shared by
 * hearing, head motion and local litter; a wall reflection is an additional load, never a second free-air wind.
 *
 * @param arrivalSeconds time since detonation, s (ChemicalFront, including fractional game ticks)
 * @param pressurePa incident peak overpressure, Pa
 * @param positiveSeconds positive-phase duration, s
 * @param impulsePaSeconds incident positive impulse, Pa s (Kinney-Graham, not a rectangular p*t estimate)
 * @param dynamicPressurePa peak dynamic pressure, Pa
 * @param windMetresPerSecond peak air particle velocity, m/s
 * @param reflectedPressurePa peak normal reflected overpressure at a backing wall, Pa; zero if none is established
 */
public record ShockAtBody(double arrivalSeconds, double pressurePa, double positiveSeconds, double impulsePaSeconds,
                          double dynamicPressurePa, double windMetresPerSecond, double reflectedPressurePa) {
    /** Incident chemical front: TNT equivalent in kg, slant range in m, surface hemisphere or free-air sphere. */
    public static ShockAtBody chemical(double tntKg, boolean surface, double rangeM) {
        if (!(tntKg > 0)) return new ShockAtBody(0, 0, 0, 0, 0, 0, 0);
        double r = Math.max(0, rangeM), kpa = BlastModel.overpressureKpa(r, tntKg, surface);
        return new ShockAtBody(ChemicalFront.cached(tntKg, surface).arrivalSeconds(r), 1000 * kpa,
                BlastModel.chemicalPhaseSeconds(r, tntKg, surface), BlastModel.chemicalImpulse(r, tntKg, surface),
                1000 * BlastModel.dynamicPressureKpa(kpa), BlastModel.windSpeed(kpa), 0);
    }

    /** The same normal-reflection law used by server BlastBody, after the caller establishes a backing wall. */
    public ShockAtBody withBackingWall(boolean backed) {
        return new ShockAtBody(arrivalSeconds, pressurePa, positiveSeconds, impulsePaSeconds, dynamicPressurePa,
                windMetresPerSecond, backed ? 1000 * BlastInjury.reflectedKpa(pressurePa / 1000) : 0);
    }

    /** Whether the front has reached this body at elapsed seconds since detonation. */
    public boolean arrived(double elapsedSeconds) {
        return pressurePa > 0 && elapsedSeconds >= arrivalSeconds;
    }

    /** Upper bound u_peak*t+ on the positive-phase air displacement, m; the decaying pulse travels less. */
    public double maxAirDisplacementM() {
        return windMetresPerSecond * positiveSeconds;
    }
}
