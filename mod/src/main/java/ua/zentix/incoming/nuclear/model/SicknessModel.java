package ua.zentix.incoming.nuclear.model;

/**
 * Острая лучевая болезнь по накопленной дозе (Гр у поверхности тела — то, что меряет прибор, Glasstone & Dolan 1977
 * §12.108) и времени после облучения (игровые часы, 1000 тиков). Течение — по G&D табл. 12.108 и §12.113–12.122:
 * первичная реакция (тошнота, рвота), скрытый период, разгар; после разгара выздоровление ({@link Stage#NONE}) или
 * смерть ({@link Stage#FATAL}).
 *
 * <p>Умрёт ли облучённый, решает его случайность {@code fate} (0..1, своя у каждого): смерть, если
 * {@code fate < deathChance(доза)}. Кривая — логнормальная по дозе без лечения: ЛД50 ≈ 4,5 Гр у поверхности
 * (≈ 3,2 Гр в середине тела: середина — 70 % поверхности, G&D §12.108), разброс подобран так, что при 2 Гр не умирает
 * почти никто, при 6 Гр — 80 %, при 10 Гр — все: «0–80 %» при 2–6 Гр и «80–100 %» при 6–10 Гр у G&D.
 *
 * <p>Времена — опорные точки по дозе (между ними — по логарифму дозы и времени), G&D табл. 12.108:
 * <ul>
 *   <li>начало рвоты: 3 ч при 1 Гр, 2 ч при 2, 1 ч при 6, 30 мин при 10, сразу при 50 («delay time»);</li>
 *   <li>первичная реакция: «первый день-два» (§12.115–12.116), у больших доз короче;</li>
 *   <li>скрытый период: «до двух недель и больше» при 1–2 Гр, «от нескольких дней до двух недель» при 2–10 Гр,
 *       у кишечной формы (10–50 Гр) — дни, у мозговой (от 50 Гр) его нет;</li>
 *   <li>смерть: кроветворная форма (2–10 Гр) — «критический период 4–6 недель», кишечная (10–50 Гр) — «5–14 дней»,
 *       мозговая — «1–48 часов»: 36 ч при 50 Гр (случай Келли, 1958: около 40 Гр, смерть через 35 ч) и быстрее с дозой
 *       как D^−1,5 (оценка). Опорные точки: 6 недель при 2 Гр, 30 дней при 6, 12 при 10, 36 ч при 50; между ними —
 *       переход форм (18 дней при 8 Гр, 5 при 20, 2 при 40);</li>
 *   <li>выздоровление выживших: разгар кончается через 5 недель при 1 Гр, 8 при 2 и 10 при 6 Гр и больше (дальше —
 *       месяцы слабости, которых здесь нет).</li>
 * </ul>
 */
public final class SicknessModel {
    /** Стадия болезни. */
    public enum Stage { NONE, PRODROMAL, LATENT, MANIFEST, FATAL }

    /** Смертельная доза для половины без лечения, Гр у поверхности тела. */
    public static final double LD50 = 4.5;
    /** Разброс логнормальной кривой смерти (σ логарифма дозы). */
    public static final double LD_BETA = 0.34;
    /** Меньше этого болезни нет (G&D: до 1 Гр — без симптомов). */
    public static final double MIN_DOSE = 1;

    private static final double DAY = 24;
    /** Опорные дозы, Гр. */
    private static final double[] DOSE = {1, 2, 6, 10, 50};
    /** Начало первичной реакции, ч. */
    private static final double[] ONSET = {3, 2, 1, 0.5, 0.1};
    /** Длительность первичной реакции, ч. */
    private static final double[] PRODROMAL = {24, 36, 24, 12, 2};
    /** Конец скрытого периода, ч после облучения (у 50 Гр — сразу за первичной реакцией). */
    private static final double[] LATENT_END = {21 * DAY, 18 * DAY, 12 * DAY, 5 * DAY, 2.1};
    /** Смерть обречённых, ч после облучения. */
    private static final double[] DEATH = {42 * DAY, 42 * DAY, 30 * DAY, 12 * DAY, 36};
    /** Конец разгара у выживших, ч после облучения. */
    private static final double[] RECOVERY = {35 * DAY, 56 * DAY, 70 * DAY, 70 * DAY, 70 * DAY};
    /** Выше 50 Гр смерть быстрее: 36 ч·(50/D)^1,5. */
    private static final double CNS_EXP = 1.5;

