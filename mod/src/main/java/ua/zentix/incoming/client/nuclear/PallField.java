package ua.zentix.incoming.client.nuclear;

import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

/**
 * Пелена после ударной волны (план «ядерка уровня кино», шаг 2): поле плотности пыли и дыма одного подрыва по радиусу
 * от эпицентра и времени. Его рисует объём слоя эффектов ({@code fx/layer/PallPass}: лучи по полю, умноженному на
 * трёхмерный шум), по нему же видимость, звук, пепел и тень у камеры ({@link NukePall}). Чистая математика по модели
 * подрыва, без мира и без нового пакета: что волна поднимает, клиент знает из давления на земле
 * ({@link Detonation#groundOverpressureKpa}) и прихода фронта ({@link Detonation#arrivalTicks}), как и сервер.
 * <p>
 * <b>Пыль</b> — тот же слой, что голова стены у фронта ({@link BlastWall}), по одним законам ({@link DustModel}): что
 * подняла волна (грунт по ветру за фронтом и пыль обломков там, где рушатся дома), верх слоя (у фронта — десятки метров,
 * потом его поднимает нагретая вспышкой земля), оседание пяти корзин по размеру и уход мелких. Возраст пыли — секунды
 * модели с прихода фронта в игре: голова — пыль моложе положительной фазы, пелена — старше, и место переходит от головы
 * к пелене за полфазы ({@link BlastWall#handoff}). Ослабление у земли — толща слоя на его профиль
 * ({@link #profile}: столб — 0,548 верха). Пыль сносит ветер подрыва ({@link #DRIFT}) — с того мига, как её подняло:
 * у фронта пыль ещё на месте ({@link #source}). Высота — от земли под точкой там, где она выше земли под эпицентром
 * (холм: пыль поднимает ветер с его склона), а в долину пыль стекает — там слой от земли под эпицентром.
 * <p>
 * <b>Мгла.</b> Над зоной поджига (световой импульс от {@link ThermalModel#IGNITE}) горит город: дымная мгла
 * (видно на {@link #HAZE_VISIBILITY_M} м) встаёт за пару минут и держится до утра ({@link #hazeEndTicks}; утро — по часам
 * мира, {@link #daylight}: сон и {@code /time} его приближают), тянется по ветру шлейфом; снизу её подсвечивают пожары.
 * <p>
 * <b>Вода</b> (план, шаг 6). Над водой пыли нет: ветер за фронтом срывает с гребней брызги ({@link WaterModel#spray}) —
 * свой слой со своей высотой (высота срыва гребней, {@link WaterModel#sprayHeight}; крупные капли выпадают — верх
 * опускается, {@link #sprayTop}) и густотой (капли: белые, почти не поглощают, рассеивают вперёд и за пару минут
 * выпадают и испаряются, {@link #SPRAY_S}). У подрыва на воде ещё и базисная волна — туман из воды упавшего столба
 * ({@link #surge}, законы и часы {@link WaterModel}, те же, что у столба в объёме гриба). Что под пеленой — суша или
 * вода — знает карта {@link PallGround} (доля воды по клеткам, из того, что знает клиент); вещество — по месту, откуда
 * его подняло: пыль, снесённая ветром на воду, остаётся пылью. Поэтому шейдер берёт долю воды там же, где таблицу пыли
 * и брызг, — у точки за вычетом сноса; мглу — у её источника (над водой пожаров нет).
 * <p>
 * Время: пыль и брызги — по часам модели (тики / (20·scale)), как фронт и голова; мгла, пожары и бумага — по настоящим
 * секундам (тики клиента / 20). Длины — в метрах модели и блоках через масштаб подрыва. Таблица по радиусу
 * ({@link #row}) — то, что получает шейдер: всё дорогое (модель пыли, приход фронта) считается здесь.
 */
public final class PallField {
    /**
     * Неровность верха слоя пыли: верх над точкой — (1 ± это) верха таблицы (шейдер: {@code PALL_BUMPS}, шум плитки).
     * Верх перемешанного слоя над нагретой землёй бугрится термиками — зона вовлечения у конвективного слоя толщиной 0,2–0,4
     * его высоты (Stull 1988, гл. 11; Deardorff, Willis и Stockton 1980), половина её — 0,3 (оценка). Слой — тот же профиль
     * до своего верха, поэтому толща над точкой для света — та же, что у густоты: в ложбине между буграми — у верха слоя,
     * а не в глубине средней пелены.
     */
    public static final double BUMPS = 0.3;
    /**
     * Перемешанный слой: густота внутри отличается от средней на эту долю прежнего (в молодом слое у фронта — клочья с
     * просветами, Kuhl §3.5: густота в 7 раз скачет). Колебания примеси в перемешанном конвективном слое — 10–20 % средней
     * (Willis и Deardorff 1976, бак; оценка — 0,2): сплошь густая пелена сверху — ровное одеяло, а не клочья с провалами
     * до земли.
     */
    public static final double MIXED = 0.2;

