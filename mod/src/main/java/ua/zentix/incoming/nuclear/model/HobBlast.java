package ua.zentix.incoming.nuclear.model;

/**
 * Ground-level nuclear blast by ground range and burst height above the point, meters; yield in kilotons.
 * Everything scales by ∛W: one table for all yields.
 * <ul>
 * <li>Pressure — Brode equation 62 (H. L. Brode, “Airblast from Nuclear Bursts — Analytic Approximations”, PSR 1419-3,
 * 1987, pp. 60, 70–71; constant in f is 17.15, as in the paper): reproduces Glasstone &amp; Dolan height-of-burst curves
 * (fig. 3.73) within 10 %. Fit valid down to 2 psi: beyond that, an airburst tends to zero and below (15 kt at 592 m —
 * beyond 15 km). Below 1 psi — DNA standard (Needham &amp; Crepeau 1981: 1 kt free air, Mach reflection as double-yield
 * free air, regular reflection with Rankine–Hugoniot factor; formulas from E. Geist's glasstone, MIT): weak-wave pressure
 * decays as 1/(R·√ln R). Smooth in log pressure between 2 and 1 psi; they differ by 3–8 % there. Closer than 2 psi, DNA is
 * invalid (hundreds of psi instead of 28 at an airburst epicenter).</li>
 * <li>A point above ground ({@code Detonation.overpressureKpa}) is treated as a ground point with burst height reduced
 * by its altitude; above the burst, a surface-burst hemisphere. Upper estimate, not data: high above ground the incident
 * wave arrives, reflection later and separately; they merge (Mach stem) only near ground below the triple point
 * (tens–hundreds of meters; its height is not modeled). Above an airburst — 1.4 times incident at 2–5 psi, up to 1.9 times
 * at hundreds of psi; a craft 300 m above ground at 1 and 1.5 km from 15 kt at 592 m — 8.3 and 4.1 psi versus incident
 * 4.0 and 2.3.</li>
 * <li>Ground front arrival and positive phase — Brode's PT subroutine (ibid., app. B, pp. 179–180), Mach region onset —
 * his XM; phase below 1 psi — Kinney–Graham (Brode approaches a constant; a weak wave's phase grows).</li>
 * <li>Ground-aligned dynamic pressure: in the Mach region post-front air follows the ground (Rankine–Hugoniot from
 * pressure); in regular reflection it is inclined, leaving cos²α along the ground (α — burst elevation angle; as DNA,
 * QAIR). Transition from 0.8·XM to XM. Checked against G&amp;D fig. 3.75 (15 kt at 592 m: 1 psi at 170 m, 2 psi at 450 m,
 * ~4 psi at XM).</li>
 * </ul>
 * Pressure is tabulated by scaled range (logarithmic) and burst angle ({@value #NR} × {@value #NT}, log pressure):
 * less than 0.6 % difference from the exact formula over 0.05–3000 psi.
 */
public final class HobBlast {
    /** Foot, m. */
    private static final double FT = 0.3048;
    /** psi in Pa. */
    private static final double PA_PER_PSI = 1000 * BlastModel.KPA_PER_PSI;
    /** Above this many psi, Brode fit; below {@link #DNA_BELOW}, DNA standard. */
    static final double BRODE_ABOVE = 2, DNA_BELOW = 1;
    /** Table: nodes in log scaled range (kft/kt^⅓, {@link #R_MIN} to {@link #R_MAX}) and angle 0..90°. */
    static final int NR = 384, NT = 91;
    static final double R_MIN = 1e-3, R_MAX = 1e3;
    private static final double LN_MIN = Math.log(R_MIN), LN_SPAN = Math.log(R_MAX) - LN_MIN;

    private HobBlast() {
    }

    /** Ground pressure, psi: ground range and burst height above the point in m. */
    public static double psi(double groundM, double heightM, double yieldKt) {
        double m = scale(yieldKt);
        return Table.psi(Math.max(0, groundM) / m, Math.max(0, heightM) / m);
    }

    /** Ground range where pressure falls to {@code psi} (0 — not reached even directly beneath the burst), m. */
    public static double rangeFor(double psi, double heightM, double yieldKt) {
        if (psi >= psi(0, heightM, yieldKt)) return 0;
        return Solve.decreasingRoot(g -> psi(g, heightM, yieldKt), psi, 1e-3, 1e8);
    }

    /** Build pressure table now (~25 ms, once per run): otherwise the first detonation waits on its own thread. */
    public static void warm() {
        Table.psi(1, 1);
    }

    /** Length scale: divide meters by this for kft per kt^⅓. */
    private static double scale(double yieldKt) {
        return 1000 * FT * Math.cbrt(Math.max(1e-9, yieldKt));
    }

