package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.CloudModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.WaterModel;

/**
 * Голова слоя пыли ударной волны (DESIGN-nuke §6): самая молодая, низкая и густая часть того же слоя, что потом стоит
 * пеленой ({@link PallField}), — законы одни ({@link DustModel}). Сам фронт не виден — это скачок давления в прозрачном
 * воздухе; ветер за ним вдоль земли ({@link Detonation#groundWind}) поднимает с земли пыль и обломки, над водой —
 * брызги ({@link WaterModel#spray}). Своего вещества у волны нет.
 * <p>
 * Голова — клин от фронта назад до места, где кончилась положительная фаза (своя на каждой дальности, {@link HobBlast},
 * {@link #length}; у наземного первые ~0,6 с — короче: голова с переходом к пелене не заходит в огненный шар на земле,
 * {@link #underBall}): у переднего края пыли ещё нет, и слой нулевой высоты; назад поднятое растёт как импульс скоростного
 * напора ({@link DustModel#lifted}), а верх — как пыльный пограничный слой Kuhl, u^(5/6) ({@link #top}); поэтому
 * густота у земли — больше всего у самого края, на десятой части клина (в 2,6 раза гуще, чем в его конце,
 * {@link #shape}), а столб над землёй растёт назад. За клином пыль остаётся слоем пелены (её таблица — та же пыль,
 * {@link PallField}): голова отдаёт ей место на полдлины клина ({@link #handoff}). Где ветер больше не поднимает пыль,
 * густоты у края нет, и голова сходит на нет, отрываясь от фронта, — пыль стоит (MET: «перестала идти по земле на
 * 945 м»).
 * <p>
 * Клин — объём в проходе пелены ({@code PallPass}: {@code pallWall*} в {@code pall.glsl}, числа слота — {@link #slot}),
 * с вихрями слоя — шумом в координатах самой пыли ({@link #carried}): клубы встают там, где фронт сорвал пыль с земли, и
 * остаются там, сдвинутые ветром за фронтом на его путь за фазу (десятки метров), а фронт уходит дальше. Против фронта
 * пыль у края идёт вверх и назад — так катится вал. Метры и секунды модели; в мир — через {@link Detonation#blocks}.
 */
public final class BlastWall {
    /**
     * Голова в момент: передний край пыли (блоки от эпицентра по земле), верх клина (блоки) и густота 0..1 против самой
     * густой пыли пелены прежде ({@link PallField#SIGMA_DENSE}, видно на 40 м; 0 — головы нет).
     */
    public record Wall(double head, double height, double density) {
        static final Wall NONE = new Wall(0, 0, 0);
    }

    /**
     * Плитка шума клубов головы — доля верха слоя к концу фазы волны ({@link DustModel.Column#first}, наибольшего по
     * дальности): клубы (четверть плитки) — около пятой его части, вихри слоя — его толщины (Kuhl §3). Плитка у подрыва
     * одна на всё время: шум — в координатах пыли, и плитка, растущая с головой, двигала бы клубы по земле.
     */
    static final double TILE = 0.9;
    /**
     * Путь воздуха за фронтом за положительную фазу на месте — доля U·t₊ (U — ветер сразу за фронтом): скорость частиц
     * по Фридлендеру u(t) = U(1 − t/t₊)e^(−t/t₊), ∫₀^t₊ = U·t₊/e (Glasstone &amp; Dolan §3.57: форма спада; коэффициент
     * спада 1 — у слабых волн, вдали; ближе к эпицентру спад круче, и путь меньше — оценка сверху).
     */
    static final double CARRY = Math.exp(-1);
    /** Голова отдаёт место пелене от конца клина (доля его длины) до этой доли ({@link #handoff}). */
    static final double HANDOFF_FROM = 1, HANDOFF_TO = 1.5;
    /** Головы нет, когда ослабление в ней слабее этого, 1/м (видно на 4 км). */
    static final double GONE = 1e-3;
    /** Узлов таблицы слоя по дальности. */
    private static final int NODES = 256;
    /** Облако Вильсона: середина оболочки — доля радиуса фронта (за фронтом, в фазе разрежения), полутолщина — доля. */
    static final double WILSON_AT = 0.93, WILSON_WIDTH = 0.06;
    /** Толща облака Вильсона поперёк оболочки в самой густой его поре. */
    static final double WILSON_TAU = 0.5;
    /** Чисел слота стены для шейдера ({@link #slot}): голова (mat4) и облако Вильсона (vec4). */
    public static final int SLOT = 20;

