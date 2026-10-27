package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Draw.conceal} (LEAN, 27.10: the pass took 3.0–3.6 ms of the 100-strike raid frame) against the algorithm it
 * replaced ({@link Reference}, copied from 3.9.0): the same frame plan of 100- and 400-strike raids from the stand
 * cameras, at full and partial concealment strength and through 4× binoculars, must give the same visibility, partial
 * hiding, drops and opacity scaling of every puff and place.
 */
class SmokeConcealTest {
    @Test
    void concealMatchesTheReplacedAlgorithmOnRaids() throws ReflectiveOperationException {
        double now = 1000;
        List<SmokeGrid.Source> hundred = SmokeRaidTest.raid(now), four = raid400(now);
        Vec3 eye = SmokeRaidTest.EYES[1];
        FarView binoculars = zoomed(eye, new Vector3f(10, 2, 120), 4);
        Object[][] scenes = {
                {"100 edge", hundred, SmokeRaidTest.view(1)}, {"100 inside", hundred, SmokeRaidTest.view(2)},
                {"400 outside", four, SmokeRaidTest.view(0)}, {"400 edge", four, SmokeRaidTest.view(1)},
                {"100 binoculars", hundred, binoculars}};
        Plan p = new Plan();
        StringBuilder all = new StringBuilder();
        boolean ok = true;
        int dropped = 0, partial = 0;
        double was = SmokePuffs.budget;
        try {
            for (Object[] scene : scenes) {
                @SuppressWarnings("unchecked") List<SmokeGrid.Source> raid = (List<SmokeGrid.Source>) scene[1];
                FarView view = (FarView) scene[2];
                // Settle the place stride and merge level of this view, then plan one more frame without drawing it.
                SmokeRaidTest.frame(raid, now, view, SmokePuffs.FILL_BUDGET);
                SmokePuffs.budget = SmokePuffs.FILL_BUDGET;
                SmokeFillTest.Screen screen = new SmokeFillTest.Screen(view);
                FarFlightView.budget();
                for (SmokeGrid.Source b : raid) b.emit(now, SmokePuffs.sink(view, screen, b, now));
                p.call("plan");
                double raw = p.d("lastRaw");
                for (double budget : new double[]{SmokePuffs.FILL_BUDGET, raw / 1.1}) {
                    if (!(raw > budget)) continue;
                    State before = p.capture();
                    p.call("conceal", budget);
                    State actual = p.capture();
                    int cells = p.i("occlusionCells");
                    before.restore(p);
                    new Reference(p, cells).conceal(budget);
                    State expected = p.capture();
                    before.restore(p);
                    String got = String.format(Locale.ROOT, "%s, budget %.2f (raw %.2f, cells %d): %s", scene[0], budget, raw, cells,
                            expected.compare(actual));
                    System.out.println("CONCEAL " + got);
                    all.append(got).append('\n');
                    ok &= expected.same(actual);
                    dropped += expected.dropped();
                    partial += expected.partial();
                }
                p.call("forget");
            }
        } finally {
            SmokePuffs.budget = was;
            SmokePuffs.resetBudget();
        }
        assertTrue(dropped > 0 && partial > 0, "The scenes must exercise drops and partial hiding: " + dropped + ", " + partial);
        assertTrue(ok, all.toString());
    }

