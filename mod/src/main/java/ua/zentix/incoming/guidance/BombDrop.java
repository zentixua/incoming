package ua.zentix.incoming.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.strike.WeaponSpec;

/**
 * Bunker-buster bomb fall after release: controls steer the nose in an arc toward the point; speed comes from
 * gravity along the path and air drag (bomb {@link WeaponSpec.Ballistic} in the spec), with wind drift. The bomb
 * and bomber use identical steps; before release the B-2 simulates its fall ({@link #miss}): would a bomb
 * released now hit from this height and range?
 */
public final class BombDrop {
    /** Bomb this far below B-2 (separates from the belly). */
    public static final double DROP_BELOW = 4;
    /** Detonation range margin beyond one step: within step plus this margin of the point counts as a hit. */
    public static final double REACH_PAD = 5.3;
    /**
     * Nose length: each tick the bomb checks obstacles from nose midpoint to its tip after the step (like all
     * projectiles).
     */
    public static final double NOSE = 3.1;
    /**
     * Release window edge along heading: no farther than height above point, nose-lowering arc ({@link
     * #pullOver}), and this.
     */
    public static final double EDGE_REACH = 30;
    /** Bomb controls: maximum pitch speed °/tick and acceleration °/tick² (game values, not physical). */
    private static final double FIN_RATE = 7, FIN_ACCEL = 0.8;
    /** Ground this far above aim point: B-2 aims at top-block center; bomb strikes its top. */
    public static final double GROUND_ABOVE_AIM = 0.5;
    /**
     * Minimum nose-down angle: bomb inherits carrier velocity and pitch ({@link #miss}, nearly horizontal);
     * controls lower the nose and never raise it again; once down to this angle, it does not level to a shallower
     * angle.
     */
    public static final float MIN_DIVE = 10;
    /** Fall when there is no target under the nose. */
    public static final float FALL_PITCH = 60;
    /**
     * Minimum bomb fall ticks in {@link #miss}: from up to ~1000 blocks it reaches ground sooner. Higher
     * (flight-scale altitude up to 12 km), twice the straight-line time at release speed: bomb acceleration or
     * deceleration along the path changes speed by at most a factor of two.
     */
    private static final int MAX_TICKS = 200;

    private BombDrop() {}

    /** Point behind heading {@code yaw} (more than 8 horizontal blocks): bomb can no longer turn toward it. */
    public static boolean passed(Vec3 pos, Vec3 aim, float yaw) {
        double dx = aim.x - pos.x, dz = aim.z - pos.z;
        if (dx * dx + dz * dz <= 64) return false;
        return Math.abs(Mth.wrapDegrees(FlightController.anglesTo(pos, aim)[0] - yaw)) >= 90;
    }

    /**
     * Falling-bomb guidance step: turn nose; returns this tick's speed. Gravity along path accelerates; drag
     * (quadratic, altitude density) decelerates: a bomb released at B-2 speed slows toward √(g/drag); a slow one
     * accelerates toward it.
     */
    public static double steer(FlightController flight, Vec3 pos, double speed, Vec3 aim) {
        return steer(flight, pos, speed, aim, fall());
    }

    /** {@link #steer(FlightController, Vec3, double, Vec3)} with {@code fall} drag (caller supplies flight scale). */
    public static double steer(FlightController flight, Vec3 pos, double speed, Vec3 aim, Ballistics.Drag fall) {
        float[] a = FlightController.anglesTo(pos, aim);
        double dx = aim.x - pos.x, dz = aim.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // A freely falling bomb neither levels nor climbs: point less than MIN_DIVE below the nose
        // (at bomb height or above, already passed) means simply fall steeply, without relying on controls.
        boolean below = a[1] >= MIN_DIVE && !passed(pos, aim, flight.yaw());
        float floor = Math.min(MIN_DIVE, flight.pitch());
        flight.arcPitch(below ? a[1] : FALL_PITCH, speed, pos.distanceTo(aim), FIN_RATE, FIN_ACCEL);
        if (flight.pitch() < floor) flight.set(flight.yaw(), floor);
        if (below && horizontal > 8) flight.steerYaw(a[0], 0.15, 3.0, 0.3);
        double drag = fall.at(pos.y, speed);
        return Math.max(0, speed - Ballistics.GRAVITY * flight.forward().y - drag * speed * speed);
    }

    /**
     * Arc radius for controls lowering the nose at {@code speed}: v / ω. Released horizontally at B-2 speed (12
     * blocks/tick), the bomb travels ~100 blocks along heading before descending steeply; closer points are
     * overshot from any height.
     */
    public static double pullOver(double speed) {
        return speed / Math.toRadians(FIN_RATE);
    }

