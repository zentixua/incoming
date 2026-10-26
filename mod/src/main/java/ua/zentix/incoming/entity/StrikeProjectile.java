package ua.zentix.incoming.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.entity.flight.ChunkHold;
import ua.zentix.incoming.entity.flight.ProximityFuse;
import ua.zentix.incoming.entity.flight.TargetAreaHold;
import ua.zentix.incoming.guidance.AltitudeHold;
import ua.zentix.incoming.guidance.Autopilot;
import ua.zentix.incoming.guidance.Bearing;
import ua.zentix.incoming.guidance.Craft;
import ua.zentix.incoming.util.Relief;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.guidance.Mission;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.util.TerrainSurvey;
import ua.zentix.incoming.strike.CameraLink;
import ua.zentix.incoming.strike.ChunkTickets;
import ua.zentix.incoming.strike.FlightLog;
import ua.zentix.incoming.strike.FlightTickets;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.StrikeService;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.target.TargetTracker;
import ua.zentix.incoming.util.Nbt;
import ua.zentix.incoming.warhead.DebrisSpawner;
import ua.zentix.incoming.warhead.Warheads;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared projectile base: orientation/turning ({@link FlightController}), flight phase ({@link FlightPhase}),
 * route ({@link Route}), range reserve ({@link Mission}), target tracking, collision path sweep (blocks,
 * Sable craft, people near trajectory — {@link ProximityFuse}), forward chunk tickets ({@link ChunkHold}) and target
 * area ({@link TargetAreaHold}), synchronization, persistence. Guided flight laws live in {@code guidance}: entity
 * exposes itself as {@link Craft}.
 * <p>
 * Flight outside loaded world: projectile leaving ticking chunks does not freeze at edge, but continues
 * “virtually” ({@link VirtualFlights}): same guidance without block reads, reappearing in-world as
 * soon as it enters ticking chunks. Target area preloads in background ({@link FlightTickets}).
 * <p>
 * One projectile's tick error cannot crash server: remove projectile and log stack.
 */