    /** 400 strikes in a 240 m circle with every second fuel fire burning: places and clouds both at their frame limits. */
    static List<SmokeGrid.Source> raid400(double now) {
        CloudModel.Air air = new CloudModel.Air(Stability.STANDARD_N, SmokeRaidTest.Fire.WIND, 0, 0.15, (int) Stability.D, 0.5);
        List<SmokeGrid.Source> raid = new ArrayList<>();
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < 400; i++) {
            double r = 240 * Math.sqrt((i + 0.5) / 400), a = i * golden, x = r * Math.cos(a), z = r * Math.sin(a), born = now - 35 - 0.5 * (399 - i);
            raid.add(new SmokeRaidTest.Strike(air, i % 3 == 2 ? GroundMaterial.STONE : GroundMaterial.DIRT, x, z, born, 7000L + i));
            if (i % 2 == 0) raid.add(new SmokeRaidTest.Fire(x, z, born, 9000L + i));
        }
        return raid;
    }

    private static FarView zoomed(Vec3 eye, Vector3f forward, double zoom) {
        forward.normalize();
        Vector3f left = new Vector3f(0, 1, 0).cross(forward, new Vector3f()).normalize(), up = forward.cross(left, new Vector3f()).normalize();
        double pixel = SmokeFillTest.PIXEL / zoom;
        return new FarView(eye, forward, left, up, 0, 1000, pixel, Math.atan(0.5 * pixel * Math.hypot(1920, 1080)), 1, 1, new Vector3f(1, 1, 1),
                32000, 192, 192, 0, Sight.Air.at(0, 20000));
    }

    /** The frame's {@code Draw} through reflection. */
    static final class Plan {
        final Class<?> type;
        final Object draw;

        Plan() throws ReflectiveOperationException {
            type = Class.forName(SmokePuffs.class.getName() + "$Draw");
            Field field = SmokePuffs.class.getDeclaredField("fill");
            field.setAccessible(true);
            draw = field.get(null);
        }

        Field field(String name) throws ReflectiveOperationException {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }

        Object get(String name) throws ReflectiveOperationException { return field(name).get(draw); }
        double d(String name) throws ReflectiveOperationException { return field(name).getDouble(draw); }
        int i(String name) throws ReflectiveOperationException { return field(name).getInt(draw); }

        void call(String name) throws ReflectiveOperationException {
            Method m = type.getDeclaredMethod(name);
            m.setAccessible(true);
            m.invoke(draw);
        }

        void call(String name, double value) throws ReflectiveOperationException {
            Method m = type.getDeclaredMethod(name, double.class);
            m.setAccessible(true);
            m.invoke(draw, value);
        }

        State capture() throws ReflectiveOperationException {
            int plans = i("plans"), holding = i("holding"), places = i("placeCount");
            return new State(Arrays.copyOf((double[]) get("planSeen"), plans), Arrays.copyOf((double[]) get("planShown"), plans),
                    Arrays.copyOf((boolean[]) get("planDead"), plans), Arrays.copyOf((double[]) get("held"), holding * SmokePuffs.FIELDS),
                    Arrays.copyOf((double[]) get("places"), places * SmokePuffs.PLACE_FIELDS), Arrays.copyOf((boolean[]) get("merged"), holding),
                    Arrays.copyOf((boolean[]) get("placeGone"), places), d("floor"));
        }
    }

    /** What conceal changes: visibility and shown opacity of plan entries, drops, the frame floor, puff and place opacity. */
    record State(double[] seen, double[] shown, boolean[] dead, double[] held, double[] places, boolean[] merged, boolean[] gone, double floor) {
        void restore(Plan p) throws ReflectiveOperationException {
            System.arraycopy(seen, 0, p.get("planSeen"), 0, seen.length);
            System.arraycopy(shown, 0, p.get("planShown"), 0, shown.length);
            System.arraycopy(dead, 0, p.get("planDead"), 0, dead.length);
            System.arraycopy(held, 0, p.get("held"), 0, held.length);
            System.arraycopy(places, 0, p.get("places"), 0, places.length);
            System.arraycopy(merged, 0, p.get("merged"), 0, merged.length);
            System.arraycopy(gone, 0, p.get("placeGone"), 0, gone.length);
            p.field("floor").setDouble(p.draw, floor);
        }

        boolean same(State o) {
            return Arrays.equals(seen, o.seen) && Arrays.equals(shown, o.shown) && Arrays.equals(dead, o.dead) && Arrays.equals(held, o.held)
                    && Arrays.equals(places, o.places) && Arrays.equals(merged, o.merged) && Arrays.equals(gone, o.gone)
                    && Double.compare(floor, o.floor) == 0;
        }

        int dropped() {
            int n = 0;
            for (boolean d : dead) if (d) n++;
            return n;
        }

        int partial() {
            int n = 0;
            for (int e = 0; e < dead.length; e++) if (!dead[e] && shown[e] > 0 && seen[e] < 1) n++;
            return n;
        }

        String compare(State o) {
            double worst = Math.abs(floor - o.floor);
            for (int e = 0; e < seen.length; e++) worst = Math.max(worst, Math.max(Math.abs(seen[e] - o.seen[e]), Math.abs(shown[e] - o.shown[e])));
            for (int k = 0; k < held.length; k++) worst = Math.max(worst, Math.abs(held[k] - o.held[k]));
            for (int k = 0; k < places.length; k++) worst = Math.max(worst, Math.abs(places[k] - o.places[k]));
            int flips = 0;
            for (int e = 0; e < dead.length; e++) if (dead[e] != o.dead[e]) flips++;
            for (int k = 0; k < merged.length; k++) if (merged[k] != o.merged[k]) flips++;
            for (int k = 0; k < gone.length; k++) if (gone[k] != o.gone[k]) flips++;
            return String.format(Locale.ROOT, "%d entries, %d dropped, %d partly hidden; max |difference| %.3g, drop flips %d, identical %b",
                    seen.length, dropped(), partial(), worst, flips, same(o));
        }
    }

    /** Draw.conceal of 3.9.0 (STEADY #481, PL6 #483), on the same plan arrays. */
    static final class Reference {
        static final double CORE = 0.6, INNER = 0.8;
        static final double[] RING_X = {1, 0.7071067811865476, 0, -0.7071067811865476, -1, -0.7071067811865476, 0, 0.7071067811865476},
                RING_Y = {0, 0.7071067811865476, 1, 0.7071067811865476, 0, -0.7071067811865476, -1, -0.7071067811865476};
        final Plan p;
        final int cells, side, faceSize, plans;
        final double[] plan, planFlat, planOpacity, planSeen, planShown, planVeil, planCover0, held, places, occlusion, rays;
        final boolean[] planNear, planPlace, planDead, merged, placeGone;
        final int[] planOwner;
        final long[] planSeed;
        final java.util.BitSet nodes = new java.util.BitSet();
        final double lastRaw;
        double floor;

        Reference(Plan p, int cells) throws ReflectiveOperationException {
            this.p = p;
            this.cells = cells;
            side = cells + 1;
            faceSize = side * side;
            plans = p.i("plans");
            plan = (double[]) p.get("plan");
            planFlat = (double[]) p.get("planFlat");
            planOpacity = (double[]) p.get("planOpacity");
            planSeen = (double[]) p.get("planSeen");
            planShown = (double[]) p.get("planShown");
            planVeil = (double[]) p.get("planVeil");
            planCover0 = (double[]) p.get("planCover0");
            held = (double[]) p.get("held");
            places = (double[]) p.get("places");
            planNear = (boolean[]) p.get("planNear");
            planPlace = (boolean[]) p.get("planPlace");
            planDead = (boolean[]) p.get("planDead");
            merged = (boolean[]) p.get("merged");
            placeGone = (boolean[]) p.get("placeGone");
            planOwner = (int[]) p.get("planOwner");
            planSeed = (long[]) p.get("planSeed");
            lastRaw = p.d("lastRaw");
            floor = p.d("floor");
            occlusion = new double[6 * faceSize];
            rays = new double[18 * faceSize];
            for (int face = 0; face < 6; face++) for (int j = 0; j < side; j++) for (int i = 0; i < side; i++) {
                double u = 2.0 * i / cells - 1, v = 2.0 * j / cells - 1;
                double sign = (face & 1) == 0 ? 1 : -1;
                int axis = face / 2, q = 3 * (face * faceSize + j * side + i);
                rays[q] = axis == 0 ? sign : u;
                rays[q + 1] = axis == 0 ? u : axis == 1 ? sign : v;
                rays[q + 2] = axis == 2 ? sign : v;
            }
        }

        double occlusion(double x, double y, double z, boolean reserve) {
            double ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z), m = Math.max(ax, Math.max(ay, az));
            if (!(m > 0)) return 0;
            int face = m == ax ? (x > 0 ? 0 : 1) : m == ay ? (y > 0 ? 2 : 3) : (z > 0 ? 4 : 5);
            double u = (face < 2 ? y : x) / m, v = (face < 4 ? z : y) / m;
            double s = Math.clamp((u + 1) * 0.5 * cells, 0, cells), t = Math.clamp((v + 1) * 0.5 * cells, 0, cells);
            int i = Math.min((int) s, cells - 1), j = Math.min((int) t, cells - 1);
            double du = s - i, dv = t - j;
            int q = face * faceSize + j * side + i;
            if (reserve) {
                nodes.set(q, q + 2);
                nodes.set(q + side, q + side + 2);
                return 0;
            }
            return Math.min(12, (1 - dv) * ((1 - du) * occlusion[q] + du * occlusion[q + 1])
                    + dv * ((1 - du) * occlusion[q + side] + du * occlusion[q + side + 1]));
        }

        void reserve(int e) {
            int o = 6 * e;
            double x = plan[o], y = plan[o + 1], z = plan[o + 2];
            occlusion(x, y, z, true);
            if (planNear[e] || planFlat[e] > 0) return;
            double h = Math.hypot(x, z), d = Math.hypot(h, y), fx = x / d, fy = y / d, fz = z / d;
            double hx = h > 1e-9 ? z / h : 1, hz = h > 1e-9 ? -x / h : 0;
            double ux = fy * hz, uy = fz * hx - fx * hz, uz = -fy * hx;
            double size = planPlace[e] ? SmokePuffs.clubScale(1, mix(planSeed[e]), true) : 1;
            double ra = CORE * plan[o + 3] * size, rb = CORE * plan[o + 4] * size;
            for (int j = 0; j < 8; j++) {
                double a = ra * RING_X[j], b = rb * RING_Y[j];
                occlusion(x + a * hx + b * ux, y + b * uy, z + a * hz + b * uz, true);
            }
        }

        void drop(int e) {
            if (e < 0 || planDead[e]) return;
            planDead[e] = true;
            if (planVeil[e] < 1) floor -= planCover0[e];
        }

        void conceal(double budget) throws ReflectiveOperationException {
            double strength = smoothstep(budget, 1.25 * budget, lastRaw);
            long[] order = new long[plans];
            int n = 0;
            for (int e = 0; e < plans; e++) {
                if (planDead[e] || planOwner[e] == Integer.MIN_VALUE) continue;
                int o = 6 * e;
                double d2 = plan[o] * plan[o] + plan[o + 1] * plan[o + 1] + plan[o + 2] * plan[o + 2];
                if (!(d2 > 0)) continue;
                reserve(e);
                order[n++] = (long) Float.floatToIntBits((float) d2) << 32 | e;
            }
            Arrays.sort(order, 0, n);
            for (int k = 0; k < n; k++) {
                int e = (int) order[k], o = 6 * e;
                double x = plan[o], y = plan[o + 1], z = plan[o + 2], h = Math.hypot(x, z), d = Math.hypot(h, y);
                double fx = x / d, fy = y / d, fz = z / d;
                double hx = h > 1e-9 ? z / h : 1, hz = h > 1e-9 ? -x / h : 0;
                double ux = fy * hz, uy = fz * hx - fx * hz, uz = -fy * hx;
                double size = planPlace[e] ? SmokePuffs.clubScale(1, mix(planSeed[e]), true) : 1;
                double ra = CORE * plan[o + 3] * size, rb = CORE * plan[o + 4] * size;
                double least = occlusion(x, y, z, false);
                planSeen[e] = 1 - strength * (1 - Math.exp(-least));
                double hidden = 0;
                if (!planNear[e] && planFlat[e] == 0) {
                    for (int j = 0; j < 8 && least > 2; j++) {
                        double a = ra * RING_X[j], b = rb * RING_Y[j];
                        least = Math.min(least, occlusion(x + a * hx + b * ux, y + b * uy, z + a * hz + b * uz, false));
                    }
                    hidden = strength * smoothstep(2, 3, least);
                }
                if (hidden >= 1) {
                    drop(e);
                    int owner = planOwner[e];
                    if (owner >= 0) merged[owner] = true;
                    else placeGone[~owner] = true;
                    continue;
                }
                double tau = -Math.log1p(-Math.min(planOpacity[e], 0.999)) * (1 - hidden);
                if (hidden > 0) {
                    double keep = 1 - hidden;
                    int owner = planOwner[e];
                    if (owner >= 0) held[owner * SmokePuffs.FIELDS + 13] *= keep;
                    else places[~owner * SmokePuffs.PLACE_FIELDS + 12] *= keep;
                    planShown[e] *= keep;
                }
                if (!(tau > 0) || !(ra > 0) || !(rb > 0) || planFlat[e] > 0) continue;
                double sa = d / ra, sb = d / rb;
                double ax = sa * hx, az = sa * hz, bx = sb * ux, by = sb * uy, bz = sb * uz;
                double radius = Math.max(ra, rb);
                double margin = (d + radius * radius / d) / Math.min(ra, rb) * (2 * Math.sqrt(2) / cells);
                if (margin >= 1) continue;
                double remaining2 = (1 - margin) * (1 - margin);
                radius *= 1 - margin;
                for (int face = 0; face < 6; face++) {
                    int axis = face / 2;
                    double c = ((face & 1) == 0 ? 1 : -1) * (axis == 0 ? x : axis == 1 ? y : z);
                    if (c + radius <= 0) continue;
                    double u = axis == 0 ? y : x, v = axis == 2 ? y : z;
                    if (Math.abs(u) - c > Math.sqrt(2) * radius || Math.abs(v) - c > Math.sqrt(2) * radius) continue;
                    double u0 = -1, u1 = 1, v0 = -1, v1 = 1;
                    if (c > radius) {
                        double near = c - radius, far = c + radius;
                        u0 = Math.min((u - radius) / near, (u - radius) / far);
                        u1 = Math.max((u + radius) / near, (u + radius) / far);
                        v0 = Math.min((v - radius) / near, (v - radius) / far);
                        v1 = Math.max((v + radius) / near, (v + radius) / far);
                    }
                    int i0 = Math.max(0, (int) Math.floor((u0 + 1) * 0.5 * cells)), i1 = Math.min(cells, (int) Math.ceil((u1 + 1) * 0.5 * cells)),
                            j0 = Math.max(0, (int) Math.floor((v0 + 1) * 0.5 * cells)), j1 = Math.min(cells, (int) Math.ceil((v1 + 1) * 0.5 * cells));
                    for (int j = j0; j <= j1; j++) {
                        int first = face * faceSize + j * side + i0, last = first + i1 - i0;
                        for (int q = nodes.nextSetBit(first); q >= first && q <= last; q = nodes.nextSetBit(q + 1)) {
                            int r = 3 * q;
                            double rx = rays[r], ry = rays[r + 1], rz = rays[r + 2];
                            double den = fx * rx + fy * ry + fz * rz;
                            if (!(den > 0)) continue;
                            double a = ax * rx + az * rz, b = bx * rx + by * ry + bz * rz;
                            double numerator = a * a + b * b, denominator = den * den;
                            if (numerator >= denominator * remaining2) continue;
                            double rho = Math.sqrt(numerator / denominator) + margin;
                            double weight = 1 - smoothstep(INNER * INNER, 1, rho * rho);
                            occlusion[q] += tau * weight;
                        }
                    }
                }
            }
            p.field("floor").setDouble(p.draw, floor);
        }

        static long mix(long z) {
            z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
            z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
            return z ^ z >>> 31;
        }

        static double smoothstep(double a, double b, double x) {
            double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
            return t * t * (3 - 2 * t);
        }
    }
}
