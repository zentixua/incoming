package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.DustModel;

/**
 * Облако обычного взрыва по законам, без Minecraft: что поднялось, куда и как долго его видно — из заряда, топлива, грунта
 * и воздуха. Метры, секунды, килограммы; блок — метр. Картинку из него делают сетка дыма ({@link SmokeGrid}: облако —
 * гауссовы клубы {@link #blobs}), вспышка и дальний вид ({@code FarBlasts}).
 * <p>
 * <b>Тепло.</b> Облако всплывает от тепла, оставшегося в газе: у взрывчатки — {@link #HE_HEAT} на кг ТНТ, у топлива —
 * теплота сгорания без доли, ушедшей излучением огненного шара ({@link #RADIANT}). Плавучесть всего тепла Q —
 * B = g·Q / (ρ·c_p·T) (м⁴/с²).
 * <p>
 * <b>Подъём</b> — термик Мортона–Тейлора–Тёрнера (Proc. R. Soc. A 234, 1956; уравнения в форме Bhamidipati &amp; Woods,
 * JFM 2023, ур. 2.1–2.3): сфера радиуса R поднимается со скоростью W, подсасывая воздух через поверхность со скоростью αW
 * (α = {@value #ALPHA} — опыты MTT), ускоряется плавучестью g', а в устойчивом воздухе (частота Брента–Вяйсяля N) теряет её:
 * d(R³)/dt = 3α·R²W, d(R³W)/dt = R³g', d(R³g')/dt = −N²·R³W. У точечного источника наибольшая высота —
 * (8F/(α³N²))^¼ при F = R³g' (B = 4π/3·F), за π/N секунд; радиус — α·высоты. Дальше облако стоит на этой высоте и
 * расплывается турбулентностью воздуха (Паскуилл–Гиффорд, формулы Бриггса для открытой местности, по пути ветра x = u·t),
 * уходя с ветром на своей высоте. Высоту и радиус в первые секунды задаёт огненный шар: начальный объём — тот, в котором
 * тепло Q держит температуру шара ({@link #FUEL_BALL_K} или конец шара взрывчатки).
 * <p>
 * <b>Огненные шары.</b> Шар топлива — CCPS (1994), «Guidelines for Evaluating the Characteristics of Vapor Cloud
 * Explosions, Flash Fires and BLEVEs»: D = 5,8·M^⅓ м, горит 0,45·M^⅓ с (M &lt; 30 т), центр поднимается к 0,75·D; излучает
 * 200–350 кВт/м² — чёрное тело {@value #FUEL_BALL_K} K (середина). Шар продуктов детонации — радиус 1,7·W^⅓ м, светит
 * 0,2·W^0,35 с (так было в {@code FarBlasts}; первоисточник не найден — оценка), 3000 K → 1500 K (продукты детонации —
 * 3000–5000 K, обзор Ciccarelli и др. в Shock Waves 2021; к концу — оценка).
 * <p>
 * <b>Вещество.</b> Сажа взрывчатки — свободный углерод продуктов детонации ({@link BlastSource.Explosive#freeCarbon}), из
 * которого догорание в шаре оставляет долю {@link #SOOT_SURVIVES} (оценка); сажа топлива — выход {@link BlastSource.Fuel}.
 * Пыль — грунт воронки: диаметр 0,65·W^⅓ м (середина 0,51–0,8, Ambrosini, Luccioni — ref/formulas.md §4, не сверено),
 * глубина — четверть, параболоид; в воздух уходит доля {@link #DUST_LOFTED} (оценка), половина — в облако, половина —
 * у земли. Ослабление на массу — сажа {@link #SOOT_K} м²/кг при альбедо 0,25 и асимметрии 0,5 (Bond, Bergstrom 2006:
 * поглощение 7,5 м²/г на 550 нм), пыль — {@code DustModel.K_MIX} (геометрическая оптика зёрен ядерной пыли), капли
 * конденсации (5 мкм) — 3Q/(2ρr) = 600 м²/кг. Облако видно, пока его толща поперёк больше {@link #SEEN} (порог
 * контраста Кошмидера).
 */
