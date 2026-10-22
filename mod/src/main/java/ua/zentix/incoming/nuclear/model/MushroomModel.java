package ua.zentix.incoming.nuclear.model;

/**
 * Гриб объёмом (DESIGN-nuke, шаги M1, M2): форма шара и шапки, купола, ножки и юбки, их вещество и температура по
 * времени. Метры, секунды, килотонны; высоты — над землёй под эпицентром. Шапка — сам огненный шар ({@link FireballModel}:
 * радиус, всплытие), который сворачивается в вихревое кольцо под куполом ({@link #rollUpSeconds}) — одной формой; её
 * верх, низ и радиус после этого — {@link CloudModel}, растекание после стабилизации и снос ветром — как у прежних клубов
 * гриба. Клиент (поле в шейдере) берёт числа отсюда раз в кадр.
 */
public final class MushroomModel {
    /** Температура воздуха вокруг, K. */
    public static final double AMBIENT_K = FireballModel.AMBIENT_K;
    /**
     * Показатель остывания ядра кольца смешением после сворачивания: ΔT ∝ (t/t_roll)^−0,27 — ночью ядро 15 кт светится
     * около двух с половиной минут, у 1 кт — около полуминуты.
     */
    static final double CORE_COOLING = 0.27;
    /** Время сворачивания в кольцо в единицах √(R/g): 2–3 (Turner 1957, Morton–Taylor–Turner 1956). */
    static final double ROLL_UP = 2.5;
    static final double G = 9.81;
    /** Большой радиус кольца шапки — эта доля её радиуса (как у прежних клубов шапки). */
    public static final double RING = 0.55;
    /**
     * Вещество шапки — капли и NO₂ (G&D §2.08: облако сперва красное или красно-бурое от оксидов азота, белеет, когда в
     * нём конденсируется вода; Taylor и Minge 1963, AFSC 61-142-1, с. 201–202: в отражённом свете облако белое от капель,
     * в проходящем — красно-бурое от NO₂, и внутри него бурый свет). Цвет и толща — из этих двух, без часов: отражение
     * облака — рассеяние капель против поглощения NO₂ на пути света в нём, а NO₂ редеет, пока шапка растёт. Физика даёт:
     * свернувшееся кольцо в отражённом свете — охра (у 15 кт на экране ~0,60 0,48 0,31, оттенок 35°), насквозь —
     * красно-бурое (17°); через минуту-две снаружи почти белое. Красно-бурым в отражённом свете кольцо не бывает ни при
     * какой доле NO₂: у сильного поглощения отражение ∝ ω' ∝ 1/σ по каналу — оттенок 29°, темнее, но не краснее.
     * <p>
     * Капли: ослабление σ = 3·LWC/(2·ρw·r_e) — толща облака τ = 3·LWP/(2·ρw·r_e) (Stephens 1978) на единицу пути, при
     * сечении капли вдвое больше её площади (капли много больше длины волны). <b>Оценки</b> (замеров водности шапки нет):
     * водность {@link #WATER} 0,5 г/м³ — середина 0,1–1 г/м³ кучевых облаков, эффективный радиус {@link #DROP_RADIUS} 10
     * мкм — как у кучевых. Шапка 15 кт после подъёма (1,7 км поперёк сечения кольца) — τ ≈ 130, у 1 кт (0,6 км) — ≈ 47.
     * Капли приходят с воздухом, который шапка подхватывает, сворачиваясь в кольцо: шар — горячий газ без капель, доля
     * капель — доля кольца {@link #rollUp} (оценка: срока конденсации в источниках нет).
     */
    static final double WATER = 0.5e-3, DROP_RADIUS = 10e-6, WATER_DENSITY = 1000;
    /** Ослабление каплями шапки, 1/м (0,075): {@code 3·WATER/(2·WATER_DENSITY·DROP_RADIUS)}. */
    public static final double DROP_SIGMA = 1.5 * WATER / (WATER_DENSITY * DROP_RADIUS);
    /**
     * Молекул NOx на килотонну мощности: 10²⁹ — NRC 1985 («The Effects on the Atmosphere of a Major Nuclear Exchange»,
     * гл. 6, с. 107–126: 10³² молекул NO на мегатонну); Gilmore 1975 (JGR 80, 4553) — 0,8·10³² от нагрева ударной волной,
     * до 1,5·10³² с перемешиванием. Оценка: у малых мощностей замеров нет, закон — пропорционально мощности.
     */
    static final double NOX_PER_KT = 1e29;
    /**
     * Доля NO₂ в NOx шапки, оценка: замеров нет. Окисление 2NO + O₂ за подъём шапки 15 кт даёт около 2 % (скорость
     * ∝ [NO]², а NO редеет с ростом шапки), озон и атомы кислорода у шара (G&D §2.123) — больше. 0,1 — с проверкой по
     * наблюдениям ({@code MushroomFieldTest}): шапка 15 кт после подъёма в отражённом свете почти белая, в проходящем —
     * бурая (Taylor и Minge), свет сквозь свернувшееся кольцо — красно-бурый (G&D). При 0,3 шапка после подъёма осталась бы
     * бежевой (синий — 0,72 красного), при 0,03 свет сквозь кольцо — оранжево-бурый (оттенок 31°), а не красно-бурый.
     */
    static final double NO2_SHARE = 0.1;
    /**
     * Сечение поглощения NO₂ по каналам кадра R, G, B, м² на молекулу: Bogumil et al. 2003 (J. Photochem. Photobiol. A 157,
     * 167), 243 K (воздух на высоте шапки), среднее по полосе канала — положительная часть функции sRGB (CIE 1931 → sRGB)
     * × свет Солнца (Планк 5 800 K). Vandaele et al. 1998 (294 K) — то же в пределах 2 %. В синем NO₂ поглощает в восемь
     * раз сильнее, чем в красном, — отсюда бурый.
     */
    public static final double[] NO2_CROSS = {5.74e-24, 1.33e-23, 4.64e-23};
    /** Толща юбки поперёк у низкого подрыва (у ножки — по массе пыли, {@link StemDust}). */
    static final double TAU_SKIRT = 2;
    /** За сколько секунд (e-кратно) оседает пыль у земли: воздушный подрыв, наземный (как у стены пыли). */
    public static final double DUST_SETTLE_AIR = 90, DUST_SETTLE_SURFACE = 240;
    /**
     * Радиус ножки — доля радиуса шапки по мощности (Glasstone и Dolan 2.17): до 20 кт — около половины, с мощностью доля
     * падает, у мегатонн ножка в 5–10 раз уже облака. Между узлами — линейно по логарифму мощности.
     */
    private static final double[] STEM_Y = {20, 1000, 10000};
    private static final double[] STEM_SHARE = {0.5, 0.2, 0.1};
    /**
     * Ножка под шаром, пока он не свернулся в кольцо, — эта доля его радиуса: след всплывающего шара, кольца, которое
     * тянет её вширь, ещё нет; со сворачиванием — к {@link #stemShare}.
     */
    static final double BALL_STEM = 0.16;
    /** Низ ножки, пока пыль у земли не осела, — не уже этой доли радиуса шапки: раструб пыли ({@link #dustFlare}). */
    static final double DUST_BASE = 0.376;

