package ua.zentix.incoming.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.guidance.Ballistics;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.guidance.Mission;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Nbt;
import ua.zentix.incoming.util.Terrain;

import java.util.UUID;

/**
 * An unguided MLRS rocket (122 mm, like Grad). It waits in a rack tube, ignites, exits, then follows only
 * ballistics ({@link Ballistics}): gravity, air drag, and world wind ({@link WeaponSpec.Ballistic}, game
 * coefficient). The fire solution ({@link Ballistics#aim}) gives launch rail exit speed and heading at rack
 * elevation. The firing data belong to the order ({@link #fireData}): the fire solution for a player order knows
 * only ground wind (stronger wind aloft drifts the salvo downwind), and each rocket uses the launch rail exit
 * dispersion from its spec. Without firing data (tests), the launch rail exit is exact and uses the full wind
 * profile. Thus, a 500-block shot takes ~10 s, rises a hundred blocks, descends more steeply than it rose, and
 * the salvo lands with dispersion and wind drift. The engine burns for the first {@link #BURN_TICKS} (plume and
 * dense smoke; launch rail exit speed is immediate, not physical: acceleration in the tube and first meters is
 * not calculated), then coasts; descending flight is the attack phase.
 * <p>
 * The entire flight path is calculated at launch rail exit ({@link Ballistics#fly}) and traversed by ticks. If
 * the rocket is displaced (materialization raises it above terrain), solve again from the current position at the
 * same path inclination. The target is not tracked: the rocket lands where it was aimed at launch (map-site
 * height is refined once, {@link #settleImpact}).
 * <p>
 * The impact area starts loading when the rocket is placed in a tube, and the rocket exits immediately. If it
 * flies outside the loaded world toward the target while the area is unready, the end of the trajectory stretches
 * in time ({@link #stretch}) so the rocket does not freeze in the air near the target.
 */
public class RocketEntity extends StrikeProjectile {
    private static final WeaponSpec.Ballistic FLIGHT = WeaponSpec.ROCKET.airframe().ballistic();
    private static final WeaponSpec.RocketFlight ROCKET = FLIGHT.rocket();
    // Retained for callers; weapon-specific values live in the passport.
    public static final int BURN_TICKS = WeaponSpec.RocketFlight.BURN_TICKS;
    public static final int IGNITION_TICKS = WeaponSpec.RocketFlight.IGNITION_TICKS;
    public static final int MIN_FLIGHT = WeaponSpec.RocketFlight.MIN_FLIGHT;
    public static final double STRETCH_TICKS = WeaponSpec.RocketFlight.STRETCH_TICKS;

    /** 9M22U drag on the world flight scale ({@link WeaponSpec.Airframe#drag()}): game at 0, real with f(M) at 1. */
    private static Ballistics.Drag drag() {
        return WeaponSpec.ROCKET.airframe().drag();
    }

    /** Impact point: where aimed at launch. */
    @Nullable
    private Vec3 impactAt;
    /** This tick's correction to the nose step ({@link #drift}); zero outside the step. */
    private Vec3 correction = Vec3.ZERO;
    /**
     * The current trajectory is indexed by tick (not saved: recalculated from its origin and launch rail exit
     * velocity).
     */
    @Nullable
    private Ballistics.Path path;
    /**
     * Where the rocket arrives ({@link Ballistics.Path#arrival}): descending at impact-point height (wind and
     * dispersion displace it); a point above the descending path is reached on ascent.
     */
    @Nullable
    private Vec3 landing;
    /** Origin, initial velocity, and flight duration of the current trajectory. */
    @Nullable
    private Vec3 start;
    private Vec3 v0 = Vec3.ZERO;
    private int flightTicks;
    /** Time from current trajectory origin, ticks (fractional: virtual flight stretches). */
    private double t;
    /** Trajectory time rate: 1 matches world time; less means virtual flight stretched until target area loads. */
    private double rate = 1;
    /** World time accumulated beyond trajectory time (stretching); whole ticks count toward area waiting. */
    private double stretched;
    /**
     * Trajectory time after which the rocket is within {@link WeaponSpec.RocketFlight#holdOff} of target: stretching only approaches
     * it.
     */
    private double holdAt;
    /**
     * This rocket's launch rail exit dispersion in spec standard deviations: (sideways direction, upward, speed);
     * zero means exact launch rail exit ({@link #fireData}).
     */
    private Vec3 spread = Vec3.ZERO;
    /** Fire solution knows only ground wind ({@link Ballistics.Wind#ground}); otherwise the full altitude profile. */
    private boolean groundWindOnly;
    /** Rack elevation while the rocket stood on the launcher. */
    private float elevation = LauncherEntity.elevation(WeaponType.ROCKET);

