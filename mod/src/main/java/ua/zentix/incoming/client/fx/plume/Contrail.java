package ua.zentix.incoming.client.fx.plume;

/**
 * Инверсионный след по критерию Шмидта–Эпплмана (Schumann, «On conditions for contrail formation from aircraft
 * exhausts», Meteorol. Z. 5, 1996). Смесь выхлопа с воздухом идёт по прямой в осях (T, e) с наклоном
 * G = EI_H₂O·c_p·p/(ε·Q·(1 − η)), Па/K; след встаёт, если эта прямая заходит за насыщение над водой: воздух холоднее
 * T_LC. При насыщенном воздухе T_LC = T_LM, T_LM ≈ −46,46 + 9,43·ln(G − 0,053) + 0,72·ln²(G − 0,053) °C; при
 * влажности U над водой T_LC — корень e_w(T_LM) − U·e_w(T) = G·(T_LM − T). Держится след, пока воздух пересыщен
 * надо льдом; иначе лёд испаряется, когда разбавление снимает избыток пара ({@link #dryAt}).
 * <p>
 * Давление насыщения — формулы Магнуса по WMO (CIMO Guide, 2008, прил. 4.B): над водой 6,112·e^(17,62t/(243,12 + t)),
 * надо льдом 6,112·e^(22,46t/(272,62 + t)) гПа, t в °C.
 */
public final class Contrail {
    /** Теплоёмкость воздуха, Дж/(кг·K). */
    static final double CP_AIR = 1004;
    /** Отношение молярных масс воды и воздуха. */
    static final double EPSILON = 0.622;
    /** Газовая постоянная водяного пара, Дж/(кг·K). */
    static final double R_VAPOUR = 461.5;
    private static final double C = 273.15;

    private Contrail() {}

    /** Наклон линии смешения G, Па/K, у двигателя на давлении p, Па. */
    public static double slope(Motor m, double p) {
        return m.water() * CP_AIR * p / (EPSILON * m.heat() * (1 - m.efficiency()));
    }

    /** Пороговая температура T_LM при насыщенном воздухе, K (для G ≤ 0,053 следа нет — 0). */
    public static double thresholdSaturated(double g) {
        if (g <= 0.053) return 0;
        double l = Math.log(g - 0.053);
        return C - 46.46 + 9.43 * l + 0.72 * l * l;
    }

    /** Пороговая температура T_LC при влажности над водой u (0..1), K. */
    public static double threshold(double g, double u) {
        double tlm = thresholdSaturated(g);
        if (tlm <= 0 || u >= 1) return tlm;
        double hi = tlm, lo = tlm - 80, ew = waterSaturation(tlm);
        for (int i = 0; i < 60; i++) {
            double t = (hi + lo) / 2, f = ew - u * waterSaturation(t) - g * (tlm - t);
            // f растёт с температурой: у T_LM больше нуля, на 80 K холоднее — меньше
            if (f > 0) hi = t;
            else lo = t;
        }
        return lo;
    }

    /** Встаёт ли след у двигателя в воздухе температуры t (K), давления p (Па), влажности над водой u. */
    public static boolean forms(Motor m, double t, double p, double u) {
        return m.water() > 0 && m.heat() > 0 && t < threshold(slope(m, p), u);
    }

    /**
     * Сколько водяного пара на кубометр не хватает воздуху до насыщения надо льдом, кг/м³ (0 и меньше — пересыщен:
     * след держится).
     */
    public static double iceDeficit(double t, double u) {
        double ei = iceSaturation(t), e = u * waterSaturation(t);
        return (ei - e) / (R_VAPOUR * t);
    }

    /**
     * σ следа, при котором лёд испаряется: пар следа λ_w (кг/м) на гауссовом сечении 2πσ² снимает недостачу воздуха
     * deficit (кг/м³) — дальше σ = √(λ_w/(2π·deficit)); пересыщенный воздух — бесконечность.
     */
    public static double dryAt(double water, double deficit) {
        return deficit <= 0 ? Double.POSITIVE_INFINITY : Math.sqrt(water / (2 * Math.PI * deficit));
    }

    /** Давление насыщения над водой, Па. */
    public static double waterSaturation(double t) {
        double c = t - C;
        return 611.2 * Math.exp(17.62 * c / (243.12 + c));
    }

    /** Давление насыщения надо льдом, Па. */
    public static double iceSaturation(double t) {
        double c = t - C;
        return 611.2 * Math.exp(22.46 * c / (272.62 + c));
    }
}
