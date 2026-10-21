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
 * Волна по телу ({@link BlastInjury}, DESIGN-nuke §4): давление на лёгкие, бросок с ударом о препятствие на пути или
 * кувырками по земле, осколки окна между телом и взрывом, завал рухнувшим домом. Давление доходит туда, куда от тела
 * есть путь по воздуху к небу (сквозь окна и деревянные двери — их волна выбивает): в бункер и закрытую пещеру — нет.
 * Ветер за фронтом — под небом (и под кроной: листву волна продувает) и там, откуда виден шар (сквозь проём); в доме
 * без такого окна только давление. Блок здесь — метр при любом {@code effects_scale}: масштаб сжимает радиусы
 * подрыва, а тело летит среди стен своего размера.
 * <p>
 * Окно перед телом и стена за спиной — какими их застал фронт ({@link Before}): стекло лопается и летит в тело, а стена
 * отражает волну в миг прихода, ещё до того, как рухнет, — а руины чанка встают на тик раньше фронта. Стена на пути
 * броска — по миру, как он есть: что волна сносит, падает раньше, чем до этого места долетит тело (фронт быстрее).
 */
public final class BlastBody {
    /** Сколько клеток воздуха от тела обходится в поисках неба: не нашлось — тело в закрытом. */
    private static final int AIR_CELLS = 2048;
    /** Осколки окна долетают до тела, блоков (табл. 12.43: 2–4 м от окна). */
    static final double GLASS_REACH = 4;
    /**
     * Насколько тело к приходу фронта могло отойти по горизонтали от места, где запомнило стену и окно ({@link Before}),
     * блоков: стена за спиной — вплотную, полблока меняют её; окно — до {@link #GLASS_REACH} впереди, два блока — нет.
     * Дальше — по миру как есть.
     */
    private static final double WALL_DRIFT = 0.5, GLASS_DRIFT = 2;
    /**
     * Бросок в игре — не быстрее, блоков за тик: ваниль откатывает игрока, сдвинутого больше чем на 10 за тик, а 6 —
     * это 120 м/с, гибель наверняка (о твёрдое — с 42 м/с).
     */
    private static final double MAX_THROW = 6;
    /** Подскок при броске, блоков за тик: кувырок отрывает тело от земли, но не выше прыжка — урона от падения нет. */
    private static final double HOP = 0.42;

    private BlastBody() {}

    /**
     * Волна, которая бьёт по телу: ядерный подрыв ({@link #of(Detonation)}) или взрыв обычного заряда
     * ({@code warhead.StagedExplosion}). Давление, напор, ветер и фаза — в точке тела; что до тела доходит — у каждой своё.
     */
    public interface Wave {
        Vec3 centre();

        /** Избыточное давление на фронте, кПа. */
        double overpressureKpa(Vec3 p);

        /** Скоростной напор вдоль земли, кПа. */
        double dynamicPressureKpa(Vec3 p);

        /** Ветер за фронтом, м/с. */
        double windSpeed(Vec3 p);

        /** Положительная фаза, с. */
        double phaseSeconds(Vec3 p);

        /** Слабее этого волна по телу не бьёт, кПа. */
        double hitKpa();

        /** Тело в ветре за фронтом: открыто к волне — его бросает. */
        boolean wind(ServerLevel level, LivingEntity e);

        /** Давление доходит до тела и без ветра (воздухом сквозь проёмы). */
        boolean reaches(ServerLevel level, LivingEntity e);

        /** Номер волны для случайности тела ({@link #fate}). */
        long id();

        /** Тип урона. */
        net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> damage();

        /** Волна ударила тело в ветре (паника мобов у ядерки). */
        default void blown(ServerLevel level, LivingEntity e) {}
    }

