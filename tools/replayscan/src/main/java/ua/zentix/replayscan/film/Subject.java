package ua.zentix.replayscan.film;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Shot subject: projectile (UUID/tick path from scan {@code tracks} and distant {@code far} rows),
 * events (impact tick/position/fireball diameter), site (chaos impact centre/spread). Look target at
 * {@code t}: event for first {@link #KEY_HOLD} ticks (rising ball), projectile before event, launch site
 * before projectile appears; site if no path.
 */
final class Subject {
    /** Impact: replay tick, position, fireball diameter in blocks. */
    record Key(int t, Vec3 pos, double size) {}

    /** Ticks camera looks at an event after it starts. */
    static final int KEY_HOLD = 60;

    @Nullable
    final UUID uuid;
    /** Projectile width, blocks. */
    final double size;
    private final double[][] path;
    final List<Key> keys;
    @Nullable
    final Vec3 area;
    final double areaSize;

    Subject(@Nullable String uuid, double size, List<double[]> path, List<Key> keys, @Nullable Vec3 area, double areaSize) {
        this.uuid = uuid == null ? null : UUID.fromString(uuid);
        this.size = size;
        this.path = path.stream().sorted(Comparator.comparingDouble(p -> p[0])).toArray(double[][]::new);
        this.keys = keys.stream().sorted(Comparator.comparingInt(Key::t)).toList();
        this.area = area;
        this.areaSize = areaSize;
    }

    /** First event tick ({@code −1} if no events). */
    int firstKey() {
        return keys.isEmpty() ? -1 : keys.get(0).t();
    }

    /** Event viewed at tick t: latest starting within the past {@link #KEY_HOLD} ticks. */
    @Nullable
    Key keyAt(double t) {
        Key at = null;
        for (Key k : keys) {
            if (k.t() <= t && t < k.t() + KEY_HOLD) at = k;
        }
        return at;
    }

    /** Projectile position at t from path (linear between points, nearest endpoint beyond edges); null without path. */
    @Nullable
    Vec3 pathAt(double t) {
        if (path.length == 0) return null;
        if (t <= path[0][0]) return at(0);
        if (t >= path[path.length - 1][0]) return at(path.length - 1);
        int lo = 0, hi = path.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (path[mid][0] <= t) lo = mid;
            else hi = mid;
        }
        double k = (t - path[lo][0]) / Math.max(1e-9, path[hi][0] - path[lo][0]);
        return at(lo).lerp(at(hi), k);
    }

    private Vec3 at(int i) {
        return new Vec3(path[i][1], path[i][2], path[i][3]);
    }

    /** Path projectile speed at t, blocks/tick; 0 without path. */
    double speed(double t) {
        if (path.length < 2) return 0;
        return pathAt(t).distanceTo(pathAt(t - 1));
    }

    double pathFirst() {
        return path.length == 0 ? Double.NaN : path[0][0];
    }

    double pathLast() {
        return path.length == 0 ? Double.NaN : path[path.length - 1][0];
    }

    /** Look point at t; null if nothing to view. */
    @Nullable
    Vec3 aim(double t) {
        Key k = keyAt(t);
        if (k != null) return k.pos().add(0, Math.min(1, (t - k.t()) / 40.0) * k.size() * 0.4, 0);
        Vec3 p = pathAt(t);
        if (p != null) {
            // After path (projectile left recording), use its last event
            if (t > pathLast() && !keys.isEmpty() && keys.get(keys.size() - 1).t() <= t) return keys.get(keys.size() - 1).pos();
            return p;
        }
        if (area != null) return area;
        return keys.isEmpty() ? null : keys.get(0).pos();
    }

    /** Width of the viewed subject at t. */
    double aimSize(double t) {
        Key k = keyAt(t);
        if (k != null) return k.size();
        if (path.length > 0) return size;
        if (area != null) return Math.max(areaSize, size);
        return keys.isEmpty() ? size : keys.get(0).size();
    }

    /** Horizontal approach heading for first event from prior 40 ticks of path; north without path. */
    Vec3 heading() {
        if (path.length < 2) return new Vec3(0, 0, -1);
        double t1 = firstKey() >= 0 ? Math.min(firstKey() - 1, pathLast()) : pathLast(), t0 = Math.max(pathFirst(), t1 - 40);
        Vec3 d = pathAt(t1).subtract(pathAt(t0)).multiply(1, 0, 1);
        return d.lengthSqr() < 1e-6 ? new Vec3(0, 0, -1) : d.normalize();
    }

    /** Main shot site: first event, otherwise site centre, otherwise path centre. */
    @Nullable
    Vec3 anchor(int a, int b) {
        if (!keys.isEmpty()) return keys.get(0).pos();
        if (area != null) return area;
        return pathAt(Mth.clamp((a + b) / 2.0, pathFirst(), pathLast()));
    }
}