    /** Узлов таблицы по радиусу (ширина строки текстуры слоя). */
    public static final int BINS = 128;
    /**
     * Видимость в густой пыли прежней пелены, м (по Кошмидеру — ослабление 3,912 / V): мера «густой пыли» для формы клубов
     * и пепла ({@link #SIGMA_DENSE}); слой у фронта гуще в десятки раз.
     */
    static final double DENSE_VISIBILITY_M = 40;
    /** Видимость в дымной мгле над горящим городом, м. */
    static final double HAZE_VISIBILITY_M = 900;
    /**
     * Пыли тоньше этой толщи над землёй в таблице нет (сквозь неё солнце — 95 %): докуда по радиусу таблица и когда пелена
     * осела ({@link #dustEnd}).
     */
    static final double GONE_DEPTH = 0.05;
    /** Брызги и базисная волна тоньше этой доли густоты не рисуются. */
    static final double GONE = 0.0015;
    /** Мгла встаёт над пожарами: от стольких секунд после фронта до стольких. */
    static final double HAZE_FROM_S = 15, HAZE_FULL_S = 150;
    /** Мгла держится не меньше стольких игровых тиков (5 минут) и гаснет за последнюю минуту. */
    static final long HAZE_MIN_TICKS = 6000, HAZE_FADE_TICKS = 1200;
    /** Тиков в игровых сутках; утро (6:00) — время суток, кратное им, день — их первая половина. */
    static final long DAY = 24000;
    /**
     * Время суток ушло вперёд игрового времени больше чем на столько (сон, {@code /time add}) — это прыжок. Поправки
     * сервера ({@code ClientNuclear.ClockLead}: отставая, он отводит часы клиента назад и на сотни тиков) двигают оба
     * вместе и прыжком не выходят.
     */
    static final long SKIP_TICKS = 100;
    /** Назад — больше чем на столько ({@code /time set day}). */
    static final long SET_TICKS = 1200;
    /** Пыль сносит долей ветра подрыва: у земли ветер слабее, тяжёлое оседает. */
    static final double DRIFT = 0.5;
    /** Шлейф мглы по ветру — не длиннее стольких радиусов зоны пожаров. */
    static final double PLUME_MAX = 2;
    /** Высота мглы над пожарами, м (у 15 кт): дым поднимается выше пыли. */
    static final double HAZE_TOP_M = 260;
    /**
     * Пепел сыплется во всю силу, когда из пыли оседает столько, кг/(м²·с) (полграмма на м² в секунду: крупное в первые
     * минуты, {@link DustModel.Column#fallout}).
     */
    static final double ASH_FULL = 5e-4;

    /**
     * Видимость в самых густых брызгах за фронтом над водой, м ({@link WaterModel#spray} = 1, ветер за 45 м/с): как в
     * ураган, когда воздух полон пены и брызг (шкала Бофорта, 12 баллов: «видимость очень плохая»).
     */
    static final double SPRAY_VISIBILITY_M = 60;
    /**
     * Брызги за фронтом редеют e-кратно за столько секунд: крупные капли пены (сотни микрон, падают со скоростью в доли
     * метра и метр в секунду из слоя в десятки метров) выпадают за десятки секунд, мелкие испаряются — через две-три минуты
     * над водой чисто ({@link #GONE}).
     */
    static final double SPRAY_S = 30;
    /**
     * Верх брызг опускается к этой доле высоты срыва, пока крупные капли выпадают (за {@link #SPRAY_S}): остаётся мелкая
     * водяная пыль ниже (оценка: капли в сотни микрон падают метр в секунду, мелкие — сантиметры).
     */
    static final double SPRAY_SINK = 0.6;
    /**
     * Видимость в самой густой базисной волне, м ({@link WaterModel#surgeDensity} = 1): туман из капель упавшей воды столба,
     * у «Бейкера» — сплошная белая стена (Glasstone &amp; Dolan, §2.47–2.65; оценка, как и доли у {@link WaterModel}).
     */
    static final double SURGE_VISIBILITY_M = 50;

    /** Ослабление света в густой пыли прежней пелены (мера густоты) и в мгле, 1/м. */
    static final double SIGMA_DENSE = Sight.KOSCHMIEDER / DENSE_VISIBILITY_M, SIGMA_HAZE = Sight.KOSCHMIEDER / HAZE_VISIBILITY_M;
    /** Ослабление в самых густых брызгах и в самой густой базисной волне, 1/м. */
    static final double SIGMA_SPRAY = Sight.KOSCHMIEDER / SPRAY_VISIBILITY_M, SIGMA_SURGE = Sight.KOSCHMIEDER / SURGE_VISIBILITY_M;

    /** Доля воды под точкой 0..1 (dx, dz — от эпицентра по земле, блоки): карта {@link PallGround}. */
    @FunctionalInterface
    public interface Water {
        /** Под пеленой всюду суша. */
        Water DRY = (dx, dz) -> 0;

        double at(double dx, double dz);
    }

    private final Detonation d;
    /** Докуда таблица по земле (где пыль тоньше {@link #GONE_DEPTH} и кончается зона пожаров), блоки. */
    private final double reach;
    /** Радиус зоны пожаров по земле, блоки (0 — пожаров нет). */
    private final double fireReach;
    /** По узлам — слой пыли ({@link DustModel}). */
    private final DustModel.Column[] columns = new DustModel.Column[BINS];
    /**
     * По узлам: приход фронта (тики), разрушение 0..1 (бумага), верх, до которого дорастает пыль (блоки; под ним мгла),
     * доля зоны пожаров 0..1, срыв брызг над водой 0..1 и высота срыва (блоки).
     */
    private final double[] arrival = new double[BINS], destroyed = new double[BINS], ceiling = new double[BINS],
            fire = new double[BINS], spray = new double[BINS], sprayFrom = new double[BINS];
    /**
     * Вода под эпицентром 0..1 ({@code NukeWater.under}: чанк, плитка карты или догадка по уровню моря) и насколько подрыв
     * «водяной» ({@link WaterModel#wetBurst} × она) — пишет тик клиента ({@link #water}); до решения — суша.
     */
    private double under, wet;
    /** Когда пыль осела везде (толща тоньше {@link #GONE_DEPTH}) — тики после подрыва. */
    private final double dustEnd;
    /** Когда мгла гаснет по прошедшим тикам — тики после подрыва (утро, если часы мира идут ровно). */
    private final long hazeEnd;
    /**
     * Когда мгла гаснет на самом деле — тики после подрыва: не позже {@link #hazeEnd} и утра по часам мира
     * ({@link #daylight}). Пишет тик клиента, читает и кадр — один поток.
     */
    private double end;
    /** Часы мира в миг подрыва: игровое время и время суток. */
    private final long burstGame, burstDay;
    /** Прошлый замер часов мира ({@link #daylight}): игровое время и время суток; до первого — миг подрыва. */
    private long lastGame, lastDay;
    /** Прошлый замер — тиком раньше на глазах у клиента, а не миг подрыва (вход в мир, перемотка повтора). */
    private boolean watched;

