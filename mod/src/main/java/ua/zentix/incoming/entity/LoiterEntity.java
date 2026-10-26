package ua.zentix.incoming.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.guidance.Bearing;
import ua.zentix.incoming.guidance.Dive;
import ua.zentix.incoming.guidance.Orbit;
import ua.zentix.incoming.strike.FlightTickets;
import ua.zentix.incoming.strike.FlightScale;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.util.UUID;

/**
 * Lancet-style loitering munition: small electric drone with two cruciform wing sets. Catapult launch (pneumatic
 * impulse, no booster), wings unfold in air, then straight to the target and circles above it at spec cruise
 * height ({@link FlightPhase#LOITER}) while waiting. The orbit follows a moving target (a sighted target only
 * while held in the operator's camera frame, {@code strike.CameraLink}). Loiter time is {@code loiter_time} from
 * settings (each salvo member ±20%), then a steep dive accelerating to 4 blocks/tick. From the projectile camera
 * (left-click), the operator selects a target: dive immediately if nearby, otherwise fly there and orbit there.
 * With an operator route ({@code Waypoints}), first pass its points, then join the orbit from the final leg.
 */
public class LoiterEntity extends StrikeProjectile {
    /** Spec: speeds (≈115 km/h, dive 4 blocks/tick), turn limits, orbit height above target, catapult. */
    private static final WeaponSpec.Airframe AIR = WeaponSpec.LOITER.airframe();
    private static final WeaponSpec.Handling HANDLING = AIR.handling();
    /** Orbit radius above the target. */
    public static final double LOITER_RADIUS = 40;
    /** Retargeted within this horizontal distance: dive immediately without orbiting. */
    private static final double STRIKE_NOW = 140;
    /** Average ticks for the dive from orbit (for time to impact). */
    public static final int DIVE_TICKS = 25;
    /** Missed dive: this many blocks beyond closest approach, return to orbit and approach again. */
    private static final double MISSED_BY = 20;

    private double cruiseAlt;
    /** Ticks to circle above the target (set at launch). */
    private int loiterTicks;
    /** Orbit direction: +1 or −1 (mixed within a salvo). */
    private int orbitSide = 1;
    private double orbitRadius = LOITER_RADIUS;
    /** Operator selected a target from camera (or dive missed): dive without waiting for the orbit to end. */
    private boolean strikeNow;
    /** Closest approach to target in this dive, blocks. */
    private double closest = Double.MAX_VALUE;

    public LoiterEntity(EntityType<? extends LoiterEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.LOITER;
    }

    /** Path ends at target only during the dive: approach and loiter orbit pass by it. */
    @Override
    @Nullable
    protected Vec3 pathEnd() {
        return flightPhase() == FlightPhase.TERMINAL ? super.pathEnd() : null;
    }

    /** Loiter and orbit: each munition has its own (salvo spreads by height and direction, dives in sequence). */
    private void pickOrbit() {
        int seconds = IncomingConfig.SERVER.loiterTime.get();
        loiterTicks = (int) (seconds * 20 * (0.8 + 0.4 * random.nextDouble()));
        orbitSide = random.nextBoolean() ? 1 : -1;
        orbitRadius = LOITER_RADIUS * (0.8 + 0.4 * random.nextDouble());
    }

    /** Already airborne (approach from afar, tests): at orbit height. */
    @Override
    public void launch(Vec3 pos, Target target, Vec3 targetPoint, @Nullable UUID owner) {
        super.launch(pos, target, targetPoint, owner);
        pickOrbit();
        cruiseAlt = Math.max(pos.y, targetPoint.y + AIR.cruiseHeight());
        altitude.reset(pos.y);
        speed = AIR.cruiseSpeed();
        setPhase(FlightPhase.CRUISE);
    }

    @Override
    public void placeOnLauncher(Vec3 rail, float yaw, float elevation, int readyTicks, int hiddenTicks, Target target, Vec3 targetPoint,
                                @Nullable UUID owner) {
        super.placeOnLauncher(rail, yaw, elevation, readyTicks, hiddenTicks, target, targetPoint, owner);
        cruiseAlt = Math.max(rail.y + 30, targetPoint.y + AIR.cruiseHeight());
    }

