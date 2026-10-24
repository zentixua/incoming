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
import java.util.LinkedHashMap;
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
    /** Сколько чанков генератора считается разом: один из общих потоков Minecraft, остальные — генерации мира; следующий — сразу по концу. */
    static final int GENERATOR_WORKERS = 1;
    /** Верхняя граница карт каждого вида (выгрузки идут и без полётов, память должна быть ограничена сразу). */
    static final int MAX_KNOWN = 8192;
    /** После ошибки генератора чанк неизвестен столько тиков; вопросы не продлевают запрет повторной попытки. */
    static final int RETRY_TICKS = 200;
    /** Чанк, о котором не спрашивали столько тиков, забывается. */
    static final int FORGET_TICKS = 6000;
    /** Узлов генератора больше этого — память узлов сбрасывается (≈1,5 МБ). */
    static final int MAX_POINTS = 65_536;

    /** Известные карты высот: вид карты → чанк → колонки. */
    private final Map<Heightmap.Types, Map<Long, Heights>> known = new EnumMap<>(Heightmap.Types.class);
    /** Заказанные: вид карты → чанк → метка заказа (заказ, чья метка снята, отбрасывается). */
    private final Map<Heightmap.Types, Map<Long, Order>> pending = new EnumMap<>(Heightmap.Types.class);
    /** Чтения с диска в пути (для {@link #settle}). */
    private final Queue<CompletableFuture<?>> reads = new ConcurrentLinkedQueue<>();
    /** Чанки без карты на диске — ждут генератора (кладёт поток ввода-вывода, считает фоновый поток, {@link #pump}). */
    private final Orders toGenerate = new Orders();
    /** Счёт генератора в пути (для {@link #settle}). */
    private final Queue<CompletableFuture<?>> generating = new ConcurrentLinkedQueue<>();
    private final AtomicInteger workers = new AtomicInteger();
    private final AtomicInteger reading = new AtomicInteger();
    /** Заказы, ждущие чтения (поток сервера): вопросы датчика — первыми, заказ вперёд — следом. */
    private final Orders toRead = new Orders();

    /** Заказ чанка с меткой (ждёт чтения или генератора). */
    static final class Order {
        final Heightmap.Types type;
        final long chunk;
        volatile boolean urgent;

        Order(Heightmap.Types type, long chunk, boolean urgent) {
            this.type = type;
            this.chunk = chunk;
            this.urgent = urgent;
        }
    }

    /** Две FIFO с продвижением уже ожидающего заказа; потоки IO и генератора тоже обращаются к очереди. */
    static final class Orders {
        private final Map<Order, Order> urgent = new LinkedHashMap<>(), later = new LinkedHashMap<>();

        synchronized boolean add(Order o) {
            if (urgent.containsKey(o) || later.containsKey(o)) return true;
            if (size() >= MAX_QUEUED) return false;
            (o.urgent ? urgent : later).put(o, o);
            return true;
        }

        synchronized void promote(Order o) {
            o.urgent = true;
            if (later.remove(o) != null) urgent.put(o, o);
        }

        synchronized void remove(Order o) {
            urgent.remove(o);
            later.remove(o);
        }

        synchronized Order poll() {
            Map<Order, Order> q = urgent.isEmpty() ? later : urgent;
            var i = q.keySet().iterator();
            if (!i.hasNext()) return null;
            Order o = i.next();
            i.remove();
            return o;
        }

        synchronized int size() {
            return urgent.size() + later.size();
        }

        synchronized boolean isEmpty() {
            return size() == 0;
        }
    }
    private final java.util.Set<Heightmap.Types> asked = java.util.EnumSet.of(Heightmap.Types.MOTION_BLOCKING);
    /** Узлы рельефа генератора: (x / шаг, z / шаг) → первый воздух. */
    private final Map<Long, Integer> points = new ConcurrentHashMap<>();

    /** Для {@link ModAttachments#TERRAIN_SURVEY}. */
    public TerrainSurvey() {
        for (Heightmap.Types t : Heightmap.Types.values()) {
            known.put(t, new LinkedHashMap<>(16, 0.75f, true));
            pending.put(t, new ConcurrentHashMap<>());
        }
    }

    public static TerrainSurvey get(ServerLevel level) {
        return level.getData(ModAttachments.TERRAIN_SURVEY);
    }

    /**
     * Первый воздух в колонках чанка и откуда он; {@code used} — тик последнего вопроса. {@code y} null — чанк на диске
     * с окончательными блоками, но без годной карты высот (формат до 1.18 или длина не по высоте мира): рельеф неизвестен,
     * генератор тут не годится — мир построен не им или с тех пор изменён.
     */
    static final class Heights {
        @Nullable
        final int[] y;
        final Terrain.Source source;
        long used;
        final long retryAt;

        Heights(@Nullable int[] y, Terrain.Source source, long used) {
            this(y, source, used, Long.MAX_VALUE);
        }

        Heights(@Nullable int[] y, Terrain.Source source, long used, long retryAt) {
            this.y = y;
            this.source = source;
            this.used = used;
            this.retryAt = retryAt;
        }
    }


    /** Ответ заказа из фонового потока: в известные — в конце тика мира ({@link #publish}). */
    private record Answer(Order order, @Nullable int[] y, Terrain.Source source, boolean retry) {}

    /** Ответы, пришедшие с прошлого конца тика. */
    private final Queue<Answer> answers = new ConcurrentLinkedQueue<>();

    /**
     * Поверхность колонки (первый воздух над картой высот {@code type}) по съёмке; null — ещё не известна (съёмка
     * заказана). Готовый чанк {@link Terrain#estimate} читает сам, до съёмки. Только поток сервера; чанк не грузится.
     */
    @Nullable
    public Terrain.Surface at(ServerLevel level, Heightmap.Types type, int x, int z) {
        long chunk = ChunkPos.asLong(x >> 4, z >> 4);
        if (level.dimensionType().hasCeiling()) return null;
        asked.add(type);
        LevelChunk memory = Terrain.inMemory(level, chunk);
        if (memory != null) return new Terrain.Surface(memory.getHeight(type, x & 15, z & 15) + 1, Terrain.Source.DISK);
        Heights h = cached(type, chunk, level.getGameTime());
        if (h != null) {
            h.used = level.getGameTime();
            return h.y == null ? null : new Terrain.Surface(h.y[(x & 15) + (z & 15) * 16], h.source);
        }
        request(level, type, chunk, true);
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
                        request(level, type, c, false);
                }
            return true;
        });
    }

    private void request(ServerLevel level, Heightmap.Types type, long chunk, boolean urgent) {
        order(type, chunk, urgent);
        submit(level, MAX_READS);
    }

    /** Один живой заказ на чанк и вид карты; общий предел действует до ответа генератора включительно. */
    @Nullable
    Order order(Heightmap.Types type, long chunk, boolean urgent) {
        Map<Long, Order> waiting = pending.get(type);
        Order existing = waiting.get(chunk);
        if (existing != null) {
            if (urgent) {
                toRead.promote(existing);
                toGenerate.promote(existing);
            }
            return existing;
        }
        // Половина мест оставлена вопросам датчика: предзаказ не вытесняет новую нужную сейчас колонку.
        if (pendingCount() >= (urgent ? MAX_QUEUED : MAX_QUEUED / 2)) return null;
        Order order = new Order(type, chunk, urgent);
        waiting.put(chunk, order);
        toRead.add(order);
        return order;
    }

    @Nullable
    Heights cached(Heightmap.Types type, long chunk, long now) {
        Heights h = known.get(type).get(chunk);
        if (h != null && now >= h.retryAt) {
            forget(chunk);
            return null;
        }
        return h;
    }

    private boolean current(Order o) {
        return pending.get(o.type).get(o.chunk) == o;
    }

    /** Отдать потоку ввода-вывода заказы из очереди, пока в пути меньше {@code limit} чтений (поток сервера). */
    private void submit(ServerLevel level, int limit) {
        while (reading.get() < limit) {
            Order o = toRead.poll();
            if (o == null) return;
            if (current(o)) read(level, o);
        }
    }

    private void read(ServerLevel level, Order o) {
        reading.incrementAndGet();
        CollectFields fields = DiskStatus.fields(new FieldSelector(HEIGHTMAPS, LongArrayTag.TYPE, o.type.getSerializationKey()));
        int minY = level.getMinBuildHeight(), height = level.getHeight();
        reads.add(level.getChunkSource().chunkMap.chunkScanner().scanChunk(new ChunkPos(o.chunk), fields).handle((v, e) -> {
            reading.decrementAndGet();
            CompoundTag tag = e == null && fields.getResult() instanceof CompoundTag t ? t : null;
            int[] y = tag == null ? null : decode(tag, o.type, minY, height);
            if (y != null) {
                finish(o, y, Terrain.Source.DISK, false);
            } else if (e != null) {
                // Ошибка чтения — не доказательство отсутствия чанка: генератор мог бы выдумать чужой рельеф.
                finish(o, null, Terrain.Source.UNKNOWN, true);
                Incoming.LOG.warn("Terrain survey: reading chunk {} failed", new ChunkPos(o.chunk), e);
            } else if (tag != null && DiskStatus.of(tag).blocksFinal()) {
                finish(o, null, Terrain.Source.UNKNOWN, false);
            } else if (current(o)) {
                if (toGenerate.add(o)) pump(level);
                else finish(o, null, Terrain.Source.UNKNOWN, true);
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
    void finish(Order o, @Nullable int[] y, Terrain.Source source, boolean retry) {
        answers.add(new Answer(o, y, source, retry));
    }

    /** Ответы становятся видны одновременно, в конце тика; старый заказ не заменяет карту уходящего чанка. */
    void publish(long now) {
        for (Answer a; (a = answers.poll()) != null; ) {
            Order o = a.order;
            if (pending.get(o.type).remove(o.chunk, o))
                remember(o.type, o.chunk, new Heights(a.y, a.source, now, a.retry ? now + RETRY_TICKS : Long.MAX_VALUE));
        }
    }

    void remember(Heightmap.Types type, long chunk, Heights h) {
        Map<Long, Heights> map = known.get(type);
        map.remove(chunk);
        map.put(chunk, h);
        if (map.size() > MAX_KNOWN) {
            var i = map.keySet().iterator();
            i.next();
            i.remove();
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
            if (!current(job)) {
                workers.decrementAndGet();
                continue;
            }
            generating.add(CompletableFuture.supplyAsync(() -> generate(level, job.chunk), Util.backgroundExecutor()).handle((y, e) -> {
                // Ответ кладётся до освобождения работника: settle не пропустит ещё не опубликованный результат.
                if (current(job)) {
                    if (y != null) finish(job, y, Terrain.Source.GENERATOR, false);
                    else {
                        finish(job, null, Terrain.Source.UNKNOWN, true);
                        Incoming.LOG.warn("Terrain survey: generator relief for chunk {} failed; retry in {} ticks", new ChunkPos(job.chunk), RETRY_TICKS, e);
                    }
                }
                workers.decrementAndGet();
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
        s.publish(now);
        s.submit(level, MAX_READS);
        if (now % 1200 == 0) {
            for (Map<Long, Heights> m : s.known.values()) m.values().removeIf(h -> now - h.used > FORGET_TICKS);
            if (s.points.size() > MAX_POINTS) s.points.clear();
        }
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
     * Чанк уходит из памяти перед записью: всегда оставляет готовую MOTION_BLOCKING, даже если полётов ещё не было.
     * Все старые заказы отменяются независимо от полноты чанка и наличия карты; поздний ответ их не воскресит.
     * Читаются только уже посчитанные карты полного чанка, без изменения уходящего чанка. Память ограничена MAX_KNOWN.
     */
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        TerrainSurvey s = get(level);
        ChunkAccess chunk = event.getChunk();
        long now = level.getGameTime(), pos = chunk.getPos().toLong();
        s.invalidate(pos);
        if (level.dimensionType().hasCeiling() || !(chunk instanceof LevelChunk)) return;
        for (Heightmap.Types type : s.asked) {
            if (!chunk.hasPrimedHeightmap(type)) continue;
            int[] y = new int[256];
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++) y[x + z * 16] = chunk.getHeight(type, x, z) + 1;
            s.remember(type, pos, new Heights(y, Terrain.Source.DISK, now));
        }
    }

    /** Свежий чанк в памяти точнее любых прежних ответов или запросов к файлу. */
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.hasData(ModAttachments.TERRAIN_SURVEY))
            get(level).invalidate(event.getChunk().getPos().toLong());
    }

    void invalidate(long chunk) {
        forget(chunk);
        for (Map<Long, Order> m : pending.values()) {
            Order o = m.remove(chunk);
            if (o != null) {
                toRead.remove(o);
                toGenerate.remove(o);
            }
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
            if (job == null && s.reads.isEmpty() && s.generating.isEmpty() && s.toRead.isEmpty() && s.workers.get() == 0) {
                s.publish(level.getGameTime());
                return;
            }
            if (job != null && s.current(job)) {
                try {
                    s.finish(job, s.generate(level, job.chunk), Terrain.Source.GENERATOR, false);
                } catch (RuntimeException e) {
                    s.finish(job, null, Terrain.Source.UNKNOWN, true);
                }
            }
        }
    }

    /**
     * Забыть чанк (GameTest: выгруженный чанк оставил съёмке свою карту, а проверка спрашивает файл на диске). В игре не
     * зовётся.
     */
    public void forget(long chunk) {
        for (Map<Long, Heights> m : known.values()) m.remove(chunk);
    }

    /** Заказов в пути (GameTest). */
    public int pendingCount() {
        int n = 0;
        for (Map<Long, Order> m : pending.values()) n += m.size();
        return n;
    }
}
