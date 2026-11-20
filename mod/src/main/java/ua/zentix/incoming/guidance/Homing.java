package ua.zentix.incoming.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Interceptor pursuit: heading toward the point where it meets the target ({@link #lead}) with a turn-rate limit,
 * plus closest approach of two points within a tick for the proximity fuze. Independent of the Minecraft world
 * (unit test {@code HomingTest}).
 */
public final class Homing {
    private Homing() {}

    /**
     * Turn unit direction {@code dir} toward unit {@code want} by at most {@code maxDeg} degrees along the great
     * circle in their plane. Target directly behind: turn over the top (vertical heading: via +X).
     */
    public static Vec3 turn(Vec3 dir, Vec3 want, double maxDeg) {
        double cos = Mth.clamp(dir.dot(want), -1, 1);
        double max = Math.toRadians(maxDeg);
        if (Math.acos(cos) <= max) return want;
        // Perpendicular to dir toward want.
        Vec3 side = want.subtract(dir.scale(cos));
        if (side.lengthSqr() < 1.0e-12) {
            Vec3 up = Math.abs(dir.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            side = up.subtract(dir.scale(up.dot(dir)));
        }
        side = side.normalize();
        return dir.scale(Math.cos(max)).add(side.scale(Math.sin(max))).normalize();
    }

    /**
     * Where a missile leaving {@code from} at {@code speed} meets a target at {@code at} at the same instant, flying
     * straight on at {@code velocity} (both per tick): the predicted intercept point that a SAM's fire control steers
     * its missile to, recomputed every tick. A missile not faster than the target aims at the target itself
     * (fallback: a meeting point need not exist, e.g. receding).
     * <p>
     * Pure pursuit (always at the target) needs an ever sharper turn at the end when the missile is much faster
     * than a crossing target; at real speeds (50 blocks/tick against 12.5) it passed cruise missiles 10–40 blocks
     * away.
     */
    public static Vec3 lead(Vec3 from, double speed, Vec3 at, Vec3 velocity) {
        Vec3 r = at.subtract(from);
        // |r + v t| = s t: (v·v − s²) t² + 2 (r·v) t + r·r = 0, a < 0 gives one positive root
        double a = velocity.lengthSqr() - speed * speed, b = r.dot(velocity), c = r.lengthSqr();
        if (a >= 0) return at;
        double t = (-b - Math.sqrt(b * b - a * c)) / a;
        return at.add(velocity.scale(t));
    }

    /**
     * Tick fraction {@code s} ∈ [0, 1] when points moving uniformly from {@code a0} to {@code a1} and {@code b0}
     * to {@code b1} are closest: a fast missile passes through the target within a tick, which endpoint distances
     * miss.
     */
    public static double closest(Vec3 a0, Vec3 a1, Vec3 b0, Vec3 b1) {
        Vec3 r0 = a0.subtract(b0);
        Vec3 dv = a1.subtract(a0).subtract(b1.subtract(b0));
        double dd = dv.lengthSqr();
        if (dd < 1.0e-12) return 0;
        return Mth.clamp(-r0.dot(dv) / dd, 0, 1);
    }

    /** Distance between the same points at tick fraction {@code s}. */
    public static double distanceAt(Vec3 a0, Vec3 a1, Vec3 b0, Vec3 b1, double s) {
        return a0.lerp(a1, s).distanceTo(b0.lerp(b1, s));
    }
}
