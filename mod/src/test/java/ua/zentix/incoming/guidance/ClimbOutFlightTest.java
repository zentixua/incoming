package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.flight.ProximityFuse;
import ua.zentix.incoming.strike.WeaponSpec;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lancet climb-out flown through the entity's approach phases (review round 2 probes): the circle side is judged by the
 * arc flown, not by a wall on a part of the circle it never reaches; a nearby picked target above the craft does not
 * skip the climb before the dive.
 */
class ClimbOutFlightTest {
    private static final WeaponSpec.Airframe AIR = WeaponSpec.LOITER.airframe();
    private static final WeaponSpec.Handling HANDLING = AIR.handling();
    private static final WeaponSpec.Loiter LOITER = HANDLING.loiter();
    private static final Vec3 AIM = new Vec3(0.5, 0, 600.5);

    @Test
    void distantWallOnPredictedCircleDoesNotSendLaunchIntoNearWall() {
        AutopilotPropertiesTest.Ground ground = (x, z) -> {
            if (x >= 12 && z >= 25 && z < 90) return 35;
            if (x < -60 && z < 15) return 150;
            if (z >= 125 && z < 145) return 72;
            return 0;
        };
        assertNull(fly(ground, true), "control with the preferred climb-out side");
        assertNull(fly(ground, false), "climb-out with circle-side selection");
    }

    @Test
    void nearbyAimAboveTheAircraftDoesNotBypassClimbBeforeDive() {
        AutopilotPropertiesTest.Ground ground = (x, z) -> Math.max(0, Math.min(134, Math.floor((z - 50) * 1.5)));
        Vec3 start = new Vec3(0.5, 20, 0.5);
        Vec3 aim = new Vec3(0.5, 133.5, 139);
        Bearing bearing = Bearing.of(start, aim);
        assertTrue(bearing.horizontal() < LOITER.strikeNow());
        assertTrue(bearing.pitch() < LOITER.immediateDivePitch());
        assertTrue(bearing.horizontal() > LOITER.radius() + LOITER.immediateDivePad());
        AutopilotPropertiesTest.Ground columns = (x, z) -> ground.at(Math.floor(x) + 0.5, Math.floor(z) + 0.5);
        assertEquals(Double.POSITIVE_INFINITY, AutopilotPropertiesTest.blockedAt(columns, start, aim, 0.01),
                "clear camera ray to the face of the selected block");
        assertNull(approachSelectedPoint(ground, start, aim, false), "approach with the course sensor, as the entity flies");
    }

    /** Replays the final-leg CRUISE branch after a camera retarget, through the first dive command. */
    private static String approachSelectedPoint(AutopilotPropertiesTest.Ground ground, Vec3 start, Vec3 aim, boolean bypass) {
        var craft = new AutopilotPropertiesTest.Model(ground, start, AIR.cruiseSpeed());
        craft.altitude.reset(start.y);
        ClimbOut sensor = new ClimbOut();
        double cruiseAlt = aim.y + AIR.cruiseHeight();
        WeaponSpec.Agility agility = HANDLING.cruise();
        double gain = LOITER.cruiseYawGain();
        for (craft.tick = 1; craft.tick <= 1000; craft.tick++) {
            Bearing bearing = Bearing.of(craft.pos, aim);
            double floor = floor(craft);
            craft.throttle(AIR, AIR.cruiseSpeed());
            int side = 0;
            if (!bypass) {
                sensor.measure(craft, aim, Autopilot.Turn.of(gain, agility), HANDLING.climbAngle(), bearing.horizontal(), 1);
                cruiseAlt = Math.max(cruiseAlt, sensor.ridge() + LOITER.terrainClearance());
                side = sensor.circle(craft.pos);
                if (side != 0) {
                    craft.flight.steerYaw(craft.flight.yaw() + side * 90, gain, agility.turnRate(), agility.turnAccel());
                }
            }
            craft.holdAltitude(Math.max(cruiseAlt, floor), LOITER.altitudeGain(), HANDLING, agility);
            if (side == 0) {
                craft.flight.steerYaw(bearing.yaw(), gain, agility.turnRate(), agility.turnAccel());
                if (bearing.pitch() >= LOITER.immediateDivePitch()
                        || bearing.horizontal() <= LOITER.radius() + LOITER.immediateDivePad()) {
                    craft.setPhase(FlightPhase.TERMINAL);
                }
            }
            Vec3 collision = collision(craft, ground);
            if (collision != null) {
                return String.format(Locale.ROOT, "collision at (%.2f, %.2f, %.2f), tick %d (%s), %.1f blocks to aim",
                        collision.x, collision.y, collision.z, craft.tick, craft.phase, collision.distanceTo(aim));
            }
            if (craft.phase == FlightPhase.TERMINAL) return null;
            craft.pos = craft.pos.add(craft.flight.forward().scale(craft.speed));
        }
        return "did not enter terminal";
    }

