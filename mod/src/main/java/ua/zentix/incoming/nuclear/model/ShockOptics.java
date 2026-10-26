package ua.zentix.incoming.nuclear.model;

/**
 * Light through a shock wave, without Minecraft (meters). Post-front air is compressed; its refractive index grows
 * with density: a ray nearly tangent to the front sphere bends toward its center, shifting what lies behind the front
 * in a thin ring along its edge — as seen against sky and ground in test footage, and detected by shadowgraphy
 * (Settles G. S., “Schlieren and Shadowgraph Techniques”, 2001, ch. 1–2: a shock wave is an index discontinuity).
 * <ul>
 * <li><b>Jump.</b> Post-front compression is Rankine–Hugoniot ({@link HobBlast#densityRatio}); n − 1 ∝ density
 * (Gladstone–Dale), so δn = (n₀ − 1)·(ρ/ρ₀ − 1), with n₀ − 1 for pre-front air.</li>
 * <li><b>Shell.</b> Compression decays behind the front toward positive-phase end; ⚠ approximation: an exponential with
 * e-folding length {@link #DECAY_SHARE} of phase length (Friedlander with b = 1 falls to 1/e at 0.43 phase; stronger
 * blasts have larger b and thinner shells).</li>
 * <li><b>Path.</b> Extra optical path for a ray of impact parameter b through a thin shell (L ≪ R), per intersection:
 * ∫δn·ds = δn·√(2RL)·F(a), a² = (R² − b²)/(2RL), F is Dawson's function (Abel integral of the exponential with
 * r² − b² ≈ R² − b² − 2R(R − r)). Near tangency, as for a step, 2δn·√(2R(R − b)) for two intersections; away from it,
 * δn·L/cos incidence angle.</li>
 * <li><b>Deflection</b> toward center is the path derivative by b. At tangency a discontinuity makes it infinite, so
 * average over pixel width w at the front intersection (what the pixel sees): edge path difference divided by w.
 * Capped at √(2δn) per intersection — maximum ray turn at grazing incidence on the jump (Snell's law).</li>
 * </ul>
 * Shader law copy: {@code front*} in {@code fx_heat.fsh}, checked by {@code ShockOpticsGlslTest}.
 */
public final class ShockOptics {
    /** Post-front compression e-folding length as this fraction of positive-phase length (⚠ Friedlander-based estimate). */
    public static final double DECAY_SHARE = 0.43;

    private ShockOptics() {}

    /** Index jump at a front with overpressure {@code kpa}: n0 is pre-front air n − 1. */
    public static double indexJump(double kpa, double n0) {
        return n0 * (HobBlast.densityRatio(1000 * Math.max(0, kpa)) - 1);
    }

    /**
     * Extra optical path per shell crossing, m: impact parameter b, front radius r, e-folding thickness l, jump dn.
     * Beyond the front (b ≥ r) — 0.
     */
    public static double path(double b, double r, double l, double dn) {
        double rl = 2 * r * l;
        double a2 = Math.max((r - b) * (r + b), 0) / rl;
        return dn * Math.sqrt(rl) * dawson(Math.sqrt(a2));
    }

    /**
     * Ray deflection per crossing, averaged over pixel width {@code w} (m across the ray there), rad: positive toward
     * sphere center. Magnitude capped at √(2·dn).
     */
    public static double bend(double b, double r, double l, double dn, double w) {
        double lo = Math.max(b - 0.5 * w, 0), hi = b + 0.5 * w;
        double bend = (path(lo, r, l, dn) - path(hi, r, l, dn)) / Math.max(hi - lo, 1e-6);
        double most = Math.sqrt(2 * dn);
        return Math.max(-most, Math.min(most, bend));
    }

    /**
     * Maximum deflection of an eye ray {@code near} m from the sphere (nearest intersection), pixel {@code pixel} rad,
     * for both intersections: upper estimate (tangent, pixel narrower than shell).
     */
    public static double most(double r, double dn, double near, double pixel) {
        double w = Math.max(pixel * Math.max(near, 1e-3), 1e-6);
        return 2 * Math.min(Math.sqrt(2 * dn), dn * Math.sqrt(2 * r / w));
    }

    /**
     * Dawson function F(x) = e^(−x²)·∫₀ˣ e^(t²) dt: rational approximation degree 11/12 (coefficients from English Wikipedia's
     * “Dawson function” article; error against {@code scipy.special.dawsn} over 0–30 at most 1.1·10⁻⁵, against direct integral —
     * {@code ShockOpticsTest}); beyond 10, asymptote 1/(2x)·(1 + 1/(2x²) + 3/(4x⁴)) (error there under 2·10⁻⁶).
     */
    public static double dawson(double x) {
        if (x > 10) return 0.5 / x * (1 + 0.5 / (x * x) + 0.75 / (x * x * x * x));
        double y = x * x;
        double p = 1 + y * (0.1049934947 + y * (0.0424060604 + y * (0.0072644182 + y * (0.0005064034 + y * 0.0001789971))));
        double q = 1 + y * (0.7715471019 + y * (0.2909738639 + y * (0.0694555761 + y * (0.0140005442 + y * (0.0008327945 + y * 0.0003579942)))));
        return x * p / q;
    }
}