    /** Exact pressure (without table), psi, from scaled range and height, kft/kt^⅓. */
    static double exact(double x, double y) {
        double b = brode(x, y);
        if (b >= BRODE_ABOVE) return b;
        double d = dna(x, y);
        if (b <= DNA_BELOW) return d;
        double lb = Math.log(BRODE_ABOVE), ld = Math.log(DNA_BELOW), f = (Math.log(b) - ld) / (lb - ld), w = f * f * (3 - 2 * f);
        return Math.exp(w * Math.log(b) + (1 - w) * Math.log(d));
    }

    /** Brode equation 62: ground pressure, psi; x, y — kft/kt^⅓. */
    static double brode(double x, double y) {
        x = Math.max(x, 1e-9);
        y = Math.max(y, 1e-9);
        double r = Math.hypot(x, y), z = Math.min(y / x, 100);
        double z2 = z * z, z5 = Math.pow(z, 5), z17 = Math.pow(z, 17), z18 = z17 * z;
        double a = 1.22 - 3.908 * z2 / (1 + 810.2 * z5);
        double b = 2.321 + 6.195 * z18 / (1 + 1.113 * z18) - 0.03831 * z17 / (1 + 0.02415 * z17) + 0.6692 / (1 + 4164 * Math.pow(z, 8));
        double c = 4.153 - 1.149 * z18 / (1 + 1.641 * z18) - 1.1 / (1 + 2.771 * Math.pow(z, 2.5));
        double d = -4.166 + 25.76 * Math.pow(z, 1.75) / (1 + 1.382 * z18) + 8.257 * z / (1 + 3.219 * z);
        double e = 1 - 0.004642 * z18 / (1 + 0.003886 * z18);
        double f = 0.6096 + 2.879 * Math.pow(z, 9.25) / (1 + 2.359 * Math.pow(z, 14.5)) - 17.15 * z2 / (1 + 71.66 * z2 * z);
        double g = 1.83 + 5.361 * z2 / (1 + 0.3139 * Math.pow(z, 6));
        double h = 8.808 * Math.pow(z, 1.5) / (1 + 154.5 * Math.pow(z, 3.5)) - (0.2905 + 64.67 * z5) / (1 + 441.5 * z5)
                - 1.389 * z / (1 + 49.03 * z5) + 1.094 * r * r / ((781.2 - 123.4 * r + 37.98 * Math.pow(r, 1.5) + r * r) * (1 + 2 * y));
        double j = 0.000629 * Math.pow(y, 4) / (3.493e-9 + Math.pow(y, 4)) - 2.67 * y * y / (1 + 1e7 * Math.pow(y, 4.3));
        double k = 5.18 + 0.2803 * Math.pow(y, 3.5) / (3.788e-6 + Math.pow(y, 4));
        return 10.47 / Math.pow(r, a) + b / Math.pow(r, c) + d * e / (1 + f * Math.pow(r, g)) + h + j / Math.pow(r, k);
    }

    // ---------------------------------------------------------------- DNA standard (far zone)

    /** DNA standard pressure, psi; x, y — kft/kt^⅓. */
    static double dna(double x, double y) {
        double sgr = Math.max(x * 1000 * FT, 1e-6), shob = y * 1000 * FT;
        double alpha = Math.atan2(shob, sgr), free = freeAirPa(Math.hypot(sgr, shob));
        double t = 340 / Math.pow(free, 0.55);
        double merge = Math.atan(1 / (t + 1 / (7782 * Math.pow(free, 0.7) + 0.9)));
        double width = Math.atan(1 / (t + 1 / (647 * Math.pow(free, 0.8) + 1 / (7473 * Math.sqrt(free) + 6.6))));
        double s = Math.max(-1, Math.min(1, (alpha - merge) / width)), sigma = 0.5 * (Math.sin(0.5 * Math.PI * s) + 1);
        double pa;
        if (sigma >= 1) pa = regular(free, alpha);
        else if (sigma <= 0) pa = mach(sgr, alpha);
        else pa = regular(free, alpha) * sigma + mach(sgr, alpha) * (1 - sigma);
        return pa / PA_PER_PSI;
    }

    /**
     * Incident free-air wave (DNA standard, ∛W scaling), psi, at slant range {@code rangeM} m from {@code yieldKt} kt burst:
     * front pressure where ground reflection has not arrived yet.
     */
    public static double freeAirPsi(double rangeM, double yieldKt) {
        return freeAirPa(Math.max(rangeM, 1e-6) / Math.cbrt(Math.max(1e-9, yieldKt))) / PA_PER_PSI;
    }

    /** DNA standard for 1 kt free air, Pa, at range {@code r} m. */
    static double freeAirPa(double r) {
        double q = r / 445.42;
        return 3.04e11 / (r * r * r) + 1.13e9 / (r * r) + 7.9e6 / (r * Math.sqrt(Math.log(q + 3 * Math.exp(-Math.sqrt(q) / 3))));
    }

