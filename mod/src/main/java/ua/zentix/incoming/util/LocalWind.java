package ua.zentix.incoming.util;

/**
 * Ветер у земли в месте: поправка к ветру мира ({@link Wind}, {@link Air}) от того, что стоит вокруг, — доля
 * {@link #factor} скорости на высоте над землёй против той же высоты над ровным полем. Ветер мира — погода над слоем у
 * земли и остаётся одним; поправка — только ниже высоты смешения застройки и у холмов, по карте высот вокруг места, один
 * раз на место: сервер и клиент по тем же картам считают одинаково, пакета нет.
 * <ul>
 *   <li><b>Земля и постройки.</b> Карта высот ({@code MOTION_BLOCKING}: дома, кроны, столбы) делится на землю и то, что
 *   на ней стоит, морфологическим открытием окном {@value #GROUND_WINDOW} м — фильтр земли лидара (Zhang K. et al.,
 *   IEEE TGRS 41:872, 2003): всё уже окна (дом, дерево) снимается, склон остаётся склоном. ⚠ Хребет уже окна теряет
 *   макушку — на гребне выходит низкая «застройка».</li>
 *   <li><b>Шероховатость.</b> Доля площади под постройками λp, их средняя высота H и доля фронтальной площади против
 *   ветра λf дают высоту вытеснения d и шероховатость z₀ (морфометрия Macdonald, Griffiths, Hall 1998, Atmos. Environ.
 *   32:1857: A = 4,43, C_D = 1,2, β = 1). Выше H — логарифмический профиль над d, до высоты смешения
 *   {@value #BLEND}·H (Grimmond, Oke 1999, J. Appl. Meteor. 38:1262: 2–5 H), где он сходится с полем (z₀ травы
 *   {@value #OPEN_Z0} м, класс «открытое место» Davenport/WMO). Ниже H, в улице, — экспонента
 *   u(z) = u(H)·exp(a(z/H − 1)), a = 9,6·λf (Macdonald 2000, Bound.-Layer Meteor. 97:25; подгонка до λf 0,33 — дальше
 *   не продолжается). ⚠ Castro 2017 (BLM 162:207) показывает, что в плотной застройке средний профиль не всегда
 *   экспонента; направление в улице (вдоль неё) не меняется.</li>
 *   <li><b>Холм.</b> Над вершиной высоты H с полушириной L (на половине высоты) ветер разгоняется на ΔS = B·H/L,
 *   B = {@value #HILL_B} (трёхмерный холм), и разгон убывает с высотой как exp(−{@value #HILL_A}·z/L); в низине —
 *   наоборот (линейная теория Jackson, Hunt 1975; руководство Taylor, Lee 1984, Bound.-Layer Meteor. 30:415). Круче
 *   H/L = {@value #HILL_SLOPE} поток отрывается — разгон дальше не растёт. ⚠ L — по кольцу вокруг места, где земля
 *   ниже всего относительно вершины; затишье за гребнем не моделируется.</li>
 * </ul>
 *
 * @param ground       земля под местом, y мира ({@link Double#NaN} — неизвестна: высота берётся над уровнем моря)
 * @param base         высота поверхности, от которой меряет вызывающий (крыша под взрывом), над землёй, м
 * @param canopy       средняя высота построек и крон H, м (0 — поле)
 * @param displacement высота вытеснения d, м
 * @param roughness    шероховатость z₀, м
 * @param attenuation  показатель a профиля в застройке
 * @param hill         разгон у земли ΔS (меньше нуля — в низине)
 * @param hillLength   полуширина холма L, м
 */