public final class CloudModel {
    static final double G = 9.80665, RHO = 1.225, CP = 1005, T_AIR = 288.15;
    /** Воздух вокруг облака, K. */
    public static final double T_AIR_K = T_AIR;
    /** Подсос термика (MTT 1956). */
    public static final double ALPHA = 0.285;
    /**
     * Устойчивость стандартной атмосферы ISO 2533 до 11 км: N² = g/T·(dT/dz + g/c_p), градиент −6,5 К/км — N ≈ 0,0105 с⁻¹.
     */
    public static final double N_STANDARD = Math.sqrt(G / T_AIR * (-0.0065 + G / CP));
    /** Ночью у земли — инверсия: 0,02–0,04 с⁻¹ (ref/formulas.md §1), середина нижней половины — оценка. */
    public static final double N_NIGHT = 0.025;
    /**
     * Тепло взрывчатки в газе облака, Дж на кг ТНТ — по замерам облаков: верх стабилизированного облака химического
     * взрыва 92,6·W^0,25 м (W — кг ТНТ, «средняя атмосфера»; Church H. W., «Cloud Rise from High-Explosives Detonations»,
     * Sandia SC-RR-68-903, 1969) — у термика MTT в стандартной атмосфере это верх (1 + α)·наибольшей высоты. Выходит
     * 1,3 МДж/кг — 0,31 от 4,184 МДж/кг энергии ТНТ: остальное уносит ударная волна и излучение. Закон MTT сам даёт
     * высоту ∝ W^¼, как у Чёрча.
     */
    public static final double HE_HEAT;
    /** Доля тепла огненного шара топлива, уходящая излучением: 0,25–0,4 (CCPS) — середина, оценка. */
    public static final double RADIANT = 0.3;
    /** Чёрное тело шара топлива, K: 275 кВт/м² (середина 200–350 по CCPS) = σT⁴. */
    public static final double FUEL_BALL_K = Math.pow(275e3 / 5.670374e-8, 0.25);
    /** Шар взрывчатки: в начале и в конце жизни, K (оценка). */
    public static final double HE_BALL_HOT = 3000, HE_BALL_END = 1500;
    /** Сажа: ослабление на массу (до дельта-Эддингтона), м²/кг; альбедо и асимметрия. */
    public static final double SOOT_K = 7.5e3 / (1 - 0.25), SOOT_W = 0.25, SOOT_G = 0.5;
    /** Капли конденсации 5 мкм: 3·2/(2·1000·5·10⁻⁶), м²/кг; асимметрия. */
    public static final double DROP_K = 600, DROP_G = 0.85;
    /** Сколько свободного углерода детонации остаётся сажей после догорания в шаре — оценка. */
    public static final double SOOT_SURVIVES = 0.2;
    /** Диаметр воронки на W^⅓, м; глубина — доля диаметра; плотность грунта, кг/м³. */
    public static final double CRATER_D = 0.65, CRATER_DEPTH = 0.25, SOIL_DENSITY = 1600;
    /** Доля грунта воронки, что уходит в воздух пылью мельче ~50 мкм, — оценка. */
    public static final double DUST_LOFTED = 0.02;
    /**
     * Доля пыли воронки, что встаёт с облаком (остальное — у земли, вал пыли от ветра за фронтом), и доля пыли облака в
     * ножке, пока облако поднимается (тает с его подъёмом), — оценки: замеров разделения пыли у малых зарядов нет.
     */
    public static final double CLOUD_SHARE = 0.5, STEM_SHARE = 0.3;
    /** Облако видно, пока его толща поперёк середины больше этого (порог контраста 0,02). */
    public static final double SEEN = 0.02;
    /** Дольше этого облако не живёт, с (дальше оно — дымка над районом). */
    public static final double LIFE_MAX = 3600;
    /** Шаг интегрирования подъёма, с. */
    static final double DT = 0.05;

    static {
        // точечный источник в стандартной атмосфере: верх (1 + α)·(8F/(α³N²))^¼ = 92,6·W^¼ → F на кг ТНТ
        double top = 92.6 / (1 + ALPHA);
        double f = Math.pow(top, 4) * ALPHA * ALPHA * ALPHA * N_STANDARD * N_STANDARD / 8;
        HE_HEAT = 4 * Math.PI / 3 * f * RHO * CP * T_AIR / G;
    }