    /** Mach reflection: double-yield free air at ground range (1 kt), enhanced at shallow angles. */
    private static double mach(double sgr, double alpha) {
        double ln = Math.log(sgr), c = Math.max(Math.min(3.7 - 0.94 * ln, 0.7), 0.77 * ln - 3.8 - 18 / sgr);
        return freeAirPa(sgr / Math.cbrt(2)) / (1 - c * Math.sin(alpha));
    }

    /** Regular reflection: incident wave × angle-dependent Rankine–Hugoniot reflection factor. */
    private static double regular(double free, double alpha) {
        double rn = 2 + (shockGamma(free) + 1) * (densityRatio(free) - 1) / 2;
        double f = free / 75842, f6 = Math.pow(f, 6), d = f6 * (1.2 + 0.07 * Math.sqrt(f)) / (f6 + 1);
        return free * ((rn - 2) * Math.pow(Math.sin(alpha), d) + 2);
    }

    /** Air adiabatic index behind a front of pressure {@code op} Pa (dissociation in strong shocks). */
    private static double shockGamma(double op) {
        double xi = op / 101325 + 1, t = 1e-12 * Math.pow(xi, 6), z = Math.log(xi) - 0.47 * t / (100 + t);
        return 1.402 - 3.4e-4 * Math.pow(z, 4) / (1 + 2.22e-5 * Math.pow(z, 6));
    }

    /**
     * Air compression behind a front of pressure {@code op} Pa: Rankine–Hugoniot with pressure-dependent adiabatic index
     * ({@link #shockGamma}; weak wave γ = 1.4 and ρ/ρ₀ = (7·P0 + 6P)/(7·P0 + P): 1 psi — 1.05, 5 psi — 1.23, 10 psi — 1.44).
     */
    public static double densityRatio(double op) {
        double xi = op / 101325 + 1, g = shockGamma(op), mu = (g + 1) / (g - 1);
        return (1 + mu * xi) / (5.975 + xi);
    }

    // ---------------------------------------------------------------- arrival, phase, dynamic pressure (Brode, PT)

    /** Mach region ground onset, m: 694 m for 15 kt at 592 m, 0 for surface burst. */
    public static double machStem(double heightM, double yieldKt) {
        double m = Math.cbrt(Math.max(1e-9, yieldKt)), y = Math.max(0, heightM) / FT / m;
        return (170 * y / (1 + 60 * Math.pow(y, 0.25)) + 2.89 * Math.pow(y / 100, 2.5)) * FT * m;
    }

    /** Front arrival at ground, s: 0.79 s beneath 15 kt at 592 m, 2.17 s at 1 km, 27.5 s at 10 km. */
    public static double arrivalSeconds(double groundM, double heightM, double yieldKt) {
        double m = Math.cbrt(Math.max(1e-9, yieldKt));
        return tau(Math.max(0, groundM) / FT / m, Math.max(0, heightM) / FT / m) * m / 1000;
    }

    /** Brode, PT: arrival, ms/kt^⅓; X, Y — ft/kt^⅓. */
    private static double tau(double x, double y) {
        double r = Math.hypot(x, y) / 1000, r2 = r * r, r8 = Math.pow(r, 8);
        double u = (0.543 - 21.8 * r + 386 * r2 + 2383 * r2 * r) * r8
                / (2.99e-14 - 1.91e-10 * r2 + 1.032e-6 * r2 * r2 - 4.43e-6 * r2 * r2 * r2 + (1.028 + 2.087 * r + 2.69 * r2) * r8);
        double xm = 170 * y / (1 + 60 * Math.pow(y, 0.25)) + 2.89 * Math.pow(y / 100, 2.5);
        if (x <= xm) return u;
        double w = (1.086 - 34.605 * r + 486.3 * r2 + 2383 * r2 * r) * r8
                / (3.0137e-13 - 1.2128e-9 * r2 + 4.128e-6 * r2 * r2 - 1.116e-5 * r2 * r2 * r2 + (1.632 + 2.629 * r + 2.69 * r2) * r8);
        return u * xm / x + w * (1 - xm / x);
    }

    /**
     * Free-air front arrival (DNA), s, at range {@code rangeM} m: beneath the burst, same as Brode at ground below it
     * (15 kt at 592 m — 0.79 s).
     */
    public static double freeAirArrivalSeconds(double rangeM, double yieldKt) {
        double m = Math.cbrt(Math.max(1e-9, yieldKt)), r = Math.max(0, rangeM) / m;
        return r * r * (6.7 + r) / (7.12e6 + 7.32e4 * r + 340.5 * r * r) * m;
    }

