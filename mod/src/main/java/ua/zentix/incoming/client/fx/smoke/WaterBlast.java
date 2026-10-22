package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.nuclear.model.WaterModel;

/**
 * Обычный взрыв на воде (снаряд рвётся о гладь или сразу под ней), без Minecraft: столб брызг, корона, водяная пыль,
 * что остаётся, когда вода падает. Метры, секунды, килограммы ТНТ.
 * <ul>
 * <li><b>Размер</b> — тот же закон, что у столба ядерки ({@link WaterModel}): высота и радиус столба подрыва на
 * поверхности растут как кубический корень заряда от «Бейкера» (Glasstone и Dolan 1977, §2.47–2.65: 23 кт на 27 м —
 * столб 1,8 км, 600 м в поперечнике; у подрыва на поверхности — доля {@code SURFACE_COLUMN}). В воде энергия ядерного
 * подрыва, как у взрывчатки, уходит в ударную волну и пузырь (светом в воде её не уносит), поэтому закон подобия по
 * энергии — общий. Сверка: у 9,1 кг ТНТ на лучшей глубине (10 м) столб по таблицам Michael и Swisdak — 23 м
 * (Swisdak, «Explosion effects and properties II», NSWC TR 76-116, 1978, по WIT Trans. Built Env. 87, 2006); у того же
 * заряда на поверхности здесь — ~8 м: подрыв на поверхности выбрасывает меньше воды (⚠ оценка через долю «Бейкера»).</li>
 * <li><b>Время</b> — баллистика, а не подобие по энергии: у малого заряда воду держит тяжесть (число Фруда), и столб
 * высотой H встаёт со скоростью √(2gH) за √(2H/g) и столько же падает. У «Бейкера» по этому закону — ~15 с до верха,
 * у шахеда — ~1,7 с.</li>
 * <li><b>Вода</b> — масса столба по массе «Бейкера» (Glasstone и Dolan, §2.52: ~1 млн т), пропорционально заряду;
 * ослабление брызг — капли радиуса {@link #DROP_R} м: 3/(2ρr) м²/кг (геометрическая оптика); в клуб она идёт
 * каплями сетки ({@link CloudModel#DROP_K}, капли 5 мкм) — пересчётом массы на то же ослабление. Падая, столб
 * оставляет водяную пыль — долю {@link #MIST} мелких капель ({@link #MIST_DROP_R}) кольцом базисной волны ({@link #surgeRadius}, закон
 * ядерки), её сносит ветром и расплывает, как облако.</li>
 * </ul>
 */
public final class WaterBlast {
    /** Заряд «Бейкера», кг ТНТ. */
    static final double BAKER_KG = 23e6;
    /** Масса воды в столбе «Бейкера», кг (Glasstone, Dolan §2.52: около миллиона тонн; ⚠ по памяти). */
    static final double BAKER_WATER_KG = 1e9;
    /** Радиус капель столба, м (О: брызги — миллиметры). */
    static final double DROP_R = 0.5e-3;
    /** Доля воды столба, что остаётся водяной пылью, когда он падает (О: 1–5 %). */
    static final double MIST = 0.02;
    /**
     * Радиус капель водяной пыли, м. В воздухе после падения столба висят только капли, что оседают медленнее, чем
     * волна держится над водой: по Стоксу v = 2ρgr²/(9μ) у 50 мкм — 0,3 м/с (за 10 с — 3 м при высоте волны в метры), а
     * миллиметровые капли столба падают со скоростью ~4 м/с и уходят в воду сразу (размер — О, по скорости оседания).
     */
    static final double MIST_DROP_R = 50e-6;
    /** Вязкость воздуха у земли, Па·с (15 °C). */
    static final double AIR_MU = 1.81e-5;

    /** Скорость оседания капель водяной пыли по Стоксу, м/с: 2ρgr²/(9μ). */
    static double mistSettling() {
        return 2 * 1000 * G * MIST_DROP_R * MIST_DROP_R / (9 * AIR_MU);
    }

    /**
     * Доля водяной пыли, что ещё в воздухе через since секунд после вершины столба: слой высотой с базисную волну,
     * перемешанный, теряет капли через дно со скоростью оседания — e^(−v·t/h) (испарение не считается: оно только
     * укорачивает жизнь).
     */
    double mistLeft(double since) {
        return Math.exp(-mistSettling() * Math.max(0, since) / surgeTop());
    }
    static final double G = 9.80665;

    /** Высота и радиус столба, м; время подъёма, с; вода, кг. */
    public final double height, radius, rise, water;
    private final CloudModel.Air air;
    /** Заряд, кт. */
    private final double kt;

    public WaterBlast(double tntKg, CloudModel.Air air) {
        this.air = air;
        kt = tntKg / 1e6;
        height = WaterModel.columnHeight(kt);
        radius = WaterModel.columnRadius(kt);
        rise = Math.sqrt(2 * height / G);
        water = BAKER_WATER_KG * tntKg / BAKER_KG;
    }

    /** Верх столба в возрасте t, м: бросок со скоростью √(2gH) (до вершины), падение — обратно к воде. */
    public double top(double t) {
        if (t <= 0 || t >= 2 * rise) return 0;
        double v0 = G * rise;
        return v0 * t - 0.5 * G * t * t;
    }

