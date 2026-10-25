package ua.zentix.incoming.warhead;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
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
import ua.zentix.incoming.strike.ImpactCost;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.nuclear.world.CraterJob;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.work.UnitQueue;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Что взрыв заряда делает с землёй после волны: воронка по закону обычного заряда ({@link CraterModel#chargeRadius}) и
 * поджиг шаром топлива из бака ({@link FuelFireball}). Каждое — своей единицей в очереди попаданий, после главного взрыва;
 * топливо, что осталось в воронке, — {@link FuelFires}.
 */
final class Craters {
    /**
     * Больше клеток шар топлива не обходит: у «Томагавка» на ровной земле клеток с импульсом выше
     * {@link PulseIgnition#FLOOR} под потолком обхода ({@link Ignition#ceiling}) — ~7 тыс. (круг в 47 м), в лесу и в городе —
     * в разы больше; клетки идут от большего импульса к меньшему, и за пределом остаются те, где он меньше всего.
     */
    static final int FIREBALL_CELLS = 40_000;
    /** Клеток обхода шара топлива за единицу работы: клетка с соседями — 3–5 мкс (GameTest), единица — около 1 мс. */
    static final int PORTION = 256;

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
     * Огонь от шара топлива: {@code fuelKg} кг к удару, из них шаром ({@link FuelFireball#ballKg}) горит диаметром
     * 5,8·M^⅓ м — тот же шар, что видит игрок ({@link FuelFireball.Sweep}: растёт от земли, всплывает к 0,75·D, уходит с
     * ветром мира). Импульс у места — от этого шара за всё горение ({@link FuelFireball.Sweep#exposure}): пока место в шаре —
     * полный поток поверхности, вне — меньше. Прежде шар сервера стоял полусферой D/2 у точки удара с полным импульсом в
     * ней: крону над поднявшимся шаром он не трогал, а траву, которую шар покинул в начале горения, жёг всю (BLASTSEQ D18).
     * Обход по воздуху от точки удара, пока импульс не ниже {@link PulseIgnition#FLOOR} (за уцелевшей стеной шара нет); у
     * каждой горючей грани, к которой подходит этот воздух, — решение раз на блок: импульс поджигает тонкое горючее с
     * долей его порога ({@link PulseIgnition#share}: листва, бумага, трава, ткань — тегами {@code pulse_*}; блок другого
     * мода без тега, что огонь ванили берёт быстро (поджиг ≥ {@link #THIN_SPREAD}), — как трава, ⚠ приближение); толстое
     * дерево — нет: за секунды шара прогревается слой меньше миллиметра. Топливо, что осталось в воронке
     * ({@link FuelFireball#POOL_SHARE}), горит своим огнём на блоках, куда легло ({@link FuelFires}): дерево, к которому оно
     * стекло, загорается от него по правилам игры.
     * <p>
     * Огонь — на самом горючем ({@link #light}): растение без формы (трава, цветы) горит на своём месте, твёрдое — пламенем в
     * воздухе у его грани, если ванильный огонь там держится за него (над его верхом или без твёрдого пола), а не стоит на
     * камне или песке рядом. У шахеда у точки удара загорается пятая часть сухой травы и три четверти листвы, к краю шара —
     * меньше; у ракеты — почти вся трава и листва в шаре, листва — и в полутора радиусах (четверть), и в кроне над шаром;
     * ткань — редко ({@code FuelFireballTest}). Ставит обход в очередь попаданий ({@link Ignition}): после взрывов {@code after}, если взрыв был ({@code happened}).
     */
    static void ignite(ServerLevel level, BlastArea area, List<StagedExplosion> after, BooleanSupplier happened, Vec3 pos, double fuelKg) {
        if (!IncomingConfig.SERVER.fire.get() || FuelFireball.ballKg(fuelKg) <= 0) return;
        StrikeWorld.get(level).impacts().add(level, new Ignition(area.retain(), after, happened, pos, FuelFireball.ballKg(fuelKg)));
    }

    /**
     * Обход шара топлива ({@link #ignite}) порциями по {@link #PORTION} клеток за единицу работы: у ракеты он берёт до
     * {@link #FIREBALL_CELLS} клеток — около 0,2 с потока сервера разом. Порции идут по тикам под бюджетом работы:
     * огонь у ракеты встаёт за десятки тиков, а не в один.
     */
    static final class Ignition implements UnitQueue.Job {
        private final BlastArea area;
        private final List<StagedExplosion> after;
        private final BooleanSupplier happened;
        private final Vec3 pos;
        private final double ballKg;
        /** Путь шара: считается в первой единице, когда ветер мира — тот, что у шара на картинке. */
        private FuelFireball.Sweep ball;
        /** Импульс в клетке воздуха: считается раз, когда клетка встала в обход. */
        private final Long2DoubleOpenHashMap pulse = new Long2DoubleOpenHashMap();
        private final LongOpenHashSet seen = new LongOpenHashSet();
        /** Решение по блоку горючего: загорится ли (раз на блок), и стоит ли уже его огонь. */
        private final Long2BooleanOpenHashMap burns = new Long2BooleanOpenHashMap();
        private final LongOpenHashSet lit = new LongOpenHashSet();
        /** Сначала клетки с большим импульсом: за пределом {@link #FIREBALL_CELLS} остаются те, где он меньше всего. */
        private final LongHeapPriorityQueue queue = new LongHeapPriorityQueue((a, b) -> Double.compare(pulse.get(b), pulse.get(a)));
        /** Потолок обхода по столбцам ({@link #ceiling}). */
        private final Long2IntOpenHashMap tops = new Long2IntOpenHashMap();
        private int cells;

        Ignition(BlastArea area, List<StagedExplosion> after, BooleanSupplier happened, Vec3 pos, double ballKg) {
            this.area = area;
            this.after = after;
            this.happened = happened;
            this.pos = pos;
            this.ballKg = ballKg;
        }

        @Override
        public boolean ready(ServerLevel level) {
            return area.ready(level);
        }

        @Override
        public boolean blocked() {
            return Warheads.pending(after);
        }

        @Override
        public int unitKind() {
            return ImpactCost.Kind.GROUND.ordinal();
        }

        @Override
        public boolean step(ServerLevel level) {
            long t0 = System.nanoTime();
            int done = ball == null ? start(level) : portion(level);
            area.record(level, ImpactCost.Kind.GROUND, System.nanoTime() - t0, done);
            return !queue.isEmpty() && cells < FIREBALL_CELLS;
        }

        /** Путь шара и первая клетка обхода; взрыв, отменённый приватом, не жжёт. */
        private int start(ServerLevel level) {
            Air air = Air.of(level);
            double alpha = Stability.alpha(air.stability()), u = air.wind().at(10, alpha);
            ball = FuelFireball.Sweep.of(ballKg, 0, u * air.wind().x(), u * air.wind().z(), alpha);
            BlockPos start = BlockPos.containing(pos);
            if (!happened.getAsBoolean() || !Terrain.ready(level, start)) return 0;
            if (!passes(level, start, level.getBlockState(start))) start = start.above();
            seen.add(start.asLong());
            pulse.put(start.asLong(), exposure(ball, pos, start));
            queue.enqueue(start.asLong());
            return portion(level);
        }

        /** До {@link #PORTION} клеток обхода; сколько обошла. */
        private int portion(ServerLevel level) {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
            int done = 0;
            while (!queue.isEmpty() && cells < FIREBALL_CELLS && done < PORTION) {
                p.set(queue.dequeueLong());
                if (!level.isInWorldBounds(p) || !Terrain.ready(level, p)) continue;
                BlockState here = level.getBlockState(p);
                if (!passes(level, p, here)) continue;
                cells++;
                done++;
                double q = pulse.get(p.asLong());
                for (Direction dir : Direction.values()) {
                    n.setWithOffset(p, dir);
                    if (!Terrain.ready(level, n)) continue;
                    BlockState s = level.getBlockState(n);
                    if (s.isFlammable(level, n, dir.getOpposite())) {
                        long key = n.asLong();
                        if (!burns.containsKey(key)) {
                            double share = PulseIgnition.share(q, fuel(level, n, s, dir.getOpposite()));
                            burns.put(key, share > 0 && level.random.nextDouble() < share);
                        }
                        if (burns.get(key) && !lit.contains(key) && light(level, p, here, n.immutable(), s, dir)) lit.add(key);
                    }
                    if (seen.add(n.asLong())) {
                        if (n.getY() > ceiling(level, n.getX(), n.getZ())) continue;
                        double qn = exposure(ball, pos, n);
                        if (qn < PulseIgnition.FLOOR) continue;
                        pulse.put(n.asLong(), qn);
                        queue.enqueue(n.asLong());
                    }
                }
            }
            return done;
        }

        /**
         * Выше всего в столбце и у его соседей ({@link Heightmap.Types#MOTION_BLOCKING}, с листвой) клетка воздуха не
         * касается ни одной грани — обход туда не идёт: шар ракеты всплывает на 26 м, и небо над ним — сотни тысяч клеток с
         * импульсом выше {@link PulseIgnition#FLOOR}, а горючего там нет. Связь по воздуху остаётся: через стену — полосой у
         * её верха, в двор и в окно — вниз вдоль стены.
         */
        private int ceiling(ServerLevel level, int x, int z) {
            long key = (long) x << 32 | (z & 0xFFFFFFFFL);
            int known = tops.getOrDefault(key, Integer.MIN_VALUE);
            if (known != Integer.MIN_VALUE) return known;
            int top = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x, z);
            for (Direction d : Direction.Plane.HORIZONTAL) top = Math.max(top, Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x + d.getStepX(), z + d.getStepZ()));
            tops.put(key, top);
            return top;
        }

        @Override
        public void end(ServerLevel level) {
            area.release(level);
        }

        @Override
        public String describe() {
            return "Fuel fireball at " + Mth.floor(pos.x) + " " + Mth.floor(pos.y) + " " + Mth.floor(pos.z);
        }
    }

    /** Импульс шара {@code ball} от точки взрыва {@code pos} в середине клетки {@code at}, кал/см². */
    private static double exposure(FuelFireball.Sweep ball, Vec3 pos, BlockPos at) {
        return ball.exposure(at.getX() + 0.5 - pos.x, at.getY() + 0.5 - pos.y, at.getZ() + 0.5 - pos.z);
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
