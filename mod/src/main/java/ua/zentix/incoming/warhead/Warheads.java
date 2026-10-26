package ua.zentix.incoming.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.model.FuelFireball;
import ua.zentix.incoming.nuclear.model.GroundShock;
import ua.zentix.incoming.nuclear.model.HearingModel;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.registry.ModDataMaps;
import ua.zentix.incoming.registry.ModParticles;
import ua.zentix.incoming.registry.ModTags;
import ua.zentix.incoming.strike.ImpactCost;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.Timeline;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.nuclear.world.CraterJob;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.work.UnitQueue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Warheads. Server timeline handles destruction/damage: main vanilla {@link Explosion} (claims respected, Sable
 * pushes craft itself), secondary detonations, glass, debris, sonic-speed blast wave. Client builds all
 * visuals/sound from {@link S2C.Blast}: particles, flash, distance-delayed sound, shake.
 */
public final class Warheads {
    /** Sound/blast front: 343 m/s = 17.15 blocks/tick. */
    public static final double FRONT_SPEED = BlastModel.SOUND_SPEED / 20;

    private Warheads() {}

    // ---------------------------------------------------------------- entry

    public static void detonate(ServerLevel level, WeaponType weapon, Vec3 pos, @Nullable Entity projectile, @Nullable UUID owner) {
        long t0 = System.nanoTime();
        StrikeWorld world = StrikeWorld.get(level);
        world.add(new SurfaceBlast(level, weapon, pos, projectile, owner));
        world.impactCost().step(System.nanoTime() - t0);
    }

    /** Same complete surface impact with a fixed source seed, for reproducible fixtures; no world RNG reseeding. */
    public static void testDetonate(ServerLevel level, WeaponType weapon, Vec3 pos, @Nullable Entity projectile, long seed) {
        StrikeWorld.get(level).add(new SurfaceBlast(level, weapon, pos, projectile, null, seed));
    }

    /**
     * Bunker-buster entered ground at {@code speed} m/s: no explosion (delay fuze), body-ground impact. Clients
     * get entry ejecta dust/impact sound; nearby players get ground impulse from impact energy.
     */
    public static void bunkerEntry(ServerLevel level, Vec3 point, double speed, @Nullable Entity bomb, @Nullable UUID owner) {
        long t0 = System.nanoTime();
        GroundMaterial mat = GroundMaterial.sample(level, BlockPos.containing(point));
        double hit = BunkerImpact.equivalentTnt(WeaponSpec.BUNKER.payload().article().dryMass(), speed);
        double reach = Math.max(IncomingConfig.SERVER.farRange.get(), Math.min(IncomingConfig.FAR_RANGE_MAX,
                HearingModel.audibleRangeM(BunkerImpact.airTnt(hit))));
        PacketDistributor.sendToPlayersNear(level, null, point.x, point.y, point.z, reach, new S2C.BunkerImpact(point, mat.ordinal(), (float) speed));
        // Estimate: use equal TNT energy for the kinetic contact pulse; this is not a measured impact spectrum.
        // TM coupling is applied once to the full source energy, not again after subtracting its airborne share.
        quake(level, new S2C.Quake(point, hit, 0, mat.ordinal(), level.getGameTime()));
        StrikeWorld.get(level).impactCost().step(System.nanoTime() - t0);
    }

    /**
     * Ground source, not a command to shake now. GroundShock decides the perceptible recipient range; the client
     * computes arrival and motion for its own position. In loose ground it may arrive AFTER the air front.
     */
    static void quake(ServerLevel level, S2C.Quake event) {
        S2C.Quake.Ground source = event.source().orElseThrow();
        double reach = GroundShock.feltRangeM(source.model());
        if (!(reach > 0)) return;
        for (ServerPlayer p : level.players()) {
            if (p.position().distanceTo(source.pos()) <= reach) PacketDistributor.sendToPlayer(p, event);
        }
    }

    /** Underground bunker-buster detonation. */
    public static void bunker(ServerLevel level, Vec3 pos, Vec3 entry, @Nullable Entity bomb, @Nullable UUID owner) {
        long t0 = System.nanoTime();
        StrikeWorld world = StrikeWorld.get(level);
        world.add(new BunkerBlast(level, pos, entry, bomb, owner));
        world.impactCost().step(System.nanoTime() - t0);
    }

    // ---------------------------------------------------------------- shared helpers

    /**
     * World read reach of vanilla {@code power} blast: rays attenuate within 1.3·power / 0.225 steps of 0.3
     * blocks (≈1.73·power); damage searches entities within 2·power, visibility ray to each.
     */
    public static double reach(float power) {
        return power * 2 + 1;
    }

    /**
     * Run world-reading action within {@code reach} of {@code centre} (vanilla blast) once ready: immediately if
     * ready, otherwise after {@link BlastArea} background load. Unready reads load synchronously in tick;
     * secondary salvo burst at loaded-world edge stalled 1.2 s.
     */
    public static void whenReady(ServerLevel level, Vec3 centre, double reach, Consumer<ServerLevel> action) {
        if (Terrain.readyAround(level, centre, reach)) {
            action.accept(level);
        } else {
            StrikeWorld.get(level).add(new Deferred(BlastArea.hold(level, centre, reach), action));
        }
    }