    /**
     * Planned dive ticks from orbit: {@link #DIVE_TICKS} from game orbit; flight scale raises the orbit (up to
     * 700 m), increasing time with path length from orbit to target and slower (or faster) dive speed.
     */
    static int diveTicks() {
        double x = FlightScale.x();
        if (x == 0) return DIVE_TICKS;
        double path = Math.hypot(AIR.cruiseHeight(x), LOITER_RADIUS) / Math.hypot(AIR.gameHeight(), LOITER_RADIUS);
        return (int) Math.ceil(DIVE_TICKS * path * AIR.gameDive() / AIR.diveSpeed(x));
    }

    /**
     * Range also covers the orbit over the target (at cruise speed) and dive from it (at dive speed: over planned
     * {@link #DIVE_TICKS}, faster than orbit, so cruise-speed range would not cover the dive).
     */
    @Override
    protected double extraRange() {
        return loiterTicks * AIR.cruiseSpeed() + diveTicks() * AIR.diveSpeed();
    }

    /** Catapult: pop and vapor cloud; no booster to drop. */
    @Override
    protected void separate(ServerLevel level) {}

    /**
     * The orbit (up to 48 blocks plus ~10 turn drift) must fit entirely in ticking chunks regardless of target
     * position in its chunk: target area is 9×9 ticking chunks (±64 blocks from target chunk edge).
     */
    @Override
    protected int targetArea() {
        return FlightTickets.LOITER_DISTANCE;
    }

    /**
     * Orbit above target runs in the world; time to impact while circling is the entire loiter time: area ticks
     * as soon as acquired.
     */
    @Override
    protected boolean targetAreaTicks() {
        return true;
    }

    @Override
    protected boolean launchTick(ServerLevel level) {
        if (flightPhase() == FlightPhase.IGNITION && phaseAge() == 0) {
            Vec3 back = flight.forward().scale(-1);
            level.sendParticles(ParticleTypes.CLOUD, getX() + back.x * 2, getY() + back.y * 2, getZ() + back.z * 2, 14, 0.5, 0.3, 0.5, 0.05);
        }
        return super.launchTick(level);
    }

    @Override
    public int etaTicks() {
        if (tracker == null) return 0;
        Vec3 aim = tracker.point();
        return switch (flightPhase()) {
            case TERMINAL -> (int) Math.ceil(position().distanceTo(aim) / Math.max(speed, AIR.cruiseSpeed()));
            case LOITER -> strikeNow ? diveTicks() : Math.max(0, loiterTicks - phaseAge()) + diveTicks();
            default -> {
                double toOrbit = Math.max(0, horizontalPathTo(aim) - orbitRadius);
                yield (int) Math.ceil(toOrbit / AIR.cruiseSpeed()) + launchTicksLeft() + (strikeNow ? diveTicks() : loiterTicks + diveTicks());
            }
        };
    }

    /**
     * Planned path to impact, as for time to impact ({@link #etaTicks}): to orbit, remaining orbit at cruise,
     * then dive at dive speed. Losing a target on orbit does not cut loitering short: the Lancet finishes
     * circling and approaches its last position.
     */
    @Override
    protected double plannedPathLeft() {
        double dive = diveTicks() * AIR.diveSpeed();
        return switch (flightPhase()) {
            case TERMINAL -> super.plannedPathLeft();
            case LOITER -> (strikeNow ? 0 : Math.max(0, loiterTicks - phaseAge()) * AIR.cruiseSpeed()) + dive;
            default -> {
                double toOrbit = Math.max(0, horizontalPathTo(tracker.point()) - orbitRadius);
                yield toOrbit + (strikeNow ? 0 : loiterTicks * AIR.cruiseSpeed()) + dive;
            }
        };
    }

    @Override
    protected void onRetarget() {
        // Nearby: attack immediately; far away: fly there and orbit there.
        Bearing b = bearingTo(tracker.point());
        strikeNow = b.horizontal() < STRIKE_NOW;
        if (!strikeNow && flightPhase() == FlightPhase.LOITER) setPhase(FlightPhase.CRUISE);
        cruiseAlt = Math.max(cruiseAlt, tracker.point().y + AIR.cruiseHeight());
    }

