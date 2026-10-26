package ua.zentix.incoming.sound;

/**
 * Supersonic body N-wave at the ear — simplified Carlson method (H. W. Carlson, “Simplified sonic-boom prediction”,
 * NASA TP-1122, 1978, eqs. 1 and 2), without Minecraft:
 * <pre>
 * Δp = K<sub>p</sub>·K<sub>R</sub>·√(p<sub>v</sub>·p<sub>g</sub>)·(M² − 1)^⅛·h<sub>e</sub>^−¾·l^¾·K<sub>S</sub>
 * Δt = K<sub>t</sub>·(3.42 / a<sub>v</sub>)·M / (M² − 1)^⅜·h<sub>e</sub>^¼·l^¾·K<sub>S</sub>
 * </pre>
 * Near ground, homogeneous atmosphere: K<sub>p</sub> = K<sub>t</sub> = 1, p<sub>v</sub> = p<sub>g</sub>;
 * ground reflection K<sub>R</sub> = 2 (as Carlson). Non-lifting body shape (projectile, bomb, missile) from frontal area:
 * K<sub>S</sub> = 0.685·√A<sub>max</sub> / (l^¾·l<sub>e</sub>^¼), l<sub>e</sub> = l (shape parameter 0.65–0.69 in
 * Carlson examples, fig. 2). h<sub>e</sub> is flight-line-to-ear distance; method is far-field, using body length as
 * minimum distance.
 */
public final class SonicBoom {
    /** Ground reflection and shape parameter (TP-1122). */
    static final double REFLECTION = 2, SHAPE = 0.685;
    /** Ground pressure, Pa. */
    static final double PRESSURE = Air.KPA * 1000;

    private SonicBoom() {}

    /** Pressure jump and N-wave duration. */
    public record Wave(double pressure, double seconds) {}

    /**
     * N-wave at the ear.
     *
     * @param mach     body Mach number (above 1)
     * @param distance flight-line-to-ear distance, m
     * @param body     body
     */
    public static Wave at(double mach, double distance, Noise.Body body) {
        double l = body.length();
        return signature(mach, Math.max(distance, l), l, shape(body.area(), l, l), PRESSURE, PRESSURE, Noise.SOUND, 1, 1);
    }

    /** Shape factor K<sub>S</sub> from maximum section {@code area}, m², length {@code l} and distance to it {@code le}, m. */
    static double shape(double area, double l, double le) {
        return SHAPE * Math.sqrt(area) / (Math.pow(l, 0.75) * Math.pow(le, 0.25));
    }

    /**
     * Full TP-1122 eqs. 1 and 2.
     *
     * @param h  effective height, m
     * @param l  length, m
     * @param ks shape factor
     * @param pv pressure at body, Pa
     * @param pg ground pressure, Pa
     * @param av sound speed at body, m/s
     * @param kp atmospheric pressure factor
     * @param kt atmospheric duration factor
     */
    static Wave signature(double mach, double h, double l, double ks, double pv, double pg, double av, double kp, double kt) {
        double m2 = mach * mach - 1, scale = Math.pow(l, 0.75) * ks;
        double dp = kp * REFLECTION * Math.sqrt(pv * pg) * Math.pow(m2, 0.125) * Math.pow(h, -0.75) * scale;
        double dt = kt * 3.42 / av * mach / Math.pow(m2, 0.375) * Math.pow(h, 0.25) * scale;
        return new Wave(dp, dt);
    }
}
