package ua.zentix.incoming.util;

/**
 * Стандартная атмосфера ISO 2533 (она же ICAO и US 1976 до 20 км): тропосфера до 11 км — температура падает на 6,5 K/км,
 * давление p = 101 325·(T/288,15)^5,25588; от 11 до 20 км — 216,65 K, давление спадает в e раз на 6 341,6 м.
 * Высота — метры над уровнем моря (в мире — блоки над уровнем моря Minecraft).
 */
public final class StandardAtmosphere {
    /** У уровня моря: температура, K, и давление, Па. */
    public static final double T0 = 288.15, P0 = 101_325;
    /** Градиент температуры в тропосфере, K/м. */
    static final double LAPSE = 0.0065;
    /** Показатель g₀·M/(R·L) = 9,80665·0,0289644/(8,31447·0,0065). */
    static final double EXPONENT = 5.25588;
    /** Тропопауза: высота, м, температура, K, давление, Па. */
    static final double TROPOPAUSE = 11_000, T11 = 216.65, P11 = 22_632.06;
    /** Шкала высот над тропопаузой R·T/g₀ = 287,053·216,65/9,80665, м. */
    static final double SCALE11 = 6_341.62;
    /** Газовая постоянная сухого воздуха, Дж/(кг·K). */
    public static final double R_AIR = 287.053;

    private StandardAtmosphere() {}

    /** Температура на высоте h метров, K (ниже уровня моря — тот же закон, выше 20 км — как на 20 км). */
    public static double temperature(double h) {
        return h < TROPOPAUSE ? T0 - LAPSE * h : T11;
    }

    /** Давление на высоте h метров, Па. */
    public static double pressure(double h) {
        if (h < TROPOPAUSE) return P0 * Math.pow(temperature(h) / T0, EXPONENT);
        return P11 * Math.exp(-(Math.min(h, 20_000) - TROPOPAUSE) / SCALE11);
    }

    /** Плотность воздуха на высоте h метров, кг/м³. */
    public static double density(double h) {
        return pressure(h) / (R_AIR * temperature(h));
    }
}
