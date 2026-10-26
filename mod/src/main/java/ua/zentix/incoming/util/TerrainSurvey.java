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
 * World terrain survey without chunk loading — {@link Terrain.Source#DISK} and {@link Terrain.Source#GENERATOR}
 * for {@link Terrain.Allowed#RELIEF}: flight sees terrain along its entire path, not only ready chunks.
 * <ul>
 *   <li>In-memory unready chunk (awaiting unload, downgraded at visibility edge, {@link Terrain#inMemory}): its heightmap
 *   immediately, the same data that will go to disk.</li>
 *   <li>Disk chunk: its file's {@code Heightmaps} field, read by chunk I/O thread ({@code chunkScanner}, same
 *   as {@link DiskStatus#scan} headers), without parsing/loading the chunk. Also supplies not-yet-written data
 *   ({@code IOWorker.pendingWrites}); unloaded chunk writes enter its queue before subsequent reads.
 *   Only chunks with final blocks ({@link DiskStatus.State#blocksFinal}) in 1.18+ format qualify.</li>
 *   <li>Chunk absent/partially generated on disk: generator terrain ({@code ChunkGenerator.getBaseHeight}, no
 *   trees or structures, at least sea level), on a Minecraft background thread: grid nodes every {@link #GENERATOR_STEP}
 *   blocks; column uses maximum of its cell's four nodes. Upper estimate for smooth slopes, lower for
 *   forests (generator knows no crowns): approximation, not a map.</li>
 * </ul>
 * Tick waits for neither disk nor generator: first chunk query requests survey, returns null (“unknown yet”); answer
 * arrives at tick end ({@link #publish}). In-memory chunks answer themselves; on leaving memory ({@code ChunkEvent.Unload}),
 * leave their heightmap with the survey — same as disk data: no answer gap even for one tick, regardless of
 * when vanilla unloads them (in spare tick time), and old disk answers cannot replace it. Per-world state,
 * unsaved ({@link ModAttachments#TERRAIN_SURVEY}).
 */
public final class TerrainSurvey {
    /** Generator terrain-node spacing, blocks: ~3 ms per node, average ~4 new nodes per chunk. */
    public static final int GENERATOR_STEP = 8;
    /**
     * Reads submitted to chunk I/O at once per world: same queue loads player chunks, so hundreds of reads
     * would delay them. Other requests wait in their own queue, never dropped: otherwise neighboring flights
     * and disk speed would decide whose request is accepted.
     */
    public static final int MAX_READS = 64;
    /** Maximum queued requests (memory; even a salvo over unexplored land does not reach this many). */
    static final int MAX_QUEUED = 8192;
    /** Generator chunks computed at once: one shared Minecraft thread, leaving others for worldgen; next starts immediately on completion. */
    static final int GENERATOR_WORKERS = 1;
    /** Maximum maps of each kind (unloads occur without flights too; memory must be bounded from the start). */
    static final int MAX_KNOWN = 8192;
    /** Ticks of unknown chunk after generator error; queries do not extend retry inhibition. */
    static final int RETRY_TICKS = 200;
    /** Forget chunks not queried for this many ticks. */
    static final int FORGET_TICKS = 6000;
    /** Clear node memory when generator node count exceeds this (≈1.5 MB). */
    static final int MAX_POINTS = 65_536;

    /** Known heightmaps: map kind → chunk → columns. */
    private final Map<Heightmap.Types, Map<Long, Heights>> known = new EnumMap<>(Heightmap.Types.class);
    /** Requested: map kind → chunk → request token (requests whose token was removed are discarded). */
    private final Map<Heightmap.Types, Map<Long, Order>> pending = new EnumMap<>(Heightmap.Types.class);
    /** Disk reads in flight (for {@link #settle}). */
    private final Queue<CompletableFuture<?>> reads = new ConcurrentLinkedQueue<>();
    /** Chunks without disk heightmaps awaiting generator (I/O enqueues, background thread computes, {@link #pump}). */
    private final Orders toGenerate = new Orders();
    /** Generator computations in flight (for {@link #settle}). */
    private final Queue<CompletableFuture<?>> generating = new ConcurrentLinkedQueue<>();
    private final AtomicInteger workers = new AtomicInteger();
    private final AtomicInteger reading = new AtomicInteger();
    /** Requests awaiting read (server thread): sensor queries first, preorders afterward. */
    private final Orders toRead = new Orders();

    /** Tagged chunk request (awaiting read or generator). */
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

    /** Two FIFOs with promotion of already waiting requests; I/O and generator threads also access the queue. */
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
    /** Generator terrain nodes: (x / step, z / step) → first air. */
    private final Map<Long, Integer> points = new ConcurrentHashMap<>();

    /** For {@link ModAttachments#TERRAIN_SURVEY}. */
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
     * First air in chunk columns and its source; {@code used} is last query tick. Null {@code y}: disk chunk
     * has final blocks but no usable heightmap (pre-1.18 format or length mismatching world height): unknown terrain,
     * generator unsuitable — world built by another generator or changed since.
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


    /** Background-thread request answer: publish to known maps at world-tick end ({@link #publish}). */
    private record Answer(Order order, @Nullable int[] y, Terrain.Source source, boolean retry) {}

    /** Answers received since previous tick end. */
    private final Queue<Answer> answers = new ConcurrentLinkedQueue<>();

    /**
     * Survey column surface (first air above {@code type} heightmap); null if still unknown (survey
     * requested). {@link Terrain#estimate} reads ready chunks itself before survey. Server thread only; no chunk loading.
     */
    @Nullable
    public Terrain.Surface at(ServerLevel level, Heightmap.Types type, int x, int z) {
        Terrain.View view = view(level, type, x >> 4, z >> 4);
        return view == null ? null : view.at(x, z);
    }

    /** The same sources and requests as {@link #at}, without resolving the chunk or creating Surface per column. */
    @Nullable
    Terrain.View view(ServerLevel level, Heightmap.Types type, int cx, int cz) {
        long chunk = ChunkPos.asLong(cx, cz);
        if (level.dimensionType().hasCeiling()) return null;
        asked.add(type);
        LevelChunk memory = Terrain.inMemory(level, chunk);
        if (memory != null) return new Terrain.View((x, z) -> memory.getHeight(type, x & 15, z & 15) + 1, Terrain.Source.DISK);
        Heights h = cached(type, chunk, level.getGameTime());
        if (h != null) {
            h.used = level.getGameTime();
            return h.y == null ? null : new Terrain.View((x, z) -> h.y[(x & 15) + (z & 15) * 16], h.source);
        }
        request(level, type, chunk, true);
        return null;
    }

    /**
     * Preorder chunks along horizontal segment {@code from → to} (plus one neighbor on each side:
     * corridor over path is wider than a line), so answers are ready when projectile arrives. Server thread only.
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

    /** One live request per chunk and map kind; total limit includes waiting for generator answer. */
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
        // Half the slots reserved for sensor queries: preorders cannot displace a newly needed column.
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

    /** Submit queued requests to I/O while fewer than {@code limit} reads are in flight (server thread). */
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
                // Read failure does not prove chunk absence: generator could invent terrain for someone else's world.
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

    /** Heightmap field in chunk file ({@code ChunkSerializer.HEIGHTMAPS_TAG}). */
    private static final String HEIGHTMAPS = "Heightmaps";

    /**
     * Request answer (any thread) goes to queue: world-tick end publishes it to known maps ({@link #publish}). Otherwise
     * a mid-tick answer would reach one sensor-corridor column but not its same-chunk neighbor, making flight depend
     * on disk speed.
     */
    void finish(Order o, @Nullable int[] y, Terrain.Source source, boolean retry) {
        answers.add(new Answer(o, y, source, retry));
    }

    /** Answers become visible together at tick end; old requests cannot replace a departing chunk's map. */
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
     * First air in 256 columns from chunk-file fields ({@link DiskStatus#fields} and {@code type} heightmap); null if
     * map absent/untrustworthy: nonfinal blocks, pre-1.18 format (heights relative to a different world bottom), length
     * mismatching world height. Packing like {@code Heightmap}: {@code ceil(log2(world height + 1))} bits per column, value:
     * first air minus world bottom, column {@code x + 16 z}.
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
     * Generator for chunks without disk heightmaps: at most {@link #GENERATOR_WORKERS} at once; next starts immediately
     * after previous completion (from any thread).
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
                // Enqueue answer before freeing worker: settle cannot miss an unpublished result.
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

    /** World-tick end: publish incoming answers to known maps ({@link #publish}), forget old data. */
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
     * Generator terrain in chunk columns: {@link #GENERATOR_STEP} grid nodes at corners and side midpoints (shared with
     * neighboring chunks via node cache); column uses largest node of its cell. Background thread.
     */
    int[] generate(ServerLevel level, long chunk) {
        return cells(ChunkPos.getX(chunk) << 4, ChunkPos.getZ(chunk) << 4, (x, z) -> point(level, x, z));
    }

    /** Chunk columns starting at {@code x0, z0}, using {@code node(x, z)}: largest of four cell nodes. */
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

    /** Generator terrain node: first air at least at sea level, like {@link Terrain.Source#GENERATOR}. */
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
     * Chunk leaves memory before save: always leaves a ready MOTION_BLOCKING map, even without prior flights.
     * Cancel all old requests regardless of chunk completeness or map presence; late answers cannot revive them.
     * Read only already computed maps of full chunks, without modifying departing chunk. Memory capped at MAX_KNOWN.
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

    /** Fresh in-memory chunk is more accurate than all old answers or file requests. */
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
     * Await all world requests on this thread (GameTest flight scenarios: answer in request tick, regardless of disk
     * timing, otherwise flight would diverge from baseline). Not called in game.
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
     * Forget chunk (GameTest: unloaded chunk left its map with survey, but check queries disk file). Not called
     * in game.
     */
    public void forget(long chunk) {
        for (Map<Long, Heights> m : known.values()) m.remove(chunk);
    }

    /** Requests in flight (GameTest). */
    public int pendingCount() {
        int n = 0;
        for (Map<Long, Order> m : pending.values()) n += m.size();
        return n;
    }
}
