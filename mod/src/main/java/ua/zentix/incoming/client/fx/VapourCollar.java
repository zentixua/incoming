package ua.zentix.incoming.client.fx;

import ua.zentix.incoming.util.StandardAtmosphere;

/**
 * «Воротник» пара у тела около скорости звука — без Minecraft. Над телом на околозвуковой скорости — зона разрежения:
 * воздух в ней расширяется и остывает, и во влажном воздухе пар конденсируется в облачко (сингулярность
 * Прандтля–Глауэрта на снимках — около М 0,9–1,1). В сухом воздухе и на скорости ниже — ничего. Пределы — оценка: точнее
 * их даёт только расчёт обтекания конкретного корпуса и точки росы, а этого у нас нет.
 */
public final class VapourCollar {
    /** Околозвуковое: от и до этого числа Маха (оценка по снимкам). */
    static final double MACH_MIN = 0.9, MACH_MAX = 1.1;
    /**
     * Влажнее этого — пару хватает охлаждения в зоне разрежения (оценка: у земли охлаждение там — градусы, точка росы
     * в пределах нескольких градусов при относительной влажности от ~0,7).
     */
    static final double HUMIDITY = 0.7;

    private VapourCollar() {}

    /** Число Маха: скорость {@code speed} м/с на высоте {@code altitude} м стандартной атмосферы. */
    public static double mach(double speed, double altitude) {
        return speed / Math.sqrt(1.4 * StandardAtmosphere.R_AIR * StandardAtmosphere.temperature(altitude));
    }

    /** Есть ли воротник при числе Маха {@code mach} и относительной влажности {@code humidity} (0..1). */
    public static boolean forms(double mach, double humidity) {
        return mach >= MACH_MIN && mach <= MACH_MAX && humidity >= HUMIDITY;
    }
}
