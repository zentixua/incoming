package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.MobPanic;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.model.BlastInjury;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.registry.ModTags;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Blast on the body ({@link BlastInjury}, DESIGN-nuke §4): lung pressure, throw into a path obstacle or ground tumbling,
 * fragments of a window between body and blast, burial by a collapsed house. Pressure reaches where an air path to sky
 * exists from the body (through windows and wooden doors — the blast breaks them): not a bunker or sealed cave.
 * Post-front wind reaches open sky (and under crowns: blast blows through leaves) and positions seeing the ball
 * (through an opening); a house without such a window receives pressure only. A block here is a meter at any
 * {@code effects_scale}: scale compresses burst radii while the body flies among full-size walls.
 * <p>
 * Window ahead and wall behind are as the front found them ({@link Before}): glass breaks and flies into the body;
 * the wall reflects the wave at arrival before collapsing, while chunk ruins are applied one tick before the front.
 * Walls along the throw path use the current world: blast-destroyed objects fall before the body reaches them
 * (front is faster).
 */
public final class BlastBody {
    /** Maximum air cells searched from the body for sky: none found — body is enclosed. */
    private static final int AIR_CELLS = 2048;
    /** Window fragment reach to the body, blocks (table 12.43: 2–4 m from window). */
    static final double GLASS_REACH = 4;
    /**
     * Allowed horizontal body movement by front arrival from the remembered wall/window position ({@link Before}), blocks:
     * wall directly behind — half a block changes it; window up to {@link #GLASS_REACH} ahead — two blocks do not.
     * Beyond that, use the current world.
     */
    private static final double WALL_DRIFT = 0.5, GLASS_DRIFT = 2;
    /**
     * Maximum in-game throw speed, blocks/tick: vanilla rolls back a player moved over 10 per tick; 6 is 120 m/s,
     * certainly fatal (solid impact from 42 m/s).
     */
    private static final double MAX_THROW = 6;
    /** Throw hop, blocks/tick: tumbling lifts the body but no higher than a jump — no fall damage. */
    private static final double HOP = 0.42;

    private BlastBody() {}

    /**
     * Wave hitting the body: nuclear detonation ({@link #of(Detonation)}) or conventional charge blast
     * ({@code warhead.StagedExplosion}). Pressure, dynamic pressure, wind and phase at the body; each has its own reach rules.
     */
    public interface Wave {
        Vec3 centre();

        /** Front overpressure, kPa. */
        double overpressureKpa(Vec3 p);

        /** Dynamic pressure along the ground, kPa. */
        double dynamicPressureKpa(Vec3 p);

        /** Post-front wind, m/s. */
        double windSpeed(Vec3 p);

        /** Positive phase, s. */
        double phaseSeconds(Vec3 p);

        /** Below this strength the wave does not hit the body, kPa. */
        double hitKpa();

        /** Body in post-front wind: open to the wave — thrown. */
        boolean wind(ServerLevel level, LivingEntity e);

        /** Pressure reaches the body even without wind (air through openings). */
        boolean reaches(ServerLevel level, LivingEntity e);

        /** Wave number for body randomness ({@link #fate}). */
        long id();

        /** Damage type. */
        net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> damage();

        /** Wave hit the body in wind (nuclear mob panic). */
        default void blown(ServerLevel level, LivingEntity e) {}
    }

    /** Nuclear detonation wave. */
    public static Wave of(Detonation d) {
        return new Wave() {
            @Override
            public Vec3 centre() {
                return d.burst();
            }

            @Override
            public double overpressureKpa(Vec3 p) {
                return d.overpressureKpa(p);
            }

            @Override
            public double dynamicPressureKpa(Vec3 p) {
                return d.dynamicPressureKpa(p);
            }

            @Override
            public double windSpeed(Vec3 p) {
                return d.blastWind(p);
            }

            @Override
            public double phaseSeconds(Vec3 p) {
                return d.positivePhaseSeconds(p);
            }

            @Override
            public double hitKpa() {
                return Detonation.HIT_KPA;
            }

            @Override
            public boolean wind(ServerLevel level, LivingEntity e) {
                return windAt(level, e.getEyePosition()) || NuclearWarhead.sees(level, d, e);
            }

            @Override
            public boolean reaches(ServerLevel level, LivingEntity e) {
                return airReaches(level, e);
            }

            @Override
            public long id() {
                return d.id();
            }

            @Override
            public net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> damage() {
                return ModDamageTypes.NUCLEAR_BLAST;
            }

            @Override
            public void blown(ServerLevel level, LivingEntity e) {
                MobPanic.wave(d, e, level.getGameTime());
            }
        };
    }