    private MushroomModel() {}

    /**
     * Гриб в момент t.
     *
     * @param life      доля гриба 0..1: до половины рассеяния после стабилизации — весь, к его концу
     *                  ({@link #lifeSeconds}) — ноль
     * @param top       верх облака (у шара — его верх)
     * @param capBottom низ шапки (у шара — его низ)
     * @param capY      середина шапки по высоте (у шара — его середина)
     * @param capR      радиус шапки по горизонтали, до края кольца (у шара — его радиус)
     * @param ringR     большой радиус кольца (0 — ещё шар)
     * @param ringA     полуось сечения кольца по горизонтали
     * @param ringB     полуось сечения по вертикали
     * @param domeY     середина купола над кольцом
     * @param domeR     полуось купола по горизонтали
     * @param domeH     полуось купола по вертикали
     * @param capDrops  ослабление каплями шапки, 1/м ({@link #DROP_SIGMA} × доля кольца × доля гриба, растекаясь — редеет
     *                  как площадь)
     * @param capNo2    молекул NO₂ в кубометре шапки (весь NO₂ подрыва в её объёме, {@link #capVolume}, × доля гриба)
     * @param stemTop   верх ножки (догоняет низ шапки)
     * @param stemR     радиус ножки, средний по высоте ({@link #stemShare} радиуса шапки; у шапки шире, у земли — раструб пыли)
     * @param stemTau   толща ножки поперёк (с видимостью, оседанием пыли не считая раструба)
     * @param lowDust   доля пыли у земли 0..1 (оседает): раструб ножки и юбка
     * @param skirtR    радиус середины юбки по земле
     * @param skirtW    полуширина юбки по земле
     * @param skirtH    высота юбки
     * @param skirtTau  толща юбки поперёк (с оседанием)
     * @param endTop    верх облака после стабилизации: снос ветром — по доле этой высоты
     */
    public record Shape(double t, double life, double top, double capBottom, double capY, double capR, double ringR, double ringA,
                        double ringB, double domeY, double domeR, double domeH, double capDrops, double capNo2, double stemTop, double stemR,
                        double stemTau, double lowDust, double skirtR, double skirtW, double skirtH, double skirtTau, double endTop) {}

