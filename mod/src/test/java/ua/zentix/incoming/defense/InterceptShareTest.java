package ua.zentix.incoming.defense;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.LoiterEntity;
import ua.zentix.incoming.guidance.AltitudeHold;
import ua.zentix.incoming.guidance.Craft;
import ua.zentix.incoming.guidance.DroneAutopilot;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.guidance.MissileAutopilot;
import ua.zentix.incoming.guidance.Orbit;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The share of SAM defeats by target class: running many raids on models without Minecraft, the numbers are as they came out, without adjustment
 *  (printed; they are also in the {@code docs/guide/defense.md} manual). SAM missile - the same code as in the world
 *  ({@link Interceptor#steer}, {@link Interceptor#fuze}), range - spec {@link InterceptorSpec#SAM}, SAM launches
 *  at the target, like {@link SamBlockEntity}: while scanning the sky once in {@link SamBlockEntity#SWEEP} tick, when the target closer
 *  fire range and it is visible, one missile on the target, the next one - when the last one ended without defeat. The ground is flat
 *  (the radar does not cover the terrain here), there are as many guides as you like.
 * <p>
 * Targets: Shahed and the cruise missile are their real autopilots ({@link DroneAutopilot}, {@link MissileAutopilot}) with a dive
 *  and slide; “Lancet” - approach and circle over the target ({@link Orbit}, radius {@link LoiterEntity#LOITER_RADIUS}), dive at
 *  dive speed, when the target under the wing is steeper than 40° after the circle; B-2 - in a straight line at your altitude through the release point.
 * Target - a point on the ground in 0–600 blocks from SAM, approach - from any side from beyond the edge of the radar range.
 */
class InterceptShareTest {
    private static final InterceptorSpec SPEC = InterceptorSpec.SAM;
    private static final int RAIDS = 400;
    private static final double GROUND = 64;
    /** The middle of the rocket container at the SAM on the block (0, 64, 0) is {@code SamBlockEntity.rail}. */
    private static final Vec3 RAIL = new Vec3(0.5 - 0.33, GROUND + 3.25, 0.5 - 0.33);
    private static final Vec3 ANTENNA = new Vec3(0.5, GROUND + 1.5, 0.5);

    /** Result raids of the same class. */
    record Share(int raids, int downed, int missiles, int hits) {
        double downedShare() {
            return (double) downed / raids;
        }

        double hitShare() {
            return missiles == 0 ? 0 : (double) hits / missiles;
        }
    }

    @Test
    void sharesByTargetClass() {
        Map<WeaponType, Share> shares = new EnumMap<>(WeaponType.class);
        for (WeaponType w : WeaponType.values()) {
            WeaponSpec.Airframe a = w.spec().airframe();
            if (!a.radar().intercept()) continue;
            int downed = 0, missiles = 0, hits = 0;
            for (int i = 0; i < RAIDS; i++) {
                int[] r = raid(w, new SplittableRandom(w.ordinal() * 100_003L + i));
                downed += r[0];
                missiles += r[1];
                hits += r[2];
            }
            shares.put(w, new Share(RAIDS, downed, missiles, hits));
        }
        List<String> lines = new ArrayList<>();
        shares.forEach((w, s) -> lines.add(String.format(Locale.ROOT, "%-8s shot down %5.1f %% raids, defeats %5.1f %% missiles (%d missiles on %d raids)",
                w, 100 * s.downedShare(), 100 * s.hitShare(), s.missiles(), s.raids())));
        System.out.println("InterceptShareTest:\n" + String.join("\n", lines));
        assertEquals(List.of(WeaponType.DRONE, WeaponType.MISSILE, WeaponType.BUNKER, WeaponType.LOITER), List.copyOf(shares.keySet()),
                "sAM target classes");
        // shares are not a fitting, but a check that the model is not broken: every SAM class at least sometimes fails
        shares.forEach((w, s) -> assertTrue(s.downed() > 0, w + ": not a single one shot down"));
    }

    /** One raid: {shot down (0/1), missiles launched, of those hit}. */
    private static int[] raid(WeaponType w, SplittableRandom r) {
        WeaponSpec.Airframe a = w.spec().airframe();
        double aimFrom = Math.sqrt(r.nextDouble()) * 600, aimBearing = r.nextDouble(Math.PI * 2);
        Vec3 aim = new Vec3(Math.sin(aimBearing) * aimFrom, GROUND + 0.5, Math.cos(aimBearing) * aimFrom);
        double from = SPEC.radarRange() + 200, bearing = r.nextDouble(Math.PI * 2);
        Vec3 start = new Vec3(aim.x + Math.sin(bearing) * from, 0, aim.z + Math.cos(bearing) * from);
        Target t = new Target(w, a, aim, start, r);
        int sweep = r.nextInt(SamBlockEntity.SWEEP);
        Interceptor f = null;
        int missiles = 0;
        for (int tick = 0; tick < 20_000; tick++) {
            Vec3 prev = t.pos;
            if (!t.step()) return new int[] {0, missiles, 0};
            Vec3 now = t.pos;
            if (f == null && (tick + sweep) % SamBlockEntity.SWEEP == 0 && seen(a, now)) {
                f = new Interceptor(SPEC, UUID.randomUUID(), null, "тест", RAIL, Interceptor.railDirection(RAIL, now, SPEC));
                missiles++;
            }
            if (f == null) continue;
            if (f.age() >= SPEC.fuelTicks()) {
                f = null;
                continue;
            }
            Vec3 next = f.steer(now);
            if (f.fuze(next, prev, now, a.noseLength()) >= 0) return new int[] {1, missiles, 1};
            if (next.y < GROUND) f = null;
            else f.moveTo(next);
        }
        throw new AssertionError(w + ": the raid is not over");
    }

    /** SAM fires at the target: closer to the firing range and closer than it can be seen ({@link Radar#scan}). */
    private static boolean seen(WeaponSpec.Airframe a, Vec3 p) {
        double d = p.distanceTo(ANTENNA);
        return d <= SPEC.radarRange() * a.radar().visibility() && d <= SPEC.engageRange();
    }

    /** Target over level ground. */
    private static final class Target implements Craft {
        final WeaponType weapon;
        final WeaponSpec.Airframe air;
        final Vec3 aim;
        final FlightController flight = new FlightController(0, 0);
        final AltitudeHold altitude = new AltitudeHold();
        final DroneAutopilot drone;
        final MissileAutopilot missile;
        final Orbit orbit;
        Vec3 pos;
        double speed;
        FlightPhase phase = FlightPhase.CRUISE;
        int tick, phaseStart, laps;

        Target(WeaponType weapon, WeaponSpec.Airframe air, Vec3 aim, Vec3 start, SplittableRandom r) {
            this.weapon = weapon;
            this.air = air;
            this.aim = aim;
            drone = weapon == WeaponType.DRONE ? new DroneAutopilot(air) : null;
            missile = weapon == WeaponType.MISSILE ? new MissileAutopilot(air) : null;
            orbit = weapon == WeaponType.LOITER ? new Orbit(LoiterEntity.LOITER_RADIUS, r.nextBoolean() ? 1 : -1) : null;
            double y = drone != null ? drone.airborneAltitude(start, aim, GROUND) : missile != null ? missile.airborneAltitude(aim, GROUND)
                    : GROUND + air.cruiseHeight();
            pos = new Vec3(start.x, y, start.z);
            speed = air.cruiseSpeed();
            altitude.reset(y);
            if (missile != null) missile.approach(Math.sqrt(aim.subtract(pos).horizontalDistanceSqr()));
            // B-2 goes above the release point and further in a straight line
            flight.set(FlightController.anglesTo(pos, new Vec3(aim.x, y, aim.z))[0], 0);
        }

        /**
         * Step tick; false - the target has reached (hit) or B-2 has gone beyond the radar range. The B-2 drops the bomb long before the
         *  range from which the radar sees it (hardly noticeable), so to shoot it down means to shoot down the B-2 itself, and not to disrupt the drop.
         */
        boolean step() {
            tick++;
            if (drone != null) drone.fly(this, aim, aim, true);
            else if (missile != null) missile.fly(this, aim, aim, true);
            else if (orbit != null) loiter();
            Vec3 next = pos.add(flight.forward().scale(speed));
            if (weapon == WeaponType.BUNKER) {
                Vec3 to = aim.subtract(pos), dir = flight.forward();
                if (to.x * dir.x + to.z * dir.z < 0 && next.distanceTo(ANTENNA) > SPEC.radarRange()) return false;
            } else if (next.distanceTo(aim) <= speed + air.reachPad() || next.y < GROUND) {
                return false;
            }
            pos = next;
            return true;
        }

        /** “Lancet”: to the circle and circle at the height of the circle, after the circle - dive, when the target under the wing is steeper than 40°. */
        private void loiter() {
            // as LoiterEntity: engine and altitude - according to spec (Handling), to the circle and on the circle - course field, dive - Dive
            WeaponSpec.Handling h = air.handling();
            if (phase == FlightPhase.TERMINAL) {
                throttle(air, air.diveSpeed());
                ua.zentix.incoming.guidance.Dive.steer(flight, pos, speed, aim, Vec3.ZERO, h.dive());
                return;
            }
            throttle(air, air.cruiseSpeed());
            holdAltitude(aim.y + air.cruiseHeight(), 0.12, h, h.cruise());
            orbit.steer(flight, pos, aim, Vec3.ZERO, speed);
            if (phase == FlightPhase.CRUISE && orbit.captured(pos, aim, Vec3.ZERO, speed, flight.yaw())) setPhase(FlightPhase.LOITER);
            double lap = 2 * Math.PI * LoiterEntity.LOITER_RADIUS / speed;
            if (phase == FlightPhase.LOITER && phaseAge() >= lap && FlightController.anglesTo(pos, aim)[1] >= 40) setPhase(FlightPhase.TERMINAL);
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

        @Override
        public double relief(int x, int z) {
            return GROUND;
        }

        @Override
        public double clearAlong(Vec3 to, double margin) {
            return Double.POSITIVE_INFINITY;
        }
    }
}
