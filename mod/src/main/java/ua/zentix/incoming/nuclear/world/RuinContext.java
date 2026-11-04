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
    /** Marker identity is assigned at handoff; immutable geometry remains safe for running solvers. */
    private int eventId;
    /** Diagnostic server-thread fracture cache; background tasks own their fragments until actual exit. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Blast> blasts = new java.util.concurrent.ConcurrentHashMap<>();
    /** Only diagnostic server-thread solvers keep this cache; background fragments stay task-owned. */
    private long blastBytes;
    private long fragmentCacheLimit = 16L << 20;
    @Nullable private volatile RuinFragmentCache fragmentCache;
    private boolean fragmentCacheClosed;
    /** Chunk snapshots (server thread) retained while unfinished plan windows read them. Outside-memory chunks from disk ({@link DiskShots}). */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<ChunkShot> shots = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
    /**
     * Pre-wave {@code MOTION_BLOCKING} heightmaps (first air) for light-pulse shadow
     * ({@link #lit}) on any thread: in-memory from live chunk (pre-ruin for ruined chunk), others disk.
     * Server-thread writes only, immutable values; missing means no terrain (like unloaded chunk).
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, RuinHeights> heights = new java.util.concurrent.ConcurrentHashMap<>();
    private long heightBytes;
    private long appliedBytes;
    private final RuinInputs inputs = new RuinInputs();
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
        this.eventId = d.id();
        this.noise = new ua.zentix.incoming.nuclear.model.WorldNoise(level.getSeed(), d.scale());
        this.ruinRadius = d.ruinRadius();
        this.reads = new RuinInbox<>(NuclearWorld.get(level).prep().readAccounting(), WINDOW_READ_BYTES);
    }

    /** Adopt the actual event marker without mutating geometry captured by background tasks. */
    void bindEvent(int id) {
        if (id <= 0 || eventId > 0 && eventId != id) throw new IllegalArgumentException("Ruin context belongs to another event");
        eventId = id;
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
            ChunkShot disk = shots.getAndMoveToLast(key);
            return disk != null && disk.fromDisk() && disk.fresh() ? disk : null;
        }
        ChunkShot cached = shots.getAndMoveToLast(key);
        if (cached != null && cached.current(c, applied.get(key))) return cached;
        try (var reservation = RuinMemory.reserveInputs(level, liveShotBytes(c))) {
            return reservation == null ? null : captureShot(c);
        }
    }

    /** Caller owns the whole window reservation; no nested admission after partial publication. */
    private ChunkShot captureShot(LevelChunk c) {
        long key = c.getPos().toLong();
        Applied a = applied.get(key);
        ChunkShot s = shots.getAndMoveToLast(key);
        if (s == null || !s.current(c, a)) {
            RuinPlan flash = flashed.get(key);
            // flash layer present but plan forgotten (detonation ruins forgotten/restart): snapshot fire is air
            putShot(key, s = ChunkShot.take(c, a, flash, flash == null && a == null && FlashScorch.marked(c, eventId)));
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
        ChunkShot s = shots.getAndMoveToLast(pos.toLong());
        return s != null && !s.fromDisk() && s.current(chunk, applied.get(pos.toLong()));
    }

    /** Caller must wait on false before applying a stored flash plan that has no captured height tile yet. */
    boolean flashHeightsReady(ServerLevel level, LevelChunk chunk) {
        if (heights.containsKey(chunk.getPos().toLong())) return true;
        try (var reservation = RuinMemory.reserveInputs(level, heightTileBound(chunk.getHeight()) + 1024)) {
            if (reservation == null) return false;
            captureHeights(level, chunk.getPos().x, chunk.getPos().z);
            return true;
        }
    }

    /**
     * Flash layer applied (server thread). Previously valid cached snapshot remains pre-flash world, subsequently checked
     * against layered chunk ({@link ChunkShot#relive}): fractures/plans remain valid; otherwise retake snapshot.
     */
    void flashed(LevelChunk chunk, RuinPlan plan, boolean shotCurrent) {
        long key = chunk.getPos().toLong();
        RuinPlan was = flashed.put(key, plan);
        flashedBytes += plan.bytes() - (was == null ? 0 : was.bytes());
        plan.releaseResultToCountedOwner();
        ChunkShot s = shots.getAndMoveToLast(key);
        if (s == null) return;
        if (shotCurrent) s.relive(chunk, plan);
        else dropShot(key);
    }

    /**
     * Snapshots for chunk flash layer (server thread): chunk plus fully in-memory neighbors (edge face scorch
     * checks neighbor; absent means exposed face), from detonation ruin cache, later reused for ruins. Chunk in memory.
     */
    @Nullable
    Grid flashGrid(ServerLevel level, ChunkPos centre, boolean background) {
        return captureGrid(level, centre, background, 1);
    }

    /** Reserve every new snapshot and shadow tile before publishing any member of the window. */
    @Nullable
    private Grid captureGrid(ServerLevel level, ChunkPos centre, boolean background, int radius) {
        long bytes = bandBytes(level, centre);
        for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
            LevelChunk c = level.getChunkSource().getChunkNow(centre.x + dx, centre.z + dz);
            if (c == null) continue;
            ChunkShot cached = shots.get(c.getPos().toLong());
            if (cached == null || !cached.current(c, applied.get(c.getPos().toLong()))) bytes += liveShotBytes(c);
        }
        try (var reservation = RuinMemory.reserveInputs(level, bytes)) {
            if (reservation == null) return null;
            ChunkShot[] g = new ChunkShot[Grid.SIDE * Grid.SIDE];
            for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                int cx = centre.x + dx, cz = centre.z + dz;
                LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
                // Missing live input is never produced by an admission refusal: the entire window was admitted.
                g[(dz + Grid.R) * Grid.SIDE + dx + Grid.R] = c == null ? (radius == Grid.R ? cachedShot(cx, cz) : null) : captureShot(c);
            }
            captureBand(level, centre);
            RuinPlanner.ensureOwnStates();
            return new Grid(this, centre, level.getMinBuildHeight(), level.getMaxBuildHeight(), g,
                    background ? Blast.publish() : Blast.SERVER, IncomingConfig.SERVER.nukeBlockDamage.get(),
                    IncomingConfig.SERVER.nukeTreeFall.get(), IncomingConfig.SERVER.nukeFires.get());
        }
    }

    @Nullable
    private ChunkShot cachedShot(int cx, int cz) {
        ChunkShot s = shots.getAndMoveToLast(ChunkPos.asLong(cx, cz));
        return s != null && s.fromDisk() && s.fresh() ? s : null;
    }

    /** Same retained-size law as ChunkShot, using the source palettes before copying them. */
    private long liveShotBytes(LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        RuinPlan flash = flashed.get(key);
        boolean unfire = flash == null && applied.get(key) == null && FlashScorch.marked(chunk, eventId);
        var sections = chunk.getSections();
        Applied a = applied.get(key);
        boolean ruined = a != null;
        long bytes = snapshotMetadata(sections.length, true) + ChunkShot.oldBytes(a == null ? null : a.plan());
        for (int i = 0; i < sections.length; i++) {
            // unflash can restore a formerly empty section; only touched sections need the palette-growth bound.
            boolean edit = flash != null ? flash.touches(i) : unfire && !sections[i].hasOnlyAir();
            if (edit) bytes += editableSectionBytes();
            else bytes += ChunkShot.sectionBytes(sections[i].hasOnlyAir() ? null : sections[i].getStates(), ruined);
        }
        return bytes;
    }

    private static long snapshotMetadata(int sections, boolean sky) {
        return ChunkShot.metadataBytes(sections, RuinPlan.HEIGHTMAP_TYPES.length * 256, sky);
    }

    /** SECTION_STATES switches to the registry palette above 8 bits. Include the largest local palette too. */
    private static long editableSectionBytes() {
        int bits = Math.max(9, net.minecraft.util.Mth.ceillog2(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size()));
        return 1L + 5 + 256L * 5 + 5 + 8L * ((4096 + 64 / bits - 1) / (64 / bits)) + 64 + 4096L * 8;
    }

    /**
     * Disk-read chunk (server thread): snapshot if no in-memory snapshot or chunk not fully in memory now
     * (in-memory snapshot would not be used anyway), with heightmap for light shadow. Missing disk heightmaps
     * computed here from blocks ({@link DiskShots.Read#primed}).
     */
    boolean putDisk(ServerLevel level, DiskShots.Read read) {
        long key = read.pos().toLong();
        ChunkShot now = shots.getAndMoveToLast(key);
        if (now != null && !now.fromDisk() && level.getChunkSource().getChunkNow(read.pos().x, read.pos().z) != null) return true;
        try (var reservation = RuinMemory.reserveInputs(level, diskPublicationBytes(read))) {
            if (reservation == null) return false;
            publishDisk(read);
            return true;
        }
    }

    /** Known detached payload boundary; NBT read/upgrade/codec allocation precedes this API. */
    private long diskPublicationBytes(DiskShots.Read read) {
        Applied a = applied.get(read.pos().toLong());
        boolean ruined = a != null;
        long bytes = snapshotMetadata(read.states().length, false) + ChunkShot.oldBytes(a == null ? null : a.plan());
        RuinPlan flash = flashed.get(read.pos().toLong());
        boolean unfire = flash == null && applied.get(read.pos().toLong()) == null && FlashScorch.marks(read.flashLayer(), eventId);
        for (int i = 0; i < read.states().length; i++) {
            var section = read.states()[i];
            if (flash != null && flash.touches(i) || unfire && section != null) bytes += editableSectionBytes();
            else bytes += ChunkShot.sectionBytes(section, ruined);
        }
        // Priming/cloning and motion extraction can overlap publication; height tile bound follows the section domain.
        return bytes + (heights.containsKey(read.pos().toLong()) ? 0 : heightTileBound(read.states().length * 16))
                + 4L * read.heights().length + 1024;
    }

    private void publishDisk(DiskShots.Read read) {
        long key = read.pos().toLong();
        DiskShots.Read primed = read.primed(Blast.SERVER);
        Applied a = applied.get(key);
        RuinPlan flash = flashed.get(key);
        putShot(key, ChunkShot.fromDisk(primed, a, flash, flash == null && a == null && FlashScorch.marks(primed.flashLayer(), eventId)));
        putHeights(key, primed.motion());
    }

    /** Plan snapshot exists: fully in-memory chunk or disk read (property table not reset since). */
    boolean shotAvailable(ServerLevel level, long chunk) {
        if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) != null) return true;
        ChunkShot s = shots.getAndMoveToLast(chunk);
        return s != null && s.fromDisk() && s.fresh();
    }

    /**
     * Maximum disk snapshots taken by server thread per {@link #requestWindow}: 5×5 window; no-disk-heightmap
     * chunk counts as several ({@link DiskShots.Read#cost}).
     */
    private static final int PUTS_PER_WINDOW = (2 * RuinPlanner.REACH + 1) * (2 * RuinPlanner.REACH + 1);
    /** Window neighbors being read from disk ({@link #requestWindow}), plus read results not yet taken by server thread. */
    private final LongOpenHashSet reading = new LongOpenHashSet();
    /** Windows assembling snapshots, awaiting worker admission, or rebuilding on the server thread. */
    private final LongOpenHashSet pendingWindows = new LongOpenHashSet();
    private static final long WINDOW_READ_BYTES = 4L << 20;
    private static final int WINDOW_READ_LIMIT = 32;
    private record WindowRead(long key, @Nullable DiskShots.Read disk,
                              @Nullable java.util.Optional<net.minecraft.nbt.CompoundTag> cached) {
        int cost() { return cached != null ? Math.max(PUTS_PER_WINDOW / 4, disk.cost()) : disk.cost(); }
    }
    private final RuinInbox<WindowRead> reads;
    /** Window neighbors not whole on disk (unfinished generation): solid window mass like world edge. */
    private final LongOpenHashSet absent = new LongOpenHashSet();
    /** Detonation log: disk window neighbors read, those not whole on disk. */
    int windowReads, windowAbsent;
    private boolean windowFailed;
    boolean windowFailed() { return windowFailed; }

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
        pendingWindows.add(pos.toLong());
        drainWindowReads(level);
        int missing = 0;
        for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) {
            for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                long key = ChunkPos.asLong(pos.x + dx, pos.z + dz);
                if (absent.contains(key) || shotAvailable(level, key)) continue;
                missing++;
                if (!reading.contains(key) && reads.pending() < WINDOW_READ_LIMIT
                        && WINDOW_READ_BYTES <= RuinMemory.sourceReadRoom(level)) {
                    reading.add(key);
                    if (archive != null && archive.mayHave(key)) {
                        var accept = reads.expect();
                        var disk = DiskShots.read(DiskShots.Format.of(level), new ChunkPos(key), priority);
                        archive.read(key).handle((tag, failure) -> failure == null ? tag : java.util.Optional.<net.minecraft.nbt.CompoundTag>empty())
                                .thenCombine(disk, (tag, current) -> new WindowRead(key, current, tag)).thenAccept(accept);
                    } else readWindowDisk(level, key, priority);
                }
            }
        }
        return missing;
    }

    /** Unadmitted far demand must survive re-readable snapshot cache trimming. */
    void pinWindow(long centre) { pendingWindows.add(centre); }
    void unpinWindow(long centre) { pendingWindows.remove(centre); }

    /** Pending source ownership survives a todo being overtaken by another planner. */
    int pendingWindowReads() { return reads.pending(); }
    long pendingWindowBytes() { return WINDOW_READ_BYTES * reads.pending(); }
    boolean readyWindowReads() { return reads.peek() != null; }

    /** Collect owned results independently of fresh planning/admission; never opens another read. */
    void drainWindowReads(ServerLevel level) {
        // Results and delayed callbacks remain charged after cancellation. Decode at most one
        // window per call; archived snapshots enter the same bounded path after a clean restart.
        int put = 0;
        for (WindowRead r; (r = reads.peek()) != null && (put == 0 || put + r.cost() <= PUTS_PER_WINDOW); put += r.cost()) {
            long key = r.key();
            if (r.disk().skip() != null) {
                reading.remove(key);
                windowReads++;
                windowFailed |= r.disk().sourceFailure();
                if (absent.add(key)) windowAbsent++;
                reads.poll();
                continue;
            }
            DiskShots.Read current = r.disk();
            // The completed slot already owns the detached source. Resize/transfer that owner,
            // rather than demanding the same source/copies twice at a full shared budget.
            long known = diskSourceBytes(current) + diskPublicationBytes(current) + archivePublicationBytes(r.cached());
            try (var publication = reads.takeForPublication(r, known, NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level))) {
                if (publication == null) break;
                long before = retainedBytes();
                boolean restored = false;
                if (r.cached() != null && r.cached().isPresent()) {
                    try {
                        restored = restoreArchived(key, r.cached().get(), current);
                    } catch (RuntimeException error) {
                        Incoming.LOG.warn("Prepared window snapshot rejected for {}", new ChunkPos(key), error);
                    }
                }
                if (!restored) publishDisk(current);
                publication.releaseToCountedOwner(Math.max(0, retainedBytes() - before));
                reading.remove(key);
                windowReads++;
            }
        }
    }

    /** Detached Read arrays remain live while priming or a flash/archived copy is published. */
    private static long diskSourceBytes(DiskShots.Read read) {
        long bytes = 128 + 8L * read.states().length + 4L * read.heights().length;
        for (var section : read.states()) if (section != null) bytes += section.getSerializedSize();
        return bytes;
    }

    /** Archived codec scratch is not a full heap bound. Charge known section-domain publication before decoding. */
    private static long archivePublicationBytes(@Nullable java.util.Optional<net.minecraft.nbt.CompoundTag> cached) {
        if (cached == null || cached.isEmpty()) return 0;
        var tag = cached.get();
        int sections = tag.getInt("sections");
        // The decoder rejects this before allocating states. Keep normal fallback/error classification.
        if (sections < 1 || sections > 256) return 0;
        long bytes = snapshotMetadata(sections, tag.contains("sky"));
        boolean ruined = tag.getBoolean("ruined");
        for (int i = 0; i < sections; i++)
            bytes += tag.contains("s" + i) ? editableSectionBytes() : ChunkShot.sectionBytes(null, ruined);
        long old = tag.contains("old", net.minecraft.nbt.Tag.TAG_COMPOUND) ? storedOldBytes(tag.getCompound("old")) + 4L * 512 : 0;
        return bytes + old + heightTileBound(sections * 16) + tag.sizeInBytes();
    }

    /** Restored array field law, including prepaid lazy lookups (also conservative for older plans). */
    private static long storedOldBytes(net.minecraft.nbt.CompoundTag tag) {
        int sections = Math.max(0, Math.min(256, tag.getInt("sections")));
        return 64 + 4L * 512 + 4L * arrayLength(tag, "cells")
                + 8L * (arrayLength(tag, "old") + arrayLength(tag, "states") + arrayLength(tag, "fires"))
                + 4L * arrayLength(tag, "heights") + 8L * arrayLength(tag, "light")
                + 8L * (sections + arrayLength(tag, "outside") + arrayLength(tag, "outside_states")
                + arrayLength(tag, "fluids") + arrayLength(tag, "material") + Math.max(1, (sections + 63) / 64))
                + 6L * arrayLength(tag, "biomes") + 4L * (arrayLength(tag, "flash") + arrayLength(tag, "blown"));
    }

    private static int arrayLength(net.minecraft.nbt.CompoundTag tag, String key) {
        var value = tag.get(key);
        if (value instanceof net.minecraft.nbt.IntArrayTag array) return array.size();
        if (value instanceof net.minecraft.nbt.LongArrayTag array) return array.size();
        return value instanceof net.minecraft.nbt.ByteArrayTag array ? array.size() : 0;
    }

    /** null means retry with the same owned input; false means source validation rejected the archive. */
    @Nullable
    Boolean restoreArchived(ServerLevel level, long key, net.minecraft.nbt.CompoundTag data, DiskShots.Read current) {
        if (current.skip() != null) return false;
        try (var reservation = RuinMemory.reserveInputs(level, diskPublicationBytes(current)
                + archivePublicationBytes(java.util.Optional.of(data)))) {
            return reservation == null ? null : restoreArchived(key, data, current);
        }
    }

    /** Server-thread source validation shared by restart reads and live window read-ahead. */
    boolean restoreArchived(long key, net.minecraft.nbt.CompoundTag data, DiskShots.Read current) {
        if (current.skip() != null) return false;
        ChunkShot shot = ChunkShot.restore(data, this);
        if (shot.pos().toLong() != key || current.pos().toLong() != key)
            throw new IllegalArgumentException("Wrong archived snapshot coordinate");
        // A scarred neighbour needs its original world. Unscarred later edits use fresh data.
        var fresh = ChunkShot.fromDisk(current.primed(Blast.SERVER), applied.get(key), flashed.get(key),
                FlashScorch.marks(current.flashLayer(), eventId));
        if (!(current.scar() >= eventId && eventId > 0)
                && !java.util.Arrays.equals(shot.normalPrints(Blast.SERVER), fresh.normalPrints(Blast.SERVER))) return false;
        restoreShot(key, shot);
        return true;
    }

    private void readWindowDisk(ServerLevel level, long key, RuinWorkers.Priority priority) {
        var accept = reads.expect();
        DiskShots.read(DiskShots.Format.of(level), new ChunkPos(key), priority)
                .thenApply(disk -> new WindowRead(key, disk, null)).thenAccept(accept);
    }

    /**
     * Chunk will have no plan (preparation: beyond heavy-zone edge or not from disk); neighbors release snapshots without awaiting its plan.
     * If later planned (post-wave ruin queue), snapshots retaken.
     */
    void noPlan(long chunk) {
        pendingWindows.remove(chunk);
        if (planned.add(chunk)) {
            ChunkPos pos = new ChunkPos(chunk);
            forget(pos, 1, planned, this::retireBlast);
            forget(pos, RuinPlanner.REACH, planned, this::retireShot);
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
    @Nullable private RuinSnapshots archive;

    boolean archiveChecked, archivePending;
    @Nullable RetainedPlans restoredPlans;
    private java.util.concurrent.CompletableFuture<Void> closing = java.util.concurrent.CompletableFuture.completedFuture(null);

    void archive(RuinSnapshots snapshots) { archive = snapshots; archiveChecked = true; archivePending = false; }

    java.util.concurrent.CompletableFuture<Void> closeStorage() {
        closing = java.util.concurrent.CompletableFuture.allOf(closing,
                archive == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : archive.close(),
                restoredPlans == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : restoredPlans.closeStorage());
        return closing;
    }

    private void retireShot(long key) {
        ChunkShot shot = shots.get(key);
        if (shot == null || archive == null || archive.evictable(key, shot)) dropShot(key);
    }

    RuinSnapshots archive() { return archive; }

    void restoreShot(long key, ChunkShot shot) {
        putShot(key, shot);
        if (archive != null) archive.restored(key, shot);
        int[] motion = new int[256];
        System.arraycopy(shot.heights, ChunkShot.type(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING) * 256,
                motion, 0, 256);
        putHeights(key, motion);
    }

    private void putShot(long key, ChunkShot shot) {
        ChunkShot was = shots.putAndMoveToLast(key, shot);
        if (was != null && was != shot) inputs.retire(was.bytes);
        shotBytes += shot.bytes - (was == null ? 0 : was.bytes);
    }

    void dropShot(long key) {
        ChunkShot was = shots.remove(key);
        if (was != null) {
            inputs.retire(was.bytes);
            shotBytes -= was.bytes;
        }
    }

    /** Re-readable cache only: keep old changed blocks and shadow heights, and snapshots held by worker grids. */
    void trimShots(long bytesToFree, WorkClock clock) {
        long limit = Math.max(0, shotBytes - bytesToFree);
        int visits = shots.size();
        while (shotBytes > limit && visits-- > 0 && !shots.isEmpty() && clock.canStart()) {
            long unit = clock.begin();
            try {
                long key = shots.firstLongKey();
                boolean inUse = false;
                int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
                for (int dx = -RuinPlanner.REACH; !inUse && dx <= RuinPlanner.REACH; dx++)
                    for (int dz = -RuinPlanner.REACH; !inUse && dz <= RuinPlanner.REACH; dz++)
                        inUse = tasks.containsKey(ChunkPos.asLong(x + dx, z + dz))
                                || pendingWindows.contains(ChunkPos.asLong(x + dx, z + dz));
                if (inUse) shots.getAndMoveToLast(key);
                else {
                    ChunkShot shot = shots.get(key);
                    if (archive != null && !archive.evictable(key, shot)) shots.getAndMoveToLast(key);
                    else { dropShot(key); retireBlast(key); }
                }
            } finally { clock.end(unit); }
        }
    }

    private void retireBlast(long key) {
        Blast old = blasts.remove(key);
        if (old != null) {
            long bytes = RuinPlan.fragmentBytes(old);
            inputs.retire(bytes);
            blastBytes -= bytes;
        }
    }

    /** For tests: discard snapshots/fractures, as detonation ruins starting chunks anew. */
    void dropShotsForTest() {
        shots.clear();
        shotBytes = 0;
        for (long key : java.util.List.copyOf(blasts.keySet())) retireBlast(key);
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
        if (heights.containsKey(chunk)) return;
        var tile = new RuinHeights(firstAir);
        heights.put(chunk, tile);
        heightBytes += tile.bytes() + 48;
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
        putHeights(key, h);
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

    private static long heightTileBound(int range) {
        int bits = 64 - Long.numberOfLeadingZeros(Math.max(0, range));
        return 112L + 8L * ((256 * bits + 63) / 64);
    }

    /** Unique missing loaded tiles only, matching captureBand; no chunk acquisition or height arrays. */
    private long bandBytes(ServerLevel level, ChunkPos to) {
        int bx = net.minecraft.util.Mth.floor(d.burst().x) >> 4, bz = net.minecraft.util.Mth.floor(d.burst().z) >> 4;
        int steps = Math.max(Math.abs(to.x - bx), Math.abs(to.z - bz));
        long bytes = 0;
        int previousX = bx, previousZ = bz;
        for (int i = 0; i <= steps; i++) {
            int cx = bx + (int) Math.round((to.x - bx) * (double) i / Math.max(1, steps));
            int cz = bz + (int) Math.round((to.z - bz) * (double) i / Math.max(1, steps));
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                long key = ChunkPos.asLong(cx + dx, cz + dz);
                // The line is monotone on both axes. Any already visited tile in this 3x3 square
                // also lies in the previous square; avoid allocating a demand-sized deduplication set.
                if (i > 0 && Math.abs(cx + dx - previousX) <= 1 && Math.abs(cz + dz - previousZ) <= 1
                        || heights.containsKey(key)) continue;
                LevelChunk c = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
                if (c != null) bytes += heightTileBound(c.getHeight()) + 1024;
            }
            previousX = cx; previousZ = cz;
        }
        return bytes;
    }

    /** First pre-wave air over {@code MOTION_BLOCKING} (any thread); chunk absent from maps means world bottom. */
    private int height(int x, int z, int minY) {
        RuinHeights h = heights.get(ChunkPos.asLong(x >> 4, z >> 4));
        return h == null ? minY : h.get((z & 15) << 4 | (x & 15));
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

    /** Conservative retained-plan accounting for long disk settlement passes, including old blocks and marks. */
    long retainedBytes() {
        return inputs.retained(currentBytes());
    }

    private long currentBytes() {
        long bytes = shotBytes() + applied.size() * 640L + (planned.size() + stood.size()) * 32L;
        return bytes + blastBytes + appliedBytes + (restoredPlans == null ? 0 : restoredPlans.bytes());
    }

    /** Snapshots, heightmaps and flash-layer plans, toward {@link NuclearPrep#PLANS_CAP}. */
    long shotBytes() {
        return shotBytes + heightBytes + flashedBytes + (archive == null ? 0 : archive.bytes());
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
            // A background task owns its nine fragments through actual exit. Optional shared reuse
            // has a separately prepaid fixed capacity, including entries borrowed by other tasks.
            if (view != Blast.SERVER) {
                // Only permit-owning workers may borrow entries: eviction/close does not end their input lifetime.
                RuinFragmentCache cache = RuinResults.currentProducer() == null ? null : ctx.fragmentCache;
                if (cache != null) {
                    Blast hit = cache.get(pos.toLong(), RuinWindow.Stamp.of(w));
                    if (hit != null) return hit;
                }
                Blast fresh = Blast.solve(new RuinWindow(pos, minY, maxY, w, view), ctx.d, blockDamage, treeFall);
                if (cache != null) cache.put(pos.toLong(), fresh);
                return fresh;
            }
            Blast b = ctx.blasts.get(pos.toLong());
            if (b != null && b.stamp().same(RuinWindow.Stamp.of(w))) return b;
            b = Blast.solve(new RuinWindow(pos, minY, maxY, w, view), ctx.d, blockDamage, treeFall);
            ctx.retireBlast(pos.toLong());
            ctx.blasts.put(pos.toLong(), b);
            ctx.blastBytes += RuinPlan.fragmentBytes(b);
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
    @Nullable
    Grid grid(ServerLevel level, ChunkPos centre, boolean background) {
        return captureGrid(level, centre, background, Grid.R);
    }

    /**
     * For tests: chunk window from cached snapshots only (disk-read, {@link #putDisk}), for background solver:
     * {@link #shot} would replace in-memory chunk's disk snapshot with memory snapshot.
     */
    Grid cachedGridForTest(ServerLevel level, ChunkPos centre) {
        ChunkShot[] g = new ChunkShot[Grid.SIDE * Grid.SIDE];
        for (int dz = -Grid.R; dz <= Grid.R; dz++) {
            for (int dx = -Grid.R; dx <= Grid.R; dx++) g[(dz + Grid.R) * Grid.SIDE + dx + Grid.R] = shots.getAndMoveToLast(ChunkPos.asLong(centre.x + dx, centre.z + dz));
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
        pendingWindows.add(pos.toLong());
        Grid g = grid(level, pos, false);
        if (g == null) return false;
        for (int k = 0; k < 9; k++) {
            int dx = k % 3 - 1, dz = k / 3 - 1;
            if (g.at(dx, dz) == null || g.hasBlast(dx, dz)) continue;
            try (var reservation = RuinMemory.reserveInputs(level, RuinPlan.maxFragmentBytes(g))) {
                if (reservation == null) return false;
                g.blast(dx, dz);
            }
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

    /** Background-plan timeout, ticks: interrupt an expired attempt, then retry in the pool. */
    static final int TASK_AGE_LIMIT = 600;

    private final Long2ObjectOpenHashMap<Task> tasks = new Long2ObjectOpenHashMap<>();
    /** Result permits outlive Future cancellation until the actual callable exits. */
    private final java.util.IdentityHashMap<java.util.concurrent.Future<RuinPlan>, RuinResults.Lease> resultOwners = new java.util.IdentityHashMap<>();
    /** Chunks whose last background attempt failed or expired; preparation reports failure until a new attempt is admitted. */
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
        if (g == null) return false;
        if (g.at(0, 0) == null) throw new IllegalStateException("no snapshot of chunk " + pos);
        var future = submitPlanWorker(level, g, () -> {
            RuinWorkers.checkStop();
            Collapse c = Collapse.solve(g);
            RuinWorkers.checkStop();
            return RuinPlanner.finish(g, c);
        }, priority);
        if (future == null) return false;
        tasks.put(key, new Task(future, chunkId, level.getGameTime(), priority));
        failed.remove(key);
        pendingWindows.remove(key);
        return true;
    }

    /** Ruin and flash-layer jobs share this context's actual input owner. */
    <T> java.util.concurrent.Future<T> submitWorker(java.util.concurrent.Callable<T> task, RuinWorkers.Priority priority) {
        return RuinWorkers.submit(task, priority, inputs);
    }

    /** Reserve output before the solver can allocate it, including completed/uncollected results. */
    @Nullable
    java.util.concurrent.Future<RuinPlan> submitPlanWorker(ServerLevel level, Grid grid,
            java.util.concurrent.Callable<RuinPlan> task, RuinWorkers.Priority priority) {
        int height = level.getHeight();
        for (ServerLevel other : level.getServer().getAllLevels()) height = Math.max(height, other.getHeight());
        // A queued output permit must still leave enough shared room to start a solver after idle expiry.
        long room = NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level) - RuinWorkers.threadBufferBytes(height);
        RuinResults.Lease owner = RuinResults.tryReserve(RuinPlan.maxResultBytes(grid), RuinPlan.maxFragmentBytes(grid), room);
        if (owner == null) return null;
        try {
            ensureFragmentCache(level, height);
            var future = RuinWorkers.submit(() -> {
                RuinPlan result = RuinResults.producing(owner, task);
                owner.markResult(result);
                return result;
            }, priority, inputs, owner::actualExit);
            owner.bindFuture(future);
            resultOwners.put(future, owner);
            return future;
        } catch (RuntimeException | Error failure) {
            owner.abortBeforeSubmit();
            throw failure;
        }
    }

    private void ensureFragmentCache(ServerLevel level, int height) {
        if (fragmentCache != null || fragmentCacheClosed || fragmentCacheLimit == 0) return;
        // Optional reuse cannot borrow the decoder/publication transition or the first solver's workspace.
        long room = NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level)
                - RuinWorkers.threadBufferBytes(height) - RuinMemory.TRANSITION_ROOM;
        var budget = RuinResults.tryReserveCache(fragmentCacheLimit, room);
        if (budget != null) fragmentCache = new RuinFragmentCache(fragmentCacheLimit, budget);
    }

    long fragmentCacheBytes() {
        RuinFragmentCache cache = fragmentCache;
        return cache == null ? 0 : cache.reservedBytes();
    }

    /** Finished callable only: transfer its result permit to the returned plan. */
    RuinPlan takeWorker(java.util.concurrent.Future<RuinPlan> future)
            throws java.util.concurrent.ExecutionException, InterruptedException {
        RuinResults.Lease owner = resultOwners.remove(future);
        try {
            return future.get();
        } catch (java.util.concurrent.ExecutionException | InterruptedException | java.util.concurrent.CancellationException failure) {
            if (owner != null) owner.cancel();
            throw failure;
        } finally {
            if (owner != null) owner.detachFuture();
        }
    }

    /** Abandon output, but a running producer retains its permit through actual exit. */
    void cancelWorker(java.util.concurrent.Future<RuinPlan> future, boolean interrupt) {
        RuinResults.Lease owner = resultOwners.remove(future);
        if (owner != null) owner.cancel();
        future.cancel(interrupt);
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
        RuinResults.Lease owner = resultOwners.get(t.future);
        if (owner != null) owner.beginTransfer();
        java.util.concurrent.Future<RuinPlan> moved = RuinWorkers.promote(t.future, priority);
        if (owner != null) {
            owner.bindFuture(moved == null ? t.future : moved);
            if (moved != null) { resultOwners.remove(t.future); resultOwners.put(moved, owner); }
        }
        if (moved != null) tasks.put(chunk, new Task(moved, t.chunkId, t.since, priority));
    }

    /** Background plans running or ready/uncollected. */
    int tasks() {
        return tasks.size();
    }

    /** Background chunk plan ready (collect in one work unit). */
    boolean done(long chunk) {
        Task t = tasks.get(chunk);
        return t != null && RuinWorkers.finished(t.future);
    }

    /** Last admitted background attempt failed or expired; never authorizes a synchronous solver. */
    boolean failed(long chunk) {
        return failed.contains(chunk);
    }

    /** Applied positions changed: discard completion markers and retry from validated current snapshots. */
    void retry(long chunk) {
        planned.remove(chunk);
        doneAt.remove(chunk);
        pendingWindows.add(chunk);
    }

    /**
     * Interrupt background work after its running deadline. Keep the task/window owned until the
     * worker actually exits: Future cancellation alone must not admit overlapping replacement work.
     * A queued task's deadline starts when it begins running, not when it entered the pool.
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
            cancelWorker(t.future, true);
            failed.add(e.getLongKey());
            RuinWorkers.expired();
            Incoming.LOG.warn("Ruins for detonation #{}: background plan for chunk {} not ready in {} ticks — interrupted; awaiting worker exit before retry",
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
        if (t != null && t.chunkId != 0 && RuinWorkers.finished(t.future) && System.identityHashCode(chunk) != t.chunkId) {
            // Observe the completed terminal outcome before replacing a stale instance. An explicit
            // collector must still see failure/cancellation rather than a successful retry hiding it.
            try { t.future.get(); } // actual execution is finished above, so this never waits
            catch (java.util.concurrent.ExecutionException | InterruptedException | java.util.concurrent.CancellationException error) {
                return collect(chunk.getPos());
            }
            // chunk unloaded/reloaded: successful snapshots from a different chunk
            tasks.remove(chunk.getPos().toLong());
            cancelWorker(t.future, false);
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
        if (t == null || !RuinWorkers.finished(t.future)) return null;
        tasks.remove(key);
        RuinPlan plan;
        try {
            plan = takeWorker(t.future);
        } catch (java.util.concurrent.ExecutionException e) {
            failed.add(key);
            // solver error repeats across many chunks: log first occurrences
            if (failures++ < 3) Incoming.LOG.error("Ruins for detonation #{}: background plan for chunk {} failed; leaving the chunk pending for background retry", d.id(), pos, e.getCause());
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
        pendingWindows.remove(chunk);
        Task t = tasks.get(chunk);
        if (t != null && t.chunkId != 0) {
            tasks.remove(chunk);
            cancelWorker(t.future, false);
        }
        dropShot(chunk);
    }

    /** A new owner will request current windows; old callbacks retain reservations until they finish. */
    void retireWindowReads() {
        pendingWindows.clear();
        reads.invalidate();
        reading.clear();
    }

    /** Background plans no longer needed (cancel, world unload): abandon tasks. */
    void cancelTasks() {
        fragmentCacheClosed = true;
        if (fragmentCache != null) fragmentCache.close();
        // Transfer before cancel: queued callbacks may release immediately; running callbacks may not.
        inputs.detach(currentBytes());
        for (Task t : tasks.values()) cancelWorker(t.future, false);
        tasks.clear();
        for (var future : java.util.List.copyOf(resultOwners.keySet())) cancelWorker(future, false);
        retireWindowReads();
        closeStorage();
        if (restoredPlans != null) { restoredPlans.clear(); restoredPlans = null; }
        archive = null;
    }

    /** Chunk plan built: release fractures needed only by surrounding plans. */
    void planned(ChunkPos pos) {
        pendingWindows.remove(pos.toLong());
        planned.add(pos.toLong());
        forget(pos, 1, planned, this::retireBlast);
        forget(pos, RuinPlanner.REACH, planned, this::retireShot);
    }

    /** Chunk ruins applied from plan. */
    void applied(LevelChunk chunk, RuinPlan plan, short[] motion) {
        ChunkPos pos = chunk.getPos();
        Applied old = applied.put(pos.toLong(), new Applied(plan, motion));
        appliedBytes += plan.bytes() - (old == null || old.plan == null ? 0 : old.plan.bytes());
        plan.releaseResultToCountedOwner();
        finished(pos);
    }

    /** Empty plans also complete their neighbors' old-world retention window. */
    void finished(ChunkPos pos) {
        pendingWindows.remove(pos.toLong());
        planned.add(pos.toLong());
        stood.add(pos.toLong());
        forget(pos, 1, stood, this::retireBlast);
        forget(pos, RuinPlanner.REACH, stood, this::retireShot);
        forget(pos, 2, stood, k -> {
            Applied a = applied.remove(k);
            if (a != null) {
                if (a.plan != null) appliedBytes -= a.plan.bytes();
                // Every centre whose window could need these old cells is already settled.
                // Keep one packed shadow tile, never a second short[256] per completed chunk.
                if (!heights.containsKey(k)) {
                    int[] original = new int[256];
                    for (int i = 0; i < original.length; i++) original[i] = a.motion[i];
                    putHeights(k, original);
                }
            }
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