    /**
     * Ground positive phase, s. Where Brode pressure is at least 2 psi, Brode PT subroutine (0.45 s beneath 15 kt at 592 m,
     * 0.74 s at 1 km): it tends to a constant for a weak wave (1.2 s), while a real phase grows with range, so below 1 psi
     * use Kinney–Graham at the listener ({@link BlastModel#positivePhaseSeconds(double, double)}, checked against Garcés
     * tables and ANSI S2.20); smooth log transition between them, as pressure ({@link #exact}). For 15 kt at 592 m:
     * 0.93 s at 2 km, 1.29 s at 5 km, 1.44 s at 10 km.
     */
    public static double positivePhaseSeconds(double groundM, double heightM, double yieldKt) {
        double m = Math.cbrt(Math.max(1e-9, yieldKt)), x = Math.max(0, groundM) / FT / m, y = Math.max(0, heightM) / FT / m;
        double b = brode(x / 1000, y / 1000), near = brodePhase(x, y) * m;
        if (b >= BRODE_ABOVE) return near;
        double far = BlastModel.positivePhaseSeconds(Math.hypot(groundM, heightM), yieldKt);
        if (b <= DNA_BELOW) return far;
        double lb = Math.log(BRODE_ABOVE), ld = Math.log(DNA_BELOW), f = (Math.log(b) - ld) / (lb - ld), w = f * f * (3 - 2 * f);
        return Math.exp(w * Math.log(near) + (1 - w) * Math.log(far));
    }

    /** Brode positive phase (PT), s per kt^⅓; x, y — ft/kt^⅓. */
    private static double brodePhase(double x, double y) {
        double t = tau(x, y), y100 = Math.pow(y / 100, 3.5);
        double s2 = 1 - 15.18 * y100 / (1 + 15.18 * y100)
                - 0.02441 * Math.pow(y / 1e6, 2) / (1 + 9000 * Math.pow(y / 100, 7)) * (1e10 / (0.441 + Math.pow(x / 100, 10)));
        double t15 = Math.pow(t, 1.5);
        double d = (1640700 + 24629 * t + 416.15 * t * t) / (10880 + 619.76 * t + t * t)
                * (0.4 + 0.001204 * t15 / (1 + 0.001559 * t15)
                + (0.6126 + 0.5486 * Math.pow(t, 0.25) / (1 + 0.00357 * t15) - 3.47 * Math.pow(t, 0.637) / (1 + 5.696 * Math.pow(t, 0.645))) * s2);
        return d / 1000;
    }

    /**
     * Share of post-front dynamic pressure along the ground: 1 in the Mach region, cos²α for regular reflection,
     * transition from 0.8·XM to XM. Ground wind is Rankine–Hugoniot wind × its square root.
     */
    public static double alongGround(double groundM, double heightM, double yieldKt) {
        double xm = machStem(heightM, yieldKt);
        if (heightM <= 0 || groundM >= xm) return 1;
        double f = Math.max(0, (groundM - 0.8 * xm) / (0.2 * xm)), mach = f * f * (3 - 2 * f);
        double sin2 = heightM * heightM / (heightM * heightM + groundM * groundM);
        return 1 - (1 - mach) * sin2;
    }

    // ---------------------------------------------------------------- table

    /** Pressure table by scaled range and burst elevation angle: built on first access. */
    private static final class Table {
        /** Log pressure, psi: [range][angle]. */
        private static final double[] LN = build();

        private static double[] build() {
            double[] t = new double[NR * NT];
            for (int i = 0; i < NR; i++) {
                double r = Math.exp(LN_MIN + LN_SPAN * i / (NR - 1));
                for (int j = 0; j < NT; j++) {
                    double a = 0.5 * Math.PI * j / (NT - 1);
                    t[i * NT + j] = Math.log(Math.max(exact(r * Math.cos(a), r * Math.sin(a)), 1e-12));
                }
            }
            return t;
        }

        /** Pressure, psi, from scaled ground range and height, kft/kt^⅓. */
        static double psi(double x, double y) {
            double r = Math.hypot(x, y);
            // beyond the table — weak-wave behavior, 1/R
            double far = r > R_MAX ? R_MAX / r : 1;
            double fi = (Math.log(Math.max(Math.min(r, R_MAX), R_MIN)) - LN_MIN) / LN_SPAN * (NR - 1);
            double fj = (r <= 0 ? 0 : Math.atan2(y, x)) / (0.5 * Math.PI) * (NT - 1);
            int i = Math.min((int) fi, NR - 2), j = Math.min((int) fj, NT - 2);
            double a = fi - i, b = fj - j;
            int k = i * NT + j;
            double v = (LN[k] * (1 - a) + LN[k + NT] * a) * (1 - b) + (LN[k + 1] * (1 - a) + LN[k + NT + 1] * a) * b;
            return Math.exp(v) * far;
        }
    }
}
