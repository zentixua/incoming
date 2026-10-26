package ua.zentix.incoming.client.fx.plume;

/**
 * Contrail by the Schmidt–Appleman criterion (Schumann, “On conditions for contrail formation from aircraft
 * exhausts”, Meteorol. Z. 5, 1996). Exhaust/air mixture follows a straight line in (T, e) coordinates with slope
 * G = EI_H₂O·c_p·p/(ε·Q·(1 − η)), Pa/K; a trail forms if this line exceeds water saturation: air colder than
 * T_LC. In saturated air T_LC = T_LM, T_LM ≈ −46.46 + 9.43·ln(G − 0.053) + 0.72·ln²(G − 0.053) °C; at
 * water-relative humidity U, T_LC solves e_w(T_LM) − U·e_w(T) = G·(T_LM − T). The trail persists while air is ice-
 * supersaturated; otherwise ice sublimates when dilution removes excess vapour ({@link #dryAt}).
 * <p>
 * Saturation pressure — WMO Magnus formulas (CIMO Guide, 2008, app. 4.B): over water 6.112·e^(17.62t/(243.12 + t)),
 * over ice 6.112·e^(22.46t/(272.62 + t)) hPa, t in °C.
 */
public final class Contrail {
    /** Air heat capacity, J/(kg·K). */
    static final double CP_AIR = 1004;
    /** Water-to-air molar mass ratio. */
    static final double EPSILON = 0.622;
    /** Water vapour gas constant, J/(kg·K). */
    static final double R_VAPOUR = 461.5;
    private static final double C = 273.15;

    private Contrail() {}

    /** Engine mixing-line slope G, Pa/K, at pressure p, Pa. */
    public static double slope(Motor m, double p) {
        return m.water() * CP_AIR * p / (EPSILON * m.heat() * (1 - m.efficiency()));
    }

    /** Threshold temperature T_LM in saturated air, K (G ≤ 0.053 means no trail — 0). */
    public static double thresholdSaturated(double g) {
        if (g <= 0.053) return 0;
        double l = Math.log(g - 0.053);
        return C - 46.46 + 9.43 * l + 0.72 * l * l;
    }

    /** Threshold temperature T_LC at water-relative humidity u (0..1), K. */
    public static double threshold(double g, double u) {
        double tlm = thresholdSaturated(g);
        if (tlm <= 0 || u >= 1) return tlm;
        double hi = tlm, lo = tlm - 80, ew = waterSaturation(tlm);
        for (int i = 0; i < 60; i++) {
            double t = (hi + lo) / 2, f = ew - u * waterSaturation(t) - g * (tlm - t);
            // f rises with temperature: positive at T_LM, negative 80 K colder
            if (f > 0) hi = t;
            else lo = t;
        }
        return lo;
    }

    /** Whether an engine forms a trail in air at temperature t (K), pressure p (Pa), water-relative humidity u. */
    public static boolean forms(Motor m, double t, double p, double u) {
        return m.water() > 0 && m.heat() > 0 && t < threshold(slope(m, p), u);
    }

    /**
     * Water vapour deficit per cubic metre for ice saturation, kg/m³ (0 or below — supersaturated:
     * trail persists).
     */
    public static double iceDeficit(double t, double u) {
        double ei = iceSaturation(t), e = u * waterSaturation(t);
        return (ei - e) / (R_VAPOUR * t);
    }

    /**
     * Trail σ at ice sublimation: trail vapour λ_w (kg/m) over a Gaussian cross-section 2πσ² fills ambient air
     * deficit (kg/m³) — threshold σ = √(λ_w/(2π·deficit)); supersaturated air — infinity.
     */
    public static double dryAt(double water, double deficit) {
        return deficit <= 0 ? Double.POSITIVE_INFINITY : Math.sqrt(water / (2 * Math.PI * deficit));
    }

    /** Saturation pressure over water, Pa. */
    public static double waterSaturation(double t) {
        double c = t - C;
        return 611.2 * Math.exp(17.62 * c / (243.12 + c));
    }

    /** Saturation pressure over ice, Pa. */
    public static double iceSaturation(double t) {
        double c = t - C;
        return 611.2 * Math.exp(22.46 * c / (272.62 + c));
    }
}
