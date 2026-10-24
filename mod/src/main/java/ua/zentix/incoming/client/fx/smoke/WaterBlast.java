package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.nuclear.model.WaterModel;

/**
 * Обычный взрыв на воде (снаряд рвётся о гладь или под ней), без Minecraft: столб брызг (султан), его падение завесой,
 * базисная волна водяной пыли, пена и волна-кольцо на воде. Метры, секунды, килограммы ТНТ.
 * <ul>
 * <li><b>Размер</b> — таблица Swisdak («Explosion Effects and Properties, Part II: Explosion Effects in Water»,
 * NSWC/WOL TR 76-116, 1978, гл. 14, табл. 14): наибольшая высота и радиус столба TNT — H/W^⅓ и R/W^⅓ по приведённой
 * глубине заряда Y/W^¼ (W — кг ТНТ, Y — м под гладью). Таблица — для безопасных расстояний: «настоящий столб бывает
 * до 50 % меньше» (оговорка там же); берём ту долю, что гл. 13 того же отчёта добавляет к своей высоте струи для
 * безопасности, — высоту ÷ {@link #MARGIN_H} (1,4), радиус ÷ {@link #MARGIN_R} (1,3) (⚠ оценка внутри диапазона
 * источника). Сверка: у 500 кг на 30 м таблица даёт 56 м (пример отчёта), у 9,1 кг на 10 м — 23 м; струя заряда на дне
 * мелкой воды по гл. 13 (Milligan, Young 1954) при Y/W^¼ = 0,74 — 31·W^⅓ против 38,6/1,4 = 28·W^⅓ здесь. Удар о гладь
 * — строка Y = 0 (⚠ половина контактного заряда над водой; таблица начинается с заряда у самой глади). Шахед (50 кг) —
 * столб ~78 м, радиус ~24 м; ракета (150 кг) — ~113 м; «Град» (6,4 кг) — ~39 м.</li>
 * <li><b>Форма и время</b> — баллистика: купол над пузырём рвётся струями, которые летят дальше без сопротивления (масса
 * воды на площадь сечения — тонны на квадратный метр, торможение воздухом на десятках метров мало). Струи — веер с
 * наибольшей скоростью √(2gH) в конусе с полууглом θ: вершина крайней струи — на высоте H·cos²θ и в стороне H·sin2θ,
 * это и есть радиус столба: sin2θ = R/H (у шахеда θ ≈ 9°). Скорости струй — от доли {@link #SPEEDS} до полной: столб
 * заполнен от воды до верха (верх √(2H/g) = 4 с у шахеда). Падают струи завесой: медленные раньше и ближе, крайние — на
 * 2R от середины.</li>
 * <li><b>Вода</b> — та же доля энергии заряда поднимает воду столба, что у «Бейкера» (Glasstone и Dolan, §2.52: ~1 млн т
 * на 1,8 км при 23 кт): m·g·H/2 ∝ W, m = m_Б·(W/W_Б)·(H_Б/H) (⚠ допущение: доля одна у ядерного и обычного подрыва) —
 * у шахеда ~50 т;
 * ослабление брызг — капли радиуса {@link #DROP_R} м: 3/(2ρr) м²/кг (геометрическая оптика); в клуб она идёт
 * каплями сетки ({@link CloudModel#DROP_K}, капли 5 мкм) — пересчётом массы на то же ослабление. Падая, столб
 * оставляет водяную пыль — долю {@link #MIST} мелких капель ({@link #MIST_DROP_R}) кольцом базисной волны
 * ({@link #surgeRadius}, закон ядерки), её сносит ветром и расплывает, как облако.</li>
 * <li><b>На воде</b> — пена, где упала вода (взбитая пузырями вода, ⚠ тает за {@link #FOAM_S} с — оценка), и волна от
 * полости взрыва: кольцо идёт с групповой скоростью волн длины диаметра столба у его основания, на мелкой воде — не
 * быстрее √(gD) ({@link #waveRadius}).</li>
 * </ul>
 */