    /**
     * Wind drift per tick: the bomb's transverse air-relative velocity is pulled toward {@code wind}
     * (blocks/tick) by drag·|v| per tick; wind barely drifts a heavy fast bomb.
     */
    public static Vec3 drift(Vec3 drift, Vec3 wind, double speed) {
        double k = Math.min(1, fall().coefficient() * speed);
        return drift.add(wind.subtract(drift).scale(k));
    }

    /**
     * Spec bomb drag on world flight scale ({@link WeaponSpec.Airframe#drag()}; read at call time: spec itself
     * references this class).
     */
    private static Ballistics.Drag fall() {
        return WeaponSpec.BUNKER.payload().drag();
    }

    /**
     * Release bomb now: B-2 at {@code pos}, traveling {@code step} per tick (also the bomb's release velocity). A
     * bomb released here hits the point ({@link #miss}), and there is no reason to wait: B-2 reached release
     * range for its height ({@code lineRatio} × height, same angle to point as at cruising altitude), or next
     * tick's release would miss, at the window edge (below ~160 blocks the bomb at B-2 speed lacks room to lower
     * its nose before the line; edge is farther out). Search the edge along heading no farther than height,
     * nose-lowering arc ({@link #pullOver}), and {@link #EDGE_REACH}: farther hits are intermittent (a two-tick
     * window between misses is not an edge). Fall simulation uses the bomb's own steps, so it lands identically
     * on flat ground. Works from any height; if every release misses (point too close ahead, behind, or above
     * B-2), approach again.
     */
    public static boolean releaseNow(Vec3 pos, Vec3 step, Vec3 aim, double lineRatio) {
        return releaseNow(pos, step, aim, lineRatio, fall());
    }

    /** {@link #releaseNow(Vec3, Vec3, Vec3, double)} with bomb drag {@code fall}. */
    public static boolean releaseNow(Vec3 pos, Vec3 step, Vec3 aim, double lineRatio, Ballistics.Drag fall) {
        double dx = aim.x - pos.x, dz = aim.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz), height = pos.y - aim.y, line = height * lineRatio;
        // Edge along heading: a lateral point shifts it by its offset (otherwise below 100 blocks a lateral edge would not be found).
        double course = Math.sqrt(step.x * step.x + step.z * step.z), along = (step.x * dx + step.z * dz) / course;
        if (horizontal > line && along > height + pullOver(step.length()) + EDGE_REACH || miss(pos, step, aim, fall) > 0) return false;
        return horizontal <= line || miss(pos.add(step), step, aim, fall) > 0;
    }

    /**
     * Miss for a bomb released now from {@code release} (B-2 position) at {@code aim} on flat ground ({@link
     * #GROUND_ABOVE_AIM} above the point): 0 means it reaches the point (within a step plus {@link #REACH_PAD});
     * otherwise horizontal ground-entry distance from the point, blocks. Ground is handled as for the world bomb
     * ({@code StrikeProjectile.advance}): first point arrival, then obstacle on the nose segment from midpoint to
     * tip after the step. The nose touches ground one tick before the bomb is within a step plus margin; at the
     * release window edge this is a 13–18-block miss. Bomb inherits carrier {@code velocity} (blocks/tick):
     * magnitude, heading, and pitch.
     */
    public static double miss(Vec3 release, Vec3 velocity, Vec3 aim) {
        return miss(release, velocity, aim, fall());
    }

    /** {@link #miss(Vec3, Vec3, Vec3)} with bomb drag {@code fall}. */
    public static double miss(Vec3 release, Vec3 velocity, Vec3 aim, Ballistics.Drag fall) {
        double ground = aim.y + GROUND_ABOVE_AIM;
        float[] angles = FlightController.anglesTo(Vec3.ZERO, velocity);
        FlightController flight = new FlightController(angles[0], angles[1]);
        Vec3 pos = release.add(0, -DROP_BELOW, 0);
        double speed = velocity.length();
        int ticks = Math.max(MAX_TICKS, (int) Math.ceil(2 * release.distanceTo(aim) / Math.max(1, speed)));
        for (int t = 0; t < ticks; t++) {
            speed = steer(flight, pos, speed, aim, fall);
            if (pos.distanceTo(aim) <= speed + REACH_PAD) return 0;
            Vec3 dir = flight.forward();
            Vec3 noseFrom = pos.add(dir.scale(NOSE * 0.5)), noseTo = pos.add(dir.scale(speed + NOSE));
            if (noseTo.y <= ground) {
                Vec3 at = noseFrom.y <= ground ? noseFrom : noseFrom.lerp(noseTo, (noseFrom.y - ground) / (noseFrom.y - noseTo.y));
                return Math.sqrt((at.x - aim.x) * (at.x - aim.x) + (at.z - aim.z) * (at.z - aim.z));
            }
            pos = pos.add(dir.scale(speed));
        }
        return Double.POSITIVE_INFINITY;
    }
}
