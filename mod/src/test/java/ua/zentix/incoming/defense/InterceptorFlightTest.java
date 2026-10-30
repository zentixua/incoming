package ua.zentix.incoming.defense;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.guidance.Ballistics;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
  * Flight of an anti-aircraft missile without a world ({@link Interceptor#steer}, {@link Interceptor#fuze}) on a
  * straight target: every weapon that SAM hits ({@code Signature.intercept}), missile spec {@link InterceptorSpec#SAM}
  * catches up to self-destruction from the edge of the range at which it is visible - flying towards, past and (except
  * for B-2) SAM. The tick order is as in the world: the target has already moved, the rocket turns to its current
  * place, the fuse looks at the segments of both.
 */
class InterceptorFlightTest {
    private static final InterceptorSpec SPEC = InterceptorSpec.SAM;
    /** Radar and SAM fire ranges (spec). */
    private static final double RADAR = SPEC.radarRange(), ENGAGE = SPEC.engageRange();
    /** The middle of the rocket container at the SAM on the block (0, 64, 0) is {@code SamBlockEntity.rail}. */
    private static final Vec3 RAIL = new Vec3(0.5 - 0.33, 64 + 3.25, 0.5 - 0.33);
    private static final double ALTITUDE = 100;

    /** tick, on which the fuse was triggered, or −1 - the fuel ran out earlier. */
    private static int fly(Vec3 start, Vec3 velocity, double halfLength) {
        Interceptor f = new Interceptor(SPEC, UUID.randomUUID(), null, "test", RAIL, Interceptor.railDirection(RAIL, start, SPEC));
        Vec3 t = start;
        for (int tick = 1; tick <= SPEC.fuelTicks(); tick++) {
            Vec3 prev = t;
            t = t.add(velocity);
            Vec3 next = f.steer(t);
            if (f.fuze(next, prev, t, halfLength) >= 0) return tick;
            f.moveTo(next);
        }
        return -1;
    }

    private static List<WeaponType> interceptable() {
        List<WeaponType> out = new ArrayList<>();
        for (WeaponType w : WeaponType.values()) if (w.spec().airframe().radar().intercept()) out.add(w);
        return out;
    }

    /**
     * From what horizontal range does the SAM fire at this weapon: firing range, but no further than it can be seen.
     */
    private static double engageFrom(WeaponSpec.Airframe a) {
        return Math.min(ENGAGE, RADAR * a.radar().visibility());
    }

    @Test
    void catchesApproachingAndCrossingTargets() {
        List<WeaponType> types = interceptable();
        assertTrue(types.size() >= 4, "less than four intercepted: " + types);
        for (WeaponType w : types) {
            WeaponSpec.Airframe a = w.spec().airframe();
            double d = engageFrom(a);
            for (double speed : new double[]{a.cruiseSpeed(), a.diveSpeed()}) {
                for (int bearing = 0; bearing < 360; bearing += 45) {
                    double b = Math.toRadians(bearing);
                    Vec3 start = RAIL.add(Math.sin(b) * d, ALTITUDE, -Math.cos(b) * d);
                    Vec3 toward = RAIL.add(0, ALTITUDE, 0).subtract(start).normalize().scale(speed);
                    // on SAM; past - in 300 blocks on the side
                    Vec3 cross = new Vec3(-toward.z, 0, toward.x);
                    Vec3 aside = start.add(cross.normalize().scale(300));
                    int head = fly(start, toward, a.noseLength());
                    int pass = fly(aside, toward, a.noseLength());
                    String what = w + " " + speed + " bl/tick with " + (int) d + " blocks, pall " + bearing;
                    assertTrue(head > 0, what + ": on SAM - didn’t catch up");
                    assertTrue(pass > 0, what + ": past - didn’t catch up");
                }
            }
        }
    }

    /**
      * The outgoing target (except for B-2: the missile is faster than it by 2 block/tick) is also caught up by the
      * missile from the edge of the firing range.
     */
    @Test
    void catchesRecedingSlowTargets() {
        for (WeaponType w : interceptable()) {
            WeaponSpec.Airframe a = w.spec().airframe();
            if (a.cruiseSpeed() > SPEC.maxSpeed() / 2) continue;
            double d = engageFrom(a);
            Vec3 start = RAIL.add(d, ALTITUDE, 0);
            int tick = fly(start, new Vec3(a.cruiseSpeed(), 0, 0), a.noseLength());
            assertTrue(tick > 0, w + " leaves with " + (int) d + " blocks - didn’t catch up");
        }
    }

    /**
      * Where is Shahed via {@code tick} tick: goes at a height of {@code height} above SAM, in 6 blocks on the side,
      * with {@code z0}, with z 0 - dives into the ground at z 100.
     */
    private static Vec3 overhead(double z0, int tick, double height, double cruise, double dive) {
        double z = z0 + cruise * tick;
        if (z <= 0) return RAIL.add(6, height, z);
        Vec3 start = RAIL.add(6, height, 0), ground = RAIL.add(6, -1, 100);
        double s = dive * (tick + z0 / cruise);
        return s >= start.distanceTo(ground) ? ground : start.add(ground.subtract(start).normalize().scale(s));
    }

    /**
      * Shahed passes over the SAM itself and behind it dives into the target in 100 blocks (GameTest {@code
      * shootsDownHostileDrones}): at whatever tick of inspection of the sky the rocket leaves - from the target 50
      * blocks before the SAM to the target above it - it knocks it down to the ground. With one angular speed of
      * rotation, the rocket that came towards him turned around behind him and followed him to the ground ({@link
      * InterceptorSpec#turnLimit}).
     */
    @Test
    void catchesTargetPassingOverhead() {
        WeaponSpec.Airframe a = WeaponType.DRONE.spec().airframe();
        for (double z0 = -50; z0 <= 0; z0 += 0.5) {
            Interceptor f = new Interceptor(SPEC, UUID.randomUUID(), null, "test", RAIL, Interceptor.railDirection(RAIL, overhead(z0, 0, 45, a.cruiseSpeed(),
                    a.diveSpeed()), SPEC));
            int hit = -1;
            for (int tick = 1; tick <= SPEC.fuelTicks(); tick++) {
                Vec3 prev = overhead(z0, tick - 1, 45, a.cruiseSpeed(), a.diveSpeed()), t = overhead(z0, tick, 45, a.cruiseSpeed(), a.diveSpeed());
                if (t.y <= RAIL.y) break;
                Vec3 next = f.steer(t);
                if (f.fuze(next, prev, t, a.noseLength()) >= 0) {
                    hit = tick;
                    break;
                }
                f.moveTo(next);
            }
            assertTrue(hit > 0, "shahed in " + (int) -z0 + " blocks to SAM: the missile did not hit it to the ground");
        }
    }

    /**
      * From a vertical launch to a target far and low on the horizon, the rocket lies in an arc: each tick turn is no
      * steeper than the lateral acceleration spec at the speed of this tick (immediately after the rail - ~20°/tick),
      * and 10° above the horizon the course passes beyond 5–10 tick (0,25–0,5 s) after it.
     */
    @Test
    void pitchOverIsAnArc() {
        Vec3 target = RAIL.add(1500, 100, 0);
        Interceptor f = new Interceptor(SPEC, UUID.randomUUID(), null, "test", RAIL, Interceptor.railDirection(RAIL, target, SPEC));
        for (int i = 1; i <= SPEC.railTicks(); i++) f.moveTo(f.steer(target));
        int level = -1;
        for (int i = 1; i <= 40 && level < 0; i++) {
            Vec3 dir = f.direction();
            double speed = f.speed();
            f.moveTo(f.steer(target));
            double turn = Math.toDegrees(Math.acos(Math.min(1, dir.dot(f.direction()))));
            double byG = Math.toDegrees(SPEC.lateralG() * Ballistics.GRAVITY / Math.max(speed, SPEC.launchSpeed()));
            assertTrue(turn <= SPEC.turnLimit(speed) + 1e-9, i + "-th tick after launch rail exit: turn " + turn + "°");
            assertTrue(turn <= byG + 1e-9, i + "-th tick after launch rail exit: turn " + turn + "° cooler " + SPEC.lateralG() + " g");
            if (Math.toDegrees(Math.asin(f.direction().y)) <= 10) level = i;
        }
        assertTrue(level >= 5 && level <= 10, "10° above the horizon - at " + level + "-m tick after rail");
    }

    /**
      * Where is Shahed via {@code tick} tick: with {@code z0} it goes on 46 blocks above SAM, in 6 blocks on the side,
      * with z −41 - dive 18° to the target at z 100.
     */
    private static Vec3 diving(double z0, int tick, double cruise, double dive) {
        double z = z0 + cruise * tick;
        if (z <= -41) return RAIL.add(6, 46, z);
        Vec3 start = RAIL.add(6, 46, -41), ground = RAIL.add(6, -1, 100);
        double s = dive * (tick - (-41 - z0) / cruise);
        return s >= start.distanceTo(ground) ? ground : start.add(ground.subtract(start).normalize().scale(s));
    }

    /**
      * Shahed GameTest {@code shootsDownHostileDrones}: he starts diving into the target behind SAM even before it
      * (from 46 blocks under 18°) and passes over SAM, descending. A missile that lands at any tick - from a target in
      * 96 blocks to SAM to a target that is already 15 blocks behind it - knocks it down to the ground: on one rail
      * with reloading, the second missile launch, when the second is already above SAM.
     */
    @Test
    void catchesDivingTargetOverhead() {
        WeaponSpec.Airframe a = WeaponType.DRONE.spec().airframe();
        for (double z0 = -96; z0 <= 15; z0 += 0.5) {
            Interceptor f = new Interceptor(SPEC, UUID.randomUUID(), null, "test", RAIL,
                    Interceptor.railDirection(RAIL, diving(z0, 0, a.cruiseSpeed(), a.diveSpeed()), SPEC));
            int hit = -1;
            for (int tick = 1; tick <= SPEC.fuelTicks(); tick++) {
                Vec3 prev = diving(z0, tick - 1, a.cruiseSpeed(), a.diveSpeed()), t = diving(z0, tick, a.cruiseSpeed(), a.diveSpeed());
                if (t.y <= RAIL.y) break;
                Vec3 next = f.steer(t);
                if (f.fuze(next, prev, t, a.noseLength()) >= 0) {
                    hit = tick;
                    break;
                }
                f.moveTo(next);
            }
            assertTrue(hit > 0, "shahed in " + z0 + " blocks from SAM on course: the missile did not hit it to the ground");
        }
    }

    /** The fuse on the rail is not armed: the target next to the launcher does not explode the rocket at launch. */
    @Test
    void fuzeIsSafeOnTheRail() {
        Interceptor f = new Interceptor(SPEC, UUID.randomUUID(), null, "test", RAIL, new Vec3(0, 1, 0));
        Vec3 t = RAIL.add(1, 1, 0);
        for (int i = 1; i <= SPEC.railTicks(); i++) {
            Vec3 next = f.steer(t);
            assertEquals(-1, f.fuze(next, t, t, 2), 0, "the fuse went off " + i + "-m tick launch");
            f.moveTo(next);
        }
        Vec3 next = f.steer(f.position().add(1, 0, 0));
        assertTrue(f.fuze(next, f.position().add(1, 0, 0), f.position().add(1, 0, 0), 2) >= 0, "after launch the fuse did not arm");
    }

    /**
      * The fuse explodes the rocket at the moment of closest approach: it has entered the radius, but is still getting
      * closer - waits for the next tick and explodes where it is closest; flying further than the radius does not break
      * (there is no dice roll, the target flies further).
     */
    @Test
    void fuzeFiresAtClosestApproachWithinRadius() {
        Interceptor f = new Interceptor(SPEC, UUID.randomUUID(), null, "test", RAIL, new Vec3(1, 0, 0));
        // the fuse is cocked behind the rail
        for (int i = 1; i <= SPEC.railTicks() + 1; i++) f.moveTo(f.steer(RAIL.add(1000, 0, 0)));
        Vec3 p0 = f.position(), step = new Vec3(14, 0, 0);
        Vec3 near = p0.add(16, 3, 0), wide = p0.add(16, SPEC.fuse() + 2, 0);
        // end of tick - 3,6 block from the target, but approach is still in progress: wait
        assertEquals(-1, f.fuze(p0.add(step), near, near, 0), 0);
        assertEquals(-1, f.fuze(p0.add(step), wide, wide, 0), 0);
        f.moveTo(p0.add(step));
        double s = f.fuze(p0.add(step).add(step), near, near, 0);
        assertEquals(2.0 / 14, s, 1e-9, "gap - at the nearest point, 3 blocks from the target");
        assertEquals(-1, f.fuze(p0.add(step).add(step), wide, wide, 0), 0, "flying beyond the fuse radius is not a rupture");
    }

    /**
      * launch: SAM rocket - vertically out of the container, rocket with an inclined rail - upward at its angle towards
      * the target; the first tick the course does not change, during acceleration the speed increases.
     */
    @Test
    void leavesTheRailUpwards() {
        Vec3 target = RAIL.add(-800, 50, 600);
        Vec3 dir = Interceptor.railDirection(RAIL, target, SPEC);
        assertEquals(0, dir.subtract(0, 1, 0).length(), 1e-9, "SAM launches vertically");
        InterceptorSpec inclined = new InterceptorSpec(SPEC.radarRange(), SPEC.engageRange(), SPEC.railTicks(), 65, SPEC.launchSpeed(), SPEC.boostTicks(), SPEC.gameBoostAccel(),
                SPEC.gameMaxSpeed(), SPEC.lateralG(), SPEC.fuelTicks(), SPEC.fuse(), SPEC.noseLength(), SPEC.passport());
        Vec3 slant = Interceptor.railDirection(RAIL, target, inclined);
        assertEquals(65, Math.toDegrees(Math.asin(slant.y)), 1e-9);
        assertEquals(Math.atan2(600, -800), Math.atan2(slant.z, slant.x), 1e-9);
        Interceptor f = new Interceptor(SPEC, UUID.randomUUID(), null, "test", RAIL, dir);
        Vec3 rail = f.direction();
        double speed = f.speed();
        for (int i = 1; i <= SPEC.railTicks(); i++) {
            f.moveTo(f.steer(target));
            assertEquals(rail, f.direction(), "rail course");
            assertTrue(f.speed() > speed);
            speed = f.speed();
        }
        for (int i = SPEC.railTicks() + 1; i <= SPEC.fuelTicks(); i++) f.moveTo(f.steer(target));
        // the motor maintains the highest speed; in the climb to the target above SAM - without exchanging altitude for
        // speed per tick
        assertEquals(SPEC.maxSpeed(), f.speed(), ua.zentix.incoming.strike.WeaponSpec.Handling.TRADE * Ballistics.GRAVITY, "after acceleration - highest speed");
    }
}
