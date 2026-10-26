package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.grid.ChunkLights;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.DiskStatus;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Outside-world chunks mod would change on load go directly to Distant Horizons LODs ({@link DhUpdates#offer}): nuke
 * ruins behind wave ({@link NuclearPrep}: ready plan, chunk itself loads only on player approach), ground
 * trace beyond heavy zone ({@link #scorch}), lamps of darkened/relit district
 * ({@code BlackoutWorld}). Read chunk from disk (chunk I/O thread, parsing {@link RuinWorkers}); section copy
 * ({@link DiskShots#readSections}) receives ready-plan ruins ({@link RuinPlan#applyToCopy}: stale plan
 * invalidates copy), or trace planned directly from same copy ({@link DiskShots#readScorch}: snapshot on
 * server thread, {@link RuinPlanner#scorch} plan in background), and current grid lamps ({@link ChunkLights#applyToCopy});
 * send copy to DH as {@link ProtoChunk}. Copy leaves world untouched: later-loaded chunk receives same ruins/lamps
 * through own path. Copy unchanged from disk not sent: DH already has LOD from same blocks.
 * <p>
 * Wave-reached chunk without ready plan checked on disk ({@code Status}, background header read, same rule
 * as disk plan, {@link DiskStatus#scan}): final blocks await plan ({@link #PLAN_WAIT}); partial
 * or absent (explored-world edge: cannot build copy, DH generator would build intact LOD) enters world
 * in 5×5 square ({@link FarZone}): vanilla finishes generation, load path applies ruins, mark sends LOD to DH.
 * 1.17 chunk with below-zero retrogen ({@link DiskStatus.State#FINAL}) that timed out awaiting plan also enters world square:
 * zone behind wave never loads it (requires generation), otherwise LOD remains intact city.
 * <p>
 * Only with DH or Voxy players ({@link DhUpdates#room}, {@code compat/VoxyLods}), only chunks within
 * {@link #FAR_CHUNKS} of player: farther DH by default
 * sends no updates, and disk reads share I/O thread with player world loading; at most
 * {@link #READS} concurrent and {@link #PER_TICK} new reads per tick; inspect at most {@link #VISITS} requests per tick
 * (heavy zone tens of thousands, nearly all await plans).
 */
public final class FarLods {
    /**
     * Player range (chunks, larger axis): default DH player-update range ({@code
     * realTimeUpdates.playerDistance}); same for Voxy player LODs ({@code compat/VoxyLods}).
     */
    public static final int FAR_CHUNKS = 256;
    /** Concurrent disk reads and new reads per tick. */
    static final int READS = 16, PER_TICK = 8;
    /** Ticks wave-reached chunk awaits ready background plan; afterward no distant ruins (requested light/trace still sent). */
    static final int PLAN_WAIT = 1200;
    /**
     * Maximum queue visits per tick (rest round-robin in later ticks): heavy zone tens of thousands
     * (15278 for Artem), mostly awaiting plan to {@link #PLAN_WAIT}; each needs chunk-holder/ready-plan lookup.
     */
    public static final int VISITS = 256;
    /** Concurrent disk chunk-header reads. */
    static final int SCANS = 32;
    /**
     * Disk state of wave-reached chunk without plan ({@link DiskStatus.State}): header reading; whole or below-zero
     * retrogen (plan may still arrive); partial or absent.
     */
    private static final byte SCANNING = 1, WHOLE = 2, FINAL = 3, PARTIAL = 4;

    /**
     * Chunk queue reason: behind nuke wave, await ruin plan ({@link #RUINS}); district light changed, send LOD
     * even unchanged from disk since DH may retain darkened district ({@link #LIGHTS}); ground trace without
     * ruins, ring beyond heavy zone ({@link #SCORCH}, {@link #scorch}). Multiple simultaneous reasons possible.
     */
    private static final byte RUINS = 1, LIGHTS = 2, SCORCH = 4;
    /** Maximum queued trace requests: ring feeds more as queue reads. */
    static final int SCORCH_QUEUE = 2 * VISITS;
    /**
     * Server-thread trace snapshots per tick, in chunks with disk heightmaps ({@link DiskShots.Read#cost}): snapshot
     * tens of microseconds, heightmap priming up to 1.4 ms.
     */
    static final int SHOTS_PER_TICK = 2 * PER_TICK;
    /**
     * Maximum trace-snapshot time per tick (first always): detailed-city section has global palette,
     * snapshot ({@code ChunkShot.register}) much costlier than chunk count suggests.
     */
    static final long SHOT_NANOS = 2_000_000;
    /**
     * Maximum ring chunks per tick, including ones far from players (not enqueued): in 30 s wave crosses
     * hundreds of thousands of ring chunks; one-tick traversal would cost tens of ms.
     */
    static final int FEED_PER_TICK = 4 * VISITS;

    /** Queued request: enqueue tick. */
    private record Request(long chunk, long since) {}

    /** Ready background copy (or absence reason); {@code scorched} means ground trace without ruins. */
    record Built(long chunk, @Nullable ProtoChunk copy, boolean ruined, boolean lights, @Nullable String skip, boolean scorched) {
        Built(long chunk, @Nullable ProtoChunk copy, boolean ruined, boolean lights, @Nullable String skip) {
            this(chunk, copy, ruined, lights, skip, false);
        }
    }

    /** Read trace chunk: snapshot on server thread ({@link #shoot}), plan/copy in background. */
    private record Read(long chunk, Ring ring, DiskShots.Scorch scorch, boolean lights) {}

    /**
     * Distant ground trace ({@link #scorch}): chunks out to {@link RuinPlanner#scorchReach} on wave arrival, nearest
     * first; prepared heavy zone excluded: its LODs use ready-plan ruins ({@link #RUINS}).
     */
    private static final class Ring {
        final Detonation d;
        /** Own detonation context: world noise and ruin edge; retains no snapshots, fractures or heightmaps. */
        final RuinContext ruins;
        /** Chunks (nearest horizontal point, {@link NuclearPrep#horizontal}) within {@code (skip, reach]}. */
        final double skip, reach;
        /** Ring chunks by distance, computed in background; empty once traversed (memory). */
        java.util.concurrent.CompletableFuture<long[]> order;
        int next;

        Ring(ServerLevel level, Detonation d, double skip) {
            this.d = d;
            this.ruins = new RuinContext(level, d);
            this.skip = skip;
            this.reach = RuinPlanner.scorchReach(d);
            this.order = java.util.concurrent.CompletableFuture.supplyAsync(() -> layout(d, skip, reach), RuinWorkers.executor(RuinWorkers.Priority.FAR)).exceptionally(e -> {
                Incoming.LOG.error("Far scorch of detonation #{}: ring layout failed", d.id(), e);
                return new long[0];
            });
        }

        /** Ring trace on chunk: within radius and wave arrived (also heavy-zone chunk that timed out awaiting plan). */
        boolean covers(long chunk, long now) {
            ChunkPos pos = new ChunkPos(chunk);
            return NuclearPrep.horizontal(pos, d) <= reach && ScarQueue.due(d, pos) <= now;
        }

        boolean done() {
            long[] o = order.getNow(null);
            return o != null && next >= o.length;
        }
    }

    /** Ring chunks ordered by epicenter distance (any thread): distance key, one numeric sort. */
    private static long[] layout(Detonation d, double skip, double reach) {
        int cx = net.minecraft.util.Mth.floor(d.burst().x) >> 4, cz = net.minecraft.util.Mth.floor(d.burst().z) >> 4;
        int r = net.minecraft.util.Mth.ceil(reach / 16) + 1;
        it.unimi.dsi.fastutil.longs.LongArrayList keyed = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                double h = NuclearPrep.horizontal(new ChunkPos(x, z), d);
                // key: distance (1/16 block) in high bits, chunk offset from epicenter in low bits
                if (h > skip && h <= reach) keyed.add((long) (h * 16) << 32 | (long) (x - cx + 0x8000) << 16 | (z - cz + 0x8000));
            }
        }
        long[] out = keyed.toLongArray();
        java.util.Arrays.sort(out);
        for (int i = 0; i < out.length; i++) out[i] = ChunkPos.asLong(cx + (int) (out[i] >> 16 & 0xFFFF) - 0x8000, cz + (int) (out[i] & 0xFFFF) - 0x8000);
        return out;
    }

    /**
     * Queues: ruins first, light gets remaining visits (at least quarter, {@link #VISITS}). Blackout enqueues entire
     * radius at once (up to 159 thousand light requests); shared queue would put wave ruins behind it.
     */
    private final ArrayDeque<Request> ruinQueue = new ArrayDeque<>(), lightQueue = new ArrayDeque<>(), scorchQueue = new ArrayDeque<>();
    /** This world's trace rings ({@link #scorch}), retained after traversal for light requests within them. */
    private final java.util.List<Ring> rings = new java.util.ArrayList<>();
    /** Read trace chunks awaiting server-thread snapshot. */
    private final ConcurrentLinkedQueue<Read> shots = new ConcurrentLinkedQueue<>();
    /**
     * Queued chunk reasons ({@link #RUINS}, {@link #LIGHTS}, {@link #SCORCH}): second request for same chunk
     * adds reason rather than disappearing; otherwise ruin request for chunk already in light queue would be lost,
     * leaving it without ruins.
     */
    private final Long2ByteOpenHashMap queued = new Long2ByteOpenHashMap();
    /** Chunks being read. */
    private final LongOpenHashSet reading = new LongOpenHashSet();
    /**
     * Subset for trace (read, snapshot, plan, copy): ring starts new reads while fewer than half
     * {@link #READS}; remainder for ruins/light (light and unplanned ruins in ring use same path, also count).
     */
    private final LongOpenHashSet scorchReading = new LongOpenHashSet();
    /** Chunks requested again during read, plus reasons: enqueue again afterward. */
    private final Long2ByteOpenHashMap again = new Long2ByteOpenHashMap();
    /**
     * Chunks queued only for Voxy players whose copy LOD Server Support overwrote ({@link #refresh}): copy
     * sent only to them, not DH/everyone, since DH/others already correct.
     */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<java.util.Set<java.util.UUID>> refreshFor = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<Built> built = new ConcurrentLinkedQueue<>();
    /** Wave-reached unplanned chunks: disk state ({@link #SCANNING}, {@link #WHOLE}, {@link #FINAL}, {@link #PARTIAL}); I/O-thread results. */
    private final Long2ByteOpenHashMap disk = new Long2ByteOpenHashMap();
    private final ConcurrentLinkedQueue<long[]> scanned = new ConcurrentLinkedQueue<>();
    private int scanning;
    /** Chunks not whole on disk: load into world as squares. */
    private final FarZone zone = new FarZone();
    @Nullable
    private DiskShots.Format format;
    /**
     * For log/tests: sent to DH (with ruins, trace without ruins), unchanged, stale plan,
     * unreadable, plan timeout, not whole on disk.
     */
    private long sent, sentRuins, sentScorch, unchanged, stale, unread, noPlan, partial;
    /** For tests: maximum queue visits in one tick. */
    private int visitsMax;
    private long nextReport;
    /** Trace snapshot failed previously (log first occurrence). */
    private boolean shotFailed;
    /** Tests: position treated as player ({@link #nearPlayer}), GameTest has no players. */
    @Nullable
    private ChunkPos viewer;

    /** For {@link ModAttachments#FAR_LODS}: per world, unsaved. */
    public FarLods() {}

    public static FarLods get(ServerLevel level) {
        return level.getData(ModAttachments.FAR_LODS);
    }

    /**
     * Outside-world chunk whose lamps/ruins now differ from DH LOD: update LOD if near player.
     *
     * @param ruins chunk behind nuke wave, await ruin plan ({@link #PLAN_WAIT}); otherwise district light changed
     */
    public static void request(ServerLevel level, long chunk, boolean ruins) {
        get(level).request(level, chunk, ruins ? RUINS : LIGHTS);
    }

    /**
     * Chunk whose Voxy player copy LOD Server Support overwrote with intact disk chunk ({@code compat/VoxyLods.stale}):
     * rebuild copy only for that player (DH/others already correct), only if different from disk:
     * ready-plan ruins, ground trace outside heavy zone (without plan, copy there is intact city with trace), district darkness.
     * Otherwise copy equals disk chunk. Loaded chunk sent as current world state, also only to that player.
     */
    public static void refresh(ServerLevel level, long chunk, java.util.UUID player) {
        if (!DhUpdates.enabled(level)) return;
        if (inWorld(level, chunk)) {
            LevelChunk loaded = level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
            if (loaded != null) ua.zentix.incoming.compat.VoxyLods.sendTo(level, loaded, player);
            return;
        }
        FarLods f = get(level);
        long now = level.getGameTime();
        java.util.Set<java.util.UUID> waiting = f.refreshFor.get(chunk);
        if (waiting != null) {
            waiting.add(player);
            return;
        }
        // already queued for own reason: copy will go to everyone including this player
        if (f.queued.containsKey(chunk) || f.reading.contains(chunk)) return;
        byte kind;
        if (NuclearWorld.get(level).prep().arrivedPlan(level, NuclearEvents.get(level), chunk, now) != null) kind = RUINS;
        else if (f.ringOf(chunk, now) != null && !f.inHeavyZone(chunk, now)) kind = SCORCH;
        else if (PowerGrid.get(level).dark(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), now)) kind = LIGHTS;
        // no plan, trace outside heavy zone or darkness: copy equals disk chunk already sent by LSS
        else return;
        f.refreshFor.computeIfAbsent(chunk, c -> new java.util.HashSet<>()).add(player);
        f.queue(level, chunk, kind);
    }

    /** Chunk inside heavy zone with trace: without plan copy is intact city with trace, not ruins. */
    private boolean inHeavyZone(long chunk, long now) {
        Ring ring = ringOf(chunk, now);
        return ring != null && NuclearPrep.horizontal(new ChunkPos(chunk), ring.d) <= Math.max(ring.skip, NuclearPrep.heavyRadius(ring.d) + 16);
    }

    private void request(ServerLevel level, long chunk, byte kind) {
        if (!DhUpdates.enabled(level) || !nearPlayer(level, chunk)) return;
        refreshFor.remove(chunk);
        // prebuilt heavy-zone copy sends itself with front, including chunk ruins and district light
        if (kind != SCORCH && front(level, chunk, kind == LIGHTS)) return;
        queue(level, chunk, kind);
    }

    /**
     * Chunk with front-held copy ({@link FrontLods#claim}): request belongs to copy; district darkness at front differs from sent
     * copy, so request fresh copy for ruins/light (ruins await plan, hence front).
     */
    private boolean front(ServerLevel level, long chunk, boolean lights) {
        int claim = NuclearWorld.get(level).prep().frontClaim(level, chunk, lights, level.getGameTime());
        if (claim == FrontLods.AFTER_FRONT) {
            queue(level, chunk, RUINS);
            queue(level, chunk, LIGHTS);
        }
        return claim != FrontLods.NONE;
    }

    private void queue(ServerLevel level, long chunk, byte kind) {
        if (reading.contains(chunk)) {
            again.put(chunk, (byte) (again.get(chunk) | kind));
            return;
        }
        byte was = queued.get(chunk);
        if ((was & kind) != 0) return;
        queued.put(chunk, (byte) (was | kind));
        // ruined chunk handled by ruin queue (light/trace with it), traced chunk by trace queue (light with it);
        // skip any entries in other queues
        long now = level.getGameTime();
        if (kind == RUINS) ruinQueue.add(new Request(chunk, now));
        else if (kind == SCORCH && (was & RUINS) == 0) scorchQueue.add(new Request(chunk, now));
        else if (kind == LIGHTS && was == 0) lightQueue.add(new Request(chunk, now));
    }

    /**
     * Detonation in DH world: ground trace without ruins ({@link RuinPlanner#scorch}: burned turf, scorch, scorched biomes)
     * into outside-world chunk LODs out to {@link RuinPlanner#scorchReach} (~7.5 km for 15 kt), on wave arrival, nearest
     * first. Without it distant LOD beyond heavy zone stays green: prebuilt ruin plans cover only heavy zone.
     *
     * @param prepared detonation prepared ({@link NuclearPrep}): heavy-zone LODs use ready-plan ruins
     */
    public static void scorch(ServerLevel level, Detonation d, boolean prepared) {
        if (!DhUpdates.enabled(level)) return;
        get(level).rings.add(new Ring(level, d, prepared ? NuclearPrep.heavyRadius(d) + 16 : -1));
    }

    /** Trace rings feed wave-reached chunks while trace queue has room. */
    private void feedRings(ServerLevel level, long now) {
        for (Ring ring : rings) {
            long[] order = ring.order.getNow(null);
            if (order == null || ring.done()) continue;
            for (int n = FEED_PER_TICK; n > 0 && ring.next < order.length && scorchQueue.size() < SCORCH_QUEUE; n--, ring.next++) {
                if (ScarQueue.due(ring.d, new ChunkPos(order[ring.next])) > now) break;
                request(level, order[ring.next], SCORCH);
            }
            if (ring.next >= order.length) {
                Incoming.LOG.info("Far scorch of detonation #{}: all {} chunks to {} m handed to the LOD queue", ring.d.id(), order.length, Math.round(ring.reach));
                ring.order = java.util.concurrent.CompletableFuture.completedFuture(new long[0]);
                ring.next = 0;
            }
        }
    }

    /** Latest ring whose trace covers chunk (server thread); null if none. */
    @Nullable
    private Ring ringOf(long chunk, long now) {
        for (int i = rings.size() - 1; i >= 0; i--) if (rings.get(i).covers(chunk, now)) return rings.get(i);
        return null;
    }

    /** Chunk within DH range ({@link #FAR_CHUNKS}) of player in this world. */
    static boolean nearPlayer(ServerLevel level, long chunk) {
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        ChunkPos viewer = get(level).viewer;
        if (viewer != null && Math.max(Math.abs(viewer.x - x), Math.abs(viewer.z - z)) <= FAR_CHUNKS) return true;
        for (ServerPlayer p : level.players()) {
            ChunkPos at = p.chunkPosition();
            if (Math.max(Math.abs(at.x - x), Math.abs(at.z - z)) <= FAR_CHUNKS) return true;
        }
        return false;
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.hasData(ModAttachments.FAR_LODS)) get(level).tick(level);
    }

    private void tick(ServerLevel level) {
        if (queued.isEmpty()) {
            // queues contain only removed requests
            ruinQueue.clear();
            lightQueue.clear();
            scorchQueue.clear();
        }
        // Voxy-only requests removed without read (loaded, plan timeout) discarded
        if (!refreshFor.isEmpty() && level.getGameTime() % 20 == 0) refreshFor.keySet().removeIf(c -> !queued.containsKey(c) && !reading.contains(c));
        if (queued.isEmpty() && reading.isEmpty() && scanning == 0 && !zone.busy() && !ringsFeeding()) return;
        long now = level.getGameTime();
        for (Built b; (b = built.poll()) != null; ) harvest(level, b);
        shoot(level);
        for (long[] s; (s = scanned.poll()) != null; ) {
            scanning--;
            // result for chunk no longer waiting (cancelled, loaded) unnecessary
            if (queued.containsKey(s[0])) disk.put(s[0], (byte) s[1]);
            else disk.remove(s[0]);
        }
        if (!DhUpdates.enabled(level)) {
            // DH gone (DH world closed): nothing to request
            clear(level);
            return;
        }
        feedRings(level, now);
        int room = DhUpdates.room(level) - reading.size();
        NuclearWorld nuclear = NuclearWorld.get(level);
        NuclearEvents events = NuclearEvents.get(level);
        int started = 0, visits = 0;
        // ruins first, then trace; light gets remainder, at least quarter of visits: ruin requests awaiting plans
        // rotate round-robin
        int ruinVisits = Math.min(ruinQueue.size(), lightQueue.isEmpty() ? VISITS : VISITS - VISITS / 4);
        for (int n = ruinVisits; n > 0 && started < PER_TICK && reading.size() < READS && room > 0; n--) {
            visits++;
            if (visit(level, ruinQueue, nuclear, events, now)) {
                started++;
                room--;
            }
        }
        // trace at most half reads (snapshot in ruin-pool queue also holds slot); with queued light, not all new reads
        // per tick, leaving room for ruins/light
        int scorchStarts = lightQueue.isEmpty() ? PER_TICK : PER_TICK - PER_TICK / 4;
        for (int n = Math.min(scorchQueue.size(), (lightQueue.isEmpty() ? VISITS : VISITS - VISITS / 4) - visits);
             n > 0 && started < scorchStarts && reading.size() < READS && scorchReading.size() < READS / 2 && room > 0; n--) {
            visits++;
            if (visit(level, scorchQueue, nuclear, events, now)) {
                started++;
                room--;
            }
        }
        for (int n = Math.min(lightQueue.size(), VISITS - visits); n > 0 && started < PER_TICK && reading.size() < READS && room > 0; n--) {
            visits++;
            if (visit(level, lightQueue, nuclear, events, now)) {
                started++;
                room--;
            }
        }
        visitsMax = Math.max(visitsMax, visits);
        // setting disabled mid-zone: release squares
        if (IncomingConfig.SERVER.nukeFarZone.get()) zone.tick(level);
        else zone.clear(level);
        if (now >= nextReport && sent + unchanged + stale + unread + noPlan + partial > 0) {
            nextReport = now + 600;
            Incoming.LOG.info("Far Distant Horizons LODs ({}): sent {} chunks ({} with ruins, {} scorched), unchanged {}, stale plan {}, unread {}, "
                            + "no plan in time {}, incomplete on disk {} (into the world: {}); queued {} (ruins {}, scorch {}), reading {}{}", level.dimension().location(),
                    sent, sentRuins, sentScorch, unchanged, stale, unread, noPlan, partial, zone.summary(), queued.size(), ruinQueue.size(), scorchQueue.size(),
                    reading.size(), ringSummary());
        }
    }

    /** Trace ring still feeding chunks (or order being computed). */
    private boolean ringsFeeding() {
        for (Ring ring : rings) if (!ring.done()) return true;
        return !shots.isEmpty();
    }

    private String ringSummary() {
        StringBuilder b = new StringBuilder();
        for (Ring ring : rings) {
            long[] o = ring.order.getNow(null);
            if (o != null && ring.next < o.length) b.append(String.format(java.util.Locale.ROOT, "; scorch of detonation #%d: %d of %d chunks to %.0f m", ring.d.id(), ring.next, o.length, ring.reach));
        }
        return b.toString();
    }

    /**
     * Read trace chunks snapshotted on server thread (palette states into property table), at most
     * {@link #SHOTS_PER_TICK} and {@link #SHOT_NANOS} per tick while ruin pool admits tasks; trace plan/copy in background.
     * Unreadable (absent on disk) complete immediately, no pool needed.
     */
    private void shoot(ServerLevel level) {
        if (shots.isEmpty()) return;
        java.util.List<Read> taken = new java.util.ArrayList<>();
        java.util.List<ChunkShot> made = new java.util.ArrayList<>();
        int cost = 0;
        long until = System.nanoTime() + SHOT_NANOS;
        // server thread only: peek/poll return same chunk
        for (Read r; (r = shots.peek()) != null; ) {
            String skip = r.scorch.read().skip();
            if (skip == null && (cost >= SHOTS_PER_TICK || !taken.isEmpty() && System.nanoTime() >= until
                    || RuinWorkers.inFlight() + taken.size() >= RuinWorkers.capacity())) break;
            shots.poll();
            if (skip != null) {
                built.add(new Built(r.chunk, null, false, r.lights, skip));
                continue;
            }
            DiskShots.Read read = r.scorch.read();
            cost += read.cost();
            try {
                // LOD-copy trace, not ruins: flash-layer chunk already traced; layer fire does not obstruct plan
                made.add(ChunkShot.fromDisk(read.primed(Blast.SERVER), null, null, false));
                taken.add(r);
            } catch (RuntimeException e) {
                // mod block state with throwing properties: no copy, not “Exception ticking world”
                if (!shotFailed) Incoming.LOG.warn("Far Distant Horizons LODs: snapshot of chunk {} failed, it stays without the scorch (further failures are counted as unread)",
                        new ChunkPos(r.chunk), e);
                shotFailed = true;
                built.add(new Built(r.chunk, null, false, r.lights, "snapshot error: " + e));
            }
        }
        if (taken.isEmpty()) return;
        RuinPlanner.ensureOwnStates();
        Blast.PropsView view = Blast.publish();
        boolean fires = IncomingConfig.SERVER.nukeFires.get();
        DiskShots.Format f = format;
        for (int i = 0; i < taken.size(); i++) {
            Read r = taken.get(i);
            RuinContext.Grid g = r.ring.ruins.scorchGrid(level, made.get(i), view);
            java.util.concurrent.CompletableFuture.supplyAsync(() -> build(f, r.scorch.sections(), RuinPlanner.scorch(g), fires, r.lights, true), RuinWorkers.executor(RuinWorkers.Priority.FAR))
                    .exceptionally(e -> new Built(r.chunk, null, false, r.lights, "error: " + e)).thenAccept(built::add);
        }
    }

    /**
     * Request from {@code from}: removed (in world, not whole on disk, plan timeout), postponed round-robin (awaiting
     * ruin plan), or submitted for read (true).
     */
    private boolean visit(ServerLevel level, ArrayDeque<Request> from, NuclearWorld nuclear, NuclearEvents events, long now) {
        Request r = from.poll();
        byte kind = queued.get(r.chunk);
        // removed earlier or also requested ruins/trace: handled by ruin/trace queue
        if (kind == 0 || from != ruinQueue && (kind & RUINS) != 0 || from == lightQueue && (kind & SCORCH) != 0) return false;
        if (kind == LIGHTS) {
            // front copy built while request waited: light included with it
            int claim = nuclear.prep().frontClaim(level, r.chunk, true, now);
            if (claim == FrontLods.OURS) {
                done(r.chunk);
                return false;
            }
            if (claim == FrontLods.AFTER_FRONT) {
                queued.put(r.chunk, (byte) (RUINS | LIGHTS));
                ruinQueue.add(new Request(r.chunk, now));
                return false;
            }
        }
        boolean ruins = (kind & RUINS) != 0;
        ChunkPos pos = new ChunkPos(r.chunk);
        if (inWorld(level, r.chunk)) {
            // loaded: world supplies ruins/lamps, LOD via their mark (DhUpdates.mark)
            done(r.chunk);
            return false;
        }
        RuinPlan plan = nuclear.prep().arrivedPlan(level, events, r.chunk, now);
        if (plan == null && ruins) {
            byte state = disk.get(r.chunk);
            if (state == 0 && scanning < SCANS) {
                scan(level, r.chunk);
                state = SCANNING;
            }
            if (state == PARTIAL) {
                // cannot build copy: load into world, ruins via load path (far_zone disabled means wait for player approach)
                done(r.chunk);
                if (IncomingConfig.SERVER.nukeFarZone.get()) zone.offer(r.chunk);
                partial++;
                return false;
            }
            if (state != WHOLE && state != FINAL || now - r.since < PLAN_WAIT) {
                from.add(r);
                return false;
            }
            noPlan++;
            if (state == FINAL) {
                // unplanned 1.17 chunk not loaded by zone behind wave: load as square, ruins via load path
                done(r.chunk);
                if (IncomingConfig.SERVER.nukeFarZone.get()) zone.offer(r.chunk);
                return false;
            }
            if ((kind & LIGHTS) == 0 && ringOf(r.chunk, now) == null) {
                done(r.chunk);
                return false;
            }
            // district light changed or ground trace present: send copy even without ruins
        }
        done(r.chunk);
        if (format == null) format = DiskShots.Format.of(level);
        reading.add(r.chunk);
        DiskShots.Format f = format;
        // Voxy-only request: send copy only if different from disk
        boolean fires = IncomingConfig.SERVER.nukeFires.get(), lights = (kind & LIGHTS) != 0 && !refreshFor.containsKey(r.chunk);
        // unplanned chunk may carry ground trace (district light included): snapshot, trace plan, copy
        Ring ring = plan == null ? ringOf(r.chunk, now) : null;
        if (ring != null) {
            scorchReading.add(r.chunk);
            DiskShots.readScorch(f, pos).thenAccept(s -> shots.add(new Read(r.chunk, ring, s, lights)));
            return true;
        }
        DiskShots.readSections(f, pos).thenApply(s -> build(f, s, plan, fires, lights, false)).exceptionally(e -> new Built(r.chunk, null, false, false, "error: " + e))
                .thenAccept(built::add);
        return true;
    }

    /** Request removed: disk state no longer needed (in-flight header result still arrives). */
    private void done(long chunk) {
        queued.remove(chunk);
        if (disk.get(chunk) != SCANNING) disk.remove(chunk);
    }

    /** Disk chunk header ({@link DiskStatus#scan}): header only, chunk I/O thread; result into {@link #scanned}. */
    private void scan(ServerLevel level, long chunk) {
        disk.put(chunk, SCANNING);
        scanning++;
        ConcurrentLinkedQueue<long[]> out = scanned;
        DiskStatus.scan(level, new ChunkPos(chunk)).thenAccept(state -> out.add(new long[]{chunk, switch (state) {
            case WHOLE -> WHOLE;
            case FINAL -> FINAL;
            case PARTIAL -> PARTIAL;
        }}));
    }

    /** Chunk copy with ruins/trace (background): disk sections, plan positions. */
    static Built build(DiskShots.Format f, DiskShots.Sections s, @Nullable RuinPlan plan, boolean fires, boolean lights, boolean scorch) {
        long chunk = s.pos().toLong();
        if (s.skip() != null) return new Built(chunk, null, false, lights, s.skip());
        int biomes = plan == null ? 0 : plan.applyToCopy(s.sections(), fires, f.biomes(), f.minSection());
        if (biomes < 0) return new Built(chunk, null, false, lights, "stale plan");
        ProtoChunk copy = new ProtoChunk(s.pos(), UpgradeData.EMPTY, s.sections(), new ProtoChunkTicks<>(), new ProtoChunkTicks<>(),
                LevelHeightAccessor.create(f.minY(), f.height()), f.biomes(), null);
        copy.setPersistedStatus(ChunkStatus.FULL);
        return new Built(chunk, copy, plan != null && plan.changedBlocks() + plan.fireCount() + biomes > 0, lights, null, scorch);
    }

    /** Server thread: lamps by current grid; send to DH if changed from disk (or district light changed). */
    private void harvest(ServerLevel level, Built b) {
        reading.remove(b.chunk);
        scorchReading.remove(b.chunk);
        byte asked = again.remove(b.chunk);
        for (byte kind : new byte[]{RUINS, SCORCH, LIGHTS}) if ((asked & kind) != 0) queue(level, b.chunk, kind);
        if (b.copy == null) {
            if ("stale plan".equals(b.skip)) stale++;
            else unread++;
            return;
        }
        // loaded during read: world has own path
        if (inWorld(level, b.chunk)) return;
        // unruined copy for chunk with front-held ruined copy would overwrite it
        if (!b.ruined && front(level, b.chunk, true)) return;
        // no twins on disk: darken if district dark
        boolean dark = PowerGrid.get(level).dark(ChunkPos.getX(b.chunk), ChunkPos.getZ(b.chunk), level.getGameTime());
        int lamps = dark ? ChunkLights.applyToCopy(b.copy.getSections(), true) : 0;
        java.util.Set<java.util.UUID> only = refreshFor.remove(b.chunk);
        if (!b.ruined && lamps == 0 && !b.lights) {
            unchanged++;
            return;
        }
        if (only != null) {
            // LSS-overwritten for these players: DH/others already correct
            for (java.util.UUID p : only) ua.zentix.incoming.compat.VoxyLods.sendTo(level, b.copy, p);
            return;
        }
        DhUpdates.offer(level, b.copy);
        sent++;
        if (b.ruined && b.scorched) sentScorch++;
        else if (b.ruined) sentRuins++;
    }

    /**
     * Tests: loaded chunk copy from save data (like disk), parsed as outside-world chunk, with
     * plan ruins ({@code fires} includes fires) and grid lamps; null if plan does not match copy.
     */
    @Nullable
    public static ProtoChunk testCopy(ServerLevel level, LevelChunk chunk, @Nullable RuinPlan plan, boolean fires) {
        DiskShots.Format f = DiskShots.Format.of(level);
        CompoundTag tag = ChunkSerializer.write(level, chunk);
        Built b = build(f, DiskShots.parseSections(f, chunk.getPos(), tag), plan, fires, false, false);
        if (b.copy == null) return null;
        if (PowerGrid.get(level).dark(chunk.getPos().x, chunk.getPos().z, level.getGameTime())) ChunkLights.applyToCopy(b.copy.getSections(), true);
        return b.copy;
    }

    /**
     * In-memory chunk formerly fully loaded (possibly lowered at view edge): world applies ruins/lamps
     * ({@link ScarQueue}, blackout). In-memory partial chunk (ring around loaded world) counts as outside world:
     * nobody processes it until loaded.
     */
    static boolean inWorld(ServerLevel level, long chunk) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(chunk);
        return holder != null && holder.getChunkIfPresentUnchecked(ChunkStatus.FULL) instanceof LevelChunk;
    }

    /**
     * Cancellation, DH gone or server stop: queue unnecessary (in-flight reads finish/discarded), squares
     * {@link FarZone} released before {@code util/StopDrain}, which finishes their started generation.
     */
    public void clear(ServerLevel level) {
        ruinQueue.clear();
        lightQueue.clear();
        scorchQueue.clear();
        // read trace chunks (shots) finish like in-flight reads
        rings.clear();
        queued.clear();
        again.clear();
        refreshFor.clear();
        disk.long2ByteEntrySet().removeIf(e -> e.getByteValue() != SCANNING);
        zone.clear(level);
    }

    /** Tests: chunks near {@code at} treated like player range (DH); null restores actual players only. */
    public static void testViewer(ServerLevel level, @Nullable ChunkPos at) {
        get(level).viewer = at;
    }

    /** Tests: {@link FarZone} square-generation deadline, ticks; negative restores {@link FarZone#LOAD_LIMIT}. */
    public static void testZoneLoadLimit(ServerLevel level, int ticks) {
        get(level).zone.loadLimit(ticks >= 0 ? ticks : FarZone.LOAD_LIMIT);
    }

    /** For tests: maximum queue visits per tick ({@link #VISITS}). */
    public int visitsMax() {
        return visitsMax;
    }

    /**
     * For tests: sent, ruined, unchanged, stale plan, unreadable, plan timeout, not whole on disk,
     * queued, reading, traced.
     */
    public long[] stats() {
        return new long[] {sent, sentRuins, unchanged, stale, unread, noPlan, partial, queued.size(), reading.size(), sentScorch};
    }

    /** For tests: world squares ({@link FarZone#stats}): waiting, held, acquired, released, expired, abandoned, peak loading. */
    public long[] zoneStats() {
        return zone.stats();
    }

    /** For tests: await in-flight reads, headers, squares. */
    public boolean busy() {
        return !queued.isEmpty() || !reading.isEmpty() || scanning > 0 || zone.busy() || ringsFeeding();
    }
}
