package ua.zentix.incoming.nuclear.model;

/**
 * Reduced element response to a triangular positive phase. An undamped single-degree-of-freedom
 * oscillator represents the first bending mode (UFC 3-340-02, chapter 3). Its peak displacement,
 * including free vibration after the pulse, is compared with the element's connection displacement.
 * This is not a finite-element analysis: Minecraft supplies neither reinforcement nor joint details.
 * The caller supplies an explicitly estimated resistance and displacement, and geometry supplies mass.
 * Pressure is Pa, areal mass kg/m², displacement m and time s. Game wave speed is never an input.
 */
public final class StructuralResponse {
    public static final double GRAVITY = 9.80665;

    private StructuralResponse() {}

    /**
     * Peak displacement divided by the static displacement under peak pressure. Exact solution of
     * x'' + ω²x = ω²(1 - t/T), followed by free vibration. The first positive maximum is at
     * ωt = 2 atan(ωT); subsequent forced maxima are smaller because the forcing decreases.
     */
    public static double amplification(double omegaDuration) {
        double a = omegaDuration;
        if (!(a > 0)) return 0;
        // Avoid cancellation in sin(a)/a - cos(a), and retain the impulse limit ωT/2.
        if (a < 1e-4) return a * 0.5;
        double end = -Math.cos(a) + Math.sin(a) / a;
        double speed = Math.sin(a) + (Math.cos(a) - 1) / a;
        double peak = Math.hypot(end, speed);
        double first = 2 * Math.atan(a);
        if (first <= a) peak = Math.max(peak, 1 - first / a - Math.cos(first) + Math.sin(first) / a);
        return peak;
    }

    /** Maximum displacement of an elastic mode, including its post-pulse momentum. */
    public static double displacement(double pressure, double seconds, double arealMass,
                                      double resistance, double connectionDisplacement) {
        if (pressure <= 0 || seconds <= 0) return 0;
        if (!(arealMass > 0 && resistance > 0 && connectionDisplacement > 0))
            throw new IllegalArgumentException("Positive mass, resistance and connection displacement required");
        double stiffness = resistance / connectionDisplacement;
        return pressure / stiffness * amplification(seconds * Math.sqrt(stiffness / arealMass));
    }

    /**
     * Existing long-pulse failure pressure converted to an equivalent peak for the same element.
     * The legacy threshold represents a suddenly loaded element, whose dynamic amplification is two;
     * it is not silently reinterpreted as a measured static material strength.
     */
    public static double equivalentPressure(double pressure, double seconds, double arealMass,
                                            double longPulseThreshold, double connectionDisplacement) {
        double resistance = 2 * longPulseThreshold;
        return displacement(pressure, seconds, arealMass, resistance, connectionDisplacement)
                * longPulseThreshold / connectionDisplacement;
    }

    /** Velocity acquired by a released body from triangular drag, capped by the carrier flow. */
    public static double driftSpeed(double dynamicPressure, double seconds, double arealMass, double windSpeed) {
        if (!(arealMass > 0)) throw new IllegalArgumentException("Positive areal mass required");
        // Cd=1 for a tumbling block (est.); ∫q dt = q_peak T/2. No extra mass for cosmetic dust.
        return Math.min(Math.max(0, windSpeed), Math.max(0, dynamicPressure * seconds * 0.5 / arealMass));
    }
}
