package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.Detonation;

/**
 * One detonation's ruins across chunks: wave fracture per chunk ({@link Blast}, from original window blocks) and
 * which ruins already applied (old blocks provide neighbors' original world). Chunk plan depends on original surrounding blocks,
 * so advance/on-site plans identical regardless of chunk order.
 * <p>
 * Lives during detonation (flight preparation then ruin queue), releases unneeded data:
 * chunk fracture once all neighbor plans exist; applied ruins' old blocks once entire surrounding
 * 5×5 square ruined (read by neighbor and neighbor-of-neighbor windows).
 */
final class RuinContext {
    /** Ruin inputs (preparation location without ID; actual detonation afterward). */
    final Detonation d;
    /** Chunk fractures: also computed by background tasks ({@link RuinWorkers}), hence shared map. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Blast> blasts = new java.util.concurrent.ConcurrentHashMap<>();
    /** Chunk snapshots (server thread) retained while unfinished plan windows read them. Outside-memory chunks from disk ({@link DiskShots}). */
    private final Long2ObjectOpenHashMap<ChunkShot> shots = new Long2ObjectOpenHashMap<>();
    /**
     * Pre-wave {@code MOTION_BLOCKING} heightmaps (first air) for light-pulse shadow
     * ({@link #lit}) on any thread: in-memory from live chunk (pre-ruin for ruined chunk), others disk.
     * Server-thread writes only, immutable values; missing means no terrain (like unloaded chunk).
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, int[]> heights = new java.util.concurrent.ConcurrentHashMap<>();
    private final Long2ObjectOpenHashMap<Applied> applied = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet planned = new LongOpenHashSet(), stood = new LongOpenHashSet();
    /** Last context use (game time), for queue to forget long-idle contexts. */
    long used;

    /**
     * Applied chunk ruins: plan (old position blocks; null released), pre-replacement {@code MOTION_BLOCKING} heightmap
     * (first air; shadows light pulse that preceded wave).
     */
    record Applied(@Nullable RuinPlan plan, short[] motion) {}

    /**
     * Detonation ruins in {@code level}. World noise derives from world seed, not detonation: patches are terrain properties, so
     * preplan (geometry without detonation seed, {@code NuclearWarhead.geometry}) matches actual-detonation plan
     * for chunk loaded after preparation ruins forgotten, or after restart.
     */
    RuinContext(net.minecraft.server.level.ServerLevel level, Detonation d) {
        this.d = d;
        this.noise = new ua.zentix.incoming.nuclear.model.WorldNoise(level.getSeed(), d.scale());
        this.ruinRadius = d.ruinRadius();
    }

    /** Ruin reach, blocks ({@link Detonation#ruinRadius}): light trace fades at edge ({@code RuinPlanner}). */
    final double ruinRadius;

    /** Detonation world noise for damage curves ({@code RuinPlanner}): burned-grass/scorch/biome-tier patches. */
    final ua.zentix.incoming.nuclear.model.WorldNoise noise;

    @Nullable
    Applied applied(long chunk) {
        return applied.get(chunk);
    }

    /** Snapshot if chunk fully in memory (server thread): cached while valid, otherwise fresh. */
    @Nullable
    ChunkShot shot(ServerLevel level, int cx, int cz) {
        LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
        long key = ChunkPos.asLong(cx, cz);
        if (c == null) {
            // outside memory: disk snapshot if read (and property table not reset since)
            ChunkShot disk = shots.get(key);
            return disk != null && disk.fromDisk() && disk.fresh() ? disk : null;
        }
        Applied a = applied.get(key);
        ChunkShot s = shots.get(key);
        if (s == null || !s.current(c, a)) {
            RuinPlan flash = flashed.get(key);
            // flash layer present but plan forgotten (detonation ruins forgotten/restart): snapshot fire is air
            putShot(key, s = ChunkShot.take(c, a, flash, flash == null && a == null && FlashScorch.marked(c, d.id())));
        }
        return s;
    }

