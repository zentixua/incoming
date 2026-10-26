package ua.zentix.incoming.nuclear;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.GoalUtils;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Mob panic (DESIGN-nuke §4): mobs seeing flash or caught by wave flee epicenter, then
 * calm down. Each {@link PathfinderMob} gets its own {@link Flee} goal on world entry (highest priority, movement):
 * until panic expires, every 2–3 seconds it finds a place 8–16 blocks away from epicenter ({@link #place}: near
 * mob, chunks ready) and a path there. Brain mobs (villagers, piglins, goats) move through memory
 * {@link MemoryModuleType#WALK_TARGET}: goal stores position, brain moves; villager's own
 * panic activity is not activated (it summons golems). Similar speed for all, ~0.38 blocks per tick,
 * like a panicking cow; bosses do not flee. Neither do owned mobs: tamed and someone's property (sitting wolf or cat would run
 * hundreds of blocks and sit there; follow-owner would not bring them back), leashed or named.
 * <p>
 * Flash ({@link NuclearWarhead#expose}: sees fireball) and wave ({@code BlastFront}: from 0.3 psi, not
 * sheltered) wake goal, both in their own budgeted work units. Start and repathing stagger by entity ID. Path
 * search no more than once per {@link #MIN_REPATH} ticks; enclosed mobs (pen, mob farm, pond fish: path ends
 * where it stands or no place found) search less often: each search checks up to {@link #TRIES} places and a full path. Airborne
 * ground mobs (just spawned, wave-tossed) wait for ground instead of searching: a ground path from air cannot
 * be built, which does not mean a pen.
 */
public final class MobPanic {
    /** Highest-priority goal: ahead of all walking. */
    static final int PRIORITY = -1;
    /** Panic after flash and wave, ticks: minimum and additional random duration. */
    public static final int FLASH = 200, FLASH_SPREAD = 200, WAVE = 400, WAVE_SPREAD = 400;
    /** Run speed, blocks per tick. */
    static final double SPEED = 0.38;
    /** Place search reach (horizontal half REACH to REACH, vertical up to Y_REACH). */
    private static final int REACH = 16, Y_REACH = 7;
    /** Places per search and their spread from direction away from epicenter, rad. */
    private static final int TRIES = 8;
    private static final double CONE = Math.PI / 4;
    /** New path no earlier than this many ticks after previous; normally after {@link #REPATH} (+ ID-based spread). */
    static final int MIN_REPATH = 20, REPATH = 40;
    /** A path moving less than this many blocks means enclosure: next search delay doubles (up to ×4). */
    private static final double STUCK = 2;

    private MobPanic() {}

    /** Flee goal for every path-navigating mob (server). */
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof PathfinderMob mob) || mob.getType().is(Tags.EntityTypes.BOSSES)) return;
        if (flee(mob) == null) mob.goalSelector.addGoal(PRIORITY, new Flee(mob));
    }

    /** Mob saw flash. */
    static void flash(Detonation d, LivingEntity e, long now) {
        if (e instanceof Mob mob) scare(mob, d.burst(), now, FLASH + spread(mob, FLASH_SPREAD));
    }

    /** Mob caught by wave. */
    public static void wave(Detonation d, LivingEntity e, long now) {
        if (e instanceof Mob mob) scare(mob, d.burst(), now, WAVE + spread(mob, WAVE_SPREAD));
    }

    /** Panic for another {@code ticks} from {@code from} (extends, never shortens). */
    static void scare(Mob mob, Vec3 from, long now, int ticks) {
        Flee f = flee(mob);
        if (f == null || !mob.isAlive()) return;
        if (f.until <= now) f.startAt = now + Math.floorMod(mob.getId() * 7, 20);
        f.from = from;
        f.until = Math.max(f.until, now + ticks);
    }

    /** Whether mob is panicking now (for tests). */
    public static boolean panicking(Mob mob) {
        Flee f = flee(mob);
        return f != null && f.from != null && f.until > mob.level().getGameTime();
    }

    /** Whether mob is fleeing now: flee goal active (for tests). */
    public static boolean fleeing(Mob mob) {
        for (WrappedGoal w : mob.goalSelector.getAvailableGoals()) if (w.isRunning() && w.getGoal() instanceof Flee) return true;
        return false;
    }

    /** Number of flee-place/path searches (for tests: enclosed mobs search less often). */
    public static int searches(Mob mob) {
        Flee f = flee(mob);
        return f == null ? 0 : f.searches;
    }

    private static int spread(Mob mob, int spread) {
        return Math.floorMod(mob.getId() * 31 + 17, spread);
    }

    @Nullable
    private static Flee flee(Mob mob) {
        for (WrappedGoal w : mob.goalSelector.getAvailableGoals()) if (w.getGoal() instanceof Flee f) return f;
        return null;
    }

    /** Fleeing epicenter. */
    static final class Flee extends Goal {
        private final PathfinderMob mob;
        @Nullable
        Vec3 from;
        long until, startAt;
        private int repath;
        @Nullable
        private WalkTarget walking;
        /** Ticks since last path search; consecutive searches going nowhere; total searches (for tests). */
        private int sincePath, stuck, searches;

        Flee(PathfinderMob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        private long now() {
            return mob.level().getGameTime();
        }

        @Override
        public boolean canUse() {
            long now = now();
            return from != null && now >= startAt && now < until && mob.isAlive() && !mob.isPassenger() && !mine(mob);
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            repath = 0;
            sincePath = MIN_REPATH;
            // enclosure from previous panic does not apply to this one
            stuck = 0;
        }

        @Override
        public void tick() {
            // goal ticks every other tick: count by twos
            repath -= 2;
            sincePath += 2;
            if (sincePath < MIN_REPATH) return;
            // arrived: continue immediately (no earlier than MIN_REPATH); enclosed waits its delay regardless of navigation
            if (repath <= 0 || stuck == 0 && !brain() && mob.getNavigation().isDone()) path();
        }

        @Override
        public void stop() {
            if (brain()) {
                Brain<?> b = mob.getBrain();
                if (walking != null && b.getMemory(MemoryModuleType.WALK_TARGET).orElse(null) == walking) b.eraseMemory(MemoryModuleType.WALK_TARGET);
            } else {
                mob.getNavigation().stop();
            }
            walking = null;
            from = null;
        }

        /** Whether brain drives movement (position in memory, brain moves). */
        private boolean brain() {
            return mob.getBrain().checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED);
        }

        private void path() {
            Vec3 away = from;
            if (away == null || mob.getNavigation() instanceof GroundPathNavigation && !mob.onGround() && !mob.isInLiquid()) return;
            sincePath = 0;
            searches++;
            BlockPos to = place(mob, away);
            boolean gone = false;
            if (to != null) {
                double attr = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
                float speed = (float) Math.max(0.6, Math.min(2.0, attr > 0 ? SPEED / attr : 1));
                if (brain()) {
                    walking = new WalkTarget(to, speed, 1);
                    mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET, walking);
                    gone = true;
                } else {
                    // like PathNavigation.moveTo(x, y, z, speed): reach cell beside position
                    Path p = mob.getNavigation().createPath(to, 1);
                    gone = p != null && p.getEndNode() != null
                            && p.getEndNode().distanceTo(mob.blockPosition()) >= STUCK && mob.getNavigation().moveTo(p, speed);
                }
            }
            stuck = gone ? 0 : Math.min(stuck + 1, 2);
            repath = (REPATH << stuck) + Math.floorMod(mob.getId(), 20);
        }
    }

    /**
     * Flee position: {@link #TRIES} places away from epicenter (±{@link #CONE}), {@link #REACH}/2…{@link #REACH} blocks away,
     * each using valid cell nearest mob height ({@link #stand}); choose farthest valid place from epicenter. Unlike vanilla
     * {@link DefaultRandomPos#getPosAway}: random height (one of 15 valid on flat ground, often finding
     * nothing), spread to ±90° (sideways), positions underfoot (one-step path); mistaking this for enclosure left mob
     * standing for seconds. No valid places: vanilla {@link LandRandomPos#getPosAway}, which raises place out of solid block.
     */
    @Nullable
    static BlockPos place(PathfinderMob mob, Vec3 from) {
        BlockPos best = awayPlace(mob, from);
        if (best != null) return best;
        Vec3 land = LandRandomPos.getPosAway(mob, REACH, Y_REACH, from);
        return land == null ? null : BlockPos.containing(land);
    }

    /** Custom flee place {@link #place} without vanilla fallback (also for tests); {@code null} if none valid. */
    @Nullable
    public static BlockPos awayPlace(PathfinderMob mob, Vec3 from) {
        RandomSource r = mob.getRandom();
        double away = Math.atan2(mob.getZ() - from.z, mob.getX() - from.x);
        boolean restricted = GoalUtils.mobRestricted(mob, REACH);
        BlockPos best = null;
        double farthest = -1;
        for (int i = 0; i < TRIES; i++) {
            double a = away + (2 * r.nextDouble() - 1) * CONE, d = REACH * (0.5 + 0.5 * r.nextDouble());
            BlockPos p = stand(mob, Mth.floor(mob.getX() + d * Math.cos(a)), mob.getBlockY(), Mth.floor(mob.getZ() + d * Math.sin(a)), restricted);
            if (p == null) continue;
            double gone = Math.hypot(p.getX() + 0.5 - from.x, p.getZ() + 0.5 - from.z);
            if (gone > farthest) {
                best = p;
                farthest = gone;
            }
        }
        return best;
    }

    /**
     * Column cell nearest height y (0, +1, −1, +2 …, up to {@link #Y_REACH}) valid for goal, with same checks
     * as vanilla flee position: in world, within restriction, stable for mob navigation (ground mobs have support,
     * fish no full block), no path penalty. For a flyer (parrot, allay), navigation considers the solid
     * landing cell itself stable, but it has a path penalty: place is the free cell above such support. Without this,
     * no cell was valid for flyers, so they fled only via vanilla places, sometimes empty, sometimes a couple blocks away: two
     * empty searches delayed flight for 8 s. Pathfinding decides reachability.
     */
    @Nullable
    private static BlockPos stand(PathfinderMob mob, int x, int y, int z, boolean restricted) {
        boolean flyer = mob.getNavigation() instanceof FlyingPathNavigation;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k <= 2 * Y_REACH; k++) {
            p.set(x, y + (k % 2 == 0 ? -k / 2 : (k + 1) / 2), z);
            if (!GoalUtils.isOutsideLimits(p, mob) && !GoalUtils.isRestricted(restricted, mob, p)
                    && !GoalUtils.isNotStable(mob.getNavigation(), flyer ? p.below() : p) && !(flyer && GoalUtils.isSolid(mob, p))
                    && !GoalUtils.hasMalus(mob, p)) return p.immutable();
        }
        return null;
    }

    /** Owned mob: tamed or someone's property (horse, llama), leashed or named; does not flee. */
    static boolean mine(Mob mob) {
        if (mob.isLeashed() || mob.hasCustomName()) return true;
        if (mob instanceof TamableAnimal t && (t.isTame() || t.isOrderedToSit())) return true;
        return mob instanceof OwnableEntity o && o.getOwnerUUID() != null;
    }
}