    /**
     * Постоянная Ричардсона — Обухова: пары частиц в турбулентности расходятся как ⟨r²⟩ = g·ε·t³, g ≈ 0,5 (Ott и Mann 2000,
     * JFM 422, 207; Sawford 2001, Annu. Rev. Fluid Mech. 33, 289).
     */
    static final double RICHARDSON = 0.5;
    /**
     * Скорость диссипации турбулентной энергии там, где стоит облако, м²/с³. <b>Оценка</b>: замеров у облаков испытаний нет.
     * Выбрана так, чтобы гриб 15 кт жил около прежнего (27 минут, было 24), — наибольшее круглое значение, при котором
     * жизнь растёт с мощностью везде: при ε больше 4,2·10⁻³ сразу выше 1 кт стабилизация укорачивается (τ «Уоспа» вдвое
     * дольше, чем у «Грейбла») быстрее, чем удлиняется рассеяние, и гриб 1,2 кт жил бы на секунды меньше гриба 1 кт (при
     * прежних 24 минутах у 15 кт — ε = 6,2·10⁻³). В спокойной свободной тропосфере ε — 10⁻⁵–10⁻⁴, в кучевых облаках и
     * при слабой болтанке — 10⁻³–10⁻²: облако после подъёма само ещё турбулентно. Сверка: облако 1 Мт видно 68 минут —
     * «около часа или больше» (Glasstone и Dolan, §2.15).
     */
    static final double DISSIPATION = 4e-3;

    /**
     * Сколько секунд гриб виден: поднимается до стабилизации ({@link CloudModel#stabilizationSeconds}), потом турбулентность
     * воздуха рассеивает его ({@link #dispersalSeconds}): 0,1 кт — 17 минут, 1 кт — 22, 15 кт — 27, 1 Мт — 68. Рассеяние
     * растёт с размером облака, а не с тем, как медленно оно поднималось.
     */
    public static double lifeSeconds(double yieldKt) {
        return CloudModel.stabilizationSeconds(yieldKt) + dispersalSeconds(yieldKt);
    }

    /**
     * За сколько секунд турбулентность рассеивает остановившееся облако: пары его частиц расходятся на радиус шапки после
     * стабилизации R ({@link CloudModel#capRadius}) — относительная дисперсия Ричардсона — Обухова, ⟨r²⟩ = g·ε·t³, и
     * t = (R²/(g·ε))^⅓ ({@link #RICHARDSON}, {@link #DISSIPATION}): 1 кт — 10,6 минуты, 15 кт — 21, 1 Мт — 61.
     */
    public static double dispersalSeconds(double yieldKt) {
        double r = CloudModel.capRadius(yieldKt);
        return Math.cbrt(r * r / (RICHARDSON * DISSIPATION));
    }

