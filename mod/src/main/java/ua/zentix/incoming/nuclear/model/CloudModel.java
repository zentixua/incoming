package ua.zentix.incoming.nuclear.model;

/**
 * Ядерный гриб: высота и размер шапки, время стабилизации, подъём. Метры, секунды, килотонны.
 * <p>
 * Подъём — один закон для шара и облака: верх облака идёт от верха шара наибольшего размера к верху после стабилизации
 * экспонентой с постоянной τ, h(t) = h₀ + (h∞ − h₀)·(1 − e^(−t/τ)), а центр шара поднимается вместе с ним
 * ({@link FireballModel#centreHeight}): шар — та же шапка, которая ещё не свернулась.
 * <p>
 * <b>Замеры</b> — облака испытаний в Неваде (Hawthorne 1979, DNA 1251-1-EX; верх и низ — красные линии рис. 2 в Blakeley
 * et al., «Real time simulations of nuclear cloud rise with the ERF model», LLNL-JRNL-2012579, сняты с графика 300 dpi):
 * «Грейбл», 15 кт на 160 м, — верх 10,0 км над землёй (9,97 км с 9-й минуты), низ 6,1–6,2 км; «Уосп», 1 кт на 232 м,
 * — верх 5,27 км через 8 минут, ещё растёт (~4 м/с), низ 3,2 км. Высоты — над землёй: ось рисунка — «km AGL», а первые
 * точки (верх «Уоспа» 0,68 км через 15 с, низ 0,82 км через 30 с) лежат ниже самих площадок над морем (Юкка-Флэт
 * ~1,28 км, Френчмен-Флэт ~0,94 км) — над морем облако было бы под землёй. Неточность — снятие с графика (±0,1 км) и
 * то, что первоисточник (DNA 1251) не сверен. Мегатонна — Glasstone и Dolan: верх через 0,3; 0,7; 1,1; 2,5 и 3,8 минуты —
 * 2, 4, 6, 10 и 12 миль над точкой подрыва (табл. 2.12, «грубые средние»), после стабилизации 1 Мт ≈ 21,5 км и 10 Мт ≈
 * 30 км (рис. 2.16: облако упирается в тропопаузу, а 10 Мт её пробивают).
 * <p>
 * Подогнано ({@code CloudModelTest}): «Грейбл» — τ = 121 с при верхе 10 км (среднеквадратично 4,0 % по 13 точкам, хуже
 * всего 11 % на 15-й секунде), «Уосп» — τ = 227 с и верх 5,66 км над точкой подрыва (0,9 %, хуже всего 2,6 %), табл.
 * 2.12 — τ = 144 с при верхе 21,5 км (11 %, хуже всего 18 %: там подъём почти ровный и встаёт у тропопаузы, экспонента
 * его сглаживает).
 * Верх между 1 и 15 кт растёт как Y^0,20 — близко к закону ¼ для термика в устойчивом воздухе (Morton, Taylor, Turner
 * 1956: высота ∝ плавучесть^¼·N^−½). Время подъёма у них ∝ 1/N и от мощности не зависит: τ у «Уоспа» вдвое дольше, чем у
 * «Грейбла» и мегатонны, — это устойчивость воздуха там и в тот день, а не мощность. Поэтому узлы τ — замеры как есть.
 * <p>
 * <b>Оценки</b>: верх и τ между узлами — по прямой в логарифмах; τ выше 1 Мт — как у 1 Мт (замеров нет); радиус шапки
 * ({@link #capRadius}) — данных мало (у 15 кт источники дают 1,5–2,3 км).
 */
public final class CloudModel {
    /**
     * Опорные точки (кт): высота верха стабилизированного облака над точкой подрыва ({@link #TOP_M}, м) и постоянная
     * подъёма ({@link #TAU_S}, с). Между ними — по прямой в логарифмах, верх за краями — продолжением крайних отрезков, τ —
     * как у крайнего узла. 1 кт — «Уосп» (подгонка верха и τ), 15 кт — «Грейбл» (верх 10 км над землёй минус 160 м, τ —
     * подгонка), 1 Мт — Glasstone и Dolan (верх — рис. 2.16, τ — подгонка табл. 2.12), 10 Мт — рис. 2.16.
     */
    private static final double[] TOP_Y = {1, 15, 1000, 10000};
    private static final double[] TOP_M = {5660, 9840, 21500, 30000};
    private static final double[] TAU_S = {227, 121, 144, 144};
    /** Радиус шапки 15 кт, м, и показатель роста: 1 Мт → ~10 км (оценка: замеров радиуса нет, источники — 1,5–2,3 км). */
    private static final double CAP_RADIUS_15KT = 2000;
    private static final double CAP_RADIUS_EXP = 0.38;
    /** Низ шапки — эта доля пути от подрыва до верха: «Грейбл» 6,1 из 10 км, «Уосп» 3,2 из 5,3 км. */
    private static final double CAP_BOTTOM = 0.6;
    /** Влажность биома, начиная с которой видно облако Вильсона. */
    private static final double WILSON_HUMIDITY = 0.3;

