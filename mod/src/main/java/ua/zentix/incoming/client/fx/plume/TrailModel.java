package ua.zentix.incoming.client.fx.plume;

import ua.zentix.incoming.util.Stability;

import ua.zentix.incoming.util.Air;

import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.util.StandardAtmosphere;

/**
 * След двигателя — один закон для всех ({@link Trail} — паспорт следа, {@link Motor} — откуда дым).
 * <ul>
 * <li><b>Масса на метр</b>: конденсированные продукты ṁ_s = расход топлива × их доля ({@link Motor#smokeFlow}); снаряд
 * кладёт след со своей скоростью v (из сущности или пути), λ = ṁ_s/v. Это след линейного источника — пока снаряд
 * обгоняет ветер ({@link #laid}); медленнее ветра продукты уносит ветер от почти стоящего источника — это шлейф точки,
 * облако старта у пусковой, а не след (его рисуют клубы старта {@code Exhaust}): лентой из точки такого шлейфа выходили
 * плоскости в сотню метров. Ветер слабее {@link #CALM} формулы шлейфа не держат — берётся он. Скорость — та, с которой
 * снаряд летит в мире: двигатель горит своё настоящее время, и след — путь снаряда за это время. На игровой шкале
 * полёта (не по физике, {@code FlightScale}) путь короткий (у «Града» ~160 блоков, у настоящего 9М22У с разгоном до
 * ~690 м/с — ~650 м), а тот же дым на коротком пути — гуще и держится дольше. Длина — из скорости, как все длины полёта
 * ({@code Airframe.stretch}); удлинить след без удлинения горения нельзя — дым лёг бы там, где двигатель не горел.
 * «Град» сходит с трубы сразу на полной скорости (не по физике, {@code RocketEntity}): при шкале 1 его след ~1,4 км.</li>
 * <li><b>Оптика</b>: ослабление на метр E = σ_m·λ (м); сечение — гаусс шириной σ, толща поперёк через середину
 * τ = E/(√(2π)·σ), непрозрачность поперёк o(y) = 1 − e^(−τ·e^(−y²/2σ²)). Лента и клубы мягкие по краю, поэтому
 * рисуются шириной на половине непрозрачности середины (у тонкого дыма — 2,355σ, у густого — где τ(y) = ln 2) и с
 * непрозрачностью середины; след живёт, пока толща в середине выше порога Кошмидера ({@link #THRESHOLD}, контраст 2 %).
 * Ширину до порога (у густого — до 13σ) рисовать нельзя: ровная лента такой ширины — непрозрачный ком вместо мягкого
 * края.</li>
 * <li><b>Расплывание</b>: σ² = σ₀² + σ_y·σ_z + (α·z/2)²: σ₀ — струя в конце факела, σ_y, σ_z — Бриггс по классу
 * устойчивости на пути x = u·t по ветру ({@link Stability}), третье — клуб всплывающего тёплого следа.</li>
 * <li><b>Всплытие</b>: след — линейный термик (Turner, «Buoyancy Effects in Fluids», 1973, гл. 6): плавучесть на метр
 * B = g·H/(v·ρ·c_p·T), H — тепло, что двигатель оставляет в воздухе ({@link Motor#heatFlow}); радиус клуба α·z,
 * импульс π·α²·z²·ż растёт на B, а в устойчивом воздухе (частота N) B теряется на поднятый с ним воздух:
 * d(z²ż)/dt = B/(πα²) − N²z³/3. В безразмерном виде одно уравнение на всех ({@link #rise}); после вершины клуб стоит на
 * ней (колебания вокруг уровня равновесия затухают — не рисуются). α = 0,5 (⚠ оценка: у линейных термиков 0,3–0,6).</li>
 * <li><b>Инверсия</b>: лёд из водяного пара выхлопа, если смесь заходит за насыщение ({@link Contrail}); тот же закон
 * с ослаблением льда. В воздухе, пересыщенном надо льдом, на лёд следа садится и избыток пара подмешанного воздуха —
 * ρ_изб·2πσ² на метр: след густеет, расплываясь, и держится, пока держится пересыщение (до {@link #LONGEST}); иначе лёд
 * испаряется, когда разбавление снимает избыток пара выхлопа ({@link Contrail#dryAt}).</li>
 * </ul>
 * Время — секунды, длины — метры.
 */
