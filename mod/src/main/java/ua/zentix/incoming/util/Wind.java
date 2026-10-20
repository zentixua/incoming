package ua.zentix.incoming.util;

/**
 * Ветер мира — один на всё: снос облака и осадков ядерного удара ({@code NuclearWarhead}), дым, пыль и шлейфы на
 * клиенте. Зависит только от времени игры, поэтому сервер и клиент считают его одинаково без пакета. На каждые сутки
 * игры — свой ветер: направление равновероятно, скорость 5–15 м/с (тот же выбор, что был у ядерки на подрыв); между
 * сутками он плавно поворачивается и меняет силу (вектором: при развороте на встречный проходит через штиль).
 * <p>
 * Это ветер над приземным слоем, на высоте {@link #REFERENCE} — его несёт облако ядерного удара. Ниже он слабеет по
 * степенному закону u(z) = U·(z/{@link #REFERENCE})^α ({@link #at(double, double)}): α ≈ 0,1 днём (неустойчиво),
 * 0,14 нейтрально, 0,3–0,4 ночью (устойчиво) — типовые значения степенного профиля, ⚠ оценка с точностью ~30 %, с
 * первоисточником не сверена. Высота отсчёта 1 км — обычная верхняя граница дневного слоя
 * перемешивания (оценка): у земли (10 м) нейтрально выходит половина, ночью — четверть.
 *
 * @param direction направление, рад: дует в сторону (cos, sin) в плоскости x–z
 * @param speed     скорость на высоте {@link #REFERENCE}, м/с
 */
public record Wind(double direction, double speed) {
    /** Высота отсчёта скорости над землёй, м. */
    public static final double REFERENCE = 1000;
    /** Ниже этой высоты над землёй профиль не продолжается (шероховатость травы и кустов), м. */
    static final double FLOOR = 1;
    /** Сутки игры, тиков. */
    static final long DAY = 24_000;
    /** Скорость: от SLOWEST до SLOWEST + SPREAD, м/с. */
    static final double SLOWEST = 5, SPREAD = 10;

    /** Ветер в тик игры gameTime. */
    public static Wind at(long gameTime) {
        long day = Math.floorDiv(gameTime, DAY);
        double f = (double) (gameTime - day * DAY) / DAY, k = f * f * (3 - 2 * f);
        double a0 = angle(day), a1 = angle(day + 1), s0 = speed(day), s1 = speed(day + 1);
        double x = s0 * Math.cos(a0) * (1 - k) + s1 * Math.cos(a1) * k, z = s0 * Math.sin(a0) * (1 - k) + s1 * Math.sin(a1) * k;
        return new Wind(Math.atan2(z, x), Math.sqrt(x * x + z * z));
    }

    /** Скорость на высоте height метров над землёй при показателе профиля alpha, м/с (выше отсчёта — как на нём). */
    public double at(double height, double alpha) {
        double z = Math.max(FLOOR, Math.min(height, REFERENCE));
        return speed * Math.pow(z / REFERENCE, alpha);
    }

    /** Составляющая x единичного направления. */
    public double x() {
        return Math.cos(direction);
    }

    /** Составляющая z единичного направления. */
    public double z() {
        return Math.sin(direction);
    }

    private static double angle(long day) {
        return unit(mix(day, 0x9E3779B97F4A7C15L)) * 2 * Math.PI;
    }

    private static double speed(long day) {
        return SLOWEST + SPREAD * unit(mix(day, 0xD1B54A32D192ED03L));
    }

    /** Число 0..1 из 53 старших бит. */
    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    /** SplitMix64 (Steele, Lea, Flood 2014) от суток и соли. */
    private static long mix(long day, long salt) {
        long z = day * 0xBF58476D1CE4E5B9L + salt;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
