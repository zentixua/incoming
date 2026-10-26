package ua.zentix.incoming.client.far;

import ua.zentix.incoming.client.fx.plume.TrailModel.Trail;

import java.util.Arrays;

/**
 * Trail points: one ring buffer for all projectiles; when full, new point overwrites oldest (oldest trails'
 * tails fade sooner — new ones remain unbroken). A point stores position, birth tick, trail look ({@link Trail}),
 * birth-time trail-law ({@code TrailModel}) values (seconds per trail metre, lifetime, ice lifetime, local wind),
 * world light, owner and previous point in same ribbon by all-time record number: link valid until that point
 * is overwritten or fades ({@link #holds}). Frame traverses live points once, oldest to newest, laying segment
 * from previous to each: O(points), allocation-free.
 */
final class TrailPoints {
    /** Maximum points across all trails: 40-rocket MLRS pack uses 860 (test), ICBM boost in world ~50. */
    static final int CAPACITY = 16384;
    /** No point. */
    static final long NONE = -1;

    final double[] x = new double[CAPACITY], y = new double[CAPACITY], z = new double[CAPACITY];
    final long[] birth = new long[CAPACITY];
    /** Packed world light at point, as in {@code LevelRenderer#getLightColor}. */
    final int[] light = new int[CAPACITY];
    /** Trail look; null — point absent (faded or removed by all-clear). */
    final Trail[] style = new Trail[CAPACITY];
    final int[] owner = new int[CAPACITY];
    /** Seconds per trail metre ({@code TrailModel.inverseSpeed}): from projectile speed when laying point. */
    final float[] inverse = new float[CAPACITY];
    /** Point visibility and contrail ice lifetime, ticks; wind at its altitude, m/s. */
    final float[] life = new float[CAPACITY], ice = new float[CAPACITY], wind = new float[CAPACITY];
    /**
     * Vapour excess over ice there, kg/m³; buoyancy scale, m, and Brunt–Väisälä frequency, 1/s, at birth
     * ({@code TrailModel.excess}, {@code riseScale}): frame does not recompute them.
     */
    final float[] excess = new float[CAPACITY], rise = new float[CAPACITY], brunt = new float[CAPACITY];
    /** Projectile path length from path start to point birth, m ({@code FarFlightView}: how far point is behind nozzle). */
    final double[] path = new double[CAPACITY];
    /** Point height above ground at birth, m ({@code TrailModel.wander}: vortices do not bend axis into ground); unknown ground — infinity. */
    final float[] clearance = new float[CAPACITY];
    /**
     * Axis bends ({@code TrailModel.wander}) at whole ages k and k + 1, ticks (x, y, z — six numbers per point):
     * frame interpolates, computed once per tick rather than each frame. bendAge is k (−1 — not computed).
     */
    final int[] bendAge = new int[CAPACITY];
    final float[] bend = new float[6 * CAPACITY];
    /** Stability class at birth: used for lifetime and spreading alike. */
    final float[] stability = new float[CAPACITY];
    /** Launch cloud owning point matter (jet struck ground then); null — trail. */
    final ua.zentix.incoming.client.fx.smoke.LaunchCloud[] cloud = new ua.zentix.incoming.client.fx.smoke.LaunchCloud[CAPACITY];
    /**
     * Launch-cloud point's matter fraction in nozzle-to-ground jet column ({@code LaunchCloud.column}): while
     * jet strikes ground, from current nozzle position; afterward as it was when jet left ground (this column
     * remains as trail).
     */
    final float[] column = new float[CAPACITY];
    /** Previous point record number in same ribbon, or {@link #NONE}. */
    final long[] prev = new long[CAPACITY];
    /**
     * Point ends ribbon ({@link #finish}): ribbon tapers there. Otherwise burnt-out engine's trail end cuts
     * straight across, and overlapping salvo ribbon ends appear as stripes.
     */
    final boolean[] last = new boolean[CAPACITY];
    /** Next record number. */
    private long next;
    /** Points with lower numbers faded or overwritten. */
    private long first;

    /** Record number's array slot. */
    static int index(long serial) {
        return (int) (serial % CAPACITY);
    }

    /**
     * New ribbon point: prev is previous point in same ribbon ({@link #NONE} — ribbon starts here); trail-law
     * birth values: inverse — seconds per metre, life and ice — ticks, wind — m/s, excess — kg/m³, rise — m,
     * brunt — 1/s, stability — class.
     *
     * @return record number
     */
    long add(double x, double y, double z, long birth, Trail style, int light, int owner, long prev, float inverse, float life, float ice,
             float wind, float excess, float rise, float brunt, float stability) {
        int i = index(next);
        this.stability[i] = stability;
        this.excess[i] = excess;
        this.rise[i] = rise;
        this.brunt[i] = brunt;
        this.inverse[i] = inverse;
        this.life[i] = life;
        this.ice[i] = ice;
        this.wind[i] = wind;
        this.x[i] = x;
        this.y[i] = y;
        this.z[i] = z;
        this.birth[i] = birth;
        this.light[i] = light;
        this.style[i] = style;
        this.owner[i] = owner;
        this.prev[i] = prev;
        cloud[i] = null;
        column[i] = 0;
        clearance[i] = Float.POSITIVE_INFINITY;
        bendAge[i] = -1;
        last[i] = false;
        // ribbon resumed (path present again) — previous end is no longer an end
        if (holds(prev)) last[index(prev)] = false;
        next++;
        first = Math.max(first, next - CAPACITY);
        return next - 1;
    }

    /** Ribbon ends at this point (engine burnt out, path ended); a following new point clears this. */
    void finish(long serial) {
        if (holds(serial)) last[index(serial)] = true;
    }

    /** Oldest potentially live point (number). */
    long first() {
        return first;
    }

    /** After last recorded point (number). */
    long end() {
        return next;
    }

    /** Point with this number remains in buffer: not overwritten or faded from tail. */
    boolean holds(long serial) {
        return serial >= first && serial < next;
    }

    boolean isEmpty() {
        return first == next;
    }

    /** Trim points faded by now from buffer tail (long and short trails coexist farther in buffer — frame filters them). */
    void expire(double now) {
        while (first < next) {
            int i = index(first);
            if (style[i] != null && now - birth[i] < life[i]) return;
            style[i] = null;
            cloud[i] = null;
            first++;
        }
    }

    /** World frozen ({@code /tick freeze}) for a tick: live points do not age, like particles. */
    void hold() {
        for (long s = first; s < next; s++) birth[index(s)]++;
    }

    /** Projectile trail vanishes immediately (all-clear: flight ended without explosion). */
    void kill(int owner) {
        for (long s = first; s < next; s++) {
            int i = index(s);
            if (this.owner[i] == owner) style[i] = null;
        }
    }

    void clear() {
        Arrays.fill(style, null);
        Arrays.fill(cloud, null);
        first = next;
    }
}
