package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.warhead.BuriedBlast;
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
 * Пыль воронки — грунт воронки ({@link CraterModel#chargeVolume}: диаметр 0,65·W^⅓ м, глубина — четверть, параболоид —
 * тот же закон, что роет сервер); в воздух уходит доля {@link #DUST_LOFTED} (оценка). Воронка заряда меньше его шара
 * продуктов: выброс проходит сквозь шар, и его мелкая пыль поднимается с облаком. Воронки нет у подрыва выше
 * {@link #CRATER_HOB} радиуса шара продуктов (разрыв в воздухе): пыли воронки нет.
 * <p>
 * <b>Пыль волны</b> — {@link GroundDust}: тот же закон, что у ядерки ({@code DustModel}: вынос ветром за фронтом, слой Kuhl,
 * корзины, подсос в ножку), с высотой подрыва над землёй {@link #hob} и ядерной мощностью того же давления у земли
 * ({@link #equivalentKt}); у разрыва в воздухе пыли нет — давление у земли ниже порога срыва. Чёрч — только размер и
 * высота стабилизированного облака ({@link #HE_HEAT}).
 * <p>
 * <b>Оптика.</b> Ослабление на массу — сажа {@link #SOOT_K} м²/кг при альбедо 0,25 и асимметрии 0,5 (Bond, Bergstrom 2006:
 * поглощение 7,5 м²/г на 550 нм), пыль — {@code DustModel.K_MIX} (геометрическая оптика зёрен ядерной пыли; что осела
 * крупная, ослабление идёт по доле мелкой — {@code DustModel.extinctionAloft}), капли конденсации (5 мкм) — 3Q/(2ρr) =
 * 600 м²/кг. Облако видно, пока его толща поперёк больше {@link #SEEN} (порог контраста Кошмидера).
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
     * Облако старта ({@link LaunchCloud}): доля пыли, сорванной струёй, что встаёт с облаком (остальное — у земли), —
     * оценка: замеров разделения пыли у стартов нет. У взрыва пыль у земли — слой волны ({@link GroundDust}).
     */
    public static final double CLOUD_SHARE = 0.5;
    /**
     * Доля энергии ядерного взрыва в ударной волне — ~0,5 (Glasstone, Dolan, «The Effects of Nuclear Weapons», 1977, §1.24:
     * около половины — волна, треть — свет, остальное — радиация); у ВВ тротиловый эквивалент — сама волна. Давление у
     * земли заряда W кг ТНТ — как у ядерного наземного W/0,5 кг: Броуд ({@code HobBlast}) против Кинни–Грэхема
     * ({@code BlastModel}) дают отношение мощностей 1,4–2,4 от 1 до 50 psi у 6–1000 кг (CloudModelTest).
     */
    public static final double NUCLEAR_BLAST_SHARE = 0.5;
    /**
     * Воронки нет у подрыва выше этой доли радиуса огненного шара — тот же признак, что у ядерного
     * ({@code CraterModel.formsCrater}: Glasstone и Dolan, 0,1 радиуса шара); у ВВ — по шару продуктов детонации
     * ({@link #heBall}). ⚠ Порога по опытам с ВВ в наших источниках нет: у Кинни и Грэхема (1985) лишь сказано, что заряд над
     * землёй может не дать воронки.
     */
    public static final double CRATER_HOB = 0.1;
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
    /** Высота подрыва над землёй под точкой взрыва, м (0 — удар в поверхность). */
    public final double hob;
    /** Пыль волны у земли и ножка. */
    public final GroundDust ground;
    /** Плавучесть всего тепла, м⁴/с²; F = 3B/(4π). */
    public final double buoyancy;
    /** Сажа и пыль воронки (в облаке), кг. */
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
        this(source, air, onWater, 0);
    }

    /**
     * Взрыв на высоте {@code hobM} над землёй (0 — удар в поверхность; разрыв зенитной ракеты — высота над рельефом):
     * воронка — только если заряд её роет ({@link #CRATER_HOB}), пыль волны — по давлению у земли ({@link GroundDust}).
     */
    public CloudModel(BlastSource source, Air air, boolean onWater, double hobM) {
        this(source, air, onWater, hobM, GroundDust.Ground.BARE);
    }

    /**
     * То же над поверхностью {@code ground} вокруг по секторам: голый рыхлый грунт, дёрн или твёрдое, вода ({@link GroundDust}).
     */
    public CloudModel(BlastSource source, Air air, boolean onWater, double hobM, GroundDust.Ground ground) {
        // тот же закон воронки, что роет сервер (грунт — сухой: пакет несёт только «вода или нет»)
        this(source, air, onWater || !digs(source.chargeKg(), hobM) ? 0
                : CraterModel.chargeVolume(source.chargeKg(), CraterModel.Soil.DRY) * SOIL_DENSITY * DUST_LOFTED, 0.5, onWater, hobM, ground);
    }

    /** Роет ли заряд {@code tntKg} кг на высоте {@code hobM} над землёй воронку ({@link #CRATER_HOB}). */
    public static boolean digs(double tntKg, double hobM) {
        return tntKg > 0 && hobM <= CRATER_HOB * 1.7 * Math.cbrt(tntKg);
    }

    /** Ядерная мощность, кт, с тем же давлением у земли, что у заряда {@code tntKg} кг ТНТ ({@link #NUCLEAR_BLAST_SHARE}). */
    public static double equivalentKt(double tntKg) {
        return Math.max(0, tntKg) * 1e-6 / NUCLEAR_BLAST_SHARE;
    }

    /**
     * Облако взрыва {@code source}, у которого пыль — не воронка его заряда: {@code dustKg} кг грунта в воздухе, начальный
     * размер облака — не меньше {@code size} м (пыль, поднятая не жаром, — облако такого размера у земли).
     */
    public CloudModel(BlastSource source, Air air, double dustKg, double size) {
        this(source, air, dustKg, size, false, 0, GroundDust.Ground.BARE);
    }

    private CloudModel(BlastSource source, Air air, double dustKg, double size, boolean onWater, double hobM, GroundDust.Ground surface) {
        this.source = source;
        this.air = air;
        this.hob = Math.max(0, hobM);
        double fuelHeat = source.fuelKg() * source.fuel().heatJPerKg * (1 - RADIANT);
        double heat = source.chargeKg() * HE_HEAT + fuelHeat;
        buoyancy = G * heat / (RHO * CP * T_AIR);
        dust = dustKg;
        soot = source.chargeKg() * source.explosive().freeCarbon() * SOOT_SURVIVES + source.fuelKg() * source.fuel().sootYield;
        fuelBall = FuelFireball.diameter(source.fuelKg());
        fuelBallSeconds = FuelFireball.seconds(source.fuelKg());
        heBall = 1.7 * Math.cbrt(source.chargeKg());
        heBallSeconds = 0.2 * Math.pow(source.chargeKg(), 0.35);
        burnSeconds = Math.max(fuelBallSeconds, heBallSeconds);
        // начальный объём: в нём тепло держит температуру шара (у ideal gas при том же давлении g' = g·(1 − T_a/T))
        double ballK = source.fuelKg() > 0 ? FUEL_BALL_K : HE_BALL_END;
        thermal = new Thermal(buoyancy, G * (1 - T_AIR / ballK), size, air);
        maxRise = thermal.maxRise;
        topZ = thermal.topZ;
        topR = thermal.topR;
        topSeconds = thermal.topSeconds;
        // остывание: шар в конец горения — термик того же тепла с его радиусом и температурой
        boolean fuelLast = source.fuelKg() > 0 && fuelBallSeconds >= heBallSeconds;
        burnRadius = fuelLast ? 0.5 * fuelBall : heBall;
        burnHeight = fuelLast ? fuelBallHeight(fuelBallSeconds) : 0.45 * heBall;
        cooling = new Thermal(buoyancy, G * (1 - T_AIR / (fuelLast ? FUEL_BALL_K : HE_BALL_END)), Math.max(burnRadius, size), air);
        fireSeconds = glowEnd();
        // на воде воронки в грунте нет: топливо растекается плёнкой, а плёнка тоньше ~1 мм не держит горение — вода
        // уносит тепло (Buist и др., «In situ burning», обзор для разливов нефти: гаснет у 1–2 мм; ⚠ по памяти)
        poolKg = onWater ? 0 : POOL_SHARE * source.fuelKg();
        poolRadius = CraterModel.chargeRadius(source.chargeKg(), CraterModel.Soil.DRY);
        double burn = THIN_LAYER * source.fuel().poolBurn * Math.PI * poolRadius * poolRadius;
        poolSeconds = poolKg > 0 && burn > 0 ? poolKg / burn : 0;
        poolWatts = poolSeconds > 0 ? burn * source.fuel().heatJPerKg : 0;
        // пыль волны: над водой грунта нет (брызги — WaterBlast)
        ground = new GroundDust(onWater ? 0 : source.chargeKg(), hob, heBall, surface, air);
        life = Math.max(lifeSeconds(), Math.min(LIFE_MAX, ground.life));
    }

    /**
     * Облако бомбы, прорвавшейся наружу ({@code source} — доля её заряда, что вышла в воздух): пыль — грунт воронки выброса
     * ({@link BuriedBlast#craterVolume}; грунта пакет не несёт — сухой, как у наземного), доля {@link #DUST_LOFTED} в воздухе.
     */
    public static CloudModel buried(BlastSource source, Air air, BuriedBlast law, boolean onWater) {
        return buried(source, air, law, onWater, GroundDust.Ground.BARE);
    }

    /** То же над поверхностью {@code ground} вокруг ({@link GroundDust}). */
    public static CloudModel buried(BlastSource source, Air air, BuriedBlast law, boolean onWater, GroundDust.Ground ground) {
        return new CloudModel(source, air, onWater ? 0 : law.craterVolume(CraterModel.Soil.DRY) * SOIL_DENSITY * DUST_LOFTED, 0.5, onWater, 0, ground);
    }

    /**
     * Пыль без жара ({@code source} — заряд 0: звук и место берёт событие): {@code volume} м³ грунта, сорванного ударом или
     * обвалом, доля {@link #DUST_LOFTED} — в воздухе, облако размером {@code size} м у земли расплывается и уходит с ветром.
     */
    public static CloudModel dust(BlastSource source, Air air, double volume, double size) {
        return new CloudModel(source, air, volume * SOIL_DENSITY * DUST_LOFTED, size);
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
        double ext = SOOT_K * soot + DustModel.K_MIX * capDust(t);
        return ext / (2 * Math.PI * sigmaH(t) * sigmaV(t));
    }

    /**
     * Пыль в облаке в возрасте t (ослабление в кг смеси {@code DustModel.K_MIX}): пыль воронки и сорванная волной под
     * огненным шаром, что не осела из облака (слой — высота его середины), и пыль ножки, что дошла до его низа.
     */
    double capDust(double t) {
        return (dust + ground.underBall(t)) * DustModel.extinctionAloft(t / Math.max(height(t), 1)) + ground.drawn(stemEntered(t));
    }

    /**
     * До какого возраста вошедшая в ножку пыль уже дошла до низа облака в возрасте t, с: воздух ножки идёт вверх долей
     * {@code MushroomModel.STEM_INFLOW} подъёма облака (как у ядерки), путь пыли, вошедшей в t', —
     * доля·(высота(t) − высота(t')); низ облака — середина − 1,5σ.
     */
    double stemEntered(double t) {
        double bottom = height(t) - 1.5 * sigmaV(t), k = ua.zentix.incoming.nuclear.model.MushroomModel.STEM_INFLOW;
        if (bottom <= 0 || k * (height(t) - height(0)) < bottom) return 0;
        double lo = 0, hi = t;
        for (int i = 0; i < 30; i++) {
            double mid = 0.5 * (lo + hi);
            if (k * (height(t) - height(mid)) >= bottom) lo = mid;
            else hi = mid;
        }
        return lo;
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
     * стадии), облако (термик: сажа, пыль воронки и пыль ножки, дошедшая до его низа), ножка — от земли до низа облака
     * (пыль, что подсос забрал из слоя волны и ещё не донёс до облака), слой пыли волны у земли ({@link GroundDust}).
     * Каждый клуб несёт массу — пыль как ослабление в кг смеси {@code DustModel.K_MIX}.
     */
    public void blobs(double t, Blobs out) {
        if (t < 0 || t >= life) return;
        double fade = fade(t);
        double cx = driftX(t), cz = driftZ(t), cy = height(t), sh = sigmaH(t), sv = sigmaV(t);
        double k = kelvin(t), up = rise(t);
        // ножка — шар на земле: пыль идёт по всей ножке до низа облака сразу (как у ядерки, StemDust); что дошло — в облаке
        double entered = stemEntered(t), stem = Math.max(0, ground.drawn(t) - ground.drawn(entered));
        // низ облака над землёй (у разрыва в воздухе земля — на hob ниже точки взрыва)
        double bottom = cy - 1.5 * sv + hob;
        boolean stemShown = bottom > 2 && stem > 0;
        double cloudDust = capDust(t) + (stemShown ? 0 : stem);
        // огненная стадия: шары светят своим радиусом и температурой — шар продуктов детонации (сажа взрывчатки), шар
        // топлива (сажа топлива); пыль облака — в большем
        double he = heBallRadius(t), fuel = fuelBallRadius(t);
        double heSoot = source.chargeKg() * source.explosive().freeCarbon() * SOOT_SURVIVES, fuelSoot = soot - heSoot;
        if (he > 0 || fuel > 0) {
            boolean heBig = he > fuel;
            if (he > 0) {
                double s = he / Math.sqrt(5);
                out.blob(cx, heBallHeight(t), cz, s, s, (fuel > 0 ? heSoot : soot) * fade, heBig ? cloudDust * fade : 0, 0,
                        Math.max(k, heBallKelvin(t)), up);
            }
            if (fuel > 0) {
                double s = fuel / Math.sqrt(5);
                out.blob(cx, fuelBallHeight(t), cz, s, s, (he > 0 ? fuelSoot : soot) * fade, heBig ? 0 : cloudDust * fade, 0,
                        Math.max(k, FUEL_BALL_K), up);
            }
        } else {
            out.blob(cx, cy, cz, sh, sv, soot * fade, cloudDust * fade, 0, k, up);
        }
        if (stemShown) {
            // клубы ножки — σ ножки (⚠ оценка: треть σ облака), клонится с ветром: снос на её высоте — доля сноса облака
            double ss = Math.max(0.35 * sh, 1);
            int count = (int) Math.min(8, Math.max(1, Math.round(bottom / (2 * ss))));
            double mass = stem * fade / count;
            for (int i = 0; i < count; i++) {
                double f = (i + 0.5) / count;
                double h = f * bottom;
                double lean = air.wind(h) / air.wind(cy + hob);
                out.blob(cx * lean, h - hob, cz * lean, ss, ss, 0, mass, 0, T_AIR + (k - T_AIR) * f * 0.5, up * f);
            }
        }
        // слой пыли волны у земли (на hob ниже точки взрыва)
        ground.blobs(t, fade, (x, y, z, gh, gv, d) -> out.blob(x, y - hob, z, gh, gv, 0, d, 0, T_AIR, 0));
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

    /** Толща самого густого клуба слоя пыли волны в возрасте t (через середину по горизонтали). */
    public double groundTauForTest(double t) {
        return ground.tau(t);
    }

    /** Радиус и жизнь облака конденсации — один раз (зовут каждый кадр); −1 — ещё не считались. */
    private double condensationR = -1, condensationLife = -1;
}