    @Override
    protected void serverTick(ServerLevel level) {
        Vec3 aim = updateTarget(level);
        if (launchTick(level)) return;

        Bearing b = bearingTo(aim);
        FlightPhase ph = flightPhase();
        double terrain = reliefStraightAhead(level, AIR.reliefLookahead());
        double floor = terrain + 15;

        if (ph == FlightPhase.CLIMB) {
            // Wings unfolded, propeller pulling: climb toward the orbit (or operator route waypoint).
            throttle(AIR.cruiseSpeed());
            holdAltitude(Math.max(cruiseAlt, floor), 0.12, HANDLING.climb());
            flight.steerYaw(bearingTo(navPoint(aim)).yaw(), 0.08, HANDLING.climb().turnRate(), HANDLING.climb().turnAccel());
            if (phaseAge() > 40) setPhase(FlightPhase.CRUISE);
        } else if (ph == FlightPhase.CRUISE) {
            throttle(AIR.cruiseSpeed());
            holdAltitude(Math.max(cruiseAlt, floor), 0.12, HANDLING.cruise());
            Vec3 nav = navPoint(aim);
            if (!onFinalLeg()) {
                // Operator route: point by point, then join the orbit from the final leg.
                flight.steerYaw(bearingTo(nav).yaw(), 0.15, HANDLING.cruise().turnRate(), HANDLING.cruise().turnAccel());
            } else if (strikeNow) {
                flight.steerYaw(b.yaw(), 0.15, HANDLING.cruise().turnRate(), HANDLING.cruise().turnAccel());
                if (b.pitch() >= 30 || b.horizontal() <= orbitRadius + 15) setPhase(FlightPhase.TERMINAL);
            } else {
                // Approach orbit with the same heading field as on orbit: tangential entry without overshoot.
                orbit().steer(flight, position(), aim, tracker.velocity(), speed);
                if (orbit().captured(position(), aim, tracker.velocity(), speed, flight.yaw())) setPhase(FlightPhase.LOITER);
            }
        } else if (ph == FlightPhase.LOITER) {
            throttle(AIR.cruiseSpeed());
            holdAltitude(Math.max(aim.y + AIR.cruiseHeight(), floor), 0.12, HANDLING.cruise());
            orbit().steer(flight, position(), aim, tracker.velocity(), speed);
            // Enter dive when target is under the wing (40° or steeper) and time has elapsed.
            boolean due = strikeNow || phaseAge() >= loiterTicks;
            if (due && b.pitch() >= 40) setPhase(FlightPhase.TERMINAL);
        } else if (ph == FlightPhase.TERMINAL) {
            closest = Math.min(closest, b.distance());
            if (b.distance() > closest + MISSED_BY) {
                // Passed by (target dodged, or dive angle insufficient): do not weave around it; like a real
                // Lancet, return to orbit and approach again as soon as the target is under the wing.
                strikeNow = true;
                setPhase(FlightPhase.LOITER);
            } else {
                Dive.steer(flight, position(), speed, aim, tracker.velocity(), HANDLING.dive());
                throttle(AIR.diveSpeed());
            }
        }

        advance(level, aim, AIR.reachPad());
    }

    @Override
    protected void setPhase(FlightPhase phase) {
        super.setPhase(phase);
        if (phase == FlightPhase.TERMINAL) closest = Double.MAX_VALUE;
    }

    private Orbit orbit() {
        return new Orbit(orbitRadius, orbitSide);
    }

    /** This munition's orbit radius (each salvo member has its own), blocks. */
    public double orbitRadius() {
        return orbitRadius;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        cruiseAlt = tag.getDouble("cruise_alt");
        loiterTicks = tag.getInt("loiter_ticks");
        orbitSide = tag.getInt("orbit_side") < 0 ? -1 : 1;
        orbitRadius = tag.contains("orbit_radius") ? tag.getDouble("orbit_radius") : LOITER_RADIUS;
        strikeNow = tag.getBoolean("strike_now");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putDouble("cruise_alt", cruiseAlt);
        tag.putInt("loiter_ticks", loiterTicks);
        tag.putInt("orbit_side", orbitSide);
        tag.putDouble("orbit_radius", orbitRadius);
        tag.putBoolean("strike_now", strikeNow);
    }
}
