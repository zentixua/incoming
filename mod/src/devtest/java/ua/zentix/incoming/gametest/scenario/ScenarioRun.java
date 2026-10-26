package ua.zentix.incoming.gametest.scenario;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.LoiterEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.entity.flight.ProximityFuse;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.gametest.TicketProbe;
import ua.zentix.incoming.guidance.BombDrop;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.stress.StressDirector;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.target.TargetTracker;
import ua.zentix.incoming.util.Local;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One flight scenario from site to property check. The projectile is launched directly ({@code launch},
 * {@code placeOnLauncher}, {@code setRoute}) with the geometry of a combat launch ({@code StrikeService}), but everything is random -
 *  from the seed script: for launch from the remote control, the bypass side, the MLRS spread and the bomb point are selected {@code level.random}.
 * <p>
 * Properties that the script checks for any flight:
 * <ul>
 *     <li>flight ends within the plan × 1,5 with a margin to chase ({@link #checkDuration});</li>
 *     <li>no circling: outside the barrage circle, the guided projectile within its turning radius from the target point turns
 *         in total no more than {@link #MAX_TURN}° ({@link Track#turn});</li>
 *     <li>a stationary target is hit within the fuse range ({@link #checkHit});</li>
 *     <li> along the operator’s route the projectile passes its points in order ({@link #checkWaypoints});</li>
 *     <li>after the end of the flight, neither the ticket with the UUID projectile, nor the entries in {@link VirtualFlights};</li>
 *     <li> the mod did not read unready chunks ({@link SyncLoadWatch});</li>
 *     <li>work budget per tick (root 1): the hit streak is not more than the total period and one, with blackout - units
 *          per strip; the hit queue empties; everything chosen by the shell explosions is demolished ({@link #checkWork}). Clock
 *         general for the server: the excess is counted to all scenarios whose flight was on that tick;</li>
 *     <li>flight coincides with the reference ({@link Baseline}), if the scenario it.</li>
 * </ul>
 */
final class ScenarioRun {
    /** More than this for a flight within a turning radius from the target - circling (two full circles). */
    static final double MAX_TURN = 720;
    /** Stock by the deadline, tick: after the plan × 1,5, the mod’s projectile still lives 600 tick - the one circling will select them. */
    private static final int DURATION_SLACK = 200;
    /** How long to wait to build craft Sable. */
    private static final int CRAFT_WAIT = 100;
    /** How long to wait after the end of the flight until the areas are released (the queue of areas ticks at the end of the tick world). */
    private static final int SETTLE_TICKS = 5;
    /** How long after the end of the flight should we wait until the work of hits demolishes everything selected by the projectile explosions, tick. */
    private static final int IMPACT_LIMIT = 600;
    /** The amplitude of the mob's movement along the rail, blocks. */
    private static final double RAIL_HALF = 20;
    /**
     * Retargeting to the side ({@code BESIDE}): when there are so many blocks to the target, the new point is so much to the side and in front of the
     *  projectile. Inside the missile's turning circle (≈ 400 blocks), beyond its attack cancellation threshold (64 blocks).
     */
    private static final double BESIDE_RANGE = 120, BESIDE_ACROSS = 80, BESIDE_AHEAD = 20;

    private final GameTestHelper h;
    private final ServerLevel level;
    private final Scenario s;
    private final Scenario.Params p;
    private final ScenarioSite site;
    private final UUID owner;
    private final UUID projectileId;

    // goal
    @Nullable
    private Entity targetEntity;
    private Vec3 targetBase;
    @Nullable
    private Target target;
    private Vec3 lastTargetPoint;
    /** The point at which the projectile is aimed at launch. */
    private Vec3 aim;
    private double targetTravel;
    /** The point of a stationary target where the projectile should hit (null - the target is moving or has disappeared). */
    @Nullable
    private Vec3 stationaryAim;
    /** Firing range MLRS, blocks: hit tolerance - with its dispersion ({@link WeaponSpec.Ballistic#sigma}). */
    private double rocketRange;
    private double retargetShift;
    /** The operator’s route ({@code WAYPOINTS}) and how many of its points the projectile has already passed. */
    private Waypoints via = Waypoints.NONE;
    private int viaPassed;

    // scenario progress
    private int tick;
    private int craftSince = -1;
    private int launchTick = -1;
    private long launchGameTime;
    private int eta0;
    private int disturbAt = -1;
    private boolean disturbed;
    private int endTick = -1;
    private boolean checked;

    // observation
    private final Map<UUID, Track> tracks = new LinkedHashMap<>();
    private final FlightTrace trace = new FlightTrace();
    @Nullable
    private Outcome outcome;
    private final Consumer<ExplosionEvent.Start> onBlast = this::onBlast;
    private final Consumer<ExplosionEvent.Detonate> onDetonate = this::onDetonate;
    /**
     * What the projectile explosions chose (world blocks nearby): location → block at the moment {@code Detonate}. What was demolished leaves here by
     *  update of neighbors ({@code NeighborNotifyEvent} with air or fire in place): the crater is then filled with debris
     *  and collapse, and the final state of the place says nothing about whether the explosion demolished it.
     */
    private final Map<BlockPos, Block> blasted = new HashMap<>();
    /** How many blocks did the explosions select (for the total row). */
    private int blastedTotal;
    private final Consumer<BlockEvent.NeighborNotifyEvent> onBlockUpdate = this::onBlockUpdate;
    /**
     * Whether the script shell ({@code ExplosionEvent.Start} with its UUID) exploded. The bomb B-2 has only the charge itself: the explosion of the entry
     *  into the ground ({@code Warheads.bunkerEntry}, force {@link Warheads#BUNKER_ENTRY_POWER}) is not considered an explosion.
     */
    private boolean detonated;
    /** server tick at launch and at the end of the flight (budget limit, hit queue). */
    private int launchServerTick, endServerTick;

    /** Result flight: how it ended, at what tick from launch and where. */
    private record Outcome(String end, int tick, Vec3 at, WeaponType weapon) {}

    /** Script projectile (or bomb B-2) spotted in flight. */
    private static final class Track {
        final int order;
        final WeaponType weapon;
        /** spec craft: rotation limits. */
        final WeaponSpec.Airframe airframe;
        /** Concrete bomb B-2 (not B-2 itself). */
        final boolean bomb;
        Vec3 last;
        double lastStep;
        double heading = Double.NaN;
        @Nullable
        Vec3 aim;
        /** Course rotation within the turning radius from a fixed target point outside the barrage circle, °. */
        double turn;

        Track(int order, StrikeProjectile p) {
            this.order = order;
            this.weapon = p.weapon();
            this.bomb = p instanceof BunkerBusterEntity;
            this.airframe = p.airframe();
            this.last = p.position();
        }
    }

    ScenarioRun(GameTestHelper h, Scenario s) {
        this.h = h;
        this.level = h.getLevel();
        this.s = s;
        this.p = s.params();
        this.site = new ScenarioSite(level, s.cell());
        this.owner = new UUID(0x5CE7A41000000000L, s.cell());
        this.projectileId = new UUID(0x5CE7A41000000001L, s.cell());
        this.targetBase = site.origin;
    }

    void start() {
        SyncLoadWatch.ensureRegistered();
        if (ScenarioMode.REAL_CHUNKS) ScenarioMode.paceServer();
        else InstantChunks.acquire();
        NeoForge.EVENT_BUS.addListener(onBlast);
        NeoForge.EVENT_BUS.addListener(onDetonate);
        NeoForge.EVENT_BUS.addListener(onBlockUpdate);
        WorkBudgetWatch.acquire(level.getServer());
        if (!Baseline.recording() && !ScenarioMode.REAL_CHUNKS) ChunkChurn.acquire();
        StrikeGameTests.afterTest(h, this::cleanup);
        h.onEachTick(this::tick);
        h.succeedWhen(() -> h.assertTrue(checked, "the flight is still underway: " + status() + " — " + ScenarioMode.reproduce(s)));
    }

    // ---------------------------------------------------------------- progress

    private void tick() {
        tick++;
        if (tick == 1) setUp();
        if (launchTick < 0) {
            if (!ready()) return;
            launch();
            return;
        }
        moveTarget();
        if (!disturbed && s.disturbance().inFlight() && (s.disturbance() == Scenario.Disturbance.BESIDE ? onApproach() : tick >= disturbAt)) disturb();
        observe();
        if (endTick >= 0 && !checked && tick >= endTick + SETTLE_TICKS && (impactsDone() || tick >= endTick + IMPACT_LIMIT)) {
            check();
            checked = true;
        }
    }

    private void setUp() {
        Vec3 o = site.origin;
        site.zone(o, ScenarioSite.TARGET_ZONE);
        if (s.launch().rail) site.zone(launchSite(), ScenarioSite.LAUNCH_ZONE);
        if (s.disturbance().inFlight() && s.disturbance() != Scenario.Disturbance.TARGET_GONE && s.disturbance() != Scenario.Disturbance.BESIDE) {
            site.zone(shifted(), ScenarioSite.TARGET_ZONE);
        }
        if (s.disturbance() == Scenario.Disturbance.HILL) site.corridor(o, o.subtract(p.approach().scale(p.place() + p.size() * 1.5 + 64)));
        site.load();
        if (s.disturbance() == Scenario.Disturbance.HILL) site.hill(p.approach(), p.place(), p.size());
        if (s.disturbance() == Scenario.Disturbance.QUARRY) site.quarry(p.place(), p.size());
        switch (s.target()) {
            case POINT -> target = new Target.Point(o);
            case MOB -> targetEntity = spawn(EntityType.PIG, o);
            case PLAYER -> targetEntity = spawn(EntityType.ARMOR_STAND, playerAt(0));
            case CRAFT -> assembleCraft(o);
        }
        if (targetEntity != null) target = Target.OfEntity.center(targetEntity);
    }

    private Entity spawn(EntityType<?> type, Vec3 at) {
        Entity e = type.create(level);
        if (e == null) throw new GameTestAssertException("the goal was not created " + type);
        e.moveTo(at.x, at.y, at.z, 0, 0);
        e.setNoGravity(true);
        if (e instanceof Mob m) m.setNoAi(true);
        level.addFreshEntity(e);
        return e;
    }

    /** Flooring 3×3 from boards on the ground at the target - craft Sable after assembly. */
    private void assembleCraft(Vec3 o) {
        BlockPos c = BlockPos.containing(o);
        BlockPos a = c.offset(-1, 0, -1), b = c.offset(1, 0, 1);
        BlockPos.betweenClosed(a, b).forEach(q -> level.setBlockAndUpdate(q, Blocks.OAK_PLANKS.defaultBlockState()));
        MinecraftServer server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
        craftSince = tick;
    }

    /** The areas are ticking, the craft (if it is the target) is collected: you can start. */
    private boolean ready() {
        if (s.target() == Scenario.TargetKind.CRAFT && target == null) {
            Vec3 centre = site.origin.add(0, 0.5, 0);
            List<SubLevelAccess> near = SubLevels.near(level, centre, 8);
            if (near.isEmpty()) {
                if (tick - craftSince > CRAFT_WAIT) throw new GameTestAssertException("craft didn't get together " + CRAFT_WAIT + " tick");
                return false;
            }
            target = new Target.OfSubLevel(SubLevels.toPlot(near.getFirst(), centre));
        }
        return site.ticking();
    }

    // ---------------------------------------------------------------- launch

    private Vec3 targetPoint() {
        return target.resolve(level).orElse(site.origin);
    }

    /** Launcher's place: opposite approach at the launch range, on the terrain (the hill near the launcher is its place on it). */
    private Vec3 launchSite() {
        Vec3 at = site.origin.subtract(p.approach().scale(p.standoff()));
        return site.ground(at.x, at.z, false);
    }

    /** Where retargeting or teleporting takes the target. */
    private Vec3 shifted() {
        Vec3 at = targetBase.add(p.shiftDir().scale(p.shift()));
        return site.ground(at.x, at.z, false);
    }

    private void launch() {
        Vec3 point = targetPoint();
        lastTargetPoint = point;
        aim = point;
        via = new Waypoints(p.waypoints().stream().map(targetBase::add).toList());
        StrikeProjectile proj = switch (s.launch()) {
            case DRONE_FAR, MISSILE_FAR -> fromAfar(point);
            case DRONE_RAIL, MISSILE_RAIL -> guidedFromRail(point);
            case LOITER_FAR -> loiterFromAfar(point);
            case LOITER_RAIL -> loiterFromRail(point);
            case ROCKET_FAR -> rocketFromAfar(point);
            case ROCKET_TUBE -> rocketFromTube(point);
            case BOMBER -> bomber(point);
            case ICBM -> icbm(point);
        };
        eta0 = proj.etaTicks();
        // where the projectile aims itself: for MLRS - a point with dispersion, for B-2 - a surface (synchronous target point - float)
        if (s.target() == Scenario.TargetKind.POINT && s.launch() != Scenario.Launch.ICBM) stationaryAim = aim;
        launchTick = tick;
        launchGameTime = level.getGameTime();
        launchServerTick = level.getServer().getTickCount();
        disturbAt = tick + (int) Math.round(p.when() * eta0);
    }

    /** Projectile before launch: your seed and UUID from the script. */
    private <T extends StrikeProjectile> T create(EntityType<T> type) {
        T e = type.create(level);
        if (e == null) throw new GameTestAssertException("the projectile was not created " + type);
        e.setUUID(projectileId);
        e.seed(p.random());
        return e;
    }

    private StrikeProjectile guided() {
        return create(s.launch().weapon.spec().airframe().entity().get());
    }

    private double pathLength() {
        return s.launch().weapon.spec().airframe().cruiseSpeed() * p.flightTime() * 20;
    }

    /**
     * Like {@code StrikeService.fromAfar}: start on the approach line, at the flight length from the target, out of the world; along the operator’s route
     *  - before his first point on the continuation of the first section.
     */
    private StrikeProjectile fromAfar(Vec3 point) {
        StrikeProjectile e = guided();
        if (!via.isEmpty()) {
            WeaponSpec spec = s.launch().weapon.spec();
            Vec3 start = via.afar(point, spec.finalLeg()).add(0, point.y + spec.airframe().cruiseHeight(), 0);
            e.launch(start, target, point, owner);
            e.setRoute(via.route(start));
            VirtualFlights.launch(level, e);
            return e;
        }
        double length = Math.max(pathLength(), p.entry());
        Vec3 start = point.subtract(p.approach().scale(length)).add(0, s.launch().weapon.spec().airframe().cruiseHeight(), 0);
        e.launch(start, target, point, owner);
        e.setRoute(Route.plan(start, point, p.approach(), length, p.entry(), p.side()));
        VirtualFlights.launch(level, e);
        return e;
    }

    /**
     * Like {@code StrikeService.fromLauncher}: the packet looks at the first waypoint; along the operator's route - gate
     *  cocking on the course to his first point, then his point.
     */
    private StrikeProjectile guidedFromRail(Vec3 point) {
        StrikeProjectile e = guided();
        Vec3 rail = rail();
        if (!via.isEmpty()) {
            float yaw = FlightController.anglesTo(rail, via.points().getFirst())[0];
            Vec3 gate = rail.add(Local.horizontal(yaw).scale(ProximityFuse.ARM_DISTANCE));
            e.placeOnLauncher(rail, yaw, LauncherEntity.elevation(s.launch().weapon), p.readyTicks(), 0, target, point, owner);
            e.setRoute(via.route(gate).after(gate, rail));
            add(e);
            return e;
        }
        Route plan = Route.plan(rail, point, p.approach(), pathLength(), p.entry(), p.side());
        Vec3 first = plan.current() == null ? point : plan.current();
        e.placeOnLauncher(rail, FlightController.anglesTo(rail, first)[0], LauncherEntity.elevation(s.launch().weapon), p.readyTicks(), 0, target, point, owner);
        e.setRoute(Route.plan(rail, point, p.approach(), pathLength(), p.entry(), p.side()));
        add(e);
        return e;
    }

    private StrikeProjectile loiterFromAfar(Vec3 point) {
        LoiterEntity e = create(ModEntities.LOITER.get());
        Vec3 from = via.isEmpty() ? point.subtract(p.approach().scale(p.standoff()))
                : via.afar(point, WeaponSpec.LOITER.standoff()).add(0, point.y, 0);
        from = from.add(0, WeaponSpec.LOITER.airframe().cruiseHeight(), 0);
        e.launch(from, target, point, owner);
        e.setRoute(via.isEmpty() ? null : via.route(from));
        VirtualFlights.launch(level, e);
        return e;
    }

    private StrikeProjectile loiterFromRail(Vec3 point) {
        LoiterEntity e = create(ModEntities.LOITER.get());
        Vec3 rail = rail();
        e.placeOnLauncher(rail, FlightController.anglesTo(rail, via.isEmpty() ? point : via.points().getFirst())[0],
                LauncherEntity.elevation(WeaponType.LOITER), p.readyTicks(), 0, target, point, owner);
        e.setRoute(via.isEmpty() ? null : via.route(rail));
        add(e);
        return e;
    }

    /**
     * The MLRS impact point is the point itself, like {@code StrikeService}: dispersion - projectile firing data
     *  ({@link RocketEntity#fireData}: launch rail exit dispersion from the seed script {@code p.scatter()} and the wind, which the calculation knows
     *  only near the ground); range - for hit tolerance ({@link #checkHit}).
     */
    private Vec3 scatter(RocketEntity r, Vec3 from, Vec3 point) {
        r.fireData(new Vec3(p.scatter().x, p.scatter().z, 0), true);
        rocketRange = Math.sqrt(from.distanceToSqr(point.x, from.y, point.z));
        aim = point;
        return aim;
    }

    private StrikeProjectile rocketFromAfar(Vec3 point) {
        RocketEntity r = create(ModEntities.ROCKET.get());
        Vec3 from = point.subtract(p.approach().scale(p.standoff()));
        r.launchFrom(from, target, scatter(r, from, point), owner);
        VirtualFlights.launch(level, r);
        return r;
    }

    private StrikeProjectile rocketFromTube(Vec3 point) {
        RocketEntity r = create(ModEntities.ROCKET.get());
        Vec3 rail = rail();
        r.placeInTube(rail, FlightController.anglesTo(rail, point)[0], LauncherEntity.elevation(WeaponType.ROCKET), p.readyTicks(), 0, target,
                scatter(r, rail, point), owner);
        add(r);
        return r;
    }

    /** Like {@code StrikeService.launchBomber}: a point on the surface (height map of the finished chunk), without scatter. */
    private StrikeProjectile bomber(Vec3 point) {
        BomberEntity e = create(ModEntities.BOMBER.get());
        Vec3 surface = new Vec3(point.x, point.y - 0.5, point.z);
        aim = surface;
        double length = WeaponSpec.BUNKER.airframe().cruiseSpeed() * p.flightTime() * 20 + BomberEntity.releaseLine(WeaponSpec.BUNKER.airframe().cruiseHeight());
        e.launch(surface.subtract(p.approach().scale(length)), surface, null, owner);
        e.setRoute(null);
        VirtualFlights.launch(level, e);
        return e;
    }

    /** ICBM starts from the table at the launcher (it is no longer she who strikes the target). */
    private StrikeProjectile icbm(Vec3 point) {
        IcbmEntity e = create(ModEntities.ICBM.get());
        e.prepare(launchSite(), point, owner);
        add(e);
        return e;
    }

    /** Launch rail exit point with rail: above the ground of the launcher location. */
    private Vec3 rail() {
        return launchSite().add(0, 2, 0);
    }

    private void add(StrikeProjectile e) {
        if (!level.addFreshEntity(e)) throw new GameTestAssertException("the projectile was not added to the world " + e.blockPosition());
    }

    // ---------------------------------------------------------------- purpose and disturbances

    /** The “player” point on the circle through {@code t} tick. */
    private Vec3 playerAt(int t) {
        double a = t * p.mobSpeed() / p.orbit();
        return targetBase.add(Math.cos(a) * p.orbit(), p.altitude(), Math.sin(a) * p.orbit());
    }

    private void moveTarget() {
        if (targetEntity != null && !targetEntity.isRemoved()) {
            int t = tick - launchTick;
            Vec3 at = switch (s.target()) {
                case MOB -> {
                    // back and forth across approach
                    double x = (t * p.mobSpeed()) % (RAIL_HALF * 4);
                    double off = x < RAIL_HALF * 2 ? x - RAIL_HALF : RAIL_HALF * 3 - x;
                    Vec3 across = new Vec3(-p.approach().z, 0, p.approach().x);
                    Vec3 q = targetBase.add(across.scale(off));
                    yield site.ground(q.x, q.z, false);
                }
                case PLAYER -> playerAt(t);
                default -> targetEntity.position();
            };
            targetEntity.setPos(at.x, at.y, at.z);
        }
        if (target != null && !(target instanceof Target.Point)) {
            target.resolve(level).ifPresent(now -> {
                targetTravel += now.distanceTo(lastTargetPoint);
                lastTargetPoint = now;
            });
        }
    }

    private void disturb() {
        disturbed = true;
        switch (s.disturbance()) {
            case TARGET_GONE -> {
                if (targetEntity != null) targetEntity.discard();
            }
            case TELEPORT_NEAR, TELEPORT_FAR -> {
                if (targetEntity == null) return;
                targetBase = shifted();
                Vec3 to = s.target() == Scenario.TargetKind.PLAYER ? playerAt(tick - launchTick) : targetBase;
                targetEntity.teleportTo(to.x, to.y, to.z);
            }
            case RETARGET -> {
                Vec3 to = shifted();
                if (s.launch().weapon == WeaponType.BUNKER) to = to.add(0, -0.5, 0);
                StrikeProjectile live = main();
                if (live != null && live.retarget(new Target.Point(to), to)) {
                    stationaryAim = to;
                    retargetShift = p.shift();
                }
            }
            case BESIDE -> {
                StrikeProjectile live = main();
                if (live == null) return;
                Vec3 at = live.position();
                Vec3 ahead = new Vec3(site.origin.x - at.x, 0, site.origin.z - at.z).normalize();
                Vec3 across = new Vec3(-ahead.z, 0, ahead.x).scale(p.side());
                Vec3 q = at.add(across.scale(BESIDE_ACROSS)).add(ahead.scale(BESIDE_AHEAD));
                Vec3 to = site.ground(q.x, q.z, false);
                site.zoneNow(to, ScenarioSite.TARGET_ZONE);
                if (live.retarget(new Target.Point(to), to)) {
                    stationaryAim = to;
                    retargetShift = to.distanceTo(site.origin);
                }
            }
            default -> {}
        }
    }

    /** Projectile on approach: horizontally to the target no more than {@link #BESIDE_RANGE} (and not in the barrage circle). */
    private boolean onApproach() {
        StrikeProjectile live = main();
        return live != null && live.flightPhase() != FlightPhase.LOITER
                && live.position().subtract(site.origin).horizontalDistance() <= BESIDE_RANGE;
    }

    /** A projectile fired by a script (in-world or out-of-world), or null. */
    @Nullable
    private StrikeProjectile main() {
        if (level.getEntity(projectileId) instanceof StrikeProjectile e && !e.isRemoved()) return e;
        for (StrikeProjectile e : VirtualFlights.get(level).flights()) if (e.getUUID().equals(projectileId)) return e;
        return null;
    }

    // ---------------------------------------------------------------- observation

    private List<StrikeProjectile> live() {
        List<StrikeProjectile> out = new ArrayList<>(level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class),
                e -> !e.isRemoved() && owner.equals(e.ownerId())));
        for (StrikeProjectile e : VirtualFlights.get(level).flights()) if (owner.equals(e.ownerId())) out.add(e);
        return out;
    }

    private void observe() {
        int t = tick - launchTick;
        List<StrikeProjectile> now = live();
        // new ones - in order of first appearance (UUID bombs are random, do not sort by it)
        now.stream().filter(e -> !tracks.containsKey(e.getUUID()))
                .sorted(Comparator.comparing((StrikeProjectile e) -> e.getClass().getName()))
                .forEach(e -> tracks.put(e.getUUID(), new Track(tracks.size(), e)));
        now.sort(Comparator.comparingInt(e -> tracks.get(e.getUUID()).order));
        for (StrikeProjectile e : now) {
            Track tr = tracks.get(e.getUUID());
            Vec3 pos = e.position();
            Vec3 step = pos.subtract(tr.last);
            tr.lastStep = step.length();
            accumulateTurn(tr, e, step);
            if (e.getUUID().equals(projectileId)) passWaypoints(e, tr, pos);
            tr.last = pos;
            if (outcome == null) trace.add(t, tr.order, e.flightPhase().ordinal(), e.isVirtual(), pos.subtract(site.origin));
            if (outcome == null && e instanceof BunkerBusterEntity bomb && bomb.isDrilling()) {
                outcome = new Outcome("entry", t, bomb.entry(), WeaponType.BUNKER);
            }
        }
        if (endTick < 0 && now.isEmpty() && !tracks.isEmpty()) {
            endTick = tick;
            endServerTick = level.getServer().getTickCount();
            if (outcome == null) {
                // while “disappeared”: the hit explosion begins in the hit job (WorkScheduler), at the end of the server tick
                // , or later, when it comes to his turn - then the result will be an explosion with the same tick
                Track last = tracks.values().stream().reduce((a, b) -> b).orElseThrow();
                outcome = new Outcome("gone", t, last.last, last.weapon);
            }
        }
    }

    /**
     * The operator's route point has been passed when the projectile has approached it horizontally no further than two spec grabs
     *  and a step: this is what {@code Route.update} leaves for the projectile that has passed the point with a large turning circle.
     *  Count only in order: approaching the second before the first means not passing it.
     */
    private void passWaypoints(StrikeProjectile e, Track tr, Vec3 pos) {
        if (viaPassed >= via.size()) return;
        Vec3 wp = via.points().get(viaPassed);
        double near = 2 * e.weapon().spec().capture() + tr.lastStep + 1;
        if (Math.hypot(pos.x - wp.x, pos.z - wp.z) <= near) viaPassed++;
    }

    /**
     * Turning the course in steps - only in a controlled manner, outside the barrage circle, within the turning radius from the target point and while it is standing
     *  in place: chasing a target that itself is walking in a circle - also circles, but not circling at the point (the deadline catches it).
     */
    private void accumulateTurn(Track tr, StrikeProjectile e, Vec3 step) {
        if (tr.airframe.turnRate() <= 0) return;
        Vec3 aim = e.aimPoint();
        boolean aimMoved = tr.aim == null || aim.distanceToSqr(tr.aim) > 0.01;
        tr.aim = aim;
        double horizontal = step.horizontalDistance();
        if (horizontal < 0.05) return;
        double heading = Math.toDegrees(Math.atan2(step.z, step.x));
        double prev = tr.heading;
        tr.heading = heading;
        if (Double.isNaN(prev) || aimMoved || e.flightPhase() == FlightPhase.LOITER) return;
        // when climbing, the turn is slower - the circle is wider (like the projectile itself, StrikeProjectile.insideTurn)
        double rate = e.flightPhase() == FlightPhase.CLIMB ? tr.airframe.climbTurnRate() : tr.airframe.turnRate();
        double radius = horizontal / Math.toRadians(rate) * 2;
        if (e.position().subtract(aim).horizontalDistance() > radius) return;
        tr.turn += Math.abs(Mth.wrapDegrees(heading - prev));
    }

    /** What the scenario projectile explosion chose (any: explosion, fireball, secondary) - world blocks nearby, not craft. */
    private void onDetonate(ExplosionEvent.Detonate e) {
        if (e.getLevel() != level) return;
        UUID by = StressDirector.blastBy(e.getExplosion());
        // collision before arming (crash) - explosion without destroying blocks: it has the list Detonate, but vanilla does not demolish it
        if (by == null || !tracks.containsKey(by) || !e.getExplosion().interactsWithBlocks()) return;
        Vec3 c = e.getExplosion().center();
        double reach = e.getExplosion().radius() * 2 + 2;
        for (BlockPos q : e.getAffectedBlocks()) {
            // craft blocks Sable live in the plot, far from the explosion site: their fate is the business of Sable
            BlockState state = level.getBlockState(q);
            if (!state.isAir() && Vec3.atCenterOf(q).distanceTo(c) <= reach && blasted.putIfAbsent(q.immutable(), state.getBlock()) == null) blastedTotal++;
        }
    }

    /** The location chosen by the explosion was demolished (air or fire): cross out. */
    private void onBlockUpdate(BlockEvent.NeighborNotifyEvent e) {
        if (e.getLevel() == level && !blasted.isEmpty() && gone(e.getState())) blasted.remove(e.getPos());
    }

    /**
     * A selected and not demolished place on which the same block stands as at the time of selection. {@code StagedExplosion} does not touch the replaced block (the debris of
     *  or the crumbling lay down before the portion of the explosion reached the site) - this is not a mistake.
     */
    private boolean untouched(Map.Entry<BlockPos, Block> q) {
        return level.getBlockState(q.getKey()).is(q.getValue());
    }

    /** The work of hits had arrived: everything selected by explosions was demolished or replaced, and the line of hits from the end of the flight was empty. */
    private boolean impactsDone() {
        // B-2: result - bomb entry, detonation occurs later; wait for it (or the deadline, then checkHit will fall)
        return (detonated || s.launch().weapon != WeaponType.BUNKER) && WorkBudgetWatch.emptiedSince(endServerTick)
                && blasted.entrySet().stream().noneMatch(this::untouched);
    }

    private static boolean gone(BlockState state) {
        return state.isAir() || state.getBlock() instanceof BaseFireBlock;
    }

    /** The first explosion of a scenario shell is based on the source of damage (neighboring scenarios are far away, but someone else’s explosion will not be counted). */
    private void onBlast(ExplosionEvent.Start e) {
        if (e.getLevel() != level || launchTick < 0) return;
        UUID by = StressDirector.blastBy(e.getExplosion());
        Track tr = by == null ? null : tracks.get(by);
        // a bomb does not have an explosion when entering the ground: any explosion is an explosion
        if (tr != null) detonated = true;
        if (outcome != null && !outcome.end.equals("gone")) return;
        // tick total - hit tick (the projectile is gone), not the work queue: it depends on other people’s explosions in the same tick
        if (tr != null) outcome = new Outcome("blast", outcome != null ? outcome.tick : tick - launchTick, e.getExplosion().center(), tr.weapon);
    }

    // ---------------------------------------------------------------- checks

    private void check() {
        double turn = tracks.values().stream().mapToDouble(tr -> tr.turn).max().orElse(0);
        Incoming.LOG.info("SCENARIO flight {}: {} on tick {} (plan {}), at {}, turn at the target to {}°, miss {}, explosions selected {} blocks", s.id(),
                outcome.end, outcome.tick, eta0, rel(outcome.at), Math.round(turn),
                stationaryAim == null ? "—" : String.format(Locale.ROOT, "%.1f", outcome.at.distanceTo(stationaryAim)), blastedTotal);
        try {
            known(Scenario.Property.TURN, this::checkTurns);
            checkDuration();
            known(Scenario.Property.HIT, this::checkHit);
            checkWaypoints();
            checkReleased();
            checkWork();
            List<SyncLoadWatch.Violation> reads = SyncLoadWatch.since(launchGameTime);
            h.assertTrue(reads.isEmpty(), "synchronous loading of chunks due to the fault of the mod: " + reads);
            // with a real load, the flight depends on the generation speed on the machine: the reference is not about it
            if (s.baselined() && !ScenarioMode.REAL_CHUNKS) checkBaseline();
        } catch (GameTestAssertException e) {
            throw new GameTestAssertException(e.getMessage() + " — " + ScenarioMode.reproduce(s));
        }
    }

    /** A property that is violated by a known flaw in the mod ({@link Scenario#knownIssues}): violation - in the log, test further. */
    private void known(Scenario.Property property, Runnable check) {
        if (!s.knownIssues().contains(property)) {
            check.run();
            return;
        }
        try {
            check.run();
        } catch (GameTestAssertException e) {
            Incoming.LOG.warn("SCENARIO flight {}: known flaw ({}): {}", s.id(), property, e.getMessage());
        }
    }

    /** Deadline: plan × 1,5 plus pursuit of the target (as the projectile itself extends the lifespan) and barrage circle. */
    private void checkDuration() {
        double cruise = cruiseSpeed();
        double chase = Math.min(TargetTracker.CHASE_BUDGET, targetTravel + retargetShift);
        double loiter = s.launch().weapon == WeaponType.LOITER ? IncomingConfig.SERVER.loiterTime.get() * 20 * 1.2 + 25 : 0;
        double bound = 1.5 * (eta0 + chase / cruise + loiter) + DURATION_SLACK;
        h.assertTrue(outcome.tick <= bound, String.format(Locale.ROOT, "flight ended (%s) on tick %d, time limit %.0f (plan %d, pursuit of %.0f blocks)",
                outcome.end, outcome.tick, bound, eta0, chase));
    }

    /** Scenario weapon marching speed (spec). */
    private double cruiseSpeed() {
        return s.launch().weapon.spec().airframe().cruiseSpeed();
    }

    private void checkTurns() {
        for (Track tr : tracks.values()) {
            h.assertTrue(tr.turn <= MAX_TURN, String.format(Locale.ROOT, "circling: %s turned to %.0f° within the turning radius of the target",
                    tr.weapon, tr.turn));
        }
    }

    /** Stationary target: explosion (bomb - entry into the ground) no further than a step and fuse margin from the point. */
    private void checkHit() {
        if (stationaryAim == null) return;
        if (s.launch().weapon == WeaponType.BUNKER) {
            h.assertTrue(outcome.end.equals("entry") || outcome.end.equals("blast"), "the bomb did not reach the ground: " + outcome.end);
            // the reference and the result end with the input: the explosion after it is checked here (and its blocks - in checkWork)
            h.assertTrue(detonated, "the bomb entered the ground and did not explode for " + IMPACT_LIMIT + " tick");
            double miss = outcome.at.subtract(stationaryAim).horizontalDistance();
            h.assertTrue(miss <= BombDrop.REACH_PAD + 3, String.format(Locale.ROOT, "the bomb entered the ground %.1f blocks from the point", miss));
            return;
        }
        h.assertTrue(outcome.end.equals("blast"), "a stationary target is not hit: " + outcome.end + " at " + rel(outcome.at));
        double step = tracks.values().stream().mapToDouble(tr -> tr.lastStep).max().orElse(0);
        // fuse margin over pitch, as in advance (spec)
        double reach = step + s.launch().weapon.spec().airframe().reachPad() + 1;
        // uncontrolled: three more standard deviations of dispersion along each axis
        WeaponSpec.Ballistic b = s.launch().weapon.spec().airframe().ballistic();
        if (b != null) reach += 3 * Math.sqrt(2) * b.sigma(rocketRange);
        double miss = outcome.at.distanceTo(stationaryAim);
        h.assertTrue(miss <= reach, String.format(Locale.ROOT, "explosion in %.1f blocks from a stationary target (range %.1f) at %s",
                miss, reach, rel(outcome.at)));
    }

    private void checkWaypoints() {
        h.assertTrue(viaPassed == via.size(), "operator route: points passed " + viaPassed + " from " + via.size() + ", next "
                + (viaPassed < via.size() ? rel(via.points().get(viaPassed)) : "—"));
    }

    private void checkReleased() {
        for (UUID id : tracks.keySet()) {
            List<String> tickets = TicketProbe.types(level, id);
            h.assertTrue(tickets.isEmpty(), "after the flight of the projectile " + id + " ticket: " + tickets);
            h.assertTrue(VirtualFlights.get(level).flights().stream().noneMatch(e -> e.getUUID().equals(id)),
                    "after the flight the projectile " + id + " still in flight out of the world");
        }
    }

    /**
     * Work budget (root 1) on the counting clock ({@link WorkBudgetWatch}): from launch to check, not a single tick did the stripe of
     *  hits take more than the total period and one (with blackout - one per stripe); The hit queue was empty after
     *  flight; every block that was targeted by the projectile explosions is demolished (or burns).
     */
    private void checkWork() {
        List<WorkBudgetWatch.Violation> over = WorkBudgetWatch.since(launchServerTick);
        h.assertTrue(over.isEmpty(), "work budget per tick exceeded: " + over);
        h.assertTrue(WorkBudgetWatch.emptiedSince(endServerTick), "the hit queue was not empty in " + IMPACT_LIMIT + " tick after flight");
        List<String> left = blasted.entrySet().stream().filter(this::untouched).limit(5)
                .map(q -> q.getKey().toShortString() + " " + level.getBlockState(q.getKey())).toList();
        h.assertTrue(left.isEmpty(), "explosions were chosen, but not demolished (" + blastedTotal + " selected): " + left);
    }

    private void checkBaseline() {
        Baseline.Entry got = new Baseline.Entry(outcome.end, outcome.tick, round(outcome.at.x - site.origin.x),
                round(outcome.at.y - site.origin.y), round(outcome.at.z - site.origin.z), trace.hex());
        if (Baseline.recording()) {
            Baseline.record(s.id(), got);
            return;
        }
        Baseline.Entry expected = Baseline.expected(s.id());
        h.assertTrue(expected != null, "trajectoriesMatchBaseline: the scenario is not in the reference - write ./gradlew runScenarioRecord and explain in PR");
        h.assertTrue(expected.equals(got), "trajectoriesMatchBaseline: the flight diverged from the reference: it was " + expected + ", it became " + got);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private String rel(Vec3 at) {
        Vec3 r = at.subtract(site.origin);
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f) from target", r.x, r.y, r.z);
    }

    private String status() {
        if (launchTick < 0) return "waiting for the platform (tick " + tick + ")";
        StrikeProjectile m = main();
        String where = m == null ? "no shell" : m.flightPhase() + (m.isVirtual() ? " out of the world " : " ") + rel(m.position());
        return "tick " + (tick - launchTick) + " from launch, plan " + eta0 + ", " + where + (outcome == null ? "" : ", total " + outcome.end);
    }

    private void cleanup() {
        NeoForge.EVENT_BUS.unregister(onBlast);
        NeoForge.EVENT_BUS.unregister(onDetonate);
        NeoForge.EVENT_BUS.unregister(onBlockUpdate);
        WorkBudgetWatch.release(level.getServer());
        if (!ScenarioMode.REAL_CHUNKS) InstantChunks.release();
        if (!Baseline.recording() && !ScenarioMode.REAL_CHUNKS) ChunkChurn.release(level);
        VirtualFlights.get(level).clear(level, e -> owner.equals(e.ownerId()));
        for (StrikeProjectile e : live()) e.discard();
        if (targetEntity != null) targetEntity.discard();
        site.release();
    }
}
