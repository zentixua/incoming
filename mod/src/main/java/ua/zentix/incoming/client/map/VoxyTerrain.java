package ua.zentix.incoming.client.map;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.HandleFailures;

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
 * Terrain from Voxy's own client LOD database (NeoForge 1.21.1: Voxy 0.2.16 via Roxy): everything Voxy has seen
 * (client chunks throughout play in this world), received from a LOD Server Support server, and imported by the
 * player ({@code /voxy import current}: entire singleplayer save). Distant terrain thus works in singleplayer,
 * where LSS does not, and without Distant Horizons.
 * <p>
 * Voxy has no API (ARR licence: call its code, never copy): read its classes by reflection only, no mixins, through
 * the same path as LSS ({@code compat/VoxyTerrainProbe}, MIT): {@code WorldIdentifier.ofEngineNullable(Level)} is
 * the Voxy world (null if Voxy does not hold it), {@code WorldEngine.acquireIfExists(level, x, y, z)} gives a 32³-voxel
 * section or null, {@code WorldSection.copyDataTo}, {@code release()} immediately after copying (otherwise section
 * cannot unload), {@code acquireRef/releaseRef} around reads (world cannot close during them), {@code Mapper.getBlockId}
 * and {@code getBlockStateFromBlockId} give the voxel block. Section absent from Voxy memory means database read on
 * the calling thread, so background threads only, never the game thread.
 * <p>
 * Voxy levels (voxel 1, 2, 4, 8, 16 blocks) match map tile levels, so a level L tile reads level L sections
 * ({@link #open(ClientLevel, int)}): 2×2 section columns per tile at any level, rather than (2^L)² regions as in DH.
 * Read columns become surface summaries ({@link VoxyColumns}, 4 KB) in memory: {@link #peek} answers instantly
 * (rays, sound, nuke). To keep rays from waiting for the map, background scan around the player to {@link #SCAN_RADIUS}
 * reads level 4 (16 blocks) every {@link #RESCAN_NS}. Voxy provides no change events (its only
 * {@code setDirtyCallback} slot is used by its renderer): a changed reread summary notifies overlying tiles itself
 * ({@link #changes}). Different Voxy classes or methods mean one log line and no source.
 */
final class VoxyTerrain implements TerrainSource {
    /** Level scanned around the player for rays and sound: 16-block voxel, ray step no smaller. */
    static final int SCAN_LEVEL = 4;
    /** Scan range around the player, blocks: default far-flight and blast range, and default Voxy LOD range (~8 km). */
    static final int SCAN_RADIUS = 8192;
    /** At most this many summaries in memory (4 KB each, ~32 MB): discard farthest from the player above that. */
    static final int LIMIT = 8192;
    /** Minimum scan interval; restart only if player moved beyond {@link #SCAN_MOVE} or summaries became stale. */
    private static final long SCAN_PERIOD_NS = 30_000_000_000L;
    private static final double SCAN_MOVE = 512;
    /** Scan rereads older summaries (distant craters and ruins enter Voxy's database when it sees them). */
    private static final long RESCAN_NS = 60_000_000_000L;
    /** Tile takes a summary from memory if younger than this (just read by a neighbouring tile or scan). */
    private static final long FRESH_NS = 5_000_000_000L;
    /** Complete tile reread every 2 minutes (scan-detected changes arrive earlier, {@link #changes}). */
    private static final long REFRESH_NS = 120_000_000_000L;
    /** Minimum Voxy world check interval: world appears after login, disappears on exit or Voxy restart. */
    private static final long RESOLVE_NS = 1_000_000_000L;

    /** Voxy 0.2.16 methods; null if absent, different classes, or read broke at runtime ({@link #disable}). */
    @Nullable
    private static volatile Access access;
    /** For logs: section columns and sections read, time, failures. */
    private static final AtomicLong columnsRead = new AtomicLong(), sectionsRead = new AtomicLong(), nanos = new AtomicLong(),
            maxNanos = new AtomicLong(), failures = new AtomicLong();
    @Nullable
    private static volatile String firstFailure;
    /** One background thread scans around the player across all worlds. */
    private static final ExecutorService scanner = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat("Incoming Voxy terrain").setDaemon(true).setPriority(Thread.MIN_PRIORITY).build());
    /** Section buffer (32768 voxels, 256 KB) per thread. */
    private static final ThreadLocal<long[]> buffer = ThreadLocal.withInitial(() -> new long[VoxyColumns.VOLUME]);

    /** Reflection into Voxy classes, read only. */
    static final class Access {
        final MethodHandle ofEngineNullable, acquireIfExists, copyDataTo, release, acquireRef, releaseRef, isLive, mapper, blockId, stateOf;

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
            isLive = l.unreflect(engine.getMethod("isLive")).asType(MethodType.methodType(boolean.class, Object.class));
            mapper = l.unreflect(engine.getMethod("getMapper")).asType(toObject);
            blockId = l.unreflect(mapperClass.getMethod("getBlockId", long.class)).asType(MethodType.methodType(int.class, long.class));
            Class<?> state = mapperClass.getMethod("getBlockStateFromBlockId", int.class).getReturnType();
            if (!BlockState.class.isAssignableFrom(state)) throw new NoSuchMethodException("Mapper.getBlockStateFromBlockId returns " + state.getName());
            stateOf = l.unreflect(mapperClass.getMethod("getBlockStateFromBlockId", int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class));
        }
    }

    /**
     * At client startup: Voxy installed and all classes and methods found. If not, no source (one log line),
     * game continues as without Voxy.
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

    /** Voxy changed: methods exist but invocation differs; disable source until restart with one log line. */
    private static void disable(Throwable e) {
        if (access == null) return;
        access = null;
        Incoming.LOG.warn("Voxy terrain: reading Voxy failed ({}) — far terrain from Voxy is off", e.toString());
    }

    /** Column-top summary of a level {@code level} section column: read time, whether any column exists. */
    record Section(int[] columns, long readAt, boolean any) {}

    /**
     * Current client Voxy world and everything read from it: a different Voxy world (exit, Voxy restarted its
     * instance) gets a new object; reads started for the old world store results in the old object.
     */
    static final class Engine {
        final Object engine, mapper;
        final Map<Long, Section> cache = new ConcurrentHashMap<>();
        /** Block information by Voxy ID ({@link VoxyColumns#info}); 0 if not yet inspected. */
        volatile int[] infos = new int[1024];
        final AtomicBoolean any = new AtomicBoolean();

        Engine(Object engine, Object mapper) {
            this.engine = engine;
            this.mapper = mapper;
        }
    }

    /** Client world (null in unit tests: fake Voxy provides its own world). */
    @Nullable
    private final Level level;
    /** World height bounds: which Voxy sections to read. */
    private final int minY, maxY;
    @Nullable
    private volatile Engine engine;
    /** Last Voxy world lookup time; whether ever looked up (no clock comparison before first: nanoTime is arbitrary). */
    private long resolvedAt;
    private boolean resolvedOnce;
    /** Chunks ({@code ChunkPos.asLong}) beneath changed reread summaries: rebuild their tiles. */
    private final Set<Long> changed = ConcurrentHashMap.newKeySet();
    private volatile boolean scanning, closed;
    /** Scan completed (section columns visited, duration); where and when last started. */
    private volatile boolean scanned;
    private volatile int lastScanColumns;
    private volatile long lastScanNanos, scanStarted;
    private double scanX = Double.NaN, scanZ = Double.NaN;

    VoxyTerrain(ClientLevel level) {
        this(level, level.getMinBuildHeight(), level.getMaxBuildHeight());
    }

    VoxyTerrain(@Nullable Level level, int minY, int maxY) {
        this.level = level;
        this.minY = minY;
        this.maxY = maxY;
    }

    /** Summary key: level and section column. */
    static long key(int level, int sx, int sz) {
        return (long) level << 60 | ((long) sx & 0x3FFFFFFFL) << 30 | (long) sz & 0x3FFFFFFFL;
    }

    /** Voxy world for this client world: game thread, at most once per {@link #RESOLVE_NS}. */
    @Nullable
    private Engine resolve() {
        Access a = access;
        if (a == null) return null;
        long now = System.nanoTime();
        if (resolvedOnce && now - resolvedAt < RESOLVE_NS) return engine;
        resolvedOnce = true;
        resolvedAt = now;
        try {
            Object e = (Object) a.ofEngineNullable.invokeExact(level);
            Engine was = engine;
            if (e == null) engine = null;
            else if (was == null || was.engine != e) engine = new Engine(e, (Object) a.mapper.invokeExact(e));
        } catch (Exception | LinkageError t) {
            fail(t, null);
        } catch (Throwable t) {
            throw HandleFailures.propagate(t);
        }
        return engine;
    }

    /**
     * Voxy call failure: changed structure ({@link LinkageError}, wrong type) disables source; Voxy world closed
     * during read ({@link IllegalStateException}, Voxy reports {@code isLive() == false}) means forget and resolve
     * again; other failures (including other {@code IllegalStateException} in a live world) counted, first logged.
     */
    private void fail(Throwable t, @Nullable Engine in) {
        if (t instanceof LinkageError || t instanceof WrongMethodTypeException || t instanceof ClassCastException) {
            disable(t);
            return;
        }
        if (t instanceof Error err) throw err;
        if (t instanceof IllegalStateException && in != null && !live(in)) {
            if (engine == in) engine = null;
            return;
        }
        if (failures.getAndIncrement() == 0) {
            firstFailure = t.toString();
            Incoming.LOG.warn("Voxy terrain: a section of its LOD database not read", t);
        }
    }

    /** Whether Voxy world is live (not closed): if query fails, treat as closed. */
    private static boolean live(Engine e) {
        Access a = access;
        if (a == null) return false;
        try {
            return (boolean) a.isLive.invokeExact(e.engine);
        } catch (Exception | LinkageError t) {
            return false;
        } catch (Throwable t) {
            throw HandleFailures.propagate(t);
        }
    }

    /** Block {@code id} information in Voxy world {@code e}: 0 if unknown (Voxy still appending its list, failure). */
    private static int info(Access a, Engine e, int id) {
        int[] t = e.infos;
        if (id < t.length && t[id] != 0) return t[id];
        int v;
        try {
            Object state = (Object) a.stateOf.invokeExact(e.mapper, id);
            if (!(state instanceof BlockState s)) return 0;
            v = VoxyColumns.info(s);
        } catch (RuntimeException ex) {
            // Voxy block list grows on its thread without a lock: an ID not yet visible is air; ask later.
            return 0;
        } catch (Throwable ex) {
            throw HandleFailures.propagate(ex);
        }
        synchronized (e) {
            t = e.infos;
            if (id >= t.length) e.infos = t = Arrays.copyOf(t, Math.max(id + 1, t.length * 2));
            t[id] = v;
        }
        return v;
    }

    /**
     * Level {@code lvl} section column from Voxy's database on the calling (background) thread; null if unread
     * (failure, Voxy world closed). Cache summary in memory; changes rebuild overlying tiles.
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
        } catch (Exception | LinkageError t) {
            fail(t, e);
            return null;
        } catch (Throwable t) {
            throw HandleFailures.propagate(t);
        }
        int[] columns = s.finish();
        boolean any = false;
        for (int c : columns) any |= c != 0;
        Section now = new Section(columns, System.nanoTime(), any);
        Section was = e.cache.put(key(lvl, sx, sz), now);
        if (was != null && !Arrays.equals(was.columns, columns)) {
            markChanged(lvl, sx, sz);
            // Finer summaries below it predate the change (crater, ruins): rays and sound ({@link #peek}) prefer detailed data.
            dropFiner(e.cache, lvl, sx, sz);
        }
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
            throw HandleFailures.propagate(t);
        }
    }

    /** Column summary younger than {@code maxAge} from memory, otherwise read. */
    @Nullable
    private Section section(Engine e, int lvl, int sx, int sz, long maxAge) {
        Section s = e.cache.get(key(lvl, sx, sz));
        if (s != null && System.nanoTime() - s.readAt < maxAge) return s;
        Section read = read(e, lvl, sx, sz);
        return read != null ? read : s;
    }

    /** Evict summaries finer than {@code lvl} under its section column (sx, sz); reread when needed. */
    static void dropFiner(Map<Long, Section> cache, int lvl, int sx, int sz) {
        for (int l = 0; l < lvl; l++) {
            int k = lvl - l, n = 1 << k;
            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) cache.remove(key(l, (sx << k) + x, (sz << k) + z));
            }
        }
    }

    /** Chunks under the section column, every 64 blocks (level 0 tile): rebuild their tiles at all levels. */
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

    /** Voxy database reads run on our threads: as many concurrent threads, as many parallel section reads. */
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

    /** Column (x, z) in its level {@code level} section-column summary. */
    static int index(int level, int x, int z) {
        return ((z >> level) & 31) << 5 | (x >> level) & 31;
    }

    /** Most detailed summary in memory where column is known. */
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
     * Once per tick on the game thread: background scan around the player, at most once per {@link #SCAN_PERIOD_NS},
     * earlier if the player moved beyond {@link #SCAN_MOVE}.
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

    /** Scan: level {@link #SCAN_LEVEL} section columns in circle {@link #SCAN_RADIUS}, nearest first; then evict excess. */
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

    /** Level {@code level} section columns (x, z) with centres in a {@code radius}-block circle, nearest first. */
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

    /** More than {@code limit} summaries: discard farthest from (x, z) until nine tenths remain. */
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

    /** Scan finished with nothing nearby in Voxy's database: Voxy has not seen this place. */
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