    /** Волна ядерного подрыва. */
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
     * Окно и стена у тела в {@code at}, какими их застал фронт: стена вплотную за спиной (отражение) и стекло между
     * телом и взрывом не дальше {@link #GLASS_REACH}.
     *
     * @param drift на сколько блоков по горизонтали тело ушло от {@code at} к приходу фронта
     */
    public record Before(Vec3 at, boolean backed, boolean glass, double drift) {
        /** Тело к приходу фронта — в {@code now}. */
        public Before drifted(Vec3 now) {
            return new Before(at, backed, glass, Math.max(drift, Math.hypot(now.x - at.x, now.z - at.z)));
        }
    }

    /**
     * Что волна делает телу.
     *
     * @param throwSpeed скорость броска, м/с (0 — не бросает)
     * @param wind       тело в ветре за фронтом (под небом, под кроной или видит шар)
     */
    public record Wound(double death, double injury, double throwSpeed, Vec3 away, boolean wind) {}

    /** Окно и стена у тела сейчас (мир до руин чанка — {@link BlastFront#remember}). */
    public static Before before(ServerLevel level, Detonation d, LivingEntity e) {
        return new Before(e.position(), wallBehind(level, d.burst(), e), windowAhead(level, d.burst(), e), 0);
    }

    private static boolean wallBehind(ServerLevel level, Vec3 centre, LivingEntity e) {
        return march(level, mid(e), away(centre, e), e.getBbWidth() * 0.5 + 0.5, BlastBody::solid) >= 0;
    }

    private static boolean windowAhead(ServerLevel level, Vec3 centre, LivingEntity e) {
        Vec3 eye = e.getEyePosition();
        return march(level, eye, centre.subtract(eye).normalize(), GLASS_REACH, BlastBody::glass) >= 0;
    }

    /**
     * Удар волны по телу. Творческий режим ран не получает, но волна бросает и его.
     *
     * @param before окно и стена, какими их застал фронт (со сдвигом тела к его приходу); null — по миру как есть
     * @return тело было в ветре за фронтом (обратный ветер тянет только таких)
     */
    public static boolean hit(ServerLevel level, Detonation d, LivingEntity e, @Nullable Entity owner, @Nullable Before before) {
        return hit(level, of(d), e, owner, before);
    }

    /** Удар волны {@code wave} по телу ({@link #hit(ServerLevel, Detonation, LivingEntity, Entity, Before)}). */
    public static boolean hit(ServerLevel level, Wave wave, LivingEntity e, @Nullable Entity owner, @Nullable Before before) {
        return hit(level, wave, e, null, owner, before);
    }

    /**
     * Удар волны по телу.
     *
     * @param direct что взорвалось (снаряд) — для урона, null — без него
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
        // в укрытии (дом без окна к шару, пещера, ферма мобов под крышей) волна не пугает: бежать оттуда некуда
        if (w.wind()) wave.blown(level, e);
        return w.wind();
    }

    /** Что волна сделает телу; null — не дойдёт (слабее 0.3 psi, в закрытом). */
    @Nullable
    public static Wound wound(ServerLevel level, Detonation d, LivingEntity e, @Nullable Before before) {
        return wound(level, of(d), e, before);
    }

    /** Что волна {@code d} сделает телу; null — не дойдёт. */
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
        // удар — о первое твёрдое на пути тела, со скоростью, набранной к нему; нет такого — кувырки по земле
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

    /** Завал: тело, которое руины чанка засыпали. Кто жив, остаётся под завалом и задыхается, пока не выберется. */
    public static void bury(ServerLevel level, Detonation d, LivingEntity e, @Nullable Entity owner) {
        if (!buried(level, e)) return;
        harm(level, of(d), e, null, owner, BlastInjury.CRUSH_DEATH, BlastInjury.CRUSH_INJURY, fate(e, d.id()));
    }

    /** Живые в чанке, которых не засыпало (снимок до руин): засыпанные после руин — {@link #buried}. */
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
     * Тело в блоках: его рамка задевает твёрдое. Рамка у края чанка заходит в соседний; пока тот не готов, чтение его
     * ждало бы загрузки в потоке сервера — тогда не засыпано (не видно).
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

    /** Случайность тела 0..1 ({@link BlastInjury#lostShare}): своя у каждого и у каждой волны. */
    static double fate(LivingEntity e, long wave) {
        long h = HashCommon.mix(e.getUUID().getMostSignificantBits() ^ HashCommon.mix(e.getUUID().getLeastSignificantBits() ^ wave));
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Прочь от взрыва по горизонтали (единичный). */
    private static Vec3 away(Vec3 centre, LivingEntity e) {
        Vec3 away = new Vec3(e.getX() - centre.x, 0, e.getZ() - centre.z);
        return away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
    }

    private static Vec3 mid(LivingEntity e) {
        return e.position().add(0, e.getBbHeight() * 0.5, 0);
    }

    /** Над точкой нет ничего, кроме листвы: ветер за фронтом крону продувает (дождь листва держит — карта без неё). */
    public static boolean windAt(ServerLevel level, Vec3 p) {
        return Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(p.x), Mth.floor(p.z)) <= p.y;
    }

    /**
     * Есть ли от тела путь по воздуху к небу (обход клеток, не больше {@link #AIR_CELLS}): воздух, неполные блоки,
     * стекло и листва, деревянные и открытые двери, люки и калитки. Неготовый чанк — путь есть (за ним не видно).
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
     * Расстояние до первого блока из {@code material}, чью форму столкновений задевает луч из {@code from} в сторону
     * {@code dir} (единичный), не дальше {@code max} блоков: по сетке (Amanatides–Woo), в клетке — по самой форме (ковёр
     * под ногами, лестница сбоку и плита ниже луча — не стена); −1 — нет такого или дальше неготовый чанк.
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