    /** Доля воды, что ещё в столбе (не упала): до вершины — вся, к 2·подъёма — ничего. */
    public double aloft(double t) {
        if (t <= rise) return 1;
        double u = Math.min(1, (t - rise) / rise);
        return 1 - u * u;
    }

    /** Масса капель сетки (5 мкм) с тем же ослаблением, что у {@code kg} брызг столба. */
    static double asGridDrops(double kg) {
        return asGridDrops(kg, DROP_R);
    }

    /** То же у капель радиуса {@code r}, м: ослабление 3/(2ρr) м²/кг. */
    static double asGridDrops(double kg, double r) {
        return kg * 3 / (2 * 1000 * r) / CloudModel.DROP_K;
    }

    /** Клубов кольца базисной волны. */
    static final int RING = 8;

    /**
     * Базисная волна: падая, вода столба растекается кольцом брызг — тот же закон, что у ядерки ({@link WaterModel#surgeReach},
     * {@link WaterModel#surgeTop}: доля «Бейкера» по кубическому корню), начало — когда вода начинает падать (вершина
     * столба), растекается со скоростью падения √(2gH) (постоянная — предел радиуса на неё, ⚠ оценка: у «Бейкера»
     * волна идёт ~25–30 м/с), брызги — доля {@link #MIST} воды столба, расплываются по Бриггсу и сносятся ветром.
     */
    public double surgeRadius(double since) {
        if (since <= 0) return 0;
        double reach = surgeReach(), tau = reach / Math.sqrt(2 * G * height);
        return radius + reach * (1 - Math.exp(-since / Math.max(tau, 1e-3)));
    }

    double surgeReach() {
        return WaterModel.surgeReach(kt);
    }

    double surgeTop() {
        return Math.max(1, WaterModel.surgeTop(kt));
    }

    /** Сколько живёт водяная пыль после вершины столба, с: пока толща клуба кольца поперёк больше порога (с оседанием). */
    public double mistLife() {
        double ext = CloudModel.DROP_K * asGridDrops(MIST * water, MIST_DROP_R) / RING;
        double lo = 0, hi = CloudModel.LIFE_MAX;
        for (int i = 0; i < 30; i++) {
            double mid = 0.5 * (lo + hi);
            if (ext * mistLeft(mid) / (2 * Math.PI * mistSigmaH(mid) * mistSigmaV(mid)) >= CloudModel.SEEN) lo = mid;
            else hi = mid;
        }
        return rise + lo;
    }

    /** σ клуба кольца через since секунд после вершины столба: шаг кольца, высота волны и расплывание по Бриггсу. */
    double mistSigmaH(double since) {
        double s = CloudModel.spreadH(air.spread(), path(since)), ring = Math.PI * surgeRadius(since) / RING;
        return Math.sqrt(ring * ring + s * s);
    }

    double mistSigmaV(double since) {
        double s = CloudModel.spreadV(air.spread(), path(since)), s0 = surgeTop() / 2;
        return Math.sqrt(s0 * s0 + s * s);
    }

    private double path(double since) {
        return Math.max(1, Math.hypot(air.windX(), air.windZ()) * air.wind(surgeTop())) * Math.max(0, since);
    }

    /**
     * Клубы брызг в возрасте t от места взрыва на глади (центр от точки взрыва, м): столб — вертикальный отрезок до
     * верха с σ поперёк в радиус столба, корона — клуб на верху шире столба (струи тормозят и раскрываются), водяная
     * пыль — кольцом базисной волны, когда вода падает, у воды, сносится ветром. false — брызг уже нет.
     */
    public boolean blobs(double t, SmokeGrid.Sink out, double x, double y, double z) {
        if (t < 0) return true;
        double top = top(t), aloft = aloft(t), drops = asGridDrops(water);
        if (top > 0 && aloft > 0) {
            double column = 0.7 * drops * aloft, crown = 0.3 * drops * aloft;
            double half = 0.5 * top;
            out.segment(x, y + half, z, 0, half, 0, radius / 2, Math.max(radius / 2, 0.25 * top), 0, 0, column, 1, 1, 1, CloudModel.T_AIR_K,
                    G * Math.max(0, rise - t));
            // корона раскрывается к вершине: вдвое шире столба (у «Бейкера» корона ~втрое шире, WaterModel.CROWN_R — 1,4)
            double open = Math.min(1, t / rise);
            out.blob(x, y + top, z, radius * (0.5 + 0.5 * open), radius * 0.5, 0, 0, crown, 1, 1, 1, CloudModel.T_AIR_K, 0);
        }
        double since = t - rise;
        if (since > 0) {
            if (t > mistLife()) return false;
            // кольцо набирает воду, пока столб падает (до 2·подъёма)
            double mist = asGridDrops(MIST * water, MIST_DROP_R) * Math.min(1, since / rise) * mistLeft(since) / RING;
            double r = surgeRadius(since), lean = air.wind(surgeTop()) * since, sh = mistSigmaH(since), sv = mistSigmaV(since);
            for (int i = 0; i < RING; i++) {
                double a = 2 * Math.PI * i / RING;
                out.blob(x + r * Math.cos(a) + air.windX() * lean, y + sv, z + r * Math.sin(a) + air.windZ() * lean, sh, sv, 0, 0, mist, 1, 1, 1,
                        CloudModel.T_AIR_K, 0);
            }
        }
        return true;
    }
}
