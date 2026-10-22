package ua.zentix.incoming.client.map;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Рельеф из базы LOD самого Voxy на клиенте (на NeoForge 1.21.1 — Voxy 0.2.16 через Roxy): всё, что Voxy видел (чанки
 * клиента за всю игру в этом мире), что прислал ему сервер с LOD Server Support и что игрок импортировал
 * ({@code /voxy import world <мир>} — сохранение одиночной игры целиком). Так дальний рельеф есть и в одиночной игре,
 * где LSS не работает, и без Distant Horizons.
 * <p>
 * API у Voxy нет (лицензия ARR: его код не копируем, только зовём): только чтение его классов отражением, без миксинов,
 * тем же путём, что LSS ({@code compat/VoxyTerrainProbe}, MIT): {@code WorldIdentifier.ofEngineNullable(Level)} — мир
 * Voxy (null — Voxy его не держит), {@code WorldEngine.acquireIfExists(уровень, x, y, z)} — секция 32³ вокселей или null,
 * {@code WorldSection.copyDataTo}, {@code release()} — сразу после копии (иначе секция не выгрузится),
 * {@code acquireRef/releaseRef} вокруг чтения (мир не закроется под ним), {@code Mapper.getBlockId} и
 * {@code getBlockStateFromBlockId} — блок вокселя. Секции нет в памяти Voxy — он читает свою базу в вызывающем потоке,
 * поэтому только из фоновых потоков, никогда из потока игры.
 * <p>
 * Уровни Voxy (воксель 1, 2, 4, 8, 16 блоков) — те же, что у плиток карты, поэтому плитка уровня L читает секции уровня
 * L ({@link #open(ClientLevel, int)}): 2×2 столбца секций на плитку любого уровня, а не (2^L)² участков, как у DH.
 * Прочитанный столбец — сводка верха колонок ({@link VoxyColumns}, 4 КБ) в памяти: из неё мгновенно отвечает
 * {@link #peek} (лучи, звук, ядерка). Чтобы лучам не ждать карты, вокруг игрока до {@link #SCAN_RADIUS} фоновый обход
 * читает уровень 4 (16 блоков) раз в {@link #RESCAN_NS}. Событий об изменениях Voxy не даёт (его единственный слот
 * {@code setDirtyCallback} занят его рендером): перечитанная сводка, которая стала другой, сама сообщает плиткам над
 * ней ({@link #changes}). Классы или методы Voxy другой версии — одна строка в лог, и источника нет.
 */
final class VoxyTerrain implements TerrainSource {
    /** Уровень, который обход вокруг игрока читает для лучей и звука: воксель 16 блоков, шаг луча не мельче. */
    static final int SCAN_LEVEL = 4;
    /** Докуда обход вокруг игрока, блоков: дальность дальних полётов и взрывов по умолчанию и LOD Voxy по умолчанию (~8 км). */
    static final int SCAN_RADIUS = 8192;
    /** Сводок в памяти не больше этого (по 4 КБ, ~32 МБ): сверх — самые дальние от игрока уходят. */
    static final int LIMIT = 8192;
    /** Обход не чаще этого и заново, только если игрок ушёл дальше {@link #SCAN_MOVE} или сводки устарели. */
    private static final long SCAN_PERIOD_NS = 30_000_000_000L;
    private static final double SCAN_MOVE = 512;
    /** Сводка старше этого перечитывается обходом (воронки и руины вдали появляются в базе Voxy, когда он их увидел). */
    private static final long RESCAN_NS = 60_000_000_000L;
    /** Плитка берёт сводку из памяти, если она моложе этого (прочитана только что соседней плиткой или обходом). */
    private static final long FRESH_NS = 5_000_000_000L;
    /** Полная плитка перечитывается раз в 2 минуты (изменение, замеченное обходом, приходит раньше, {@link #changes}). */
    private static final long REFRESH_NS = 120_000_000_000L;
    /** Мир Voxy проверяется не чаще этого: он появляется после входа в мир и уходит при выходе или перезапуске Voxy. */
    private static final long RESOLVE_NS = 1_000_000_000L;

    /** Методы Voxy 0.2.16; null — Voxy не стоит, его классы другие или чтение сломалось на ходу ({@link #disable}). */
    @Nullable
    private static volatile Access access;
    /** Для лога: прочитано столбцов секций и секций, время, отказы. */
    private static final AtomicLong columnsRead = new AtomicLong(), sectionsRead = new AtomicLong(), nanos = new AtomicLong(),
            maxNanos = new AtomicLong(), failures = new AtomicLong();
    @Nullable
    private static volatile String firstFailure;
    /** Обход вокруг игрока — одним фоновым потоком на все миры. */
    private static final ExecutorService scanner = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat("Incoming Voxy terrain").setDaemon(true).setPriority(Thread.MIN_PRIORITY).build());
    /** Буфер секции (32768 вокселей, 256 КБ) на поток. */
    private static final ThreadLocal<long[]> buffer = ThreadLocal.withInitial(() -> new long[VoxyColumns.VOLUME]);

    /** Отражение до классов Voxy, только чтение. */
    static final class Access {
        final MethodHandle ofEngineNullable, acquireIfExists, copyDataTo, release, acquireRef, releaseRef, mapper, blockId, stateOf;

        Access(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> identifier = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier", false, loader);
            Class<?> engine = Class.forName("me.cortex.voxy.common.world.WorldEngine", false, loader);
            Class<?> section = Class.forName("me.cortex.voxy.common.world.WorldSection", false, loader);
            Class<?> mapperClass = Class.forName("me.cortex.voxy.common.world.other.Mapper", false, loader);
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            MethodType toObject = MethodType.methodType(Object.class, Object.class);
            ofEngineNullable = l.unreflect(identifier.getMethod("ofEngineNullable", Level.class)).asType(MethodType.methodType(Object.class, Level.class));
            acquireIfExists = l.unreflect(engine.getMethod("acquireIfExists", int.class, int.class, int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class, int.class, int.class));
            copyDataTo = l.unreflect(section.getMethod("copyDataTo", long[].class)).asType(MethodType.methodType(void.class, Object.class, long[].class));
            release = l.unreflect(section.getMethod("release")).asType(MethodType.methodType(void.class, Object.class));
            acquireRef = l.unreflect(engine.getMethod("acquireRef")).asType(MethodType.methodType(void.class, Object.class));
            releaseRef = l.unreflect(engine.getMethod("releaseRef")).asType(MethodType.methodType(void.class, Object.class));
            mapper = l.unreflect(engine.getMethod("getMapper")).asType(toObject);
            blockId = l.unreflect(mapperClass.getMethod("getBlockId", long.class)).asType(MethodType.methodType(int.class, long.class));
            Class<?> state = mapperClass.getMethod("getBlockStateFromBlockId", int.class).getReturnType();
            if (!BlockState.class.isAssignableFrom(state)) throw new NoSuchMethodException("Mapper.getBlockStateFromBlockId returns " + state.getName());
            stateOf = l.unreflect(mapperClass.getMethod("getBlockStateFromBlockId", int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class));
        }
    }

    /**
     * При запуске клиента: стоит ли Voxy и нашлись ли все его классы и методы. Нет — источника нет (одна строка в лог),
     * игра идёт как без Voxy.
     */
    static boolean init() {
        if (!ModList.get().isLoaded("voxy")) return false;
        return init(VoxyTerrain.class.getClassLoader());
    }

    static boolean init(ClassLoader loader) {
        try {
            access = new Access(loader);
            Incoming.LOG.info("Voxy terrain for the map, sight lines and sound: classes found, reading its LOD database (read-only)");
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            access = null;
            Incoming.LOG.warn("Voxy: its classes differ from Voxy 0.2.16 the mod reads ({}) — far terrain from Voxy is off", e.toString());
            return false;
        }
    }

    /** Voxy поменялся так, что методы есть, а вызов не тот: источник выключается до перезапуска одной строкой в лог. */
    private static void disable(Throwable e) {
        if (access == null) return;
        access = null;
        Incoming.LOG.warn("Voxy terrain: reading Voxy failed ({}) — far terrain from Voxy is off", e.toString());
    }

    /** Свёрнутый верх колонок столбца секций уровня {@code level}: прочитан тогда-то, есть ли хоть одна колонка. */
    record Section(int[] columns, long readAt, boolean any) {}

    /**
     * Мир Voxy, который сейчас у клиента, и всё прочитанное из него: другой мир Voxy (вышли, Voxy перезапустил свой
     * экземпляр) — новый объект, и чтения, начатые для старого, кладут своё в старый.
     */
    static final class Engine {
        final Object engine, mapper;
        final Map<Long, Section> cache = new ConcurrentHashMap<>();
        /** Сведения о блоке по номеру Voxy ({@link VoxyColumns#info}); 0 — ещё не смотрели. */
        volatile int[] infos = new int[1024];
        final AtomicBoolean any = new AtomicBoolean();

        Engine(Object engine, Object mapper) {
            this.engine = engine;
            this.mapper = mapper;
        }
    }

    /** Мир клиента (в юнит-тестах — null: подделка Voxy отдаёт свой мир). */
    @Nullable
    private final Level level;
    /** Высоты мира: какие секции Voxy смотреть. */
    private final int minY, maxY;
    @Nullable
    private volatile Engine engine;
    private long resolvedAt = Long.MIN_VALUE;
    /** Чанки ({@code ChunkPos.asLong}), над которыми сводка перечитана другой: плитки там — перестроить. */
    private final Set<Long> changed = ConcurrentHashMap.newKeySet();
    private volatile boolean scanning, closed;
    /** Обход был (и сколько колонок секций прошёл, за сколько); где и когда начат последний. */
    private volatile boolean scanned;
    private volatile int lastScanColumns;
    private volatile long lastScanNanos, scanStarted = Long.MIN_VALUE / 2;
    private double scanX = Double.NaN, scanZ = Double.NaN;

    VoxyTerrain(ClientLevel level) {
        this(level, level.getMinBuildHeight(), level.getMaxBuildHeight());
    }

    VoxyTerrain(@Nullable Level level, int minY, int maxY) {
        this.level = level;
        this.minY = minY;
        this.maxY = maxY;
    }

    /** Ключ сводки: уровень и столбец секций. */
    static long key(int level, int sx, int sz) {
        return (long) level << 60 | ((long) sx & 0x3FFFFFFFL) << 30 | (long) sz & 0x3FFFFFFFL;
    }

    /** Мир Voxy этого мира клиента: в потоке игры, не чаще {@link #RESOLVE_NS}. */
    @Nullable
    private Engine resolve() {
        Access a = access;
        if (a == null) return null;
        long now = System.nanoTime();
        if (now - resolvedAt < RESOLVE_NS) return engine;
        resolvedAt = now;
        try {
            Object e = (Object) a.ofEngineNullable.invokeExact(level);
            Engine was = engine;
            if (e == null) engine = null;
            else if (was == null || was.engine != e) engine = new Engine(e, (Object) a.mapper.invokeExact(e));
        } catch (Throwable t) {
            fail(t, null);
        }
        return engine;
    }

    /**
     * Отказ при вызове Voxy: изменилось устройство ({@link LinkageError}, не тот тип) — источник выключен; мир Voxy
     * закрыт под чтением ({@link IllegalStateException} «World is not live») — забыть его, найти заново; другое — в
     * счётчик отказов, первый — в лог.
     */
    private void fail(Throwable t, @Nullable Engine in) {
        if (t instanceof LinkageError || t instanceof WrongMethodTypeException || t instanceof ClassCastException) {
            disable(t);
            return;
        }
        if (t instanceof Error err) throw err;
        if (t instanceof IllegalStateException && in != null) {
            if (engine == in) engine = null;
            return;
        }
        if (failures.getAndIncrement() == 0) {
            firstFailure = t.toString();
            Incoming.LOG.warn("Voxy terrain: a section of its LOD database not read", t);
        }
    }

    /** Сведения о блоке {@code id} мира Voxy {@code e}: 0 — не знаем (Voxy ещё дописывает свой список, сбой). */
    private static int info(Access a, Engine e, int id) {
        int[] t = e.infos;
        if (id < t.length && t[id] != 0) return t[id];
        int v;
        try {
            Object state = (Object) a.stateOf.invokeExact(e.mapper, id);
            if (!(state instanceof BlockState s)) return 0;
            v = VoxyColumns.info(s);
        } catch (RuntimeException ex) {
            // список блоков Voxy растёт в его потоке без замка: номер, которого ещё не видно, — как воздух, спросим позже
            return 0;
        } catch (Throwable ex) {
            if (ex instanceof Error err) throw err;
            throw new IllegalStateException(ex);
        }
        synchronized (e) {
            t = e.infos;
            if (id >= t.length) e.infos = t = Arrays.copyOf(t, Math.max(id + 1, t.length * 2));
            t[id] = v;
        }
        return v;
    }

    /**
     * Столбец секций уровня {@code lvl} из базы Voxy — в вызывающем (фоновом) потоке; null — не прочитан (сбой, мир
     * Voxy закрыт). Сводка — в память; стала другой — плитки над ней перестроятся.
     */
    @Nullable
    private Section read(Engine e, int lvl, int sx, int sz) {
        Access a = access;
        if (a == null || closed) return null;
        long start = System.nanoTime();
        int shift = 5 + lvl, top = (maxY - 1) >> shift, bottom = minY >> shift;
        VoxyColumns.Summary s = new VoxyColumns.Summary(lvl);
        long[] data = buffer.get();
        int sections = 0;
        try {
            a.acquireRef.invokeExact(e.engine);
            try {
                for (int sy = top; sy >= bottom && !s.done(); sy--) {
                    Object section = (Object) a.acquireIfExists.invokeExact(e.engine, lvl, sx, sy, sz);
                    if (section == null) {
                        s.section(sy, null, v -> 0, id -> 0);
                        continue;
                    }
                    try {
                        a.copyDataTo.invokeExact(section, data);
                    } finally {
                        a.release.invokeExact(section);
                    }
                    sections++;
                    s.section(sy, data, v -> blockId(a, v), id -> info(a, e, id));
                }
            } finally {
                a.releaseRef.invokeExact(e.engine);
            }
        } catch (Throwable t) {
            fail(t, e);
            return null;
        }
        int[] columns = s.finish();
        boolean any = false;
        for (int c : columns) any |= c != 0;
        Section now = new Section(columns, System.nanoTime(), any);
        Section was = e.cache.put(key(lvl, sx, sz), now);
        if (was != null && !Arrays.equals(was.columns, columns)) markChanged(lvl, sx, sz);
        if (any) e.any.set(true);
        long took = System.nanoTime() - start;
        columnsRead.incrementAndGet();
        sectionsRead.addAndGet(sections);
        nanos.addAndGet(took);
        maxNanos.accumulateAndGet(took, Math::max);
        return now;
    }

    private static int blockId(Access a, long voxel) {
        try {
            return (int) a.blockId.invokeExact(voxel);
        } catch (RuntimeException | Error t) {
            throw t;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Сводка столбца моложе {@code maxAge} — из памяти, иначе — прочитать. */
    @Nullable
    private Section section(Engine e, int lvl, int sx, int sz, long maxAge) {
        Section s = e.cache.get(key(lvl, sx, sz));
        if (s != null && System.nanoTime() - s.readAt < maxAge) return s;
        Section read = read(e, lvl, sx, sz);
        return read != null ? read : s;
    }

    /** Чанки под столбцом секций — через каждые 64 блока (плитка уровня 0), их плитки на всех уровнях перестроятся. */
    private void markChanged(int lvl, int sx, int sz) {
        int span = VoxyColumns.SIDE << lvl, x0 = sx * span, z0 = sz * span;
        int step = Math.min(span, TerrainTiles.SIZE);
        for (int z = 0; z < span; z += step) {
            for (int x = 0; x < span; x += step) changed.add(ChunkPos.asLong((x0 + x) >> 4, (z0 + z) >> 4));
        }
    }

    @Override
    public boolean active() {
        return access != null && resolve() != null;
    }

    @Override
    public boolean offThread() {
        return true;
    }

    /** Чтение базы Voxy — в наших потоках: сколько их сразу, столько секций читается параллельно. */
    @Override
    public int parallelism() {
        return 4;
    }

    @Override
    public long refreshNanos() {
        return REFRESH_NS;
    }

    @Nullable
    @Override
    public Reader open(ClientLevel level) {
        return open(level, 0);
    }

    @Nullable
    @Override
    public Reader open(ClientLevel level, int detail) {
        Engine e = engine;
        if (e == null || access == null) return null;
        int shift = 5 + detail;
        return new Reader() {
            private final Map<Long, int[]> read = new HashMap<>();

            @Nullable
            @Override
            public Column column(int x, int z) {
                int sx = x >> shift, sz = z >> shift;
                int[] c = read.computeIfAbsent(key(detail, sx, sz), k -> {
                    Section s = section(e, detail, sx, sz, FRESH_NS);
                    return s == null ? new int[0] : s.columns;
                });
                return c.length == 0 ? null : LssColumns.column(c[index(detail, x, z)]);
            }

            @Override
            public void close() {}
        };
    }

    /** Колонка (x, z) в сводке её столбца секций уровня {@code level}. */
    static int index(int level, int x, int z) {
        return ((z >> level) & 31) << 5 | (x >> level) & 31;
    }

    /** Самая подробная сводка в памяти, где колонка известна. */
    @Nullable
    @Override
    public Column peek(int x, int z) {
        Engine e = engine;
        if (e == null) return null;
        for (int l = 0; l <= TerrainTiles.MAX_LEVEL; l++) {
            Section s = e.cache.get(key(l, x >> (5 + l), z >> (5 + l)));
            if (s == null || !s.any) continue;
            int c = s.columns[index(l, x, z)];
            if (LssColumns.present(c)) return LssColumns.column(c);
        }
        return null;
    }

    /**
     * Раз в тик, в потоке игры: обход вокруг игрока — в фоне, не чаще {@link #SCAN_PERIOD_NS}, раньше — если игрок ушёл
     * дальше {@link #SCAN_MOVE}.
     */
    @Override
    public void tickNear(double playerX, double playerZ) {
        Engine e = resolve();
        if (e == null || scanning || closed) return;
        long now = System.nanoTime();
        boolean moved = Double.isNaN(scanX) || Math.hypot(playerX - scanX, playerZ - scanZ) > SCAN_MOVE;
        if (!moved && now - scanStarted < SCAN_PERIOD_NS) return;
        scanning = true;
        scanStarted = now;
        scanX = playerX;
        scanZ = playerZ;
        try {
            scanner.execute(() -> {
                try {
                    scan(e, playerX, playerZ);
                } finally {
                    scanning = false;
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            scanning = false;
        }
    }

    /** Обход: столбцы секций уровня {@link #SCAN_LEVEL} в круге {@link #SCAN_RADIUS}, ближние первыми; потом — лишнее из памяти. */
    private void scan(Engine e, double x, double z) {
        long start = System.nanoTime();
        List<long[]> todo = around(x, z, SCAN_LEVEL, SCAN_RADIUS);
        int n = 0;
        for (long[] c : todo) {
            if (closed || engine != e || access == null) break;
            section(e, SCAN_LEVEL, (int) c[0], (int) c[1], RESCAN_NS);
            n++;
        }
        trim(e.cache, x, z, LIMIT);
        lastScanColumns = n;
        lastScanNanos = System.nanoTime() - start;
        scanned = true;
    }

    /** Столбцы секций уровня {@code level} (x, z), середина которых в круге {@code radius} блоков, ближние первыми. */
    static List<long[]> around(double x, double z, int level, int radius) {
        int span = VoxyColumns.SIDE << level, r = radius / span + 1;
        int cx = (int) Math.floor(x / span), cz = (int) Math.floor(z / span);
        List<long[]> out = new ArrayList<>();
        for (int sz = cz - r; sz <= cz + r; sz++) {
            for (int sx = cx - r; sx <= cx + r; sx++) {
                double dx = (sx + 0.5) * span - x, dz = (sz + 0.5) * span - z;
                long d2 = (long) (dx * dx + dz * dz);
                if (d2 <= (long) radius * radius) out.add(new long[] {sx, sz, d2});
            }
        }
        out.sort(Comparator.comparingLong(a -> a[2]));
        return out;
    }

    /** Сводок больше {@code limit} — самые дальние от (x, z) уходят, пока не останется девять десятых. */
    static int trim(Map<Long, Section> cache, double x, double z, int limit) {
        if (cache.size() <= limit) return 0;
        List<long[]> all = new ArrayList<>(cache.size());
        for (long k : cache.keySet()) {
            int lvl = (int) (k >>> 60), span = VoxyColumns.SIDE << lvl;
            int sx = (int) (k << 4 >> 34), sz = (int) (k << 34 >> 34);
            double dx = (sx + 0.5) * span - x, dz = (sz + 0.5) * span - z;
            all.add(new long[] {k, (long) (dx * dx + dz * dz)});
        }
        all.sort(Comparator.comparingLong((long[] a) -> a[1]).reversed());
        int drop = cache.size() - limit * 9 / 10, done = 0;
        for (long[] a : all) {
            if (done >= drop) break;
            if (cache.remove(a[0]) != null) done++;
        }
        return done;
    }

    @Override
    public void changes(ClientLevel level, ChunkSink sink) {
        if (changed.isEmpty()) return;
        for (var it = changed.iterator(); it.hasNext(); ) {
            long c = it.next();
            it.remove();
            sink.changed(ChunkPos.getX(c), ChunkPos.getZ(c));
        }
    }

    /** Обход прошёл, а в базе Voxy вокруг нет ничего: Voxy этого места не видел. */
    @Override
    public boolean waiting() {
        Engine e = engine;
        return e != null && scanned && !e.any.get();
    }

    @Override
    public String pendingNote() {
        return "incoming.map.far_pending_voxy";
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public String describe() {
        if (access == null) return "Voxy off (classes differ)";
        Engine e = engine;
        long n = columnsRead.get();
        return String.format(Locale.ROOT, "Voxy (world %s): section columns read %d (%d sections), avg %.2f ms max %.1f ms, failures %d%s; in memory %d, "
                        + "last scan %d columns in %.1f s",
                e == null ? "none" : "open", n, sectionsRead.get(), n == 0 ? 0.0 : nanos.get() / 1e6 / n, maxNanos.get() / 1e6, failures.get(),
                firstFailure == null ? "" : " (first: " + firstFailure + ")", e == null ? 0 : e.cache.size(), lastScanColumns, lastScanNanos / 1e9);
    }
}