public abstract class StrikeProjectile extends Entity implements IEntityWithComplexSpawn {
    private static final EntityDataAccessor<Float> DATA_ROLL = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SPEED = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.FLOAT);
    /**
     * Last server-tick displacement ({@link #velocity()}). Vanilla entity velocity packet cannot carry it: components
     * capped at 3.9 blocks/tick, while B-2 flies at 12 and ICBM up to 25.
     */
    private static final EntityDataAccessor<Vector3f> DATA_VELOCITY = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Byte> DATA_PHASE = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Vector3f> DATA_AIM = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Boolean> DATA_NUCLEAR = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.BOOLEAN);
    /** First launcher ticks when projectile is invisible (rack still raising — projectile inside slot). */
    private static final EntityDataAccessor<Integer> DATA_HIDDEN = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.INT);
    /** Whether operator camera tracks a sighted target ({@link Pursuit}), for camera presentation. */
    private static final EntityDataAccessor<Byte> DATA_PURSUIT = SynchedEntityData.defineId(StrikeProjectile.class, EntityDataSerializers.BYTE);

    /**
     * Camera projectile ({@link WeaponSpec.Tracking#CAMERA}) pursuing sighted target ({@link Target.Sighted}):
     * shown by operator's projectile camera.
     */
    public enum Pursuit {
        /** No sighted target, lost, or weapon does not pursue it. */
        NONE,
        /** Operator holds target in frame: projectile follows it. */
        HELD,
        /** Target outside frame: projectile flies toward last seen position. */
        WAITING
    }

    /**
     * Target area failed to load within a minute: remove projectile — server cannot handle generation (dozens of
     * 9×9-chunk areas for a spread salvo) or target unreachable.
     */
    private static final int AREA_WAIT_LIMIT = 1200;
    /** Outside-world projectile below surface keeps flying toward target until this many blocks below it (see groundCrossing). */
    private static final double BELOW_AIM = 16;
    /** Preload target area this many flight ticks away (and at least 400 blocks). */
    private static final int PRELOAD_TICKS = 300;
    /**
     * Target area ticks this many ticks before impact ({@link #etaTicks}): outside-world projectile has time to reenter
     * at target, MLRS rocket avoids trajectory stretching ({@code WeaponSpec.RocketFlight.stretchTicks()} to its waiting line).
     */
    public static final int AREA_TICK_LEAD = 160;

    protected final FlightController flight = new FlightController(0, 0);
    protected double speed;
    @Nullable
    protected TargetTracker tracker;
    protected int age;
    /** Remaining damage capacity; -1 — unset (use {@link #maxHealth()}). */
    protected float health = -1;
    /**
     * Air pinned by scenario seed ({@link #seed}), and its game tick (saved: leaving for outside-world flight creates
     * a new entity from NBT); null — world air.
     */
    @Nullable
    private Air pinnedAir;
    private long pinnedAirTime;
    /** Altitude hold: smoothed altitude setpoint. */
    protected final AltitudeHold altitude = new AltitudeHold();
    /** Projectile as seen by autopilot ({@code guidance}): sensors and controls. */
    protected final Craft craft = new Craft() {
        @Override
        public Vec3 position() {
            return StrikeProjectile.this.position();
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
        public void setSpeed(double v) {
            speed = v;
        }

        @Override
        public FlightPhase phase() {
            return flightPhase();
        }

        @Override
        public int phaseAge() {
            return StrikeProjectile.this.phaseAge();
        }

        @Override
        public void setPhase(FlightPhase phase) {
            StrikeProjectile.this.setPhase(phase);
        }

        @Override
        public double relief(int x, int z) {
            return StrikeProjectile.this.relief(level(), x, z);
        }

        @Override
        public Relief reliefSurvey() {
            return Terrain.relief(level(), virtual ? Terrain.Allowed.VIRTUAL_RELIEF : Terrain.Allowed.RELIEF);
        }

        @Override
        public double clearAlong(Vec3 to, double margin) {
            return StrikeProjectile.this.clearAlong(level(), to, margin);
        }
    };
    /** Route to entry point; null — straight to target. */
    @Nullable
    protected Route route;
    /** Launch origin (fuze arming). */
    @Nullable
    protected Vec3 launchPos;
    /** Range reserve: flight plan with margin; null — spec ({@link WeaponSpec.Airframe#range}) until plan set. */
    @Nullable
    private Mission mission;
    /** Nuclear warhead replacing conventional one (cruise missile, bomb). */
    @Nullable
    protected Loadout.Nuke nuclear;

    private int phaseStart;
    /** Client: phase whose change established {@link #phaseStart}. */
    private byte clientPhase = -1;
    /** Launcher wait before ignition. */
    private int readyTicks;
    /** Sound target siren this many ticks before impact; -1 — no siren. */
    private int sirenLead = -1;
    /** Flying outside loaded world (see {@link VirtualFlights}). */
    private boolean virtual;
    /** Outside-world target-area loading wait, ticks; limit: {@link #AREA_WAIT_LIMIT}. */
    private int areaWait;
    /** Outside-world projectile reached target with ticking area: reenter here without forward margin (unsaved). */
    private boolean arrived;
    /**
     * Outside-world path met surface (no collisions there): wait for location loading, reenter world and hit
     * on first tick via ordinary {@link #impact}. Null — path did not meet surface.
     */
    @Nullable
    private Vec3 grounded;
    /** Mission target area and approach lane: transfer to same-UUID copy; unsaved, like tickets. */
    private TargetAreaHold aimArea = new TargetAreaHold();
    /** Own chunk and chunk ahead. */
    private final ChunkHold chunks = new ChunkHold();

    /** Client: server positions, one per tick ({@link ArrivalQueue}); server-tick packets arrive unevenly. */
    private final ArrivalQueue arrivals = new ArrivalQueue();

    protected StrikeProjectile(EntityType<? extends StrikeProjectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    // ---------------------------------------------------------------- type parameters

    public abstract WeaponType weapon();

    /** This projectile's airframe spec (B-2 bomb uses {@link WeaponSpec#payload}). */
    public WeaponSpec.Airframe airframe() {
        return weapon().spec().airframe();
    }

    /** Body half-length: center to nose, blocks. */
    protected final double noseLength() {
        return airframe().noseLength();
    }

    /** Cruise speed, blocks/tick: used for approach ETA. */
    public final double cruiseSpeed() {
        return airframe().cruiseSpeed();
    }

    /** Range reserve: flight plan, or spec before plan exists. */
    private Mission mission() {
        if (mission == null) mission = Mission.of(airframe().range());
        return mission;
    }

    /** New range reserve (MLRS: trajectory arc recalculated). */
    protected final void setMission(Mission mission) {
        this.mission = mission;
    }

    /**
     * Range exhausted. Waiting outside world for target-area loading spends no range: on slow servers,
     * generation for spread salvos takes time, so projectiles would vanish without detonation (stress rig: 38 of 41
     * MLRS rockets). Waiting has its own limit — {@link #AREA_WAIT_LIMIT}.
     */
    protected final boolean exhausted() {
        return mission().exhausted();
    }

    /** Remaining flight distance, blocks (server). */
    public double rangeLeft() {
        return mission().range();
    }

    /** Durability: damage before being shot down. 0 — cannot be shot down. */
    protected final float maxHealth() {
        return airframe().health();
    }

    /** Can continue flying outside loaded world (otherwise vanishes at edge). */
    protected boolean fliesVirtually() {
        return true;
    }

    /** Minimum terrain clearance for world reentry from virtual flight. */
    protected final double clearance() {
        return airframe().clearance();
    }

    /** Launcher start phase; null — no launcher start. */
    @Nullable
    protected final WeaponSpec.LaunchProfile launchProfile() {
        return airframe().launchProfile();
    }

    /** Launcher wait before ignition (set by {@link #placeOnLauncher}). */
    protected int readyTicks() {
        return readyTicks;
    }

    /** Ticks until launch rail exit and cruise speed (for approach ETA). */
    protected int launchTicksLeft() {
        WeaponSpec.LaunchProfile lp = launchProfile();
        if (lp == null) return 0;
        return switch (flightPhase()) {
            case READY -> Math.max(0, readyTicks - phaseAge()) + lp.ignitionTicks() + lp.boostTicks();
            case IGNITION -> Math.max(0, lp.ignitionTicks() - phaseAge()) + lp.boostTicks();
            case BOOST -> Math.max(0, lp.boostTicks() - phaseAge());
            default -> 0;
        };
    }

    // ---------------------------------------------------------------- launch

    /** Place projectile, face target and assign owner. */
    public void launch(Vec3 pos, Target target, Vec3 targetPoint, @Nullable UUID owner) {
        this.tracker = new TargetTracker(target, targetPoint);
        float[] a = FlightController.anglesTo(pos, targetPoint);
        this.flight.set(a[0], 0);
        this.moveTo(pos.x, pos.y, pos.z, a[0], 0);
        this.yRotO = a[0];
        this.launchPos = pos;
        this.entityData.set(DATA_OWNER, Optional.ofNullable(owner));
        syncAim();
        syncSpeed();
    }

    /**
     * Place on launcher rail: nose at {@code yaw} heading and {@code elevation}, launch after
     * {@code readyTicks}; invisible for first {@code hiddenTicks} (rack raising). Target/owner as in {@link #launch}.
     */
    public void placeOnLauncher(Vec3 rail, float yaw, float elevation, int readyTicks, int hiddenTicks, Target target, Vec3 targetPoint,
                                @Nullable UUID owner) {
        entityData.set(DATA_HIDDEN, hiddenTicks);
        launch(rail, target, targetPoint, owner);
        flight.set(yaw, -elevation);
        moveTo(rail.x, rail.y, rail.z, yaw, -elevation);
        yRotO = yaw;
        xRotO = -elevation;
        speed = 0;
        this.readyTicks = readyTicks;
        altitude.reset(rail.y);
        setPhase(FlightPhase.READY);
    }

    /** Target alert with {@code leadTicks} remaining (siren from approach detection). */
    public void armSiren(int leadTicks) {
        this.sirenLead = leadTicks;
    }

    /** Route (null — straight to target). */
    @Nullable
    public Route route() {
        return route;
    }

    /**
     * Route to entry point (then target) and range reserve from flight plan: route distance (horizontal)
     * plus descent to target. Without descent, Shahed at real flight level (flight scale 1: 2 km above target) exhausted range
     * in the dive overhead and self-destructed a hundred blocks above target.
     */
    public void setRoute(@Nullable Route route) {
        this.route = route;
        this.mission = Mission.plan(pathToAim() + descent(), cruiseSpeed()).extend(extraRange());
    }

    /** Target descent absent from horizontal route distance, blocks; no route — 0. */
    private double descent() {
        return route != null && !route.finished() && tracker != null ? Math.max(0, getY() - tracker.point().y) : 0;
    }

    /** Range beyond target path: uncounted segments (loiter circle), blocks. */
    protected double extraRange() {
        return 0;
    }

    /**
     * Remaining planned distance to impact, blocks: used to reduce range when target lost
     * ({@link Mission#lose}). Default: target path ({@link #pathToAim}) plus descent; Lancet adds circle/dive.
     */
    protected double plannedPathLeft() {
        return pathToAim() + descent();
    }

    /** Target path: remaining route, or direct without route, blocks. */
    protected final double pathToAim() {
        Vec3 aim = tracker == null ? position() : tracker.point();
        return route != null && !route.finished() ? route.remaining(position(), aim) : position().distanceTo(aim);
    }

    /**
     * Projectile's own random seed (loiter circle, B-2 departure side), before {@link #launch}: flight scenarios
     * (GameTest) repeat exactly. Also pins projectile air ({@link #air}): world wind depends on game time,
     * while scenario batch timing varies (CI shards). Not called in game: each projectile has its own random seed
     * and world air.
     */
    public void seed(long seed) {
        random.setSeed(seed);
        pinAir(Math.floorMod(seed, 30L * 24_000));
    }

    /** Pin projectile air to game tick {@code gameTime}, clear noon ({@link #seed}). */
    private void pinAir(long gameTime) {
        pinnedAirTime = gameTime;
        pinnedAir = Air.of(gameTime, 1, 0, 0);
    }

    /** Projectile released by this one (B-2 bomb) flies in same air, including scenario-pinned air. */
    final void shareAir(StrikeProjectile child) {
        child.pinnedAir = pinnedAir;
        child.pinnedAirTime = pinnedAirTime;
    }

    /** Projectile air: current world air, or seed-pinned air in flight scenarios ({@link #seed}). */
    protected final Air air() {
        return pinnedAir != null ? pinnedAir : Air.of(level());
    }

    /** Nuclear warhead (only on compatible carriers). */
    public void setNuclear(@Nullable Loadout.Nuke nuke) {
        this.nuclear = nuke;
        entityData.set(DATA_NUCLEAR, nuke != null);
    }

    /** Carries nuclear warhead: canceled only by nuclear recall. */
    public boolean isNuclear() {
        return entityData.get(DATA_NUCLEAR);
    }

    @Nullable
    public UUID ownerId() {
        return entityData.get(DATA_OWNER).orElse(null);
    }

    @Nullable
    public ServerPlayer ownerPlayer() {
        UUID id = ownerId();
        return id != null && level() instanceof ServerLevel sl ? sl.getServer().getPlayerList().getPlayer(id) : null;
    }

    /** Operator camera tracks sighted target (synchronized for camera presentation). */
    public Pursuit pursuit() {
        return Pursuit.values()[Mth.clamp(entityData.get(DATA_PURSUIT), 0, Pursuit.values().length - 1)];
    }

    /** Current aim point (synchronized for HUD, alert and rocket whistle). */
    public Vec3 aimPoint() {
        Vector3f v = entityData.get(DATA_AIM);
        return new Vec3(v.x, v.y, v.z);
    }

    public FlightPhase flightPhase() {
        return FlightPhase.byId(entityData.get(DATA_PHASE));
    }

    protected void setPhase(FlightPhase phase) {
        if (phase.ordinal() != entityData.get(DATA_PHASE)) {
            entityData.set(DATA_PHASE, (byte) phase.ordinal());
            phaseStart = age;
        }
    }

    /** Current phase age, ticks (client: since learning the phase). */
    public int phaseAge() {
        return age - phaseStart;
    }

    /** Launch booster burning. */
    public boolean boosterLit() {
        return flightPhase().boosterLit();
    }

    public float roll() {
        return entityData.get(DATA_ROLL);
    }

    /**
     * Last server-tick displacement, blocks/tick (synchronized on client). Actual motion for sound
     * (Doppler, airflow): client-tick entity positions are unsuitable — server-tick packets
     * arrive in this client tick or the next, making projectile stop then jump two steps.
     */
    public Vec3 velocity() {
        if (!level().isClientSide) return getDeltaMovement();
        Vector3f v = entityData.get(DATA_VELOCITY);
        return new Vec3(v.x, v.y, v.z);
    }

    /** Speed, blocks/tick (synchronized on client). */
    public double speed() {
        return level().isClientSide ? entityData.get(DATA_SPEED) : speed;
    }

    public int age() {
        return age;
    }

    /** Visible/audible (not while launcher rack raises: inside closed slot). */
    public boolean isActive() {
        return !(flightPhase() == FlightPhase.READY && phaseAge() < entityData.get(DATA_HIDDEN));
    }

    /** Target vanished (dead, changed dimension, craft disassembled): fly to last known point. */
    public boolean targetLost() {
        return tracker != null && tracker.isLost();
    }

    /** Tracked target (server). */
    @Nullable
    public Target target() {
        return tracker == null ? null : tracker.target();
    }

    /** Outside-world path met surface: wait for location loading, hit after reentry. */
    protected final boolean isGrounded() {
        return grounded != null;
    }

    public boolean isVirtual() {
        return virtual;
    }

    /** Handed to {@link ua.zentix.incoming.strike.VirtualFlights}: do not add to world until self-return. */
    public void markVirtual() {
        virtual = true;
    }

    /**
     * Transfer area/lane to new copy of this mission before removing old entity ({@link VirtualFlights#park}).
     * Own chunks remain with old entity: removing it releases them, but no longer the mission hold.
     */
    public final void handOverTargetArea(StrikeProjectile copy) {
        if (copy == this || copy.level() != level() || !copy.getUUID().equals(getUUID()) || !copy.aimArea.isEmpty()) {
            throw new IllegalArgumentException("Target area handover needs a fresh copy of the same mission");
        }
        copy.aimArea = aimArea;
        aimArea = new TargetAreaHold();
    }

    /** Impact ETA, ticks (server): remaining route at cruise speed plus launch. */
    public int etaTicks() {
        if (tracker == null) return 0;
        double v = Math.max(cruiseSpeed(), speed);
        return (int) Math.ceil(pathToAim() / v) + launchTicksLeft();
    }

    /**
     * Target point refined (same target): tracker holds it as fixed point, also used by HUD, siren and camera;
     * target area follows it ({@link #holdTargetArea}).
     */
    protected final void settleAim(Vec3 point) {
        tracker.settle(point);
        syncAim();
    }

    /**
     * Retarget in flight (from projectile camera): new target, abandon route — fly straight to it.
     *
     * @return projectile accepted target
     */
    public boolean retarget(Target target, Vec3 point) {
        if (tracker == null || !acceptsRetarget()) return false;
        mission().retarget(tracker.retarget(target, point));
        if (route != null) route.skip();
        releaseTargetArea();
        syncAim();
        onRetarget();
        return true;
    }

    /**
     * Climbs behind target: guided projectile holds altitude above it and turns upward below a raised outside-world
     * flight floor ({@link #groundCrossing}) — floor does not stop it. Ballistic projectile (bomb, MLRS) cannot
     * climb: descending below floor is already underground.
     */
    protected boolean climbs() {
        return true;
    }

    /** Retargeting allowed (not after bomb drop, nor for bomber). */
    protected boolean acceptsRetarget() {
        return true;
    }

    protected void onRetarget() {}

    // ---------------------------------------------------------------- tick

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            clientLerp();
            age++;
            return;
        }
        try {
            age++;
            if (tracker == null) {
                // Load failure or spawn without launch(): nowhere to fly.
                Incoming.LOG.warn("Projectile {} {} at {} has no target (load failure or spawned without launch), removed", getType().getDescriptionId(), getUUID(), blockPosition());
                discard();
                return;
            }
            ServerLevel level = (ServerLevel) level();
            if (grounded != null) {
                // Reentered where outside-world path met surface: hit here.
                Vec3 at = grounded;
                grounded = null;
                if (armed()) impact(level, at, null);
                else crashUnarmed(level, at);
                return;
            }
            if (chunks.isEmpty()) chunks.update(level, getUUID(), position(), flight.forward(), speed, lookAhead(level));
            serverTick(level);
            // Detonated or left for outside-world flight (copy now flies there): no siren or new tickets.
            if (isRemoved()) return;
            updateVelocity();
            mission().spend(getDeltaMovement().length());
            checkSiren(level);
            holdTargetArea(level);
            surveyAhead(level);
            syncSpeed();
        } catch (RuntimeException e) {
            Incoming.LOG.error("Projectile {} {} at {} failed with an exception and was removed", getType().getDescriptionId(), getUUID(), blockPosition(), e);
            discard();
        }
    }

    /**
     * Outside-loaded-world tick ({@link VirtualFlights}): same guidance, without collisions or reading
     * unloaded chunks.
     */
    public final void virtualTick(ServerLevel level) {
        virtual = true;
        try {
            age++;
            if (tracker == null) {
                Incoming.LOG.warn("Projectile {} {} off-world at {} has no target (load failure), removed", getType().getDescriptionId(), getUUID(), blockPosition());
                discard();
                return;
            }
            xo = getX();
            yo = getY();
            zo = getZ();
            serverTick(level);
            if (isRemoved()) return;
            updateVelocity();
            mission().spend(getDeltaMovement().length());
            checkSiren(level);
            holdTargetArea(level);
            surveyAhead(level);
        } catch (RuntimeException e) {
            Incoming.LOG.error("Projectile {} {} off-world at {} failed with an exception and was removed", getType().getDescriptionId(), getUUID(), blockPosition(), e);
            discard();
        }
    }

    /**
     * May reenter world: projectile above a ticking ready chunk, with entire forward path ready too (otherwise it
     * would bounce across boundary each tick, {@link VirtualFlights#clearAhead}). No forward margin needed once target
     * reached: it will explode in-world.
     */
    public boolean canMaterialize(ServerLevel level) {
        if (arrived) {
            BlockPos here = BlockPos.containing(position());
            return level.isPositionEntityTicking(here) && Terrain.ready(level, here);
        }
        return clearAhead(level, position());
    }

    /** Projectile can reenter here: entire forward path ready and ticking ({@link VirtualFlights#clearAhead}). */
    private boolean clearAhead(ServerLevel level, Vec3 pos) {
        return VirtualFlights.clearAhead(pos, flight.forward(), speed,
                (x, z) -> Terrain.ready(level, x, z) && level.isPositionEntityTicking(new BlockPos(x << 4, 0, z << 4)));
    }

    /**
     * Reenter world with terrain clearance, except launcher projectile (went outside world on server stop):
     * it returns to rail at location checked by {@link ua.zentix.incoming.strike.LaunchSite}.
     * Called by {@link VirtualFlights}.
     */
    public void materialize(ServerLevel level) {
        virtual = false;
        // Surface arrival means impact: no reason to raise above terrain.
        if (!flightPhase().onLauncher() && grounded == null) {
            // Terrain beneath and 5 flight ticks ahead, every corridor column (ready chunks only): avoid appearing
            // before an unclimbable slope/wall. A path ending at target checks only up to it:
            // terrain beyond would raise an MLRS rocket reentering 10 blocks from target below terrain by dozens of blocks,
            // causing airburst or slope impact beside target.
            double ahead = Math.min(Math.max(80, speed * 5), pathLeft());
            double floor = reliefStraightAhead(level, ahead) + clearance();
            if (getY() < floor) {
                setPos(getX(), floor, getZ());
                altitude.reset(floor);
            }
        }
        xo = getX();
        yo = getY();
        zo = getZ();
        setYRot(flight.yaw());
        setXRot(flight.pitch());
        yRotO = getYRot();
        xRotO = getXRot();
        // Own tickets before reentry: chunk might tick only from another projectile's ticket; if released
        // before first tick, projectile freezes in non-ticking chunk — no flight, departure or range spending.
        holdChunks(level);
    }

    /**
     * Acquire own tickets (own/forward chunk, {@link ChunkTickets}) before world entry. Projectile in a non-entity-ticking
     * chunk cannot acquire them itself: acquisition runs in its tick. Happens on return from outside-world flight or on
     * launcher at ordered launch site beyond players' simulation distance.
     */
    public void holdChunks(ServerLevel level) {
        chunks.update(level, getUUID(), position(), flight.forward(), speed, lookAhead(level));
    }

    /**
     * Hold forward chunk too ({@link ChunkHold}): at launch, yes (ordered launch site may be far from
     * players, rail exit occurs in-world); in flight, while someone can see it ({@link FlightTickets#watched}:
     * player within server render distance — farther away client receives no entity), and in final approach seconds
     * when target area already ticks ({@link TargetAreaHold#ticking}). Otherwise world flight adds nothing, while forward
     * chunk would drag ticking chunks across map: at own chunk edge projectile leaves for outside-world flight,
     * and players receive {@code FarFlights} path packets. At target, in-world projectile stays in-world: without forward chunk it
     * would leave at target-area edge and return a tick or two later, depending on when fresh area chunks
     * start entity ticking (completed by chunk tasks, not tickets), making flight scenarios diverge by a tick.
     * Conditions use distance/tickets rather than entity presence on client: independent of chunk-send rate.
     */
    private boolean lookAhead(ServerLevel level) {
        return flightPhase().launching() || aimArea.ticking() || FlightTickets.watched(level, position(), 0);
    }

    /**
     * Remaining horizontal path to its end ({@link #pathEnd}); infinity — path does not end ahead near target
     * (no end, or end behind: projectile reapproaching).
     */
    private double pathLeft() {
        Vec3 end = pathEnd();
        if (end == null) return Double.POSITIVE_INFINITY;
        double dx = end.x - getX(), dz = end.z - getZ();
        double d2 = dx * dx + dz * dz;
        Vec3 f = flight.forward();
        if (d2 > 1 && f.x * dx + f.z * dz <= 0) return Double.POSITIVE_INFINITY;
        return Math.sqrt(d2);
    }

    /**
     * Projectile path end (null — not ending at point: bomber passes target, loitering weapon circles overhead):
     * default target once route finished.
     */
    @Nullable
    protected Vec3 pathEnd() {
        return tracker != null && onFinalLeg() ? tracker.point() : null;
    }

    /**
     * Target area preloads in background within {@link #PRELOAD_TICKS} flight ticks, inside/outside world:
     * in-world projectile can also fly where no one is present (salvo spread hundreds of blocks from player); without this
     * area acquisition would wait until leaving ticking chunks, right at target. Moving target
     * (craft, player) drags area along; otherwise projectile would await loading that never starts and vanish
     * when range runs out (4 of 10 missiles chasing departing craft). Approach lane: {@link TargetAreaHold}.
     */
    protected void holdTargetArea(ServerLevel level) {
        if (tracker == null || isRemoved()) return;
        // Outside-world path ended at surface: load impact location, not target.
        Vec3 aim = grounded != null ? grounded : areaCenter();
        aimArea.hold(level, getUUID(), position(), aim, preloadDistance(), targetArea(), targetAreaTicks(), visibleLeg(), route, age);
    }

    /** Target-area center: target point; unguided — actual predicted arrival. */
    protected Vec3 areaCenter() {
        return tracker.point();
    }

    /**
     * Target area should tick ({@link TargetAreaHold}): at most {@link #AREA_TICK_LEAD} ticks to impact, or projectile
     * already waiting for area at surface where outside-world path ended. Earlier it only loads.
     */
    protected boolean targetAreaTicks() {
        return grounded != null || etaTicks() <= AREA_TICK_LEAD;
    }

    /**
     * Final target-path distance flown in-world rather than outside ({@link FlightTickets#approach}): approach visible
     * to target-side player within render distance. 0 — target area only.
     */
    protected final double visibleLeg() {
        return airframe().visibleLeg();
    }

    /** Target-area preload distance: {@link #PRELOAD_TICKS} flight ticks, at least 400 blocks. */
    protected double preloadDistance() {
        return Math.max(400, Math.max(speed, cruiseSpeed()) * PRELOAD_TICKS);
    }

    /** Target area loaded and entity-ticking: arriving projectile will explode in-world. */
    protected final boolean aimAreaReady(ServerLevel level, Vec3 aim) {
        BlockPos at = BlockPos.containing(aim);
        return Terrain.ready(level, at) && level.isPositionEntityTicking(at);
    }

    /**
     * Another target-area loading wait tick. Waiting spends no range and has its own limit,
     * {@link #AREA_WAIT_LIMIT}: exhausted — projectile removed (false).
     */
    protected final boolean waitForAimArea(Vec3 aim) {
        if (++areaWait > AREA_WAIT_LIMIT) {
            Incoming.LOG.warn("Projectile {} {} gave up waiting for target area {} to load, removed", getType().getDescriptionId(), getUUID(),
                    BlockPos.containing(aim));
            discard();
            return false;
        }
        return true;
    }

    /** Release target area (removed/retargeted projectile — new area acquired on approach). */
    private void releaseTargetArea() {
        if (level() instanceof ServerLevel level) aimArea.release(level, getUUID());
    }

    /** Preloaded target-area size: {@link FlightTickets} ticket level (4 — ±40 blocks). */
    protected int targetArea() {
        return FlightTickets.DISTANCE;
    }

    private void checkSiren(ServerLevel level) {
        if (sirenLead >= 0 && !isRemoved() && tracker != null && etaTicks() <= sirenLead) {
            sirenLead = -1;
            StrikeService.siren(level, weapon(), tracker.point());
        }
    }

    /**
     * Launcher start: waiting, ignition, rail exit and booster acceleration, then booster separation
     * ({@link #separate}) and {@link FlightPhase#CLIMB}. Returns true while on start segment.
     */
    protected boolean launchTick(ServerLevel level) {
        WeaponSpec.LaunchProfile lp = launchProfile();
        FlightPhase ph = flightPhase();
        if (lp == null || !ph.launching()) return false;
        switch (ph) {
            case READY -> {
                speed = 0;
                if (phaseAge() >= readyTicks) setPhase(FlightPhase.IGNITION);
            }
            case IGNITION -> {
                speed = 0;
                if (phaseAge() >= lp.ignitionTicks()) setPhase(FlightPhase.BOOST);
            }
            default -> {
                speed = lp.boost(flight, speed, phaseAge());
                if (!advance(level, tracker.point(), 0)) return true;
                if (phaseAge() >= lp.boostTicks()) {
                    separate(level);
                    setPhase(FlightPhase.CLIMB);
                }
            }
        }
        return true;
    }

    /** Booster spent: booster body separates and falls. */
    protected void separate(ServerLevel level) {
        SpentBoosterEntity.spawn(level, this, noseLength());
    }

    protected abstract void serverTick(ServerLevel level);

    /** Impact point reached or collision: default is weapon's conventional warhead. */
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        discard();
        Warheads.detonate(level, weapon(), point, this, ownerId());
    }

    /**
     * Own recall ({@code ServerActions.recall}) in world flight: self-destruct — projectile breaks up in air, warhead
     * does not detonate ({@link #crash}), blocks remain intact.
     *
     * @return projectile no longer a threat; false — cannot abort ({@link #recallable}), continues flying
     */
    public boolean recall(ServerLevel level) {
        if (!recallable()) return false;
        Incoming.LOG.debug("Projectile {} {} self-destructed on recall at {}", getType().getDescriptionId(), getUUID(), blockPosition());
        crash(level, position().add(flight.forward().scale(noseLength())));
        return true;
    }

    /**
     * Own recall stops projectile ({@link #recall}): guided has self-destruct; unguided (MLRS rocket,
     * dropped bomb) continues flying.
     */
    public boolean recallable() {
        return true;
    }

    /**
     * Collision before fuze arming (at launch): warhead does not detonate — projectile breaks up,
     * fuel burns.
     */
    protected void crash(ServerLevel level, Vec3 point) {
        discard();
        Warheads.whenReady(level, point, Warheads.reach(1.5f),
                l -> l.explode(this, point.x, point.y, point.z, 1.5f, false, Level.ExplosionInteraction.NONE));
    }

    /**
     * Collision before arming ({@link #crash}): one log line per salvo ({@link FlightLog}), each projectile at
     * DEBUG. Otherwise this ending is invisible: vanilla explosion without impact line; a 30-Shahed salvo crashing entirely
     * into a structure beside launcher would look lost in logs.
     */
    protected final void crashUnarmed(ServerLevel level, Vec3 point) {
        Incoming.LOG.debug("Projectile {} {} crashed before arming at {} (phase {})", getType().getDescriptionId(), getUUID(),
                BlockPos.containing(point), flightPhase());
        // Heading in 10° bins: shared by salvo from one launcher, one line.
        int course = Math.floorMod(Math.round(flight.yaw() / 10f) * 10, 360);
        StrikeWorld.get(level).flightLog().note(getType().getDescriptionId(), FlightLog.Event.CRASHED, BlockPos.containing(point), targetLost(), 0,
                "heading " + course + "°, phase " + flightPhase().getSerializedName());
        crash(level, point);
    }

    /**
     * Track target; returns current aim point. Sighted target ({@link Target.Sighted}) followed only while held in this
     * projectile's camera frame ({@link CameraLink#holds}) and weapon supports it
     * ({@link WeaponSpec.Tracking#CAMERA}).
     */
    protected Vec3 updateTarget(ServerLevel level) {
        boolean byCamera = weapon().spec().tracking() == WeaponSpec.Tracking.CAMERA;
        mission().chase(tracker.tick(level, at -> byCamera && CameraLink.holds(level, this, tracker.target().subject(), at)));
        Pursuit pursuit = !byCamera || !(tracker.target() instanceof Target.Sighted) || tracker.isLost() ? Pursuit.NONE
                : tracker.inSight() ? Pursuit.HELD : Pursuit.WAITING;
        entityData.set(DATA_PURSUIT, (byte) pursuit.ordinal());
        // Also for projectile saved with already-lost target but without “reduced” flag.
        if (tracker.isLost() && mission().lose(plannedPathLeft(), cruiseSpeed())) onTargetLost(level);
        syncAim();
        return tracker.point();
    }

    /**
     * Target lost: fly to last point, with range only for that path ({@link Mission#lose}).
     * Fails to arrive: self-destruct ({@link #advance}). One log line per salvo ({@link FlightLog}): remaining
     * flight seconds at cruise speed.
     */
    private void onTargetLost(ServerLevel level) {
        BlockPos last = BlockPos.containing(tracker.point());
        int seconds = (int) (Math.max(0, rangeLeft()) / cruiseSpeed()) / 20;
        Incoming.LOG.debug("Projectile {} {} at {} lost its target, heading to {}, endurance {} s", getType().getDescriptionId(), getUUID(),
                blockPosition(), last, seconds);
        StrikeWorld.get(level).flightLog().note(getType().getDescriptionId(),
                tracker.outOfReach() ? FlightLog.Event.LOST_OUT_OF_REACH : FlightLog.Event.LOST_GONE, last, true, seconds);
    }

    /** Range exhausted: one log line per salvo ({@link FlightLog}), each projectile at DEBUG. */
    private void noteExpired(ServerLevel level, Vec3 aim, FlightLog.Event event) {
        Incoming.LOG.debug("Projectile {} {} did not reach {}, out of endurance ({}) at {}", getType().getDescriptionId(), getUUID(),
                BlockPos.containing(aim), event, blockPosition());
        StrikeWorld.get(level).flightLog().note(getType().getDescriptionId(), event, BlockPos.containing(aim), targetLost(), 0);
    }

    /**
     * Heading destination: next waypoint (captured within spec radius or behind,
     * {@link Route#update}) or target. Last operator waypoint captured: {@link #onRouteDone}.
     */
    protected Vec3 navPoint(Vec3 aim) {
        if (route != null && !route.finished()) {
            route.update(position(), weapon().spec().capture());
            Vec3 wp = route.current();
            if (wp != null) return new Vec3(wp.x, aim.y, wp.z);
            if (route.byOperator()) onRouteDone(aim);
        }
        return aim;
    }

    /** Operator route completed this tick; final leg to {@code aim} next (retargeting abandons route without this; launch route ends at calculated approach entry). */
    protected void onRouteDone(Vec3 aim) {}

    /** Route complete (or absent): final leg to target. */
    protected boolean onFinalLeg() {
        return route == null || route.finished();
    }

    /** Horizontal path to {@code aim}: remaining route, or direct if absent, blocks. */
    protected final double horizontalPathTo(Vec3 aim) {
        if (route != null && !route.finished()) return route.remaining(position(), aim);
        double dx = aim.x - getX(), dz = aim.z - getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void syncAim() {
        if (tracker == null) return;
        Vec3 p = tracker.point();
        Vector3f cur = entityData.get(DATA_AIM);
        if (Math.abs(cur.x - p.x) > 0.05 || Math.abs(cur.y - p.y) > 0.05 || Math.abs(cur.z - p.z) > 0.05) {
            entityData.set(DATA_AIM, new Vector3f((float) p.x, (float) p.y, (float) p.z));
        }
    }

    /**
     * Velocity: actual travel this tick (from tick-start position), in flight, target-area waiting
     * and drilling. Also sent to clients.
     */
    private void updateVelocity() {
        setDeltaMovement(getX() - xo, getY() - yo, getZ() - zo);
        Vector3f cur = entityData.get(DATA_VELOCITY);
        Vec3 v = getDeltaMovement();
        if (v.distanceToSqr(cur.x, cur.y, cur.z) > 1.0e-6) entityData.set(DATA_VELOCITY, v.toVector3f());
    }

    private void syncSpeed() {
        if (Math.abs(entityData.get(DATA_SPEED) - speed) > 0.01) entityData.set(DATA_SPEED, (float) speed);
    }

    /**
     * Per-tick drift beyond nose-directed step, blocks: wind carrying unpowered projectile (bomb). Powered projectiles lack it:
     * our game flight model has autopilot hold the path itself.
     */
    protected Vec3 drift() {
        return Vec3.ZERO;
    }

    /** Point inside turn circle: cannot turn to it ({@link Autopilot#insideTurn}). */
    protected final boolean insideTurn(Vec3 point, double maxRateDeg) {
        return Autopilot.insideTurn(position(), flight.forward(), speed, point, maxRateDeg);
    }

    protected final Bearing bearingTo(Vec3 aim) {
        return Bearing.of(position(), aim);
    }

    /** Hold altitude ({@link AltitudeHold}) using spec flight-phase turns {@code a}. */
    protected final void holdAltitude(double desired, double gain, WeaponSpec.Agility a) {
        craft.holdAltitude(desired, gain, airframe().handling(), a);
    }

    /** Per-tick speed from our flight model ({@link Craft#throttle}): motor pulls toward {@code target}. */
    protected final void throttle(double target) {
        craft.throttle(airframe(), target);
    }

    /** Fuze armed: left launcher or reached cruise segment ({@link ProximityFuse#armed}). */
    protected boolean armed() {
        return ProximityFuse.armed(flightPhase(), position(), launchPos);
    }

    /**
     * Nose path for flight step from {@code pos} along {@code dir} at {@code speed}: nose midpoint to post-step nose,
     * swept by {@link #advance}; launch-sector check ({@code LaunchSite.clearAhead}) uses acceleration ticks.
     *
     * @return {from, to}
     */
    public static Vec3[] noseSweep(Vec3 pos, Vec3 dir, double speed, double noseLength) {
        return new Vec3[]{pos.add(dir.scale(noseLength * 0.5)), pos.add(dir.scale(speed + noseLength))};
    }

    /**
     * Flight step: sweep nose path over step length. Collision (block, craft, person near trajectory)
     * or target reached calls {@link #impact}. Returns true while still flying.
     *
     * @param reachPad detonation reach beyond step length (Shahed 4.3, missile 6.5, bomb 5.3)
     */
    protected boolean advance(ServerLevel level, Vec3 aim, double reachPad) {
        Vec3 dir = flight.forward();
        Vec3 pos = position();

        if (virtual) return advanceVirtual(level, aim, reachPad, dir);

        boolean onTarget = onFinalLeg() && pos.distanceTo(aim) <= speed + reachPad;
        if (onTarget && armed()) {
            impact(level, aim, null);
            return false;
        }
        if (exhausted()) {
            // Self-destruct: failed to reach target. Nuclear warhead does not detonate far from target — only body/fuel.
            noteExpired(level, aim, FlightLog.Event.EXPIRED);
            Vec3 nose = pos.add(dir.scale(noseLength()));
            if (nuclear != null) crash(level, nose);
            else impact(level, nose, null);
            return false;
        }

        Vec3[] sweep = noseSweep(pos, dir, speed, noseLength());
        sweep[1] = sweep[1].add(drift());
        Vec3 noseFrom = sweep[0];
        // Nose never reads an unready chunk (clip would load it); projectile leaves for outside-world flight before stepping there.
        Vec3 noseTo = Terrain.readyUntil(level, noseFrom, sweep[1]);

        // Also during boost: passing through a house on booster would end boost inside it and crash
        // on first climb tick — silently and far from initial encounter.
        Vec3 blockPoint = null;
        BlockHitResult block = level.clip(new ClipContext(noseFrom, noseTo, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
        if (block.getType() != HitResult.Type.MISS) {
            // Sable craft hit arrives in plot coordinates.
            blockPoint = SubLevels.toWorld(level, block.getLocation());
        }
        Vec3 sweepEnd = blockPoint != null ? blockPoint : noseTo;

        if (armed()) {
            UUID targetId = tracker.target().subject() instanceof Target.OfEntity e ? e.uuid() : null;
            Entity victim = ProximityFuse.victim(level, this, noseFrom, sweepEnd, speed, targetId, ownerId());
            if (victim != null) {
                Vec3 at = victim.getBoundingBox().getCenter();
                impact(level, ProximityFuse.closestOnSegment(noseFrom, sweepEnd, at), victim);
                return false;
            }
        }
        if (blockPoint != null) {
            if (armed()) impact(level, blockPoint, null);
            else crashUnarmed(level, blockPoint);
            return false;
        }

        Vec3 next = pos.add(dir.scale(speed)).add(drift());
        if (next.y < level.getMinBuildHeight() - 64) {
            Incoming.LOG.warn("Projectile {} {} fell below the world at {}, removed", getType().getDescriptionId(), getUUID(), blockPosition());
            discard();
            return false;
        }
        if (leavesTickingChunks(level, next)) return false;
        moveAlong(level, next, dir);
        return true;
    }

    /**
     * Outside world: no collisions. Reach target only when area loaded (otherwise wait on approach until
     * loading completes: detonation in an unloaded chunk would stall server).
     * <p>
     * At target, stop there and reenter world to explode: outside-world flight has no collisions; without enough ticking
     * forward chunks for reentry (target in an isolated ticking chunk —
     * airborne player at loaded edge), projectile would overshoot and fall until range ran out (MLRS rockets 900 blocks
     * below world).
     */
    private boolean advanceVirtual(ServerLevel level, Vec3 aim, double reachPad, Vec3 dir) {
        if (grounded != null) {
            // Standing on surface: reenter once location loaded (same waiting limit),
            // at actual surface from loaded-chunk heightmap.
            if (!aimAreaReady(level, grounded)) return waitForAimArea(grounded);
            grounded = new Vec3(grounded.x, groundBound(level, grounded.x, grounded.z), grounded.z);
            moveAlong(level, grounded, dir);
            arrived = true;
            return true;
        }
        if (exhausted()) {
            noteExpired(level, aim, FlightLog.Event.EXPIRED_VIRTUAL);
            discard();
            return false;
        }
        Vec3 pos = position();
        boolean near = onFinalLeg() && pos.distanceTo(aim) <= speed + reachPad + 48;
        if (near && !aimAreaReady(level, aim)) {
            // Await area loading (ticket acquired on approach).
            return waitForAimArea(aim);
        }
        if (onFinalLeg() && pos.distanceTo(aim) <= speed + reachPad) {
            // Target area ticks (otherwise waiting above): reenter at target — VirtualFlights does it this tick.
            moveAlong(level, aim, dir);
            arrived = true;
            return true;
        }
        Vec3 next = pos.add(dir.scale(speed)).add(drift());
        Vec3 ground = groundCrossing(level, aim, pos, next);
        if (ground != null) {
            // Missed target (or target underground): no outside-world collisions, so projectile would fall without exploding until
            // range exhaustion or world bottom (bomb aimed behind B-2 down to y=−3022); path ends at surface.
            Incoming.LOG.info("Projectile {} {} reached the surface off-world at {}, target {}", getType().getDescriptionId(), getUUID(),
                    BlockPos.containing(ground), BlockPos.containing(aim));
            grounded = ground;
            moveAlong(level, ground, dir);
            return true;
        }
        moveAlong(level, next, dir);
        return true;
    }

    /**
     * Where outside-world step enters ground (null — does not). Surface from ready-chunk heightmap; unready chunk uses
     * generator sea level ({@code Level.getSeaLevel} always 63 in 1.21.1; flat-world sea −63): land rarely lies
     * below it (water itself is surface over water); generator terrain ({@code ChunkGenerator.getBaseHeight})
     * costs milliseconds per point, unsuitable per tick. Ceiling dimension (Nether): heightmap is ceiling, use only sea level.
     * <p>
     * Outside world, projectile does not know terrain and flies at target altitude (distant launch above it, MLRS from it,
     * low flight above it), while target can be below sea/terrain (cave, quarry, ravine): beneath surface but no farther than
     * {@link #BELOW_AIM} below target, it still flies toward it. Descending ballistic projectile (bomb, MLRS) below this floor is
     * already underground: impact at surface above it (also when floor rises: underlying chunk loaded). Guided projectile
     * ({@link #climbs}) only goes below floor when it rises above it — target whose altitude it follows
     * (missile +12, Shahed +30) rose (player left ravine, teleport) — and keeps descending for a few ticks while
     * turning upward. Floor does not stop it, or it would fall wherever caught (3000 blocks from target,
     * with nuclear warhead). Nobody goes below world bottom. Rule leaves projectiles reentering world after this step
     * alone: world has its own collisions.
     */
    @Nullable
    private Vec3 groundCrossing(ServerLevel level, Vec3 aim, Vec3 from, Vec3 to) {
        double floor = Math.min(groundBound(level, to.x, to.z), aim.y - BELOW_AIM);
        boolean buried = !climbs() && to.y < floor && to.y < from.y;
        boolean bottom = to.y < level.getMinBuildHeight();
        if (!(buried || bottom) || clearAhead(level, to)) return null;
        // Step crossed floor: at crossing; already beneath (floor rose, world bottom): current projectile position.
        double t = from.y >= floor && from.y > to.y ? (from.y - floor) / (from.y - to.y) : 0;
        Vec3 at = from.lerp(to, t);
        // Impact at surface where path met it (on slope, step end can differ by blocks in height).
        return new Vec3(at.x, groundBound(level, at.x, at.z), at.z);
    }

    /**
     * Outside-world flight surface: ready-chunk heightmap, otherwise sea level; Nether ceiling is not ground
     * ({@link Terrain.Allowed#FLIGHT}, see {@link #groundCrossing}).
     */
    private static int groundBound(ServerLevel level, double x, double z) {
        return Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z), Terrain.Allowed.FLIGHT).y();
    }

    /**
     * Next step leaves entity-ticking chunks: leave for outside-world flight at current position
     * (or vanish if unsupported), and copy performs step outside world. Check before step: stepping into a non-ticking
     * chunk would leave projectile in-world without ticks, retaining tickets no one could release — such projectiles
     * would hang until game exit and hold chunks (stress rig: 81 tickets for 2 projectiles).
     */
    protected final boolean leavesTickingChunks(ServerLevel level, Vec3 next) {
        if (virtual || level.isPositionEntityTicking(BlockPos.containing(next))) return false;
        if (fliesVirtually()) VirtualFlights.park(level, this);
        else discard();
        return true;
    }

    /**
     * Unchecked movement (bomber, drilling). In-world projectile moves only this way: its chunk tickets
     * ({@link ChunkHold}) follow. Bypassing via {@code setPos} into a non-entity-ticking chunk would leave it there without
     * flight ending, tickets retaining both it and target area until server stop.
     */
    protected void moveAlong(ServerLevel level, Vec3 next, Vec3 dir) {
        setPos(next.x, next.y, next.z);
        setYRot(flight.yaw());
        setXRot(flight.pitch());
        float roll = flight.bankAngle(speed);
        if (Math.abs(roll - entityData.get(DATA_ROLL)) > 0.2f) entityData.set(DATA_ROLL, roll);
        if (!virtual) chunks.update(level, getUUID(), next, dir, speed, lookAhead(level));
    }

    /**
     * Terrain height (obstacle top) at point. Server uses only ready chunk (otherwise world bottom): reading unloaded
     * chunks during tick loads synchronously and stalls server.
     */
    public static double surfaceY(Level level, double x, double z) {
        return Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z), Terrain.Allowed.CHUNK).y();
    }

    /**
     * {@link Craft#relief} sensor: column terrain height along entire path, inside/outside world ({@link Terrain.Allowed#RELIEF}):
     * ready chunk, otherwise survey without loading — disk/memory heightmap, or for chunks absent on disk,
     * generator terrain ({@link TerrainSurvey}); world bottom until survey answers.
     */
    protected double relief(Level level, int x, int z) {
        return Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING, x, z, virtual ? Terrain.Allowed.VIRTUAL_RELIEF : Terrain.Allowed.RELIEF).y();
    }

    /** Request terrain survey this many path blocks ahead ({@link #surveyAhead}). */
    static final double SURVEY_AHEAD = 1024;

    /**
     * Request terrain survey ({@link TerrainSurvey#ahead}) for {@link #SURVEY_AHEAD} path blocks ahead: to next
     * waypoint, then toward target. Terrain sensor requests queried columns itself, but answers take ticks;
     * preordering makes them ready by arrival. Only projectiles using terrain sensing ({@code reliefLookahead}).
     */
    private void surveyAhead(ServerLevel level) {
        if (airframe().reliefLookahead() <= 0 || age % 20 != 0) return;
        Vec3 aim = tracker.point(), pos = position();
        Vec3 wp = route != null && !route.finished() ? route.current() : null;
        double left = SURVEY_AHEAD;
        TerrainSurvey survey = TerrainSurvey.get(level);
        for (Vec3 to : wp == null ? new Vec3[]{aim} : new Vec3[]{wp, aim}) {
            double d = Math.hypot(to.x - pos.x, to.z - pos.z);
            Vec3 end = d <= left ? to : pos.lerp(to, left / d);
            survey.ahead(level, Heightmap.Types.MOTION_BLOCKING, pos, end);
            left -= d;
            if (left <= 0) break;
            pos = to;
        }
    }

    /**
     * Maximum terrain beneath corridor ({@link Autopilot#reliefAlong}) straight ahead for {@code distance} horizontal blocks,
     * starting at column beneath projectile.
     */
    protected double reliefStraightAhead(Level level, double distance) {
        Vec3 pos = position();
        double yawRad = Math.toRadians(flight.yaw());
        double[] track = {pos.x, pos.z, pos.x - Math.sin(yawRad) * distance, pos.z + Math.cos(yawRad) * distance};
        return Autopilot.reliefAlong(track, Terrain.relief(level, virtual ? Terrain.Allowed.VIRTUAL_RELIEF : Terrain.Allowed.RELIEF));
    }

    /**
     * {@link Craft#clearAlong} sensor: straight-line block cells to point except last {@code margin} blocks, in ready
     * chunks ({@link Terrain#readyUntil}); beyond them/outside world, survey terrain top ({@link Terrain#underRelief}). Any collision shape blocks
     * whole cell: thin shapes (fence, fence mast — 0.25-block post) usually missed by shape ray ({@code Level.clip})
     * can still hit Shahed body. Cost: straight cell traversal ({@code BlockGetter.traverseBlocks})
     * to first blocked cell.
     */
    protected double clearAlong(Level level, Vec3 to, double margin) {
        Vec3 from = position();
        double length = from.distanceTo(to);
        if (length <= margin) return Double.POSITIVE_INFINITY;
        Vec3 end = from.lerp(to, (length - margin) / length);
        Vec3 start = from;
        if (!virtual) {
            Vec3 ready = Terrain.readyUntil(level, from, end);
            BlockPos blocked = BlockGetter.traverseBlocks(from, ready, level,
                    (l, pos) -> l.getBlockState(pos).getCollisionShape(l, pos).isEmpty() ? null : pos.immutable(), l -> null);
            if (blocked != null) return from.distanceTo(Vec3.atCenterOf(blocked));
            if (ready == end) return Double.POSITIVE_INFINITY;
            start = ready;
        }
        // Beyond ready chunks/outside world use survey terrain top without Nether ceiling:
        // after gap, ready chunks may reappear with ceiling heightmap rather than an obstacle.
        double t = Terrain.underRelief(level, start, end, Terrain.Allowed.VIRTUAL_RELIEF);
        return t >= 1 ? Double.POSITIVE_INFINITY : from.distanceTo(start.lerp(end, t));
    }

    // ---------------------------------------------------------------- chunks

    /**
     * World stopped tracking it — chunk unload ({@code setRemoved}, no {@code remove}), server stop,
     * chunk no longer delivered: release tickets ({@code ServerLevel.EntityCallbacks.onTrackingEnd}).
     * Returning from chunk, reacquire tickets on first tick. Flying projectile should not leave this way
     * (own chunk held by ticket, leaves ticking chunks through {@link VirtualFlights}): while in an untracked chunk,
     * absent both from world and outside-world flights, so HUD/camera map lose it — log this.
     */
    @Override
    public void onRemovedFromLevel() {
        RemovalReason reason = getRemovalReason();
        if (level() instanceof ServerLevel level && level.getServer().isRunning() && isActive()
                && (reason == null || reason == RemovalReason.UNLOADED_TO_CHUNK)) {
            Incoming.LOG.warn("Projectile {} {} unloaded in flight with its chunk at {} ({})", getType().getDescriptionId(), getUUID(), blockPosition(),
                    reason == null ? "chunk no longer tracked" : "chunk unloaded");
        }
        super.onRemovedFromLevel();
        releaseTickets();
    }

    /**
     * Removal (explosion, cancel, outside-world departure) always here: outside-world projectiles (not added to world)
     * and those in chunks no longer tracked never receive {@link #onRemovedFromLevel}.
     */
    @Override
    public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() && !isRemoved() && !level().isClientSide()) noteForeignRemoval(reason);
        super.remove(reason);
        releaseTickets();
    }

    /**
     * Projectile removed externally ({@code /kill}, another mod's entity cleaner): WARN per salvo ({@link FlightLog},
     * remover is class calling removal), call chain at DEBUG.
     * Our flight endings (explosion, cancel, outside-world flight) originate in mod code and are visible in stack; external
     * removal would otherwise look like a lost salvo — without impact, error or lifetime expiry.
     */
    private void noteForeignRemoval(RemovalReason reason) {
        List<StackWalker.StackFrame> frames = StackWalker.getInstance().walk(s -> s.skip(2).limit(48).toList());
        String own = StrikeProjectile.class.getPackageName().substring(0, StrikeProjectile.class.getPackageName().lastIndexOf('.'));
        for (StackWalker.StackFrame f : frames) if (f.getClassName().startsWith(own)) return;
        StringBuilder by = new StringBuilder();
        for (int i = 0; i < Math.min(6, frames.size()); i++) {
            StackWalker.StackFrame f = frames.get(i);
            if (i > 0) by.append(" ← ");
            by.append(f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1)).append('.').append(f.getMethodName());
        }
        // Remover: first call outside entity itself (discard, kill).
        String who = frames.stream().map(StackWalker.StackFrame::getClassName).filter(c -> !c.startsWith("net.minecraft.world.entity."))
                .findFirst().orElse("?");
        Incoming.LOG.debug("Projectile {} {} at {} (phase {}) removed externally ({}): {}", getType().getDescriptionId(), getUUID(), blockPosition(),
                flightPhase(), reason, by);
        if (level() instanceof ServerLevel level) {
            StrikeWorld.get(level).flightLog().note(getType().getDescriptionId(), FlightLog.Event.REMOVED, blockPosition(), targetLost(), 0,
                    who.substring(who.lastIndexOf('.') + 1) + " (" + reason + ")");
        }
    }

    private void releaseTickets() {
        if (level() instanceof ServerLevel level) chunks.release(level, getUUID());
        releaseTargetArea();
    }

    /**
     * Server stopping: in-world projectile leaves for outside-world flight ({@link VirtualFlights}, saved with
     * world), rather than freezing in a chunk file that may stay unloaded for hours after restart. Chunk tickets
     * would be gone after restart anyway (see {@link ChunkTickets}).
     */
    public void parkForShutdown(ServerLevel level) {
        if (fliesVirtually()) VirtualFlights.park(level, this);
    }

    // ---------------------------------------------------------------- damage: drone and missile can be shot down

    @Override
    public boolean isPickable() {
        return maxHealth() > 0 && !isRemoved() && isActive();
    }

    @Override
    public boolean isAttackable() {
        return maxHealth() > 0;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source) || maxHealth() <= 0 || isRemoved() || !isActive()) return false;
        if (level() instanceof ServerLevel level) {
            if (health < 0) health = maxHealth();
            health -= amount;
            if (health <= 0) {
                shotDown(level, source);
            }
        }
        return true;
    }

    /** Shot down: detonate where hit (at launch before fuze arming: just crash). */
    protected void shotDown(ServerLevel level, DamageSource source) {
        if (armed()) {
            impact(level, position(), null);
            return;
        }
        Incoming.LOG.debug("Projectile {} {} shot down before arming at {} (phase {}, damage {})", getType().getDescriptionId(), getUUID(),
                blockPosition(), flightPhase(), source.getMsgId());
        StrikeWorld.get(level).flightLog().note(getType().getDescriptionId(), FlightLog.Event.SHOT_DOWN, blockPosition(), targetLost(), 0,
                source.getMsgId());
        crash(level, position());
    }

    /**
     * Shot down by SAM missile ({@code defense.Interceptor}): warhead does not detonate; body breaks and debris
     * falls along flight direction ({@link DebrisSpawner#wreck}); outside-world projectile simply removed — no world for debris
     * to fall in. One log line per salvo ({@link FlightLog.Event#INTERCEPTED}, detail: which SAM).
     */
    public void intercepted(ServerLevel level, String by) {
        if (isRemoved()) return;
        Vec3 at = position();
        StrikeWorld.get(level).flightLog().note(getType().getDescriptionId(), FlightLog.Event.INTERCEPTED, BlockPos.containing(at), targetLost(), 0, by);
        if (virtual) {
            VirtualFlights.get(level).clear(level, p -> p == this);
            return;
        }
        Vec3 v = getDeltaMovement();
        discard();
        if (level.isPositionEntityTicking(BlockPos.containing(at))) DebrisSpawner.wreck(level, at, v, noseLength());
    }

    // ---------------------------------------------------------------- synchronization and interpolation

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ROLL, 0f);
        builder.define(DATA_SPEED, 0f);
        builder.define(DATA_VELOCITY, new Vector3f());
        builder.define(DATA_PHASE, (byte) FlightPhase.CRUISE.ordinal());
        builder.define(DATA_AIM, new Vector3f());
        builder.define(DATA_OWNER, Optional.empty());
        builder.define(DATA_NUCLEAR, false);
        builder.define(DATA_HIDDEN, 0);
        builder.define(DATA_PURSUIT, (byte) Pursuit.NONE.ordinal());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_PHASE.equals(key) && level().isClientSide) {
            byte phase = entityData.get(DATA_PHASE);
            if (phase != clientPhase) {
                clientPhase = phase;
                phaseStart = age;
            }
        }
    }

    /**
     * Age/phase age sent with entity spawn: clients first seeing projectile mid-phase
     * (approaching, reentry) see animations (doors, wings, launcher exit) at same point as server.
     * World clocks unsuitable: client clock jumps while server catches up.
     */
    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        buf.writeByte(entityData.get(DATA_PHASE));
        buf.writeVarInt(age);
        buf.writeVarInt(phaseAge());
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        // Vanilla sets old position using previous (zero) rotation, then packet rotation: first frames would turn
        // model from south.
        yRotO = getYRot();
        xRotO = getXRot();
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        clientPhase = buf.readByte();
        age = buf.readVarInt();
        phaseStart = age - buf.readVarInt();
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        arrivals.add(new ArrivalQueue.Sample(x, y, z, yRot, xRot));
    }

    /** This client tick's position from queue; empty — predict on credit from last received position ({@link ArrivalQueue}). */
    private void clientLerp() {
        ArrivalQueue.Sample s = arrivals.next(getX(), getY(), getZ(), getYRot(), getXRot());
        if (s != null) lerpPositionAndRotationStep(1, s.x(), s.y(), s.z(), s.yRot(), s.xRot());
    }

    @Override
    public double lerpTargetX() {
        ArrivalQueue.Sample s = arrivals.latest();
        return s != null ? s.x() : getX();
    }

    @Override
    public double lerpTargetY() {
        ArrivalQueue.Sample s = arrivals.latest();
        return s != null ? s.y() : getY();
    }

    @Override
    public double lerpTargetZ() {
        ArrivalQueue.Sample s = arrivals.latest();
        return s != null ? s.z() : getZ();
    }

    /**
     * Beyond this distance projectile entity stops drawing its model ({@link #shouldRenderAtSqrDistance}): distant
     * rendering handles body there ({@code client.far.FarView#entityReach}).
     */
    public static final double DRAW_RANGE = 1024;

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        // Visible from afar: bounded only by server entity tracking range.
        return distanceSq < DRAW_RANGE * DRAW_RANGE;
    }

    // ---------------------------------------------------------------- persistence

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("pinned_air")) pinAir(tag.getLong("pinned_air"));
        flight.load(tag.getCompound("flight"));
        speed = tag.getDouble("speed");
        age = tag.getInt("age");
        altitude.reset(tag.getDouble("alt"));
        health = tag.contains("health") ? tag.getFloat("health") : -1;
        entityData.set(DATA_PHASE, (byte) FlightPhase.byName(tag.getString("flight_phase")).ordinal());
        phaseStart = age - tag.getInt("phase_age");
        entityData.set(DATA_HIDDEN, tag.getInt("hidden_ticks"));
        if (tag.contains("tracker")) tracker = TargetTracker.load(tag.getCompound("tracker"));
        if (tag.hasUUID("owner")) entityData.set(DATA_OWNER, Optional.of(tag.getUUID("owner")));
        route = tag.contains("route") ? Route.load(tag.getCompound("route")) : null;
        launchPos = Nbt.getVec(tag, "launch");
        areaWait = tag.getInt("area_wait");
        mission = readMission(tag);
        grounded = Nbt.getVec(tag, "grounded");
        readyTicks = tag.getInt("ready_ticks");
        sirenLead = tag.contains("siren_lead") ? tag.getInt("siren_lead") : -1;
        setNuclear(tag.contains("nuclear") ? Loadout.Nuke.CODEC.parse(NbtOps.INSTANCE, tag.get("nuclear")).result().orElse(null) : null);
        syncAim();
        syncSpeed();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (pinnedAir != null) tag.putLong("pinned_air", pinnedAirTime);
        CompoundTag f = new CompoundTag();
        flight.save(f);
        tag.put("flight", f);
        tag.putDouble("speed", speed);
        tag.putInt("age", age);
        tag.putDouble("alt", altitude.filter());
        tag.putFloat("health", health);
        tag.putString("flight_phase", flightPhase().getSerializedName());
        tag.putInt("phase_age", phaseAge());
        tag.putInt("hidden_ticks", entityData.get(DATA_HIDDEN));
        if (tracker != null) tag.put("tracker", tracker.save());
        UUID owner = ownerId();
        if (owner != null) tag.putUUID("owner", owner);
        if (route != null) tag.put("route", route.save());
        if (launchPos != null) Nbt.putVec(tag, "launch", launchPos);
        tag.putInt("area_wait", areaWait);
        tag.put("mission", mission().save());
        if (grounded != null) Nbt.putVec(tag, "grounded", grounded);
        tag.putInt("ready_ticks", readyTicks);
        tag.putInt("siren_lead", sirenLead);
        if (nuclear != null) Loadout.Nuke.CODEC.encodeStart(NbtOps.INSTANCE, nuclear).result().ifPresent(n -> tag.put("nuclear", n));
    }

    /**
     * Saved range reserve. Projectile saved with lifetime (2.3.0 and earlier, no {@code mission} key): remaining
     * lifetime (excluding target-area wait) and fractional pursuit ticks convert to flight at saved cruise speed
     * ({@link #savedCruiseSpeed}); range reduced after target loss stays reduced.
     */
    @Nullable
    private Mission readMission(CompoundTag tag) {
        if (tag.contains("mission")) return Mission.load(tag.getCompound("mission"));
        if (!tag.contains("lifetime")) return null;
        int lifetime = tag.getInt("lifetime");
        double total = lifetime > 0 ? lifetime : airframe().range() / cruiseSpeed();
        return Mission.fromLifetime(total - (age - areaWait), tag.getDouble("lifetime_credit"), tag.getBoolean("lost_capped"),
                savedCruiseSpeed(tag));
    }

    /** Saved cruise speed (converts old lifetime to range). */
    protected double savedCruiseSpeed(CompoundTag tag) {
        return cruiseSpeed();
    }
}
