package ua.zentix.incoming.nuclear.model;

/**
 * Огненный шар: размер, двойная вспышка, цвет, всплытие. Время t — секунды после подрыва,
 * мощность Y — килотонны, расстояния — метры.
 */
public final class FireballModel {
    /** Во сколько раз второй максимум тусклее первого импульса (для нормированной яркости). */
    private static final double SECOND_PEAK = 0.85;
    /** Показатель спада второго импульса: при 3.75 за 10·t_max излучается ~80% его энергии. */
    private static final double PULSE_EXP = 3.75;
    /** Шар дорастает до этой доли от максимума, дальше растёт медленно, остывая. */
    static final double LATE_GROWTH_CAP = 1.3;

    /** Видимая температура шара во втором максимуме, K (G&D 2.110: ~7 500–8 000 K почти без зависимости от мощности). */
    public static final double PEAK_K = 7700;
    /** Температура воздуха вокруг, K. */
    public static final double AMBIENT_K = 288;
    /** Показатель остывания после второго максимума: ΔT ∝ (t/t_max)^−0,35 (ключи хроники — {@link #temperature}). */
    static final double COOLING = 0.35;
    /** Горячее шар не показывается (первый импульс короче кадра): край строки света чёрного тела у клиента. */
    static final double HOTTEST_K = 30_000;

    private FireballModel() {
    }

    /** Максимальный радиус светящегося шара, м (Glasstone 2.12): 0.07·Y^0.4 км воздушный, 0.088·Y^0.4 км наземный. */
    public static double maxRadius(double yieldKt, boolean surfaceBurst) {
        return (surfaceBurst ? 88 : 70) * Math.pow(yieldKt, 0.4);
    }

    /** Минимум яркости между импульсами, с. */
    public static double firstMinimumSeconds(double yieldKt) {
        return 0.0025 * Math.sqrt(yieldKt);
    }

    /** Второй (главный тепловой) максимум, с. */
    public static double secondMaximumSeconds(double yieldKt) {
        return 0.032 * Math.sqrt(yieldKt);
    }

    /**
     * Световой импульс, с: 10·t_max — за это время второй импульс излучает ~80 % своей энергии (у 15 кт — 1,24 с), дальше
     * шар светит слабо. Вспышка у клиента, след света на земле и поджиг у сервера ({@code FlashScorch}) — за это время.
     */
    public static double pulseSeconds(double yieldKt) {
        return 10 * secondMaximumSeconds(yieldKt);
    }

    /** Шагов таблицы излучённой доли на τ от 0 до 10. */
    private static final int THERMAL_STEPS = 2000;
    /** Излучённая вторым импульсом энергия к τ = 10·i/{@link #THERMAL_STEPS} — доля излученной к концу импульса (τ = 10). */
    private static final double[] THERMAL = thermalTable();

    private static double[] thermalTable() {
        double[] out = new double[THERMAL_STEPS + 1];
        double h = 10.0 / THERMAL_STEPS, last = 0;
        for (int i = 1; i <= THERMAL_STEPS; i++) {
            double now = second(i * h);
            out[i] = out[i - 1] + (last + now) * h / 2;
            last = now;
        }
        for (int i = 1; i <= THERMAL_STEPS; i++) out[i] /= out[THERMAL_STEPS];
        return out;
    }

    /** Второй импульс по τ = t/t_max: (p/2)·τ² / (1 + (p−2)/2·τ^p), как в {@link #brightness}. */
    private static double second(double tau) {
        return PULSE_EXP / 2 * tau * tau / (1 + (PULSE_EXP - 2) / 2 * Math.pow(tau, PULSE_EXP));
    }

    /**
     * Доля светового импульса, излученная к τ = t/t_max: энергия второго импульса (мощность — его яркость) к τ от
     * излученной за весь импульс ({@link #pulseSeconds}, τ = 10). Первый импульс — доли процента энергии, не в счёте.
     */
    public static double thermalFraction(double tau) {
        if (tau <= 0) return 0;
        if (tau >= 10) return 1;
        double x = tau / 10 * THERMAL_STEPS;
        int i = (int) x;
        return THERMAL[i] + (THERMAL[i + 1] - THERMAL[i]) * (x - i);
    }

