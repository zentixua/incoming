package ua.zentix.incoming.util;

/**
 * Pasquill surface-air stability (A–F, here 0..5 with fractional classes), wind profile and Briggs rural smoke dispersion —
 * one server/client function: world air ({@link Air}), smoke, blast clouds and flight derive class here.
 * <p>
 * Class from Pasquill table (Pasquill 1961; Turner 1964, “A diurnal stability classification scheme”): daytime by surface
 * wind (10 m) and sun (strong above 60°, moderate 35–60°, slight 15–35°); nighttime by wind and cloud cover (clear is
 * “≤ 3/8” column); overcast (rain, thunderstorm) gives D day and night. Table half-classes (A–B) use midpoint; smooth
 * between wind columns and sun elevations.
 * <p>
 * Dispersion (Briggs 1973 rural, via Hanna, Briggs, Hosker, “Handbook on Atmospheric Diffusion”, 1982), downwind path
 * x in m: σ_y = a·x·(1 + 0.0001x)^−½; σ_z: A 0.20x; B 0.12x; C 0.08x(1 + 0.0002x)^−½;
 * D 0.06x(1 + 0.0015x)^−½; E 0.03x(1 + 0.0003x)^−1; F 0.016x(1 + 0.0003x)^−1 (a: 0.22, 0.16, 0.11, 0.08, 0.06, 0.04).
 * Formulas are for near-ground smoke; aloft without ground, same time diffusion (x = u·t).
 */
public final class Stability {
    public static final double A = 0, B = 1, C = 2, D = 3, E = 4, F = 5;
    /** Standard-atmosphere surface Brunt–Väisälä frequency, 1/s: g = 9.80665, T = 288.15 K, c_p = 1005 J/(kg·K). */
    public static final double STANDARD_N = Math.sqrt(9.80665 / StandardAtmosphere.T0 * (-0.0065 + 9.80665 / 1005));
    private static final double[] SIGMA_Y = {0.22, 0.16, 0.11, 0.08, 0.06, 0.04};
    /** Surface wind — table column midpoints (&lt;2, 2–3, 3–5, 5–6, &gt;6 m/s). */
    private static final double[] WIND = {1, 2.5, 4, 5.5, 7};
    /** Daytime: strong, moderate, slight sun; nighttime: clear. */
    private static final double[] STRONG = {A, 0.5, B, C, C}, MODERATE = {0.5, B, 1.5, 2.5, D}, SLIGHT = {B, C, C, D, D},
            NIGHT = {F, F, E, D, D};
    /**
     * Brunt–Väisälä frequency, 1/s: daytime and neutral — ISO 2533 standard atmosphere, N² = g/T·(dT/dz + g/c_p), giving 0.0105
     * at −6.5 K/km near ground; nocturnal inversion 0.02–0.04 (⚠ estimates: E 0.02, F 0.03). No separate daytime mixing layer
     * (N ≈ 0): smoke rises no higher there than in standard atmosphere.
     */
    private static final double[] BRUNT = {STANDARD_N, STANDARD_N, STANDARD_N, STANDARD_N, 0.02, 0.03};
    /**
     * Wind power-profile exponent u(z) = u_ref·(z/z_ref)^p by class for open (rural) terrain: A 0.07, B 0.07, C 0.10,
     * D 0.15, E 0.35, F 0.55 — EPA recommended values (J. S. Irwin, “A theoretical variation of the wind profile power-law
     * exponent as a function of surface roughness and stability”, Atmos. Environ. 13, 1979; EPA, J. Tikvart, memo 29.10.1987,
     * https://gaftp.epa.gov/air/aqmg/SCRAM/mchisrs/R443_TIKVART_29_OCT_87.pdf). Measured in the surface layer (up to hundreds
     * of meters); {@link Wind} extends the profile to its 1 km reference height — approximation.
     */
    private static final double[] ALPHA = {0.07, 0.07, 0.10, 0.15, 0.35, 0.55};

    private Stability() {}

    /**
     * Class by surface wind u10 (m/s), sun elevation sine sun and overcast share overcast (0..1: rain, thunderstorm).
     */
    public static double of(double u10, double sun, double overcast) {
        double e = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, sun))));
        double night = column(NIGHT, u10), slight = column(SLIGHT, u10), moderate = column(MODERATE, u10), day;
        if (e <= 0) day = night;
        else if (e < 15) day = lerp(night, slight, e / 15);
        else if (e < 35) day = slight;
        else if (e < 47.5) day = lerp(slight, moderate, (e - 35) / 12.5);
        else if (e < 60) day = lerp(moderate, column(STRONG, u10), (e - 47.5) / 12.5);
        else day = column(STRONG, u10);
        return lerp(day, D, Math.max(0, Math.min(1, overcast)));
    }

    /** σ_y over x meters downwind in class c, m (linear between classes, as coefficient a). */
    public static double sigmaY(double c, double x) {
        return between(SIGMA_Y, c) * x / Math.sqrt(1 + 0.0001 * x);
    }

    /** σ_z over x meters downwind in class c, m. */
    public static double sigmaZ(double c, double x) {
        c = Math.max(A, Math.min(F, c));
        int k = (int) Math.min(4, Math.floor(c));
        double t = c - k, lo = sigmaZ(k, x);
        return t == 0 ? lo : lo + (sigmaZ(k + 1, x) - lo) * t;
    }

    /** Briggs σ_z for class k. */
    private static double sigmaZ(int k, double x) {
        return switch (k) {
            case 0 -> 0.20 * x;
            case 1 -> 0.12 * x;
            case 2 -> 0.08 * x / Math.sqrt(1 + 0.0002 * x);
            case 3 -> 0.06 * x / Math.sqrt(1 + 0.0015 * x);
            case 4 -> 0.03 * x / (1 + 0.0003 * x);
            default -> 0.016 * x / (1 + 0.0003 * x);
        };
    }

    /** Brunt–Väisälä frequency in class c, 1/s. */
    public static double brunt(double c) {
        return between(BRUNT, c);
    }

    /** Wind power-profile exponent in class c. */
    public static double alpha(double c) {
        return between(ALPHA, c);
    }

    /** Row value by class c (linear between classes). */
    private static double between(double[] row, double c) {
        c = Math.max(A, Math.min(F, c));
        int k = (int) Math.min(4, Math.floor(c));
        return row[k] + (row[k + 1] - row[k]) * (c - k);
    }

    private static double column(double[] row, double u) {
        if (u <= WIND[0]) return row[0];
        for (int i = 1; i < WIND.length; i++) if (u <= WIND[i]) return lerp(row[i - 1], row[i], (u - WIND[i - 1]) / (WIND[i] - WIND[i - 1]));
        return row[WIND.length - 1];
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
