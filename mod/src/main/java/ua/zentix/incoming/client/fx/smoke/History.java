package ua.zentix.incoming.client.fx.smoke;

/**
 * История величины источника по времени (сила очага пожара): круговой буфер записей по возрастанию времени, значение в
 * миг t — по прямой между записями, раньше первой — первая, позже последней — последняя. Без Minecraft.
 */
public final class History {
    private final double[] when, value;
    private int records;

    public History(int capacity) {
        when = new double[capacity];
        value = new double[capacity];
    }

    /** Запись: в миг t — v (t не меньше прошлой записи). */
    public void record(double t, double v) {
        int i = records++ % when.length;
        when[i] = t;
        value[i] = v;
    }

    /** Значение в миг t (поиск — делением пополам); записей нет — 0. */
    public double at(double t) {
        if (records == 0) return 0;
        int n = Math.min(records, when.length), first = records - n, last = (records - 1) % when.length;
        if (t >= when[last]) return value[last];
        if (t <= when[first % when.length]) return value[first % when.length];
        // номера first..records−1 идут по времени: последняя запись не позже t
        int lo = first, hi = records - 1;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (when[m % when.length] <= t) lo = m;
            else hi = m;
        }
        int i = lo % when.length, j = hi % when.length;
        double f = (t - when[i]) / Math.max(1e-9, when[j] - when[i]);
        return value[i] + (value[j] - value[i]) * f;
    }
}
