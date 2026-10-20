package ua.zentix.incoming.nuclear.model;

/**
 * Ударная волна: соотношения Ренкина–Гюгонио за фронтом (скоростной напор, ветер, скорость фронта) и давление наземного
 * взрыва обычного заряда по Кинни–Грэхему. Давление ядерного подрыва — {@link HobBlast} (по высоте подрыва).
 * Дальность наклонная, в метрах (= блоках); заряд — в кг ТНТ.
 */
public final class BlastModel {
    /** Атмосферное давление, кПа. */
    public static final double P0 = 101.325;
    /** Скорость звука, м/с: к ней стремится фронт вдали. */
    public static final double SOUND_SPEED = 343.0;
    /** кПа в одном psi. */
    public static final double KPA_PER_PSI = 6.895;
    /** Усиление наземного взрыва отражением от земли (часть энергии уходит в воронку и грунт — не вдвое). */
    private static final double GROUND_REFLECTION = 1.8;

    private BlastModel() {
    }

    /**
     * Избыточное давление наземного взрыва обычного заряда {@code tntKg} кг ТНТ на наклонной дальности R, кПа: волна
     * от земли отражается ({@link #GROUND_REFLECTION}).
     */
    public static double surfaceOverpressureKpa(double slantRangeM, double tntKg) {
        return kinneyGraham(slantRangeM, tntKg * GROUND_REFLECTION);
    }

    /** Избыточное давление заряда {@code tntKg} кг ТНТ в свободном воздухе (разрыв в воздухе, без отражения от земли), кПа. */
    public static double freeAirOverpressureKpa(double slantRangeM, double tntKg) {
        return kinneyGraham(slantRangeM, tntKg);
    }

    /** Давление обычного заряда на наклонной дальности, кПа: на земле ({@link #surfaceOverpressureKpa}) или в воздухе. */
    public static double overpressureKpa(double slantRangeM, double tntKg, boolean surface) {
        return surface ? surfaceOverpressureKpa(slantRangeM, tntKg) : freeAirOverpressureKpa(slantRangeM, tntKg);
    }

    /**
     * Положительная фаза волны обычного заряда {@code tntKg} кг ТНТ на наклонной дальности R, с: Кинни–Грэхем для
     * химического ВВ (Kinney &amp; Graham 1985, «Explosive Shocks in Air»; формула — по handoff ref/formulas.md §3, с
     * книгой в этой работе не сверена): t₊/∛W = 980·(1 + (Z/0,54)¹⁰) / ((1 + (Z/0,02)³)·(1 + (Z/0,74)⁶)·√(1 + (Z/6,9)²)) мс/кг^⅓,
     * Z = R/∛W; на земле — W × {@link #GROUND_REFLECTION}, как у давления. Вдали формула выходит на постоянную 4,2 мс·∛W
     * (у 50 кг на земле — 19 мс), вблизи — миллисекунды: у обычного взрыва вся фаза слышна, у ядерного вдали — нет.
     */
    public static double chemicalPhaseSeconds(double slantRangeM, double tntKg, boolean surface) {
        double w = Math.cbrt(Math.max(1e-9, surface ? tntKg * GROUND_REFLECTION : tntKg));
        double z = Math.max(1e-6, slantRangeM / w);
        double a = z / 0.02, b = z / 0.74;
        return 1e-3 * w * 980 * (1 + Math.pow(z / 0.54, 10)) / ((1 + a * a * a) * (1 + Math.pow(b, 6)) * Math.sqrt(1 + (z / 6.9) * (z / 6.9)));
    }

    /** Дальность, где давление наземного взрыва заряда {@code tntKg} падает до {@code kpa} (0 — нет заряда или давления). */
    public static double rangeForSurfaceOverpressure(double kpa, double tntKg) {
        if (tntKg <= 0 || kpa >= surfaceOverpressureKpa(0, tntKg)) return 0;
        return Solve.decreasingRoot(r -> surfaceOverpressureKpa(r, tntKg), kpa, 1e-3, 1e8);
    }

    /** Кинни–Грэхем: давление на дальности R от заряда W кг ТНТ в свободном воздухе, кПа (масштаб Z = R / ∛W). */
    private static double kinneyGraham(double rangeM, double chargeKg) {
        double z = Math.max(0, rangeM) / Math.cbrt(chargeKg);
        double a = z / 4.5, b = z / 0.048, c = z / 0.32, d = z / 1.35;
        return P0 * 808 * (1 + a * a) / Math.sqrt((1 + b * b) * (1 + c * c) * (1 + d * d));
    }

    public static double psi(double kpa) {
        return kpa / KPA_PER_PSI;
    }

    public static double kpa(double psi) {
        return psi * KPA_PER_PSI;
    }

    /** Скоростной напор за фронтом, кПа: q = 2.5·P² / (7·P0 + P). */
    public static double dynamicPressureKpa(double overpressureKpa) {
        return 2.5 * overpressureKpa * overpressureKpa / (7 * P0 + overpressureKpa);
    }

    /** Скорость фронта по Ренкину–Гюгонио, м/с: U = 343·√(1 + 6P/(7·P0)). */
    public static double frontSpeed(double overpressureKpa) {
        return SOUND_SPEED * Math.sqrt(1 + 6 * Math.max(0, overpressureKpa) / (7 * P0));
    }

    /**
     * Скорость воздуха сразу за фронтом по Ренкину–Гюгонио, м/с: u = 5P/(7·P0) · 343 / √(1 + 6P/(7·P0)) — 5 psi ≈ 72 м/с,
     * 1 psi ≈ 17 м/с.
     */
    public static double windSpeed(double overpressureKpa) {
        double x = Math.max(0, overpressureKpa) / P0;
        return SOUND_SPEED * 5 * x / 7 / Math.sqrt(1 + 6 * x / 7);
    }

    /**
     * Длительность положительной фазы у слушателя на наклонной дальности R, с: Кинни–Грэхем для ядерного взрыва
     * (KG85 NE, табл. XI; формула и проверка по таблице — Garcés 2019, Infrasound Monitoring for Atmospheric Studies,
     * гл. 8, прил. 4: 2 кт на 8 км — 0,67 с, 1 т на 1 км — 0,057 с), отражение от земли — как у давления
     * ({@link #GROUND_REFLECTION}): W = 1.8·Y кт, Z = R / ∛W, м/кт^⅓. Таблица — от Z = 35 (ближе — как на её краю), до
     * 5000; дальше формула выходит на постоянную 0,77·∛W (как ANSI S2.20: 1 кт на 10 км — 0,52 с, на 100 км — 0,93).
     */
    public static double positivePhaseSeconds(double slantRangeM, double yieldKt) {
        double w = Math.cbrt(GROUND_REFLECTION * Math.max(1e-6, yieldKt));
        double z = Math.max(35, slantRangeM / w);
        double a = z / 100, b = z / 285;
        return w * 0.18 * Math.sqrt(1 + a * a * a) / (Math.sqrt(1 + z / 40) * Math.pow(1 + Math.pow(b, 5), 1 / 6.0)
                * Math.pow(1 + z / 50_000, 1 / 6.0));
    }
}