public final class WaterBlast {
    /** Приведённая глубина заряда Y/W^¼, м/кг^¼ (Swisdak 1978, табл. 14). */
    static final double[] DEPTH = {0, 0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5, 5.5, 6, 6.5, 7, 7.5, 8, 8.5, 9};
    /** Наибольший радиус столба R/W^⅓, м/кг^⅓ (там же). */
    static final double[] SCALED_R = {8.3, 8.3, 8.3, 10, 12.8, 12.8, 12.8, 12.8, 12.8, 11.6, 10.6, 9.5, 8.5, 7.4, 6.4, 5.4, 4.3, 3.3, 2.3};
    /** Наибольшая высота столба H/W^⅓, м/кг^⅓ (там же; у 0,5 скан нечёток — 38,6, как у 1,0). */
    static final double[] SCALED_H = {29.7, 38.6, 38.6, 29, 20.6, 19.7, 18.9, 18, 17.1, 16.2, 15.4, 13.6, 9.5, 5.9, 5.9, 5.9, 5.9, 5.9, 5.9};
    /** Запас таблицы для безопасности по высоте и радиусу (гл. 13 того же отчёта: +40 % к высоте, +30 % к диаметру). */
    static final double MARGIN_H = 1.4, MARGIN_R = 1.3;
    /** Заряд «Бейкера», кг ТНТ. */
    static final double BAKER_KG = 23e6;
    /** Масса воды в столбе «Бейкера», кг (Glasstone, Dolan §2.52: около миллиона тонн; ⚠ по памяти) и его высота, м (§2.47). */
    static final double BAKER_WATER_KG = 1e9, BAKER_TOP = 1800;
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
    static final double G = 9.80665;
    /**
     * Струи столба: доли наибольшей скорости (верх струи — доля² высоты) и доли полуугла конуса с числом струй по
     * кругу у каждой. Сколько воды в струе — поровну (⚠ оценка: у настоящего столба вершина — тонкие струи, низ — купол).
     */
    static final double[] SPEEDS = {0.35, 0.55, 0.72, 0.86, 1};
    static final double[] TILTS = {0, 0.5, 1};
    static final int[] AROUND = {1, 5, 7};
    /** Расплывание струи брызг поперёк — доля пройденного пути (полуугол ~7°, ⚠ оценка формы). */
    static final double JET_SPREAD = 0.12;
    /** За сколько тает пена на воде, с (e-кратно; ⚠ оценка: барашки ветра — секунды, взбитая взрывом вода глубже). */
    static final double FOAM_S = 15;
    /** Клубов кольца базисной волны. */
    static final int RING = 8;

    /** Высота и радиус столба, м; время подъёма до верха, с; вода, кг; глубина заряда и воды под ним, м. */
    public final double height, radius, rise, water, depth, waterDepth;
    /** Наибольшая скорость струй, м/с; полуугол конуса струй, рад. */
    public final double speed, cone;
    private final CloudModel.Air air;
    /** Заряд, кг ТНТ. */
    private final double kg;

    /**
     * @param tntKg      заряд, кг ТНТ
     * @param depthM     заряд под гладью, м (удар о воду — 0)
     * @param waterDepth глубина воды под местом взрыва, м (не знаем — бесконечность: глубокая)
     */
    public WaterBlast(double tntKg, double depthM, double waterDepth, CloudModel.Air air) {
        this.air = air;
        kg = tntKg;
        depth = Math.max(0, depthM);
        this.waterDepth = waterDepth > 0 ? waterDepth : Double.POSITIVE_INFINITY;
        double cube = Math.cbrt(Math.max(tntKg, 0)), scaled = tntKg > 0 ? depth / Math.pow(tntKg, 0.25) : 0;
        height = table(SCALED_H, scaled) * cube / MARGIN_H;
        radius = table(SCALED_R, scaled) * cube / MARGIN_R;
        speed = Math.sqrt(2 * G * height);
        rise = speed / G;
        cone = 0.5 * Math.asin(Math.min(1, radius / Math.max(height, 1e-9)));
        water = height > 0 ? BAKER_WATER_KG * tntKg / BAKER_KG * BAKER_TOP / height : 0;
    }

    /** Значение таблицы по приведённой глубине (линейно между строками, за краем — последняя строка). */
    static double table(double[] values, double scaled) {
        if (scaled <= DEPTH[0]) return values[0];
        for (int i = 1; i < DEPTH.length; i++) {
            if (scaled <= DEPTH[i]) {
                double f = (scaled - DEPTH[i - 1]) / (DEPTH[i] - DEPTH[i - 1]);
                return values[i - 1] + f * (values[i] - values[i - 1]);
            }
        }
        return values[values.length - 1];
    }

    /** Время полёта струи с долей скорости s под углом a к вертикали, с: туда и обратно до глади. */
    double flight(double s, double a) {
        return 2 * s * speed * Math.cos(a) / G;
    }

    /** Верх столба в возрасте t, м: самая быстрая отвесная струя (до вершины — вверх, потом — обратно к воде). */
    public double top(double t) {
        if (t <= 0 || t >= 2 * rise) return 0;
        return speed * t - 0.5 * G * t * t;
    }

    /** Доля воды, что ещё в воздухе в возрасте t: струи, что не упали. */
    public double aloft(double t) {
        if (t <= 0) return 1;
        double up = 0, all = 0;
        for (double s : SPEEDS) {
            for (int k = 0; k < TILTS.length; k++) {
                all += AROUND[k];
                if (t < flight(s, TILTS[k] * cone)) up += AROUND[k];
            }
        }
        return up / all;
    }