    /**
     * @param dayTime время суток мира в миг подрыва (тики, как {@code Level.getDayTime}): мгла держится до утра
     */
    public PallField(Detonation d, long dayTime) {
        this.d = d;
        this.hazeEnd = hazeEndTicks(dayTime);
        this.end = hazeEnd;
        this.burstGame = d.gameTime();
        this.burstDay = dayTime;
        this.lastGame = burstGame;
        this.lastDay = burstDay;
        double hob = Math.max(0, d.hobMetres()), y = d.yieldKt();
        DustModel.Surface surface = DustModel.Surface.REFERENCE;
        double dustM = DustModel.reach(hob, y, d.surface(), d.visibility(), surface, GONE_DEPTH);
        // сервер не поджигает (Detonation.fires) — зоны пожаров нет: ни мглы над ними, ни их зарева, ни шторма
        double ignite = !d.fires() ? 0 : groundRange(ThermalModel.rangeForFluence(ThermalModel.IGNITE, y, d.surface(), d.visibility()), hob);
        this.fireReach = d.blocks(ignite);
        this.reach = Math.max(1, d.blocks(Math.max(dustM, ignite)) * 1.02);
        double last = 0;
        for (int i = 0; i < BINS; i++) {
            double rm = d.metres(radius(i)), slant = Math.hypot(rm, hob);
            arrival[i] = d.arrivalTicks(d.blocks(slant));
            double wind = d.groundWind(radius(i));
            destroyed[i] = DustModel.destroyed(BlastModel.psi(d.groundOverpressureKpa(radius(i))));
            spray[i] = WaterModel.spray(wind);
            sprayFrom[i] = Math.max(1, d.blocks(WaterModel.sprayHeight(wind)));
            columns[i] = DustModel.column(rm, hob, y, d.surface(), d.visibility(), surface);
            ceiling[i] = Math.max(MIN_TOP_BLOCKS, d.blocks(columns[i].ceiling()));
            fire[i] = ignite <= 0 ? 0 : 1 - smooth(ignite * 0.85, ignite * 1.05, rm);
            last = Math.max(last, arrival[i] + 20 * d.scale() * settledBy(columns[i]));
        }
        this.dustEnd = last;
    }

    /** Ниже стольких блоков пелена не бывает (малый масштаб удара): иначе её не видно. */
    static final double MIN_TOP_BLOCKS = 6;

    private static double groundRange(double slant, double hob) {
        return Math.sqrt(Math.max(0, slant * slant - hob * hob));
    }

    /**
     * Через сколько секунд модели после фронта слой тоньше {@link #GONE_DEPTH} (толща после фазы волны только убывает), до
     * секунды: пелену строит поток кадра в миг подрыва.
     */
    private static double settledBy(DustModel.Column c) {
        double positive = c.positive();
        if (c.depth(positive) < GONE_DEPTH) return positive;
        double lo = positive, hi = 6 * 3600;
        if (c.depth(hi) >= GONE_DEPTH) return hi;
        while (hi - lo > 1) {
            double m = 0.5 * (lo + hi);
            if (c.depth(m) >= GONE_DEPTH) lo = m;
            else hi = m;
        }
        return hi;
    }

    /** Подрыв поля. */
    public Detonation detonation() {
        return d;
    }

    /** Докуда по земле от эпицентра (до сноса ветром) есть пыль или мгла, блоки. */
    public double reach() {
        return reach;
    }

    /** Радиус зоны поджига по земле, блоки (0 — пожаров нет): над ней мгла, в ней огненный шторм ({@link FireStorm}). */
    double fireReach() {
        return fireReach;
    }

    /** Когда мгла гаснет (утро) — тики после подрыва; к нему гаснет и огненный шторм. */
    double end() {
        return end;
    }

    /** Верх мглы над пожарами, блоки (у 15 кт — 260 м): до него встают столбы дыма шторма. */
    double hazeTop() {
        return d.blocks(HAZE_TOP_M * Math.cbrt(d.yieldKt() / 15));
    }

    /**
     * Вода под эпицентром решена ({@code NukeWater}): {@code under} — доля воды там 0..1 (её же карта {@link PallGround}
     * берёт там, где ничего не знает), {@code wet} — насколько подрыв «водяной» ({@link WaterModel#wetBurst} × она): от
     * него базисная волна.
     */
    void water(double under, double wet) {
        this.under = under;
        this.wet = wet;
    }

    /** Вода под эпицентром 0..1 ({@link #water}). */
    double under() {
        return under;
    }

    /** Радиус узла i, блоки (середины узлов от 0 до {@link #reach}). */
    double radius(int i) {
        return (i + 0.5) / BINS * reach;
    }

    /** Слой пыли узла i ({@link DustModel}). */
    DustModel.Column layer(int i) {
        return columns[i];
    }