    // ---------------------------------------------------------------- flash layer

    /**
     * Applied chunk flash layers ({@link RuinPlan#applyFlash}, server thread): detonation ruins use pre-flash
     * world; plan checks chunk against it ({@link RuinPlan#current}), snapshots use pre-flash world
     * ({@link ChunkShot#take}). Retained as long as chunk ruins' old blocks ({@link #applied}).
     */
    private final Long2ObjectOpenHashMap<RuinPlan> flashed = new Long2ObjectOpenHashMap<>();
    /** Bytes retained by flash-layer plans ({@link #flashed}). */
    private long flashedBytes;

    /** Applied chunk flash layer; null absent. */
    @Nullable
    RuinPlan flashed(long chunk) {
        return flashed.get(chunk);
    }

    /**
     * Before chunk flash layer (server thread): light-shadow heightmap from pre-flash world (if not already captured).
     *
     * @return cached chunk snapshot valid (remains pre-flash world, {@link #flashed(LevelChunk, RuinPlan, boolean)})
     */
    boolean beforeFlash(ServerLevel level, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        captureHeights(level, pos.x, pos.z);
        ChunkShot s = shots.get(pos.toLong());
        return s != null && !s.fromDisk() && s.current(chunk, applied.get(pos.toLong()));
    }

    /**
     * Flash layer applied (server thread). Previously valid cached snapshot remains pre-flash world, subsequently checked
     * against layered chunk ({@link ChunkShot#relive}): fractures/plans remain valid; otherwise retake snapshot.
     */
    void flashed(LevelChunk chunk, RuinPlan plan, boolean shotCurrent) {
        long key = chunk.getPos().toLong();
        RuinPlan was = flashed.put(key, plan);
        flashedBytes += plan.bytes() - (was == null ? 0 : was.bytes());
        ChunkShot s = shots.get(key);
        if (s == null) return;
        if (shotCurrent) s.relive(chunk, plan);
        else dropShot(key);
    }

