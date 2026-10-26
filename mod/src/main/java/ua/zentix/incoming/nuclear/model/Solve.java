package ua.zentix.incoming.nuclear.model;

import java.util.function.DoubleUnaryOperator;

/** Shared numerical model helpers. */
final class Solve {
    private Solve() {
    }

    /**
     * Range where decreasing {@code f} falls to {@code target}: bisection on a logarithmic scale over [lo, hi].
     * If already no greater than target at {@code lo}, return {@code lo}; if still greater at {@code hi}, return {@code hi}.
     */
    static double decreasingRoot(DoubleUnaryOperator f, double target, double lo, double hi) {
        return decreasingRoot(f, target, lo, hi, 0);
    }

    /**
     * {@link #decreasingRoot(DoubleUnaryOperator, double, double, double)} to precision {@code tol} (argument units):
     * stop when the interval is shorter — for expensive {@code f}, a couple dozen calls instead of a hundred.
     */
    static double decreasingRoot(DoubleUnaryOperator f, double target, double lo, double hi, double tol) {
        if (f.applyAsDouble(lo) <= target) return lo;
        if (f.applyAsDouble(hi) > target) return hi;
        for (int i = 0; i < 100 && hi - lo > tol; i++) {
            double m = Math.sqrt(lo * hi);
            if (f.applyAsDouble(m) > target) lo = m;
            else hi = m;
        }
        return Math.sqrt(lo * hi);
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