    /**
     * За сколько секунд горячий пузырь сворачивается в вихревое кольцо: 2,5·√(R/g), R — радиус шара. Пузырь с перепадом
     * плотности порядка самой плотности становится кольцом, пройдя один-два своих радиуса, — время по плавучести, а не
     * по свечению шара: у 15 кт ~12 с, у 1 кт ~7 с.
     */
    public static double rollUpSeconds(double yieldKt, boolean surface) {
        return ROLL_UP * Math.sqrt(FireballModel.maxRadius(yieldKt, surface) / G);
    }

    /** Доля кольца 0..1: 0 — шар, 1 — кольцо (с четверти времени сворачивания до его конца, плавно). */
    public static double rollUp(double t, double yieldKt, boolean surface) {
        double tr = rollUpSeconds(yieldKt, surface);
        return smooth(0.25 * tr, tr, t);
    }

    /**
     * Нагрев края свернувшейся шапки — доля перепада температуры ядра: край смешан с подхваченным воздухом (термик
     * Мортона–Тейлора–Тёрнера: горячее всего — ядро кольца).
     */
    public static final double EDGE_HEAT = 0.35;
    /**
     * Языки клубов на краю: нагрев края ± эта доля его же, на размах клубов ({@code MUSH_SKIN} в {@code mushroom.glsl}):
     * выступ клуба — свежий горячий газ изнутри, ложбина — уже смешанный. У кольца — ±9 % перепада ядра; у шара узор
     * нагрева — запечённый ({@code client/nuclear/NukeBallSurface}), языки клубов — только его доля, пока он сворачивается
     * (и весь, если узор не прочитан).
     */
    public static final double SKIN = 0.09 / EDGE_HEAT;
    /**
     * Размах клубов края у шара — доля их размаха у кольца: пока шар растёт, его край — гладкая сфера (фронт излучения
     * и ударной волны); клубы растут с подхваченным воздухом, когда он всплывает и сворачивается.
     */
    static final double BALL_BILLOWS = 0.3;

    /**
     * Доля перепада температуры ядра у видимого края 0..1: у шара — вся (он почти изотермичен: свет внутри выравнивает
     * температуру), сворачиваясь в кольцо, край смешивается с воздухом — до {@link #EDGE_HEAT}.
     */
    public static double edgeShare(double t, double yieldKt, boolean surface) {
        return 1 - (1 - EDGE_HEAT) * rollUp(t, yieldKt, surface);
    }

    /** Размах клубов края 0..1 против кольца: у шара — {@link #BALL_BILLOWS}, растёт со сворачиванием. */
    public static double billows(double t, double yieldKt, boolean surface) {
        return BALL_BILLOWS + (1 - BALL_BILLOWS) * rollUp(t, yieldKt, surface);
    }

    /**
     * Видимая температура шара и шапки снаружи, K: перепад ядра × {@link #edgeShare}. Пока шар — его температура
     * ({@link FireballModel#temperature}); свернувшись, он темнеет снаружи: край смешан с воздухом, а ядро — в глубине
     * шапки. Толща поперёк сечения кольца (σ' по зелёному, {@code MushroomField.capMatter}) от сворачивания до
     * стабилизации — у 1 кт 5–13, у 15 кт 11–36, у 1 Мт 37–140, до ядра — половина её: у 1 кт ядро ночью просвечивает,
     * у мегатонны — нет. Шейдер ослабляет свечение ядра по пути к глазу прямым путём, e^(−τ'); двумя потоками оно
     * выходило бы и рассеянным, как e^(−kτ') ({@code PallLight.k}): в красном через 30 с у 1 кт — втрое больше, у 15 кт —
     * в ~200 раз (не сделано, DESIGN-nuke). У 15 кт через 9 с — ~1 250 K, через 12 с — ~830 K.
     */
    public static double edgeTemperature(double t, double yieldKt, boolean surface) {
        return AMBIENT_K + (coreTemperature(t, yieldKt, surface) - AMBIENT_K) * edgeShare(t, yieldKt, surface);
    }

    /**
     * Температура ядра шапки, K: до конца сворачивания шапка — сам шар ({@link FireballModel#temperature}), дальше кольцо
     * остывает смешением с воздухом: T_окр + ΔT_св·(t/t_св)^−0,27. Непрерывна на сворачивании.
     */
    public static double coreTemperature(double t, double yieldKt, boolean surface) {
        double tr = rollUpSeconds(yieldKt, surface);
        if (t <= tr) return FireballModel.temperature(t, yieldKt);
        double rolled = FireballModel.temperature(tr, yieldKt) - AMBIENT_K;
        return AMBIENT_K + rolled * Math.pow(t / tr, -CORE_COOLING);
    }

