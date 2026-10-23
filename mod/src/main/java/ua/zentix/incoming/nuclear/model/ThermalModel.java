package ua.zentix.incoming.nuclear.model;

import ua.zentix.incoming.util.Visibility;

/**
 * Световой импульс: Q = f·Y·10¹²·τ / (4π·(100·R)²) кал/см², f — доля энергии в свете: 0.35 воздушный, 0.18 наземный.
 * Пропускание воздуха τ = exp(−(R/V)^0.544) — прямой и рассеянный свет вместе, подбор по кривым Glasstone 1964, рис. 7.104
 * (видимость 10 и 50 миль): ±0,03 до половины видимости, при видимости меньше 16 км (дождь, гроза) — продолжение
 * подбора. R, V — метры.
 */
public final class ThermalModel {
    /** Ожог 1-й степени, кал/см². */
    public static final double BURN_1 = 3;
    /** Ожог 2-й степени. */
    public static final double BURN_2 = 5;
    /** Ожог 3-й степени. */
    public static final double BURN_3 = 8;
    /** Воспламенение листвы, травы, шерсти. */
    public static final double IGNITE = 5;
    /** Световой импульс, кал/см², при котором горючее руин ядерки загорается наверняка ({@code RuinPlanner}). */
    public static final double IGNITE_ALL = 20;
    /** Видимость, м: ясно, дождь, гроза — та же, что у картинки ({@link Visibility}). */
    public static final double VISIBILITY_CLEAR = Visibility.CLEAR;
    public static final double VISIBILITY_RAIN = Visibility.RAIN;
    public static final double VISIBILITY_THUNDER = Visibility.THUNDER;
    /** Показатель степени пропускания по Glasstone 1964, рис. 7.104. */
    private static final double TRANSMITTANCE_POWER = 0.544;

    private ThermalModel() {
    }

    /** Плотность световой энергии на наклонной дальности R, кал/см². */
    public static double fluenceCalPerCm2(double slantRangeM, double yieldKt, boolean surfaceBurst, double visibilityM) {
        double r = Math.max(slantRangeM, 1);
        double f = surfaceBurst ? 0.18 : 0.35;
        double rCm = 100 * r;
        return f * yieldKt * 1e12 * transmittance(r, visibilityM) / (4 * Math.PI * rCm * rCm);
    }

    /** Доля светового импульса, что проходит наклонную дальность R при видимости V (прямой и рассеянный свет). */
    public static double transmittance(double slantRangeM, double visibilityM) {
        return Math.exp(-Math.pow(Math.max(slantRangeM, 0) / visibilityM, TRANSMITTANCE_POWER));
    }

    /** Дальность, где плотность энергии падает до q, м. */
    public static double rangeForFluence(double q, double yieldKt, boolean surfaceBurst, double visibilityM) {
        return Solve.decreasingRoot(r -> fluenceCalPerCm2(r, yieldKt, surfaceBurst, visibilityM), q, 1, 1e8);
    }
}
