package ua.zentix.incoming.sound;

/**
 * Поглощение звука воздухом — ISO 9613-1:1993, ф. (3)–(5), без Minecraft (проверяется юнит-тестами). Условия мода —
 * 20 °C, влажность 70%, 101,325 кПа: при них скорость звука 343 м/с ({@code BlastModel.SOUND_SPEED}), и при них же
 * {@code tools/build_sounds.py} кладёт в дальние записи «столько-то метров воздуха» (табл. {@code AIR_DB_KM}).
 */
public final class Air {
    /** Условия мода: температура, °C; относительная влажность, %; давление, кПа. */
    public static final double CELSIUS = 20, HUMIDITY = 70, KPA = 101.325;
    /** Плотность воздуха при этих условиях, кг/м³ (ISO 2533: 1,204 при 20 °C). */
    public static final double DENSITY = 1.204;

    private Air() {}

    /** Поглощение на частоте f, Гц, при условиях мода, дБ/м. */
    public static double alpha(double f) {
        return alpha(f, CELSIUS, HUMIDITY, KPA);
    }

    /**
     * Поглощение, дБ/м (ISO 9613-1, ф. 5): классическое и вращательное — первое слагаемое, колебательная релаксация
     * кислорода и азота — второе и третье, частоты релаксации — ф. (3) и (4), молярная доля водяного пара — прил. B.
     *
     * @param f        частота, Гц
     * @param celsius  температура, °C
     * @param humidity относительная влажность, %
     * @param kpa      давление, кПа
     */
    public static double alpha(double f, double celsius, double humidity, double kpa) {
        double t = celsius + 273.15, t0 = 293.15, t01 = 273.16, pr = 101.325;
        double pa = kpa / pr, tr = t / t0;
        // давление насыщенного пара (прил. B, ф. B.1–B.3) и молярная доля пара, %
        double c = -6.8346 * Math.pow(t01 / t, 1.261) + 4.6151;
        double h = humidity * Math.pow(10, c) / pa;
        double frO = pa * (24 + 4.04e4 * h * (0.02 + h) / (0.391 + h));
        double frN = pa / Math.sqrt(tr) * (9 + 280 * h * Math.exp(-4.170 * (Math.pow(tr, -1.0 / 3) - 1)));
        double f2 = f * f;
        return 8.686 * f2 * (1.84e-11 / pa * Math.sqrt(tr)
                + Math.pow(tr, -2.5) * (0.01275 * Math.exp(-2239.1 / t) / (frO + f2 / frO)
                + 0.1068 * Math.exp(-3352.0 / t) / (frN + f2 / frN)));
    }
}
