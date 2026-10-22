package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.FuelFireball;

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
 * 3000–5000 K, обзор Ciccarelli и др. в Shock Waves 2021; к концу — оценка). Горение кончилось — шар не гаснет, а
 * остывает подсосом воздуха: тот же термик MTT ({@link #cooling}), начатый с шара в конец горения (его радиус и
 * температура), — температура газа T = T_a/(1 − g'/g) падает, пока шар растёт; светит, пока горячее точки Дрейпера
 * ({@value #DRAPER_K} K), яркость — чёрное тело этой температуры (по Планку: от 1500 до 1000 K — в тысячи раз). Излучение
 * шар остужает ещё быстрее (CCPS: 25–40 % тепла уходит светом) — здесь не считается, свечение кончается чуть позже
 * настоящего.
 * <p>
 * <b>Топливо в воронке.</b> Что не разлетелось брызгами в шар, остаётся в воронке, пропитав грунт, и догорает там:
 * доля {@link #POOL_SHARE}, площадь — круг воронки ({@link CraterModel#chargeRadius}), выгорание с единицы площади — у
 * большой лужи топлива ({@link BlastSource.Fuel#poolBurn}) × {@link #THIN_LAYER}: слой в миллиметры на грунте горит
 * медленнее глубокой лужи. Горит {@link #poolSeconds}, тепловыделение {@link #poolWatts}: у шахеда ~2,5 мин по ~2 МВт, у
 * ракеты ~16 мин по ~3 МВт. Над ним — шлейф Бриггса ({@link FirePlume}) с сажей топлива, пока горит; пламя — по
 * Хескестаду.
 * <p>
 * <b>Вещество.</b> Сажа взрывчатки — свободный углерод продуктов детонации ({@link BlastSource.Explosive#freeCarbon}), из
 * которого догорание в шаре оставляет долю {@link #SOOT_SURVIVES} (оценка); сажа топлива — выход {@link BlastSource.Fuel}.
 * Пыль — грунт воронки ({@link CraterModel#chargeVolume}: диаметр 0,65·W^⅓ м, глубина — четверть, параболоид — тот же
 * закон, что роет сервер); в воздух уходит доля {@link #DUST_LOFTED} (оценка), половина — в облако, половина —
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
    public static final double FUEL_BALL_K = Math.pow(FuelFireball.SURFACE_POWER / 5.670374e-8, 0.25);
    /** Шар взрывчатки: в начале и в конце жизни, K (оценка). */
    public static final double HE_BALL_HOT = 3000, HE_BALL_END = 1500;
    /** Сажа: ослабление на массу (до дельта-Эддингтона), м²/кг; альбедо и асимметрия. */
    public static final double SOOT_K = 7.5e3 / (1 - 0.25), SOOT_W = 0.25, SOOT_G = 0.5;
    /** Капли конденсации 5 мкм: 3·2/(2·1000·5·10⁻⁶), м²/кг; асимметрия. */
    public static final double DROP_K = 600, DROP_G = 0.85;
    /** Сколько свободного углерода детонации остаётся сажей после догорания в шаре — оценка. */
    public static final double SOOT_SURVIVES = 0.2;
    /** Плотность грунта, кг/м³ (воронка — {@link CraterModel#chargeVolume}). */
    public static final double SOIL_DENSITY = 1600;
    /** Доля грунта воронки, что уходит в воздух пылью мельче ~50 мкм, — оценка. */
    public static final double DUST_LOFTED = 0.02;
    /**
     * Доля пыли воронки, что встаёт с облаком (остальное — у земли, вал пыли от ветра за фронтом), и доля пыли облака в
     * ножке, пока облако поднимается (тает с его подъёмом), — оценки: замеров разделения пыли у малых зарядов нет.
     */
    public static final double CLOUD_SHARE = 0.5, STEM_SHARE = 0.3;
    /** Облако видно, пока его толща поперёк середины больше этого (порог контраста 0,02). */
    public static final double SEEN = 0.02;
    /** Точка Дрейпера: газ холоднее не светится, K. */
    public static final double DRAPER_K = 798;
    /**
     * Газ горячее этого светится в полную силу тела огня: яркость чёрного тела 1200 K — около сотой шара топлива
     * (по Вину на 555 нм), дальше остывающий шар — уже больше дым, чем огонь, K.
     */
    public static final double GLOW_FULL_K = 1200;
    /**
     * Доля топлива к удару, что остаётся в воронке и догорает там, а не разлетается брызгами в огненный шар: опыты Sandia с
     * крылом С-141 в масштабе ~1/42, бьющим в песок с глиной (Tieszen, Attaway, «Fuel dispersal in high-speed aircraft/soil
     * impact scenarios», SAND95-2529, 1996), — в воронке от почти нуля при ударе по касательной до 25–50 % при крутом.
     * Шахед бьёт под 60°, ракета под 45° — круто; взят нижний край: у опытов не было заряда, а взрыв БЧ рядом с баком
     * выбрасывает топливо из воронки (⚠ оценка внутри диапазона). Шар CCPS считается по всей массе (как у сервера, что
     * поджигает по нему), поэтому эта доля посчитана дважды: по массе без неё диаметр шара на 9 % меньше (M^⅓) — в пределах
     * разброса CCPS; тепло воронки в подъём облака не идёт.
     */
    public static final double POOL_SHARE = 0.25;
    /**
     * Выгорание топлива тонким слоем на грунте против глубокой лужи: у разлива толщиной в миллиметры наибольшая скорость
     * горения — порядка пятой доли глубокой (Mealy, Benfer, Gottuk, «Liquid fuel spill fire dynamics», Fire Safety Science
     * 10, 2011: тепло уходит в подложку); у топлива в пористом слое песка она тоже ниже, чем у лужи того же размера. В
     * воронке шахеда — ~2 мм бензина, ракеты — ~8 мм JP-10 в перемешанном грунте (⚠ одна доля на оба: зависимость от
     * толщины и грунта по их корреляциям не взята).
     */
    public static final double THIN_LAYER = 0.2;
    /** Дольше этого облако не живёт, с (дальше оно — дымка над районом). */
    public static final double LIFE_MAX = 3600;

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
        public static final Air STANDARD = of(ua.zentix.incoming.util.Air.CALM, 0.5);

        /**
         * Воздух в месте взрыва из воздуха мира ({@link ua.zentix.incoming.util.Air} — один на полёт, звук и дым): устойчивость
         * N, показатель профиля ветра и класс расплывания — от его класса устойчивости ({@link Stability}), ветер на 10 м
         * над землёй, влажность — его (в дождь насыщен) или 1 у воды.
         *
         * @param humidity влажность в месте взрыва 0..1
         */
        public static Air of(ua.zentix.incoming.util.Air air, double humidity) {
            double c = air.stability(), u = air.wind().at(10, Stability.alpha(c));
            return new Air(air.brunt(), u * air.wind().x(), u * air.wind().z(), Stability.alpha(c), (int) Math.round(c), humidity);
        }

        /** Ветер на высоте z (м) над землёй: доля ветра на 10 м. */
        public double wind(double z) {
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
    /** Конец горения шаров (топлива и продуктов детонации), с. */
    public final double burnSeconds;
    /** Конец свечения шара, с: остывший до точки Дрейпера; дальше облако — термик. */
    public final double fireSeconds;
    /** Остывание шара после горения: термик MTT от шара в конец горения ({@link #burnSeconds}). */
    public final Thermal cooling;
    /** Радиус шара в конец горения, м, и высота его центра тогда. */
    final double burnRadius, burnHeight;
    /** Топливо в воронке: масса, кг; радиус, м; сколько горит, с; тепловыделение, Вт (0 — топлива нет). */
    public final double poolKg, poolRadius, poolSeconds, poolWatts;
    /** Подъём облака ({@link Thermal}). */
    public final Thermal thermal;
    /** Наибольшая скорость подъёма, м/с. */
    final double maxRise;
    /** Наибольшая высота центра и время, когда она достигнута, с; радиус там. */
    public final double topZ, topSeconds, topR;
    /** Сколько облако видно, с. */
    public final double life;

    public CloudModel(BlastSource source, Air air) {
        this(source, air, false);
    }

    /** {@code onWater} — взрыв на воде: воронки в грунте нет, пыли нет (вода — брызгами, {@link WaterBlast}). */
    public CloudModel(BlastSource source, Air air, boolean onWater) {
        this.source = source;
        this.air = air;
        double fuelHeat = source.fuelKg() * source.fuel().heatJPerKg * (1 - RADIANT);
        double heat = source.chargeKg() * HE_HEAT + fuelHeat;
        buoyancy = G * heat / (RHO * CP * T_AIR);
        // тот же закон воронки, что роет сервер (грунт — сухой: пакет несёт только «вода или нет»)
        dust = onWater ? 0 : CraterModel.chargeVolume(source.chargeKg(), CraterModel.Soil.DRY) * SOIL_DENSITY * DUST_LOFTED;
        soot = source.chargeKg() * source.explosive().freeCarbon() * SOOT_SURVIVES + source.fuelKg() * source.fuel().sootYield;
        fuelBall = FuelFireball.diameter(source.fuelKg());
        fuelBallSeconds = FuelFireball.seconds(source.fuelKg());
        heBall = 1.7 * Math.cbrt(source.chargeKg());
        heBallSeconds = 0.2 * Math.pow(source.chargeKg(), 0.35);
        burnSeconds = Math.max(fuelBallSeconds, heBallSeconds);
        // начальный объём: в нём тепло держит температуру шара (у ideal gas при том же давлении g' = g·(1 − T_a/T))
        double ballK = source.fuelKg() > 0 ? FUEL_BALL_K : HE_BALL_END;
        thermal = new Thermal(buoyancy, G * (1 - T_AIR / ballK), 0.5, air);
        maxRise = thermal.maxRise;
        topZ = thermal.topZ;
        topR = thermal.topR;
        topSeconds = thermal.topSeconds;
        // остывание: шар в конец горения — термик того же тепла с его радиусом и температурой
        boolean fuelLast = source.fuelKg() > 0 && fuelBallSeconds >= heBallSeconds;
        burnRadius = fuelLast ? 0.5 * fuelBall : heBall;
        burnHeight = fuelLast ? fuelBallHeight(fuelBallSeconds) : 0.45 * heBall;
        cooling = new Thermal(buoyancy, G * (1 - T_AIR / (fuelLast ? FUEL_BALL_K : HE_BALL_END)), burnRadius, air);
        fireSeconds = glowEnd();
        // на воде воронки в грунте нет: топливо растекается плёнкой, а плёнка тоньше ~1 мм не держит горение — вода
        // уносит тепло (Buist и др., «In situ burning», обзор для разливов нефти: гаснет у 1–2 мм; ⚠ по памяти)
        poolKg = onWater ? 0 : POOL_SHARE * source.fuelKg();
        poolRadius = CraterModel.chargeRadius(source.chargeKg(), CraterModel.Soil.DRY);
        double burn = THIN_LAYER * source.fuel().poolBurn * Math.PI * poolRadius * poolRadius;
        poolSeconds = poolKg > 0 && burn > 0 ? poolKg / burn : 0;
        poolWatts = poolSeconds > 0 ? burn * source.fuel().heatJPerKg : 0;
        life = lifeSeconds();
    }

    /**
     * Доля газа температуры kelvin, что светится телом огня (0 — дым): от точки Дрейпера до {@link #GLOW_FULL_K} — плавно.
     * Тело огненного шара тает в дым и дым проступает из шара одной долей — без шва.
     */
    public static double glowShare(double kelvin) {
        double u = Math.max(0, Math.min(1, (kelvin - DRAPER_K) / (GLOW_FULL_K - DRAPER_K)));
        return u * u * (3 - 2 * u);
    }

    /** Температура остывающего шара в возрасте t ≥ {@link #burnSeconds}, K. */
    double coolKelvin(double t) {
        return cooling.kelvin(t - burnSeconds);
    }

    /** Когда остывающий шар холоднее точки Дрейпера, с (шаг — тик). */
    private double glowEnd() {
        double t = burnSeconds;
        while (t < burnSeconds + 120 && coolKelvin(t) > DRAPER_K) t += 0.05;
        return t;
    }

    /** Высота центра облака над точкой взрыва в возрасте t, м. */
    public double height(double t) {
        return thermal.height(t);
    }

    /** Радиус термика (верх-хат MTT) в возрасте t, м; дальше вершины — тот же. */
    public double radius(double t) {
        return thermal.radius(t);
    }

    /** Скорость подъёма, м/с. */
    public double rise(double t) {
        return thermal.rise(t);
    }

    /** Снос центра ветром за t, м (x и z). */
    public double driftX(double t) {
        return thermal.driftX(t);
    }

    public double driftZ(double t) {
        return thermal.driftZ(t);
    }

    /**
     * Температура газа облака в возрасте t, K: в огненной стадии — шар (шар взрывчатки остывает от {@link #HE_BALL_HOT}
     * к {@link #HE_BALL_END}, шар топлива держит {@link #FUEL_BALL_K}), потом — остывающий шар ({@link #cooling}), дальше —
     * термик: T = T_a / (1 − g'/g).
     */
    public double kelvin(double t) {
        double gas = thermal.kelvin(t);
        if (t < fuelBallSeconds) return Math.max(gas, FUEL_BALL_K);
        if (t < heBallSeconds) return Math.max(gas, heBallKelvin(t));
        if (t < fireSeconds) return Math.max(gas, coolKelvin(t));
        return gas;
    }

    /** Температура шара продуктов детонации в возрасте t, K. */
    public double heBallKelvin(double t) {
        double u = Math.min(1, t / Math.max(heBallSeconds, 1e-6));
        return HE_BALL_HOT + (HE_BALL_END - HE_BALL_HOT) * u;
    }

    /**
     * Радиус светящегося шара (большего из двух) в возрасте t, м: в горении — шары, потом — остывающий шар (растёт
     * подсосом); 0 — остыл до точки Дрейпера.
     */
    public double ballRadius(double t) {
        if (t >= burnSeconds) return t < fireSeconds ? cooling.radius(t - burnSeconds) : 0;
        return Math.max(fuelBallRadius(t), heBallRadius(t));
    }

    /**
     * Температура самого горячего светящегося шара, K: пока светят продукты детонации — их шар, потом — шар топлива; 0 —
     * шары погасли.
     */
    public double ballKelvin(double t) {
        if (t < heBallSeconds) return heBallKelvin(t);
        if (t < fuelBallSeconds) return FUEL_BALL_K;
        return t < fireSeconds ? coolKelvin(t) : 0;
    }

    /** Радиус шара продуктов детонации в возрасте t, м (0 — погас): за первые проценты жизни почти весь, как кадры шара. */
    public double heBallRadius(double t) {
        return t < heBallSeconds ? heBall * (0.3 + 0.7 * (1 - Math.exp(-t / (0.05 * heBallSeconds)))) : 0;
    }

    /** Радиус шара топлива в возрасте t, м (0 — погас): растёт к D/2 за первую пятую горения (оценка формы). */
    public double fuelBallRadius(double t) {
        return t < fuelBallSeconds ? 0.5 * fuelBall * Math.min(1, Math.sqrt(t / (0.2 * fuelBallSeconds))) : 0;
    }

    /** Свет одного тела для {@link #ballLight} (зовут с потока отрисовки каждый кадр — без новых массивов). */
    private final double[] light = new double[4];

    /**
     * Свет поверхности светящегося шара радиуса {@link #ballRadius} в возрасте t — в {@code out} (r, g, b, яркость Y; белая
     * стена в полдень — 1): шары продуктов детонации и топлива вместе, каждый — чёрное тело своей температуры с долей
     * площади (r/R)² (поток тот же, что у двух шаров).
     */
    public void ballLight(double t, double[] out) {
        java.util.Arrays.fill(out, 0, 4, 0);
        double big = ballRadius(t);
        if (big <= 0) return;
        if (t >= burnSeconds) {
            BlackBody.light(coolKelvin(t), out);
            return;
        }
        double[] c = light;
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
        if (t >= burnSeconds) return burnHeight + cooling.height(t - burnSeconds) - cooling.height(0);
        return fuelBallRadius(t) >= heBallRadius(t) ? fuelBallHeight(t) : heBallHeight(t);
    }

    /**
     * Лужа топлива в возрасте t: доля тепловыделения 0..1 — полное, пока горит, к концу (последняя пятая) гаснет, как
     * тонкий слой топлива (⚠ форма спада — оценка); 0 — лужи нет или выгорела.
     */
    public double poolShare(double t) {
        if (poolSeconds <= 0 || t < 0 || t >= poolSeconds) return 0;
        double u = (poolSeconds - t) / (0.2 * poolSeconds);
        return u >= 1 ? 1 : u * u * (3 - 2 * u);
    }

    /** Высота пламени лужи по Хескестаду, м: L = 0,235·Q^⅖ − 1,02·D (Q — кВт), не меньше полуметра. */
    public double poolFlame() {
        return poolWatts <= 0 ? 0 : Math.max(0.5, 0.235 * Math.pow(poolWatts / 1e3, 0.4) - 1.02 * 2 * poolRadius);
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
        return thermal.sigmaH(t);
    }

    public double sigmaV(double t) {
        return thermal.sigmaV(t);
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
        double heSoot = source.chargeKg() * source.explosive().freeCarbon() * SOOT_SURVIVES, fuelSoot = soot - heSoot;
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
        double church = 4.7 * Math.pow(source.chargeKg(), 0.375) / Math.sqrt(5);
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
        if (condensationR < 0) {
            condensationR = ua.zentix.incoming.nuclear.model.CloudModel.wilsonCloud(air.humidity())
                    ? ua.zentix.incoming.nuclear.model.BlastModel.rangeForSurfaceOverpressure(20, source.chargeKg()) : 0;
        }
        return condensationR;
    }

    /** Сколько живёт облако конденсации, с: 10 положительных фаз волны на его радиусе (оценка). */
    public double condensationSeconds() {
        if (condensationLife < 0) {
            double r = condensation();
            condensationLife = r <= 0 ? 0 : 10 * ua.zentix.incoming.nuclear.model.BlastModel.positivePhaseSeconds(r, source.chargeKg() * 1e-6);
        }
        return condensationLife;
    }

    /** Радиус и жизнь облака конденсации — один раз (зовут каждый кадр); −1 — ещё не считались. */
    private double condensationR = -1, condensationLife = -1;
}