    /**
     * Свет шара и шапки на белой стене в {@code distanceM} метрах от середины шапки, в долях белой стены в полдень (без
     * привыкания глаза): ламбертов шар радиуса шапки с яркостью чёрного тела её видимой температуры освещает как
     * L·R²/d² (у поверхности — L). Источник света вокруг: на ножке, юбке, пыли, дыме, небе.
     */
    public static double light(Shape s, double yieldKt, boolean surface, double distanceM) {
        double r = s.capR();
        return BlackBody.luminance(edgeTemperature(s.t(), yieldKt, surface)) * r * r / Math.max(distanceM * distanceM, r * r);
    }

    /**
     * Объём шапки, м³: сфероид с её радиусом и высотой, (2/3)·π·R²·h — у шара его объём, у свернувшегося кольца с куполом
     * — на 5 % меньше суммы тора и полукупола.
     */
    public static double capVolume(Shape s) {
        return volume(s.capR(), s.top() - s.capBottom());
    }

    private static double volume(double r, double h) {
        return 2.0 / 3 * Math.PI * r * r * h;
    }

    /** Поглощение NO₂ шапки в канале кадра c (0 — R, 1 — G, 2 — B), 1/м. */
    public static double capAbsorption(Shape s, int c) {
        return s.capNo2() * NO2_CROSS[c];
    }

    /**
     * Доля грунта в веществе шапки 0..1. Шар касается земли (наземный подрыв; воздушный — не выше радиуса шара) — потоки
     * под шапкой втягивают в неё поднятую землю, и остывшее облако серое от пыли; выше двух радиусов в шапке только
     * конденсат и пары бомбы, и она белеет, как обычное облако (G&D 2.08); между — плавно, как у ножки, что догоняет
     * шапку ({@link #shape}).
     */
    public static double soil(double yieldKt, double hobM, boolean surface) {
        if (surface) return 1;
        double rf = FireballModel.maxRadius(yieldKt, false);
        return 1 - smooth(rf, 2 * rf, Math.max(0, hobM));
    }

    /**
     * Гриб в момент t (секунды после подрыва) у подрыва мощностью {@code yieldKt} на высоте {@code hobM} над землёй;
     * {@code groundFrontM} — докуда по земле дошёл фронт (у клиента — по его профилю фронта). Шапка — огненный шар
     * ({@link FireballModel#radius}, {@link FireballModel#centreHeight}), который по {@link #rollUp} сворачивается в кольцо
     * шапки: числа формы — от шара к шапке одной долей, без скачка. Законы шапки, ножки и юбки — прежних клубов гриба.
     * null — гриба ещё нет или он уже рассеялся.
     */
    public static Shape shape(double t, double yieldKt, double hobM, boolean surface, double groundFrontM) {
        return shape(t, yieldKt, hobM, surface, groundFrontM, StemDust.of(yieldKt, hobM, surface, StemDust.CLEAR));
    }

