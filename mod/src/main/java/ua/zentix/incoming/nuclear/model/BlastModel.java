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
}
