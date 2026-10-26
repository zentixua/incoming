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
 * A missile to a target behind the house (width 40, thickness 30, 20–70 above the target, 20–190 blocks before it) from a low level flight—
 *  with a hill (approach from afar) and without (approach is closer than {@link WeaponSpec.Attack#popUpMinRange}): the straight line to the target is closed, the rocket
 *  does not go into a dive, but gains altitude in a slide until the straight line opens ({@link Autopilot.Sightline}), and hits.
 *  The short approach begins over the roofs: the rocket, retargeted over the city, goes over it at low altitude flight.
 * House, the straight line through the top of which goes to the target steeper than {@link #STEEPEST}° (above the target at 40 in 20 blocks from it, at 70 in 30),
 *  the missile does not go around: the dive cannot keep up with it turn it further, the missile misses the target, enters again and hits the house;
 *  such flights are only in conclusion.
 */
class MissileSightlineTest {
    private static final double GROUND = 64;
    /** The rocket does not bypass the house steeper than this angle through the top of the house to the target (see class description), °. */
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
                    all.append(String.format(Locale.ROOT, "%n from %.0f, house %.0f to %.0f to destination: %s", start, height, before, end));
                    if (!end.startsWith("hit") && Math.toDegrees(Math.atan2(height, before)) <= STEEPEST) {
                        misses.add(String.format(Locale.ROOT, "approach from %.0f, house height %.0f in %.0f blocks to target: %s", start, height, before, end));
                    }
                }
            }
        }
        System.out.println("MissileSightlineTest:" + all);
        assertTrue(misses.isEmpty(), String.join("\n", misses));
    }

    /**
     * Target under the canopy (16×16 on 5 blocks above it): the straight line will never open. The rocket does not circle over the target, gaining
     *  height, but attacks when the nose otherwise does not have time, and hits the canopy above the target - with or without a slide, on the plain and at the bottom of a
     *  quarry with a depth of 30 (the rocket goes over the edge, and the slide raises it highest above the target).
     */
    @Test
    void strikesRoofWhenTargetNeverInSight() {
        List<String> misses = new ArrayList<>();
        for (double pit : new double[] {0, 30}) {
            for (double start : new double[] {800, 170}) {
                AutopilotPropertiesTest.Ground ground = (x, z) -> {
                    if (Math.abs(x) <= 8 && Math.abs(z) <= 8) return GROUND + 6;
                    double q = (x * x + z * z) / (90.0 * 90.0);
                    return q < 1 ? GROUND : GROUND + pit;
                };
                String end = strike(start, ground, pit + 6);
                System.out.println("MissileSightlineTest, canopy, quarry " + pit + ", entry from " + start + ": " + end);
                if (!end.startsWith("hit") && !end.contains("into the canopy")) {
                    misses.add(String.format(Locale.ROOT, "quarry %.0f, entry from %.0f: %s", pit, start, end));
                }
            }
        }
        assertTrue(misses.isEmpty(), String.join("\n", misses));
    }

    /** A missile with {@code start} blocks at low level to the target at the origin of coordinates behind the house; what happened. */
    private static String strike(double start, double height, double before) {
        return strike(start, (x, z) -> Math.abs(x) <= 20 && z <= -before && z >= -before - 30 ? GROUND + height : GROUND, height);
    }

    /** A missile with {@code start} blocks on a low-level flight (short approach - over roofs of height {@code roofs}) to the target at the origin. */
    private static String strike(double start, AutopilotPropertiesTest.Ground ground, double roofs) {
        WeaponSpec.Airframe air = WeaponSpec.MISSILE.airframe();
        MissileAutopilot missile = new MissileAutopilot(air);
        Vec3 aim = new Vec3(0, GROUND + 0.5, 0);
        boolean popUp = start >= air.attack().popUpMinRange();
        Vec3 from = new Vec3(0, missile.airborneAltitude(aim, popUp ? ground.at(0, -start) : Math.max(ground.at(0, -start), GROUND + roofs)), -start);
        missile.approach(start);
        AutopilotPropertiesTest.Model c = new AutopilotPropertiesTest.Model(ground, from, air.cruiseSpeed());
        c.altitude.reset(from.y);
        c.flight.set(FlightController.anglesTo(from, new Vec3(aim.x, from.y, aim.z))[0], 0);
        double maxPitch = 0;
        for (c.tick = 0; c.tick < 2000; c.tick++) {
            missile.fly(c, aim, aim, true);
            if (c.phase == FlightPhase.TERMINAL) maxPitch = Math.max(maxPitch, c.flight.pitch());
            if (c.pos.distanceTo(aim) <= c.speed + air.reachPad()) return String.format(Locale.ROOT, "hit, pitch up to %.0f°", maxPitch);
            Vec3 dir = c.flight.forward();
            for (double d = 0; d <= c.speed; d += 0.5) {
                Vec3 p = c.pos.add(dir.scale(d));
                if (p.y < ground.at(p.x, p.z)) {
                    if (p.distanceTo(aim) <= c.speed + air.reachPad()) return "hit on the ground at the target";
                    if (Math.abs(p.x) <= 8 && Math.abs(p.z) <= 8 && p.y >= GROUND + 5) return String.format(Locale.ROOT, "in the canopy above the target, %.0f blocks from it", p.distanceTo(aim));
                    return String.format(Locale.ROOT, "crashed into (%.0f %.0f %.0f), %.0f blocks from the target", p.x, p.y, p.z, p.distanceTo(aim));
                }
            }
            c.pos = c.pos.add(dir.scale(c.speed));
        }
        return String.format(Locale.ROOT, "did not reach: (%.0f %.0f %.0f)", c.pos.x, c.pos.y, c.pos.z);
    }
}