    /**
     * Воздух в месте взрыва.
     *
     * @param n       частота Брента–Вяйсяля, 1/с
     * @param windX   ветер на 10 м по x, м/с
     * @param windZ   и по z
     * @param shear   показатель степени профиля ветра u(z) = u₁₀·(z/10)^p (0,1 днём, 0,14 — нейтрально, 0,3 ночью)
     * @param spread  класс устойчивости Паскуилла (0 — A … 5 — F)
     * @param humidity влажность 0..1 (облако конденсации)
     */
    public record Air(double n, double windX, double windZ, double shear, int spread, double humidity) {
        /** Стандартная атмосфера без ветра — для проверок. */
        public static final Air STANDARD = new Air(N_STANDARD, 0, 0, 0.14, 3, 0.5);

        /**
         * Воздух по свету дня и погоде: днём (day = 1, ясно) — стандартная устойчивость выше слоя перемешивания, класс B,
         * профиль 0,1; ночью — инверсия {@link #N_NIGHT}, класс F, 0,3 (ясно); облачность (дождь) тянет к нейтральному D:
         * {@code ref/formulas.md} §1, таблица Паскуилла–Гиффорда.
         */
        public static Air of(double day, double rain, double windX, double windZ, double humidity) {
            double d = Math.max(0, Math.min(1, day)), r = Math.max(0, Math.min(1, rain));
            double n2 = N_NIGHT * N_NIGHT + (N_STANDARD * N_STANDARD - N_NIGHT * N_NIGHT) * Math.max(d, r);
            double clear = 1 - r;
            double shear = 0.14 + (d > 0.5 ? -0.04 : 0.16) * clear;
            int spread = (int) Math.round(3 + (d > 0.5 ? -2 : 2) * clear);
            return new Air(Math.sqrt(n2), windX, windZ, shear, spread, humidity);
        }

        /** Ветер на высоте z (м) над землёй: доля ветра на 10 м. */
        double wind(double z) {
            return Math.pow(Math.max(z, 1) / 10, shear);
        }
    }