    /**
     * Snapshots for chunk flash layer (server thread): chunk plus fully in-memory neighbors (edge face scorch
     * checks neighbor; absent means exposed face), from detonation ruin cache, later reused for ruins. Chunk in memory.
     */
    Grid flashGrid(ServerLevel level, ChunkPos centre, boolean background) {
        ChunkShot[] g = new ChunkShot[Grid.SIDE * Grid.SIDE];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (level.getChunkSource().getChunkNow(centre.x + dx, centre.z + dz) == null) continue;
                g[(dz + Grid.R) * Grid.SIDE + dx + Grid.R] = shot(level, centre.x + dx, centre.z + dz);
            }
        }
        captureBand(level, centre);
        RuinPlanner.ensureOwnStates();
        Blast.PropsView view = background ? Blast.publish() : Blast.SERVER;
        return new Grid(this, centre, level.getMinBuildHeight(), level.getMaxBuildHeight(), g, view,
                IncomingConfig.SERVER.nukeBlockDamage.get(), IncomingConfig.SERVER.nukeTreeFall.get(), IncomingConfig.SERVER.nukeFires.get());
    }

    /**
     * Disk-read chunk (server thread): snapshot if no in-memory snapshot or chunk not fully in memory now
     * (in-memory snapshot would not be used anyway), with heightmap for light shadow. Missing disk heightmaps
     * computed here from blocks ({@link DiskShots.Read#primed}).
     */
    void putDisk(ServerLevel level, DiskShots.Read read) {
        long key = read.pos().toLong();
        ChunkShot now = shots.get(key);
        // shadow heights only with snapshot: retained live snapshot already captured in-memory heights
        if (now == null || now.fromDisk() || level.getChunkSource().getChunkNow(read.pos().x, read.pos().z) == null) {
            DiskShots.Read primed = read.primed(Blast.SERVER);
            Applied a = applied.get(key);
            RuinPlan flash = flashed.get(key);
            // flash layer present, plan forgotten: snapshot fire is air like in-memory chunk ({@link #shot})
            putShot(key, ChunkShot.fromDisk(primed, a, flash, flash == null && a == null && FlashScorch.marks(primed.flashLayer(), d.id())));
            putHeights(key, primed.motion());
        }
    }

    /** Plan snapshot exists: fully in-memory chunk or disk read (property table not reset since). */
    boolean shotAvailable(ServerLevel level, long chunk) {
        if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) != null) return true;
        ChunkShot s = shots.get(chunk);
        return s != null && s.fromDisk() && s.fresh();
    }

    /**
     * Maximum disk snapshots taken by server thread per {@link #requestWindow}: 5×5 window; no-disk-heightmap
     * chunk counts as several ({@link DiskShots.Read#cost}).
     */
    private static final int PUTS_PER_WINDOW = (2 * RuinPlanner.REACH + 1) * (2 * RuinPlanner.REACH + 1);
    /** Window neighbors being read from disk ({@link #requestWindow}), plus read results not yet taken by server thread. */
    private final LongOpenHashSet reading = new LongOpenHashSet();
    private final java.util.concurrent.ConcurrentLinkedQueue<DiskShots.Read> reads = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** Window neighbors not whole on disk (unfinished generation): solid window mass like world edge. */
    private final LongOpenHashSet absent = new LongOpenHashSet();
    /** Detonation log: disk window neighbors read, those not whole on disk. */
    int windowReads, windowAbsent;

    /**
     * Background-plan window for in-memory chunk (server thread): radius-{@link RuinPlanner#REACH} square neighbors not fully
     * in memory read from disk ({@link DiskShots}) without world loading, so player view edge need not await neighbor
     * tickets (otherwise edge chunks wait 200 ticks, reach player intact). Neighbor not whole
     * on disk (absent, generating, unparseable) means absent from window ({@code null} in
     * {@link #grid}), solid mass like world edge; final for context, never await generation.
     * Reason logged in detonation “disk windows” line. In-memory neighbor below full loading also read
     * from disk: plan misses player edits since last save.
     *
     * @return neighbors still reading (0 means full window, plan may be submitted)
     */
    int requestWindow(ServerLevel level, ChunkPos pos) {
        return requestWindow(level, pos, RuinWorkers.Priority.NEAR);
    }

    /** Same, parse read neighbors at ruin-pool {@code priority}. */
    int requestWindow(ServerLevel level, ChunkPos pos, RuinWorkers.Priority priority) {
        // read results limited to window per call: server thread builds disk snapshot; while paused, background
        // reads continue, accumulating unlimited results, rest taken by subsequent windows. Only this thread takes them:
        // peeked chunk same as later polled chunk
        int put = 0;
        for (DiskShots.Read r; (r = reads.peek()) != null && (put == 0 || put + r.cost() <= PUTS_PER_WINDOW); put += r.cost()) {
            reads.poll();
            long key = r.pos().toLong();
            reading.remove(key);
            windowReads++;
            if (r.skip() != null) {
                if (absent.add(key)) windowAbsent++;
            } else {
                putDisk(level, r);
            }
        }
        int missing = 0;
        DiskShots.Format format = null;
        for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) {
            for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                long key = ChunkPos.asLong(pos.x + dx, pos.z + dz);
                if (absent.contains(key) || shotAvailable(level, key)) continue;
                missing++;
                if (reading.add(key)) {
                    if (format == null) format = DiskShots.Format.of(level);
                    DiskShots.read(format, new ChunkPos(key), priority).thenAccept(reads::add);
                }
            }
        }
        return missing;
    }

    /**
     * Chunk will have no plan (preparation: beyond heavy-zone edge or not from disk); neighbors release snapshots without awaiting its plan.
     * If later planned (post-wave ruin queue), snapshots retaken.
     */
    void noPlan(long chunk) {
        if (planned.add(chunk)) {
            ChunkPos pos = new ChunkPos(chunk);
            forget(pos, 1, planned, k -> blasts.remove(k));
            forget(pos, RuinPlanner.REACH, planned, this::dropShot);
        }
    }

    /** Like {@link #noPlan} before surrounding snapshots exist (nothing to release). */
    void markNoPlan(long chunk) {
        planned.add(chunk);
    }

    /** Chunk plan ready time (game time), −1 if not yet: for “far rings” line. */
    long doneAt(long chunk) {
        return doneAt.getOrDefault(chunk, -1L);
    }

    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap doneAt = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    /** Game time from last {@link #expire}. */
    private long now;

    private long shotBytes;

    private void putShot(long key, ChunkShot shot) {
        ChunkShot was = shots.put(key, shot);
        shotBytes += shot.bytes - (was == null ? 0 : was.bytes);
    }

    void dropShot(long key) {
        ChunkShot was = shots.remove(key);
        if (was != null) shotBytes -= was.bytes;
    }

    /** For tests: discard snapshots/fractures, as detonation ruins starting chunks anew. */
    void dropShotsForTest() {
        shots.clear();
        shotBytes = 0;
        blasts.clear();
    }

    /** Whether chunk snapshot exists (memory/disk). */
    boolean hasShot(long chunk) {
        return shots.containsKey(chunk);
    }

    /** Retained snapshot count (detonation memory). */
    int shotCount() {
        return shots.size();
    }

    // ---------------------------------------------------------------- light-pulse shadow

    /** Disk chunk heightmap (server thread): first {@code MOTION_BLOCKING} air, if absent. */
    void putHeights(long chunk, int[] firstAir) {
        heights.putIfAbsent(chunk, firstAir);
    }

    /** In-memory heightmap (server thread), if absent: ruined chunk uses higher of pre-ruin/live values. */
    private void captureHeights(ServerLevel level, int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        if (heights.containsKey(key)) return;
        LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
        if (c == null) return;
        Applied a = applied.get(key);
        int[] h = new int[256];
        for (int col = 0; col < 256; col++) {
            int now = c.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, col & 15, col >> 4) + 1;
            h[col] = a == null ? now : Math.max(now, a.motion[col]);
        }
        heights.put(key, h);
    }

    /**
     * Heightmaps between epicenter/chunk (server thread, before plan): rays to chunk pass overhead. Three-chunk-wide
     * lane along line from epicenter.
     */
    private void captureBand(ServerLevel level, ChunkPos to) {
        int bx = net.minecraft.util.Mth.floor(d.burst().x) >> 4, bz = net.minecraft.util.Mth.floor(d.burst().z) >> 4;
        int steps = Math.max(Math.abs(to.x - bx), Math.abs(to.z - bz));
        for (int i = 0; i <= steps; i++) {
            int cx = bx + (int) Math.round((to.x - bx) * (double) i / Math.max(1, steps));
            int cz = bz + (int) Math.round((to.z - bz) * (double) i / Math.max(1, steps));
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) captureHeights(level, cx + dx, cz + dz);
        }
    }

    /** First pre-wave air over {@code MOTION_BLOCKING} (any thread); chunk absent from maps means world bottom. */
    private int height(int x, int z, int minY) {
        int[] h = heights.get(ChunkPos.asLong(x >> 4, z >> 4));
        return h == null ? minY : h[(z & 15) << 4 | (x & 15)];
    }

    /** Light pulse at position as pre-wave world saw it (any thread). */
    double lit(net.minecraft.core.BlockPos pos, int minY) {
        Vec3 p = Vec3.atCenterOf(pos).add(0, 0.5, 0);
        return ThermalShadow.visible(d.burst(), p, (x, z) -> height(x, z, minY)) ? d.fluence(p) : 0;
    }

    /** Whether fireball sees point as pre-wave world saw it (any thread): same shadow heightmaps as {@link #lit}. */
    boolean sees(Vec3 p, int minY) {
        return ThermalShadow.visible(d.burst(), p, (x, z) -> height(x, z, minY));
    }

    /**
     * Bytes retained by detonation snapshots, heightmaps, flash-layer plans (detonation memory, toward plan cap
     * {@link NuclearPrep#PLANS_CAP}).
     */
    long shotBytes() {
        return shotBytes + heights.size() * 1100L + flashedBytes;
    }

    /**
     * Everything for world-free chunk planning: surrounding 5×5 snapshots ({@link RuinPlanner#REACH}: chunk and
     * neighbor windows), property table/settings captured on server thread. Uncached fractures computed by plan
     * itself on its execution thread.
     */
    record Grid(RuinContext ctx, ChunkPos centre, int minY, int maxY, ChunkShot[] shots, Blast.PropsView view, boolean blockDamage, boolean treeFall,
                boolean fires) {
        static final int R = RuinPlanner.REACH, SIDE = 2 * R + 1;

        @Nullable
        ChunkShot at(int dx, int dz) {
            return shots[(dz + R) * SIDE + dx + R];
        }

        /** Chunk-window snapshots offset {@code dx, dz} from center (at most 1). */
        ChunkShot[] window(int dx, int dz) {
            ChunkShot[] out = new ChunkShot[9];
            for (int k = 0; k < 9; k++) out[k] = at(dx + k % 3 - 1, dz + k / 3 - 1);
            return out;
        }

        RuinWindow windowOf(int dx, int dz) {
            return new RuinWindow(new ChunkPos(centre.x + dx, centre.z + dz), minY, maxY, window(dx, dz), view);
        }

        /** Offset chunk fracture: detonation cache if built from same snapshots, otherwise rebuild/cache. */
        Blast blast(int dx, int dz) {
            ChunkPos pos = new ChunkPos(centre.x + dx, centre.z + dz);
            ChunkShot[] w = window(dx, dz);
            Blast b = ctx.blasts.get(pos.toLong());
            if (b != null && b.stamp().same(RuinWindow.Stamp.of(w))) return b;
            b = Blast.solve(new RuinWindow(pos, minY, maxY, w, view), ctx.d, blockDamage, treeFall);
            ctx.blasts.put(pos.toLong(), b);
            return b;
        }

        /** Light pulse at position as pre-wave world saw it. */
        double lit(net.minecraft.core.BlockPos pos) {
            return ctx.lit(pos, minY);
        }

        /** Whether fireball sees point as pre-wave world saw it. */
        boolean sees(Vec3 p) {
            return ctx.sees(p, minY);
        }

        /** Offset chunk fracture already cached from same snapshots. */
        boolean hasBlast(int dx, int dz) {
            Blast b = ctx.blasts.get(ChunkPos.asLong(centre.x + dx, centre.z + dz));
            return b != null && b.stamp().same(RuinWindow.Stamp.of(window(dx, dz)));
        }
    }

    /**
     * Plan snapshots (server thread): radius-{@link RuinPlanner#REACH} square fully loaded. Property table
     * published after snapshots, only table read by background plan ({@code view} for server thread).
     */
    Grid grid(ServerLevel level, ChunkPos centre, boolean background) {
        ChunkShot[] g = new ChunkShot[Grid.SIDE * Grid.SIDE];
        for (int dz = -Grid.R; dz <= Grid.R; dz++) {
            for (int dx = -Grid.R; dx <= Grid.R; dx++) g[(dz + Grid.R) * Grid.SIDE + dx + Grid.R] = shot(level, centre.x + dx, centre.z + dz);
        }
        captureBand(level, centre);
        RuinPlanner.ensureOwnStates();
        Blast.PropsView view = background ? Blast.publish() : Blast.SERVER;
        return new Grid(this, centre, level.getMinBuildHeight(), level.getMaxBuildHeight(), g, view,
                IncomingConfig.SERVER.nukeBlockDamage.get(), IncomingConfig.SERVER.nukeTreeFall.get(), IncomingConfig.SERVER.nukeFires.get());
    }

    /**
     * For tests: chunk window from cached snapshots only (disk-read, {@link #putDisk}), for background solver:
     * {@link #shot} would replace in-memory chunk's disk snapshot with memory snapshot.
     */
    Grid cachedGridForTest(ServerLevel level, ChunkPos centre) {
        ChunkShot[] g = new ChunkShot[Grid.SIDE * Grid.SIDE];
        for (int dz = -Grid.R; dz <= Grid.R; dz++) {
            for (int dx = -Grid.R; dx <= Grid.R; dx++) g[(dz + Grid.R) * Grid.SIDE + dx + Grid.R] = shots.get(ChunkPos.asLong(centre.x + dx, centre.z + dz));
        }
        captureBand(level, centre);
        RuinPlanner.ensureOwnStates();
        return new Grid(this, centre, level.getMinBuildHeight(), level.getMaxBuildHeight(), g, Blast.publish(),
                IncomingConfig.SERVER.nukeBlockDamage.get(), IncomingConfig.SERVER.nukeTreeFall.get(), IncomingConfig.SERVER.nukeFires.get());
    }

    /**
     * Single snapshot for ground trace without ruins ({@link RuinPlanner#scorch}, server thread): trace needs no
     * neighbors, window contains only chunk.
     */
    Grid scorchGrid(ServerLevel level, ChunkShot shot, Blast.PropsView view) {
        ChunkShot[] g = new ChunkShot[Grid.SIDE * Grid.SIDE];
        g[Grid.R * Grid.SIDE + Grid.R] = shot;
        return new Grid(this, shot.pos(), level.getMinBuildHeight(), level.getMaxBuildHeight(), g, view,
                IncomingConfig.SERVER.nukeBlockDamage.get(), IncomingConfig.SERVER.nukeTreeFall.get(), IncomingConfig.SERVER.nukeFires.get());
    }

    /**
     * Chunk-window fractures (chunk/loaded neighbors), at most one per call (server thread): cold neighbor
     * fracture is separate work unit, not nine in one. true all cached/valid (one-unit chunk plan),
     * false one fracture computed.
     */
    boolean blastsReady(ServerLevel level, ChunkPos pos) {
        Grid g = grid(level, pos, false);
        for (int k = 0; k < 9; k++) {
            int dx = k % 3 - 1, dz = k / 3 - 1;
            if (g.at(dx, dz) == null || g.hasBlast(dx, dz)) continue;
            g.blast(dx, dz);
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- background plans

    /**
     * Background chunk plan: task (fractures, collapse, completion all background), snapshot chunk
     * ({@code identityHashCode}; 0 disk snapshot), submission time or latest queued tick while waiting
     * (game time for {@link #TASK_AGE_LIMIT}) and pool priority.
     */
    private record Task(java.util.concurrent.Future<RuinPlan> future, int chunkId, long since, RuinWorkers.Priority priority) {}

    /** Background-plan timeout, ticks: abandon task beyond, build on server thread. */
    static final int TASK_AGE_LIMIT = 600;

    private final Long2ObjectOpenHashMap<Task> tasks = new Long2ObjectOpenHashMap<>();
    /** Chunks whose background plan failed, stalled or stale at replacement ({@link #planOnServer}): plan on server thread. */
    private final LongOpenHashSet failed = new LongOpenHashSet();
    private int failures;

    /**
     * Submit plan to background threads (server thread; chunk plus radius-{@link RuinPlanner#REACH} square fully
     * loaded): snapshot now, fractures/collapse/completion background. false too many tasks
     * ({@link RuinWorkers#admit}), retry later.
     */
    boolean submit(ServerLevel level, LevelChunk chunk) {
        return submit(level, chunk, RuinWorkers.Priority.NEAR);
    }

    /** Same at ruin-pool {@code priority}. */
    boolean submit(ServerLevel level, LevelChunk chunk, RuinWorkers.Priority priority) {
        return submit(level, chunk.getPos(), System.identityHashCode(chunk), priority);
    }

    /**
     * Submit outside-memory chunk plan to background (server thread): radius-{@link RuinPlanner#REACH} square
     * snapshots read from disk ({@link #putDisk}) or captured from in-memory chunks. Distant prebuilt ruins,
     * lowest pool priority.
     */
    boolean submitDisk(ServerLevel level, ChunkPos pos) {
        return submit(level, pos, 0, RuinWorkers.Priority.FAR);
    }

    private boolean submit(ServerLevel level, ChunkPos pos, int chunkId, RuinWorkers.Priority priority) {
        long key = pos.toLong();
        if (tasks.containsKey(key)) return true;
        // running and ready-uncollected tasks bounded: ready plan retains memory. Player-visible tasks use only
        // their priority capacity: tasks preparation handed to detonation do not block them
        if (!RuinWorkers.admit(priority) || priority != RuinWorkers.Priority.SEEN && tasks.size() >= 2 * RuinWorkers.capacity()) return false;
        Grid g = grid(level, pos, true);
        if (g.at(0, 0) == null) throw new IllegalStateException("no snapshot of chunk " + pos);
        tasks.put(key, new Task(RuinWorkers.submit(() -> {
            RuinWorkers.checkStop();
            Collapse c = Collapse.solve(g);
            RuinWorkers.checkStop();
            return RuinPlanner.finish(g, c);
        }, priority), chunkId, level.getGameTime(), priority));
        return true;
    }

    /** Chunk has running or ready background plan. */
    boolean running(long chunk) {
        return tasks.containsKey(chunk);
    }

    /**
     * Promote chunk plan still waiting at lower pool priority to {@code priority}
     * (server thread; {@link RuinWorkers#promote}).
     */
    void promote(long chunk, RuinWorkers.Priority priority) {
        Task t = tasks.get(chunk);
        if (t == null || t.priority.compareTo(priority) <= 0) return;
        java.util.concurrent.Future<RuinPlan> moved = RuinWorkers.promote(t.future, priority);
        if (moved != null) tasks.put(chunk, new Task(moved, t.chunkId, t.since, priority));
    }

    /** Background plans running or ready/uncollected. */
    int tasks() {
        return tasks.size();
    }

    /** Background chunk plan ready (collect in one work unit). */
    boolean done(long chunk) {
        Task t = tasks.get(chunk);
        return t != null && t.future.isDone();
    }

    /** Background plan failed/stalled: build on server thread. */
    boolean failed(long chunk) {
        return failed.contains(chunk);
    }

    /**
     * Background plan stale at replacement, plan-position blocks changed during build (server thread): fresh
     * plan from current chunk on server thread. New snapshot plan could stale again (redstone clock,
     * Create mechanism), leaving chunk awaiting ruins indefinitely.
     */
    void planOnServer(long chunk) {
        failed.add(chunk);
    }

    /**
     * Background plans running over {@link #TASK_AGE_LIMIT} ticks (server thread, every tick): abandon task
     * ({@code cancel(true)} interrupts solver thread if stuck), add chunk to {@link #failed}, plan on
     * server thread. Deadline from work start: distant task awaiting higher-priority tasks in pool queue is
     * not stuck.
     */
    void expire(long now) {
        this.now = now;
        if (tasks.isEmpty()) return;
        var it = tasks.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            var e = it.next();
            Task t = e.getValue();
            if (t.future.isDone()) continue;
            if (!RuinWorkers.started(t.future)) {
                // still queued
                if (t.since != now) e.setValue(new Task(t.future, t.chunkId, now, t.priority));
                continue;
            }
            if (now - t.since < TASK_AGE_LIMIT) continue;
            t.future.cancel(true);
            it.remove();
            failed.add(e.getLongKey());
            RuinWorkers.expired();
            Incoming.LOG.warn("Ruins for detonation #{}: background plan for chunk {} not ready in {} ticks — abandoned, building on the server thread",
                    d.id(), new ChunkPos(e.getLongKey()), TASK_AGE_LIMIT);
        }
    }

    /**
     * Ready background plan (server thread). null if building, chunk reloaded since (resubmitted),
     * or plan failed ({@link #failed}). Disk-snapshot plan also valid for chunk loaded since:
     * replacement checks old plan-position states and rescans columns ({@link RuinPlan#apply}).
     */
    @Nullable
    RuinPlan collect(ServerLevel level, LevelChunk chunk) {
        Task t = tasks.get(chunk.getPos().toLong());
        if (t != null && t.chunkId != 0 && t.future.isDone() && System.identityHashCode(chunk) != t.chunkId) {
            // chunk unloaded/reloaded: snapshots from different chunk
            tasks.remove(chunk.getPos().toLong());
            submit(level, chunk, t.priority);
            return null;
        }
        return collect(chunk.getPos());
    }

    /** Ready background plan wherever chunk is (server thread): see {@link #collect(ServerLevel, LevelChunk)}. */
    @Nullable
    RuinPlan collect(ChunkPos pos) {
        long key = pos.toLong();
        Task t = tasks.get(key);
        if (t == null || !t.future.isDone()) return null;
        tasks.remove(key);
        RuinPlan plan;
        try {
            plan = t.future.get();
        } catch (java.util.concurrent.ExecutionException e) {
            failed.add(key);
            // solver error repeats across many chunks: log first occurrences
            if (failures++ < 3) Incoming.LOG.error("Ruins for detonation #{}: background plan for chunk {} failed; building on the server thread", d.id(), pos, e.getCause());
            return null;
        } catch (InterruptedException | java.util.concurrent.CancellationException e) {
            return null;
        }
        planned(pos);
        doneAt.put(key, now);
        return plan;
    }

    /** Chunk unloaded: live-chunk background plan/snapshot invalid (disk-snapshot plan remains valid). */
    void forget(long chunk) {
        Task t = tasks.get(chunk);
        if (t != null && t.chunkId != 0) {
            tasks.remove(chunk);
            t.future.cancel(false);
        }
        dropShot(chunk);
    }

    /** Background plans no longer needed (cancel, world unload): abandon tasks. */
    void cancelTasks() {
        for (Task t : tasks.values()) t.future.cancel(false);
        tasks.clear();
    }

    /** Chunk plan built: release fractures needed only by surrounding plans. */
    void planned(ChunkPos pos) {
        planned.add(pos.toLong());
        forget(pos, 1, planned, k -> blasts.remove(k));
        forget(pos, RuinPlanner.REACH, planned, this::dropShot);
    }

    /** Chunk ruins applied from plan. */
    void applied(LevelChunk chunk, RuinPlan plan, short[] motion) {
        ChunkPos pos = chunk.getPos();
        applied.put(pos.toLong(), new Applied(plan, motion));
        planned.add(pos.toLong());
        stood.add(pos.toLong());
        forget(pos, 1, stood, k -> blasts.remove(k));
        forget(pos, RuinPlanner.REACH, stood, this::dropShot);
        forget(pos, 2, stood, k -> {
            Applied a = applied.get(k);
            if (a != null && a.plan != null) applied.put(k, new Applied(null, a.motion));
            // pre-flash world no longer needed: surrounding windows built
            RuinPlan f = flashed.remove(k);
            if (f != null) flashedBytes -= f.bytes();
        });
    }

    /** Chunks within radius r of pos whose entire radius-r square in {@code done} go into {@code drop}. */
    private static void forget(ChunkPos pos, int r, LongOpenHashSet done, java.util.function.LongConsumer drop) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = pos.x + dx, z = pos.z + dz;
                boolean all = true;
                for (int ex = -r; all && ex <= r; ex++) {
                    for (int ez = -r; all && ez <= r; ez++) all = done.contains(ChunkPos.asLong(x + ex, z + ez));
                }
                if (all) drop.accept(ChunkPos.asLong(x, z));
            }
        }
    }

}