public record LocalWind(double ground, double base, double canopy, double displacement, double roughness, double attenuation,
                        double hill, double hillLength) {
    /** Шероховатость открытого места (короткая трава), м: к ней приведён ветер мира у земли. */
    public static final double OPEN_Z0 = 0.03;
    /** Ровное поле: поправки нет. */
    public static final LocalWind OPEN = new LocalWind(Double.NaN, 0, 0, 0, OPEN_Z0, 0, 0, 1);
    /** Шаг выборки карты высот и полуразмер квадрата застройки, м. */
    static final int STEP = 2, RADIUS = 32;
    /** Окно фильтра земли (сторона квадрата), м: дома и кроны уже него снимаются. */
    static final int GROUND_WINDOW = 25;
    /** Ниже этого над землёй — не постройка (забор, куст, ступень рельефа), м. */
    static final double OBSTACLE = 2;
    /** Высота смешения в H. */
    static final double BLEND = 3;
    /** Морфометрия Macdonald 1998 и экспонента Macdonald 2000. */
    static final double A = 4.43, CD = 1.2, KAPPA = 0.4, ATTENUATION = 9.6, FRONTAL_MAX = 0.33;
    /** Холм: кольца поиска подножия, м, лучей на кольцо; Taylor, Lee 1984. */
    static final double[] HILL_RINGS = {16, 32, 64, 128};
    static final int SPOKES = 16;
    static final double HILL_B = 1.6, HILL_A = 4, HILL_SLOPE = 0.5, HILL_LOW = -0.5;

    /** Верх колонки (первый воздух над картой высот), y мира; {@link Double#NaN} — неизвестен. */
    @FunctionalInterface
    public interface Heights {
        double at(int x, int z);
    }

    /**
     * Ветер у земли в (x, z) при ветре мира в сторону (ux, uz) (единичный: от него — фронтальная площадь построек).
     * Поверхность, от которой меряет вызывающий ({@link #base}), — верх колонки в самом месте.
     */
    public static LocalWind of(Heights heights, double x, double z, double ux, double uz) {
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        int w = GROUND_WINDOW / 2 / STEP, n = 2 * (RADIUS / STEP) + 1, m = n + 4 * w;
        double[][] top = new double[m][m];
        for (int i = 0; i < m; i++) for (int j = 0; j < m; j++) top[i][j] = heights.at(cx + (i - m / 2) * STEP, cz + (j - m / 2) * STEP);
        double[][] ground = opening(top, w);
        int c = m / 2;
        double g0 = ground[c][c];
        if (Double.isNaN(g0)) return OPEN;
        // застройка в квадрате места: доля под постройками, их высота и ступени против ветра
        int di = (int) Math.round(ux), dj = (int) Math.round(uz);
        int known = 0, built = 0;
        double sum = 0, frontal = 0;
        for (int i = 2 * w; i < 2 * w + n; i++) {
            for (int j = 2 * w; j < 2 * w + n; j++) {
                double o = obstacle(top, ground, i, j);
                if (Double.isNaN(o)) continue;
                known++;
                if (o > 0) {
                    built++;
                    sum += o;
                }
                double up = obstacle(top, ground, i - di, j - dj);
                if (!Double.isNaN(up) && o > up) frontal += o - up;
            }
        }
        double canopy = 0, d = 0, z0 = OPEN_Z0, a = 0;
        if (known >= n * n / 4 && built > 0) {
            double lp = (double) built / known, lf = frontal / (known * (double) STEP), h = sum / built;
            double dh = 1 + Math.pow(A, -lp) * (lp - 1);
            double z0h = (1 - dh) * Math.exp(-1 / Math.sqrt(0.5 * CD / (KAPPA * KAPPA) * (1 - dh) * Math.max(lf, 1e-6)));
            if (z0h * h > OPEN_Z0) {
                canopy = h;
                d = dh * h;
                z0 = z0h * h;
                a = ATTENUATION * Math.min(lf, FRONTAL_MAX);
            }
        }
        double here = top[c][c];
        double base = Double.isNaN(here) ? 0 : Math.max(0, here - g0);
        // холм: земля на кольцах против земли места (то же грубое минимальное окно, чтобы сдвиг склона был одним)
        double g = coarse(heights, cx, cz), hill = 0, length = 1, best = 0;
        if (!Double.isNaN(g)) {
            for (double ring : HILL_RINGS) {
                double s = 0;
                int k = 0;
                for (int q = 0; q < SPOKES; q++) {
                    double ang = 2 * Math.PI * q / SPOKES, v = coarse(heights, (int) Math.floor(x + ring * Math.cos(ang)), (int) Math.floor(z + ring * Math.sin(ang)));
                    if (Double.isNaN(v)) continue;
                    s += v;
                    k++;
                }
                if (k < SPOKES / 2) continue;
                double l = ring / 2, ds = Math.max(HILL_LOW, HILL_B * Math.clamp((g - s / k) / l, -HILL_SLOPE, HILL_SLOPE));
                double at10 = Math.abs(ds) * Math.exp(-HILL_A * 10 / l);
                if (at10 > best) {
                    best = at10;
                    hill = ds;
                    length = l;
                }
            }
        }
        return new LocalWind(g0, base, canopy, d, z0, a, hill, length);
    }

    /** Высота постройки над землёй в ячейке, м: ниже {@link #OBSTACLE} — 0; неизвестно — NaN. */
    private static double obstacle(double[][] top, double[][] ground, int i, int j) {
        if (i < 0 || j < 0 || i >= top.length || j >= top.length) return Double.NaN;
        double o = top[i][j] - ground[i][j];
        if (Double.isNaN(o)) return Double.NaN;
        return o >= OBSTACLE ? o : 0;
    }

    /** Морфологическое открытие квадратом (2w+1)²: минимум, потом максимум; неизвестное не в счёт. */
    static double[][] opening(double[][] v, int w) {
        return filter(filter(v, w, true), w, false);
    }

    private static double[][] filter(double[][] v, int w, boolean min) {
        int m = v.length;
        double[][] a = new double[m][m], b = new double[m][m];
        double[] line = new double[m], out = new double[m];
        for (int i = 0; i < m; i++) {
            run(v[i], a[i], w, min);
        }
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < m; i++) line[i] = a[i][j];
            run(line, out, w, min);
            for (int i = 0; i < m; i++) b[i][j] = out[i];
        }
        return b;
    }

    /**
     * Минимум (максимум) в окне ±w по строке за постоянное время на точку (van Herk 1992, Gil, Werman 1993): накопленные
     * слева и справа в блоках по 2w + 1. Неизвестное — не в счёт; окно без известного — NaN.
     */
    static void run(double[] v, double[] out, int w, boolean min) {
        int m = v.length, k = 2 * w + 1;
        double none = min ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        double[] left = new double[m], right = new double[m];
        for (int i = 0; i < m; i++) {
            double x = Double.isNaN(v[i]) ? none : v[i];
            left[i] = i % k == 0 ? x : min ? Math.min(left[i - 1], x) : Math.max(left[i - 1], x);
        }
        for (int i = m - 1; i >= 0; i--) {
            double x = Double.isNaN(v[i]) ? none : v[i];
            right[i] = i == m - 1 || (i + 1) % k == 0 ? x : min ? Math.min(right[i + 1], x) : Math.max(right[i + 1], x);
        }
        for (int i = 0; i < m; i++) {
            int lo = Math.max(0, i - w), hi = Math.min(m - 1, i + w);
            // окно в двух соседних блоках — правое накопленное от lo и левое до hi; в одном (у краёв строки окно короче) —
            // накопленное от его начала блока или до конца строки
            double r;
            if (lo / k != hi / k) r = min ? Math.min(right[lo], left[hi]) : Math.max(right[lo], left[hi]);
            else r = lo % k == 0 ? left[hi] : right[lo];
            out[i] = Double.isInfinite(r) ? Double.NaN : r;
        }
    }

    /** Земля для холма: низ из пяти колонок крестом ±4 м (дом или дерево в самой точке не поднимает её). */
    private static double coarse(Heights h, int x, int z) {
        double r = Double.NaN;
        int[][] cross = {{0, 0}, {4, 0}, {-4, 0}, {0, 4}, {0, -4}};
        for (int[] d : cross) {
            double v = h.at(x + d[0], z + d[1]);
            if (!Double.isNaN(v) && (Double.isNaN(r) || v < r)) r = v;
        }
        return r;
    }

    /**
     * Доля ветра на высоте z м над поверхностью вызывающего ({@link #base} над землёй) против той же высоты над полем: 1 —
     * выше высоты смешения у земли и вдали от холма; над крышей — ещё и выше над землёй.
     */
    public double factor(double z) {
        return factor(z, base);
    }

    /** Доля ветра на высоте h м над землёй места против той же высоты над полем. */
    public double overGround(double h) {
        return factor(h, 0);
    }

    private double factor(double z, double base) {
        double zz = Math.max(Wind.FLOOR, z), h = Math.max(Wind.FLOOR, z + base);
        // скорости в долях ветра поля на высоте смешения (у поля — логарифм над z₀ травы)
        double here = Math.log(h / OPEN_Z0), zb = BLEND * canopy;
        if (canopy > 0 && h < zb) {
            double above = Math.max(h, canopy);
            here = Math.log(zb / OPEN_Z0) * Math.log((above - displacement) / roughness) / Math.log((zb - displacement) / roughness);
            if (h < canopy) here *= Math.exp(attenuation * (h / canopy - 1));
        }
        double f = here / Math.log(zz / OPEN_Z0);
        if (hill != 0) f *= 1 + hill * Math.exp(-HILL_A * h / hillLength);
        return f;
    }

    /** Высота точки y мира над землёй места, м (земля неизвестна — над уровнем моря, как у ветра мира). */
    public double above(double y) {
        return y - (Double.isNaN(ground) ? Air.SEA : ground);
    }

    /** Ключ кэша облаков: поправка с точностью до 2 % на высотах слоя у земли. */
    public String key() {
        return Math.round(50 * factor(2)) + ":" + Math.round(50 * factor(10)) + ":" + Math.round(50 * factor(40));
    }
}