public final class TrailModel {
    /** Чисел вида точки ({@link #look}). */
    public static final int LOOK = 7;
    /** Порог видимости толщи. */
    public static final double THRESHOLD = Sight.THRESHOLD;
    /** Слабее этого ветра (м/с) формулы гауссова шлейфа не держат (штиль): берётся он. */
    static final double CALM = 1;
    /** Раствор клуба термика. */
    static final double ALPHA = 0.5;
    /** Теплоёмкость воздуха, Дж/(кг·K). */
    static final double CP_AIR = 1004;
    /** Плавучесть на метр следа на ватт-секунду тепла: g/(ρ·c_p·T) у земли, м³/(с²·Дж/м). */
    private static final double BUOYANCY = 9.80665 / (StandardAtmosphere.density(0) * CP_AIR * StandardAtmosphere.T0);
    /** Самое долгое, что живёт след, с: дальше — не дольше игровых суток. */
    static final double LONGEST = 1200;
    /** Безразмерное всплытие s(τ) до вершины, шаг τ. */
    private static final double RISE_STEP = 0.01;
    private static final double[] RISE = riseTable();

    private TrailModel() {}

    /**
     * Паспорт следа: двигатель и шаг точек ленты.
     *
     * @param motor  откуда дым
     * @param step   точка ленты — раз в столько тиков (разрешение ленты, не физика)
     * @param sigma0 σ дыма, когда он выходит из струи (полуширина струи в конце видимого факела — 2σ), м
     */
    public record Trail(Motor motor, int step, double sigma0) {
        /** След двигателя: начальная ширина — из его факела у земли (без факела — струя к концу её ядра, вдвое шире среза). */
        public static Trail of(Motor motor, int step) {
            Flame f = FlameModel.of(motor, 0);
            return new Trail(motor, step, f != null ? f.spread() / 2 : 2 * motor.exitRadius() * Math.sqrt(motor.nozzles()));
        }
    }

    /**
     * Доля вещества точки следа, что уже вышла из пламени дымом, когда точка — в behind метрах позади сопла по пути снаряда
     * (снаряд кладёт точку у сопла и уходит), а яркое пламя сейчас длиной flame метров — ядро и догорание до стехиометрии
     * ({@link Flame#flame}; нет пламени — 0): в нём её продукты — само пламя (раскалённые частицы светятся в десятки раз
     * ярче белого, {@code FlameModel}), и дымом поверх него они легли бы дважды: ночью — тёмным комом на месте огня.
     * Остывающий хвост за ним до точки Дрейпера на экране почти не светит — там уже дым. Плавно от конца пламени до
     * полуторной его длины. ⚠ Пламя считается прямым вдоль пути.
     */
    public static double emerged(double behind, double flame) {
        if (flame <= 0) return 1;
        double u = Math.clamp((behind / flame - 1) / 0.5, 0, 1);
        return u * u * (3 - 2 * u);
    }

    /** Снаряд со скоростью speed кладёт след (обгоняет ветер wind), а не стоит в облаке своего старта, м/с. */
    public static boolean laid(double speed, double wind) {
        return speed >= Math.max(wind, CALM);
    }

    /** Секунды на метр следа у снаряда со скоростью speed в ветре wind, м/с. */
    public static double inverseSpeed(double speed, double wind) {
        return 1 / Math.max(speed, Math.max(wind, CALM));
    }

    /** Ослабление на метр дыма, м (λ·σ_m). */
    public static double smoke(Trail t, double inverseSpeed) {
        Motor m = t.motor();
        return m.smoke() == null ? 0 : m.smoke().extinction * m.smokeFlow() * inverseSpeed;
    }

    /**
     * Ослабление на метр льда, м, если инверсия встаёт: лёд из пара выхлопа и, в пересыщенном надо льдом воздухе
     * (excess — избыток пара, кг/м³), из пара подмешанного воздуха на сечении 2πσ².
     */
    public static double ice(Trail t, double inverseSpeed, double excess, double sigma) {
        return Smoke.ICE.extinction * (t.motor().waterFlow() * inverseSpeed + Math.max(0, excess) * 2 * Math.PI * sigma * sigma);
    }

    /** Избыток пара над насыщением надо льдом на высоте y мира, кг/м³ (0 — не пересыщен). */
    public static double excess(double y, Air air) {
        return Math.max(0, -Contrail.iceDeficit(StandardAtmosphere.temperature(y - Air.SEA), air.humidity()));
    }

