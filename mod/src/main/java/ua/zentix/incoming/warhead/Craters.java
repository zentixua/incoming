package ua.zentix.incoming.warhead;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.model.FuelFireball;
import ua.zentix.incoming.nuclear.model.PulseIgnition;
import ua.zentix.incoming.registry.ModTags;
import ua.zentix.incoming.nuclear.world.CraterJob;
import ua.zentix.incoming.util.Terrain;

/**
 * Что взрыв заряда делает с землёй после волны: воронка по закону обычного заряда ({@link CraterModel#chargeRadius}) и
 * пожар от шара топлива из бака ({@link FuelFireball}). Обе — одной единицей в очереди попаданий, после главного взрыва.
 */
final class Craters {
    /** Больше клеток шар топлива не обходит (у «Томагавка» шар радиусом 17 м — ~10 тыс. клеток полусферы). */
    private static final int FIREBALL_CELLS = 40_000;

    private Craters() {}

    /**
     * Воронка заряда {@code tntKg} кг ТНТ у {@code pos}: чаша и вал той же формы, что у ядерной ({@link CraterJob#digColumn}),
     * только по грунту — постройки над ней ломает волна; чаша мельче блока встаёт блоками со случайной добавкой (объём в
     * среднем — по закону: у «Ланцета» и «Града» — ямка в блок или ничего). Воронки нет, если заряд рвётся выше грунта
     * больше чем на её глубину (крыша, этаж). Возвращает, сколько столбцов смотрели.
     */
    static int dig(ServerLevel level, Vec3 pos, double tntKg, GroundMaterial mat, long seed) {
        CraterModel.Soil soil = mat.soil();
        return dig(level, pos, CraterModel.chargeRadius(tntKg, soil), CraterModel.chargeDepth(tntKg, soil), mat, RandomSource.create(seed));
    }

    /** Воронка радиусом {@code rc} и глубиной {@code depth} блоков у {@code pos} (та же чаша и вал). Сколько столбцов смотрели. */
    static int dig(ServerLevel level, Vec3 pos, double rc, double depth, GroundMaterial mat) {
        return dig(level, pos, rc, depth, mat, level.random);
    }

    private static int dig(ServerLevel level, Vec3 pos, double rc, double depth, GroundMaterial mat, RandomSource random) {
        if (depth <= 0) return 0;
        int cx = Mth.floor(pos.x), cz = Mth.floor(pos.z);
        if (!Terrain.neighbourhoodReady(level, cx >> 4, cz >> 4)) return 0;
        int top = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
        int ground = CraterJob.naturalSurface(level, cx, cz, top);
        ground = SurfaceBlocks.excavationTop(level, cx, cz, ground, top, pos.y);
        if (Math.abs(pos.y - ground) > depth + 1) return 0;
        double outer = 2 * rc + 1;
        CraterJob.Profile profile = new CraterJob.Profile() {
            @Override
            public double depth(double r) {
                return CraterModel.bowl(r, rc, depth);
            }

            @Override
            public double rim(double r) {
                return CraterModel.rim(r, rc, depth);
            }
        };
        double phase = random.nextDouble() * Math.PI * 2;
        int n = Mth.ceil(outer), columns = 0;
        for (int x = cx - n; x <= cx + n; x++) {
            for (int z = cz - n; z <= cz + n; z++) {
                if (!Terrain.neighbourhoodReady(level, x >> 4, z >> 4)) continue;
                CraterJob.digColumn(level, x, z, pos, outer, phase, profile, mat.debris(), null, CraterJob.Mode.DIG, random);
                columns++;
            }
        }
        return columns;
    }

