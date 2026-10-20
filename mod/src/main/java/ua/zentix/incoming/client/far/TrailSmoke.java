package ua.zentix.incoming.client.far;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

/**
 * След снаряда объёмом в сетке дыма у глаза: отрезки лент ({@link TrailPoints}) с той же массой и тем же сечением, что у
 * ленты ({@code TrailModel}), — гауссы по отрезкам ({@link SmokeGrid.Sink#segment}). Источник сетки — у каждого снаряда
 * свой (сетка кладёт источники целиком, от ближних): не влез — его след весь лентой ({@link #inGrid}). Соседние отрезки
 * одной ленты сливаются в один, пока лента прямая (точка отходит от хорды не больше четверти σ), сечение и масса на метр
 * меняются не больше чем в {@value #MERGE} раза и каскад тот же: прямой след залпа — несколько клубов, а не десятки.
 * Отрезки кадра строит {@link FarFlightView#prepareVolume} до раскладки сетки.
 */
final class TrailSmoke implements SmokeGrid.Source {
    /** Отрезки сливаются, пока сечение и масса на метр меняются не больше чем во столько раз. */
    static final double MERGE = 1.3;
    /** Точек в одном отрезке не больше. */
    static final int RUN_POINTS = 16;
    /** Отклонение точки от хорды отрезка — не больше этой доли σ. */
    static final double STRAIGHT = 0.25;

    /** Отрезков в кадре не больше (таблица сетки — 512 клубов). */
    static final int CAPACITY = 4096;
    /** Отрезки кадра: начало и конец, σ (сумма по точкам), массы сажи и капель, подъём (сумма), точек, σ наименьшая и наибольшая, масса на метр (наименьшая и наибольшая), клетка каскада, следующий отрезок того же снаряда. */
    static final double[] SX = new double[CAPACITY], SY = new double[CAPACITY], SZ = new double[CAPACITY], EX = new double[CAPACITY],
            EY = new double[CAPACITY], EZ = new double[CAPACITY], SIGMA = new double[CAPACITY], SOOT = new double[CAPACITY],
            WATER = new double[CAPACITY], RISE = new double[CAPACITY], SMIN = new double[CAPACITY], SMAX = new double[CAPACITY],
            LMIN = new double[CAPACITY], LMAX = new double[CAPACITY];
    static final float[] CELL = new float[CAPACITY];
    static final int[] POINTS = new int[CAPACITY], NEXT = new int[CAPACITY];
    static int runs;

    private static final Int2ObjectMap<TrailSmoke> BY_OWNER = new Int2ObjectOpenHashMap<>();
    /** Кадр сетки (номер {@link FarFlightView#prepareVolume}) и поколение (сброс снимает прежние источники). */
    static int frame, generation;

    final int owner;
    private final int born;
    /** Первый и последний отрезок кадра; кадр, в котором они есть. */
    int head = -1, tail = -1, at;
    /** Последний кадр, в котором источник отдал отрезки сетке. */
    int emitted;
    /** Отрезки легли в сетку (иначе весь след — лентой). */
    boolean inGrid;

    private TrailSmoke(int owner) {
        this.owner = owner;
        this.born = generation;
    }

    /** Источник снаряда owner в этом кадре (новый — в сетку). */
    static TrailSmoke of(int owner) {
        TrailSmoke s = BY_OWNER.get(owner);
        if (s == null || s.emitted < frame - 1) {
            s = new TrailSmoke(owner);
            s.emitted = frame;
            BY_OWNER.put(owner, s);
            SmokeGrid.add(s);
        }
        if (s.at != frame) {
            s.at = frame;
            s.head = s.tail = -1;
        }
        return s;
    }

    /** Отрезки следа снаряда owner лежат в сетке в этом кадре: доля объёма его точек — их, остальное — лента. */
    static boolean volume(int owner) {
        TrailSmoke s = BY_OWNER.get(owner);
        return s != null && s.at == frame && s.inGrid && SmokeGrid.on();
    }

    /** Новый кадр: отрезков нет, источники без отрезков в прошлом кадре — забыты. */
    static void begin() {
        frame++;
        runs = 0;
        BY_OWNER.values().removeIf(s -> s.at < frame - 1);
    }

