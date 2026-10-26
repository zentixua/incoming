package ua.zentix.incoming.nuclear.model;

/**
 * In-game front arrival, in blocks and ticks. Out to {@code slowFrom} blocks it follows the model ({@link ArrivalTable}: supersonic,
 * fireball and flash as in real life); then it smoothly slows to far-zone speed {@code far} by {@code slowTo} and
 * continues at that speed while the dust wall is visible (out to {@code soundFrom}, 0.3 psi). Far-zone speed is a fraction of sound speed
 * (server setting, stored as {@code Detonation.wave} per detonation): at the real 17 blocks per tick, ruins across the entire city
 * must appear within seconds; slower gives the server more time and lets the player see the wall roll. Beyond
 * the wall only sound remains, traveling at sound speed, so a distant strike (Distant Horizons, 5 km) is heard in seconds,
 * not minutes. Pressure remains a function of range, as in the model; only arrival time changes. Dust wall, sound, damage,
 * knockback and ruins take their timing from here and arrive together.
 */
public final class FrontProfile {
    /** Distance by which the front finishes slowing, blocks (at least three times {@code slowFrom}). */
    public static final double SLOW_TO = 600;
    /** Maximum distance where the front follows the model (at 15 kt and scale 1, two fireball radii are 400–500 blocks). */
    public static final double SLOW_FROM_MAX = 200;
    /** Sound speed, blocks per tick (at any scale). */
    public static final double SOUND_SPEED = 17.15;
    private static final int NODES = 2000;

    /** Distances, blocks (first node is 0), ascending. */
    private final double[] range;
    /** Arrival times at nodes, ticks, ascending. */
    private final double[] time;

    /** Speed beyond the table, blocks per tick. */
    private final double tail;

    private FrontProfile(double[] range, double[] time, double tail) {
        this.range = range;
        this.time = time;
        this.tail = tail;
    }

    /** Distance where the front follows the model: two fireball radii, no farther than {@link #SLOW_FROM_MAX}. */
    public static double slowFrom(double fireballRadius) {
        return Math.min(2 * fireballRadius, SLOW_FROM_MAX);
    }

    /**
     * @param model    model front arrival, m and s
     * @param scale    blocks per model meter (front time in ticks is seconds × 20 × scale)
     * @param slowFrom  distance where the front follows the model, blocks
     * @param soundFrom end of the dust wall (0.3 psi), blocks: sound speed beyond
     * @param maxRange  table range, blocks
     * @param far       far-zone dust-wall speed, blocks per tick (no faster than sound)
     */
    public static FrontProfile of(ArrivalTable model, double scale, double slowFrom, double soundFrom, double maxRange, double far) {
        far = Math.max(0.1, Math.min(SOUND_SPEED, far));
        double slowTo = Math.max(SLOW_TO, slowFrom * 3);
        double max = Math.max(maxRange, slowTo) * 1.05;
        double[] r = new double[NODES + 1], t = new double[NODES + 1];
        double v0 = speed(model, scale, slowFrom, far);
        for (int i = 1; i <= NODES; i++) {
            // denser near the epicenter: the front changes fastest there
            double f = (double) i / NODES;
            r[i] = max * f * f;
            double dr = r[i] - r[i - 1];
            double dtModel = ticks(model, scale, r[i]) - ticks(model, scale, r[i - 1]);
            double mid = (r[i] + r[i - 1]) * 0.5;
            // sound speed far away, as in real life, without a transition: the model front is already at least sonic
            double v = mid > soundFrom ? SOUND_SPEED : far >= SOUND_SPEED ? Double.POSITIVE_INFINITY : limit(mid, slowFrom, slowTo, v0, far);
            t[i] = t[i - 1] + Math.max(dtModel, dr / v);
        }
        return new FrontProfile(r, t, max > soundFrom ? SOUND_SPEED : far);
    }

    /** Speed limit at distance {@code r}, blocks per tick: none before {@code from}, then smoothly down to {@code far}. */
    static double limit(double r, double from, double to, double v0, double far) {
        if (r <= from) return Double.POSITIVE_INFINITY;
        double f = Math.min(1, (r - from) / (to - from));
        double s = f * f * (3 - 2 * f);
        return v0 + (far - v0) * s;
    }

    private static double ticks(ArrivalTable model, double scale, double blocks) {
        return model.arrivalSeconds(blocks / scale) * 20 * scale;
    }

    /** Model front speed at distance {@code blocks}, blocks per tick (at least {@code far}). */
    private static double speed(ArrivalTable model, double scale, double blocks, double far) {
        double h = Math.max(0.5, blocks * 0.01);
        double dt = ticks(model, scale, blocks + h) - ticks(model, scale, Math.max(0, blocks - h));
        return Math.max(far, (blocks + h - Math.max(0, blocks - h)) / Math.max(dt, 1e-9));
    }

    /** Ticks until the front reaches distance {@code blocks} from the detonation point. */
    public double arrivalTicks(double blocks) {
        if (blocks <= 0) return 0;
        if (blocks >= range[NODES]) return time[NODES] + (blocks - range[NODES]) / tail;
        return interpolate(range, time, blocks);
    }

    /** Front distance from detonation after {@code ticks}, blocks (inverse of {@link #arrivalTicks}). */
    public double radiusAt(double ticks) {
        if (ticks <= 0) return 0;
        if (ticks >= time[NODES]) return range[NODES] + (ticks - time[NODES]) * tail;
        return interpolate(time, range, ticks);
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
