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
 * Доля поражений ЗРК по классам целей: прогон многих налётов на модели без Minecraft, числа — как вышли, без подгонки
 * (печатаются; они же — в руководстве {@code docs/guide/defense.md}). Ракета ЗРК — тот же код, что в мире
 * ({@link Interceptor#steer}, {@link Interceptor#fuze}), дальности — паспорт {@link InterceptorSpec#SAM}, ЗРК пускает
 * по цели, как {@link SamBlockEntity}: на осмотре неба раз в {@link SamBlockEntity#SWEEP} тиков, когда цель ближе
 * дальности огня и её видно, одна ракета на цель, следующая — когда прошлая кончилась без поражения. Земля — ровная
 * (рельеф радар здесь не закрывает), направляющих сколько угодно.
 * <p>
 * Цели: шахед и крылатая ракета — их настоящие автопилоты ({@link DroneAutopilot}, {@link MissileAutopilot}) с пике
 * и горкой; «Ланцет» — подлёт и круг над целью ({@link Orbit}, радиус {@link LoiterEntity#LOITER_RADIUS}), пике на
 * скорости пике, когда цель под крылом круче 40° после круга; B-2 — по прямой на своей высоте через точку сброса.
 * Цель — точка на земле в 0–600 блоках от ЗРК, заход — с любой стороны из-за края дальности радара.
 */
class InterceptShareTest {
    private static final InterceptorSpec SPEC = InterceptorSpec.SAM;
    private static final int RAIDS = 400;
    private static final double GROUND = 64;
    /** Середина контейнера ракеты у ЗРК на блоке (0, 64, 0) — {@code SamBlockEntity.rail}. */
    private static final Vec3 RAIL = new Vec3(0.5 - 0.33, GROUND + 3.25, 0.5 - 0.33);
    private static final Vec3 ANTENNA = new Vec3(0.5, GROUND + 1.5, 0.5);

    /** Итог налётов одного класса. */
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
        shares.forEach((w, s) -> lines.add(String.format(Locale.ROOT, "%-8s сбито %5.1f %% налётов, поражений %5.1f %% ракет (%d ракет на %d налётов)",
                w, 100 * s.downedShare(), 100 * s.hitShare(), s.missiles(), s.raids())));
        System.out.println("InterceptShareTest:\n" + String.join("\n", lines));
        assertEquals(List.of(WeaponType.DRONE, WeaponType.MISSILE, WeaponType.BUNKER, WeaponType.LOITER), List.copyOf(shares.keySet()),
                "классы целей ЗРК");
        // доли — не подгонка, а проверка, что модель не сломана: каждый класс ЗРК хоть иногда сбивает
        shares.forEach((w, s) -> assertTrue(s.downed() > 0, w + ": ни одного сбитого"));
    }

    /** Один налёт: {сбит (0/1), ракет пущено, из них поразили}. */
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
        throw new AssertionError(w + ": налёт не кончился");
    }

    /** ЗРК пускает по цели: ближе дальности огня и ближе, чем её видно ({@link Radar#scan}). */
    private static boolean seen(WeaponSpec.Airframe a, Vec3 p) {
        double d = p.distanceTo(ANTENNA);
        return d <= SPEC.radarRange() * a.radar().visibility() && d <= SPEC.engageRange();
    }

    /** Цель над ровной землёй. */
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
            // B-2 идёт над точкой сброса и дальше по прямой
            flight.set(FlightController.anglesTo(pos, new Vec3(aim.x, y, aim.z))[0], 0);
        }

        /**
         * Шаг тика; false — цель дошла (попала) или B-2 ушёл за дальность радара. Бомбу B-2 сбрасывает задолго до
         * дальности, с которой его видит радар (малозаметен), поэтому сбить его — сбить сам B-2, а не сорвать сброс.
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

        /** «Ланцет»: к кругу и круг на высоте круга, после круга — пике, когда цель под крылом круче 40°. */
        private void loiter() {
            if (phase == FlightPhase.TERMINAL) {
                speed += (air.diveSpeed() - speed) * 0.1;
                float[] to = FlightController.anglesTo(pos, aim);
                flight.steerYaw(to[0], 0.3, air.turnRate() * 2, 1);
                flight.holdPitch(to[1], 0.3, air.turnRate() * 2, 1);
                return;
            }
            holdAltitude(aim.y + air.cruiseHeight(), 0.12, 1.2, 0.15);
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
