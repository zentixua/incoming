package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.warhead.BuriedBlast;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.FuelFireball;
import ua.zentix.incoming.nuclear.model.FuelPool;

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
 * (8F/(α³N²))^¼ при F = R³g' (B = 4π/3·F), за π/N секунд; радиус — α·высоты. Дальше облако стоит на этой высоте. С первой
 * секунды его расплывает турбулентность воздуха (Паскуилл–Гиффорд, формулы Бриггса для открытой местности, по пути ветра
 * x = u·t, в квадратуре с радиусом термика), а ветер растёт с высотой: облако — стопка слоёв ({@link #LAYERS}), каждый со
 * своим ветром, — наклоняется и вытягивается по ветру. Высоту и радиус в первые секунды задаёт огненный шар: начальный объём — тот, в котором
 * тепло Q держит температуру шара ({@link #FUEL_BALL_K} или конец шара взрывчатки).
 * <p>
 * <b>Огненные шары.</b> Шар топлива — CCPS (1994), «Guidelines for Evaluating the Characteristics of Vapor Cloud
 * Explosions, Flash Fires and BLEVEs»: D = 5,8·M^⅓ м, горит 0,45·M^⅓ с (M &lt; 30 т; M — топливо шара без доли воронки,
 * {@link #POOL_SHARE}), центр поднимается к 0,75·D; излучает
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
 * доля {@link #POOL_SHARE}, закон — общий с сервером {@link FuelPool} (площадь — круг воронки, выгорание тонкого слоя): у
 * шахеда ~2,5 мин по ~2 МВт, у ракеты ~16 мин по ~3 МВт. Где оно горит, решает сервер: огонь Minecraft на блоках, куда
 * топливо легло и стекло ({@code warhead/FuelFires}); над ним — шлейф Бриггса ({@link FirePlume}) с сажей топлива, пока
 * горит ({@code FireSmoke.pool}).
 * <p>
 * <b>Вещество.</b> Сажа взрывчатки — свободный углерод продуктов детонации ({@link BlastSource.Explosive#freeCarbon}), из
 * которого догорание в шаре оставляет долю {@link #SOOT_SURVIVES} (оценка); сажа топлива — выход {@link FuelPool.Fuel}.
 * Пыль воронки — разрушенное у цели ({@link BlastTarget}: чаша {@link CraterModel#chargeVolume} по грунту материала —
 * диаметр 0,65·W^⅓ м, глубина — четверть, параболоид, тот же закон, что роет сервер, у скалы и бетона — 0,7 диаметра; у
 * плиты над пустотой — пролом; × насыпная плотность вещества цели); в воздух уходит доля мелочи вещества
 * ({@link BlastTarget.Matter#fines}): у рыхлого и досок — {@link #DUST_LOFTED} (2 %, оценка), у связного (скала, бетон,
 * кладка) — {@code BlastTarget.FINES_CRUSHED} (по PM10 контактного взрыва по бетону, Costa 2025; ⚠ оценка).
 * Цвет — вещества цели ({@link BlastTarget.Matter}), а не грунта у любой цели. Мелочь — два потока: вовлечённая в горячий
 * газ поднимается с облаком; остальную поток газа выносит вдоль поверхностей вокруг юбкой у земли ({@link #crushed},
 * {@link GroundDust#skirt}). Деление — {@link #CRUSHED_HOT_SHARE}. Воронки нет у подрыва выше
 * {@link #CRATER_HOB} радиуса шара продуктов (разрыв в воздухе): пыли воронки нет.
 * <p>
 * <b>Пыль волны</b> — {@link GroundDust}: тот же закон, что у ядерки ({@code DustModel}: вынос ветром за фронтом, слой Kuhl,
 * корзины, подсос в ножку), с поверхностей вокруг — у каждой своя высота взрыва над ней и свой материал, — и ядерной
 * мощностью того же давления у поверхности ({@link #equivalentKt}); слой тяжелее воздуха — стекает и опускается к
 * поверхности под собой; у разрыва в воздухе пыли нет — давление у земли ниже порога срыва. Чёрч — только размер и
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
    /** Доля рыхлого грунта воронки, что уходит в воздух пылью мельче ~50 мкм, — оценка (у связного — {@code BlastTarget.Matter#fines}). */
    public static final double DUST_LOFTED = 0.02;
    /**
     * Облако старта ({@link LaunchCloud}): доля пыли, сорванной струёй, что встаёт с облаком (остальное — у земли), —
     * оценка: замеров разделения пыли у стартов нет. У взрыва пыль у земли — слой волны ({@link GroundDust}).
     */
    public static final double CLOUD_SHARE = 0.5;
    /**
     * Доля мелочи разрушенного, что уходит с горячим газом облака; остальная — юбкой у земли ({@link GroundDust#skirt}).
     * Brown и др., «SCM», ARI-RR-1384 (2004), табл. 5: η_partition 0,2–0,8, принято 0,5 (⚠ оценка: там же
     * помечено неопределённым по нехватке данных).
     */
    public static final double CRUSHED_HOT_SHARE = 0.5;
    /** Юбка пыли разрушенного у земли ({@link GroundDust#skirt}): отдельно от пыли, что лежала на поверхностях. */
    public final GroundDust crushed;
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
     * Доля топлива к удару, что остаётся в воронке и догорает там (общая с сервером, {@link FuelFireball#POOL_SHARE}): шар
     * горит остальным ({@link FuelFireball#ballKg}) — его размер, тепло подъёма облака и сажа облака; тепло и сажа воронки
     * уходят шлейфом пожара ({@code FireSmoke.pool}).
     */
    public static final double POOL_SHARE = FuelFireball.POOL_SHARE;
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

        /**
         * Кусок трубки пыли (ножка облака) между концами a и b от точки взрыва: σ сечения, пыли на метр оси у концов, кг/м.
         * Приёмник без трубок получает гауссы того же сечения через σ вдоль оси (вещество — то же).
         */
        /**
         * Клуб пыли у земли, что волна сорвала с поверхностей вокруг ({@link GroundDust}): вещество поверхностей, а не цели.
         * Приёмник без различия — обычный клуб без сажи и подъёма.
         */
        default void ground(double x, double y, double z, double sh, double sv, double dust, double kelvin) {
            blob(x, y, z, sh, sv, 0, dust, 0, kelvin, 0);
        }

        /**
         * Клуб юбки пыли разрушенного у земли ({@link #crushed}): цвет и кадры — вещества цели. Приёмник без различия —
         * обычный клуб без сажи и подъёма.
         */
        default void crushed(double x, double y, double z, double sh, double sv, double dust, double kelvin) {
            blob(x, y, z, sh, sv, 0, dust, 0, kelvin, 0);
        }

        default void tube(double ax, double ay, double az, double sa, double dustA, double bx, double by, double bz, double sb, double dustB,
                          double kelvin, double rise) {
            double tx = bx - ax, ty = by - ay, tz = bz - az, len = Math.sqrt(tx * tx + ty * ty + tz * tz);
            int n = (int) Math.max(1, Math.ceil(len / Math.max(Math.min(sa, sb), 1e-3)));
            for (int i = 0; i < n; i++) {
                double f = (i + 0.5) / n, s = sa + (sb - sa) * f;
                blob(ax + tx * f, ay + ty * f, az + tz * f, s, s, 0, (dustA + (dustB - dustA) * f) * len / n, 0, kelvin, rise);
            }
        }
    }

    public final BlastSource source;
    public final Air air;
    /** Во что попал заряд: семейство и вещество ({@link BlastTarget}). */
    public final BlastTarget target;
    /** Высота подрыва над землёй под точкой взрыва, м (0 — удар в поверхность). */
    public final double hob;
    /** Кусков трубки ножки (изгиб по сдвигу ветра с высотой). */
    static final int STEM_PIECES = 6;
    /**
     * Облако после огненной стадии — стопка слоёв по высоте: гаусс σv делится на {@value #LAYERS} гаусса σ = {@link #SLICE}·σv
     * на высотах {@link #LAYER_Q}·σv (тот же второй момент — σv), и каждый слой несёт ветер своей
     * высоты ({@link #shear}): верх уходит вперёд низа, облако наклоняется и вытягивается по ветру (Saffman, QJRMS 88,
     * 1962: дисперсия облака в сдвиге ветра; так же — пуфы SCIPUFF и CALPUFF). Прежде облако было одним гауссом и
     * сносилось целиком (жалоба 23.10: «как одно твёрдое тело»). ⚠ Вихрь термика, пока он всплывает, сдвиг частью
     * держит — здесь нет: облако наклоняется чуть раньше настоящего.
     */
    static final int LAYERS = 3;
    static final double SLICE = 0.85;
    /** Порядок слоёв в выводе: середина (самый тяжёлый, он же клуб шара в огненной стадии) — первым. */
    private static final int[] LAYER_ORDER = {1, 0, 2};
    /**
     * Высоты слоёв (в σv облака) и их доли массы: квадратура Гаусса–Эрмита на три точки для середин с дисперсией
     * 1 − {@link #SLICE}² — 0 и ±√(3·(1 − SLICE²)), доли 2/3 и 1/6: вместе с шириной слоёв — тот же второй и четвёртый
     * момент, что у одного гаусса σv; профиль по высоте без провалов (слой у края виден краем середины).
     */
    static final double[] LAYER_Q = {-Math.sqrt(3 * (1 - SLICE * SLICE)), 0, Math.sqrt(3 * (1 - SLICE * SLICE))},
            LAYER_W = {1 / 6.0, 2 / 3.0, 1 / 6.0};
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
    /**
     * Топливо в воронке ({@link FuelPool}; где оно горит, решает сервер — огонь на блоках, {@code warhead/FuelFires}):
     * закон, масса, кг; радиус, м; сколько горит, с; тепловыделение, Вт (0 — топлива нет).
     */
    public final FuelPool pool;
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
        // без сведений о цели — голый рыхлый грунт (или вода)
        this(source, air, BlastTarget.of(onWater ? ua.zentix.incoming.warhead.GroundMaterial.WATER : ua.zentix.incoming.warhead.GroundMaterial.DIRT,
                source.chargeKg(), hobM), hobM, ground);
    }

    /**
     * Взрыв в цель {@code target} на высоте {@code hobM} над поверхностью {@code ground}: пыль раздробленного — доля мелочи
     * вещества ({@link BlastTarget.Matter#fines}: у рыхлого — {@link #DUST_LOFTED}, у дроблёного камня и бетона — {@link BlastTarget#FINES_CRUSHED}, меньше) массы разрушенного
     * ({@link BlastTarget#mass}: воронка закона по грунту материала × его насыпная плотность, у плиты — пролом; тот же закон,
     * что роет сервер), а не чаша сухого грунта у любой цели (у бетона, досок и плиты прежде выходило 1600 кг/м³ сухой
     * почвы, physics.md фронта F).
     */
    public CloudModel(BlastSource source, Air air, BlastTarget target, double hobM, GroundDust.Ground ground) {
        this(source, air, target, target.dust(source.chargeKg()), 0.5, hobM, ground);
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
        this(source, air, BlastTarget.SOIL, dustKg, size, 0, GroundDust.Ground.BARE);
    }

    private CloudModel(BlastSource source, Air air, BlastTarget target, double dustKg, double size, double hobM, GroundDust.Ground surface) {
        boolean onWater = target.family() == BlastTarget.Family.WATER;
        this.source = source;
        this.air = air;
        this.target = target;
        this.hob = Math.max(0, hobM);
        // шаром горит топливо без доли воронки: её тепло и сажа — у шлейфа пожара над воронкой
        double ballKg = FuelFireball.ballKg(source.fuelKg());
        double fuelHeat = ballKg * source.fuel().heatJPerKg * (1 - RADIANT);
        double heat = source.chargeKg() * HE_HEAT + fuelHeat;
        buoyancy = G * heat / (RHO * CP * T_AIR);
        // контакт — по цели (у опоры пакета его нашёл сервер; без неё — та же проверка digs по высоте), а не снова по высоте:
        // грубая высота вдали иначе снимала бы холодную пыль у пролома, подтверждённого сервером (ревью 2 фронта F)
        boolean contact = !source.underground() && source.chargeKg() > 0 && target.family() != BlastTarget.Family.AIR;
        double cold = contact ? dustKg * (1 - CRUSHED_HOT_SHARE) : 0;
        dust = dustKg - cold;
        soot = source.chargeKg() * source.explosive().freeCarbon() * SOOT_SURVIVES + ballKg * source.fuel().sootYield;
        fuelBall = FuelFireball.diameter(ballKg);
        fuelBallSeconds = FuelFireball.seconds(ballKg);
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
        // та же середина, что у горящего шара в последний миг горения ({@link #ballHeight}): у разрыва в воздухе прежде
        // 0,45 радиуса над точкой взрыва против середины в ней же — скачок в конец горения
        burnHeight = onGround(burnRadius) + lift(burnSeconds);
        cooling = new Thermal(buoyancy, G * (1 - T_AIR / (fuelLast ? FUEL_BALL_K : HE_BALL_END)), Math.max(burnRadius, size), air);
        fireSeconds = glowEnd();
        // на воде воронки в грунте нет: топливо растекается плёнкой, а плёнка тоньше ~1 мм не держит горение — вода
        // уносит тепло (Buist и др., «In situ burning», обзор для разливов нефти: гаснет у 1–2 мм; ⚠ по памяти)
        pool = onWater ? FuelPool.NONE : FuelPool.of(source.fuel(), source.fuelKg(), source.chargeKg());
        poolKg = pool.kg();
        poolRadius = pool.radius();
        poolSeconds = pool.seconds();
        poolWatts = pool.watts();
        // пыль волны: над водой грунта нет (брызги — WaterBlast)
        ground = new GroundDust(onWater ? 0 : source.chargeKg(), hob, heBall, surface, air);
        // юбка — по поверхностям вокруг (высоты — от точки взрыва, как у пыли волны); у грани, которую нашёл сервер, — от
        // точки взрыва, а не от грубой высоты земли вдали (там, где высота поверхностей неизвестна, — ровно у грани)
        crushed = GroundDust.skirt(onWater ? 0 : source.chargeKg(), target.atFace() ? 0 : hob, cold, surface, air);
        life = Math.max(lifeSeconds(), Math.min(LIFE_MAX, Math.max(ground.life, crushed.life)));
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
        return new CloudModel(source, air, onWater ? BlastTarget.of(ua.zentix.incoming.warhead.GroundMaterial.WATER, source.chargeKg(), 0) : BlastTarget.SOIL,
                onWater ? 0 : law.craterVolume(CraterModel.Soil.DRY) * SOIL_DENSITY * DUST_LOFTED, 0.5, 0, ground);
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

    /**
     * Свет густого дыма облака на экране в полдень, белая стена — 1: сажа отражает R∞ ~0,03, пыль ~0,3 (два потока,
     * {@code SmokePuffs.thick}); ⚠ оценка — их среднее геометрическое 0,1. Против него видно свечение остывающего шара.
     */
    public static final double SMOKE_SEEN = 0.1;

    /**
     * Доля газа температуры kelvin, что видна телом огня, когда дым освещает небо силы sky (белая стена в полдень — 1;
     * днём 1, ночью ~8·10⁻⁴ — {@code FarView.skyLuminance}): в пикселе свечение газа B(T) и свет неба, рассеянный его
     * дымом ({@link #SMOKE_SEEN}·sky), складываются. Огонь не виден, пока его свет ниже порога контраста ({@link #SEEN},
     * 0,02 — Blackwell 1946) к свету дыма, и дым не виден, когда его свет ниже того же порога к свету огня; между — плавно
     * по логарифму их отношения (при равных — половина), и не больше {@link #glowShare(double)} (светится ли газ вообще).
     * Ровно 0 и 1 на краях — не 1 − ε: клуб шара бомбы несёт тонны пыли, и даже 0,04 % её непрозрачны (тёмный ком поверх
     * шара днём в первую секунду, кадры F6). Привыкание глаза не входит: на экране оба умножаются на одно и то же (с
     * привыканием к шару у глаза холодный газ рядом выходил бы ночью чёрным комом). Днём шар бомбы в 1260 K (3 с после
     * взрыва 1,3 т, яркость ~0,009 белой стены) — уже дым, ночью — ещё огонь; по одной температуре он днём оставался сплошь
     * оранжевым до 3,7 с (шлагбаум RV2, бомба в 150 м). ⚠ Свет огня на самом дыме не входит.
     */
    public static double glowShare(double kelvin, double sky) {
        double g = glowShare(kelvin);
        if (g <= 0) return 0;
        double b = glowLuminance(kelvin), smoke = SMOKE_SEEN * Math.max(sky, 0);
        if (smoke <= 0) return g;
        double edge = -Math.log(SEEN), u = Math.max(0, Math.min(1, (Math.log(b / smoke) + edge) / (2 * edge)));
        return g * u * u * (3 - 2 * u);
    }

    /**
     * Яркость чёрного тела ({@link BlackBody#luminance}) по таблице логарифмов с шагом 10 K от точки Дрейпера (холоднее —
     * 0): доля огня считается на каждый клуб кадра, а по Вину яркость между узлами — экспонента.
     */
    static double glowLuminance(double kelvin) {
        if (kelvin <= DRAPER_K) return 0;
        double i = Math.min(GLOW_LOG.length - 1.001, (kelvin - DRAPER_K) / 10);
        int k = (int) i;
        return Math.exp(GLOW_LOG[k] + (GLOW_LOG[k + 1] - GLOW_LOG[k]) * (i - k));
    }

    private static final double[] GLOW_LOG = new double[421];

    static {
        for (int i = 0; i < GLOW_LOG.length; i++) GLOW_LOG[i] = Math.log(BlackBody.luminance(DRAPER_K + 10.0 * i));
    }

    /**
     * Доля сажи облака в возрасте t, что уже в облаке термика, а не в остывающем шаре: 0 в конец горения, 1 — когда шар
     * остыл до точки Дрейпера ({@link #fireSeconds}), между — гладко (у шахеда ~0,8 с, у ракеты ~1,2 с, у бомбы ~1,4 с).
     * ⚠ Сшивка двух приближений: термик MTT облака ведётся с мига взрыва, остывающий шар — с конца горения, и середины их
     * к этому мигу расходятся (у бомбы — на ~17 м); где сажа на самом деле, пока шар светится, — в нём.
     */
    double handoff(double t) {
        if (t <= burnSeconds) return 0;
        if (t >= fireSeconds || fireSeconds <= burnSeconds) return 1;
        double u = (t - burnSeconds) / (fireSeconds - burnSeconds);
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

    /** Шаг таблицы сдвига, с. */
    private static final double SHEAR_DT = 0.5;
    /** Сдвиг слоя на σv выше середины против середины по ветру, м, по шагам {@link #SHEAR_DT} (считается при первом вызове). */
    private double[] shearTable;

    /**
     * На сколько слой на σv выше середины облака ушёл по ветру вперёд середины к возрасту t, м: ∫ ½·(u(z + σv) − u(z − σv)) dt,
     * u — ветер на высоте над землёй (степенной профиль класса устойчивости, {@link Air#wind}), z — середина облака
     * (над землёй — с высотой подрыва). Слой на q·σv — на q·сдвиг.
     */
    public double shear(double t) {
        if (t <= 0) return 0;
        double[] a = shearTable;
        if (a == null) {
            int n = (int) Math.ceil(Math.min(life, LIFE_MAX) / SHEAR_DT) + 2;
            a = new double[n];
            double w = Math.hypot(air.windX(), air.windZ());
            for (int i = 1; i < n; i++) {
                double tm = (i - 0.5) * SHEAR_DT, z = height(tm) + hob, v = sigmaV(tm);
                a[i] = a[i - 1] + 0.5 * w * (air.wind(z + v) - air.wind(Math.max(z - v, 1))) * SHEAR_DT;
            }
            shearTable = a;
        }
        double f = t / SHEAR_DT;
        int i = (int) Math.min(a.length - 2, Math.floor(f));
        return a[i] + (a[i + 1] - a[i]) * Math.min(1, f - i);
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
        // остывающий шар — термик с объёма, что держит тепло при температуре шара (у шахеда с теплом боевой части он на
        // 4 % больше шара CCPS): от радиуса шара в конец горения к нему — за остывание, без скачка размера ({@link #handoff})
        if (t >= burnSeconds) return t < fireSeconds ? cooling.radius(t - burnSeconds) + (burnRadius - cooling.radius(0)) * (1 - handoff(t)) : 0;
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

    /**
     * Свет, что шар в возрасте t отдаёт наружу при свете неба sky ({@link #glowShare(double, double)}), — в {@code out},
     * как {@link #ballLight}: чёрное тело той доли шара, что ещё огонь, а не дым. Один выход источника для всех, кто его
     * берёт (тело на экране, свет на дым, на мир, на облака): потемневший шар не светит на землю и небо ярче своего пламени.
     */
    public void shownLight(double t, double sky, double[] out) {
        ballLight(t, out);
        double g = glowShare(ballKelvin(t), sky);
        for (int i = 0; i < 4; i++) out[i] *= g;
    }

    /**
     * Высота центра шара топлива, м: растёт от места удара, лёжа на земле своим нынешним радиусом ({@link #onGround}), и
     * всплывает к 0,75·D к концу горения (CCPS) — {@link #lift}. Прежде середина стояла на D/2 с первого мига, когда шар
     * ещё в метр: огонь рождался в воздухе над воронкой, и в 0,1–0,2 с середина видимого шара прыгала на 6 м у шахеда и на
     * 14 м у ракеты (BLASTSEQ D01).
     */
    public double fuelBallHeight(double t) {
        double r = fuelBallRadius(t);
        return r <= 0 ? 0 : onGround(r) + lift(t);
    }

    /** Высота центра шара продуктов детонации над точкой взрыва, м: на земле своим радиусом ({@link #onGround}), всплывая с шаром топлива. */
    public double heBallHeight(double t) {
        double r = heBallRadius(t);
        return r <= 0 ? 0 : onGround(r) + lift(t);
    }

    /**
     * Середина шара радиуса r, растущего от точки взрыва, над ней, м: у наземного — полусфера (центр видимой части — на 0,45
     * радиуса над землёй), у разрыва в воздухе шар свободный — центр в точке взрыва; между — по доле радиуса, что уходит в
     * землю (⚠ оценка формы: шар, срезанный землёй, к полусфере — линейно по высоте подрыва; physics.md фронта F: посадка
     * полусферой у разрыва в воздухе не обоснована).
     */
    double onGround(double r) {
        return r <= 0 ? 0 : 0.45 * r * Math.clamp(1 - hob / r, 0, 1);
    }

    /**
     * Всплытие горящего шара топлива к возрасту t, м: от земли к середине 0,75·D в конец горения (CCPS) — ровно за горение
     * (⚠ оценка формы: CCPS даёт только конечную высоту). Шар продуктов детонации внутри шара топлива всплывает с ним; без
     * топлива — 0: подъём продуктов — остывающий шар ({@link #cooling}).
     */
    double lift(double t) {
        if (fuelBall <= 0) return 0;
        return (0.75 * fuelBall - onGround(0.5 * fuelBall)) * Math.min(1, t / Math.max(fuelBallSeconds, 1e-6));
    }

    /**
     * Высота центра светящегося тела, м: пока горят шары — середина большего ({@link #onGround} его радиуса и общее
     * всплытие: у меньшего тот же закон, поэтому при смене большего середина не прыгает), остывая — термик остывающего
     * шара. Её же берут картинка шара, его свет на дым и мир, жар и дым шара ({@link #blobs}).
     */
    public double ballHeight(double t) {
        if (t >= burnSeconds) return burnHeight + cooling.height(t - burnSeconds) - cooling.height(0);
        return onGround(Math.max(fuelBallRadius(t), heBallRadius(t))) + lift(t);
    }

    /**
     * Снос светящегося тела ветром к возрасту t, м (x и z): тот же, что у его дыма ({@link #driftX}) — картинка шара, его
     * свет и жар идут вместе с клубами, в которые он остывает. Прежде шар стоял над местом удара, а дым уходил по ветру: у
     * ракеты в конец свечения при ветре 5 м/с — на 21 м, больше радиуса шара (BLASTSEQ D01).
     */
    public double ballX(double t) {
        return driftX(t);
    }

    public double ballZ(double t) {
        return driftZ(t);
    }

    /**
     * Топливо в воронке в возрасте t: доля тепловыделения 0..1 ({@link FuelPool#share}); 0 — топлива нет или выгорело.
     */
    public double poolShare(double t) {
        return pool.share(t);
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
     * Доля пыли облака в возрасте t, что — раздробленное вещество цели (остальное — пыль поверхностей вокруг: из-под шара и
     * из ножки): по ослаблению, как в {@link #capDust}. Цвет пыли облака — смесь их цветов этой долей.
     */
    public double capMatterShare(double t) {
        double all = capDust(t);
        return all > 0 ? dust * DustModel.extinctionAloft(t / Math.max(height(t), 1)) / all : 1;
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

    /**
     * Докуда над слоем у земли поднялась пыль ножки к возрасту t, м: самая ранняя её порция (подсос начался в
     * {@code GroundDust.drawnFrom}) идёт вверх долей {@code MushroomModel.STEM_INFLOW} подъёма облака с того мига — тот же
     * путь, что у {@link #stemEntered}. Прежде ножка стояла во всю высоту до облака с первого мига подсоса (BLASTSEQ D09).
     */
    double stemTop(double t) {
        double from = ground.drawnFrom;
        if (!(t > from)) return 0;
        return ua.zentix.incoming.nuclear.model.MushroomModel.STEM_INFLOW * Math.max(0, height(t) - height(from));
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
     * стадии), облако (термик: сажа, пыль воронки и пыль ножки, дошедшая до его низа), ножка — от земли вверх, докуда
     * дошла её пыль, не выше низа облака (пыль, что подсос забрал из слоя волны и ещё не донёс до облака), слой пыли волны у
     * земли ({@link GroundDust}).
     * Каждый клуб несёт массу — пыль как ослабление в кг смеси {@code DustModel.K_MIX}.
     */
    public void blobs(double t, Blobs out) {
        if (t < 0 || t >= life) return;
        double fade = fade(t);
        double cx = driftX(t), cz = driftZ(t), cy = height(t), sh = sigmaH(t), sv = sigmaV(t);
        double k = kelvin(t), up = rise(t);
        // ножка: пыль, что подсос забрал из слоя у земли и ещё не донёс до облака (что дошло — в облаке); снизу вверх — не
        // выше, чем дошла самая ранняя её порция ({@link #stemTop})
        double entered = stemEntered(t), stem = Math.max(0, ground.drawn(t) - ground.drawn(entered));
        // низ облака над землёй (у разрыва в воздухе земля — на hob ниже точки взрыва)
        double bottom = cy - 1.5 * sv + hob, ss = Math.max(0.35 * sh, 1), h0 = Math.min(ss, 0.5 * bottom), top = Math.min(bottom, h0 + stemTop(t));
        boolean stemShown = bottom > 2 && stem > 0 && top > h0;
        double cloudDust = capDust(t) + (stemShown ? 0 : stem);
        // огненная стадия: шары светят своим радиусом и температурой — шар продуктов детонации (сажа взрывчатки — вся с
        // мига детонации), шар топлива (сажа топлива — сколько его уже сгорело, {@link #sootBurnt}); пыль облака — в большем
        double he = heBallRadius(t), fuel = fuelBallRadius(t);
        double heSoot = heSoot(), fuelSoot = sootBurnt(t) - heSoot;
        if (he > 0 || fuel > 0) {
            boolean heBig = he > fuel;
            if (he > 0) {
                double s = he / Math.sqrt(5);
                out.blob(cx, heBallHeight(t), cz, s, s, (fuel > 0 ? heSoot : heSoot + fuelSoot) * fade, heBig ? cloudDust * fade : 0, 0,
                        Math.max(k, heBallKelvin(t)), up);
            }
            if (fuel > 0) {
                double s = fuel / Math.sqrt(5);
                out.blob(cx, fuelBallHeight(t), cz, s, s, (he > 0 ? fuelSoot : heSoot + fuelSoot) * fade, heBig ? 0 : cloudDust * fade, 0,
                        Math.max(k, FUEL_BALL_K), up);
            }
        } else {
            // слои по высоте: каждый — с ветром своей высоты (сдвиг относительно середины — вдоль ветра)
            double lean = shear(t), wx = air.windX(), wz = air.windZ(), w = Math.hypot(wx, wz);
            double ux = w > 0 ? wx / w : 0, uz = w > 0 ? wz / w : 0;
            // Сажа остывающего шара — ещё в шаре (его середина и размер), облако термика принимает её за время остывания:
            // одно вещество в двух приближениях (CloudModel.cooling пока шар светится, thermal — дальше). Смена разом в
            // конец горения ставила тёмный купол за тик (ревью #426: у ракеты край +32 %, у бомбы середина на 17 м выше).
            // Середина слоёв — первой: номер клуба держит зерно его клубов у SmokePuffs, слияние — тот же клуб шара.
            double hand = handoff(t), ball = ballRadius(t) / Math.sqrt(5), by = ballHeight(t);
            for (int i = 0; i < LAYERS; i++) {
                int j = LAYER_ORDER[i];
                double q = LAYER_Q[j], m = LAYER_W[j] * fade;
                double sx = hand * ux * q * lean, sz = hand * uz * q * lean, y = by + hand * (cy + q * sv - by);
                out.blob(cx + sx, y, cz + sz, ball + hand * (sh - ball), ball + hand * (SLICE * sv - ball), soot * m, cloudDust * m, 0,
                        k, up);
            }
        }
        if (stemShown) {
            // ножка — трубка пыли до верха поднявшейся пыли, не выше низа облака (σ — ⚠ оценка: треть σ облака), клонится с ветром: снос на её высоте —
            // доля сноса облака; пыль — поровну по высоте (на метр оси куска — меньше во столько, во сколько ось длиннее
            // подъёма). Кусками трубки, а не отдельными клубами через 2σ: те шли бусами (кадры F5)
            // низ ножки — на её σ над землёй: ниже — слой пыли волны (свои клубы), а снос по степенному профилю ветра падает к
            // нулю только у самой земли, и кусок от точки удара до основания наклонённой ножки лёг бы полосой по земле
            double perHeight = stem * fade / (top - h0);
            double lean0 = air.wind(h0) / air.wind(cy + hob);
            double px = cx * lean0, py = h0 - hob, pz = cz * lean0;
            for (int i = 1; i <= STEM_PIECES; i++) {
                double f = (double) i / STEM_PIECES, h = h0 + f * (top - h0), lean = air.wind(h) / air.wind(cy + hob);
                double qx = cx * lean, qy = h - hob, qz = cz * lean;
                double dx = qx - px, dy = qy - py, dz = qz - pz, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double dust = len > 0 ? perHeight * dy / len : 0, fm = f - 0.5 / STEM_PIECES;
                out.tube(px, py, pz, ss, dust, qx, qy, qz, ss, dust, inBall(t, 0.5 * (px + qx), 0.5 * (py + qy), 0.5 * (pz + qz),
                        T_AIR + (k - T_AIR) * fm * 0.5), up * fm);
                px = qx;
                py = qy;
                pz = qz;
            }
        }
        // слой пыли волны — на поверхностях, с которых его подняло, и там, куда он стёк (высоты — от точки взрыва)
        ground.blobs(t, fade, (x, y, z, gh, gv, d) -> out.ground(x, y, z, gh, gv, d, inBall(t, x, y, z, T_AIR)));
        crushed.blobs(t, fade, (x, y, z, gh, gv, d) -> out.crushed(x, y, z, gh, gv, d, inBall(t, x, y, z, T_AIR)));
    }

    /** Сажа взрывчатки, кг: свободный углерод детонации, что пережил догорание в шаре ({@link #SOOT_SURVIVES}). */
    double heSoot() {
        return source.chargeKg() * source.explosive().freeCarbon() * SOOT_SURVIVES;
    }

    /**
     * Сажа облака, что уже родилась к возрасту t, кг: сажа взрывчатки — с мига детонации (микросекунды), сажа топлива — по
     * мере того, как шар его сжигает: доля сгоревшего — доля прожитого горения (шар CCPS горит своим топливом всё своё
     * время; ⚠ ровный расход — оценка, хода выгорания CCPS не даёт). Прежде вся будущая сажа топлива была в шаре с первого
     * мига (BLASTSEQ D02): днём шар в метр стоял тёмным пятном в два своих радиуса.
     */
    public double sootBurnt(double t) {
        double he = heSoot();
        if (fuelBallSeconds <= 0 || t >= fuelBallSeconds) return soot;
        return he + (soot - he) * Math.max(0, t) / fuelBallSeconds;
    }

    /**
     * Температура вещества в (x, y, z) от точки взрыва, у которого своя — kelvin: внутри светящегося шара оно — часть шара
     * (его пыль проступает той же долей, что шар тает), снаружи — своя, у поверхности (0,8–1,2 радиуса) — плавно. Днём по своей температуре ножка и пыль у земли внутри
     * шара ложились тёмным комом поверх него в первую секунду (кадры F6, бомба в 150 м).
     */
    private double inBall(double t, double x, double y, double z, double kelvin) {
        double ball = ballRadius(t), hot = ballKelvin(t);
        if (ball <= 0 || hot <= kelvin) return kelvin;
        double dx = x - ballX(t), dy = y - ballHeight(t), dz = z - ballZ(t), d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // у поверхности шара — плавно (0,8–1,2 радиуса): кусок, что выходит из растущего шара, не выскакивает непрозрачным
        double u = Math.max(0, Math.min(1, (1.2 * ball - d) / (0.4 * ball)));
        return kelvin + (hot - kelvin) * u * u * (3 - 2 * u);
    }

    /** Wilson cloud: shock heating, expansion, saturation and latent heat, see {@link Condensation}. */
    public double condensation() {
        return condensationModel().reach;
    }

    public double condensationSeconds() {
        return condensationModel().life;
    }

    public void condensation(double t, Blobs out) {
        double ball = ballRadius(t), cx = ballX(t), cy = ballHeight(t), cz = ballZ(t);
        condensationModel().blobs(t, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> {
            double dx = x - cx, dy = y - cy, dz = z - cz, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // The pressure history describes ambient air, not the hot products/fuel inside the fireball.
            // Smooth mixing across one cell prevents an opacity step at its moving boundary (shape estimate).
            double cold = Math.max(0, Math.min(1, (d - ball) / Math.max(sh, 1e-6)));
            out.blob(x, y, z, sh, sv, soot, dust, water * cold * cold * (3 - 2 * cold), kelvin, rise);
        });
    }

    private Condensation condensationModel() {
        if (condensation == null) condensation = new Condensation(source.chargeKg(), source.surface(), air.humidity());
        return condensation;
    }

    /** Толща самого густого клуба слоя пыли волны в возрасте t (через середину по горизонтали). */
    public double groundTauForTest(double t) {
        return ground.tau(t);
    }

    private Condensation condensation;
}
