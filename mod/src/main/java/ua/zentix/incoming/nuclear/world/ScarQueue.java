package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Chunk damage queue (DESIGN-nuke §3.1). Enqueue loaded chunk within radius of a detonation
 * newer than its {@code chunk_scar} mark. Process no earlier than wave arrival
 * (immediately for later-loaded chunks), one whole chunk per work unit under shared time budget: write ruin
 * plan into sections ({@link RuinPlan}), either prebuilt during ICBM flight ({@link NuclearPrep}) or
 * planned on-site. Mark after ruins; unload before ruins means no mark, retry next load.
 * <p>
 * Chunk stays queued while in memory, not only fully loaded: at view edge repeatedly falls below
 * full loading and returns without unloading; no further {@code ChunkEvent.Load}
 * (one per in-memory lifetime). Wait queued like chunk lacking loaded neighbors;
 * detonation snapshot includes it. Otherwise lost forever, leaving untouched zone stripes.
 * <p>
 * Loaded-world edge chunk (loaded, neighbors not) need not await neighbors: ready plan needs none, background
 * plan reads disk snapshots ({@link #work}). Only edge/craft world-path replacement (radius 1) and
 * server-thread plan (window) wait; chunk holds neighbor ticket ({@link NuclearTickets#holdForScar}), vanilla
 * loads in background, process then release. Chunks under live ticket ({@link #underHold}: square ± ticket
 * radius) take no own ticket, avoiding load spread across entire radius.
 * <p>
 * Thousands loaded at detonation: snapshots and enqueue already budgeted
 * ({@link #scanLoaded}); nothing on detonation tick.
 */
public final class ScarQueue {
    /** Work for one chunk: detonations by ascending ID. */
    private static final class Job {
        final long chunk;
        final List<Detonation> events = new ArrayList<>();
        int event;
        long due;
        /** Current detonation's wave-arrival time (deadline unaffected by repeated neighbor waits), basis for lag. */
        long wave;
        /** May hold neighbor ticket: not loaded by our ticket. */
        final boolean mayHold;
        /** Held neighbor-ticket radius (0 none). */
        int held;
        /** Due: in ready queue ({@link #readySeen}/{@link #readyUnseen}), not {@link #byDue}. */
        boolean ready;
        /** Awaiting neighbors (loaded-world edge): may never contribute detonation work. */
        boolean waitsNeighbours;
        /** Awaited neighbor radius ({@link #waitNeighbours}; 0 if chunk itself below full loading). */
        int needs;
        /** Neighbor ticket for released zone square ({@link #holdForTile}): included in {@link #tileHolds}. */
        boolean tileHold;
        /**
         * First request to send unruined chunk to player after wave ({@link #withholds}; {@link Long#MIN_VALUE} means
         * never requested), basis for withholding deadline/player wait.
         */
        long asked = Long.MIN_VALUE;
        /** Detonation ID ({@link Detonation#id}) already in {@link #ahead}: −1 none. */
        int aheadEvent = -1;
        /**
         * Only {@link NukeDiag#ON}: work wait reasons ({@link NukeDiag.Wait}) and ready-queue ticks where
         * tick budget ended before job, for “DIAG late seen chunk” line.
         */
        @Nullable
        int[] diagWaits;
        int diagSkipped;

        Job(long chunk, boolean mayHold) {
            this.chunk = chunk;
            this.mayHold = mayHold;
        }
    }

    /** Chunks loaded within range at detonation (snapshot first work unit); {@code next} first not enqueued. */
    private static final class Scan {
        final Detonation d;
        /** Ready-ruin chunks nearest epicenter first: enqueue before others. */
        final long[] first;
        @Nullable
        long[] chunks;
        int next;

        Scan(Detonation d, long[] first) {
            this.d = d;
            this.first = first;
        }
    }

    /** Chunks enqueued per work unit (enqueue costs microseconds). */
    private static final int OFFERS_PER_UNIT = 64;
    /**
     * Ticks before wall reaches chunk center when destruction may start: near epicenter front fast (hundreds
     * of chunks per tick), one-tick-early hidden beneath dust wall, spreading work over extra tick; farther wall
     * travels at far-zone speed ({@code Detonation.wave}, a few blocks per tick), one tick means a few blocks.
     */
    private static final int EARLY = 1;
    /**
     * Ticks before wave to precheck player-visible chunk ({@link #lookAhead}): stale ready plan
     * (grass grew, player placed block) or absent plan rebuilt in background now, ready
     * by wave. Visible background plan first in ruin pool, 1–3 ticks; margin for GC and pool busy with
     * long tasks.
     */
    private static final int LOOKAHEAD = 20;
    /**
     * Extra time beyond short lane deadline (server without spare tick time, {@link WorkClock#canStartVisible}) for
     * player-visible ruins/prechecks, ns. Wave crosses player's city when server already
     * busy (bots, chunk loads, other nuclear queues): netcup 16.10 (15 kt, bot at 1 km), no spare
     * tick time, lane got 1–3 ms for all nuclear work, queue took 0–2 visible chunks/tick, up to 255 accumulated,
     * ruins up to 64 ticks behind front despite average ready-plan replacement 0.24 ms. 6 ms permits 20–30 such
     * replacements/tick, exceeding newly visible front chunks (~10/tick); at most 6 extra ms/tick only while
     * visible chunks wait or wave approaches them (prechecks).
     */
    static final long SEEN_BUSY_NANOS = 6_000_000L;
    /** Retry delay for chunk/neighbors below full loading, ticks. */
    private static final int NEIGHBOUR_RETRY = 10;
    /**
     * Removed chunk positions sent as player debris: dozens of front chunks in view, each position
     * one or two client pieces ({@code Fragments}: at most 1200 live).
     */
    static final int THROWN_PER_CHUNK = 24;
    /** {@link #ruin} unit result: applied; another unit (neighbor fracture computed, stale plan); await neighbors ({@link #waitRadius}). */
    private static final int RUINED = 0, AGAIN = 1, WAIT = 2;
    /**
     * Ready-queue inspection result ({@link #work(ServerLevel, Job, long)}): waiting without work (neighbors,
     * window read, pool slot, background plan); awaiting neighbors after on-site plan; unit done (ruins, neighbor
     * fracture, window snapshots). Inspection without work is no clock unit ({@link WorkClock#spent}): test counting clock
     * would charge full unit, taking ruin budget. But all-waiting tick records no units,
     * clock always admits first unit, so entire queue would drain in tick: inspect only until
     * deadline ({@link WorkClock#overdue}).
     */
    private static final int LOOKED = 0, PLANNED_WAITS = 1, WORKED = 2;
    /** Slow nuclear-tick log/tests: jobs inspected this tick, those waiting. */
    private int inspected, waitedThisTick;
    /**
     * Only {@link NukeDiag#ON}, per tick: visible ruins/max lag, other ruins, prechecks;
     * source of latest ruin plan ({@link #ruin}).
     */
    private int diagSeen, diagUnseen, diagAhead;
    private long diagSeenLag;
    private String diagSource = "";

    /** Maximum simultaneous neighbor tickets for released zone squares ({@link #holdForTile}). */
    private static final int TILE_HOLDS = 64;
    private int tileHolds;
    /** Peak simultaneous {@link #tileHolds} (“DIAG” line). */
    private int tileHoldsPeak;

    /** Neighbor radius awaited by replacement returning {@code WAIT} ({@link RuinPlan#waitsNeighbours}). */
    private int waitRadius;

    /**
     * Ready plans whose replacement awaits neighbors ({@link RuinPlan#neighbourRadius}): collected background
     * plan retained while neighbors load.
     */
    private final Long2ObjectOpenHashMap<Parked> parked = new Long2ObjectOpenHashMap<>();

    private record Parked(int detonation, RuinPlan plan) {}

    private final Long2ObjectOpenHashMap<Job> jobs = new Long2ObjectOpenHashMap<>();
    /** Chunks under live neighbor tickets (holder square ± {@link RuinPlanner#REACH}): ticket count. */
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap underHold = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private long lastSlowChunk = Long.MIN_VALUE / 2;
    private final PriorityQueue<Job> byDue = new PriorityQueue<>(Comparator.comparingLong(j -> j.due));
    /** Jobs by wave arrival: precheck ({@link #lookAhead}) {@link #LOOKAHEAD} ticks before. */
    private final PriorityQueue<Ahead> ahead = new PriorityQueue<>(Comparator.comparingLong(Ahead::wave));

    /** Job, detonation ID ({@link Detonation#id}: location in {@code events} changes if earlier detonation arrives), wave arrival. */
    private record Ahead(Job job, int detonation, long wave) {}
    /**
     * Due chunks: player-visible first ({@link #watched}: ruins with front, same-tick changes to
     * player), then others (unseen lag of a few ticks invisible; still processed
     * into ruins, disk, Distant Horizons LODs). Player-requested chunk previously unseen
     * (teleport) moves to visible ({@link #withholds}). Ordered sets, not queues: transition
     * needs no search through thousands of unseen post-nuke chunks.
     */
    private record Log(long at, net.minecraft.world.level.block.state.BlockState state) {}

    private final LinkedHashSet<Job> readySeen = new LinkedHashSet<>(), readyUnseen = new LinkedHashSet<>();
    /** Trunks fallen into not-yet-ruined chunk: placed after its ruins (otherwise stale its plan). */
    private final Long2ObjectOpenHashMap<List<Log>> logs = new Long2ObjectOpenHashMap<>();
    private final Map<Integer, ColumnScar.Budget> budgets = new HashMap<>();
    private final ArrayDeque<Scan> scans = new ArrayDeque<>();
    /** Prebuilt ICBM-flight ruins ({@link NuclearPrep}): detonation ID → chunk → plan. */
    private final Map<Integer, Long2ObjectOpenHashMap<RuinPlan>> prepared = new HashMap<>();
    /** Tests/status: ready-plan chunks and plans built at wave arrival. */
    private int appliedPrepared, appliedFresh, stalePlans;
    /**
     * Per detonation: [0] ready-plan ruins, [1] stale plans, [2] maximum wave lag
     * for player-visible chunks (ticks), [3] total ready-plan replacement time, [4] longest (ns),
     * [5] maximum unseen lag, [6] on-site-plan ruins, [7] total on-site planning/replacement time,
     * [8] longest (ns, server thread), [9] chunks in memory at detonation, [10] subset with loaded neighbors
     * ({@link RuinPlanner#REACH}), [11] subset using ready plan, [12] chunks sent to players before ruins,
     * [13] chunks loaded after detonation, [14]/[15] total/max wave lag (ticks),
     * [16] later-loaded ready-plan chunks (disk), [17]/[18] total/max lag, [19] in-memory chunks at
     * detonation visible to player without loaded neighbors (no preplan, cannot snapshot window), [20] chunks
     * with loaded neighbors ([10]) using ready plan: [20]/[10] same-set fraction ([11] from all [9]),
     * [21] disk-read queue window neighbors ({@link RuinContext#requestWindow}), [22] not whole on disk,
     * [23] maximum player wait from first post-wave send request ({@link #withholds}) to ruins (ticks),
     * [24]/[25] maximum visible-chunk lag for ready/on-site plans (ticks),
     * [26] visible chunks replanned before wave ({@link #lookAhead}: ready plan stale or absent).
     */
    private final Map<Integer, long[]> preparedStats = new HashMap<>();
    private static final int STATS = 27;
    /**
     * In-memory-at-detonation chunks awaiting ruins (by detonation ID): when empty, immediately log criterion lines,
     * without awaiting entire queue (test scenario may finish sooner).
     */
    private final Map<Integer, it.unimi.dsi.fastutil.longs.LongOpenHashSet> awaitingMemory = new HashMap<>();
    /** Chunks sent before ruins (by detonation ID): each counted once in summary. */
    private final Map<Integer, it.unimi.dsi.fastutil.longs.LongOpenHashSet> sentEarly = new HashMap<>();
    /** Ruined chunks (by detonation ID): behind-wave zone progress ({@link NuclearPrep}). */
    private final Map<Integer, it.unimi.dsi.fastutil.longs.LongOpenHashSet> ruinedBy = new HashMap<>();
    /** In-memory-at-detonation chunks (by detonation ID): ready-plan ruin fraction. */
    private final Map<Integer, it.unimi.dsi.fastutil.longs.LongOpenHashSet> atDetonation = new HashMap<>();
    /** Subset with loaded neighbors at detonation ({@link RuinPlanner#REACH}): criterion A denominator. */
    private final Map<Integer, it.unimi.dsi.fastutil.longs.LongOpenHashSet> withNeighbours = new HashMap<>();
    /** Job with background plan building ({@link RuinContext#submit}): enters ready queue once ready. */
    private final List<Job> background = new ArrayList<>();
    /**
     * Maximum ticks withholding unruined chunk from first post-wave send request ({@link #withholds});
     * beyond, send current chunk
     * (counted as “sent to players before ruins”), avoiding holes if ruins never arrive.
     */
    private static final int WITHHOLD_LIMIT = 200;

    public int size() {
        return jobs.size();
    }

    /**
     * Detonation: {@link #work} queues currently loaded in-range chunks; coordinate snapshot plus chunks/marks
     * read under budget. Nothing on detonation tick.
     */
    public void scanLoaded(Detonation d) {
        scans.add(new Scan(d, new long[0]));
    }

    /**
     * Prepared detonation: prebuilt chunks nearest first queued before other loaded
     * chunks, already queued by wave arrival.
     */
    public void scanLoaded(Detonation d, Long2ObjectOpenHashMap<RuinPlan> plans, long[] order) {
        prepared.put(d.id(), plans);
        scans.add(new Scan(d, order));
    }

    /** Whether unused prepared ruins remain (chunks held by {@link NuclearPrep}). */
    public boolean hasPrepared(int detonation) {
        Long2ObjectOpenHashMap<RuinPlan> plans = prepared.get(detonation);
        return plans != null && !plans.isEmpty();
    }

    /**
     * Prepared ruins no longer needed (chunks released); log summary.
     *
     * @param zone behind-wave zone chunks ({@link NuclearPrep}): ready-plan chunks loaded by zone; plans remain only if
     *              zone ended earlier (deadline, memory); beyond edge, previously in-memory preparation chunk unloaded
     *              before wave: ruins applied on load
     */
    public void dropPrepared(int detonation, java.util.function.LongPredicate zone) {
        Long2ObjectOpenHashMap<RuinPlan> left = prepared.remove(detonation);
        long[] st = preparedStats.remove(detonation);
        if (st == null) st = new long[STATS];
        Incoming.LOG.info("Ruins for detonation #{}: from prepared plan {}, stale plan {}, on the spot {}, unplaced {}; lag behind the wave up to {} ticks "
                        + "(near players up to {}, elsewhere up to {}); chunk swap avg {} µs, longest {} ms",
                detonation, st[0], st[1], st[6], left == null ? 0 : left.size(), Math.max(st[2], st[5]), st[2], st[5],
                st[0] == 0 ? 0 : st[3] / st[0] / 1000, String.format(java.util.Locale.ROOT, "%.1f", st[4] / 1e6));
        if (st[6] > 0) {
            Incoming.LOG.info("Ruins for detonation #{}: plan and swap on the spot — avg {} ms, longest {} ms", detonation, ms(st[7] / st[6]), ms(st[8]));
        }
        criteria(detonation, st, "final");
        // “ruins placed for all chunks loaded at detonation” not logged after summary: counters removed with summary
        var unplaced = awaitingMemory.remove(detonation);
        if (unplaced != null && !unplaced.isEmpty()) {
            Incoming.LOG.info("Ruins for detonation #{}: {} chunks loaded at detonation still have no ruins (waiting for neighbours at the edge of the loaded world)", detonation, unplaced.size());
        }
        sentEarly.remove(detonation);
        atDetonation.remove(detonation);
        withNeighbours.remove(detonation);
        ruinedBy.remove(detonation);
        if (left != null && !left.isEmpty()) {
            // why timed out: still queued (neighbors not loaded) or never queued (unloaded)
            StringBuilder some = new StringBuilder();
            int queued = 0, inZone = 0, shown = 0;
            for (long c : left.keySet()) {
                boolean inQueue = jobs.containsKey(c);
                if (inQueue) queued++;
                if (!zone.test(c)) continue;
                inZone++;
                // examples from zone behind wave: beyond edge chunks intentionally await load
                if (shown++ < 5) some.append(shown > 1 ? ", " : "").append(new ChunkPos(c)).append(inQueue ? " queued" : "");
            }
            Incoming.LOG.info("Ruins for detonation #{}: unplaced {} chunks (in the zone behind the wave {}, beyond its edge {}), queued {}{}", detonation,
                    left.size(), inZone, left.size() - inZone, queued, inZone == 0 ? "" : "; in the zone: " + some);
        }
        long[] ph = RuinPlan.PHASES;
        Incoming.LOG.info("Ruins: chunk swap by phase (total since start, ms) — check {}, sections {}, heightmaps {}, light and packets {}, block entities {}",
                ph[0] / 1_000_000, ph[1] / 1_000_000, ph[2] / 1_000_000, ph[3] / 1_000_000, ph[4] / 1_000_000);
        if (RuinPlan.slowestWorldNanos > 0) {
            Incoming.LOG.info("Ruins: slowest through the world — chunk {}: {} cells, {} ms (block entities, POI, Distant Horizons LOD); "
                            + "slowest block {} — {} ms, Distant Horizons LOD {} ms",
                    new ChunkPos(RuinPlan.slowestWorldChunk), RuinPlan.slowestWorldCells, ms(RuinPlan.slowestWorldNanos),
                    RuinPlan.slowestWorldBlock == null ? "—" : BuiltInRegistries.BLOCK.getKey(RuinPlan.slowestWorldBlock.getBlock()),
                    ms(RuinPlan.slowestWorldBlockNanos), ms(RuinPlan.slowestWorldDhNanos));
            RuinPlan.slowestWorldNanos = 0;
            RuinPlan.slowestWorldBlock = null;
        }
        // trunks deferred until ruins for chunk never queued (unloaded with ready plan)
        if (left != null) {
            for (long c : left.keySet()) if (!jobs.containsKey(c)) logs.remove(c);
        }
    }

    /**
     * Test criterion lines (A: ready-plan ruin fraction among chunks in memory at detonation, player lag;
     * B: sent before ruins): when all in-memory-at-detonation chunks ruined, and at end.
     */
    private static void criteria(int detonation, long[] st, String when) {
        Incoming.LOG.info("Ruins for detonation #{} ({}): lag behind the wave near players up to {} ticks (prepared plan {}, planned on the spot {}), "
                        + "elsewhere up to {}; stale plan {}, re-planned ahead of the wave near players {}; "
                        + "a player waited for chunk ruins up to {} ticks (from the first chunk request after the wave)",
                detonation, when, st[2], st[24], st[25], st[5], st[1], st[26], st[23]);
        Incoming.LOG.info("Ruins for detonation #{}: {} chunks loaded at detonation, {} of them with loaded neighbours; from prepared plan {} ({} % of all, "
                        + "{} % of chunks with neighbours); on a player's screen without neighbours {}",
                detonation, st[9], st[10], st[11], percent(st[11], st[9]), percent(st[20], st[10]), st[19]);
        Incoming.LOG.info("Ruins for detonation #{}: sent to players before ruins: {} (distinct chunks)", detonation, st[12]);
        Incoming.LOG.info("Ruins for detonation #{}: disk windows — neighbours read {}, not whole on disk {} (solid in the window, like the world edge)",
                detonation, st[21], st[22]);
        Incoming.LOG.info("Ruins for detonation #{}: {} chunks loaded after detonation, planned on the spot — ruins avg {} ticks after the wave, max {}; "
                        + "{} from prepared plan — avg {}, max {}",
                detonation, st[13], st[13] == 0 ? 0 : st[14] / st[13], st[15], st[16], st[16] == 0 ? 0 : st[17] / st[16], st[18]);
    }

    private static String percent(long part, long whole) {
        return whole == 0 ? "—" : String.format(java.util.Locale.ROOT, "%.1f", 100.0 * part / whole);
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1e6);
    }

    /**
     * Detonation still has work: prepared ruins, loaded-chunk snapshot, queued chunks except awaiting neighbors
     * (loaded-world edge may await player approach for hours).
     */
    public boolean pending(int detonation) {
        if (prepared.containsKey(detonation)) return true;
        for (Scan s : scans) if (s.d.id() == detonation) return true;
        for (Job j : jobs.values()) {
            if (j.waitsNeighbours) continue;
            for (int e = j.event; e < j.events.size(); e++) if (j.events.get(e).id() == detonation) return true;
        }
        return false;
    }

    /** Chunk ruined by detonation (only prepared detonations while remembered). */
    public boolean ruined(int detonation, long chunk) {
        var set = ruinedBy.get(detonation);
        return set != null && set.contains(chunk);
    }

    /** Chunk awaits damage queue. */
    public boolean queued(long chunk) {
        return jobs.containsKey(chunk);
    }

    /** For {@link NukeDiag}: current queued chunk job state (null no job). */
    @Nullable
    public String diagState(long chunk, long now) {
        Job j = jobs.get(chunk);
        if (j == null) return null;
        if (background.contains(j)) return "plan in the background";
        if (parked.containsKey(chunk)) return "plan waits for neighbours, own ticket r" + j.held;
        if (j.waitsNeighbours) return "waits for neighbours, own ticket r" + j.held + (j.mayHold ? "" : " (under another ticket)");
        if (j.ready) return readySeen.contains(j) ? "in the ready queue, visible" : "in the ready queue";
        return j.due > now ? "due in " + (j.due - now) : "due now";
    }

    /**
     * Behind-wave square ({@link NuclearPrep}) releases ticket while chunk awaits neighbors: chunk holds
     * itself/neighbors with ticket of awaited radius until ruined. Otherwise chunk loaded under
     * external neighbor ticket ({@link Job#mayHold} false) awaits neighbors nobody loads, square awaits
     * chunk: all zone slots stall.
     *
     * @return awaiting neighbors and holding them itself (or nothing to await); false awaiting other thing (plan, deadline, own loading)
     */
    public boolean holdForTile(ServerLevel level, long chunk) {
        Job j = jobs.get(chunk);
        if (j == null) return true;
        if (!j.waitsNeighbours || j.needs <= 0) return false;
        if (j.held < j.needs) {
            unhold(level, j);
            hold(level, j, j.needs);
            j.tileHold = true;
            tileHolds++;
            tileHoldsPeak = Math.max(tileHoldsPeak, tileHolds);
        }
        return true;
    }

    /** Current/peak holds for released squares (“DIAG” line). */
    public String tileHoldsDiag() {
        return "now " + tileHolds + ", peak " + tileHoldsPeak + " of " + TILE_HOLDS;
    }

    /**
     * Available {@link #holdForTile} tickets: each holds up to 25 chunks (radius up to
     * {@link RuinPlanner#REACH}) while neighbors load/ruins applied, usually tens of ticks. Square
     * without enough capacity awaits prior releases.
     */
    public int tileHoldsLeft() {
        return TILE_HOLDS - tileHolds;
    }

    /** Whether chunk job awaits neighbors ({@link #holdForTile} can release square). */
    public boolean waitsNeighbours(long chunk) {
        Job j = jobs.get(chunk);
        return j != null && j.waitsNeighbours && j.needs > 0;
    }

    /** Unapplied prepared ruins: whether chunk has plan. */
    public boolean pendingPlan(int detonation, long chunk) {
        Long2ObjectOpenHashMap<RuinPlan> plans = prepared.get(detonation);
        return plans != null && plans.containsKey(chunk);
    }

    /** Ready-plan, on-site, stale-plan counts (tests, status). */
    public int[] ruinStats() {
        return new int[]{appliedPrepared, appliedFresh, stalePlans};
    }

    /**
     * Coordinates of in-memory chunks within detonation radius. Chunk never fully loaded queued by
     * {@code onChunkLoad} once loaded.
     */
    private static long[] loadedInRange(ServerLevel level, Detonation d, long[] except) {
        double radius = d.ruinRadius();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet skip = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(except);
        LongArrayList in = new LongArrayList();
        var chunkMap = level.getChunkSource().chunkMap;
        for (ChunkHolder holder : chunkMap.getChunks()) {
            ChunkPos p = holder.getPos();
            if (nearest(p, d) <= radius && skip.add(p.toLong())) in.add(p.toLong());
        }
        // pending-unload chunks also in memory: restored ticket gives no ChunkEvent.Load (NuclearTickets.inMemory)
        for (long c : chunkMap.pendingUnloads.keySet()) {
            if (nearest(new ChunkPos(c), d) <= radius && skip.add(c)) in.add(c);
        }
        return in.toLongArray();
    }

    /** Enqueue chunk for detonation (in range, newer than chunk mark). */
    public void offer(LevelChunk chunk, Detonation d) {
        if (!inRange(chunk.getPos(), d)) return;
        // no mark means no prior detonation; getData would attach mark 0 to every in-range chunk
        int applied = chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0);
        if (d.id() <= applied) return;
        long key = chunk.getPos().toLong();
        Job job = jobs.get(key);
        if (job != null && job.events.stream().anyMatch(e -> e.id() == d.id())) return;
        if (job == null) {
            job = new Job(key, !underHold.containsKey(key));
            jobs.put(key, job);
        } else if (!job.ready) {
            byDue.remove(job);
        }
        if (job.events.stream().noneMatch(e -> e.id() == d.id())) {
            job.events.add(d);
            job.events.sort(Comparator.comparingInt(Detonation::id));
        }
        // ready queue means already due; newer detonation taken next
        if (job.ready) return;
        job.due = job.wave = due(job.events.get(job.event), chunk.getPos());
        byDue.add(job);
        lookAheadLater(job);
    }

    /** Precheck chunk ({@link #lookAhead}) before current detonation's wave, once per detonation. */
    private void lookAheadLater(Job job) {
        int id = job.events.get(job.event).id();
        if (job.aheadEvent == id) return;
        job.aheadEvent = id;
        ahead.add(new Ahead(job, id, job.wave));
    }

    /**
     * Withhold chunk from player: wave arrived, ruins absent (server thread, {@code PlayerChunkSenderMixin}).
     * At most {@link #WITHHOLD_LIMIT} ticks from first post-wave request, not wave itself: player may load chunk
     * thousands of ticks after wave before zone reaches it; stalled ruins must not leave
     * player a hole.
     */
    public boolean withholds(long chunk, long now) {
        Job job = jobs.get(chunk);
        if (job == null || job.event >= job.events.size() || job.wave > now) return false;
        if (job.asked == Long.MIN_VALUE) {
            job.asked = now;
            // player awaiting unseen queued chunk (became visible after due, teleport): promote to visible
            if (job.ready && readyUnseen.remove(job)) readySeen.add(job);
        }
        return now - job.asked < WITHHOLD_LIMIT;
    }

    /**
     * Whether player sees chunk: in {@code ChunkTrackingView}, already sent or queued for send. Unlike
     * {@code ChunkMap.getPlayers(pos, false)}, excluding players with queued chunk; {@link ChunkSendGate}
     * keeps chunk queued until ruined, otherwise post-teleport awaited chunks' ruins would follow
     * unseen ones until {@link #WITHHOLD_LIMIT} sends them intact.
     */
    private static boolean watched(ServerLevel level, ChunkPos pos) {
        java.util.function.LongPredicate test = seenForTest;
        if (test != null && test.test(pos.toLong())) return true;
        for (ServerPlayer player : level.players()) {
            if (player.getChunkTrackingView().contains(pos)) return true;
        }
        return false;
    }

    /** For tests: extra visible chunks beyond real players (GameTest mock player has no view). */
    @Nullable
    private static volatile java.util.function.LongPredicate seenForTest;

    /** For tests: also consider these chunks visible; null actual players only. */
    public static void seenForTest(@Nullable java.util.function.LongPredicate chunks) {
        seenForTest = chunks;
    }

    /** Chunk visible under test override ({@link #seenForTest}). */
    static boolean seenInTest(long chunk) {
        java.util.function.LongPredicate test = seenForTest;
        return test != null && test.test(chunk);
    }

    /** Any potentially withheld chunks (fast check before iteration). */
    public boolean mayWithhold() {
        return !jobs.isEmpty();
    }

    /** Chunk sent to player: if wave reached but not ruined, include in detonation summary. */
    public void sent(long chunk, long now) {
        Job job = jobs.get(chunk);
        if (job == null || job.event >= job.events.size() || job.wave > now) return;
        int id = job.events.get(job.event).id();
        if (sentEarly.computeIfAbsent(id, k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet()).add(chunk)) preparedStats.computeIfAbsent(id, k -> new long[STATS])[12]++;
    }

    /** Chunks holding neighbor tickets (tests). */
    public long[] heldChunks() {
        return jobs.values().stream().filter(j -> j.held > 0).mapToLong(j -> j.chunk).toArray();
    }

    private void hold(ServerLevel level, Job job, int radius) {
        NuclearTickets.holdForScar(level, new ChunkPos(job.chunk), true, radius);
        job.held = radius;
        mark(job.chunk, 1, radius);
    }

    /** Job removed/finished: release neighbor ticket/collected plan. */
    private void release(ServerLevel level, Job job) {
        parked.remove(job.chunk);
        unhold(level, job);
    }

    private void unhold(ServerLevel level, Job job) {
        if (job.tileHold) {
            job.tileHold = false;
            tileHolds--;
        }
        if (job.held == 0) return;
        NuclearTickets.holdForScar(level, new ChunkPos(job.chunk), false, job.held);
        mark(job.chunk, -1, job.held);
        job.held = 0;
    }

    /** Holder chunk ticket square (± ticket radius): accounting in {@link #underHold}. */
    private void mark(long chunk, int delta, int r) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long k = ChunkPos.asLong(cx + dx, cz + dz);
                if (underHold.addTo(k, delta) + delta <= 0) underHold.remove(k);
            }
        }
    }

    /** Chunk ruined (or left memory): all in-memory-at-detonation chunks done, log criterion lines. */
    private void placedFromMemory(int detonation, long chunk) {
        var waiting = awaitingMemory.get(detonation);
        if (waiting == null) return;
        waiting.remove(chunk);
        if (!waiting.isEmpty()) return;
        awaitingMemory.remove(detonation);
        criteria(detonation, preparedStats.computeIfAbsent(detonation, k -> new long[STATS]), "ruins placed for all chunks loaded at detonation");
    }

    /** Unloaded: remove job (ticket-held chunk cannot unload: ticket removed earlier in {@link #clear}). */
    public void drop(ServerLevel level, ChunkPos pos) {
        for (int id : new ArrayList<>(awaitingMemory.keySet())) placedFromMemory(id, pos.toLong());
        Job job = jobs.remove(pos.toLong());
        if (job != null) release(level, job);
        // ready queue removes lazily: process job only if still in jobs
        if (job != null && !job.ready) byDue.remove(job);
        logs.remove(pos.toLong());
    }

    /**
     * Detonation fire counter ({@code fires_per_detonation}), shared by ruins/flash layer ({@link FlashScorch}). Forgotten
     * detonation (chunk first loaded days later) scorches without ignition: fires would long be extinguished.
     */
    ColumnScar.Budget budget(ServerLevel level, Detonation d) {
        return budgets.computeIfAbsent(d.id(), k -> new ColumnScar.Budget(!NuclearEvents.get(level).isPast(k)));
    }

    /** Fire counters only for remembered detonations. */
    public void retainBudgets(java.util.Set<Integer> detonations) {
        budgets.keySet().retainAll(detonations);
        // summary only for prepared detonations (dropPrepared logs it), others forgotten
        preparedStats.keySet().removeIf(id -> !detonations.contains(id) && !prepared.containsKey(id));
        atDetonation.keySet().removeIf(id -> !detonations.contains(id) && !prepared.containsKey(id));
        withNeighbours.keySet().retainAll(atDetonation.keySet());
        ruinedBy.keySet().removeIf(id -> !detonations.contains(id) && !prepared.containsKey(id));
        sentEarly.keySet().removeIf(id -> !detonations.contains(id) && !prepared.containsKey(id));
        awaitingMemory.keySet().removeIf(id -> !detonations.contains(id) && !prepared.containsKey(id));
    }

    public void clear(ServerLevel level) {
        jobs.values().forEach(j -> release(level, j));
        underHold.clear();
        tileHolds = 0;
        tileHoldsPeak = 0;
        scans.clear();
        prepared.clear();
        preparedStats.clear();
        atDetonation.clear();
        withNeighbours.clear();
        ruinedBy.clear();
        sentEarly.clear();
        awaitingMemory.clear();
        background.clear();
        jobs.clear();
        byDue.clear();
        ahead.clear();
        readySeen.clear();
        readyUnseen.clear();
        logs.clear();
        budgets.clear();
    }

    /** Chunk within detonation ruin radius (ground range to nearest point): ruins will reach it. */
    static boolean inRange(ChunkPos p, Detonation d) {
        return nearest(p, d) <= d.ruinRadius();
    }

    /**
     * Chunk already has detonation plan, prebuilt, deferred for neighbors, or background (running/ready):
     * snapshot no longer needed ({@link FlashScorch}).
     */
    boolean hasPlan(RuinContext ctx, Detonation d, long chunk) {
        Long2ObjectOpenHashMap<RuinPlan> plans = prepared.get(d.id());
        if (plans != null && plans.containsKey(chunk)) return true;
        Parked kept = parked.get(chunk);
        return kept != null && kept.detonation() == d.id() || ctx.running(chunk);
    }

    /** Ground epicenter range to nearest chunk point: {@link Detonation#ruinRadius} also ground range. */
    private static double nearest(ChunkPos p, Detonation d) {
        return NuclearPrep.horizontal(p, d);
    }

    /**
     * Wall arrival at chunk center, not nearest edge: distant front moves few blocks/tick, nearest
     * edge would change far side visibly before wall.
     */
    public static long due(Detonation d, ChunkPos p) {
        double dx = p.getMiddleBlockX() + 0.5 - d.burst().x, dz = p.getMiddleBlockZ() + 0.5 - d.burst().z, dy = d.burst().y - d.groundY();
        return d.gameTime() + (long) d.arrivalTicks(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    /**
     * Process while budget permits. Failed chunk alone removed with own ticket, without
     * mark, so retries on next load.
     *
     * @param clock tick budget: start column only if it fits
     */
    public void work(ServerLevel level, long now, WorkClock clock) {
        if (!NukeDiag.ON) {
            queue(level, now, clock);
            return;
        }
        diagSeen = diagUnseen = diagAhead = 0;
        diagSeenLag = 0;
        queue(level, now, clock);
        for (Job j : readySeen) j.diagSkipped++;
        if (diagSeen > 0 || !readySeen.isEmpty()) {
            Incoming.LOG.info("DIAG ruin tick {}: seen ruined {} (lag up to {}), seen left {}, unseen ruined {}, unseen left {}, checked ahead {}, "
                            + "inspected {} (waited {}), nuclear lane {} ms, stopped: {}", now, diagSeen, diagSeenLag, readySeen.size(), diagUnseen,
                    readyUnseen.size(), diagAhead, inspected, waitedThisTick, ms(clock.usedThisTickNanos()),
                    readySeen.isEmpty() && readyUnseen.isEmpty() ? "queue empty"
                            : (readySeen.isEmpty() ? clock.canStart() : clock.canStartVisible(SEEN_BUSY_NANOS)) ? "time over (overdue)" : "budget");
        }
    }

    private void queue(ServerLevel level, long now, WorkClock clock) {
        // first enqueue chunks loaded at detonations. Order irrelevant: queue uses wave arrival;
        // enqueuing thousands takes several ticks, wave crosses couple chunks meanwhile
        while (!scans.isEmpty() && clock.canStart()) {
            Scan scan = scans.peek();
            long c0 = clock.begin();
            try {
                if (scan.chunks == null) {
                    long[] loaded = loadedInRange(level, scan.d, scan.first);
                    scan.chunks = new long[scan.first.length + loaded.length];
                    System.arraycopy(scan.first, 0, scan.chunks, 0, scan.first.length);
                    System.arraycopy(loaded, 0, scan.chunks, scan.first.length, loaded.length);
                } else {
                    // unloaded after detonation: skip; onChunkLoad queues on return
                    int end = Math.min(scan.chunks.length, scan.next + OFFERS_PER_UNIT);
                    long[] st = preparedStats.computeIfAbsent(scan.d.id(), k -> new long[STATS]);
                    var memory = atDetonation.computeIfAbsent(scan.d.id(), k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
                    while (scan.next < end) {
                        long c = scan.chunks[scan.next++];
                        LevelChunk chunk = NuclearTickets.inMemory(level, c);
                        if (chunk == null) continue;
                        offer(chunk, scan.d);
                        if (!inRange(chunk.getPos(), scan.d) || !memory.add(c)) continue;
                        st[9]++;
                        if (NuclearTickets.neighbourhoodLoaded(level, chunk.getPos(), RuinPlanner.REACH)) {
                            st[10]++;
                            withNeighbours.computeIfAbsent(scan.d.id(), k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet()).add(c);
                        }
                        else if (watched(level, chunk.getPos())) st[19]++;
                    }
                }
            } catch (RuntimeException e) {
                // later-loaded chunks queued by onChunkLoad
                Incoming.LOG.error("Queueing chunks of detonation #{} failed with an exception; dropped", scan.d.id(), e);
                scans.poll();
                continue;
            } finally {
                clock.end(c0);
            }
            if (scan.next >= scan.chunks.length) {
                scans.poll();
                // in-memory-at-detonation chunks awaiting ruins: log criteria once all done
                var memory = atDetonation.get(scan.d.id());
                if (memory != null) {
                    var waiting = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                    for (long c : memory) {
                        Job j = jobs.get(c);
                        if (j != null && j.event < j.events.size() && j.events.subList(j.event, j.events.size()).contains(scan.d)) waiting.add(c);
                    }
                    awaitingMemory.put(scan.d.id(), waiting);
                    placedFromMemory(scan.d.id(), Long.MIN_VALUE);
                }
            }
        }
        // ready background plans enter ready queue first: chunks waiting since wave arrival
        for (java.util.Iterator<Job> it = background.iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (jobs.get(job.chunk) != job) {
                it.remove();
                continue;
            }
            // running plan waits; ready or task absent (collected by preparation/abandoned) enqueue
            RuinContext ctx = NuclearWorld.get(level).ruins(level, job.events.get(job.event), now);
            if (ctx.running(job.chunk) && !ctx.done(job.chunk)) continue;
            it.remove();
            job.ready = true;
            (watched(level, new ChunkPos(job.chunk)) ? readySeen : readyUnseen).addFirst(job);
        }
        while (!byDue.isEmpty() && byDue.peek().due <= now + EARLY) {
            Job job = byDue.poll();
            job.ready = true;
            (watched(level, new ChunkPos(job.chunk)) ? readySeen : readyUnseen).add(job);
        }
        inspected = waitedThisTick = 0;
        // due visible chunks, approaching-wave visible prechecks, others. Visible/prechecks get extended
        // deadline without spare server time (SEEN_BUSY_NANOS), others lane deadline
        while (clock.canStartVisible(SEEN_BUSY_NANOS)) {
            if (inspected > 0 && clock.overdueVisible(SEEN_BUSY_NANOS)) return;
            Job job;
            if (!readySeen.isEmpty()) job = readySeen.removeFirst();
            else if (lookAheadNext(level, now, clock)) continue;
            else if (!readyUnseen.isEmpty() && clock.canStart() && !(inspected > 0 && clock.overdue())) job = readyUnseen.removeFirst();
            else return;
            job.ready = false;
            if (jobs.get(job.chunk) != job) continue; // unloaded or removed
            long c0 = clock.begin();
            int result = WORKED;
            try {
                result = work(level, job, now);
            } catch (RuntimeException e) {
                Incoming.LOG.error("Damage to chunk {} failed with an exception; chunk removed from the queue", new ChunkPos(job.chunk), e);
                jobs.remove(job.chunk);
                logs.remove(job.chunk);
                release(level, job);
            } finally {
                long took = result == LOOKED ? clock.spent(c0) : clock.end(c0);
                inspected++;
                if (result != WORKED) waitedThisTick++;
                // single chunk over 50 ms means external delay (chunk load, GC): log at most every 5 s
                if (took > 50_000_000L && now - lastSlowChunk >= 100) {
                    lastSlowChunk = now;
                    Incoming.LOG.warn("Slow ruin chunk {}: {} ms", new ChunkPos(job.chunk), took / 1_000_000);
                }
            }
        }
    }

    /**
     * Next player-visible chunk whose wave arrives in {@link #LOOKAHEAD} ticks gets precheck
     * ({@link #lookAhead}). Skip unseen/removed without clock (checking iterates players).
     *
     * @return chunk inspected (counts toward tick time); false nothing to precheck
     */
    private boolean lookAheadNext(ServerLevel level, long now, WorkClock clock) {
        for (Ahead a; (a = ahead.peek()) != null && a.wave() - LOOKAHEAD <= now; ) {
            ahead.poll();
            Job job = a.job();
            // removed, different detonation, already due
            if (jobs.get(job.chunk) != job || job.event >= job.events.size() || job.events.get(job.event).id() != a.detonation()
                    || job.ready || job.wave <= now + EARLY) continue;
            ChunkPos pos = new ChunkPos(job.chunk);
            if (!watched(level, pos)) continue;
            long c0 = clock.begin();
            boolean worked = false;
            try {
                worked = lookAhead(level, job, pos, now);
                diagAhead++;
            } catch (RuntimeException e) {
                // chunk follows normal path when due
                Incoming.LOG.error("Ruins: checking chunk {} ahead of the wave failed with an exception", pos, e);
            } finally {
                if (worked) clock.end(c0);
                else clock.spent(c0);
                inspected++;
                if (!worked) waitedThisTick++;
            }
            return true;
        }
        return false;
    }

    /**
     * Visible chunk shortly before wave: check ready plan against chunk (positions still match
     * snapshot), remove stale plan, submit it or absent plan in background now, first in ruin pool. Without
     * this, plan that became stale during ICBM flight (grass grew, player placed block) discovered only when due,
     * visible chunk ruined ticks after wave. Window absent from memory starts disk read;
     * plan follows normal path when due.
     *
     * @return work unit completed (plan validation, window snapshots)
     */
    private boolean lookAhead(ServerLevel level, Job job, ChunkPos pos, long now) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) return false;
        Detonation d = job.events.get(job.event);
        Long2ObjectOpenHashMap<RuinPlan> plans = prepared.get(d.id());
        RuinPlan plan = plans != null ? plans.get(job.chunk) : null;
        Parked kept = parked.get(job.chunk);
        if (plan == null && kept != null && kept.detonation() == d.id()) return false;
        long[] st = preparedStats.computeIfAbsent(d.id(), k -> new long[STATS]);
        if (plan != null) {
            if (plan.current(chunk, d)) return true;
            plans.remove(job.chunk);
            stalePlans++;
            st[1]++;
        }
        RuinContext ctx = NuclearWorld.get(level).ruins(level, d, now);
        if (background(ctx, job.chunk) && ctx.running(job.chunk)) {
            // plan running (preparation submitted at far priority): promote if still queued
            ctx.promote(job.chunk, RuinWorkers.Priority.SEEN);
            return plan != null;
        }
        if (!background(ctx, job.chunk)) return plan != null;
        if (ctx.requestWindow(level, pos, RuinWorkers.Priority.SEEN) > 0) return plan != null;
        if (!ctx.submit(level, chunk, RuinWorkers.Priority.SEEN)) return plan != null;
        st[26]++;
        return true;
    }

    /** Slow nuclear-tick line: ready queue “inspected N, waited M” last tick. */
    String tickSummary() {
        return "inspected " + inspected + ", waited " + waitedThisTick;
    }

    /** Jobs inspected last tick (tests). */
    int inspectedLastTick() {
        return inspected;
    }

    /** Burial of entities free before ruins ({@link BlastBody#free}). */
    private static void buried(ServerLevel level, Detonation d, List<LivingEntity> free) {
        for (LivingEntity e : free) {
            if (e.isAlive() && BlastBody.buried(level, e)) NuclearWorld.get(level).buried(d, e);
        }
    }

    /**
     * Chunk ruins: ready plan if valid, otherwise current-chunk plan. On-site plan awaits window
     * fractures: cold neighbor fracture separate work unit; false computed it, ruins next unit.
     */
    private int ruin(ServerLevel level, Detonation d, LevelChunk chunk, ColumnScar.Budget budget, long lag, boolean seen) {
        long key = chunk.getPos().toLong();
        Long2ObjectOpenHashMap<RuinPlan> plans = prepared.get(d.id());
        RuinPlan plan = plans != null ? plans.get(key) : null;
        if (plan != null && (waitRadius = plan.waitsNeighbours(level, chunk)) > 0) return WAIT;
        if (plan != null) plans.remove(key);
        long[] st = preparedStats.computeIfAbsent(d.id(), k -> new long[STATS]);
        if (seen) st[2] = Math.max(st[2], lag);
        else st[5] = Math.max(st[5], lag);
        if (plan != null) {
            List<LivingEntity> free = BlastBody.free(level, chunk);
            if (plan != RuinPlan.EMPTY) NuclearWorld.get(level).beforeRuins(level, d, chunk);
            RuinPlan.Debris debris = debris(level, chunk, plan);
            long t0 = System.nanoTime();
            if (plan.apply(level, chunk, d, budget)) {
                long took = System.nanoTime() - t0;
                buried(level, d, free);
                thrown(level, d, chunk, debris);
                diagSource = "prepared";
                appliedPrepared++;
                st[0]++;
                if (seen) st[24] = Math.max(st[24], lag);
                st[3] += took;
                st[4] = Math.max(st[4], took);
                var memory = atDetonation.get(d.id());
                if (memory != null && memory.contains(key)) {
                    st[11]++;
                    var near = withNeighbours.get(d.id());
                    if (near != null && near.contains(key)) st[20]++;
                } else {
                    // loaded after detonation, ready disk plan (phase 2)
                    st[16]++;
                    st[17] += lag;
                    st[18] = Math.max(st[18], lag);
                }
                deferLogs(level, d, plan);
                return RUINED;
            }
            stalePlans++;
            st[1]++;
            // stale: on-site plan in subsequent units (background or window fractures)
            return AGAIN;
        }
        long t0 = System.nanoTime();
        RuinContext ctx = NuclearWorld.get(level).ruins(level, d, level.getGameTime());
        boolean server = false;
        Parked kept = parked.get(key);
        diagSource = "parked";
        if (kept != null && kept.detonation() == d.id()) {
            plan = kept.plan();
            if ((waitRadius = plan.waitsNeighbours(level, chunk)) > 0) return WAIT;
            parked.remove(key);
        } else if (background(ctx, key)) {
            // background plan ready ({@link #work} takes only when ready): finish/apply
            diagSource = "background";
            plan = ctx.collect(level, chunk);
            if (plan == null) return AGAIN;
            if ((waitRadius = plan.waitsNeighbours(level, chunk)) > 0) {
                parked.put(key, new Parked(d.id(), plan));
                return WAIT;
            }
        } else {
            if (!RuinPlanner.blastsReady(level, d, chunk)) return AGAIN;
            plan = RuinPlanner.plan(level, d, chunk);
            diagSource = "server thread";
            server = true;
        }
        List<LivingEntity> free = BlastBody.free(level, chunk);
        if (plan != RuinPlan.EMPTY) NuclearWorld.get(level).beforeRuins(level, d, chunk);
        RuinPlan.Debris debris = debris(level, chunk, plan);
        boolean applied = plan.apply(level, chunk, d, budget);
        if (applied) buried(level, d, free);
        if (!applied && !server) {
            // background-build positions changed: rebuild on server thread from current chunk
            // (otherwise intact chunk gets ruin mark). Server-thread plan built this tick, valid
            stalePlans++;
            st[1]++;
            ctx.planOnServer(key);
            return AGAIN;
        }
        var memory = atDetonation.get(d.id());
        if (memory == null || !memory.contains(key)) {
            st[13]++;
            st[14] += lag;
            st[15] = Math.max(st[15], lag);
        }
        long took = System.nanoTime() - t0;
        thrown(level, d, chunk, debris);
        appliedFresh++;
        st[6]++;
        if (seen) st[25] = Math.max(st[25], lag);
        st[7] += took;
        st[8] = Math.max(st[8], took);
        deferLogs(level, d, plan);
        return RUINED;
    }

    /**
     * Removed debris ({@link RuinPlan#thrown}, up to {@link #THROWN_PER_CHUNK} positions) if player viewers, before replacement:
     * replacement completing surrounding 5×5 square forgets chunk flash layer ({@link RuinContext#applied}),
     * positions already changed by layer (flash-burned) do not scatter.
     */
    private static RuinPlan.Debris debris(ServerLevel level, LevelChunk chunk, RuinPlan plan) {
        if (level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false).isEmpty()) return RuinPlan.Debris.NONE;
        return plan.debris(chunk, THROWN_PER_CHUNK);
    }

    /**
     * Removed debris ({@link #debris}) to chunk viewers: same-tick ruins, block pieces
     * scatter when front reaches position.
     */
    private static void thrown(ServerLevel level, Detonation d, LevelChunk chunk, RuinPlan.Debris debris) {
        if (debris.pieces().isEmpty()) return;
        S2C.NukeDebris packet = new S2C.NukeDebris(d.id(), debris.pieces(), debris.removed());
        for (ServerPlayer p : level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false)) PacketDistributor.sendToPlayer(p, packet);
    }

    /** Ready detonation plan: prebuilt or collected awaiting neighbors. */
    private boolean hasPlan(int detonation, long chunk) {
        Parked p = parked.get(chunk);
        return pendingPlan(detonation, chunk) || p != null && p.detonation() == detonation;
    }

    /** Background plan building: destruction enabled, background plan not failed. */
    private static boolean background(RuinContext ctx, long chunk) {
        return ua.zentix.incoming.IncomingConfig.SERVER.nukeBlockDamage.get() && !ctx.failed(chunk);
    }

    /**
     * Chunk awaits neighbors (or own full loading): retry {@link #NEIGHBOUR_RETRY}. Fully
     * loaded edge requests neighbor ticket of {@code radius} (0 none); under external live ticket takes
     * none, already in loaded square, own ticket would spread loading.
     */
    private void waitNeighbours(ServerLevel level, Job job, long now, int radius) {
        job.needs = radius;
        // always expand own ticket (normal or released-square): stale plan waits r2 instead of r1;
        // own underHold/mayHold “external ticket” accounting would otherwise leave r1, nobody loads r2
        // neighbors, holding chunk/square count forever; growth capped at REACH, no new chain
        boolean own = job.held > 0;
        if (radius > 0 && job.held < radius && (own || job.mayHold && !underHold.containsKey(job.chunk))) {
            // expanded square ticket still counted in holds (unhold removes)
            boolean tile = job.tileHold;
            unhold(level, job);
            hold(level, job, radius);
            if (tile) {
                job.tileHold = true;
                tileHolds++;
                tileHoldsPeak = Math.max(tileHoldsPeak, tileHolds);
            }
        }
        job.waitsNeighbours = true;
        job.due = now + NEIGHBOUR_RETRY;
        byDue.add(job);
    }

    /** Trunks in neighboring chunks: immediately if neighbor already ruined/not queued, otherwise afterward. */
    private void deferLogs(ServerLevel level, Detonation d, RuinPlan plan) {
        for (int k = 0; k < plan.outsideCount(); k++) {
            long at = plan.outsidePos(k);
            long chunk = ChunkPos.asLong(BlockPos.getX(at) >> 4, BlockPos.getZ(at) >> 4);
            Job job = jobs.get(chunk);
            // neighbor still ahead: queued for this detonation or ready plan not yet taken by queue
            if (job != null ? job.events.stream().anyMatch(e -> e.id() == d.id()) && job.events.indexOf(d) >= job.event
                    : pendingPlan(d.id(), chunk)) {
                logs.computeIfAbsent(chunk, c -> new ArrayList<>()).add(new Log(at, plan.outsideState(k)));
            } else {
                RuinPlan.placeLog(level, at, plan.outsideState(k));
            }
        }
    }

    /** Wait reason into total “DIAG ruin queue” and job count ({@link Job#diagWaits}). */
    private static void waited(Job job, NukeDiag.Wait w) {
        NukeDiag.waited(w);
        if (!NukeDiag.ON) return;
        if (job.diagWaits == null) job.diagWaits = new int[NukeDiag.Wait.values().length];
        job.diagWaits[w.ordinal()]++;
    }

    /** Chunk ruined (only {@link NukeDiag#ON}): tick count; visible chunk late gets line ({@link #diagLate}). */
    private void diagRuined(ServerLevel level, Job job, ChunkPos pos, long lag, boolean seen) {
        if (seen) {
            diagSeen++;
            diagSeenLag = Math.max(diagSeenLag, lag);
        } else diagUnseen++;
        if (seen && lag > 5) diagLate(level, job, pos, lag);
        // wait counts per detonation: reset for chunk awaiting next detonation
        job.diagWaits = null;
        job.diagSkipped = 0;
    }

    /** “DIAG late seen chunk”: why visible chunk ruined after wave. */
    private void diagLate(ServerLevel level, Job job, ChunkPos pos, long lag) {
        int near = Integer.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            ChunkPos at = p.chunkPosition();
            near = Math.min(near, Math.max(Math.abs(at.x - pos.x), Math.abs(at.z - pos.z)));
        }
        StringBuilder waits = new StringBuilder();
        if (job.diagWaits != null) {
            for (NukeDiag.Wait w : NukeDiag.Wait.values()) {
                if (job.diagWaits[w.ordinal()] == 0) continue;
                if (!waits.isEmpty()) waits.append(", ");
                waits.append(w.text).append(' ').append(job.diagWaits[w.ordinal()]);
            }
        }
        Incoming.LOG.info("DIAG late seen chunk {}: lag {} ticks, {} chunks from the nearest player, plan {}, ticks left in the ready queue {}, waited: {}",
                pos, lag, near, diagSource, job.diagSkipped, waits.isEmpty() ? "nothing" : waits);
    }

    /**
     * Due queue head: ruins in one work unit. Neighbor needs depend on plan:
     * ready (prebuilt/background) needs none except radius 1 for edge/craft world-path replacement,
     * radius {@link RuinPlanner#REACH} for non-vanilla block entities ({@link RuinPlan#neighbourRadius}); background
     * plan needs 5×5 window snapshots ({@link RuinPlanner#REACH}), memory neighbors snapshotted,
     * others disk ({@link RuinContext#requestWindow}) without world loading; non-whole disk neighbor
     * (ungenerated) becomes solid mass like world edge; server-thread plan (destruction disabled or
     * background plan failed) needs loaded window, neighbors loaded by ticket.
     *
     * @return {@link #LOOKED} waiting without work; {@link #PLANNED_WAITS} on-site plan built, replacement awaits
     *      neighbors; {@link #WORKED} ruins, neighbor fracture, window snapshots
     */
    private int work(ServerLevel level, Job job, long now) {
        if (NuclearTickets.inMemory(level, job.chunk) == null) {
            // unloaded (onChunkUnload would already remove job): queued by onChunkLoad on return
            jobs.remove(job.chunk);
            release(level, job);
            logs.remove(job.chunk);
            return LOOKED;
        }
        ChunkPos pos = new ChunkPos(job.chunk);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        Detonation d = job.events.get(job.event);
        RuinContext ctx = NuclearWorld.get(level).ruins(level, d, now);
        boolean ready = hasPlan(d.id(), job.chunk), background = background(ctx, job.chunk);
        if (chunk == null) {
            // below full loading (view edge): ruin when full
            waited(job, NukeDiag.Wait.BELOW_FULL);
            waitNeighbours(level, job, now, 0);
            return LOOKED;
        }
        if (!ready && !background && !NuclearTickets.neighbourhoodLoaded(level, pos, RuinPlanner.REACH)) {
            // server-thread plan reads world window: ticket loads neighbors
            waited(job, NukeDiag.Wait.SERVER_PLAN);
            waitNeighbours(level, job, now, RuinPlanner.REACH);
            return LOOKED;
        }
        job.waitsNeighbours = false;
        boolean seen = watched(level, pos);
        // visible chunk first in ruin pool: distant tasks (LODs, disk ruins) keep pool full
        RuinWorkers.Priority priority = seen ? RuinWorkers.Priority.SEEN : RuinWorkers.Priority.NEAR;
        boolean submit = !ready && background && !ctx.running(job.chunk);
        if (!ready && background && ctx.running(job.chunk) && !ctx.done(job.chunk)) {
            // plan building; far-priority preparation task not started promoted to wave-chunk priority
            ctx.promote(job.chunk, priority);
            waited(job, NukeDiag.Wait.BACKGROUND);
            this.background.add(job);
            return LOOKED;
        }
        int reading = submit ? ctx.requestWindow(level, pos, priority) : 0;
        long[] st = preparedStats.get(d.id());
        if (st != null) {
            st[21] = ctx.windowReads;
            st[22] = ctx.windowAbsent;
        }
        if (submit && (reading > 0 || !RuinWorkers.admit(priority))) {
            // neighbor snapshots reading or detonation has too many tasks: retry next tick
            waited(job, reading > 0 ? NukeDiag.Wait.WINDOW : NukeDiag.Wait.ADMIT);
            job.due = now + 1;
            byDue.add(job);
            return LOOKED;
        }
        ColumnScar.Budget budget = budget(level, d);
        if (submit) {
            // no plan: snapshots this unit, background plan; job awaits in background
            if (ctx.submit(level, chunk, priority)) this.background.add(job);
            else {
                waited(job, NukeDiag.Wait.SUBMIT);
                job.due = now + 1;
                byDue.add(job);
            }
            return WORKED;
        }
        int r = ruin(level, d, chunk, budget, Math.max(0, now - job.wave), seen);
        if (r == AGAIN) {
            // neighbor fracture computed or plan stale: next unit behind already waiting jobs in same queue.
            // At head would block all: on-site plan up to 9 one-unit window fractures plus collapse, each several ms,
            // ready replacement 0.2 ms; netcup 16.10 one such chunk blocked 60–85 ready-plan visible chunks
            // for 38 ticks. Plan independent of chunk order (RuinContext)
            waited(job, NukeDiag.Wait.AGAIN);
            job.ready = true;
            (seen ? readySeen : readyUnseen).addLast(job);
            return WORKED;
        }
        if (r == WAIT) {
            waited(job, waitRadius > 1 ? NukeDiag.Wait.PLAN_R2 : NukeDiag.Wait.PLAN_R1);
            waitNeighbours(level, job, now, waitRadius);
            return PLANNED_WAITS;
        }
        if (NukeDiag.ON) diagRuined(level, job, pos, Math.max(0, now - job.wave), seen);
        chunk.setData(ModAttachments.CHUNK_SCAR, d.id());
        chunk.setUnsaved(true);
        if (job.asked != Long.MIN_VALUE) {
            long[] waited = preparedStats.computeIfAbsent(d.id(), k -> new long[STATS]);
            waited[23] = Math.max(waited[23], now - job.asked);
            job.asked = Long.MIN_VALUE;
        }
        placedFromMemory(d.id(), job.chunk);
        if (prepared.containsKey(d.id())) ruinedBy.computeIfAbsent(d.id(), k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet()).add(job.chunk);
        List<Log> fallen = logs.remove(job.chunk);
        if (fallen != null) fallen.forEach(l -> RuinPlan.placeLog(level, l.at(), l.state()));
        job.event++;
        if (job.event >= job.events.size()) {
            release(level, job);
            jobs.remove(job.chunk);
        } else {
            job.due = job.wave = due(job.events.get(job.event), chunk.getPos());
            byDue.add(job);
            lookAheadLater(job);
        }
        return WORKED;
    }
}
