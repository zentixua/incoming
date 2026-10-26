package ua.zentix.incoming.client.fx.smoke;

/**
 * Source magnitude history over time (fire source strength): circular buffer of records in increasing time order;
 * value at t interpolates linearly, first value before first record, last value after last. Without Minecraft.
 */
public final class History {
    private final double[] when, value;
    private int records;

    public History(int capacity) {
        when = new double[capacity];
        value = new double[capacity];
    }

    /** Record: value v at t (t no earlier than previous record). */
    public void record(double t, double v) {
        int i = records++ % when.length;
        when[i] = t;
        value[i] = v;
    }

    /** Value at t (binary search); 0 if no records. */
    public double at(double t) {
        if (records == 0) return 0;
        int n = Math.min(records, when.length), first = records - n, last = (records - 1) % when.length;
        if (t >= when[last]) return value[last];
        if (t <= when[first % when.length]) return value[first % when.length];
        // Indices first..records−1 follow time: latest record no later than t.
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
