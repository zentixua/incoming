package ua.zentix.incoming.nuclear.model;

/**
 * Ожоги светом шара (Glasstone &amp; Dolan 1977, гл. 7 и 12): степень ожога открытой кожи по световому импульсу, поджиг
 * одежды, ожоговый шок. Пороги растут с мощностью: импульс большого подрыва длиннее, и кожа успевает отдать тепло.
 *
 * <p>Ожог у каждого свой: порог человека — медиана, умноженная на {@code exp(β·z)}, {@code z} — его случайность
 * (нормальная, усечена до ±{@link Fragility#TAIL}); один и тот же {@code z} для всех степеней — та же кожа.
 */
public final class BurnInjury {
    /** Мощности опорных точек рис. 12.64, кт. */
    private static final double[] SKIN_YIELDS = {1, 10, 100, 1000, 10000};
    /**
     * Световой импульс, кал/см², при котором ожог 1-й, 2-й и 3-й степени у половины людей: рис. 12.64, кожа средней
     * пигментации, без укрытия и без попытки уклониться (снято с графика с точностью ~0,05).
     */
    private static final double[][] SKIN = {
            {2.05, 2.38, 2.78, 3.25, 4.15},
            {4.0, 4.32, 5.12, 6.28, 7.65},
            {6.18, 6.95, 8.35, 10.0, 11.8}};
    /**
     * Разброс порогов у людей (σ логарифма): §12.65 — у 1 Мт при 4,5–6 кал/см² 2-ю степень получают 18 %, при медиане
     * 6,28 это 0,18 (разница светлой и тёмной кожи на рис. 12.64 — ±12 %, часть того же разброса).
     */
    public static final double BETA = 0.18;
    /** Мощности столбцов табл. 7.35, кт. */
    private static final double[] FABRIC_YIELDS = {35, 1400, 20000};
    /** Тонкая ткань загорается, кал/см²: хлопковая рубашечная, 3 унции на ярд², хаки (табл. 7.35). */
    private static final double[] THIN = {14, 21, 28};
    /** Плотная ткань загорается, кал/см²: хлопок 8 унций на ярд², хаки (табл. 7.35). */
    private static final double[] HEAVY = {20, 30, 39};
    /**
     * Ожоговый шок (§12.62): 3-я степень на 25 % тела или 2-я на 30 % — шок через 30 минут — 12 часов; без лечения
     * он смертелен (в игре лечения нет).
     */
    public static final double SHOCK_THIRD = 25, SHOCK_SECOND = 30;
    /** Когда наступает шок, игровые часы. */
    public static final double SHOCK_FROM_H = 0.5, SHOCK_TO_H = 12;
    /** Ступень потери здоровья у ожогового шока ({@link #level}): здоровья остаётся {@link #SHOCK_LEFT}. */
    public static final int SHOCK_LEVEL = 10;
    public static final double SHOCK_LEFT = 0.05;
    /**
     * Заживление, игровые дни: 1-я степень — около недели без следа (§12.53, как солнечный ожог; срок — клиника, оценка),
     * 2-я — одна-две недели (§12.54), 3-я — несколько недель, со шрамом (§12.55).
     */
    public static final double[] HEAL_DAYS = {7, 14, 35};

    /** Одежда на месте тела. */
    public enum Cover {
        /** Открытая кожа. */
        SKIN,
        /** Тонкая ткань: от света не защищает, пока не загорится (тогда ожог под ней — от пламени). */
        THIN,
        /** Плотная ткань (кожаная броня). */
        HEAVY,
        /** Металл и всё твёрдое: свет не проходит, не горит. */
        SOLID
    }

    private BurnInjury() {}

    /** Порог ожога степени {@code degree} (1–3) у половины людей, кал/см². */
    public static double median(int degree, double yieldKt) {
        return interpolate(SKIN_YIELDS, SKIN[degree - 1], yieldKt);
    }

    /** Степень ожога открытой кожи (0 — нет) при импульсе {@code q} кал/см² у человека со случайностью {@code z}. */
    public static int degree(double q, double yieldKt, double z) {
        for (int k = 3; k >= 1; k--) {
            if (Fragility.hit(q, median(k, yieldKt), BETA, z)) return k;
        }
        return 0;
    }

    /** Ниже этого импульса (кал/см²) ожогов не бывает ни у кого: 1-я степень у самого чувствительного. */
    public static double lowest(double yieldKt) {
        return median(1, yieldKt) * Math.exp(-BETA * Fragility.TAIL);
    }

    /** Загорается ли одежда {@code cover} при импульсе {@code q} кал/см². */
    public static boolean ignites(Cover cover, double q, double yieldKt) {
        return switch (cover) {
            case THIN -> q >= interpolate(FABRIC_YIELDS, THIN, yieldKt);
            case HEAVY -> q >= interpolate(FABRIC_YIELDS, HEAVY, yieldKt);
            default -> false;
        };
    }

    /** Доля порога шока: ожоги 2-й и 3-й степени на {@code second} и {@code third} % тела; от 1 — шок. */
    public static double shock(double second, double third) {
        return second / SHOCK_SECOND + third / SHOCK_THIRD;
    }

    /**
     * Ступень потери здоровья для ожогов, которые составляют долю {@code share} порога шока: десятые доли с округлением
     * вверх, от 0,9 до шока — 9 (0,9); от 1 — шок, {@link #SHOCK_LEVEL}.
     */
    public static int level(double share) {
        return share >= 1 ? SHOCK_LEVEL : (int) Math.min(SHOCK_LEVEL - 1, Math.max(0, Math.ceil(share * 10 - 1e-9)));
    }

    /** Доля здоровья, которую отнимают ожоги ступени {@code level} (меньше нуля — ожогов нет). */
    public static double lost(int level) {
        return level >= SHOCK_LEVEL ? 1 - SHOCK_LEFT : Math.max(0, level) / 10.0;
    }

    /**
     * Рана ожогов ступени {@code level} поверх прежних ступени {@code before} (−1 — их нет): доля здоровья без ожогов,
     * на которую ступень выросла; не тяжелее прежних — ноль (те же ожоги второй раз ничего не отнимают).
     */
    public static double wound(int before, int level) {
        return level <= before ? 0 : lost(level) - lost(before);
    }

    /** Через сколько игровых часов наступает шок у человека со случайностью {@code u} 0..1 (равномерно по логарифму). */
    public static double shockHours(double u) {
        return SHOCK_FROM_H * Math.pow(SHOCK_TO_H / SHOCK_FROM_H, Math.max(0, Math.min(1, u)));
    }

    /**
     * Значение по опорным точкам: линейно по логарифму мощности (так построены рис. 12.64 и табл. 7.35); мощность — от
     * 1 кт до последней точки, ниже первой точки (у ткани — 35 кт) — наклоном первого отрезка.
     */
    private static double interpolate(double[] yields, double[] values, double yieldKt) {
        double x = Math.log(Math.max(1, Math.min(yields[yields.length - 1], yieldKt)));
        int i = 0;
        while (i < yields.length - 2 && x > Math.log(yields[i + 1])) i++;
        double x0 = Math.log(yields[i]), x1 = Math.log(yields[i + 1]);
        return values[i] + (values[i + 1] - values[i]) * (x - x0) / (x1 - x0);
    }
}
