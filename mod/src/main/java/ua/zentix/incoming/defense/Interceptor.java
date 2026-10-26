package ua.zentix.incoming.defense;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.Homing;
import ua.zentix.incoming.util.Terrain;

import java.util.UUID;

/**
 * Interceptor missile flight: our own game model (not physical). Rail exit is upward at the spec's angle
 * (vertically from a canister for the SAM); acceleration and pursuit aim at the target's current position every
 * tick, with a lateral-acceleration turn limit ({@link Homing}, {@link InterceptorSpec#turnLimit}). The proximity
 * fuze bursts it at closest approach if within fuze radius ({@link #fuze}): a burst shoots the target down. There
 * is no die roll: a miss passes outside that radius; the target keeps flying and the missile turns after it while
 * fuel lasts. If the target disappears, gains a rider, or fuel runs out, the missile self-destructs in the air.
 * Targets are only mod projectiles ({@link StrikeProjectile}), identified by UUID.
 * <p>
 * {@link DefenseWorld} runs flight with the same code inside and outside the loaded world: in ticking chunks
 * flight has a visual entity ({@link InterceptorEntity}); elsewhere it does not. Blocks are read only in ready
 * chunks ({@link Terrain#readyUntil}); beyond them, only ready-chunk heightmaps count as ground ({@link
 * Terrain#estimate}). Flight takes no tickets and loads no chunks.
 */
public final class Interceptor {
    /** How flight ended. */
    public enum End {
        /** Burst near the target within fuze radius and shot it down. */
        KILL,
        /** Target disappeared (detonation, abort, left the world) or gained a rider: self-destruct. */
        LOST,
        /** Fuel ran out: self-destruct. */
        FUEL,
        /** Hit ground or a structure. */
        GROUND
    }

    /**
     * Persistent visual UUID: the entity may disappear and reappear at ticking chunks, but the smoke track stays
     * the same.
     */
    private final UUID id = UUID.randomUUID();
    private final InterceptorSpec spec;
    private final UUID target;
    @Nullable
    private final UUID owner;
    /** Launch location (for the log). */
    private final String launcher;
    private Vec3 pos, dir;
    private double speed;
    private int age;
    @Nullable
    private End end;
    @Nullable
    private InterceptorEntity view;

    Interceptor(InterceptorSpec spec, UUID target, @Nullable UUID owner, String launcher, Vec3 rail, Vec3 dir) {
        this.spec = spec;
        this.target = target;
        this.owner = owner;
        this.launcher = launcher;
        this.pos = rail;
        this.dir = dir.normalize();
        this.speed = spec.launchSpeed();
    }

    public UUID id() {
        return id;
    }

    public Vec3 position() {
        return pos;
    }

    /** Heading, unit vector. */
    public Vec3 direction() {
        return dir;
    }

    /** Speed, blocks/tick. */
    public double speed() {
        return speed;
    }

    public int age() {
        return age;
    }

