package ua.zentix.incoming.nuclear.model;

/**
 * Огненный шар топлива в баке изделия при ударе (CCPS 1994, «Guidelines for Evaluating the Characteristics of Vapor Cloud
 * Explosions, Flash Fires and BLEVEs»): диаметр D = 5,8·M^⅓ м, горит t = 0,45·M^⅓ с (M — кг топлива, до 30 т), с
 * поверхности излучает 200–350 кВт/м² — середина {@link #SURFACE_POWER}. Один закон у сервера (что шар поджигает) и у
 * картинки и звука клиента ({@code CloudModel}).
 */
public final class FuelFireball {
    /** Излучение поверхности шара, Вт/м²: середина 200–350 кВт/м² (CCPS). */
    public static final double SURFACE_POWER = 275e3;
    /** Джоулей в калории: световой импульс поджига — в кал/см², как у ядерки ({@link ThermalModel#IGNITE}). */
    private static final double J_PER_CAL_CM2 = 4.1868e4;

    private FuelFireball() {}

    /** Диаметр шара, м. */
    public static double diameter(double fuelKg) {
        return fuelKg <= 0 ? 0 : 5.8 * Math.cbrt(fuelKg);
    }

    /** Сколько горит шар, с. */
    public static double seconds(double fuelKg) {
        return fuelKg <= 0 ? 0 : 0.45 * Math.cbrt(fuelKg);
    }

    /**
     * Световой импульс на поверхность внутри шара, кал/см²: излучение поверхности × время горения (поверхность в шаре
     * видит пламя со всех сторон — оценка сверху для граней, повёрнутых от центра).
     */
    public static double exposure(double fuelKg) {
        return SURFACE_POWER * seconds(fuelKg) / J_PER_CAL_CM2;
    }
}