    /** Радиус на воде, куда к возрасту t уже упала вода столба, м (0 — ещё ничего): дальняя из упавших струй. */
    public double fallen(double t) {
        double r = 0;
        for (double s : SPEEDS) {
            for (double tilt : TILTS) {
                double a = tilt * cone;
                if (t >= flight(s, a)) r = Math.max(r, s * s * speed * speed * Math.sin(2 * a) / G);
            }
        }
        return r;
    }

    /** Масса капель сетки (5 мкм) с тем же ослаблением, что у {@code kg} брызг столба. */
    static double asGridDrops(double kg) {
        return asGridDrops(kg, DROP_R);
    }

    /** То же у капель радиуса {@code r}, м: ослабление 3/(2ρr) м²/кг. */
    static double asGridDrops(double kg, double r) {
        return kg * 3 / (2 * 1000 * r) / CloudModel.DROP_K;
    }

    /** Скорость оседания капель водяной пыли по Стоксу, м/с: 2ρgr²/(9μ). */
    static double mistSettling() {
        return 2 * 1000 * G * MIST_DROP_R * MIST_DROP_R / (9 * AIR_MU);
    }

    /**
     * Доля водяной пыли, что ещё в воздухе через since секунд после начала падения: слой высотой с базисную волну,
     * перемешанный, теряет капли через дно со скоростью оседания — e^(−v·t/h) (испарение не считается: оно только
     * укорачивает жизнь).
     */
    double mistLeft(double since) {
        return Math.exp(-mistSettling() * Math.max(0, since) / surgeTop());
    }

    /** Когда вода начинает падать в воду, с: самая медленная струя. */
    double landing() {
        return flight(SPEEDS[0], cone);
    }

    /**
     * Базисная волна: падая, вода столба растекается кольцом брызг — тот же закон, что у ядерки ({@link WaterModel#surgeReach},
     * {@link WaterModel#surgeTop}: доля «Бейкера» по кубическому корню), начало — когда вода начинает падать
     * ({@link #landing}), растекается со скоростью падения √(2gH) (постоянная — предел радиуса на неё, ⚠ оценка: у
     * «Бейкера» волна идёт ~25–30 м/с), от места, куда падает завеса (радиус столба), брызги — доля {@link #MIST} воды
     * столба, расплываются по Бриггсу и сносятся ветром.
     */
    public double surgeRadius(double since) {
        if (since <= 0) return 0;
        double reach = surgeReach(), tau = reach / speed;
        return radius + reach * (1 - Math.exp(-since / Math.max(tau, 1e-3)));
    }

    double surgeReach() {
        return WaterModel.surgeReach(kg / 1e6);
    }

    double surgeTop() {
        return Math.max(1, WaterModel.surgeTop(kg / 1e6));
    }

    /** Сколько живёт водяная пыль, с от взрыва: пока толща клуба кольца поперёк больше порога (с оседанием). */
    public double mistLife() {
        double ext = CloudModel.DROP_K * asGridDrops(MIST * water, MIST_DROP_R) / RING;
        double lo = 0, hi = CloudModel.LIFE_MAX;
        for (int i = 0; i < 30; i++) {
            double mid = 0.5 * (lo + hi);
            if (ext * mistLeft(mid) / (2 * Math.PI * mistSigmaH(mid) * mistSigmaV(mid)) >= CloudModel.SEEN) lo = mid;
            else hi = mid;
        }
        return Math.max(landing() + lo, 2 * rise);
    }

