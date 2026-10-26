package ua.zentix.incoming.gametest.scenario;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Flight scenario: how the projectile was launched × what the target was × what happened to it in flight × seed. Everything that the script selects
 *  randomly (approach direction, range, route length, instant and magnitude of disturbance, hill) is from the grain
 * ({@link #params}), so the same script flies the same way in any run: this is the reference of trajectories.
 */
public record Scenario(Launch launch, TargetKind target, Disturbance disturbance, int seed) {
    /** How the projectile was launched. {@code rail} - with a launcher in the world (at the shooter), otherwise approach from afar, outside the world. */
    public enum Launch {
        DRONE_FAR(WeaponType.DRONE, false),
        DRONE_RAIL(WeaponType.DRONE, true),
        MISSILE_FAR(WeaponType.MISSILE, false),
        MISSILE_RAIL(WeaponType.MISSILE, true),
        LOITER_FAR(WeaponType.LOITER, false),
        LOITER_RAIL(WeaponType.LOITER, true),
        ROCKET_FAR(WeaponType.ROCKET, false),
        ROCKET_TUBE(WeaponType.ROCKET, true),
        BOMBER(WeaponType.BUNKER, false),
        ICBM(WeaponType.NUKE, true);

        public final WeaponType weapon;
        public final boolean rail;

        Launch(WeaponType weapon, boolean rail) {
            this.weapon = weapon;
            this.rail = rail;
        }

        /** Guided: Holds the target, tracks it, and accepts retargeting. */
        boolean guided() {
            return weapon == WeaponType.DRONE || weapon == WeaponType.MISSILE || weapon == WeaponType.LOITER;
        }
    }

    /** Goal. */
    public enum TargetKind {
        /** A point on the ground. */
        POINT,
        /** A mob without AI, which the script guides along a rail back and forth in steps. */
        MOB,
        /**
         * Player in flight: a stub with UUID and a position (stance), which the script moves in a circle, like a player on elytra.
         *  Does not give a real player GameTest: the mock player breaks on the packages Create (CLAUDE.md).
         */
        PLAYER,
        /** craft Sable: assembled from planks above the target (only when Sable is in the assembly). */
        CRAFT;

        boolean entity() {
            return this == MOB || this == PLAYER;
        }
    }

    /** What happened to the target in flight (or what is standing in the way). */
    public enum Disturbance {
        NONE,
        /** The target disappeared (died, left the game). */
        TARGET_GONE,
        /** The target teleported 300 blocks. */
        TELEPORT_NEAR,
        /** The target teleported 3000 blocks. */
        TELEPORT_FAR,
        /** The operator redirected the projectile from the camera to a point hundreds of blocks away. */
        RETARGET,
        /** The hill is on course for the last hundred blocks (in a world where the projectile follows the terrain). */
        HILL,
        /** The target is at the bottom of the pit: the terrain around it is higher than it. */
        QUARRY,
        /**
         * The approaching operator redirected the projectile to a point to the side of him, inside the turning circle: he couldn’t reach it directly,
         *  the projectile had to leave and come in again, and not circle around it.
         */
        BESIDE,
        /**
         * The operator plotted a route on the map ({@link Waypoints}): the projectile passes through its points in order, rather than bypassing the
         *  loop, and hits the target.
         */
        WAYPOINTS;

        /** Disturbance in flight (and not the relief constructed in advance). */
        boolean inFlight() {
            return this == TARGET_GONE || this == TELEPORT_NEAR || this == TELEPORT_FAR || this == RETARGET || this == BESIDE;
        }
    }

    /** Test name: "weapon.target.perturbation.seed". */
    public String id() {
        return (launch + "." + target + "." + disturbance + "." + seed).toLowerCase(Locale.ROOT);
    }

    /**
     * Scenario in the trajectories reference: seed 0 (set of a normal run) and not craft - the Sable mod does not control the physics,
     *  and it decides where the craft will be for approach.
     */
    boolean baselined() {
        return seed == 0 && target != TargetKind.CRAFT && knownIssues().isEmpty();
    }

    /** A flight property that a known flaw in the mod can disrupt ({@link #knownIssues}). */
    enum Property {
        /** There is no spinning around a stationary target. */
        TURN,
        /** A stationary target is hit. */
        HIT
    }

    /**
     * Known flaws of the mod that open PR fixes: the broken script property is written to the log, and does not fail the test.
     *  Only this is forgiven - the flight period, ticket, synchronous loading remain mandatory; this scenario is not in the reference
     *  (its flight will change with the correction). Here - with a link to PR, which is repairing; after merging it, remove it and write the
     *  reference again. Now there are none.
     */
    Set<Property> knownIssues() {
        return Set.of();
    }

    /**
     * Place of the scenario in full space: its own site in the world ({@link ScenarioSite}) and seed parameters do not depend on the run set. Disturbance is the most significant digit: the new one (to the end of the enumeration) does not shift the previous scenarios, and the reference
     *  remains true; a new weapon or type of target is shifted - the reference is recorded again.
     */
    int cell() {
        int combo = (disturbance.ordinal() * Launch.values().length + launch.ordinal()) * TargetKind.values().length + target.ordinal();
        return combo * MAX_SEEDS + seed;
    }

    /** There are no more grains per combination: scenario sites are counted from this number. */
    static final int MAX_SEEDS = 16;

    /**
     * The combination makes sense. Unguided (MLRS, B-2) hit the point and do not track the target; ICBM - start only
     *  (undermines the planned strike, not her); craft - only when Sable is in the assembly.
     */
    static boolean valid(Launch launch, TargetKind target, Disturbance d, boolean sable) {
        if (target == TargetKind.CRAFT && !sable) return false;
        if (launch == Launch.ICBM) return target == TargetKind.POINT && d == Disturbance.NONE;
        if (launch.weapon == WeaponType.ROCKET) {
            return target == TargetKind.POINT && (d == Disturbance.NONE || d == Disturbance.HILL || d == Disturbance.QUARRY)
                    || target == TargetKind.MOB && d == Disturbance.NONE;
        }
        if (launch == Launch.BOMBER) {
            return target == TargetKind.POINT && (d == Disturbance.NONE || d == Disturbance.RETARGET || d == Disturbance.HILL || d == Disturbance.QUARRY);
        }
        return switch (d) {
            case NONE, RETARGET -> true;
            case TARGET_GONE, TELEPORT_NEAR, TELEPORT_FAR -> target.entity();
            case HILL, QUARRY -> target == TargetKind.POINT || target == TargetKind.MOB;
            case BESIDE, WAYPOINTS -> target == TargetKind.POINT;
        };
    }

    /** All scenarios with seed {@code 0..seeds-1}. */
    static List<Scenario> all(int seeds, boolean sable) {
        List<Scenario> out = new ArrayList<>();
        for (Launch l : Launch.values())
            for (TargetKind t : TargetKind.values())
                for (Disturbance d : Disturbance.values())
                    if (valid(l, t, d, sable)) for (int s = 0; s < seeds; s++) out.add(new Scenario(l, t, d, s));
        return out;
    }

    /**
     * Parameters from the seed. Lengths match a live launch ({@code StrikeService}): flight time within the
     * settings, final straight leg, launcher distance from the target.
     *
     * @param approach direction of the final leg (where the projectile flies at the target), horizontal unit vector
     * @param flightTime planned flight time, s (Shahed, missile, B-2)
     * @param entry length of the final straight leg, blocks
     * @param side detour to the left (+1) or right (−1)
     * @param standoff launch-site distance from the target, blocks
     * @param readyTicks ticks on the launcher before ignition
     * @param when fraction of the flight plan when the disturbance occurs
     * @param shift blocks the target moves on retarget or teleport
     * @param shiftDir direction (horizontal unit vector)
     * @param size hill height or pit depth, blocks
     * @param place hill: blocks from target to crest; pit: bottom radius
     * @param mobSpeed mob step, blocks/tick; for a player, flight speed
     * @param orbit radius of the circle flown by the "player"
     * @param altitude "player" height above ground
     * @param scatter MLRS dispersion (unit normal: × 1% of range)
     * @param random projectile random seed ({@link ua.zentix.incoming.entity.StrikeProjectile#seed})
     * @param waypoints operator route ({@code WAYPOINTS}): horizontal offsets from the target in flight order,
     *                  1 to 3, each closer to the target than the previous one, beside the approach line;
     *                  the last is 250–450 blocks from the target
     */
    record Params(Vec3 approach, double flightTime, double entry, double side, double standoff, int readyTicks,
                  double when, double shift, Vec3 shiftDir, double size, double place, double mobSpeed, double orbit,
                  double altitude, Vec3 scatter, long random, List<Vec3> waypoints) {}

    Params params() {
        // each cell has its own seed: neighboring scenarios do not repeat each other with a shift
        SplittableRandom r = new SplittableRandom(0x5CE7A410L * 31 + cell());
        double a = r.nextDouble() * Math.PI * 2, s = r.nextDouble() * Math.PI * 2;
        Vec3 approach = new Vec3(Math.cos(a), 0, Math.sin(a));
        Vec3 shiftDir = new Vec3(Math.cos(s), 0, Math.sin(s));
        double flightTime = switch (launch.weapon) {
            case DRONE -> 15 + r.nextDouble() * 35;
            case MISSILE -> 8 + r.nextDouble() * 22;
            default -> 10 + r.nextDouble() * 30;
        };
        double entry = launch.weapon == WeaponType.DRONE ? 300 + r.nextDouble() * 200 : 500 + r.nextDouble() * 200;
        double side = r.nextBoolean() ? 1 : -1;
        double standoff = switch (launch.weapon) {
            case ROCKET -> launch.rail ? 150 + r.nextDouble() * 550 : 400 + r.nextDouble() * 500;
            case LOITER -> launch.rail ? 120 + r.nextDouble() * 380 : 300 + r.nextDouble() * 400;
            default -> 150 + r.nextDouble() * 450;
        };
        int ready = 12 + r.nextInt(13);
        double when = 0.3 + r.nextDouble() * 0.55;
        double shift = switch (disturbance) {
            case TELEPORT_NEAR -> 300;
            case TELEPORT_FAR -> 3000;
            default -> 100 + r.nextDouble() * 300;
        };
        double size = disturbance == Disturbance.HILL ? 10 + r.nextDouble() * 30 : 8 + r.nextDouble() * 10;
        double place = disturbance == Disturbance.HILL ? 110 + r.nextDouble() * 110 : 14 + r.nextDouble() * 14;
        double mobSpeed = target == TargetKind.PLAYER ? 0.3 + r.nextDouble() * 1.1 : 0.05 + r.nextDouble() * 0.2;
        double orbit = 20 + r.nextDouble() * 40;
        double altitude = 5 + r.nextDouble() * 35;
        Vec3 scatter = new Vec3(r.nextGaussian(), 0, r.nextGaussian());
        long random = r.nextLong();
        // the route is from the numbers after the previous ones: the previous scenarios have the same row
        List<Vec3> waypoints = new ArrayList<>();
        if (disturbance == Disturbance.WAYPOINTS) {
            Vec3 across = new Vec3(-approach.z, 0, approach.x);
            int n = 1 + r.nextInt(3);
            double back = 250 + r.nextDouble() * 200;
            for (int i = 0; i < n; i++) {
                double lateral = (r.nextBoolean() ? 1 : -1) * (80 + r.nextDouble() * 320);
                waypoints.addFirst(approach.scale(-back).add(across.scale(lateral)));
                back += 200 + r.nextDouble() * 300;
            }
        }
        return new Params(approach, flightTime, entry, side, standoff, ready, when, shift, shiftDir, size, place, mobSpeed,
                orbit, altitude, scatter, random, List.copyOf(waypoints));
    }
}
