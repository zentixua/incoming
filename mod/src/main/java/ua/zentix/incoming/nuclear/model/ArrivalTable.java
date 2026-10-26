package ua.zentix.incoming.nuclear.model;

/**
 * Shock front arrival time by slant range from detonation: at the ground — Brode ({@link HobBlast#arrivalSeconds}, ground
 * range at the same slant range); closer than burst height (in air beneath it) — free air
 * ({@link HobBlast#freeAirArrivalSeconds}, joined to Brode beneath the burst). Nodes use a logarithmic grid (1000 from
 * 1 m), linear interpolation between them; beyond the table — sound speed.
 */
public final class ArrivalTable {
    private static final int NODES = 1000;
    private static final double FIRST_NODE_M = 1.0;

    /** Ranges, m (first node — 0), increasing. */
    private final double[] range;
    /** Arrival times at nodes, s, increasing. */
    private final double[] time;

    private ArrivalTable(double[] range, double[] time) {
        this.range = range;
        this.time = time;
    }

    /**
     * @param hobM height of burst above ground, m (0 — surface burst)
     */
    public static ArrivalTable of(double yieldKt, double hobM, double maxRangeM) {
        double hob = Math.max(0, hobM), max = Math.max(maxRangeM, FIRST_NODE_M * 2);
        double[] r = new double[NODES + 1];
        double[] t = new double[NODES + 1];
        double step = Math.log(max / FIRST_NODE_M) / (NODES - 1);
        // free air beneath the burst joins ground arrival beneath it (difference of fractions of a percent)
        double stitch = hob <= 0 ? 1 : HobBlast.arrivalSeconds(0, hob, yieldKt) / HobBlast.freeAirArrivalSeconds(hob, yieldKt);
        for (int i = 1; i <= NODES; i++) {
            r[i] = FIRST_NODE_M * Math.exp(step * (i - 1));
            t[i] = r[i] < hob ? HobBlast.freeAirArrivalSeconds(r[i], yieldKt) * stitch
                    : HobBlast.arrivalSeconds(Math.sqrt(r[i] * r[i] - hob * hob), hob, yieldKt);
            t[i] = Math.max(t[i], t[i - 1] + 1e-12);
        }
        return new ArrivalTable(r, t);
    }

    /** Last node's range, m. */
    public double maxRange() {
        return range[NODES];
    }

    /** Front arrival time at range R, s. */
    public double arrivalSeconds(double rangeM) {
        if (rangeM <= 0) return 0;
        if (rangeM >= range[NODES]) return time[NODES] + (rangeM - range[NODES]) / BlastModel.SOUND_SPEED;
        return interpolate(range, time, rangeM);
    }

    /** Front radius at time t, s (inverse of {@link #arrivalSeconds}). */
    public double radiusAt(double seconds) {
        if (seconds <= 0) return 0;
        if (seconds >= time[NODES]) return range[NODES] + (seconds - time[NODES]) * BlastModel.SOUND_SPEED;
        return interpolate(time, range, seconds);
    }

    private static double interpolate(double[] xs, double[] ys, double x) {
        int lo = 0, hi = xs.length - 1;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (xs[m] <= x) lo = m;
            else hi = m;
        }
        double f = (x - xs[lo]) / (xs[hi] - xs[lo]);
        return ys[lo] + f * (ys[hi] - ys[lo]);
    }
}
