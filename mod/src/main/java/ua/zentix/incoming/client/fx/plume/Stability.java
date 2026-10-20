package ua.zentix.incoming.client.fx.plume;

/**
 * Устойчивость приземного воздуха по Паскуиллу (классы A–F, здесь 0..5 и дробные между ними) и расплывание дыма по
 * формулам Бриггса для открытой местности.
 * <p>
 * Класс — по таблице Паскуилла (Pasquill 1961; Turner 1964, «A diurnal stability classification scheme»): днём — по
 * ветру у земли (10 м) и солнцу (сильное — выше 60°, умеренное — 35–60°, слабое — 15–35°), ночью — по ветру и облачности
 * (ясно — столбец «≤ 3/8»), сплошные облака (дождь, гроза) днём и ночью — D. Половинные классы таблицы (A–B) — середина,
 * между столбцами ветра и высотами солнца — плавно.
 * <p>
 * Расплывание (Briggs 1973 для открытой местности, по Hanna, Briggs, Hosker, «Handbook on Atmospheric Diffusion», 1982),
 * x — путь по ветру, м: σ_y = a·x·(1 + 0,0001x)^−½; σ_z: A 0,20x; B 0,12x; C 0,08x(1 + 0,0002x)^−½;
 * D 0,06x(1 + 0,0015x)^−½; E 0,03x(1 + 0,0003x)^−1; F 0,016x(1 + 0,0003x)^−1 (a: 0,22, 0,16, 0,11, 0,08, 0,06, 0,04).
 * Формулы — для дыма у земли; на высоте, где земли нет, — та же диффузия по времени (x = u·t).
 */
public final class Stability {
    public static final double A = 0, B = 1, C = 2, D = 3, E = 4, F = 5;
    private static final double[] SIGMA_Y = {0.22, 0.16, 0.11, 0.08, 0.06, 0.04};
    /** Ветер у земли — середины столбцов таблицы (&lt;2, 2–3, 3–5, 5–6, &gt;6 м/с). */
    private static final double[] WIND = {1, 2.5, 4, 5.5, 7};
    /** Днём: солнце сильное, умеренное, слабое; ночью: ясно. */
    private static final double[] STRONG = {A, 0.5, B, C, C}, MODERATE = {0.5, B, 1.5, 2.5, D}, SLIGHT = {B, C, C, D, D},
            NIGHT = {F, F, E, D, D};
    /**
     * Частота Брента–Вяйсяля, 1/с: днём и нейтрально — стандартная атмосфера (6,5 K/км, N ≈ 0,012), ночная инверсия —
     * 0,02–0,04 (⚠ оценки; класс E — 0,02, F — 0,03). Дневной слой перемешивания (N ≈ 0) не выделен: дым поднимается в нём
     * не выше, чем в стандартной атмосфере.
     */
    private static final double[] BRUNT = {0.012, 0.012, 0.012, 0.012, 0.02, 0.03};
    /** Показатель степенного профиля ветра по классу: A–C 0,1, D 0,14, E 0,25, F 0,35 (⚠ оценки, {@code util.Wind}). */
    private static final double[] ALPHA = {0.1, 0.1, 0.1, 0.14, 0.25, 0.35};

    private Stability() {}

    /**
     * Класс по ветру у земли u10 (м/с), синусу высоты солнца sun и доле сплошной облачности overcast (0..1: дождь,
     * гроза).
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

    /** σ_y на пути x метров по ветру в классе c, м. */
    public static double sigmaY(double c, double x) {
        return blend(c, k -> SIGMA_Y[k] * x / Math.sqrt(1 + 0.0001 * x));
    }

    /** σ_z на пути x метров по ветру в классе c, м. */
    public static double sigmaZ(double c, double x) {
        return blend(c, k -> switch (k) {
            case 0 -> 0.20 * x;
            case 1 -> 0.12 * x;
            case 2 -> 0.08 * x / Math.sqrt(1 + 0.0002 * x);
            case 3 -> 0.06 * x / Math.sqrt(1 + 0.0015 * x);
            case 4 -> 0.03 * x / (1 + 0.0003 * x);
            default -> 0.016 * x / (1 + 0.0003 * x);
        });
    }

    /** Частота Брента–Вяйсяля в классе c, 1/с. */
    public static double brunt(double c) {
        return blend(c, k -> BRUNT[k]);
    }

    /** Показатель степенного профиля ветра в классе c. */
    public static double alpha(double c) {
        return blend(c, k -> ALPHA[k]);
    }

    private interface ByClass {
        double at(int k);
    }

    private static double blend(double c, ByClass f) {
        c = Math.max(A, Math.min(F, c));
        int k = (int) Math.floor(c);
        if (k >= 5) return f.at(5);
        double t = c - k;
        return t == 0 ? f.at(k) : f.at(k) * (1 - t) + f.at(k + 1) * t;
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