    /**
     * Тик клиента: часы мира — игровое время {@code gameTime} и время суток {@code dayTime}; {@code ticks} — тики после
     * подрыва. Пока часы идут ровно, мгла ждёт утра, выбранного в миг подрыва ({@link #hazeEndTicks}: подрыв на рассвете —
     * до следующего). Прыгнули (сон, {@code /time add}, {@code /time set}) — утро заново по времени суток после прыжка:
     * уже день, а был ночью или часы прошли через восход, — мгла гаснет; иначе — к ближайшему восходу, но не раньше
     * 5 минут после подрыва ({@link #HAZE_MIN_TICKS}). Позже, чем ждала до прыжка, мгла не гаснет. Часы стоят
     * ({@code doDaylightCycle false}) — гаснет по прошедшим тикам.
     * <p>
     * Прыжок — сдвиг времени суток против игрового времени: оба приходят одним пакетом времени и вместе идут тиками
     * клиента. Первый замер после входа в мир и перемотки повтора сравнивается с мигом подрыва (время суток подрыва —
     * с сервера, {@link Detonation#dayTime}): прыжок, которого клиент не видел, — тот же, что видели все, только уже без
     * минуты угасания (он был раньше). Кроме {@code /time set} назад на время суток позже подрыва: для вошедшего оно
     * отстало от игрового, как у стоявших часов, и отличить их нельзя — мгла у него ждёт утра от подрыва, дольше, чем
     * у видевших прыжок.
     */
    public void daylight(double ticks, long gameTime, long dayTime) {
        long shift = (dayTime - gameTime) - (lastDay - lastGame);
        // назад — только если ушло назад и время суток: у стоящих часов оно отстаёт от игрового, но не прыгает
        if (shift > SKIP_TICKS || shift < -SET_TICKS && dayTime - lastDay < -SET_TICKS) {
            // по времени суток, а не по числу часов: /time set ставит их как есть, и в мире после первых суток
            // «назад» бывает и на ночь позже прежней (85000 → 18000 — с вечера на полночь)
            boolean dawn = day(dayTime) && (!day(lastDay) || Math.floorDiv(dayTime, DAY) > Math.floorDiv(lastDay, DAY));
            double by = dawn ? ticks : Math.max(ticks + Math.floorMod(-dayTime, DAY), HAZE_MIN_TICKS);
            // на глазах — раньше не скачком: гаснет не быстрее чем за минуту (уже гаснет — как гасла)
            if (by < end) end = Math.min(end, watched ? Math.max(by, ticks + HAZE_FADE_TICKS) : by);
        }
        lastGame = gameTime;
        lastDay = dayTime;
        watched = true;
    }

    /** День — первая половина суток (с 6:00 до 18:00). */
    private static boolean day(long dayTime) {
        return Math.floorMod(dayTime, DAY) < DAY / 2;
    }

    /**
     * Перемотка повтора: часы мира — с другого места записи; утро — снова от мига подрыва, и первый замер сравнивается
     * с ним (прыжок часов до этого места записи — как у вошедшего в мир).
     */
    public void rewound() {
        end = hazeEnd;
        lastGame = burstGame;
        lastDay = burstDay;
        watched = false;
    }

    // ---------------------------------------------------------------- законы (общие с шейдером fx_pall)

    /**
     * Профиль по высоте: доля густоты у земли на высоте {@code x} пелены над землёй под эпицентром (0 — земля, 1 — верх):
     * гуще у земли, к верху мягко тает. Ниже (долина) — как у земли, глубже 0,4 высоты пелены тает к нулю (на всей
     * высоте ниже земли её нет): пыль стекает в долины, но в пещерах под эпицентром её нет. Где за пикселем нет мира
     * (пустота за дальностью прорисовки и за краем LOD Distant Horizons), луч пелены кончается на рельефе под ней
     * ({@link PallGround}, {@code pallFloor} в {@code pall.glsl}). Профиль и головы стены ({@link BlastWall#mean}).
     */
    static double profile(double x) {
        if (x <= 0) return smooth(-1, -0.4, x);
        return (1 - smooth(0.45, 1.0, x)) * (0.55 + 0.45 * Math.exp(-2.5 * x));
    }

    /**
     * Столб над точкой на высоте {@code x} пелены — интеграл {@link #profile} от x до верха в долях высоты: им шейдер
     * считает тень и свет неба внутри пелены. Приближение (с интегралом его сверяет тест: ошибка меньше 0,01).
     */
    static double column(double x) {
        double xa = Math.min(1, Math.max(0, x));
        double above = 0.548 * Math.pow(1 - xa, 2.45) * (1 + 0.83 * xa);
        if (x >= 0) return above;
        // ниже земли: до −0,4 профиль 1, дальше к −1 тает (интеграл гладкой ступени — точно)
        double f = Math.max(0, Math.min(1, (-x - 0.4) / 0.6));
        return above + Math.min(-x, 0.4) + 0.6 * (f - f * f * f + 0.5 * f * f * f * f);
    }

    /**
     * Густота брызг по возрасту (секунды модели после фронта): встают за стеной брызг, отстающей от фронта на
     * {@link WaterModel#SPRAY_LAG}, и редеют e-кратно за {@link #SPRAY_S}; 0 — до того и после {@link #GONE}.
     */
    static double sprayEnvelope(double ageS) {
        if (ageS <= WaterModel.SPRAY_LAG) return 0;
        double e = smooth(WaterModel.SPRAY_LAG, WaterModel.SPRAY_LAG + 2.5, ageS) * Math.exp(-ageS / SPRAY_S);
        return e < GONE ? 0 : e;
    }

    /**
     * Верх брызг по возрасту (секунды модели), в единицах высоты срыва {@code from}: сорванные гребни встают на высоту
     * срыва ({@link WaterModel#sprayHeight}), крупные капли выпадают — верх опускается к {@link #SPRAY_SINK} её. Своя
     * высота, а не слоя пыли: брызги ниже пыли и не растут над нагретой землёй.
     */
    static double sprayTop(double from, double ageS) {
        return from * (SPRAY_SINK + (1 - SPRAY_SINK) * Math.exp(-Math.max(ageS, 0) / SPRAY_S));
    }

