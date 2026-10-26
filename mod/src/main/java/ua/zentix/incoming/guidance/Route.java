package ua.zentix.incoming.guidance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Flight route: horizontal turn points (projectile holds its own altitude), then target after the last one. Like
 * real Shaheds and cruise missiles: a detour loop and chosen approach direction rather than a straight line from
 * launcher, giving tens of seconds of flight and a strike “from behind”.
 */
public final class Route {
    private final List<Vec3> points;
    /** Route origin: first leg start (for “passed” checks even on that leg). */
    @Nullable
    private final Vec3 origin;
    /**
     * Operator placed the points on the map ({@code strike/Waypoints}), rather than launch logic: final target
     * leg can be anything.
     */
    private final boolean operator;
    private int next;

    public Route(List<Vec3> points) {
        this(points, null);
    }

    public Route(List<Vec3> points, @Nullable Vec3 origin) {
        this(points, origin, false);
    }

    private Route(List<Vec3> points, @Nullable Vec3 origin, boolean operator) {
        this.points = new ArrayList<>(points);
        this.origin = origin;
        this.operator = operator;
    }

    /** Route through operator waypoints. */
    public static Route operator(List<Vec3> points, Vec3 origin) {
        return new Route(points, new Vec3(origin.x, 0, origin.z), true);
    }

    public boolean byOperator() {
        return operator;
    }

    public static Route direct() {
        return new Route(List.of());
    }

    /** Next turn point, or null if only the target remains. */
    @Nullable
    public Vec3 current() {
        return next < points.size() ? points.get(next) : null;
    }

    public boolean finished() {
        return next >= points.size();
    }

    /**
     * This route's remaining points preceded by {@code first}; first leg starts at {@code origin} (launcher
     * start: initially exactly along launcher heading, {@code StrikeService}).
     */
    public Route after(Vec3 first, Vec3 origin) {
        List<Vec3> pts = new ArrayList<>();
        pts.add(new Vec3(first.x, 0, first.z));
        pts.addAll(points.subList(next, points.size()));
        return new Route(pts, new Vec3(origin.x, 0, origin.z), operator);
    }

    /** Skip remaining points: straight to target (camera retargeting). */
    public void skip() {
        next = points.size();
    }

    /**
     * Point passed if within {@code capture} horizontally or already behind along the leg (a large turning radius
     * does not make the projectile circle it forever). First leg starts at launch: otherwise an entry point
     * behind launch (short flight) would trap Shahed in an endless orbit.
     */
    public void update(Vec3 pos, double capture) {
        while (next < points.size()) {
            Vec3 wp = points.get(next);
            Vec3 from = next == 0 ? origin : points.get(next - 1);
            double dx = wp.x - pos.x, dz = wp.z - pos.z;
            boolean reached = dx * dx + dz * dz <= capture * capture;
            if (!reached && from != null) {
                // Crossed the perpendicular to the leg at the turn point.
                double lx = wp.x - from.x, lz = wp.z - from.z;
                reached = lx * dx + lz * dz < 0 && dx * dx + dz * dz < 4 * capture * capture;
            }
            if (!reached) return;
            next++;
        }
    }

    /** Remaining horizontal path length: to next point, through points, then last point to target. */
    public double remaining(Vec3 pos, Vec3 target) {
        double len = 0;
        Vec3 at = pos;
        for (int i = next; i < points.size(); i++) {
            len += horizontal(at, points.get(i));
            at = points.get(i);
        }
        return len + horizontal(at, target);
    }

    public int size() {
        return points.size();
    }

    public int index() {
        return next;
    }

    public List<Vec3> points() {
        return List.copyOf(points);
    }

