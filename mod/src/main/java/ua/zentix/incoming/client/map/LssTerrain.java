package ua.zentix.incoming.client.map;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Рельеф из LOD Server Support (LSS, MIT) через его публичное клиентское API
 * ({@code LSSApi.registerColumnConsumer}): сервер с LSS шлёт клиенту чанки по всему своему радиусу LOD (по умолчанию
 * 512 чанков), и мод получает каждый — тот же поток, которым LSS кормит Voxy и карты Xaero и JourneyMap. Подписчик
 * есть — клиент LSS качает и без Voxy. Верх колонок считается сразу в потоке LSS ({@link LssColumns#summarize}, десятки
 * микросекунд на чанк, без блокировок и ожиданий: его поток не стоит) и лежит в памяти — читается мгновенно из любого
 * потока ({@link #peek}): лучам и звуку не нужно ждать плиток карты.
 * <p>
 * Клиент LSS помнит, какие чанки уже получил (свой кэш меток на диске), и при новом входе просит у сервера только
 * изменённые: его подписчики хранят присланное у себя (Voxy — в базе). Поэтому и колонки хранятся на диске —
 * {@code incoming/lss-terrain/<сервер>/<измерение>.bin} в папке игры, раз в {@link #SAVE_TICKS} тиков, если что-то
 * пришло, и при выходе из мира. Файла нет (LSS стоял раньше мода или файл потерян) — вдали пусто, пока сервер не
 * пришлёт чанки снова: {@code /lss clearcache} просит всё заново.
 * <p>
 * LSS на встроенном сервере одиночной игры не включается (его клиент проверяет это сам), кроме мира, открытого по
 * сети: там рельеф вдали — Distant Horizons, если стоит. Класс загружается, только если LSS стоит (см. {@link TerrainTiles}).
 */
final class LssTerrain implements TerrainSource {
    /** Подробных чанков (1 КБ) в памяти не больше этого (~64 МБ, круг ~2,3 км), дальние от игрока — грубыми (64 байта). */
    static final int FULL_LIMIT = 65_536;
    /** Понижать — до этой доли предела, чтобы не делать это каждые несколько секунд. */
    private static final int FULL_KEEP = FULL_LIMIT * 9 / 10;
    /**
     * Чанков всего не больше этого (~80 МБ грубыми; круг радиусом LOD сервера по умолчанию, 512 чанков, — ~820 тыс.):
     * сверх — самые дальние от игрока уходят. LSS о них не знает и сам их не пришлёт (дыра далеко от игры, до
     * изменения чанка или {@code /lss clearcache}); его собственный кэш меток так же ограничен 2 млн.
     */
    static final int TOTAL_LIMIT = 1 << 20;
    private static final int TOTAL_KEEP = TOTAL_LIMIT * 9 / 10;
    private static final int SAVE_TICKS = 2400;
    private static final int COARSEN_TICKS = 200;
    /** Полная плитка без событий перечитывается раз в 5 минут (изменения приходят событием, {@link #changes}). */
    private static final long REFRESH_NS = 300_000_000_000L;
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");

    /**
     * Колонки мира одного сервера (адрес и хэш сида мира — как ключ кэша меток самого LSS: другой мир на том же адресе
     * LSS шлёт заново, и чужой рельеф не должен остаться) и измерения; что пришло с прошлого тика; прочитан ли файл.
     */
    static final class World {
        final String server, seed;
        final ResourceKey<Level> dimension;
        final LssColumns.Store store = new LssColumns.Store();
        final Set<Long> changed = ConcurrentHashMap.newKeySet();
        /** Что-то пришло после записи файла; файл прочитан; прочитанное ещё не отдано плиткам ({@link #invalidated}). */
        final AtomicBoolean dirty = new AtomicBoolean(), loaded = new AtomicBoolean(), fresh = new AtomicBoolean();
        volatile String file = "";

        World(String server, String seed, ResourceKey<Level> dimension) {
            this.server = server;
            this.seed = seed;
            this.dimension = dimension;
        }
    }

    /** Миры текущего сервера; сервер сменился — прежние сохраняются и уходят. Пишут поток LSS и поток игры. */
    private static final Map<String, World> worlds = new HashMap<>();
    @Nullable
    private static String server;
    /** Для лога: чанки, колонки с верхом, время в потоке LSS, отказы. */
    private static final AtomicLong received = new AtomicLong(), withTop = new AtomicLong(), nanos = new AtomicLong(), maxNanos = new AtomicLong(),
            failures = new AtomicLong();
    @Nullable
    private static volatile String firstFailure;
    /** Чтение и запись файлов, понижение до грубого — одним фоновым потоком. */
    private static final ExecutorService io = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat("Incoming LSS terrain").setDaemon(true).setPriority(Thread.MIN_PRIORITY).build());

    static {
        // закрыли игру в мире: присланное после последней записи — на диск до выхода JVM (поток записи — демон, и без
        // этого запись обрывалась бы, а метки LSS остались бы — дыры до /lss clearcache)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            flush();
            io.shutdown();
            try {
                if (!io.awaitTermination(30, TimeUnit.SECONDS)) Incoming.LOG.warn("LOD Server Support: map terrain not saved in 30 s at exit");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Incoming LSS terrain save"));
    }
    private static int ticks;
    private static volatile boolean coarsening;
    /** Подписчик, пока API LSS то, под которое собран мод; null — выключен ({@link #disable}). */
    @Nullable
    private static volatile VoxelColumnConsumer consumer;

    private final ClientLevel level;

    LssTerrain(ClientLevel level) {
        this.level = level;
    }

    /**
     * Один раз при запуске клиента: подписка на колонки LSS. Другое API (LSS изменил классы) — {@link LinkageError}
     * тут же: слой LSS выключается одной строкой в лог (см. {@link TerrainTiles#init}).
     */
    static void subscribe() {
        VoxelColumnConsumer c = new VoxelColumnConsumer() {
            @Override
            public void onVoxelColumnReceived(ClientLevel level, ResourceKey<Level> dimension, int chunkX, int chunkZ, VoxelColumnData data) {
                try {
                    receive(level, dimension, chunkX, chunkZ, data);
                } catch (LinkageError e) {
                    disable(e);
                }
            }
        };
        LSSApi.registerColumnConsumer(c);
        consumer = c;
    }

    /**
     * API LSS не то, под которое собран мод (класс или метод изменился, {@link LinkageError} при вызове): слой LSS
     * выключается до перезапуска одной строкой в лог, подписчик снимается — LSS не шлёт ему больше ничего.
     */
    private static void disable(LinkageError e) {
        VoxelColumnConsumer c = consumer;
        consumer = null;
        if (c == null) return;
        Incoming.LOG.warn("LOD Server Support: API differs from the one the mod was built for — far terrain from it is off ({})", e.toString());
        try {
            LSSApi.removeColumnConsumer(c);
        } catch (LinkageError ignored) {
            // и снять нечем: колонки дальше не читаются (consumer == null)
        }
    }

    /**
     * Чанк от сервера, в потоке LSS ({@code LSS-ColumnProcessor}). Исключение не выпускается: LSS счёл бы чанк
     * не принятым и прислал заново всем подписчикам (и Voxy) — отказ только в счётчик.
     */
    private static void receive(ClientLevel level, ResourceKey<Level> dimension, int chunkX, int chunkZ, VoxelColumnData data) {
        if (consumer == null) return;
        long start = System.nanoTime();
        try {
            int[] columns = LssColumns.summarize(minSection(data), sections(data));
            World w = world(level, dimension);
            w.store.put(chunkX, chunkZ, columns);
            w.changed.add(ChunkPos.asLong(chunkX, chunkZ));
            w.dirty.set(true);
            int top = 0;
            for (int c : columns) if (LssColumns.present(c)) top++;
            withTop.addAndGet(top);
        } catch (RuntimeException e) {
            if (failures.getAndIncrement() == 0) {
                firstFailure = e.toString();
                Incoming.LOG.warn("LOD Server Support: chunk ({}, {}) not read for the map", chunkX, chunkZ, e);
            }
        }
        long took = System.nanoTime() - start;
        received.incrementAndGet();
        nanos.addAndGet(took);
        maxNanos.accumulateAndGet(took, Math::max);
    }

    private static int minSection(VoxelColumnData data) {
        int min = Integer.MAX_VALUE;
        for (VoxelColumnData.SectionData s : data.sections()) min = Math.min(min, s.sectionY());
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private static LssColumns.Section[] sections(VoxelColumnData data) {
        int min = minSection(data), max = min - 1;
        for (VoxelColumnData.SectionData s : data.sections()) max = Math.max(max, s.sectionY());
        LssColumns.Section[] out = new LssColumns.Section[max - min + 1];
        for (VoxelColumnData.SectionData s : data.sections()) {
            LevelChunkSection section = s.section();
            if (section == null) continue;
            out[s.sectionY() - min] = new LssColumns.Section() {
                @Override
                public boolean empty() {
                    return section.hasOnlyAir();
                }

                @Override
                public BlockState block(int x, int y, int z) {
                    return section.getBlockState(x, y, z);
                }
            };
        }
        return out;
    }

    /** Сервер, к которому подключён клиент: адрес из списка серверов или папка мира одиночной игры. */
    private static String currentServer() {
        Minecraft mc = Minecraft.getInstance();
        ServerData data = mc.getCurrentServer();
        if (data != null && data.ip != null) return UNSAFE.matcher(data.ip).replaceAll("_");
        IntegratedServer sp = mc.getSingleplayerServer();
        Path root = sp == null ? null : sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
        return "local_" + (root == null ? "world" : UNSAFE.matcher(root.toString()).replaceAll("_"));
    }

    /**
     * Мир измерения у текущего сервера; новый — с чтением файла в фоне. {@code level} — мир клиента, для которого
     * чанк (у LSS — снимок на миг отправки): хэш сида — из пакета входа или возрождения ({@code BiomeManager}).
     */
    private static World world(ClientLevel level, ResourceKey<Level> dimension) {
        String now = currentServer();
        String seed = "world-" + Long.toHexString(level.getBiomeManager().biomeZoomSeed);
        synchronized (worlds) {
            if (!now.equals(server)) {
                flushLocked();
                server = now;
            }
            return worlds.computeIfAbsent(seed + "/" + dimension.location(), k -> {
                World w = new World(now, seed, dimension);
                io.execute(() -> load(w));
                return w;
            });
        }
    }

    private static Path file(World w) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("incoming").resolve("lss-terrain").resolve(w.server).resolve(w.seed)
                .resolve(UNSAFE.matcher(w.dimension.location().toString()).replaceAll("_") + ".bin");
    }

    private static void load(World w) {
        Path f = file(w);
        w.file = f.toString();
        if (!Files.isRegularFile(f)) {
            w.loaded.set(true);
            return;
        }
        long start = System.nanoTime();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f), 1 << 16)))) {
            int n = w.store.read(in);
            w.fresh.set(n > 0);
            Incoming.LOG.info("LOD Server Support: {} chunks of map terrain read from {} in {} ms", n, f, (System.nanoTime() - start) / 1_000_000);
        } catch (IOException | RuntimeException e) {
            Incoming.LOG.warn("LOD Server Support: map terrain file {} not read ({}) — far terrain comes as the server sends it; /lss clearcache asks for all of it again",
                    f, e.toString());
        }
        w.loaded.set(true);
    }

    private static void save(World w) {
        if (!w.dirty.getAndSet(false)) return;
        Path f = file(w), tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try {
            Files.createDirectories(f.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp), 1 << 16) {
                {
                    def.setLevel(Deflater.BEST_SPEED);
                }
            }, 1 << 16))) {
                w.store.write(out);
            }
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            w.dirty.set(true);
            Incoming.LOG.warn("LOD Server Support: map terrain file {} not written", f, e);
        }
    }

    /** Под замком {@link #worlds}: все миры — на диск (в фоне) и из памяти. После выхода JVM запись уже не принимается. */
    private static void flushLocked() {
        for (World w : worlds.values()) {
            if (!io.isShutdown()) io.execute(() -> save(w));
        }
        worlds.clear();
    }

    /** Выход из мира: присланное — на диск, память свободна. */
    static void flush() {
        synchronized (worlds) {
            flushLocked();
            server = null;
        }
    }

    /**
     * Раз в тик, в потоке игры: сохранение по часам; подробных чанков сверх предела — грубыми, всех сверх предела —
     * дальние долой (в фоне).
     */
    static void tick(double playerX, double playerZ) {
        // JVM уже выходит (крюк остановки закрыл поток записи), а тик ещё идёт: задачи не принимаются
        if (io.isShutdown()) return;
        ticks++;
        if (ticks % SAVE_TICKS == 0) {
            synchronized (worlds) {
                for (World w : worlds.values()) io.execute(() -> save(w));
            }
        }
        if (ticks % COARSEN_TICKS != 0 || coarsening) return;
        synchronized (worlds) {
            for (World w : worlds.values()) {
                if (w.store.fullChunks() <= FULL_LIMIT && w.store.chunks() <= TOTAL_LIMIT) continue;
                coarsening = true;
                io.execute(() -> {
                    try {
                        w.store.coarsen(playerX, playerZ, FULL_LIMIT, FULL_KEEP);
                        w.store.trim(playerX, playerZ, TOTAL_LIMIT, TOTAL_KEEP);
                    } finally {
                        coarsening = false;
                    }
                });
            }
        }
    }

    /** Мир этого источника: источник живёт, пока клиент в одном мире ({@link TerrainTiles} заводит новый на смену). */
    @Nullable
    private World mine;

    private World mine() {
        World w = mine;
        return w != null ? w : (mine = world(level, level.dimension()));
    }

    /** Сервер с LSS раздаёт LOD этому клиенту (рукопожатие прошло); иначе слой не нужен. */
    @Override
    public boolean active() {
        if (consumer == null) return false;
        try {
            return LSSApi.isServerEnabled();
        } catch (LinkageError e) {
            disable(e);
            return false;
        }
    }

    /** Колонок этого мира ещё нет ни с сервера, ни с диска. */
    @Override
    public boolean waiting() {
        World w = mine();
        return w.loaded.get() && w.store.chunks() == 0;
    }

    @Override
    public String pendingNote() {
        return "incoming.map.far_pending_lss";
    }

    @Override
    public boolean offThread() {
        return true;
    }

    /** Чтение — из памяти: потоки плиток ничего не ждут. */
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
        LssColumns.Store store = mine().store;
        return new Reader() {
            @Nullable
            @Override
            public Column column(int x, int z) {
                return LssColumns.column(store.get(x, z));
            }

            @Override
            public void close() {}
        };
    }

    @Nullable
    @Override
    public Column peek(int x, int z) {
        return LssColumns.column(mine().store.get(x, z));
    }

    @Override
    public void changes(ClientLevel level, ChunkSink sink) {
        World w = mine();
        if (w.changed.isEmpty()) return;
        for (var it = w.changed.iterator(); it.hasNext(); ) {
            long c = it.next();
            it.remove();
            sink.changed(ChunkPos.getX(c), ChunkPos.getZ(c));
        }
    }

    /** Файл прочитан (один раз за мир): плитки, построенные до него, — без того, что в нём было. */
    @Override
    public boolean invalidated(ClientLevel level) {
        return mine().fresh.getAndSet(false);
    }

    @Override
    public String describe() {
        World w = mine();
        long n = received.get();
        if (consumer == null) return "LSS off (API differs)";
        boolean on;
        int distance;
        try {
            on = LSSApi.isServerEnabled();
            distance = LSSApi.getServerLodDistance();
        } catch (LinkageError e) {
            disable(e);
            return "LSS off (API differs)";
        }
        return String.format(Locale.ROOT, "LSS (server %s, LOD distance %d chunks): chunks received %d, columns with top %d, in LSS thread avg %.0f µs max %.1f ms, "
                        + "failures %d%s; in memory %d chunks (%d detailed), file %s",
                on ? "on" : "off", distance, n, withTop.get(), n == 0 ? 0.0 : nanos.get() / 1e3 / n,
                maxNanos.get() / 1e6, failures.get(), firstFailure == null ? "" : " (first: " + firstFailure + ")", w.store.chunks(), w.store.fullChunks(),
                w.file.isEmpty() ? "—" : w.file);
    }
}