    /** Новый отрезок от точки (x, y, z) с сечением sigma и подъёмом rise; номер или −1 (кадр полон). */
    static int open(TrailSmoke s, double x, double y, double z, double sigma, double rise, float cell) {
        if (runs >= CAPACITY) return -1;
        int r = runs++;
        SX[r] = EX[r] = x;
        SY[r] = EY[r] = y;
        SZ[r] = EZ[r] = z;
        SIGMA[r] = sigma;
        SMIN[r] = SMAX[r] = sigma;
        LMIN[r] = Double.MAX_VALUE;
        LMAX[r] = 0;
        SOOT[r] = WATER[r] = 0;
        RISE[r] = rise;
        POINTS[r] = 1;
        CELL[r] = cell;
        NEXT[r] = -1;
        if (s.tail >= 0) NEXT[s.tail] = r;
        else s.head = r;
        s.tail = r;
        return r;
    }

    /**
     * Можно ли продолжить отрезок r до точки (x, y, z) с сечением sigma, массой на метр perMetre в каскаде с клеткой cell:
     * прямо, сечение и масса на метр — в {@link #MERGE} раза, тот же каскад, не больше {@link #RUN_POINTS} точек.
     */
    static boolean extends_(int r, double x, double y, double z, double sigma, double perMetre, float cell) {
        if (POINTS[r] >= RUN_POINTS || CELL[r] != cell) return false;
        double lo = Math.min(SMIN[r], sigma), hi = Math.max(SMAX[r], sigma);
        if (hi > MERGE * lo) return false;
        double llo = Math.min(LMIN[r], perMetre), lhi = Math.max(LMAX[r], perMetre);
        if (llo > 0 && lhi > MERGE * llo) return false;
        // нынешний конец отрезка — не дальше четверти σ от новой хорды
        double cx = x - SX[r], cy = y - SY[r], cz = z - SZ[r], len2 = cx * cx + cy * cy + cz * cz;
        if (len2 < 1e-12) return true;
        double px = EX[r] - SX[r], py = EY[r] - SY[r], pz = EZ[r] - SZ[r];
        double k = (px * cx + py * cy + pz * cz) / len2;
        double qx = px - k * cx, qy = py - k * cy, qz = pz - k * cz;
        return qx * qx + qy * qy + qz * qz <= STRAIGHT * STRAIGHT * lo * lo;
    }

    /** Отрезок r дошёл до точки (x, y, z): масса куска — его, σ и подъём — в среднее. */
    static void add(int r, double x, double y, double z, double sigma, double soot, double water, double rise, double perMetre) {
        EX[r] = x;
        EY[r] = y;
        EZ[r] = z;
        SIGMA[r] += sigma;
        RISE[r] += rise;
        POINTS[r]++;
        SMIN[r] = Math.min(SMIN[r], sigma);
        SMAX[r] = Math.max(SMAX[r], sigma);
        LMIN[r] = Math.min(LMIN[r], perMetre);
        LMAX[r] = Math.max(LMAX[r], perMetre);
        SOOT[r] += soot;
        WATER[r] += water;
    }

    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        if (born != generation || at != frame) return false;
        emitted = frame;
        for (int r = head; r >= 0; r = NEXT[r]) {
            if (POINTS[r] < 2 || SOOT[r] + WATER[r] <= 0) continue;
            double sigma = SIGMA[r] / POINTS[r];
            out.segment(0.5 * (SX[r] + EX[r]), 0.5 * (SY[r] + EY[r]), 0.5 * (SZ[r] + EZ[r]), 0.5 * (EX[r] - SX[r]), 0.5 * (EY[r] - SY[r]),
                    0.5 * (EZ[r] - SZ[r]), sigma, sigma, SOOT[r], 0, WATER[r], 1, 1, 1, CloudModel.T_AIR_K, RISE[r] / POINTS[r]);
        }
        return true;
    }

    @Override
    public void placed(boolean inGrid) {
        this.inGrid = inGrid;
    }

    @Override
    public boolean ended() {
        return born != generation;
    }

    /** Выход из мира, сброс дальней картинки: прежние источники уходят из сетки. */
    static void reset() {
        generation++;
        BY_OWNER.clear();
        runs = 0;
    }
}
