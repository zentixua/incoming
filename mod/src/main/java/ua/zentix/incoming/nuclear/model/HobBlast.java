package ua.zentix.incoming.nuclear.model;

/**
 * Ударная волна ядерного подрыва у земли — по дальности по земле и высоте подрыва над точкой, метры; мощность — килотонны.
 * Всё подобно по ∛W: одна таблица на все мощности.
 * <ul>
 * <li>Давление — уравнение 62 Броуда (H. L. Brode, «Airblast from Nuclear Bursts — Analytic Approximations», PSR 1419-3,
 * 1987, с. 60, 70–71; постоянная в f — 17.15, как в статье): кривые высоты подрыва Glasstone &amp; Dolan (рис. 3.73) оно
 * повторяет в пределах 10 %. Подгонка верна, пока давление не ниже 2 psi: дальше у воздушного подрыва она уходит в ноль
 * и ниже (у 15 кт на 592 м — за 15 км). Ниже 1 psi — стандарт DNA (Needham &amp; Crepeau 1981: свободный воздух 1 кт,
 * отражение по Маху — свободный воздух удвоенной мощности, у правильного отражения — множитель Ренкина–Гюгонио; формулы
 * — по glasstone Э. Гайста, MIT): у слабой волны давление спадает как 1/(R·√ln R). Между 2 и 1 psi — плавно в логарифме;
 * там они расходятся на 3–8 %. Ближе 2 psi DNA неверен (у воздушного подрыва у эпицентра — сотни psi вместо 28).</li>
 * <li>Над подрывом (точка выше него) — как у земли на той же дальности, полусфера наземного: у аппаратов над воздушным
 * подрывом давление завышено (до ~40 %: туда приходит падающая волна без отражения). Оценка, а не данные.</li>
 * <li>Приход фронта и положительная фаза на земле — подпрограмма PT Броуда (там же, прил. B, с. 179–180), начало области
 * Маха — его же XM.</li>
 * <li>Напор вдоль земли: в области Маха воздух за фронтом идёт по земле (Ренкин–Гюгонио по давлению), у правильного
 * отражения — под углом к ней, и вдоль земли остаётся доля cos²α (α — угол на подрыв над горизонтом; так у DNA, QAIR).
 * Переход — от 0.8·XM до XM. Сверено с G&amp;D рис. 3.75 (15 кт на 592 м: 1 psi на 170 м, 2 psi на 450 м, ~4 psi у XM).</li>
 * </ul>
 * Давление считается таблицей по масштабной дальности (логарифм) и углу на подрыв ({@value #NR} × {@value #NT},
 * в логарифме давления): от точной формулы — меньше 0,5 % от 0,05 до 3000 psi.
 */
public final class HobBlast {
    /** Фут, м. */
    private static final double FT = 0.3048;
    /** psi в Па. */
    private static final double PA_PER_PSI = 1000 * BlastModel.KPA_PER_PSI;
    /** Выше стольких psi — подгонка Броуда, ниже {@link #DNA_BELOW} — стандарт DNA. */
    static final double BRODE_ABOVE = 2, DNA_BELOW = 1;
    /** Таблица: узлов по логарифму масштабной дальности (кфут/кт^⅓, от {@link #R_MIN} до {@link #R_MAX}) и по углу 0..90°. */
    static final int NR = 384, NT = 91;
    static final double R_MIN = 1e-3, R_MAX = 1e3;
    private static final double LN_MIN = Math.log(R_MIN), LN_SPAN = Math.log(R_MAX) - LN_MIN;

    private HobBlast() {
    }

    /** Давление на земле, psi: дальность по земле и высота подрыва над точкой — м. */
    public static double psi(double groundM, double heightM, double yieldKt) {
        double m = scale(yieldKt);
        return Table.psi(Math.max(0, groundM) / m, Math.max(0, heightM) / m);
    }

    /** Дальность по земле, где давление падает до {@code psi} (0 — такого нет и под самым подрывом), м. */
    public static double rangeFor(double psi, double heightM, double yieldKt) {
        if (psi >= psi(0, heightM, yieldKt)) return 0;
        return Solve.decreasingRoot(g -> psi(g, heightM, yieldKt), psi, 1e-3, 1e8);
    }

    /** Масштаб длины: кфут на кт^⅓ из метров — делить на это. */
    private static double scale(double yieldKt) {
        return 1000 * FT * Math.cbrt(Math.max(1e-9, yieldKt));
    }

    /** Точное давление (без таблицы), psi, по масштабным дальности и высоте, кфут/кт^⅓. */
    static double exact(double x, double y) {
        double b = brode(x, y);
        if (b >= BRODE_ABOVE) return b;
        double d = dna(x, y);
        if (b <= DNA_BELOW) return d;
        double lb = Math.log(BRODE_ABOVE), ld = Math.log(DNA_BELOW), f = (Math.log(b) - ld) / (lb - ld), w = f * f * (3 - 2 * f);
        return Math.exp(w * Math.log(b) + (1 - w) * Math.log(d));
    }

    /** Уравнение 62 Броуда: давление на земле, psi; x, y — кфут/кт^⅓. */
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

    // ---------------------------------------------------------------- стандарт DNA (дальняя зона)

    /** Давление по стандарту DNA, psi; x, y — кфут/кт^⅓. */
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

    /** Стандарт DNA для 1 кт в свободном воздухе, Па, на дальности {@code r} м. */
    static double freeAirPa(double r) {
        double q = r / 445.42;
        return 3.04e11 / (r * r * r) + 1.13e9 / (r * r) + 7.9e6 / (r * Math.sqrt(Math.log(q + 3 * Math.exp(-Math.sqrt(q) / 3))));
    }