    /**
     * Базисная волна по радиусу ({@code u} — доля её радиуса): у края катится вал — гуще, к середине реже, за краем тает
     * (как её клубы: кольцо от половины радиуса до края, гуще у края). {@code pallSurge} в {@code pall.glsl} — то же.
     */
    static double surgeProfile(double u) {
        return (0.35 + 0.65 * smooth(0.2, 0.85, u)) * (1 - smooth(0.9, 1.05, u));
    }

    /** Верх базисной волны по радиусу, доля её высоты: вал у края выше, к середине ниже ({@code pallSurgeTop}). */
    static double surgeTop(double u) {
        return 0.55 + 0.45 * smooth(0.3, 0.95, u);
    }

    /** Мгла по времени: встаёт над пожарами за пару минут после фронта, к утру ({@link #end}) гаснет. */
    double hazeEnvelope(double ageS, double ticks) {
        if (ageS <= HAZE_FROM_S) return 0;
        return smooth(HAZE_FROM_S, HAZE_FULL_S, ageS) * (1 - smooth(end - HAZE_FADE_TICKS, end, ticks));
    }

    /**
     * Сколько игровых тиков после подрыва до утра (время суток 0 — 6:00), но не меньше {@link #HAZE_MIN_TICKS}: город
     * горит до утра; подрыв на рассвете — до следующего.
     */
    static long hazeEndTicks(long dayTime) {
        long toMorning = Math.floorMod(-dayTime, 24000L);
        while (toMorning < HAZE_MIN_TICKS) toMorning += 24000;
        return toMorning;
    }

    // ---------------------------------------------------------------- таблица для шейдера

    /** Ослабление 1/м → 1/блок (блок — 1/scale метра). */
    private double perBlock(double sigmaM) {
        return sigmaM / d.scale();
    }

    /** Настоящие секунды узла i в момент {@code ticks} после подрыва (тики клиента): от прихода фронта — мгла и пожары. */
    private double age(int i, double ticks) {
        return (ticks - arrival[i]) / 20.0;
    }

    /** Секунды модели узла i в момент {@code ticks}: от прихода фронта в игре — возраст пыли и брызг, как у головы стены. */
    private double seconds(int i, double ticks) {
        return (ticks - arrival[i]) / (20.0 * d.scale());
    }

    /** Строк таблицы на подрыв: пыль, брызги, мгла — по порядку. */
    public static final int ROWS = 3;
    static final int DUST_ROW = 0, SPRAY_ROW = 1, HAZE_ROW = 2;

    /**
     * Строка таблицы в момент {@code ticks} после подрыва (тики клиента): по {@link #BINS} узлам 12 чисел — пыль
     * ({@link #dust}), брызги ({@link #spray}) и мгла ({@link #haze}). Пишет в {@code out} (длина ≥ 12·BINS): сначала все
     * узлы пыли, потом брызг, потом мглы. {@code land}, {@code water} — есть ли под пеленой суша и вода
     * ({@link PallGround}): чего нет, того и в таблице нет — пыли и мглы над открытым морем, брызг над сушей (шейдер их и
     * так убрал бы по карте, но слот без них — пустой, и подрыв в море не тратит на невидимую пыль ни одного прохода, а
     * над сушей шейдер не читает карту воды).
     */
    public void row(double ticks, boolean land, boolean water, float[] out) {
        double[] v = new double[4];
        for (int i = 0; i < BINS; i++) {
            dust(i, ticks, v);
            if (!land) v[0] = 0;
            for (int k = 0; k < 4; k++) out[4 * (DUST_ROW * BINS + i) + k] = (float) v[k];
            spray(i, ticks, v);
            if (!water) v[0] = 0;
            for (int k = 0; k < 4; k++) out[4 * (SPRAY_ROW * BINS + i) + k] = (float) v[k];
            haze(i, ticks, v);
            // мглы и пожаров под ней над открытым морем нет (доля сноса пыли — для брызг)
            if (!land) v[0] = v[2] = 0;
            for (int k = 0; k < 4; k++) out[4 * (HAZE_ROW * BINS + i) + k] = (float) v[k];
        }
    }

    /**
     * Доля пыли узла i в таблице (пелена) против головы стены в момент {@code ticks}: пыль моложе положительной фазы на
     * месте — голова, за полфазы место переходит к пелене ({@link BlastWall#handoff}). Где на земле огненный шар
     * ({@link FireballModel#groundRadius}, как у головы — {@link BlastWall#underBall}), пыли нет: там шар, пелена встаёт
     * из-под него, когда он всплывает.
     */
    private double pallShare(int i, double ticks) {
        double t = ticks / (20.0 * d.scale());
        if (d.metres(radius(i)) < FireballModel.groundRadius(t, Math.max(0, d.hobMetres()), d.yieldKt(), d.surface())) return 0;
        return 1 - BlastWall.handoff(seconds(i, ticks) / columns[i].positive());
    }

    /**
     * Пыль узла i: ослабление у земли (1/блок) — толща слоя ({@link DustModel.Column#depth}) на его столб (0,548 верха),
     * без той, что ещё в голове стены; верх слоя (блоки); доля дыма в ней 0..1 (над пожарами пыль темнеет от сажи:
     * пожары разгораются минуты — первые полминуты пелена бурая от пыли, к пятой минуте над огнём серая); насколько слой
     * перемешан ({@link #mixed}).
     */
    void dust(int i, double ticks, double[] out) {
        double age = seconds(i, ticks), depth = columns[i].depth(age);
        out[1] = Math.max(d.blocks(columns[i].height(age)), MIN_TOP_BLOCKS);
        // столб профиля — column(0) верха: толща над землёй ровно та, что у слоя, и при нижнем пределе верха
        out[0] = depth < GONE_DEPTH ? 0 : depth / (column(0) * out[1]) * pallShare(i, ticks);
        out[2] = fire[i] * smooth(30, 300, age(i, ticks));
        out[3] = mixed(age - columns[i].positive());
    }

