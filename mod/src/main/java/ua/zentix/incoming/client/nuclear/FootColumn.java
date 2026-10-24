package ua.zentix.incoming.client.nuclear;

/**
 * Vertical optical depth of the lifted carpet, without its radial wall weight. The density is exactly
 * {@link MushroomField#footDensity}: rational dilution, a linear arrival front and the original carpet subtracted.
 * Positive intervals have a closed primitive (atan/log and cubic × exponential); only their zero crossings need
 * bisection. Build once per mushroom/frame, pack 64 heights in its existing texture, never integrate in a ray step.
 */
final class FootColumn {
    static final int NODES = 64;
    private final double top, sigma, rise, end;
    private final double[] intervals = new double[16];
    private int count;

    FootColumn(double top, double sigma, double share, double rise, double merge) {
        this.top = Math.max(top, 1);
        this.sigma = share > 0 ? Math.max(sigma, 0) : 0;
        this.rise = Math.max(rise, 0) / this.top;
        end = Math.max(0, Math.min(merge / this.top, this.rise));
        if (this.sigma <= 0 || end <= 0) return;
        if (this.rise >= 2) {
            // The complete arrival front is above the carpet; both-profile is nonnegative everywhere.
            intervals[count++] = 0;
            intervals[count++] = end;
            return;
        }
        // Only 0..1 can have zero crossings: the carpet vanishes at 1. This is setup on the CPU, not shader work.
        double limit = Math.min(end, 1), a = 0, fa = difference(a), start = fa > 0 ? 0 : -1;
        for (int i = 1; i <= 512; i++) {
            double b = limit * i / 512, fb = difference(b);
            if ((fa > 0) != (fb > 0)) {
                double lo = a, hi = b;
                for (int j = 0; j < 40; j++) {
                    double mid = (lo + hi) * 0.5;
                    if ((difference(mid) > 0) == (fa > 0)) lo = mid;
                    else hi = mid;
                }
                double root = (lo + hi) * 0.5;
                if (fb > 0) start = root;
                else { intervals[count++] = start; intervals[count++] = root; start = -1; }
            }
            a = b;
            fa = fb;
        }
        if (end > 1 && start < 0) start = 1;
        if (start >= 0) { intervals[count++] = start; intervals[count++] = end; }
    }

    private double difference(double x) {
        return Math.max(0, Math.min(1, rise - x)) / (1 + x * x) - PallField.profile(x);
    }

    /** Optical depth from h up to merge, bounded by the arrival front. */
    double above(double h) {
        double a = Math.max(h / top, 0), sum = 0;
        for (int i = 0; i < count; i += 2) {
            double lo = Math.max(a, intervals[i]), hi = intervals[i + 1];
            if (hi > lo) sum += primitive(hi) - primitive(lo);
        }
        return sigma * top * Math.max(sum, 0);
    }

    private double primitive(double x) {
        double full = Math.max(rise - 1, 0), split = Math.min(x, full);
        double both = Math.atan(split);
        if (x > full) both += rise * (Math.atan(x) - Math.atan(full)) - 0.5 * Math.log((1 + x * x) / (1 + full * full));
        return both - profilePrimitive(x);
    }

    /** Exact primitive of PallField.profile on x >= 0; its fitted column is not exact enough for subtraction. */
    private static double profilePrimitive(double x) {
        double a = 0.45, b = Math.min(x, a);
        double low = 0.55 * b - 0.18 * Math.exp(-2.5 * b);
        if (x <= a) return low;
        return low + envelopePrimitive(Math.min(x, 1)) - envelopePrimitive(a);
    }

    private static double envelopePrimitive(double x) {
        double w = 0.55, z = x - 0.45, u = z / w, k = 2.5;
        double e = 1 - 3 * u * u + 2 * u * u * u;
        double d1 = (-6 * u + 6 * u * u) / w, d2 = (-6 + 12 * u) / (w * w), d3 = 12 / (w * w * w);
        double polynomial = x - z * z * z / (w * w) + 0.5 * z * z * z * z / (w * w * w);
        return 0.55 * polynomial - 0.45 * Math.exp(-k * x) * (e / k + d1 / (k * k) + d2 / (k * k * k) + d3 / (k * k * k * k));
    }

    void pack(double merge, float[] into, int offset) {
        for (int i = 0; i < NODES; i++) into[offset + i] = (float) above(merge * i / (NODES - 1));
    }
}
