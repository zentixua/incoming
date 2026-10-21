package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ракета на цель за домом (шириной 40, толщиной 30, на 20–70 выше цели, в 20–190 блоках до неё) с бреющего полёта —
 * с горкой (заход издалека) и без (заход ближе {@link WeaponSpec.Attack#popUpMinRange}): прямая до цели закрыта, ракета
 * в пике не идёт, а набирает высоту горкой, пока прямая не откроется ({@link Autopilot.Sightline}), и попадает.
 * Короткий заход начинается над крышами: ракета, перенацеленная над городом, идёт над ним на высоте бреющего полёта.
 * Дом, прямая через верх которого идёт к цели круче {@link #STEEPEST}° (выше цели на 40 в 20 блоках от неё, на 70 в 30),
 * ракета не обходит: пике за ним не успевает довернуть, ракета проскакивает цель, заходит снова и задевает дом;
 * такие полёты только в выводе.
 */
class MissileSightlineTest {
    private static final double GROUND = 64;
    /** Круче этого угла прямой через верх дома к цели ракета дом не обходит (см. описание класса), °. */
    private static final double STEEPEST = 60;

    @Test
    void climbsUntilTargetIsInSight() {
        List<String> misses = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (double start : new double[] {800, 170}) {
            for (double height : new double[] {20, 30, 40, 50, 60, 70}) {
                for (double before : new double[] {20, 30, 45, 70, 100, 130, 190}) {
                    if (before + 30 >= start - 40) continue;
                    String end = strike(start, height, before);
                    all.append(String.format(Locale.ROOT, "%n  с %.0f, дом %.0f в %.0f до цели: %s", start, height, before, end));
                    if (!end.startsWith("hit") && Math.toDegrees(Math.atan2(height, before)) <= STEEPEST) {
                        misses.add(String.format(Locale.ROOT, "заход с %.0f, дом высотой %.0f в %.0f блоках до цели: %s", start, height, before, end));
                    }
                }
            }
        }
        System.out.println("MissileSightlineTest:" + all);
        assertTrue(misses.isEmpty(), String.join("\n", misses));
    }

    /** Ракета с {@code start} блоков на бреющем полёте на цель в начале координат за домом; что вышло. */
    private static String strike(double start, double height, double before) {
        AutopilotPropertiesTest.Ground ground = (x, z) -> Math.abs(x) <= 20 && z <= -before && z >= -before - 30 ? GROUND + height : GROUND;
        WeaponSpec.Airframe air = WeaponSpec.MISSILE.airframe();
        MissileAutopilot missile = new MissileAutopilot(air);
        Vec3 aim = new Vec3(0, GROUND + 0.5, 0);
        boolean popUp = start >= air.attack().popUpMinRange();
        Vec3 from = new Vec3(0, missile.airborneAltitude(aim, popUp ? GROUND : GROUND + height), -start);
        missile.approach(start);
        AutopilotPropertiesTest.Model c = new AutopilotPropertiesTest.Model(ground, from, air.cruiseSpeed());
        c.altitude.reset(from.y);
        c.flight.set(FlightController.anglesTo(from, new Vec3(aim.x, from.y, aim.z))[0], 0);
        double maxPitch = 0;
        for (c.tick = 0; c.tick < 2000; c.tick++) {
            missile.fly(c, aim, aim, true);
            if (c.phase == FlightPhase.TERMINAL) maxPitch = Math.max(maxPitch, c.flight.pitch());
            if (c.pos.distanceTo(aim) <= c.speed + air.reachPad()) return String.format(Locale.ROOT, "hit, тангаж до %.0f°", maxPitch);
            Vec3 dir = c.flight.forward();
            for (double d = 0; d <= c.speed; d += 0.5) {
                Vec3 p = c.pos.add(dir.scale(d));
                if (p.y < ground.at(p.x, p.z)) {
                    return p.distanceTo(aim) <= c.speed + air.reachPad() ? "hit о землю у цели"
                            : String.format(Locale.ROOT, "врезался в (%.0f %.0f %.0f), в %.0f блоках от цели", p.x, p.y, p.z, p.distanceTo(aim));
                }
            }
            c.pos = c.pos.add(dir.scale(c.speed));
        }
        return String.format(Locale.ROOT, "не долетел: (%.0f %.0f %.0f)", c.pos.x, c.pos.y, c.pos.z);
    }
}