    /**
     * Насколько слой перемешан через {@code afterS} секунд модели после фазы волны, 0..1: клочья и просветы молодого слоя
     * (у фронта его рвут вихри пограничного слоя) расходятся за оборот вихря слоя h/w*. Слой встаёт над нагретой землёй со
     * скоростью w* за {@link DustModel#RISE_S} до своей высоты h — оборот вихря и есть это время (оценка: w* — 1,5–4 м/с,
     * h — 70–400 м у 15 кт, обзор §5), e-кратно.
     */
    static double mixed(double afterS) {
        return afterS <= 0 ? 0 : 1 - Math.exp(-afterS / DustModel.RISE_S);
    }

    /**
     * Клубы пелены в перемешанном слое: множитель густоты по шуму {@code shape} (в среднем 1) — колебания убывают от
     * клочьев к {@link #MIXED} по {@link #mixed}; среднее остаётся 1. Копия — {@code pallMixed}.
     */
    static double clumps(double shape, double mixed) {
        return 1 + (shape - 1) * (1 - (1 - MIXED) * mixed);
    }

    /**
     * Брызги узла i над водой: ослабление у воды (1/блок; там, где под пеленой вода, и без того, что ещё в голове стены),
     * их верх (блоки, {@link #sprayTop}); 0, 0. Свой слой: брызги не смешаны с пылью и ниже её.
     */
    void spray(int i, double ticks, double[] out) {
        double age = seconds(i, ticks);
        out[0] = spray[i] <= 0 ? 0 : perBlock(SIGMA_SPRAY) * spray[i] * sprayEnvelope(age) * pallShare(i, ticks);
        out[1] = Math.max(sprayTop(sprayFrom[i], age), 1);
        out[2] = out[3] = 0;
    }

    /**
     * Базисная волна в момент {@code ticks} после подрыва: в {@code out} — радиус и верх (блоки) и ослабление в самой
     * густой её части (1/блок; по радиусу — {@link #surgeProfile}, верх — {@link #surgeTop}). Законы и часы — как у
     * столба, из которого она падает (секунды модели, {@link WaterModel}), густота — × насколько подрыв «водяной»; тоньше
     * {@link #GONE} её нет. Над чем она катится, не важно: это вода, поднятая у эпицентра. False — волны нет (в {@code out}
     * нули).
     */
    public boolean surge(double ticks, double[] out) {
        out[0] = out[1] = out[2] = 0;
        if (wet <= 0 || ticks <= 0) return false;
        double t = ticks / (20 * d.scale()), y = d.yieldKt();
        double a = WaterModel.surgeDensity(t, y) * wet, r = WaterModel.surgeRadius(t, y);
        if (a < GONE || r <= 0) return false;
        out[0] = d.blocks(r);
        out[1] = Math.max(MIN_TOP_BLOCKS, d.blocks(WaterModel.surgeHeight(t, y)));
        out[2] = perBlock(SIGMA_SURGE) * a;
        return true;
    }

    /**
     * Доля сноса {@link #drift} у пыли узла i в момент {@code ticks}: её возраст на время после подрыва — ветер несёт её
     * с прихода фронта. У фронта — 0: пыль, поднятая только что, стоит там, где её подняло, — край пелены и голова стены
     * пыли идут по фронту сервера (звук, тряска, отброс, обломки), в любую сторону от эпицентра, и с ветром.
     */
    double share(int i, double ticks) {
        return ticks <= 0 ? 0 : Math.max(0, Math.min(1, (ticks - arrival[i]) / ticks));
    }

    /**
     * Откуда подняло пыль и брызги, которые в момент {@code ticks} в точке (dx, dz) от эпицентра (блоки): точка минус их
     * снос ({@link #share} — по узлу самой точки: доля меняется на длине пути фронта, снос — на порядки короче). В
     * {@code out} — x, z. Так же — {@code pallField} в {@code pall.glsl}.
     */
    public void source(double dx, double dz, double ticks, double[] out) {
        drift(ticks, out);
        double k = share(bin(Math.hypot(dx, dz)), ticks);
        out[0] = dx - out[0] * k;
        out[1] = dz - out[1] * k;
    }

    /**
     * Мгла узла i (по расстоянию от оси шлейфа): ослабление (1/блок), высота (блоки; не ниже верха, до которого
     * дорастает пыль), свет пожаров снизу 0..1; четвёртое — доля сноса пыли узла i ({@link #share}: у шейдера строка
     * мглы читается у точки до сноса).
     */
    void haze(int i, double ticks, double[] out) {
        double age = age(i, ticks);
        out[0] = perBlock(SIGMA_HAZE) * fire[i] * hazeEnvelope(age, ticks);
        out[1] = Math.max(ceiling[i], hazeTop() * (0.5 + 0.5 * smooth(HAZE_FROM_S, HAZE_FULL_S, age)));
        out[2] = fire[i] * fireLight(age, ticks);
        out[3] = share(i, ticks);
    }

    /** Свет пожаров снизу 0..1: разгораются за минуту после волны, к утру гаснут. */
    double fireLight(double ageS, double ticks) {
        if (ageS <= 0) return 0;
        return smooth(5, 60, ageS) * (1 - smooth(end - HAZE_FADE_TICKS, end, ticks));
    }