    /** Приёмник клубов: гауссов клуб вещества (центр от точки взрыва, м; σ по горизонтали и вертикали, м). */
    public interface Blobs {
        /**
         * @param x       центр от точки взрыва, м
         * @param sh      σ по горизонтали, м
         * @param sv      σ по вертикали, м
         * @param soot    сажи, кг
         * @param dust    пыли, кг
         * @param water   капель, кг
         * @param kelvin  температура газа, K
         * @param rise    скорость подъёма вещества, м/с (шум клубов идёт с ним)
         */
        void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, double kelvin, double rise);
    }

    public final BlastSource source;
    public final Air air;
    /** Плавучесть всего тепла, м⁴/с²; F = 3B/(4π). */
    public final double buoyancy;
    /** Сажа и пыль (в облаке и у земли), кг. */
    public final double soot, dust;
    /** Шар топлива: диаметр, м, и сколько горит, с (0 — топлива нет). */
    public final double fuelBall, fuelBallSeconds;
    /** Шар взрывчатки: радиус, м, и сколько светит, с. */
    public final double heBall, heBallSeconds;
    /** Конец огненной стадии, с: дальше облако — остывающий термик. */
    public final double fireSeconds;
    /** Подъём по шагам {@link #DT}: высота центра, радиус, скорость, g'; снос по x и z. */
    private final double[] z, r, w, gp, dx, dz;
    /** Наибольшая скорость подъёма, м/с. */
    final double maxRise;
    /** Наибольшая высота центра и время, когда она достигнута, с; радиус там. */
    public final double topZ, topSeconds, topR;
    /** Сколько облако видно, с. */
    public final double life;

    public CloudModel(BlastSource source, Air air) {
        this.source = source;
        this.air = air;
        double fuelHeat = source.fuelKg() * source.fuel().heatJPerKg * (1 - RADIANT);
        double heat = source.tntKg() * HE_HEAT + fuelHeat;
        buoyancy = G * heat / (RHO * CP * T_AIR);
        double crater = CRATER_D * Math.cbrt(source.tntKg());
        double craterVolume = Math.PI / 8 * crater * crater * CRATER_DEPTH * crater;
        dust = craterVolume * SOIL_DENSITY * DUST_LOFTED;
        soot = source.tntKg() * source.explosive().freeCarbon() * SOOT_SURVIVES + source.fuelKg() * source.fuel().sootYield;
        double m3 = Math.cbrt(source.fuelKg());
        fuelBall = 5.8 * m3;
        fuelBallSeconds = 0.45 * m3;
        heBall = 1.7 * Math.cbrt(source.tntKg());
        heBallSeconds = 0.2 * Math.pow(source.tntKg(), 0.35);
        fireSeconds = Math.max(fuelBallSeconds, heBallSeconds);
        // начальный объём: в нём тепло держит температуру шара (у ideal gas при том же давлении g' = g·(1 − T_a/T))
        double ballK = source.fuelKg() > 0 ? FUEL_BALL_K : HE_BALL_END;
        double g0 = G * (1 - T_AIR / ballK);
        double volume = buoyancy / g0;
        double r0 = Math.cbrt(3 * volume / (4 * Math.PI));
        int n = (int) Math.ceil(Math.PI / air.n() / DT) + 1;
        z = new double[n];
        r = new double[n];
        w = new double[n];
        gp = new double[n];
        dx = new double[n];
        dz = new double[n];
        // MTT в переменных u = R³, m = R³W, f = R³g'; центр — от половины радиуса над землёй (наземный шар — полусфера)
        double u = r0 * r0 * r0, m = 0, f = u * g0, h = 0.5 * r0, x = 0, y = 0;
        for (int i = 0; i < n; i++) {
            double rr = Math.cbrt(u);
            z[i] = h;
            r[i] = rr;
            w[i] = m / u;
            gp[i] = f / u;
            dx[i] = x;
            dz[i] = y;
            // РК4 по (u, m, f, h)
            double[] k1 = rate(u, m, f), k2 = rate(u + 0.5 * DT * k1[0], m + 0.5 * DT * k1[1], f + 0.5 * DT * k1[2]);
            double[] k3 = rate(u + 0.5 * DT * k2[0], m + 0.5 * DT * k2[1], f + 0.5 * DT * k2[2]);
            double[] k4 = rate(u + DT * k3[0], m + DT * k3[1], f + DT * k3[2]);
            u += DT / 6 * (k1[0] + 2 * k2[0] + 2 * k3[0] + k4[0]);
            m += DT / 6 * (k1[1] + 2 * k2[1] + 2 * k3[1] + k4[1]);
            f += DT / 6 * (k1[2] + 2 * k2[2] + 2 * k3[2] + k4[2]);
            h += DT / 6 * (k1[3] + 2 * k2[3] + 2 * k3[3] + k4[3]);
            double along = air.wind(h) * DT;
            x += air.windX() * along;
            y += air.windZ() * along;
            if (m <= 0 && i > 0) {
                // вершина: дальше облако не поднимается
                for (int j = i + 1; j < n; j++) {
                    z[j] = z[i];
                    r[j] = r[i];
                    w[j] = 0;
                    gp[j] = gp[i];
                    dx[j] = dx[j - 1] + air.windX() * air.wind(z[i]) * DT;
                    dz[j] = dz[j - 1] + air.windZ() * air.wind(z[i]) * DT;
                }
                break;
            }
        }
        int top = 0;
        for (int i = 1; i < n; i++) if (z[i] > z[top]) top = i;
        double fastest = 0;
        for (double v : w) fastest = Math.max(fastest, v);
        maxRise = fastest;
        topZ = z[top];
        topR = r[top];
        topSeconds = top * DT;
        life = lifeSeconds();
    }

    /** Скорости d/dt (u, m, f, h) термика MTT при m = R³W, f = R³g'. */
    private double[] rate(double u, double m, double f) {
        double rr = Math.cbrt(u);
        double wv = m / u;
        return new double[]{3 * ALPHA * rr * rr * wv, f, -air.n() * air.n() * m, wv};
    }

    /** Номер шага подъёма для возраста t, с (за таблицей — последний). */
    private double step(double t) {
        return Math.max(0, Math.min(z.length - 1.0, t / DT));
    }

    private static double at(double[] a, double s) {
        int i = (int) Math.min(a.length - 2, Math.max(0, Math.floor(s)));
        if (a.length == 1) return a[0];
        double f = Math.min(1, s - i);
        return a[i] + (a[i + 1] - a[i]) * f;
    }

    /** Высота центра облака над точкой взрыва в возрасте t, м. */
    public double height(double t) {
        return at(z, step(t));
    }

    /** Радиус термика (верх-хат MTT) в возрасте t, м; дальше вершины — тот же. */
    public double radius(double t) {
        return at(r, step(t));
    }

    /** Скорость подъёма, м/с. */
    public double rise(double t) {
        return Math.max(0, at(w, step(t)));
    }

    /** Снос центра ветром за t, м (x и z). */
    public double driftX(double t) {
        return at(dx, step(t)) + air.windX() * air.wind(topZ) * beyond(t);
    }

    public double driftZ(double t) {
        return at(dz, step(t)) + air.windZ() * air.wind(topZ) * beyond(t);
    }

    /** Сколько секунд возраст t за таблицей подъёма. */
    private double beyond(double t) {
        return Math.max(0, t - (z.length - 1) * DT);
    }

    /**
     * Температура газа облака в возрасте t, K: в огненной стадии — шар (шар взрывчатки остывает от {@link #HE_BALL_HOT}
     * к {@link #HE_BALL_END}, шар топлива держит {@link #FUEL_BALL_K}), дальше — термик: T = T_a / (1 − g'/g).
     */
    public double kelvin(double t) {
        double thermal = T_AIR / (1 - Math.min(0.9, Math.max(0, at(gp, step(t))) / G));
        if (t < fuelBallSeconds) return Math.max(thermal, FUEL_BALL_K);
        if (t < heBallSeconds) return Math.max(thermal, heBallKelvin(t));
        return thermal;
    }

    /** Температура шара продуктов детонации в возрасте t, K. */
    public double heBallKelvin(double t) {
        double u = Math.min(1, t / Math.max(heBallSeconds, 1e-6));
        return HE_BALL_HOT + (HE_BALL_END - HE_BALL_HOT) * u;
    }

    /** Радиус светящегося шара (большего из двух) в возрасте t, м; 0 — шары погасли. */
    public double ballRadius(double t) {
        return Math.max(fuelBallRadius(t), heBallRadius(t));
    }

    /**
     * Температура самого горячего светящегося шара, K: пока светят продукты детонации — их шар, потом — шар топлива; 0 —
     * шары погасли.
     */
    public double ballKelvin(double t) {
        if (t < heBallSeconds) return heBallKelvin(t);
        return t < fuelBallSeconds ? FUEL_BALL_K : 0;
    }

    /** Радиус шара продуктов детонации в возрасте t, м (0 — погас): за первые проценты жизни почти весь, как кадры шара. */
    public double heBallRadius(double t) {
        return t < heBallSeconds ? heBall * (0.3 + 0.7 * (1 - Math.exp(-t / (0.05 * heBallSeconds)))) : 0;
    }

    /** Радиус шара топлива в возрасте t, м (0 — погас): растёт к D/2 за первую пятую горения (оценка формы). */
    public double fuelBallRadius(double t) {
        return t < fuelBallSeconds ? 0.5 * fuelBall * Math.min(1, Math.sqrt(t / (0.2 * fuelBallSeconds))) : 0;
    }

    /**
     * Свет поверхности светящегося шара радиуса {@link #ballRadius} в возрасте t — в {@code out} (r, g, b, яркость Y; белая
     * стена в полдень — 1): шары продуктов детонации и топлива вместе, каждый — чёрное тело своей температуры с долей
     * площади (r/R)² (поток тот же, что у двух шаров).
     */
    public void ballLight(double t, double[] out) {
        java.util.Arrays.fill(out, 0, 4, 0);
        double big = ballRadius(t);
        if (big <= 0) return;
        double[] c = new double[4];
        double he = heBallRadius(t), fuel = fuelBallRadius(t);
        if (he > 0) {
            BlackBody.light(heBallKelvin(t), c);
            for (int i = 0; i < 4; i++) out[i] += c[i] * he * he / (big * big);
        }
        if (fuel > 0) {
            BlackBody.light(FUEL_BALL_K, c);
            for (int i = 0; i < 4; i++) out[i] += c[i] * fuel * fuel / (big * big);
        }
    }

    /** Высота центра шара топлива, м: к 0,75·D к концу горения (CCPS). */
    public double fuelBallHeight(double t) {
        return 0.5 * fuelBall + 0.25 * fuelBall * Math.min(1, t / Math.max(fuelBallSeconds, 1e-6));
    }

    /** Высота центра шара продуктов детонации, м: наземный — полусфера, центр у земли. */
    public double heBallHeight(double t) {
        return 0.45 * heBallRadius(t);
    }

    /** Высота центра светящегося шара (большего), м. */
    public double ballHeight(double t) {
        return fuelBallRadius(t) >= heBallRadius(t) ? fuelBallHeight(t) : heBallHeight(t);
    }

    /**
     * Расплывание после вершины по Бриггсу (открытая местность, класс {@link Air#spread}), σ по горизонтали и вертикали,
     * м, на пути x = u·t, но не короче пути с ветром 1 м/с: в штиль воздух тоже перемешивается.
     */
    static double spreadH(int cls, double x) {
        double[] a = {0.22, 0.16, 0.11, 0.08, 0.06, 0.04};
        return a[cls] * x / Math.sqrt(1 + 0.0001 * x);
    }

    static double spreadV(int cls, double x) {
        return switch (cls) {
            case 0 -> 0.20 * x;
            case 1 -> 0.12 * x;
            case 2 -> 0.08 * x / Math.sqrt(1 + 0.0002 * x);
            case 3 -> 0.06 * x / Math.sqrt(1 + 0.0015 * x);
            case 4 -> 0.03 * x / (1 + 0.0003 * x);
            default -> 0.016 * x / (1 + 0.0003 * x);
        };
    }

    /** σ облака по горизонтали в возрасте t, м: термик (сфера R — σ = R/√5) и расплывание после вершины. */
    public double sigmaH(double t) {
        double s0 = radius(t) / Math.sqrt(5);
        double x = after(t);
        double s = spreadH(air.spread(), x);
        return Math.sqrt(s0 * s0 + s * s);
    }

    public double sigmaV(double t) {
        double s0 = radius(t) / Math.sqrt(5);
        double x = after(t);
        double s = spreadV(air.spread(), x);
        return Math.sqrt(s0 * s0 + s * s);
    }

    /** Путь ветра после вершины, м (не меньше, чем при 1 м/с). */
    private double after(double t) {
        double u = Math.max(1, Math.hypot(air.windX(), air.windZ()) * air.wind(topZ));
        return u * Math.max(0, t - topSeconds);
    }

    /**
     * Толща облака поперёк середины в возрасте t: горизонтальная прямая через центр 3D-гаусса массы m —
     * Σκ'·m / (2π·σh·σv) по веществам облака (у земли — своё облако, не в счёт).
     */
    public double tau(double t) {
        double ext = SOOT_K * soot + ua.zentix.incoming.nuclear.model.DustModel.K_MIX * CLOUD_SHARE * dust * DustModel.aloft(t, height(t));
        return ext / (2 * Math.PI * sigmaH(t) * sigmaV(t));
    }

    /** Сколько облако видно, с: пока {@link #tau} больше {@link #SEEN}, не дольше {@link #LIFE_MAX}. */
    private double lifeSeconds() {
        double lo = 0, hi = LIFE_MAX;
        if (tau(hi) >= SEEN) return hi;
        if (tau(1) < SEEN) return Math.max(fireSeconds, 1);
        lo = 1;
        for (int i = 0; i < 40; i++) {
            double mid = 0.5 * (lo + hi);
            if (tau(mid) >= SEEN) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /**
     * Доля вещества облака, что рисуется в возрасте t: облако, истончившееся до порога видимости, к концу жизни и так не
     * видно — 1; облако, что дожило до {@link #LIFE_MAX} густым, уходит плавно за последнюю десятую жизни (без скачка).
     */
    double fade(double t) {
        if (t >= life) return 0;
        if (life < LIFE_MAX) return 1;
        return Math.min(1, (life - t) / (0.1 * life));
    }

    /**
     * Клубы облака в возрасте t (с), от точки взрыва (y — от точки удара вверх): огненный шар (горячий, в огненной
     * стадии), облако (термик: сажа и половина пыли, своя температура), ножка — след облака от земли до его низа (доля
     * пыли облака, тает с ростом), пыль у земли (другая половина пыли: расползается и оседает). Каждый клуб несёт массу —
     * сетка сама переводит её в ослабление.
     */
    public void blobs(double t, Blobs out) {
        if (t < 0 || t >= life) return;
        double fade = fade(t);
        double cx = driftX(t), cz = driftZ(t), cy = height(t), sh = sigmaH(t), sv = sigmaV(t);
        double k = kelvin(t), up = rise(t);
        // огненная стадия: шары светят своим радиусом и температурой — шар продуктов детонации (сажа взрывчатки), шар
        // топлива (сажа топлива); пыль облака — в большем
        // пыль облака оседает из него (корзины DustModel), ножку питает подъём облака — тает с его скоростью
        double cloudDust = CLOUD_SHARE * dust * DustModel.aloft(t, cy);
        double stemShare = STEM_SHARE * Math.min(1, up / Math.max(maxRise, 1e-6));
        double he = heBallRadius(t), fuel = fuelBallRadius(t);
        double heSoot = source.tntKg() * source.explosive().freeCarbon() * SOOT_SURVIVES, fuelSoot = soot - heSoot;
        if (he > 0 || fuel > 0) {
            boolean heBig = he > fuel;
            if (he > 0) {
                double s = he / Math.sqrt(5);
                out.blob(cx, heBallHeight(t), cz, s, s, (fuel > 0 ? heSoot : soot) * fade, heBig ? cloudDust * (1 - stemShare) * fade : 0, 0,
                        Math.max(k, heBallKelvin(t)), up);
            }
            if (fuel > 0) {
                double s = fuel / Math.sqrt(5);
                out.blob(cx, fuelBallHeight(t), cz, s, s, (he > 0 ? fuelSoot : soot) * fade, heBig ? 0 : cloudDust * (1 - stemShare) * fade, 0,
                        Math.max(k, FUEL_BALL_K), up);
            }
        } else {
            out.blob(cx, cy, cz, sh, sv, soot * fade, cloudDust * (1 - stemShare) * fade, 0, k, up);
        }
        // ножка: от земли до низа облака, клубы через σ ножки
        double bottom = cy - 1.5 * sv;
        if (bottom > 2 && stemShare > 1e-3) {
            double ss = Math.max(0.35 * sh, 1);
            int count = (int) Math.min(8, Math.max(1, Math.round(bottom / (2 * ss))));
            double mass = cloudDust * stemShare * fade / count;
            for (int i = 0; i < count; i++) {
                double f = (i + 0.5) / count;
                // ножка клонится с ветром: снос на её высоте — доля сноса облака
                double h = f * bottom;
                double lean = air.wind(h) / air.wind(cy);
                out.blob(cx * lean, h, cz * lean, ss, ss, 0, mass, 0, T_AIR + (k - T_AIR) * f * 0.5, up * f);
            }
        }
        // пыль у земли: вал, который ветер за фронтом гонит до радиуса облака Чёрча (R = 4,7·W^0,375 м; σ = R/√5, как у
        // термика) со скоростью звука, дальше — расплывание Бриггса по пути ветра; высота — начальный шар, оседает корзинами
        double c = ua.zentix.incoming.nuclear.model.BlastModel.SOUND_SPEED;
        double church = 4.7 * Math.pow(source.tntKg(), 0.375) / Math.sqrt(5);
        double x = Math.max(1, Math.hypot(air.windX(), air.windZ()) * air.wind(2)) * t;
        double bh = Math.hypot(church * Math.min(1, t * c / Math.max(church, 1e-6)), spreadH(air.spread(), x));
        double bv = Math.max(radius(0) / Math.sqrt(5), spreadV(air.spread(), x));
        double ground = (1 - CLOUD_SHARE) * dust * DustModel.aloft(t, 2 * bv);
        out.blob(cx * air.wind(2) / air.wind(cy), bv, cz * air.wind(2) / air.wind(cy), Math.max(bh, 0.5), bv, 0, ground * fade, 0, T_AIR, 0);
    }

    /**
     * Облако конденсации — во влажном воздухе (влажность не меньше {@code CloudModel.WILSON_HUMIDITY} ядерки) за фронтом
     * волны, где давление ещё 20 кПа (Кинни–Грэхем; оценка), живёт {@link #condensationSeconds}: радиус, м; 0 — нет.
     */
    public double condensation() {
        if (!ua.zentix.incoming.nuclear.model.CloudModel.wilsonCloud(air.humidity())) return 0;
        return ua.zentix.incoming.nuclear.model.BlastModel.rangeForSurfaceOverpressure(20, source.tntKg());
    }

    /** Сколько живёт облако конденсации, с: 10 положительных фаз волны на его радиусе (оценка). */
    public double condensationSeconds() {
        double r = condensation();
        if (r <= 0) return 0;
        return 10 * ua.zentix.incoming.nuclear.model.BlastModel.positivePhaseSeconds(r, source.tntKg() * 1e-6);
    }
}
