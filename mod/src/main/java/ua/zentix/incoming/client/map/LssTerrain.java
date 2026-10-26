package ua.zentix.incoming.client.map;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.ServerCacheKey;

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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Terrain from LOD Server Support (LSS, MIT) through its public client API
 * ({@code LSSApi.registerColumnConsumer}): a server with LSS sends chunks throughout its LOD radius (512 chunks by
 * default), and the mod receives each one through the same stream LSS feeds to Voxy, Xaero and JourneyMap. A subscriber
 * makes LSS download even without Voxy. Column tops are computed immediately on the LSS thread
 * ({@link LssColumns#summarize}, tens of microseconds per chunk, no locks or waits: its thread never stalls), retained
 * in memory and read instantly from any thread ({@link #peek}): rays and sound need not wait for map tiles.
 * <p>
 * LSS client remembers received chunks (its own disk timestamp cache), requesting only changes on next login:
 * subscribers store received data themselves (Voxy in its database). Columns are therefore stored on disk too,
 * {@code incoming/lss-terrain/<server>/<dimension>.bin} in the game folder, every {@link #SAVE_TICKS} ticks if anything
 * arrived, and on world exit. No file (LSS installed before the mod or file lost) leaves distant terrain empty until
 * the server sends chunks again: {@code /lss clearcache} requests everything anew.
 * <p>
 * LSS does not enable on the singleplayer integrated server (its client checks), except a world opened to LAN:
 * distant terrain there comes from Distant Horizons if installed. Class loads only with LSS installed (see {@link TerrainTiles}).
 */
final class LssTerrain implements TerrainSource {
    /** At most this many detailed chunks (1 KB) in memory (~64 MB, ~2.3 km circle); distant ones coarse (64 bytes). */
    static final int FULL_LIMIT = 65_536;
    /** Coarsen down to this fraction of the limit, avoiding work every few seconds. */
    private static final int FULL_KEEP = FULL_LIMIT * 9 / 10;
    /**
     * At most this many total chunks (~80 MB coarse; default server LOD radius, 512 chunks, is ~820 thousand):
     * discard the farthest from the player above that. LSS does not know and will not resend them itself (a hole far
     * from play until a chunk change or {@code /lss clearcache}); its own timestamp cache is likewise limited to 2 million.
     */
    static final int TOTAL_LIMIT = 1 << 20;
    private static final int TOTAL_KEEP = TOTAL_LIMIT * 9 / 10;
    private static final int SAVE_TICKS = 2400;
    private static final int COARSEN_TICKS = 200;
    /** Complete tile reread every 5 minutes without events (changes arrive by event, {@link #changes}). */
    private static final long REFRESH_NS = 300_000_000_000L;
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");

    /**
     * Columns for one server (address and world seed hash, as in LSS's timestamp cache key: LSS resends a different
     * world at the same address, and old terrain must not remain) and dimension; received since last tick; file read flag.
     */
    static final class World {
        final ServerCacheKey server;
        final String seed;
        final ResourceKey<Level> dimension;
        final LssColumns.Store store = new LssColumns.Store();
        final Set<Long> changed = ConcurrentHashMap.newKeySet();
        /** Something arrived since file write; file read; read data not yet given to tiles ({@link #invalidated}). */
        final AtomicBoolean dirty = new AtomicBoolean(), loaded = new AtomicBoolean(), fresh = new AtomicBoolean();
        volatile String file = "";
        /** Selected server folder, file thread only; old folder also used for subsequent writes. */
        @Nullable
        Path directory;

        World(ServerCacheKey server, String seed, ResourceKey<Level> dimension) {
            this.server = server;
            this.seed = seed;
            this.dimension = dimension;
        }
    }

    /** Current server's worlds; on server change, save and release previous ones. Written by LSS and game threads. */
    private static final Map<String, World> worlds = new HashMap<>();
    @Nullable
    private static String server;
    /** For logs: chunks, columns with surface, time on LSS thread, failures. */
    private static final AtomicLong received = new AtomicLong(), withTop = new AtomicLong(), nanos = new AtomicLong(), maxNanos = new AtomicLong(),
            failures = new AtomicLong();
    @Nullable
    private static volatile String firstFailure;
    /** File reads and writes, coarsening, all on one background thread. */
    private static final ExecutorService io = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat("Incoming LSS terrain").setDaemon(true).setPriority(Thread.MIN_PRIORITY).build());

    static {
        // Game closed while in a world: save data received since the last write before JVM exit (writer is a daemon;
        // otherwise the write would stop while LSS timestamps remained, leaving holes until /lss clearcache).
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
    /** Subscriber while LSS API matches the build; null if disabled ({@link #disable}). */
    @Nullable
    private static volatile VoxelColumnConsumer consumer;

    private final ClientLevel level;

    LssTerrain(ClientLevel level) {
        this.level = level;
    }

    /**
     * Once at client startup: subscribe to LSS columns. Different API (LSS classes changed) throws {@link LinkageError}
     * here immediately: disable the LSS layer with one log line (see {@link TerrainTiles#init}).
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
     * LSS API differs from the build (class or method changed, {@link LinkageError} on call): disable LSS layer until
     * restart with one log line; unsubscribe so LSS sends it nothing further.
     */
    private static void disable(LinkageError e) {
        VoxelColumnConsumer c = consumer;
        consumer = null;
        if (c == null) return;
        Incoming.LOG.warn("LOD Server Support: API differs from the one the mod was built for — far terrain from it is off ({})", e.toString());
        try {
            LSSApi.removeColumnConsumer(c);
        } catch (LinkageError ignored) {
            // Cannot unsubscribe either: no further column reads (consumer == null).
        }
    }

    /**
     * Chunk from server on the LSS thread ({@code LSS-ColumnProcessor}). Catch exceptions: LSS would treat the chunk
     * as unaccepted and resend to all subscribers (including Voxy); only count the failure.
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

    /**
     * Dimension world on the current server; a new one reads its file in the background. {@code level} is the chunk's
     * client world (LSS snapshot at send time): seed hash comes from the login or respawn packet ({@code BiomeManager}).
     */
    private static World world(ClientLevel level, ResourceKey<Level> dimension) {
        ServerCacheKey now = ServerCacheKey.current();
        String seed = "world-" + Long.toHexString(level.getBiomeManager().biomeZoomSeed);
        synchronized (worlds) {
            if (!now.key().equals(server)) {
                flushLocked();
                server = now.key();
            }
            return worlds.computeIfAbsent(seed + "/" + dimension.location(), k -> {
                World w = new World(now, seed, dimension);
                submit(() -> load(w));
                return w;
            });
        }
    }

    private static Path file(World w) throws IOException {
        if (w.directory == null) {
            Path root = Minecraft.getInstance().gameDirectory.toPath().resolve("incoming").resolve("lss-terrain");
            w.directory = w.server.directory(root);
            if (!w.directory.equals(root.resolve(w.server.key()))) {
                Incoming.LOG.info("LOD Server Support: using legacy map terrain directory {} for server {}", w.directory, w.server.key());
            }
        }
        return w.directory.resolve(w.seed)
                .resolve(UNSAFE.matcher(w.dimension.location().toString()).replaceAll("_") + ".bin");
    }

    private static void load(World w) {
        Path f = null;
        long start = System.nanoTime();
        try {
            f = file(w);
            w.file = f.toString();
            if (!Files.isRegularFile(f)) return;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f), 1 << 16)))) {
                int n = w.store.read(in);
                w.fresh.set(n > 0);
                Incoming.LOG.info("LOD Server Support: {} chunks of map terrain read from {} in {} ms", n, f, (System.nanoTime() - start) / 1_000_000);
            }
        } catch (IOException | RuntimeException e) {
            Incoming.LOG.warn("LOD Server Support: map terrain file {} not read ({}) — far terrain comes as the server sends it; /lss clearcache asks for all of it again",
                    f == null ? w.server.key() : f, e.toString());
        } finally {
            w.loaded.set(true);
        }
    }

    private static void save(World w) {
        if (!w.dirty.getAndSet(false)) return;
        Path f = null;
        try {
            // Folder lookup failed during read: finish reading old terrain first after access is restored.
            if (w.directory == null) load(w);
            f = file(w);
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
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
            Incoming.LOG.warn("LOD Server Support: map terrain file {} not written", f == null ? w.server.key() : f, e);
        }
    }

    /**
     * Submit work to the file thread. JVM exiting (shutdown hook closed the thread while tick or LSS thread still runs)
     * means no task needed: the hook already wrote everything present.
     */
    private static void submit(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException e) {
            // Game exit.
        }
    }

    /** Under the {@link #worlds} lock: all worlds to disk (background) and out of memory. No writes accepted after JVM exit. */
    private static void flushLocked() {
        for (World w : worlds.values()) {
            submit(() -> save(w));
        }
        worlds.clear();
    }

    /** World exit: received data to disk, memory released. */
    static void flush() {
        synchronized (worlds) {
            flushLocked();
            server = null;
        }
    }

    /**
     * Once per tick on the game thread: timed saves; detailed chunks above the limit become coarse, and farthest
     * total chunks above the limit are discarded (background).
     */
    static void tick(double playerX, double playerZ) {
        ticks++;
        if (ticks % SAVE_TICKS == 0) {
            synchronized (worlds) {
                for (World w : worlds.values()) submit(() -> save(w));
            }
        }
        if (ticks % COARSEN_TICKS != 0 || coarsening) return;
        synchronized (worlds) {
            for (World w : worlds.values()) {
                if (w.store.fullChunks() <= FULL_LIMIT && w.store.chunks() <= TOTAL_LIMIT) continue;
                coarsening = true;
                submit(() -> {
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

    /** This source's world: source lives while the client stays in one world ({@link TerrainTiles} replaces it on change). */
    @Nullable
    private World mine;

    private World mine() {
        World w = mine;
        return w != null ? w : (mine = world(level, level.dimension()));
    }

    /** Server with LSS distributes LODs to this client (handshake complete); otherwise no layer needed. */
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

    /** No columns for this world yet, from server or disk. */
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

    /** Read from memory: tile threads never wait. */
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

    /** File read (once per world): tiles built before it lack its contents. */
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
