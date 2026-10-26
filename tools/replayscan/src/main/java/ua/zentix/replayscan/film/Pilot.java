package ua.zentix.replayscan.film;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * FPV pilot (Artem, 13.10: "a person sees what happens, smoke, and avoids obstacles in flight; transfer
 * that principle"): plan camera flight like an FPV-drone pilot using replay foreknowledge: client terrain,
 * buildings/crowns ({@link Grid}), each impact smoke over time ({@link Plume}), projectile and impact site.
 * Path optimization:
 * <ul>
 * <li>Pilot controls ({@link Controls}) define speed, heading turn and climb at {@link #KNOT}-tick nodes
 * (smooth between), position/heading at impact flyby; integrate both ways by ticks. Always flyable arcs
 * and accelerations, not a zigzag of points;</li>
 * <li>Look at action: impact site (projectile enters frame), fireball, then increasingly forward; follows
 * with hand lag {@link #LAG}; roll from lateral acceleration like a turning drone;</li>
 * <li>Path cost ({@link #cost}) sums window ticks: camera within clearance of ground/buildings/crowns or
 * above absent chunks; inside smoke; smoke/terrain on sightline; lower frame ground beyond replay chunks
 * (export {@code low_out}); impact wrong distance (too small or clipped); speed/acceleration beyond drone
 * limits; look turn fast enough to blur distance; sideways flight (FPV camera faces travel); projectile
 * missing before impact; impact invisible in its first ticks. Other window impacts in frame are bonuses;</li>
 * <li>Search seeds: straight/curved passes, headings, sides, lateral/vertical offsets; refine best diverse
 * seeds with cross-entropy: sample controls around best, narrow spread using best.</li>
 * </ul>
 * Add operator hands and explosion impulses ({@link Hands}) over path; {@link FrameLog} checks each exported frame.
 */
final class Pilot {
    /** The client’s relief: the height of the ground (the top of the blocks that hold the movement, with foliage) in a column; NaN - no chunk. */
    interface Terrain {
        double ground(int x, int z);
    }

    /** Pilot handle assembly pitch, tick. */
    static final int KNOT = 24;
    /** Handles: course rotation - no steeper, ° per tick (for 1 block per tick - circle in 14 blocks); set and decrease, blocks per tick. */
    static final double W_MAX = 4, CLIMB = 0.5;
    /** The drone tilts forward at a speed of 20 m/s - 25° (air resistance): flies sideways - roll by the same amount. */
    static final double TILT = 0.0114;
    /** The path is calculated with a margin before the window (the output puts the camera keys 42 tick before the start) and after. */
    static final int BEFORE = 50, AFTER = 10;
    /** Delay of sight behind the target, tick (like a chase trailer). */
    static final double LAG = 2;
    /** Drone speed: lowest, comfortable and highest, blocks per tick (10, 21 and 30 m/s). */
    static final double V_MIN = 0.5, V_PREF = 1.05, V_MAX = 1.5;
    /** Highest acceleration, blocks per tick² (18 m/s², almost 2 g). */
    static final double A_MAX = 0.045;
    /** Highest angular velocity of gaze, ° per tick (44°/s): the farthest is blurred faster. */
    static final double TURN = 2.2;
    /**
     * Looking sideways to the course, without counting up to so many degrees: on approach - almost on course (FPV flies towards the target), at impact -
     *  turns towards the ball as it flies past (for pilots - fly-by with yaw), after - forward again; changes no faster
     * {@link #CRAB_RATE} ° per tick.
     */
    static final double CRAB_IN = 20, CRAB_PASS = 60, CRAB_OUT = 30, CRAB_RATE = 1.5;
    /** Reserve from land, houses and crowns in height, blocks; the neighborhood of the column where the tallest block is searched. */
    static final double CLEAR = 3;
    static final int REACH = 2;
    /** The height above the ground where the drone flies “low”: lower – the ground is nearby, higher – no longer low. */
    static final double LOW = 5, HIGH = 14;
    /**
     * Stock against smoke, blocks; strikes beyond this from the impact of the passage into the pilot's smoke are not taken. The pilot measures the smoke at the camera
     *  with a grid of output rays ({@link #smother}): more than {@link #SMOTHER_FROM} frames are closed - this already counts, from the output threshold
     *  ({@link FrameLog#SMOTHERED}) - the frame is in the smoke.
     */
    static final double SMOKE = 3, SMOTHER_FROM = 0.3;
    static final double SMOKE_REACH = 260;
    /** Maximum roll, °; up and down look - no more, °; after the impact - forward along the course so much downwards, °. */
    static final double ROLL = 35, UP = 35, DOWN = 60, AHEAD = 12;
    /** The roll does not change faster, ° per tick (40°/s): faster - the path wobbles. */
    static final double ROLL_RATE = 1.5;
    /** How far down the frame is checked for chunks (like {@link FrameLog#LOW_NEAR}); vanilla sea level. */
    static final double LOW_NEAR = 128, SEA = 63;
    /** Seeds: courses, passing through as many ticks after impact; brings the best different ones. */
    static final int HEADINGS = 12, PASS = 24, SEEDS = 6;
    /** Cross entropy: sampling the best steps in it; scatter of reference points at the beginning and smallest, blocks. */
    static final int POP = 40, ELITE = 6, ITER = 30;
    /** The smallest search spread is the share of the initial one ({@link Controls#sigma}). */
    static final double SIG_MIN = 0.03;

    /** Relief with a square {@code ±half} at the place: the heights of the columns and the largest in the {@link #REACH} blocks around. */
    static final class Grid {
        final int x0, z0, n;
        private final float[] ground, high;

        Grid(Terrain t, int cx, int cz, int half) {
            n = 2 * half + 1;
            x0 = cx - half;
            z0 = cz - half;
            ground = new float[n * n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) ground[i * n + j] = (float) t.ground(x0 + i, z0 + j);
            }
            // greatest height squared ±REACH: by x, then by z; next to it is a column without a chunk - unknown
            float[] tmp = new float[n * n];
            high = new float[n * n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) tmp[i * n + j] = max(ground, i - REACH, i + REACH, j, j, n);
            }
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) high[i * n + j] = max(tmp, i, i, j - REACH, j + REACH, n);
            }
        }

        private static float max(float[] v, int i0, int i1, int j0, int j1, int n) {
            float m = Float.NEGATIVE_INFINITY;
            for (int i = i0; i <= i1; i++) {
                for (int j = j0; j <= j1; j++) {
                    if (i < 0 || j < 0 || i >= n || j >= n) return Float.NaN;
                    float h = v[i * n + j];
                    if (Float.isNaN(h)) return Float.NaN;
                    m = Math.max(m, h);
                }
            }
            return m;
        }

        /** Height of the ground; NaN - there is no chunk or behind the square. */
        double ground(double x, double z) {
            return at(ground, x, z);
        }

        /** The greatest height in {@link #REACH} blocks around (camera margin from walls and crowns); NaN - there is no chunk somewhere nearby. */
        double high(double x, double z) {
            return at(high, x, z);
        }

        private double at(float[] v, double x, double z) {
            int i = Mth.floor(x) - x0, j = Mth.floor(z) - z0;
            return i < 0 || j < 0 || i >= n || j >= n ? Double.NaN : v[i * n + j];
        }

        /** The relief closes the segment (closer to {@code spare} blocks towards the end - does not count); where there is no chunk, it does not close. */
        boolean hides(Vec3 from, Vec3 to, double spare) {
            Vec3 d = to.subtract(from);
            double len = d.length();
            for (double k = 1; k < len - spare; k += 2) {
                double u = k / len, y = from.y + d.y * u, g = ground(from.x + d.x * u, from.z + d.z * u);
                if (!Double.isNaN(g) && y < g) return true;
            }
            return false;
        }

        /**
         * An event with a diameter of {@code size} in {@code k} is visible as it is considered by the conclusion ({@link Cameras#keySeen}): in the frame and
         *  at least two rays out of three are open - to the middle and to the sides at a diameter of 0,35 across the view; The last
         * {@code max(1.5, 0.4·size)} blocks of the event do not count. The relief is in columns, not block shapes.
         */
        boolean seen(Cameras.View v, Vec3 k, double size) {
            if (!v.in(k, 1)) return false;
            Vec3 r = v.right().scale(size * 0.35);
            double spare = Math.max(1.5, size * 0.4);
            int open = 0;
            for (Vec3 q : new Vec3[]{k, k.add(r), k.subtract(r)}) if (!hides(v.eye(), q, spare)) open++;
            return open >= 2;
        }

        /**
         * The bottom of the frame (rays at the bottom edge: left, middle, right) on the ground behind the replay chunks - like {@code low_out}
         * output ({@link FrameLog}); how many such rays?
         */
        int lowOut(Cameras.View v) {
            int out = 0;
            for (double sx : new double[]{-0.9, 0, 0.9}) {
                Vec3 d = v.fwd().add(v.right().scale(sx * v.tanX())).add(v.up().scale(-0.95 * v.tanY())).normalize();
                if (d.y >= 0) continue;
                double last = SEA;
                for (double k = 0; k <= LOW_NEAR; k += 4) {
                    double x = v.eye().x + d.x * k, y = v.eye().y + d.y * k, z = v.eye().z + d.z * k;
                    // the top block of the column, like the pin (LevelChunk.getHeight), not the space above it
                    double g = ground(x, z) - 1;
                    if (Double.isNaN(g)) {
                        if (v.eye().y + d.y * LOW_NEAR <= last + 1) out++;
                        break;
                    }
                    last = g;
                    if (y <= g + 1) break;
                }
            }
            return out;
        }
    }

    /** Path along tick {@code from…}: location, speed, acceleration, gaze (course, pitch), roll. */
    record Track(int from, Vec3[] pos, Vec3[] vel, Vec3[] acc, double[] yaw, double[] pitch, double[] roll) {
        int index(double t) {
            return Mth.clamp((int) Math.round(t - from), 0, pos.length - 1);
        }
    }

    /** Found flight: candidate name, route, price and what it is from. */
    record Flight(String name, double[] controls, double cost, String why) {}

    final int a, b;
    final double fov, aspect, tanX, tanY;
    final Subject subject;
    /** A blow for which a pass is made. */
    final Subject.Key main;
    final List<Plume> plumes;
    /** Other window impacts: in the frame - a plus. */
    final List<Plume> others;
    /**
     * Smoke according to window patterns ({@link #a}…{@link #b} via 2 tick): figures of blows next to each other, by blows in a row; each blow has the
     *  end of its figures, the ball around them (it is checked first: there are hundreds of blows in 4 minutes of war) and whether it is the smoke of the blow
     *  of the passage.
     */
    private final Plume.Shape[][] smoke;
    private final int[][] groupEnd;
    private final double[][] bound;
    private final boolean[][] own;
    final Grid grid;
    final long seed;
    /** tick the path (with a margin before and after the window) and tick passing the impact - from it the path is counted in both directions. */
    final int t0, t1, tp;
    final Controls controls;

    Pilot(int a, int b, double fov, double aspect, Subject subject, Subject.Key main, List<Plume> plumes, Grid grid, long seed) {
        this.a = a;
        this.b = b;
        this.fov = fov;
        this.aspect = aspect;
        this.tanY = Math.tan(Math.toRadians(fov / 2));
        this.tanX = tanY * aspect;
        this.subject = subject;
        this.main = main;
        this.plumes = plumes;
        this.others = plumes.stream().filter(p -> p.t() >= a + 10 && p.t() <= b - 6
                && !(Math.abs(p.t() - main.t()) <= 4 && Plume.beside(p.pos(), main.pos(), Plume.OWN))).toList();
        this.grid = grid;
        this.seed = seed;
        this.t0 = a - BEFORE;
        this.t1 = b + AFTER;
        this.tp = Mth.clamp(main.t() + PASS, t0, t1);
        this.controls = new Controls();
        int n = (b - a) / 2 + 1;
        this.smoke = new Plume.Shape[n][];
        this.groupEnd = new int[n][];
        this.bound = new double[n][];
        this.own = new boolean[n][];
        List<Plume.Shape> shapes = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        List<double[]> bounds = new ArrayList<>();
        List<Boolean> mine = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int t = a + 2 * k;
            shapes.clear();
            ends.clear();
            bounds.clear();
            mine.clear();
            for (Plume pl : plumes) {
                if (!pl.alive(t)) continue;
                int before = shapes.size();
                pl.shapes(t, shapes);
                for (int i = shapes.size() - 1; i >= before; i--) {
                    Plume.Shape s = shapes.get(i);
                    if (s.alpha() < Plume.SEEN || Math.hypot(s.x() - main.pos().x, s.z() - main.pos().z) > SMOKE_REACH + s.r()) shapes.remove(i);
                }
                if (shapes.size() == before) continue;
                ends.add(shapes.size());
                bounds.add(bound(shapes.subList(before, shapes.size())));
                mine.add(Plume.beside(pl.pos(), main.pos(), Plume.OWN));
            }
            smoke[k] = shapes.toArray(new Plume.Shape[0]);
            groupEnd[k] = ends.stream().mapToInt(Integer::intValue).toArray();
            bound[k] = new double[4 * bounds.size()];
            for (int g = 0; g < bounds.size(); g++) System.arraycopy(bounds.get(g), 0, bound[k], 4 * g, 4);
            own[k] = new boolean[mine.size()];
            for (int g = 0; g < own[k].length; g++) own[k][g] = mine.get(g);
        }
    }

    /** The ball around the impact figures: the middle of the frame and its semi-diagonal. */
    private static double[] bound(List<Plume.Shape> shapes) {
        double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        for (Plume.Shape s : shapes) {
            x0 = Math.min(x0, s.x() - s.r());
            x1 = Math.max(x1, s.x() + s.r());
            z0 = Math.min(z0, s.z() - s.r());
            z1 = Math.max(z1, s.z() + s.r());
            y0 = Math.min(y0, s.cylinder() ? s.y() : s.y() - s.r());
            y1 = Math.max(y1, s.cylinder() ? s.top() : s.y() + s.r());
        }
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        return new double[]{(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2, Math.sqrt(dx * dx + dy * dy + dz * dz) / 2};
    }

    /** The distance to the smoke of sample {@code k} (less than 0 - in the smoke), no more than {@code limit}: then the pilot doesn’t care. */
    double smokeClearance(int k, double x, double y, double z, double limit) {
        double best = limit;
        double[] bd = bound[k];
        int from = 0;
        for (int g = 0; g < groupEnd[k].length; g++) {
            int to = groupEnd[k][g];
            double dx = x - bd[4 * g], dy = y - bd[4 * g + 1], dz = z - bd[4 * g + 2];
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) - bd[4 * g + 3] < best) {
                for (int i = from; i < to; i++) best = Math.min(best, smoke[k][i].distance(x, y, z));
            }
            from = to;
        }
        return best;
    }

    /** The smoke of sample {@code k} is in the line of sight (its own smoke from the impact of the passage is not: they look at it). */
    boolean smokeOnLine(int k, Vec3 from, Vec3 to) {
        int lo = 0;
        for (int g = 0; g < groupEnd[k].length; g++) {
            int hi = groupEnd[k][g];
            if (!own[k][g] && crossesGroup(k, g, lo, hi, from, to, 0.5)) return true;
            lo = hi;
        }
        return false;
    }

    /**
     * The segment passes through the impact figure {@code g} (figures {@code lo}...{@code hi}) more opaque than {@code alpha}; first -
     * through the ball around them.
     */
    private boolean crossesGroup(int k, int g, int lo, int hi, Vec3 from, Vec3 to, double alpha) {
        double[] bd = bound[k];
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z, len2 = dx * dx + dy * dy + dz * dz;
        double cx = bd[4 * g], cy = bd[4 * g + 1], cz = bd[4 * g + 2], r = bd[4 * g + 3];
        double u = len2 < 1e-9 ? 0 : Mth.clamp(((cx - from.x) * dx + (cy - from.y) * dy + (cz - from.z) * dz) / len2, 0, 1);
        double ex = from.x + dx * u - cx, ey = from.y + dy * u - cy, ez = from.z + dz * u - cz;
        if (ex * ex + ey * ey + ez * ez >= r * r) return false;
        for (int i = lo; i < hi; i++) {
            Plume.Shape s = smoke[k][i];
            if (s.alpha() >= alpha && s.crosses(from, to)) return true;
        }
        return false;
    }

    /**
     * The share of the frame in the smoke of the camera, as measured by the output ({@link Smoke#smothered}): rays of the same grid to the same distance,
     * , the ray is closed if it enters the smoke figure of sample {@code k}. The smoke of the impact is the same: the cloud of the ball covers the entire frame
     *  it in the same way. The previous check - the middle of the frame on 8–24 blocks - did not see smoke at the edge of the frame and beyond 24 blocks: on
     *  frames clips9 (14.10) she recognized 310 from 862 frames in smoke, the grid is 535 (the rest are smoke, which the model does not know,
     * README), and from 7238 the grid marks 309.
     */
    double smother(int k, Cameras.View v) {
        Vec3 e = v.eye();
        double reach = Smoke.SMOTHER_REACH;
        // a beam of length reach enters only into a figure closer to reach: their selection is once per sample, and not for each ray (the ball of impact
        //  live replay is 30–130 blocks, screening by it misses almost everything)
        Plume.Shape[] near = new Plume.Shape[smoke[k].length];
        int m = 0, lo = 0;
        double[] bd = bound[k];
        for (int g = 0; g < groupEnd[k].length; g++) {
            int hi = groupEnd[k][g];
            double dx = e.x - bd[4 * g], dy = e.y - bd[4 * g + 1], dz = e.z - bd[4 * g + 2];
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) - bd[4 * g + 3] < reach) {
                for (int i = lo; i < hi; i++) if (smoke[k][i].distance(e.x, e.y, e.z) < reach) near[m++] = smoke[k][i];
            }
            lo = hi;
        }
        if (m == 0) return 0;
        int closed = 0;
        for (double sx : Smoke.GRID_X) {
            for (double sy : Smoke.GRID_Y) {
                Vec3 to = e.add(v.fwd().add(v.right().scale(sx * v.tanX())).add(v.up().scale(sy * v.tanY())).normalize().scale(reach));
                for (int i = 0; i < m; i++) {
                    if (near[i].crosses(e, to)) {
                        closed++;
                        break;
                    }
                }
            }
        }
        return (double) closed / (Smoke.GRID_X.length * Smoke.GRID_Y.length);
    }

    /**
     * The impact of a passage from the events of an object in the window: the one where the path of the projectile ends (its impact), without a path - the first in the window;
     *  not in the window - null.
     */
    static Subject.Key mainKey(Subject s, int a, int b) {
        List<Subject.Key> in = s.keys.stream().filter(k -> k.t() >= a && k.t() <= b).toList();
        if (in.isEmpty()) return null;
        if (Double.isNaN(s.pathLast())) return in.get(0);
        return in.stream().min(Comparator.comparingDouble(k -> Math.abs(k.t() - s.pathLast()))).orElseThrow();
    }

    // ============================================================================= What to look for?

    /** Where to look: before the impact - the place of impact (the projectile flies into the frame), then the fireball (rises), then the same. */
    Vec3 focus(double t) {
        if (t < main.t()) return main.pos().add(0, main.size() * 0.3, 0);
        return Cameras.keyPoint(main, Math.min(t, main.t() + Subject.KEY_HOLD));
    }

    /** As far as looking at the target (the rest is forward along the course): the first 2 s after the impact - all of it, then up to a quarter. */
    double weight(double t) {
        double u = t - main.t();
        return u < 40 ? 1 : Math.max(0.25, 1 - (u - 40) / 60 * 0.75);
    }

    // ================================================================================= flying along the way

    /**
     * Pilot's handles - a vector of search parameters: place and course in the tick of passage {@link #tp}, then by nodes through {@link #KNOT}
     *  ticks from {@link #t0}: speeds, course turns, climbs.
     */
    final class Controls {
        static final int PX = 0, PY = 1, PZ = 2, COURSE = 3, V = 4;
        final int knots = (t1 - t0) / KNOT + 2;

        int w() {
            return V + knots;
        }

        int climb() {
            return V + 2 * knots;
        }

        int size() {
            return V + 3 * knots;
        }

        /** Straight ({@code turn} 0) or arc: place and course in tick of passage, speed, turn of course per tick, set. */
        double[] steady(Vec3 p, double course, double v, double turn, double climb) {
            double[] x = new double[size()];
            x[PX] = p.x;
            x[PY] = p.y;
            x[PZ] = p.z;
            x[COURSE] = course;
            for (int k = 0; k < knots; k++) {
                x[V + k] = v;
                x[w() + k] = turn;
                x[climb() + k] = climb;
            }
            return x;
        }

        /** Search scatter at the beginning. */
        double[] sigma() {
            double[] s = new double[size()];
            s[PX] = s[PZ] = 10;
            s[PY] = 4;
            s[COURSE] = 20;
            for (int k = 0; k < knots; k++) {
                s[V + k] = 0.2;
                s[w() + k] = 0.7;
                s[climb() + k] = 0.12;
            }
            return s;
        }

        /** Handle {@code from} (nodes in a row) in tick {@code t}: between nodes - according to Catmull - Rom, within ±{@code lim}. */
        double at(double[] x, int from, double t, double lo, double hi) {
            double u = Mth.clamp((t - t0) / KNOT, 0, knots - 1);
            int k = Math.min(knots - 2, (int) Math.floor(u));
            double s = u - k;
            double p0 = x[from + Math.max(0, k - 1)], p1 = x[from + k], p2 = x[from + k + 1], p3 = x[from + Math.min(knots - 1, k + 2)];
            double v = 0.5 * (2 * p1 + (-p0 + p2) * s + (2 * p0 - 5 * p1 + 4 * p2 - p3) * s * s + (-p0 + 3 * p1 - 3 * p2 + p3) * s * s * s);
            return Mth.clamp(v, lo, hi);
        }
    }

    private static Vec3 dir(double course) {
        return new Vec3(-Math.sin(Math.toRadians(course)), 0, Math.cos(Math.toRadians(course)));
    }

    /** Flight along the handles: path along tick {@link #t0}...{@link #t1} from the passage point in both directions, look, roll. */
    Track fly(double[] x) {
        Controls c = controls;
        int n = t1 - t0 + 1, ip = tp - t0;
        double[] v = new double[n], w = new double[n], cl = new double[n], course = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = c.at(x, Controls.V, t0 + i, V_MIN * 0.8, V_MAX);
            w[i] = c.at(x, c.w(), t0 + i, -W_MAX, W_MAX);
            cl[i] = c.at(x, c.climb(), t0 + i, -CLIMB, CLIMB);
        }
        Vec3[] pos = new Vec3[n], vel = new Vec3[n], acc = new Vec3[n];
        course[ip] = x[Controls.COURSE];
        pos[ip] = new Vec3(x[Controls.PX], x[Controls.PY], x[Controls.PZ]);
        for (int i = ip + 1; i < n; i++) {
            course[i] = course[i - 1] + (w[i - 1] + w[i]) / 2;
            pos[i] = pos[i - 1].add(dir((course[i - 1] + course[i]) / 2).scale((v[i - 1] + v[i]) / 2)).add(0, (cl[i - 1] + cl[i]) / 2, 0);
        }
        for (int i = ip - 1; i >= 0; i--) {
            course[i] = course[i + 1] - (w[i] + w[i + 1]) / 2;
            pos[i] = pos[i + 1].subtract(dir((course[i] + course[i + 1]) / 2).scale((v[i] + v[i + 1]) / 2)).subtract(0, (cl[i] + cl[i + 1]) / 2, 0);
        }
        for (int i = 0; i < n; i++) vel[i] = dir(course[i]).scale(v[i]).add(0, cl[i], 0);
        for (int i = 0; i < n; i++) acc[i] = vel[Math.min(n - 1, i + 1)].subtract(vel[Math.max(0, i - 1)]).scale(i == 0 || i == n - 1 ? 1 : 0.5);
        double[] yaw = new double[n], pitch = new double[n], bank = new double[n];
        double k = 1 - Math.exp(-1 / LAG);
        Vec3 look = null;
        for (int i = 0; i < n; i++) {
            double t = t0 + i;
            Vec3 toFocus = focus(t).subtract(pos[i]);
            Vec3 want = toFocus.lengthSqr() < 1e-6 ? dir(course[i]) : toFocus.normalize();
            double wt = weight(t);
            if (wt < 1) {
                Vec3 ahead = dir(course[i]).add(0, -Math.tan(Math.toRadians(AHEAD)), 0).normalize();
                want = want.scale(wt).add(ahead.scale(1 - wt)).normalize();
            }
            look = look == null ? want : look.add(want.subtract(look).scale(k)).normalize();
            double y = Math.toDegrees(Math.atan2(-look.x, look.z));
            yaw[i] = i == 0 ? y : yaw[i - 1] + Mth.wrapDegrees(y - yaw[i - 1]);
            pitch[i] = -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
            // drone roll: in a turn - lateral acceleration (speed × heading angular velocity) to g, turn to the right - roll
            //  to the right (Flashback has a minus); flies sideways (look at the angle crab from the course) - the tilt of the drone against
            //  air resistance is visible as a roll: movement to the left of the camera - roll to the left
            double crab = Math.toRadians(Mth.wrapDegrees(yaw[i] - course[i])), speed = v[i] * 20;
            double lateral = speed * Math.toRadians(w[i]) * 20;
            double tilt = Math.toDegrees(Math.atan(TILT * speed * speed / 9.81));
            bank[i] = Mth.clamp(-Math.toDegrees(Math.atan(lateral * Math.cos(crab) / 9.81)) + tilt * Math.sin(crab), -ROLL, ROLL);
        }
        return new Track(t0, pos, vel, acc, yaw, pitch, Hands.gauss(bank, 2));
    }

    /** View from the course, ° (plus - to the right). */
    static double crab(Track tr, int i) {
        Vec3 v = tr.vel()[i];
        return Mth.wrapDegrees(tr.yaw()[i] - Math.toDegrees(Math.atan2(-v.x, v.z)));
    }

    Cameras.View view(Track tr, int i) {
        return Cameras.View.of(new Cameras.Pose(tr.pos()[i], (float) tr.yaw()[i], (float) tr.pitch()[i], fov), aspect).rolled(tr.roll()[i]);
    }

    // ==================================================================================== price

    /** Count of violations by window samples - for a line in the log. */
    static final class Tally {
        int samples, low, out, smoke, ahead, hidden, smoky, lowOut, fast, crab, turn, missed;
        double speed, height, near = Double.POSITIVE_INFINITY;

        @Override
        public String toString() {
            int n = Math.max(1, samples);
            return String.format(Locale.ROOT, "speed %.2f b/t, height %.1f, closest to the hit %.0f; of %d samples: near ground %d, "
                            + "off chunks %d, in smoke %d, smoke fills the frame %d, hit hidden by terrain %d, by smoke %d, frame bottom off chunks %d, "
                            + "too fast %d, sideways %d, fast turn %d; hit unseen %d",
                    speed / n, height / n, near, samples, low, out, smoke, ahead, hidden, smoky, lowOut, fast, crab, turn, missed);
        }
    }

    double cost(double[] x) {
        return cost(x, null);
    }

    /** Cost of travel (less is better); {@code tally} - count violations here (null - do not count). */
    double cost(double[] x, Tally tally) {
        Track tr = fly(x);
        double j = 0;
        for (int t = a; t <= b; t += 2) {
            int i = t - tr.from();
            Vec3 p = tr.pos()[i], v = tr.vel()[i];
            Vec3 q = focus(t);
            double w = weight(t);
            if (tally != null) tally.samples++;
            // land, houses, crowns; over chunks that the client does not have - it is impossible
            double g = grid.high(p.x, p.z);
            if (Double.isNaN(g)) {
                j += 60;
                if (tally != null) tally.out++;
            } else {
                double c = p.y - g;
                if (c < CLEAR) {
                    j += 30 + 40 * (CLEAR - c) * (CLEAR - c);
                    if (tally != null) tally.low++;
                } else if (c < LOW) {
                    j += 0.3 * (LOW - c) * (LOW - c);
                } else if (c > HIGH) {
                    j += 0.03 * (c - HIGH) * (c - HIGH);
                }
                if (tally != null) tally.height += c;
            }
            // smoke: camera in it, frame in the smoke (as the output measures it), smoke in the line of sight to the impact
            int k = (t - a) / 2;
            double sc = smokeClearance(k, p.x, p.y, p.z, SMOKE);
            if (sc < SMOKE) {
                j += 25 + 10 * Math.min(SMOKE - sc, 10);
                if (tally != null) tally.smoke++;
            }
            Cameras.View view = view(tr, i);
            double sm = smother(k, view);
            if (sm > SMOTHER_FROM) j += 30 * (sm - SMOTHER_FROM);
            if (sm >= FrameLog.SMOTHERED) {
                j += 15;
                if (tally != null) tally.ahead++;
            }
            if (smokeOnLine(k, p, q)) {
                j += 6 * w;
                if (tally != null) tally.smoky++;
            }
            if (grid.hides(p, q, Math.max(1.5, main.size() * 0.4))) {
                j += 10 * w;
                if (tally != null) tally.hidden++;
            }
            // Distance to impact: on approach, no farther than 200 blocks; at impact and for 3 s afterward,
            // keep the fireball readable and within the frame, ideally at dPref.
            double d = p.distanceTo(main.pos());
            if (tally != null) tally.near = Math.min(tally.near, d);
            double dMin = Math.max(24, 1.5 * main.size()), dMax = Math.max(75, 4.5 * main.size()), dPref = Math.max(32, 2.8 * main.size());
            if (t >= main.t() - 10 && t <= main.t() + Subject.KEY_HOLD) {
                if (d < dMin) j += 0.05 * (dMin - d) * (dMin - d);
                if (d > dMax) j += 0.01 * (d - dMax) * (d - dMax);
                j += 0.004 * (d - dPref) * (d - dPref);
            } else if (d > 200) {
                j += 0.002 * (d - 200) * (d - 200);
            } else if (d < dMin * 0.6) {
                j += 0.05 * (dMin * 0.6 - d) * (dMin * 0.6 - d);
            }
            // bottom of frame behind replay chunks
            int lo = grid.lowOut(view);
            if (lo > 0) {
                j += 5 * lo;
                if (tally != null) tally.lowOut++;
            }
            // drone: speed, acceleration
            double speed = v.length();
            if (tally != null) tally.speed += speed;
            if (speed > V_MAX) {
                j += 400 * (speed - V_MAX) * (speed - V_MAX);
                if (tally != null) tally.fast++;
            }
            if (speed < V_MIN) j += 100 * (V_MIN - speed) * (V_MIN - speed);
            j += 6 * (speed - V_PREF) * (speed - V_PREF);
            // smooth: acceleration is always counted (arcs, not zigzag), above the limit - steep; roll - not jerky
            double acc = tr.acc()[i].length();
            j += 300 * acc * acc;
            if (acc > A_MAX) j += 10_000 * (acc - A_MAX) * (acc - A_MAX);
            double rollRate = Math.abs(tr.roll()[i] - tr.roll()[i - 2]) / 2;
            if (rollRate > ROLL_RATE) j += 0.5 * (rollRate - ROLL_RATE) * (rollRate - ROLL_RATE);
            // look: turn by tick, up and down, sideways to the course
            double turn = Math.hypot(tr.yaw()[i] - tr.yaw()[i - 1], tr.pitch()[i] - tr.pitch()[i - 1]);
            if (turn > TURN) {
                j += 0.5 * (turn - TURN) * (turn - TURN);
                if (tally != null) tally.turn++;
            }
            double pitch = tr.pitch()[i];
            if (pitch < -UP) j += 0.05 * (-UP - pitch) * (-UP - pitch);
            if (pitch > DOWN) j += 0.05 * (pitch - DOWN) * (pitch - DOWN);
            if (Math.hypot(v.x, v.z) > 0.3) {
                double crab = crab(tr, i), free = t < main.t() - 30 ? CRAB_IN : t <= main.t() + Subject.KEY_HOLD ? CRAB_PASS : CRAB_OUT;
                if (Math.abs(crab) > free) {
                    j += 0.02 * (Math.abs(crab) - free) * (Math.abs(crab) - free);
                    if (tally != null) tally.crab++;
                }
                double rate = Math.abs(Mth.wrapDegrees(crab - crab(tr, i - 2))) / 2;
                if (rate > CRAB_RATE) j += 0.5 * (rate - CRAB_RATE) * (rate - CRAB_RATE);
            }
            // the projectile before impact is in the frame (flies into the place of impact or passes by the camera)
            if (t >= main.t() - 40 && t < main.t()) {
                Vec3 shell = subject.pathAt(t);
                if (shell != null && !(view.in(shell, 0.9) && !grid.hides(p, shell, 1.5))) j += 3;
            }
        }
        // hit - in the frame and not behind the relief in the first tick (the output checks them)
        for (int t = main.t() + 2; t <= Math.min(b, main.t() + FrameLog.KEY_CHECK); t += 2) {
            int i = t - tr.from();
            Vec3 k = Cameras.keyPoint(main, t);
            if (!grid.seen(view(tr, i), k, main.size())) {
                j += 20;
                if (tally != null) tally.missed++;
            }
        }
        // other window impacts visible in the first tick are a plus
        for (Plume o : others) {
            int i = o.t() + 4 - tr.from();
            if (i < 0 || i >= tr.pos().length) continue;
            Vec3 k = o.pos().add(0, o.size() * 0.3, 0);
            if (tr.pos()[i].distanceTo(k) < 220 && view(tr, i).in(k, 0.9) && !grid.hides(tr.pos()[i], k, Math.max(1.5, o.size() * 0.4))) j -= 6;
        }
        return j;
    }

    // ================================================================================== search

    /**
     * The best {@code count} of different flights: seeds - passes past the blow directly and in an arc around it, the best different of them
     *  are completed ({@link #refine}).
     */
    List<Flight> plan(int count) {
        Random r = new Random(seed);
        record Seed(String name, double head, int side, double[] x, double cost) {}
        List<Seed> seeds = new ArrayList<>();
        for (int h = 0; h < HEADINGS; h++) {
            double head = 360.0 * h / HEADINGS;
            Vec3 fwd = dir(head), right = new Vec3(-fwd.z, 0, fwd.x);
            for (int side : new int[]{1, -1}) {
                for (double off : new double[]{25, 45}) {
                    for (double up : new double[]{8, 16}) {
                        Vec3 spot = main.pos().add(right.scale(side * off));
                        double g = grid.high(spot.x, spot.z);
                        Vec3 p = new Vec3(spot.x, Math.max(main.pos().y, Double.isNaN(g) ? main.pos().y : g) + up, spot.z);
                        // straight or in an arc to the blow (it is from the side: from the right - turn to the right)
                        for (double turn : new double[]{0, -side * 0.8}) {
                            double[] x = controls.steady(p, head, V_PREF, turn, 0);
                            String name = String.format(Locale.ROOT, "pilot-h%03.0f-%s", head, side > 0 ? "R" : "L");
                            seeds.add(new Seed(name, head, side, x, cost(x)));
                        }
                    }
                }
            }
        }
        seeds.sort(Comparator.comparingDouble(Seed::cost));
        // best different: the other side or course further 45°
        List<Seed> picks = new ArrayList<>();
        for (Seed s : seeds) {
            if (picks.size() >= SEEDS) break;
            if (picks.stream().anyMatch(o -> o.side() == s.side() && Math.abs(Mth.wrapDegrees(o.head() - s.head())) < 45)) continue;
            picks.add(s);
        }
        List<Flight> out = new ArrayList<>();
        for (Seed s : picks) {
            double[] best = refine(s.x(), s.cost(), r);
            Tally tally = new Tally();
            double c = cost(best, tally);
            out.add(new Flight(s.name(), best, c, String.format(Locale.ROOT, "seed %.1f → %.1f: %s", s.cost(), c, tally)));
        }
        out.sort(Comparator.comparingDouble(Flight::cost));
        return out.subList(0, Math.min(count, out.size()));
    }

    /**
     * Cross entropy: {@link #POP} sets of handles around the average, {@link #ELITE} the best ones give a new average and
     *  spread (not less than {@link #SIG_MIN} initial); the best result in the entire search.
     */
    double[] refine(double[] start, double startCost, Random r) {
        double[] mu = start.clone(), sigma = controls.sigma(), floor = sigma.clone();
        for (int i = 0; i < floor.length; i++) floor[i] *= SIG_MIN;
        double[] best = mu.clone();
        double bestCost = startCost;
        double[][] pop = new double[POP][];
        double[] costs = new double[POP];
        Integer[] order = new Integer[POP];
        for (int it = 0; it < ITER; it++) {
            for (int p = 0; p < POP; p++) {
                double[] x;
                if (p == 0) {
                    x = best.clone();
                } else if (p == 1) {
                    x = mu.clone();
                } else {
                    x = new double[mu.length];
                    for (int i = 0; i < x.length; i++) x[i] = mu[i] + sigma[i] * r.nextGaussian();
                }
                pop[p] = x;
                costs[p] = cost(x);
                order[p] = p;
                if (costs[p] < bestCost) {
                    bestCost = costs[p];
                    best = x.clone();
                }
            }
            java.util.Arrays.sort(order, Comparator.comparingDouble(i -> costs[i]));
            for (int i = 0; i < mu.length; i++) {
                double m = 0;
                for (int e = 0; e < ELITE; e++) m += pop[order[e]][i];
                m /= ELITE;
                double var = 0;
                for (int e = 0; e < ELITE; e++) var += (pop[order[e]][i] - m) * (pop[order[e]][i] - m);
                mu[i] = m;
                sigma[i] = Math.max(floor[i], 0.7 * Math.sqrt(var / ELITE) + 0.3 * sigma[i] * 0.85);
            }
        }
        return best;
    }

    /** Flight camera: location and view along the tick path (between ticks - in a straight line), drone roll. */
    Cameras.Path path(Flight f) {
        Track tr = fly(f.controls());
        return new Cameras.Path() {
            @Override
            public Cameras.Pose at(double t) {
                double u = Mth.clamp(t - tr.from(), 0, tr.pos().length - 1);
                int i = (int) Math.floor(u), j = Math.min(tr.pos().length - 1, i + 1);
                double k = u - i;
                return new Cameras.Pose(tr.pos()[i].lerp(tr.pos()[j], k), (float) Mth.lerp(k, tr.yaw()[i], tr.yaw()[j]),
                        (float) Mth.lerp(k, tr.pitch()[i], tr.pitch()[j]), fov);
            }

            @Override
            public double roll(double t) {
                double u = Mth.clamp(t - tr.from(), 0, tr.pos().length - 1);
                int i = (int) Math.floor(u), j = Math.min(tr.pos().length - 1, i + 1);
                return Mth.lerp(u - i, tr.roll()[i], tr.roll()[j]);
            }
        };
    }
}
