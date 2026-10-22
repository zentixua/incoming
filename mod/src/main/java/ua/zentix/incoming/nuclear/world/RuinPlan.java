package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;

import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.compat.SableTerrain;
import ua.zentix.incoming.grid.GridLights;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.util.Terrain;

import java.util.List;

/**
 * Руины одного чанка, построенные заранее ({@link RuinPlanner}), и их подмена в мире одним махом, когда до чанка дошла
 * волна: не блок за блоком ({@code setBlock} с картами высот, светом, соседями и Sable — ≈10 мкс на блок, в городе
 * тысячи блоков на чанк), а записью мест плана прямо в секции чанка и работой {@code setBlock} один раз на чанк.
 * <p>
 * План — только разница: изменённые места (по 4 байта: место в секции, секция, новое состояние из палитры плана,
 * «проверить свет», «через мир»), пожары, карты высот и нижние источники неба изменённых столбцов (значение до и после)
 * и места проверок света. Своих копий секций план не держит. Всё остальное в секции остаётся как есть к приходу волны —
 * лампы, которые погасил блэкаут (он меняет их прямо в палитре), блоки, поставленные после плана.
 * <p>
 * План устаревает, если места плана после него меняли (игрок, взрыв, другой мод): у каждой изменённой секции — хеш
 * старых состояний в местах плана (лампа и её погашенный двойник — одно и то же). Не совпал — план строится заново
 * по чанку как есть. Блоки вне мест плана план сверяет с чанком при подмене сам — по отпечаткам секций снимка, по
 * которому построен ({@link #sameBlocks}): изменения чанка мод не подслушивает.
 * <p>
 * Подмена повторяет то, что делает {@code LevelChunk.setBlockState} для каждого блока, один раз на чанк: места — в
 * секции на месте (как и у {@code setBlock}; секцию не заменяют чтением в неё — см. подводные камни), карты высот и
 * источники неба столбцов — из плана (значение «до» в чанке уже другое — столбец меняли после плана — весь чанк
 * заново), пустота секций — движку света, проверки света (верхняя изменённая точка столбца, новые и убранные блоки под
 * уцелевшим верхом, убранные и новые источники света) — одной задачей движку света. Игроки, которые видят чанк,
 * получают изменения в том же тике пакетами секций ({@code ChunkHolder.blockChanged}, как при {@code setBlock}), LOD
 * Distant Horizons — в том же тике в очередь ({@link DhUpdates}). Блоки с блок-сущностью и места POI так не меняются: после подмены их
 * меняет мир ({@link ColumnScar#replace}: {@code onRemove} с его последствиями, содержимое не высыпается, отложенные
 * данные снимаются). Стволы, упавшие в соседний чанк, кладёт очередь после руин соседа. Пожары ставятся вместе
 * с руинами (без {@code setBlock}: огонь у стен и деревьев не будит соседей), им только назначается тик огня.
 */
public final class RuinPlan {
    /** Карты высот чанка на сервере; номер в этом массиве — вид карты в {@link #heights}. */
    public static final Heightmap.Types[] HEIGHTMAP_TYPES = {Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE};
    /** Вид в {@link #heights}: нижний источник неба столбца. */
    static final int SKY = HEIGHTMAP_TYPES.length;

    /** Упаковка места: 12 бит — место в секции (y, z, x), 8 бит — номер секции, 10 бит — состояние в палитре, флаги. */
    static final int SECTION_SHIFT = 12, STATE_SHIFT = 20, STATE_MASK = 0x3FF;
    /** Флаг места: после подмены проверить свет. */
    static final int LIGHT = 1 << 30;
    /** Флаг места: блок-сущность или POI — меняется через мир. */
    static final int SLOW = 1 << 31;
    /** Сколько разных состояний вмещает палитра плана. */
    static final int MAX_STATES = STATE_MASK + 1;

    /** Тиков жидкости за секунду на чанк (остальные — в следующие секунды). */
    private static final int FLUID_TICKS = 64;
    private static final int[] NONE = new int[0];
    private static final long[] NO_LONGS = new long[0];
    private static final BlockState[] NO_STATES = new BlockState[0];

    /** План без изменений: чанк, где волне нечего менять (поле, вода, чанк у края зоны). */
    static final RuinPlan EMPTY = new RuinPlan(NONE, NO_LONGS, NO_STATES, NO_STATES, NONE, NONE, NO_LONGS, null, null, null, null, null, NO_LONGS, null);

    /**
     * Блоков менять нечего, но руины чанка «стоят»: соседи читают его исходным (он и есть исходный), счётчики — на
     * подмене; {@code biomes} — свет всё же опалил его биом.
     */
    static RuinPlan nothing(@Nullable RuinContext ctx, @Nullable ScorchedBiomes.Cells biomes) {
        return ctx == null && biomes == null ? EMPTY : new RuinPlan(NONE, NO_LONGS, NO_STATES, NO_STATES, NONE, NONE, NO_LONGS, null, null, null, null, ctx, NO_LONGS, biomes);
    }

    /**
     * Для строки в лог: время подмен по частям, нс — проверка плана, места и пожары в секциях (и пустота секций
     * движку света), карты высот и источники неба, свет и пакеты игрокам, блок-сущности через мир.
     */
    static final long[] PHASES = new long[5];
    /** Для строки в лог: самая долгая часть «через мир» — время (нс), чанк, сколько мест через мир (с прошлой строки). */
    static long slowestWorldNanos, slowestWorldChunk;
    static int slowestWorldCells;
    /** В этом чанке: самый долгий блок через мир (его замена), и вызов LOD Distant Horizons, нс. */
    @Nullable
    static BlockState slowestWorldBlock;
    static long slowestWorldBlockNanos, slowestWorldDhNanos;

    /**
     * Карты высот и источники неба изменённых столбцов, посчитанные по плану: тройки (столбец {@code << 3} | вид —
     * номер в {@link #HEIGHTMAP_TYPES} или {@link #SKY}; значение до руин; значение после). Только те, что меняются.
     */
    private final int[] heights;
    /** Места с проверкой света (кроме мест «через мир»: их свет проверяет мир), как {@link BlockPos#asLong}. */
    private final long[] lightAt;
    /**
     * Отпечатки секций снимка, по которому построен план: как есть ({@link ChunkShot#rawPrint}) и с точностью до
     * погашенных ламп ({@link ChunkShot#normalPrint}); null — сверять не с чем (план без мест).
     */
    @Nullable
    private final long[] prints, normals;
    /** Места плана по секциям (по возрастанию номера секции). */
    private final int[] cells;
    /** По номеру секции: хеш старых состояний в её местах (секции без мест — 0, не проверяются). */
    private final long[] oldHashes;
    private final BlockState[] states;
    /** Старые состояния мест плана (по порядку {@link #cells}): соседи читают по ним исходный чанк, пока его руины стоят. */
    private final BlockState[] olds;
    /** Руины подрыва по всем чанкам (подмена отмечается там: её старые блоки — исходный мир соседей); null — вне подрыва. */
    @Nullable
    private final RuinContext ctx;
    /** Жидкость у изменённых мест: место ({@link BlockPos#asLong}) — ей тик, чтобы она затекла в пролом или стекла. */
    private final long[] fluidTicks;
    /** Пожары: место (как в {@link #cells}) и состояние огня из палитры. */
    private final int[] fires;
    /** Брёвна поваленных деревьев в соседних чанках: место (высота — по поверхности при подмене) и состояние. */
    private final LongArrayList outside;
    private final List<BlockState> outsideState;
    /** Ступени опалённого биома по клеткам чанка ({@link ScorchedBiomes}); null — биом не меняется. */
    @Nullable
    private final ScorchedBiomes.Cells biomes;
    /**
     * Обугленные вспышкой грани уцелевших блоков чанка ({@link ua.zentix.incoming.nuclear.flash.FlashChar#mark}): ставятся
     * в отметки чанка с руинами ({@link ua.zentix.incoming.nuclear.flash.FlashSync#chars}); null — не считались.
     */
    @Nullable
    private int[] flash;
    /**
     * Слой вспышки ({@link RuinPlanner#flash}): пожары, которые собьёт волна (места, как в {@link #fires}, без состояния,
     * по возрастанию), — в счёт пожаров подрыва не идут: гаснут с руинами чанка до своего первого тика ({@link #write});
     * null — таких нет.
     */
    @Nullable
    private int[] blown;
    /** Места пожаров по возрастанию (без состояния), для поиска; считаются по первому запросу. */
    @Nullable
    private int[] fireKeys;
    /** Секции, где у плана места или пожары (бит на секцию); считаются по первому запросу (поток сервера). */
    @Nullable
    private long[] sectionBits;