    /**
     * Vanilla blast without sound/particles (client supplies delayed sound/visual): TNT destruction rules, custom
     * damage type (“airstrike victim”), claims/Sable normal. Impact-queue work units ({@link StagedExplosion})
     * under shared tick budget, ready chunks only.
     *
     * @param area timeline area covering this explosion, or null to acquire own
     * @param after explosions required to finish first (main strike); remove blocks once same-area/same-tick
     * explosions selected rays/damaged entities ({@link BlastArea#peersPicking})
     */
    static StagedExplosion explode(ServerLevel level, @Nullable BlastArea area, List<StagedExplosion> after, Vec3 at, float power,
                                   boolean fire, @Nullable Entity direct, @Nullable Entity owner, @Nullable ExplosionDamageCalculator calculator) {
        boolean blocks = IncomingConfig.SERVER.blockDamage.get();
        boolean burns = fire && IncomingConfig.SERVER.fire.get();
        BlastArea held = area != null ? area.retain() : BlastArea.hold(level, at, reach(power));
        StagedExplosion job = new StagedExplosion(held, after, at, power, burns, blocks, direct, owner, calculator);
        held.queued(job, level.getGameTime());
        StrikeWorld.get(level).impacts().add(level, job);
        return job;
    }

    /**
     * Tests: nondestructive blast (ray selection visible in {@code ExplosionEvent.Detonate}), {@code
     * level.random} seeded {@code seed} before rays, vanilla {@code explode()} or own {@code raysPerUnit} loop.
     */
    public static Probe testRays(ServerLevel level, Vec3 at, float power, @Nullable ExplosionDamageCalculator calculator, long seed,
                                 boolean vanilla, int raysPerUnit) {
        StagedExplosion job = new StagedExplosion(BlastArea.hold(level, at, reach(power)), List.of(), at, power, false, false, null, null, calculator);
        job.seed = seed;
        job.forceVanilla = vanilla;
        job.raysPerUnit = raysPerUnit;
        StrikeWorld.get(level).impacts().add(level, job);
        return new Probe(job);
    }

    /**
     * Tests: blast (destruction {@code blocks}), vanilla {@code explode()} ({@code vanilla}, like craft) or own
     * loop, block portions on {@code clock} with {@code portionNanos} deadline.
     */
    public static Probe testBlast(ServerLevel level, Vec3 at, float power, boolean blocks, boolean vanilla, LongSupplier clock,
                                  long portionNanos) {
        StagedExplosion job = new StagedExplosion(BlastArea.hold(level, at, reach(power)), List.of(), at, power, false, blocks, null, null, null);
        job.forceVanilla = vanilla;
        job.clock = clock;
        job.portionNanos = portionNanos;
        StrikeWorld.get(level).impacts().add(level, job);
        return new Probe(job);
    }

    /** Tests: result of {@link #testBlast} or {@link #testRays}. */
    public static final class Probe {
        private final StagedExplosion job;

        private Probe(StagedExplosion job) {
            this.job = job;
        }

        public boolean done() {
            return job.done();
        }

        /** Used vanilla {@code explode()} (craft nearby or requested). */
        public boolean vanilla() {
            return job.vanilla();
        }

        /** Still selecting rays/damaging entities (not removing world blocks). */
        public boolean picking() {
            return job.picking();
        }

        /** Vanilla path handed damage to mod: own units hit {@code Detonate} entities, not {@code explode()} loop. */
        public boolean handedDamage() {
            return job.handedDamage();
        }

        /** World block portions. */
        public int portions() {
            return job.portions;
        }

        /** Largest portion, blocks. */
        public int largestPortion() {
            return job.largestPortion;
        }

        /** Blocks exceeding {@code SLOW_BLOCK_NANOS} per blast. */
        public int slowBlocks() {
            return job.slowBlocks();
        }

        /** Obstacle-breach removals ({@link SlabBreach}). */
        public int breached() {
            return job.breached;
        }
    }

    /** {@code ExplosionEvent.Detonate}, {@code LOWEST}: vanilla craft-blast damage handed to mod units. */
    public static void onDetonate(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        StagedExplosion.onDetonate(event);
    }

    /** Whether all {@code after} explosions finished. */
    static boolean pending(List<StagedExplosion> after) {
        for (StagedExplosion e : after) if (!e.done()) return true;
        return false;
    }

    /**
     * One impact work unit queued after {@code after} explosions (main strike), when {@code area} ready.
     *
     * @param work completed count (blocks/debris) for measurement
     */
    static void unit(ServerLevel level, BlastArea area, List<StagedExplosion> after, ImpactCost.Kind kind, String what,
                     ToIntFunction<ServerLevel> work) {
        StrikeWorld.get(level).impacts().add(level, new Unit(area.retain(), after, kind, what, work));
    }

