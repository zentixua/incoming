package ua.zentix.incoming.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shahed and cruise-missile flight properties, the same as the in-world flight scenarios (GameTest
 * {@code gametest.scenario}), but on a model without Minecraft: real autopilots ({@link DroneAutopilot},
 * {@link MissileAutopilot}), route ({@link Route}) and range ({@link Mission}); the world is a terrain model,
 * flight step matches the in-world projectile ({@code StrikeProjectile.advance}: final-leg hit within one
 * step and fuze margin, otherwise terrain collision). Ten thousand random scenarios on all cores:
 * <ul>
 * <li>flight ends in a hit, within one step and fuze margin of a stationary target, also of moving targets
 * and targets retargeted into the turn circle, before range runs out and without terrain collision;</li>
 * <li>no circling: within a turn radius of a stationary target point, projectile turn totals no more than 720°;</li>
 * <li>the projectile stays above terrain in cruise;</li>
 * <li>terrain blocking the final sightline (target at a quarry bottom, behind a hill after retarget, moving
 * up the slope of a bowl) still leads to a hit ({@link Autopilot.Sightline}).</li>
 * </ul>
 */
class AutopilotPropertiesTest {
    private static final int SCENARIOS = 10_000;
    /** Circling: so many degrees turns in the turning radius from the fixed point of the target - already circles. */
    private static final double MAX_TURN = 720;

    enum Weapon {
        DRONE(WeaponSpec.DRONE, 40), MISSILE(WeaponSpec.MISSILE, 120);

        final WeaponSpec spec;
        /** The radius of capturing the waypoint (same as the entity: {@code navPoint}). */
        final double capture;

        Weapon(WeaponSpec spec, double capture) {
            this.spec = spec;
            this.capture = capture;
        }
    }

    enum Relief { FLAT, HILLS, QUARRY }

    enum Aim { STILL, WALKING, RETARGET_IN_TURN }

    /** Relief: the height of a surface at a point. */
    interface Ground {
        double at(double x, double z);
    }

    /** Projectile model: what the entity gives to the autopilot ({@code StrikeProjectile.craft}). */
    static final class Model implements Craft {
        final FlightController flight = new FlightController(0, 0);
        final AltitudeHold altitude = new AltitudeHold();
        final Ground ground;
        Vec3 pos;
        double speed;
        FlightPhase phase = FlightPhase.CRUISE;
        int tick, phaseStart;

        Model(Ground ground, Vec3 pos, double speed) {
            this.ground = ground;
            this.pos = pos;
            this.speed = speed;
        }

        @Override
        public Vec3 position() {
            return pos;
        }

        @Override
        public FlightController flight() {
            return flight;
        }

        @Override
        public AltitudeHold altitude() {
            return altitude;
        }

        @Override
        public double speed() {
            return speed;
        }

        @Override
        public void setSpeed(double speed) {
            this.speed = speed;
        }

        @Override
        public FlightPhase phase() {
            return phase;
        }

        @Override
        public int phaseAge() {
            return tick - phaseStart;
        }

        @Override
        public void setPhase(FlightPhase phase) {
            if (phase != this.phase) {
                this.phase = phase;
                phaseStart = tick;
            }
        }

        /** Like {@code StrikeProjectile.relief}: the top of the relief in a column of blocks is the largest at its four corners. */
        @Override
        public double relief(int x, int z) {
            return Math.max(Math.max(ground.at(x, z), ground.at(x + 1, z)), Math.max(ground.at(x, z + 1), ground.at(x + 1, z + 1)));
        }

        /** As {@code StrikeProjectile.clearAlong}: to the first point of the straight line below the relief, except for the last {@code margin} blocks. */
        @Override
        public double clearAlong(Vec3 to, double margin) {
            return blockedAt(ground, pos, to, margin);
        }
    }

