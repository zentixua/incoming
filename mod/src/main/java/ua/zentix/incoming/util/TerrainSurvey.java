package ua.zentix.incoming.util;

import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Съёмка рельефа мира без загрузки чанков — источники {@link Terrain.Source#DISK} и {@link Terrain.Source#GENERATOR}
 * для набора {@link Terrain.Allowed#RELIEF}: полёт видит рельеф на всём пути, а не только на готовых чанках.
 * <ul>
 *   <li>Чанк в памяти, но не готов (ждёт выгрузки, опущен у края видимости, {@link Terrain#inMemory}) — его карта высот
 *   сразу: это те же данные, что уйдут на диск.</li>
 *   <li>Чанк на диске — поле {@code Heightmaps} его файла: читает поток ввода-вывода чанков ({@code chunkScanner}, тот
 *   же, что у заголовков {@link DiskStatus#scan}), не разбирая и не загружая чанк. Он же отдаёт ещё не записанное
 *   ({@code IOWorker.pendingWrites}), а запись выгруженного чанка встаёт в его очередь раньше любого чтения после неё.
 *   Годится только чанк с окончательными блоками ({@link DiskStatus.State#blocksFinal}) в формате 1.18 и новее.</li>
 *   <li>Чанка на диске нет или он недогенерирован — рельеф генератора ({@code ChunkGenerator.getBaseHeight}, без
 *   деревьев и построек, не ниже уровня моря), в фоновом потоке Minecraft: узлы сетки через {@link #GENERATOR_STEP}
 *   блоков, колонка — наибольший из четырёх узлов своей клетки. Это оценка сверху для гладкого склона и снизу для
 *   леса (крон генератор не знает): приближение, не карта.</li>
 * </ul>
 * Тик не ждёт ни диска, ни генератора: первый вопрос о чанке заказывает съёмку и получает null («ещё не знаю»), ответ
 * приходит в следующих тиках. Чанк, который пришёл в память или ушёл из неё ({@code ChunkEvent.Load}/{@code Unload}),
 * забывается: его карта высот могла измениться, а заказ, который читал старый файл, отбрасывается. Своё у каждого мира,
 * не сохраняется ({@link ModAttachments#TERRAIN_SURVEY}).
 */
public final class TerrainSurvey {
    /** Шаг узлов рельефа генератора, блоков: ~3 мс на узел, в среднем ~4 новых узла на чанк. */
    public static final int GENERATOR_STEP = 8;
    /** Сколько чтений с диска может ждать мир разом: дальше заказ повторится при следующем вопросе. */
    static final int MAX_PENDING = 512;
    /** Сколько чанков генератора считается разом (фоновые потоки Minecraft общие с генерацией мира). */
    static final int GENERATOR_WORKERS = 2;
    /** Чанк, о котором не спрашивали столько тиков, забывается. */
    static final int FORGET_TICKS = 6000;
    /** Узлов генератора больше этого — память узлов сбрасывается (≈1,5 МБ). */
    static final int MAX_POINTS = 65_536;

    /** Известные карты высот: вид карты → чанк → колонки. */
    private final Map<Heightmap.Types, Map<Long, Heights>> known = new EnumMap<>(Heightmap.Types.class);
    /** Заказанные: вид карты → чанк → метка заказа (заказ, чья метка снята, отбрасывается). */
    private final Map<Heightmap.Types, Map<Long, Object>> pending = new EnumMap<>(Heightmap.Types.class);
    /** Чтения с диска в пути (для {@link #settle}). */
    private final Queue<CompletableFuture<?>> reads = new ConcurrentLinkedQueue<>();
    /** Чанки без карты на диске — ждут генератора (кладёт поток ввода-вывода, берёт тик мира). */
    private final Queue<Job> toGenerate = new ConcurrentLinkedQueue<>();
    /** Счёт генератора в пути (для {@link #settle}). */
    private final Queue<CompletableFuture<?>> generating = new ConcurrentLinkedQueue<>();
    private final AtomicInteger workers = new AtomicInteger();
    /**
     * Последний известный чанк, о котором спросили (поток сервера): полоса датчика идёт по колонкам подряд, и почти все
     * вопросы — о том же чанке; без этого каждый вопрос — поиск в общей карте с упаковкой ключа.
     */
    private long lastChunk = Long.MIN_VALUE;
    @Nullable
    private Heightmap.Types lastType;
    @Nullable
    private Heights last;
    /** Узлы рельефа генератора: (x / шаг, z / шаг) → первый воздух. */
    private final Map<Long, Integer> points = new ConcurrentHashMap<>();

    /** Для {@link ModAttachments#TERRAIN_SURVEY}. */
    public TerrainSurvey() {
        for (Heightmap.Types t : Heightmap.Types.values()) {
            known.put(t, new ConcurrentHashMap<>());
            pending.put(t, new ConcurrentHashMap<>());
        }
    }

    public static TerrainSurvey get(ServerLevel level) {
        return level.getData(ModAttachments.TERRAIN_SURVEY);
    }

    /** Первый воздух в колонках чанка и откуда он; {@code used} — тик последнего вопроса. */
    private static final class Heights {
        final int[] y;
        final Terrain.Source source;
        volatile long used;

        Heights(int[] y, Terrain.Source source, long used) {
            this.y = y;
            this.source = source;
            this.used = used;
        }
    }

    /** Чанк, который считает генератор, с меткой своего заказа. */
    private record Job(Heightmap.Types type, long chunk, Object token) {}

    /**
     * Поверхность колонки (первый воздух над картой высот {@code type}) по съёмке; null — ещё не известна (съёмка
     * заказана). Готовый чанк {@link Terrain#estimate} читает сам, до съёмки. Только поток сервера; чанк не грузится.
     */
    @Nullable
    public Terrain.Surface at(ServerLevel level, Heightmap.Types type, int x, int z) {
        long chunk = ChunkPos.asLong(x >> 4, z >> 4);
        LevelChunk memory = Terrain.inMemory(level, chunk);
        if (memory != null) return new Terrain.Surface(memory.getHeight(type, x & 15, z & 15) + 1, Terrain.Source.DISK);
        Heights h = chunk == lastChunk && type == lastType ? last : known.get(type).get(chunk);
        if (h != null) {
            lastChunk = chunk;
            lastType = type;
            last = h;
            h.used = level.getGameTime();
            return new Terrain.Surface(h.y[(x & 15) + (z & 15) * 16], h.source);
        }
        request(level, type, chunk);
        return null;
    }

    /**
     * Заказать заранее чанки вдоль отрезка {@code from → to} по горизонтали (и по одному соседнему с каждой стороны:
     * полоса над путём шире линии): к тому, как снаряд дойдёт, ответ уже будет. Только поток сервера.
     */
    public void ahead(ServerLevel level, Heightmap.Types type, Vec3 from, Vec3 to) {
        if (level.dimensionType().hasCeiling()) return;
        GridWalk.walk(from.x, from.z, to.x, to.z, 16, (cx, cz) -> {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    long c = ChunkPos.asLong(cx + dx, cz + dz);
                    if (!Terrain.ready(level, cx + dx, cz + dz) && Terrain.inMemory(level, c) == null && !known.get(type).containsKey(c))
                        request(level, type, c);
                }
            return true;
        });
    }

    private void request(ServerLevel level, Heightmap.Types type, long chunk) {
        Map<Long, Object> waiting = pending.get(type);
        if (waiting.containsKey(chunk) || waiting.size() >= MAX_PENDING) return;
        Object token = new Object();
        waiting.put(chunk, token);
        ChunkPos pos = new ChunkPos(chunk);
        CollectFields fields = DiskStatus.fields(new FieldSelector(HEIGHTMAPS, LongArrayTag.TYPE, type.getSerializationKey()));
        int minY = level.getMinBuildHeight(), height = level.getHeight();
        long now = level.getGameTime();
        reads.add(level.getChunkSource().chunkMap.chunkScanner().scanChunk(pos, fields).handle((v, e) -> {
            int[] y = e == null && fields.getResult() instanceof CompoundTag tag ? decode(tag, type, minY, height) : null;
            if (y != null) finish(type, chunk, token, y, Terrain.Source.DISK, now);
            else if (waiting.get(chunk) == token) toGenerate.add(new Job(type, chunk, token));
            return null;
        }));
    }

    /** Поле карт высот в файле чанка ({@code ChunkSerializer.HEIGHTMAPS_TAG}). */
    private static final String HEIGHTMAPS = "Heightmaps";

    /** Ответ заказа: в известные, если метка заказа ещё стоит (чанк за это время не приходил в память). */
    private void finish(Heightmap.Types type, long chunk, Object token, int[] y, Terrain.Source source, long now) {
        if (pending.get(type).remove(chunk, token)) known.get(type).put(chunk, new Heights(y, source, now));
    }

    /**
     * Первый воздух в 256 колонках по полям файла чанка ({@link DiskStatus#fields} и карта высот {@code type}); null —
     * карты нет или ей нельзя верить: блоки не окончательные, формат до 1.18 (высоты в нём от другого низа мира), длина
     * не по высоте мира. Упаковка — как у {@code Heightmap}: {@code ceil(log2(высота мира + 1))} бит на колонку, значение —
     * первый воздух минус низ мира, колонка {@code x + 16 z}.
     */
    @Nullable
    static int[] decode(CompoundTag tag, Heightmap.Types type, int minY, int worldHeight) {
        if (!DiskStatus.of(tag).blocksFinal() || tag.contains("Level", Tag.TAG_COMPOUND)) return null;
        CompoundTag maps = tag.getCompound(HEIGHTMAPS);
        if (!maps.contains(type.getSerializationKey(), Tag.TAG_LONG_ARRAY)) return null;
        SimpleBitStorage storage;
        try {
            storage = new SimpleBitStorage(Mth.ceillog2(worldHeight + 1), 256, maps.getLongArray(type.getSerializationKey()));
        } catch (SimpleBitStorage.InitializationException e) {
            return null;
        }
        int[] y = new int[256];
        for (int i = 0; i < 256; i++) y[i] = storage.get(i) + minY;
        return y;
    }

    /** Тик мира: генератор для чанков без карты на диске (не больше {@link #GENERATOR_WORKERS} разом), забывание старого. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.hasData(ModAttachments.TERRAIN_SURVEY)) return;
        TerrainSurvey s = get(level);
        s.reads.removeIf(CompletableFuture::isDone);
        s.generating.removeIf(CompletableFuture::isDone);
        long now = level.getGameTime();
        while (s.workers.get() < GENERATOR_WORKERS) {
            Job job = s.toGenerate.poll();
            if (job == null) break;
            s.workers.incrementAndGet();
            s.generating.add(CompletableFuture.supplyAsync(() -> s.generate(level, job.chunk), Util.backgroundExecutor()).handle((y, e) -> {
                s.workers.decrementAndGet();
                if (y != null) {
                    s.finish(job.type, job.chunk, job.token, y, Terrain.Source.GENERATOR, now);
                } else {
                    s.pending.get(job.type).remove(job.chunk, job.token);
                    Incoming.LOG.warn("Terrain survey: generator relief for chunk {} failed", new ChunkPos(job.chunk), e);
                }
                return null;
            }));
        }
        if (now % 1200 == 0) {
            for (Map<Long, Heights> m : s.known.values()) m.values().removeIf(h -> now - h.used > FORGET_TICKS);
            s.last = null;
            if (s.points.size() > MAX_POINTS) s.points.clear();
        }
    }

    /**
     * Рельеф генератора в колонках чанка: узлы сетки {@link #GENERATOR_STEP} в углах и серединах сторон (общие с
     * соседними чанками — по памяти узлов), колонка — наибольший узел своей клетки. Фоновый поток.
     */
    int[] generate(ServerLevel level, long chunk) {
        int x0 = ChunkPos.getX(chunk) << 4, z0 = ChunkPos.getZ(chunk) << 4;
        int n = 16 / GENERATOR_STEP + 1;
        int[] node = new int[n * n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) node[i + j * n] = point(level, x0 + i * GENERATOR_STEP, z0 + j * GENERATOR_STEP);
        int[] y = new int[256];
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                int i = x / GENERATOR_STEP, j = z / GENERATOR_STEP;
                y[x + z * 16] = Math.max(Math.max(node[i + j * n], node[i + 1 + j * n]), Math.max(node[i + (j + 1) * n], node[i + 1 + (j + 1) * n]));
            }
        return y;
    }

    /** Узел рельефа генератора — первый воздух не ниже уровня моря, как у {@link Terrain.Source#GENERATOR}. */
    private int point(ServerLevel level, int x, int z) {
        long key = ChunkPos.asLong(Math.floorDiv(x, GENERATOR_STEP), Math.floorDiv(z, GENERATOR_STEP));
        Integer y = points.get(key);
        if (y != null) return y;
        ChunkGenerator g = level.getChunkSource().getGenerator();
        int v = Math.max(g.getSeaLevel(), g.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState()));
        points.put(key, v);
        return v;
    }

    /** Чанк пришёл в память или ушёл из неё: его карта высот и заказы — заново. */
    public static void onChunkLoad(ChunkEvent.Load event) {
        forget(event);
    }

    public static void onChunkUnload(ChunkEvent.Unload event) {
        forget(event);
    }

    private static void forget(ChunkEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.hasData(ModAttachments.TERRAIN_SURVEY)) return;
        TerrainSurvey s = get(level);
        long chunk = event.getChunk().getPos().toLong();
        if (chunk == s.lastChunk) s.last = null;
        for (Map<Long, Heights> m : s.known.values()) m.remove(chunk);
        for (Map<Long, Object> m : s.pending.values()) m.remove(chunk);
    }

    /**
     * Дождаться всех заказов мира в этом потоке (сценарии полёта GameTest: ответ — в тике заказа, а не когда успеет
     * диск, иначе полёт расходился бы с эталоном). В игре не зовётся.
     */
    public static void settle(ServerLevel level) {
        if (!level.hasData(ModAttachments.TERRAIN_SURVEY)) return;
        TerrainSurvey s = get(level);
        while (true) {
            List<CompletableFuture<?>> wait = new ArrayList<>(s.reads);
            wait.addAll(s.generating);
            for (CompletableFuture<?> f : wait) f.join();
            s.reads.removeAll(wait);
            s.generating.removeAll(wait);
            Job job = s.toGenerate.poll();
            if (job == null && s.reads.isEmpty() && s.generating.isEmpty()) return;
            if (job != null) s.finish(job.type, job.chunk, job.token, s.generate(level, job.chunk), Terrain.Source.GENERATOR, level.getGameTime());
        }
    }

    /** Заказов в пути (GameTest). */
    public int pendingCount() {
        int n = 0;
        for (Map<Long, Object> m : pending.values()) n += m.size();
        return n;
    }
}
