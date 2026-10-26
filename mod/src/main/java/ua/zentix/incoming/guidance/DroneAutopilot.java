package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponSpec;

/**
 * Shahed post-launch flight law: propeller climb, cruise above terrain and at least target+30, arcing dive once
 * the final-leg target is 18° below horizon and the line to it is clear of blocks, accelerating to dive speed.
 * Blocked line uses the missile approach rule ({@link Autopilot.Sightline}) without climbing: continue at cruise
 * altitude and dive more steeply once clear or the nose can no longer turn toward target in time. Climbing hurts
 * here: the line clears above the obstacle at the same target angle (over its top), but the nose is already
 * raised, so a 4°/tick dive cannot reach that angle. With climbing, Shahed overshot targets behind buildings 45
 * blocks or closer to them ({@code DroneDiveTest}). Launcher start, target, and flight step belong to the entity;
 * here only control commands ({@link Craft}).
 */
public final class DroneAutopilot {
    /** Cruise at least this many blocks above target. */
    private static final double ABOVE_TARGET = 30;
    /** At least this many blocks above terrain ahead. */
    private static final double ABOVE_RELIEF = 18;
    /** Dive when target is this many degrees below horizon. */
    private static final double DIVE_PITCH = 18;
    /** Dive: maximum pitch angular speed, °/tick, and acceleration, °/tick². */
    /**
     * While the target line is blocked, check the forward path at flight height for this many blocks: Shahed
     * climbs over the tallest city parts, where masts and towers need advance visibility; climb is at most 15°.
     * Terrain strip ({@link Autopilot#reliefAhead}) sees them; this additionally checks block cells on the
     * forward line every {@link Autopilot#SIGHT_RECHECK} ticks. Over 90 blocks, climb gains ~20 blocks.
     */
    private static final double PATH_AHEAD = 90;

    private final WeaponSpec.Airframe air;
    private final Autopilot.Turn climbTurn;
    private final Autopilot.Turn cruiseTurn;
    /** Terrain ahead. */
    private final Autopilot.ReliefSensor relief = new Autopilot.ReliefSensor();
    /** Cruise altitude: never descend below it even if terrain falls away. */
    private double cruiseAlt;
    /** Line to target on approach. */
    private final Autopilot.Sightline sight = new Autopilot.Sightline();
    /** Ticks until next forward-path check while the target line is blocked (not saved). */
    private int pathWait;
    /**
     * Top of an obstacle ahead on our path, found while target line blocked: cruise stays {@link #ABOVE_RELIEF}
     * above it; {@code -Infinity} means none. Reset outside the dive window (not saved: check immediately after
     * loading).
     */
    private double obstacleTop = Double.NEGATIVE_INFINITY;

    public DroneAutopilot(WeaponSpec.Airframe air) {
        this.air = air;
        // Gentler launch turns: speed is still low.
        this.climbTurn = Autopilot.Turn.of(0.08, air.handling().climb());
        this.cruiseTurn = Autopilot.Turn.of(0.15, air.handling().cruise());
    }

    public double cruiseAlt() {
        return cruiseAlt;
    }

    public void setCruiseAlt(double cruiseAlt) {
        this.cruiseAlt = cruiseAlt;
    }

    /**
     * Already airborne (approach from afar, tests): flight height = max(launch, target+30, terrain+20), also
     * cruise altitude.
     *
     * @param surface terrain height under launch
     */
    public double airborneAltitude(Vec3 pos, Vec3 target, double surface) {
        double y = Math.max(pos.y, target.y + ABOVE_TARGET);
        y = Math.max(y, surface + 20);
        cruiseAlt = y;
        return y;
    }

    /** From launcher: cruise at spec height above launch or target, whichever is higher. */
    public void fromLauncher(Vec3 rail, Vec3 target) {
        cruiseAlt = Math.max(rail.y, target.y) + air.cruiseHeight();
    }