    /**
     * То же с пылью ножки подрыва ({@link StemDust}: по его видимости — свет вспышки греет землю); null — таблица ещё
     * считается, ножка без пыли.
     */
    public static Shape shape(double t, double yieldKt, double hobM, boolean surface, double groundFrontM, @org.jetbrains.annotations.Nullable StemDust dust) {
        if (t <= 0) return null;
        double stab = CloudModel.stabilizationSeconds(yieldKt);
        // рассеяние: первую половину его облако видно целиком, за вторую тает (оценка: тот же вид, что был у прежней жизни
        // в 4 стабилизации, — целиком до 2,5)
        double life = clamp(2 * (1 - Math.max(0, t - stab) / dispersalSeconds(yieldKt)), 0, 1);
        if (life <= 0) return null;
        double hob = Math.max(0, hobM);
        double rf = FireballModel.maxRadius(yieldKt, surface);
        double start = CloudModel.start(hob, yieldKt, surface), endTop = CloudModel.end(hob, yieldKt, surface);
        double top = CloudModel.top(t, hob, yieldKt, surface);
        double capBot = Math.max(hob, CloudModel.capBottom(t, hob, yieldKt, surface));
        double rise = clamp((top - start) / Math.max(1, endTop - start), 0, 1);
        double spread = 1 + 0.25 * clamp((t - stab) / stab, 0, 2);
        double capR = (rf * 1.1 + (CloudModel.capRadius(yieldKt) - rf * 1.1) * Math.pow(rise, 0.8)) * spread;
        // шапка не ниже шара: пока верх облака по CloudModel ниже, верх шапки — по её высоте
        double capH = Math.max(rf, top - capBot);
        top = capBot + capH;
        // Шапка — вихревое кольцо термика под куполом. Кольцо: большой радиус растёт от нуля (шар) до доли радиуса шапки,
        // край — на радиусе шапки; по высоте — от низа шапки почти до верха (середина — capY). Купол — над дыркой кольца,
        // до верха шапки; снизу у оси под ним выемка, в которую входит ножка (кольцо тянет воздух вверх сквозь дырку).
        // Клубы выступают за эти края на долю меньшей полуоси сечения кольца (MushroomField.REACH_CAP)
        // Шапка — огненный шар, свернувшийся в кольцо: до четверти времени сворачивания — сфера шара (кольца нет, купол
        // внутри), дальше каждое число формы идёт от шара к шапке той же долей roll — одна форма, без смены картинки.
        // Шар для наземного — полусфера: середина на земле, низ под ней
        double roll = rollUp(t, yieldKt, surface);
        double ballR = FireballModel.radius(t, yieldKt, surface), ballY = FireballModel.centreHeight(t, hob, yieldKt, surface);
        capR = lerp(ballR, capR, roll);
        capBot = lerp(ballY - ballR, capBot, roll);
        capH = lerp(2 * ballR, capH, roll);
        top = capBot + capH;
        double capY = lerp(ballY, capBot + 0.45 * capH, roll);
        double ringR = RING * capR * roll, ringA = capR - ringR, ringB = lerp(ballR, 0.4 * capH, roll);
        double domeR = 0.6 * capR * Math.sqrt(Math.max(roll, 0.05)), domeH = 0.35 * capH, domeY = lerp(ballY, capBot + 0.6 * capH, roll);
        double tau = t / FireballModel.secondMaximumSeconds(yieldKt);
        // капли — с подхваченным воздухом, пока шар сворачивается в кольцо; растекаясь наковальней, шапка редеет как
        // площадь. NO₂ подрыва — во всём её объёме: шар (через секунду поперёк в зелёном τ ≈ 8 у 1 кт, 15 у 15 кт, 52 у
        // 1 Мт) непрозрачен им
        double capDrops = DROP_SIGMA * roll * life / (spread * spread);
        double capNo2 = NO2_SHARE * NOX_PER_KT * yieldKt * life / Math.max(volume(capR, capH), 1);
        // ножка входит в выемку под куполом (воздух поднимается сквозь кольцо; у килотонн она шире купола и занимает весь
        // низ шапки внутри кольца). Под шаром она — его след, со сворачиванием расходится до доли G&D. Её вещество — пыль,
        // которую подсос забрал из слоя у эпицентра (StemDust): у наземного шар касается земли, и ножка — его след без
        // разрыва под шапкой; у воздушного пыль идёт вверх с воздухом ножки от начала подсоса и может не догнать шапку —
        // столб пыли тогда висит под ней (NRC 1985, с. 22)
        double stemR = capR * lerp(BALL_STEM, stemShare(yieldKt), roll);
        double stemTop = hob <= rf ? capBot + 0.3 * capH : dust == null ? 0 : Math.min(capBot + 0.3 * capH, dust.top(t));
        double stemTau = dust == null ? 0 : dust.tau(t, stemR) * smooth(4, 20, tau) * life;
        double lowDust = Math.exp(-Math.max(0, t - stab * 0.1) / (hob <= rf ? DUST_SETTLE_SURFACE : DUST_SETTLE_AIR));
        // юбка — пыль у основания ножки, поднятая за фронтом: её край без клубов — на 0,85 пути фронта по земле (с клубами
        // — до него), не дальше 0,7 радиуса шапки (за низом ножки с раструбом, DUST_BASE, и у килотонн — 0,43). Пока фронт
        // не дошёл до земли (воздушный), поднимать её нечему — юбки нет; сечение — не меньше метра (единица клубов юбки делит)
        double outer = Math.min(Math.max(0, groundFrontM), capR * 0.7);
        double skirtR = 0.6 * outer, skirtW = Math.max(1, 0.25 * outer);
        double skirtH = Math.max(1, Math.min(capBot * 0.12, 350 * Math.cbrt(yieldKt / 15)) * smooth(0, 30, t));
        double skirtTau = outer <= 0 ? 0 : TAU_SKIRT * smooth(0.5, 3, t) * (hob <= rf * 4 ? 1 : 0.5) * lowDust * life;
        return new Shape(t, life, top, capBot, capY, capR, ringR, ringA, ringB, domeY, domeR, domeH, capDrops, capNo2, stemTop,
                stemR, stemTau, lowDust, skirtR, skirtW, skirtH, skirtTau, endTop);
    }