    /** Отражение по Маху: свободный воздух удвоенной мощности на дальности по земле (1 кт), усиленный у пологого угла. */
    private static double mach(double sgr, double alpha) {
        double ln = Math.log(sgr), c = Math.max(Math.min(3.7 - 0.94 * ln, 0.7), 0.77 * ln - 3.8 - 18 / sgr);
        return freeAirPa(sgr / Math.cbrt(2)) / (1 - c * Math.sin(alpha));
    }

    /** Правильное отражение: падающая волна × множитель отражения Ренкина–Гюгонио по углу. */
    private static double regular(double free, double alpha) {
        double rn = 2 + (shockGamma(free) + 1) * (densityRatio(free) - 1) / 2;
        double f = free / 75842, f6 = Math.pow(f, 6), d = f6 * (1.2 + 0.07 * Math.sqrt(f)) / (f6 + 1);
        return free * ((rn - 2) * Math.pow(Math.sin(alpha), d) + 2);
    }

    /** Показатель адиабаты воздуха за фронтом давлением {@code op} Па (диссоциация у сильных волн). */
    private static double shockGamma(double op) {
        double xi = op / 101325 + 1, t = 1e-12 * Math.pow(xi, 6), z = Math.log(xi) - 0.47 * t / (100 + t);
        return 1.402 - 3.4e-4 * Math.pow(z, 4) / (1 + 2.22e-5 * Math.pow(z, 6));
    }

    /** Сжатие воздуха за фронтом давлением {@code op} Па. */
    private static double densityRatio(double op) {
        double xi = op / 101325 + 1, g = shockGamma(op), mu = (g + 1) / (g - 1);
        return (1 + mu * xi) / (5.975 + xi);
    }

    // ---------------------------------------------------------------- приход, фаза, напор (Броуд, PT)

    /** Начало области Маха по земле, м: 694 м у 15 кт на 592 м, 0 — у наземного. */
    public static double machStem(double heightM, double yieldKt) {
        double m = Math.cbrt(Math.max(1e-9, yieldKt)), y = Math.max(0, heightM) / FT / m;
        return (170 * y / (1 + 60 * Math.pow(y, 0.25)) + 2.89 * Math.pow(y / 100, 2.5)) * FT * m;
    }

    /** Приход фронта на землю, с: 0.79 с под подрывом 15 кт на 592 м, 2.17 с на 1 км, 27.5 с на 10 км. */
    public static double arrivalSeconds(double groundM, double heightM, double yieldKt) {
        double m = Math.cbrt(Math.max(1e-9, yieldKt));
        return tau(Math.max(0, groundM) / FT / m, Math.max(0, heightM) / FT / m) * m / 1000;
    }

    /** Броуд, PT: приход, мс/кт^⅓; X, Y — фут/кт^⅓. */
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
     * Приход фронта в свободном воздухе (DNA), с, на дальности {@code rangeM} м: под подрывом — тот же, что у Броуда на
     * земле под ним (у 15 кт на 592 м — 0.79 с).
     */
    public static double freeAirArrivalSeconds(double rangeM, double yieldKt) {
        double m = Math.cbrt(Math.max(1e-9, yieldKt)), r = Math.max(0, rangeM) / m;
        return r * r * (6.7 + r) / (7.12e6 + 7.32e4 * r + 340.5 * r * r) * m;
    }

    /** Положительная фаза на земле, с: 0.45 с под подрывом 15 кт на 592 м, 0.74 с на 1 км, 1.15 с на 10 км. */
    public static double positivePhaseSeconds(double groundM, double heightM, double yieldKt) {
        double m = Math.cbrt(Math.max(1e-9, yieldKt)), x = Math.max(0, groundM) / FT / m, y = Math.max(0, heightM) / FT / m;
        double t = tau(x, y), y100 = Math.pow(y / 100, 3.5);
        double s2 = 1 - 15.18 * y100 / (1 + 15.18 * y100)
                - 0.02441 * Math.pow(y / 1e6, 2) / (1 + 9000 * Math.pow(y / 100, 7)) * (1e10 / (0.441 + Math.pow(x / 100, 10)));
        double t15 = Math.pow(t, 1.5);
        double d = (1640700 + 24629 * t + 416.15 * t * t) / (10880 + 619.76 * t + t * t)
                * (0.4 + 0.001204 * t15 / (1 + 0.001559 * t15)
                + (0.6126 + 0.5486 * Math.pow(t, 0.25) / (1 + 0.00357 * t15) - 3.47 * Math.pow(t, 0.637) / (1 + 5.696 * Math.pow(t, 0.645))) * s2);
        return d * m / 1000;
    }

    /**
     * Доля скоростного напора за фронтом, что идёт вдоль земли: 1 в области Маха, у правильного отражения — cos²α, переход
     * — от 0.8·XM до XM. Ветер вдоль земли — ветер Ренкина–Гюгонио × корень из неё.
     */
    public static double alongGround(double groundM, double heightM, double yieldKt) {
        double xm = machStem(heightM, yieldKt);
        if (heightM <= 0 || groundM >= xm) return 1;
        double f = Math.max(0, (groundM - 0.8 * xm) / (0.2 * xm)), mach = f * f * (3 - 2 * f);
        double sin2 = heightM * heightM / (heightM * heightM + groundM * groundM);
        return 1 - (1 - mach) * sin2;
    }

    // ---------------------------------------------------------------- таблица

    /** Таблица давления по масштабной дальности и углу на подрыв над горизонтом: строится при первом обращении. */
    private static final class Table {
        /** Логарифм давления, psi: [дальность][угол]. */
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

        /** Давление, psi, по масштабным дальности по земле и высоте, кфут/кт^⅓. */
        static double psi(double x, double y) {
            double r = Math.hypot(x, y);
            // дальше таблицы — как слабая волна, 1/R
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