    public UUID target() {
        return target;
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    /** Flight has ended. */
    public boolean done() {
        return end != null;
    }

    /** Flight visual in the world (null means absent: flight is outside ticking chunks). */
    @Nullable
    public InterceptorEntity view() {
        return view;
    }

    void setView(@Nullable InterceptorEntity view) {
        this.view = view;
    }

    /**
     * Rail exit heading: upward at the spec's angle toward the target (90° is vertical; south if directly over
     * the launcher).
     */
    static Vec3 railDirection(Vec3 rail, Vec3 target, InterceptorSpec spec) {
        Vec3 to = target.subtract(rail);
        Vec3 flat = new Vec3(to.x, 0, to.z);
        flat = flat.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : flat.normalize();
        double e = Math.toRadians(spec.elevation());
        return flat.scale(Math.cos(e)).add(0, Math.sin(e), 0);
    }

    /** Flight tick: called by {@link DefenseWorld}. */
    void tick(ServerLevel level, DefenseWorld world) {
        StrikeProjectile t = world.find(level, target);
        if (t == null || !Radar.airborne(t) || t.isVehicle()) {
            world.burst(level, this, pos, null, End.LOST);
            return;
        }
        if (age >= spec.fuelTicks()) {
            world.burst(level, this, pos, null, End.FUEL);
            return;
        }
        // The target has already moved this tick (entities and virtual flights tick earlier): its segment starts at its previous position.
        Vec3 tNow = t.position(), tPrev = tNow.subtract(t.getDeltaMovement());
        Vec3 next = steer(tNow);
        double s = fuze(next, tPrev, tNow, t.airframe().noseLength());
        Vec3 hit = obstacle(level, pos, next);
        if (s >= 0 && (hit == null || s * pos.distanceTo(next) <= pos.distanceTo(hit))) {
            world.burst(level, this, pos.lerp(next, s), t, End.KILL);
            return;
        }
        if (hit != null) {
            world.burst(level, this, hit, null, End.GROUND);
            return;
        }
        moveTo(next);
        if (pos.y > spec.ceiling() || pos.y < level.getMinBuildHeight()) world.burst(level, this, pos, null, End.FUEL);
    }

    /**
     * World-independent pursuit tick: age; after leaving the rail, turn toward the current target position within
     * the spec's limit at this speed ({@link InterceptorSpec#turnLimit}); speed follows the powered-flight law
     * ({@link InterceptorSpec#speed}: engine and altitude/speed exchange). Returns the missile's end-of-tick
     * position; {@link #moveTo} moves it after obstacle checks.
     */
    Vec3 steer(Vec3 targetNow) {
        age++;
        if (age > spec.railTicks()) {
            Vec3 to = targetNow.subtract(pos);
            if (to.lengthSqr() > 1.0e-6) dir = Homing.turn(dir, to.normalize(), spec.turnLimit(speed));
        }
        speed = spec.speed(speed, age, dir.y);
        return pos.add(dir.scale(speed));
    }

    /**
     * Proximity fuze on the missile's tick segment ({@code position() → next}) against the target segment ({@code
     * tPrev → tNow}): fraction of the missile segment at closest approach if that occurs this tick within fuze
     * radius plus half the target length; otherwise −1. If still closing at tick end (closest segment points are
     * at the end), the fuze waits for the next tick: the missile enters the radius before reaching closest
     * approach. The fuze is not armed on the rail.
     */
    double fuze(Vec3 next, Vec3 tPrev, Vec3 tNow, double targetHalfLength) {
        if (age <= spec.railTicks()) return -1;
        double s = Homing.closest(pos, next, tPrev, tNow);
        if (s >= 1) return -1;
        return Homing.distanceAt(pos, next, tPrev, tNow, s) <= spec.fuse() + targetHalfLength ? s : -1;
    }

    void moveTo(Vec3 next) {
        pos = next;
    }

    void finish(End end) {
        this.end = end;
    }

    String launcher() {
        return launcher;
    }

    /**
     * Where this tick's flight segment hits ground or a structure; null if nowhere. Trace blocks up to the first
     * unready chunk, then use the heightmap if the segment's end chunk is ready. Sea level does not count as
     * ground: it is a guess, and targets can be below it (an unloaded valley); the missile follows its target
     * through air there and will still run out of fuel.
     */
    @Nullable
    static Vec3 obstacle(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 ready = Terrain.readyUntil(level, from, to);
        if (ready.distanceToSqr(from) > 1.0e-8 && Terrain.ready(level, BlockPos.containing(from))) {
            BlockHitResult hit = level.clip(new ClipContext(from, ready, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.MISS) return hit.getLocation();
        }
        if (ready.distanceToSqr(to) > 1.0e-8) {
            Terrain.Surface ground = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(to.x), Mth.floor(to.z), Terrain.Allowed.FLIGHT);
            if (ground.source() == Terrain.Source.CHUNK && to.y < ground.y()) return to;
        }
        return null;
    }
}