    /**
     * Window and wall near the body at {@code at}, as the front found them: wall directly behind (reflection) and glass
     * between body and blast within {@link #GLASS_REACH}.
     *
     * @param drift horizontal blocks the body moved from {@code at} by front arrival
     */
    public record Before(Vec3 at, boolean backed, boolean glass, double drift) {
        /** Body at {@code now} when the front arrives. */
        public Before drifted(Vec3 now) {
            return new Before(at, backed, glass, Math.max(drift, Math.hypot(now.x - at.x, now.z - at.z)));
        }
    }

    /**
     * What the wave does to the body.
     *
     * @param throwSpeed throw speed, m/s (0 — no throw)
     * @param wind       body in post-front wind (open sky, under crown or seeing the ball)
     */
    public record Wound(double death, double injury, double throwSpeed, Vec3 away, boolean wind) {}

    /** Window and wall near the body now (world before chunk ruins — {@link BlastFront#remember}). */
    public static Before before(ServerLevel level, Detonation d, LivingEntity e) {
        return new Before(e.position(), wallBehind(level, d.burst(), e), windowAhead(level, d.burst(), e), 0);
    }

    /** Conventional charge: remember glass and reflection before its outward sweep removes nearer blocks. */
    public static Before before(ServerLevel level, Wave wave, LivingEntity e) {
        return new Before(e.position(), wallBehind(level, wave.centre(), e), windowAhead(level, wave.centre(), e), 0);
    }

    private static boolean wallBehind(ServerLevel level, Vec3 centre, LivingEntity e) {
        return march(level, mid(e), away(centre, e), e.getBbWidth() * 0.5 + 0.5, BlastBody::solid) >= 0;
    }

    private static boolean windowAhead(ServerLevel level, Vec3 centre, LivingEntity e) {
        Vec3 eye = e.getEyePosition();
        return march(level, eye, centre.subtract(eye).normalize(), GLASS_REACH, BlastBody::glass) >= 0;
    }

    /**
     * Wave impact on the body. Creative mode takes no wounds, but is still thrown.
     *
     * @param before window and wall as the front found them (with body drift at arrival); null — current world
     * @return body was in post-front wind (reverse wind pulls only such bodies)
     */
    public static boolean hit(ServerLevel level, Detonation d, LivingEntity e, @Nullable Entity owner, @Nullable Before before) {
        return hit(level, of(d), e, owner, before);
    }

    /** Wave {@code wave} hits the body ({@link #hit(ServerLevel, Detonation, LivingEntity, Entity, Before)}). */
    public static boolean hit(ServerLevel level, Wave wave, LivingEntity e, @Nullable Entity owner, @Nullable Before before) {
        return hit(level, wave, e, null, owner, before);
    }

    /**
     * Wave impact on the body.
     *
     * @param direct what exploded (projectile), for damage; null — none
     */
    public static boolean hit(ServerLevel level, Wave wave, LivingEntity e, @Nullable Entity direct, @Nullable Entity owner, @Nullable Before before) {
        Wound w = wound(level, wave, e, before);
        if (w == null) return false;
        harm(level, wave, e, direct, owner, w.death(), w.injury(), fate(e, wave.id()));
        double v = Math.min(MAX_THROW, w.throwSpeed() / 20);
        if (v > 0) {
            e.push(w.away().x * v, Math.min(HOP, 0.25 * v), w.away().z * v);
            e.hurtMarked = true;
        }
        // in shelter (house without a window toward the ball, cave, roofed mob farm) the wave causes no panic: nowhere to flee
        if (w.wind()) wave.blown(level, e);
        return w.wind();
    }

    /**
     * Wave that crossed the body's place while it was not in memory ({@code LateExposure}): the same injury law, without the
     * throw and the fleeing that happen only at that moment. The world is read as it is now (estimate: ruins may already
     * stand where the wave found walls and windows).
     *
     * @return whether the wave reached the body
     */
    public static boolean late(ServerLevel level, Detonation d, LivingEntity e, @Nullable Entity owner) {
        Wave wave = of(d);
        Wound w = wound(level, wave, e, null);
        if (w == null) return false;
        harm(level, wave, e, null, owner, w.death(), w.injury(), fate(e, wave.id()));
        return true;
    }

    /** What the wave will do to the body; null — will not reach it (below 0.3 psi, enclosed). */
    @Nullable
    public static Wound wound(ServerLevel level, Detonation d, LivingEntity e, @Nullable Before before) {
        return wound(level, of(d), e, before);
    }