    /**
     * Встаёт ли инверсия на высоте y мира: температура и давление — стандартная атмосфера над уровнем моря мира.
     */
    public static boolean contrail(Trail t, double y, Air air) {
        double h = y - Air.SEA;
        return Contrail.forms(t.motor(), StandardAtmosphere.temperature(h), StandardAtmosphere.pressure(h), air.humidity());
    }

    /**
     * Точка ленты родилась: в out[0] — сколько она видна, с; out[1] — сколько держится лёд, с (0 — инверсии нет).
     *
     * @param inverseSpeed {@link #inverseSpeed}
     * @param y            высота мира
     * @param wind         ветер там, м/с
     */
    public static void birth(Trail t, double inverseSpeed, double y, double wind, Air air, double[] out) {
        double n = air.brunt(), c = air.stability();
        double smoke = smoke(t, inverseSpeed);
        double life = smoke > 0 ? until(t, sigmaFor(smoke), inverseSpeed, c, wind, n) : 0;
        double ice = 0;
        if (contrail(t, y, air)) {
            double h = y - Air.SEA, deficit = Contrail.iceDeficit(StandardAtmosphere.temperature(h), air.humidity());
            // пересыщенный воздух: след густеет, расплываясь, — держится, пока держится пересыщение
            ice = deficit <= 0 ? LONGEST : until(t, Math.min(sigmaFor(ice(t, inverseSpeed, 0, 0)),
                    Contrail.dryAt(t.motor().waterFlow() * inverseSpeed, deficit)), inverseSpeed, c, wind, n);
        }
        out[0] = Math.max(life, ice);
        out[1] = ice;
    }

    /**
     * Вид точки, рождённой на высоте y мира, в возрасте age секунд: out[0] — ширина на половине непрозрачности середины, м;
     * out[1] — непрозрачность середины; out[2] — подъём, м; out[3] — отражение густого дыма (линейно,
     * {@link Smoke#reflectance}); сама трубка — out[4] σ сечения, м, out[5] ослабление на метр (дым и лёд), м, out[6] альбедо
     * однократного рассеяния смеси ({@link #LOOK} чисел).
     */
    public static void look(Trail t, double age, double inverseSpeed, double iceLife, double wind, double y, Air air, double[] out) {
        double n = air.brunt();
        look(t, age, inverseSpeed, iceLife, wind, excess(y, air), riseScale(t, inverseSpeed, n), n, air.stability(), out);
    }

    /**
     * То же по числам, которые точка получила при рождении (кадр обходит тысячи точек: здесь нет ни кубических корней,
     * ни давления насыщения): избыток пара excess ({@link #excess}), масштаб всплытия riseScale ({@link #riseScale}) при
     * частоте brunt, класс устойчивости stability.
     */
    public static void look(Trail t, double age, double inverseSpeed, double iceLife, double wind, double excess, double riseScale, double brunt,
                            double stability, double[] out) {
        look(t, age, inverseSpeed, iceLife, wind, excess, riseScale, brunt, stability, 1, out);
    }

    /**
     * То же для доли mass вещества точки (остальное рисует объём сетки дыма у глаза): толща — mass × толщи дыма и льда
     * (и лёд из пара подмешанного воздуха), сечение и подъём — те же.
     */
    public static void look(Trail t, double age, double inverseSpeed, double iceLife, double wind, double excess, double riseScale, double brunt,
                            double stability, double mass, double[] out) {
        double lift = riseScale * risen(brunt * age);
        double sigma = sigma(t, age, stability, wind, lift);
        double smoke = mass * smoke(t, inverseSpeed), ice = age < iceLife ? mass * ice(t, inverseSpeed, excess, sigma) : 0, e = smoke + ice;
        double tau = e / (Math.sqrt(2 * Math.PI) * sigma);
        out[2] = lift;
        Smoke s = t.motor().smoke();
        double albedo = e <= 0 ? 1 : ((s == null ? 0 : smoke * s.albedo) + ice * Smoke.ICE.albedo) / e;
        out[3] = Smoke.reflectance(albedo);
        out[4] = sigma;
        out[5] = e;
        out[6] = albedo;
        if (tau <= THRESHOLD) {
            out[0] = out[1] = 0;
            return;
        }
        // половина непрозрачности середины: e^(−τg) = (1 + e^(−τ))/2
        double clear = Math.exp(-tau), g = -Math.log((1 + clear) / 2) / tau;
        out[0] = 2 * sigma * Math.sqrt(-2 * Math.log(g));
        out[1] = 1 - clear;
    }