    record Scenario(long seed, Weapon weapon, Relief relief, Aim aim, boolean routed) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s %s %s %s seed %d", weapon, relief, aim, routed ? "route" : "directly", seed);
        }
    }

    /**
     * @param occluded on approach (hill, peak) the terrain stood between the projectile and the target: such flights should hit
     *                  just like the others; their number is in the test message
     */
    record Outcome(String end, int ticks, double miss, double turn, double clearance, Vec3 at, boolean occluded) {}

    /** How many scenarios must at least cover the straight line to the target with relief on approach: otherwise the approach rule is not checked. */
    private static final int MIN_OCCLUDED = 300;

    @Test
    void randomFlightsHitWithoutCircling() {
        List<String> failures = new ArrayList<>();
        int[] ends = new int[3];
        int occluded = 0;
        // the scenarios are independent (each has its own seed, autopilot and terrain): flights - on all cores, analysis - in order,
        //  the result is the same as in a row; the test ran ~2 min in a row and was ~85 % of the time of all unit tests
        List<Scenario> scenarios = IntStream.range(0, SCENARIOS).mapToObj(i -> {
            SplittableRandom r = new SplittableRandom(i);
            return new Scenario(i, Weapon.values()[r.nextInt(2)], Relief.values()[r.nextInt(3)], Aim.values()[r.nextInt(3)], r.nextBoolean());
        }).toList();
        List<Outcome> outcomes = scenarios.parallelStream().map(AutopilotPropertiesTest::fly).toList();
        for (int i = 0; i < SCENARIOS; i++) {
            Scenario s = scenarios.get(i);
            Outcome o = outcomes.get(i);
            String why = null;
            if (o.occluded) occluded++;
            if (o.end.equals("forever") || o.end.equals("exhausted")) why = o.end;
            else if (!o.end.equals("hit")) why = o.end;
            else if (o.turn > MAX_TURN) why = String.format(Locale.ROOT, "circling %.0f°", o.turn);
            else if (o.clearance < 0) why = String.format(Locale.ROOT, "on a cruise below the terrain at %.1f", -o.clearance);
            ends[o.end.equals("hit") ? 0 : o.end.equals("exhausted") ? 1 : 2]++;
            if (why != null) failures.add(String.format(Locale.ROOT, "%s: %s on tick %d at %s, miss %.1f", s, why, o.ticks, o.at, o.miss));
        }
        String summary = "hits " + ends[0] + ", no stock " + ends[1] + ", about the relief " + ends[2] + ", the terrain covered the target on approach - " + occluded;
        System.out.println("AutopilotPropertiesTest: " + SCENARIOS + " scripts, " + summary);
        assertTrue(failures.isEmpty(), failures.size() + " from " + SCENARIOS + " (" + summary + "):\n"
                + String.join("\n", failures.subList(0, Math.min(15, failures.size()))));
        assertTrue(occluded >= MIN_OCCLUDED, "the terrain covered the target on approach only at " + occluded + " scenarios (" + summary + ")");
    }

    /**
     * A thin mast (column 1×1, at 40 above the ground) in the path of the rocket in 600 blocks: a sensor from three points along the course of
     *  does not see the column between the points, and the altitude filter smoothes out the short peak, and with them the rocket at low level hits her.
     * The strip of relief sees it behind {@link WeaponSpec.Airframe#reliefLookahead} blocks, and the rocket manages to gain altitude—
     * , both from low level and while climbing after the accelerator.
     */
    @Test
    void missileClearsMast() {
        double ground = 64;
        WeaponSpec.Airframe air = WeaponSpec.MISSILE.airframe();
        List<String> failures = new ArrayList<>();
        for (double x : new double[] {0.1, 0.5, 0.9}) {
            for (double mastAt : new double[] {250, 600}) {
                for (FlightPhase phase : new FlightPhase[] {FlightPhase.CLIMB, FlightPhase.CRUISE}) {
                    int mz = (int) mastAt;
                    Ground g = (px, pz) -> Mth.floor(px) == 0 && Mth.floor(pz) == mz ? ground + 40 : ground;
                    Vec3 aim = new Vec3(x, ground + 0.5, 1500);
                    MissileAutopilot missile = new MissileAutopilot(air);
                    missile.approach(1500);
                    Vec3 start = phase == FlightPhase.CLIMB ? new Vec3(x, ground + 2, 0) : new Vec3(x, missile.airborneAltitude(aim, ground), 0);
                    String end = straightFlight(g, start, phase, phase == FlightPhase.CLIMB ? 1.5 : air.cruiseSpeed(), air, aim, mz + 10,
                            c -> missile.fly(c, aim, aim, true));
                    if (!end.equals("passed")) failures.add(String.format(Locale.ROOT, "x %.1f, mast in %.0f, start %s: %s", x, mastAt, phase, end));
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /**
     * High house (20×20 to y 135) on the Shahed path in 600 blocks, target on 70 behind it: cruise - target +30, house above it
     *  on ~35, the set is no cooler than 15° - you need ~150 blocks of path, and the three-point sensor sees the house behind 45. The slant strip
     *  of the set sees him in advance, Shahed passes over him and hits.
     */
    @Test
    void droneClearsTallHouse() {
        double ground = 70;
        WeaponSpec.Airframe air = WeaponSpec.DRONE.airframe();
        List<String> failures = new ArrayList<>();
        for (double x : new double[] {-12, 0, 9.5}) {
            Ground g = (px, pz) -> px >= -10 && px < 10 && pz >= 600 && pz < 620 ? 135 : ground;
            Vec3 aim = new Vec3(x, ground + 0.5, 900);
            DroneAutopilot drone = new DroneAutopilot(air);
            Vec3 start = new Vec3(x, 0, 0);
            start = new Vec3(x, drone.airborneAltitude(start, aim, ground), 0);
            String end = straightFlight(g, start, FlightPhase.CRUISE, air.cruiseSpeed(), air, aim, Double.POSITIVE_INFINITY,
                    c -> drone.fly(c, aim, aim, true));
            if (!end.equals("hit")) failures.add(String.format(Locale.ROOT, "x %.1f: %s", x, end));
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /**
     * The flight of the projectile on the course +Z from {@code start} in the phase {@code phase} to {@code aim}: “passed” - passed {@code passZ},
     *  "hit" - reached the target, otherwise where it crashed.
     */
    static String straightFlight(Ground ground, Vec3 start, FlightPhase phase, double speed, WeaponSpec.Airframe air, Vec3 aim,
                                 double passZ, Consumer<Model> fly) {
        Model c = new Model(ground, start, speed);
        c.phase = phase;
        c.altitude.reset(start.y);
        c.flight.set(FlightController.anglesTo(start, new Vec3(aim.x, start.y, aim.z))[0], phase == FlightPhase.CLIMB ? -14 : 0);
        for (c.tick = 0; c.tick < 4000; c.tick++) {
            fly.accept(c);
            if (c.pos.distanceTo(aim) <= c.speed + air.reachPad()) return "hit";
            Vec3 dir = c.flight.forward();
            for (double d = 0; d <= c.speed; d += 0.25) {
                Vec3 p = c.pos.add(dir.scale(d));
                if (p.y < ground.at(p.x, p.z)) {
                    if (p.distanceTo(aim) <= c.speed + air.reachPad()) return "hit";
                    return String.format(Locale.ROOT, "crashed into (%.1f %.1f %.1f) on tick %d, phase %s", p.x, p.y, p.z, c.tick, c.phase);
                }
            }
            c.pos = c.pos.add(dir.scale(c.speed));
            if (c.pos.z > passZ) return "passed";
        }
        return String.format(Locale.ROOT, "did not reach: (%.1f %.1f %.1f)", c.pos.x, c.pos.y, c.pos.z);
    }

    static Outcome fly(Scenario s) {
        SplittableRandom r = new SplittableRandom(s.seed * 31 + 7);
        WeaponSpec.Airframe air = s.weapon.spec.airframe();
        Ground ground = ground(s.relief, r);
        // the target is at the origin, on the surface; projectile - in 250–1500 blocks in any direction
        Vec3 aim = new Vec3(r.nextDouble(-8, 8), 0, r.nextDouble(-8, 8));
        aim = new Vec3(aim.x, ground.at(aim.x, aim.z) + 0.5, aim.z);
        double bearing = r.nextDouble(Math.PI * 2), distance = r.nextDouble(250, 1500);
        Vec3 start = new Vec3(aim.x + Math.sin(bearing) * distance, 0, aim.z + Math.cos(bearing) * distance);
        double surface = ground.at(start.x, start.z);

        DroneAutopilot drone = s.weapon == Weapon.DRONE ? new DroneAutopilot(air) : null;
        MissileAutopilot missile = s.weapon == Weapon.MISSILE ? new MissileAutopilot(air) : null;
        double y = drone != null ? drone.airborneAltitude(start, aim, surface) : missile.airborneAltitude(aim, surface);
        start = new Vec3(start.x, y, start.z);
        Model c = new Model(ground, start, air.cruiseSpeed());
        c.altitude.reset(y);
        if (missile != null) missile.approach(Math.sqrt(aim.subtract(start).horizontalDistanceSqr()));

        Route route = null;
        if (s.routed) {
            // approach from behind: the direction of approach is deviated from straight to 60°, the path is up to one and a half times longer than straight
            Vec3 direct = aim.subtract(start);
            double turn = r.nextDouble(-Math.PI / 3, Math.PI / 3);
            Vec3 approach = new Vec3(direct.x * Math.cos(turn) - direct.z * Math.sin(turn), 0, direct.x * Math.sin(turn) + direct.z * Math.cos(turn));
            route = Route.plan(start, aim, approach, distance * r.nextDouble(1, 1.5), s.weapon.spec.finalLeg(), r.nextBoolean() ? 1 : -1);
        }
        Vec3 first = route != null && route.current() != null ? route.current() : aim;
        c.flight.set(FlightController.anglesTo(start, new Vec3(first.x, start.y, first.z))[0], 0);
        Mission mission = Mission.plan(route != null ? route.remaining(start, aim) : start.distanceTo(aim), air.cruiseSpeed());

        Vec3 walk = Vec3.ZERO;
        if (s.aim == Aim.WALKING) {
            double h = r.nextDouble(Math.PI * 2), v = r.nextDouble(0.05, 0.25);
            walk = new Vec3(Math.sin(h) * v, 0, Math.cos(h) * v);
        }
        boolean retargeted = false, occluded = false;
        double turn = 0, clearance = Double.MAX_VALUE;
        double heading = Double.NaN;
        Vec3 lastAim = aim;
        for (c.tick = 0; c.tick < 20_000; c.tick++) {
            // tracking the target: goes - the reserve grows by its shift
            if (walk != Vec3.ZERO) {
                Vec3 to = aim.add(walk);
                to = new Vec3(to.x, ground.at(to.x, to.z) + 0.5, to.z);
                mission.chase(to.distanceTo(aim));
                aim = to;
            }
            boolean finalLeg = route == null || route.finished();
            if (s.aim == Aim.RETARGET_IN_TURN && !retargeted && finalLeg && Bearing.of(c.pos, aim).horizontal() < 200) {
                // new target to the side, inside the turning circle - like retargeting from the camera or missing in a dive
                retargeted = true;
                double radius = WeaponSpec.turnRadius(c.speed, air.turnRate()) * WeaponSpec.TURN_MARGIN;
                Vec3 f = c.flight.forward();
                Vec3 fw = new Vec3(f.x, 0, f.z).normalize();
                Vec3 side = new Vec3(-fw.z, 0, fw.x).scale(r.nextBoolean() ? 1 : -1);
                Vec3 to = c.pos.add(side.scale(radius * 0.8)).add(fw.scale(radius * 0.3));
                to = new Vec3(to.x, ground.at(to.x, to.z) + 0.5, to.z);
                mission.retarget(to.distanceTo(aim));
                aim = to;
                if (missile != null) missile.retarget(c, aim);
            }
            Vec3 nav = aim;
            if (route != null) {
                route.update(c.pos, s.weapon.capture);
                Vec3 wp = route.current();
                if (wp != null) nav = new Vec3(wp.x, aim.y, wp.z);
            }
            finalLeg = route == null || route.finished();
            if (drone != null) drone.fly(c, aim, nav, finalLeg);
            else missile.fly(c, aim, nav, finalLeg);

            if (c.phase == FlightPhase.TERMINAL || c.phase == FlightPhase.POP_UP) occluded |= blocked(ground, c.pos, aim, 3);

            // flight pitch, like a projectile in the world
            if (finalLeg && c.pos.distanceTo(aim) <= c.speed + air.reachPad()) {
                return new Outcome("hit", c.tick, c.pos.distanceTo(aim), turn, clearance, c.pos, occluded);
            }
            if (mission.exhausted()) return new Outcome("exhausted", c.tick, c.pos.distanceTo(aim), turn, clearance, c.pos, occluded);
            Vec3 dir = c.flight.forward();
            Vec3 next = c.pos.add(dir.scale(c.speed));
            for (double d = 0; d <= c.speed + 1e-9; d += 0.5) {
                Vec3 p = c.pos.add(dir.scale(Math.min(d, c.speed)));
                if (p.y < ground.at(p.x, p.z)) {
                    // hitting the terrain at the target - the same hit (the explosion covers it), in the distance - collision
                    boolean near = p.distanceTo(aim) <= c.speed + air.reachPad();
                    return new Outcome(near ? "hit" : "crash", c.tick, p.distanceTo(aim), turn, clearance, p, occluded);
                }
            }
            Vec3 step = next.subtract(c.pos);
            c.pos = next;
            mission.spend(step.length());
            if (c.phase == FlightPhase.CRUISE) clearance = Math.min(clearance, c.pos.y - ground.at(c.pos.x, c.pos.z));

            // turn at a fixed target point, according to flight scenarios (ScenarioRun.accumulateTurn)
            boolean aimMoved = aim.distanceToSqr(lastAim) > 0.01;
            lastAim = aim;
            double horizontal = step.horizontalDistance();
            if (horizontal < 0.05) continue;
            double h = Math.toDegrees(Math.atan2(step.z, step.x));
            double prev = heading;
            heading = h;
            if (Double.isNaN(prev) || aimMoved) continue;
            double rate = c.phase == FlightPhase.CLIMB ? air.climbTurnRate() : air.turnRate();
            if (c.pos.subtract(aim).horizontalDistance() <= horizontal / Math.toRadians(rate) * 2) {
                turn += Math.abs(Mth.wrapDegrees(h - prev));
            }
        }
        return new Outcome("forever", c.tick, c.pos.distanceTo(aim), turn, clearance, c.pos, occluded);
    }

    /** The terrain is above the line of sight {@code from → to} (except for the last {@code margin} blocks near the target). */
    static boolean blocked(Ground ground, Vec3 from, Vec3 to, double margin) {
        return blockedAt(ground, from, to, margin) != Double.POSITIVE_INFINITY;
    }

    /** Where the straight line {@code from → to} goes under the relief for the first time (except for the last {@code margin} blocks); nowhere - infinity. */
    static double blockedAt(Ground ground, Vec3 from, Vec3 to, double margin) {
        double length = from.distanceTo(to);
        for (double d = 0; d < length - margin; d += 1) {
            Vec3 p = from.lerp(to, d / length);
            if (ground.at(p.x, p.z) > p.y) return d;
        }
        return Double.POSITIVE_INFINITY;
    }

    /** Relief: plain, gentle hills (slope no steeper than ~15°) or target at the bottom of a quarry bowl. */
    private static Ground ground(Relief relief, SplittableRandom r) {
        double base = 64;
        return switch (relief) {
            case FLAT -> (x, z) -> base;
            case HILLS -> {
                double a = r.nextDouble(10, 30), lx = r.nextDouble(120, 200), lz = r.nextDouble(120, 200);
                double px = r.nextDouble(Math.PI * 2), pz = r.nextDouble(Math.PI * 2);
                yield (x, z) -> base + a * Math.sin(x / lx + px) * Math.sin(z / lz + pz);
            }
            case QUARRY -> {
                double depth = r.nextDouble(10, 25), radius = r.nextDouble(50, 90);
                yield (x, z) -> {
                    double q = (x * x + z * z) / (radius * radius);
                    return q < 1 ? base - depth * (1 - q) : base;
                };
            }
        };
    }
}