    static double horizontal(Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Route about {@code length} long: launch → (lateral detour point) → entry point → target. Entry is {@code
     * entry} blocks before target along approach direction; if the direct path is too short, add a lateral detour
     * from the “launch–entry” midpoint, far enough to give the required length.
     *
     * @param approach horizontal target approach direction (unit vector, direction of flight on final leg)
     * @param side +1 detour left, -1 right
     */
    public static Route plan(Vec3 launch, Vec3 target, Vec3 approach, double length, double entry, double side) {
        Vec3 dir = new Vec3(approach.x, 0, approach.z);
        dir = dir.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : dir.normalize();
        Vec3 e = new Vec3(target.x - dir.x * entry, 0, target.z - dir.z * entry);
        Vec3 l = new Vec3(launch.x, 0, launch.z);
        double le = horizontal(l, e);
        List<Vec3> pts = new ArrayList<>();
        double need = length - entry;
        if (need > le + 1) {
            Vec3 m = l.add(e).scale(0.5);
            Vec3 along = le > 1 ? e.subtract(l).normalize() : dir;
            Vec3 perp = new Vec3(-along.z, 0, along.x).scale(side);
            double half = le / 2;
            double h = Math.sqrt(Math.max(0, need * need / 4 - half * half));
            pts.add(m.add(perp.scale(h)));
        }
        pts.add(e);
        return new Route(pts, l);
    }

    /**
     * This route's remaining points in a salvo projectile's lane ({@code WeaponSpec.Lanes}): shift path sideways
     * {@code offset} blocks (sign gives side, like {@code side} in {@link #plan}), smoothly: path offset = offset
     * · min(1, s / exit) · min(1, r / converge), where s is path from route start, r is path to target. No shift
     * at start or target: projectile exits launch heading into its lane and converges on its own aim point
     * (target itself does not move). Offset route points (at turns use the bisector so both legs stay in lane;
     * above 120° use two points, one beside each leg: bisector would extend beyond 2·offset along path and swap
     * lane sides on a reversal). Add a point where lane entry ends (s = exit) or convergence begins (r =
     * converge) within a leg: between these, fly exactly parallel to the path. Requires route {@link #origin};
     * otherwise unchanged. Launch only: first leg runs from {@link #origin} to nearest unpassed point, valid only
     * before any point is captured.
     *
     * @param target aim point: projectile heads here after the last waypoint
     */
    public Route inLane(double offset, double exit, double converge, Vec3 target) {
        if (offset == 0 || origin == null) return this;
        List<Vec3> path = new ArrayList<>();
        path.add(origin);
        path.addAll(points.subList(next, points.size()));
        path.add(new Vec3(target.x, 0, target.z));
        double total = 0;
        for (int i = 1; i < path.size(); i++) total += horizontal(path.get(i - 1), path.get(i));
        // Points where offset stops growing and starts shrinking: within the leg, at least one block from its ends.
        double[] breaks = {Math.min(exit, total - converge), Math.max(exit, total - converge)};
        List<Vec3> pts = new ArrayList<>();
        List<Double> along = new ArrayList<>();
        double s = 0;
        for (int i = 1; i < path.size(); i++) {
            Vec3 a = path.get(i - 1), b = path.get(i);
            double len = horizontal(a, b);
            for (double at : breaks) {
                if (at > s + 1 && at < s + len - 1 && (along.isEmpty() || at > along.getLast() + 1)) {
                    pts.add(a.add(b.subtract(a).scale((at - s) / len)));
                    along.add(at);
                }
            }
            s += len;
            if (i < path.size() - 1) {
                pts.add(b);
                along.add(s);
            }
        }
        List<Vec3> out = new ArrayList<>(pts.size());
        for (int i = 0; i < pts.size(); i++) {
            Vec3 p = pts.get(i);
            Vec3 prev = i == 0 ? origin : pts.get(i - 1);
            Vec3 after = i == pts.size() - 1 ? path.getLast() : pts.get(i + 1);
            double sAt = along.get(i);
            double k = (exit > 0 ? Math.min(1, sAt / exit) : 1) * (converge > 0 ? Math.min(1, (total - sAt) / converge) : 1);
            for (Vec3 side : sides(prev, p, after)) out.add(p.add(side.scale(offset * k)));
        }
        return new Route(out, origin, operator);
    }

    /**
     * Unit lateral offsets at {@code p} between legs prev → p → next ({@link #across}): up to 120°, one along the
     * bisector, longer (1 / cos half-angle, at most 2) to keep both legs one unit from path; steeper, two, one
     * beside each leg (a reversal has no bisector and the lane must not switch sides).
     */
    private static List<Vec3> sides(Vec3 prev, Vec3 p, Vec3 next) {
        Vec3 in = across(prev, p), out = across(p, next);
        if (in == null) in = out;
        if (out == null) out = in;
        if (in == null) return List.of(Vec3.ZERO);
        Vec3 sum = in.add(out);
        double len = sum.length();
        // |in + out| = 2·cos(θ/2): less than 1 means a turn above 120°.
        if (len < 1) return List.of(in, out);
        return List.of(sum.scale(2 / (len * len)));
    }

    /** Horizontal unit sideways from a → b, toward {@code side} = +1 in {@link #plan}; null if points coincide. */
    @Nullable
    private static Vec3 across(Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z, len = Math.sqrt(dx * dx + dz * dz);
        return len < 1.0e-6 ? null : new Vec3(-dz / len, 0, dx / len);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Vec3 p : points) {
            CompoundTag t = new CompoundTag();
            t.putDouble("x", p.x);
            t.putDouble("z", p.z);
            list.add(t);
        }
        tag.put("points", list);
        tag.putInt("next", next);
        if (origin != null) {
            tag.putDouble("origin_x", origin.x);
            tag.putDouble("origin_z", origin.z);
        }
        if (operator) tag.putBoolean("operator", true);
        return tag;
    }

    public static Route load(CompoundTag tag) {
        List<Vec3> pts = new ArrayList<>();
        for (Tag t : tag.getList("points", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            pts.add(new Vec3(c.getDouble("x"), 0, c.getDouble("z")));
        }
        Vec3 origin = tag.contains("origin_x") ? new Vec3(tag.getDouble("origin_x"), 0, tag.getDouble("origin_z")) : null;
        Route r = new Route(pts, origin, tag.getBoolean("operator"));
        r.next = Math.min(tag.getInt("next"), pts.size());
        return r;
    }
}
