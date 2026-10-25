package ua.zentix.incoming.client.fx.smoke;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Облака клиента и сетка дыма у камеры. Источник облака ({@link Source}: облако взрыва, облако старта, дым пожара —
 * {@link #add}) отдаёт гауссовы клубы вещества по своему закону; кто их рисует — решает масштаб. Объём сетки — одна
 * трёхмерная текстура ослабления, которую проход пелены ({@code PallPass}, {@code fx_pall}) проходит своим ходом луча:
 * цена — пиксели кадра лучей, а не сколько дыма (решение Артёма 25.10 — висящий дым налёта объёмом, как пелена ядерки).
 * В объём идёт только устоявшийся дым ({@link Source#volume}: облака взрывов и дым пожаров) и только та доля каждого
 * клуба, что клетка его каскада разрешает и что уже не клубится ({@link #share}); остальное — запечённые клубы слоя
 * эффектов ({@code far/SmokePuffs}, {@link #quads}, {@link #rest}). Молодое облако (метры, клубится) клетка в 8–32 м
 * размазала бы в кубы и мыло (3.5.0: в сетку шло всё) — его рисуют кадры симуляции. Без Minecraft и OpenGL: здесь — кто
 * в кадре, каскады и раскладка; в видеопамять кладёт {@code fx/layer/SmokePass}.
 * <p>
 * <b>Каскады.</b> {@link #CASCADES} кубов по {@link #N}³ клеток вокруг глаза, клетка — {@link #CELL} м (2, 8, 32: у глаза
 * 128 м, дальше 512 м и 1 км), угол куба — кратен клетке (сетка стоит на месте мира, а не ездит с глазом). Клуб
 * (гаусс массы вещества) ложится ровно в один каскад: самый мелкий, где он целиком (три σ) и где он не шире
 * {@link #SPAN} клеток σ, — значит у каждого каскада свой набор клубов, и плотность в точке — сумма каскадов, в которых она
 * есть. Шва на краю каскада нет: клуб у края уже в следующем. Клеткой мельче клуба гаусс не дробится: σ клуба в каскаде
 * — √(σ² + (клетка/2)²), масса та же.
 * <p>
 * <b>Отрезок.</b> Клуб бывает размазан по отрезку от c − a до c + a ({@link Sink#segment}): плотность — среднее гаусса по
 * его центрам на отрезке, ρ = m·G₀·e^(−q⊥²/2)·√(π/2)/(2A)·[erf((A − q∥)/√2) + erf((A + q∥)/√2)] в осях, где σ — единица
 * (A — длина a там, q∥ и q⊥ — вдоль и поперёк него; {@link #line}). След и столб дыма — несколько отрезков, а не сотни
 * клубов цепочкой: шаг цепочки больше σ давал бы бусы.
 * <p>
 * <b>Текстура</b> — RGBA16F (2N × N × N·каскады): по x — две страницы, по y (слои) — высота, по z — z мира. Страница 0:
 * ослабление сажи, пыли и капель (1/м, σ' после дельта-Эддингтона) и Σ ослабление × ΔT (кК); страница 1: Σ ослабление
 * пыли × её альбедо ω' (r, g, b) и Σ ослабление × скорость подъёма (м/с) — по ним шум клубов идёт с веществом.
 * Заполняется каждый кадр: слой за слоем, клуб — прямоугольником клеток, где он есть, сложением (шейдер
 * {@code fx_smoke_fill}); пустые слои не рисуются.
 * <p>
 * <b>Вещество</b> — оптика {@link CloudModel}: сажа (альбедо 0,25, асимметрия 0,5), пыль (альбедо — по цвету грунта,
 * асимметрия 0,6), капли (альбедо ~1, асимметрия 0,85); у шейдера — те же числа ({@code smoke.glsl}, сверяет
 * {@code SmokeGlslTest}).
 */
public final class SmokeGrid {
    /** Клеток по стороне каскада. */
    public static final int N = 64;
    /** Каскадов и размер их клетки, м. */
    public static final int CASCADES = 3;
    /**
     * Клетка 16 м у последнего (а не 32): облака налёта в сотнях метров над глазом (σ 30–50 м в 1–4 мин) последний каскад
     * разрешает (две клетки), и глаз у земли видит их объёмом; дальше 0,5 км дым налёта мал на экране — квадратами.
     */
    public static final float[] CELL = {2, 8, 16};
    /**
     * Клуб шире стольких клеток σ — в каскад крупнее (у последнего — как есть). Вдвое больше отношения клеток соседних
     * каскадов: клуб, что перерос каскад, в следующем — не уже двух его клеток ({@link #resolved} — вся доля), и доля в
     * объёме, пока облако растёт, не скачет.
     */
    public static final float SPAN = 8;
    /** Клуб в каскаде — до стольких σ от центра. */
    public static final float REACH = 3;
    /**
     * Клубов в кадре не больше (ширина таблицы клубов). Не помещаются — уходят источники дальше всех от глаза, целиком
     * ({@link Source#placed}): их рисуют сами (квадраты {@code FarBlasts}), а не половиной облака.
     */
    public static final int MAX_BLOBS = 4096;
    /** Клубов, что источники могут отдать за кадр до раскладки (сверх — источник не в сетке). */
    static final int STAGED = 8 * MAX_BLOBS;
    /** Чисел на клуб в черновике: 16 параметров {@link Sink#segment} и номер источника. */
    private static final int STAGE = 17;
    /**
     * Строк таблицы клубов: центр и σ по горизонтали; σ по вертикали и пиковые ослабления; альбедо пыли и x полуотрезка;
     * ΔT, подъём, y и z полуотрезка.
     */
    public static final int BLOB_ROWS = 4;
    /** Вершин на клуб в слое: два прямоугольника (страницы) по два треугольника. */
    static final int VERTICES = 12;
    /** Температура воздуха, K (как у модели облака). */
    public static final float AIR_K = (float) CloudModel.T_AIR;

    /** Сажа, пыль, капли: альбедо ω' и асимметрия g' после дельта-Эддингтона (f = g²), ослабление на массу σ', м²/кг. */
    public static final float SOOT_W, SOOT_G, DUST_G, DROP_W, DROP_G;
    public static final double SOOT_K, DROP_K;

    static {
        double f = CloudModel.SOOT_G * CloudModel.SOOT_G;
        SOOT_W = (float) ((1 - f) * CloudModel.SOOT_W / (1 - f * CloudModel.SOOT_W));
        SOOT_G = (float) (CloudModel.SOOT_G / (1 + CloudModel.SOOT_G));
        SOOT_K = CloudModel.SOOT_K * (1 - CloudModel.SOOT_W * f);
        DUST_G = (float) (0.6 / 1.6);
        // капли 5 мкм почти не поглощают: ω = 0,9999 (вода в видимом), f = g²
        double fw = CloudModel.DROP_G * CloudModel.DROP_G, ww = 0.9999;
        DROP_W = (float) ((1 - fw) * ww / (1 - fw * ww));
        DROP_G = (float) (CloudModel.DROP_G / (1 + CloudModel.DROP_G));
        DROP_K = CloudModel.DROP_K * (1 - ww * fw);
    }

    /**
     * Источник вещества: облако взрыва (здесь), облако старта, след, дым пожара (их добавляют другие части клиента).
     * Каждый кадр отдаёт свои клубы в мировых координатах; false — иссяк, уходит из сетки.
     */
    public interface Source {
        boolean emit(double seconds, Sink out);

        /**
         * Источник, чей устоявшийся дым идёт в объём ({@link #share}: клуб, который клетка разрешает, у облака — ещё и
         * переставший клубиться): облака взрывов обычного оружия и дым пожаров. Молодое облако и тонкое рисуют квадраты
         * слоя ({@link #quads}) — та доля каждого клуба, что не в объёме.
         */
        default boolean volume() {
            return false;
        }

        /**
         * У источника есть брызги — вода, брошенная вверх и падающая обратно (взрыв на воде): клубы, где капель больше
         * сажи и пыли, рисуются кадрами брызг ({@code FxAtlas.spray6}). Нет — все клубы кадрами дыма: вода облака старта
         * и облака конденсации — туман, что всплывает с газом, а не падает.
         */
        default boolean sprayClubs() {
            return false;
        }

        /** В возрасте seconds: после падения струй у водяного взрыва остаётся туман, ему нужны клубы без полос падения. */
        default boolean sprayClubs(double seconds) {
            return sprayClubs();
        }

        /**
         * Источник сам рисует светящийся газ телом огня (облако взрыва — огненным шаром): его горячие клубы слой эффектов
         * не рисует дымом, а проступают они дымом той же долей, что тело тает ({@code CloudModel.glowShare}). Нет —
         * клубы рисуются дымом при любой температуре (облако старта, дым пожара).
         */
        default boolean drawsGlow() {
            return false;
        }

        /** Зерно клубов: места и кадры квадратов клуба у слоя эффектов. */
        default long seed() {
            return 0;
        }

        /**
         * Во сколько раз вещество выросло с выброса (σ сейчас против σ в начале): кадр запечённого клуба у слоя эффектов —
         * тот, где симуляция выросла во столько же ({@code FxAtlas.puffFrame}).
         */
        default double puffGrowth(double seconds) {
            return 2;
        }

        /**
         * Какая доля клуба с центром на высоте y (мир) и радиусом r видна над рельефом, что закрывает место источника вдали
         * (в прорисовке клуб режет глубина мира): 1 — открыто.
         */
        default double visible(double y, double r) {
            return 1;
        }

        /**
         * Итог раскладки этого кадра: true — клубы источника в сетке; false — нет ни одного (таблица полна, а он дальше
         * других): тогда облако рисует себя само.
         */
        default void placed(boolean inGrid) {}

        /** Источник иссяк и без кадра (тик сетки убирает его, даже если кадров нет). */
        default boolean ended() {
            return false;
        }
    }

    /** Куда источник кладёт клубы. */
    public interface Sink {
        /**
         * Гауссов клуб, размазанный по отрезку от центра − (ax, ay, az) до центра + (ax, ay, az) (см. {@link SmokeGrid}):
         * центр в мире (x, y, z), полуотрезок, м; σ по горизонтали и вертикали, м; массы сажи, пыли и капель на весь отрезок, кг;
         * альбедо ω' пыли по каналам (после дельта-Эддингтона, {@link #dustAlbedo}); температура газа, K; подъём, м/с.
         */
        void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot, double dust,
                     double water, float dustR, float dustG, float dustB, double kelvin, double rise);

        /**
         * Кусок трубки дыма между концами a и b (центры сечения в мире; шлейф пожара, ножка облака взрыва): у каждого конца
         * σ по горизонтали и вертикали, м, сажи и пыли на метр оси куска (не пути по ветру), кг/м; альбедо ω' пыли по
         * каналам; кусок без вещества — пустой (держит номер куска у приёмника); температура газа, K; подъём, м/с. Сетка берёт его отрезком со средними концов; слой эффектов рисует
         * трубкой, как след снаряда ({@code FarFlightView.line}): σ и вещество меняются вдоль оси без ступенек, вдали —
         * лентой. sigma0 — σ сечения у самого источника (кадр клуба — по росту σ с него).
         */
        default void tube(double ax, double ay, double az, double sha, double sva, double sootA, double dustA, double bx, double by, double bz,
                     double shb, double svb, double sootB, double dustB, float albedoR, float albedoG, float albedoB, double kelvin, double rise,
                     double sigma0) {
            double hx = 0.5 * (bx - ax), hy = 0.5 * (by - ay), hz = 0.5 * (bz - az), len = 2 * Math.sqrt(hx * hx + hy * hy + hz * hz);
            if (sootA + sootB + dustA + dustB <= 0) return;
            segment(ax + hx, ay + hy, az + hz, hx, hy, hz, 0.5 * (sha + shb), 0.5 * (sva + svb), 0.5 * (sootA + sootB) * len,
                    0.5 * (dustA + dustB) * len, 0, albedoR, albedoG, albedoB, kelvin, rise);
        }

        /** Гауссов клуб в точке (отрезок нулевой длины). */
        default void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water,
                          float dustR, float dustG, float dustB, double kelvin, double rise) {
            segment(x, y, z, 0, 0, 0, sh, sv, soot, dust, water, dustR, dustG, dustB, kelvin, rise);
        }
    }

    /** Кадр сетки: клубы, их каскады и слои — то, что {@code SmokePass} кладёт в видеопамять. */
    public static final class Frame {
        /** Таблица клубов: {@link #BLOB_ROWS} строк по {@link #MAX_BLOBS} RGBA (строка 0 — центр от глаза и σh…). */
        public final float[] blobs = new float[BLOB_ROWS * MAX_BLOBS * 4];
        /** Клубов в таблице. */
        public int count;
        /** Каскад клуба. */
        final int[] cascade = new int[MAX_BLOBS];
        /** Угол каскада от глаза (x, y, z) и клетка — по каскаду; угол в мире (для кратности клетке). */
        public final float[] corner = new float[CASCADES * 4];
        /** Рамка всего дыма от глаза; min > max — дыма нет. */
        public final float[] min = new float[3], max = new float[3];
        /** Коробка клубов каждого каскада от глаза (x, y, z по каскаду) внутри каскада; lo > hi — каскад пуст. */
        public final float[] boxLo = new float[CASCADES * 3], boxHi = new float[CASCADES * 3];
        /** Вершины прямоугольников клубов по слоям (x, y — NDC, z — номер клуба) и начало слоя в них (по вершинам). */
        public float[] vertices = new float[4096 * 3];
        public final int[] layerStart = new int[CASCADES * N + 1];

        public boolean empty() {
            return count == 0;
        }
    }

    private static final List<Source> SOURCES = new ArrayList<>();
    /** Источники, чьи клубы легли в сетку этого кадра (остальных рисуют квадраты, {@link #quads}). */
    private static final java.util.Set<Source> IN_GRID = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final Frame FRAME = new Frame();
    /** Часы сетки: тики клиента не на паузе. */
    private static long ticks;

    private SmokeGrid() {}

    /** Объём рисуется (шейдеры и сетка в порядке, {@code SmokeFrame}); нет — облака взрывов вдали и вблизи рисуют квадраты. */
    private static boolean enabled;
    /** Проход пелены нарисовал сетку в прошлом кадре ({@code FxLayer}): нет — облака рисуют квадраты, как без объёма. */
    private static boolean drawn = true;

    public static void enable(boolean on) {
        enabled = on;
    }

    /** Объём есть и в прошлом кадре был нарисован. */
    public static boolean on() {
        return enabled && drawn;
    }

    /** Итог кадра слоя эффектов: сетку нарисовал проход пелены (или дыма не было — тогда объёму ничто не мешает). */
    public static void drawn(boolean yes) {
        drawn = yes;
    }

    /**
     * Доля облака с центром (dx, dy, dz) от глаза, что рисует объём сетки: до 0,55 полустороны последнего каскада по каждой
     * оси — вся, к 0,75 — плавно ни одной (клубы облака — до трёх σ, сотни метров, — ещё в сетке); остальное — квадраты
     * клубов той же модели ({@code FarBlasts}).
     */
    public static double volumeShare(double dx, double dy, double dz) {
        double half = 0.5 * N * CELL[CASCADES - 1], d = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        double t = Math.max(0, Math.min(1, (d - 0.55 * half) / (0.2 * half)));
        return 1 - t * t * (3 - 2 * t);
    }

    /** Новый источник вещества (облако взрыва, старта, след…). */
    public static void add(Source s) {
        SOURCES.add(s);
    }

    /** Часы сетки, с: тики клиента не на паузе и доля тика. */
    public static double seconds(float partial) {
        return (ticks + partial) / 20.0;
    }

    /** Тик клиента (не на паузе); иссякшие источники уходят и без кадров (слой эффектов может не рисоваться вовсе). */
    public static void tick() {
        ticks++;
        SOURCES.removeIf(Source::ended);
        IN_GRID.removeIf(Source::ended);
    }

    /** Смена мира, перемотка повтора: источников нет. */
    public static void reset() {
        SOURCES.clear();
        IN_GRID.clear();
        FRAME.count = 0;
    }

    public static int sources() {
        return SOURCES.size();
    }

    /** Доля рассеяния вперёд пыли f = g² (асимметрия 0,6): её дельта-Эддингтон убирает из ослабления. */
    static final double DUST_F = 0.36;

    /**
     * Ослабление пыли на массу σ' после дельта-Эддингтона, м²/кг: {@code DustModel.K_MIX}·(1 − ωf), ω — альбедо
     * однократного рассеяния, обращённое из ω' ({@link #dustAlbedo}) среднего канала (ослабление у клуба одно на все
     * каналы).
     */
    public static double dustK(float wr, float wg, float wb) {
        double wp = (wr + wg + wb) / 3.0, w = wp / (1 - DUST_F + DUST_F * wp);
        return ua.zentix.incoming.nuclear.model.DustModel.K_MIX * (1 - w * DUST_F);
    }

    /**
     * Альбедо ω' пыли цвета грунта (r, g, b на экране) — обращение отражения толстого слоя двух потоков R∞ = (1 − s)/(1 + s),
     * s² = (1 − ω')/(1 − ω'g') (замыкание по средним полусфер, как у цвета клубов слоя, {@code SmokePuffs.thick}).
     */
    public static float dustAlbedo(float display) {
        double r = Math.pow(Math.max(display, 0), 2.2);
        double u = (1 - r) / (1 + r), v = u * u;
        return (float) ((1 - v) / (1 - DUST_G * v));
    }

    /**
     * Кадр сетки у глаза (eyeX, eyeY, eyeZ) в момент {@code seconds}: источники отдают клубы, клубы — в каскады и слои.
     * Иссякшие источники уходят. Пустой кадр — дыма нет (проход пелены его не рисует).
     */
    public static Frame frame(double eyeX, double eyeY, double eyeZ, double seconds) {
        Frame f = FRAME;
        build(SOURCES, f, eyeX, eyeY, eyeZ, seconds);
        return f;
    }

    /**
     * Источники, которых объём этого кадра не рисует ({@link Source#volume} нет, клетка не разрешает, таблица полна,
     * объёма нет), — квадратами слоя: each получает источник и отдаёт, жив ли он (false — иссяк, уходит).
     */
    public static void quads(java.util.function.Predicate<Source> each) {
        for (var it = SOURCES.iterator(); it.hasNext(); ) {
            Source s = it.next();
            if (!each.test(s)) {
                IN_GRID.remove(s);
                it.remove();
            }
        }
    }

    /** Черновик клубов кадра: {@link #STAGE} чисел на клуб; источники по порядку и их ближний край. */
    private static final double[] STAGE_BLOBS = new double[STAGED * STAGE];
    private static int staged;

    /** Глаз кадра, что сейчас раскладывается (источники берут его в {@link Source#emit}). */
    private static double frameX, frameY, frameZ;

    /** Глаз кадра сетки (x, y, z) — у источников в {@link Source#emit} и после. */
    public static double eyeX() {
        return frameX;
    }

    public static double eyeY() {
        return frameY;
    }

    public static double eyeZ() {
        return frameZ;
    }

    /** То же для своего списка источников и кадра (юнит-тест). */
    static void build(List<Source> sources, Frame f, double eyeX, double eyeY, double eyeZ, double seconds) {
        frameX = eyeX;
        frameY = eyeY;
        frameZ = eyeZ;
        f.count = 0;
        Arrays.fill(f.min, Float.MAX_VALUE);
        Arrays.fill(f.max, -Float.MAX_VALUE);
        Arrays.fill(f.boxLo, Float.MAX_VALUE);
        Arrays.fill(f.boxHi, -Float.MAX_VALUE);
        double[] corner = new double[CASCADES * 3];
        for (int c = 0; c < CASCADES; c++) {
            double cell = CELL[c];
            corner[3 * c] = Math.floor(eyeX / cell - N / 2.0) * cell;
            corner[3 * c + 1] = Math.floor(eyeY / cell - N / 2.0) * cell;
            corner[3 * c + 2] = Math.floor(eyeZ / cell - N / 2.0) * cell;
            f.corner[4 * c] = (float) (corner[3 * c] - eyeX);
            f.corner[4 * c + 1] = (float) (corner[3 * c + 1] - eyeY);
            f.corner[4 * c + 2] = (float) (corner[3 * c + 2] - eyeZ);
            f.corner[4 * c + 3] = (float) cell;
        }
        System.arraycopy(corner, 0, FRAME_CORNER, 0, corner.length);
        frameOn = on();
        // клубы — в черновик: ближний край каждого источника и сколько клубов он дал
        staged = 0;
        int n = sources.size();
        double[] near = new double[n];
        int[] first = new int[n + 1], count = new int[n];
        boolean[] over = new boolean[n];
        for (int k = 0; k < n; k++) {
            int src = k;
            near[k] = Double.MAX_VALUE;
            first[k] = staged;
            Sink sink = new Sink() {
                @Override
                public void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot,
                                    double dust, double water, float dr, float dg, float db, double kelvin, double rise) {
                    stage(share(corner, x, y, z, ax, ay, az, sh, sv, rise, false), x, y, z, ax, ay, az, sh, sv, soot, dust, water, dr, dg,
                            db, kelvin, rise);
                }

                @Override
                public void tube(double ax, double ay, double az, double sha, double sva, double sootA, double dustA, double bx, double by,
                                 double bz, double shb, double svb, double sootB, double dustB, float dr, float dg, float db, double kelvin,
                                 double rise, double sigma0) {
                    // как у приёмника по умолчанию: отрезок со средними концов
                    double hx = 0.5 * (bx - ax), hy = 0.5 * (by - ay), hz = 0.5 * (bz - az), len = 2 * Math.sqrt(hx * hx + hy * hy + hz * hz);
                    double sh = 0.5 * (sha + shb), sv = 0.5 * (sva + svb);
                    stage(share(corner, ax + hx, ay + hy, az + hz, hx, hy, hz, sh, sv, rise, true), ax + hx, ay + hy, az + hz, hx, hy, hz, sh,
                            sv, 0.5 * (sootA + sootB) * len, 0.5 * (dustA + dustB) * len, 0, dr, dg, db, kelvin, rise);
                }

                /** Доля w клуба — в черновик (остальное рисуют квадраты, {@link #rest}). */
                private void stage(double w, double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot,
                                   double dust, double water, float dr, float dg, float db, double kelvin, double rise) {
                    if (!(w > 1e-3)) return;
                    soot *= w;
                    dust *= w;
                    water *= w;
                if (soot + dust + water <= 0 || !(sh > 0) || !(sv > 0)) return;
                if (staged >= STAGED) {
                    over[src] = true;
                    return;
                }
                double[] b = STAGE_BLOBS;
                int at = staged++ * STAGE;
                b[at] = x;
                b[at + 1] = y;
                b[at + 2] = z;
                b[at + 3] = sh;
                b[at + 4] = sv;
                b[at + 5] = soot;
                b[at + 6] = dust;
                b[at + 7] = water;
                b[at + 8] = dr;
                b[at + 9] = dg;
                b[at + 10] = db;
                b[at + 11] = kelvin;
                b[at + 12] = rise;
                b[at + 13] = ax;
                b[at + 14] = ay;
                b[at + 15] = az;
                b[at + 16] = src;
                double dx = x - eyeX, dy = y - eyeY, dz = z - eyeZ;
                double reach = REACH * Math.max(sh, sv) + Math.sqrt(ax * ax + ay * ay + az * az);
                near[src] = Math.min(near[src], Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - reach));
                }
            };
            // источник, чей дым в объём не идёт: его клубы рисуют квадраты, emit — у них
            if (off || !sources.get(k).volume()) {
                count[k] = 0;
                over[k] = true;
                continue;
            }
            boolean alive = sources.get(k).emit(seconds, sink);
            count[k] = alive ? staged - first[k] : -1;
        }
        // в таблицу — источники от ближнего к дальнему, пока помещаются целиком
        Integer[] order = new Integer[n];
        for (int k = 0; k < n; k++) order[k] = k;
        Arrays.sort(order, (a, b) -> Double.compare(near[a], near[b]));
        boolean[] in = new boolean[n];
        int room = MAX_BLOBS;
        for (int k : order) {
            // не поместился — облако целиком квадратами (не половиной в объёме)
            if (count[k] <= 0 || over[k] || count[k] > room) continue;
            room -= count[k];
            in[k] = true;
            for (int i = first[k]; i < first[k] + count[k]; i++) {
                double[] b = STAGE_BLOBS;
                int at = i * STAGE;
                put(f, corner, eyeX, eyeY, eyeZ, b[at], b[at + 1], b[at + 2], b[at + 13], b[at + 14], b[at + 15], b[at + 3], b[at + 4],
                        b[at + 5], b[at + 6], b[at + 7], (float) b[at + 8], (float) b[at + 9], (float) b[at + 10], b[at + 11], b[at + 12]);
            }
        }
        for (int k = 0; k < n; k++) {
            if (count[k] < 0) continue;
            boolean placed = in[k] || count[k] == 0 && !over[k];
            sources.get(k).placed(placed);
            if (placed) IN_GRID.add(sources.get(k));
            else IN_GRID.remove(sources.get(k));
        }
        OUT.clear();
        for (int k = 0; k < n; k++) if (count[k] >= 0 && sources.get(k).volume() && (over[k] || count[k] > 0 && !in[k])) OUT.add(sources.get(k));
        for (int k = n - 1; k >= 0; k--) if (count[k] < 0) IN_GRID.remove(sources.get(k));
        for (int k = n - 1; k >= 0; k--) if (count[k] < 0) sources.remove(k);
        layers(f);
    }

    /**
     * Каскад клуба с центром (x, y, z), полуотрезком (ax, ay, az) и σ (sh, sv): самый мелкий, где он весь (три σ, смазанный
     * на полклетки, и отрезок) и не шире {@link #SPAN} клеток σ; последний — если центр в нём (края за ним срежет текстура);
     * −1 — за всеми.
     */
    private static int cascade(double[] corner, double x, double y, double z, double ax, double ay, double az, double sh, double sv) {
        ax = Math.abs(ax);
        ay = Math.abs(ay);
        az = Math.abs(az);
        for (int k = 0; k < CASCADES; k++) {
            double cell = CELL[k], half = 0.5 * cell;
            double h = Math.sqrt(sh * sh + half * half), v = Math.sqrt(sv * sv + half * half);
            double span = N * cell;
            if (k == CASCADES - 1) {
                boolean inside = x >= corner[3 * k] && x < corner[3 * k] + span && z >= corner[3 * k + 2] && z < corner[3 * k + 2] + span
                        && y >= corner[3 * k + 1] && y < corner[3 * k + 1] + span;
                return inside ? k : -1;
            }
            double rx = REACH * h + ax, ry = REACH * v + ay, rz = REACH * h + az;
            boolean inside = x - rx >= corner[3 * k] && x + rx <= corner[3 * k] + span
                    && z - rz >= corner[3 * k + 2] && z + rz <= corner[3 * k + 2] + span
                    && y - ry >= corner[3 * k + 1] && y + ry <= corner[3 * k + 1] + span;
            if (inside && Math.max(h, v) <= SPAN * cell) return k;
        }
        return -1;
    }

    /** Углы каскадов в мире у глаза (как у кадра, {@link #build}) — в out (3 числа на каскад). */
    public static void corners(double eyeX, double eyeY, double eyeZ, double[] out) {
        for (int c = 0; c < CASCADES; c++) {
            double cell = CELL[c];
            out[3 * c] = Math.floor(eyeX / cell - N / 2.0) * cell;
            out[3 * c + 1] = Math.floor(eyeY / cell - N / 2.0) * cell;
            out[3 * c + 2] = Math.floor(eyeZ / cell - N / 2.0) * cell;
        }
    }

    /**
     * Клетка каскада, в который ляжет клуб σ (sh, sv) с центром (x, y, z) в мире и полуотрезком (ax, ay, az), м, при углах
     * каскадов corner ({@link #corners}); 0 — ни в какой.
     */
    public static float cellFor(double[] corner, double x, double y, double z, double ax, double ay, double az, double sh, double sv) {
        int k = cascade(corner, x, y, z, ax, ay, az, sh, sv);
        return k < 0 ? 0 : CELL[k];
    }

    /**
     * Доля сечения σ, что объём сетки показывает верно в каскаде с клеткой cell: клетка расширяет сечение до
     * √(σ² + (клетка/2)²) — при σ не меньше двух клеток шире на ≤ 3 % (толща по оси — в пределах 6 % от толщи клуба
     * квадратами), вся доля; при σ не больше клетки — на ≥ 12 %, доли нет (кубы и мыло 3.5.0); между — плавно. Не
     * настройка картинки, а предел разрешения каскада.
     */
    public static double resolved(double sigma, double cell) {
        if (cell <= 0) return 0;
        double t = Math.max(0, Math.min(1, sigma / cell - 1));
        return t * t * (3 - 2 * t);
    }

    /** Полоса у грани каскада, клеток: в ней доля клуба переходит к доле каскада крупнее (без скачка, когда клуб меняет каскад). */
    static final double EDGE = 8;
    /**
     * Облако клубится, пока клуб оборачивается быстрее этого (σ ÷ скорость подъёма, с): тогда его рисуют кадры симуляции
     * (квадраты слоя); вдвое медленнее — весь клуб в объёме, между — плавно. У термика σ/w растёт с возрастом (σ ∝ √t,
     * w ∝ 1/√t: Scorer 1957), ~0,26·t у облаков модели — у шахеда и ракеты объём с ~20 с до ~40 с (⚠ оценка порога по
     * кадрам: облако, что ещё растёт клубами, — кадрами симуляции). У трубок (шлейф пожара, след) — только клетка.
     */
    static final double MATURE_S = 5;

    /** Углы каскадов кадра {@link #build} и рисует ли объём этот кадр: {@link #rest} того же кадра берёт их же. */
    private static final double[] FRAME_CORNER = new double[CASCADES * 3];
    private static boolean frameOn;
    /** Весь дым — квадратами, как до объёма (песочница: {@code volume off} — тот же кадр для сравнения). */
    public static volatile boolean off;
    /** Источники кадра, что в таблицу не поместились: их клубы — квадратами целиком. */
    private static final java.util.Set<Source> OUT = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Доля клуба (центр в мире, полуотрезок, σ, подъём м/с), которую рисует объём при углах каскадов corner: каскад, куда
     * клуб ляжет, должен его разрешать ({@link #resolved}); в полосе {@link #EDGE} клеток у грани каскада доля плавно
     * переходит в долю каскада крупнее (у последнего — в ноль), чтобы клуб, меняя каскад, не менял вид скачком; облако
     * (не трубка) — ещё и устоявшееся ({@link #MATURE_S}). Остальное (1 − доля) рисуют квадраты: сумма — весь клуб.
     */
    public static double share(double[] corner, double x, double y, double z, double ax, double ay, double az, double sh, double sv,
                               double rise, boolean tube) {
        double w = shareFrom(corner, 0, x, y, z, Math.abs(ax), Math.abs(ay), Math.abs(az), sh, sv);
        if (w <= 0 || tube) return w;
        double t = Math.max(0, Math.min(1, (Math.min(sh, sv) / Math.max(Math.abs(rise), 1e-3) - MATURE_S) / MATURE_S));
        return w * t * t * (3 - 2 * t);
    }

    private static double shareFrom(double[] corner, int from, double x, double y, double z, double ax, double ay, double az, double sh,
                                    double sv) {
        for (int k = from; k < CASCADES; k++) {
            double cell = CELL[k], half = 0.5 * cell, span = N * cell;
            double h = Math.sqrt(sh * sh + half * half), v = Math.sqrt(sv * sv + half * half);
            boolean last = k == CASCADES - 1;
            if (!last && Math.max(h, v) > SPAN * cell) continue;
            double rx = last ? 0 : REACH * h + ax, ry = last ? 0 : REACH * v + ay, rz = last ? 0 : REACH * h + az;
            double m = Math.min(Math.min(x - rx - corner[3 * k], corner[3 * k] + span - x - rx),
                    Math.min(Math.min(y - ry - corner[3 * k + 1], corner[3 * k + 1] + span - y - ry),
                            Math.min(z - rz - corner[3 * k + 2], corner[3 * k + 2] + span - z - rz)));
            if (m < 0) continue;
            double here = resolved(Math.min(sh, sv), cell), band = EDGE * cell;
            if (m >= band) return here;
            double t = m / band, s = t * t * (3 - 2 * t);
            return here * s + (last ? 0 : shareFrom(corner, k + 1, x, y, z, ax, ay, az, sh, sv)) * (1 - s);
        }
        return 0;
    }

    /**
     * Приёмник квадратов {@code to} источника s в этом кадре: клубы — без доли, что рисует объём ({@link #share}); объёма
     * нет или источник не в таблице — как есть. Один на кадр (как приёмник слоя).
     */
    public static Sink rest(Source s, Sink to) {
        if (!frameOn || off || !s.volume() || OUT.contains(s)) return to;
        REST.to = to;
        return REST;
    }

    private static final Rest REST = new Rest();

    private static final class Rest implements Sink {
        Sink to;

        @Override
        public void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot, double dust,
                            double water, float dustR, float dustG, float dustB, double kelvin, double rise) {
            double k = 1 - share(FRAME_CORNER, x, y, z, ax, ay, az, sh, sv, rise, false);
            if (k < 1e-3) k = 0;
            to.segment(x, y, z, ax, ay, az, sh, sv, soot * k, dust * k, water * k, dustR, dustG, dustB, kelvin, rise);
        }

        @Override
        public void tube(double ax, double ay, double az, double sha, double sva, double sootA, double dustA, double bx, double by, double bz,
                         double shb, double svb, double sootB, double dustB, float albedoR, float albedoG, float albedoB, double kelvin,
                         double rise, double sigma0) {
            double hx = 0.5 * (bx - ax), hy = 0.5 * (by - ay), hz = 0.5 * (bz - az);
            double k = 1 - share(FRAME_CORNER, ax + hx, ay + hy, az + hz, hx, hy, hz, 0.5 * (sha + shb), 0.5 * (sva + svb), rise, true);
            if (k < 1e-3) k = 0;
            // кусок без вещества приёмник держит под своим номером: отдаём и пустой
            to.tube(ax, ay, az, sha, sva, sootA * k, dustA * k, bx, by, bz, shb, svb, sootB * k, dustB * k, albedoR, albedoG, albedoB, kelvin,
                    rise, sigma0);
        }
    }

    /**
     * Множитель гаусса по отрезку против гаусса в точке (та же масса и σ) у точки на расстоянии q∥ вдоль отрезка от центра,
     * в осях, где σ — единица; A — длина полуотрезка там: √(π/2)/(2A)·[erf((A − q∥)/√2) + erf((A + q∥)/√2)], к A → 0 —
     * e^(−q∥²/2). Поперёк — свой множитель e^(−q⊥²/2). Та же формула — в {@code fx_smoke_fill} ({@code SmokeGlslTest}).
     */
    public static double line(double a, double q) {
        if (a < LINE_POINT) return Math.exp(-0.5 * q * q);
        return Math.sqrt(Math.PI / 2) / (2 * a) * Math.max(0, erf((a - q) / Math.sqrt(2)) + erf((a + q) / Math.sqrt(2)));
    }

    /** Короче этого (в σ) отрезок — точка: разность erf у коротких теряет точность в половинной точности шейдера. */
    public static final double LINE_POINT = 0.05;

    /** erf по Абрамовицу и Стиган 7.1.26 (ошибка ≤ 1,5·10⁻⁷), как в шейдере. */
    public static double erf(double x) {
        double ax = Math.abs(x), t = 1 / (1 + 0.3275911 * ax);
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-ax * ax);
        return Math.signum(x) * y;
    }

    /** Клуб — в таблицу и в свой каскад; вне всех каскадов, пустой или сверх таблицы — нет. */
    private static void put(Frame f, double[] corner, double ex, double ey, double ez, double x, double y, double z, double ax, double ay,
                            double az, double sh, double sv, double soot, double dust, double water, float dr, float dg, float db,
                            double kelvin, double rise) {
        if (f.count >= MAX_BLOBS) return;
        int c = cascade(corner, x, y, z, ax, ay, az, sh, sv);
        if (c < 0) return;
        double half = 0.5 * CELL[c], h = Math.sqrt(sh * sh + half * half), v = Math.sqrt(sv * sv + half * half);
        int i = f.count++;
        f.cascade[i] = c;
        double peak = 1 / (Math.pow(2 * Math.PI, 1.5) * h * h * v);
        float[] b = f.blobs;
        set(b, 0, i, (float) (x - ex), (float) (y - ey), (float) (z - ez), (float) h);
        set(b, 1, i, (float) v, (float) (SOOT_K * soot * peak), (float) (dustK(dr, dg, db) * dust * peak),
                (float) (DROP_K * water * peak));
        set(b, 2, i, dr, dg, db, (float) ax);
        set(b, 3, i, (float) (Math.max(0, kelvin - AIR_K) / 1000), (float) rise, (float) ay, (float) az);
        float rx = (float) (REACH * h + Math.abs(ax)), ry = (float) (REACH * v + Math.abs(ay)), rz = (float) (REACH * h + Math.abs(az));
        float px = (float) (x - ex), py = (float) (y - ey), pz = (float) (z - ez);
        f.min[0] = Math.min(f.min[0], px - rx);
        f.min[1] = Math.min(f.min[1], py - ry);
        f.min[2] = Math.min(f.min[2], pz - rz);
        f.max[0] = Math.max(f.max[0], px + rx);
        f.max[1] = Math.max(f.max[1], py + ry);
        f.max[2] = Math.max(f.max[2], pz + rz);
        // коробка каскада — в его границах (в последнем клуб может выходить за край)
        double span = N * CELL[c];
        double[] lo = {x - rx, y - ry, z - rz}, hi = {x + rx, y + ry, z + rz};
        double[] eye = {ex, ey, ez};
        for (int a = 0; a < 3; a++) {
            double from = corner[3 * c + a];
            f.boxLo[3 * c + a] = Math.min(f.boxLo[3 * c + a], (float) (Math.max(lo[a], from) - eye[a]));
            f.boxHi[3 * c + a] = Math.max(f.boxHi[3 * c + a], (float) (Math.min(hi[a], from + span) - eye[a]));
        }
    }

    /** В каскаде {@code c} кадра есть клубы. */
    public static boolean occupied(Frame f, int c) {
        return f.boxLo[3 * c] <= f.boxHi[3 * c];
    }

    private static void set(float[] b, int row, int i, float x, float y, float z, float w) {
        int at = (row * MAX_BLOBS + i) * 4;
        b[at] = x;
        b[at + 1] = y;
        b[at + 2] = z;
        b[at + 3] = w;
    }

    /**
     * Прямоугольники клубов по слоям: слой L = каскад·N + номер слоя по высоте; клуб — в слоях своего каскада, где он
     * есть (три σ по вертикали), прямоугольником клеток, где он есть по горизонтали, на обеих страницах.
     */
    static void layers(Frame f) {
        int layers = CASCADES * N;
        int[] count = new int[layers];
        int[] lo = new int[f.count], hi = new int[f.count];
        for (int i = 0; i < f.count; i++) {
            int c = f.cascade[i];
            float cell = f.corner[4 * c + 3];
            float y = f.blobs[(i) * 4 + 1], v = f.blobs[(MAX_BLOBS + i) * 4], ay = Math.abs(f.blobs[(3 * MAX_BLOBS + i) * 4 + 2]);
            lo[i] = Math.max(0, (int) Math.floor((y - REACH * v - ay - f.corner[4 * c + 1]) / cell));
            hi[i] = Math.min(N - 1, (int) Math.floor((y + REACH * v + ay - f.corner[4 * c + 1]) / cell));
            for (int s = lo[i]; s <= hi[i]; s++) count[c * N + s]++;
        }
        int total = 0;
        for (int l = 0; l < layers; l++) {
            f.layerStart[l] = total;
            total += count[l] * VERTICES;
        }
        f.layerStart[layers] = total;
        if (f.vertices.length < total * 3) f.vertices = new float[Math.max(total * 3, f.vertices.length * 2)];
        int[] at = new int[layers];
        for (int l = 0; l < layers; l++) at[l] = f.layerStart[l];
        for (int i = 0; i < f.count; i++) {
            int c = f.cascade[i];
            float cell = f.corner[4 * c + 3];
            float x = f.blobs[i * 4], z = f.blobs[i * 4 + 2], h = f.blobs[i * 4 + 3];
            float sx = Math.abs(f.blobs[(2 * MAX_BLOBS + i) * 4 + 3]), sz = Math.abs(f.blobs[(3 * MAX_BLOBS + i) * 4 + 3]);
            float x0 = clampCell((x - REACH * h - sx - f.corner[4 * c]) / cell), x1 = clampCell((x + REACH * h + sx - f.corner[4 * c]) / cell + 1);
            float z0 = clampCell((z - REACH * h - sz - f.corner[4 * c + 2]) / cell), z1 = clampCell((z + REACH * h + sz - f.corner[4 * c + 2]) / cell + 1);
            if (x1 <= x0 || z1 <= z0) continue;
            for (int s = lo[i]; s <= hi[i]; s++) {
                int l = c * N + s;
                for (int page = 0; page < 2; page++) {
                    // NDC: страница 0 — левая половина ширины 2N, страница 1 — правая
                    float ax = (page * N + x0) / N - 1, bx = (page * N + x1) / N - 1;
                    float ay = z0 / N * 2 - 1, by = z1 / N * 2 - 1;
                    at[l] = quad(f.vertices, at[l], ax, ay, bx, by, i);
                }
            }
        }
    }

    private static float clampCell(float c) {
        return Math.max(0, Math.min(N, (float) Math.floor(c)));
    }

    private static int quad(float[] v, int at, float ax, float ay, float bx, float by, int blob) {
        at = vertex(v, at, ax, ay, blob);
        at = vertex(v, at, bx, ay, blob);
        at = vertex(v, at, bx, by, blob);
        at = vertex(v, at, ax, ay, blob);
        at = vertex(v, at, bx, by, blob);
        return vertex(v, at, ax, by, blob);
    }

    private static int vertex(float[] v, int at, float x, float y, int blob) {
        v[3 * at] = x;
        v[3 * at + 1] = y;
        v[3 * at + 2] = blob;
        return at + 1;
    }
}