    private final Detonation d;
    /** Высота подрыва над землёй, м; мощность — кубическим корнем от 15 кт (масштаб форм). */
    private final double hob, ys;
    /** Докуда таблица слоя по земле, м. */
    private final double tableM;
    /**
     * Слой по дальности (узлы от 0 до {@link #tableM}): толща всего поднятого ({@link DustModel.Column#lifted}) и когда на
     * месте кончается положительная фаза — приход фронта в игре и фаза на месте ({@link DustModel.Column#positive}), с
     * модели (не убывает с дальностью).
     */
    private final double[] lifted = new double[NODES], phaseEnd = new double[NODES];
    /** Положительная фаза в конце таблицы, с модели: за таблицей конец клина — путь фронта за неё. */
    private final double lastPositive;
    /** Влажный воздух — облако Вильсона есть ({@link CloudModel#wilsonCloud}). */
    private final boolean humid;
    /** Плитка шума клубов головы, м ({@link #TILE}). */
    private final double tileM;

    public BlastWall(Detonation d) {
        this(d, CloudModel.wilsonCloud(humidity(d)));
    }

    BlastWall(Detonation d, boolean humid) {
        this.d = d;
        this.humid = humid;
        hob = Math.max(0, d.hobMetres());
        ys = Math.cbrt(d.yieldKt() / 15);
        tableM = Math.max(1, HobBlast.rangeFor(0.2, hob, d.yieldKt()));
        double positive = 0, first = 0;
        for (int i = 0; i < NODES; i++) {
            double g = tableM * i / (NODES - 1);
            DustModel.Column c = DustModel.column(g, hob, d.yieldKt(), d.surface(), d.visibility(), DustModel.Surface.REFERENCE);
            lifted[i] = c.lifted();
            positive = c.positive();
            first = Math.max(first, c.first());
            phaseEnd[i] = arrival(g) + positive;
            if (i > 0) phaseEnd[i] = Math.max(phaseEnd[i], phaseEnd[i - 1]);
        }
        lastPositive = positive;
        tileM = TILE * first;
    }

