package ua.zentix.incoming.warhead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
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
 * упало; по поверхности — и на ступень в блок вверх (брызги ложатся на всю чашу воронки, не только на дно), на стену в
 * два блока и сквозь неё — нет. Клеток — по площади закона ({@link FuelPool#cells}: шахед 5, ракета 9). Клетка — только
 * там, где готовы и соседние чанки: огню, чтобы стоять, нужны блоки вокруг ({@link #holds}).</li>
 * <li><b>Огонь.</b> Ванильный: {@link BaseFireBlock#getState} на клетке — на негорючем грунте стоит на верхней грани, как
 * огонь на незерраке, на горючем — жжёт его по правилам игры; распространение — правила игры и модов на огонь.</li>
 * <li><b>Пока горит.</b> Ванильный огонь на камне или земле гаснет сам за секунды (возраст выше 3 без горючего рядом),
 * поэтому огонь клетки держится тем же, чем ваниль меняет возраст огня, — {@code setBlock} с возрастом 0 и
 * {@link Block#UPDATE_INVISIBLE}, без миксина. Погашенный не топливом (дождь, рука, мод на огонь) — снова горит: дождь
 * горящую лужу углеводородов не гасит ({@link #RELIGHT}). Если огонь в клетке гаснет почти сразу после поджига (мод на
 * огонь ставит свой и гасит его — Burnt на грунте под дождём, каждый тик), с миром не борется: следующая попытка — вдвое
 * позже, до {@link #RELIGHT_MAX}; топливо при этом горит по закону — его дым, свет и гул у клиента идут как шли. Чужой огонь в
 * клетке (мод на огонь ставит свой {@code FireBlock}) — тоже огонь: возраст у него сбрасывается, только если он есть.
 * Клетку засыпало блоком или залило водой — топливо там кончилось. Опору под клеткой сожгло или сломало — топливо капает
 * вниз в той же колонке до новой опоры: огня без опоры нет.</li>
 * <li><b>Сколько.</b> Клетки гаснут по закону: горящая площадь — доля {@link FuelPool#share}, первой гаснет самая высокая
 * (край плёнки), дольше всех — самая низкая, куда стекло больше всего ({@link FuelPool#cellSeconds}). Погасшая клетка
 * снимает огонь, если его держало только топливо (рядом нет горючего); у горючего огонь горит дальше сам. Чанк клетки
 * не готов (игрок ушёл) — она ждёт: огонь в незагруженном чанке не тикает, и снимается он, когда чанк снова готов.</li>
 * </ul>
 * Клиентам — {@link S2C.FuelFire}: состояние воронки (клетки, какие погасли раньше срока, оружие, тик удара) при её
 * появлении, при каждой перемене (стекло, засыпало, залило) и тому, кому уходит её чанк; дым, свет и гул пожара клиент
 * ведёт по тому же закону ({@code WeaponSpec.pool(lit)}). Сохраняется с миром ({@link ModAttachments#FUEL_FIRES}): после перезапуска сервера топливо
 * догорает в свой срок.
 */
public final class FuelFires {
    /** Глубже этого топливо, сорвавшееся с края или упавшее в пробитую крышу, опору не ищет, блоков. */
    static final int FALL = 64;
    /** Клеток, которые смотрит растекание, на клетку топлива не больше (обход вокруг стен и ям). */
    static final int SEARCH = 24;
    /**
     * Через столько тиков огонь клетки, погашенный не топливом, зажигается снова. Дождь горящую лужу бензина или керосина не
     * гасит: вода тяжелее и уходит под топливо, а испаряя ливень в 10 мм/ч, огонь тратит ~7 кВт/м² против 20–60 кВт/м²,
     * что пламя лужи отдаёт её поверхности (⚠ оценка по Babrauskas, SFPE Handbook, «Heat Release Rates»; поэтому для
     * таких пожаров нужна пена, NFPA 11), — а ванильный дождь гасит огонь в его тик.
     */
    static final int RELIGHT = 5;
    /** Огонь погас раньше этого после поджига — мир его не держит: следующая попытка — вдвое позже, тиков. */
    static final int QUICK = 40;
    /** Реже этого клетка с огнём, которого мир не держит, не зажигается, тиков (10 с). */
    static final int RELIGHT_MAX = 200;

    final List<Pool> pools;
    private int nextId;
    /** Сколько раз клетки зажигались снова и сколько времени ушло на учёт с загрузки мира (для проверок и пробы). */
    private long relights, nanos, ticks;

    /** Для {@link ModAttachments#FUEL_FIRES}: своё у каждого мира. */
    public FuelFires() {
        this(0, new ArrayList<>());
    }

    private FuelFires(int nextId, List<Pool> pools) {
        this.nextId = nextId;
        this.pools = new ArrayList<>(pools);
    }

    public static final Codec<FuelFires> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("next").forGetter(f -> f.nextId),
            Pool.CODEC.listOf().fieldOf("pools").forGetter(f -> f.pools)).apply(i, FuelFires::new));

    /** Сохранять ли: есть горящее топливо. */
    public boolean isEmpty() {
        return pools.isEmpty();
    }

    public static FuelFires get(ServerLevel level) {
        return level.getData(ModAttachments.FUEL_FIRES);
    }

    /**
     * Горящее топливо одного удара: клетки в порядке выгорания (0 — горит дольше всех), какие погасли раньше срока
     * (засыпало, залило, упало в воду) и какие кончились; когда каждая гаснет по закону (игровое время) — из оружия и тика
     * удара. Попытки зажечь снова — только в памяти.
     */
    static final class Pool {
        static final Codec<Pool> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(p -> p.id),
                Codec.INT.fieldOf("weapon").forGetter(p -> p.weapon),
                Codec.LONG.fieldOf("blast").forGetter(p -> p.blastTick),
                Codec.DOUBLE.fieldOf("reach").forGetter(p -> p.reach),
                Codec.LONG.listOf().fieldOf("cells").forGetter(p -> java.util.Arrays.stream(p.cells).map(BlockPos::asLong).toList()),
                Codec.LONG.fieldOf("smothered").forGetter(p -> p.smothered),
                Codec.LONG.fieldOf("done").forGetter(p -> p.done)).apply(i, Pool::new));

        final int id, weapon;
        final long blastTick;
        final double reach;
        final BlockPos[] cells;
        final long[] out, relight, relitAt, seen;
        final int[] backoff;
        /** Клетки (биты по номеру), чьё топливо кончилось раньше срока; клетки, с которыми покончено. */
        long smothered, done;

        Pool(int id, int weapon, long blastTick, double reach, List<Long> cells, long smothered, long done) {
            this.id = id;
            this.weapon = weapon;
            this.blastTick = blastTick;
            this.reach = reach;
            int n = cells.size();
            this.cells = new BlockPos[n];
            for (int k = 0; k < n; k++) this.cells[k] = BlockPos.of(cells.get(k));
            this.smothered = smothered;
            this.done = done;
            FuelPool law = law(weapon, n);
            this.out = new long[n];
            this.relight = new long[n];
            this.relitAt = new long[n];
            this.seen = new long[n];
            this.backoff = new int[n];
            for (int k = 0; k < n; k++) {
                out[k] = blastTick + Math.round(20 * law.cellSeconds(k, n));
                relitAt[k] = blastTick;
                backoff[k] = RELIGHT;
            }
        }

        boolean done(int k) {
            return (done >>> k & 1) != 0;
        }

        void finish(int k, boolean early) {
            done |= 1L << k;
            if (early) smothered |= 1L << k;
        }

        boolean finished() {
            return Long.bitCount(done) >= cells.length;
        }

        S2C.FuelFire packet() {
            return new S2C.FuelFire(id, List.of(cells), smothered, weapon, blastTick);
        }
    }

    /** Закон топлива оружия {@code weapon}, легшего на {@code lit} клеток ({@code WeaponSpec.pool(lit)}). */
    static FuelPool law(int weapon, int lit) {
        return WeaponType.byId(weapon).spec().pool(lit);
    }

    /**
     * Топливо оружия {@code weapon}, что осталось в воронке удара в {@code pos} (удар — в тик {@code blastTick}), ложится
     * на блоки и горит: клетки, огонь, учёт, пакет клиентам до {@code reach} блоков. Сколько клеток зажглось.
     */
    static int pour(ServerLevel level, Vec3 pos, WeaponType weapon, long blastTick, double reach) {
        if (!IncomingConfig.SERVER.fire.get()) return 0;
        int n = weapon.spec().pool().cells();
        if (n <= 0 || n > S2C.FuelFire.MAX_CELLS) return 0;
        List<Long> lit = new ArrayList<>(n);
        for (BlockPos p : cells(level, pos, n)) {
            if (level.getBlockState(p).getBlock() instanceof BaseFireBlock || Warheads.igniteIn(level, p)) lit.add(p.asLong());
        }
        if (lit.isEmpty()) return 0;
        FuelFires all = get(level);
        Pool pool = new Pool(all.nextId++, weapon.id(), blastTick, reach, lit, 0, 0);
        all.pools.add(pool);
        send(level, pool);
        return lit.size();
    }

    /** Для проверок: топливо оружия {@code weapon} после удара в {@code pos} в этот тик, без взрыва и без пакета. */
    public static int testPour(ServerLevel level, Vec3 pos, WeaponType weapon) {
        return testPour(level, pos, weapon, 0);
    }

    /** Для проверок: то же, но удар был {@code burned} тиков назад — топливо уже горело столько. */
    public static int testPour(ServerLevel level, Vec3 pos, WeaponType weapon, long burned) {
        return pour(level, pos, weapon, level.getGameTime() - burned, 0);
    }

    /** Состояние воронки — игрокам, до кого дотягивается её пакет взрыва (место — первая клетка). */
    private static void send(ServerLevel level, Pool pool) {
        if (pool.reach <= 0) return;
        BlockPos at = pool.cells[0];
        PacketDistributor.sendToPlayersNear(level, null, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, pool.reach, pool.packet());
    }

    /** Чанк уходит игроку (вошёл, подошёл, вернулся): воронки, чья первая клетка в нём, — ему, в их нынешнем состоянии. */
    public static void onChunkSent(ChunkWatchEvent.Sent e) {
        FuelFires all = e.getLevel().getExistingDataOrNull(ModAttachments.FUEL_FIRES.get());
        if (all == null || all.pools.isEmpty()) return;
        long chunk = e.getPos().toLong();
        for (Pool pool : all.pools) {
            if (pool.reach > 0 && ChunkPos.asLong(pool.cells[0]) == chunk) PacketDistributor.sendToPlayer(e.getPlayer(), pool.packet());
        }
    }

    /**
     * Куда ложится {@code n} клеток топлива удара в {@code pos}: вниз до опоры ({@link #land}), потом растекание — сперва
     * ниже, потом ближе к месту падения, на ступень вверх по поверхности ({@link #step}), по клеткам, где огонь держится
     * ({@link #holds}); по клеткам без него (плита, тропинка) топливо проходит, но не горит. Первой — самая низкая (горит
     * дольше всех). Пусто — некуда: вода, пустота, неготовые чанки.
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
                if (!Terrain.ready(level, q)) continue;
                // с края — вниз до опоры; ступень в блок — по поверхности (брызги ложатся на всю чашу воронки, не только на
                // дно); выше и сквозь стену — нет
                BlockPos to = open(level, q) ? land(level, q) : step(level, q);
                if (to != null && seen.add(to.asLong())) front.add(to);
            }
        }
        out.sort(Comparator.comparingInt(BlockPos::getY));
        return out;
    }

    /** Клетка над блоком {@code q} с формой (ступень в блок по поверхности), если она свободна; иначе null. */
    private static BlockPos step(ServerLevel level, BlockPos q) {
        BlockPos up = q.above();
        if (level.getBlockState(q).getCollisionShape(level, q).isEmpty() || !level.isInWorldBounds(up) || !open(level, up)) return null;
        return up;
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

    /**
     * Огонь Minecraft держится на клетке (на верхней грани опоры или у горючего рядом). Ваниль при этом смотрит все шесть
     * соседей (горючесть, опора, огонь душ) — у края чанка и соседний: только если готовы чанк клетки и все вокруг, иначе
     * соседний грузился бы прямо в тике.
     */
    static boolean holds(ServerLevel level, BlockPos p) {
        return Terrain.neighbourhoodReady(level, p.getX() >> 4, p.getZ() >> 4) && BaseFireBlock.getState(level, p).canSurvive(level, p);
    }

    /** Раз в тик мира: огонь клеток держится, пока есть топливо; погасшие — снимают свой огонь; перемены — клиентам. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        FuelFires all = level.getExistingDataOrNull(ModAttachments.FUEL_FIRES.get());
        if (all == null || all.pools.isEmpty()) return;
        long t0 = System.nanoTime(), now = level.getGameTime();
        all.pools.removeIf(pool -> {
            boolean changed = false;
            for (int k = 0; k < pool.cells.length; k++) if (!pool.done(k)) changed |= all.hold(level, pool, k, now);
            if (changed) send(level, pool);
            return pool.finished();
        });
        all.nanos += System.nanoTime() - t0;
        all.ticks++;
    }

    /** Клетка {@code k} воронки в тик {@code now}; сменилось ли то, что знает клиент (место или топливо кончилось раньше). */
    private boolean hold(ServerLevel level, Pool pool, int k, long now) {
        BlockPos cell = pool.cells[k];
        // чанк не готов (игрок ушёл): огонь там не тикает и не гаснет; выгорело — снять, когда чанк снова готов
        if (!Terrain.neighbourhoodReady(level, cell.getX() >> 4, cell.getZ() >> 4)) return false;
        if (now >= pool.out[k]) {
            burnOut(level, cell);
            pool.finish(k, false);
            return false;
        }
        BlockPos below = cell.below();
        boolean moved = false;
        if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
            // опору сожгло или сломало: топливо капает вниз в той же колонке; огонь на прежнем месте — уже не его
            BlockPos to = land(level, cell);
            if (to == null) {
                pool.finish(k, true);
                return true;
            }
            pool.cells[k] = cell = to;
            pool.relight[k] = now;
            pool.backoff[k] = RELIGHT;
            moved = true;
        }
        BlockState s = level.getBlockState(cell);
        if (s.getBlock() instanceof BaseFireBlock) {
            pool.seen[k] = now;
            // как ваниль меняет возраст своего огня: без соседей и без пакета клиентам (возраст не рисуется)
            if (s.hasProperty(FireBlock.AGE) && s.getValue(FireBlock.AGE) > 0) level.setBlock(cell, s.setValue(FireBlock.AGE, 0), Block.UPDATE_INVISIBLE);
            return moved;
        }
        // клетку засыпало или залило — топливо там кончилось
        if (!open(level, cell)) {
            pool.finish(k, true);
            return true;
        }
        if (now >= pool.relight[k]) {
            // огонь не прожил и QUICK тиков с прошлого поджига — мир его не держит (мод на огонь под дождём): реже
            long alive = Math.max(0, pool.seen[k] - pool.relitAt[k]);
            pool.backoff[k] = !moved && alive < QUICK ? Math.min(RELIGHT_MAX, 2 * pool.backoff[k]) : RELIGHT;
            pool.relight[k] = now + pool.backoff[k];
            pool.relitAt[k] = now;
            if (Warheads.igniteIn(level, cell)) relights++;
        }
        return moved;
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

    /** Клеток топлива, с которыми не покончено, — их нынешние места (для проверок). */
    public List<BlockPos> cells() {
        List<BlockPos> out = new ArrayList<>();
        for (Pool p : pools) for (int k = 0; k < p.cells.length; k++) if (!p.done(k)) out.add(p.cells[k]);
        return out;
    }

    /** Для проверок и пробы: зажиганий клеток снова, тиков учёта и среднее время учёта за тик, мс. */
    public String stats() {
        return String.format(java.util.Locale.ROOT, "pools %d cells %d relights %d ticks %d mean %.4f ms", pools.size(), cells().size(), relights, ticks,
                ticks == 0 ? 0 : nanos / 1e6 / ticks);
    }

    /** Для проверок: состояние воронок, как его получает клиент. */
    public List<S2C.FuelFire> state() {
        return pools.stream().map(Pool::packet).toList();
    }

    /** Для проверок: зажиганий клеток снова с загрузки мира. */
    public long relights() {
        return relights;
    }
}
