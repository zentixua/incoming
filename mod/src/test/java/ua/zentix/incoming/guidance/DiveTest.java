package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiveTest {
    /** Meeting point: the projectile and the target arrive at it at the same time. */
    @Test
    void interceptIsReachedTogether() {
        Vec3 pos = new Vec3(0, 45, 0), target = new Vec3(40, 0, 0), v = new Vec3(0, 0, 0.5);
        Vec3 meet = Dive.intercept(pos, 4, target, v);
        double t = meet.subtract(target).length() / v.length();
        assertEquals(t * 4, meet.distanceTo(pos), 1e-6);
        assertEquals(target, Dive.intercept(pos, 4, target, Vec3.ZERO));
    }

    /**
     * Pike from a loitering circle (40 blocks to the side, 45 above) on a target that is walking or circling (like a player in flight
     *  or a wandering mob) until 0.6 block/tick: the projectile passes within the range of damage (speed + 3 block) from the first approach,
     *  from whatever side of the circle you start.
     */
    @Test
    void hitsMovingTargetFromCircle() {
        for (double v : new double[]{0, 0.3, 0.6}) {
            for (boolean circling : new boolean[]{false, true}) {
                for (int dir = 0; dir < 12; dir++) {
                    double a = Math.toRadians(dir * 30);
                    Vec3 target = Vec3.ZERO;
                    Vec3 pos = new Vec3(Math.sin(a) * 40, 45, Math.cos(a) * 40);
                    // on a circle course - tangent
                    FlightController flight = new FlightController(FlightController.anglesTo(pos, target)[0] - 90f, 0);
                    double speed = 1.6, closest = Double.MAX_VALUE;
                    Vec3 vel = Vec3.ZERO;
                    for (int t = 0; t < 120; t++) {
                        double heading = circling ? t * v / 16 : 1.1;
                        vel = new Vec3(Math.cos(heading) * v, 0, Math.sin(heading) * v);
                        target = target.add(vel);
                        Dive.steer(flight, pos, speed, target, vel, WeaponSpec.LOITER.airframe().handling().dive());
                        speed = Math.min(4, speed + 0.1);
                        pos = pos.add(flight.forward().scale(speed));
                        closest = Math.min(closest, pos.distanceTo(target));
                    }
                    assertTrue(closest <= 4 + 3, "past on " + closest + ": v=" + v + (circling ? " in a circle" : " straight") + " s " + dir * 30 + "°");
                }
            }
        }
    }
}
