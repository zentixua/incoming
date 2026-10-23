package ua.zentix.incoming.strike;

import ua.zentix.incoming.entity.FlightPhase;

/**
 * Тяга стартового твердотопливного ускорителя во времени — доля тяги полки, один закон у пламени, света, облака старта
 * и звука ускорителя (сам разгон — игровая модель полёта, {@link WeaponSpec.LaunchProfile#boost}). Три участка:
 * <ol>
 * <li><b>Выход на режим</b> после поджига — за {@link #RISE} тиков: давление в камере РДТТ растёт за 0,1–0,3 с (⚠ оценка).</li>
 * <li><b>Полка</b>.</li>
 * <li><b>Спад</b> (tail-off): свод заряда прогорел, догорают его остатки, и площадь горения S падает до нуля за {@link #tail}.
 * Давление в камере — равновесное: приход газа S·ρ·a·pⁿ (закон скорости горения Сен-Робера r = a·pⁿ) равен расходу
 * через критическое сечение p·S_кр/c*, поэтому p ∝ (S/S_кр)^(1/(1 − n)) (Sutton, Biblarz, «Rocket Propulsion Elements»,
 * гл. 12). Площадь остатков убывает линейно (⚠ оценка: настоящая — по геометрии заряда). Сопло в критике — расход и тяга
 * ∝ давлению (⚠ коэффициент тяги при падении давления не меняется). Значение без скачка: на входе в спад — 1, в конце —
 * 0 с нулевым наклоном (показатель больше 1).</li>
 * </ol>
 * Отделение ускорителя — в конце фазы {@link FlightPhase#BOOST} ({@code StrikeProjectile.launchTick}), то есть после спада:
 * когда корпус отходит, огня уже нет.
 *
 * @param rise  выход на режим после поджига, тиков
 * @param burn  тиков фазы {@link FlightPhase#BOOST} до отделения (игровые, {@link WeaponSpec.LaunchProfile#boostTicks})
 * @param tail  спад в конце неё, тиков
 */
public record ThrustCurve(double rise, double burn, double tail) {
    /** Выход на тягу после поджига, тиков. */
    public static final double RISE = 6;
    /** Показатель n закона Сен-Робера у смесевого топлива на перхлорате аммония: 0,2–0,5 (Sutton, Biblarz, гл. 12) — ⚠ 0,35. */
    public static final double EXPONENT = 0.35;
    /**
     * Спад — эта доля настоящего времени работы ({@link Article.SolidMotor#burn}). ⚠ Оценка: остатки свода горят с той же
     * скоростью, что весь свод, поэтому спад к работе — как толщина остатков к своду, у зарядов ракет — единицы процентов;
     * у ускорителя «Томагавка» (12 с) — 0,6 с, у шахеда (2,5 с) — 0,13 с.
     */
    public static final double TAIL_SHARE = 0.05;

    /**
     * Кривая ускорителя m с пусковой lp: спад — настоящей длительности (доля {@link #TAIL_SHARE} настоящей работы, в тиках
     * по 20 в секунду), полка — остаток игровой фазы разгона (она короче настоящей — не по физике, {@code WeaponSpec});
     * спад — не больше половины фазы.
     */
    public static ThrustCurve of(WeaponSpec.LaunchProfile lp, Article.SolidMotor m) {
        return new ThrustCurve(RISE, lp.boostTicks(), Math.min(TAIL_SHARE * m.burn() * 20, lp.boostTicks() / 2.0));
    }

    /** Доля тяги на поджиге: выход на режим за {@link #RISE} тиков (у всех РДТТ, и без кривой). */
    public static double ignition(double age) {
        return Math.min(1, Math.max(age, 0.5) / RISE);
    }

    /**
     * Доля тяги полки в фазе ph на age тиков фазы (с долей тика): на поджиге — выход на режим, в разгоне — полка и спад к
     * отделению, в остальных фазах ускоритель не горит.
     */
    public double share(FlightPhase ph, double age) {
        if (ph == FlightPhase.IGNITION) return Math.min(1, Math.max(age, 0.5) / rise);
        if (ph != FlightPhase.BOOST) return 0;
        double left = burn - age;
        if (left >= tail) return 1;
        if (left <= 0) return 0;
        return Math.pow(left / tail, 1 / (1 - EXPONENT));
    }
}