    /** σ сечения в возрасте age секунд при подъёме lift метров, м. */
    public static double sigma(Trail t, double age, double stability, double wind, double lift) {
        double x = Math.max(0, wind) * age, th = ALPHA * lift / 2;
        return Math.sqrt(t.sigma0() * t.sigma0() + Stability.sigmaY(stability, x) * Stability.sigmaZ(stability, x) + th * th);
    }

    /** Подъём тёплого следа через age секунд в воздухе с частотой Брента–Вяйсяля brunt, м. */
    public static double rise(Trail t, double age, double inverseSpeed, double brunt) {
        return age <= 0 ? 0 : riseScale(t, inverseSpeed, brunt) * risen(brunt * age);
    }

    /** Масштаб всплытия L = (3b/N²)^⅓, b = B/(πα²): подъём — L·s(N·t), м. */
    public static double riseScale(Trail t, double inverseSpeed, double brunt) {
        double heat = t.motor().heatFlow() * inverseSpeed;
        if (heat <= 0 || brunt <= 0) return 0;
        return Math.cbrt(3 * BUOYANCY * heat / (Math.PI * ALPHA * ALPHA) / (brunt * brunt));
    }

    /** σ, до которой середина следа с ослаблением e на метр видна. */
    static double sigmaFor(double e) {
        return e / (Math.sqrt(2 * Math.PI) * THRESHOLD);
    }

    /** Через сколько секунд σ дорастёт до target (не дольше {@link #LONGEST}). */
    static double until(Trail t, double target, double inverseSpeed, double stability, double wind, double brunt) {
        if (sigma(t, 0, stability, wind, 0) >= target) return 0;
        double lo = 0, hi = LONGEST;
        if (sigma(t, hi, stability, wind, rise(t, hi, inverseSpeed, brunt)) < target) return LONGEST;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if (sigma(t, mid, stability, wind, rise(t, mid, inverseSpeed, brunt)) < target) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    /** Безразмерный подъём s(τ) уравнения d(s²s′)/dτ = (1 − s³)/3; после вершины — вершина. */
    static double risen(double tau) {
        double i = tau / RISE_STEP;
        int k = (int) i;
        if (k >= RISE.length - 1) return RISE[RISE.length - 1];
        return RISE[k] + (RISE[k + 1] - RISE[k]) * (i - k);
    }

    /** Таблица s(τ) до вершины: Рунге–Кутта от начала, где s = (τ²/2)^⅓, s²s′ = τ/3. */
    private static double[] riseTable() {
        java.util.ArrayList<Double> out = new java.util.ArrayList<>();
        out.add(0.0);
        double tau = RISE_STEP, s = Math.cbrt(tau * tau / 2), q = tau / 3;
        out.add(s);
        while (q > 0 && out.size() < 100_000) {
            double h = RISE_STEP;
            double k1s = q / (s * s), k1q = (1 - s * s * s) / 3;
            double s2 = s + h / 2 * k1s, q2 = q + h / 2 * k1q;
            double k2s = q2 / (s2 * s2), k2q = (1 - s2 * s2 * s2) / 3;
            double s3 = s + h / 2 * k2s, q3 = q + h / 2 * k2q;
            double k3s = q3 / (s3 * s3), k3q = (1 - s3 * s3 * s3) / 3;
            double s4 = s + h * k3s, q4 = q + h * k3q;
            double k4s = q4 / (s4 * s4), k4q = (1 - s4 * s4 * s4) / 3;
            s += h / 6 * (k1s + 2 * k2s + 2 * k3s + k4s);
            q += h / 6 * (k1q + 2 * k2q + 2 * k3q + k4q);
            if (q > 0) out.add(s);
        }
        double[] table = new double[out.size()];
        for (int i = 0; i < table.length; i++) table[i] = out.get(i);
        return table;
    }

    /** Нет следа у этого двигателя вообще (ни дыма, ни воды). */
    public static boolean none(@Nullable Trail t) {
        return t == null || t.motor().smokeFlow() <= 0 && t.motor().waterFlow() <= 0;
    }
}
