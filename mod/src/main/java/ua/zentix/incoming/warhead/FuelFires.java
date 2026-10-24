package ua.zentix.incoming.warhead;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.FuelPool;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Топливо, что после огненного шара осталось в воронке ({@link FuelPool}: доля бака, площадь воронки, выгорание тонкого
 * слоя), горит огнём Minecraft на настоящих блоках, пока оно есть. Закон решает, сколько топлива и сколько оно горит;
 * где — сами блоки: топливо — жидкость, оно падает до опоры и стекает вниз ({@link #cells}).
 * <ul>
 * <li><b>Где.</b> От точки удара вниз до первого блока с формой (дно воронки, пол под пробитой крышей, земля под мостом);
 * на воду или в пустоту — огня нет (плёнка на воде не держит горение, как у клиента). Дальше — растекание: из клеток, где
 * топливо уже лежит, к соседним по горизонтали, сперва ниже (с края стекает вниз до опоры), потом ближе к месту, куда
 * упало; вверх и сквозь стену — нет. Клеток — по площади закона ({@link FuelPool#cells}: шахед 5, ракета 9).</li>
 * <li><b>Огонь.</b> Ванильный: {@link BaseFireBlock#getState} на клетке — на негорючем грунте стоит на верхней грани, как
 * огонь на незерраке, на горючем — жжёт его по правилам игры; распространение — правила игры и модов на огонь.</li>
 * <li><b>Пока горит.</b> Ванильный огонь на камне или земле гаснет сам за секунды (возраст выше 3 без горючего рядом),
 * поэтому огонь клетки держится тем же, чем ваниль меняет возраст огня, — {@code setBlock} с возрастом 0 и
 * {@link Block#UPDATE_INVISIBLE}, без миксина. Погашенный не топливом (дождь, рука, мод на огонь) — снова горит не
 * реже раза в {@link #RELIGHT} тиков: горящее топливо не гасят ни дождь, ни удар рукой. Клетку засыпало блоком или
 * залило водой — топливо там кончилось. Опору под клеткой сожгло или сломало — топливо капает вниз в той же колонке до
 * новой опоры: огня без опоры нет.</li>
 * <li><b>Сколько.</b> Клетки гаснут по закону: горящая площадь — доля {@link FuelPool#share}, первой гаснет самая высокая
 * (край плёнки), дольше всех — самая низкая, куда стекло больше всего ({@link FuelPool#cellSeconds}). Погасшая клетка
 * снимает огонь, если его держало только топливо (рядом нет горючего); у горючего огонь горит дальше сам.</li>
 * </ul>
 * Клиентам — {@link S2C.FuelFire}: клетки и возраст, дым и звук пожара они ведут сами по тому же закону. Не сохраняется
 * (несохраняемый attachment мира {@link ModAttachments#FUEL_FIRES}): после перезапуска сервера огонь клеток догорает
 * по ванили — на голой земле за секунды.
 */
public final class FuelFires {
    /** Глубже этого топливо, сорвавшееся с края или упавшее в пробитую крышу, опору не ищет, блоков. */
    static final int FALL = 64;
    /** Клеток, которые смотрит растекание, на клетку топлива не больше (обход вокруг стен и ям). */
    static final int SEARCH = 24;
    /** Не чаще — снова зажечь клетку, чей огонь погас не по топливу, тиков. */
    static final int RELIGHT = 20;

    private final List<Pool> pools = new ArrayList<>();

    /** Для {@link ModAttachments#FUEL_FIRES}: своё у каждого мира, не сохраняется. */
    public FuelFires() {}

    public static FuelFires get(ServerLevel level) {
        return level.getData(ModAttachments.FUEL_FIRES);
    }

    /** Горящее топливо одного удара: клетки, когда каждая гаснет (игровое время), когда её можно зажечь снова. */
    static final class Pool {
        final BlockPos[] cells;
        final long[] out, relight;
        final boolean[] live;

        Pool(List<BlockPos> cells, FuelPool law, long blastTick) {
            int n = cells.size();
            this.cells = cells.toArray(new BlockPos[0]);
            this.out = new long[n];
            this.relight = new long[n];
            this.live = new boolean[n];
            for (int k = 0; k < n; k++) {
                out[k] = blastTick + Math.round(20 * law.cellSeconds(k, n));
                live[k] = true;
            }
        }

        boolean done() {
            for (boolean b : live) if (b) return false;
            return true;
        }
    }

    /**
     * Топливо оружия {@code weapon}, что осталось в воронке удара в {@code pos} (удар — в тик {@code blastTick}), ложится
     * на блоки и горит: клетки, огонь, учёт, пакет клиентам до {@code reach} блоков. Сколько клеток зажглось.
     */
    static int pour(ServerLevel level, Vec3 pos, WeaponType weapon, long blastTick, double reach) {
        if (!IncomingConfig.SERVER.fire.get()) return 0;
        FuelPool law = weapon.spec().pool();
        int n = law.cells();
        if (n <= 0) return 0;
        List<BlockPos> lit = new ArrayList<>(n);
        for (BlockPos p : cells(level, pos, n)) {
            if (level.getBlockState(p).getBlock() instanceof BaseFireBlock || Warheads.igniteIn(level, p)) lit.add(p);
        }
        if (lit.isEmpty()) return 0;
        // топливо легло на меньшую площадь (узкая яма, шахта) — слой толще: горит дольше и слабее (у клиента — то же)
        get(level).pools.add(new Pool(lit, law.spread(lit.size(), n), blastTick));
        int age = (int) Math.max(0, level.getGameTime() - blastTick);
        PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, reach, new S2C.FuelFire(List.copyOf(lit), weapon.id(), age));
        return lit.size();
    }

    /** Для проверок: топливо оружия {@code weapon} после удара в {@code pos} в этот тик, без взрыва и без пакета. */
    public static int testPour(ServerLevel level, Vec3 pos, WeaponType weapon) {
        return pour(level, pos, weapon, level.getGameTime(), 0);
    }

    /**
     * Куда ложится {@code n} клеток топлива удара в {@code pos}: вниз до опоры ({@link #land}), потом растекание — сперва
     * ниже, потом ближе к месту падения, по клеткам, где огонь держится ({@link #holds}); по клеткам без него (ступень
     * плиты, тропинка) топливо проходит, но не горит. Первой — самая низкая (горит дольше всех). Пусто — некуда: вода,
     * пустота, неготовые чанки.
     */
    static List<BlockPos> cells(ServerLevel level, Vec3 pos, int n) {
        BlockPos start = BlockPos.containing(pos);
        if (!Terrain.ready(level, start)) return List.of();
        // точка удара — в блоке (полблока в землю): топливо — над ним
        if (!open(level, start)) start = start.above();
        if (!open(level, start)) return List.of();
        BlockPos land = land(level, start);
        if (land == null) return List.of();
        Comparator<BlockPos> lower = Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingInt(p -> horizontal(p, land)).thenComparingLong(BlockPos::asLong);
        PriorityQueue<BlockPos> front = new PriorityQueue<>(lower);
        LongOpenHashSet seen = new LongOpenHashSet();
        front.add(land);
        seen.add(land.asLong());
        List<BlockPos> out = new ArrayList<>(n);
        for (int looked = 0; !front.isEmpty() && out.size() < n && looked < SEARCH * n; looked++) {
            BlockPos p = front.poll();
            if (holds(level, p)) out.add(p);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos q = p.relative(d);
                // вверх и сквозь стену топливо не течёт; с края — вниз до опоры
                if (!Terrain.ready(level, q) || !open(level, q)) continue;
                BlockPos to = land(level, q);
                if (to != null && seen.add(to.asLong())) front.add(to);
            }
        }
        out.sort(Comparator.comparingInt(BlockPos::getY));
        return out;
    }

    /** Квадрат расстояния по горизонтали. */
    private static int horizontal(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Свободная клетка {@code p} — топливо проходит её (воздух, огонь, растение без формы; не жидкость): вниз до первой
     * клетки, под которой блок с формой столкновений, не глубже {@link #FALL}. null — под ней вода или лава, пустота
     * или неготовый чанк.
     */
    static BlockPos land(ServerLevel level, BlockPos p) {
        BlockPos.MutableBlockPos at = p.mutable();
        for (int i = 0; i <= FALL; i++) {
            BlockPos below = at.below();
            if (!level.isInWorldBounds(below)) return null;
            BlockState s = level.getBlockState(below);
            if (!s.getFluidState().isEmpty()) return null;
            if (!s.getCollisionShape(level, below).isEmpty()) return at.immutable();
            at.move(Direction.DOWN);
        }
        return null;
    }

    /** Топливо проходит клетку: воздух, огонь, растение без формы, — не жидкость и не блок с формой. */
    static boolean open(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isAir() || s.getFluidState().isEmpty() && s.getCollisionShape(level, p).isEmpty();
    }

    /** Огонь Minecraft держится на клетке (на верхней грани опоры или у горючего рядом). */
    static boolean holds(ServerLevel level, BlockPos p) {
        return BaseFireBlock.getState(level, p).canSurvive(level, p);
    }

    /** Раз в тик мира: огонь клеток держится, пока есть топливо; погасшие — снимают свой огонь. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        FuelFires all = level.getExistingDataOrNull(ModAttachments.FUEL_FIRES.get());
        if (all == null || all.pools.isEmpty()) return;
        long now = level.getGameTime();
        all.pools.removeIf(pool -> {
            for (int k = 0; k < pool.cells.length; k++) if (pool.live[k]) hold(level, pool, k, now);
            return pool.done();
        });
    }

    private static void hold(ServerLevel level, Pool pool, int k, long now) {
        BlockPos cell = pool.cells[k];
        boolean ready = Terrain.neighbourhoodReady(level, cell.getX() >> 4, cell.getZ() >> 4);
        if (now >= pool.out[k]) {
            pool.live[k] = false;
            if (ready) burnOut(level, cell);
            return;
        }
        // чанк не готов (игрок ушёл): огонь там не тикает и не гаснет — ждать
        if (!ready) return;
        BlockPos below = cell.below();
        if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
            // опору сожгло или сломало: топливо капает вниз в той же колонке; огонь на прежнем месте — уже не его
            BlockPos to = land(level, cell);
            if (to == null) {
                pool.live[k] = false;
                return;
            }
            pool.cells[k] = cell = to;
            pool.relight[k] = now;
        }
        BlockState s = level.getBlockState(cell);
        if (s.getBlock() instanceof BaseFireBlock) {
            // как ваниль меняет возраст своего огня: без соседей и без пакета клиентам (возраст не рисуется)
            if (s.hasProperty(FireBlock.AGE) && s.getValue(FireBlock.AGE) > 0) level.setBlock(cell, s.setValue(FireBlock.AGE, 0), Block.UPDATE_INVISIBLE);
            return;
        }
        // клетку засыпало или залило — топливо там кончилось
        if (!open(level, cell)) {
            pool.live[k] = false;
            return;
        }
        if (now >= pool.relight[k]) {
            pool.relight[k] = now + RELIGHT;
            Warheads.igniteIn(level, cell);
        }
    }

    /**
     * Топливо клетки выгорело: огонь гаснет, если держался только им — рядом нет горючего и опора не вечный огонь
     * (незеррак); у горючего огонь горит дальше по правилам игры.
     */
    private static void burnOut(ServerLevel level, BlockPos cell) {
        BlockState s = level.getBlockState(cell);
        if (!(s.getBlock() instanceof BaseFireBlock)) return;
        BlockPos below = cell.below();
        if (level.getBlockState(below).isFireSource(level, below, Direction.UP)) return;
        for (Direction d : Direction.values()) {
            BlockPos n = cell.relative(d);
            if (level.getBlockState(n).isFlammable(level, n, d.getOpposite())) return;
        }
        level.removeBlock(cell, false);
    }

    /** Горящего топлива сейчас: клеток, что ещё горят (для проверок). */
    public int burning() {
        int n = 0;
        for (Pool p : pools) for (boolean b : p.live) if (b) n++;
        return n;
    }

    /** Клетки, что ещё горят, — их нынешние места (для проверок). */
    public List<BlockPos> cells() {
        List<BlockPos> out = new ArrayList<>();
        for (Pool p : pools) for (int k = 0; k < p.cells.length; k++) if (p.live[k]) out.add(p.cells[k]);
        return out;
    }

    /** Игровое время, когда гаснет последняя клетка (для проверок; нет горящих — {@link Long#MIN_VALUE}). */
    public long lastOut() {
        long t = Long.MIN_VALUE;
        for (Pool p : pools) for (int k = 0; k < p.cells.length; k++) if (p.live[k]) t = Math.max(t, p.out[k]);
        return t;
    }
}