    /** Свет пожаров под грибом 0..1 (у эпицентра; {@link #fireLight}): зоны пожаров нет — 0. */
    double fireBelow(double ticks) {
        return fireReach > 0 ? fireLight(age(0, ticks), ticks) : 0;
    }

    /**
     * Есть ли в момент {@code ticks} хоть что-то от пелены: мгла (только над пожарами), пыль — пока фронт идёт по
     * таблице и пока не осела везде (брызги оседают раньше пыли), базисная волна.
     */
    public boolean visible(double ticks) {
        return ticks > 0 && (fireReach > 0 && ticks < end || ticks <= arrival[BINS - 1] || ticks < dustEnd || surge(ticks, new double[3]));
    }

    /**
     * Снос ветром к моменту {@code ticks} того, что подняло в миг подрыва, блоки (x, z) — в {@code out}: у базисной волны
     * — весь, у пыли и брызг — доля по их возрасту ({@link #share}).
     */
    public void drift(double ticks, double[] out) {
        double s = d.blocks(d.windSpeed()) * DRIFT * ticks / 20.0;
        out[0] = Math.cos(d.windDir()) * s;
        out[1] = Math.sin(d.windDir()) * s;
    }

    /**
     * Докуда по земле от эпицентра может быть пелена в момент {@code ticks}, блоки: таблица (или базисная волна, если она
     * шире), снос пыли (пока она не осела или пока катится волна — дальше сносить нечего) и шлейф мглы, в любую сторону.
     * Рамка слота ({@link NukePall}) и карта рельефа под пеленой ({@link PallGround}).
     */
    public double extent(double ticks) {
        double[] s = new double[3];
        boolean surge = surge(ticks, s);
        return atSource(ticks, surge, s) + d.blocks(d.windSpeed()) * DRIFT * (surge ? ticks : Math.min(ticks, dustEnd)) / 20.0;
    }

    /**
     * {@link #extent} без сноса: таблица (или базисная волна, если она шире) и шлейф мглы — пелена у своего источника, где
     * её подняло. Карта рельефа под пеленой ({@link PallGround}) — на неё и камеру, а не на весь снос.
     */
    public double sourceExtent(double ticks) {
        double[] s = new double[3];
        return atSource(ticks, surge(ticks, s), s);
    }

    private double atSource(double ticks, boolean surge, double[] s) {
        return (surge ? Math.max(reach, 1.05 * s[0]) : reach) + plume(ticks);
    }

    /** Длина шлейфа мглы по ветру к моменту {@code ticks}, блоки: растёт с ветром, не длиннее {@link #PLUME_MAX} зон пожаров. */
    public double plume(double ticks) {
        return Math.min(PLUME_MAX * fireReach, d.blocks(d.windSpeed()) * ticks / 20.0);
    }

    /** Мгла вдоль шлейфа редеет: в конце шлейфа длины {@code len} — вдвое ({@code along} — от эпицентра по ветру). */
    static double plumeThin(double along, double len) {
        return 1 - 0.5 * along / Math.max(len, 1);
    }

    /** Край таблицы (u — доля {@link #reach}): к нему поле тает, дальше — ноль. */
    static double edge(double u) {
        return 1 - smooth(0.96, 1, u);
    }

    /**
     * Ослабление в точке без шума (среднее по шуму — 1), 1/блок: пыль и брызги (снесённые ветром с того мига, как их
     * подняло, {@link #source}; что из них — по воде там, откуда их подняло), мгла (шлейф по ветру; над водой у её источника пожаров нет) и базисная волна — так же, как
     * {@code pallField} в {@code pall.glsl} по таблице. Точка — от эпицентра по земле (dx, dz, блоки) и высота над землёй
     * под эпицентром (блоки); {@code floorUp} — рельеф под ней над землёй под эпицентром (блоки, карта {@link PallGround}):
     * над холмом слой — от его склона, в долине — от земли под эпицентром ({@link #profile}); {@code water} — доля воды под
     * пеленой ({@link PallGround}). Без головы стены ({@link BlastWall#sigma}). Для того, что у камеры: звук, пепел, докуда
     * идёт луч пелены.
     */
    public double sigma(double dx, double dz, double above, double floorUp, double ticks, Water water) {
        double[] l = new double[LAYERS];
        layers(dx, dz, ticks, water, l);
        double h = above - Math.max(floorUp, 0);
        return l[0] * profile(h / l[2]) + l[1] * profile(h / l[7]) + l[3] * profile(h / l[4]) + l[5] * profile(h / l[6]);
    }

    /** Из {@link #sigma} — водяная пыль: брызги и базисная волна, 1/блок. */
    public double mist(double dx, double dz, double above, double floorUp, double ticks, Water water) {
        double[] l = new double[LAYERS];
        layers(dx, dz, ticks, water, l);
        double h = above - Math.max(floorUp, 0);
        return l[1] * profile(h / l[7]) + l[5] * profile(h / l[6]);
    }

    /**
     * Толща пелены по вертикали над точкой (dx, dz) от высоты {@code from} до {@code to} (блоки над землёй под эпицентром,
     * {@code to} — хоть бесконечность): интеграл {@link #sigma} по высоте. Сколько пыли над глазом и под ним до земли —
     * докуда идёт луч пелены ({@link NukePall}).
     */
    public double tau(double dx, double dz, double from, double to, double floorUp, double ticks, Water water) {
        if (!(to > from)) return 0;
        double[] l = new double[LAYERS];
        layers(dx, dz, ticks, water, l);
        double lift = Math.max(floorUp, 0), a = from - lift, b = to - lift;
        return l[0] * between(a, b, l[2]) + l[1] * between(a, b, l[7]) + l[3] * between(a, b, l[4]) + l[5] * between(a, b, l[6]);
    }