    private CloudModel() {
    }

    /** Высота верха облака после стабилизации над точкой подрыва, м: 1 кт — 5,7 км, 15 кт — 9,8 км, 1 Мт — 21,5 км. */
    public static double stabilizedTop(double yieldKt) {
        return Math.exp(logLine(yieldKt, TOP_M, true));
    }

    /** Радиус шапки после стабилизации, м: 2 км для 15 кт, ~10 км для 1 Мт. */
    public static double capRadius(double yieldKt) {
        return CAP_RADIUS_15KT * Math.pow(yieldKt / 15, CAP_RADIUS_EXP);
    }

    /**
     * Время стабилизации, с: три постоянных времени подъёма верха (95 % высоты): 1 кт — ~11 мин, 15 кт — ~6 мин, 1 Мт —
     * ~7 мин (к 10 минутам — 98 %, «около 10 минут» Glasstone и Dolan, §2.15).
     */
    public static double stabilizationSeconds(double yieldKt) {
        return 3 * riseTau(yieldKt);
    }

    /**
     * Высота верха облака в момент t, м над землёй под эпицентром: от верха шара наибольшего размера ({@link #start})
     * экспоненциально к верху после стабилизации над точкой подрыва ({@link #stabilizedTop}).
     */
    public static double top(double t, double hobM, double yieldKt, boolean surface) {
        double start = start(hobM, yieldKt, surface), end = end(hobM, yieldKt, surface);
        return start + (end - start) * (1 - Math.exp(-Math.max(0, t) / riseTau(yieldKt)));
    }

    /**
     * Скорость подъёма верха облака в момент t, м/с: производная {@link #top}, к стабилизации падает до нуля. Сразу после
     * подрыва — (h∞ − h₀)/τ: 1 кт — 25 м/с, 15 кт — 79, 1 Мт — 139 (табл. 2.12: в первую минуту в среднем ~134).
     */
    public static double riseSpeed(double t, double hobM, double yieldKt, boolean surface) {
        double start = start(hobM, yieldKt, surface), end = end(hobM, yieldKt, surface), tau = riseTau(yieldKt);
        return (end - start) / tau * Math.exp(-Math.max(0, t) / tau);
    }

    /** Нижняя кромка шапки в момент t, м: доля {@link #CAP_BOTTOM} пути от подрыва до верха. */
    public static double capBottom(double t, double hobM, double yieldKt, boolean surface) {
        double base = Math.max(0, hobM);
        return base + CAP_BOTTOM * (top(t, hobM, yieldKt, surface) - base);
    }

    /** Будет ли облако Вильсона при такой влажности (0..1, из биома; над водой — 1). */
    public static boolean wilsonCloud(double humidity01) {
        return humidity01 >= WILSON_HUMIDITY;
    }

    /** Постоянная времени подъёма верха, с: 1 кт — 227, 15 кт — 121, 1 Мт и выше — 144 ({@link #TAU_S}). */
    static double riseTau(double yieldKt) {
        return Math.exp(logLine(yieldKt, TAU_S, false));
    }

    /**
     * Верх облака в миг подрыва, м над землёй: верх шара наибольшего размера ({@link FireballModel#LATE_GROWTH_CAP}
     * радиуса), так что верх шара, всплывающего вместе с облаком, его не обгоняет. Замеры с ним ложатся не хуже, чем с
     * верхом шара во втором максимуме.
     */
    static double start(double hobM, double yieldKt, boolean surface) {
        return Math.max(0, hobM) + FireballModel.LATE_GROWTH_CAP * FireballModel.maxRadius(yieldKt, surface);
    }

    /** Верх после стабилизации, м над землёй: над точкой подрыва, не ниже начала. */
    static double end(double hobM, double yieldKt, boolean surface) {
        return Math.max(start(hobM, yieldKt, surface), Math.max(0, hobM) + stabilizedTop(yieldKt));
    }

    /**
     * Логарифм величины по узлам {@link #TOP_Y}: по прямой в логарифмах между узлами, за краями — продолжением крайнего
     * отрезка ({@code extend}) или значением крайнего узла.
     */
    private static double logLine(double yieldKt, double[] values, boolean extend) {
        double ly = Math.log(Math.max(yieldKt, 1e-3));
        if (!extend) ly = Solve.clamp(ly, Math.log(TOP_Y[0]), Math.log(TOP_Y[TOP_Y.length - 1]));
        int i = 1;
        while (i < TOP_Y.length - 1 && ly > Math.log(TOP_Y[i])) i++;
        double x0 = Math.log(TOP_Y[i - 1]), x1 = Math.log(TOP_Y[i]);
        double y0 = Math.log(values[i - 1]), y1 = Math.log(values[i]);
        return y0 + (ly - x0) * (y1 - y0) / (x1 - x0);
    }
}
