package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.CloudModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

/**
 * Голова слоя пыли ударной волны (DESIGN-nuke §6): самая молодая, низкая и густая часть того же слоя, что потом стоит
 * пеленой ({@link PallField}), — законы одни ({@link DustModel}). Сам фронт не виден — это скачок давления в прозрачном
 * воздухе; ветер за ним ({@link BlastModel#windSpeed}) поднимает с земли пыль и обломки, над водой — брызги
 * ({@link WaterModel#spray}). Своего вещества у волны нет.
 * <p>
 * Голова — клин за фронтом на его путь за положительную фазу ({@link #length}): у переднего края пыли ещё нет, и слой
 * нулевой высоты; назад поднятое растёт как импульс скоростного напора ({@link DustModel#lifted}), а верх — как пыльный
 * пограничный слой Kuhl, u^(5/6) ({@link #top}); поэтому густота у земли — больше всего у самого края, на десятой
 * части клина (в 2,6 раза гуще, чем в его конце, {@link #shape}), а столб над землёй растёт назад. За клином пыль
 * остаётся слоем пелены (её таблица — та же пыль, {@link PallField}): голова отдаёт ей место на полдлины клина
 * ({@link #handoff}). Где ветер больше не поднимает пыль, густоты у края нет, и голова сходит на нет, отрываясь от фронта,
 * — пыль стоит (MET: «перестала идти по земле на 945 м»).
 * <p>
 * Клин — объём в проходе пелены ({@code PallPass}: {@code pallWall*} в {@code pall.glsl}, числа слота — {@link #slot}),
 * с вихрями слоя (шум, повёрнутый на угол вала, {@link #rollPhase}). Метры и секунды модели; в мир — через
 * {@link Detonation#blocks}.
 */
public final class BlastWall {
    /**
     * Голова в момент: передний край пыли (блоки от эпицентра по земле), верх клина (блоки) и густота 0..1 против самой
     * густой пыли пелены прежде ({@link PallField#SIGMA_DENSE}, видно на 40 м; 0 — головы нет).
     */
    public record Wall(double head, double height, double density) {
        static final Wall NONE = new Wall(0, 0, 0);
    }

    /** Радиус вала — доля верха клина: вихри слоя — его толщины (Kuhl §3); им вал крутится. */
    static final double ROLL = 0.42;
    /** Самый быстрый оборот вала, рад/с: у эпицентра воздух за фронтом — сотни метров в секунду, вал мельтешил бы. */
    static final double ROLL_MAX = 1.5;
    /** Плитка шума клубов головы — доля верха клина: клубы (четверть плитки) — около пятой его части. */
    static final double TILE = 0.9;
    /**
     * Клубы головы — две фазы шума (Neyret 2003): у каждой окружность головы в плитках постоянна, пока голова не вырастет
     * в столько раз, потом фаза начинается с новыми клубами, а другая в это время — в полную силу. Иначе клубы на
     * растущем кольце либо скользили бы вдоль него со скоростью фронта, либо растягивались в разы.
     */
    static final double PHASE_GROWTH = 1.25;
    /** Голова отдаёт место пелене от конца клина (доля его длины) до этой доли ({@link #handoff}). */
    static final double HANDOFF_FROM = 1, HANDOFF_TO = 1.5;
    /** Головы нет, когда ослабление в ней слабее этого, 1/м (видно на 4 км). */
    static final double GONE = 1e-3;
    /** Узлов таблицы слоя по дальности. */
    private static final int NODES = 256;
    /** Узлов таблицы угла вала по времени. */
    private static final int ROLL_NODES = 256;
    /** Облако Вильсона: середина оболочки — доля радиуса фронта (за фронтом, в фазе разрежения), полутолщина — доля. */
    static final double WILSON_AT = 0.93, WILSON_WIDTH = 0.06;
    /** Толща облака Вильсона поперёк оболочки в самой густой его поре. */
    static final double WILSON_TAU = 0.5;
    /** Чисел слота стены для шейдера ({@link #slot}): голова (mat4) и облако Вильсона (vec4). */
    public static final int SLOT = 20;

    private final Detonation d;
    /** Высота подрыва над землёй, м; мощность — кубическим корнем от 15 кт (масштаб форм). */
    private final double hob, ys;
    /** Положительная фаза, с модели: столько пыль поднимается за фронтом. */
    private final double positive;
    /** Докуда таблица слоя по земле, м. */
    private final double tableM;
    /**
     * Слой по дальности (узлы от 0 до {@link #tableM}): толща всего поднятого ({@link DustModel.Column#lifted}) и верх к
     * концу фазы волны, м ({@link DustModel.Column#first}).
     */
    private final double[] lifted = new double[NODES], first = new double[NODES];
    /** Влажный воздух — облако Вильсона есть ({@link CloudModel#wilsonCloud}). */
    private final boolean humid;
    /** Угол вала по времени ({@link #rollPhase}): секунды модели и угол, рад. */
    private final double[] rollT = new double[ROLL_NODES + 1], rollAngle = new double[ROLL_NODES + 1];

    public BlastWall(Detonation d) {
        this(d, CloudModel.wilsonCloud(humidity(d)));
    }

