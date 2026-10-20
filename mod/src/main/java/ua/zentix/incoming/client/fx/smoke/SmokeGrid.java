package ua.zentix.incoming.client.fx.smoke;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Сетка дыма у камеры: весь дым, пыль и огонь обычных взрывов (и всё, что добавят другие источники: облака старта,
 * следы, дым пожаров — {@link #add}) — одной трёхмерной текстурой ослабления, которую проход пелены ({@code PallPass},
 * {@code fx_pall}) проходит своим ходом луча, сколько бы облаков ни было. Без Minecraft и OpenGL: здесь — кто в кадре,
 * каскады и раскладка; в видеопамять кладёт {@code fx/layer/SmokePass}.
 * <p>
 * <b>Каскады.</b> {@link #CASCADES} кубов по {@link #N}³ клеток вокруг глаза, клетка — {@link #CELL} м (2, 8, 32: у глаза
 * 128 м, дальше 512 м и 2 км), угол куба — кратен клетке (сетка стоит на месте мира, а не ездит с глазом). Клуб
 * (гаусс массы вещества) ложится ровно в один каскад: самый мелкий, где он целиком (три σ) и где он не шире
 * {@link #SPAN} клеток σ, — значит у каждого каскада свой набор клубов, и плотность в точке — сумма каскадов, в которых она
 * есть. Шва на краю каскада нет: клуб у края уже в следующем. Клеткой мельче клуба гаусс не дробится: σ клуба в каскаде
 * — √(σ² + (клетка/2)²), масса та же.
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
    public static final float[] CELL = {2, 8, 32};
    /** Клуб шире стольких клеток σ — в каскад крупнее (у последнего — как есть). */
    public static final float SPAN = 4;
    /** Клуб в каскаде — до стольких σ от центра. */
    public static final float REACH = 3;
    /**
     * Клубов в кадре не больше (ширина таблицы клубов). Не помещаются — уходят источники дальше всех от глаза, целиком
     * ({@link Source#placed}): их рисуют сами (квадраты {@code FarBlasts}), а не половиной облака.
     */
    public static final int MAX_BLOBS = 512;
    /** Клубов, что источники могут отдать за кадр до раскладки (сверх — источник не в сетке). */
    static final int STAGED = 8 * MAX_BLOBS;
    /** Чисел на клуб в черновике: 13 параметров {@link Sink#blob} и номер источника. */
    private static final int STAGE = 14;
    /** Строк таблицы клубов: центр и σ по горизонтали; σ по вертикали и пиковые ослабления; альбедо пыли; ΔT и подъём. */
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
         * Гауссов клуб: центр в мире (x, y, z), σ по горизонтали и вертикали, м; массы сажи, пыли и капель, кг; альбедо
         * ω' пыли по каналам (после дельта-Эддингтона, {@link #dustAlbedo}); температура газа, K; подъём, м/с.
         */
        void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water,
                  float dustR, float dustG, float dustB, double kelvin, double rise);
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
    }

    /** Смена мира, перемотка повтора: источников нет. */
    public static void reset() {
        SOURCES.clear();
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

    /** Альбедо ω' пыли цвета грунта (r, g, b на экране) — обращение отражения толстого слоя двух потоков (как у пелены). */
    public static float dustAlbedo(float display) {
        double r = Math.pow(Math.max(display, 0), 2.2);
        double u = (1 - r) / (1 + r), v = 0.75 * u * u;
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

    /** Черновик клубов кадра: {@link #STAGE} чисел на клуб; источники по порядку и их ближний край. */
    private static final double[] STAGE_BLOBS = new double[STAGED * STAGE];
    private static int staged;

    /** То же для своего списка источников и кадра (юнит-тест). */
    static void build(List<Source> sources, Frame f, double eyeX, double eyeY, double eyeZ, double seconds) {
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
            Sink sink = (x, y, z, sh, sv, soot, dust, water, dr, dg, db, kelvin, rise) -> {
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
                b[at + 13] = src;
                double dx = x - eyeX, dy = y - eyeY, dz = z - eyeZ;
                near[src] = Math.min(near[src], Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - REACH * Math.max(sh, sv)));
            };
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
            if (count[k] <= 0 || over[k] || count[k] > room) continue;
            // клуб источника за всеми каскадами — источник не в сетке целиком (облако рисует себя само, а не обрезком)
            boolean all = true;
            for (int i = first[k]; i < first[k] + count[k] && all; i++) all = cascade(corner, STAGE_BLOBS, i * STAGE) >= 0;
            if (!all) continue;
            room -= count[k];
            in[k] = true;
            for (int i = first[k]; i < first[k] + count[k]; i++) {
                double[] b = STAGE_BLOBS;
                int at = i * STAGE;
                put(f, corner, eyeX, eyeY, eyeZ, b[at], b[at + 1], b[at + 2], b[at + 3], b[at + 4], b[at + 5], b[at + 6], b[at + 7],
                        (float) b[at + 8], (float) b[at + 9], (float) b[at + 10], b[at + 11], b[at + 12]);
            }
        }
        for (int k = 0; k < n; k++) if (count[k] >= 0) sources.get(k).placed(in[k] || count[k] == 0 && !over[k]);
        for (int k = n - 1; k >= 0; k--) if (count[k] < 0) sources.remove(k);
        layers(f);
    }

    /** Каскад клуба черновика с началом {@code at} в {@code b} ({@link #cascade(double[], double, double, double, double, double)}). */
    private static int cascade(double[] corner, double[] b, int at) {
        return cascade(corner, b[at], b[at + 1], b[at + 2], b[at + 3], b[at + 4]);
    }

    /**
     * Каскад клуба с центром (x, y, z) и σ (sh, sv): самый мелкий, где он весь (три σ, смазанный на полклетки) и не шире
     * {@link #SPAN} клеток σ; последний — если центр в нём (края за ним срежет текстура); −1 — за всеми.
     */
    private static int cascade(double[] corner, double x, double y, double z, double sh, double sv) {
        for (int k = 0; k < CASCADES; k++) {
            double cell = CELL[k], half = 0.5 * cell;
            double h = Math.sqrt(sh * sh + half * half), v = Math.sqrt(sv * sv + half * half);
            double span = N * cell;
            if (k == CASCADES - 1) {
                boolean inside = x >= corner[3 * k] && x < corner[3 * k] + span && z >= corner[3 * k + 2] && z < corner[3 * k + 2] + span
                        && y >= corner[3 * k + 1] && y < corner[3 * k + 1] + span;
                return inside ? k : -1;
            }
            boolean inside = x - REACH * h >= corner[3 * k] && x + REACH * h <= corner[3 * k] + span
                    && z - REACH * h >= corner[3 * k + 2] && z + REACH * h <= corner[3 * k + 2] + span
                    && y - REACH * v >= corner[3 * k + 1] && y + REACH * v <= corner[3 * k + 1] + span;
            if (inside && Math.max(h, v) <= SPAN * cell) return k;
        }
        return -1;
    }

    /** Клуб — в таблицу и в свой каскад; вне всех каскадов, пустой или сверх таблицы — нет. */
    private static void put(Frame f, double[] corner, double ex, double ey, double ez, double x, double y, double z, double sh, double sv,
                            double soot, double dust, double water, float dr, float dg, float db, double kelvin, double rise) {
        if (f.count >= MAX_BLOBS) return;
        int c = cascade(corner, x, y, z, sh, sv);
        if (c < 0) return;
        double half = 0.5 * CELL[c], h = Math.sqrt(sh * sh + half * half), v = Math.sqrt(sv * sv + half * half);
        int i = f.count++;
        f.cascade[i] = c;
        double peak = 1 / (Math.pow(2 * Math.PI, 1.5) * h * h * v);
        float[] b = f.blobs;
        set(b, 0, i, (float) (x - ex), (float) (y - ey), (float) (z - ez), (float) h);
        set(b, 1, i, (float) v, (float) (SOOT_K * soot * peak), (float) (dustK(dr, dg, db) * dust * peak),
                (float) (DROP_K * water * peak));
        set(b, 2, i, dr, dg, db, 0);
        set(b, 3, i, (float) (Math.max(0, kelvin - AIR_K) / 1000), (float) rise, 0, 0);
        float rx = (float) (REACH * h), ry = (float) (REACH * v);
        float px = (float) (x - ex), py = (float) (y - ey), pz = (float) (z - ez);
        f.min[0] = Math.min(f.min[0], px - rx);
        f.min[1] = Math.min(f.min[1], py - ry);
        f.min[2] = Math.min(f.min[2], pz - rx);
        f.max[0] = Math.max(f.max[0], px + rx);
        f.max[1] = Math.max(f.max[1], py + ry);
        f.max[2] = Math.max(f.max[2], pz + rx);
        // коробка каскада — в его границах (в последнем клуб может выходить за край)
        double span = N * CELL[c];
        double[] lo = {x - REACH * h, y - REACH * v, z - REACH * h}, hi = {x + REACH * h, y + REACH * v, z + REACH * h};
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
            float y = f.blobs[(i) * 4 + 1], v = f.blobs[(MAX_BLOBS + i) * 4];
            lo[i] = Math.max(0, (int) Math.floor((y - REACH * v - f.corner[4 * c + 1]) / cell));
            hi[i] = Math.min(N - 1, (int) Math.floor((y + REACH * v - f.corner[4 * c + 1]) / cell));
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
            float x0 = clampCell((x - REACH * h - f.corner[4 * c]) / cell), x1 = clampCell((x + REACH * h - f.corner[4 * c]) / cell + 1);
            float z0 = clampCell((z - REACH * h - f.corner[4 * c + 2]) / cell), z1 = clampCell((z + REACH * h - f.corner[4 * c + 2]) / cell + 1);
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