    public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.ROCKET;
    }

    /** Unguided rocket: no self-destructor; abort does not stop it (flight lasts seconds). */
    @Override
    public boolean recallable() {
        return false;
    }

    /** Mean trajectory speed for estimates; approach time uses the trajectory itself. */

    @Override
    protected boolean climbs() {
        return false;
    }

    @Override
    protected boolean acceptsRetarget() {
        return false;
    }

    /**
     * Ordered firing data ({@code StrikeService}): launch rail exit dispersion is {@code random}'s normal values
     * in spec standard deviations ({@link WeaponSpec.Ballistic}); solution knows only ground wind. Before launch
     * rail exit.
     */
    public void fireData(RandomSource random) {
        fireData(new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()), true);
    }

    /**
     * Firing data: launch rail exit dispersion in standard deviations (sideways, upward, speed) and whether the
     * solution knows only ground wind. Before launch rail exit.
     */
    public void fireData(Vec3 spread, boolean groundWindOnly) {
        this.spread = spread;
        this.groundWindOnly = groundWindOnly;
    }

    /** Place in tube: as for Shahed, plus rack angle used to build the trajectory. */
    public void placeInTube(Vec3 rail, float yaw, float elevation, int readyTicks, int hiddenTicks,
                            Target target, Vec3 impact, @Nullable UUID owner) {
        placeOnLauncher(rail, yaw, elevation, readyTicks, hiddenTicks, target, impact, owner);
        this.elevation = elevation;
        this.impactAt = impact;
    }

    /**
     * Launch without a launcher (console, no suitable site): already on a trajectory starting at {@code from}.
     * Fuze armed: this rocket arrives from afar.
     */
    public void launchFrom(Vec3 from, Target target, Vec3 impact, @Nullable UUID owner) {
        launch(from, target, impact, owner);
        this.impactAt = impact;
        this.elevation = LauncherEntity.elevation(WeaponType.ROCKET);
        solve(from, impact);
        setPhase(FlightPhase.CRUISE);
        face(v0);
    }

    @Override
    protected int launchTicksLeft() {
        return switch (flightPhase()) {
            case READY -> Math.max(0, readyTicks() - phaseAge()) + ROCKET.ignitionTicks();
            case IGNITION -> Math.max(0, ROCKET.ignitionTicks() - phaseAge());
            default -> 0;
        };
    }

    @Override
    public int etaTicks() {
        if (start == null) {
            // Still in tube: flight time starts there.
            Vec3 aim = impactAt != null ? impactAt : aimPoint();
            return launchTicksLeft() + Ballistics.ticksFor(position(), aim, elevation, ROCKET.minFlight());
        }
        return (int) Math.ceil(Math.max(0, flightTicks - t));
    }

    @Override
    protected void serverTick(ServerLevel level) {
        if (impactAt == null) impactAt = tracker.point();
        settleImpact(level);
        FlightPhase ph = flightPhase();
        if (ph == FlightPhase.READY) {
            speed = 0;
            if (phaseAge() >= readyTicks()) setPhase(FlightPhase.IGNITION);
            return;
        }
        if (ph == FlightPhase.IGNITION) {
            speed = 0;
            if (phaseAge() >= ROCKET.ignitionTicks()) {
                solve(position(), impactAt);
                setPhase(FlightPhase.BOOST);
            }
            return;
        }
        // Virtual path ended at the surface: no trajectory or stretching; wait there for loading and impact.
        if (isGrounded()) {
            advance(level, landing(), airframe().reachPad());
            return;
        }
        if (start == null) solve(position(), impactAt);

        // Displaced from trajectory (materialized above terrain): solve again from current position.
        if (position().distanceToSqr(at(t)) > 0.25) {
            resolve(position(), impactAt);
        }

        if (ph == FlightPhase.BOOST && phaseAge() >= ROCKET.burnTicks()) setPhase(FlightPhase.CRUISE);
        // In the world the rocket is visible: time must match world time.
        double step = isVirtual() ? stretch(level) : (rate = 1);
        if (isRemoved()) return;
        Vec3 v = at(t + step).subtract(at(t));
        if (flightPhase() == FlightPhase.CRUISE && v.y < 0) setPhase(FlightPhase.TERMINAL);

        // Virtual flight stretched almost to a stop near target: stay still until rate recovers.
        if (v.lengthSqr() < 1.0e-12) return;
        face(v);
        speed = v.length();
        Vec3 before = position();
        // Nose step (angles and tabulated sine) drifts from the path by fractions of a block per tick; correction places the rocket exactly at
        // the path point: without it, over hundreds of ticks it would drift half a block and trigger an unnecessary new fire solution.
        correction = at(t + step).subtract(before.add(flight.forward().scale(speed)));
        boolean flying = advance(level, landing(), airframe().reachPad());
        correction = Vec3.ZERO;
        if (!flying) return;
        // Virtually near the target, the rocket waits for area loading: no movement, so trajectory time stops.
        if (position().distanceToSqr(before) > 1.0e-6) t += step;
    }

    /**
     * Map site ({@link Target.Ground}): impact height at launch is an estimate while its chunk is unready
     * (generator estimate in a saved city is street level under the roof). Impact area loads from tube placement;
     * once the chunk is ready, set the point on its surface (keeping dispersion), once, as the fire solution's
     * target height: the target becomes this point and is then untracked, like all MLRS shots. Already in flight:
     * recalculate trajectory from current position for remaining time.
     */
    private void settleImpact(ServerLevel level) {
        if (!(tracker.target() instanceof Target.Ground) || !Terrain.ready(level, BlockPos.containing(impactAt))) return;
        Vec3 surface = new Target.Ground(impactAt).surface(level);
        boolean moved = !surface.equals(impactAt);
        impactAt = surface;
        // Target is the dispersed impact point itself, as at launch: marker, siren, camera, and target area belong to it.
        settleAim(impactAt);
        if (moved && start != null) resolve(position(), impactAt);
    }

    /** Trajectory time rate: 1 matches world time; less means stretched virtual flight (for the scene bench). */
    public double timeRate() {
        return rate;
    }

    /**
     * Virtual flight rate. If impact area is unready, smoothly slow the last {@link #STRETCH_TICKS} trajectory
     * ticks until {@link WeaponSpec.RocketFlight#holdOff} blocks from target (rate = time remaining to that boundary / STRETCH_TICKS,
     * changing by at most {@link WeaponSpec.RocketFlight#rateSlew} per tick): approach that line ever more slowly without freezing;
     * once ready, smoothly return rate to 1. Nobody sees the virtual rocket, only hears its distant path.
     * Stretched time counts as area waiting: the rocket travels more slowly without spending range on it; waiting
     * shares the limit used at the target.
     */
    private double stretch(ServerLevel level) {
        double want = aimAreaReady(level, landing()) ? 1 : Math.min(1, Math.max(0, holdAt - t) / ROCKET.stretchTicks());
        rate += Math.max(-ROCKET.rateSlew(), Math.min(ROCKET.rateSlew(), want - rate));
        stretched += 1 - rate;
        while (stretched >= 1 && !isRemoved()) {
            stretched -= 1;
            waitForAimArea(impactAt);
        }
        return rate;
    }

    /**
     * Target area loads from tube placement: arc flight is short (500 blocks, ~10 s), so an area acquired on
     * approach would not load in time. Rocket exits immediately: flight exceeds area loading (measured 0.7–4.6 s
     * for a fresh area); short virtual flights stretch ({@link #stretch}).
     */
    @Override
    protected double preloadDistance() {
        return Double.MAX_VALUE;
    }

    /**
     * Path ends where the descending trajectory crosses impact height ({@link #landing}): wind and launch rail
     * exit dispersion shift it blocks from the aim point.
     */
    @Override
    @Nullable
    protected Vec3 pathEnd() {
        return landing != null ? landing : impactAt != null ? impactAt : super.pathEnd();
    }

    /** Where the rocket lands: point on descending trajectory; if no trajectory yet, target point. */
    private Vec3 landing() {
        return landing != null ? landing : impactAt != null ? impactAt : tracker.point();
    }

    /** Step correction beyond the nose step ({@link #drift}): places the rocket exactly on its path. */
    @Override
    protected Vec3 drift() {
        return correction;
    }

    /**
     * Target area is where the rocket will arrive: launch rail exit dispersion and wind shift it from the target
     * point.
     */
    @Override
    protected Vec3 areaCenter() {
        return landing();
    }

    /** Nose along velocity. */
    private void face(Vec3 v) {
        float[] a = FlightController.anglesTo(Vec3.ZERO, v);
        flight.set(a[0], a[1]);
    }

    private Vec3 at(double k) {
        return path().at(k);
    }

    /**
     * Current trajectory (after loading, recalculate from origin and launch rail exit velocity with current world
     * wind).
     */
    private Ballistics.Path path() {
        if (path == null) fly(impactAt != null ? impactAt : start);
        return path;
    }

    /** Last trajectory tick when still farther than {@link WeaponSpec.RocketFlight#holdOff} from impact (0 means already closer). */
    private double holdPoint(Vec3 impact) {
        for (int k = flightTicks; k > 0; k--) {
            if (at(k).distanceTo(impact) > ROCKET.holdOff()) return k;
        }
        return 0;
    }

    /**
     * Launch rail exit from {@code from} toward {@code to}: rack elevation (if too shallow for {@link
     * #MIN_FLIGHT}, steepen up to 80°); fire solution for speed and heading in ground wind, then spec launch rail
     * exit dispersion.
     */
    private void solve(Vec3 from, Vec3 to) {
        float el = Ballistics.elevationFor(from, to, elevation, ROCKET.minFlight());
        restart(from, to, scatter(Ballistics.aim(from, to, el, drag(), known())));
    }

    /**
     * Re-solve from {@code from} (displaced from trajectory, refined point height), keeping current path
     * inclination without new dispersion.
     */
    private void resolve(Vec3 from, Vec3 to) {
        Vec3 v = at(t + 1).subtract(at(t));
        float el = (float) Math.toDegrees(Math.atan2(v.y, Math.max(1e-6, v.horizontalDistance())));
        Vec3 aim = Ballistics.aim(from, to, Math.max(-80, Math.min(80, el)), drag(), known());
        // Cannot reach the point at this inclination (on descent it lies above the path): do not accelerate the rocket; aim directly there over
        // remaining time, as a drag-free parabola (not physical, rare case: refined point height).
        if (!Ballistics.hits(from, aim, to, drag(), known(), 1) || aim.length() > 2 * v.length() + 1) {
            // Velocity for a drag-free parabola p = from + v·n − g·n²/2 (as in the integrator) to reach the point in n ticks.
            int n = Math.max(8, (int) Math.ceil(flightTicks - t));
            aim = to.subtract(from).scale(1.0 / n).add(0, Ballistics.GRAVITY * n / 2, 0);
        }
        restart(from, to, aim);
    }

    /** Wind known to the fire solution ({@link #groundWindOnly}). */
    private Ballistics.Wind known() {
        Air air = air();
        return groundWindOnly ? Ballistics.Wind.ground(air) : Ballistics.Wind.of(air);
    }

    /**
     * Launch rail exit dispersion {@link #spread}: direction in standard deviations of {@link
     * WeaponSpec.Ballistic#spreadAngle} along two axes; speed uses {@link WeaponSpec.Ballistic#spreadSpeed}.
     */
    private Vec3 scatter(Vec3 v) {
        double speed = v.length();
        if (speed < 1e-9) return v;
        Vec3 dir = v.scale(1 / speed);
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        side = side.lengthSqr() < 1e-9 ? new Vec3(1, 0, 0) : side.normalize();
        Vec3 up = side.cross(dir);
        double sa = Math.toRadians(FLIGHT.spreadAngle());
        Vec3 d = dir.add(side.scale(spread.x * sa)).add(up.scale(spread.y * sa)).normalize();
        return d.scale(speed * (1 + spread.z * FLIGHT.spreadSpeed()));
    }

    /**
     * Current trajectory arc length from time {@code from} to impact, blocks: distance traveled by ticks
     * (fractional start: virtual flight stretches).
     */
    private double arcFrom(double from) {
        int first = (int) Math.ceil(from);
        double arc = first < flightTicks ? at(first).subtract(at(from)).length() : 0;
        for (int k = first; k < flightTicks; k++) arc += at(k + 1).subtract(at(k)).length();
        return arc;
    }

    /** Range for remaining trajectory from {@link #t}: arc to impact plus {@link WeaponSpec.RocketFlight#overshootTicks} overshoot. */
    private Mission missionLeft() {
        return Mission.of(arcFrom(t) + ROCKET.overshootTicks() * cruiseSpeed());
    }

    /** New trajectory from {@code from} with velocity {@code v} toward {@code to}. */
    private void restart(Vec3 from, Vec3 to, Vec3 v) {
        start = from;
        v0 = v;
        t = 0;
        fly(to);
        setMission(missionLeft());
        holdAt = holdPoint(landing);
    }

    /**
     * Path from {@link #start} with {@link #v0} in current world wind, to {@code to}'s height on descent plus
     * {@link WeaponSpec.RocketFlight#overshootTicks} or {@link WeaponSpec.RocketFlight#belowImpact} below; gives flight time and arrival point.
     */
    private void fly(Vec3 to) {
        double h = Math.max(0.05, v0.horizontalDistance()), d = Math.sqrt(to.subtract(start).horizontalDistanceSqr());
        int ticks = (int) Math.min(6000, 3 * d / h + 400) + ROCKET.overshootTicks();
        path = Ballistics.fly(start, v0, drag(), Ballistics.Wind.of(air()), to.y - ROCKET.belowImpact(), ticks);
        double k = path.arrival(start, to);
        flightTicks = Double.isNaN(k) ? Math.max(1, path.ticks()) : Math.max(1, (int) Math.ceil(k));
        landing = Double.isNaN(k) ? to : path.at(k);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        elevation = tag.contains("elevation") ? tag.getFloat("elevation") : LauncherEntity.elevation(WeaponType.ROCKET);
        Vec3 s = Nbt.getVec(tag, "spread");
        spread = s != null ? s : Vec3.ZERO;
        groundWindOnly = tag.getBoolean("ground_wind");
        Vec3 impact = Nbt.getVec(tag, "impact");
        if (impact != null) impactAt = impact;
        if (tag.contains("start_x")) {
            start = Nbt.getVec(tag, "start");
            v0 = Nbt.getVec(tag, "v0");
            flightTicks = tag.getInt("flight_ticks");
            t = tag.contains("t") ? tag.getDouble("t") : tag.getInt("n");
            // Recalculate path from origin and launch rail exit velocity (saves from before air drag follow the new law;
            // if they diverge from it, solve again from current position).
            path = null;
            landing = null;
            if (impactAt != null) {
                fly(impactAt);
                holdAt = holdPoint(landing);
                // Saved before air drag (3.5 and earlier): its launch rail exit velocity is for a parabola; drag would leave it short,
                // so solve again from saved position.
                if (!tag.contains("ground_wind") && t > 0) resolve(position(), impactAt);
            }
            // 2.3.x (lifetime, no mission key): on a distant arc the rocket is faster than cruise; remaining lifetime × cruise
            // would run out halfway (5 km: ≈3600 blocks of range for ≈5700 blocks of arc); use the arc itself.
            if (!tag.contains("mission")) setMission(missionLeft());
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("elevation", elevation);
        Nbt.putVec(tag, "spread", spread);
        tag.putBoolean("ground_wind", groundWindOnly);
        if (impactAt != null) Nbt.putVec(tag, "impact", impactAt);
        if (start != null) {
            Nbt.putVec(tag, "start", start);
            Nbt.putVec(tag, "v0", v0);
            tag.putInt("flight_ticks", flightTicks);
            tag.putDouble("t", t);
        }
    }
}