    /** What wave {@code d} will do to the body; null — will not reach it. */
    @Nullable
    public static Wound wound(ServerLevel level, Wave d, LivingEntity e, @Nullable Before before) {
        double kpa = d.overpressureKpa(e.position());
        if (kpa < d.hitKpa()) return null;
        boolean wind = d.wind(level, e);
        if (!wind && !d.reaches(level, e)) return null;
        Vec3 away = away(d.centre(), e), mid = mid(e);
        double radius = e.getBbWidth() * 0.5;
        Vec3 look = e.getLookAngle();
        double flat = Math.hypot(look.x, look.z);
        double facing = flat < 1e-6 ? 0 : -(look.x * away.x + look.z * away.z) / flat;
        boolean backed = before != null && before.drift() <= WALL_DRIFT ? before.backed() : wallBehind(level, d.centre(), e);
        double eff = BlastInjury.effectiveKpa(kpa, d.dynamicPressureKpa(e.position()), backed, facing);
        double drag = wind ? BlastInjury.HUMAN_DRAG_PER_KG * BlastInjury.HUMAN_WIDTH / Math.max(0.1, e.getBbWidth())
                * (1 - e.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)) : 0;
        BlastInjury.Throw t = BlastInjury.thrown(kpa, d.windSpeed(e.position()), d.phaseSeconds(e.position()), drag);
        // impact against the first solid object on the body's path, at speed gained by then; none — ground tumbling
        double wall = t.peak() > 0 ? march(level, mid, away, radius + t.stop(), BlastBody::solid) : -1;
        double impact = wall >= 0 ? t.speedAfter(Math.max(0, wall - radius)) : 0;
        boolean glass = BlastModel.psi(kpa) >= BlockResponse.FRAGILE_PSI
                && (before != null && before.drift() <= GLASS_DRIFT ? before.glass() : windowAhead(level, d.centre(), e));
        double death = BlastInjury.any(BlastInjury.lungDeath(eff),
                wall >= 0 ? BlastInjury.impactDeath(impact) : BlastInjury.tumbleDeath(t.peak()));
        double injury = BlastInjury.any(BlastInjury.lungInjury(eff),
                wall >= 0 ? BlastInjury.impactInjury(impact) : BlastInjury.tumbleInjury(t.peak()),
                glass ? BlastInjury.glassInjury(kpa) : 0);
        return new Wound(death, injury, t.peak(), away, wind);
    }

    /** Burial: body covered by chunk ruins. Survivors stay buried and suffocate until they escape. */
    public static void bury(ServerLevel level, Detonation d, LivingEntity e, @Nullable Entity owner) {
        if (!buried(level, e)) return;
        harm(level, of(d), e, null, owner, BlastInjury.CRUSH_DEATH, BlastInjury.CRUSH_INJURY, fate(e, d.id()));
    }

    /** Living entities in the chunk not buried (snapshot before ruins): buried after ruins — {@link #buried}. */
    public static List<LivingEntity> free(ServerLevel level, LevelChunk chunk) {
        int x = chunk.getPos().getMinBlockX(), z = chunk.getPos().getMinBlockZ();
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(x, level.getMinBuildHeight(), z, x + 16, level.getMaxBuildHeight(), z + 16))) {
            if (e.isAlive() && !e.isSpectator() && !e.isPassenger() && !buried(level, e)) out.add(e);
        }
        return out;
    }

    /**
     * Body inside blocks: its bounds intersect solid material. At a chunk edge the bounds enter a neighbor; if unready,
     * reading it would wait for loading on the server thread — then treat as not buried (unknown).
     */
    public static boolean buried(ServerLevel level, LivingEntity e) {
        AABB box = e.getBoundingBox().deflate(1e-3);
        return Terrain.readyAround(level, box.getCenter(), Math.max(box.getXsize(), box.getZsize()) * 0.5 + 1.5)
                && level.getBlockCollisions(e, box).iterator().hasNext();
    }

    private static void harm(ServerLevel level, Wave wave, LivingEntity e, @Nullable Entity direct, @Nullable Entity owner, double death, double injury,
                             double fate) {
        if (e instanceof Player p && p.getAbilities().invulnerable) return;
        double lost = BlastInjury.lostShare(death, injury, fate);
        float dmg = lost >= 1 ? Float.MAX_VALUE : (float) (lost * e.getMaxHealth());
        if (dmg >= 0.5f) e.hurt(ModDamageTypes.source(level, wave.damage(), direct, owner), dmg);
    }

    /** Body randomness 0..1 ({@link BlastInjury#lostShare}): unique per body and wave. */
    static double fate(LivingEntity e, long wave) {
        long h = HashCommon.mix(e.getUUID().getMostSignificantBits() ^ HashCommon.mix(e.getUUID().getLeastSignificantBits() ^ wave));
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Horizontally away from blast (unit vector). */
    private static Vec3 away(Vec3 centre, LivingEntity e) {
        Vec3 away = new Vec3(e.getX() - centre.x, 0, e.getZ() - centre.z);
        return away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
    }

    private static Vec3 mid(LivingEntity e) {
        return e.position().add(0, e.getBbHeight() * 0.5, 0);
    }

    /** Only leaves above the point: post-front wind blows through crowns (leaves block rain — use the map excluding them). */
    public static boolean windAt(ServerLevel level, Vec3 p) {
        return Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(p.x), Mth.floor(p.z)) <= p.y;
    }

    /**
     * Whether an air path to sky exists from the body (cell traversal, at most {@link #AIR_CELLS}): air, partial blocks,
     * glass and leaves, wooden and open doors, trapdoors and fence gates. Unready chunk — path exists (cannot see beyond it).
     */
    private static boolean airReaches(ServerLevel level, LivingEntity e) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        for (BlockPos start : new BlockPos[]{e.blockPosition(), BlockPos.containing(e.getEyePosition())}) {
            if (seen.add(start.asLong())) queue.enqueue(start.asLong());
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty()) {
            p.set(queue.dequeueLong());
            if (!Terrain.ready(level, p)) return true;
            if (p.getY() >= Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ())) return true;
            for (Direction dir : Direction.values()) {
                BlockPos n = p.relative(dir);
                if (n.getY() < level.getMinBuildHeight() || n.getY() >= level.getMaxBuildHeight() || seen.size() >= AIR_CELLS) continue;
                if (!seen.add(n.asLong())) continue;
                if (!Terrain.ready(level, n) || airPasses(level, n)) queue.enqueue(n.asLong());
            }
        }
        return false;
    }

    private static boolean airPasses(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        VoxelShape shape = s.getCollisionShape(level, p);
        if (shape.isEmpty() || s.is(ModTags.SHATTERS) || s.is(BlockTags.LEAVES)) return true;
        if (s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.FENCE_GATES)) {
            return s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.WOODEN_TRAPDOORS) || s.is(BlockTags.FENCE_GATES)
                    || s.getOptionalValue(BlockStateProperties.OPEN).orElse(false);
        }
        return !Block.isShapeFullBlock(shape);
    }

    private static boolean solid(BlockState s) {
        return !s.is(ModTags.SHATTERS) && !s.is(BlockTags.LEAVES);
    }

    private static boolean glass(BlockState s) {
        return s.is(ModTags.SHATTERS);
    }

    /**
     * Distance to the first {@code material} block whose collision shape intersects a ray from {@code from} along unit
     * {@code dir}, within {@code max} blocks: grid traversal (Amanatides–Woo), actual shape within a cell (carpet underfoot,
     * ladder to the side and slab below the ray are not walls); −1 — none or unready chunk ahead.
     */
    private static double march(ServerLevel level, Vec3 from, Vec3 dir, double max, Predicate<BlockState> material) {
        int x = Mth.floor(from.x), y = Mth.floor(from.y), z = Mth.floor(from.z);
        int sx = dir.x > 0 ? 1 : -1, sy = dir.y > 0 ? 1 : -1, sz = dir.z > 0 ? 1 : -1;
        double ddx = dir.x == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.x);
        double ddy = dir.y == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.y);
        double ddz = dir.z == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.z);
        double tx = dir.x == 0 ? Double.POSITIVE_INFINITY : (dir.x > 0 ? x + 1 - from.x : from.x - x) * ddx;
        double ty = dir.y == 0 ? Double.POSITIVE_INFINITY : (dir.y > 0 ? y + 1 - from.y : from.y - y) * ddy;
        double tz = dir.z == 0 ? Double.POSITIVE_INFINITY : (dir.z > 0 ? z + 1 - from.z : from.z - z) * ddz;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double t = 0;
        while (t <= max) {
            p.set(x, y, z);
            if (!Terrain.ready(level, p)) return -1;
            double out = Math.min(max, Math.min(tx, Math.min(ty, tz)));
            if (y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight()) {
                BlockState s = level.getBlockState(p);
                VoxelShape shape = material.test(s) ? s.getCollisionShape(level, p) : null;
                if (shape != null && !shape.isEmpty()) {
                    BlockHitResult hit = shape.clip(from.add(dir.scale(t)), from.add(dir.scale(out)), p);
                    if (hit != null) return from.distanceTo(hit.getLocation());
                }
            }
            if (tx <= ty && tx <= tz) {
                t = tx;
                x += sx;
                tx += ddx;
            } else if (ty <= tz) {
                t = ty;
                y += sy;
                ty += ddy;
            } else {
                t = tz;
                z += sz;
                tz += ddz;
            }
        }
        return -1;
    }
}