    BlastWall(Detonation d, boolean humid) {
        this.d = d;
        this.humid = humid;
        hob = Math.max(0, d.hobMetres());
        ys = Math.cbrt(d.yieldKt() / 15);
        positive = BlastModel.positivePhaseSeconds(d.yieldKt());
        double stop = BlastModel.rangeForOverpressure(BlastModel.kpa(0.2), d.yieldKt());
        tableM = Math.max(1, Math.sqrt(Math.max(0, stop * stop - hob * hob)));
        for (int i = 0; i < NODES; i++) {
            DustModel.Column c = DustModel.column(tableM * i / (NODES - 1), hob, d.yieldKt(), d.surface(), d.visibility(), DustModel.Surface.REFERENCE);
            lifted[i] = c.lifted();
            first[i] = c.first();
        }
        // угол вала — интеграл скорости оборота по времени (средней точкой), узлы гуще в начале
        double end = CloudPuffs.lifeSeconds(d);
        for (int i = 1; i <= ROLL_NODES; i++) {
            double f = (double) i / ROLL_NODES;
            rollT[i] = end * f * f;
            rollAngle[i] = rollAngle[i - 1] + rollSpeed((rollT[i] + rollT[i - 1]) * 0.5) * (rollT[i] - rollT[i - 1]);
        }
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
     * Длина клина в момент t, м: путь фронта в игре за последнюю положительную фазу — за краем пыль моложе фазы, её
     * поднимает ветер волны; в первую фазу — весь путь фронта.
     */
    double length(double t) {
        return Math.max(1e-3, shock(t) - shock(t - positive));
    }

    /** Верх клина в момент t, м: верх слоя к концу фазы волны там, где кончается клин ({@link DustModel.Column#first}). */
    double height(double t) {
        double first = at(this.first, Math.max(0, head(t) - length(t)));
        return first > 0 ? first : this.first[0];
    }

    /** Ослабление у земли в конце фазы волны там, где пыль подняло на {@code groundM}, при верхе {@code top} (1/м). */
    private double sigma(double groundM, double top) {
        return at(lifted, groundM) / (PallField.column(0) * Math.max(top, 1e-3));
    }

    /**
     * Скорость оборота вала, рад/с: воздух за фронтом на радиус вала, не быстрее {@link #ROLL_MAX}. Фронт в игре вдали
     * медленнее настоящего ({@code FrontProfile}), а вал крутит сам воздух у головы — его скорость настоящая.
     */
    double rollSpeed(double t) {
        return Math.min(ROLL_MAX, wind(head(t)) / Math.max(1, ROLL * height(t)));
    }

    /** Ветер сразу за фронтом на {@code groundM} от эпицентра по земле, м/с. */
    double wind(double groundM) {
        return BlastModel.windSpeed(BlastModel.overpressureKpa(Math.max(1, Math.hypot(groundM, hob)), d.yieldKt()));
    }

    /** Угол вала в момент t (секунды модели), рад: растёт — у переднего края пыль идёт вверх, поверху — назад. */
    double rollPhase(double t) {
        if (t <= 0) return 0;
        if (t >= rollT[ROLL_NODES]) return rollAngle[ROLL_NODES] + rollSpeed(t) * (t - rollT[ROLL_NODES]);
        int i = Math.min(ROLL_NODES, Math.max(1, (int) Math.ceil(Math.sqrt(t / rollT[ROLL_NODES]) * ROLL_NODES)));
        while (i > 1 && rollT[i - 1] > t) i--;
        while (i < ROLL_NODES && rollT[i] < t) i++;
        double f = (t - rollT[i - 1]) / Math.max(1e-12, rollT[i] - rollT[i - 1]);
        return rollAngle[i - 1] + f * (rollAngle[i] - rollAngle[i - 1]);
    }

    /**
     * Ослабление пыли у земли в конце фазы волны в момент t (1/м): у переднего края (пыль, которую волна поднимает сейчас)
     * и в конце клина — в {@code into}; ослабление брызг над водой — третьим. Вернёт наибольшее.
     */
    double sigmas(double t, double[] into) {
        double edge = head(t), len = length(t), top = height(t);
        into[0] = sigma(edge, top);
        into[1] = sigma(Math.max(0, edge - len), top);
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
     *     <li>[4..7] — угол вала (рад); брызги над водой: отставание от края пыли, доля верха, ослабление (1/блок);</li>
     *     <li>[8..11] — две фазы клубов: окружность головы в плитках у каждой, вес первой, плитка шума;</li>
     *     <li>[12..15] — номера фаз (у каждой свои клубы); ослабление пыли у земли в конце фазы волны у переднего края и в
     *     конце клина — доли масштаба.</li>
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
            into[4] = (float) (rollPhase(t) % (2 * Math.PI));
            // над водой — брызги: ниже пыли (высота срыва гребней), позади края пыли на путь фронта за SPRAY_LAG
            double u = wind(Math.max(0, head - 0.5 * len));
            into[5] = (float) d.blocks(Math.max(0, head - head(Math.max(0, t - WaterModel.SPRAY_LAG))));
            into[6] = (float) Mth.clamp(WaterModel.sprayHeight(u) / Math.max(1, top), 0.15, 1);
            into[7] = (float) (s[2] / d.scale());
            double psi = Math.log(Math.max(head, 1)) / Math.log(PHASE_GROWTH);
            for (int j = 0; j < 2; j++) {
                double phase = psi + 0.5 * j, n = Math.floor(phase);
                into[8 + j] = (float) around(Math.pow(PHASE_GROWTH, n - 0.5 * j));
                into[12 + j] = (float) (n % 64);
                if (j == 0) into[10] = (float) (1 - Math.abs(2 * (phase - n) - 1));
            }
            into[11] = (float) d.blocks(TILE * top);
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
     * Окружность головы радиуса {@code head} м в плитках шума — целое: плитка повторяется, шва на кольце нет. Фаза клубов
     * держит её, пока голова растёт.
     */
    double around(double head) {
        double top = at(first, head);
        return Math.max(1, Math.round(2 * Math.PI * head / (TILE * (top > 0 ? top : first[0]))));
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

    /** Положительная фаза, с модели: голова — пыль моложе её ({@link PallField} отдаёт ей место). */
    double positive() {
        return positive;
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
