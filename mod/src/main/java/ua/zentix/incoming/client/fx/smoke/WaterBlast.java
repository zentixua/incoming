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
 * воды на площадь сечения — тонны на квадратный метр, торможение воздухом на десятках метров мало). H и R таблицы — что
 * видно (верх и наибольший радиус султана на снимках), поэтому на них — видимый край брызг, а не середина струй: у
 * густой струи край — там, где толща поперёк падает до порога видимости ({@link CloudModel#SEEN}), за
 * {@link #halo} σ от оси. Струя одного направления — отрезок: вода, выброшенная в один миг с разной скоростью, лежит на
 * прямой (место линейно по скорости), сечение — круглое, σ {@link #jet}. Середина самой быстрой отвесной струи
 * поднимается на H − ореол (скорость √(2g(H − ореол)), верх у шахеда ~3,7 с); веер струй — конус с полууглом θ.
 * R таблицы — наибольший видимый радиус за весь полёт, а дальше всего брызги уходят, падая: крайние быстрые струи
 * ложатся в 2H'·sin2θ от середины, и с ореолом это R (у вершины столб уже, ~¾R). Скорости — поровну от
 * {@link #SPEED_MIN} до полной: столб заполнен от воды до верха. Падают струи завесой: медленные раньше и ближе.</li>
 * <li><b>Вода</b> — та же доля энергии заряда поднимает воду столба, что у «Бейкера» (Glasstone и Dolan, §2.52: ~1 млн т
 * на 1,8 км при 23 кт): m·g·H/2 ∝ W, m = m_Б·(W/W_Б)·(H_Б/H) (⚠ допущение: доля одна у ядерного и обычного подрыва) —
 * у шахеда ~50 т;
 * ослабление брызг — капли радиуса {@link #DROP_R} м: 3/(2ρr) м²/кг (геометрическая оптика); в клуб она идёт
 * каплями сетки ({@link CloudModel#DROP_K}, капли 5 мкм) — пересчётом массы на то же ослабление. Падая, столб
 * оставляет водяную пыль — долю {@link #MIST} мелких капель ({@link #MIST_DROP_R}) кольцом базисной волны: видимый
 * край кольца — на радиусе базисной волны ({@link #surgeRadius}, закон ядерки), верх слоя — на её высоте; её сносит
 * ветром и расплывает, как облако, а капли оседают в воду через дно слоя ({@link #mistLeft}).</li>
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
     * волна держится над водой: по Стоксу v = 2ρgr²/(9μ) у 50 мкм — 0,3 м/с (за 10 с — 3 м при высоте волны в метры; у
     * Re ≈ 2 Стокс завышает на ~10 %: замер Gunn, Kinzer 1949 у капли 0,1 мм — 0,27 м/с), а миллиметровые капли столба
     * падают со скоростью ~4 м/с и уходят в воду сразу (размер — О, по скорости оседания).
     */
    static final double MIST_DROP_R = 50e-6;
    /** Вязкость воздуха у земли, Па·с (15 °C). */
    static final double AIR_MU = 1.81e-5;
    /**
     * Сопротивление глади ветру: u* = √C_D·U₁₀, C_D = 1,2·10⁻³ при ветре 4–11 м/с (Large, Pond, «Open ocean momentum flux
     * measurements in moderate to strong winds», J. Phys. Oceanogr. 11, 1981); постоянная Кармана.
     */
    static final double DRAG_WATER = 1.2e-3, KARMAN = 0.4;
    /**
     * Число Рауза P = v/(κu*), выше которого турбулентность у глади капли не держит: взвеси нет, слой оседает сам
     * (van Rijn, «Sediment transport, part II: suspended load transport», J. Hydraul. Eng. 110, 1984: взвесь — при
     * P ≲ 2,5). Капли водяной пыли (0,3 м/с) при ветре 6 м/с — P ≈ 3,6: слой не растёт вверх, как газ по Бриггсу.
     */
    static final double ROUSE_SUSPENDED = 2.5;
    static final double G = 9.80665;
    /**
     * Струи столба: наименьшая доля наибольшей скорости (вода — поровну по скорости от неё до полной; медленнее — купол
     * над пузырём, его вода падает сразу) и доли полуугла конуса с числом струй по кругу у каждой (⚠ оценка: у
     * настоящего столба вершина — тонкие струи, низ — купол).
     */
    static final double SPEED_MIN = 0.35;
    static final double[] TILTS = {0, 0.5, 1};
    static final int[] AROUND = {1, 5, 7};
    /**
     * Видимый ореол брызг одной струи (край её сечения) — такая доля радиуса столба; остальное — разлёт середин струй
     * (⚠ оценка формы: таблица даёт только внешний край столба, R и H, а как он делится между разлётом струй и ореолом
     * каждой, в источнике нет).
     */
    static final double JET_HALO = 0.5;
    /** За сколько тает пена на воде, с (e-кратно; ⚠ оценка: барашки ветра — секунды, взбитая взрывом вода глубже). */
    static final double FOAM_S = 15;
    /** Клубов кольца базисной волны. */
    static final int RING = 8;

    /** Высота и радиус столба, м; время подъёма до верха, с; вода, кг; глубина заряда и воды под ним, м. */
    public final double height, radius, rise, water, depth, waterDepth;
    /** Наибольшая скорость струй (у их середин), м/с; полуугол конуса струй, рад. */
    public final double speed, cone;
    /** σ круглого сечения струи, м; видимый край её ореола у вершины, в σ (толща поперёк — до {@link CloudModel#SEEN}). */
    public final double jet, halo;
    private final CloudModel.Air air;
    /** Заряд, кг ТНТ. */
    private final double kg;
    /** Сколько живёт водяная пыль, с (считается один раз). */
    private double mistLife = -1;

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
        water = height > 0 ? BAKER_WATER_KG * tntKg / BAKER_KG * BAKER_TOP / height : 0;
        // ореол струи: её сечение такое, что видимый край (толща поперёк до порога видимости) у вершины — доля радиуса
        // столба; толща — у отвесной струи в вершине (в воздухе — скорости от ½ до полной, отрезок от глади до верха)
        double sigma = 0, edge = 1, centre = height, tilt = 0, landing = 1;
        if (height > 0) {
            double ext = water * 3 / (2 * 1000 * DROP_R) / directions();
            sigma = JET_HALO * radius / 3;
            for (int i = 0; i < 30; i++) {
                centre = Math.max(0.5 * height, height - edge * sigma);
                edge = visible(ext / ((1 - SPEED_MIN) * 2 * centre) / (Math.sqrt(2 * Math.PI) * sigma));
                sigma = JET_HALO * radius / edge;
            }
            centre = Math.max(0.5 * height, height - edge * sigma);
            // конус — по огибающей всего полёта: R таблицы — наибольший видимый радиус султана, а дальше всего брызги
            // уходят, когда крайние быстрые струи падают в воду — середина на 2H'·sin2θ, ореол поперёк струи (по
            // горизонтали — ÷cos θ); вода струи на метр отрезка — ext/((1 − s₀)·v·t), к падению (t = 2·подъём·cos θ) толща
            // поперёк — вдвое/cos θ меньше, чем в вершине
            for (int i = 0; i < 30; i++) {
                double cos = Math.cos(tilt);
                landing = visible(ext / ((1 - SPEED_MIN) * 2 * centre * 2 * cos) / (Math.sqrt(2 * Math.PI) * sigma));
                tilt = 0.5 * Math.asin(Math.clamp((radius - landing * sigma / cos) / (2 * centre), 0, 1));
            }
        }
        jet = sigma;
        halo = edge;
        speed = Math.sqrt(2 * G * centre);
        rise = speed / G;
        cone = tilt;
    }

    /** Видимый край гаусса толщи tau поперёк середины, в σ: где толща падает до порога видимости {@link CloudModel#SEEN}. */
    static double visible(double tau) {
        return Math.sqrt(2 * Math.log(Math.max(tau, CloudModel.SEEN * Math.E) / CloudModel.SEEN));
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

    /** Верх середины столба в возрасте t, м: самая быстрая отвесная струя (до вершины — вверх, потом — обратно к воде). */
    public double top(double t) {
        if (t <= 0 || t >= 2 * rise) return 0;
        return speed * t - 0.5 * G * t * t;
    }

    /**
     * Доля скорости, медленнее которой вода струи под углом a к вертикали к возрасту t уже в воде (не меньше
     * {@link #SPEED_MIN}): полёт доли s — 2·s·v·cos a / g.
     */
    double slowest(double t, double a) {
        return Math.max(SPEED_MIN, G * Math.max(t, 0) / (2 * Math.max(speed, 1e-9) * Math.cos(a)));
    }

    /** Доля воды струи под углом a, что ещё в воздухе в возрасте t (скорости поровну от {@link #SPEED_MIN} до полной). */
    double share(double t, double a) {
        return Math.clamp((1 - slowest(t, a)) / (1 - SPEED_MIN), 0, 1);
    }

    /** Доля воды, что ещё в воздухе в возрасте t: струи, что не упали. */
    public double aloft(double t) {
        if (t <= 0) return 1;
        double up = 0, all = 0;
        for (int k = 0; k < TILTS.length; k++) {
            all += AROUND[k];
            up += AROUND[k] * share(t, TILTS[k] * cone);
        }
        return up / all;
    }

    /** Радиус на воде, куда к возрасту t уже упала вода столба, м (0 — ещё ничего): дальняя из упавших струй. */
    public double fallen(double t) {
        double r = 0;
        for (double tilt : TILTS) {
            double a = tilt * cone, s = Math.min(1, G * Math.max(t, 0) / (2 * Math.max(speed, 1e-9) * Math.cos(a)));
            if (s >= SPEED_MIN) r = Math.max(r, s * s * speed * speed * Math.sin(2 * a) / G);
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
     * Доля водяной пыли, что ещё в воздухе через since секунд после начала падения: капли оседают через дно слоя —
     * поток v·c(0), c(0) — у глади; у клуба кольца (гаусс σv с серединой на высоте σv, {@link #blobs}) слой воды на
     * единицу площади — c(0)·√(2π)·e^½·σv, поэтому dM/dt = −v·M / (√(2π)·e^½·σv(t)): в начале (верх слоя — высота
     * базисной волны) — как перемешанный слой её высоты, e^(−v·t/h); расплываясь вверх, слой теряет капли медленнее.
     * Испарение не считается: оно только укорачивает жизнь.
     */
    double mistLeft(double since) {
        if (since <= 0) return 1;
        // интеграл по середине 24 равных шагов: σv(t) гладкая (√(σ0² + (k·путь)²))
        int n = 24;
        double sum = 0, v = mistSettling(), depth = Math.sqrt(2 * Math.PI * Math.E);
        for (int i = 0; i < n; i++) {
            double t = since * (i + 0.5) / n;
            sum += v / (depth * mistSigmaV(t)) * since / n;
        }
        return Math.exp(-sum);
    }

    /** Когда вода начинает падать в воду, с: самая медленная струя. */
    double landing() {
        return flight(SPEED_MIN, cone);
    }

    /**
     * Базисная волна: падая, вода столба растекается кольцом брызг — тот же закон, что у ядерки ({@link WaterModel#surgeReach},
     * {@link WaterModel#surgeTop}: доля «Бейкера» по кубическому корню), начало — когда вода начинает падать
     * ({@link #landing}), растекается со скоростью падения √(2gH) (постоянная — предел радиуса на неё, ⚠ оценка: у
     * «Бейкера» волна идёт ~25–30 м/с), от места, куда падает завеса (радиус столба), брызги — доля {@link #MIST} воды
     * столба, расплываются по Бриггсу и сносятся ветром. Радиус и высота закона — видимый край кольца.
     */
    public double surgeRadius(double since) {
        if (since <= 0) return 0;
        double reach = surgeReach(), tau = reach / Math.max(speed, 1e-9);
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
        if (mistLife >= 0) return mistLife;
        double lo = 0, hi = CloudModel.LIFE_MAX;
        for (int i = 0; i < 30; i++) {
            double mid = 0.5 * (lo + hi);
            if (mistTau(mid) >= CloudModel.SEEN) lo = mid;
            else hi = mid;
        }
        mistLife = Math.max(landing() + lo, 2 * rise);
        return mistLife;
    }

    /** Толща клуба кольца поперёк середины через since секунд после начала падения (вся вода столба уже в воде). */
    double mistTau(double since) {
        double ext = CloudModel.DROP_K * asGridDrops(MIST * water, MIST_DROP_R) / RING * mistLeft(since);
        double sv = mistSigmaV(since);
        return ext / (2 * Math.PI * mistSigmaH(since, ringCentre(since, ext, sv)) * sv);
    }

    /**
     * Середина клубов кольца через since секунд после начала падения, м от оси: видимый край кольца (толща поперёк до
     * порога видимости) — на радиусе базисной волны, середины — внутрь на столько σh; ext — ослабление клуба, м².
     */
    double ringCentre(double since, double ext, double sv) {
        double outer = surgeRadius(since), r = 0.5 * outer;
        for (int i = 0; i < 8; i++) {
            double sh = mistSigmaH(since, r);
            r = Math.max(0, outer - visible(ext / (2 * Math.PI * sh * sv)) * sh);
        }
        return r;
    }

    /** σh клуба кольца с серединой на r через since секунд после начала падения: шаг кольца и расплывание по Бриггсу. */
    double mistSigmaH(double since, double r) {
        double s = CloudModel.spreadH(air.spread(), path(since)), ring = Math.PI * Math.max(r, radius / 2) / RING;
        return Math.sqrt(ring * ring + s * s);
    }

    /**
     * σv клуба кольца: середина — на высоте σv, видимый верх слоя у начала — на высоте базисной волны
     * (σ0·(1 + край) = её высота, край — у толщи клуба в начале), дальше — расплывание по Бриггсу, насколько турбулентность
     * у глади держит капли ({@link #suspended}).
     */
    double mistSigmaV(double since) {
        double s = suspended() * CloudModel.spreadV(air.spread(), path(since)), s0 = surgeTop() / (1 + mistEdge0());
        return Math.sqrt(s0 * s0 + s * s);
    }

    /**
     * Насколько вихри у глади поднимают капли водяной пыли, 0..1: 1 − P/{@link #ROUSE_SUSPENDED} (P — число Рауза у ветра
     * мира над водой; ⚠ переход от взвеси к оседанию — линейная оценка между P = 0 и порогом). Без ветра — 0.
     */
    double suspended() {
        double ustar = Math.sqrt(DRAG_WATER) * Math.hypot(air.windX(), air.windZ());
        if (ustar <= 0) return 0;
        return Math.clamp(1 - mistSettling() / (KARMAN * ustar) / ROUSE_SUSPENDED, 0, 1);
    }

    /** Видимый край клуба кольца в начале падения, в σ (толща — у всей водяной пыли при σ кольца и высоте волны). */
    private double mistEdge0() {
        if (mistEdge0 < 0) {
            double ext = CloudModel.DROP_K * asGridDrops(MIST * water, MIST_DROP_R) / RING, sh = Math.PI * Math.max(radius, 1e-3) / RING;
            double sv = surgeTop() / 4;
            for (int i = 0; i < 8; i++) sv = surgeTop() / (1 + visible(ext / (2 * Math.PI * sh * sv)));
            mistEdge0 = surgeTop() / sv - 1;
        }
        return mistEdge0;
    }

    private double mistEdge0 = -1;

    private double path(double since) {
        return Math.max(1, Math.hypot(air.windX(), air.windZ()) * air.wind(surgeTop())) * Math.max(0, since);
    }

    /** Ballistic spray and wind-driven mist support, without building individual jets or puffs. */
    public void bounds(double t, SmokeBounds out, double x, double y, double z) {
        if (t < 0) return;
        // Ballistic endpoints are inside speed*t; use the same landing and mist laws as blobs.
        if (t < 2 * speed / G) {
            double r = speed * t + SmokeBounds.PUFF * jet;
            out.box(x - r, y - SmokeBounds.PUFF * jet, z - r, x + r, y + r, z + r);
        }
        double since = t - landing();
        if (since > 0 && t <= mistLife()) {
            double r = surgeRadius(since), sv = mistSigmaV(since);
            double pad = SmokeBounds.PUFF * Math.max(mistSigmaH(since, r), sv), lean = air.wind(surgeTop()) * since;
            double cx = x + air.windX() * lean, cz = z + air.windZ() * lean;
            out.box(cx - r - pad, y + sv - pad, cz - r - pad, cx + r + pad, y + sv + pad, cz + r + pad);
        }
    }

    /**
     * Клубы брызг в возрасте t от места взрыва на глади (центр от точки взрыва, м): струя каждого направления — отрезок
     * от самой медленной воды, что ещё в воздухе, до самой быстрой, круглого сечения σ {@link #jet}; упавшая вода — уже не в
     * воздухе. Водяная пыль — кольцом базисной волны, пока вода падает и после, у воды, сносится ветром. false — брызг
     * уже нет.
     */
    public boolean blobs(double t, SmokeGrid.Sink out, double x, double y, double z) {
        if (t < 0) return true;
        double each = asGridDrops(water) / directions(), drop = 0.5 * G * t * t;
        for (int k = 0; k < TILTS.length; k++) {
            double a = TILTS[k] * cone, lo = slowest(t, a);
            if (lo >= 1) continue;
            double share = (1 - lo) / (1 - SPEED_MIN), up = Math.cos(a), side = Math.sin(a);
            // середина отрезка и полуотрезок: место воды доли s — s·v·t вдоль струи и −gt²/2 по вертикали
            double mid = 0.5 * (1 + lo) * speed * t, half = 0.5 * (1 - lo) * speed * t;
            double lift = Math.max(0, 0.5 * (1 + lo) * speed * up - G * t);
            for (int i = 0; i < AROUND[k]; i++) {
                // струи по кругу: у каждой тройки углов свой поворот (не в линию), по зерну не надо — форма одна
                double az = 2 * Math.PI * (i + 0.5 * k) / AROUND[k], ox = side * Math.cos(az), oz = side * Math.sin(az);
                out.segment(x + mid * ox, y + mid * up - drop, z + mid * oz, half * ox, half * up, half * oz, jet, jet, 0, 0, each * share, 1, 1, 1,
                        CloudModel.T_AIR_K, lift);
            }
        }
        double since = t - landing();
        if (since > 0) {
            if (t > mistLife()) return false;
            // кольцо набирает воду, пока столб падает
            double mist = asGridDrops(MIST * water, MIST_DROP_R) * (1 - aloft(t)) * mistLeft(since) / RING;
            double sv = mistSigmaV(since), lean = air.wind(surgeTop()) * since;
            double r = ringCentre(since, CloudModel.DROP_K * mist, sv), sh = mistSigmaH(since, r);
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

    /** Струй в столбе (направлений по всем углам и кругу). */
    static int directions() {
        int n = 0;
        for (int k : AROUND) n += k;
        return n;
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
     * наружу: медленные струи — ближе и раньше) — с мига падения, у самой полости (купол, ½R) — сразу; за дальностью
     * середин струй вода струи ложится по её сечению (гаусс σ {@link #jet}); тает за {@link #FOAM_S} с.
     */
    public double foam(double r, double t) {
        double since, share = 1;
        if (r <= radius / 2) since = t;
        else {
            // когда завеса дошла сюда: струя, что падает на r, — доля скорости s с s² = r / (наибольшей дальности)
            double reach = speed * speed * Math.sin(2 * cone) / G;
            if (r > reach) {
                // за дальностью середин — вода сечения самых быстрых крайних струй
                double off = (r - reach) / Math.max(jet, 1e-9);
                if (off > halo) return 0;
                share = Math.exp(-0.5 * off * off);
            }
            double s = Math.sqrt(Math.min(r, reach) / Math.max(reach, 1e-9));
            since = t - flight(Math.max(s, SPEED_MIN), cone);
        }
        return since < 0 ? 0 : share * Math.exp(-since / FOAM_S);
    }

    /** Сколько живёт картина на воде (пена и волна), с. */
    public double waterLife() {
        return 2 * rise + 4 * FOAM_S;
    }
}
