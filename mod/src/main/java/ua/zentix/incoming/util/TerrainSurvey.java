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
import net.minecraft.world.level.chunk.ChunkAccess;
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
 * приходит в конце тика ({@link #publish}). Чанк в памяти отвечает сам; уходя из неё ({@code ChunkEvent.Unload}),
 * он оставляет съёмке свою карту высот — ту же, что уходит на диск: ответ о нём не прерывается ни на тик, когда бы
 * ваниль его ни выгрузила (выгрузка идёт по свободному времени тика), и старый ответ с диска её не заменит. Своё у
 * каждого мира, не сохраняется ({@link ModAttachments#TERRAIN_SURVEY}).
 */
public final class TerrainSurvey {
    /** Шаг узлов рельефа генератора, блоков: ~3 мс на узел, в среднем ~4 новых узла на чанк. */
    public static final int GENERATOR_STEP = 8;
    /**
     * Сколько чтений мир отдаёт потоку ввода-вывода чанков разом: та же очередь грузит чанки игрокам, и сотни чтений
     * задержали бы их. Остальные заказы ждут в своей очереди — не отбрасываются: иначе чей заказ принят, решали бы
     * соседние полёты и скорость диска.
     */
    public static final int MAX_READS = 64;
    /** Заказов в очереди больше этого не берётся (память; столько не набирает и залп над неисследованной землёй). */
    static final int MAX_QUEUED = 8192;
    /** Сколько чанков генератора считается разом (фоновые потоки Minecraft общие с генерацией мира); следующий — сразу по концу. */
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
    /** Чанки без карты на диске — ждут генератора (кладёт поток ввода-вывода, считает фоновый поток, {@link #pump}). */
    private final Queue<Order> toGenerate = new ConcurrentLinkedQueue<>();
    /** Счёт генератора в пути (для {@link #settle}). */
    private final Queue<CompletableFuture<?>> generating = new ConcurrentLinkedQueue<>();
    private final AtomicInteger workers = new AtomicInteger();
    private final AtomicInteger reading = new AtomicInteger();
    /** Заказы, ждущие чтения (поток сервера): вопросы датчика — первыми, заказ вперёд — следом. */
    private final java.util.ArrayDeque<Order> urgent = new java.util.ArrayDeque<>(), later = new java.util.ArrayDeque<>();

    /** Заказ чанка с меткой (ждёт чтения или генератора). */
    private record Order(Heightmap.Types type, long chunk, Object token) {}
    /** Время мира последнего тика (для ответов из фоновых потоков). */
    private volatile long now;
    /**
     * Последний известный чанк, о котором спросили (поток сервера): полоса датчика идёт по колонкам подряд, и почти все
     * вопросы — о том же чанке; без этого каждый вопрос — поиск в общей карте с упаковкой ключа.
     */
    private long lastChunk = Long.MIN_VALUE;
    @Nullable
    private Heightmap.Types lastType;
    @Nullable
    private Heights last;
    /** Какие карты спрашивали и когда последний раз (поток сервера): уходящий чанк оставляет только их, и только пока спрашивают. */
    private final java.util.Set<Heightmap.Types> asked = java.util.EnumSet.noneOf(Heightmap.Types.class);
    private long lastAsked = Long.MIN_VALUE;
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

    /**
     * Первый воздух в колонках чанка и откуда он; {@code used} — тик последнего вопроса. {@code y} null — чанк на диске
     * с окончательными блоками, но без годной карты высот (формат до 1.18, догенерация под нулём): рельеф неизвестен,
     * генератор тут не годится — мир построен не им или с тех пор изменён.
     */
    private static final class Heights {
        @Nullable
        final int[] y;
        final Terrain.Source source;
        volatile long used;

        Heights(@Nullable int[] y, Terrain.Source source, long used) {
            this.y = y;
            this.source = source;
            this.used = used;
        }
    }


    /** Ответ заказа из фонового потока: в известные — в конце тика мира ({@link #publish}). */
    private record Answer(Heightmap.Types type, long chunk, Object token, @Nullable int[] y, @Nullable Terrain.Source source) {}

    /** Ответы, пришедшие с прошлого конца тика. */
    private final Queue<Answer> answers = new ConcurrentLinkedQueue<>();

    /**
     * Поверхность колонки (первый воздух над картой высот {@code type}) по съёмке; null — ещё не известна (съёмка
     * заказана). Готовый чанк {@link Terrain#estimate} читает сам, до съёмки. Только поток сервера; чанк не грузится.
     */
    @Nullable
    public Terrain.Surface at(ServerLevel level, Heightmap.Types type, int x, int z) {
        long chunk = ChunkPos.asLong(x >> 4, z >> 4);
        lastAsked = level.getGameTime();
        asked.add(type);
        LevelChunk memory = Terrain.inMemory(level, chunk);
        if (memory != null) return new Terrain.Surface(memory.getHeight(type, x & 15, z & 15) + 1, Terrain.Source.DISK);
        Heights h = chunk == lastChunk && type == lastType ? last : known.get(type).get(chunk);
        if (h != null) {
            lastChunk = chunk;
            lastType = type;
            last = h;
            h.used = level.getGameTime();
            return h.y == null ? null : new Terrain.Surface(h.y[(x & 15) + (z & 15) * 16], h.source);
        }
        request(level, type, chunk, urgent);
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
                        request(level, type, c, later);
                }
            return true;
        });
    }

    private void request(ServerLevel level, Heightmap.Types type, long chunk, java.util.ArrayDeque<Order> queue) {
        Map<Long, Object> waiting = pending.get(type);
        if (waiting.containsKey(chunk) || urgent.size() + later.size() >= MAX_QUEUED) return;
        Object token = new Object();
        waiting.put(chunk, token);
        now = level.getGameTime();
        queue.add(new Order(type, chunk, token));
        submit(level, MAX_READS);
    }

    /** Отдать потоку ввода-вывода заказы из очереди, пока в пути меньше {@code limit} чтений (поток сервера). */
    private void submit(ServerLevel level, int limit) {
        while (reading.get() < limit) {
            Order o = urgent.isEmpty() ? later.poll() : urgent.poll();
            if (o == null) return;
            // чанк за это время пришёл в память и ушёл, оставив карту (onChunkUnload), — заказ уже не нужен
            if (pending.get(o.type).get(o.chunk) != o.token) continue;
            read(level, o.type, o.chunk, o.token);
        }
    }

    private void read(ServerLevel level, Heightmap.Types type, long chunk, Object token) {
        Map<Long, Object> waiting = pending.get(type);
        reading.incrementAndGet();
        ChunkPos pos = new ChunkPos(chunk);
        CollectFields fields = DiskStatus.fields(new FieldSelector(HEIGHTMAPS, LongArrayTag.TYPE, type.getSerializationKey()));
        int minY = level.getMinBuildHeight(), height = level.getHeight();
        reads.add(level.getChunkSource().chunkMap.chunkScanner().scanChunk(pos, fields).handle((v, e) -> {
            reading.decrementAndGet();
            CompoundTag tag = e == null && fields.getResult() instanceof CompoundTag t ? t : null;
            int[] y = tag == null ? null : decode(tag, type, minY, height);
            if (y != null) {
                finish(type, chunk, token, y, Terrain.Source.DISK, now);
            } else if (tag != null && DiskStatus.of(tag).blocksFinal()) {
                // блоки окончательные, а карты не прочесть: генератор дал бы чужой рельеф — неизвестно
                finish(type, chunk, token, null, Terrain.Source.UNKNOWN, now);
            } else if (waiting.get(chunk) == token) {
                toGenerate.add(new Order(type, chunk, token));
                pump(level);
            }
            return null;
        }));
    }

    /** Поле карт высот в файле чанка ({@code ChunkSerializer.HEIGHTMAPS_TAG}). */
    private static final String HEIGHTMAPS = "Heightmaps";

    /**
     * Ответ заказа (любой поток) — в очередь: в известные его кладёт конец тика мира ({@link #publish}). Иначе ответ,
     * пришедший посреди тика, видела бы одна колонка полосы датчика и не видела соседняя того же чанка, а полёт зависел
     * бы от скорости диска.
     */
    private void finish(Heightmap.Types type, long chunk, Object token, @Nullable int[] y, Terrain.Source source, long now) {
        answers.add(new Answer(type, chunk, token, y, source));
    }

    /**
     * Конец тика мира: пришедшие ответы — в известные. Карту, которую оставил ушедший из памяти чанк, ответ не заменяет:
     * заказ мог читать файл, записанный до того, как чанк побывал в памяти.
     */
    private void publish(long now) {
        for (Answer a; (a = answers.poll()) != null; ) {
            if (pending.get(a.type).remove(a.chunk, a.token) && a.source != null) known.get(a.type).putIfAbsent(a.chunk, new Heights(a.y, a.source, now));
        }
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

    /**
     * Генератор для чанков без карты на диске: не больше {@link #GENERATOR_WORKERS} разом, следующий — сразу по концу
     * предыдущего (из любого потока).
     */
    private void pump(ServerLevel level) {
        while (!toGenerate.isEmpty()) {
            int w = workers.get();
            if (w >= GENERATOR_WORKERS) return;
            if (!workers.compareAndSet(w, w + 1)) continue;
            Order job = toGenerate.poll();
            if (job == null) {
                workers.decrementAndGet();
                return;
            }
            generating.add(CompletableFuture.supplyAsync(() -> generate(level, job.chunk), Util.backgroundExecutor()).handle((y, e) -> {
                workers.decrementAndGet();
                if (y != null) {
                    finish(job.type, job.chunk, job.token, y, Terrain.Source.GENERATOR, now);
                } else {
                    // заказ снимается в конце тика без ответа: следующий вопрос закажет заново
                    answers.add(new Answer(job.type, job.chunk, job.token, null, null));
                    Incoming.LOG.warn("Terrain survey: generator relief for chunk {} failed", new ChunkPos(job.chunk), e);
                }
                pump(level);
                return null;
            }));
        }
    }

    /** Конец тика мира: пришедшие ответы — в известные ({@link #publish}), забывание старого. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.hasData(ModAttachments.TERRAIN_SURVEY)) return;
        TerrainSurvey s = get(level);
        s.reads.removeIf(CompletableFuture::isDone);
        s.generating.removeIf(CompletableFuture::isDone);
        long now = level.getGameTime();
        s.now = now;
        s.publish(now);
        s.submit(level, MAX_READS);
        if (now % 1200 == 0) {
            for (Map<Long, Heights> m : s.known.values()) m.values().removeIf(h -> now - h.used > FORGET_TICKS);
            s.forgetLast();
            if (s.points.size() > MAX_POINTS) s.points.clear();
        }
    }

    private void forgetLast() {
        lastChunk = Long.MIN_VALUE;
        lastType = null;
        last = null;
    }

    /**
     * Рельеф генератора в колонках чанка: узлы сетки {@link #GENERATOR_STEP} в углах и серединах сторон (общие с
     * соседними чанками — по памяти узлов), колонка — наибольший узел своей клетки. Фоновый поток.
     */
    int[] generate(ServerLevel level, long chunk) {
        return cells(ChunkPos.getX(chunk) << 4, ChunkPos.getZ(chunk) << 4, (x, z) -> point(level, x, z));
    }

    /** Колонки чанка с началом {@code x0, z0} по узлам {@code node(x, z)}: наибольший из четырёх узлов клетки. */
    static int[] cells(int x0, int z0, java.util.function.IntBinaryOperator nodeAt) {
        int n = 16 / GENERATOR_STEP + 1;
        int[] node = new int[n * n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) node[i + j * n] = nodeAt.applyAsInt(x0 + i * GENERATOR_STEP, z0 + j * GENERATOR_STEP);
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

    /**
     * Чанк уходит из памяти (перед записью): его карта высот — в известные, как с диска, для видов, которые спрашивали за
     * последние {@link #FORGET_TICKS} тиков, а заказы о нём в пути отбрасываются (их файл может быть старше). Без этого
     * между выгрузкой и ответом диска чанк был бы неизвестен тик-другой — смотря когда ваниль его выгрузила. Только
     * полный чанк ({@code LevelChunk}) и только готовая карта ({@code hasPrimedHeightmap}): у недогенерированного карт нет
     * или они без построек, а несчитанную карту {@code getHeight} посчитал бы и дописал в уходящий на диск чанк. Прежний
     * ответ о чанке забывается в любом случае: чанк, побывав в памяти, мог измениться.
     */
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.hasData(ModAttachments.TERRAIN_SURVEY)) return;
        TerrainSurvey s = get(level);
        ChunkAccess chunk = event.getChunk();
        long now = level.getGameTime(), pos = chunk.getPos().toLong();
        s.forget(pos);
        boolean recent = now - s.lastAsked <= FORGET_TICKS && !level.dimensionType().hasCeiling() && chunk instanceof LevelChunk;
        for (Heightmap.Types type : s.asked) {
            if (!recent || !chunk.hasPrimedHeightmap(type)) continue;
            s.pending.get(type).remove(pos);
            int[] y = new int[256];
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++) y[x + z * 16] = chunk.getHeight(type, x, z) + 1;
            s.known.get(type).put(pos, new Heights(y, Terrain.Source.DISK, now));
        }
    }

    /**
     * Дождаться всех заказов мира в этом потоке (сценарии полёта GameTest: ответ — в тике заказа, а не когда успеет
     * диск, иначе полёт расходился бы с эталоном). В игре не зовётся.
     */
    public static void settle(ServerLevel level) {
        if (!level.hasData(ModAttachments.TERRAIN_SURVEY)) return;
        TerrainSurvey s = get(level);
        while (true) {
            s.submit(level, Integer.MAX_VALUE);
            List<CompletableFuture<?>> wait = new ArrayList<>(s.reads);
            wait.addAll(s.generating);
            for (CompletableFuture<?> f : wait) f.join();
            s.reads.removeAll(wait);
            s.generating.removeAll(wait);
            Order job = s.toGenerate.poll();
            if (job == null && s.reads.isEmpty() && s.generating.isEmpty() && s.urgent.isEmpty() && s.later.isEmpty()) {
                s.publish(level.getGameTime());
                return;
            }
            if (job != null) s.finish(job.type, job.chunk, job.token, s.generate(level, job.chunk), Terrain.Source.GENERATOR, s.now);
        }
    }

    /**
     * Забыть чанк (GameTest: выгруженный чанк оставил съёмке свою карту, а проверка спрашивает файл на диске). В игре не
     * зовётся.
     */
    public void forget(long chunk) {
        if (chunk == lastChunk) forgetLast();
        for (Map<Long, Heights> m : known.values()) m.remove(chunk);
    }

    /** Заказов в пути (GameTest). */
    public int pendingCount() {
        int n = 0;
        for (Map<Long, Object> m : pending.values()) n += m.size();
        return n;
    }
}