    private record Unit(BlastArea area, List<StagedExplosion> after, ImpactCost.Kind kind, String what,
                        ToIntFunction<ServerLevel> work) implements UnitQueue.Job {
        @Override
        public boolean ready(ServerLevel level) {
            return area.ready(level);
        }

        @Override
        public boolean blocked() {
            return pending(after);
        }

        @Override
        public int unitKind() {
            return kind.ordinal();
        }

        @Override
        public boolean step(ServerLevel level) {
            long t0 = System.nanoTime();
            int n = work.applyAsInt(level);
            area.record(level, kind, System.nanoTime() - t0, n);
            return false;
        }

        @Override
        public void end(ServerLevel level) {
            area.release(level);
        }

        @Override
        public String describe() {
            Vec3 c = area.centre();
            return what + " at " + Mth.floor(c.x) + " " + Mth.floor(c.y) + " " + Mth.floor(c.z);
        }
    }

    /**
     * Deferred blast ({@link #whenReady}) area wait: ticket loads in seconds; after 5 minutes (world not
     * loading), cancel.
     */
    public static final int DEFERRED_GIVE_UP_TICKS = 6000;

    /** Blast awaiting area readiness ({@link #whenReady}). */
    private static final class Deferred implements Timeline {
        private final BlastArea area;
        private final Consumer<ServerLevel> action;
        private int waited;

        Deferred(BlastArea area, Consumer<ServerLevel> action) {
            this.area = area;
            this.action = action;
        }

        @Override
        public boolean tick(ServerLevel level) {
            if (area.ready(level)) {
                action.accept(level);
                return false;
            }
            if (++waited < DEFERRED_GIVE_UP_TICKS) return true;
            Vec3 c = area.centre();
            Incoming.LOG.warn("Blast at {} {} {} cancelled: area did not load within {} ticks",
                    Mth.floor(c.x), Mth.floor(c.y), Mth.floor(c.z), DEFERRED_GIVE_UP_TICKS);
            return false;
        }

        @Override
        public void end(ServerLevel level) {
            area.release(level);
        }
    }

    /**
     * Break all {@code tag} in box around {@code center} immediately, without queue ({@link Shatter}: ready
     * chunks only, no neighbor updates at loaded edge). Tests.
     *
     * @return broken blocks
     */
    public static int shatter(ServerLevel level, Vec3 center, int radius, int below, int above, TagKey<Block> tag) {
        return Shatter.now(level, center, Shatter.Box.around(center, radius, below, above, tag));
    }

    /**
     * Immediately shatter glass with surface {@code tntKg} kg TNT wave, no queue ({@link Shatter.Wave}). Tests.
     *
     * @return shattered glass count
     */
    public static int shatterGlass(ServerLevel level, Vec3 center, double tntKg) {
        return Shatter.now(level, center, Shatter.Wave.of(center, tntKg));
    }

    /** Glass-shattering reach of {@code tntKg} kg TNT wave, blocks. */
    public static double glassReach(double tntKg) {
        return Shatter.Wave.of(Vec3.ZERO, tntKg).radius();
    }

