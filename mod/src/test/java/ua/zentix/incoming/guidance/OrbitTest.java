package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OrbitTest {
    private static final Vec3 CENTER = Vec3.ZERO;
    /** Loitering: 1.6 block/tick. */
    private static final double SPEED = 1.6;

    /**
     * All radii chosen by the patroller (32–48), both directions of flight, approach from 12 sides, from 60–600 blocks
     *  and with a course past the center to ±40°: entering the circle, and then the craft maintains its line within {@link Orbit#TOLERANCE}.
     */
    @Test
    void capturesAndHoldsCircleFromAnyApproach() {
        double worst = 0;
        String where = "";
        for (double radius = 32; radius <= 48; radius += 4) {
            for (int side : new int[]{1, -1}) {
                for (int dir = 0; dir < 12; dir++) {
                    for (double start : new double[]{60, 150, 600}) {
                        for (double miss : new double[]{-40, 0, 40}) {
                            double a = Math.toRadians(dir * 30);
                            Vec3 pos = new Vec3(Math.sin(a) * start, 0, Math.cos(a) * start);
                            Orbit orbit = new Orbit(radius, side);
                            FlightController flight = new FlightController(FlightController.anglesTo(pos, CENTER)[0] + (float) miss, 0);
                            int captured = -1;
                            for (int t = 0; t < 1500; t++) {
                                orbit.steer(flight, pos, CENTER, Vec3.ZERO, SPEED);
                                pos = pos.add(flight.forward().scale(SPEED));
                                if (captured < 0 && orbit.captured(pos, CENTER, Vec3.ZERO, SPEED, flight.yaw())) captured = t;
                                if (captured >= 0 && Math.abs(orbit.offset(pos, CENTER)) > Math.abs(worst)) {
                                    worst = orbit.offset(pos, CENTER);
                                    where = "R=" + radius + " side=" + side + " dir=" + dir * 30 + " start=" + start + " miss=" + miss;
                                }
                            }
                            assertTrue(captured >= 0 && captured < (start + 300) / SPEED,
                                    "did not enter the circle: R=" + radius + " side=" + side + " dir=" + dir * 30 + " start=" + start + " miss=" + miss);
                        }
                    }
                }
            }
        }
        assertTrue(Math.abs(worst) <= Orbit.TOLERANCE, "left the circle " + worst + ": " + where);
    }

    /** Flight side: +1 and −1 are circling in different directions (heading speed of different sign). */
    @Test
    void sideSetsDirection() {
        for (int side : new int[]{1, -1}) {
            Orbit orbit = new Orbit(40, side);
            Vec3 pos = new Vec3(0, 0, -40);
            FlightController flight = new FlightController((float) (FlightController.anglesTo(pos, CENTER)[0] - side * 90.0), 0);
            double turned = 0;
            for (int t = 0; t < 100; t++) {
                float before = flight.yaw();
                orbit.steer(flight, pos, CENTER, Vec3.ZERO, SPEED);
                pos = pos.add(flight.forward().scale(SPEED));
                turned += net.minecraft.util.Mth.wrapDegrees(flight.yaw() - before);
            }
            assertTrue(turned * side > 100, "side " + side + ": turned to " + turned);
        }
    }

    /**
     * The center goes (target - mob or player, 0.1–0.6 block/tick, straight or in a circle): craft enters a circle around
     *  him and keeps his line the same as that of a stationary one; at the target, which quickly circles itself (the player is in flight), -
     *  is twice as free: the course field does not predict its turns.
     */
    @Test
    void followsMovingCenter() {
        for (double v : new double[]{0.1, 0.3, 0.6}) {
            for (boolean circling : new boolean[]{false, true}) {
                for (int side : new int[]{1, -1}) {
                    Orbit orbit = new Orbit(40, side);
                    Vec3 center = Vec3.ZERO, pos = new Vec3(300, 0, -200);
                    FlightController flight = new FlightController(FlightController.anglesTo(pos, center)[0], 0);
                    int captured = -1;
                    double worst = 0;
                    for (int t = 0; t < 2000; t++) {
                        double heading = circling ? t * v / 16 : 0.7;
                        Vec3 vel = new Vec3(Math.cos(heading) * v, 0, Math.sin(heading) * v);
                        center = center.add(vel);
                        orbit.steer(flight, pos, center, vel, SPEED);
                        pos = pos.add(flight.forward().scale(SPEED));
                        if (captured < 0 && orbit.captured(pos, center, vel, SPEED, flight.yaw())) captured = t;
                        if (captured >= 0) worst = Math.max(worst, Math.abs(orbit.offset(pos, center)));
                    }
                    String what = "v=" + v + (circling ? " in a circle" : " straight") + " side=" + side;
                    assertTrue(captured >= 0 && captured < 600, "didn't make it to the circle: " + what);
                    double allowed = circling && v > 0.3 ? 2 * Orbit.TOLERANCE : Orbit.TOLERANCE;
                    assertTrue(worst <= allowed, "left the circle " + worst + ": " + what);
                }
            }
        }
    }
}