    /**
     * Огонь после шара топлива: {@code fuelKg} кг к удару, из них шаром ({@link FuelFireball#ballKg}) горит диаметром
     * 5,8·M^⅓ м. Обход по воздуху от точки удара в шаре (за уцелевшей стеной шара нет); у каждой горючей грани, к которой
     * подходит воздух шара, — решение раз на блок:
     * <ul>
     * <li>импульс шара ({@link FuelFireball#exposure}) поджигает тонкое горючее с долей его порога
     * ({@link PulseIgnition#share}: листва, бумага, трава, ткань — тегами {@code pulse_*}; блок другого мода без тега, что
     * огонь ванили берёт быстро (поджиг ≥ {@link #THIN_SPREAD}), — как трава, ⚠ приближение); толстое дерево — нет;</li>
     * <li>горящее топливо, что легло в круге воронки ({@link FuelFireball#POOL_SHARE}, радиус —
     * {@link CraterModel#chargeRadius} сухого грунта, как у лужи клиента), поджигает всё горючее, к чьей грани подходит:
     * оно горит минутами (⚠ оценка круга).</li>
     * </ul>
     * Огонь — на самом горючем ({@link #light}): растение без формы (трава, цветы) горит на своём месте, твёрдое — пламенем в
     * воздухе у его грани, если ванильный огонь там держится за него (над его верхом или без твёрдого пола), а не стоит на
     * камне или песке рядом. У шахеда в шаре загорается около половины сухой травы и почти вся листва, у ракеты — почти
     * вся трава и листва, ткань — редко; доски и брёвна — только у самой воронки. Возвращает, сколько клеток обошёл.
     */
    static int ignite(ServerLevel level, Vec3 pos, double fuelKg, double tntKg) {
        if (!IncomingConfig.SERVER.fire.get()) return 0;
        double ballKg = FuelFireball.ballKg(fuelKg);
        double r = FuelFireball.diameter(ballKg) / 2, q = FuelFireball.exposure(ballKg);
        double wet = CraterModel.chargeRadius(tntKg, CraterModel.Soil.DRY);
        if (r <= 0) return 0;
        LongOpenHashSet seen = new LongOpenHashSet();
        // решение по блоку горючего: загорится ли (раз на блок), и стоит ли уже его огонь
        Long2BooleanOpenHashMap burns = new Long2BooleanOpenHashMap();
        LongOpenHashSet lit = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        BlockPos start = BlockPos.containing(pos);
        if (!Terrain.ready(level, start)) return 0;
        if (!passes(level, start, level.getBlockState(start))) start = start.above();
        seen.add(start.asLong());
        queue.enqueue(start.asLong());
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        int cells = 0;
        while (!queue.isEmpty() && cells < FIREBALL_CELLS) {
            p.set(queue.dequeueLong());
            if (!level.isInWorldBounds(p) || !Terrain.ready(level, p)) continue;
            BlockState here = level.getBlockState(p);
            if (!passes(level, p, here)) continue;
            cells++;
            for (Direction dir : Direction.values()) {
                n.setWithOffset(p, dir);
                if (!Terrain.ready(level, n)) continue;
                BlockState s = level.getBlockState(n);
                if (s.isFlammable(level, n, dir.getOpposite())) {
                    long key = n.asLong();
                    if (!burns.containsKey(key)) {
                        // грань горючего, к которой подходит воздух шара: середина между клетками
                        double fx = p.getX() + 0.5 + 0.5 * dir.getStepX() - pos.x, fy = p.getY() + 0.5 + 0.5 * dir.getStepY() - pos.y,
                                fz = p.getZ() + 0.5 + 0.5 * dir.getStepZ() - pos.z;
                        double share = fx * fx + fy * fy + fz * fz <= wet * wet ? 1 : PulseIgnition.share(q, fuel(level, n, s, dir.getOpposite()));
                        burns.put(key, share > 0 && level.random.nextDouble() < share);
                    }
                    if (burns.get(key) && !lit.contains(key) && light(level, p, here, n.immutable(), s, dir)) lit.add(key);
                }
                if (Vec3.atCenterOf(n).distanceToSqr(pos) <= r * r && seen.add(n.asLong())) queue.enqueue(n.asLong());
            }
        }
        return cells;
    }

    /** Поджиг ванили (скорость, с какой огонь берёт блок), с которой горючее без тега считается тонким, как трава. */
    static final int THIN_SPREAD = 60;

    /** Как импульс поджигает горючее {@code s} на месте {@code at} (грань {@code face} к шару). */
    static PulseIgnition.Fuel fuel(ServerLevel level, BlockPos at, BlockState s, Direction face) {
        if (s.is(ModTags.PULSE_LEAVES)) return PulseIgnition.Fuel.LEAVES;
        if (s.is(ModTags.PULSE_PAPER)) return PulseIgnition.Fuel.PAPER;
        if (s.is(ModTags.PULSE_GRASS)) return PulseIgnition.Fuel.GRASS;
        if (s.is(ModTags.PULSE_FABRIC)) return PulseIgnition.Fuel.FABRIC;
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.PLANKS) || s.is(BlockTags.WOODEN_SLABS) || s.is(BlockTags.WOODEN_STAIRS)
                || s.is(BlockTags.WOODEN_FENCES) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.WOODEN_TRAPDOORS)) {
            return PulseIgnition.Fuel.SOLID;
        }
        return s.getFireSpreadSpeed(level, at, face) >= THIN_SPREAD ? PulseIgnition.Fuel.GRASS : PulseIgnition.Fuel.SOLID;
    }

    /**
     * Огонь на горючем {@code fuel} ({@code s}), к чьей грани подходит клетка воздуха шара {@code air} ({@code here}) с
     * его стороны {@code dir}: растение без формы столкновений — огонь на его месте (так его берёт и огонь ванили); иначе —
     * огонь в клетке воздуха, если он держится за горючее: оно под ним или пола под ним нет (огонь у стены). Над камнем,
     * песком, рельсами рядом с горючим огонь не ставится: ванильный огонь с твёрдым полом рисуется на полу, и горел бы
     * песок. Поставлен ли огонь.
     */
    private static boolean light(ServerLevel level, BlockPos air, BlockState here, BlockPos fuel, BlockState s, Direction dir) {
        // растение без формы (трава, цветы, посевы, тростник) — как его жжёт огонь ванили: огонь на его месте
        if (s.getCollisionShape(level, fuel).isEmpty() && s.getFluidState().isEmpty()) return Warheads.igniteIn(level, fuel);
        if (!here.isAir()) return false;
        BlockPos below = air.below();
        if (dir != Direction.DOWN && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
        return Warheads.igniteAt(level, air.immutable());
    }

    private static boolean passes(ServerLevel level, BlockPos p, BlockState s) {
        return s.isAir() || s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty();
    }
}