    /**
     * Радиус ножки в долях радиуса шапки (Glasstone и Dolan 2.17): до 20 кт — 0,5, к 1 Мт — 0,2, к 10 Мт и выше — 0,1
     * (ножка в 5–10 раз уже облака).
     */
    public static double stemShare(double yieldKt) {
        if (yieldKt <= STEM_Y[0]) return STEM_SHARE[0];
        for (int i = 1; i < STEM_Y.length; i++) {
            if (yieldKt <= STEM_Y[i]) {
                double f = Math.log(yieldKt / STEM_Y[i - 1]) / Math.log(STEM_Y[i] / STEM_Y[i - 1]);
                return STEM_SHARE[i - 1] + (STEM_SHARE[i] - STEM_SHARE[i - 1]) * f;
            }
        }
        return STEM_SHARE[STEM_SHARE.length - 1];
    }

    /**
     * Раструб пыли у земли сверх низа ножки, м: пыль, которую втягивает подъём у земли, тянется с круга в
     * {@link #DUST_BASE} радиуса шапки (её поднимает волна подрыва, не ножка) и оседает с {@code lowDust}. Ножка шире
     * этого круга (килотонны после сворачивания) — раструба нет: иначе он накрыл бы юбку.
     */
    public static double dustFlare(Shape s) {
        return Math.max(0, DUST_BASE * s.capR() - stemRadius(0, s.stemR(), 0)) * s.lowDust();
    }

    /**
     * Скорость обращения вещества шапки по её сечению, рад/с: вещество вихревого кольца обходит сечение со скоростью
     * подъёма облака (Turner 1957: жидкость кольца движется со скоростью порядка его подъёма), путь — эллипс сечения
     * a × b, длиной около 2π·√((a² + b²)/2). У 15 кт оборот — около 25 с на 20-й секунде и около трёх минут ко второй
     * минуте, к стабилизации шапка стоит. Пока шар не свернулся в кольцо, оси обращения ещё нет: шейдер поворачивает узор
     * на долю сворачивания, а клубы кипят уже по этим часам (пузырь шара перемешивается так же быстро).
     */
    public static double churnRate(Shape s, double riseSpeed) {
        double mean = Math.sqrt(0.5 * (s.ringA() * s.ringA() + s.ringB() * s.ringB()));
        return riseSpeed / Math.max(mean, 1);
    }