    /** Влажность в эпицентре (облако Вильсона видно во влажном воздухе): из биома на клиенте, над водой — 1. */
    static double humidity(Detonation d) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return 0.5;
        // блок поверхности: у суши — под первым воздухом, у воды с прицела (гладь, 62,889) — сама вода
        var pos = new net.minecraft.core.BlockPos(Mth.floor(d.burst().x), NukeWater.surfaceBlock(d.groundY()), Mth.floor(d.burst().z));
        if (!level.getFluidState(pos).isEmpty()) return 1;
        return level.getBiome(pos).value().getModifiedClimateSettings().downfall();
    }

    /** Таблица слоя на {@code groundM} м от эпицентра (линейно между узлами; дальше таблицы — пыли нет). */
    private double at(double[] table, double groundM) {
        double x = Math.max(0, groundM) / tableM * (NODES - 1);
        if (x >= NODES - 1) return 0;
        int i = (int) x;
        return Mth.lerp(x - i, table[i], table[i + 1]);
    }

    /** Приход фронта в игре на {@code groundM} м от эпицентра по земле, секунды модели. */
    private double arrival(double groundM) {
        return d.arrivalTicks(d.slantOf(d.blocks(groundM))) / (20 * d.scale());
    }

    /** Фронт по земле в момент t (секунды модели), м: наклонная дальность фронта в игре — по земле. */
    double shock(double t) {
        if (t <= 0) return 0;
        double front = d.metres(d.frontRadius(t * 20 * d.scale()));
        return Math.sqrt(Math.max(0, front * front - hob * hob));
    }

    /** Передний край пыли по земле в момент t (секунды модели), м: сам фронт в игре — пыль встаёт сразу за ним. */
    double head(double t) {
        return shock(t);
    }

    /**
     * Длина клина в момент t, м: от фронта в игре до места, где положительная фаза на месте уже кончилась ({@link #tail}),
     * — за краем пыль моложе своей фазы, её поднимает ветер волны; пока фаза не кончилась нигде — весь путь фронта.
     * Голова с переходом к пелене ({@link #HANDOFF_TO} длин) — не в огненном шаре на земле ({@link #underBall}): там шар,
     * а не пыль, и клин короче — до места, где пыль ещё моложе своей фазы ({@link #backAge}).
     */
    double length(double t) {
        double shock = shock(t), len = shock - tail(t), ball = underBall(t);
        if (ball > 0) len = Math.min(len, (shock - ball) / HANDOFF_TO);
        return Math.max(1e-3, len);
    }

    /**
     * Докуда по земле от эпицентра в момент t (секунды модели) лежит огненный шар, м; 0 — земли он не касается
     * ({@link FireballModel#groundRadius}: тот же шар, что в грибе). У наземного 15 кт фронт выходит из шара к ~0,13 с
     * (первые 2 мс он на метр впереди шара — голова там в сантиметр высотой), клин доходит до конца фазы к ~0,6 с, шар
     * отрывается от земли к ~4,4 с; воздушного (592 м) шар земли не касается.
     */
    double underBall(double t) {
        return FireballModel.groundRadius(t, hob, d.yieldKt(), d.surface());
    }

    /** Конец клина по земле в момент t (секунды модели), м. */
    private double back(double t) {
        return Math.max(0, shock(t) - length(t));
    }

    /**
     * Возраст пыли в конце клина в момент t, секунды модели: с прихода фронта в игре, не больше положительной фазы на месте
     * (с модели).
     */
    private double backSeconds(double t) {
        double back = back(t);
        return Mth.clamp(t - arrival(back), 0, HobBlast.positivePhaseSeconds(back, hob, d.yieldKt()));
    }

    /**
     * Какая доля положительной фазы на месте прошла к моменту t там, где кончается клин, 0..1: 1 — клин до конца фазы
     * ({@link #tail}). Пока фаза не кончилась нигде, клин — весь путь фронта, и пыль в его конце (у эпицентра) моложе своей
     * фазы; у огненного шара клин короче — тоже. Такая пыль ниже и тоньше: пограничный слой за свежим фронтом начинается
     * с нулевой толщины.
     */
    double backAge(double t) {
        double back = back(t);
        return Mth.clamp((t - arrival(back)) / Math.max(1e-9, HobBlast.positivePhaseSeconds(back, hob, d.yieldKt())), 0, 1);
    }

    /**
     * Докуда по земле к моменту t (секунды модели) положительная фаза кончилась, м: где приход фронта в игре и фаза на
     * месте вместе — не позже t (0 — нигде). За таблицей — путь фронта за фазу в её конце.
     */
    double tail(double t) {
        if (t <= phaseEnd[0]) return 0;
        if (t >= phaseEnd[NODES - 1]) return Math.max(tableM, shock(t - lastPositive));
        int lo = 0, hi = NODES - 1;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (phaseEnd[m] <= t) lo = m;
            else hi = m;
        }
        double f = (t - phaseEnd[lo]) / Math.max(1e-12, phaseEnd[hi] - phaseEnd[lo]);
        return tableM * (lo + Mth.clamp(f, 0, 1)) / (NODES - 1);
    }

    /**
     * Верх клина в момент t, м: верх слоя там, где кончается клин, в возрасте пыли там — пыльный пограничный слой по пути
     * фронта по своей нормали за местом ({@link DustModel#waveTop}; к концу фазы — {@link DustModel.Column#first}).
     */
    double height(double t) {
        // не ниже миллиметра, как длина клина: делители шейдера (smoothstep по верху) не вырождаются
        return Math.max(1e-3, DustModel.waveTop(back(t), hob, d.yieldKt(), backSeconds(t)));
    }

    /** Ослабление у земли в конце фазы волны там, где пыль подняло на {@code groundM}, при верхе {@code top} (1/м). */
    private double sigma(double groundM, double top) {
        return at(lifted, groundM) / (PallField.column(0) * Math.max(top, 1e-3));
    }

    /**
     * Место пыли головы в координатах её самой — откуда её подняло (копия — {@code pallWallOrigin}): точка (rx, rz — от
     * эпицентра по земле, z — над рельефом) за вычетом пути с ветром за фронтом ({@code carried} × {@link #carriedShare} по
     * доле u фазы); у земли воздух стоит, выше идёт по закону 1/7 турбулентного пограничного слоя (z против верха в конце
     * клина {@code top}), вверх — 0,02–0,08 пути (Kuhl §3.4: вертикальная скорость в слое — 0,02 u∞ у дна и 0,04–0,12 у
     * верха). Шум клубов головы — в этих координатах: клубы стоят там, где пыль сорвало, а не едут с фронтом.
     */
    static double[] origin(double rx, double rz, double z, double u, double top, double carried) {
        double zr = Mth.clamp(z / Math.max(top, 0.001), 0, 1);
        double x = carried * carriedShare(u) * Math.pow(zr, 1.0 / 7);
        double len = Math.max(Math.hypot(rx, rz), 1e-3);
        return new double[] {rx - rx / len * x, z - x * (0.02 + 0.06 * zr), rz - rz / len * x};
    }

    /** Ветер сразу за фронтом вдоль земли на {@code groundM} от эпицентра по земле, м/с. */
    double wind(double groundM) {
        return d.groundWind(d.blocks(Math.max(0, groundM)));
    }

    /**
     * Путь пыли с ветром за фронтом в момент t (секунды модели), м: путь воздуха за всю положительную фазу ({@link #CARRY})
     * там, где сейчас середина клина. На своём месте пыль проходит его за фазу — {@link #carriedShare} по её доле;
     * дальше она стоит (ветер фазы разрежения слаб и обратен — без него).
     */
    double carried(double t) {
        double mid = Math.max(0, head(t) - 0.5 * length(t));
        return CARRY * wind(mid) * HobBlast.positivePhaseSeconds(mid, hob, d.yieldKt());
    }

    /**
     * Доля пути с ветром ({@link #carried}), пройденная пылью за долю u положительной фазы с прихода фронта:
     * ∫₀^u (1 − x)e^(−x) dx / e^(−1) = u·e^(1 − u), за фазой — 1. Копия — {@code pallWallCarried}.
     */
    static double carriedShare(double u) {
        if (u <= 0) return 0;
        if (u >= 1) return 1;
        return u * Math.exp(1 - u);
    }

    /**
     * Ослабление пыли у земли в конце фазы волны в момент t (1/м): у переднего края (пыль, которую волна поднимает сейчас)
     * и в конце клина — в {@code into}; ослабление брызг над водой — третьим. Вернёт наибольшее; пока фронт в огненном
     * шаре ({@link #underBall}) — нули: головы ещё нет.
     */
    double sigmas(double t, double[] into) {
        double edge = head(t), len = length(t), top = height(t);
        if (edge <= underBall(t)) {
            java.util.Arrays.fill(into, 0, 3, 0);
            return 0;
        }
        // клин короче фазы (у шара, или фаза ещё нигде не кончилась): пыль в конце подняла лишь долю поднятого за фазу
        // (DustModel.lifted), а верх ниже — слой в возрасте этой пыли (height); у края — та же доля фазы: поднятое у фронта
        // растёт с возрастом линейно
        double age = backAge(t);
        into[0] = age * sigma(edge, top);
        into[1] = DustModel.lifted(age) * sigma(Math.max(0, edge - len), top);
        into[2] = PallField.SIGMA_SPRAY * WaterModel.spray(wind(Math.max(0, edge - 0.5 * len)));
        return Math.max(into[0], Math.max(into[1], into[2]));
    }

    /** Голова в момент t (секунды модели): блоки и густота против самой густой пыли пелены прежде. */
    public Wall wall(double t) {
        double[] s = new double[3];
        if (t <= 0 || sigmas(t, s) < GONE) return Wall.NONE;
        return new Wall(d.blocks(head(t)), d.blocks(height(t)), Math.min(1, Math.max(s[0], s[1]) / PallField.SIGMA_DENSE));
    }

    /**
     * Числа слота стены для {@code pall.glsl} в момент t (секунды модели), блоки и 1/блок; false — ни головы, ни облака
     * Вильсона (в {@code into} нули). Голова — mat4 по столбцам ({@code pallWall*}):
     * <ul>
     *     <li>[0..3] — передний край пыли от эпицентра, длина клина, его верх в конце; ослабление-масштаб (1/блок; 0 — головы
     *     нет);</li>
     *     <li>[4..7] — путь пыли с ветром за фронтом ({@link #carried(double)}); брызги над водой: отставание от края
     *     пыли, доля верха, ослабление (1/блок);</li>
     *     <li>[8..11] — 0, 0, 0; плитка шума клубов ({@link #TILE});</li>
     *     <li>[12..15] — 0, 0; ослабление пыли у земли в конце фазы волны у переднего края и в конце клина — доли
     *     масштаба.</li>
     * </ul>
     * [16..19] — облако Вильсона ({@code pallWilson}): радиус середины оболочки от точки подрыва, полутолщина, ослабление в
     * середине (0 — облака нет), высота точки подрыва над землёй под эпицентром.
     */
    public boolean slot(double t, float[] into) {
        java.util.Arrays.fill(into, 0, SLOT, 0f);
        boolean any = false;
        double[] s = new double[3];
        double ref = t > 0 ? sigmas(t, s) : 0;
        if (ref >= GONE) {
            any = true;
            double head = head(t), len = length(t), top = height(t);
            into[0] = (float) d.blocks(head);
            into[1] = (float) d.blocks(len);
            into[2] = (float) d.blocks(top);
            into[3] = (float) (ref / d.scale());
            into[4] = (float) d.blocks(carried(t));
            // над водой — брызги: ниже пыли (высота срыва гребней), позади края пыли на путь фронта за SPRAY_LAG, но тоже
            // не в огненном шаре на земле: у него — ближе к фронту
            double u = wind(Math.max(0, head - 0.5 * len)), ball = underBall(t);
            double lag = Math.max(0, head - head(Math.max(0, t - WaterModel.SPRAY_LAG)));
            if (ball > 0) lag = Math.min(lag, Math.max(0, head - ball - HANDOFF_TO * len));
            into[5] = (float) d.blocks(lag);
            into[6] = (float) Mth.clamp(WaterModel.sprayHeight(u) / Math.max(1, top), 0.15, 1);
            into[7] = (float) (s[2] / d.scale());
            // плитка шейдера — не мельче блока
            into[11] = (float) Math.max(1, d.blocks(tileM));
            into[14] = (float) (s[0] / ref);
            into[15] = (float) (s[1] / ref);
        }
        double wilson = wilson(t);
        if (wilson > 0) {
            any = true;
            double front = d.metres(d.frontRadius(t * 20 * d.scale())), w = WILSON_WIDTH * front;
            into[16] = (float) d.blocks(WILSON_AT * front);
            into[17] = (float) d.blocks(w);
            // толща поперёк оболочки — WILSON_TAU в самой густой поре: у гауссовой оболочки ∫ = √π · полутолщина · σ
            into[18] = (float) (WILSON_TAU * wilson / (Math.sqrt(Math.PI) * Math.max(1e-3, d.blocks(w))));
            into[19] = (float) d.blocks(hob);
        }
        return any;
    }


    /**
     * Облако Вильсона 0..1 в момент t (секунды модели): во влажном воздухе в фазе разрежения за фронтом воздух
     * расширяется и остывает ниже точки росы — оболочка тумана. Встаёт с 0,15·∛(Y/15) с, тает к 3,5·∛(Y/15) с (у 15 кт —
     * 0,15–3,5 с); в сухом воздухе его нет.
     */
    double wilson(double t) {
        if (!humid) return 0;
        return smooth(0.15 * ys, 0.5 * ys, t) * (1 - smooth(1.5 * ys, 3.5 * ys, t));
    }

    /** Докуда по земле от эпицентра (без сноса) может быть голова в момент t, блоки; 0 — головы нет. */
    public double reach(double t) {
        double[] s = new double[3];
        return t > 0 && sigmas(t, s) >= GONE ? d.blocks(head(t)) : 0;
    }

    /** Докуда от точки подрыва может быть облако Вильсона в момент t, блоки; 0 — облака нет. */
    public double wilsonReach(double t) {
        if (wilson(t) <= 0) return 0;
        return d.blocks((WILSON_AT + 2 * WILSON_WIDTH) * d.metres(d.frontRadius(t * 20 * d.scale())));
    }

    // ---------------------------------------------------------------- законы клина (общие с шейдером: pallWall* в pall.glsl)

    /** Верх клина по доле u его длины за краем — доля верха в конце: u^(5/6) (Kuhl), за клином — весь. Копия {@code pallWallTop}. */
    static double top(double u) {
        return Math.pow(Mth.clamp(u, 0, 1), 5 / 6.0);
    }

    /**
     * Густота у земли по доле u клина — против густоты в его конце: поднятое к этой доле фазы ({@link DustModel#lifted}) в
     * слое верха {@link #top}; столб над землёй растёт назад как поднятое. Самая густая — у u ≈ 0,1 (в 2,6 раза), за клином —
     * 1. Копия {@code pallWallShape}.
     */
    static double shape(double u) {
        if (u <= 0) return 0;
        double x = Math.min(u, 1);
        return DustModel.lifted(x) / Math.pow(Math.max(x, 1e-4), 5 / 6.0);
    }

    /** Доля головы за клином: от {@link #HANDOFF_FROM} до {@link #HANDOFF_TO} его длины место переходит к пелене. Копия {@code pallWallFade}. */
    static double handoff(double u) {
        return 1 - smooth(HANDOFF_FROM, HANDOFF_TO, u);
    }

    /**
     * Доля густоты головы без шума (среднее по клубам) против густоты у земли в конце клина: {@code s} — за передним краем
     * пыли (блоки; впереди — меньше нуля), {@code z} — над рельефом (блоки), {@code len} — длина клина, {@code top} — его
     * верх в конце. По высоте — профиль пелены ({@link PallField#profile}) до верха клина здесь; под рельефом карты — до
     * половины верха (карта грубее мира). Копия {@code pallWallMean}.
     */
    static double mean(double s, double z, double len, double top) {
        double u = s / Math.max(len, 0.001);
        if (u <= 0) return 0;
        double h = top * top(u), k = shape(u) * handoff(u);
        if (z < 0) return k * smooth(-0.5 * top, -0.25 * top, z);
        return k * PallField.profile(z / Math.max(h, 0.001));
    }

    /**
     * Ослабление средней головы (без клубов) в точке, 1/блок — как {@code pallWallAt} и {@code pallWallMean}: {@code slot}
     * — числа {@link #slot}, {@code relX}, {@code relZ} — точка от эпицентра (блоки), {@code up} — над рельефом под ней,
     * {@code wet} — доля воды под ней (над водой голова — брызги: ниже и позади края пыли).
     */
    static double sigma(float[] slot, double relX, double relZ, double up, double wet) {
        if (slot[3] <= 0) return 0;
        double s = slot[0] - Math.hypot(relX, relZ), len = slot[1], top = slot[2];
        if (s < -1 || s > HANDOFF_TO * len + slot[5] || up > top || up < -0.5 * top) return 0;
        double back = slot[5] * wet, at = s - back;
        double k = density(slot, at, wet);
        return k * mean(at, up, len, top * (1 + (slot[6] - 1) * wet));
    }

    /** Ослабление-множитель головы {@code s} блоков за краем (с отставанием брызг): пыль — от края к концу клина, брызги — свои. */
    private static double density(float[] slot, double s, double wet) {
        double u = Mth.clamp(s / Math.max(slot[1], 0.001), 0, 1);
        double dust = slot[3] * (slot[14] + (slot[15] - slot[14]) * u);
        return dust + (slot[7] - dust) * wet;
    }

    /**
     * Толща средней головы по вертикали над точкой от {@code from} до {@code to} (блоки над рельефом под ней; {@code to} —
     * хоть бесконечность): интеграл {@link #sigma} по высоте — по Симпсону порознь под рельефом и над ним (там излом), до
     * верха клина в этом месте. Сколько пыли головы над глазом и под ним — докуда идёт луч пелены ({@link NukePall}).
     */
    static double tau(float[] slot, double relX, double relZ, double from, double to, double wet) {
        if (slot[3] <= 0) return 0;
        double s = slot[0] - Math.hypot(relX, relZ), len = slot[1];
        double full = slot[2] * (1 + (slot[6] - 1) * wet), back = slot[5] * wet;
        double here = full * top((s - back) / Math.max(len, 0.001));
        return simpson(slot, relX, relZ, Math.max(from, -0.5 * full), Math.min(to, 0), wet)
                + simpson(slot, relX, relZ, Math.max(from, 0), Math.min(to, here), wet);
    }

    private static double simpson(float[] slot, double relX, double relZ, double a, double b, double wet) {
        if (!(b > a)) return 0;
        int n = 16;
        double h = (b - a) / n, sum = sigma(slot, relX, relZ, a, wet) + sigma(slot, relX, relZ, b, wet);
        for (int k = 1; k < n; k++) sum += (k % 2 == 1 ? 4 : 2) * sigma(slot, relX, relZ, a + k * h, wet);
        return sum * h / 3;
    }

    static double smooth(double e0, double e1, double x) {
        double f = Mth.clamp((x - e0) / (e1 - e0), 0, 1);
        return f * f * (3 - 2 * f);
    }
}
