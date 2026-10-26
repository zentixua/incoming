package ua.zentix.incoming.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Orbit above a point, a vector heading field (Nelson et al., “Vector Field Path Following for Miniature Air
 * Vehicles”, IEEE Trans. Robotics, 2007): tangent turned toward the circle by atan(k·(r − R)). Far away this aims
 * almost at the center; near the circle it smoothly approaches the tangent, entering without overshoot. Add v/r
 * feed-forward, the tangent's own turn rate, to heading-error correction; without it, orbit heading lags by
 * ω/gain and the craft drifts outward. Center may move (player or craft target): field sets heading relative to
 * center; ground heading compensates its velocity like wind drift in the same paper.
 *
 * @param radius orbit radius, blocks
 * @param side orbit direction: +1 or −1
 */
public record Orbit(double radius, int side) {
    /** Field steepness: near the circle, 2° toward it per block of deviation. */
    private static final double CONVERGENCE = Math.toRadians(2);
    /** Heading-error correction and turn limits (°/tick, °/tick²) for a loitering munition at 1.6 blocks/tick. */
    private static final double GAIN = 0.1, MAX_RATE = 4.0, MAX_ACCEL = 0.4;
    /** Orbit captured: within this distance of its line and tangent heading within {@link #CAPTURE_YAW}. */
    private static final double CAPTURE_DISTANCE = 4, CAPTURE_YAW = 15;
    /** Maximum blocks the orbiting craft may deviate from its line (including orbit entry). */
    public static final double TOLERANCE = 8;

    /** Field heading at {@code pos} around {@code center}, degrees. */
    public double heading(Vec3 pos, Vec3 center) {
        double r = horizontal(pos, center);
        double toCenter = FlightController.anglesTo(pos, center)[0];
        return toCenter - side * (90 - Math.toDegrees(Math.atan(CONVERGENCE * (r - radius))));
    }

    /**
     * Turn with the field: ground heading that makes motion relative to a center moving at {@code centerVelocity}
     * follow {@link #heading}; feed-forward is tangent rotation at center-relative speed.
     */
    public void steer(FlightController flight, Vec3 pos, Vec3 center, Vec3 centerVelocity, double speed) {
        double r = Math.max(1, horizontal(pos, center));
        double heading = heading(pos, center);
        double relative = relativeSpeed(heading, centerVelocity, speed);
        flight.trackYaw(groundCourse(heading, centerVelocity, relative), side * Math.toDegrees(relative / r), GAIN, MAX_RATE, MAX_ACCEL);
    }

    /** On orbit: near its line and heading tangent (compensating center motion). */
    public boolean captured(Vec3 pos, Vec3 center, Vec3 centerVelocity, double speed, float yaw) {
        double tangent = FlightController.anglesTo(pos, center)[0] - side * 90.0;
        double course = groundCourse(tangent, centerVelocity, relativeSpeed(tangent, centerVelocity, speed));
        return Math.abs(horizontal(pos, center) - radius) < CAPTURE_DISTANCE && Math.abs(Mth.wrapDegrees(course - yaw)) < CAPTURE_YAW;
    }

    /**
     * Center-relative speed k along {@code heading}: ground velocity w = v_center + k·u, |w| = speed. If center
     * is faster than the craft can catch along this heading, 0 (ground heading is then simply {@code heading}).
     */
    private static double relativeSpeed(double heading, Vec3 centerVelocity, double speed) {
        Vec3 u = Vec3.directionFromRotation(0, (float) heading);
        double vx = centerVelocity.x, vz = centerVelocity.z;
        double b = vx * u.x + vz * u.z;
        double disc = b * b - (vx * vx + vz * vz) + speed * speed;
        return disc < 0 ? 0 : Math.max(0, -b + Math.sqrt(disc));
    }

    private static double groundCourse(double heading, Vec3 centerVelocity, double relative) {
        if (relative <= 0) return heading;
        Vec3 u = Vec3.directionFromRotation(0, (float) heading);
        return FlightController.anglesTo(Vec3.ZERO, new Vec3(centerVelocity.x + u.x * relative, 0, centerVelocity.z + u.z * relative))[0];
    }

    /** Horizontal deviation from orbit line: > 0 outside. */
    public double offset(Vec3 pos, Vec3 center) {
        return horizontal(pos, center) - radius;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
