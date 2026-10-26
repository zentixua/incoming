package ua.zentix.incoming.warhead;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import ua.zentix.incoming.nuclear.model.BlastDamage;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.world.BlastBody;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.registry.ModTags;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.registry.ModParticles;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.strike.ImpactCost;
import ua.zentix.incoming.util.PoiRecords;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.work.UnitQueue;
import ua.zentix.incoming.work.WorkScheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Vanilla explosion in work units ({@link UnitQueue}): same {@code ServerLevel.explode} stages, portioned across
 * units. Host pack (city, missile) two power-12–20 explosions in one call cost 559 ms in one tick; splitting only
 * detonation/block portions still leaves “rays and damage” up to 150 ms.
 * <ol>
 * <li>{@link Explosion} with TNT rules, {@link EventHooks#onExplosionStart} (claims cancel normally), one Sable
 * craft query across ray reach. Craft nearby uses vanilla {@link Explosion#explode()} in same unit: Sable mixin
 * queries craft each air ray step, transforms to plot, adds craft blocks, pushes craft. These are its internals,
 * cannot duplicate; mod does not enter method. Damage/knockback split via {@code ExplosionEvent.Detonate} ({@link
 * #onDetonate}). Without craft, Sable has nothing to change; own ray loop (vanilla copy), only {@code EXPLODE}
 * event and all 1352 {@code level.random} strength factors here in vanilla order, selection independent of
 * splitting.</li>
 * <li>{@link #RAYS_PER_UNIT} rays per unit, accumulating selected set. Repeated 0.3-block steps in same block
 * read once (like Lithium {@code world.explosions}).</li>
 * <li>Entity collection and {@code ExplosionEvent.Detonate} (mod handlers in one unit, using future damage
 * list).</li>
 * <li>Process selected: discard air without fire, craft plots once per chunk, world outward from center by
 * precalculated distance.</li>
 * <li>Damage/knockback {@link #ENTITIES_PER_UNIT} entities (vanilla loop copy, life/distance/visibility at unit
 * time); player receives explosion/knockback packet in same unit. Then craft blocks together ({@link
 * #blowCrafts}).</li>
 * <li>World blocks outward in timed portions ({@link #PORTION_NANOS}), via {@code Explosion.finalizeExplosion}
 * ({@code onExplosionHit}, then fire). Collect drops across entire blast, release at end. Changed block since
 * rays (flowing water, player placement) untouched; property change of same block (fence lost neighbor) is not
 * replacement.</li>
 * </ol>
 * Charge merges blocks, bodies, craft hulls into distance-ordered front. Hull is one event at nearest solid block
 * before bodies behind; vanilla path above covers ordinary TNT/crash blast. World can change between units (1–3
 * ticks), so portions recheck blocks. Plot-grid-centered blast (on craft) uses one vanilla {@code level.explode}:
 * Sable relocates it via {@code ServerLevel.explode} wrapper.
 * <p>
 * {@link BlastArea} (own/timeline) held until completion; unit waits for readiness, skips blocks in chunks
 * unready by its deadline.
 * <p>
 * {@link Explosion} source is projectile ({@code getDirectSourceEntity}), like vanilla TNT/creeper/fireball:
 * other mods' {@code ExplosionEvent} handlers (Explosion Overhaul by entity type id, claims) distinguish Incoming
 * strikes/weapons. Projectile itself unharmed, like vanilla.
 */
final class StagedExplosion implements UnitQueue.Job {
    /** World block portion timed: continue while unit shorter than this. */
    static final long PORTION_NANOS = 5_000_000L;
    /** Longer block logs a line. */
    static final long SLOW_BLOCK_NANOS = 10_000_000L;
    /** Outward block batch with shuffled internal order. */
    static final int BATCH = 16;
    /** Rays per unit (1352 total). */
    static final int RAYS_PER_UNIT = 128;
    /** Entities per damage unit: mob death includes loot/all pack handlers. */
    static final int ENTITIES_PER_UNIT = 8;

    /** 1352 vanilla ray directions in vanilla order (normalized 16³ cube faces). */
    private static final double[] RAY_X, RAY_Y, RAY_Z;

    static {
        List<double[]> dirs = new ArrayList<>(1352);
        for (int j = 0; j < 16; j++) {
            for (int k = 0; k < 16; k++) {
                for (int l = 0; l < 16; l++) {
                    if (j == 0 || j == 15 || k == 0 || k == 15 || l == 0 || l == 15) {
                        double d0 = (double) ((float) j / 15.0F * 2.0F - 1.0F);
                        double d1 = (double) ((float) k / 15.0F * 2.0F - 1.0F);
                        double d2 = (double) ((float) l / 15.0F * 2.0F - 1.0F);
                        double d3 = Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
                        dirs.add(new double[]{d0 / d3, d1 / d3, d2 / d3});
                    }
                }
            }
        }
        RAY_X = new double[dirs.size()];
        RAY_Y = new double[dirs.size()];
        RAY_Z = new double[dirs.size()];
        for (int i = 0; i < dirs.size(); i++) {
            RAY_X[i] = dirs.get(i)[0];
            RAY_Y[i] = dirs.get(i)[1];
            RAY_Z[i] = dirs.get(i)[2];
        }
    }

    static int rays() {
        return RAY_X.length;
    }

    /** Next unit's action. */
    private enum Phase { START, RAYS, GAS, DETONATE, SPLIT, REMEMBER, DAMAGE, FRONT, BLOCKS }

    /** Tests: rays via vanilla {@code explode()} (like nearby craft), compare own selection to vanilla. */
    boolean forceVanilla;
    /** Tests: rays per unit. */
    int raysPerUnit = RAYS_PER_UNIT;
    /** Tests: {@code level.random} seed before rays (same factors for both paths). */
    @Nullable
    Long seed;
    /**
     * Tests: block-portion clock; null uses impact-lane clock ({@link WorkClock#sampler}: counting clock 1 step
     * per block).
     */
    @Nullable
    LongSupplier clock;
    /** Tests: block-portion deadline. */
    long portionNanos = PORTION_NANOS;
    /** Tests: block portions and largest size. */
    int portions, largestPortion;

    private final BlastArea area;
    /**
     * Physical warhead charge, kg TNT ({@link #charge}): Kinney–Graham wave breaks via pressure–impulse curve
     * ({@code BlockResponse.breaksAt}), gases sustain room pressure, {@link BlastBody} damages bodies. 0 means
     * vanilla {@link #power} blast (bunker-buster, ground entry, tests).
     */
    private final double charge;
    /** World wave center: Sable craft explosion world position ({@link SubLevels#toWorld}). */
    private Vec3 centre;
    /** Charge explosion reach for any block break/body wave damage, blocks. */
    private final double breakReach, hitReach;
    /** This blast's body randomness ({@code BlastBody.fate}). */
    private long waveId;
    /** Charge room (gases), null if outdoors. */
    @Nullable
    private Room room;
    @Nullable
    private BlastBody.Wave wave;
    /** Contact placement: slab breach ({@link SlabBreach}) only for contact charges. */
    private SlabBreach.Placement placement = SlabBreach.Placement.NONE;
    /** Warhead impact heading: entry face ({@link SlabBreach#locate}); null unknown. */
    @Nullable
    private Vec3 axis;
    /** Breach ray origin; null no contact (or not searched, {@link #breachLocated}). */
    @Nullable
    private SlabBreach.Origin breach;
    private boolean breachLocated;
    /** Breach-removed blocks (tests/summary). */
    int breached;
    /** Breach ray blocks (removed if breached). */
    private final LongArrayList breachPath = new LongArrayList();
    /** Explosions required to finish first (strike secondaries await main). */
    private final List<StagedExplosion> after;
    private final Vec3 at;
    private final float power;
    private final boolean fire;
    private final boolean blocks;
    @Nullable
    private final Entity direct;
    @Nullable
    private final Entity owner;
    @Nullable
    private final ExplosionDamageCalculator calculator;

    private Phase phase = Phase.START;
    @Nullable
    private Explosion explosion;
    /** Calculator used by {@link Explosion} itself (vanilla default if none supplied). */
    private ExplosionDamageCalculator damage = new ExplosionDamageCalculator();
    @Nullable
    private DamageSource source;
    /** Ray strengths: power × {@code level.random} factor, vanilla order. */
    private float[] strength = new float[0];
    private int nextRay;
    private final Set<BlockPos> chosen = new HashSet<>();
    private List<Entity> entities = List.of();
    private int nextEntity;
    /** Entity distance at the start of the outward sweep, and glass/backing before any blocks are removed. */
    private List<Double> bodyRanges = List.of();
    private long beganAt, frontDueTick;
    /** UnitQueue polls blocked between units; ready is only checked at the start of its slice. */
    private LongSupplier frontTime = () -> Long.MAX_VALUE;
    private final java.util.Map<Entity, BlastBody.Before> bodyBefore = new java.util.IdentityHashMap<>();
    private final Set<ServerPlayer> told = new HashSet<>();
    /** World blocks outward from center and identities at ray time. */
    private List<BlockPos> toBlow = List.of();
    private List<Block> blocksAtRays = List.of();
    private int next;
    /** Started batch: shuffled {@link #toBlow} indices, taken from end. */
    private final IntArrayList batch = new IntArrayList();
    /** World positions after separation, before snapshot ({@link #split}). */
    private List<BlockPos> picked = List.of();
    /** Craft blocks/identities at separation, waiting for damage completion ({@link #blowCrafts}). */
    private List<BlockPos> crafts = List.of();
    private List<Block> craftBlocks = List.of();
    /** Craft id owning plot in craft-block chunk at separation, or null. */
    private final Long2ObjectOpenHashMap<UUID> craftOwners = new Long2ObjectOpenHashMap<>();
    private boolean partitioned;
    /** Used vanilla {@code explode()} (craft nearby). */
    private boolean vanilla;
    /** Vanilla {@code explode()} already damaged: mod did not receive {@code Detonate} entity list. */
    private boolean vanillaDamage;
    /**
     * Explosion currently run through vanilla {@code explode()} by mod (server thread), and list taken from its
     * {@code Detonate}.
     */
    @Nullable
    private static Explosion driving;
    @Nullable
    private static List<Entity> handed;
    /** Drops for entire explosion (like {@code Explosion.addOrAppendStack}). */
    private final List<Pair<ItemStack, BlockPos>> drops = new ArrayList<>();
    private boolean done;

    /** Measurements: stage times ({@link ExplosionStage}), ray counters. */
    private final long[] stages = new long[ExplosionStage.values().length];
    private final RayCounts counts = new RayCounts();

    /** Explosion ray counters for strike summary. */
    static final class RayCounts {
        /** Ray steps (0.3 blocks). */
        long steps;
        /** Air steps among them: Sable mixin queries craft each such vanilla {@code explode()} step. */
        long airSteps;
        /** Steps served from ray block cache (same block as previous step). */
        long cached;
        /** Sable craft queries (ours: one per blast). */
        int craftQueries;
        /** Blasts using full vanilla {@code explode()} (craft nearby). */
        int vanilla;

        void add(RayCounts o) {
            steps += o.steps;
            airSteps += o.airSteps;
            cached += o.cached;
            craftQueries += o.craftQueries;
            vanilla += o.vanilla;
        }
    }

    StagedExplosion(BlastArea area, List<StagedExplosion> after, Vec3 at, float power, boolean fire, boolean blocks, @Nullable Entity direct,
                    @Nullable Entity owner, @Nullable ExplosionDamageCalculator calculator) {
        this(area, after, at, power, 0, fire, blocks, direct, owner, calculator);
    }

    /** Physical charge explosion {@code tntKg} kg TNT with {@code placement} contact. */
    StagedExplosion(BlastArea area, List<StagedExplosion> after, Vec3 at, double tntKg, SlabBreach.Placement placement, @Nullable Vec3 axis,
                    boolean blocks, @Nullable Entity direct, @Nullable Entity owner) {
        this(area, after, at, (float) breakReach(tntKg), tntKg, false, blocks, direct, owner, null);
        this.placement = placement;
        this.axis = axis;
    }

    private StagedExplosion(BlastArea area, List<StagedExplosion> after, Vec3 at, float power, double charge, boolean fire, boolean blocks,
                            @Nullable Entity direct, @Nullable Entity owner, @Nullable ExplosionDamageCalculator calculator) {
        this.area = area;
        this.after = after;
        this.at = at;
        this.centre = at;
        this.charge = charge;
        this.breakReach = charge > 0 ? breakReach(charge) : 0;
        this.hitReach = charge > 0 ? hitReach(charge) : 0;
        this.power = power;
        this.fire = fire;
        this.blocks = blocks;
        this.direct = direct;
        this.owner = owner;
        this.calculator = calculator;
    }

    /** Explosion finished/canceled: waiting strike jobs may proceed. */
    boolean done() {
        return done;
    }

    @Override
    public boolean ready(ServerLevel level) {
        // Whole strike area covers this explosion too: shared condition preserves work order.
        return (phase != Phase.FRONT || level.getGameTime() >= frontDueTick) && area.ready(level)
                && (frontArea == null || frontArea.ready(level));
    }

    /** Used vanilla {@code explode()}. */
    boolean vanilla() {
        return vanilla;
    }

    /** Vanilla path handed damage to mod ({@link #onDetonate}). */
    boolean handedDamage() {
        return vanilla && !vanillaDamage;
    }

    /** Still selecting rays/damaging entities (not removing blocks). */
    boolean picking() {
        return !done && (charge > 0 ? phase.ordinal() <= Phase.REMEMBER.ordinal() : phase.ordinal() < Phase.BLOCKS.ordinal());
    }

    /** Waits for entire {@code after} explosions. */
    boolean waiting() {
        return Warheads.pending(after);
    }

    @Override
    public boolean blocked() {
        return waiting() || phase == Phase.FRONT && frontTime.getAsLong() < frontDueTick
                || (phase == Phase.BLOCKS || phase == Phase.FRONT) && area.peersPicking(this);
    }

    @Override
    public int unitKind() {
        return kind().ordinal();
    }

    private ImpactCost.Kind kind() {
        return switch (phase) {
            case START, RAYS, GAS -> ImpactCost.Kind.RAYS;
            case DETONATE, REMEMBER, DAMAGE -> ImpactCost.Kind.HITS;
            case SPLIT, FRONT, BLOCKS -> ImpactCost.Kind.BLOCKS;
        };
    }

    @Override
    public boolean step(ServerLevel level) {
        long t0 = System.nanoTime();
        ImpactCost.Kind kind = kind();
        long[] before = stages.clone();
        int count = 0;
        boolean more;
        switch (phase) {
            case START -> {
                count = 1;
                more = charge > 0 ? startCharge(level) : start(level);
            }
            case RAYS -> more = charge > 0 ? chargeRays(level) : rays(level);
            case GAS -> more = gas(level);
            case DETONATE -> more = detonate(level);
            case SPLIT -> more = split(level);
            case REMEMBER -> more = remember(level);
            case DAMAGE -> more = damage(level);
            case FRONT -> {
                more = front(level);
                count = blown;
            }
            default -> {
                more = portion(level);
                count = blown;
            }
        }
        area.record(level, kind, System.nanoTime() - t0, count);
        for (int i = 0; i < before.length; i++) before[i] = stages[i] - before[i];
        area.recordStages(level, before);
        if (!more) popDrops(level);
        return more;
    }

    private void time(ExplosionStage stage, long since) {
        stages[stage.ordinal()] += System.nanoTime() - since;
    }

    /** First unit; {@code true} means more remain. */
    private boolean start(ServerLevel level) {
        long t = System.nanoTime();
        // Creeper-style drop decay: removed block drops with probability 1/power (TNT normally drops
        // everything; after 30-shot salvos, thousands of items would consume two thirds of server thread). Level.explode uses
        // game rule: BLOCK uses blockExplosionDropDecay, enabled by default.
        Level.ExplosionInteraction interaction = blocks ? Level.ExplosionInteraction.BLOCK : Level.ExplosionInteraction.NONE;
        if (SubLevels.inPlotGrid(level, new ChunkPos(BlockPos.containing(at)))) {
            counts.vanilla++;
            explosion = level.explode(direct, ModDamageTypes.source(level, ModDamageTypes.STRIKE, direct, owner), calculator,
                    at.x, at.y, at.z, power, fire, interaction, ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT);
            return false;
        }
        Explosion.BlockInteraction destroy = blocks ? Explosion.BlockInteraction.DESTROY_WITH_DECAY : Explosion.BlockInteraction.KEEP;
        source = ModDamageTypes.source(level, ModDamageTypes.STRIKE, direct, owner);
        Explosion e = new Explosion(level, direct, source, calculator,
                at.x, at.y, at.z, power, fire, destroy, ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT);
        explosion = e;
        if (calculator != null) damage = calculator;
        boolean cancelled = EventHooks.onExplosionStart(level, e);
        time(ExplosionStage.START, t);
        if (cancelled) return false;
        // In air, calculator gives no resistance; ray loses only 0.225 per 0.3-block step,
        // reaching 1.3 × 1.33 ≈ 1.73 power; search craft across blast-area reach (2 power + 1).
        double reach = Warheads.reach(power);
        counts.craftQueries++;
        if (seed != null) level.random.setSeed(seed);
        if (forceVanilla || SubLevels.mayHaveCraftNear(level, at, reach)) return vanilla(level, e);
        t = System.nanoTime();
        level.gameEvent(null, GameEvent.EXPLODE, at);
        time(ExplosionStage.GAME_EVENT, t);
        strength = new float[rays()];
        for (int i = 0; i < strength.length; i++) strength[i] = power * (0.7F + level.random.nextFloat() * 0.6F);
        phase = Phase.RAYS;
        return true;
    }

    /**
     * Craft nearby: vanilla {@code explode()} (Sable-mixin rays, entity collection, {@code Detonate}) is one
     * timed stage; world block snapshot next unit. Damage/knockback portioned ({@link Phase#DAMAGE}) over list
     * left by {@code Detonate} handlers ({@link #onDetonate}): vanilla damages in ray unit (missile by airship:
     * full {@code explode()} 181.6 ms/tick). Craft blocks after damage ({@link #blowCrafts}); if {@code
     * explode()} already damaged, immediately.
     */
    private boolean vanilla(ServerLevel level, Explosion e) {
        counts.vanilla++;
        vanilla = true;
        long t = System.nanoTime();
        // Nested explosion (event handler) runs its own state: restore outer afterward.
        Explosion outer = driving;
        List<Entity> outerHanded = handed;
        driving = e;
        handed = null;
        List<Entity> got;
        try {
            e.explode();
        } finally {
            got = handed;
            handed = outerHanded;
            driving = outer;
        }
        time(ExplosionStage.VANILLA, t);
        if (got != null) {
            entities = got;
        } else {
            // List not captured (no Detonate): explode() damaged itself; player packet as vanilla.
            vanillaDamage = true;
            for (ServerPlayer p : level.players()) {
                if (p.distanceToSqr(at.x, at.y, at.z) < 4096.0) tell(p, e.getHitPlayers().get(p));
            }
        }
        partition(level);
        // Damage already complete: craft blocks immediately.
        if (vanillaDamage) blowCrafts(level);
        phase = Phase.SPLIT;
        return true;
    }

    /** Rays {@code [nextRay, nextRay + raysPerUnit)}, vanilla {@code Explosion.explode} loop copy. */
    private boolean rays(ServerLevel level) {
        long t = System.nanoTime();
        Explosion e = explosion;
        int end = Math.min(rays(), nextRay + raysPerUnit);
        for (int r = nextRay; r < end; r++) {
            float f = strength[r];
            double x = at.x, y = at.y, z = at.z;
            double dx = RAY_X[r], dy = RAY_Y[r], dz = RAY_Z[r];
            BlockPos last = null;
            BlockState state = null;
            Optional<Float> resistance = Optional.empty();
            while (f > 0.0F) {
                BlockPos pos = BlockPos.containing(x, y, z);
                if (pos.equals(last)) {
                    counts.cached++;
                } else {
                    if (!level.isInWorldBounds(pos)) break;
                    state = level.getBlockState(pos);
                    FluidState fluid = level.getFluidState(pos);
                    resistance = damage.getBlockExplosionResistance(e, level, pos, state, fluid);
                    last = pos;
                }
                counts.steps++;
                if (state.isAir()) counts.airSteps++;
                if (resistance.isPresent()) f -= (resistance.get() + 0.3F) * 0.3F;
                if (f > 0.0F && damage.shouldBlockExplode(e, level, pos, state, f)) chosen.add(pos);
                x += dx * 0.3F;
                y += dy * 0.3F;
                z += dz * 0.3F;
                f -= 0.22500001F;
            }
        }
        nextRay = end;
        time(ExplosionStage.RAYS, t);
        if (nextRay >= rays()) phase = Phase.DETONATE;
        return true;
    }

    // ---------------------------------------------------------------- charge explosion

    /** Maximum air cells for charge room search: beyond means outdoors (UFC: room is enclosed volume). */
    static final int ROOM_CELLS = 4000;
    /** Vanilla-blast-resistant blocks (obsidian 1200, protected mod blocks) stop wave. */
    private static final float BLAST_PROOF = 1200;
    /** Ray step, blocks, like vanilla. */
    private static final double STEP = 0.3;

    /** Maximum reach where {@code tntKg} charge wave breaks anything: weakest block at lowest jitter, blocks. */
    static double breakReach(double tntKg) {
        return reachFor(BlockResponse.FRAGILE_PSI, tntKg) + 1;
    }

    /** Charge wave body reach: glass-fragment threshold ({@code BlastBody}: no injury below), blocks. */
    static double hitReach(double tntKg) {
        return BlastModel.rangeForSurfaceOverpressure(BlastModel.kpa(BlockResponse.FRAGILE_PSI), tntKg);
    }

    /** Open-field break reach for {@code psi} threshold at lowest jitter, blocks. */
    static double reachFor(double psi, double tntKg) {
        double pc = BlastModel.kpa(psi);
        double lo = 0.05, hi = 1e4;
        if (!breaksOpen(lo, tntKg, pc)) return 0;
        for (int i = 0; i < 60; i++) {
            double m = Math.sqrt(lo * hi);
            if (breaksOpen(m, tntKg, pc)) lo = m;
            else hi = m;
        }
        return lo;
    }

    /** Like {@code BlockResponse.breaksAt} at lowest jitter: divide pressure/impulse by {@code JITTER_MIN}. */
    private static boolean breaksOpen(double r, double tntKg, double pcKpa) {
        double j = BlockResponse.JITTER_MIN;
        return BlastDamage.breaks(BlastModel.surfaceOverpressureKpa(r, tntKg) / j, BlastModel.chemicalImpulse(r, tntKg, true) / j, pcKpa);
    }

    /**
     * Charge first unit: {@link Explosion} radius {@link #breakReach} (event handlers/drop decay see it), {@code
     * ExplosionEvent.Start} (claims cancel normally), {@code EXPLODE} event. No vanilla path: same law breaks
     * craft blocks ({@link #crafts}).
     */
    private boolean startCharge(ServerLevel level) {
        long t = System.nanoTime();
        if (SubLevels.inPlotGrid(level, new ChunkPos(BlockPos.containing(at)))) centre = SubLevels.toWorld(level, at);
        Explosion.BlockInteraction destroy = blocks ? Explosion.BlockInteraction.DESTROY_WITH_DECAY : Explosion.BlockInteraction.KEEP;
        source = ModDamageTypes.source(level, ModDamageTypes.STRIKE, direct, owner);
        Explosion e = new Explosion(level, direct, source, null, centre.x, centre.y, centre.z, power, false, destroy,
                ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT);
        explosion = e;
        // Start handlers already see charge ({@link #chargeOf}).
        CHARGES.put(e, charge);
        cancelled = EventHooks.onExplosionStart(level, e);
        started = true;
        time(ExplosionStage.START, t);
        if (cancelled) return false;
        waveId = level.random.nextLong();
        beganAt = level.getGameTime();
        frontTime = level::getGameTime;
        t = System.nanoTime();
        level.gameEvent(null, GameEvent.EXPLODE, centre);
        time(ExplosionStage.GAME_EVENT, t);
        phase = Phase.RAYS;
        return true;
    }

    /**
     * Charge-wave rays {@code [nextRay, nextRay + raysPerUnit)}: ground Kinney–Graham pressure/impulse ({@link
     * BlastModel}); block breaks by pressure–impulse curve ({@code BlockResponse.breaksAt}); broken transmits
     * wave share ({@code BlockResponse.passes}); surviving block, ground, fluid, indestructible cast shadow and
     * end ray. Approximation: wave does not wrap buildings, broken block consumes exactly its destruction
     * impulse.
     */
    private boolean chargeRays(ServerLevel level) {
        long t = System.nanoTime();
        Explosion e = explosion;
        int end = Math.min(rays(), nextRay + raysPerUnit);
        if (!breachLocated) {
            breachLocated = true;
            breach = SlabBreach.locate(level, centre, charge, placement, axis, e, damage);
            // The targeting point may be half a block inside the surface. The charge is on the entry face, not inside
            // half a slab. Air and contact rays must start at the same physical surface.
            if (breach != null && placement == SlabBreach.Placement.SURFACE) centre = breach.at();
        }
        boolean[] liquid = new boolean[1];
        for (int r = nextRay; r < end; r++) {
            double dx = RAY_X[r], dy = RAY_Y[r], dz = RAY_Z[r];
            // Into a contacted solid the contact-breach law applies. Starting an air wave there would bypass the
            // entire slab thickness (and remove a metre of concrete for a sub-metre Lancet charge).
            if (placement != SlabBreach.Placement.SURFACE || breach == null || !breach.inward(dx, dy, dz))
                wave(level, e, centre, dx, dy, dz, 0, 1, Long.MIN_VALUE);
            // Breach: ray into contacted obstacle; reaching void removes traversed blocks, wave continues
            // into void.
            if (breach == null || !breach.inward(dx, dy, dz)) continue;
            liquid[0] = false;
            double out = SlabBreach.march(level, breach, dx, dy, dz, e, damage, breachPath, liquid);
            if (out < 0) continue;
            for (int i = 0; i < breachPath.size(); i++) {
                if (chosen.add(BlockPos.of(breachPath.getLong(i)))) breached++;
            }
            // Approximation: wave beyond breach as open field at same center distance (breach energy
            // not deducted), upper estimate behind slab.
            if (!liquid[0]) wave(level, e, breach.at(), dx, dy, dz, out, 1, breachPath.getLong(breachPath.size() - 1));
        }
        nextRay = end;
        time(ExplosionStage.RAYS, t);
        if (nextRay >= rays()) phase = Phase.GAS;
        return true;
    }

    /**
     * Wave from {@code from} along {@code (dx, dy, dz)} starting at {@code d0}: Kinney–Graham at charge-center
     * distance, {@code share} fraction; {@code last} already traversed, leave alone.
     */
    private void wave(ServerLevel level, Explosion e, Vec3 from, double dx, double dy, double dz, double d0, double share, long last) {
        for (double d = d0; d <= breakReach; d += STEP) {
            double x = from.x + dx * d, y = from.y + dy * d, z = from.z + dz * d;
            BlockPos pos = BlockPos.containing(x, y, z);
            long key = pos.asLong();
            counts.steps++;
            if (key == last) {
                counts.cached++;
                continue;
            }
            last = key;
            if (!level.isInWorldBounds(pos) || !Terrain.ready(level, pos)) break;
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                counts.airSteps++;
                continue;
            }
            if (state.getBlock() instanceof LiquidBlock || state.getExplosionResistance(level, pos, e) >= BLAST_PROOF) break;
            BlockResponse response = BlockResponse.of(state);
            if (response.kind() == BlockResponse.Kind.NONE || response.kind() == BlockResponse.Kind.GROUND) break;
            double range = Math.max(0.25, from == centre ? d : Math.sqrt((x - centre.x) * (x - centre.x) + (y - centre.y) * (y - centre.y)
                    + (z - centre.z) * (z - centre.z)));
            double psi = BlastModel.psi(BlastModel.surfaceOverpressureKpa(range, charge)) * share;
            double impulse = BlastModel.chemicalImpulse(range, charge, true) * share;
            int seed = Mth.murmurHash3Mixer(Long.hashCode(key));
            boolean breaks = damage.shouldBlockExplode(e, level, pos, state, 1f) && response.breaksAt(psi, impulse, seed);
            // Shapeless grass/flowers/crops/torches have no mass in wave path: broken or not,
            // wave passes without loss.
            boolean massless = state.getCollisionShape(level, pos).isEmpty();
            Direction entry = SlabBreach.exitFace(pos, new Vec3(x, y, z), new Vec3(-dx, -dy, -dz));
            if (!massless && (entry == null || SurfaceBlocks.backed(level, pos, entry))) break;
            if (breaks) chosen.add(pos);
            if (massless) continue;
            if (!breaks) break;
            share *= response.passes(psi, impulse, seed);
            if (share < 1e-3) break;
        }
    }

    /**
     * Room blast gases: traverse up to {@link #ROOM_CELLS} air cells from charge. Ending sooner means enclosure;
     * gas pressure ({@link BlastDamage#quasiStaticKpa}) loads all walls until venting through openings, glass,
     * wooden doors/trapdoors, ray breaches ({@link BlastDamage#ventSeconds}); same wall curve with gas impulse.
     * Entire room blown out, destroyed floor.
     */
    private boolean gas(ServerLevel level) {
        long t = System.nanoTime();
        room = Room.find(level, centre, chosen);
        if (room != null) {
            double kpa = BlastDamage.quasiStaticKpa(charge, room.cells().size());
            double seconds = BlastDamage.ventSeconds(room.cells().size(), room.vents());
            double psi = BlastModel.psi(kpa), impulse = Double.isInfinite(seconds) ? Double.MAX_VALUE : kpa * 1000 * seconds;
            room = new Room(room.cells(), room.walls(), room.vents(), kpa);
            Explosion e = explosion;
            for (BlockPos p : room.walls()) {
                BlockState s = level.getBlockState(p);
                BlockResponse response = BlockResponse.of(s);
                if (response.kind() == BlockResponse.Kind.NONE || response.kind() == BlockResponse.Kind.GROUND) continue;
                // Gas loads a wall normally from every adjacent room cell. The vector from the charge is not that
                // normal (a tall wall above a floor-level charge must not be mistaken for a backed horizontal slab).
                boolean free = false;
                for (Direction dir : Direction.values()) {
                    if (room.cells().contains(p.relative(dir).asLong()) && !SurfaceBlocks.backed(level, p, dir)) {
                        free = true;
                        break;
                    }
                }
                if (!free) continue;
                if (s.getExplosionResistance(level, p, e) >= BLAST_PROOF || !damage.shouldBlockExplode(e, level, p, s, 1f)) continue;
                if (response.breaksAt(psi, impulse, Mth.murmurHash3Mixer(Long.hashCode(p.asLong())))) chosen.add(p);
            }
        }
        time(ExplosionStage.RAYS, t);
        phase = Phase.DETONATE;
        return true;
    }

    /**
     * Charge room: air cells, surrounding walls, openings (m²), gas pressure kPa.
     */
    record Room(LongOpenHashSet cells, List<BlockPos> walls, int vents, double kpa) {
        /**
         * Enclosure at {@code centre}; null if air extends beyond {@link #ROOM_CELLS} (outdoors) or into unready
         * chunk.
         */
        @Nullable
        static Room find(ServerLevel level, Vec3 centre, Set<BlockPos> broken) {
            LongOpenHashSet seen = new LongOpenHashSet();
            LongOpenHashSet cells = new LongOpenHashSet();
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            List<BlockPos> walls = new ArrayList<>();
            int vents = 0;
            BlockPos start = BlockPos.containing(centre);
            seen.add(start.asLong());
            queue.enqueue(start.asLong());
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            boolean first = true;
            while (!queue.isEmpty()) {
                p.set(queue.dequeueLong());
                if (!level.isInWorldBounds(p) || !Terrain.ready(level, p)) return null;
                BlockState s = level.getBlockState(p);
                // Charge bursts at struck surface: center block counts as room air too.
                if (!first && !passes(level, p, s)) {
                    BlockPos wall = p.immutable();
                    walls.add(wall);
                    if (broken.contains(wall) || vent(s)) vents++;
                    continue;
                }
                first = false;
                cells.add(p.asLong());
                if (cells.size() > ROOM_CELLS) return null;
                for (Direction dir : Direction.values()) {
                    long n = BlockPos.offset(p.asLong(), dir);
                    if (seen.add(n)) queue.enqueue(n);
                }
            }
            return new Room(cells, walls, vents, 0);
        }

        private static boolean passes(ServerLevel level, BlockPos p, BlockState s) {
            return s.isAir() || s.getCollisionShape(level, p).isEmpty();
        }

        /** Vent: glass/fragile (first broken at 0.8 psi), wooden doors/trapdoors. */
        private static boolean vent(BlockState s) {
            return s.is(ModTags.SHATTERS) || BlockResponse.of(s).thresholdPsi() <= BlockResponse.FRAGILE_PSI
                    || s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.WOODEN_TRAPDOORS);
        }

        boolean contains(BlockPos pos) {
            return cells.contains(pos.asLong());
        }
    }

    /** Charge wave on body ({@link BlastBody}): open-field pressure where it reached, room gases indoors. */
    private BlastBody.Wave wave() {
        if (wave == null) wave = new BlastBody.Wave() {
            @Override
            public Vec3 centre() {
                return centre;
            }

            /** Open-field wave, at least charge-room gas pressure indoors. */
            @Override
            public double overpressureKpa(Vec3 p) {
                double free = BlastModel.surfaceOverpressureKpa(p.distanceTo(centre), charge);
                return room != null && room.contains(BlockPos.containing(p)) ? Math.max(free, room.kpa()) : free;
            }

            @Override
            public double dynamicPressureKpa(Vec3 p) {
                return BlastModel.dynamicPressureKpa(BlastModel.surfaceOverpressureKpa(p.distanceTo(centre), charge));
            }

            @Override
            public double windSpeed(Vec3 p) {
                return BlastModel.windSpeed(BlastModel.surfaceOverpressureKpa(p.distanceTo(centre), charge));
            }

            @Override
            public double phaseSeconds(Vec3 p) {
                return BlastModel.chemicalPhaseSeconds(p.distanceTo(centre), charge, true);
            }

            @Override
            public double hitKpa() {
                return BlastModel.kpa(BlockResponse.FRAGILE_PSI);
            }

            @Override
            public boolean wind(ServerLevel level, LivingEntity e) {
                return Explosion.getSeenPercent(airOrigin(), e) > 0;
            }

            @Override
            public boolean reaches(ServerLevel level, LivingEntity e) {
                return room != null && (room.contains(e.blockPosition()) || room.contains(BlockPos.containing(e.getEyePosition())));
            }

            @Override
            public long id() {
                return waveId;
            }

            @Override
            public net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> damage() {
                return ModDamageTypes.STRIKE;
            }
        };
        return wave;
    }

    /** A point just outside the contacted face; vanilla clipping treats a start exactly on a solid face as inside. */
    private Vec3 airOrigin() {
        return breach != null && breach.open() != null ? centre.add(breach.open().scale(1e-5)) : centre;
    }

    /**
     * Outward front: remove only blocks reached before the next body. A wall behind a nearer body still exists when
     * it is hit; a breached plate in front of a further body is already an opening. Within a block the failure is
     * instantaneous (voxel approximation); scheduling may take several ticks but cannot reverse that causal order.
     */
    private record CraftFront(java.util.UUID id, double rangeSqr, float power) {}
    private final List<CraftFront> craftFront = new ArrayList<>();
    private int nextCraft;
    private List<dev.ryanhcode.sable.companion.SubLevelAccess> craftScan;
    private int scanCraft;
    private java.util.Iterator<BlockPos> craftCells;
    private double nearestCraft = Double.POSITIVE_INFINITY;
    private float frontCraftPower;
    /** Scan-time bounds may exceed those held at impact start. Released also on cancellation and timeout. */
    private BlastArea frontArea;

    /** Scan loaded plot cells under the work budget; empty space inside bounds is not the nearest hull block. */
    private boolean scanCrafts(ServerLevel level) {
        if (craftScan == null) {
            frontCraftPower = craftPower(level, centre, charge);
            craftScan = frontCraftPower > 0 ? SubLevels.near(level, centre, breakReach(charge)) : List.of();
        }
        int cells = 0;
        while (scanCraft < craftScan.size()) {
            var sub = craftScan.get(scanCraft);
            if (craftCells == null) craftCells = SubLevels.plotCells(sub).iterator();
            while (craftCells.hasNext()) {
                BlockPos p = craftCells.next();
                if (Terrain.ready(level, p) && Objects.equals(sub.getUniqueId(), SubLevels.containingId(level, new ChunkPos(p)))
                        && !level.getBlockState(p).getCollisionShape(level, p).isEmpty())
                    nearestCraft = Math.min(nearestCraft, SubLevels.blockRangeSqr(sub, p, centre));
                if (++cells >= 256) return false;
            }
            if (Double.isFinite(nearestCraft)) craftFront.add(new CraftFront(sub.getUniqueId(), nearestCraft, frontCraftPower));
            scanCraft++;
            craftCells = null;
            nearestCraft = Double.POSITIVE_INFINITY;
        }
        craftFront.sort(Comparator.comparingDouble(CraftFront::rangeSqr));
        return true;
    }

    /** The queue's ready/deadline checks own this wait, never the front's work loop. */
    void holdFrontArea(ServerLevel level, float power) {
        if (frontArea != null) frontArea.release(level);
        frontArea = BlastArea.hold(level, centre, craftReach(power));
    }

    private boolean front(ServerLevel level) {
        long t = System.nanoTime();
        LongSupplier watch = clock != null ? clock : WorkScheduler.impactClock(level.getServer()).sampler();
        long start = watch.getAsLong();
        int bodies = 0;
        blown = 0;
        while (next < toBlow.size() || nextEntity < entities.size() || nextCraft < craftFront.size()) {
            double body = nextEntity < bodyRanges.size() ? bodyRanges.get(nextEntity) : Double.POSITIVE_INFINITY;
            double block = next < toBlow.size() ? new AABB(toBlow.get(next)).distanceToSqr(centre) : Double.POSITIVE_INFINITY;
            double craft = nextCraft < craftFront.size() ? craftFront.get(nextCraft).rangeSqr() : Double.POSITIVE_INFINITY;
            frontDueTick = beganAt + (long) Math.ceil(20 * BlastModel.chemicalArrivalSeconds(Math.sqrt(Math.min(craft, Math.min(body, block))), charge, true));
            if (level.getGameTime() < frontDueTick) break;
            if (craft <= Math.min(block, body)) {
                CraftFront hit = craftFront.get(nextCraft);
                crafts(level, hit);
                nextCraft++;
            } else if (block <= body && next < toBlow.size()) {
                int i = next++;
                BlockPos p = toBlow.get(i);
                if (Terrain.ready(level, p)) {
                    BlockState state = level.getBlockState(p);
                    if (state.is(blocksAtRays.get(i)) && explosion.interactsWithBlocks()) {
                        PoiRecords.recordWithNeighbours(level, p, state);
                        long since = System.nanoTime();
                        state.onExplosionHit(level, p, explosion, this::addOrAppend);
                        long took = System.nanoTime() - since;
                        if (took > SLOW_BLOCK_NANOS) area.slowBlock(level, state, p, took);
                        blown++;
                    }
                }
            } else {
                // Reuse the charge damage implementation for exactly this body, without its final phase transition.
                hitBody(level, entities.get(nextEntity++));
                if (++bodies >= ENTITIES_PER_UNIT) break;
            }
            if (watch.getAsLong() - start >= portionNanos) break;
        }
        if (blown > 0) {
            portions++;
            largestPortion = Math.max(largestPortion, blown);
        }
        time(ExplosionStage.DAMAGE, t);
        if (next < toBlow.size() || nextEntity < entities.size() || nextCraft < craftFront.size()) return true;
        entities = List.of();
        bodyBefore.clear();
        return false;
    }

    private void hitBody(ServerLevel level, Entity entity) {
        Explosion e = explosion;
        if (!entity.isAlive() || entity.level() != level || entity == direct || entity.ignoreExplosion(e)
                || !Terrain.readyAlong(level, centre, entity.position())) return;
        if (entity instanceof LivingEntity living) {
            BlastBody.Before before = bodyBefore.remove(entity);
            if (before != null) before = before.drifted(entity.position());
            if (!living.isSpectator() && !living.isPassenger()) BlastBody.hit(level, wave(), living, direct, owner, before);
        } else {
            if (lightReach < 0) lightReach = reachFor(LIGHT_PSI, charge);
            if (entity.distanceToSqr(centre) <= lightReach * lightReach && Explosion.getSeenPercent(airOrigin(), entity) > 0)
                entity.hurt(source, BREAKS_ANYTHING);
        }
    }

    /** Snapshot bodies under the same per-unit budget as damage, before any front blocks disappear. */
    private boolean remember(ServerLevel level) {
        int remembered = 0;
        while (nextEntity < entities.size() && remembered < ENTITIES_PER_UNIT) {
            Entity entity = entities.get(nextEntity++);
            if (entity instanceof LivingEntity living) {
                remembered++;
                if (entity.level() == level && Terrain.readyAlong(level, centre, entity.position()))
                    bodyBefore.put(entity, BlastBody.before(level, wave(), living));
            }
        }
        if (nextEntity == entities.size() && scanCrafts(level)) {
            nextEntity = 0;
            if (!craftFront.isEmpty()) holdFrontArea(level, frontCraftPower);
            phase = Phase.FRONT;
            // ready() runs once per queue slice. Yield before the next unit, then use its finite loading deadline.
            if (frontArea != null && !frontArea.ready(level)) frontDueTick = level.getGameTime() + 1;
        }
        return true;
    }

    /** Light-structure break reach, blocks (−1 not calculated). */
    private double lightReach = -1;
    /** Light wooden structure threshold ({@code nuke_light}), psi: also breaks unsecured objects. */
    private static final double LIGHT_PSI = 4;
    /** Damage breaking any healthless entity (item, minecart, item frame). */
    private static final float BREAKS_ANYTHING = 1000;

    /**
     * Create Aeronautics craft in reach: blocks in Sable plots, unseen by world rays. Like nuclear wave ({@code
     * BlastFront}): vanilla center explosion whose rays Sable also traces through craft (and pushes it); same
     * pressure/impulse law at world center distance determines craft breaks ({@link CraftCharge}); entities
     * untouched. Power makes weakest-factor (0.7) air ray reach {@link #breakReach}; beyond law breaks nothing.
     * Craft impulse is Sable vanilla-ray behavior, not physical.
     */
    private void crafts(ServerLevel level, CraftFront hit) {
        float craftPower = hit.power();
        if (craftPower <= 0) return;
        Vec3 c = centre;
        double tnt = charge;
        boolean destroy = blocks;
        Entity by = direct, from = owner;
        Warheads.whenReady(level, c, craftReach(craftPower), l -> {
            // During this explosion, handlers see charge ({@link #chargeOf}), and its entity list
            // in {@code Detonate} is empty ({@link #onDetonate}): no damage/knockback or body visibility rays.
            double outer = craftCharge;
            craftCharge = tnt;
            try {
                l.explode(by, ModDamageTypes.source(l, ModDamageTypes.STRIKE, by, from), new CraftCharge(l, c, tnt, hit.id()),
                        c.x, c.y, c.z, craftPower, false, destroy ? Level.ExplosionInteraction.BLOCK : Level.ExplosionInteraction.NONE,
                        ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT);
            } finally {
                craftCharge = outer;
            }
        });
    }

    /** Craft explosion charge currently on server thread ({@link #crafts}); 0 absent. */
    private static double craftCharge;

    /**
     * Vanilla craft-explosion power ({@link #crafts}): factor-0.7 air ray reaches 0.7 × 1.33 power, to farthest
     * craft point within wave reach, capped at {@link #breakReach} (nothing breaks beyond); 0 no craft.
     * Full-reach power would read world to ~1.9 reach and spike tick.
     */
    static float craftPower(Level level, Vec3 centre, double tntKg) {
        double reach = breakReach(tntKg), far = 0;
        if (!SubLevels.mayHaveCraftNear(level, centre, reach)) return 0;
        for (var sub : SubLevels.near(level, centre, reach)) {
            AABB box = sub.boundingBox().toMojang();
            double dx = Math.max(Math.abs(box.minX - centre.x), Math.abs(box.maxX - centre.x));
            double dy = Math.max(Math.abs(box.minY - centre.y), Math.abs(box.maxY - centre.y));
            double dz = Math.max(Math.abs(box.minZ - centre.z), Math.abs(box.maxZ - centre.z));
            far = Math.max(far, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return far <= 0 ? 0 : (float) Math.min(60, Math.max(1, Math.min(reach, far) / (0.7 * 1.33)));
    }

    /** World read reach of craft blast {@code power}: factor-1.3 ray, 1.73 power, plus one block. */
    static double craftReach(float power) {
        return power * 1.73 + 1;
    }

    /** Craft explosion: plot blocks only, pressure/impulse law at world positions; no entity damage/knockback. */
    private static final class CraftCharge extends ExplosionDamageCalculator {
        private final Level level;
        private final Vec3 centre;
        private final double tnt;
        private final java.util.UUID craftId;

        CraftCharge(Level level, Vec3 centre, double tnt, java.util.UUID craftId) {
            this.level = level;
            this.centre = centre;
            this.tnt = tnt;
            this.craftId = craftId;
        }

        @Override
        public Optional<Float> getBlockExplosionResistance(Explosion explosion, BlockGetter reader, BlockPos pos,
                                                            BlockState state, FluidState fluid) {
            // A hull block that the pressure/impulse law breaks cannot also stop the ray by vanilla TNT hardness.
            // Surviving hull and world terrain retain their resistance. Failure within this craft event is instantaneous.
            if (shouldBlockExplode(explosion, reader, pos, state, 1)) return Optional.of(0f);
            return super.getBlockExplosionResistance(explosion, reader, pos, state, fluid);
        }

        @Override
        public boolean shouldBlockExplode(Explosion explosion, BlockGetter reader, BlockPos pos, BlockState state, float power) {
            if (state.isAir() || !craftId.equals(SubLevels.containingId(level, new ChunkPos(pos)))) return false;
            double r = Math.max(0.25, SubLevels.toWorld(level, pos.getCenter()).distanceTo(centre));
            return BlockResponse.of(state).breaksAt(BlastModel.psi(BlastModel.surfaceOverpressureKpa(r, tnt)),
                    BlastModel.chemicalImpulse(r, tnt, true), Mth.murmurHash3Mixer(Long.hashCode(pos.asLong())));
        }

        @Override
        public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
            return false;
        }

        @Override
        public float getKnockbackMultiplier(Entity entity) {
            return 0;
        }
    }

    /** Charges of ongoing charge explosions (event handlers, {@link #chargeOf}). */
    private static final java.util.Map<Explosion, Double> CHARGES = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Physical mod explosion charge, kg TNT; 0 vanilla/other explosion. */
    static double chargeOf(Explosion e) {
        Double c = CHARGES.get(e);
        return c != null ? c : craftCharge;
    }

    /** Charge not canceled ({@code ExplosionEvent.Start}): post-main strike work only if explosion happened. */
    boolean happened() {
        return started && !cancelled;
    }

    /** First unit ran (area ready), claim handler did not cancel. */
    private boolean started, cancelled;

    /** Ray-selected blocks into explosion, collect entities, {@code ExplosionEvent.Detonate}. */
    private boolean detonate(ServerLevel level) {
        Explosion e = explosion;
        e.getToBlow().addAll(chosen);
        chosen.clear();
        strength = new float[0];
        long t = System.nanoTime();
        // Charge blast damages bodies above glass-fragment pressure threshold; vanilla within 2 × power.
        float f2 = charge > 0 ? (float) hitReach : power * 2.0F;
        Vec3 c = centre;
        int x0 = Mth.floor(c.x - f2 - 1.0), x1 = Mth.floor(c.x + f2 + 1.0);
        int y0 = Mth.floor(c.y - f2 - 1.0), y1 = Mth.floor(c.y + f2 + 1.0);
        int z0 = Mth.floor(c.z - f2 - 1.0), z1 = Mth.floor(c.z + f2 + 1.0);
        entities = new ArrayList<>(level.getEntities((Entity) null, new AABB(x0, y0, z0, x1, y1, z1)));
        time(ExplosionStage.ENTITIES, t);
        t = System.nanoTime();
        EventHooks.onExplosionDetonate(level, e, entities, f2);
        time(ExplosionStage.DETONATE, t);
        phase = Phase.SPLIT;
        return true;
    }

    /**
     * Separate selection: Sable plot query once per chunk; craft blocks (except air without fire) wait for damage
     * end ({@link #blowCrafts}) with plot owner; world positions await snapshot ({@link #split}).
     */
    private void partition(ServerLevel level) {
        long t = System.nanoTime();
        Explosion e = explosion;
        List<BlockPos> plot = new ArrayList<>();
        List<Block> plotBlocks = new ArrayList<>();
        List<BlockPos> world = new ArrayList<>();
        // No block destruction/fire means no portion work.
        if (e.interactsWithBlocks() || fire) {
            Long2BooleanOpenHashMap plotChunk = new Long2BooleanOpenHashMap();
            for (BlockPos p : e.getToBlow()) {
                long chunk = ChunkPos.asLong(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
                boolean inPlot = plotChunk.computeIfAbsent(chunk, c -> SubLevels.inPlotGrid(level, new ChunkPos(c)));
                if (!inPlot) {
                    world.add(p);
                    continue;
                }
                BlockState s = level.getBlockState(p);
                if (!fire && s.isAir()) continue;
                plot.add(p);
                plotBlocks.add(s.getBlock());
                if (!craftOwners.containsKey(chunk)) craftOwners.put(chunk, SubLevels.containingId(level, new ChunkPos(chunk)));
            }
        }
        e.clearToBlow();
        picked = world;
        crafts = plot;
        craftBlocks = plotBlocks;
        partitioned = true;
        time(ExplosionStage.SPLIT, t);
    }

    /**
     * Craft blocks removed together after damage, like vanilla ({@code explode()} damages entities, {@code
     * finalizeExplosion} removes blocks): damage/knockback see intact hull ({@code getSeenPercent} ray also
     * traces craft via Sable). Several ticks since separation can split craft/reassign plot; changed blocks or
     * chunks now owned by another craft untouched.
     */
    private void blowCrafts(ServerLevel level) {
        if (crafts.isEmpty()) return;
        long t = System.nanoTime();
        List<BlockPos> part = new ArrayList<>(crafts.size());
        Long2BooleanOpenHashMap same = new Long2BooleanOpenHashMap();
        for (int i = 0; i < crafts.size(); i++) {
            BlockPos p = crafts.get(i);
            long chunk = ChunkPos.asLong(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
            boolean owner = same.computeIfAbsent(chunk, c -> Objects.equals(craftOwners.get(c), SubLevels.containingId(level, new ChunkPos(c))));
            if (owner && level.getBlockState(p).is(craftBlocks.get(i))) part.add(p);
        }
        crafts = List.of();
        craftBlocks = List.of();
        craftOwners.clear();
        blow(level, part);
        time(ExplosionStage.CRAFTS, t);
    }

    /**
     * Process selected after separation: discard air without fire (power 20 selects tens of thousands, mostly
     * air), world outward by precalculated distance. {@link #portion} identity comes from processing time after
     * all rays, not ray time.
     */
    private boolean split(ServerLevel level) {
        if (!partitioned) partition(level);
        long t = System.nanoTime();
        List<Target> world = new ArrayList<>(picked.size());
        for (BlockPos p : picked) {
            BlockState s = level.getBlockState(p);
            if (fire || !s.isAir()) world.add(new Target(charge > 0 ? new AABB(p).distanceToSqr(centre) : p.distToCenterSqr(at), p, s.getBlock()));
        }
        picked = List.of();
        time(ExplosionStage.SPLIT, t);
        t = System.nanoTime();
        world.sort(Comparator.comparingDouble(Target::distSqr));
        List<BlockPos> kept = new ArrayList<>(world.size());
        List<Block> seen = new ArrayList<>(world.size());
        for (Target w : world) {
            kept.add(w.pos());
            seen.add(w.block());
        }
        toBlow = kept;
        blocksAtRays = seen;
        time(ExplosionStage.SNAPSHOT, t);
        phase = vanillaDamage ? Phase.BLOCKS : Phase.DAMAGE;
        if (charge > 0) {
            entities.sort(Comparator.comparingDouble(a -> a.getBoundingBox().distanceToSqr(centre)));
            bodyRanges = entities.stream().map(a -> a.getBoundingBox().distanceToSqr(centre)).toList();
            phase = Phase.REMEMBER;
            // Even with an empty world/body list, FRONT must run the separate Sable craft blast.
            return true;
        }
        return phase == Phase.DAMAGE || !toBlow.isEmpty();
    }

    /** Damage/knockback {@link #ENTITIES_PER_UNIT} entities: vanilla {@code Explosion.explode} loop copy. */
    private boolean damage(ServerLevel level) {
        long t = System.nanoTime();
        Explosion e = explosion;
        float f2 = power * 2.0F;
        int end = Math.min(entities.size(), nextEntity + ENTITIES_PER_UNIT);
        for (int i = nextEntity; i < end; i++) {
            Entity entity = entities.get(i);
            // Entity may have died/changed dimension between units.
            if (!entity.isAlive() || entity.level() != level || entity.ignoreExplosion(e)) continue;
            double d11 = Math.sqrt(entity.distanceToSqr(at)) / (double) f2;
            if (d11 > 1.0) continue;
            double d5 = entity.getX() - at.x;
            double d7 = (entity instanceof PrimedTnt ? entity.getY() : entity.getEyeY()) - at.y;
            double d9 = entity.getZ() - at.z;
            double d12 = Math.sqrt(d5 * d5 + d7 * d7 + d9 * d9);
            if (d12 == 0.0) continue;
            d5 /= d12;
            d7 /= d12;
            d9 /= d12;
            if (damage.shouldDamageEntity(e, entity)) entity.hurt(source, damage.getEntityDamageAmount(e, entity));
            double d13 = (1.0 - d11) * (double) Explosion.getSeenPercent(at, entity) * (double) damage.getKnockbackMultiplier(entity);
            double d10 = entity instanceof LivingEntity living ? d13 * (1.0 - living.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)) : d13;
            Vec3 push = EventHooks.getExplosionKnockback(level, e, entity, new Vec3(d5 * d10, d7 * d10, d9 * d10));
            entity.setDeltaMovement(entity.getDeltaMovement().add(push));
            if (entity instanceof Player player && !player.isSpectator() && (!player.isCreative() || !player.getAbilities().flying)) {
                e.getHitPlayers().put(player, push);
                if (player instanceof ServerPlayer sp && sp.distanceToSqr(at.x, at.y, at.z) < 4096.0) tell(sp, push);
            }
            // Like vanilla: player remembers impulse for fall damage/mace; vanilla distinguishes cause only
            // for wind charge, so do not pass projectile.
            entity.onExplosionHit(null);
        }
        nextEntity = end;
        time(ExplosionStage.DAMAGE, t);
        if (nextEntity < entities.size()) return true;
        // Vanilla sends packet to all players within 64 blocks, even without knockback.
        for (ServerPlayer p : level.players()) {
            if (!told.contains(p) && p.distanceToSqr(at.x, at.y, at.z) < 4096.0) tell(p, null);
        }
        entities = List.of();
        blowCrafts(level);
        phase = Phase.BLOCKS;
        return !toBlow.isEmpty();
    }

    /**
     * {@code ExplosionEvent.Detonate}, {@code LOWEST} priority (all handlers already saw/edited list): for
     * explosion whose vanilla {@code explode()} mod runs, take entity list for mod damage, leave event list empty
     * so subsequent vanilla loop hits nobody. Event list mutable by NeoForge design, {@code getAffectedEntities}.
     * Same-priority handlers registered later see empty list only for this explosion. Other explosions (including
     * handler-created nested ones) are distinct objects, untouched.
     */
    static void onDetonate(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        if (craftCharge > 0 && CHARGES.get(event.getExplosion()) == null) {
            event.getAffectedEntities().clear();
            return;
        }
        if (driving == null || event.getExplosion() != driving || handed != null) return;
        List<Entity> list = event.getAffectedEntities();
        handed = new ArrayList<>(list);
        list.clear();
    }

    /** Vanilla player explosion packet: own knockback, no blocks (clients see removals as they happen). */
    private void tell(ServerPlayer p, @Nullable Vec3 push) {
        Explosion e = explosion;
        told.add(p);
        p.connection.send(new ClientboundExplodePacket(at.x, at.y, at.z, power, List.of(), push,
                e.getBlockInteraction(), e.getSmallExplosionParticles(), e.getLargeExplosionParticles(), e.getExplosionSound()));
    }

    /** Last portion's removed count (exclude blocks changed since rays), for “blocks removed”. */
    private int blown;

    /**
     * Timed world block portion: continue while under {@link #portionNanos}, always at least one. Outward {@link
     * #BATCH} batches, shuffled inside (vanilla shuffles whole list); unfinished batch continues next portion.
     * Fire uses this portion's removals. Block exceeding {@link #SLOW_BLOCK_NANOS} logged ({@link
     * BlastArea#slowBlock}).
     */
    private boolean portion(ServerLevel level) {
        Explosion e = explosion;
        List<BlockPos> hit = new ArrayList<>();
        LongSupplier clock = this.clock != null ? this.clock : WorkScheduler.impactClock(level.getServer()).sampler();
        long t0 = clock.getAsLong(), last = t0;
        while (!batch.isEmpty() || next < toBlow.size()) {
            if (batch.isEmpty()) {
                int end = Math.min(toBlow.size(), next + BATCH);
                for (int i = next; i < end; i++) batch.add(i);
                next = end;
                Util.shuffle(batch, level.random);
            }
            int i = batch.removeInt(batch.size() - 1);
            BlockPos p = toBlow.get(i);
            if (!Terrain.ready(level, p)) continue;
            BlockState s = level.getBlockState(p);
            if (!s.is(blocksAtRays.get(i))) continue;
            if (e.interactsWithBlocks()) {
                PoiRecords.recordWithNeighbours(level, p, s);
                s.onExplosionHit(level, p, e, (stack, at) -> addOrAppend(stack, at));
            }
            hit.add(p);
            long now = clock.getAsLong();
            if (now - last > SLOW_BLOCK_NANOS) area.slowBlock(level, s, p, now - last);
            last = now;
            if (now - t0 >= portionNanos) break;
        }
        blown = hit.size();
        portions++;
        largestPortion = Math.max(largestPortion, blown);
        if (fire) burn(level, hit);
        return !batch.isEmpty() || next < toBlow.size();
    }

    /** Ray-selected world position: center distance (order key), block identity at ray time. */
    private record Target(double distSqr, BlockPos pos, Block block) {}

    /** {@code Explosion.finalizeExplosion} on partial list: removal (shared drops list), fire. */
    private void blow(ServerLevel level, List<BlockPos> part) {
        Explosion e = explosion;
        if (part.isEmpty() || e == null) return;
        if (e.interactsWithBlocks()) {
            Util.shuffle(part, level.random);
            for (BlockPos p : part) {
                BlockState s = level.getBlockState(p);
                PoiRecords.recordWithNeighbours(level, p, s);
                s.onExplosionHit(level, p, e, (stack, at) -> addOrAppend(stack, at));
            }
        }
        if (fire) burn(level, part);
    }

    /** {@code Explosion.finalizeExplosion} fire: one third of removals, air over solid. */
    private void burn(ServerLevel level, List<BlockPos> part) {
        for (BlockPos p : part) {
            if (level.random.nextInt(3) == 0 && level.getBlockState(p).isAir() && level.getBlockState(p.below()).isSolidRender(level, p.below())) {
                level.setBlockAndUpdate(p, BaseFireBlock.getState(level, p));
            }
        }
    }

    /** {@code Explosion.addOrAppendStack}: identical items merge (up to 16) at first location. */
    private void addOrAppend(ItemStack stack, BlockPos pos) {
        for (int i = 0; i < drops.size(); i++) {
            Pair<ItemStack, BlockPos> pair = drops.get(i);
            ItemStack have = pair.getFirst();
            if (ItemEntity.areMergable(have, stack)) {
                drops.set(i, Pair.of(ItemEntity.merge(have, stack, 16), pair.getSecond()));
                if (stack.isEmpty()) return;
            }
        }
        drops.add(Pair.of(stack, pos));
    }

    private void popDrops(ServerLevel level) {
        for (Pair<ItemStack, BlockPos> pair : drops) {
            if (Terrain.ready(level, pair.getSecond())) Block.popResource(level, pair.getSecond(), pair.getFirst());
        }
        drops.clear();
    }

    @Override
    public void end(ServerLevel level) {
        // Canceled/failed midway: drops from removed blocks still released.
        popDrops(level);
        done = true;
        area.recordCounts(counts);
        if (frontArea != null) frontArea.release(level);
        area.release(level);
    }

    int slowBlocks() {
        return area.slowBlocks();
    }

    @Override
    public String describe() {
        if (charge > 0) return String.format(Locale.ROOT, "Blast of %.1f kg TNT at %d %d %d", charge, Mth.floor(at.x), Mth.floor(at.y), Mth.floor(at.z));
        return String.format(Locale.ROOT, "Explosion of power %.0f at %d %d %d", power, Mth.floor(at.x), Mth.floor(at.y), Mth.floor(at.z));
    }
}
