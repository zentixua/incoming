package ua.zentix.incoming.nuclear.model;

/**
 * Фронт ударной волны обычного заряда во времени, без Minecraft (метры, секунды): давление на фронте — Кинни–Грэхем
 * ({@link BlastModel#overpressureKpa}: на земле — отражённая полусфера, в воздухе — свободная сфера), скорость фронта —
 * Ренкин–Гюгонио ({@link BlastModel#frontSpeed}), приход на дальность R — t(R) = ∫₀ᴿ dr / U(p(r)). Длина
 * положительной фазы за фронтом — t₊ на фронте ({@link BlastModel#chemicalPhaseSeconds}) × скорость фронта (⚠ хвост
 * фазы идёт со скоростью звука, фронт быстрее: длина завышена на (U − c)/c — у 3 psi на ~10 %; формула фазы у самого
 * заряда, Z меньше ~0,5, к жизни не годится — там фронт в огненном шаре и не виден). Узлы — логарифмическая сетка от
 * {@link #FIRST_M}, между ними — линейно, за таблицей — скорость звука (фронт там уже звук).
 * <p>
 * ⚠ Кинни–Грэхем — подгонка по опытам, у самого заряда (Z меньше ~0,1 м/кг^⅓) это не газодинамика продуктов, а
 * экстраполяция: ранние миллисекунды, когда фронт ещё внутри огненного шара и его не видно, — грубо. Воздушный разрыв —
 * сфера свободного воздуха и у земли: отражённой от земли волны и стебля Маха у обычного разрыва модель не считает.
 */
public final class ChemicalFront {
    private static final int NODES = 600;
    /** Первый узел, м: ближе — один отрезок от заряда. */
    static final double FIRST_M = 0.05;

    public final double tntKg;
    public final boolean surface;
    private final double[] range, time;

    private ChemicalFront(double tntKg, boolean surface, double[] range, double[] time) {
        this.tntKg = tntKg;
        this.surface = surface;
        this.range = range;
        this.time = time;
    }

    /**
     * Фронт заряда {@code tntKg} кг ТНТ на земле ({@code surface}) или в воздухе — до {@code maxRangeM} (дальше — со
     * скоростью звука).
     */
    public static ChemicalFront of(double tntKg, boolean surface, double maxRangeM) {
        double max = Math.max(maxRangeM, 2 * FIRST_M);
        double[] r = new double[NODES + 1], t = new double[NODES + 1];
        double step = Math.log(max / FIRST_M) / (NODES - 1);
        for (int i = 1; i <= NODES; i++) {
            r[i] = FIRST_M * Math.exp(step * (i - 1));
            // отрезок — по скорости в его середине (средняя точка: у сетки в 1,5 % шага ошибка — доли процента)
            double mid = 0.5 * (r[i - 1] + r[i]);
            t[i] = t[i - 1] + (r[i] - r[i - 1]) / BlastModel.frontSpeed(BlastModel.overpressureKpa(mid, tntKg, surface));
        }
        return new ChemicalFront(tntKg, surface, r, t);
    }

    /** Таблиц в памяти не больше (залп — один заряд на все снаряды). */
    private static final int CACHED = 16;
    private static final java.util.LinkedHashMap<Long, ChemicalFront> CACHE = new java.util.LinkedHashMap<>(CACHED, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Long, ChemicalFront> eldest) {
            return size() > CACHED;
        }
    };
    /** Дальность таблиц {@link #cached}, м: дальше фронт — звук (у 450 кг на 2,5 км скачок плотности — 10⁻⁴). */
    public static final double CACHED_RANGE_M = 2500;

    /** Фронт заряда до {@link #CACHED_RANGE_M} — общий для одинаковых зарядов (залп, повтор). */
    public static ChemicalFront cached(double tntKg, boolean surface) {
        long key = Double.doubleToLongBits(tntKg) * 31 + (surface ? 1 : 0);
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(key, k -> of(tntKg, surface, CACHED_RANGE_M));
        }
    }

    /** Время прихода фронта на дальность R, с. */
    public double arrivalSeconds(double rangeM) {
        if (rangeM <= 0) return 0;
        if (rangeM >= range[NODES]) return time[NODES] + (rangeM - range[NODES]) / BlastModel.SOUND_SPEED;
        return interpolate(range, time, rangeM);
    }

    /** Радиус фронта в момент t, м. */
    public double radiusAt(double seconds) {
        if (seconds <= 0) return 0;
        if (seconds >= time[NODES]) return range[NODES] + (seconds - time[NODES]) * BlastModel.SOUND_SPEED;
        return interpolate(time, range, seconds);
    }

    /** Длина положительной фазы за фронтом радиуса R, м: t₊ × скорость фронта. */
    public double phaseLength(double rangeM) {
        return BlastModel.chemicalPhaseSeconds(rangeM, tntKg, surface) * BlastModel.frontSpeed(kpa(rangeM));
    }

    /** Давление на фронте радиуса R, кПа. */
    public double kpa(double rangeM) {
        return BlastModel.overpressureKpa(rangeM, tntKg, surface);
    }

    private static double interpolate(double[] xs, double[] ys, double x) {
        int lo = 0, hi = xs.length - 1;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (xs[m] <= x) lo = m;
            else hi = m;
        }
        double f = (x - xs[lo]) / Math.max(xs[hi] - xs[lo], 1e-300);
        return ys[lo] + f * (ys[hi] - ys[lo]);
    }
}
