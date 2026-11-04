package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
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
import ua.zentix.incoming.registry.ModAttachments;
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
    /** Keep write-encoding room free: a full read window must still be able to spill. */
    private static final long WINDOW_CAP = PLANS_CAP - (32L << 20);
    /** Concurrent disk reads and estimated memory per read (parsed city chunk tens of KB). */
    private static final int READS = 64;
    private static final long READ_BYTES = 96L << 10;
    private static final long SPILL_READ_BYTES = (4L << 20) + READ_BYTES;
    private record ArchivedShot(long key, java.util.Optional<net.minecraft.nbt.CompoundTag> data, DiskShots.Read current) {}
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
        int strike;
        long detonateTime;
        @Nullable
        Detonation geometry;
        /** Zone layout ({@link #layout}) while built in background; null if ready or not started. */
        @Nullable
        CompletableFuture<Layout> layout;
        /** Geometry exists, but layout admission/installation is still required. */
        boolean layoutRequired;
        /** Immutable reverse-index and tile backing ownership, calculated once by its builder. */
        long layoutMetadata;
        /** Heavy-zone chunks nearest first (zone behind wave). */
        long[] order = new long[0];
        /** In-memory chunks within ruin radius nearest first: prebuild plans. */
        final LongArrayList todo = new LongArrayList();
        int nextTodo;
        final MetadataSet considered = new MetadataSet();
        /** All chunks ever in memory within ruin radius during flight (denominator in “ready”/“pre-built ruins” lines). */
        final MetadataSet everInMemory = new MetadataSet();
        /** Plans for in-memory chunks (near preparation). */
        int nearPlans;
        /** Launch-critical target and loaded surroundings; far physical work continues independently. */
        final MetadataSet required = new MetadataSet();
        boolean requiredInitialized;
        long requiredTarget;
        /** Chunks with background plans running. */
        final LongArrayList submitted = new LongArrayList();
        long nextRefresh;
        boolean capped;
        String archivePath = "";
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
        final RetainedPlans plans = new RetainedPlans();
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
        final MetadataSet reading = new MetadataSet();
        final java.util.concurrent.atomic.AtomicLong asyncBytes = new java.util.concurrent.atomic.AtomicLong();
        final java.util.function.LongConsumer accounting;
        final RuinInbox<DiskShots.Read> reads;
        final RuinInbox<DiskShots.Read> terrainReads;
        boolean resolvingRead;
        final RuinInbox<ArchivedShot> archived;
        /** Chunks absent as fully generated disk chunks: no preplans for them/neighbors. */
        final MetadataSet unreadable = new MetadataSet();
        /** Unread reasons ({@link DiskShots.Read#skip} before colon), for “ready” line. */
        final MetadataReasons unreadWhy = new MetadataReasons();
        /** {@link #order} cursors: submitted window reads and plans. */
        int nextRead, nextFar;
        /** Chunks whose disk-snapshot plans background threads build. */
        final LongArrayList farRunning = new LongArrayList();
        int farPlans, diskShots, farSkipped;
        boolean farInitialized;
        /** {@link #order} cursor: wave-reached chunks submitted to distant LODs ({@link FarLods}). */
        int nextLod;
        /** Zone LOD copies from ready disk plans, into DH with front. */
        final FrontLods lods;

        UUID handle, owner;
        final UUID cacheId = UUID.randomUUID();
        Vec3 target, resolvedTarget;
        ua.zentix.incoming.strike.WeaponType carrierWeapon;
        Vec3 miss = Vec3.ZERO;
        Double terminalGround, terminalCanopy;
        double yieldKt;
        boolean surface, airBurst, bound, carrier, noRuins, archiveWritten;
        long expires;
        int generation;
        String configuration;
        RuinPreparation.Reason failure = RuinPreparation.Reason.NONE;

        Prep(int strike, long detonateTime, java.util.function.LongConsumer accounting) {
            this.strike = strike;
            this.detonateTime = detonateTime;
            var owned = asyncBytes; // Detached callbacks must not retain Prep or its world.
            this.accounting = delta -> { owned.addAndGet(delta); accounting.accept(delta); };
            this.lods = new FrontLods(strike, this.accounting);
            this.reads = new RuinInbox<>(this.accounting, READ_BYTES);
            this.terrainReads = new RuinInbox<>(this.accounting, READ_BYTES);
            this.archived = new RuinInbox<>(this.accounting, SPILL_READ_BYTES);
        }
    }

    // The completion retains this counter, never the abandoned preparation/world.
    private final java.util.concurrent.atomic.AtomicLong readBytes = new java.util.concurrent.atomic.AtomicLong();
    java.util.function.LongConsumer readAccounting() { return readBytes::addAndGet; }

    private CompletableFuture<String> policy;
    private int policyGeneration = -1;
    private final List<Prep> preps = new ArrayList<>();
    // Per-world construction function permits controlled execution ownership checks without replacing the facade.
    private java.util.function.Function<Detonation, Layout> layoutBuilder = NuclearPrep::layout;
    /** Optional cache hints outlive a drained preparation while its ledger is frozen. */
    private final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<String> archiveHints = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    /** Strikes whose preparation failed: do not restart (otherwise error logged every tick). */
    private final java.util.Set<Integer> failed = new java.util.HashSet<>();
    private final WorkClock clock = WorkClock.decaying(0.5);
    private int reclaimNext;
    /** Re-readable cache policy; tiny owned-test caches exercise eviction without a large synthetic heap. */
    private long shotCacheLimit = 64L << 20;

    // A server-wide handle limit bounds metadata as well as the shared byte admission budget.
    static final int REQUEST_LIMIT = 8;
    static final long LEASE_TICKS = 1200;
    private int nextHandleStrike = -2;

    void prepare(ServerLevel level, UUID handle, UUID owner, Vec3 target, double yieldKt, boolean surface, boolean airBurst) {
        java.util.Objects.requireNonNull(handle);
        java.util.Objects.requireNonNull(owner);
        java.util.Objects.requireNonNull(target);
        Prep old = request(handle);
        if (old != null) {
            if (!owner.equals(old.owner) || old.bound) return;
            if (compatible(old) && old.failure == RuinPreparation.Reason.NONE && level.getGameTime() < old.expires
                    && old.target.equals(target) && old.yieldKt == yieldKt && old.surface == surface && old.airBurst == airBurst) {
                keepAlive(level, handle, owner);
                return;
            }
            old.draining = true;
        }
        int count = 0;
        for (ServerLevel other : level.getServer().getAllLevels()) {
            if (!other.hasData(ModAttachments.NUCLEAR_WORLD)) continue;
            for (Prep p : NuclearWorld.get(other).prep().preps) {
                if (p.handle == null) continue;
                if (!p.draining && p.handle.equals(handle)) return; // Handles cannot migrate between dimensions.
                if (p.owner.equals(owner) && !p.bound) p.draining = true;
                count++;
            }
        }
        if (count >= REQUEST_LIMIT) return;
        Prep p = new Prep(nextHandleStrike--, Long.MAX_VALUE, readBytes::addAndGet);
        p.handle = handle;
        p.owner = owner;
        p.target = target;
        p.yieldKt = yieldKt;
        p.surface = surface;
        p.airBurst = airBurst;
        p.expires = level.getGameTime() + LEASE_TICKS;
        p.generation = Blast.generation;
        p.configuration = configuration();
        if (!Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z)
                || Math.abs(target.x) > 29_999_984 || Math.abs(target.z) > 29_999_984
                || !Double.isFinite(yieldKt) || yieldKt <= 0 || yieldKt > IncomingConfig.SERVER.nukeMaxYield.get())
            p.failure = RuinPreparation.Reason.INVALID;
        preps.add(p);
    }

    void prepareCarrier(ServerLevel level, UUID handle, UUID owner, Vec3 target, double yieldKt, boolean surface,
                        boolean airBurst, ua.zentix.incoming.strike.WeaponType weapon) {
        if (!ua.zentix.incoming.strike.Loadout.carriesNuke(weapon)) throw new IllegalArgumentException("Not a nuclear carrier");
        Prep old = request(handle);
        prepare(level, handle, owner, target, yieldKt, surface, airBurst);
        Prep p = request(handle);
        if (p == null || p == old) return; // Repeated PREPARE never redraws route error.
        p.carrierWeapon = weapon;
        p.miss = weapon.spec().route().miss(level.random);
    }

    @Nullable Vec3 carrierMiss(UUID handle, ua.zentix.incoming.strike.WeaponType weapon) {
        Prep p = request(handle);
        return p == null || p.bound || p.carrierWeapon != weapon ? null : p.miss;
    }

    /** Existing impact(aim) branch only; an earlier collision keeps its own fresh geometry. */
    private static double terminalGround(Prep p, double ground, double canopy, boolean ceiling, int bottom) {
        double aimY = ceiling ? p.target.y
                : p.carrierWeapon == ua.zentix.incoming.strike.WeaponType.BUNKER ? ground - 0.5
                : p.carrierWeapon != null && p.surface ? canopy - 0.5 : p.target.y;
        double result = ceiling ? aimY : p.carrierWeapon == null && p.surface ? ground : Math.min(ground, aimY);
        // Missile impact first calls NuclearStrikes.ground: the warhead's bottom fallback receives
        // that already-clamped point. B-2 and scheduled geometry pass their terminal input directly.
        if (p.carrierWeapon == ua.zentix.incoming.strike.WeaponType.MISSILE && !ceiling) return result;
        return result <= bottom ? aimY : result;
    }

    private Prep request(UUID handle) {
        for (Prep p : preps) if (handle.equals(p.handle) && !p.draining) return p;
        return null;
    }

    private static String configuration() {
        return IncomingConfig.SERVER.nukeMaxYield.get() + ":" + IncomingConfig.SERVER.effectsScale() + ":" + IncomingConfig.SERVER.waveSpeed() + ":"
                + IncomingConfig.SERVER.nukeBlockDamage.get() + ":" + IncomingConfig.SERVER.nukeTreeFall.get()
                + ":" + IncomingConfig.SERVER.nukeFires.get();
    }

    private static boolean compatible(Prep p) {
        return p.generation == Blast.generation && p.configuration.equals(configuration());
    }

    boolean keepAlive(ServerLevel level, UUID handle, UUID owner) {
        Prep p = request(handle);
        if (p == null || !owner.equals(p.owner) || !p.bound && level.getGameTime() >= p.expires) return false;
        p.expires = level.getGameTime() + LEASE_TICKS;
        return true;
    }

    void cancel(UUID handle) {
        Prep p = request(handle);
        if (p != null) { p.draining = true; if (p.bound) failed.add(p.strike); }
    }

    void cancelOwner(UUID owner) {
        for (Prep p : preps) if (owner.equals(p.owner) && !p.bound) p.draining = true;
    }

    RuinPreparation.Status status(ServerLevel level, UUID handle) {
        Prep p = request(handle);
        if (p == null) return new RuinPreparation.Status(RuinPreparation.Stage.ABSENT, 0, 0, 0, 0, 0, 0,
                RuinPreparation.Reason.NONE, null);
        var reason = p.failure;
        var stage = RuinPreparation.Stage.PLANNING;
        if (!p.bound && level.getGameTime() >= p.expires) reason = RuinPreparation.Reason.EXPIRED;
        if (!compatible(p)) reason = RuinPreparation.Reason.INCOMPATIBLE;
        if (p.geometry != null && p.detonation < 0) {
            Vec3 at = p.target.add(p.miss);
            var under = Terrain.estimate(level, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    Mth.floor(at.x), Mth.floor(at.z), Terrain.Allowed.CHUNK);
            if (under.source() == Terrain.Source.CHUNK) {
                var canopy = Terrain.estimate(level, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                        Mth.floor(at.x), Mth.floor(at.z), Terrain.Allowed.CHUNK);
                double ground = terminalGround(p, under.y(), canopy.y(), false, level.getMinBuildHeight());
                if (ground != p.geometry.groundY()) reason = RuinPreparation.Reason.INCOMPATIBLE;
            }
        }
        int writing = p.plans.pendingWrites() + (p.ruins == null || p.ruins.archive() == null ? 0 : p.ruins.archive().pendingWrites());
        int windowReads = p.ruins == null ? 0 : p.ruins.pendingWindowReads();
        int reading = p.reading.size() + p.plans.pendingReads() + (p.resolvingRead ? 1 : 0) + windowReads;
        int planning = p.farRunning.size() + p.submitted.size();
        long complete = p.plans.size() + (long) p.farSkipped;
        long total = p.order.length;
        if (p.requiredInitialized) {
            complete = 0;
            writing = 0;
            total = p.required.size();
            for (long key : p.required) {
                if (p.plans.containsKey(key) || key == p.requiredTarget && p.unreadable.contains(key)
                        && level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) == null) complete++;
                if (p.plans.pendingWrite(key)) writing++;
            }
        }
        if (reason != RuinPreparation.Reason.NONE) stage = RuinPreparation.Stage.FAILED;
        else if (p.heapStop && !p.bound) { stage = RuinPreparation.Stage.FAILED; reason = RuinPreparation.Reason.MEMORY; }
        else if (p.noRuins && p.geometry != null) stage = RuinPreparation.Stage.READY;
        else if (p.geometry == null && memory(level) >= WINDOW_CAP) {
            stage = RuinPreparation.Stage.PAUSED; reason = RuinPreparation.Reason.MEMORY;
        }
        else if (p.geometry == null) { stage = RuinPreparation.Stage.RESOLVING; reason = RuinPreparation.Reason.RESOLVING; }
        else if (p.layout != null) stage = RuinPreparation.Stage.PLANNING;
        else if (p.layoutRequired) {
            stage = RuinPreparation.Stage.PAUSED; reason = RuinPreparation.Reason.MEMORY;
        }
        else if (p.requiredInitialized && complete == total && windowReads == 0) {
            stage = writing == 0 ? RuinPreparation.Stage.READY : RuinPreparation.Stage.STORING;
        } else if (p.heapStop || memory(level) >= WINDOW_CAP) {
            stage = RuinPreparation.Stage.PAUSED;
            reason = RuinPreparation.Reason.MEMORY;
        }
        long resident = p.asyncBytes.get() + p.plans.bytes() + p.lods.retainedBytes()
                + metadataBytes(p)
                + (p.ruins == null ? 0 : p.ruins.retainedBytes() + p.ruins.pendingWindowBytes());
        return new RuinPreparation.Status(stage, Math.max(0, complete - writing), total, reading, planning,
                writing, resident, reason, p.resolvedTarget);
    }

    boolean bind(ServerLevel level, UUID handle, int strikeId) {
        Prep p = request(handle);
        if (p == null || p.carrierWeapon != null) return false;
        if (p.bound) return p.strike == strikeId && compatible(p) && p.failure == RuinPreparation.Reason.NONE;
        if (p.requiredInitialized && !p.bound) refresh(level, p);
        if (status(level, handle).stage() != RuinPreparation.Stage.READY) return false;
        var s = NuclearEvents.get(level).scheduled().stream().filter(x -> x.id() == strikeId).findFirst().orElse(null);
        if (s == null || s.owner().isEmpty() || !s.owner().get().equals(p.owner) || s.yieldKt() != p.yieldKt
                || s.surface() != p.surface || s.airBurst() != p.airBurst
                || !s.target().equals(p.resolvedTarget) || preps.stream().anyMatch(x -> x != p && x.strike == strikeId)) return false;
        p.strike = strikeId;
        p.detonateTime = s.detonateTime();
        p.bound = true;
        return true;
    }

    int bindCarrier(ServerLevel level, UUID handle, UUID owner, Vec3 target, double yieldKt, boolean surface, boolean airBurst) {
        Prep p = request(handle);
        if (p == null || p.bound || !owner.equals(p.owner) || !target.equals(p.resolvedTarget)
                || yieldKt != p.yieldKt || surface != p.surface || airBurst != p.airBurst) return -1;
        if (p.requiredInitialized) refresh(level, p);
        if (status(level, handle).stage() != RuinPreparation.Stage.READY) return -1;
        p.strike = NuclearEvents.get(level).nextId();
        p.carrier = true;
        p.bound = true;
        return p.strike;
    }

    void cancelCarrier(int identity) {
        for (Prep p : preps) if (p.carrier && p.detonation < 0 && p.strike == identity) p.draining = true;
    }

    /** Total prebuilt ruins across strikes (tests, status). */
    public int plannedChunks() {
        return preps.stream().mapToInt(p -> p.plans.size()).sum();
    }

    /** No preparations: release ruin-solver buffers. */
    public boolean idle() {
        return preps.isEmpty() && archiveHints.isEmpty();
    }

    /** Chunk unloaded: background plan unnecessary. */
    void forget(long chunk) {
        for (Prep p : preps) {
            // Explicit handles retain their collector/context until the actual task can be harvested.
            if (p.handle == null) p.submitted.rem(chunk);
            // before detonation preparation ruins outside NuclearWorld: preparation removes task itself
            if (p.handle == null && p.ruins != null && p.detonation < 0) p.ruins.forget(chunk);
            p.considered.remove(chunk);
        }
    }

    /** Retained preparation estimates; handed-off contexts are counted by NuclearWorld, not twice here. */
    long retainedBytes() {
        long bytes = readBytes.get() + 64L * archiveHints.size();
        for (String path : archiveHints.values()) bytes += 2L * path.length();
        for (Prep p : preps) {
            bytes += p.plans.bytes() + p.lods.retainedBytes();
            if (p.ruins != null && p.detonation < 0) bytes += p.ruins.retainedBytes();
            bytes += metadataBytes(p);
        }
        return bytes;
    }

    /** One server-wide preparation admission estimate; multiple strikes/dimensions share the cap. */
    static long memory(ServerLevel level) {
        return memory(level, true);
    }

    /** Already-owned bytes only: cold-load publication cannot recursively start another admission. */
    static long residentMemory(ServerLevel level) {
        long bytes = RuinInputs.detachedBytes() + RuinResults.bytes();
        int height = level.getHeight();
        for (ServerLevel other : level.getServer().getAllLevels()) {
            height = Math.max(height, other.getHeight());
            if (other.hasData(ModAttachments.NUCLEAR_WORLD))
                bytes += other.getData(ModAttachments.NUCLEAR_WORLD).retainedRuinBytes();
        }
        return bytes + RuinWorkers.residentBufferBytes(height);
    }

    /** Existing cache writes can release counted ownership. Reserving future solvers here would
     * block the snapshot spill needed to make room for those same solvers and material. */
    private static long storageRoom(ServerLevel level) {
        return Math.max(0, PLANS_CAP - residentMemory(level));
    }

    static long memory(ServerLevel level, boolean reserveWorkers) {
        long bytes = RuinInputs.detachedBytes() + RuinResults.bytes();
        int height = level.getHeight();
        boolean cold = false;
        for (ServerLevel other : level.getServer().getAllLevels()) {
            height = Math.max(height, other.getHeight());
            NuclearWorld world = NuclearWorld.get(other);
            bytes += world.retainedRuinBytes();
            cold |= world.ledgers.pending();
        }
        RuinWorkers.configureHeight(height);
        // Unknown old ownership has priority over new preparation/removals. Import uses only the
        // already-resident counter above and takes its reservation before decoding anything.
        if (cold) return Math.max(bytes, PLANS_CAP);
        return bytes + (reserveWorkers ? RuinWorkers.bufferBytes(height) + RuinMemory.TRANSITION_ROOM
                : RuinWorkers.residentBufferBytes(height));
    }

    /** Squares held by tickets (tests). */
    public int heldTiles() {
        return (int) preps.stream().flatMap(p -> p.tiles.stream()).filter(t -> t.state == TileState.LOADING || t.state == TileState.READY).count();
    }

    // ---------------------------------------------------------------- tick

    /**
     * One bounded opportunity to retire already-owned work, before its consumers spend the lane.
     * Rotate sources, completed solvers and cache receipts: a memory-blocked head must not prevent
     * the reclamation that funds it. This never opens fresh reads or admits another solver.
     */
    void reclaim(ServerLevel level, WorkClock shared) {
        int slots = preps.size() * 4;
        for (int checked = 0; checked < slots && shared.canStart(); checked++) {
            int slot = Math.floorMod(reclaimNext++, slots);
            Prep p = preps.get(slot / 4);
            if (p.draining || p.detonation < 0 || p.ruins == null) continue;
            switch (slot % 4) {
                case 0 -> {
                    ArchivedShot head = p.archived.peek();
                    if (head == null) continue;
                    long began = shared.begin();
                    try { acceptArchived(level, p, head); }
                    finally { shared.end(began); }
                }
                case 1 -> {
                    DiskShots.Read head = p.reads.peek();
                    if (head == null) continue;
                    long began = shared.begin();
                    try { acceptRead(level, p, head); }
                    finally { shared.end(began); }
                }
                case 2 -> {
                    int ready = -1;
                    for (int i = 0; i < p.farRunning.size(); i++) {
                        long key = p.farRunning.getLong(i);
                        if (!p.ruins.running(key) || p.ruins.done(key)) { ready = i; break; }
                    }
                    if (ready < 0) continue;
                    long began = shared.begin();
                    try { collectFar(level, p, ready); }
                    finally { shared.end(began); }
                }
                default -> {
                    if (p.ruins.shotBytes() <= shotCacheLimit) continue;
                    // A small receipt/cache batch, rather than a walk of every pinned city snapshot.
                    p.ruins.trimShots(8L << 20, shared, 16);
                }
            }
            return;
        }
    }

    /**
     * Budgeted preparation: own deadline (shared-budget fraction {@link WorkScheduler#PREP_SHARE}) and nuclear-lane deadline.
     *
     * @param shared shared tick clock (already started)
     */
    public void tick(ServerLevel level, NuclearEvents events, ScarQueue scars, WorkClock shared) {
        long now = level.getGameTime();
        flushArchiveHints(level);
        long cap = WorkScheduler.prepCapNanos();
        // new strikes: preparation from launch
        for (NuclearEvents.ScheduledStrike s : events.scheduled()) {
            if (!IncomingConfig.SERVER.nukeBlockDamage.get()) break;
            if (s.detonateTime() - now < MIN_LEAD || failed.contains(s.id()) || preps.stream().anyMatch(p -> p.strike == s.id())) continue;
            // one area, one preparation: second strike at same places prebuilds no ruins (single-zone memory)
            if (preps.stream().anyMatch(p -> p.geometry != null && p.detonation < 0
                    && Math.hypot(p.geometry.burst().x - s.target().x, p.geometry.burst().z - s.target().z) < 2 * heavyRadius(p.geometry) + 64)) continue;
            preps.add(new Prep(s.id(), s.detonateTime(), readBytes::addAndGet));
        }
        clock.start(Math.max(1_000_000L, cap));
        for (Iterator<Prep> it = preps.iterator(); it.hasNext(); ) {
            Prep p = it.next();
            try {
                if (p.draining) {
                    discardPreparation(p);
                    p.lods.clear(level);
                    // before detonation preparation tasks not handed off: abandon
                    if (p.ruins != null && p.detonation < 0) p.ruins.cancelTasks();
                    if (releaseSome(level, p, RELEASE_PER_TICK) == 0) it.remove();
                    continue;
                }
                p.plans.pump(storageRoom(level));
                if (p.detonation >= 0) {
                    rememberArchive(level, p);
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
                if (p.handle != null && !compatible(p)) {
                    p.failure = RuinPreparation.Reason.INCOMPATIBLE;
                    if (p.bound) { p.draining = true; continue; }
                }
                if (p.handle != null && !p.bound) {
                    if (now >= p.expires) { p.draining = true; continue; }
                    if (!compatible(p)) p.failure = RuinPreparation.Reason.INCOMPATIBLE;
                    if (p.failure == RuinPreparation.Reason.NONE && (p.geometry != null || !IncomingConfig.SERVER.nukeBlockDamage.get() || memory(level) < WINDOW_CAP)) work(level, null, p, shared);
                    continue;
                }
                if (p.carrier) {
                    work(level, null, p, shared);
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
                if (p.handle != null) p.failure = RuinPreparation.Reason.IO;
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
            String content = IncomingConfig.SERVER.nukeBlockDamage.get() ? content(level) : "";
            if (content == null) return;
            if (p.handle == null) {
                // Existing scheduled-flight preparation retains its loaded target behavior.
                if (!Terrain.ready(level, BlockPos.containing(s.target()))) return;
                p.geometry = NuclearWarhead.geometry(level, s.surface() ? NuclearWarhead.surfaceAt(level, s.target()) : s.target(), s.yieldKt(), s.airBurst(),
                        (float) IncomingConfig.SERVER.effectsScale());
            } else {
                var noLeaves = net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
                Vec3 at = p.target.add(p.miss);
                double ground;
                if (p.carrierWeapon == null) {
                    Terrain.Surface under = resolveSurface(level, p, shared);
                    if (under == null) return;
                    ground = terminalGround(p, under.y(), under.y(), under.source() == Terrain.Source.CEILING,
                            level.getMinBuildHeight());
                    p.resolvedTarget = p.surface ? new Vec3(p.target.x, ground, p.target.z) : p.target;
                } else {
                    if (p.resolvedTarget == null) {
                        Terrain.Surface original = resolveSurface(level, p, shared, p.target, noLeaves);
                        if (original == null) return;
                        double y = original.source() == Terrain.Source.CEILING ? p.target.y : original.y();
                        if (y <= level.getMinBuildHeight()) y = p.target.y;
                        p.resolvedTarget = p.surface ? new Vec3(p.target.x, y, p.target.z) : p.target;
                    }
                    if (p.terminalGround == null) {
                        Terrain.Surface under = resolveSurface(level, p, shared, at, noLeaves);
                        if (under == null) return;
                        p.terminalGround = (double) under.y();
                    }
                    if (p.terminalCanopy == null) {
                        Terrain.Surface canopy = resolveSurface(level, p, shared, at,
                                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING);
                        if (canopy == null) return;
                        p.terminalCanopy = (double) canopy.y();
                    }
                    ground = terminalGround(p, p.terminalGround, p.terminalCanopy,
                            level.dimensionType().hasCeiling(), level.getMinBuildHeight());
                }
                float scale = (float) IncomingConfig.SERVER.effectsScale();
                double hob = p.airBurst ? ua.zentix.incoming.nuclear.model.Yield.optimalBurstHeight(p.yieldKt) * scale : 0;
                boolean surfaceBurst = hob / scale < ua.zentix.incoming.nuclear.model.FireballModel.maxRadius(p.yieldKt, true);
                float visibility = (float) (level.isThundering() ? ua.zentix.incoming.nuclear.model.ThermalModel.VISIBILITY_THUNDER
                        : level.isRaining() ? ua.zentix.incoming.nuclear.model.ThermalModel.VISIBILITY_RAIN
                        : ua.zentix.incoming.nuclear.model.ThermalModel.VISIBILITY_CLEAR);
                p.geometry = new Detonation(-1, new Vec3(at.x, ground + hob, at.z), ground, p.yieldKt,
                        surfaceBurst, 0, 0, 0, visibility, 0, scale, false, (float) IncomingConfig.SERVER.waveSpeed());
            }
            p.noRuins = !IncomingConfig.SERVER.nukeBlockDamage.get();
            if (p.noRuins) return; // Truthful empty work: flash/radiation-only mode has no ruin zone.
            p.layoutRequired = true;
        }
        if (p.noRuins) return;
        if (p.layoutRequired && p.layout == null) {
            long reservation = layoutReservation(p.geometry);
            if (reservation > WINDOW_CAP - memory(level)) return;
            String content = content(level);
            if (content == null) return;
            var build = new LayoutBuild(p.geometry, layoutBuilder, p.accounting, reservation);
            p.layout = build;
            try { Util.backgroundExecutor().execute(build); }
            catch (RuntimeException e) { build.cancel(false); throw e; }
            p.ruins = new RuinContext(level, p.geometry);
            var info = new net.minecraft.world.level.chunk.storage.RegionStorageInfo(
                    level.getServer().getWorldData().getLevelName(), level.dimension(), "incoming_preparation");
            p.archivePath = (p.handle == null ? String.valueOf(p.strike) : p.cacheId.toString()) + "/" + preparationKey(p.geometry) + "/" + content;
            var root = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),
                            level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT))
                            .resolve("data/incoming-preparation/" + p.archivePath);
            p.plans.spill(new RuinPages(info, root.resolve("plans-v" + RuinPlan.STORAGE_VERSION)),
                    p.ruins, p.accounting, () -> Math.max(0, PLANS_CAP - memory(level)));
            p.ruins.archive(new RuinSnapshots(new RuinPages(info, root.resolve("shots")), p.accounting,
                    () -> storageRoom(level)));
        }
        if (p.noRuins) return;
        if (p.layout != null) {
            Layout l = p.layout.getNow(null);
            if (l == null) return;
            install(p, l);
        }
        long now = level.getGameTime();
        if (p.handle != null && !p.requiredInitialized) {
            p.requiredInitialized = true;
            p.requiredTarget = ChunkPos.asLong(Mth.floor(p.geometry.burst().x) >> 4, Mth.floor(p.geometry.burst().z) >> 4);
            p.required.add(p.requiredTarget);
        }
        if (now >= p.nextRefresh) {
            p.nextRefresh = now + REFRESH;
            refresh(level, p);
        }
        if (!p.heapStop && p.nextTodo < p.todo.size() && heapTight()) stopForHeap(p);
        p.ruins.expire(now);
        if (p.handle != null && p.ruins.readyWindowReads() && clock.canStart() && shared.canStart()) {
            long unit = shared.begin();
            try { p.ruins.drainWindowReads(level); }
            finally { clock.record(shared.end(unit)); }
        }
        if (p.handle != null && p.ruins.windowFailed()) {
            p.failure = RuinPreparation.Reason.IO;
            return;
        }
        harvest(level, p, shared);
        if (!p.heapStop && !p.capped) submit(level, p, shared);
        far(level, p, shared);
        frontLods(level, p, now);
        if (!p.announced && p.nextTodo >= p.todo.size() && p.submitted.isEmpty() && p.nextFar >= p.order.length && p.farRunning.isEmpty()
                && p.ruins.pendingWindowReads() == 0 && !p.ruins.windowFailed()) {
            p.announced = true;
            // line for tests/recording: strike ruins prebuilt (later-loaded chunks still picked up)
            Runtime rt = Runtime.getRuntime();
            Incoming.LOG.info("Ruins for strike #{} ready: plans for loaded chunks {} (chunks loaded during flight {}), from disk {} (heavy zone {} chunks), not from disk {} "
                            + "(unread {}{}; {} s to detonation), plans {} MB, snapshots {} MB, background threads {} busy {} %, heap {} of {} MB",
                    p.strike, p.nearPlans, p.everInMemory.size(), p.farPlans, p.order.length, p.farSkipped, p.unreadable.size(), why(p.unreadWhy),
                    Math.max(0, p.detonateTime - now) / 20,
                    p.plans.bytes() >> 20, p.ruins.shotBytes() >> 20, RuinWorkers.summary(), Math.round(RuinWorkers.utilisation() * 100),
                    (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20);
        }
    }

    private Terrain.@Nullable Surface resolveSurface(ServerLevel level, Prep p, WorkClock shared) {
        return resolveSurface(level, p, shared, p.target,
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
    }

    /** One bounded async region read also handles editor chunks with missing heightmaps. */
    private Terrain.@Nullable Surface resolveSurface(ServerLevel level, Prep p, WorkClock shared, Vec3 at, net.minecraft.world.level.levelgen.Heightmap.Types type) {
        // A ceiling heightmap describes the roof, never the ground beneath an in-cavern target.
        // Match NuclearWarhead.surfaceAt/geometry for both ready and cold ceiling chunks.
        if (level.dimensionType().hasCeiling()) return new Terrain.Surface(Mth.floor(at.y), Terrain.Source.CEILING);
        var live = Terrain.estimate(level, type, Mth.floor(at.x), Mth.floor(at.z), Terrain.Allowed.CHUNK);
        if (!p.resolvingRead && live.source() == Terrain.Source.CHUNK) return live;
        if (!p.resolvingRead) {
            if (memory(level) + READ_BYTES > WINDOW_CAP) return null;
            p.resolvingRead = true;
            p.format = DiskShots.Format.of(level);
            DiskShots.read(p.format, new ChunkPos(BlockPos.containing(at)), RuinWorkers.Priority.FAR)
                    .thenAccept(p.terrainReads.expect());
        }
        if (!clock.canStart() || !shared.canStart()) return null;
        DiskShots.Read read = p.terrainReads.peek();
        if (read == null) return null;
        if (live.source() == Terrain.Source.CHUNK) {
            p.terrainReads.poll(); p.resolvingRead = false;
            return live;
        }
        if (read.skip() != null) {
            p.terrainReads.poll(); p.resolvingRead = false;
            p.failure = read.skip().equals("not on disk") || read.skip().equals("not fully generated on disk")
                    ? RuinPreparation.Reason.INVALID : RuinPreparation.Reason.IO;
            return null;
        }
        long known = RuinContext.diskSourceBytes(read) + 4L * RuinPlan.HEIGHTMAP_TYPES.length * 256 + 128;
        try (var publication = p.terrainReads.takeForPublication(read, known, PLANS_CAP - residentMemory(level))) {
            if (publication == null) return null;
            p.resolvingRead = false;
            long unit = shared.begin();
            try {
                read = read.primed(Blast.SERVER);
                int column = (Mth.floor(at.z) & 15) * 16 + (Mth.floor(at.x) & 15);
                return new Terrain.Surface(read.heights()[ChunkShot.type(type) * 256 + column], Terrain.Source.DISK);
            } finally { clock.record(shared.end(unit)); }
        }
    }

    private String content(ServerLevel level) {
        if (policy == null || policyGeneration != Blast.generation) {
            policyGeneration = Blast.generation;
            var resources = level.getServer().getResourceManager();
            String mods = net.neoforged.fml.ModList.get().getMods().stream()
                    .map(mod -> mod.getModId() + "=" + mod.getVersion()).sorted().collect(java.util.stream.Collectors.joining("\n"));
            policy = CompletableFuture.supplyAsync(() -> RuinPolicy.fingerprint(resources, mods), Util.ioPool());
        }
        try { return policy.getNow(null); }
        catch (java.util.concurrent.CompletionException failure) {
            Incoming.LOG.warn("Prepared page reuse disabled for this resource generation", failure);
            // A unique process-local namespace permits fresh planning while rejecting every
            // previous generation's pages; a failed optional cache identity cannot halt ruins.
            String fresh = (java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID()).replace("-", "");
            policy = CompletableFuture.completedFuture(fresh);
            return fresh;
        }
    }

    /** Reopen the bounded cache after a detonation's preparation owner has disappeared on restart. */
    void restore(ServerLevel level, RuinContext ctx) {
        if (ctx.archiveChecked) {
            if (ctx.restoredPlans != null) ctx.restoredPlans.pump(storageRoom(level));
            return;
        }
        String path = RuinProgress.get(level).archive(ctx.d.id());
        if (!path.matches("(?:[0-9]+|[0-9a-f-]{36})/[0-9a-f-]{36}/[0-9a-f]{64}")) { ctx.archiveChecked = true; return; }
        String content = content(level);
        if (content == null) { ctx.archivePending = true; return; }
        ctx.archivePending = false;
        ctx.archiveChecked = true;
        if (!path.endsWith("/" + content)) return; // Data packs/mods changed: ordinary solver rebuilds.
        var root = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),
                level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT))
                .resolve("data/incoming-preparation/" + path);
        var info = new net.minecraft.world.level.chunk.storage.RegionStorageInfo(
                level.getServer().getWorldData().getLevelName(), level.dimension(), "incoming_preparation");
        ctx.restoredPlans = new RetainedPlans();
        ctx.restoredPlans.spill(new RuinPages(info, root.resolve("plans-v" + RuinPlan.STORAGE_VERSION)), ctx, readBytes::addAndGet,
                () -> Math.max(0, PLANS_CAP - memory(level)));
        ctx.restoredPlans.discover();
        ctx.archive(new RuinSnapshots(new RuinPages(info, root.resolve("shots")), readBytes::addAndGet,
                () -> storageRoom(level)));
    }

    CompletableFuture<Void> closeStorage() {
        var closing = new java.util.ArrayList<CompletableFuture<Void>>();
        for (Prep p : preps) {
            closing.add(p.plans.closeStorage());
            if (p.ruins != null) closing.add(p.ruins.closeStorage());
        }
        return CompletableFuture.allOf(closing.toArray(CompletableFuture[]::new));
    }

    /** Geometry and policy separate reusable pages from another order/configuration. */
    private static String preparationKey(Detonation d) {
        String policy = "1:" + d + ":" + IncomingConfig.SERVER.nukeBlockDamage.get() + ":"
                + IncomingConfig.SERVER.nukeTreeFall.get() + ":" + IncomingConfig.SERVER.nukeFires.get();
        return java.util.UUID.nameUUIDFromBytes(policy.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
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
            if (p.considered.contains(c.toLong()) || horizontal(c, d) > radius) continue;
            // FULL data retained by generation/unload is not a ready player chunk. Do not add a key
            // that submit() must immediately remove and bind() would re-add forever.
            if (p.handle == null ? NuclearTickets.inMemory(level, c.toLong()) == null : !Terrain.ready(level, c.x, c.z)) continue;
            p.considered.add(c.toLong());
            p.everInMemory.add(c.toLong());
            if (p.handle != null) p.required.add(c.toLong());
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
                if (p.handle != null && p.ruins.failed(c)) p.failure = RuinPreparation.Reason.IO;
                if (!p.ruins.running(c)) {
                    p.submitted.removeLong(i);
                    if (p.handle != null) p.failure = RuinPreparation.Reason.IO;
                } // task removed (chunk unloaded)
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
                } else if (p.handle != null) p.failure = RuinPreparation.Reason.IO;
            } catch (RuntimeException e) {
                if (p.handle != null) p.failure = RuinPreparation.Reason.IO;
                Incoming.LOG.error("Pre-built ruins: plan for chunk {} failed with an exception; its ruins will be built on the spot", new ChunkPos(c), e);
            } finally {
                clock.record(shared.end(c0));
            }
        }
    }

    /** Shared far-read completion: preserve scarred originals, reject later unscarred edits. */
    private static boolean acceptArchived(ServerLevel level, Prep p, ArchivedShot stored) {
        DiskShots.Read current = stored.current();
        if (current.skip() != null) {
            p.archived.poll();
            if (p.handle != null && current.sourceFailure()) p.failure = RuinPreparation.Reason.IO;
            p.unreadable.add(stored.key());
            p.unreadWhy.addTo(current.skip().split(":", 2)[0], 1);
            p.reading.remove(stored.key());
            return true;
        }
        try {
            long known = p.ruins.publicationBytes(current, stored.data());
            try (var publication = p.archived.takeForPublication(stored, known,
                    PLANS_CAP - residentMemory(level))) {
                if (publication == null) return false;
                long before = p.ruins.retainedBytes();
                try {
                    p.ruins.publishReserved(current, stored.data());
                    p.diskShots++;
                } catch (RuntimeException error) {
                    failedSource(p, stored.key(), error);
                } finally {
                    publication.releaseToCountedOwner(Math.max(0, p.ruins.retainedBytes() - before));
                }
            }
        } catch (RuntimeException error) {
            if (p.archived.peek() == stored) p.archived.poll();
            failedSource(p, stored.key(), error);
        }
        p.reading.remove(stored.key());
        return true;
    }

    /** Completion only: refusal preserves the source and its reservation for a later opportunity. */
    private static boolean acceptRead(ServerLevel level, Prep p, DiskShots.Read r) {
        long c = r.pos().toLong();
        try {
            if (r.skip() != null) {
                p.reads.poll();
                if (p.handle != null && r.sourceFailure()) p.failure = RuinPreparation.Reason.IO;
                p.unreadable.add(c);
                p.unreadWhy.addTo(r.skip().split(":", 2)[0], 1);
            } else {
                try (var publication = p.reads.takeForPublication(r, p.ruins.publicationBytes(r, null),
                        PLANS_CAP - residentMemory(level))) {
                    if (publication == null) return false;
                    long before = p.ruins.retainedBytes();
                    try {
                        p.ruins.publishReserved(r, null);
                        p.diskShots++;
                    } finally {
                        publication.releaseToCountedOwner(Math.max(0, p.ruins.retainedBytes() - before));
                    }
                }
            }
        } catch (RuntimeException error) {
            if (p.reads.peek() == r) p.reads.poll();
            failedSource(p, c, error);
        }
        p.reading.remove(c);
        return true;
    }

    /** The caller established actual completion (or another consumer already collected this key). */
    private static void collectFar(ServerLevel level, Prep p, int index) {
        long c = p.farRunning.removeLong(index);
        if (!p.ruins.done(c)) {
            if (p.handle != null && !p.bound) p.failure = RuinPreparation.Reason.IO;
            return;
        }
        try {
            RuinPlan plan = p.ruins.collect(new ChunkPos(c));
            if (plan != null) {
                keep(level, p, c, plan);
                p.farPlans++;
                p.lods.offer(c);
            } else if (p.handle != null && !p.bound) p.failure = RuinPreparation.Reason.IO;
        } catch (RuntimeException e) {
            if (p.handle != null) p.failure = RuinPreparation.Reason.IO;
            Incoming.LOG.error("Pre-built ruins: plan for chunk {} from disk failed with an exception; its ruins will be built on the spot", new ChunkPos(c), e);
        }
    }

    private static void failedSource(Prep p, long key, RuntimeException error) {
        p.unreadable.add(key);
        if (p.handle != null) p.failure = RuinPreparation.Reason.IO;
        Incoming.LOG.error("Pre-built ruins: snapshot of chunk {} from disk failed", new ChunkPos(key), error);
    }

    /** Add ready plan to the resident window; completed spill receipts free it for subsequent chunks. */
    private static void keep(ServerLevel level, Prep p, long c, RuinPlan plan) {
        p.plans.put(c, plan);
        p.planned.add(c);
    }

    /**
     * Phase 2: heavy zone outside memory. Read chunks in wave-arrival order (each 5×5 window
     * {@link #READS} reads ahead); disk snapshot enters detonation ruins, background threads plan by same
     * rules as in-memory chunks ({@link RuinContext#submitDisk}). Continues after detonation: behind-wave squares
     * take ready plans. No world loads; memory under {@link #PLANS_CAP} including plans.
     */
    private void far(ServerLevel level, Prep p, WorkClock shared) {
        if (p.heapStop || !IncomingConfig.SERVER.nukeBlockDamage.get()) return;
        if (p.format == null) p.format = DiskShots.Format.of(level);
        if (!p.farInitialized) {
            p.farInitialized = true;
            // no preplans beyond heavy-zone edge: neighbor snapshots do not await them
            for (long k : p.edge) p.ruins.markNoPlan(k);
        }
        // Cached originals are checked against a fresh source read before joining the window.
        // Count unprimed editor chunks as four units, as in the ordinary window path.
        int archivedCost = 0;
        for (ArchivedShot stored; clock.canStart() && shared.canStart()
                && (stored = p.archived.peek()) != null && (archivedCost == 0 || archivedCost + stored.current().cost() <= 4); ) {
            archivedCost += stored.current().cost();
            long unit = shared.begin();
            try {
                if (!acceptArchived(level, p, stored)) break;
            } finally { clock.record(shared.end(unit)); }
        }
        // Completed source ownership funds the known publication transition before its slot is released.
        for (DiskShots.Read r; clock.canStart() && shared.canStart() && (r = p.reads.peek()) != null; ) {
            long began = shared.begin();
            try { if (!acceptRead(level, p, r)) break; }
            finally { clock.record(shared.end(began)); }
        }
        // ready plans
        for (int i = 0; i < p.farRunning.size() && clock.canStart() && shared.canStart(); ) {
            long c = p.farRunning.getLong(i);
            if (p.ruins.running(c) && !p.ruins.done(c)) {
                i++;
                continue;
            }
            long began = shared.begin();
            try { collectFar(level, p, i); }
            finally { clock.record(shared.end(began)); }
        }
        // Bounded read-ahead follows solver admission. Otherwise the reader can sweep the whole
        // zone while LRU trimming evicts the still-unplanned first window and planning never resumes.
        if (p.nextFar < p.order.length) p.ruins.pinWindow(p.order[p.nextFar]);
        p.nextRead = Math.max(p.nextRead, p.nextFar); // Earlier centres already have a task/plan or were excluded.
        int readEnd = (int) Math.min(p.order.length, (long) p.nextFar + RuinWorkers.capacity());
        while (p.nextRead < readEnd && p.reading.size() < READS && memory(level) + READ_BYTES <= WINDOW_CAP) {
            long c = p.order[p.nextRead];
            boolean all = true;
            for (int dx = -RuinPlanner.REACH; all && dx <= RuinPlanner.REACH; dx++) {
                for (int dz = -RuinPlanner.REACH; all && dz <= RuinPlanner.REACH; dz++) {
                    long k = ChunkPos.asLong(ChunkPos.getX(c) + dx, ChunkPos.getZ(c) + dz);
                    if (p.reading.contains(k) || p.unreadable.contains(k) || p.ruins.shotAvailable(level, k)) continue;
                    if (p.reading.size() >= READS || memory(level) + READ_BYTES > WINDOW_CAP) {
                        all = false;
                        continue;
                    }
                    var archive = p.ruins.archive();
                    boolean stored = archive != null && archive.mayHave(k);
                    if (stored && memory(level) + SPILL_READ_BYTES > WINDOW_CAP) { all = false; continue; }
                    p.reading.add(k);
                    if (stored) {
                        var accept = p.archived.expect();
                        var current = DiskShots.read(p.format, new ChunkPos(k), RuinWorkers.Priority.FAR);
                        archive.read(k).handle((data, failure) -> failure == null ? data : java.util.Optional.<net.minecraft.nbt.CompoundTag>empty())
                                .thenCombine(current, (data, read) -> new ArchivedShot(k, data, read)).thenAccept(accept);
                    }
                    else DiskShots.read(p.format, new ChunkPos(k), RuinWorkers.Priority.FAR).thenAccept(p.reads.expect());
                }
            }
            if (!all) break;
            p.nextRead++;
        }
        // Retire cold snapshots as writes finish; a frontier must not retain the whole completed disk.
        if (p.ruins.shotBytes() > shotCacheLimit) p.ruins.trimShots(8L << 20, shared);
        // plans in wave order: ready window goes to background threads; pending reads stop cursor
        p.plans.prefetch(p.order, p.nextFar);
        while (p.nextFar < p.order.length && !p.capped && clock.canStart() && shared.canStart()) {
            long c = p.order[p.nextFar];
            p.ruins.pinWindow(c);
            if (!p.plans.containsKey(c) && !p.ruins.running(c) && p.ruins.doneAt(c) < 0 && readyChunk(level, c) == null) {
                long restoreUnit = shared.begin();
                int restored;
                try { restored = p.plans.probe(c); }
                finally { clock.record(shared.end(restoreUnit)); }
                if (restored == 0) break;
                if (restored > 0) {
                    p.planned.add(c);
                    p.farPlans++;
                    p.lods.offer(c);
                    p.ruins.noPlan(c);
                }
            }
            if (p.handle != null && p.ruins.failed(c)) p.failure = RuinPreparation.Reason.IO;
            if (p.plans.containsKey(c) || p.ruins.running(c) || p.ruins.failed(c) || p.ruins.doneAt(c) >= 0 || p.handle == null && readyChunk(level, c) != null) {
                // ready, building, or in-memory chunk with neighbors, preplanned by near preparation
                p.ruins.unpinWindow(c);
                p.nextFar++;
                continue;
            }
            int missing = 0;
            boolean skip = false;
            for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) {
                    long k = ChunkPos.asLong(ChunkPos.getX(c) + dx, ChunkPos.getZ(c) + dz);
                    if (p.unreadable.contains(k) && !p.ruins.shotAvailable(level, k)) {
                        if (p.handle == null || k == c) skip = true;
                    } else if (!p.ruins.shotAvailable(level, k)) missing++;
                }
            }
            if (skip) {
                // cannot read entire window: chunk ruins after wave
                // Failed required work is never credited as absent/non-required terrain.
                if (p.handle == null || p.failure == RuinPreparation.Reason.NONE) p.farSkipped++;
                p.ruins.noPlan(c);
                p.nextFar++;
                continue;
            }
            // after detonation reserve half pool for ruin queue: player chunks before distant rings
            if (missing > 0) {
                p.nextRead = Math.min(p.nextRead, p.nextFar); // Refill demand lost to eviction or a superseded read.
                break;
            }
            if (!RuinWorkers.admit(RuinWorkers.Priority.FAR) || p.detonation >= 0 && RuinWorkers.inFlight() >= RuinWorkers.capacity() / 2) break;
            long c0 = shared.begin();
            try {
                if (!p.ruins.submitDisk(level, new ChunkPos(c))) break;
                p.farRunning.add(c);
                p.ruins.unpinWindow(c); // The admitted Task/Grid now owns this window.
                p.nextFar++;
            } catch (RuntimeException e) {
                p.ruins.unpinWindow(c);
                p.nextFar++;
                if (p.handle != null) p.failure = RuinPreparation.Reason.IO;
                Incoming.LOG.error("Pre-built ruins: snapshots of chunk {} from disk failed with an exception; its ruins will be built on the spot", new ChunkPos(c), e);
            } finally {
                clock.record(shared.end(c0));
            }
        }
        // Other budgeted owners may trim the cache before the next preparation tick.
        if (p.nextFar < p.order.length) p.ruins.pinWindow(p.order[p.nextFar]);
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
        p.lods.tick(level, p.plans, Math.max(0, PLANS_CAP - memory(level) + p.lods.bytes()), plansDone, now);
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

    /** Replace only the front send-budget clock of an existing preparation (tests). */
    boolean useFrontClockForTest(int strike, java.util.function.LongSupplier time) {
        for (Prep p : preps) if (p.strike == strike) {
            p.lods.useSendClockForTest(time);
            return true;
        }
        return false;
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
            if (p.plans.containsKey(c)) continue;
            if (p.ruins.running(c)) {
                if (p.handle != null) {
                    p.ruins.promote(c, RuinWorkers.Priority.NEAR);
                    if (!p.submitted.contains(c) && !p.farRunning.contains(c)) p.submitted.add(c);
                }
                continue;
            }
            // Explicit handles also prepare the loaded view edge using asynchronous disk neighbors.
            LevelChunk chunk = p.handle == null ? readyChunk(level, c)
                    : level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c));
            if (chunk == null) {
                // neighbors not loaded (loaded-world edge): picked up again once loaded
                p.considered.remove(c);
                if (p.handle != null && c != p.requiredTarget) p.required.remove(c);
                continue;
            }
            long c0 = shared.begin();
            try {
                if (p.handle != null) {
                    int missing = p.ruins.requestWindow(level, chunk.getPos());
                    if (p.ruins.windowFailed()) {
                        p.failure = RuinPreparation.Reason.IO;
                        return;
                    }
                    if (missing > 0) { p.nextTodo--; return; }
                }
                // Drain already owned window reads above before gating fresh snapshot admission.
                long windowBytes = (long) (2 * RuinPlanner.REACH + 1) * (2 * RuinPlanner.REACH + 1) * READ_BYTES;
                if (memory(level) + windowBytes > WINDOW_CAP) { p.nextTodo--; return; }
                if (p.ruins.submit(level, chunk)) p.submitted.add(c);
                else p.nextTodo--;
            } catch (RuntimeException e) {
                if (p.handle != null) p.failure = RuinPreparation.Reason.IO;
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
    record Layout(long[] order, long[] edge, List<Tile> tiles, Long2ObjectOpenHashMap<List<Tile>> askedBy,
                          LongArrayList toScan, long metadata) {
        long bytes() { return metadata + arrayBytes(order) + arrayBytes(edge) + listBytes(toScan); }
    }

    /** Conservative admission estimate, not a measurement of JVM heap. Includes transient sort/set/list growth.
     * Each bounding-grid cell allows 512 bytes for the primitive arrays, hash slots, tile/list references and
     * construction temporaries; the tile/window margin encloses every reverse-index key. This exceeds isolated
     * actual allocations at 100/1000 kt (22.2/95.8 MB), rather than reserving only the final arrays (1.0/4.4 MB).
     */
    static long layoutReservation(Detonation d) {
        long r = (long) Math.ceil((heavyRadius(d) + 16) / 16) + 1;
        long width = 2 * r + 1 + 2 * (TILE + WINDOWS_REACH);
        return (1L << 20) + 512L * width * width;
    }

    /** The future may be cancelled before its callable exits. Only the execution owner releases running bytes. */
    static final class LayoutBuild extends CompletableFuture<Layout> implements Runnable {
        private Detonation geometry;
        private java.util.function.Function<Detonation, Layout> builder;
        private final java.util.function.LongConsumer accounting;
        private long held;
        private boolean running, abandoned;

        LayoutBuild(Detonation geometry, java.util.function.Function<Detonation, Layout> builder,
                    java.util.function.LongConsumer accounting, long reservation) {
            this.geometry = geometry;
            this.builder = builder;
            this.accounting = accounting;
            held = reservation;
            accounting.accept(held);
        }

        @Override public void run() {
            Detonation d;
            java.util.function.Function<Detonation, Layout> construct;
            synchronized (this) {
                if (abandoned) return;
                running = true;
                d = geometry;
                construct = builder;
                geometry = null;
                builder = null;
            }
            Layout result = null;
            Throwable failure = null;
            try { result = construct.apply(d); }
            catch (Throwable e) { failure = e; }
            synchronized (this) {
                running = false;
                resize(abandoned || failure != null ? 0 : result.bytes());
                if (!abandoned) {
                    if (failure != null) completeExceptionally(failure);
                    else complete(result);
                }
            }
        }

        private void resize(long bytes) {
            accounting.accept(bytes - held);
            held = bytes;
        }

        /** Installation transfers result ownership to Prep metadata before releasing this asynchronous owner. */
        synchronized void transferred() {
            obtrudeValue(null);
            resize(0);
        }

        @Override public synchronized boolean cancel(boolean interrupt) {
            abandoned = true;
            geometry = null;
            builder = null;
            boolean cancelled = super.cancel(false);
            // Also drop a completed, uncollected result; CompletableFuture.cancel alone would keep it.
            obtrudeException(new java.util.concurrent.CancellationException());
            if (!running) resize(0);
            return cancelled;
        }
    }

    private static final class MetadataSet extends LongOpenHashSet {
        private static final long serialVersionUID = 1L;
        long bytes() { return 128L + arrayBytes(key); }
    }

    private static final class MetadataReasons extends Object2IntOpenHashMap<String> {
        private static final long serialVersionUID = 1L;
        long bytes() { return 192L + 8L * ((Object[]) key).length + 4L * value.length + 128L * size(); }
    }

    private static final class LayoutMap extends Long2ObjectOpenHashMap<List<Tile>> {
        private static final long serialVersionUID = 1L;
        long bytes() { return 192L + 8L * key.length + 8L * ((Object[]) value).length; }
    }

    private static long arrayBytes(long[] values) { return 32L + 8L * values.length; }
    private static long listBytes(LongArrayList values) { return 64L + arrayBytes(values.elements()); }

    /** Backing capacities survive clear/remove, so current logical sizes cannot represent retained ownership. */
    private static long metadataBytes(Prep p) {
        return 1024L + 2L * p.archivePath.length() + p.layoutMetadata
                + arrayBytes(p.order) + arrayBytes(p.edge) + listBytes(p.toScan)
                + listBytes(p.todo) + listBytes(p.submitted) + listBytes(p.planned) + listBytes(p.farRunning)
                + p.required.bytes() + p.considered.bytes() + p.everInMemory.bytes() + p.reading.bytes() + p.unreadable.bytes()
                + p.unreadWhy.bytes() + 80L * p.scanning;
    }

    private static void install(Prep p, Layout l) {
        CompletableFuture<Layout> owner = p.layout;
        p.layout = null;
        p.layoutRequired = false;
        p.layoutMetadata = l.metadata;
        p.order = l.order;
        p.edge = l.edge;
        p.tiles = l.tiles;
        p.askedBy = l.askedBy;
        p.toScan = l.toScan;
        if (owner instanceof LayoutBuild build) build.transferred();
    }

    /** Zone chunks by distance, outside edge, load squares by distance; world-free background work. */
    static Layout layout(Detonation d) {
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
        Layout l = new Layout(new long[order.length], edge.toLongArray(), new ObjectArrayList<>(), new LayoutMap(), new LongArrayList(), 0);
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
                        l.askedBy.put(k, asked = new ObjectArrayList<Tile>(1));
                        l.toScan.add(k);
                    }
                    asked.add(t);
                }
            }
        }
        long metadata = ((LayoutMap) l.askedBy).bytes()
                + 96L + 8L * ((ObjectArrayList<?>) l.tiles).elements().length + 128L * l.tiles.size();
        for (List<Tile> asked : l.askedBy.values())
            metadata += 96L + 8L * ((ObjectArrayList<?>) asked).elements().length;
        return new Layout(l.order, l.edge, l.tiles, l.askedBy, l.toScan, metadata);
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

    /** Constant-time progress: diagnostic reporting must not scan a whole large-yield zone on tick. */
    private static void zoneReport(Prep p, ScarQueue scars, @Nullable Detonation d, long now) {
        Incoming.LOG.info("Detonation #{}: heavy-zone preparation — {} loaded and {} disk plans built, {} chunks in zone, "
                        + "{} plan pages resident, {} reading, {} building, {} unreadable; {} zone tiles", p.detonation,
                p.nearPlans, p.farPlans, p.order.length, p.plans.size(), p.reading.size(),
                p.submitted.size() + p.farRunning.size(), p.unreadable.size(), p.tiles.size());
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
        return handOff(level, d, now, -1);
    }

    Handoff handOff(ServerLevel level, Detonation d, long now, int strikeId) {
        for (Prep p : preps) {
            if (!preparedFor(p, d, strikeId)) {
                // A moving carrier can hit elsewhere: retire its exact identity, never claim another target's pages.
                if (p.carrier && p.strike == strikeId) p.draining = true;
                continue;
            }
            if (p.noRuins) { p.draining = true; return null; }
            // A late layout must not block the detonation tick. No plans exist until layout installation;
            // normal scar delivery and durable settlement cover this unprepared strike.
            if (p.layoutRequired && p.layout == null) {
                p.draining = true;
                continue;
            }
            if (p.layout != null) {
                try {
                    Layout ready = p.layout.getNow(null);
                    if (ready == null) {
                        p.draining = true;
                        discardPreparation(p);
                        continue;
                    }
                    install(p, ready);
                } catch (RuntimeException e) {
                    Incoming.LOG.error("Ruin preparation for strike #{} failed with an exception; dropped", p.strike, e);
                    failed.add(p.strike);
                    p.draining = true;
                    continue;
                }
            }
            // Flight-time near reads may outlive their centre. The scar queue owns fresh requests now;
            // retire those callbacks without dropping their still-live I/O reservations.
            p.ruins.retireWindowReads();
            p.ruins.bindEvent(d.id());
            p.detonation = d.id();
            rememberArchive(level, p);
            p.handedOff = now;
            Incoming.LOG.info("Detonation #{}: pre-built ruins — plans for loaded chunks {} (chunks loaded during flight {}), still building {}; from disk {} "
                            + "(heavy zone {} chunks), still building {}, not from disk {}; background threads {}", d.id(), p.nearPlans, p.everInMemory.size(),
                    p.submitted.size(), p.farPlans,
                    p.order.length, p.farRunning.size(), p.farSkipped, RuinWorkers.summary());
            p.nextZoneReport = now + ZONE_REPORT;
            p.lods.handOff(d);
            return new Handoff(p.plans, p.planned.toLongArray(), p.ruins);
        }
        return null;
    }

    /** Optional restart hint must never mutate a frozen ledger or delay the physical detonation. */
    private void rememberArchive(ServerLevel level, Prep p) {
        if (p.archiveWritten || p.archivePath.isEmpty()) return;
        archiveHints.put(p.detonation, p.archivePath);
        p.archiveWritten = true; // This bounded owner now survives preparation retirement.
        flushArchiveHints(level);
    }

    private void flushArchiveHints(ServerLevel level) {
        if (archiveHints.isEmpty()) return;
        RuinProgress progress = RuinProgress.get(level);
        if (!progress.writable()) return;
        var pending = archiveHints.int2ObjectEntrySet().fastIterator();
        for (int left = 4; left > 0 && pending.hasNext(); left--) {
            var hint = pending.next();
            progress.pass(hint.getIntKey()).archive = hint.getValue();
            progress.setDirty();
            pending.remove();
        }
    }

    record Handoff(Long2ObjectOpenHashMap<RuinPlan> plans, long[] order, RuinContext ruins) {}

    /**
     * Prepared detonation geometry if detonation matches preparation, otherwise {@code fresh}. Detonation
     * takes its point, ground and visibility, so prebuilt ruins match later-loaded chunk ruins after preparation
     * forgotten (derived from detonation), even if weather changed during ICBM flight.
     */
    Detonation planned(Detonation fresh) {
        return planned(fresh, -1);
    }

    Detonation planned(Detonation fresh, int strikeId) {
        for (Prep p : preps) if (preparedFor(p, fresh, strikeId)) return p.geometry;
        return fresh;
    }

    /** Preparation matches detonation: not handed off, same location, yield and scale. */
    private static boolean preparedFor(Prep p, Detonation d, int strikeId) {
        Detonation g = p.geometry;
        return (strikeId < 0 ? p.handle == null : p.strike == strikeId && (p.handle == null || p.bound && compatible(p))) && p.detonation < 0 && !p.draining && g != null && g.yieldKt() == d.yieldKt() && g.surface() == d.surface() && g.scale() == d.scale()
                && (p.handle == null ? g.burst().distanceTo(d.burst()) <= SAME_PLACE : g.burst().equals(d.burst()) && g.groundY() == d.groundY());
    }

    /** Chunks in zone behind wave (ruin summary). */
    private static java.util.function.LongPredicate zone(Prep p) {
        Detonation geometry = p.geometry;
        double radius = heavyRadius(geometry) + 16;
        return key -> horizontal(new ChunkPos(key), geometry) <= radius;
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

    /** Release unused asynchronous ownership; detached late reads cannot refill a cancelled preparation. */
    private static void discardPreparation(Prep p) {
        if (p.layout != null) {
            p.layout.cancel(false);
            p.layout = null;
        }
        p.reads.invalidate();
        p.terrainReads.invalidate();
        p.resolvingRead = false;
        p.archived.invalidate();
        p.reading.clear();
        p.plans.clear();
    }

    /** Cancellation, world unload, error: release all tickets; queues no longer need ready ruins handed to detonation. */
    public void clear(ServerLevel level, ScarQueue scars) {
        for (Prep p : preps) {
            release(level, p);
            discardPreparation(p);
            p.lods.clear(level);
            if (p.ruins != null && p.detonation < 0) p.ruins.cancelTasks();
            if (p.detonation >= 0 && !p.draining) scars.dropPrepared(p.detonation, zone(p));
        }
        preps.clear();
        archiveHints.clear();
    }
}
