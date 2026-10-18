package ua.zentix.incoming.nuclear.model;

import java.util.function.DoubleUnaryOperator;

/** Общие численные помощники модели. */
final class Solve {
    private Solve() {
    }

    /**
     * Дальность, на которой убывающая функция {@code f} падает до {@code target}: деление пополам
     * в логарифмической шкале на [lo, hi]. Если уже в {@code lo} значение не больше цели — {@code lo},
     * если и в {@code hi} больше — {@code hi}.
     */
    static double decreasingRoot(DoubleUnaryOperator f, double target, double lo, double hi) {
        return decreasingRoot(f, target, lo, hi, 0);
    }

    /**
     * {@link #decreasingRoot(DoubleUnaryOperator, double, double, double)} до точности {@code tol} (в единицах аргумента):
     * деление кончается, когда отрезок короче, — у дорогой {@code f} вместо сотни вызовов два десятка.
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
