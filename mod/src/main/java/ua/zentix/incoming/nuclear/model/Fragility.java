package ua.zentix.incoming.nuclear.model;

/**
 * Damage curves: the fraction of places where exposure {@code x} caused a consequence is lognormal, as in damage tables
 * (variation in material, moisture, thickness): {@code P = Φ(ln(x/m)/β)}, {@code m} is the median (half the places), {@code β} is
 * the spread. Local randomness is a normal variable {@code z}: the consequence occurs where {@code ln(x/m) + β·z ≥ 0}. With
 * {@code z ~ N(0, 1)} this gives exactly the same fraction; if {@code z} is a smooth world field ({@link WorldNoise}), neighboring places
 * resolve together: a ragged, patchy zone edge rather than a threshold ring.
 */
public final class Fragility {
    private Fragility() {}

    /** Fraction of places with the consequence under exposure {@code x} (0 for {@code x ≤ 0}). */
    public static double probability(double x, double median, double beta) {
        if (x <= 0) return 0;
        return cdf(Math.log(x / median) / beta);
    }

    /**
     * Local randomness is truncated at ±{@code TAIL}: beyond 2.5σ the curve describes rare isolated
     * places far outside the zone (a scorched turf cell a kilometer from the rest), which do not occur, rather than material variation.
     */
    public static final double TAIL = 2.5;

    /** Consequence at a place with randomness {@code z}: {@code x·exp(β·z) ≥ m}, {@code z} no farther than {@link #TAIL}. */
    public static boolean hit(double x, double median, double beta, double z) {
        return x > 0 && Math.log(x / median) + beta * Math.max(-TAIL, Math.min(TAIL, z)) >= 0;
    }

    /**
     * Can the consequence occur anywhere ({@link #hit} at maximum randomness {@link #TAIL})? If not,
     * local randomness is unnecessary.
     */
    public static boolean possible(double x, double median, double beta) {
        return x > 0 && Math.log(x / median) + beta * TAIL >= 0;
    }

    /** Standard normal cumulative distribution Φ(z) (Abramowitz–Stegun 26.2.17, error < 7.5·10⁻⁸). */
    public static double cdf(double z) {
        if (z < -8) return 0;
        if (z > 8) return 1;
        double t = 1 / (1 + 0.2316419 * Math.abs(z));
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        double tail = Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI) * poly;
        return z >= 0 ? 1 - tail : tail;
    }

    /** Inverse of {@link #cdf}: z such that Φ(z) = u (Acklam approximation without refinement: z error up to ~10⁻⁵). */
    public static double inverse(double u) {
        if (u <= 0) return Double.NEGATIVE_INFINITY;
        if (u >= 1) return Double.POSITIVE_INFINITY;
        double lo = 0.02425, q, r;
        if (u < lo) {
            q = Math.sqrt(-2 * Math.log(u));
            return (((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5]) / ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1);
        }
        if (u > 1 - lo) {
            q = Math.sqrt(-2 * Math.log(1 - u));
            return -(((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5]) / ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1);
        }
        q = u - 0.5;
        r = q * q;
        return (((((A[0] * r + A[1]) * r + A[2]) * r + A[3]) * r + A[4]) * r + A[5]) * q
                / (((((B[0] * r + B[1]) * r + B[2]) * r + B[3]) * r + B[4]) * r + 1);
    }

    private static final double[] A = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02,
            -3.066479806614716e+01, 2.506628277459239e+00};
    private static final double[] B = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01,
            -1.328068155288572e+01};
    private static final double[] C = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549671010115819e+00,
            4.374664141464968e+00, 2.938163982699143e+00};
    private static final double[] D = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
}
