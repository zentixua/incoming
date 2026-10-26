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
 * Shahed pike above the city: a high-rise on the pike line between the cruise and the target (houses with a height of 42–68 above the target
 *  for 54–190 blocks before it). Shahed goes over the city
 *  at the height of the relief +18 and begins a dive only when the straight line to the target is clear or the nose otherwise will not have time to turn
 *  to the target, and flies to it. A house closer than 45 blocks to the target is not always avoided by a dive from a cruise above it (the highest is
 * no: the straight line is cleared steeper than Shahed manages to turn, and it flies past the target); such flights are only in conclusion.
 */
class DroneDiveTest {
    private static final double GROUND = 64;
    /** The dive does not go around the house closer to the target (see class description). */
    private static final double NEAREST = 45;

    @Test
    void divesPastTowerOnDiveLine() {
        List<String> misses = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (double height : new double[] {20, 30, 40, 50, 60, 70}) {
            for (double before : new double[] {20, 30, 45, 54, 70, 90, 130, 190}) {
                String end = strike(height, before);
                all.append(String.format(Locale.ROOT, "%n house %.0f to %.0f to destination: %s", height, before, end));
                if (before >= NEAREST && !end.startsWith("hit")) {
                    misses.add(String.format(Locale.ROOT, "house with height %.0f in %.0f blocks to goal: %s", height, before, end));
                }
            }
        }
        System.out.println("DroneDiveTest:" + all);
        assertTrue(misses.isEmpty(), String.join("\n", misses));
    }

    /**
     * Shahed waits for a free straight line above the house at the target and heads for the mast above the cruise: while the straight line is closed, he
     *  checks his path ahead and gains altitude above the mast in advance, and then dives towards the target.
     */
    @Test
    void climbsOverMastWhileWaitingForLine() {
        List<String> misses = new ArrayList<>();
        for (double above : new double[] {3, 6, 10}) {
            for (double before : new double[] {70, 100, 130}) {
                double cruise = cruiseOver(GROUND + 30);
                AutopilotPropertiesTest.Ground ground = (x, z) -> {
                    if (Math.abs(x) <= 20 && z <= -30 && z >= -60) return GROUND + 30;
                    if (Math.abs(x) <= 1.5 && z <= -before && z >= -before - 3) return cruise + above;
                    return GROUND;
                };
                String end = strike(ground, GROUND + 30);
                if (!end.startsWith("hit")) {
                    misses.add(String.format(Locale.ROOT, "mast %.0f above the cruise in %.0f blocks to the target: %s", above, before, end));
                }
            }
        }
        assertTrue(misses.isEmpty(), String.join("\n", misses));
    }

    /** Shahed from 800 blocks above the city with height {@code height} to a target behind the house with width 40 and thickness 30; what happened. */
    private static String strike(double height, double before) {
        return strike((x, z) -> Math.abs(x) <= 20 && z <= -before && z >= -before - 30 ? GROUND + height : GROUND, GROUND + height);
    }

    /** The height of the cruise Shahed, launching above the city at height {@code city}. */
    private static double cruiseOver(double city) {
        DroneAutopilot drone = new DroneAutopilot(WeaponSpec.DRONE.airframe());
        return drone.airborneAltitude(new Vec3(0, 0, -800), new Vec3(0, GROUND + 0.5, 0), city);
    }

    /** Shahed from 800 blocks above the city with the top of {@code city} on the target at the origin along the terrain {@code ground}; what happened. */
    private static String strike(AutopilotPropertiesTest.Ground ground, double city) {
        // cruise over dense buildings: start at the height of the city + 20, as above the city
        WeaponSpec.Airframe air = WeaponSpec.DRONE.airframe();
        DroneAutopilot drone = new DroneAutopilot(air);
        Vec3 aim = new Vec3(0, GROUND + 0.5, 0);
        Vec3 start = new Vec3(0, 0, -800);
        start = new Vec3(start.x, drone.airborneAltitude(start, aim, city), start.z);
        AutopilotPropertiesTest.Model c = new AutopilotPropertiesTest.Model(ground, start, air.cruiseSpeed());
        c.altitude.reset(start.y);
        c.flight.set(FlightController.anglesTo(start, new Vec3(aim.x, start.y, aim.z))[0], 0);
        double maxPitch = 0;
        for (c.tick = 0; c.tick < 2000; c.tick++) {
            drone.fly(c, aim, aim, true);
            if (c.phase == FlightPhase.TERMINAL) maxPitch = Math.max(maxPitch, c.flight.pitch());
            if (c.pos.distanceTo(aim) <= c.speed + air.reachPad()) return String.format(Locale.ROOT, "hit, pitch up to %.0f°", maxPitch);
            Vec3 dir = c.flight.forward();
            for (double d = 0; d <= c.speed; d += 0.5) {
                Vec3 p = c.pos.add(dir.scale(d));
                if (p.y < ground.at(p.x, p.z)) {
                    return p.distanceTo(aim) <= c.speed + air.reachPad() ? "hit on the ground at the target"
                            : String.format(Locale.ROOT, "crashed into %s, %.0f blocks from the target", fmt(p), p.distanceTo(aim));
                }
            }
            c.pos = c.pos.add(dir.scale(c.speed));
        }
        return "didn't arrive: " + fmt(c.pos);
    }

    private static String fmt(Vec3 p) {
        return String.format(Locale.ROOT, "(%.0f %.0f %.0f)", p.x, p.y, p.z);
    }
}
