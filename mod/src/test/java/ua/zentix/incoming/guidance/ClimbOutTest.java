package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.flight.ProximityFuse;
import ua.zentix.incoming.strike.WeaponSpec;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lancet climb-out from its catapult toward a ridge on the course ({@link ClimbOut}), on the autopilot model
 * ({@link AutopilotPropertiesTest.Model}) with the Lancet's spec handling and its climb steering
 * ({@code LoiterEntity}: climb to the orbit altitude, target 45 blocks above, turn toward the course). The ridge runs
 * across the whole course (no way around it), 1:1.5 slopes as in the flight scenario hill.
 */
class ClimbOutTest {
    private static final WeaponSpec.Airframe AIR = WeaponSpec.LOITER.airframe();
    private static final WeaponSpec.Handling H = AIR.handling();
    private static final WeaponSpec.Loiter L = H.loiter();
    /** Target far beyond the ridge, on the launcher's ground level. */
    private static final Vec3 TARGET = new Vec3(0, 0, 600);

    /** Flight scenario {@code loiter_rail.point.hill.11}: ridge 30.5 high, crest 87.4 blocks from the rail. */
    @Test
    void scenarioHillIsClimbedOut() {
        Flight straight = fly(30.5, 87.4, false), out = fly(30.5, 87.4, true);
        assertTrue(straight.crash != null, "straight climb meets the hillside (the cause): " + straight);
        assertEquals(null, out.crash, out.toString());
    }

    /**
     * Ridges 5–100 high, crest 20–400 blocks from the rail, wherever a launcher may stand (launch sector,
     * {@code LaunchSite.clearAhead}: terrain under the end-of-boost climb ray over the arming distance): the climb-out
     * never meets terrain; it does not circle where the straight maximum-angle climb clears the crest with room to spare.
     */
    @Test
    void anyRidgeIsCleared() {
        double gradient = Math.tan(Math.toRadians(H.climbAngle()));
        double sector = Math.tan(Math.toRadians(-AIR.launchProfile().boostEndPitch()));
        int flown = 0;
        for (double height = 5; height <= 100; height += 5) {
            for (double crest = 20; crest <= 400; crest += 10) {
                double foot = crest - height * 1.5;
                double atArming = Math.max(0, height - Math.max(0, crest - ProximityFuse.ARM_DISTANCE) / 1.5);
                if (foot < ProximityFuse.ARM_DISTANCE && atArming > 2 + sector * Math.min(crest, ProximityFuse.ARM_DISTANCE)) continue;
                flown++;
                Flight f = fly(height, crest, true);
                assertEquals(null, f.crash, f.toString());
                // straight climb from the rail (2 above ground) clearing the crest by 10 blocks: no circle needed
                if (2 + gradient * (crest - 10) > height + 10) assertEquals(0, f.circling, f.toString());
            }
        }
        assertTrue(flown > 400, "ridges flown: " + flown);
    }

    /** Flat ground: the Lancet leaves its catapult straight toward the course. */
    @Test
    void flatGroundDoesNotCircle() {
        Flight f = fly(0, 100, true);
        assertEquals(null, f.crash);
        assertEquals(0, f.circling);
    }

    private record Flight(double height, double crest, String crash, int circling, double clearance) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "ridge %.1f at %.1f: %s, circled %d ticks, lowest %.1f over ground", height, crest,
                    crash == null ? "cleared" : crash, circling, clearance);
        }
    }

    /** Ridge across the course: height {@code height}, crest {@code crest} blocks ahead of the rail (z), slopes 1:1.5. */
    private static Flight fly(double height, double crest, boolean climbOut) {
        AutopilotPropertiesTest.Ground ground = (x, z) -> Math.max(0, height - Math.abs(z - crest) / 1.5);
        Vec3 rail = new Vec3(0.5, 2, 0.5);
        AutopilotPropertiesTest.Model c = new AutopilotPropertiesTest.Model(ground, rail, AIR.cruiseSpeed());
        c.phase = FlightPhase.CLIMB;
        c.altitude.reset(rail.y);
        c.flight.set(FlightController.anglesTo(rail, new Vec3(TARGET.x, rail.y, TARGET.z))[0], -15);
        ClimbOut sensor = new ClimbOut();
        double cruiseAlt = Math.max(rail.y + L.launchClearance(), TARGET.y + AIR.cruiseHeight());
        int circling = 0;
        double lowest = Double.MAX_VALUE;
        for (c.tick = 0; c.tick < 3000 && c.pos.z < crest + height * 1.5 + 20; c.tick++) {
            c.throttle(AIR, AIR.cruiseSpeed());
            boolean circle = false;
            if (climbOut) {
                double reach = Math.min(ClimbOut.REACH, Math.hypot(TARGET.x - c.pos.x, TARGET.z - c.pos.z));
                sensor.measure(c, TARGET, Autopilot.Turn.of(L.climbYawGain(), H.climb()), H.climbAngle(), reach);
                cruiseAlt = Math.max(cruiseAlt, sensor.ridge() + L.terrainClearance());
                circle = sensor.circle(c.pos);
            }
            c.holdAltitude(cruiseAlt, L.altitudeGain(), H, H.climb());
            if (circle) {
                circling++;
                c.flight.steerYaw(c.flight.yaw() + 90, L.climbYawGain(), H.climb().turnRate(), H.climb().turnAccel());
            } else {
                c.flight.steerYaw(FlightController.anglesTo(c.pos, TARGET)[0], L.climbYawGain(), H.climb().turnRate(), H.climb().turnAccel());
            }
            Vec3 dir = c.flight.forward();
            for (double d = 0; d <= c.speed; d += 0.25) {
                Vec3 p = c.pos.add(dir.scale(d));
                lowest = Math.min(lowest, p.y - ground.at(p.x, p.z));
                if (p.y < ground.at(p.x, p.z)) {
                    return new Flight(height, crest, String.format(Locale.ROOT, "crashed at (%.1f %.1f %.1f) on tick %d", p.x, p.y, p.z, c.tick),
                            circling, lowest);
                }
            }
            c.pos = c.pos.add(dir.scale(c.speed));
        }
        String crash = c.pos.z < crest + height * 1.5 + 20 ? "did not pass the ridge" : null;
        return new Flight(height, crest, crash, circling, lowest);
    }
}