    /**
     * Post-launch flight tick.
     *
     * @param aim target point
     * @param nav heading destination: waypoint or target
     * @param finalLeg route complete: final leg toward target
     */
    public void fly(Craft c, Vec3 aim, Vec3 nav, boolean finalLeg) {
        Bearing b = Bearing.of(c.position(), aim);
        Bearing n = Bearing.of(c.position(), nav);
        FlightPhase ph = c.phase();
        Autopilot.Turn turn = ph == FlightPhase.CLIMB ? climbTurn : cruiseTurn;

        if (ph == FlightPhase.CLIMB) {
            // Propeller at full RPM; speed after booster falls toward cruise.
            c.throttle(air, air.cruiseSpeed());
            double terrain = relief.reliefAhead(c, nav, turn, air);
            c.holdAltitude(Math.max(cruiseAlt, terrain + ABOVE_RELIEF), 0.10, air.handling(), air.handling().climb());
            if (c.phaseAge() > 60 && Math.abs(cruiseAlt - c.position().y) < 6) c.setPhase(FlightPhase.CRUISE);
        }
        // Target inside turning circle: cancel dive, continue straight while climbing, and approach again.
        boolean outOfTurn = Autopilot.outOfTurn(c, nav, n, air.attack().reattackMin(), turn.rate());
        if (outOfTurn && c.phase() == FlightPhase.TERMINAL) c.setPhase(FlightPhase.CRUISE);
        // Dive as soon as target reaches the required angle, even while still climbing (nearby target, retargeting);
        // check target line last: block trace costs more than other conditions. Do not check during dive: the dive arc lies
        // above the clear line; Shahed exits dives slowly (cruise pitch 1.2°/tick).
        boolean window = (c.phase() == FlightPhase.CRUISE || c.phase() == FlightPhase.CLIMB) && finalLeg && b.pitch() >= DIVE_PITCH && !outOfTurn;
        boolean masked = window && sight.masked(c, aim, b, air.reachPad(), air.handling().dive().pitchRate(), air.handling().dive().pitchAccel());
        if (!window) {
            sight.forget();
            pathWait = 0;
            obstacleTop = Double.NEGATIVE_INFINITY;
        } else if (masked) {
            watchPath(c);
        } else {
            c.setPhase(FlightPhase.TERMINAL);
        }

        if (c.phase() == FlightPhase.CRUISE) {
            c.throttle(air, air.cruiseSpeed());
            double terrain = relief.reliefAhead(c, nav, turn, air);
            double desired = Math.max(Math.max(Math.max(terrain, obstacleTop) + ABOVE_RELIEF, cruiseAlt), aim.y + ABOVE_TARGET);
            c.holdAltitude(desired, 0.12, air.handling(), air.handling().cruise());
        } else if (c.phase() == FlightPhase.TERMINAL) {
            WeaponSpec.Agility dive = air.handling().dive();
            c.flight().arcPitch(b.pitch(), c.speed(), b.distance(), dive.pitchRate(), dive.pitchAccel());
            c.throttle(air, air.diveSpeed());
        }
        Autopilot.steer(c, outOfTurn, n, turn);
    }

    /**
     * While target line is blocked, check our forward path at flight height ({@link #PATH_AHEAD}) every {@link
     * Autopilot#SIGHT_RECHECK} ticks; store the first obstacle top in {@link #obstacleTop} (highest while
     * waiting).
     */
    private void watchPath(Craft c) {
        if (pathWait > 0) {
            pathWait--;
            return;
        }
        pathWait = Autopilot.SIGHT_RECHECK - 1;
        Vec3 f = c.flight().forward();
        Vec3 level = new Vec3(f.x, 0, f.z).normalize();
        double free = c.clearAlong(c.position().add(level.scale(PATH_AHEAD)), 0);
        // Highest top while waiting: above the obstacle the path becomes clear, and cruise would descend onto it.
        if (free != Double.POSITIVE_INFINITY) {
            Vec3 p = c.position(), at = p.add(level.scale(free + 1));
            obstacleTop = Math.max(obstacleTop, Autopilot.reliefAlong(new double[]{p.x, p.z, at.x, at.z}, c::relief));
        }
    }
}
