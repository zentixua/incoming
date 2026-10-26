package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.util.DiskStatus;
import ua.zentix.incoming.work.WorkScheduler;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Prebuilt ruins during ICBM flight (minute and a half): in-memory chunks within ruin radius ({@link Detonation#ruinRadius})
 * get ruin plans ({@link RuinPlanner}): snapshots on server tick, fractures/collapse on background threads
 * ({@link RuinWorkers}), completion from live chunk back on tick under budget. When wave reaches chunk, only
 * write plan into sections ({@link RuinPlan#apply}): destruction follows front instead of chasing it. Chunks
 * loaded during flight picked up every {@link #REFRESH} ticks; preparation loads nothing.
 * <p>
 * After detonation, zone behind wave: heavy zone (ground pressure from 5 psi) loads in 5×5 squares following front, putting ruins
 * on disk and in Distant Horizons LODs beyond player locations. Each square chunk loads if ruin
 * window ({@link RuinPlanner#REACH}) whole on disk ({@link DiskStatus.State#WHOLE}: headers read in background via
 * {@code chunkScanner}) or memory, same window as disk plan. Strike generates no world: vanilla loads such chunks
 * without generation (chunk whole, neighbors ready, {@code ChunkGenerationTask.canLoadWithoutGeneration}).
 * 1.17 chunk with below-zero retrogen gets disk plan ({@link DiskStatus.State#FINAL}), but zone does not
 * load it: plan awaits player or distant LOD copy ({@link FarLods}). Each chunk held by own non-ticking load ticket
 * ({@link AreaLoader}, {@code ticks = false}, level 33: no ticking even under external tick count), nearest-target squares
 * first, at most {@link #HELD_TILES} at once; queue ({@link ScarQueue}) builds chunk ruins; release square
 * once ruins applied. Unsaved: restart rebuilds preparation from
 * scheduled strike.
 * <p>
 * Burst location/ground match detonation ({@link NuclearWarhead#geometry}); plan depends neither on time
 * nor detonation seed, only location, yield, scale, and matches detonation there; such detonation
 * takes preparation location, ground and visibility ({@link #planned}).
 */
public final class NuclearPrep {
    private static final TicketType<UUID> TYPE = TicketType.create("incoming_nuclear_prep", Comparator.<UUID>naturalOrder());
    /** Ground pressure threshold for prebuilt ruins. */
    static final double PSI = 5;
    /** Squares: 5×5 chunks whose ruins built together. */
    private static final int TILE_RADIUS = 2;
    private static final int TILE = TILE_RADIUS * 2 + 1;
    /** All square chunks: bit {@code (dx + 2) * 5 + (dz + 2)}. */
    private static final int ALL = (1 << TILE * TILE) - 1;
    /**
     * Square chunk ruin windows: square plus {@link RuinPlanner#REACH} margins; load chunk if window whole on disk.
     * Do not hold square margins: ruin queue ({@link ScarQueue}) reads edge-chunk windows from disk
     * ({@link RuinContext#requestWindow}); ready plans need no neighbors (except {@link RuinPlan#neighbourRadius}, holding
     * their own). Margined 9×9 square means 81 chunks per 25 ruins, tripling behind-wave zone load time (~23 chunks
     * per second, player lag up to 6514 ticks). Skip individual non-whole-window chunk, not entire square:
     * otherwise disk plans near explored-world edge and partial chunks would await zone completion.
     */
    private static final int WINDOWS_REACH = TILE_RADIUS + RuinPlanner.REACH, WINDOWS = 2 * WINDOWS_REACH + 1;
    /** Squares released per tick: each means 25 chunk unloads/disk writes this server tick. */
    private static final int RELEASE_PER_TICK = 2;
    /** Concurrent loading squares (not all at once: players need world loads too). */
    private static final int LOADING_TILES = 6;
    /** Concurrent disk chunk-header reads. */
    private static final int SCANS = 64;
    /** Minimum ticks before detonation to start preparation. */
    private static final int MIN_LEAD = 100;
    /** Maximum held squares behind wave (loading/ruins queued): server memory. */
    private static final int HELD_TILES = 6;
    /** Ticks between picking up flight-loaded chunks and logging behind-wave zone progress. */
    private static final int REFRESH = 40, ZONE_REPORT = 600;
    /** Maximum held-square duration after detonation, ticks. */
    private static final int ZONE_HOLD = 12_000;
    /**
     * Prebuilt-ruin memory cap, bytes: ready plans, chunk snapshots (memory/disk), in-flight reads, background
     * thread buffers. Beyond cap new reads await plans releasing snapshots; no further plans built above cap.
     */
    static final long PLANS_CAP = 512L << 20;
    /** Concurrent disk reads and estimated memory per read (parsed city chunk tens of KB). */
    private static final int READS = 64;
    private static final long READ_BYTES = 96L << 10;
    /** Allowed detonation-location difference from plan, blocks. */
    private static final double SAME_PLACE = 8;

    /** For {@link #heapTight}: previous heap sample number and consecutive above-limit samples. */
    private long lastMeasured;
    private int heapStrikes;

    private enum TileState { SCAN, SKIP, WAIT, LOADING, READY }

    private static final class Tile {
        final ChunkPos centre;
        TileState state = TileState.SCAN;
        /** Square ruin-window chunks with disk result not received/requested. */
        int unknown = WINDOWS * WINDOWS;
        /** Square chunks (bits {@link #bit}) with non-whole-disk chunk in ruin window: zone does not load them. */
        int partial;
        /** Square chunks whose tickets held (bits {@link #bit}). */
        int held;
        /** Tickets taken with all chunks already in memory: square loads nothing, uses no load slot. */
        boolean resident;
        /** For {@link NukeDiag}: ticket acquisition/all-chunks-full game times, −1 if not yet. */
        long heldAt = -1, readyAt = -1;

        Tile(ChunkPos centre) {
            this.centre = centre;
        }

        /** Chunks zone brings into world: ruin window whole on disk. */
        int loads() {
            return ALL & ~partial;
        }

        static int bit(int dx, int dz) {
            return 1 << (dx + TILE_RADIUS) * TILE + dz + TILE_RADIUS;
        }

        /** Square chunk by center offset. */
        long chunk(int dx, int dz) {
            return ChunkPos.asLong(centre.x + dx, centre.z + dz);
        }

        /** Square chunk ticket: unique per chunk, level 33 full loading only. */
        AreaLoader.Area area(int strike, int dx, int dz) {
            ChunkPos c = new ChunkPos(centre.x + dx, centre.z + dz);
            return new AreaLoader.Area(TYPE, c, 0, new UUID(strike, c.toLong()), false);
        }

        /** Acquire square chunk tickets from {@code chunks} mask. */
        void hold(ServerLevel level, int strike, int chunks) {
            held = chunks;
            tickets(level, strike, true);
        }

        /** Release tickets held by square. */
        void release(ServerLevel level, int strike) {
            tickets(level, strike, false);
            held = 0;
        }

        private void tickets(ServerLevel level, int strike, boolean hold) {
            AreaLoader areas = StrikeWorld.get(level).areas();
            for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
                for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) {
                    if ((held & bit(dx, dz)) == 0) continue;
                    if (hold) areas.hold(level, area(strike, dx, dz));
                    else areas.release(level, area(strike, dx, dz));
                }
            }
        }
    }

    private static final class Prep {
        final int strike;
        final long detonateTime;
        @Nullable
        Detonation geometry;
        /** Zone layout ({@link #layout}) while built in background; null if ready or not started. */
        @Nullable
        CompletableFuture<Layout> layout;
        /** Heavy-zone chunks nearest first (zone behind wave). */
        long[] order = new long[0];
        /** In-memory chunks within ruin radius nearest first: prebuild plans. */
        final LongArrayList todo = new LongArrayList();
        int nextTodo;
        final LongOpenHashSet considered = new LongOpenHashSet();
        /** All chunks ever in memory within ruin radius during flight (denominator in “ready”/“pre-built ruins” lines). */
        final LongOpenHashSet everInMemory = new LongOpenHashSet();
        /** Plans for in-memory chunks (near preparation). */
        int nearPlans;
        /** Chunks with background plans running. */
        final LongArrayList submitted = new LongArrayList();
        long nextRefresh;
        /** Bytes in ready plans. */
        long planBytes;
        boolean capped;
        long nextZoneReport;
        /** Chunks beyond heavy-zone edge within {@link RuinPlanner#REACH}: no prebuilt plans. */
        long[] edge = new long[0];
        List<Tile> tiles = new ArrayList<>();
        /** Squares needing chunk result (own chunk or neighbor ring). */
        Long2ObjectOpenHashMap<List<Tile>> askedBy = new Long2ObjectOpenHashMap<>();
        /** Memory tight: stop prebuilding, behind-wave squares load only chunks with ready plans. */
        boolean heapStop;
        /** Disk-read queue and results (from I/O thread). */
        LongArrayList toScan = new LongArrayList();
        int nextScan, scanning;
        final ConcurrentLinkedQueue<long[]> scanned = new ConcurrentLinkedQueue<>();
        /**
         * For {@link NukeDiag}: square admission stop reasons in ticks (slots, square awaiting disk, wave
         * not arrived, nothing to acquire, detonation forgotten), released squares: count, ticket → full (sum/max),
         * full → release, wave → ticket.
         */
        final long[] diagStops = new long[5], diagDone = new long[8];
        /** Times ready square not released because neighbor-waiting chunks lack holds (whole detonation). */
        long diagRefused;
        long nextIoReport;
        final Long2ObjectOpenHashMap<RuinPlan> plans = new Long2ObjectOpenHashMap<>();
        /** All-chunk ruins (neighbor fractures): transferred to detonation with plans. */
        @Nullable
        RuinContext ruins;
        final LongArrayList planned = new LongArrayList();
        /** Handed to this detonation ID (tickets still held); -1 still flying. */
        int detonation = -1;
        /** Ruins no longer needed: release a few squares per tick. */
        boolean draining;
        long handedOff;
        boolean announced;

        // ---- phase 2: heavy zone outside memory, disk in wave-arrival order
        @Nullable
        DiskShots.Format format;
        /** In-flight disk reads (submitted, result not yet parsed) and background results. */
        final LongOpenHashSet reading = new LongOpenHashSet();
        final ConcurrentLinkedQueue<DiskShots.Read> reads = new ConcurrentLinkedQueue<>();
        /** Chunks absent as fully generated disk chunks: no preplans for them/neighbors. */
        final LongOpenHashSet unreadable = new LongOpenHashSet();
        /** Unread reasons ({@link DiskShots.Read#skip} before colon), for “ready” line. */
        final Object2IntOpenHashMap<String> unreadWhy = new Object2IntOpenHashMap<>();
        /** {@link #order} cursors: submitted window reads and plans. */
        int nextRead, nextFar;
        /** Chunks whose disk-snapshot plans background threads build. */
        final LongArrayList farRunning = new LongArrayList();
        int farPlans, diskShots, farSkipped;
        /** {@link #order} cursor: wave-reached chunks submitted to distant LODs ({@link FarLods}). */
        int nextLod;
        /** Heavy-zone chunks outside memory at detonation, for “far rings” line. */
        @Nullable
        long[] farAtDetonation;
        /** Zone LOD copies from ready disk plans, into DH with front. */
        final FrontLods lods;

        Prep(int strike, long detonateTime) {
            this.strike = strike;
            this.detonateTime = detonateTime;
            this.lods = new FrontLods(strike);
        }
    }

    private final List<Prep> preps = new ArrayList<>();
    /** Strikes whose preparation failed: do not restart (otherwise error logged every tick). */
    private final java.util.Set<Integer> failed = new java.util.HashSet<>();
    private final WorkClock clock = WorkClock.decaying(0.5);

    /** Total prebuilt ruins across strikes (tests, status). */
    public int plannedChunks() {
        return preps.stream().mapToInt(p -> p.plans.size()).sum();
    }

    /** No preparations: release ruin-solver buffers. */
    public boolean idle() {
        return preps.isEmpty();
    }

    /** Chunk unloaded: background plan unnecessary. */
    void forget(long chunk) {
        for (Prep p : preps) {
            p.submitted.rem(chunk);
            // before detonation preparation ruins outside NuclearWorld: preparation removes task itself
            if (p.ruins != null && p.detonation < 0) p.ruins.forget(chunk);
            p.considered.remove(chunk);
        }
    }

    /** Prebuilt-ruin memory: plans, snapshots, in-flight reads, thread buffers. */
    private static long memory(ServerLevel level, Prep p) {
        return p.planBytes + p.ruins.shotBytes() + p.reading.size() * READ_BYTES + RuinWorkers.bufferBytes(level.getHeight());
    }

    /** Squares held by tickets (tests). */
    public int heldTiles() {
        return (int) preps.stream().flatMap(p -> p.tiles.stream()).filter(t -> t.state == TileState.LOADING || t.state == TileState.READY).count();
    }

    // ---------------------------------------------------------------- tick

    /**
     * Budgeted preparation: own deadline (shared-budget fraction {@link WorkScheduler#PREP_SHARE}) and nuclear-lane deadline.
     *
     * @param shared shared tick clock (already started)
     */
    public void tick(ServerLevel level, NuclearEvents events, ScarQueue scars, WorkClock shared) {
        long now = level.getGameTime();
        long cap = WorkScheduler.prepCapNanos();
        // new strikes: preparation from launch
        for (NuclearEvents.ScheduledStrike s : events.scheduled()) {
            if (!IncomingConfig.SERVER.nukeBlockDamage.get()) break;
            if (s.detonateTime() - now < MIN_LEAD || failed.contains(s.id()) || preps.stream().anyMatch(p -> p.strike == s.id())) continue;
            // one area, one preparation: second strike at same places prebuilds no ruins (single-zone memory)
            if (preps.stream().anyMatch(p -> p.geometry != null && p.detonation < 0
                    && Math.hypot(p.geometry.burst().x - s.target().x, p.geometry.burst().z - s.target().z) < 2 * heavyRadius(p.geometry) + 64)) continue;
            preps.add(new Prep(s.id(), s.detonateTime()));
        }
        clock.start(Math.max(1_000_000L, cap));
        for (Iterator<Prep> it = preps.iterator(); it.hasNext(); ) {
            Prep p = it.next();
            try {
                if (p.draining) {
                    p.plans.clear();
                    p.lods.clear(level);
                    // before detonation preparation tasks not handed off: abandon
                    if (p.ruins != null && p.detonation < 0) p.ruins.cancelTasks();
                    if (releaseSome(level, p, RELEASE_PER_TICK) == 0) it.remove();
                    continue;
                }
                if (p.detonation >= 0) {
                    // handed to detonation: behind-wave zone loads squares, releases when ruins applied
                    if (!p.heapStop && heapTight()) stopForHeap(p);
                    Detonation d = events.detonations().stream().filter(x -> x.id() == p.detonation).findFirst().orElse(null);
                    behindWave(level, p, scars, d, now, shared);
                    if (d != null && p.ruins != null) far(level, p, shared);
                    if (d != null) farLods(level, p, d, now);
                    frontLods(level, p, now);
                    boolean zoneDone = p.tiles.stream().noneMatch(t -> t.state != TileState.SKIP);
                    if (zoneDone && !scars.hasPrepared(p.detonation) || now - p.handedOff > ZONE_HOLD) {
                        zoneReport(p, scars, d, now);
                        scars.dropPrepared(p.detonation, zone(p));
                        p.draining = true;
                    }
                    continue;
                }
                NuclearEvents.ScheduledStrike s = events.scheduled().stream().filter(x -> x.id() == p.strike).findFirst().orElse(null);
                if (s == null) {
                    // cancelled or detonation differs from planned location
                    p.draining = true;
                    continue;
                }
                work(level, s, p, shared);
            } catch (RuntimeException e) {
                Incoming.LOG.error("Ruin preparation for strike #{} failed with an exception; dropped", p.strike, e);
                failed.add(p.strike);
                if (p.draining) {
                    // failed while releasing squares: release all, do not retry (otherwise error every tick)
                    it.remove();
                    try {
                        release(level, p);
                    } catch (RuntimeException again) {
                        Incoming.LOG.error("Ruin preparation for strike #{}: tiles not released", p.strike, again);
                    }
                    continue;
                }
                if (p.detonation >= 0) scars.dropPrepared(p.detonation, zone(p));
                p.draining = true;
            }
        }
    }

    private void work(ServerLevel level, NuclearEvents.ScheduledStrike s, Prep p, WorkClock shared) {
        if (p.geometry == null) {
            // detonation location as for actual burst: ground beneath target from ready chunk (target area held since launch)
            if (!Terrain.ready(level, BlockPos.containing(s.target()))) return;
            p.geometry = NuclearWarhead.geometry(level, s.surface() ? NuclearWarhead.surfaceAt(level, s.target()) : s.target(), s.yieldKt(), s.airBurst(),
                    (float) IncomingConfig.SERVER.effectsScale());
            Detonation g = p.geometry;
            // background layout: 15 kt zone ~33 thousand chunks/~1400 squares, costing 50–65 ms on tick
            p.layout = CompletableFuture.supplyAsync(() -> layout(g), Util.backgroundExecutor());
            p.ruins = new RuinContext(level, p.geometry);
        }
        if (p.layout != null) {
            Layout l = p.layout.getNow(null);
            if (l == null) return;
            install(p, l);
        }
        long now = level.getGameTime();
        if (now >= p.nextRefresh) {
            p.nextRefresh = now + REFRESH;
            refresh(level, p);
        }
        if (!p.heapStop && p.nextTodo < p.todo.size() && heapTight()) stopForHeap(p);
        p.ruins.expire(now);
        harvest(level, p, shared);
        if (!p.heapStop && !p.capped) submit(level, p, shared);
        far(level, p, shared);
        frontLods(level, p, now);
        if (!p.announced && p.nextTodo >= p.todo.size() && p.submitted.isEmpty() && p.nextFar >= p.order.length && p.farRunning.isEmpty()) {
            p.announced = true;
            // line for tests/recording: strike ruins prebuilt (later-loaded chunks still picked up)
            Runtime rt = Runtime.getRuntime();
            Incoming.LOG.info("Ruins for strike #{} ready: plans for loaded chunks {} (chunks loaded during flight {}), from disk {} (heavy zone {} chunks), not from disk {} "
                            + "(unread {}{}; {} s to detonation), plans {} MB, snapshots {} MB, background threads {} busy {} %, heap {} of {} MB",
                    p.strike, p.nearPlans, p.everInMemory.size(), p.farPlans, p.order.length, p.farSkipped, p.unreadable.size(), why(p.unreadWhy),
                    Math.max(0, p.detonateTime - now) / 20,
                    p.planBytes >> 20, p.ruins.shotBytes() >> 20, RuinWorkers.summary(), Math.round(RuinWorkers.utilisation() * 100),
                    (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20);
        }
    }

    /** Reasons descending by chunk count: “: not on disk 1200, not fully generated on disk 30”; empty if none. */
    private static String why(Object2IntOpenHashMap<String> counts) {
        StringBuilder out = new StringBuilder();
        counts.object2IntEntrySet().stream().sorted((x, y) -> Integer.compare(y.getIntValue(), x.getIntValue()))
                .forEach(e -> out.append(out.isEmpty() ? ": " : ", ").append(e.getKey()).append(' ').append(e.getIntValue()));
        return out.toString();
    }

    /** In-memory chunks within ruin radius absent from preparation queue: enqueue nearest first. */
    private static void refresh(ServerLevel level, Prep p) {
        Detonation d = p.geometry;
        double radius = d.ruinRadius();
        LongArrayList fresh = new LongArrayList();
        for (ChunkHolder holder : level.getChunkSource().chunkMap.getChunks()) {
            ChunkPos c = holder.getPos();
            if (p.considered.contains(c.toLong()) || horizontal(c, d) > radius || NuclearTickets.inMemory(level, c.toLong()) == null) continue;
            p.considered.add(c.toLong());
            p.everInMemory.add(c.toLong());
            fresh.add(c.toLong());
        }
        if (fresh.isEmpty()) return;
        // remaining/new chunks by distance (compute key once)
        LongArrayList rest = new LongArrayList(p.todo.subList(p.nextTodo, p.todo.size()));
        rest.addAll(fresh);
        long[] keyed = new long[rest.size()];
        for (int i = 0; i < keyed.length; i++) keyed[i] = (long) (horizontal(new ChunkPos(rest.getLong(i)), d) * 16) << 24 | i;
        java.util.Arrays.sort(keyed);
        p.todo.clear();
        for (long k : keyed) p.todo.add(rest.getLong((int) (k & 0xFFFFFF)));
        p.nextTodo = 0;
    }

    /** Slant range from burst to nearest chunk point at ground level (like ruin queue). */
    private static double nearest(ChunkPos p, Detonation d) {
        double h = horizontal(p, d), dy = d.burst().y - d.groundY();
        return Math.sqrt(h * h + dy * dy);
    }

    /** Complete ready background plans from live chunk, one per work unit. */
    private void harvest(ServerLevel level, Prep p, WorkClock shared) {
        for (int i = 0; i < p.submitted.size() && clock.canStart() && shared.canStart(); ) {
            long c = p.submitted.getLong(i);
            if (!p.ruins.done(c)) {
                if (!p.ruins.running(c)) p.submitted.removeLong(i); // task removed (chunk unloaded)
                else i++;
                continue;
            }
            p.submitted.removeLong(i);
            // chunk below full loading (view edge): plan valid, replacement checks against chunk
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c));
            long c0 = shared.begin();
            try {
                RuinPlan plan = chunk != null ? p.ruins.collect(level, chunk) : p.ruins.collect(new ChunkPos(c));
                if (plan != null) {
                    keep(level, p, c, plan);
                    p.nearPlans++;
                } else if (p.ruins.running(c)) {
                    p.submitted.add(c); // chunk reloaded: rebuild plan
                }
            } catch (RuntimeException e) {
                Incoming.LOG.error("Pre-built ruins: plan for chunk {} failed with an exception; its ruins will be built on the spot", new ChunkPos(c), e);
            } finally {
                clock.record(shared.end(c0));
            }
        }
    }

    /** Add ready plan to prebuilt ruins; halt further building beyond capacity. */
    private static void keep(ServerLevel level, Prep p, long c, RuinPlan plan) {
        p.plans.put(c, plan);
        p.planned.add(c);
        p.planBytes += plan.bytes();
        if (p.planBytes + RuinWorkers.bufferBytes(level.getHeight()) > PLANS_CAP && !p.capped) {
            p.capped = true;
            Incoming.LOG.warn("Ruins for strike #{}: finished plans take {} MB — no more ruins are pre-built (plans {}, heavy zone chunks {})",
                    p.strike, p.planBytes >> 20, p.plans.size(), p.order.length);
        }
    }

    /**
     * Phase 2: heavy zone outside memory. Read chunks in wave-arrival order (each 5×5 window
     * {@link #READS} reads ahead); disk snapshot enters detonation ruins, background threads plan by same
     * rules as in-memory chunks ({@link RuinContext#submitDisk}). Continues after detonation: behind-wave squares
     * take ready plans. No world loads; memory under {@link #PLANS_CAP} including plans.
     */
    private void far(ServerLevel level, Prep p, WorkClock shared) {
        if (p.heapStop || !IncomingConfig.SERVER.nukeBlockDamage.get()) return;
        if (p.format == null) {
            p.format = DiskShots.Format.of(level);
            // no preplans beyond heavy-zone edge: neighbor snapshots do not await them
            for (long k : p.edge) p.ruins.markNoPlan(k);
        }
        // disk results: snapshots into ruins (states into property table), one work unit per chunk
        for (DiskShots.Read r; clock.canStart() && shared.canStart() && (r = p.reads.poll()) != null; ) {
            long c = r.pos().toLong();
            p.reading.remove(c);
            long c0 = shared.begin();
            try {
                if (r.skip() != null) {
                    p.unreadable.add(c);
                    p.unreadWhy.addTo(r.skip().split(":", 2)[0], 1);
                } else {
                    p.ruins.putDisk(level, r);
                    p.diskShots++;
                }
            } catch (RuntimeException e) {
                p.unreadable.add(c);
                Incoming.LOG.error("Pre-built ruins: snapshot of chunk {} from disk failed", r.pos(), e);
            } finally {
                clock.record(shared.end(c0));
            }
        }
        // ready plans
        for (int i = 0; i < p.farRunning.size() && clock.canStart() && shared.canStart(); ) {
            long c = p.farRunning.getLong(i);
            if (p.ruins.running(c) && !p.ruins.done(c)) {
                i++;
                continue;
            }
            p.farRunning.removeLong(i);
            if (!p.ruins.done(c)) continue; // collected elsewhere (ruin queue) or task abandoned
            long c0 = shared.begin();
            try {
                RuinPlan plan = p.ruins.collect(new ChunkPos(c));
                if (plan != null) {
                    keep(level, p, c, plan);
                    p.farPlans++;
                    p.lods.offer(c);
                }
            } catch (RuntimeException e) {
                Incoming.LOG.error("Pre-built ruins: plan for chunk {} from disk failed with an exception; its ruins will be built on the spot", new ChunkPos(c), e);
            } finally {
                clock.record(shared.end(c0));
            }
        }
        // window read-ahead in wave order
        while (p.nextRead < p.order.length && p.reading.size() < READS && memory(level, p) < PLANS_CAP) {
            long c = p.order[p.nextRead];
            boolean all = true;
            for (int dx = -RuinPlanner.REACH; all && dx <= RuinPlanner.REACH; dx++) {
                for (int dz = -RuinPlanner.REACH; all && dz <= RuinPlanner.REACH; dz++) {
                    long k = ChunkPos.asLong(ChunkPos.getX(c) + dx, ChunkPos.getZ(c) + dz);
                    if (p.reading.contains(k) || p.unreadable.contains(k) || p.ruins.shotAvailable(level, k)) continue;
                    if (p.reading.size() >= READS) {
                        all = false;
                        continue;
                    }
                    p.reading.add(k);
                    ConcurrentLinkedQueue<DiskShots.Read> out = p.reads;
                    DiskShots.read(p.format, new ChunkPos(k), RuinWorkers.Priority.FAR).thenAccept(out::add);
                }
            }
            if (!all) break;
            p.nextRead++;
        }
        // plans in wave order: ready window goes to background threads; pending reads stop cursor
        while (p.nextFar < p.order.length && !p.capped && clock.canStart() && shared.canStart()) {
            long c = p.order[p.nextFar];
            if (p.plans.containsKey(c) || p.ruins.running(c) || p.ruins.failed(c) || p.ruins.doneAt(c) >= 0 || readyChunk(level, c) != null) {
                // ready, building, or in-memory chunk with neighbors, preplanned by near preparation
                p.nextFar++;
                continue;
            }
            int missing = 0;
            boolean skip = false;
            for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) {
                    long k = ChunkPos.asLong(ChunkPos.getX(c) + dx, ChunkPos.getZ(c) + dz);
                    if (p.unreadable.contains(k) && !p.ruins.shotAvailable(level, k)) skip = true;
                    else if (!p.ruins.shotAvailable(level, k)) missing++;
                }
            }
            if (skip) {
                // cannot read entire window: chunk ruins after wave
                p.farSkipped++;
                p.ruins.noPlan(c);
                p.nextFar++;
                continue;
            }
            // after detonation reserve half pool for ruin queue: player chunks before distant rings
            if (missing > 0 || !RuinWorkers.admit(RuinWorkers.Priority.FAR) || p.detonation >= 0 && RuinWorkers.inFlight() >= RuinWorkers.capacity() / 2) break;
            long c0 = shared.begin();
            try {
                if (!p.ruins.submitDisk(level, new ChunkPos(c))) break;
                p.farRunning.add(c);
                p.nextFar++;
            } catch (RuntimeException e) {
                p.nextFar++;
                Incoming.LOG.error("Pre-built ruins: snapshots of chunk {} from disk failed with an exception; its ruins will be built on the spot", new ChunkPos(c), e);
            } finally {
                clock.record(shared.end(c0));
            }
        }
    }

    /**
     * Wave-reached zone chunks into distant Distant Horizons LODs ({@link FarLods}): disk copy with ready-plan
     * ruins (awaits running plan). Loaded chunks receive ruins/LODs through own path.
     */
    private static void farLods(ServerLevel level, Prep p, Detonation d, long now) {
        for (; p.nextLod < p.order.length; p.nextLod++) {
            long c = p.order[p.nextLod];
            if (ScarQueue.due(d, new ChunkPos(c)) > now) break;
            FarLods.request(level, c, true);
        }
    }

    /**
     * Front zone LOD copies ({@link FrontLods}): built once all zone disk plans submitted/ready (or
     * halted at capacity), using prebuilt-ruin memory remaining after plans, snapshots, reads; after detonation
     * sent to DH for front arrival. Heap exhausted means no copies (LODs via {@link FarLods} after wave).
     */
    private static void frontLods(ServerLevel level, Prep p, long now) {
        if (p.ruins == null) return;
        if (p.heapStop) {
            p.lods.clear(level);
            return;
        }
        boolean plansDone = p.capped || p.nextFar >= p.order.length && p.farRunning.isEmpty();
        p.lods.tick(level, p.plans, PLANS_CAP - memory(level, p), plansDone, now);
    }

    /** Preparation front holds chunk LOD copy ({@link FrontLods#claim}): {@link FrontLods#NONE} if not. */
    int frontClaim(ServerLevel level, long chunk, boolean lights, long now) {
        for (Prep p : preps) {
            if (p.draining) continue;
            int claim = p.lods.claim(level, chunk, lights, now);
            if (claim != FrontLods.NONE) return claim;
        }
        return FrontLods.NONE;
    }

    /** Chunk loaded: front LOD copy unnecessary, world supplies ruins/LODs. */
    void loaded(long chunk) {
        for (Prep p : preps) p.lods.loaded(chunk);
    }

    /** For tests: strike {@code strike} preparation front LOD copies ({@link FrontLods#stats}); null without preparation. */
    @Nullable
    long[] frontStats(int strike) {
        for (Prep p : preps) if (p.strike == strike) return p.lods.stats();
        return null;
    }

    /** Ready ruin plan for chunk reached by its detonation's wave; null if none. */
    @Nullable
    RuinPlan arrivedPlan(ServerLevel level, NuclearEvents events, long chunk, long now) {
        for (Prep p : preps) {
            if (p.detonation < 0 || p.draining) continue;
            RuinPlan plan = p.plans.get(chunk);
            if (plan == null) continue;
            for (Detonation d : events.detonations()) {
                if (d.id() == p.detonation && ScarQueue.due(d, new ChunkPos(chunk)) <= now) return plan;
            }
        }
        return null;
    }

    /** Chunks in order to background threads (snapshots per work unit), while detonation has task capacity. */
    private void submit(ServerLevel level, Prep p, WorkClock shared) {
        while (p.nextTodo < p.todo.size() && RuinWorkers.admit(RuinWorkers.Priority.NEAR) && clock.canStart() && shared.canStart()) {
            long c = p.todo.getLong(p.nextTodo++);
            if (p.plans.containsKey(c) || p.ruins.running(c)) continue;
            // chunk without loaded neighbors (loaded-world edge) gets no preplan: unreadable window
            LevelChunk chunk = readyChunk(level, c);
            if (chunk == null) {
                // neighbors not loaded (loaded-world edge): picked up again once loaded
                p.considered.remove(c);
                continue;
            }
            long c0 = shared.begin();
            try {
                if (p.ruins.submit(level, chunk)) p.submitted.add(c);
                else p.nextTodo--;
            } catch (RuntimeException e) {
                Incoming.LOG.error("Pre-built ruins: snapshots of chunk {} failed with an exception; its ruins will be built on the spot", new ChunkPos(c), e);
            } finally {
                clock.record(shared.end(c0));
            }
        }
    }

    /** Ground heavy-zone radius, blocks (bisection on pressure monotonic with distance). */
    static double heavyRadius(Detonation d) {
        double lo = 0, hi = d.radiusMax();
        for (int i = 0; i < 40; i++) {
            double r = (lo + hi) / 2;
            if (d.psi(new Vec3(d.burst().x + r, d.groundY() + 1, d.burst().z)) >= PSI) lo = r;
            else hi = r;
        }
        return lo;
    }

    /** Heavy-zone layout: chunks/load squares by distance, which squares await which disk chunk. */
    private record Layout(long[] order, long[] edge, List<Tile> tiles, Long2ObjectOpenHashMap<List<Tile>> askedBy, LongArrayList toScan) {}

    private static void install(Prep p, Layout l) {
        p.layout = null;
        p.order = l.order;
        p.edge = l.edge;
        p.tiles = l.tiles;
        p.askedBy = l.askedBy;
        p.toScan = l.toScan;
    }

    /** Zone chunks by distance, outside edge, load squares by distance; world-free background work. */
    private static Layout layout(Detonation d) {
        double radius = heavyRadius(d) + 16;
        int cx = Mth.floor(d.burst().x) >> 4, cz = Mth.floor(d.burst().z) >> 4, r = Mth.ceil(radius / 16) + 1;
        LongArrayList in = new LongArrayList();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                ChunkPos c = new ChunkPos(x, z);
                if (horizontal(c, d) <= radius) in.add(c.toLong());
            }
        }
        // by distance: keys computed once (distance in sort comparator costs tens of ms for 15 thousand chunks)
        long[] order = in.toLongArray();
        long[] keyed = new long[order.length];
        for (int i = 0; i < order.length; i++) keyed[i] = (long) (horizontal(new ChunkPos(order[i]), d) * 16) << 20 | i;
        java.util.Arrays.sort(keyed);
        LongOpenHashSet zone = new LongOpenHashSet(order), edge = new LongOpenHashSet();
        for (long c : order) {
            for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) {
                    long k = ChunkPos.asLong(ChunkPos.getX(c) + dx, ChunkPos.getZ(c) + dz);
                    if (!zone.contains(k)) edge.add(k);
                }
            }
        }
        Layout l = new Layout(new long[order.length], edge.toLongArray(), new ArrayList<>(), new Long2ObjectOpenHashMap<>(), new LongArrayList());
        for (int i = 0; i < order.length; i++) l.order[i] = order[(int) (keyed[i] & 0xFFFFF)];
        LongOpenHashSet seen = new LongOpenHashSet();
        for (long c : l.order) {
            ChunkPos pos = new ChunkPos(c);
            ChunkPos centre = new ChunkPos(Math.floorDiv(pos.x, TILE) * TILE + TILE_RADIUS, Math.floorDiv(pos.z, TILE) * TILE + TILE_RADIUS);
            if (!seen.add(centre.toLong())) continue;
            Tile t = new Tile(centre);
            l.tiles.add(t);
            for (int dx = -WINDOWS_REACH; dx <= WINDOWS_REACH; dx++) {
                for (int dz = -WINDOWS_REACH; dz <= WINDOWS_REACH; dz++) {
                    long k = ChunkPos.asLong(centre.x + dx, centre.z + dz);
                    List<Tile> asked = l.askedBy.get(k);
                    if (asked == null) {
                        l.askedBy.put(k, asked = new ArrayList<>(1));
                        l.toScan.add(k);
                    }
                    asked.add(t);
                }
            }
        }
        return l;
    }

    /** Horizontal epicenter distance to nearest chunk point. */
    static double horizontal(ChunkPos p, Detonation d) {
        double x = Math.max(p.getMinBlockX(), Math.min(d.burst().x, p.getMaxBlockX() + 1));
        double z = Math.max(p.getMinBlockZ(), Math.min(d.burst().z, p.getMaxBlockZ() + 1));
        return Math.hypot(x - d.burst().x, z - d.burst().z);
    }

    /** Whether chunks whole on disk ({@link DiskStatus.State#WHOLE}): memory immediate, others background header reads. */
    private static void scan(ServerLevel level, Prep p) {
        for (long[] r; (r = p.scanned.poll()) != null; ) {
            p.scanning--;
            answer(p, r[0], r[1] != 0);
        }
        while (p.nextScan < p.toScan.size() && p.scanning < SCANS) {
            long c = p.toScan.getLong(p.nextScan++);
            if (NuclearTickets.inMemory(level, c) != null) {
                answer(p, c, true);
                continue;
            }
            p.scanning++;
            ConcurrentLinkedQueue<long[]> out = p.scanned;
            DiskStatus.scan(level, new ChunkPos(c)).thenAccept(state -> out.add(new long[]{c, state == DiskStatus.State.WHOLE ? 1 : 0}));
        }
    }

    /** Chunk result: not whole means neighboring square chunks (ruin-window radius) not loaded. */
    private static void answer(Prep p, long chunk, boolean whole) {
        List<Tile> asked = p.askedBy.get(chunk);
        if (asked == null) return;
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        for (Tile t : asked) {
            if (!whole) {
                for (int dx = Math.max(-TILE_RADIUS, x - t.centre.x - RuinPlanner.REACH); dx <= Math.min(TILE_RADIUS, x - t.centre.x + RuinPlanner.REACH); dx++) {
                    for (int dz = Math.max(-TILE_RADIUS, z - t.centre.z - RuinPlanner.REACH); dz <= Math.min(TILE_RADIUS, z - t.centre.z + RuinPlanner.REACH); dz++) {
                        t.partial |= Tile.bit(dx, dz);
                    }
                }
            }
            if (--t.unknown == 0 && t.state == TileState.SCAN) t.state = t.loads() == 0 ? TileState.SKIP : TileState.WAIT;
        }
    }

    /**
     * Squares nearest first, behind front (wave passed nearest square edge), a few loading at once
     * and at most {@link #HELD_TILES} total; square whose loadable chunks all in memory without ready plans not
     * held, since ruins already queued. With ready plan, hold without occupying slot: in-memory chunk
     * may use external ticket (detonation area released on detonation tick); without own ticket it would leave memory before
     * wave, losing plan. After heap stop, square loads only ready-plan chunks: on-site planning unplanned chunks
     * would consume scarce memory; their ruins wait for another loader.
     */
    private static void load(ServerLevel level, Prep p, ScarQueue scars, Detonation d, long now) {
        int loading = 0, held = 0;
        for (Tile t : p.tiles) {
            if (t.state == TileState.LOADING) {
                if (ready(level, t)) {
                    t.state = TileState.READY;
                    t.readyAt = now;
                } else {
                    loading++;
                }
            }
            if ((t.state == TileState.LOADING || t.state == TileState.READY) && !t.resident) held++;
        }
        int stop = 3;
        for (Tile t : p.tiles) {
            if (t.state == TileState.SCAN) {
                stop = 1;
                break; // distance order: distant await nearest disk reads
            }
            if (t.state != TileState.WAIT) continue;
            if (now < d.gameTime() + d.arrivalTicks(nearest(new ChunkPos(t.centre.x - TILE_RADIUS, t.centre.z - TILE_RADIUS), t, d))) {
                stop = 2;
                break;
            }
            int planned = withPlan(p, scars, t), take = p.heapStop ? planned : t.loads();
            if (full(level, t.centre, take)) {
                if (planned == 0) {
                    t.state = TileState.SKIP;
                } else {
                    // all already in memory: tickets hold until ruins, no loads/slot needed
                    t.hold(level, p.strike, take);
                    t.resident = true;
                    t.state = TileState.READY;
                    t.heldAt = t.readyAt = now;
                }
                continue;
            }
            if (loading >= LOADING_TILES || held >= HELD_TILES) {
                stop = 0;
                break;
            }
            t.hold(level, p.strike, take);
            t.state = TileState.LOADING;
            if (NukeDiag.ON) {
                t.heldAt = now;
                long wait = now - d.gameTime() - (long) Math.ceil(d.arrivalTicks(nearest(new ChunkPos(t.centre.x - TILE_RADIUS, t.centre.z - TILE_RADIUS), t, d)));
                p.diagDone[5] += wait;
                p.diagDone[7]++;
                p.diagDone[6] = Math.max(p.diagDone[6], wait);
            }
            loading++;
            held++;
        }
        p.diagStops[stop]++;
    }

    /** Loadable square chunks ({@link Tile#loads}) with ready but unapplied plan. */
    private static int withPlan(Prep p, ScarQueue scars, Tile t) {
        int chunks = 0;
        for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
            for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) {
                if ((t.loads() & Tile.bit(dx, dz)) != 0 && scars.pendingPlan(p.detonation, t.chunk(dx, dz))) chunks |= Tile.bit(dx, dz);
            }
        }
        return chunks;
    }

    /** Slant range to nearest square point at ground level. */
    private static double nearest(ChunkPos corner, Tile t, Detonation d) {
        double x = Math.max(corner.getMinBlockX(), Math.min(d.burst().x, (t.centre.x + TILE_RADIUS + 1) * 16.0));
        double z = Math.max(corner.getMinBlockZ(), Math.min(d.burst().z, (t.centre.z + TILE_RADIUS + 1) * 16.0));
        double dx = x - d.burst().x, dz = z - d.burst().z, dy = d.burst().y - d.groundY();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * All square chunks centered at {@code centre} fully loaded now: ruins proceed without square. In-memory chunk
     * below full loading (loaded-world edge) excluded: awaits neighbors without own ticket; skipping square
     * because of it leaves it without ruins. Exposed for tests.
     */
    public static boolean tileFull(ServerLevel level, ChunkPos centre) {
        return full(level, centre, ALL);
    }

    /** Square chunks in {@code chunks} mask (bits {@link Tile#bit}) fully loaded now. */
    private static boolean full(ServerLevel level, ChunkPos centre, int chunks) {
        for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
            for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) {
                if ((chunks & Tile.bit(dx, dz)) != 0 && level.getChunkSource().getChunkNow(centre.x + dx, centre.z + dz) == null) return false;
            }
        }
        return true;
    }

    /** Zone behind wave (after detonation): header reads, square loads, release ruined squares. */
    private static void behindWave(ServerLevel level, Prep p, ScarQueue scars, @Nullable Detonation d, long now, WorkClock clock) {
        if (d != null) {
            scan(level, p);
            load(level, p, scars, d, now);
        } else {
            // detonation forgotten: stop loading zone behind wave
            for (Tile t : p.tiles) if (t.state == TileState.SCAN || t.state == TileState.WAIT) t.state = TileState.SKIP;
            p.diagStops[4]++;
        }
        releaseDone(level, p, scars, clock);
        if (NukeDiag.ON && now >= p.nextIoReport) {
            p.nextIoReport = now + 200;
            Incoming.LOG.info("DIAG I/O #{}: {}", p.detonation, NukeDiag.io(level));
        }
        if (now >= p.nextZoneReport) {
            p.nextZoneReport = now + ZONE_REPORT;
            zoneReport(p, scars, d, now);
            if (NukeDiag.ON) diagReport(level, p, scars, now);
        }
    }

    /**
     * “DIAG” lines (only {@link NukeDiag#ON}): what holds behind-wave zone slots. Square states, admission
     * stop reasons, released-square durations, non-full chunks in loading squares; ready squares show
     * chunks blocking release; ruin-queue/disk-read wait reasons.
     */
    private static void diagReport(ServerLevel level, Prep p, ScarQueue scars, long now) {
        int[] states = new int[TileState.values().length];
        for (Tile t : p.tiles) states[t.state.ordinal()]++;
        long[] s = p.diagStops, dn = p.diagDone;
        long n = Math.max(1, dn[0]);
        Incoming.LOG.info("DIAG zone #{}: tiles SCAN {}, SKIP {}, WAIT {}, LOADING {}, READY {}; headers read {} of {} (in flight {}); "
                        + "intake stalled, ticks — slots {}, disk reply {}, wave {}, nothing to take {}, detonation forgotten {}; released {}: ticket→full avg {} (max {}), "
                        + "full→released {} ({}), wave→ticket {} ({}); holds for tiles {}, tile releases refused (too few holds) {}",
                p.detonation, states[0], states[1], states[2], states[3], states[4], p.nextScan - p.scanning, p.toScan.size(), p.scanning,
                s[0], s[1], s[2], s[3], s[4], dn[0], dn[1] / n, dn[2], dn[3] / n, dn[4], dn[5] / Math.max(1, dn[7]), dn[6], scars.tileHoldsDiag(), p.diagRefused);
        java.util.Arrays.fill(s, 0);
        for (Tile t : p.tiles) {
            if (t.state == TileState.LOADING) {
                int notFull = 0;
                for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
                    for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) {
                        if ((t.held & Tile.bit(dx, dz)) != 0 && !Terrain.ready(level, t.centre.x + dx, t.centre.z + dz)) notFull++;
                    }
                }
                Incoming.LOG.info("DIAG tile {} loading for {} ticks: {} of {} chunks not full", t.centre, now - t.heldAt, notFull, Integer.bitCount(t.held));
            } else if (t.state == TileState.READY) {
                java.util.Map<String, Integer> why = new java.util.TreeMap<>();
                String example = null;
                for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
                    for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) {
                        if ((t.held & Tile.bit(dx, dz)) == 0) continue;
                        long c = t.chunk(dx, dz);
                        String state = scars.diagState(c, now);
                        if (state == null && scars.pendingPlan(p.detonation, c)) state = "finished plan, no queued work";
                        if (state == null) continue;
                        why.merge(state, 1, Integer::sum);
                        if (example == null) example = new ChunkPos(c) + " — " + state;
                    }
                }
                Incoming.LOG.info("DIAG tile {} ready for {} ticks (loaded in {}): held by {}{}", t.centre, now - t.readyAt, t.readyAt - t.heldAt,
                        why.isEmpty() ? "nothing" : why, example == null ? "" : "; e.g. " + example);
            }
        }
        Incoming.LOG.info("DIAG ruin queue: {}", NukeDiag.takeWaits());
    }

    /** Test line: ruined heavy-zone chunks (in memory at detonation and loaded behind wave). */
    private static void zoneReport(Prep p, ScarQueue scars, @Nullable Detonation d, long now) {
        int done = 0;
        for (long c : p.order) if (scars.ruined(p.detonation, c)) done++;
        Incoming.LOG.info("Detonation #{}: zone behind the wave — {} of {} chunks done, tiles held {}", p.detonation, done, p.order.length, heldTiles(p));
        if (d == null || p.farAtDetonation == null || p.ruins == null) return;
        // distant rings: outside-memory chunks at detonation already wave-reached, whether plan ready at arrival
        int passed = 0, ready = 0, late = 0, none = 0, unreadable = 0;
        long lateSum = 0, lateMax = 0;
        for (long c : p.farAtDetonation) {
            long wave = d.gameTime() + (long) Math.ceil(d.arrivalTicks(nearest(new ChunkPos(c), d)));
            if (wave > now) continue;
            passed++;
            long at = p.ruins.doneAt(c);
            if (at >= 0 && at <= wave) {
                ready++;
            } else if (at >= 0) {
                late++;
                lateSum += at - wave;
                lateMax = Math.max(lateMax, at - wave);
            } else if (p.unreadable.contains(c)) {
                unreadable++;
            } else {
                none++;
            }
        }
        Incoming.LOG.info("Detonation #{}: far rings: ready for the wave {} of {} (after the wave {}: avg {} ticks late, max {}; "
                        + "no plan yet {}, not from disk {}; wave still ahead for {})", p.detonation, ready, passed, late, late == 0 ? 0 : lateSum / late, lateMax,
                none, unreadable, p.farAtDetonation.length - passed);
    }

    private static boolean ready(ServerLevel level, Tile t) {
        for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
            for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) {
                if ((t.held & Tile.bit(dx, dz)) != 0 && !Terrain.ready(level, t.centre.x + dx, t.centre.z + dz)) return false;
            }
        }
        return true;
    }

    /**
     * Live heap above {@link HeapWatch#LIMIT} in two consecutive samples ({@link HeapWatch}: old generation after
     * GC measuring live size): may contain some uncollected matter; one sample does not yet mean pressure.
     */
    private boolean heapTight() {
        HeapWatch watch = HeapWatch.get();
        long measured = watch.measured();
        if (measured != lastMeasured) {
            lastMeasured = measured;
            long live = watch.live();
            heapStrikes = HeapWatch.strikes(heapStrikes, live, watch.limit());
            if (NukeDiag.ON) Incoming.LOG.info("DIAG heap: {} after GC {} MB of {}, consecutive over the limit {}",
                    watch.available() ? "old generation" : "heap", live >> 20, Runtime.getRuntime().maxMemory() >> 20, heapStrikes);
        }
        return heapStrikes >= 2;
    }

    /** For tests: stop all preparations as under memory pressure. */
    void stopAllForHeap() {
        for (Prep p : preps) if (!p.heapStop && !p.draining) stopForHeap(p);
    }

    /** Memory tight: retain ready plans, other chunks planned on-site at wave (slower, avoids exhaustion). */
    private static void stopForHeap(Prep p) {
        Runtime rt = Runtime.getRuntime();
        Incoming.LOG.warn("Ruins for strike #{}: heap after GC {} of {} MB — no more ruins are pre-built (plans {}, heavy zone chunks {})",
                p.strike, HeapWatch.get().live() >> 20, rt.maxMemory() >> 20, p.plans.size(), p.order.length);
        p.heapStop = true;
    }

    /** Chunk if it and radius-{@link RuinPlanner#REACH} neighbors ready (ruins read them). */
    @Nullable
    private static LevelChunk readyChunk(ServerLevel level, long c) {
        ChunkPos pos = new ChunkPos(c);
        return NuclearTickets.neighbourhoodLoaded(level, pos, RuinPlanner.REACH) ? level.getChunkSource().getChunkNow(pos.x, pos.z) : null;
    }

    // ---------------------------------------------------------------- detonation

    /**
     * Detonation: transfer ready ruins at same location/yield/scale (queue processes their chunks first). Hold tickets
     * until ruins applied.
     *
     * @return ready ruins and chunk order; null if unprepared
     */
    @Nullable
    Handoff handOff(ServerLevel level, Detonation d, long now) {
        for (Prep p : preps) {
            if (!preparedFor(p, d)) continue;
            // layout still building (location known only tick or two before burst): await it, otherwise no behind-wave zone
            if (p.layout != null) {
                try {
                    install(p, p.layout.join());
                } catch (RuntimeException e) {
                    Incoming.LOG.error("Ruin preparation for strike #{} failed with an exception; dropped", p.strike, e);
                    failed.add(p.strike);
                    p.draining = true;
                    continue;
                }
            }
            p.detonation = d.id();
            p.handedOff = now;
            p.farAtDetonation = java.util.Arrays.stream(p.order).filter(c -> level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c)) == null).toArray();
            Incoming.LOG.info("Detonation #{}: pre-built ruins — plans for loaded chunks {} (chunks loaded during flight {}), still building {}; from disk {} "
                            + "(zone chunks not loaded at detonation {}), still building {}, not from disk {}; background threads {}", d.id(), p.nearPlans, p.everInMemory.size(),
                    p.submitted.size(), p.farPlans,
                    p.farAtDetonation.length, p.farRunning.size(), p.farSkipped, RuinWorkers.summary());
            p.nextZoneReport = now + ZONE_REPORT;
            p.lods.handOff(d);
            return new Handoff(p.plans, p.planned.toLongArray(), p.ruins);
        }
        return null;
    }

    record Handoff(Long2ObjectOpenHashMap<RuinPlan> plans, long[] order, RuinContext ruins) {}

    /**
     * Prepared detonation geometry if detonation matches preparation, otherwise {@code fresh}. Detonation
     * takes its point, ground and visibility, so prebuilt ruins match later-loaded chunk ruins after preparation
     * forgotten (derived from detonation), even if weather changed during ICBM flight.
     */
    Detonation planned(Detonation fresh) {
        for (Prep p : preps) if (preparedFor(p, fresh)) return p.geometry;
        return fresh;
    }

    /** Preparation matches detonation: not handed off, same location, yield and scale. */
    private static boolean preparedFor(Prep p, Detonation d) {
        Detonation g = p.geometry;
        return p.detonation < 0 && !p.draining && g != null && g.yieldKt() == d.yieldKt() && g.surface() == d.surface() && g.scale() == d.scale()
                && g.burst().distanceTo(d.burst()) <= SAME_PLACE;
    }

    /** Chunks in zone behind wave (ruin summary). */
    private static java.util.function.LongPredicate zone(Prep p) {
        return new LongOpenHashSet(p.order)::contains;
    }

    private static int heldTiles(Prep p) {
        int n = 0;
        for (Tile t : p.tiles) if (t.state == TileState.LOADING || t.state == TileState.READY) n++;
        return n;
    }

    /**
     * After detonation, square whose loaded chunks ruined no longer held; release only a few
     * per tick: chunk unload writes disk on server tick. Unloaded chunk saved with ruins; chunks
     * square never loaded (window not whole on disk) do not hold it: ruins follow own path if loaded
     * by someone else.
     */
    private static void releaseDone(ServerLevel level, Prep p, ScarQueue scars, WorkClock clock) {
        int released = 0;
        for (Tile t : p.tiles) {
            if (released >= RELEASE_PER_TICK || !clock.canStart()) return;
            if (t.state != TileState.READY) continue;
            // all chunks ruined, or remainder await only neighbors and hold own tickets
            boolean done = true;
            int waiting = 0;
            for (int dx = -TILE_RADIUS; done && dx <= TILE_RADIUS; dx++) {
                for (int dz = -TILE_RADIUS; done && dz <= TILE_RADIUS; dz++) {
                    if ((t.held & Tile.bit(dx, dz)) == 0) continue;
                    long c = t.chunk(dx, dz);
                    if (!scars.pendingPlan(p.detonation, c) && !scars.queued(c)) continue;
                    done = scars.waitsNeighbours(c);
                    if (done) waiting++;
                }
            }
            if (!done) continue;
            if (waiting > scars.tileHoldsLeft()) {
                p.diagRefused++;
                continue;
            }
            long c0 = clock.begin();
            if (waiting > 0) {
                for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
                    for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) if ((t.held & Tile.bit(dx, dz)) != 0) scars.holdForTile(level, t.chunk(dx, dz));
                }
            }
            t.release(level, p.strike);
            clock.end(c0);
            t.state = TileState.SKIP;
            released++;
            if (NukeDiag.ON) {
                long now = level.getGameTime(), load = t.readyAt - t.heldAt, idle = now - t.readyAt;
                long[] dn = p.diagDone;
                dn[0]++;
                dn[1] += load;
                dn[2] = Math.max(dn[2], load);
                dn[3] += idle;
                dn[4] = Math.max(dn[4], idle);
            }
        }
    }

    /** Release up to {@code limit} squares; returns remaining held count. */
    private static int releaseSome(ServerLevel level, Prep p, int limit) {
        int held = 0;
        for (Tile t : p.tiles) {
            if (t.state != TileState.READY && t.state != TileState.LOADING) continue;
            if (limit-- > 0) {
                t.release(level, p.strike);
                t.state = TileState.SKIP;
            } else {
                held++;
            }
        }
        return held;
    }

    private static void release(ServerLevel level, Prep p) {
        for (Tile t : p.tiles) {
            if (t.state == TileState.LOADING || t.state == TileState.READY) t.release(level, p.strike);
        }
        p.tiles.clear();
    }

    /** Cancellation, world unload, error: release all tickets; queues no longer need ready ruins handed to detonation. */
    public void clear(ServerLevel level, ScarQueue scars) {
        for (Prep p : preps) {
            release(level, p);
            p.lods.clear(level);
            if (p.ruins != null && p.detonation < 0) p.ruins.cancelTasks();
            if (p.detonation >= 0 && !p.draining) scars.dropPrepared(p.detonation, zone(p));
        }
        preps.clear();
    }
}