    RuinPlan(int[] cells, long[] oldHashes, BlockState[] states, BlockState[] olds, int[] fires, int[] heights, long[] lightAt, @Nullable long[] prints,
            @Nullable long[] normals, LongArrayList outside, List<BlockState> outsideState, @Nullable RuinContext ctx, long[] fluidTicks,
            @Nullable ScorchedBiomes.Cells biomes) {
        this.biomes = biomes;
        this.olds = olds;
        this.ctx = ctx;
        this.fluidTicks = fluidTicks;
        this.prints = prints;
        this.normals = normals;
        this.heights = heights;
        this.lightAt = lightAt;
        this.cells = cells;
        this.oldHashes = oldHashes;
        this.states = states;
        this.fires = fires;
        this.outside = outside;
        this.outsideState = outsideState;
    }

    /** Гарь вспышки плана (тот поток, что строит план, до того как план отдан). */
    void flash(int[] marks) {
        this.flash = marks;
    }

    /** Гарь вспышки плана: метки граней; пусто — не считались или ничего не обуглено. */
    public int[] flash() {
        return flash == null ? NONE : flash;
    }

    /** Пожары слоя вспышки, которые собьёт волна (тот поток, что строит план). */
    void blown(int[] cells) {
        java.util.Arrays.sort(cells);
        this.blown = cells;
    }

    /** Пожар слоя вспышки на месте {@code key} собьёт волна: его нет в счёте пожаров подрыва. */
    boolean isBlown(int key) {
        return blown != null && java.util.Arrays.binarySearch(blown, key) >= 0;
    }

    /** Место (без состояния) — среди пожаров плана. */
    boolean hasFire(int key) {
        int[] keys = fireKeys;
        if (keys == null) {
            keys = new int[fires.length];
            for (int k = 0; k < keys.length; k++) keys[k] = fires[k] & KEY_MASK;
            java.util.Arrays.sort(keys);
            fireKeys = keys;
        }
        return java.util.Arrays.binarySearch(keys, key) >= 0;
    }