    /** Обратная {@link #thermalFraction}: τ = t/t_max, к которому излучена доля {@code fraction} импульса (0..10). */
    public static double thermalTau(double fraction) {
        if (fraction <= 0) return 0;
        if (fraction >= 1) return 10;
        int lo = 0, hi = THERMAL_STEPS;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (THERMAL[mid] < fraction) lo = mid;
            else hi = mid;
        }
        double span = THERMAL[hi] - THERMAL[lo];
        return 10.0 * (lo + (span <= 0 ? 0 : (fraction - THERMAL[lo]) / span)) / THERMAL_STEPS;
    }

    /**
     * Нормированная яркость 0..1: резкий первый импульс (спад к минимуму за t_min), затем широкий второй
     * максимум в t_max: f(τ) = (p/2)·τ² / (1 + (p−2)/2·τ^p), τ = t/t_max, дальше медленный спад к нулю.
     */
    public static double brightness(double t, double yieldKt) {
        if (t <= 0) return 0;
        double tMin = firstMinimumSeconds(yieldKt);
        double first = (1 - Math.exp(-t / (tMin * 0.001))) * Math.exp(-t / (tMin / 3));
        double tau = t / secondMaximumSeconds(yieldKt);
        double second = PULSE_EXP / 2 * tau * tau / (1 + (PULSE_EXP - 2) / 2 * Math.pow(tau, PULSE_EXP));
        return Math.min(1, first + SECOND_PEAK * second);
    }

    /**
     * Радиус шара, м: рост ~t^0.4 до максимума ко второму максимуму яркости,
     * затем медленный рост при остывании (+10% за каждое e-кратное время, не больше ×1.3).
     */
    public static double radius(double t, double yieldKt, boolean surfaceBurst) {
        if (t <= 0) return 0;
        double max = maxRadius(yieldKt, surfaceBurst);
        double tg = secondMaximumSeconds(yieldKt);
        if (t <= tg) return max * Math.pow(t / tg, 0.4);
        return max * Math.min(LATE_GROWTH_CAP, 1 + 0.1 * Math.log(t / tg));
    }

    /**
     * Видимая температура поверхности шара, K, пока он шар. До второго максимума — по Стефану–Больцману из его яркости
     * и радиуса: свет шара P ∝ T⁴·R², и T = T₂·(P/P₂)^¼·(R₂/R)^½ — первый импульс (маленький горячий шар) голубовато-белый,
     * в минимуме темнее, ко второму максимуму — T₂ ({@link #PEAK_K}). Дальше шар остывает к воздуху:
     * T_окр + (T₂ − T_окр)·(t/t_max)^−0,35 — у 15 кт бело-жёлтый ~3 900 K через секунду, ~1 950 K через девять (по
     * хронике). Это температура ядра: свернувшись в кольцо (у 15 кт — с ~3 с), шар остывает снаружи быстрее, край
     * смешан с подхваченным воздухом — через девять секунд ~1 250 K ({@link MushroomModel#edgeTemperature}).
     */
    public static double temperature(double t, double yieldKt) {
        if (t <= 0) return AMBIENT_K;
        double tm = secondMaximumSeconds(yieldKt);
        if (t >= tm) return AMBIENT_K + (PEAK_K - AMBIENT_K) * Math.pow(t / tm, -COOLING);
        // радиус до максимума — max·(t/t_max)^0,4 ({@link #radius}): (R₂/R)^½ = (t_max/t)^0,2
        double k = PEAK_K * Math.pow(brightness(t, yieldKt) / brightness(tm, yieldKt), 0.25) * Math.pow(tm / t, 0.2);
        return Solve.clamp(k, AMBIENT_K, HOTTEST_K);
    }

    /**
     * Докуда по земле от эпицентра лежит шар в момент t, м ({@link #radius} на высоте {@link #centreHeight}); 0 — земли он
     * не касается. Там нет поднятой волной пыли — там шар: стена пыли и пелена ({@code BlastWall}, {@code PallField})
     * начинаются за ним.
     */
    public static double groundRadius(double t, double hobM, double yieldKt, boolean surfaceBurst) {
        if (t <= 0) return 0;
        double r = radius(t, yieldKt, surfaceBurst), y = centreHeight(t, Math.max(0, hobM), yieldKt, surfaceBurst);
        return Math.sqrt(Math.max(0, r * r - y * y));
    }

    /**
     * Скорость всплытия шара сразу после подрыва, м/с: та же, что у верха облака ({@link CloudModel#riseSpeed}) — 1 кт
     * ~25, 15 кт ~79, 1 Мт ~139, через 10 с — 130 (Glasstone и Dolan: у шара 1 Мт через 10 с — 76–107, §2.05, у верха —
     * в среднем ~134 за первую минуту, §2.12).
     */
    public static double riseSpeed(double yieldKt, boolean surface) {
        return CloudModel.riseSpeed(0, 0, yieldKt, surface);
    }

    /**
     * Высота центра шара (потом — центра шапки), м: стартует с высоты подрыва и поднимается, как верх облака
     * ({@link CloudModel#top}: та же экспонента, тот же путь) — один закон подъёма у шара и облака, и верх шара (не выше
     * {@link #LATE_GROWTH_CAP} радиуса над центром) не обгоняет верх облака.
     */
    public static double centreHeight(double t, double hobM, double yieldKt, boolean surface) {
        if (t <= 0) return hobM;
        return hobM + CloudModel.top(t, hobM, yieldKt, surface) - CloudModel.top(0, hobM, yieldKt, surface);
    }
}
