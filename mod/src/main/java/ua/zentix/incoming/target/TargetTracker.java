package ua.zentix.incoming.target;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.util.Nbt;

import java.lang.ref.WeakReference;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * In-flight target tracking: current world point and chase budget. Projectile follows target displacement
 * (movement, teleport, retarget) up to {@link #CHASE_BUDGET} blocks beyond flight plan: fuel/battery reserve and
 * pursuit limit. Missing target or excess displacement loses target; fly to last point. Stationary {@link
 * Target.Point} never moves/lost however distant launch. Sighted {@link Target.Sighted} moves only while visible
 * ({@link #tick}), otherwise stays last-seen.
 */
public final class TargetTracker {
    /** Target displacement blocks pursued beyond flight plan. */
    public static final double CHASE_BUDGET = 3000;
    /** Target cannot move faster than this blocks/tick: treat as jump. */
    private static final double MAX_SPEED = 10;
    /** Smaller displacement ignored (entity position jitter). */
    private static final double MIN_MOVE = 0.01;

    /** Target velocity smoothing: fraction of new per-tick displacement. */
    private static final double VELOCITY_SMOOTHING = 0.3;

    private Target target;
    private Vec3 point;
    /** Target velocity, blocks/tick (smoothed, unsaved, rebuilt over a few ticks). */
    private Vec3 velocity = Vec3.ZERO;
    private double chased;
    private boolean lost;
    /** Sighted target visible/followed this tick (unsaved: visibility decided each tick). */
    private boolean inSight;
    /** Lost by exceeding chase budget rather than disappearing (log reason, unsaved). */
    private boolean outOfReach;
    /**
     * Entity followed previous tick (unsaved: first found after load). Another same-UUID entity is not it: player
     * dying/respawning between projectile ticks ({@code doImmediateRespawn}) is a new {@code ServerPlayer};
     * otherwise projectile would miss death.
     */
    @Nullable
    private WeakReference<Entity> followed;

    public TargetTracker(Target target, Vec3 initialPoint) {
        this.target = target;
        this.point = initialPoint;
    }

    /**
     * Update target point. Follow sighted {@link Target.Sighted} only while {@code visible} accepts its point;
     * otherwise retain last-seen position. Missing target is lost as for unsighted targets.
     *
     * @param visible whether sighted target visible at its point now (not queried for other targets)
     * @return blocks chased this tick (0 stationary, unseen, or lost)
     */
    public double tick(ServerLevel level, Predicate<Vec3> visible) {
        inSight = false;
        if (lost) return 0;
        Target subject = target.subject();
        Optional<Vec3> now;
        if (subject instanceof Target.OfEntity e) {
            Entity entity = level.getEntity(e.uuid());
            if (followed != null && followed.get() != entity) {
                loseTarget();
                return 0;
            }
            if (followed == null && entity != null) followed = new WeakReference<>(entity);
            now = e.resolve(level, entity);
        } else {
            now = subject.resolve(level);
        }
        if (now.isEmpty()) {
            loseTarget();
            return 0;
        }
        if (target instanceof Target.Sighted) {
            if (!visible.test(now.get())) {
                velocity = velocity.scale(1 - VELOCITY_SMOOTHING);
                return 0;
            }
            inSight = true;
        }
        Vec3 step = now.get().subtract(point);
        double moved = step.length();
        if (moved < MIN_MOVE) {
            velocity = velocity.scale(1 - VELOCITY_SMOOTHING);
            return 0;
        }
        if (chased + moved > CHASE_BUDGET) {
            loseTarget();
            outOfReach = true;
            return 0;
        }
        chased += moved;
        point = now.get();
        // Jump (teleport/dimension change) is not velocity.
        velocity = moved > MAX_SPEED ? Vec3.ZERO : velocity.lerp(step, VELOCITY_SMOOTHING);
        return moved;
    }

    /**
     * New target (operator retarget): always accepted, but transfer uses same remaining chase budget.
     *
     * @return transfer blocks covered by budget
     */
    public double retarget(Target target, Vec3 point) {
        double moved = Math.min(this.point.distanceTo(point), CHASE_BUDGET - chased);
        chased += moved;
        this.target = target;
        this.point = point;
        velocity = Vec3.ZERO;
        lost = false;
        outOfReach = false;
        followed = null;
        return moved;
    }

    /**
     * Refined same target point (map-site height became known): stationary {@code point} afterward. Not
     * retargeting; spends no chase budget.
     */
    public void settle(Vec3 point) {
        this.target = new Target.Point(point);
        this.point = point;
        velocity = Vec3.ZERO;
    }

    private void loseTarget() {
        lost = true;
        velocity = Vec3.ZERO;
    }

    /** Current target point. */
    public Vec3 point() {
        return point;
    }

    /** Target velocity, blocks/tick, for approach lead (zero for stationary/lost). */
    public Vec3 velocity() {
        return velocity;
    }

    public Target target() {
        return target;
    }

    public boolean isLost() {
        return lost;
    }

    /** Sighted target visible/followed last tick ({@link #tick}). */
    public boolean inSight() {
        return inSight;
    }

    /** Target lost by exceeding {@link #CHASE_BUDGET}, rather than disappearing. */
    public boolean outOfReach() {
        return outOfReach;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        Target.CODEC.encodeStart(NbtOps.INSTANCE, target).resultOrPartial(Incoming.LOG::error).ifPresent(t -> tag.put("target", t));
        Nbt.putVec(tag, "", point);
        tag.putDouble("chased", chased);
        tag.putBoolean("lost", lost);
        return tag;
    }

    public static TargetTracker load(CompoundTag tag) {
        Vec3 p = Nbt.getVec(tag, "");
        if (p == null) p = Vec3.ZERO;
        Target t = tag.contains("target")
                ? Target.CODEC.parse(NbtOps.INSTANCE, tag.get("target")).resultOrPartial(Incoming.LOG::error).orElse(new Target.Point(p))
                : new Target.Point(p);
        TargetTracker tracker = new TargetTracker(t, p);
        tracker.chased = tag.getDouble("chased");
        // Stationary target cannot be lost: its lost flag comes from pre-2.1.3 saves.
        tracker.lost = tag.getBoolean("lost") && !(t instanceof Target.Point);
        return tracker;
    }
}
