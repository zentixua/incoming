package ua.zentix.incoming.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.strike.WeaponSpec;

/**
 * Loitering munition dive at a target: heading and pitch aim at interception with the moving target (velocity
 * lead), rather than its current position. Without lead, a projectile chasing a moving target lags through turns,
 * runs out of pitch near it, misses, loops, and dives again.
 */
public final class Dive {
    /** Dive heading correction gain. */
    private static final double YAW_GAIN = 0.4;
    /** No useful lead beyond this: target velocity will have changed by then. */
    private static final double MAX_LEAD_TICKS = 60;

    private Dive() {}

    /**
     * Turn in a dive toward {@code target}, moving at {@code targetVelocity} (blocks/tick), using spec {@code
     * dive} turn limits.
     */
    public static void steer(FlightController flight, Vec3 pos, double speed, Vec3 target, Vec3 targetVelocity, WeaponSpec.Agility dive) {
        Vec3 aim = intercept(pos, speed, target, targetVelocity);
        float[] a = FlightController.anglesTo(pos, aim);
        flight.arcPitch(a[1], speed, pos.distanceTo(aim), dive.pitchRate(), dive.pitchAccel());
        // Target beside the orbit: sharp banked turn and dive (turn radius ~15 blocks); do not spin directly above the target.
        // But if target is behind heading, turn even above it: pitch stops at 89°, residual drift follows heading and would move
        // away (vertical dive from a 700 m orbit ended 10 m away); vertically, changing heading turns the nose
        // by fractions of a degree.
        double dx = aim.x - pos.x, dz = aim.z - pos.z, h2 = dx * dx + dz * dz;
        boolean behind = h2 > 0.25 && Math.abs(Mth.wrapDegrees(a[0] - flight.yaw())) > 90;
        if (h2 > 9 || behind) flight.steerYaw(a[0], YAW_GAIN, dive.turnRate(), dive.turnAccel());
        else flight.settleYaw(dive.turnAccel());
    }

    /**
     * Interception point: where target will be in t ticks if projectile at {@code speed} reaches it in the same
     * t, the smaller positive root of |d + v·t| = s·t. No interception (faster target): target itself.
     */
    public static Vec3 intercept(Vec3 pos, double speed, Vec3 target, Vec3 targetVelocity) {
        Vec3 d = target.subtract(pos);
        double a = targetVelocity.lengthSqr() - speed * speed;
        double b = 2 * d.dot(targetVelocity);
        double c = d.lengthSqr();
        double t;
        if (Math.abs(a) < 1e-9) {
            t = b < 0 ? -c / b : -1;
        } else {
            double disc = b * b - 4 * a * c;
            if (disc < 0) return target;
            double q = Math.sqrt(disc);
            double t1 = (-b - q) / (2 * a), t2 = (-b + q) / (2 * a);
            double lo = Math.min(t1, t2), hi = Math.max(t1, t2);
            t = lo > 0 ? lo : hi;
        }
        if (!(t > 0)) return target;
        return target.add(targetVelocity.scale(Math.min(t, MAX_LEAD_TICKS)));
    }
}