    /** Течение при дозе: моменты после облучения, ч. */
    public record Course(double onset, double latentFrom, double manifestFrom, double death, double recovery) {
    }

    private SicknessModel() {
    }

    /** Вероятность смерти без лечения при дозе {@code doseGy}: Φ(ln(D/ЛД50)/β); до 1 Гр — 0. */
    public static double deathChance(double doseGy) {
        if (doseGy < MIN_DOSE) return 0;
        return Fragility.probability(doseGy, LD50, LD_BETA);
    }

    /** Умрёт ли облучённый со случайностью {@code fate} (0..1) при дозе {@code doseGy}. */
    public static boolean fated(double doseGy, double fate) {
        return fate < deathChance(doseGy);
    }

    /** Течение при дозе {@code doseGy}; null — меньше 1 Гр, болезни нет. */
    public static Course course(double doseGy) {
        if (doseGy < MIN_DOSE) return null;
        double onset = at(ONSET, doseGy);
        double latentFrom = onset + at(PRODROMAL, doseGy);
        double death = doseGy > DOSE[DOSE.length - 1]
                ? DEATH[DEATH.length - 1] * Math.pow(DOSE[DOSE.length - 1] / doseGy, CNS_EXP)
                : at(DEATH, doseGy);
        // у больших доз всё сжимается к смерти: рвота сразу, первичная реакция — до половины срока
        onset = Math.min(onset, death * 0.1);
        latentFrom = Math.min(latentFrom, death * 0.5);
        double manifestFrom = Math.max(latentFrom, Math.min(at(LATENT_END, doseGy), death * 0.5));
        return new Course(onset, latentFrom, manifestFrom, death, Math.max(manifestFrom, at(RECOVERY, doseGy)));
    }

    /** Стадия при дозе doseGy через hours игровых часов после облучения; {@code fated} — облучённый обречён. */
    public static Stage stage(double doseGy, double hours, boolean fated) {
        Course c = course(doseGy);
        if (c == null || hours < c.onset) return Stage.NONE;
        if (hours < c.latentFrom) return Stage.PRODROMAL;
        if (hours < c.manifestFrom) return Stage.LATENT;
        if (fated) return hours < c.death ? Stage.MANIFEST : Stage.FATAL;
        return hours < c.recovery ? Stage.MANIFEST : Stage.NONE;
    }

    /** Через сколько игровых часов начинается первичная реакция; бесконечность при дозе < 1 Гр. */
    public static double onsetHours(double doseGy) {
        Course c = course(doseGy);
        return c == null ? Double.POSITIVE_INFINITY : c.onset;
    }

    /** Снижение максимального здоровья в разгаре, единиц (2 = одно сердце). */
    public static double maxHealthPenalty(double doseGy) {
        if (doseGy >= 6) return 10;
        if (doseGy >= 2) return 4;
        return 0;
    }

    /** Множитель расхода сытости в разгаре. */
    public static double hungerMultiplier(double doseGy) {
        if (doseGy >= 2) return 2;
        if (doseGy >= 1) return 1.5;
        return 1;
    }

    /** Нет естественной регенерации в разгаре (≥ 2 Гр). */
    public static boolean noRegeneration(double doseGy) {
        return doseGy >= 2;
    }

    /** Уровень слабости в разгаре (усилитель эффекта): −1 — нет, 0 — I, 1 — II. */
    public static int weaknessAmplifier(double doseGy) {
        if (doseGy >= 6) return 1;
        if (doseGy >= 1) return 0;
        return -1;
    }

    /** Значение опорной таблицы при дозе: по логарифмам дозы и значения, за краями — крайнее. */
    private static double at(double[] values, double doseGy) {
        if (doseGy <= DOSE[0]) return values[0];
        int last = DOSE.length - 1;
        if (doseGy >= DOSE[last]) return values[last];
        int i = 1;
        while (DOSE[i] < doseGy) i++;
        double t = Math.log(doseGy / DOSE[i - 1]) / Math.log(DOSE[i] / DOSE[i - 1]);
        return Math.exp(Math.log(values[i - 1]) + t * Math.log(values[i] / values[i - 1]));
    }
}