    /** Replays the two LoiterEntity approach phases through the first ridge. */
    private static String fly(AutopilotPropertiesTest.Ground ground, boolean keepPreferred) {
        Vec3 rail = new Vec3(0.5, 2, 0.5);
        var craft = new AutopilotPropertiesTest.Model(ground, rail, 0);
        craft.setPhase(FlightPhase.CLIMB);
        craft.altitude.reset(rail.y);
        craft.flight.set(0, -WeaponSpec.LOITER.rack().elevation());
        for (int t = 1; t <= AIR.launchProfile().boostTicks(); t++) {
            craft.speed = AIR.launchProfile().boost(craft.flight, craft.speed, t);
            craft.pos = craft.pos.add(craft.flight.forward().scale(craft.speed));
        }
        ClimbOut sensor = new ClimbOut();
        double cruiseAlt = Math.max(rail.y + LOITER.launchClearance(), AIM.y + AIR.cruiseHeight());
        int firstSide = 0;
        for (craft.tick = 1; craft.tick < 1000 && craft.pos.z < 150; craft.tick++) {
            boolean climbing = craft.phase == FlightPhase.CLIMB;
            WeaponSpec.Agility agility = climbing ? HANDLING.climb() : HANDLING.cruise();
            double gain = climbing ? LOITER.climbYawGain() : LOITER.cruiseYawGain();
            double floor = floor(craft);
            craft.throttle(AIR, AIR.cruiseSpeed());
            double reach = Math.min(ClimbOut.REACH, craft.pos.subtract(AIM).horizontalDistance());
            sensor.measure(craft, AIM, Autopilot.Turn.of(gain, agility), HANDLING.climbAngle(), reach, 1);
            cruiseAlt = Math.max(cruiseAlt, sensor.ridge() + LOITER.terrainClearance());
            craft.holdAltitude(Math.max(cruiseAlt, floor), LOITER.altitudeGain(), HANDLING, agility);
            int side = sensor.circle(craft.pos);
            if (side != 0) {
                side = keepPreferred ? 1 : side;
                if (firstSide == 0) firstSide = side;
                craft.flight.steerYaw(craft.flight.yaw() + side * 90, gain, agility.turnRate(), agility.turnAccel());
            } else if (climbing) {
                craft.flight.steerYaw(Bearing.of(craft.pos, AIM).yaw(), gain, agility.turnRate(), agility.turnAccel());
            } else {
                new Orbit(LOITER.radius(), 1).steer(craft.flight, craft.pos, AIM, Vec3.ZERO, craft.speed);
            }
            if (climbing && craft.phaseAge() > LOITER.climbTicks()) craft.setPhase(FlightPhase.CRUISE);
            Vec3 collision = collision(craft, ground);
            if (collision != null) {
                return String.format(Locale.ROOT, "collision at (%.2f, %.2f, %.2f) on tick %d (%s, first side %d, armed %b)",
                        collision.x, collision.y, collision.z, craft.tick, craft.phase, firstSide,
                        ProximityFuse.armed(craft.phase, craft.pos, rail));
            }
            craft.pos = craft.pos.add(craft.flight.forward().scale(craft.speed));
        }
        return craft.pos.z >= 150 ? null : "did not clear the ridge: " + craft.pos;
    }

    private static double floor(AutopilotPropertiesTest.Model craft) {
        double yaw = Math.toRadians(craft.flight.yaw());
        Vec3 ahead = craft.pos.add(-Math.sin(yaw) * AIR.reliefLookahead(), 0, Math.cos(yaw) * AIR.reliefLookahead());
        return Autopilot.reliefAlong(new double[]{craft.pos.x, craft.pos.z, ahead.x, ahead.z},
                craft.reliefSurvey()) + LOITER.terrainClearance();
    }

    private static Vec3 collision(AutopilotPropertiesTest.Model craft, AutopilotPropertiesTest.Ground ground) {
        Vec3 direction = craft.flight.forward();
        Vec3 from = craft.pos.add(direction.scale(AIR.noseLength() * 0.5));
        Vec3 to = craft.pos.add(direction.scale(craft.speed + AIR.noseLength()));
        int steps = (int) Math.ceil(from.distanceTo(to) / 0.05);
        for (int step = 0; step <= steps; step++) {
            Vec3 at = from.lerp(to, (double) step / steps);
            double top = ground.at(Math.floor(at.x) + 0.5, Math.floor(at.z) + 0.5);
            if (at.y < top) return at;
        }
        return null;
    }
}
