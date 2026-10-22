package ua.zentix.incoming.warhead;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.model.FuelFireball;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.world.CraterJob;
import ua.zentix.incoming.util.Terrain;

/**
 * Что взрыв заряда делает с землёй после волны: воронка по закону обычного заряда ({@link CraterModel#chargeRadius}) и
 * пожар от шара топлива из бака ({@link FuelFireball}). Обе — одной единицей в очереди попаданий, после главного взрыва.
 */
final class Craters {
    /** Больше клеток шар топлива не обходит (у «Томагавка» шар радиусом 19 м — ~14 тыс. клеток полусферы). */
    private static final int FIREBALL_CELLS = 40_000;

    private Craters() {}

    /**
     * Воронка заряда {@code tntKg} кг ТНТ у {@code pos}: чаша и вал той же формы, что у ядерной ({@link CraterJob#digColumn}),
     * только по грунту — постройки над ней ломает волна; чаша мельче блока встаёт блоками со случайной добавкой (объём в
     * среднем — по закону: у «Ланцета» и «Града» — ямка в блок или ничего). Воронки нет, если заряд рвётся выше грунта
     * больше чем на её глубину (крыша, этаж). Возвращает, сколько столбцов смотрели.
     */
    static int dig(ServerLevel level, Vec3 pos, double tntKg, GroundMaterial mat) {
        CraterModel.Soil soil = mat.soil();
        return dig(level, pos, CraterModel.chargeRadius(tntKg, soil), CraterModel.chargeDepth(tntKg, soil), mat);
    }

    /** Воронка радиусом {@code rc} и глубиной {@code depth} блоков у {@code pos} (та же чаша и вал). Сколько столбцов смотрели. */
    static int dig(ServerLevel level, Vec3 pos, double rc, double depth, GroundMaterial mat) {
        if (depth <= 0) return 0;
        int cx = Mth.floor(pos.x), cz = Mth.floor(pos.z);
        if (!Terrain.neighbourhoodReady(level, cx >> 4, cz >> 4)) return 0;
        int top = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
        int ground = CraterJob.naturalSurface(level, cx, cz, top);
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
        double phase = level.random.nextDouble() * Math.PI * 2;
        int n = Mth.ceil(outer), columns = 0;
        for (int x = cx - n; x <= cx + n; x++) {
            for (int z = cz - n; z <= cz + n; z++) {
                if (!Terrain.neighbourhoodReady(level, x >> 4, z >> 4)) continue;
                CraterJob.digColumn(level, x, z, pos, outer, phase, profile, mat.debris(), null, CraterJob.Mode.DIG, level.random);
                columns++;
            }
        }
        return columns;
    }

    /**
     * Шар топлива: {@code fuelKg} кг из бака сгорают шаром диаметром 5,8·M^⅓ м ({@link FuelFireball}). Огонь — в воздухе
     * шара (обход от точки удара по воздуху: за уцелевшей стеной шара нет) у горючих граней, с долей
     * {@link ThermalModel#ignitionShare} от светового импульса внутри шара — как поджигает свет ядерки. У шахеда (30 кг —
     * 9 кал/см²) загорается почти половина открытого шару горючего, у «Томагавка» (275 кг — 19 кал/см²) — почти всё.
     * Возвращает, сколько клеток обошёл.
     */
    static int ignite(ServerLevel level, Vec3 pos, double fuelKg) {
        if (!IncomingConfig.SERVER.fire.get()) return 0;
        double r = FuelFireball.diameter(fuelKg) / 2, share = ThermalModel.ignitionShare(FuelFireball.exposure(fuelKg));
        if (r <= 0 || share <= 0) return 0;
        LongOpenHashSet seen = new LongOpenHashSet();
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
            boolean lit = false;
            for (Direction dir : Direction.values()) {
                n.setWithOffset(p, dir);
                if (!Terrain.ready(level, n)) continue;
                BlockState s = level.getBlockState(n);
                if (!lit && here.isAir() && s.isFlammable(level, n, dir.getOpposite()) && level.random.nextDouble() < share) {
                    Warheads.igniteAt(level, p.immutable());
                    lit = true;
                }
                if (Vec3.atCenterOf(n).distanceToSqr(pos) <= r * r && seen.add(n.asLong())) queue.enqueue(n.asLong());
            }
        }
        return cells;
    }

    private static boolean passes(ServerLevel level, BlockPos p, BlockState s) {
        return s.isAir() || s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty();
    }
}
