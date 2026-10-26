package ua.zentix.incoming.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.guidance.BombDrop;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.guidance.Mission;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.registry.ModDataMaps.Penetrability;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.Nbt;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.warhead.Penetration;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * GBU-57 bunker-buster bomb. Falls ({@link BombDrop}) and enters ground at the speed and angle at fall end, then
 * continues straight under Young's law ({@link Penetration}): each path block uses the medium from data map
 * {@code incoming:penetration} ({@link Penetrability}); neighboring blocks of the same medium form one layer.
 * Fuze: a void after {@value #SENSOR_SOLID} m of solid material detonates once the bomb is fully inside ({@link
 * #VOID_DEPTH}, void sensor); otherwise it detonates where it stops. An unready chunk ahead or the world bottom
 * detonates it in place. The bore is blocks along the path axis (0.8 m caliber means one block).
 */
public class BunkerBusterEntity extends StrikeProjectile {
    private static final EntityDataAccessor<Vector3f> DATA_ENTRY = SynchedEntityData.defineId(BunkerBusterEntity.class, EntityDataSerializers.VECTOR3);
    /** Void sensor arms after this many meters of solid medium behind the bomb (as before: 4 blocks). */
    public static final double SENSOR_SOLID = 4;
    /** A softer medium (S at least this value) does not arm the void sensor ({@link #arms}). */
    static final double SENSOR_SOFT = 20;
    /** Longest underground path, m: detonate beyond it (tens of meters in soft ground, hundreds in water). */
    static final double MAX_PATH = 400;
    /**
     * An axis remainder outside the block shape shorter than this is face-intersection float error, not a void,
     * m: block shapes are no finer than 1/16 m. Otherwise, crossing an angled face into a neighboring block of
     * the same medium “exited into a void” for 10⁻¹⁶ m and restarted Young's layer: a B-2 bomb at 89° penetrated
     * 18–29% deeper than the law.
     */
    static final double GRAZE = 1e-6;

    /** Underground target (cave, bunker): the bomb aims at it rather than the surface above. */
    @Nullable
    private BlockPos goal;
    /**
     * Underground path from entry ({@link #plan}); empty if not started or loaded from save (then replan from
     * here).
     */
    private Path path = Path.EMPTY;
    /** Origin of {@link #path}. */
    private Vec3 origin = Vec3.ZERO;
    /** Time already underground, s, and bore blocks already traversed. */
    private double drillSeconds;
    private int carved;
    /** Solid material already traversed that arms the void sensor, m (saved: path replans after loading). */
    private double armed;

    /** Wind drift per tick, blocks (saved: entering virtual flight creates a new bomb entity from NBT). */
    private Vec3 windDrift = Vec3.ZERO;

    public BunkerBusterEntity(EntityType<? extends BunkerBusterEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.BUNKER;
    }

    /** A released bomb falls: abort does not stop it; recall the B-2 before release. */
    @Override
    public boolean recallable() {
        return false;
    }

    /** The bomb is the weapon's second flight entity: its spec is {@link WeaponSpec#payload}. */
    @Override
    public WeaponSpec.Airframe airframe() {
        return WeaponSpec.BUNKER.payload();
    }

    @Override
    protected boolean climbs() {
        return false;
    }

    @Override
    protected boolean acceptsRetarget() {
        return false;
    }

    /** Ground entry point (for bore smoke and gas venting). */
    public Vec3 entry() {
        Vector3f v = entityData.get(DATA_ENTRY);
        return new Vec3(v.x, v.y, v.z);
    }

    public boolean isDrilling() {
        return flightPhase() == FlightPhase.DRILL;
    }

    /**
     * Bomber release: the bomb inherits carrier {@code velocity} (blocks/tick), including magnitude, heading, and
     * pitch ({@link BombDrop}).
     */
    public void drop(Vec3 pos, Vec3 velocity, Vec3 surface, @Nullable BlockPos goal, @Nullable UUID owner) {
        super.launch(pos, new Target.Point(surface), surface, owner);
        float[] angles = FlightController.anglesTo(Vec3.ZERO, velocity);
        flight.set(angles[0], angles[1]);
        moveTo(pos.x, pos.y, pos.z, angles[0], angles[1]);
        this.goal = goal;
        this.speed = velocity.length();
        // Range covers the straight path to the point with the plan margin, at least the spec's range: from real altitude (flight scale
        // 1, 12 km) the bomb travels ~16 km to the point; spec range covers a fall from game altitude.
        setMission(Mission.of(Math.max(airframe().range(), pos.distanceTo(surface) * Mission.PATH_MARGIN)));
        setPhase(FlightPhase.TERMINAL);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        if (flightPhase() == FlightPhase.DRILL) {
            drillTick(level);
            return;
        }
        Vec3 aim = tracker.point();
        // Underground target: the satellite-guided bomb aims at the target itself, entering ground on the line toward it.
        speed = BombDrop.steer(flight, position(), speed, goal != null ? Vec3.atCenterOf(goal) : aim);
        Air air = air();
        double y = getY();
        windDrift = BombDrop.drift(windDrift, new Vec3(air.windX(y) / 20, 0, air.windZ(y) / 20), speed);
        advance(level, aim, airframe().reachPad());
    }

    /** Wind drift ({@link BombDrop#drift}); none underground. */
    @Override
    protected Vec3 drift() {
        return flightPhase() == FlightPhase.DRILL ? Vec3.ZERO : windDrift;
    }

    /**
     * The bomb's underground path ends here: target area stays where it was at entry (at the target or where the
     * virtual path met the surface); it follows neither a moving target nor the original target after a virtual
     * surface impact, since the bomb will not appear there again. The bomb holds its own chunk ({@link
     * #moveAlong}).
     */
    @Override
    protected void holdTargetArea(ServerLevel level) {
        if (flightPhase() != FlightPhase.DRILL) super.holdTargetArea(level);
    }

    // ---------------------------------------------------------------- ground entry

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        if (flightPhase() == FlightPhase.DRILL) return;
        if (nuclear != null) {
            // Nuclear penetrating warhead (like B61-11): enters ground a few meters, a surface detonation with a crater.
            discard();
            NuclearWarhead.detonate(level, point, nuclear.yieldKt(), false, ownerId());
            return;
        }
        // Impact uses entity velocity at fall end: along the nose plus wind drift.
        Vec3 velocity = flight.forward().scale(speed).add(windDrift);
        double v = velocity.length() * 20;
        float[] angles = FlightController.anglesTo(Vec3.ZERO, velocity);
        flight.set(angles[0], angles[1]);
        speed = velocity.length();
        setPhase(FlightPhase.DRILL);
        // Bore mouth is one block above impact: smoke and gases emerge there.
        entityData.set(DATA_ENTRY, new Vector3f((float) point.x, (float) point.y + 1, (float) point.z));
        moveAlong(level, point, flight.forward());
        path = plan(level, point, flight.forward(), v, 0);
        ua.zentix.incoming.Incoming.LOG.info("Bunker buster entered the ground at {} {} {}: {} m/s, {}° below the horizon, path {} m ({})",
                Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z), Math.round(v), Math.round(flight.pitch()),
                String.format(java.util.Locale.ROOT, "%.1f", path.length()), path.end().name().toLowerCase(java.util.Locale.ROOT));
        origin = point;
        drillSeconds = 0;
        carved = 0;
        Warheads.bunkerEntry(level, point, v, this, ownerId());
    }

    // ---------------------------------------------------------------- underground path

    /** Bomb body for the penetration law, from the spec. */
    public static Penetration.Body body() {
        return Penetration.body(WeaponSpec.BUNKER.payload().article());
    }

    /** Bomb fully inside: in a void the sensor triggers once the bomb has entered completely, m. */
    public static double voidDepth() {
        return WeaponSpec.BUNKER.payload().article().length();
    }

    /**
     * Underground path from {@code from} along {@code dir} at {@code v} m/s: blocks on the path axis, bomb
     * position over time, and stopping point; {@code solid0} is solid material already behind it (replan after
     * loading). Reads ready chunks only: unready ends the path (detonate there). Medium length in a block equals
     * path axis length inside its collision shape (carpet, slab, stairs are not a full meter); the rest is void
     * or the fluid present. A data-map block without a shape (powder snow) is entirely medium.
     */
    public static Path plan(ServerLevel level, Vec3 from, Vec3 dir, double v, double solid0) {
        Penetration.Run run = new Penetration.Run(body(), v);
        List<Step> steps = new ArrayList<>();
        double s = 0, t = 0, solid = solid0, hollow = 0, depth = voidDepth();
        Path.End end = Path.End.LIMIT;
        while (s < MAX_PATH) {
            Vec3 at = from.add(dir.scale(s));
            BlockPos b = BlockPos.containing(at.add(dir.scale(1e-4)));
            if (!level.isInWorldBounds(b) || !Terrain.ready(level, b)) {
                end = Path.End.EDGE;
                break;
            }
            double len = Math.max(1e-4, exit(at, dir, b));
            BlockState state = level.getBlockState(b);
            FluidState fluid = state.getFluidState();
            Penetrability entry = Penetrability.find(state);
            VoxelShape shape = state.getCollisionShape(level, b);
            // Block medium: map entry (or other solid) over the axis length inside the shape; fluid in the remainder.
            double matter = entry != null && shape.isEmpty() ? len : inside(shape, b, at, dir, len);
            Penetrability medium = matter > 0 ? (entry != null ? entry : Penetrability.OTHER) : null;
            // Fluid without a map entry (modded) uses water density, not other solid material.
            Penetrability liquid = fluid.isEmpty() ? null : Penetrability.find(fluid.createLegacyBlock());
            if (liquid == null && !fluid.isEmpty()) liquid = Penetrability.WATER;
            double va = run.speed();
            if (va <= 0) {
                // Speed exhausted (replan after loading on the last tick): stopped here.
                end = Path.End.REST;
                break;
            }
            if (medium != null) {
                hollow = 0;
                double went = run.through(medium.kind(), medium.s(), matter);
                double vb = run.speed();
                s += went;
                t += went > 0 ? 2 * went / (va + vb) : 0;
                if (arms(medium)) solid += went;
                va = vb;
                if (run.stopped()) {
                    steps.add(new Step(b, s, t, carves(medium), solid));
                    end = Path.End.REST;
                    break;
                }
            }
            double rest = len - matter;
            if (rest < GRAZE) rest = 0;
            if (rest > 0 && liquid != null) {
                hollow = 0;
                double went = run.through(liquid.kind(), liquid.s(), rest);
                double vb = run.speed();
                s += went;
                t += went > 0 ? 2 * went / (va + vb) : 0;
                if (run.stopped()) {
                    steps.add(new Step(b, s, t, medium != null && carves(medium), solid));
                    end = Path.End.REST;
                    break;
                }
            } else if (rest > 0) {
                run.gap();
                if (solid >= SENSOR_SOLID && hollow + rest >= depth) {
                    double went = depth - hollow;
                    s += went;
                    t += went / va;
                    steps.add(new Step(b, s, t, medium != null && carves(medium), solid));
                    end = Path.End.VOID;
                    break;
                }
                hollow += rest;
                s += rest;
                t += rest / va;
            }
            steps.add(new Step(b, s, t, medium != null && carves(medium), solid));
        }
        return new Path(List.copyOf(steps), s, t, end);
    }

    /** Whether to clear the medium block for the bore: solid yes; fluid (water, lava) and impenetrable no. */
    static boolean carves(Penetrability m) {
        return m.kind() != Penetration.Kind.FLUID && m.kind() != Penetration.Kind.STOP;
    }

    /**
     * Whether the medium arms the void sensor: solid and no softer than S {@value #SENSOR_SOFT}. Deceleration in
     * leaves, wool, and powder snow is tens of times weaker than in ground, so the deceleration-based sensor does
     * not treat them as obstacles (estimate).
     */
    static boolean arms(Penetrability m) {
        return m.kind() != Penetration.Kind.FLUID && m.kind() != Penetration.Kind.STOP && m.s() < SENSOR_SOFT;
    }

    /**
     * Path axis length from {@code at}, length {@code len} along {@code dir}, inside block {@code b}'s {@code
     * shape}, m.
     */
    private static double inside(VoxelShape shape, BlockPos b, Vec3 at, Vec3 dir, double len) {
        if (shape.isEmpty()) return 0;
        double sum = 0;
        for (AABB box : shape.toAabbs()) {
            AABB w = box.move(b);
            // Axis segment inside the box: intersection of intervals on three axes.
            double lo = 0, hi = len;
            double[] p = {at.x, at.y, at.z}, d = {dir.x, dir.y, dir.z}, mn = {w.minX, w.minY, w.minZ}, mx = {w.maxX, w.maxY, w.maxZ};
            for (int k = 0; k < 3 && lo < hi; k++) {
                if (Math.abs(d[k]) < 1e-12) {
                    if (p[k] < mn[k] || p[k] > mx[k]) hi = lo;
                    continue;
                }
                double a = (mn[k] - p[k]) / d[k], c = (mx[k] - p[k]) / d[k];
                lo = Math.max(lo, Math.min(a, c));
                hi = Math.min(hi, Math.max(a, c));
            }
            if (hi > lo) sum += hi - lo;
        }
        return Math.min(len, sum);
    }

    /** Path length from {@code at} along {@code dir} until exiting block {@code b}, m. */
    private static double exit(Vec3 at, Vec3 dir, BlockPos b) {
        double best = Double.POSITIVE_INFINITY;
        best = Math.min(best, axis(at.x, dir.x, b.getX()));
        best = Math.min(best, axis(at.y, dir.y, b.getY()));
        best = Math.min(best, axis(at.z, dir.z, b.getZ()));
        return best;
    }

    private static double axis(double p, double d, int lo) {
        if (Math.abs(d) < 1e-9) return Double.POSITIVE_INFINITY;
        return ((d > 0 ? lo + 1 : lo) - p) / d;
    }

    /**
     * Underground tick: advance the path traveled in 0.05 s, clearing the axis bore and striking bodies in the
     * way; detonate at path end. The path is not saved: after loading, replan here at the same speed.
     */
    private void drillTick(ServerLevel level) {
        if (path == Path.EMPTY) {
            path = plan(level, position(), flight.forward(), speed * 20, armed);
            origin = position();
            drillSeconds = 0;
            carved = 0;
        }
        Vec3 from = position();
        drillSeconds += 0.05;
        double s = Math.min(path.length(), along(drillSeconds));
        while (carved < path.steps().size() && path.steps().get(carved).s() <= s + 1e-6) {
            Step step = path.steps().get(carved++);
            armed = step.armed();
            if (step.solid()) carve(level, step.pos());
        }
        Vec3 to = origin.add(flight.forward().scale(s));
        strike(level, from, to);
        speed = Math.max(0, speedAt(drillSeconds) / 20);
        moveAlong(level, to, flight.forward());
        if (drillSeconds >= path.seconds()) detonate(level);
    }

    /** Path traveled by {@code seconds} underground, m (linear in time between blocks). */
    private double along(double seconds) {
        double s0 = 0, t0 = 0;
        for (Step step : path.steps()) {
            if (step.t() >= seconds) return s0 + (step.s() - s0) * (seconds - t0) / Math.max(1e-9, step.t() - t0);
            s0 = step.s();
            t0 = step.t();
        }
        return path.length();
    }

    /** Underground speed at {@code seconds}, m/s (from the path step). */
    private double speedAt(double seconds) {
        double s0 = 0, t0 = 0;
        for (Step step : path.steps()) {
            if (step.t() >= seconds) return (step.s() - s0) / Math.max(1e-9, step.t() - t0);
            s0 = step.s();
            t0 = step.t();
        }
        return 0;
    }

    /**
     * Bore: clear the block on the path axis (0.8 m caliber means one block); loose material above falls into it.
     * Only with ready neighboring chunks too: updates and Sable read neighbors.
     */
    private static void carve(ServerLevel level, BlockPos p) {
        if (!IncomingConfig.SERVER.blockDamage.get() || !Terrain.neighbourhoodReady(level, p.getX() >> 4, p.getZ() >> 4)) return;
        BlockState s = level.getBlockState(p);
        if (s.isAir()) return;
        ColumnScar.replace(level, p, s, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** A 13.6 t body at hundreds of meters per second kills whoever is in this tick's bomb path (body impact). */
    private void strike(ServerLevel level, Vec3 from, Vec3 to) {
        double r = body().diameter() / 2;
        AABB box = new AABB(from, to).inflate(r + 0.5);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (e.getBoundingBox().inflate(r).clip(from, to).isPresent() || e.getBoundingBox().inflate(r).contains(from)) {
                e.hurt(ModDamageTypes.source(level, ModDamageTypes.KINETIC, this, ownerPlayer()), Float.MAX_VALUE);
            }
        }
    }

    private void detonate(ServerLevel level) {
        Vec3 at = position();
        Vec3 entry = entry();
        discard();
        Warheads.bunker(level, at, entry, this, ownerId());
    }

    /**
     * Path block: location, path length and time from entry to its end, whether solid (cleared for the bore).
     *
     * @param s path from entry to block end, m
     * @param t time from entry to block end, s
     * @param solid solid medium (fluid and void are not cleared for the bore)
     * @param armed solid material traversed by block end that arms the void sensor, m
     */
    public record Step(BlockPos pos, double s, double t, boolean solid, double armed) {}

    /**
     * Underground path.
     *
     * @param length entire path, m
     * @param seconds time until detonation, s
     * @param end how it ended
     */
    public record Path(List<Step> steps, double length, double seconds, End end) {
        static final Path EMPTY = new Path(List.of(), 0, 0, End.LIMIT);

        public enum End {
            /** Stopped. */
            REST,
            /** Void sensor. */
            VOID,
            /** Unready chunk or world edge. */
            EDGE,
            /** Longest path. */
            LIMIT
        }
    }

    // ---------------------------------------------------------------- data

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ENTRY, new Vector3f());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        Vec3 d = Nbt.getVec(tag, "wind_drift");
        windDrift = d != null ? d : Vec3.ZERO;
        goal = NbtUtils.readBlockPos(tag, "goal").orElse(null);
        armed = tag.getDouble("armed");
        entityData.set(DATA_ENTRY, new Vector3f(tag.getFloat("entry_x"), tag.getFloat("entry_y"), tag.getFloat("entry_z")));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        Nbt.putVec(tag, "wind_drift", windDrift);
        if (goal != null) tag.put("goal", NbtUtils.writeBlockPos(goal));
        tag.putDouble("armed", armed);
        Vec3 e = entry();
        tag.putFloat("entry_x", (float) e.x);
        tag.putFloat("entry_y", (float) e.y);
        tag.putFloat("entry_z", (float) e.z);
    }
}