    /**
     * Ready-chunk margin around block changed without neighbor updates ({@link #EDGE_FLAGS}): block plus
     * Sable-read neighbors, one-block margin.
     */
    static final int EDGE = 2;
    /** Loaded-edge change: clients, no neighbor shapes/updates. */
    static final int EDGE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * Ignite ground: fire on column top (like fallen fireballs). Ready chunks only: missile fire ring 7 blocks
     * from impact reached still-generating chunks at loaded edge; {@code igniteAt} block read would load inside
     * tick, already-loading height/block reads wait in {@code managedBlock}.
     */
    public static void igniteGround(ServerLevel level, double x, double z) {
        if (!IncomingConfig.SERVER.fire.get() || !Terrain.readyAround(level, new Vec3(x, 0, z), EDGE)) return;
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(x, 0, z));
        igniteAt(level, top);
    }

    /**
     * Fire at block if air and survives. Require block chunk/all neighbors ready: {@code setBlock} updates
     * neighbors and chains farther (nearby door redstone check); Sable reads neighboring blocks ({@code
     * SableCommonEvents.handleBlockChange}), synchronously loading at edge.
     */
    static boolean igniteAt(ServerLevel level, BlockPos pos) {
        if (!IncomingConfig.SERVER.fire.get() || !Terrain.neighbourhoodReady(level, pos.getX() >> 4, pos.getZ() >> 4)) return false;
        return level.getBlockState(pos).isAir() && igniteIn(level, pos);
    }

    /**
     * Fire replacing {@code pos} contents (air or shapeless plant burned in place like vanilla), if survives;
     * same readiness as {@link #igniteAt}. Returns placed.
     */
    static boolean igniteIn(ServerLevel level, BlockPos pos) {
        if (!IncomingConfig.SERVER.fire.get() || !Terrain.neighbourhoodReady(level, pos.getX() >> 4, pos.getZ() >> 4)) return false;
        BlockState fire = BaseFireBlock.getState(level, pos);
        if (!fire.canSurvive(level, pos)) return false;
        level.setBlock(pos, fire, 11);
        return true;
    }

    // ================================================================ Shahed, missile, Grad, Lancet

    /**
     * Physical {@code tntKg} kg TNT charge explosion ({@link StagedExplosion}: Kinney–Graham wave, block
     * pressure–impulse curve, room gases, {@code BlastBody}) in impact-queue work units.
     */
    static StagedExplosion charge(ServerLevel level, BlastArea area, Vec3 at, double tntKg, SlabBreach.Placement placement,
                                  @Nullable Vec3 axis, @Nullable Entity direct, @Nullable Entity owner) {
        boolean blocks = IncomingConfig.SERVER.blockDamage.get();
        StagedExplosion job = new StagedExplosion(area.retain(), List.of(), at, tntKg, placement, axis, blocks, direct, owner);
        area.queued(job, level.getGameTime());
        StrikeWorld.get(level).impacts().add(level, job);
        return job;
    }

    /** Tests: physical {@code tntKg} kg TNT charge explosion with own area. */
    public static Probe testCharge(ServerLevel level, Vec3 at, double tntKg) {
        BlastArea area = BlastArea.hold(level, at, chargeReach(tntKg, 0));
        Probe probe = new Probe(charge(level, area, at, tntKg, SlabBreach.Placement.NONE, null, null, null));
        area.release(level);
        return probe;
    }

    /**
     * Tests: buried {@code tntKg} kg TNT (ground-penetrating bomb), wave/breach into voids ({@link SlabBreach});
     * area {@code reach} blocks (like bomb, not whole surface-wave reach).
     */
    public static Probe testBuriedCharge(ServerLevel level, Vec3 at, double tntKg, double reach) {
        return testPlaced(level, at, tntKg, SlabBreach.Placement.BURIED, reach);
    }

    /** Tests: {@code tntKg} kg TNT on face (warhead impact), area {@code reach}. */
    public static Probe testSurfaceCharge(ServerLevel level, Vec3 at, double tntKg, double reach) {
        return testSurfaceCharge(level, at, tntKg, reach, S2C.Blast.DOWN);
    }

    /** Tests: same with warhead impact {@code axis} (oblique/side wall hit). */
    public static Probe testSurfaceCharge(ServerLevel level, Vec3 at, double tntKg, double reach, Vec3 axis) {
        return testPlaced(level, at, tntKg, SlabBreach.Placement.SURFACE, reach, axis);
    }

    private static Probe testPlaced(ServerLevel level, Vec3 at, double tntKg, SlabBreach.Placement placement, double reach) {
        return testPlaced(level, at, tntKg, placement, reach, S2C.Blast.DOWN);
    }

    private static Probe testPlaced(ServerLevel level, Vec3 at, double tntKg, SlabBreach.Placement placement, double reach, Vec3 axis) {
        BlastArea area = BlastArea.hold(level, at, reach);
        Probe probe = new Probe(charge(level, area, at, tntKg, placement, axis, null, null));
        area.release(level);
        return probe;
    }

    /**
     * Impact-axis support for {@code tntKg} kg TNT contacting face at {@code at} ({@link SlabBreach#support}):
     * same breach-rule explosion as charge {@link StagedExplosion} (no calculator, power is break reach), but no
     * events, since it breaks nothing.
     */
    public static Optional<S2C.Blast.Support> support(ServerLevel level, Vec3 at, double tntKg, @Nullable Vec3 axis, @Nullable Entity direct) {
        Explosion e = new Explosion(level, direct, null, null, at.x, at.y, at.z, (float) StagedExplosion.breakReach(tntKg), false,
                Explosion.BlockInteraction.KEEP, ModParticles.NONE.get(), ModParticles.NONE.get(), ua.zentix.incoming.registry.ModSounds.SILENT);
        return SlabBreach.support(level, at, tntKg, axis, e, new ExplosionDamageCalculator());
    }

    /** Tests: open-field {@code psi}-threshold break reach of charge (lowest jitter), blocks. */
    public static double breakReach(double psi, double tntKg) {
        return StagedExplosion.reachFor(psi, tntKg);
    }

    /** Physical mod blast charge, kg TNT; 0 vanilla/other ({@code ExplosionEvent} handlers). */
    public static double chargeOf(Explosion e) {
        return StagedExplosion.chargeOf(e);
    }

    /**
     * Charge strike area: wave rays ({@link StagedExplosion#breakReach}), crater rim, fuel ball; farther bodies
     * damaged where world loaded.
     */
    static double chargeReach(double tntKg, double fuelKg) {
        double crater = 2 * CraterModel.chargeRadius(tntKg, CraterModel.Soil.WET) + 1;
        return Math.max(StagedExplosion.breakReach(tntKg), Math.max(crater, FuelFireball.diameter(FuelFireball.ballKg(fuelKg)) / 2)) + EDGE;
    }

    /**
     * Warhead impact axis: last projectile step; stationary (waiting area), nose direction; no projectile, down.
     */
    static Vec3 axis(@Nullable Entity projectile) {
        if (projectile == null) return S2C.Blast.DOWN;
        Vec3 v = projectile.getDeltaMovement();
        if (v.lengthSqr() < 1e-8) v = projectile.getLookAngle();
        return v.lengthSqr() < 1e-8 ? S2C.Blast.DOWN : v.normalize();
    }

    /** Tests: {@code weapon} warhead fragments from {@code at} with {@code axis}, no explosion/area. */
    public static FragmentStrike testFragments(ServerLevel level, WeaponType weapon, Vec3 at, Vec3 axis, long seed) {
        FragmentModel model = java.util.Objects.requireNonNull(FragmentModel.of(weapon.spec()), "weapon has no fragments");
        return FragmentStrike.queue(level, null, List.of(), () -> true, model, at, axis, seed, null, null,
                IncomingConfig.SERVER.shatterGlass.get(), IncomingConfig.SERVER.blockDamage.get());
    }

    /**
     * Warhead ground explosion: spec charge ({@link WeaponSpec#charge}) wave/gases/bodies (main explosion), then
     * conventional crater, tank-fuel ball ignition ({@link WeaponSpec#fuelAtImpact}), crater fuel as block fire
     * while burning ({@link FuelFires}), debris, casing fragments ({@link FragmentStrike}), glass. No secondary
     * detonations/fire rings: only fuel/burning debris secondary effects.
     */
    static final class SurfaceBlast implements Timeline {
        private final WeaponType weapon;
        private final Vec3 pos;
        @Nullable
        private final Entity direct;
        @Nullable
        private final Entity owner;
        private final GroundMaterial mat;
        private final BlastArea area;
        /** Main explosion: other strike work waits for its removals. */
        private final List<StagedExplosion> main;

        SurfaceBlast(ServerLevel level, WeaponType weapon, Vec3 pos, @Nullable Entity direct, @Nullable UUID ownerId) {
            this(level, weapon, pos, direct, ownerId, level.random.nextLong());
        }

        SurfaceBlast(ServerLevel level, WeaponType weapon, Vec3 pos, @Nullable Entity direct, @Nullable UUID ownerId, long seed) {
            this.weapon = weapon;
            this.pos = pos;
            WeaponSpec spec = weapon.spec();
            double held = chargeReach(spec.charge(), spec.fuelAtImpact());
            // Craft nearby: vanilla-ray craft explosion reads farther ({@code StagedExplosion.crafts}).
            float craft = StagedExplosion.craftPower(level, pos, spec.charge());
            if (craft > 0) held = Math.max(held, StagedExplosion.craftReach(craft));
            this.area = BlastArea.hold(level, pos, held);
            this.direct = direct;
            this.owner = ownerId == null ? null : level.getPlayerByUUID(ownerId);
            this.mat = GroundMaterial.sample(level, BlockPos.containing(pos));
            int kind = switch (spec.blast()) {
                case MISSILE -> S2C.Blast.MISSILE;
                case ROCKET -> S2C.Blast.ROCKET;
                case DRONE, NONE -> S2C.Blast.DRONE;
            };
            // Visible to far_range, audible while charge blast exceeds quiet-night background (HearingModel), capped
            // at server's far-data range.
            double reach = Math.max(IncomingConfig.SERVER.farRange.get(),
                    Math.min(IncomingConfig.FAR_RANGE_MAX, HearingModel.audibleRangeM(spec.charge())));
            this.reach = reach;
            this.born = level.getGameTime();
            this.seed = seed;
            this.axis = axis(direct);
            // Charge on craft: no crater/fuel ball, hull rather than ground. Projectile supplies craft impact point
            // already in world (StrikeProjectile: SubLevels.toWorld); also check craft whose bounds contain point.
            inPlots = SubLevels.inPlotGrid(level, new net.minecraft.world.level.ChunkPos(BlockPos.containing(pos)));
            onCraft = inPlots || !SubLevels.near(level, pos, 1).isEmpty();
            // Struck material and whether slab over void: same breach that will remove slab (client target visual).
            Optional<S2C.Blast.Support> support = onCraft || !(spec.charge() > 0) ? Optional.empty() : support(level, pos, spec.charge(), axis, direct);
            PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, reach,
                    new S2C.Blast(kind, pos, mat.ordinal(), (float) pos.y, seed, Optional.ofNullable(direct).map(Entity::getUUID), weapon.id(), axis,
                            true, support));
            // Warhead bursts at impact face: breaches slab over void (SlabBreach); craft
            // hull is not world obstacle.
            main = spec.charge() > 0 ? List.of(charge(level, area, pos, spec.charge(),
                    onCraft ? SlabBreach.Placement.NONE : SlabBreach.Placement.SURFACE, axis, direct, owner)) : List.of();
        }

        /** Explosion on Sable craft. */
        private final boolean onCraft;
        /** Explosion point in Sable plot grid (craft coordinates, not world). */
        private final boolean inPlots;
        /** Blast seed (also in packet): identical server/client fragments. */
        private final long seed;
        /** Blast/crater-fuel packet range, blocks; impact tick. */
        private final double reach;
        private final long born;
        /** Warhead axis, projectile impact heading. */
        private final Vec3 axis;

        /** Main explosion occurred (claim handler did not cancel). */
        private boolean happened() {
            return !main.isEmpty() && main.getFirst().happened();
        }

        @Override
        public boolean tick(ServerLevel level) {
            boolean missile = weapon.spec().blast() == WeaponSpec.Blast.MISSILE;
            // Post-main work only if explosion happened: claim-canceled blast neither digs nor ignites.
            if (main.isEmpty()) return false;
            if (IncomingConfig.SERVER.blockDamage.get() && !onCraft) {
                unit(level, area, main, ImpactCost.Kind.GROUND, "Crater", l -> happened() ? Craters.dig(l, pos, weapon.spec().charge(), mat, seed) : 0);
            }
            if (weapon.spec().fuelAtImpact() > 0 && !onCraft) {
                Craters.ignite(level, area, main, this::happened, pos, weapon.spec().fuelAtImpact());
                // Crater fuel after crater: settles on bottom, burns as block fire while available.
                unit(level, area, main, ImpactCost.Kind.GROUND, "Fuel fire", l -> happened() ? FuelFires.pour(l, pos, weapon, born, reach) : 0);
            }
            unit(level, area, main, ImpactCost.Kind.DEBRIS, "Debris",
                    l -> !happened() ? 0 : missile ? DebrisSpawner.missile(l, pos, mat) : DebrisSpawner.drone(l, pos, mat));
            FragmentModel fragments = FragmentModel.of(weapon.spec());
            // In Sable plot grid, rays would hit craft blocks: fragments do not break craft.
            if (fragments != null && !inPlots) FragmentStrike.queue(level, area, main, this::happened, fragments, pos, axis, seed, direct, owner,
                    IncomingConfig.SERVER.shatterGlass.get(), IncomingConfig.SERVER.blockDamage.get());
            if (IncomingConfig.SERVER.shatterGlass.get()) Shatter.queue(level, area, main, pos, Shatter.Wave.of(pos, weapon.spec().charge()));
            return false;
        }

        @Override
        public void end(ServerLevel level) {
            area.release(level);
        }

    }

    // ================================================================ bunker-buster bomb

    /**
     * Underground bomb charge ({@link WeaponSpec#warheadTnt}) at scaled depth ({@link BuriedBlast}). Immediately
     * physical charge wave ({@link #charge}) from detonation: nowhere through ground (natural ground casts
     * shadow), but in bunker/cave/cavity voids it damages bodies/walls, room gases sustain pressure. Then work
     * units: cavity/crushing zone, tunnel/cave roofs in fracture zone, shallow ejecta crater (plus surface wave
     * of air-escaped charge share), otherwise bore gases/dust; after ~1 s roof-collapse chimney.
     */
    static final class BunkerBlast implements Timeline {
        /** Cavity roof collapse tick (as before, collapse follows blast by ~1 s, estimate). */
        static final int CHIMNEY_TICK = 22;
        /** Fracture-zone layers per work unit. */
        static final int FRACTURE_SLICES = 1;
        private final Vec3 pos;
        private final Vec3 entry;
        @Nullable
        private final Entity direct;
        @Nullable
        private final Entity owner;
        private final GroundMaterial ventMat;
        /** Material/depth/time sampled before excavation changes the source column. */
        private final S2C.Quake groundShock;
        private boolean groundSent;
        private final BuriedBlast law;
        /** Top above charge, excluding own bore. */
        private final int cover;
        /** Bomb bore cross-section, m². */
        private final double hole;
        private final BlastArea area;
        /** Charge wave (underground voids and above ground on breakout): other work waits. */
        private final List<StagedExplosion> main;
        private int t;

        BunkerBlast(ServerLevel level, Vec3 pos, Vec3 entry, @Nullable Entity direct, @Nullable UUID ownerId) {
            this.pos = pos;
            this.entry = entry;
            this.direct = direct;
            this.owner = ownerId == null ? null : level.getPlayerByUUID(ownerId);
            BlockPos c = BlockPos.containing(pos);
            GroundMaterial mat = GroundMaterial.sample(level, c);
            this.ventMat = GroundMaterial.sample(level, BlockPos.containing(entry));
            this.cover = surfaceAbove(level, c);
            WeaponSpec.Airframe bomb = WeaponSpec.BUNKER.payload();
            this.law = new BuriedBlast(WeaponSpec.BUNKER.warheadTnt(), BunkerCover.depth(cover, pos.y));
            this.hole = Math.PI * bomb.article().diameter() * bomb.article().diameter() / 4;
            double air = law.airCharge(hole);
            // The TM depth-coupling factor already includes venting: W and scaled depth both use the full charge.
            this.groundShock = new S2C.Quake(pos, law.tnt(), law.depth(), mat.ordinal(), level.getGameTime());
            Vec3 top = new Vec3(pos.x, cover, pos.z);
            // Area covers ground effects (fractures, chimney, crater); breakout surface wave breaks farther
            // where loaded (ray ends in unready chunk): 2.4 t wave reaches ~650 blocks, thousands of chunks;
            // whole strike would wait for their loading.
            double held = Math.max(law.fracture(), Math.max(law.chimney(), 2 * law.craterRadius(CraterModel.Soil.WET) + 1)) + EDGE;
            this.area = BlastArea.hold(level, pos, held);

            // Visible to far_range, audible to air-escaped charge share's sound range (HearingModel).
            double reach = Math.max(IncomingConfig.SERVER.farRange.get(), Math.min(IncomingConfig.FAR_RANGE_MAX, HearingModel.audibleRangeM(air)));
            PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, reach,
                    new S2C.Blast(S2C.Blast.BUNKER, pos, mat.ordinal(), cover, level.random.nextLong(), Optional.ofNullable(direct).map(Entity::getUUID),
                            WeaponType.BUNKER.id()));

            // One charge: in voids around charge, fraction not escaped to air; above ground, escaped share (at surface,
            // entire charge as surface wave).
            List<StagedExplosion> blasts = new ArrayList<>(2);
            double below = law.breaches() ? law.tnt() - air : law.tnt();
            // Buried charge tamped: breaches nearby bunker/cave voids (SlabBreach); escaped fraction is wave.
            if (below > 0) blasts.add(charge(level, area, pos, below, SlabBreach.Placement.BURIED, null, direct, owner));
            if (law.breaches() && air > 0) blasts.add(charge(level, area, top.add(0, 0.5, 0), air, SlabBreach.Placement.NONE, null, direct, owner));
            main = List.copyOf(blasts);
        }

        @Override
        public boolean tick(ServerLevel level) {
            t++;
            if (t == 1) {
                boolean blocks = IncomingConfig.SERVER.blockDamage.get();
                // Crushing-zone rock pressure hundreds of MPa: anyone there (bore, void behind wall) cannot survive.
                unit(level, area, main, ImpactCost.Kind.HITS, "Crush zone", l -> {
                    if (!happened()) return 0;
                    return crushBodies(l);
                });
                if (blocks) {
                    // Crater from top before cavity excavates charge column.
                    if (law.breaches()) unit(level, area, main, ImpactCost.Kind.GROUND, "Ejection crater", l -> happened() ? crater(l) : 0);
                    int r = Mth.ceil(law.crush());
                    for (int dy = -r; dy <= r; dy++) {
                        int y = dy;
                        unit(level, area, main, ImpactCost.Kind.GROUND, "Cavity", l -> happened() ? cavitySlice(l, y) : 0);
                    }
                }
                if (law.breaches()) {
                    if (IncomingConfig.SERVER.shatterGlass.get()) {
                        Vec3 top = new Vec3(pos.x, cover, pos.z);
                        Shatter.queue(level, area, main, top, Shatter.Wave.of(top, law.airCharge(hole)));
                    }
                } else {
                    // Cavity gases exit bomb bore.
                    unit(level, area, main, ImpactCost.Kind.DEBRIS, "Vent", l -> {
                        if (!happened()) return 0;
                        double air = law.airCharge(hole);
                        PacketDistributor.sendToPlayersNear(l, null, entry.x, entry.y, entry.z,
                                Math.max(IncomingConfig.SERVER.farRange.get(), Math.min(IncomingConfig.FAR_RANGE_MAX, HearingModel.audibleRangeM(air))),
                                new S2C.Vent(entry, ventMat.ordinal(), (float) air));
                        return 1;
                    });
                }
            }
            if (t == 2 && IncomingConfig.SERVER.blockDamage.get()) {
                // Fracture-zone roofs next tick: cavities of all same-tick detonations queue first (salvo crater falls
                // happen together, not sequentially); FRACTURE_SLICES layers per unit (~3 thousand cells/layer; falling rubble/water entering cavity cause neighbor updates).
                int f = Mth.ceil(law.fracture());
                for (int dy = -f; dy <= f; dy += FRACTURE_SLICES) {
                    int lo = dy, hi = Math.min(f, dy + FRACTURE_SLICES - 1);
                    unit(level, area, main, ImpactCost.Kind.GROUND, "Fractures", l -> {
                        int n = 0;
                        for (int y = lo; y <= hi && happened(); y++) n += fractureSlice(l, y);
                        return n;
                    });
                }
            }
            if (t == CHIMNEY_TICK && IncomingConfig.SERVER.collapse.get() && IncomingConfig.SERVER.blockDamage.get() && !law.breaches()) {
                unit(level, area, main, ImpactCost.Kind.GROUND, "Roof collapse", l -> happened() ? chimney(l) : 0);
            }
            // Send as soon as Start accepted the explosion, not after every damage/front work unit finished.
            // Keep polling if the queue took longer than the chimney timer to start; cancelled/timed-out jobs end it.
            if (!groundSent && happened()) {
                quake(level, groundShock);
                groundSent = true;
            }
            return t < CHIMNEY_TICK || !groundSent && pending(main);
        }

        @Override
        public void end(ServerLevel level) {
            area.release(level);
        }

        /** Charge wave occurred (not claim-canceled): canceled blast neither digs nor collapses. */
        private boolean happened() {
            return !main.isEmpty() && main.getFirst().happened();
        }

        /**
         * Top above charge excluding its bore ({@link BunkerCover}); ignore leaves (canopy not cover). Server
         * only: {@code …_NO_LEAVES} heightmap exists only there.
         */
        private static int surfaceAbove(ServerLevel level, BlockPos c) {
            int[] h = new int[BunkerCover.RING.length];
            for (int i = 0; i < h.length; i++) {
                h[i] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX() + BunkerCover.RING[i][0], c.getZ() + BunkerCover.RING[i][1]);
            }
            return BunkerCover.surface(h);
        }

        /** Bodies in crushing zone. Returns affected count. */
        private int crushBodies(ServerLevel level) {
            double r = law.crush();
            int n = 0;
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(r), LivingEntity::isAlive)) {
                if (e.position().distanceTo(pos) > r) continue;
                e.hurt(ModDamageTypes.source(level, ModDamageTypes.SHOCKWAVE, direct, owner), Float.MAX_VALUE);
                n++;
            }
            return n;
        }

        /**
         * Layer {@code dy} above charge: cavity (everything not {@link Penetration.Kind#STOP} becomes air),
         * crushing zone (rock to cobblestone, concrete/masonry to gravel; soil compacts unchanged). Returns
         * blocks inspected.
         */
        private int cavitySlice(ServerLevel level, int dy) {
            BlockPos c = BlockPos.containing(pos);
            double rc = law.cavity(), rk = law.crush();
            int r = Mth.ceil(rk), n = 0;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > rk) continue;
                    p.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (!level.isInWorldBounds(p) || !Terrain.ready(level, p)) continue;
                    n++;
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    ModDataMaps.Penetrability m = ModDataMaps.Penetrability.of(s);
                    if (m.kind() == Penetration.Kind.STOP || m.kind() == Penetration.Kind.FLUID) continue;
                    if (d <= rc) {
                        ColumnScar.replace(level, p.immutable(), s, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        continue;
                    }
                    // Crush mapped rock/masonry, not others (chest, mod mechanism).
                    BlockState rubble = rubble(s, ModDataMaps.Penetrability.find(s));
                    if (rubble != null && !s.is(rubble.getBlock())) ColumnScar.replace(level, p.immutable(), s, rubble, Block.UPDATE_ALL);
                }
            }
            return n;
        }

        /**
         * Crushing-zone conversion: stone to cobblestone, deepslate to its cobblestone, other
         * rock/concrete/masonry to gravel (rubble); soil compacts unchanged; unmapped unchanged.
         */
        @Nullable
        private static BlockState rubble(BlockState s, @Nullable ModDataMaps.Penetrability m) {
            if (m == null) return null;
            if (s.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
            if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.STONE_ORE_REPLACEABLES)) return Blocks.COBBLESTONE.defaultBlockState();
            return m.kind() == Penetration.Kind.ROCK || m.kind() == Penetration.Kind.CONCRETE ? Blocks.GRAVEL.defaultBlockState() : null;
        }

        /**
         * Fracture-zone {@code dy} layer: tunnel/cave roof (air below natural ground/rock) collapses as
         * self-falling gravel with probability 1 − r/R of fractures (estimate: near almost always, edge rarely).
         * Excludes cavity/crushing zone. Returns inspected blocks.
         */
        private int fractureSlice(ServerLevel level, int dy) {
            BlockPos c = BlockPos.containing(pos);
            double rf = law.fracture(), rk = law.crush();
            int r = Mth.ceil(rf), n = 0;
            RandomSource random = level.random;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(), up = new BlockPos.MutableBlockPos();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > rf || d <= rk) continue;
                    p.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    up.set(p.getX(), p.getY() + 1, p.getZ());
                    if (!level.isInWorldBounds(up) || !Terrain.ready(level, p)) continue;
                    n++;
                    if (!level.getBlockState(p).isAir()) continue;
                    BlockState roof = level.getBlockState(up);
                    if (!roof.is(ModTags.NUKE_GROUND) || random.nextDouble() > 1 - d / rf) continue;
                    ModDataMaps.Penetrability m = ModDataMaps.Penetrability.of(roof);
                    if (m.kind() != Penetration.Kind.ROCK && m.kind() != Penetration.Kind.SOIL) continue;
                    ColumnScar.replace(level, up.immutable(), roof, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            return n;
        }

        /**
         * Cavity roof collapses into chimney {@link BuriedBlast#chimney} high (cavity radius): rock above becomes
         * gravel, falls inside; reaching top causes surface subsidence/dust. Returns inspected blocks.
         */
        private int chimney(ServerLevel level) {
            BlockPos c = BlockPos.containing(pos);
            double rc = law.cavity();
            int r = Mth.ceil(rc), top = Math.min(cover, c.getY() + Mth.ceil(law.chimney())), n = 0;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int y = c.getY() + 1; y <= top; y++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dz * dz > rc * rc) continue;
                        p.set(c.getX() + dx, y, c.getZ() + dz);
                        if (!level.isInWorldBounds(p) || !Terrain.ready(level, p)) continue;
                        n++;
                        BlockState s = level.getBlockState(p);
                        if (s.isAir()) continue;
                        ModDataMaps.Penetrability m = ModDataMaps.Penetrability.of(s);
                        if (m.kind() == Penetration.Kind.STOP || m.kind() == Penetration.Kind.FLUID || s.is(Blocks.GRAVEL)) continue;
                        ColumnScar.replace(level, p.immutable(), s, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
            if (law.subsides()) {
                Vec3 sv = new Vec3(pos.x, cover, pos.z);
                // Subsidence dust: fraction of ground lost into hole (cavity volume), raised by falling (like crater).
                double cavity = 4.0 / 3 * Math.PI * Math.pow(rc, 3);
                PacketDistributor.sendToPlayersNear(level, null, sv.x, sv.y, sv.z, IncomingConfig.SERVER.farRange.get(),
                        new S2C.Collapse(sv, ventMat.ordinal(), (float) cavity));
            }
            return n;
        }

        /**
         * Ejecta crater ({@link BuriedBlast#craterRadius} using bore-mouth ground), same bowl/rim as craters
         * ({@link Craters#dig}), down to cavity center. Returns columns inspected.
         */
        private int crater(ServerLevel level) {
            CraterModel.Soil soil = ventMat.soil();
            return Craters.dig(level, new Vec3(pos.x, cover, pos.z), law.craterRadius(soil), law.craterDepth(soil), ventMat);
        }
    }
}
