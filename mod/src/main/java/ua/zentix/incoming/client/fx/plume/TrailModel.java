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
 * <li><b>Расплывание</b>: σ² = σ₀² + σ_r² + (α·z/2)²: σ₀ — струя в конце факела, третье — клуб всплывающего тёплого
 * следа, σ_r — расплывание самого следа турбулентностью. След ложится весь сразу — это клуб, а не шлейф непрерывного
 * источника: σ_y, σ_z Бриггса ({@link Stability}) — ширина шлейфа, усреднённая за минуты замера (Hanna, Briggs, Hosker
 * 1982: 3–10 мин), и в неё входит мотание всего шлейфа вихрями крупнее его самого. Мгновенный след расплывается медленнее —
 * относительной дисперсией Ричардсона — Обухова: пары частиц расходятся как ⟨r²⟩ = g·ε·t³ (тот же закон, что рассеивает
 * гриб ядерки, {@code MushroomModel}), по каждой оси σ_r² = g·ε·t³/6 — и не шире усреднённого: σ_r² ≤ σ_y·σ_z. С σ_y·σ_z
 * в этом месте (прежде) след ЗРК днём разбавлялся до невидимого к ~15 с, хотя настоящий висит десятки секунд — минуты
 * (FX-report 23.10). Остальное от усреднённой ширины — изгибы оси следа вихрями крупнее его ({@link #wander}):
 * σ_y² − σ_r² вбок, σ_z² − σ_r² по высоте (модель мотающегося шлейфа Гиффорда).</li>
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
    /**
     * Постоянная Ричардсона — Обухова g: ⟨r²⟩ = g·ε·t³ (Ott и Mann 2000, JFM 422, 207; Sawford 2001, Annu. Rev. Fluid
     * Mech. 33, 289) — та же, что у гриба ядерки ({@code MushroomModel.RICHARDSON}).
     */
    static final double RICHARDSON = 0.5;
    /**
     * Скорость диссипации турбулентной энергии в приземном слое по классу Паскуилла A–F, м²/с³. <b>Оценка</b> по порядкам
     * Глоссария AMS («dissipation rate»: днём в конвекции 10⁻³–10⁻², ночью 10⁻⁶–10⁻⁴): A 10⁻², B 5·10⁻³, C 2·10⁻³,
     * D 10⁻³ (нейтрально: u*³/(κz) при u* ≈ 0,3 м/с на ~30–70 м), E 10⁻⁴, F 10⁻⁵; между классами — по логарифму. Высоту
     * внутри слоя и свободную тропосферу над ним (10⁻⁵–10⁻⁴) не различаем.
     */
    private static final double[] DISSIPATION = {1e-2, 5e-3, 2e-3, 1e-3, 1e-4, 1e-5};
    /** Длины волн изгибов следа ({@link #wander}): вихри 4·2^k м, k = 0…8 — до 1 км, толщины слоя перемешивания. */
    static final int OCTAVES = 9;
    static final double SMALLEST_EDDY = 4;
    /** Изгиб, который рисуется, не мельче стольких шагов точек ленты: мельче отрезки между точками ломаются в зигзаг. */
    static final double POINTS_PER_EDDY = 4;
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
     * @param reach  след ложится по настоящему пути выгорания двигателя, м ({@code Article.burnPath}): пока снаряд от
     *               начала пути не прошёл столько, и не дальше — при любой скорости полёта; 0 — пока горит фаза
     * @param burn   время работы двигателя по паспорту, с: с {@code reach} — вещество на метр следа (настоящий расход
     *               на настоящий путь), а не по скорости снаряда
     */
    public record Trail(Motor motor, int step, double sigma0, double reach, double burn) {
        /** След двигателя: начальная ширина — из его факела у земли (без факела — струя к концу её ядра, вдвое шире среза). */
        public static Trail of(Motor motor, int step) {
            Flame f = FlameModel.of(motor, 0);
            return new Trail(motor, step, f != null ? f.spread() / 2 : 2 * motor.exitRadius() * Math.sqrt(motor.nozzles()), 0, 0);
        }

        /**
         * Тот же след по настоящему пути выгорания reach за время burn: на игровой скорости снаряд проходит его дольше, чем
         * горит игровой двигатель, и след ложится и после факела — не по физике (у «Града» при игровой скорости ~80 м/с
         * двигатель горит 2 с, ~160 м, а след — ~650 м, как у настоящего); на настоящей скорости — кончается на том же пути.
         */
        public Trail alongBurn(double reach, double burn) {
            return new Trail(motor, step, sigma0, reach, burn);
        }

        /** Секунды на метр следа: по настоящему пути выгорания или, без него, по скорости снаряда speed в ветре wind, м/с. */
        public double inverseSpeed(double speed, double wind) {
            return reach > 0 ? burn / reach : TrailModel.inverseSpeed(speed, wind);
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
        out[0] = width(tau, sigma);
        out[1] = 1 - Math.exp(-tau);
    }

    /**
     * Ширина гауссовой трубки сечения σ с толщей поперёк середины tau на половине непрозрачности середины, м:
     * e^(−τg) = (1 + e^(−τ))/2, ширина — 2σ·√(−2·ln g). Тоньше порога видимости — 0.
     */
    public static double width(double tau, double sigma) {
        if (!(tau > THRESHOLD)) return 0;
        double clear = Math.exp(-tau), g = -Math.log((1 + clear) / 2) / tau;
        return 2 * sigma * Math.sqrt(-2 * Math.log(g));
    }

    /** σ сечения в возрасте age секунд при подъёме lift метров, м. */
    public static double sigma(Trail t, double age, double stability, double wind, double lift) {
        double th = ALPHA * lift / 2;
        return Math.sqrt(t.sigma0() * t.sigma0() + spread(age, stability, wind) + th * th);
    }

    /**
     * σ_r² расплывания самого следа в возрасте age секунд, м²: Ричардсон — Обухов g·ε·t³/6 по оси, не шире шлейфа Бриггса
     * σ_y·σ_z на пути x = u·t.
     */
    static double spread(double age, double stability, double wind) {
        double x = Math.max(0, wind) * age;
        return Math.min(RICHARDSON * dissipation(stability) * age * age * age / 6, Stability.sigmaY(stability, x) * Stability.sigmaZ(stability, x));
    }

    /** Скорость диссипации в классе stability, м²/с³ ({@link #DISSIPATION}). */
    static double dissipation(double stability) {
        double c = Math.clamp(stability, Stability.A, Stability.F);
        int k = (int) Math.min(4, Math.floor(c));
        return DISSIPATION[k] * Math.pow(DISSIPATION[k + 1] / DISSIPATION[k], c - k);
    }

    /**
     * Сдвиг оси следа вихрями крупнее его самого в out (x, y, z мира, м): точка следа seed, s метров по пути снаряда от
     * начала, в возрасте age секунд. Сколько — по закону: усреднённый шлейф Бриггса = расплывание следа + мотание его оси,
     * поэтому СКО сдвига вбок — √(σ_y² − σ_r²) (по обеим горизонталям), по высоте — √(σ_z² − σ_r²), и не ниже земли: не
     * больше трети clearance (высоты точки над землёй при рождении). Как — по вихрям: вихрь размера λ имеет скорость
     * v = (ελ)^⅓ и живёт T = λ/v (Колмогоров); за время t он сдвигает ось на v·t, пока не распался, дальше — как случайное
     * блуждание, вклад в дисперсию — v²·t·min(t, T). Октава λ = 4·2^k м — плавный шум вдоль пути (s/λ) и во времени (t/T):
     * изгибы длиной с вихрь, мелкие меняются быстро, крупные — минутами. Вихри мельче самого следа (σ_r) и мельче
     * {@link #POINTS_PER_EDDY} шагов точек spacing (м) не рисуются — их дисперсия отдаётся крупным (сумма та же).
     * ⚠ Разложение по октавам — оценка формы; закон — только итоговая дисперсия.
     */
    public static void wander(long seed, double s, double age, double stability, double wind, double spacing, double clearance, double[] out) {
        out[0] = out[1] = out[2] = 0;
        if (age <= 0) return;
        double x = Math.max(0, wind) * age, rel = spread(age, stability, wind);
        double sy = Stability.sigmaY(stability, x), sz = Stability.sigmaZ(stability, x);
        double side = Math.sqrt(Math.max(0, sy * sy - rel)), up = Math.min(Math.sqrt(Math.max(0, sz * sz - rel)), Math.max(0, clearance) / 3);
        if (side <= 0 && up <= 0) return;
        double eps = dissipation(stability), cut = Math.max(Math.sqrt(rel), POINTS_PER_EDDY * spacing);
        double total = 0, ax = 0, ay = 0, az = 0;
        for (int k = 0; k < OCTAVES; k++) {
            double lambda = SMALLEST_EDDY * (1 << k);
            // плавно на двух октавах над порогом: ось уходит в ширину следа постепенно
            double fade = Math.clamp(Math.log(lambda / cut) / Math.log(4), 0, 1);
            if (fade <= 0) continue;
            double v = Math.cbrt(eps * lambda), turnover = lambda / v;
            fade = fade * fade * (3 - 2 * fade);
            // доля — амплитуде, не дисперсии: у корня доли дисперсии у нуля производная бесконечна — ось дёрнулась бы
            double w2 = fade * fade * v * v * Math.min(age, turnover);
            double w = Math.sqrt(w2), u = s / lambda, tt = age / turnover;
            long o = seed + k * 0x9E3779B97F4A7C15L;
            ax += w * noise(o, u, tt);
            az += w * noise(o ^ 0x5DEECE66DL, u, tt);
            ay += w * noise(o ^ 0x2545F4914F6CDD1DL, u, tt);
            total += w2;
        }
        if (total <= 0) return;
        double norm = 1 / Math.sqrt(total);
        out[0] = side * ax * norm;
        out[1] = up * ay * norm;
        out[2] = side * az * norm;
        // вниз — мягко не глубже земли (дым по ней растекается, а не уходит в неё)
        double floor = 0.8 * clearance;
        if (out[1] < 0 && floor < Double.POSITIVE_INFINITY) out[1] = -floor * Math.tanh(-out[1] / Math.max(floor, 1e-6));
    }

    /**
     * Шум октавы с дисперсией 1 в каждой точке (u — путь в длинах вихря, t — время в его жизнях): две бегущие навстречу
     * гармоники со случайными по зерну волновым числом (0,75–1,25 волны на длину вихря), частотой (0,75–1,25 радиана за
     * его жизнь) и фазой — случайные фурье-моды синтетической турбулентности (Kraichnan 1970). У каждой дисперсия ½, ось
     * сдвигается не быстрее 1,25·√2 амплитуды за жизнь вихря — с его скоростью, а не рывками. Встречные волны — чтобы изгибы
     * не бежали вдоль следа змейкой.
     */
    static double noise(long seed, double u, double t) {
        long h1 = mix(seed), h2 = mix(h1), h3 = mix(h2);
        double tau = 2 * Math.PI;
        return Math.cos(tau * (u * jitter(h1) + unit(h1 >>> 42)) + t * jitter(h1 >>> 21))
                + Math.cos(tau * (u * jitter(h2) + unit(h3)) - t * jitter(h2 >>> 21));
    }

    /** 0,75–1,25 по 21 биту. */
    private static double jitter(long h) {
        return 0.75 + 0.5 * unit(h);
    }

    /** 0…1 по младшим 21 биту. */
    private static double unit(long h) {
        return (h & 0x1FFFFF) * 0x1.0p-21;
    }

    /** Перемешивание SplitMix64. */
    private static long mix(long h) {
        h += 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ h >>> 31;
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