    /**
     * Часы кипения шапки: ψ(t) — интеграл {@link #churnRate} от подрыва, рад (сколько радиан своего сечения прошло
     * вещество шапки). Функция возраста подрыва: перемотка повтора и вход позже дают ту же шапку. Таблица — по сетке
     * t = T·(i/N)² (гуще в начале, где подъём быстрее), T — три стабилизации: дальше подъём слабее начального в 10⁴ раз,
     * и часы стоят.
     */
    public static Churn churn(double yieldKt, double hobM, boolean surface) {
        double end = 3 * CloudModel.stabilizationSeconds(yieldKt), hob = Math.max(0, hobM);
        double[] turns = new double[CHURN_NODES + 1];
        double last = 0, prev = 0;
        for (int i = 1; i <= CHURN_NODES; i++) {
            double t = end * i * i / ((double) CHURN_NODES * CHURN_NODES);
            // часам кипения нужна только форма кольца — без пыли ножки (её таблица считается в фоне)
            Shape s = shape(t, yieldKt, hob, surface, 0, null);
            double rate = s == null ? 0 : churnRate(s, CloudModel.riseSpeed(t, hob, yieldKt, surface));
            // у подрыва (t = 0) шапки ещё нет, обращение — как в первом узле
            if (i == 1) last = rate;
            turns[i] = turns[i - 1] + 0.5 * (last + rate) * (t - prev);
            last = rate;
            prev = t;
        }
        return new Churn(end, turns);
    }

    /** Узлов таблицы часов кипения. */
    static final int CHURN_NODES = 256;

    /** Часы кипения одного подрыва ({@link #churn}): {@code turns[i]} — в момент end·(i/N)². */
    public static final class Churn {
        private final double end;
        private final double[] turns;

        Churn(double end, double[] turns) {
            this.end = end;
            this.turns = turns;
        }

        /** Конец таблицы, с: дальше часы стоят. */
        public double end() {
            return end;
        }

        /** Часы в момент t, рад: между узлами — линейно, после конца таблицы стоят. */
        public double at(double t) {
            int n = turns.length - 1;
            if (t <= 0) return 0;
            if (t >= end) return turns[n];
            double x = n * Math.sqrt(t / end);
            int i = Math.min((int) x, n - 1);
            double t0 = end * i * i / ((double) n * n), t1 = end * (i + 1) * (i + 1) / ((double) n * n);
            return turns[i] + (turns[i + 1] - turns[i]) * (t - t0) / (t1 - t0);
        }
    }

    /**
     * Радиус ножки на доле её высоты w (0 — земля, 1 — низ шапки): в среднем по высоте — {@code stemR}, к шапке шире на
     * треть (её тянет кольцо: у 2 кт край шапки выступает за ножку на треть своего радиуса) и у земли — раструб пыли
     * {@code flare} сверх низа ножки ({@link #dustFlare}). {@code mushStemR} в {@code mushroom.glsl} — то же.
     */
    public static double stemRadius(double w, double stemR, double flare) {
        double x = clamp(w, 0, 1), base = Math.pow(1 - x, 6);
        return stemR * (0.85 + 0.45 * x * x) + flare * base;
    }

    /**
     * Скорость воздуха в ножке — эта доля скорости подъёма облака ({@link #stemRise}). <b>Оценка</b>: замеров потока в
     * ножке нет; число — прежнее.
     */
    public static final double STEM_INFLOW = 0.3;

    /**
     * На сколько поднялся воздух ножки к моменту t, м: кольцо тянет его вверх долей {@link #STEM_INFLOW} скорости подъёма
     * облака ({@link CloudModel#riseSpeed}), путь — её интеграл, та же доля подъёма верха. По нему струи ножки идут вверх
     * ({@code stemRise} в {@code mushroom.glsl}) и встают, когда облако стабилизируется: у 15 кт за первую минуту — на
     * 1,1 км (вначале 24 м/с), всего — 2,9 км; у 1 кт — на 0,4 км (7 м/с), всего — 1,7 км. Прежде струи шли со скоростью
     * в миг подрыва всю жизнь гриба — и над остановившимся облаком.
     */
    public static double stemRise(double t, double hobM, double yieldKt, boolean surface) {
        return STEM_INFLOW * (CloudModel.top(t, hobM, yieldKt, surface) - CloudModel.top(0, hobM, yieldKt, surface));
    }

    /** Снос ветром на высоте h, доля пути ветра за время t: верх сносит сильнее низа (как у прежних клубов). */
    public static double driftShare(double h, double endTop) {
        return clamp(h / Math.max(endTop, 1), 0, 1);
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    static double smooth(double e0, double e1, double x) {
        double f = clamp((x - e0) / (e1 - e0), 0, 1);
        return f * f * (3 - 2 * f);
    }

    static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