    /**
     * Интеграл {@link #profile} по высоте от a до b (блоки) у слоя высотой top (блоки): по Симпсону, порознь ниже и выше
     * земли (там излом), — точнее приближения {@link #column} (у тонкого слоя под глазом его ошибка — десятки процентов).
     */
    private static double between(double a, double b, double top) {
        double lo = Math.max(a / top, -1), hi = Math.min(b / top, 1);
        return top * (simpson(lo, Math.min(hi, 0)) + simpson(Math.max(lo, 0), hi));
    }

    private static double simpson(double lo, double hi) {
        if (!(hi > lo)) return 0;
        int n = 32;
        double h = (hi - lo) / n, sum = profile(lo) + profile(hi);
        for (int k = 1; k < n; k++) sum += (k % 2 == 1 ? 4 : 2) * profile(lo + k * h);
        return sum * h / 3;
    }

    /** Чисел в {@link #layers}. */
    private static final int LAYERS = 8;

    /**
     * Слои пелены над точкой (dx, dz) — ослабление у земли (1/блок; на высоте — × {@link #profile} доли верха) и верх
     * (блоки), в {@code out}: пыль, брызги, верх пыли, мгла, её верх, базисная волна, её верх, верх брызг. Слоя нет —
     * ослабление 0, верх 1.
     */
    private void layers(double dx, double dz, double ticks, Water water, double[] out) {
        out[0] = out[1] = out[3] = out[5] = 0;
        out[2] = out[4] = out[6] = out[7] = 1;
        double[] w = new double[2], v = new double[4];
        source(dx, dz, ticks, w);
        double lx = w[0], lz = w[1], ud = Math.hypot(lx, lz) / reach;
        if (ud < 1) {
            int i = bin(ud * reach);
            dust(i, ticks, v);
            double sd = v[0], topD = v[1];
            spray(i, ticks, v);
            double e = edge(ud), wet = sd + v[0] > 0 ? water.at(lx, lz) : 0;
            out[0] = sd * (1 - wet) * e;
            out[1] = v[0] * wet * e;
            out[2] = Math.max(topD, 1);
            out[7] = Math.max(v[1], 1);
        }
        double len = plume(ticks), ux = Math.cos(d.windDir()), uz = Math.sin(d.windDir());
        double along = Math.max(0, Math.min(len, dx * ux + dz * uz));
        double sx = dx - ux * along, sz = dz - uz * along, uh = Math.hypot(sx, sz) / reach;
        if (uh < 1) {
            haze(bin(uh * reach), ticks, v);
            if (v[0] > 0) {
                out[3] = v[0] * (1 - water.at(sx, sz)) * plumeThin(along, len) * edge(uh);
                out[4] = Math.max(v[1], 1);
            }
        }
        double[] s = new double[3];
        if (surge(ticks, s)) {
            drift(ticks, w);
            double u = Math.hypot(dx - w[0], dz - w[1]) / s[0];
            out[5] = s[2] * surgeProfile(u);
            out[6] = Math.max(s[1] * surgeTop(u), 1);
        }
    }

    /**
     * Сколько пепла сыплется на землю в точке 0..1 ({@code NukeAsh}): из оседающей пыли — по тому, что из неё оседает
     * ({@link DustModel.Column#fallout}, во всю силу — {@link #ASH_FULL}), с 15–60 с после волны; из мглы над пожарами —
     * пока горит город (вдоль шлейфа реже). Из брызг и тумана над водой пепла нет: вещество — по воде там, откуда его
     * подняло ({@code water}, как у {@link #sigma}).
     */
    public double ashRate(double dx, double dz, double ticks, Water water) {
        double[] w = new double[2];
        source(dx, dz, ticks, w);
        double ash = 0;
        double lx = w[0], lz = w[1], ud = Math.hypot(lx, lz) / reach;
        if (ud < 1) {
            int i = bin(ud * reach);
            double age = seconds(i, ticks);
            if (columns[i].depth(age) >= GONE_DEPTH) {
                ash = Math.min(1, columns[i].fallout(age) / ASH_FULL) * smooth(15, 60, age(i, ticks)) * edge(ud);
                if (ash > 0) ash *= 1 - water.at(lx, lz);
            }
        }
        double len = plume(ticks), ux = Math.cos(d.windDir()), uz = Math.sin(d.windDir());
        double along = Math.max(0, Math.min(len, dx * ux + dz * uz));
        double sx = dx - ux * along, sz = dz - uz * along, uh = Math.hypot(sx, sz) / reach;
        if (uh < 1) {
            int i = bin(uh * reach);
            double haze = 0.8 * fire[i] * hazeEnvelope(age(i, ticks), ticks) * plumeThin(along, len) * edge(uh);
            if (haze > ash) ash = Math.max(ash, haze * (1 - water.at(sx, sz)));
        }
        return Math.min(1, ash);
    }

    /**
     * Доля клочков бумаги в пепле 0..1: где волна рушила дома (разрушение от 0,3), первые минуты после неё (бумага падает
     * рядом с домами, без сноса); домов на воде нет — по воде в той же точке ({@code water}).
     */
    public double paperRate(double dx, double dz, double ticks, Water water) {
        double r = Math.hypot(dx, dz);
        if (r >= reach) return 0;
        int i = bin(r);
        double age = age(i, ticks);
        double paper = smooth(0.3, 0.7, destroyed[i]) * smooth(5, 20, age) * (1 - smooth(120, 300, age));
        return paper <= 0 ? 0 : paper * (1 - water.at(dx, dz));
    }

    private int bin(double r) {
        return Math.min(BINS - 1, Math.max(0, (int) (r / reach * BINS)));
    }

    static double smooth(double e0, double e1, double x) {
        double f = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return f * f * (3 - 2 * f);
    }
}
