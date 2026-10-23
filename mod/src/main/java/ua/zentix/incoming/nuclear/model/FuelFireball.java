package ua.zentix.incoming.nuclear.model;

/**
 * Огненный шар топлива в баке изделия при ударе (CCPS 1994, «Guidelines for Evaluating the Characteristics of Vapor Cloud
 * Explosions, Flash Fires and BLEVEs»): диаметр D = 5,8·M^⅓ м, горит t = 0,45·M^⅓ с (M — кг топлива, до 30 т), с
 * поверхности излучает 200–350 кВт/м² — середина {@link #SURFACE_POWER}. Шаром горит не всё топливо: доля
 * {@link #POOL_SHARE} остаётся в воронке. Один закон у сервера (что шар поджигает) и у картинки и звука клиента
 * ({@code CloudModel}).
 */
public final class FuelFireball {
    /** Излучение поверхности шара, Вт/м²: середина 200–350 кВт/м² (CCPS). */
    public static final double SURFACE_POWER = 275e3;
    /** Джоулей в калории: световой импульс поджига — в кал/см², как у ядерки ({@link ThermalModel#IGNITE}). */
    private static final double J_PER_CAL_CM2 = 4.1868e4;
    /**
     * Доля топлива к удару, что остаётся в воронке и догорает там, а не разлетается брызгами в огненный шар: опыты Sandia с
     * крылом С-141 в масштабе ~1/42, бьющим в песок с глиной (Tieszen, Attaway, «Fuel dispersal in high-speed aircraft/soil
     * impact scenarios», SAND95-2529, 1996, OSTI 188568), — в воронке от почти нуля при ударе по касательной до 25–50 % при
     * крутом. Шахед бьёт под 60°, ракета под 45° — круто; взят нижний край: у опытов не было заряда, а взрыв БЧ рядом с баком
     * выбрасывает топливо из воронки (⚠ оценка внутри диапазона). Шар — остальное ({@link #ballKg}).
     */
    public static final double POOL_SHARE = 0.25;

    private FuelFireball() {}

    /** Сколько топлива из {@code fuelKg} кг к удару сгорает шаром, кг: всё, кроме доли воронки ({@link #POOL_SHARE}). */
    public static double ballKg(double fuelKg) {
        return Math.max(0, fuelKg) * (1 - POOL_SHARE);
    }

    /** Диаметр шара из {@code fuelKg} кг топлива в шаре ({@link #ballKg}), м. */
    public static double diameter(double fuelKg) {
        return fuelKg <= 0 ? 0 : 5.8 * Math.cbrt(fuelKg);
    }

    /** Сколько горит шар из {@code fuelKg} кг топлива в шаре, с. */
    public static double seconds(double fuelKg) {
        return fuelKg <= 0 ? 0 : 0.45 * Math.cbrt(fuelKg);
    }

    /**
     * Световой импульс на поверхность внутри шара из {@code fuelKg} кг топлива в шаре, кал/см²: излучение поверхности ×
     * время горения (поверхность в шаре видит пламя со всех сторон — оценка сверху для граней, повёрнутых от центра).
     */
    public static double exposure(double fuelKg) {
        return SURFACE_POWER * seconds(fuelKg) / J_PER_CAL_CM2;
    }
}
