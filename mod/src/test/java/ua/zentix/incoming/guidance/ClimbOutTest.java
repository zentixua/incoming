package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.flight.ProximityFuse;
import ua.zentix.incoming.strike.WeaponSpec;

import java.util.List;
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
    private static final Autopilot.Turn CRUISE_TURN = Autopilot.Turn.of(L.cruiseYawGain(), H.cruise());

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

    /**
     * Valley wall beside the launcher (review probe): ridge 72 high across the course 125 blocks out, a 35-block wall to
     * the west 12 blocks off the course over Z 25–89, the 96-block launch sector clear. Whichever orbit side the munition
     * prefers, it circles on the side whose circle is clear and passes the ridge.
     */
    @Test
    void sideWallIsNotCircledInto() {
        AutopilotPropertiesTest.Ground ground = (x, z) -> {
            if (x < -12 && z >= 25 && z < 90) return 35;
            if (z >= 125 && z < 145) return 72;
            return 0;
        };
        Vec3 target = new Vec3(0.5, 0, 600.5);
        for (int preferred : new int[]{1, -1}) {
            String crash = climbFromRail(ground, target, preferred);
            assertEquals(null, crash, "preferred side " + preferred);
        }
    }

    /**
     * Under a roof (review probe: Nether ceiling, ready chunks report its top at Y 128, open air below): the surface
     * says nothing about the craft's air, so a nearby target in that air does not make it circle or climb.
     */
    @Test
    void roofDoesNotCircle() {
        Vec3 pos = new Vec3(0.5, 85, 0.5), aim = new Vec3(0.5, 40, 45.5);
        AutopilotPropertiesTest.Model c = new AutopilotPropertiesTest.Model((x, z) -> 128, pos, AIR.cruiseSpeed());
        ClimbOut sensor = new ClimbOut();
        sensor.measure(c, aim, CRUISE_TURN, H.climbAngle(), Bearing.of(pos, aim).horizontal(), 1);
        assertEquals(0, sensor.circle(pos));
        assertEquals(Double.NEGATIVE_INFINITY, sensor.ridge());
    }

    /**
     * Operator route (review probe): the course ends at the route point, where the route turns; a wall beyond that point
     * on the old heading is not on the course.
     */
    @Test
    void wallBeyondRoutePointIsNotOnTheCourse() {
        Vec3 pos = new Vec3(0.5, 45, 0.5);
        Vec3 waypoint = new Vec3(0.5, 0, 96.5);
        AutopilotPropertiesTest.Model c = new AutopilotPropertiesTest.Model((x, z) -> z >= 145 ? 115 : 0, pos, AIR.cruiseSpeed());
        Route route = Route.operator(List.of(waypoint), pos);
        route.update(pos, WeaponSpec.LOITER.capture());
        Vec3 nav = route.current();
        ClimbOut sensor = new ClimbOut();
        sensor.measure(c, nav, CRUISE_TURN, H.climbAngle(), Math.min(ClimbOut.REACH, nav.subtract(pos).horizontalDistance()), 1);
        assertEquals(0, sensor.circle(pos));
        assertTrue(sensor.ridge() < 1, "ridge " + sensor.ridge());
    }

    /**
     * Rail launch with the game launch profile and the Lancet's climb and cruise steering ({@code LoiterEntity}: climb
     * phase, then cruise with the orbit field), its 35-block straight-ahead floor and the climb-out; body swept as in
     * flight. Returns the crash, or null once past Z 150.
     */
    private static String climbFromRail(AutopilotPropertiesTest.Ground ground, Vec3 aim, int preferred) {
        Vec3 rail = new Vec3(0.5, 2, 0.5);
        AutopilotPropertiesTest.Model c = new AutopilotPropertiesTest.Model(ground, rail, 0);
        c.setPhase(FlightPhase.CLIMB);
        c.altitude.reset(rail.y);
        c.flight.set(0, -WeaponSpec.LOITER.rack().elevation());
        for (int boost = 1; boost <= AIR.launchProfile().boostTicks(); boost++) {
            c.speed = AIR.launchProfile().boost(c.flight, c.speed, boost);
            c.pos = c.pos.add(c.flight.forward().scale(c.speed));
        }
        ClimbOut sensor = new ClimbOut();
        double cruiseAlt = Math.max(rail.y + L.launchClearance(), aim.y + AIR.cruiseHeight());
        for (c.tick = 1; c.tick < 1000 && c.pos.z < 150; c.tick++) {
            boolean climbing = c.phase == FlightPhase.CLIMB;
            WeaponSpec.Agility a = climbing ? H.climb() : H.cruise();
            double yawGain = climbing ? L.climbYawGain() : L.cruiseYawGain();
            double yaw = Math.toRadians(c.flight.yaw());
            Vec3 ahead = c.pos.add(-Math.sin(yaw) * AIR.reliefLookahead(), 0, Math.cos(yaw) * AIR.reliefLookahead());
            double floor = Autopilot.reliefAlong(new double[]{c.pos.x, c.pos.z, ahead.x, ahead.z}, c.reliefSurvey()) + L.terrainClearance();
            c.throttle(AIR, AIR.cruiseSpeed());
            sensor.measure(c, aim, Autopilot.Turn.of(yawGain, a), H.climbAngle(), Math.min(ClimbOut.REACH, c.pos.subtract(aim).horizontalDistance()), preferred);
            cruiseAlt = Math.max(cruiseAlt, sensor.ridge() + L.terrainClearance());
            int side = sensor.circle(c.pos);
            c.holdAltitude(Math.max(cruiseAlt, floor), L.altitudeGain(), H, a);
            if (side != 0) {
                c.flight.steerYaw(c.flight.yaw() + side * 90.0, yawGain, a.turnRate(), a.turnAccel());
            } else if (climbing) {
                c.flight.steerYaw(Bearing.of(c.pos, aim).yaw(), yawGain, a.turnRate(), a.turnAccel());
            } else {
                new Orbit(L.radius(), preferred).steer(c.flight, c.pos, aim, Vec3.ZERO, c.speed);
            }
            if (climbing && c.phaseAge() > L.climbTicks()) c.setPhase(FlightPhase.CRUISE);
            Vec3 dir = c.flight.forward();
            for (double d = AIR.noseLength() / 2; d <= c.speed + AIR.noseLength(); d += 0.05) {
                Vec3 at = c.pos.add(dir.scale(d));
                double top = ground.at(Math.floor(at.x) + 0.5, Math.floor(at.z) + 0.5);
                if (at.y < top) return String.format(Locale.ROOT, "crashed at (%.1f %.1f %.1f) on tick %d, %s", at.x, at.y, at.z, c.tick, c.phase);
            }
            c.pos = c.pos.add(dir.scale(c.speed));
        }
        return c.pos.z >= 150 ? null : "did not pass the ridge: " + c.pos;
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
            int side = 0;
            if (climbOut) {
                double reach = Math.min(ClimbOut.REACH, Math.hypot(TARGET.x - c.pos.x, TARGET.z - c.pos.z));
                sensor.measure(c, TARGET, Autopilot.Turn.of(L.climbYawGain(), H.climb()), H.climbAngle(), reach, 1);
                cruiseAlt = Math.max(cruiseAlt, sensor.ridge() + L.terrainClearance());
                side = sensor.circle(c.pos);
            }
            c.holdAltitude(cruiseAlt, L.altitudeGain(), H, H.climb());
            if (side != 0) {
                circling++;
                c.flight.steerYaw(c.flight.yaw() + side * 90.0, L.climbYawGain(), H.climb().turnRate(), H.climb().turnAccel());
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
