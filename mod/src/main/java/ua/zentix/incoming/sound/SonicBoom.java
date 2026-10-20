package ua.zentix.incoming.sound;

/**
 * N-волна сверхзвукового корпуса у уха — упрощённый метод Карлсона (H. W. Carlson, «Simplified sonic-boom prediction»,
 * NASA TP-1122, 1978, ф. 1 и 2), без Minecraft:
 * <pre>
 * Δp = K<sub>p</sub>·K<sub>R</sub>·√(p<sub>v</sub>·p<sub>g</sub>)·(M² − 1)^⅛·h<sub>e</sub>^−¾·l^¾·K<sub>S</sub>
 * Δt = K<sub>t</sub>·(3,42 / a<sub>v</sub>)·M / (M² − 1)^⅜·h<sub>e</sub>^¼·l^¾·K<sub>S</sub>
 * </pre>
 * У земли атмосфера однородна: K<sub>p</sub> = K<sub>t</sub> = 1, p<sub>v</sub> = p<sub>g</sub>; отражение от земли
 * K<sub>R</sub> = 2 (так у Карлсона). Форма корпуса без подъёмной силы (снаряд, бомба, ракета) — по миделю:
 * K<sub>S</sub> = 0,685·√A<sub>max</sub> / (l^¾·l<sub>e</sub>^¼), l<sub>e</sub> = l (параметр формы 0,65–0,69 в
 * примерах Карлсона, рис. 2). h<sub>e</sub> — расстояние от линии полёта до уха; метод — для дальнего поля, ближе
 * длины корпуса берётся сама длина.
 */
public final class SonicBoom {
    /** Отражение от земли и параметр формы (TP-1122). */
    static final double REFLECTION = 2, SHAPE = 0.685;
    /** Давление у земли, Па. */
    static final double PRESSURE = Air.KPA * 1000;

    private SonicBoom() {}

    /** Скачок давления и длительность N-волны. */
    public record Wave(double pressure, double seconds) {}

    /**
     * N-волна у уха.
     *
     * @param mach     число Маха корпуса (больше 1)
     * @param distance расстояние от линии полёта до уха, м
     * @param body     корпус
     */
    public static Wave at(double mach, double distance, Noise.Body body) {
        double l = body.length();
        return signature(mach, Math.max(distance, l), l, shape(body.area(), l, l), PRESSURE, PRESSURE, Noise.SOUND, 1, 1);
    }

    /** Множитель формы K<sub>S</sub> по наибольшему сечению {@code area}, м², длине {@code l} и длине до него {@code le}, м. */
    static double shape(double area, double l, double le) {
        return SHAPE * Math.sqrt(area) / (Math.pow(l, 0.75) * Math.pow(le, 0.25));
    }

    /**
     * Ф. 1 и 2 TP-1122 целиком.
     *
     * @param h  эффективная высота, м
     * @param l  длина, м
     * @param ks множитель формы
     * @param pv давление у корпуса, Па
     * @param pg давление у земли, Па
     * @param av скорость звука у корпуса, м/с
     * @param kp множитель давления атмосферы
     * @param kt множитель длительности атмосферы
     */
    static Wave signature(double mach, double h, double l, double ks, double pv, double pg, double av, double kp, double kt) {
        double m2 = mach * mach - 1, scale = Math.pow(l, 0.75) * ks;
        double dp = kp * REFLECTION * Math.sqrt(pv * pg) * Math.pow(m2, 0.125) * Math.pow(h, -0.75) * scale;
        double dt = kt * 3.42 / av * mach / Math.pow(m2, 0.375) * Math.pow(h, 0.25) * scale;
        return new Wave(dp, dt);
    }
}
