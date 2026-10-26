package ua.zentix.incoming.sound;

/**
 * Air sound absorption — ISO 9613-1:1993, eqs. (3)–(5), without Minecraft (unit-tested). Recording conditions:
 * 20 °C, 70% humidity, 101.325 kPa, giving sound speed 343 m/s ({@code BlastModel.SOUND_SPEED});
 * {@code tools/build_sounds.py} adds “this many meters of air” to distant recordings under these conditions
 * ({@code AIR_DB_KM} table) — recording calibration. Current sound path uses world surface humidity
 * ({@code util.Air#surfaceHumidity}, via {@link Propagation.Weather}); world has no temperature — use 20 °C.
 */
public final class Air {
    /** Recording conditions: temperature, °C; relative humidity, %; pressure, kPa. */
    public static final double CELSIUS = 20, HUMIDITY = 70, KPA = 101.325;
    /** Air density at these conditions, kg/m³ (ISO 2533: 1.204 at 20 °C). */
    public static final double DENSITY = 1.204;

    private Air() {}

    /** Absorption at frequency f, Hz, under recording conditions, dB/m. */
    public static double alpha(double f) {
        return alpha(f, CELSIUS, HUMIDITY, KPA);
    }

    /** Absorption at frequency f, Hz, humidity {@code humidity} (0..1) and 20 °C, dB/m. */
    public static double alphaAt(double f, double humidity) {
        return alpha(f, CELSIUS, 100 * humidity, KPA);
    }

    /**
     * Absorption, dB/m (ISO 9613-1, eq. 5): classical and rotational first term, oxygen and nitrogen vibrational relaxation
     * second and third; relaxation frequencies eqs. (3) and (4), water-vapor molar fraction app. B.
     *
     * @param f        frequency, Hz
     * @param celsius  temperature, °C
     * @param humidity relative humidity, %
     * @param kpa      pressure, kPa
     */
    public static double alpha(double f, double celsius, double humidity, double kpa) {
        double t = celsius + 273.15, t0 = 293.15, t01 = 273.16, pr = 101.325;
        double pa = kpa / pr, tr = t / t0;
        // saturation vapor pressure (app. B, eqs. B.1–B.3) and vapor molar fraction, %
        double c = -6.8346 * Math.pow(t01 / t, 1.261) + 4.6151;
        double h = humidity * Math.pow(10, c) / pa;
        double frO = pa * (24 + 4.04e4 * h * (0.02 + h) / (0.391 + h));
        double frN = pa / Math.sqrt(tr) * (9 + 280 * h * Math.exp(-4.170 * (Math.pow(tr, -1.0 / 3) - 1)));
        double f2 = f * f;
        return 8.686 * f2 * (1.84e-11 / pa * Math.sqrt(tr)
                + Math.pow(tr, -2.5) * (0.01275 * Math.exp(-2239.1 / t) / (frO + f2 / frO)
                + 0.1068 * Math.exp(-3352.0 / t) / (frN + f2 / frN)));
    }
}