    /** σ клуба кольца через since секунд после начала падения: шаг кольца, высота волны и расплывание по Бриггсу. */
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
     * Клубы брызг в возрасте t от места взрыва на глади (центр от точки взрыва, м): струи столба — каждая гауссом брызг на
     * своей параболе, расплываясь поперёк ({@link #JET_SPREAD}) и вдоль (соседние по скорости струи сливаются в сплошной
     * столб: σ по вертикали — половина шага между ними); упавшая в воду струя — уже не в воздухе. Водяная пыль — кольцом
     * базисной волны, пока вода падает и после, у воды, сносится ветром. false — брызг уже нет.
     */
    public boolean blobs(double t, SmokeGrid.Sink out, double x, double y, double z) {
        if (t < 0) return true;
        double each = asGridDrops(water) / jets();
        // шаг верха соседних струй — у верхних он меньше: σ по вертикали на половину наибольшего шага (без щелей)
        double gap = (SPEEDS[SPEEDS.length - 1] - SPEEDS[SPEEDS.length - 2]) * speed * t;
        for (int j = 0; j < SPEEDS.length; j++) {
            double s = SPEEDS[j], v = s * speed;
            double step = (j == 0 ? SPEEDS[0] : s - SPEEDS[j - 1]) * speed * t;
            for (int k = 0; k < TILTS.length; k++) {
                double a = TILTS[k] * cone;
                if (t >= flight(s, a)) continue;
                double up = v * Math.cos(a) * t - 0.5 * G * t * t, out_ = v * Math.sin(a) * t;
                double path = Math.hypot(up, out_);
                double sh = Math.max(radius / 6, radius / 6 + JET_SPREAD * path), sv = Math.max(sh, 0.6 * Math.max(step, gap));
                // струи по кругу: у каждой тройки углов свой поворот (не в линию), по зерну не надо — форма одна
                for (int i = 0; i < AROUND[k]; i++) {
                    double az = 2 * Math.PI * (i + 0.5 * k) / AROUND[k];
                    out.blob(x + out_ * Math.cos(az), y + up, z + out_ * Math.sin(az), sh, Math.min(sv, Math.max(sh, up)), 0, 0, each, 1, 1, 1,
                            CloudModel.T_AIR_K, Math.max(0, v * Math.cos(a) - G * t));
                }
            }
        }
        double since = t - landing();
        if (since > 0) {
            if (t > mistLife()) return false;
            // кольцо набирает воду, пока столб падает
            double mist = asGridDrops(MIST * water, MIST_DROP_R) * (1 - aloft(t)) * mistLeft(since) / RING;
            double r = surgeRadius(since), lean = air.wind(surgeTop()) * since, sh = mistSigmaH(since), sv = mistSigmaV(since);
            if (mist > 0) {
                for (int i = 0; i < RING; i++) {
                    double a = 2 * Math.PI * i / RING;
                    out.blob(x + r * Math.cos(a) + air.windX() * lean, y + sv, z + r * Math.sin(a) + air.windZ() * lean, sh, sv, 0, 0, mist, 1, 1, 1,
                            CloudModel.T_AIR_K, 0);
                }
            }
        }
        return true;
    }

    /** Струй в столбе (по всем скоростям, углам и кругу). */
    static int jets() {
        int n = 0;
        for (int k : AROUND) n += k;
        return n * SPEEDS.length;
    }

    /**
     * Волна от полости взрыва: радиус переднего гребня в возрасте t, м. Полость — половина радиуса столба, её волны —
     * длины λ = 2R (⚠ оценка размера); фронт группы идёт с dω/dk, ω² = gk·tanh(kD), k = 2π/λ (линейная теория
     * гравитационных волн; WEC-Sim, Theory / Regular Wave Power,
     * https://wec-sim.github.io/WEC-Sim/main/theory/theory.html). Предел глубокой воды — ½√(gλ/2π), мелкой — √(gD).
     * Это модель малой волны за полостью, не нелинейного начального выброса.
     */
    public double waveRadius(double t) {
        return radius / 2 + waveSpeed() * Math.max(0, t);
    }

    /** Скорость фронта волны по воде, м/с. */
    public double waveSpeed() {
        if (radius <= 0) return 0;
        double k = Math.PI / radius, kh = k * waterDepth;
        // Глубокая вода: kh/sinh(2kh) → 0; отдельная ветка избегает ∞/∞ при неизвестном дне.
        if (kh > 20) return 0.5 * Math.sqrt(G / k);
        if (kh < 1e-6) return Math.sqrt(G * waterDepth);
        return 0.5 * Math.sqrt(G * Math.tanh(kh) / k) * (1 + 2 * kh / Math.sinh(2 * kh));
    }

    /** Длина волны кольца, м. */
    public double waveLength() {
        return 2 * radius;
    }

    /**
     * Высота волны кольца на радиусе r относительно у полости, 0..1: цилиндрическая волна — √(r₀/r) (энергия фронта
     * на длину окружности), тает и вязкостью — за минуту (⚠ оценка).
     */
    public double waveAt(double r, double t) {
        double r0 = radius / 2;
        return Math.sqrt(r0 / Math.max(r, r0)) * Math.exp(-t / 60);
    }

    /**
     * Пена на воде на расстоянии r от середины в возрасте t, 0..1: где упала вода столба (завеса ложится от середины
     * наружу: медленные струи — ближе и раньше) — с мига падения, у самой полости (купол, ½R) — сразу; тает за
     * {@link #FOAM_S} с.
     */
    public double foam(double r, double t) {
        double since;
        if (r <= radius / 2) since = t;
        else {
            // когда завеса дошла сюда: струя, что падает на r, — доля скорости s с s² = r / (наибольшей дальности)
            double reach = speed * speed * Math.sin(2 * cone) / G;
            if (r > reach) return 0;
            double s = Math.sqrt(r / Math.max(reach, 1e-9));
            since = t - flight(Math.max(s, SPEEDS[0]), cone);
        }
        return since < 0 ? 0 : Math.exp(-since / FOAM_S);
    }

    /** Сколько живёт картина на воде (пена и волна), с. */
    public double waterLife() {
        return 2 * rise + 4 * FOAM_S;
    }
}