    /** Место плана (без состояния и флагов): номер в {@link #cells}, иначе отрицательное. */
    private int find(int key) {
        int lo = 0, hi = cells.length - 1;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1, k = cells[m] & KEY_MASK;
            if (k < key) lo = m + 1;
            else if (k > key) hi = m - 1;
            else return m;
        }
        return -1;
    }

    /** Место в секции и номер секции — без состояния и флагов. */
    static final int KEY_MASK = (1 << STATE_SHIFT) - 1;

    /**
     * Состояние места до вспышки по состоянию {@code live} в чанке, где стоит этот слой вспышки: его огонь — воздух, его
     * новое состояние места — старое; иначе — как есть (место после вспышки меняли — план руин по нему устареет).
     */
    BlockState beforeFlash(int key, BlockState live) {
        if (live.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock && hasFire(key)) live = Blocks.AIR.defaultBlockState();
        int k = find(key);
        return k >= 0 && live == states[(cells[k] >>> STATE_SHIFT) & STATE_MASK] ? olds[k] : live;
    }

    /** Есть ли у слоя места или пожары в секции {@code i} (поток сервера). */
    boolean touches(int i) {
        long[] bits = sectionBits;
        if (bits == null) {
            int top = 0;
            for (int c : cells) top = Math.max(top, section(c));
            for (int c : fires) top = Math.max(top, section(c));
            bits = new long[(top >> 6) + 1];
            for (int c : cells) set(bits, section(c));
            for (int c : fires) set(bits, section(c));
            sectionBits = bits;
        }
        return i >= 0 && (i >> 6) < bits.length && bit(bits, i);
    }

    /**
     * Мир до вспышки в копии чанка (снимок для руин, {@link ChunkShot}): огонь слоя — воздух, места слоя с его новым
     * состоянием — старое; карты высот и источники неба столбцов слоя — заново по копии ({@link #rescanCopy}): значения
     * «после» в плане слоя не знают его пожаров (огонь поднимает поверхность мира на блок) и не годятся, если чанк меняли
     * после снимка слоя. Секции, которых касается слой ({@link #touches}), — свои копии ({@link #copyStates}); null —
     * секция из воздуха. Поток сервера.
     */
    void unflash(PalettedContainer<BlockState>[] sections, int minY, int[] shotHeights, @Nullable int[] sky) {
        BlockState air = Blocks.AIR.defaultBlockState();
        int[] top = new int[256];
        java.util.Arrays.fill(top, Integer.MIN_VALUE);
        for (int c : fires) {
            int column = c & 0xFF;
            top[column] = Math.max(top[column], minY + (section(c) << 4) + ((c >> 8) & 15));
            PalettedContainer<BlockState> s = sections[section(c)];
            if (s != null && s.get(c & 15, (c >> 8) & 15, (c >> 4) & 15).getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock) {
                s.getAndSetUnchecked(c & 15, (c >> 8) & 15, (c >> 4) & 15, air);
            }
        }
        for (int k = 0; k < cells.length; k++) {
            int c = cells[k], i = section(c), column = c & 0xFF;
            top[column] = Math.max(top[column], minY + (i << 4) + ((c >> 8) & 15));
            PalettedContainer<BlockState> s = sections[i];
            BlockState now = s == null ? air : s.get(c & 15, (c >> 8) & 15, (c >> 4) & 15);
            if (now != states[(c >>> STATE_SHIFT) & STATE_MASK]) continue;
            if (s == null) {
                if (olds[k].isAir()) continue;
                sections[i] = s = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
            }
            s.getAndSetUnchecked(c & 15, (c >> 8) & 15, (c >> 4) & 15, olds[k]);
        }
        rescanCopy(sections, minY, shotHeights, sky, top);
    }

    /**
     * Слой вспышки подрыва стоит в чанке ({@link FlashScorch#marked}), а его плана в памяти нет (руины подрыва забыты,
     * перезапуск сервера): огонь в своих копиях секций ({@code own}) — воздух, карты высот столбцов с огнём — по копии.
     * Поток сервера.
     *
     * @return были ли в копиях огонь
     */
    static boolean unfire(PalettedContainer<BlockState>[] sections, boolean[] own, int minY, int[] heights, @Nullable int[] sky) {
        BlockState air = Blocks.AIR.defaultBlockState();
        int[] top = new int[256];
        java.util.Arrays.fill(top, Integer.MIN_VALUE);
        boolean any = false;
        for (int i = 0; i < sections.length; i++) {
            PalettedContainer<BlockState> s = sections[i];
            if (s == null || !own[i]) continue;
            for (int k = 0; k < 4096; k++) {
                int x = k & 15, y = k >> 8, z = (k >> 4) & 15;
                if (!(s.get(x, y, z).getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock)) continue;
                s.getAndSetUnchecked(x, y, z, air);
                top[z << 4 | x] = Math.max(top[z << 4 | x], minY + (i << 4) + y);
                any = true;
            }
        }
        if (any) rescanCopy(sections, minY, heights, sky, top);
        return any;
    }

    /**
     * Карты высот ({@code вид * 256 + столбец}: первый свободный) и нижние источники неба столбцов копии секций после её
     * правки — по блокам копии, тем же правилом, что у плана ({@code RuinPlanner.Sections.heights}): {@code top} — верхнее
     * правленое место столбца ({@code Integer.MIN_VALUE} — столбец не правили). Выше него копия та же, поэтому значение,
     * которое над ним, остаётся, а остальные ищутся от него вниз. Свойства — таблица потока сервера ({@link Blast#SERVER}).
     */
    private static void rescanCopy(PalettedContainer<BlockState>[] sections, int minY, int[] heights, @Nullable int[] sky, int[] top) {
        for (int column = 0; column < 256; column++) {
            int t = top[column];
            if (t == Integer.MIN_VALUE) continue;
            int lx = column & 15, lz = column >> 4;
            for (int type = 0; type < HEIGHTMAP_TYPES.length; type++) {
                int i = type * 256 + column;
                if (heights[i] - 1 > t) continue;
                int y = t;
                while (y >= minY && !Blast.SERVER.get(copyState(sections, minY, lx, y, lz)).opaque(type)) y--;
                heights[i] = y + 1;
            }
            if (sky == null || sky[column] > t + 1) continue;
            int source = minY - 1;
            BlockState upper = copyState(sections, minY, lx, t + 1, lz);
            for (int y = t; y >= minY - 1; y--) {
                BlockState lower = copyState(sections, minY, lx, y, lz);
                Blast.Props below = Blast.SERVER.get(lower);
                if (below.lightBlock() != 0 || Shapes.faceShapeOccludes(Blast.SERVER.get(upper).occludeDown(), below.occludeUp())) {
                    source = y + 1;
                    break;
                }
                upper = lower;
            }
            sky[column] = source;
        }
    }

    /** Состояние места копии секций (y — мира); вне высоты мира — воздух. */
    private static BlockState copyState(PalettedContainer<BlockState>[] sections, int minY, int lx, int y, int lz) {
        int i = (y - minY) >> 4;
        if (y < minY || i >= sections.length || sections[i] == null) return Blocks.AIR.defaultBlockState();
        return sections[i].get(lx, y & 15, lz);
    }

    /** Состояние для сравнения со старым: погашенная блэкаутом лампа — та же лампа. */
    static BlockState normal(BlockState s) {
        BlockState lit = GridLights.lit(s);
        return lit != null ? lit : s;
    }

    /** Добавить старое состояние места к хешу секции (порядок мест — как в плане). */
    static long mix(long h, BlockState old) {
        return mixNormal(h, normal(old));
    }

    /** То же по уже приведённому состоянию ({@link Blast.Props#normal}: фоновый план не зовёт {@code GridLights}). */
    static long mixNormal(long h, BlockState normal) {
        return (h ^ System.identityHashCode(normal)) * 0x9E3779B97F4A7C15L + 1;
    }

    /** Для проверок: чем план отличается от другого (те же места, новые и старые состояния, пожары); null — ничем. */
    @Nullable
    public String differs(RuinPlan o) {
        int mask = (1 << STATE_SHIFT) - 1;
        java.util.TreeMap<Integer, String> a = new java.util.TreeMap<>(), b = new java.util.TreeMap<>();
        for (int k = 0; k < cells.length; k++) a.put(cells[k] & mask, olds[k] + "→" + states[(cells[k] >>> STATE_SHIFT) & STATE_MASK]);
        for (int k = 0; k < o.cells.length; k++) b.put(o.cells[k] & mask, o.olds[k] + "→" + o.states[(o.cells[k] >>> STATE_SHIFT) & STATE_MASK]);
        int n = 0;
        StringBuilder out = new StringBuilder();
        java.util.TreeSet<Integer> keys = new java.util.TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (int c : keys) {
            if (java.util.Objects.equals(a.get(c), b.get(c))) continue;
            if (n++ < 4) out.append(" [x ").append(c & 15).append(" y ").append((section(c) << 4) + ((c >> 8) & 15)).append(" z ").append((c >> 4) & 15)
                    .append(": ").append(a.get(c)).append(" | ").append(b.get(c)).append(']');
        }
        // пожары — по местам: тень светового импульса у соседа, чьи руины уже стоят, — та же, что до них
        java.util.TreeSet<Integer> fa = new java.util.TreeSet<>(), fb = new java.util.TreeSet<>();
        for (int c : fires) fa.add(c & mask);
        for (int c : o.fires) fb.add(c & mask);
        java.util.TreeSet<Integer> onlyA = new java.util.TreeSet<>(fa), onlyB = new java.util.TreeSet<>(fb);
        onlyA.removeAll(fb);
        onlyB.removeAll(fa);
        if (!onlyA.isEmpty() || !onlyB.isEmpty()) {
            out.append(" fires ").append(fa.size()).append(" | ").append(fb.size()).append(", only left ").append(onlyA.size())
                    .append(", only right ").append(onlyB.size());
        }
        // карты высот и нижние источники неба изменённых столбцов (у снимка с диска источники неба посчитаны по блокам)
        boolean heightsDiffer = !java.util.Arrays.equals(heights, o.heights);
        if (heightsDiffer) out.append(" heightmaps differ (").append(heights.length).append(" | ").append(o.heights.length).append(')');
        boolean biomesDiffer = biomes == null ? o.biomes != null : !biomes.same(o.biomes);
        if (biomesDiffer) out.append(" biomes ").append(biomes).append(" | ").append(o.biomes);
        return n == 0 && onlyA.isEmpty() && onlyB.isEmpty() && !heightsDiffer && !biomesDiffer ? null : n + " cells" + out;
    }

    /**
     * Какие соседи нужны подмене загруженными (0 — хватает самого чанка). Радиус 1 — когда она меняет через мир
     * блок-сущность или место POI ({@code SLOW}) у края чанка: {@code onRemove} сундука, компаратора смотрит соседние
     * блоки ({@link #EDGE} от края; Sable на смену блока — {@link NuclearTickets#SABLE_REACH}), — или когда у чанка
     * аппараты Sable ({@code SableTerrain}: на каждое место он читает соседей). Радиус {@link RuinPlanner#REACH} —
     * когда через мир снимается блок-сущность не из ванили, где бы она ни стояла: её {@code onRemove} ходит цепочкой
     * (лента Create снимает сегменты до 20 блоков через {@code getBlockState}, сеть валов обходится через
     * {@code getBlockEntity}) и грузила бы соседа синхронно.
     * Остальное пишется в свои секции; тики жидкости в незагруженных соседях не ставятся ({@link #write}).
     */
    public int neighbourRadius(ServerLevel level, LevelChunk chunk) {
        int r = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ(), minY = chunk.getMinBuildHeight();
        for (int c : cells) {
            if ((c & SLOW) == 0) continue;
            BlockState old = chunk.getBlockState(at(m, c, x0, z0, minY));
            if (old.hasBlockEntity() && !"minecraft".equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(old.getBlock()).getNamespace())) {
                return RuinPlanner.REACH;
            }
            int x = c & 15, z = (c >> 4) & 15;
            if (x < EDGE || x > 15 - EDGE || z < EDGE || z > 15 - EDGE) r = 1;
        }
        return r > 0 || SableTerrain.watched(level, chunk) ? 1 : 0;
    }

    /** Подмене нужны соседи ({@link #neighbourRadius}). */
    public boolean needsNeighbours(ServerLevel level, LevelChunk chunk) {
        return neighbourRadius(level, chunk) > 0;
    }

    /** Подмене нужны соседи ({@link #neighbourRadius}), а они не загружены: радиус, которого очередь руин ждёт, иначе 0. */
    public int waitsNeighbours(ServerLevel level, LevelChunk chunk) {
        int r = neighbourRadius(level, chunk);
        return r > 0 && !NuclearTickets.neighbourhoodLoaded(level, chunk.getPos(), r) ? r : 0;
    }

    /** Сколько блоков от края чанка {@code onRemove} блок-сущности может читать соседей (компаратор — через блок). */
    static final int EDGE = 2;

    /** Сколько тиков жидкости ставит план. */
    public int fluidTickCount() {
        return fluidTicks.length;
    }

    /** Больше всего тиков жидкости плана за одну секунду его расписания (проверки): не больше {@link #FLUID_TICKS}. */
    public int maxFluidTicksPerSecond() {
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap at = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        int most = 0;
        for (int k = 0; k < fluidTicks.length; k++) {
            long p = fluidTicks[k];
            int second = (fluidDelay(k, BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p)) - FLUID_DELAY) / 20;
            most = Math.max(most, at.addTo(second, 1) + 1);
        }
        return most;
    }

    /** Первый тик жидкости — через столько тиков после подмены. */
    private static final int FLUID_DELAY = 5;

    /**
     * Через сколько тиков тик жидкости {@code k} плана: по {@link #FLUID_TICKS} на секунду по порядку, внутри секунды —
     * вразнобой по хешу места.
     */
    private static int fluidDelay(int k, int x, int y, int z) {
        return FLUID_DELAY + 20 * (k / FLUID_TICKS) + (int) (RuinPlanner.hash(x, y, z, 37) * 20);
    }

    /** Сколько пожаров ставит план (проверки). */
    public int fireCount() {
        return fires.length;
    }

    /** Сколько блоков меняет план (без пожаров). */
    public int changedBlocks() {
        return cells.length;
    }

    /** Опаляет ли план биом чанка (проверки). */
    public boolean recolours() {
        return biomes != null;
    }

    /**
     * До {@code max} мест, где волна снесла блок целиком (был блок с моделью, стал воздух), — с блоком, который там был:
     * их куски разлетаются у игроков, которые видят чанк ({@code S2C.NukeDebris}). Места выбраны по хешу места (самые
     * малые), а не по порядку плана: разбросаны по чанку, и тот же план даёт те же места. Места, которые уже сменил слой
     * вспышки (растения сгорели со вспышкой), не разлетаются. Поток сервера, проход по всем местам плана — на место только
     * сравнения и перемешивание числа.
     */
    public List<S2C.NukeDebris.Piece> thrown(LevelChunk chunk, int max) {
        return debris(chunk, max).pieces();
    }

    /** Места снесённого ({@link #thrown}) и сколько их всего в чанке — из них места выбраны. */
    public record Debris(List<S2C.NukeDebris.Piece> pieces, int removed) {
        static final Debris NONE = new Debris(List.of(), 0);
    }

    /** {@link #thrown} со счётом всех мест, где волна снесла блок целиком: места — выборка из них. */
    public Debris debris(LevelChunk chunk, int max) {
        if (cells.length == 0 || max <= 0) return Debris.NONE;
        ChunkPos pos = chunk.getPos();
        int x0 = pos.getMinBlockX(), z0 = pos.getMinBlockZ(), minY = chunk.getMinBuildHeight();
        long salt = pos.toLong() * 0x9E3779B97F4A7C15L;
        int local = (1 << STATE_SHIFT) - 1;
        RuinPlan flash = flashOf(chunk);
        // лучшие по возрастанию хеша: номер места в плане и хеш
        int[] best = new int[max];
        long[] keys = new long[max];
        int n = 0, removed = 0;
        for (int k = 0; k < cells.length; k++) {
            int c = cells[k];
            if (!states[(c >>> STATE_SHIFT) & STATE_MASK].isAir() || olds[k].isAir() || olds[k].getRenderShape() != RenderShape.MODEL) continue;
            if (flash != null && flash.find(c & KEY_MASK) >= 0) continue;
            removed++;
            long key = HashCommon.mix(salt + (c & local)) >>> 1;
            if (n == max && key >= keys[n - 1]) continue;
            int i = n < max ? n++ : n - 1;
            while (i > 0 && keys[i - 1] > key) {
                keys[i] = keys[i - 1];
                best[i] = best[i - 1];
                i--;
            }
            keys[i] = key;
            best[i] = k;
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        List<S2C.NukeDebris.Piece> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new S2C.NukeDebris.Piece(at(m, cells[best[i]], x0, z0, minY).immutable(), olds[best[i]]));
        return new Debris(out, removed);
    }

    /** Примерный размер плана в памяти, байты (для строки в лог). */
    public long bytes() {
        if (this == EMPTY) return 0;
        return 64 + 12L * cells.length + 8L * fluidTicks.length + 8L * oldHashes.length + 8L * states.length + 4L * fires.length + 4L * heights.length
                + 8L * lightAt.length + (prints == null ? 0 : 16L * prints.length) + (outside == null ? 0 : 16L * outside.size()) + (biomes == null ? 0 : 96)
                + (flash == null ? 0 : 4L * flash.length) + (blown == null ? 0 : 4L * blown.length);
    }

    private static int section(int cell) {
        return (cell >>> SECTION_SHIFT) & 0xFF;
    }

    /**
     * Места плана руин подрыва {@code d} в секциях чанка всё те же, что при построении плана. План строится по миру до
     * вспышки: где в чанке стоит слой вспышки подрыва ({@link RuinContext#flashed}), места сверяются по миру до него
     * ({@link #beforeFlash}); слой стоит, а его плана в памяти нет ({@link FlashScorch#marked}), — огонь читается
     * воздухом, как в снимке такого чанка ({@link #unfire}).
     */
    boolean current(LevelChunk chunk, Detonation d) {
        RuinPlan flash = flashOf(chunk);
        return current(chunk, flash, flash == null && FlashScorch.marked(chunk, d.id()));
    }

    /** Слой вспышки чанка, по которому сверяется этот план руин (у самого слоя — null). */
    @Nullable
    private RuinPlan flashOf(LevelChunk chunk) {
        RuinPlan f = ctx == null ? null : ctx.flashed(chunk.getPos().toLong());
        return f == this ? null : f;
    }

    private boolean current(LevelChunk chunk, @Nullable RuinPlan flash, boolean marked) {
        LevelChunkSection[] now = chunk.getSections();
        int k = 0;
        while (k < cells.length) {
            int i = section(cells[k]);
            if (i >= now.length) return false;
            LevelChunkSection s = now[i];
            long h = 0;
            for (; k < cells.length && section(cells[k]) == i; k++) {
                int c = cells[k];
                BlockState live = s.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15);
                if (flash != null) live = flash.beforeFlash(c & KEY_MASK, live);
                else if (marked && fire(live)) live = Blocks.AIR.defaultBlockState();
                h = mix(h, live);
            }
            if (h != oldHashes[i]) return false;
        }
        return true;
    }

    private static boolean fire(BlockState s) {
        return s.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock;
    }

    /**
     * Руины в копии чанка не из мира — с диска, для LOD Distant Horizons вдали ({@link FarLods}): все места плана (и
     * «через мир» — копии нечего будить) и, если {@code fires}, пожары без счётчика пожаров подрыва — копия его не
     * тратит. Любой поток: зовёт
     * только секции копии. На диске двойников ламп не бывает ({@code ChunkSaves}), поэтому хеш старых состояний —
     * по состояниям как есть.
     *
     * @return сколько клеток биома поменялось ({@link ScorchedBiomes#write}); −1 — места плана в копии уже не те (чанк
     *         меняли после плана): копия не годится
     */
    int applyToCopy(LevelChunkSection[] sections, boolean fires, net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomeRegistry,
                        int minSection) {
        int k = 0;
        while (k < cells.length) {
            int i = section(cells[k]);
            if (i >= sections.length) return -1;
            LevelChunkSection s = sections[i];
            long h = 0;
            for (; k < cells.length && section(cells[k]) == i; k++) {
                int c = cells[k];
                h = mixNormal(h, s.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15));
            }
            if (h != oldHashes[i]) return -1;
        }
        for (int c : cells) sections[section(c)].setBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15, states[(c >>> STATE_SHIFT) & STATE_MASK], false);
        for (int c : fires ? this.fires : NONE) {
            LevelChunkSection s = sections[section(c)];
            if (s.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15).isAir()) {
                s.setBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15, states[(c >>> STATE_SHIFT) & STATE_MASK], false);
            }
        }
        return biomes == null ? 0 : ScorchedBiomes.write(biomeRegistry, sections, minSection, biomes);
    }

    /**
     * Для проверок: секции чанка (копии, с биомами), какими их сделал бы один этот план — все места и пожары там, где
     * воздух, без счётчика пожаров ({@link #applyToCopy}).
     */
    public LevelChunkSection[] testAppliedCopy(ServerLevel level, LevelChunk chunk) {
        LevelChunkSection[] live = chunk.getSections(), out = new LevelChunkSection[live.length];
        for (int i = 0; i < live.length; i++) out[i] = copyOf(live[i]);
        if (this != EMPTY && applyToCopy(out, true, level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME), chunk.getMinSection()) < 0) {
            throw new IllegalStateException("plan does not fit chunk " + chunk.getPos());
        }
        return out;
    }

    /**
     * Все блоки чанка те же, что в снимке плана (поток сервера): секция за секцией — отпечаток как есть, а где он
     * разошёлся (перестроенная палитра, лампа, погашенная блэкаутом), — по местам с точностью до погашенных ламп. Тогда
     * карты высот и источники неба из плана верны; нет — столбцы плана считаются по чанку ({@link #rescanColumns}).
     */
    public boolean sameBlocks(LevelChunk chunk) {
        if (prints == null || normals == null) return false;
        LevelChunkSection[] live = chunk.getSections();
        if (live.length != prints.length) return false;
        for (int i = 0; i < live.length; i++) {
            if (ChunkShot.rawPrint(live[i]) != prints[i] && ChunkShot.normalPrint(live[i]) != normals[i]) return false;
        }
        return true;
    }

    /**
     * Старое состояние места чанка до этих руин (y — мира) или null, если место не из плана. Для соседей, которые
     * строят свои планы по исходному миру, пока руины этого чанка уже стоят.
     */
    @Nullable
    BlockState oldState(int lx, int y, int lz, int minY) {
        int i = (y - minY) >> 4;
        if (i < 0 || i >= oldHashes.length || oldHashes[i] == 0) return null;
        int key = i << SECTION_SHIFT | (y & 15) << 8 | lz << 4 | lx;
        int lo = 0, hi = cells.length - 1;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1, k = cells[m] & (1 << STATE_SHIFT) - 1;
            if (k < key) lo = m + 1;
            else if (k > key) hi = m - 1;
            else return olds[m];
        }
        return null;
    }

    /** Каждое старое состояние мест плана (с повторами). */
    void forEachOld(java.util.function.Consumer<BlockState> each) {
        for (BlockState o : olds) each.accept(o);
    }

    /** Есть ли среди старых состояний мест секции {@code section} такое, что {@code test}. */
    boolean anyOld(int section, java.util.function.Predicate<BlockState> test) {
        if (section < 0 || section >= oldHashes.length || oldHashes[section] == 0) return false;
        for (int k = 0; k < cells.length; k++) if (section(cells[k]) == section && test.test(olds[k])) return true;
        return false;
    }

    /** Верх столбцов по старым блокам мест плана: [0..255] — не-воздух, [256..511] — опора без листвы (или жидкость); MIN — нет. */
    int[] oldTops(int minY) {
        int[] t = oldTops;
        if (t != null) return t;
        t = new int[512];
        java.util.Arrays.fill(t, Integer.MIN_VALUE);
        for (int k = 0; k < cells.length; k++) {
            int c = cells[k], column = c & 0xFF, y = minY + (section(c) << 4) + ((c >> 8) & 15);
            BlockState o = olds[k];
            if (!o.isAir()) t[column] = Math.max(t[column], y);
            if (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(o)) t[256 + column] = Math.max(t[256 + column], y);
        }
        return oldTops = t;
    }

    @Nullable
    private int[] oldTops;

    /**
     * Поставить руины в чанк (он и соседи загружены целиком — проверяет вызывающий). Устаревший план не ставится. Где
     * стоит слой вспышки подрыва ({@link FlashScorch}), руины — поверх него ({@link #write}): огонь слоя, которого нет
     * среди пожаров руин (сбит волной, место завалено, снесено то, на чём он стоял), гаснет, остальной горит дальше и
     * только теперь получает первый тик; отметка слоя на чанке снимается.
     *
     * @param d подрыв, чьи это руины: гарь вспышки встаёт его слоем. Не {@code ctx.d}: руины, подготовленные заранее,
     *          строятся по месту подрыва без номера и времени ({@code NuclearWarhead.geometry}), а слой гари должен быть
     *          тем же, что у силуэтов подрыва, и со временем вспышки
     * @return false — план устарел, ничего не изменено
     */
    public boolean apply(ServerLevel level, LevelChunk chunk, Detonation d, ColumnScar.Budget budget) {
        if (this == EMPTY) {
            // руины ничего не меняют, а слой вспышки зажёг огонь: волна сбила весь — гаснет (иначе стоял бы без тиков)
            if (FlashScorch.marked(chunk, d.id())) {
                RuinContext kept = NuclearWorld.get(level).ruinsIfKept(d.id());
                RuinPlan flash = kept == null ? null : kept.flashed(chunk.getPos().toLong());
                write(level, chunk, budget, System.nanoTime(), false, flash, flash == null, false);
                FlashScorch.unmark(chunk, d.id());
            }
            return true;
        }
        long t = System.nanoTime();
        RuinPlan flash = flashOf(chunk);
        boolean marked = flash == null && FlashScorch.marked(chunk, d.id());
        if (!current(chunk, flash, marked)) return false;
        short[] motion = new short[256];
        for (int column = 0; column < 256; column++) motion[column] = (short) (chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, column & 15, column >> 4) + 1);
        // биом — до блоков: LOD Distant Horizons после записи мест читает чанк уже с ним
        boolean recoloured = biomes != null && ScorchedBiomes.write(level, chunk, biomes);
        if (cells.length > 0 || fires.length > 0 || outside != null || fluidTicks.length > 0 || flash != null || marked) {
            // блоки вне мест плана — до записи мест: после неё чанк уже другой. Со слоем вспышки чанк не тот, что в
            // снимке плана (снят до вспышки), и столбцы плана — заново по чанку
            write(level, chunk, budget, t, flash == null && !marked && cells.length > 0 && sameBlocks(chunk), flash, marked, false);
        } else if (recoloured) {
            DhUpdates.mark(level, chunk.getPos(), level.getGameTime());
        }
        if (ctx != null) ctx.applied(chunk, this, motion);
        FlashScorch.unmark(chunk, d.id());
        // гарь вспышки — с руинами (слой вспышки её уже поставил: заменяется на гарь уцелевшего); следы прежних вспышек
        // на сменившихся блоках уходят
        if (ctx != null) ua.zentix.incoming.nuclear.flash.FlashSync.chars(level, chunk, d, flash());
        return true;
    }

    /**
     * Поставить слой вспышки ({@link RuinPlanner#flash}) в чанк — в миг вспышки, до волны ({@link FlashScorch}; поток
     * сервера, чанк загружен целиком, у аппаратов Sable — и соседи): места и пожары прямо в секции, как руины, игрокам —
     * пакетами секций в том же тике, LOD Distant Horizons, гарь на гранях — слоем подрыва {@code d}. Тиков огню слой не
     * ставит: до руин чанка огонь не растёт, не перекидывается и не гаснет, и места плана руин по миру до вспышки
     * остаются теми же, когда бы руины ни встали; тик тому, что горит дальше, даёт {@link #apply}. В счёт пожаров
     * подрыва — только те, что волна не собьёт ({@link #blown}): сбитые гаснут до первого тика. Слой отмечается в руинах
     * подрыва ({@link RuinContext#flashed}): по нему план руин сверяет чанк, а снимки чанка для руин — мир до вспышки; если
     * он зажёг огонь — и на самом чанке ({@link FlashScorch#mark}): руины, построенные без плана слоя (после
     * перезапуска), знают, что огонь в чанке — слоя.
     *
     * @return сколько зажжено пожаров (и сбиваемых волной); −1 — слой устарел (места сменились после снимка): ничего
     *         не изменено
     */
    int applyFlash(ServerLevel level, LevelChunk chunk, Detonation d, ColumnScar.Budget budget) {
        if (this == EMPTY || ctx == null) return 0;
        long t = System.nanoTime();
        if (!current(chunk, null, false)) return -1;
        boolean shotCurrent = ctx.beforeFlash(level, chunk);
        boolean recoloured = biomes != null && ScorchedBiomes.write(level, chunk, biomes);
        int lit = 0;
        if (cells.length > 0 || fires.length > 0) {
            lit = write(level, chunk, budget, t, cells.length > 0 && sameBlocks(chunk), null, false, true);
        } else if (recoloured) {
            DhUpdates.mark(level, chunk.getPos(), level.getGameTime());
        }
        ctx.flashed(chunk, this, shotCurrent);
        if (lit > 0) FlashScorch.mark(chunk, d.id());
        ua.zentix.incoming.nuclear.flash.FlashSync.chars(level, chunk, d, flash());
        return lit;
    }

    /**
     * @param untouched блоки чанка те же, что в снимке плана ({@link #sameBlocks})
     * @param flash     слой вспышки, что стоит в чанке (план руин поверх него), иначе null
     * @param marked    слой вспышки стоит, а его плана в памяти нет ({@link FlashScorch#marked}): огонь чанка — его огонь
     * @param layer     пишется сам слой вспышки: огню без тиков, в счёт пожаров — только несбиваемые
     * @return сколько пожаров зажжено
     */
    private int write(ServerLevel level, LevelChunk chunk, ColumnScar.Budget budget, long t, boolean untouched, @Nullable RuinPlan flash,
                      boolean marked, boolean layer) {
        PHASES[0] += System.nanoTime() - t;
        t = System.nanoTime();
        ChunkPos pos = chunk.getPos();
        int x0 = pos.getMinBlockX(), z0 = pos.getMinBlockZ(), minY = chunk.getMinBuildHeight();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        // места плана — прямо в секциях чанка, как setBlock (он тоже меняет секцию на месте, пока поток света читает
        // чанк), но без его работы на каждый блок; блок-сущности и POI — через мир после подмены (их onRemove:
        // содержимое, половинки сундука, конвейеры Create)
        LevelChunkSection[] now = chunk.getSections();
        // изменённые секции и какие из них были пустыми (по биту на секцию: секций бывает и больше 64)
        long[] touched = new long[(now.length + 63) >> 6], wasEmpty = new long[touched.length];
        // руины поверх слоя вспышки: огонь слоя — среди мест плана и пожаров руин или гаснет
        boolean over = flash != null || marked;
        boolean ignites = budget.ignites && IncomingConfig.SERVER.nukeFires.get();
        int maxFires = IncomingConfig.SERVER.nukeMaxFires.get();
        // Sable — смена с того, что стояло на месте перед записью (поверх слоя — не мир до вспышки из плана)
        boolean sable = SableTerrain.present() && SableTerrain.watched(level, chunk);
        BlockState[] was = sable ? new BlockState[cells.length] : null;
        // где погас огонь слоя (завал, пролом, сбит волной): проверка света — огонь светит
        LongArrayList quenched = null;
        // руины поверх слоя без его плана не знают, какой огонь чанка в счёте пожаров: весь, что взял счётчик, уходит, а
        // входит тот, что горит дальше (счётчик после перезапуска — новый: он слоя чанка не брал). С планом слоя — по месту
        int layerCounted = budget.layer.remove(pos.toLong());
        if (marked) budget.fires = Math.max(0, budget.fires - layerCounted);
        for (int k = 0; k < cells.length; k++) {
            int c = cells[k];
            if ((c & SLOW) != 0) continue;
            int i = section(c);
            LevelChunkSection s = now[i];
            BlockState state = states[(c >>> STATE_SHIFT) & STATE_MASK];
            BlockState live = s.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15);
            if (over && fire(live)) {
                // огонь слоя там, где по плану воздух с огнём руин (сгоревшая трава), горит дальше — его ведут пожары ниже
                if (state.isAir() && ignites && hasFire(c & KEY_MASK)) continue;
                if (quenched == null) quenched = new LongArrayList();
                quenched.add(at(m, c, x0, z0, minY).asLong());
                if (flash != null && flash.counted(c & KEY_MASK)) budget.fires = Math.max(0, budget.fires - 1);
            }
            touch(touched, wasEmpty, now, i);
            s.setBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15, state, false);
            if (was != null) was[k] = live;
        }
        // пожары — там, где по плану воздух, пока не кончился счётчик пожаров подрыва; огонь слоя на месте пожара руин
        // горит дальше (вид — как у руин: грани к горючим соседям после волны)
        int lit = 0, n = 0;
        int[] burning = fires.length == 0 || !ignites ? NONE : new int[fires.length];
        BlockState[] fireWas = burning.length == 0 ? NO_STATES : new BlockState[burning.length];
        for (int k = 0; k < burning.length; k++) {
            int c = fires[k];
            int i = section(c);
            LevelChunkSection s = now[i];
            BlockState fire = states[(c >>> STATE_SHIFT) & STATE_MASK];
            BlockState live = s.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15);
            if (!live.isAir()) {
                if (!over || !fire(live)) continue;
                // огонь слоя, которого нет в счёте пожаров подрыва (по слою его собьёт волна; или слой без плана), а руины
                // его оставили: теперь — в счёте
                if (flash == null || !flash.counted(c & KEY_MASK)) budget.fires++;
                if (live != fire) {
                    touch(touched, wasEmpty, now, i);
                    s.setBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15, fire, false);
                }
            } else {
                if (budget.fires >= maxFires) continue;
                touch(touched, wasEmpty, now, i);
                s.setBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15, fire, false);
                lit++;
                if (!layer || !isBlown(c & KEY_MASK)) {
                    budget.fires++;
                    if (layer) layerCounted++;
                }
            }
            fireWas[n] = live;
            burning[n++] = c;
        }
        if (layer && layerCounted > 0) budget.layer.put(pos.toLong(), layerCounted);
        // огонь слоя, которого нет ни среди мест плана, ни среди пожаров руин (сбит волной, стоял на снесённом), гаснет
        LongArrayList out = null;
        List<BlockState> outWas = null;
        if (over) {
            Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
            for (int f : flash != null ? flash.fires : fireCells(now)) {
                int key = f & KEY_MASK, i = section(f);
                if (i >= now.length) continue;
                LevelChunkSection s = now[i];
                BlockState live = s.getBlockState(f & 15, (f >> 8) & 15, (f >> 4) & 15);
                if (!fire(live) || find(key) >= 0 || ignites && hasFire(key)) continue;
                touch(touched, wasEmpty, now, i);
                s.setBlockState(f & 15, (f >> 8) & 15, (f >> 4) & 15, air, false);
                at(m, f, x0, z0, minY);
                // огонь не держит движение и не закрывает небо: из карт высот он меняет только поверхность мира
                surface.update(f & 15, m.getY(), (f >> 4) & 15, air);
                if (quenched == null) quenched = new LongArrayList();
                quenched.add(m.asLong());
                if (out == null) {
                    out = new LongArrayList();
                    outWas = new java.util.ArrayList<>();
                }
                out.add(m.asLong());
                outWas.add(live);
                if (flash != null && flash.counted(key)) budget.fires = Math.max(0, budget.fires - 1);
            }
        }
        // Sable держит копию блоков мира у аппаратов и видит только LevelChunk.setBlockState: у аппаратов — сообщить
        if (sable) {
            for (int k = 0; k < cells.length; k++) {
                if (was[k] == null) continue;
                int c = cells[k];
                at(m, c, x0, z0, minY);
                SableTerrain.changed(level, chunk, m.getX(), m.getY(), m.getZ(), was[k], states[(c >>> STATE_SHIFT) & STATE_MASK]);
            }
            for (int k = 0; k < n; k++) {
                at(m, burning[k], x0, z0, minY);
                SableTerrain.changed(level, chunk, m.getX(), m.getY(), m.getZ(), fireWas[k], states[(burning[k] >>> STATE_SHIFT) & STATE_MASK]);
            }
            for (int k = 0; out != null && k < out.size(); k++) {
                m.set(out.getLong(k));
                SableTerrain.changed(level, chunk, m.getX(), m.getY(), m.getZ(), outWas.get(k), air);
            }
        }
        LevelLightEngine light = level.getChunkSource().getLightEngine();
        for (int i = 0; i < now.length; i++) {
            if (!bit(touched, i)) continue;
            boolean empty = now[i].hasOnlyAir();
            if (bit(wasEmpty, i) != empty) light.updateSectionStatus(SectionPos.of(pos, chunk.getSectionYFromSectionIndex(i)), empty);
        }
        PHASES[1] += System.nanoTime() - t;
        t = System.nanoTime();
        // карты высот и источники неба — из плана; чанк меняли после плана вне мест плана (блок внутри дома) или
        // значение «до» в столбце не то — все столбцы плана заново по чанку
        if (!untouched || !setHeights(chunk)) rescanColumns(chunk);
        if (lit > 0) {
            // огонь не воздух, но не держит движение и не закрывает небо: из карт высот он меняет только поверхность мира
            Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
            for (int k = 0; k < n; k++) {
                if (!fireWas[k].isAir()) continue;
                int c = burning[k];
                surface.update(c & 15, minY + (section(c) << 4) + ((c >> 8) & 15), (c >> 4) & 15, states[(c >>> STATE_SHIFT) & STATE_MASK]);
            }
        }
        PHASES[2] += System.nanoTime() - t;
        t = System.nanoTime();
        // кто видит чанк, получает изменения в этом же тике — пакетами секций (как setBlock), а не очередью чанков
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos.toLong());
        List<ServerPlayer> players = holder == null ? List.of() : level.getChunkSource().chunkMap.getPlayers(pos, false);
        boolean watched = !players.isEmpty();
        boolean sections = watched && holder.getTickingChunk() != null;
        if (sections) {
            for (int c : cells) if ((c & SLOW) == 0) holder.blockChanged(at(m, c, x0, z0, minY));
            for (int k = 0; out != null && k < out.size(); k++) holder.blockChanged(m.set(out.getLong(k)));
        }
        int extra = quenched == null ? 0 : quenched.size();
        long[] checks = java.util.Arrays.copyOf(lightAt, lightAt.length + lit + extra);
        int checked = lightAt.length;
        for (int k = 0; k < extra; k++) checks[checked++] = quenched.getLong(k);
        for (int k = 0; k < n; k++) {
            int c = burning[k];
            BlockState fire = states[(c >>> STATE_SHIFT) & STATE_MASK];
            at(m, c, x0, z0, minY);
            if (fireWas[k].isAir()) checks[checked++] = m.asLong();
            if (sections && fireWas[k] != fire) holder.blockChanged(m);
            // тик огня, как у поставленного огня (FireBlock.onPlace): 30–39 тиков; у слоя вспышки — с руинами
            if (!layer) level.scheduleTick(m.immutable(), fire.getBlock(), 30 + (int) (RuinPlanner.hash(m.getX(), m.getY(), m.getZ(), 31) * 10));
        }
        checkLight(light, pos, checked == checks.length ? checks : java.util.Arrays.copyOf(checks, checked));
        // жидкость у пролома (и у соседей): тик, как дал бы ей setBlock соседа, — вразнобой по хешу места и не больше
        // FLUID_TICKS за секунду: подмена не будит соседей, без тика вода стояла бы стеной
        for (int k = 0; k < fluidTicks.length; k++) {
            m.set(fluidTicks[k]);
            // сосед не загружен (готовому плану соседи не нужны): тика нет — загрузка чанка его не ставит, и вода там
            // стоит, пока её не тронет обновление блока рядом
            if (!Terrain.ready(level, m)) continue;
            BlockState st = level.getBlockState(m);
            if (st.getFluidState().isEmpty()) continue;
            level.scheduleTick(m.immutable(), st.getFluidState().getType(), fluidDelay(k, m.getX(), m.getY(), m.getZ()));
        }
        PHASES[3] += System.nanoTime() - t;
        t = System.nanoTime();
        chunk.setUnsaved(true);
        // самый долгий блок чанка — в строку лога: onRemove бывает дорогим (сеть валов Create обходится целиком,
        // конвейер снимает всю цепочку)
        BlockState slowBlock = null;
        long slowBlockNanos = 0;
        for (int c : cells) {
            if ((c & SLOW) == 0) continue;
            at(m, c, x0, z0, minY);
            BlockState old = chunk.getBlockState(m);
            long b = System.nanoTime();
            ColumnScar.replace(level, m, old, states[(c >>> STATE_SHIFT) & STATE_MASK]);
            b = System.nanoTime() - b;
            if (b > slowBlockNanos) {
                slowBlockNanos = b;
                slowBlock = old;
            }
        }
        // видит, но чанк не тикает (край прорисовки): чанк целиком ванильной отправкой
        if (watched && !sections) {
            for (ServerPlayer p : players) p.connection.chunkSender.markChunkPendingToSend(chunk);
        }
        // LOD Distant Horizons: руины вместе с волной, а не при следующем сохранении чанка
        long dh = System.nanoTime();
        DhUpdates.mark(level, pos, level.getGameTime());
        long took = System.nanoTime() - t;
        dh = System.nanoTime() - dh;
        PHASES[4] += took;
        if (took > slowestWorldNanos) {
            slowestWorldNanos = took;
            slowestWorldChunk = pos.toLong();
            int slow = 0;
            for (int c : cells) if ((c & SLOW) != 0) slow++;
            slowestWorldCells = slow;
            slowestWorldBlock = slowBlock;
            slowestWorldBlockNanos = slowBlockNanos;
            slowestWorldDhNanos = dh;
        }
        return lit;
    }

    /** Секция {@code i} меняется: отметить её и была ли она пустой (до записи). */
    private static void touch(long[] touched, long[] wasEmpty, LevelChunkSection[] now, int i) {
        if (bit(touched, i)) return;
        set(touched, i);
        if (now[i].hasOnlyAir()) set(wasEmpty, i);
    }

    /** Пожар слоя вспышки на месте {@code key} в счёте пожаров подрыва: слой его зажёг, и волна его не сбивает. */
    private boolean counted(int key) {
        return hasFire(key) && !isBlown(key);
    }

    /** Места огня в секциях чанка (как в {@link #fires}, без состояния): огонь слоя, чей план забыт. Поток сервера. */
    private static int[] fireCells(LevelChunkSection[] sections) {
        it.unimi.dsi.fastutil.ints.IntArrayList out = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s.hasOnlyAir() || !ua.zentix.incoming.util.Palettes.contains(s.getStates(), RuinPlan::fire)) continue;
            for (int k = 0; k < 4096; k++) if (fire(s.getBlockState(k & 15, k >> 8, (k >> 4) & 15))) out.add(i << SECTION_SHIFT | k);
        }
        return out.toIntArray();
    }

    /**
     * Проверки света чанка — одной задачей движка света (как {@code LevelLightEngine.checkBlock} в его потоке), а не
     * задачей с почтой на каждое место: сотни мест на чанк, тысячи чанков за пару секунд. Движок света не ванильный
     * (другой мод) — по одному через его {@code checkBlock}.
     */
    private static void checkLight(LevelLightEngine light, ChunkPos pos, long[] at) {
        if (at.length == 0) return;
        // движок блоков есть всегда; нет — свет считает другой мод (Starlight, ScalableLux): задача была бы пустой
        if (light instanceof ThreadedLevelLightEngine threaded && light.blockEngine != null && vanilla(light.blockEngine, BlockLightEngine.class)
                && vanilla(light.skyEngine, SkyLightEngine.class)) {
            var block = light.blockEngine;
            var sky = light.skyEngine;
            threaded.addTask(pos.x, pos.z, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, () -> {
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                for (long a : at) {
                    p.set(a);
                    block.checkBlock(p);
                    if (sky != null) sky.checkBlock(p);
                }
            });
            return;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (long a : at) light.checkBlock(p.set(a));
    }

    /**
     * Карты высот и источники неба столбцов из плана. Значение до руин в чанке уже не то (столбец меняли после плана) —
     * false, ничего не изменено: вызывающий пересчитает столбцы плана по чанку.
     */
    private boolean setHeights(LevelChunk chunk) {
        ChunkSkyLightSources sky = chunk.getSkyLightSources();
        for (int k = 0; k < heights.length; k += 3) {
            int key = heights[k], column = key >> 3, type = key & 7;
            int was = type == SKY ? sky.get(column) : chunk.getOrCreateHeightmapUnprimed(HEIGHTMAP_TYPES[type]).getFirstAvailable(column & 15, column >> 4);
            if (was != heights[k + 1]) return false;
        }
        for (int k = 0; k < heights.length; k += 3) {
            int key = heights[k], column = key >> 3, type = key & 7;
            if (type == SKY) sky.set(column, heights[k + 2]);
            else chunk.getOrCreateHeightmapUnprimed(HEIGHTMAP_TYPES[type]).setHeight(column & 15, column >> 4, heights[k + 2]);
        }
        return true;
    }

    /**
     * Карты высот и нижние источники неба всех столбцов с местами плана — по чанку после записи мест плана, как
     * {@code Heightmap.primeHeightmaps} и {@code ChunkSkyLightSources.fillFrom}, но одним проходом по столбцу для всех
     * карт и неба и только от верхнего места плана вниз: выше него подмена ничего не меняла, и значение, которое там
     * держит мир, верно. Чанк меняли после плана (крышу над выпотрошенным домом сломали — высота встала на этаж,
     * который план сносит), и значения из плана могли устареть. Столбцы без мест плана подмена не трогает — их вёл
     * сам мир; блок-сущности и POI ставятся через мир после этого.
     */
    private void rescanColumns(LevelChunk chunk) {
        int minY = chunk.getMinBuildHeight();
        int[] top = new int[256];
        java.util.Arrays.fill(top, Integer.MIN_VALUE);
        for (int c : cells) {
            if ((c & SLOW) != 0) continue;
            int y = minY + (section(c) << 4) + ((c >> 8) & 15);
            if (y > top[c & 0xFF]) top[c & 0xFF] = y;
        }
        ChunkSkyLightSources sky = chunk.getSkyLightSources();
        Heightmap[] maps = new Heightmap[HEIGHTMAP_TYPES.length];
        for (int i = 0; i < maps.length; i++) maps[i] = chunk.getOrCreateHeightmapUnprimed(HEIGHTMAP_TYPES[i]);
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos upper = new BlockPos.MutableBlockPos(), lower = new BlockPos.MutableBlockPos();
        for (int column = 0; column < 256; column++) {
            int t = top[column];
            if (t == Integer.MIN_VALUE) continue;
            int lx = column & 15, lz = column >> 4, x = x0 + lx, z = z0 + lz;
            // что искать: значение не выше верхнего места плана (выше — блок над планом, он не менялся)
            int open = 0;
            for (int i = 0; i < maps.length; i++) if (maps[i].getFirstAvailable(lx, lz) <= t + 1) open |= 1 << i;
            boolean skyOpen = sky.get(column) <= t + 1;
            BlockState above = chunk.getBlockState(upper.set(x, t + 1, z));
            for (int y = t; y >= minY - 1 && (open != 0 || skyOpen); y--) {
                BlockState below = chunk.getBlockState(lower.set(x, y, z));
                for (int i = 0; open != 0 && i < maps.length; i++) {
                    if ((open & 1 << i) != 0 && y >= minY && HEIGHTMAP_TYPES[i].isOpaque().test(below)) {
                        maps[i].setHeight(lx, lz, y + 1);
                        open &= ~(1 << i);
                    }
                }
                if (skyOpen) {
                    upper.set(x, y + 1, z);
                    if (below.getLightBlock(chunk, lower) != 0 || Shapes.faceShapeOccludes(LightEngine.getOcclusionShape(chunk, upper, above, Direction.DOWN),
                            LightEngine.getOcclusionShape(chunk, lower, below, Direction.UP))) {
                        sky.set(column, y + 1);
                        skyOpen = false;
                    }
                }
                above = below;
            }
            for (int i = 0; i < maps.length; i++) if ((open & 1 << i) != 0) maps[i].setHeight(lx, lz, minY);
            if (skyOpen) sky.set(column, minY - 1);
        }
    }

    private static boolean vanilla(@Nullable Object engine, Class<?> type) {
        return engine == null || engine.getClass() == type;
    }

    /** Стволы, упавшие в соседние чанки: сколько. */
    public int outsideCount() {
        return outside == null ? 0 : outside.size();
    }

    public long outsidePos(int k) {
        return outside.getLong(k);
    }

    public BlockState outsideState(int k) {
        return outsideState.get(k);
    }

    /** Бревно поваленного ствола — на поверхность соседнего чанка (после его руин), если там не стоит постройка. */
    public static void placeLog(ServerLevel level, long at, BlockState log) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos().set(at);
        if (!Terrain.ready(level, m) || !NuclearTickets.aroundLoaded(level, m)) return;
        m.setY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, m.getX(), m.getZ()));
        BlockState was = level.getBlockState(m);
        if (was.canBeReplaced()) ColumnScar.replace(level, m, was, log);
    }

    private static final ThreadLocal<FriendlyByteBuf> COPY_BUFFER = ThreadLocal.withInitial(() -> new FriendlyByteBuf(Unpooled.buffer(16384)));

    /**
     * Независимая копия секции. Не {@code PalettedContainer.copy()}: у её палитры остаётся обработчик роста палитры
     * старого контейнера (а одноцветная палитра — вовсе тот же объект), и новое состояние в копии перестраивает данные
     * старой секции мира, а в копию пишет чужой номер («The value 1 is not in the specified inclusive range of 0 to 0»).
     * Запись и чтение через буфер собирают палитру заново, со своим обработчиком.
     */
    static LevelChunkSection copyOf(LevelChunkSection section) {
        return new LevelChunkSection(copyStates(section.getStates()), section.getBiomes());
    }

    /** Независимая копия блоков секции, которую можно править ({@link #copyOf}). */
    static PalettedContainer<BlockState> copyStates(PalettedContainer<BlockState> source) {
        FriendlyByteBuf buf = COPY_BUFFER.get();
        buf.clear();
        source.write(buf);
        PalettedContainer<BlockState> states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        states.read(buf);
        return states;
    }

    private static boolean bit(long[] bits, int i) {
        return (bits[i >> 6] & 1L << (i & 63)) != 0;
    }

    private static void set(long[] bits, int i) {
        bits[i >> 6] |= 1L << (i & 63);
    }

    private static BlockPos.MutableBlockPos at(BlockPos.MutableBlockPos m, int c, int x0, int z0, int minY) {
        return m.set(x0 + (c & 15), minY + (section(c) << 4) + ((c >> 8) & 15), z0 + ((c >> 4) & 15));
    }
}
