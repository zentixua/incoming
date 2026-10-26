package ua.zentix.incoming.grid;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.world.FarLods;
import ua.zentix.incoming.nuclear.world.NuclearTickets;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.Palettes;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Blackout in a dimension's world: brings chunks into line with the power grid ({@link PowerGrid}) within the tick time
 * budget. Non-persistent: everything is derived from outages, chunk marks and the blocks themselves.
 * <ul>
 *   <li>Cascade ({@link Sweep}): chunks of the outage area ordered by the time the outage (or power restoration) reaches
 *   their district. A loaded chunk is converted immediately (lamps to unlit counterparts or back); a chunk outside the
 *   loaded world is not loaded — it is converted when loaded ({@link #onChunkLoad}).</li>
 *   <li>Lamps change directly in the section palette ({@link ChunkLights#applyInPlace}): for the world a lamp and its
 *   counterpart are identical except for light ({@link GridLights#inPlace}: no block entity or different shape), so
 *   neighbors, heightmaps and collisions need no updates; lighting uses {@code checkBlock} at the position, clients get
 *   a block change. With Create and Sable, a lamp via {@code setBlock} costs ≈10 µs; a city may have 2.5 million invisible
 *   light blocks (up to 7400 per chunk): the queue would take minutes. Signal-controlled lamps are checked against their
 *   signal immediately if chunk neighbors are loaded; at the loaded world's edge (no neighbors, which on a dedicated
 *   server may never arrive), when those neighbors load. Only Sable craft plots go through the world
 *   ({@link ChunkLights#apply}): craft physics reads their blocks.</li>
 *   <li>A work unit is not a chunk, but {@link #UNIT_WORK} of work across as many queued chunks as fit (at most
 *   {@link #CHUNKS_PER_UNIT}): a lamp in the palette costs 1, through the world {@link #WORLD_LAMP}, scanning sections of
 *   a chunk with lamps {@link #SCAN_COST}; a chunk without lamps needs a palette check, microseconds. A pass resumes
 *   where it stopped ({@link ChunkLights.Pass#next}) and ends at the chunk's last lamp: no repeated scans from the start
 *   or empty second pass.</li>
 *   <li>Blackout never loads chunks: unlit counterparts do not exist on disk ({@link ChunkSaves}); a chunk loaded in a
 *   dark district goes dark during loading.</li>
 * </ul>
 */
public final class BlackoutWorld {
    /** Work per unit (≈0.3 ms): measured in lamps converted in the palette. */
    public static final int UNIT_WORK = 256;
    /** A lamp through the world ({@code setBlock} with lighting, clients and Sable; ≈10 µs with Create and Sable) costs this many palette lamps. */
    static final int WORLD_LAMP = 8;
    /** World-path lamps per unit (relighting counterparts unrelated to blackout, signal checks). */
    static final int LAMPS_PER_UNIT = UNIT_WORK / WORLD_LAMP;
    /** Chunks per work unit: a chunk without lamps needs section palette checks, microseconds. */
    static final int CHUNKS_PER_UNIT = 256;
    /**
     * A unit longer than this (wall time) is logged with a breakdown ({@link #logSlowUnit}): 2.5 times the default tick budget
     * (4 ms) — one such unit consumes the blackout tick; the longest unit in /incoming grid status does not show its contents.
     */
    private static final long SLOW_UNIT_NANOS = 10_000_000L;
    /** Game time of the last slow-unit log entry. */
    private long slowLogged = Long.MIN_VALUE / 2;
    /** Chunks closer than this to a player (chunks, maximum axis distance) are queued before the others. */
    static final int NEAR_CHUNKS = 8;
    /**
     * Scanning a chunk's section blocks costs this many palette lamps: the palette remembers converted states too, so a
     * chunk with no lamps left may need a scan (≈0.2 ms per city chunk).
     */
    private static final int SCAN_COST = 128;
    /** Ticks between scans of Sable craft plots ({@link #relightPlots}). */
    public static final int PLOT_SCAN = 100;
    /** District sound is sent to players closer than this (horizontal blocks). */
    private static final double DISTRICT_SOUND_RANGE = 96;

    /** Cascade of one outage (or power restoration): area chunks ordered by arrival time. */
    private static final class Sweep {
        final Outage outage;
        final boolean restore;
        final int minX, maxX, maxZ;
        /** Next unprocessed chunk row (z). */
        int row;
        final Long2ObjectOpenHashMap<LongArrayList> byDue = new Long2ObjectOpenHashMap<>();
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        /** Next time whose chunks have not yet been issued. */
        long cursor = Long.MIN_VALUE;

        Sweep(Outage outage, boolean restore) {
            this.outage = outage;
            this.restore = restore;
            // a district within the radius may include chunks beyond it: margin of one and a half cells
            double r = outage.radius() + Districts.CELL * 16 * 1.5;
            minX = (int) Math.floor((outage.x() - r) / 16);
            maxX = (int) Math.floor((outage.x() + r) / 16);
            row = (int) Math.floor((outage.z() - r) / 16);
            maxZ = (int) Math.floor((outage.z() + r) / 16);
        }

        boolean built() {
            return row > maxZ;
        }

        /** Process one chunk row: each area chunk gets the time the cascade reaches its district. */
        void buildRow() {
            int z = row++;
            for (int x = minX; x <= maxX; x++) {
                long district = Districts.of(x, z);
                if (!outage.covers(district)) continue;
                long due = restore ? outage.lightAt(district) : outage.darkAt(district);
                if (due == Outage.NEVER) continue;
                byDue.computeIfAbsent(due, k -> new LongArrayList()).add(ChunkPos.asLong(x, z));
                first = Math.min(first, due);
                last = Math.max(last, due);
            }
            if (built() && cursor == Long.MIN_VALUE) cursor = first;
        }

        boolean done() {
            return built() && cursor > last;
        }
    }

    private final List<Sweep> sweeps = new ArrayList<>();
    /**
     * Chunks due for conversion: from a cascade, loading or lamp placement. Player-near chunks first
     * ({@link #NEAR_CHUNKS}), then the rest: in a city with millions of lamps the queue takes minutes, and lights near a
     * player do not wait for distant districts.
     */
    private final LongArrayFIFOQueue near = new LongArrayFIFOQueue(), ready = new LongArrayFIFOQueue();
    /** Queue from which the active chunk was taken: an unfinished chunk returns to its head. */
    private LongArrayFIFOQueue taken = ready;
    /** Chunks of this dimension's players at tick start. */
    private final LongArrayList players = new LongArrayList();
    /** Queued chunks: each appears once, regardless of how many triggers arrive. */
    private final LongOpenHashSet queued = new LongOpenHashSet();
    /**
     * Lamps darkened on chunk load ({@link ChunkSaves}) whose light came from disk: remove it in the first work on the chunk
     * (light reduction spreads into loaded neighbors itself; unloaded neighbors have no light).
     */
    private final Long2ObjectOpenHashMap<LongArrayList> staleLight = new Long2ObjectOpenHashMap<>();
    /**
     * Signal-controlled lamps lit at the loaded world's edge ({@link ChunkLights#applyInPlace}): check against the signal
     * as soon as chunk neighbors are loaded ({@link #neighbourLoaded}).
     */
    private final Long2ObjectOpenHashMap<LongArrayList> resignal = new Long2ObjectOpenHashMap<>();
    /** Counterparts unrelated to blackout due for relighting ({@link #relightLater}): each position once. */
    private final LongLinkedOpenHashSet relight = new LongLinkedOpenHashSet();
    /**
     * Counterparts from {@link #relight} whose chunk is not ready (or whose neighbors are not loaded), grouped by chunk:
     * return to {@link #relight} on neighboring chunk load and once a second, instead of leading the queue every tick.
     */
    private final Long2ObjectOpenHashMap<LongArrayList> relightWaiting = new Long2ObjectOpenHashMap<>();
    /** Sable plot chunks with lamps waiting for full loading (converted through the world): queued once a second. */
    private final LongOpenHashSet plotsWaiting = new LongOpenHashSet();
    /**
     * Partly scanned chunk: resume position ({@link ChunkLights.Pass#next} × 2 + “darken”). The position only moves forward,
     * so the chunk finishes in a finite number of passes whatever other mods do to its blocks. Opposite direction (power
     * restored during darkening), a new trigger (a lamp placed before the position), or unload starts it over.
     * Queued chunks and plots waiting for full loading ({@link #plotsWaiting}) have a resume position.
     */
    private final Long2IntOpenHashMap resume = new Long2IntOpenHashMap();
    /** District → when its sound last played (click and hum for the whole district — once). */
    private final Long2LongOpenHashMap sounded = new Long2LongOpenHashMap();
    private boolean restored;

    /**
     * Where the budget goes (for /incoming grid status): counts and time for each work kind, over a window of
     * {@link #STATS_WINDOW} ticks. A queue consuming units without advancing is visible here by kind.
     */
    public enum Work {
        /** Chunk queue unit (includes everything below except relighting and cascade). */
        UNIT,
        /** Chunk not in memory — dropped. */
        GONE,
        /** Nothing to convert (palettes) — closed without a scan. */
        IDLE,
        /** Disk light: at lamp positions, chunk returns to the head. */
        STALE,
        /** Signal-controlled lamp check, chunk returns to the head. */
        RESIGNAL,
        /** World-path pass, completed. */
        PASS_DONE,
        /** World-path pass, lamps beyond the limit — chunk returns to the head. */
        PASS_MORE,
        /** Palette pass (loaded world's edge). */
        EDGE,
        /** Lamps converted (no separate time — included in passes). */
        LAMPS,
        /** Lamp after setBlock differs from what was placed (another mod restored or replaced the block). */
        REVERTED,
        /** Relighting unit for counterparts unrelated to blackout. */
        RELIGHT,
        /** Counterpart waiting for its chunk's neighbors (returns to the relight queue tail). */
        RELIGHT_WAIT,
        /** Cascade row (districts and deadlines of area chunks). */
        BUILD
    }

    /** Work counter window, ticks. */
    public static final int STATS_WINDOW = 100;
    private final long[] count = new long[Work.values().length], nanos = new long[Work.values().length];
    private long[] lastCount = new long[Work.values().length], lastNanos = new long[Work.values().length];
    private long statsWindow;
    /** Chunk taken by the queue most often in succession (over this window and the previous one): stuck queue head. */
    private long headChunk, repeatChunk, lastRepeatChunk;
    private int headRepeat, repeatMax, lastRepeatMax;

    /** Counters since world creation (for checks: the 100-tick window does not split them). */
    private final long[] total = new long[Work.values().length];

    private void note(Work w, long n, long t) {
        count[w.ordinal()] += n;
        nanos[w.ordinal()] += t;
        total[w.ordinal()] += n;
    }

    /** Work of each kind since world load (by {@link Work}). */
    public long[] totals() {
        return total.clone();
    }

    /** Previous window counters: {count, ns} by {@link Work}. */
    public long[][] work() {
        return new long[][]{lastCount.clone(), lastNanos.clone()};
    }

    /** Previous window: chunk taken most often in succession and how often ({@code null} if none was taken twice). */
    @Nullable
    public ChunkPos repeatedChunk() {
        return lastRepeatMax > 1 ? new ChunkPos(lastRepeatChunk) : null;
    }

    public int repeatedTimes() {
        return lastRepeatMax;
    }

    /** For {@link ModAttachments#BLACKOUT_WORLD}: separate for each world, lives while the world is loaded, non-persistent. */
    public BlackoutWorld() {
        resume.defaultReturnValue(-1);
    }

    public static BlackoutWorld get(ServerLevel level) {
        return level.getData(ModAttachments.BLACKOUT_WORLD);
    }

    // ---------------------------------------------------------------- events

    /** New outage: its cascade starts this tick. */
    void onOutage(Outage o) {
        sweeps.add(new Sweep(o, false));
    }

    /** Power restoration assigned to an outage: restoration cascade replaces the previous one (if already running). */
    void onRestore(Outage o) {
        sweeps.removeIf(s -> s.restore && s.outage.id() == o.id());
        sweeps.add(new Sweep(o, true));
    }

    /**
     * Bring a chunk into line with the grid at the first opportunity (loaded, reached by a cascade). An already queued chunk
     * resumes its pass: a repeated trigger (once-per-second signal check, event) does not restart it.
     */
    void enqueue(long chunk) {
        if (!queued.add(chunk)) return;
        resume.remove(chunk);
        (nearPlayer(chunk) ? near : ready).enqueue(chunk);
    }

    /** A lamp was placed: queue the chunk, and return a pass already beyond the lamp's position to it. */
    void lampPlaced(ServerLevel level, BlockPos pos) {
        long c = ChunkPos.asLong(pos);
        int r = resume.get(c);
        int at = level.getSectionIndex(pos.getY()) << 12 | (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15);
        if (r >= 0 && at < r >> 1) resume.put(c, at << 1 | r & 1);
        enqueue(c);
    }

    /** Chunk within {@link #NEAR_CHUNKS} of a player (using player chunks at tick start). */
    private boolean nearPlayer(long chunk) {
        return playerDistance(chunk) <= NEAR_CHUNKS;
    }

    /** Nearest player distance in chunks (maximum axis distance); no players — {@code Integer.MAX_VALUE}. */
    private int playerDistance(long chunk) {
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk), best = Integer.MAX_VALUE;
        for (int i = 0; i < players.size(); i++) {
            long p = players.getLong(i);
            best = Math.min(best, Math.max(Math.abs(ChunkPos.getX(p) - x), Math.abs(ChunkPos.getZ(p) - z)));
        }
        return best;
    }

    /**
     * General-queue chunks approached by a player move to the player-near queue (its tail, retaining pass position):
     * otherwise a chunk queued before the player's arrival would wait for the entire distant city.
     */
    void promoteNear() {
        if (players.isEmpty() || ready.isEmpty()) return;
        for (int i = ready.size(); i > 0; i--) {
            long c = ready.dequeueLong();
            (nearPlayer(c) ? near : ready).enqueue(c);
        }
    }

    private boolean queueEmpty() {
        return near.isEmpty() && ready.isEmpty();
    }

    private long take() {
        taken = near.isEmpty() ? ready : near;
        return taken.dequeueLong();
    }

    /** Counterpart unrelated to blackout (piston, craft) — relight at tick end if power is available. */
    public void relightLater(BlockPos pos) {
        relight.add(pos.asLong());
    }

    /** Whether a block has power: a Sable craft plot always does (its own supply), elsewhere it depends on the district. */
    public static boolean powered(ServerLevel level, BlockPos pos) {
        return SubLevels.isInPlot(level, Vec3.atCenterOf(pos)) || !PowerGrid.get(level).dark(pos.getX() >> 4, pos.getZ() >> 4, level.getGameTime());
    }

    /** Counterparts unrelated to blackout: {@link #LAMPS_PER_UNIT} per work unit. */
    private void relightPlaced(ServerLevel level, WorkClock clock) {
        while (!relight.isEmpty() && clock.canStart()) {
            long c0 = clock.begin(), t0 = System.nanoTime();
            long[] counted = count.clone(), timed = nanos.clone();
            int lamps = 0;
            try {
                for (int n = 0; n < LAMPS_PER_UNIT && !relight.isEmpty(); n++) {
                    BlockPos pos = BlockPos.of(relight.removeFirstLong());
                    boolean plot = SubLevels.isInPlot(level, Vec3.atCenterOf(pos));
                    // chunk ready (otherwise getBlockState loads it immediately); in the world, neighbors too (Sable reads neighbors),
                    // in a plot the craft itself holds neighboring chunks
                    if (!Terrain.ready(level, pos) || !plot && !NuclearTickets.aroundLoaded(level, pos)) {
                        long c = ChunkPos.asLong(pos);
                        if (inMemory(level, c) != null) {
                            relightWaiting.computeIfAbsent(c, k -> new LongArrayList()).add(pos.asLong());
                            note(Work.RELIGHT_WAIT, 1, 0);
                        }
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (GridLights.isUnlit(state) && powered(level, pos)) {
                        ChunkLights.relight(level, pos, state);
                        lamps++;
                    }
                }
            } finally {
                note(Work.RELIGHT, 1, clock.end(c0));
                long took = System.nanoTime() - t0;
                if (slow(level, took)) logSlowUnit(level, "relight", took, "lamps " + lamps, counted, timed);
            }
        }
    }

    /**
     * The chunk's counterparts can be relit (as in {@link #relightPlaced}): chunk fully loaded and, outside a Sable plot,
     * neighbors loaded. Neighbor load checks this itself ({@link #neighbourLoaded}).
     */
    private static boolean relightReady(ServerLevel level, long c) {
        ChunkPos p = new ChunkPos(c);
        return level.getChunkSource().getChunkNow(p.x, p.z) != null
                && (SubLevels.containing(level, p) != null || NuclearTickets.neighbourhoodLoaded(level, p));
    }

    /** Return the chunk's waiting counterparts to relighting. */
    private void retryRelight(long chunk) {
        LongArrayList waiting = relightWaiting.remove(chunk);
        if (waiting != null) relight.addAll(waiting);
    }

    /**
     * Lamps in Sable craft plots: craft assembly places blocks directly in plot sections (without {@code onPlace}), block
     * ticks do not run in the plot, and Sable saves the plot using its own codec, bypassing {@link ChunkSaves}. A craft has its
     * own power: every {@link #PLOT_SCAN} ticks, plot chunks with counterparts (by palettes) are relit — a fallback: a
     * counterpart with an assembled neighbor during assembly relights immediately ({@code Unlit.neighborChanged}). Scans
     * only palettes and sections with counterparts; lamps themselves stay within the budget ({@link #relightPlaced}).
     */
    public void relightPlots(ServerLevel level, List<SubLevelAccess> ships) {
        for (SubLevelAccess sub : ships) {
            var box = sub.boundingBox();
            double dx = box.maxX() - box.minX(), dy = box.maxY() - box.minY(), dz = box.maxZ() - box.minZ();
            // bounds are in world space: a craft scaled down in the world is that many times larger in its plot
            double r = (Math.sqrt(dx * dx + dy * dy + dz * dz) / 2 + 2) / SubLevels.minScale(sub);
            Vec3 c = SubLevels.toPlot(sub, SubLevels.center(sub));
            for (int cx = Mth.floor(c.x - r) >> 4; cx <= Mth.floor(c.x + r) >> 4; cx++) {
                for (int cz = Mth.floor(c.z - r) >> 4; cz <= Mth.floor(c.z + r) >> 4; cz++) {
                    // do not touch (or load) a chunk in another plot or outside a plot
                    if (SubLevels.containing(level, new ChunkPos(cx, cz)) != sub) continue;
                    if (!(level.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk) || !ChunkLights.anyUnlit(chunk.getSections())) continue;
                    int x0 = cx << 4, z0 = cz << 4;
                    LevelChunkSection[] sections = chunk.getSections();
                    for (int i = 0; i < sections.length; i++) {
                        LevelChunkSection section = sections[i];
                        if (section.hasOnlyAir() || !Palettes.contains(section.getStates(), GridLights::isUnlit)) continue;
                        int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                for (int x = 0; x < 16; x++) {
                                    BlockState state = section.getBlockState(x, y, z);
                                    if (GridLights.isUnlit(state)) relight.add(BlockPos.asLong(x0 + x, y0 + y, z0 + z));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Chunk unloaded: the next load removes disk light. Unchecked signal-controlled lamps went to disk as they were —
     * as in any chunk at the loaded world's edge, the next neighbor update checks them.
     */
    void forget(long chunk) {
        staleLight.remove(chunk);
        resignal.remove(chunk);
        resume.remove(chunk);
        relightWaiting.remove(chunk);
        plotsWaiting.remove(chunk);
    }

    /** Chunk loaded: queue neighboring chunks whose lamps were waiting for neighbors to check their signal. */
    void neighbourLoaded(ChunkPos pos) {
        if (resignal.isEmpty() && relightWaiting.isEmpty()) return;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long c = ChunkPos.asLong(pos.x + dx, pos.z + dz);
                if ((dx != 0 || dz != 0) && resignal.containsKey(c)) enqueue(c);
                retryRelight(c);
            }
        }
    }

    /** Chunk loaded with lamps at {@code at} darkened in its palette: remove their disk light (see {@link #staleLight}). */
    void staleLight(ChunkPos chunk, LongArrayList at) {
        if (at.isEmpty()) staleLight.remove(chunk.toLong());
        else staleLight.put(chunk.toLong(), at);
    }

    /** Chunks awaiting conversion and counterparts awaiting relighting (for /incoming grid status). */
    public int[] backlog() {
        return new int[]{near.size() + ready.size(), relight.size()};
    }

    /**
     * Whether work exists without outages: queues, disk light, lamp checks. A world with neither outages nor work does not
     * tick ({@link Blackouts#work}).
     */
    public boolean busy() {
        return !idle() || !staleLight.isEmpty() || !resignal.isEmpty();
    }

    /**
     * The queue is busy and chunk {@code chunk} has disk light or a signal check waiting (checks). Other chunks' pending
     * checks do not count: an edge chunk of another check with a signal-controlled lamp waits for neighbors until unload.
     */
    public boolean busy(ChunkPos chunk) {
        return !idle() || staleLight.containsKey(chunk.toLong()) || resignal.containsKey(chunk.toLong());
    }

    /** What the queue is busy with (checks). */
    public String busyState() {
        return "queue " + (idle() ? "empty" : "not empty") + ", disk light cleanup pending in " + staleLight.size() + " chunks, signal recheck in " + resignal.size();
    }

    // ---------------------------------------------------------------- tick

    /** @param clock server tick blackout budget, shared by all dimensions (already started) */
    public void tick(ServerLevel level, WorkClock clock) {
        long now = level.getGameTime();
        if (now / STATS_WINDOW != statsWindow) {
            // world did not tick for a whole window — previous window is empty
            boolean adjacent = now / STATS_WINDOW == statsWindow + 1;
            lastCount = adjacent ? count.clone() : new long[count.length];
            lastNanos = adjacent ? nanos.clone() : new long[nanos.length];
            Arrays.fill(count, 0);
            Arrays.fill(nanos, 0);
            lastRepeatChunk = repeatChunk;
            lastRepeatMax = adjacent ? repeatMax : 0;
            repeatMax = 0;
            statsWindow = now / STATS_WINDOW;
        }
        players.clear();
        for (var p : level.players()) players.add(p.chunkPosition().toLong());
        PowerGrid grid = PowerGrid.get(level);
        if (!restored) restore(level, grid, now);
        relightPlaced(level, clock);
        scheduleRestoreSweeps(grid, now);
        if (now % 20 == 0) {
            grid.prune(now);
            sounded.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > 200);
            // chunk left memory without becoming full (ring around edge chunks): it has no unload event
            var chunkMap = level.getChunkSource().chunkMap;
            staleLight.keySet().removeIf(c -> chunkMap.getVisibleChunkIfPresent(c) == null);
            resignal.keySet().removeIf(c -> chunkMap.getVisibleChunkIfPresent(c) == null);
            relightWaiting.keySet().removeIf(c -> chunkMap.getVisibleChunkIfPresent(c) == null);
            plotsWaiting.removeIf(c -> {
                if (chunkMap.getVisibleChunkIfPresent(c) != null) return false;
                resume.remove(c);
                return true;
            });
            // neighbor returned to full loading without an event (dropped below it at the visibility edge) — check signal now
            for (long c : resignal.keySet()) {
                ChunkPos p = new ChunkPos(c);
                if (level.getChunkSource().getChunkNow(p.x, p.z) != null && NuclearTickets.neighbourhoodLoaded(level, p)) enqueue(c);
            }
            // Sable plots receive no neighbor events: waiting counterparts and plot chunks are checked once a second when ready
            // (otherwise every waiting counterpart would lead the queue once a second and return to waiting)
            for (long c : relightWaiting.keySet().toLongArray()) {
                if (relightReady(level, c)) retryRelight(c);
            }
            for (long c : plotsWaiting.toLongArray()) {
                ChunkPos p = new ChunkPos(c);
                if (level.getChunkSource().getChunkNow(p.x, p.z) != null) {
                    plotsWaiting.remove(c);
                    // pass position retained (resume): continue from it
                    if (queued.add(c)) (nearPlayer(c) ? near : ready).enqueue(c);
                }
            }
            promoteNear();
        }
        advanceSweeps(level, grid, now, clock);
        while (!queueEmpty() && clock.canStart()) {
            long c0 = clock.begin(), t0 = System.nanoTime();
            long[] counted = count.clone(), timed = nanos.clone();
            long c = 0, first = 0;
            int chunks = 0;
            try {
                int work = 0;
                for (; chunks < CHUNKS_PER_UNIT && work < UNIT_WORK && !queueEmpty(); chunks++) {
                    c = take();
                    if (chunks == 0) first = c;
                    work += handle(level, grid, c, now, UNIT_WORK - work);
                }
            } catch (RuntimeException e) {
                if (!taken.isEmpty() && taken.firstLong() == c) taken.dequeueLong();
                queued.remove(c);
                resume.remove(c);
                Incoming.LOG.error("Blackout: switching chunk {} failed with an exception; chunk skipped", new ChunkPos(c), e);
            } finally {
                note(Work.UNIT, 1, clock.end(c0));
                long took = System.nanoTime() - t0;
                if (slow(level, took)) logSlowUnit(level, "queue", took, "chunks " + chunks + (chunks > 0 ? ", first " + new ChunkPos(first) : ""), counted, timed);
            }
        }
    }

    /** Whether to log a unit longer than {@link #SLOW_UNIT_NANOS} ({@link #logSlowUnit}): at most once per 10 s. */
    private boolean slow(ServerLevel level, long took) {
        long now = level.getGameTime();
        if (took <= SLOW_UNIT_NANOS || now < slowLogged + 200) return false;
        slowLogged = now;
        return true;
    }

    /**
     * Log a slow unit with a breakdown by work kind: what it contained and each kind's time. For a queue unit, the remainder
     * (“outside kinds”) is time assigned to no kind: GC pause, thread preemption (the integrated server runs beside the
     * client), district sound. A remainder exceeding half the unit is not blackout work: INFO, not WARN (one GC pause already
     * exceeds the threshold). Relighting and cascade rows have no kind breakdown — only time and amount.
     */
    private void logSlowUnit(ServerLevel level, String unit, long took, String what, long[] counted, long[] timed) {
        StringBuilder kinds = new StringBuilder();
        long inKinds = 0;
        for (Work w : Work.values()) {
            long n = count[w.ordinal()] - counted[w.ordinal()], t = nanos[w.ordinal()] - timed[w.ordinal()];
            // the whole unit (queue, relighting, cascade) is took
            if (w == Work.UNIT || w == Work.RELIGHT || w == Work.BUILD || n == 0 && t == 0) continue;
            inKinds += t;
            kinds.append(' ').append(w.name().toLowerCase(Locale.ROOT)).append('=').append(n);
            if (t > 0) kinds.append('/').append(ms(t)).append("ms");
        }
        // only a queue unit's time is split by kind (relighting and cascade have no kinds)
        boolean split = unit.equals("queue");
        long outside = Math.max(0, took - inKinds);
        if (split) kinds.append("; outside kinds ").append(ms(outside)).append("ms");
        String line = "Blackout ({}): work unit ({}) {} ms, {}" + (kinds.isEmpty() ? "{}" : ":{}");
        Object[] args = {level.dimension().location(), unit, ms(took), what, kinds};
        if (split && outside * 2 > took) Incoming.LOG.info(line, args);
        else Incoming.LOG.warn(line, args);
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e6);
    }

    /**
     * After world load: restart outage cascades still running; districts already reached by a cascade are issued in the
     * first tick (loaded chunks convert, the rest on load).
     */
    private void restore(ServerLevel level, PowerGrid grid, long now) {
        restored = true;
        for (Outage o : grid.outages()) {
            long dark = grid.swept(o.id(), false);
            // an outage started before the first tick (command, explosion) already has its own cascade
            boolean hasDark = sweeps.stream().anyMatch(sw -> !sw.restore && sw.outage.id() == o.id());
            boolean hasLight = sweeps.stream().anyMatch(sw -> sw.restore && sw.outage.id() == o.id());
            if (!hasDark && dark < o.lastDark()) sweeps.add(new Sweep(o, false));
            if (!hasLight && o.restoreAt() != Outage.NEVER && now >= o.restoreAt()) sweeps.add(new Sweep(o, true));
        }
    }

    /** Outages due for power restoration: restoration cascade (one per outage). */
    private void scheduleRestoreSweeps(PowerGrid grid, long now) {
        for (Outage o : grid.outages()) {
            if (o.restoreAt() == Outage.NEVER || now < o.restoreAt()) continue;
            if (sweeps.stream().noneMatch(s -> s.restore && s.outage.id() == o.id()) && grid.swept(o.id(), true) < o.restoreAt()) {
                sweeps.add(new Sweep(o, true));
            }
        }
    }

    /** Process cascades (a chunk row is a work unit) and issue chunks reached by the cascade. */
    private void advanceSweeps(ServerLevel level, PowerGrid grid, long now, WorkClock clock) {
        Iterator<Sweep> it = sweeps.iterator();
        while (it.hasNext()) {
            Sweep s = it.next();
            // outage forgotten (power restored everywhere) or assigned another restoration — cascade no longer needed
            Optional<Outage> current = grid.outages().stream().filter(o -> o.id() == s.outage.id()).findFirst();
            if (current.isEmpty() || s.restore && !current.get().equals(s.outage)) {
                it.remove();
                continue;
            }
            while (!s.built() && clock.canStart()) {
                long c0 = clock.begin(), t0 = System.nanoTime();
                long[] counted = count.clone(), timed = nanos.clone();
                s.buildRow();
                note(Work.BUILD, 1, clock.end(c0));
                long took = System.nanoTime() - t0;
                if (slow(level, took)) logSlowUnit(level, "cascade", took, "row of " + (s.maxX - s.minX + 1) + " chunks", counted, timed);
            }
            if (!s.built()) continue;
            while (s.cursor <= now && s.cursor <= s.last) {
                LongArrayList chunks = s.byDue.remove(s.cursor);
                if (chunks != null) {
                    // district outward from the player: lamps near the player go dark and relight first
                    if (!players.isEmpty()) {
                        long[] order = chunks.toLongArray();
                        LongArrays.quickSort(order, (a, b) -> Integer.compare(playerDistance(a), playerDistance(b)));
                        chunks = LongArrayList.wrap(order);
                    }
                    for (int i = 0; i < chunks.size(); i++) {
                        long c = chunks.getLong(i);
                        if (inMemory(level, c) != null) enqueue(c);
                        // not in memory: converts on load, with distant Distant Horizons LODs using a disk copy
                        else FarLods.request(level, c, false);
                    }
                }
                s.cursor++;
            }
            // save progress: fully processed — not repeated after restart
            grid.swept(s.outage.id(), s.restore, s.done() ? Long.MAX_VALUE : Math.min(now, s.last));
            if (s.done()) {
                it.remove();
                Incoming.LOG.info("Blackout #{} ({}): {} cascade covered the whole area", s.outage.id(), level.dimension().location(),
                        s.restore ? "restore" : "outage");
            }
        }
    }

    /**
     * Chunk from the queue, at most {@code limit} lamps or positions: if loaded, first remove disk light and check
     * signal-controlled lamps, then convert lamps; if more remains, return the chunk to the queue head. If unloaded, skip
     * (it converts itself on load). Returns work done (lamps and positions).
     */
    private int handle(ServerLevel level, PowerGrid grid, long c, long now, int limit) {
        headRepeat = c == headChunk ? headRepeat + 1 : 1;
        headChunk = c;
        if (headRepeat > repeatMax) {
            repeatMax = headRepeat;
            repeatChunk = c;
        }
        LevelChunk chunk = inMemory(level, c);
        ChunkPos pos = new ChunkPos(c);
        if (chunk == null) {
            queued.remove(c);
            staleLight.remove(c);
            resignal.remove(c);
            resume.remove(c);
            note(Work.GONE, 1, 0);
            return 0;
        }
        long t0 = System.nanoTime();
        // loaded world's edge: chunk in memory, neighbors absent (and will be until a player approaches); cannot wait for them —
        // blocks change in the palette, without neighbors (ChunkLights.applyInPlace)
        boolean edge = level.getChunkSource().getChunkNow(pos.x, pos.z) == null || !NuclearTickets.neighbourhoodLoaded(level, pos);
        LongArrayList stale = staleLight.get(c);
        if (stale != null) {
            // light reduction in the neighborhood; does not enter an unloaded neighbor — world's edge, not sent to players
            var light = level.getChunkSource().getLightEngine();
            int n = 0;
            for (; n < limit && !stale.isEmpty(); n++) light.checkBlock(BlockPos.of(stale.removeLong(stale.size() - 1)));
            if (stale.isEmpty()) staleLight.remove(c);
            taken.enqueueFirst(c);
            note(Work.STALE, 1, System.nanoTime() - t0);
            return n;
        }
        LongArrayList signalled = resignal.get(c);
        if (signalled != null && !edge) {
            // signal is read from neighbors — they are now loaded
            int n = 0;
            int most = Math.max(1, limit / WORLD_LAMP);
            for (; n < most && !signalled.isEmpty(); n++) ChunkLights.resignal(level, BlockPos.of(signalled.removeLong(signalled.size() - 1)));
            if (signalled.isEmpty()) resignal.remove(c);
            taken.enqueueFirst(c);
            note(Work.RESIGNAL, 1, System.nanoTime() - t0);
            return n * WORLD_LAMP;
        }
        boolean dark = grid.dark(pos.x, pos.z, now);
        // nothing to convert (most chunks have no lamps) — marks only
        boolean needed = ChunkLights.needs(chunk, dark);
        // mark before conversion: if it fails halfway, darkened lamps are already marked
        if (dark && needed) chunk.setData(ModAttachments.GRID_DARK, true);
        // Sable craft plot — through the world: craft physics reads its blocks (the craft itself holds neighboring plot chunks;
        // no neighbors outside the plot — the world's edge is no criterion here); a plot below full loading waits with its pass position
        boolean world = needed && SubLevels.containing(level, pos) != null;
        if (world && level.getChunkSource().getChunkNow(pos.x, pos.z) == null) {
            queued.remove(c);
            plotsWaiting.add(c);
            note(Work.EDGE, 1, System.nanoTime() - t0);
            return SCAN_COST;
        }
        int r = resume.remove(c);
        int from = r >= 0 && (r & 1) == (dark ? 1 : 0) ? r >> 1 : 0;
        ChunkLights.Pass pass = ChunkLights.Pass.NONE;
        int cost = world ? WORLD_LAMP : 1, resignalled = 0;
        if (world) {
            pass = ChunkLights.apply(level, chunk, dark, Math.max(1, limit / cost), from);
        } else if (needed) {
            LongArrayList lamps = new LongArrayList();
            pass = ChunkLights.applyInPlace(level, chunk, dark, limit, from, !edge, lamps);
            if (edge) {
                // signal is read from neighbors — check when they load
                if (!lamps.isEmpty()) resignal.computeIfAbsent(c, k -> new LongArrayList()).addAll(lamps);
            } else {
                for (int i = 0; i < lamps.size(); i++) ChunkLights.resignal(level, BlockPos.of(lamps.getLong(i)));
                resignalled = lamps.size();
            }
        }
        int changed = pass.changed();
        note(Work.LAMPS, changed, 0);
        note(Work.REVERTED, pass.reverted(), 0);
        note(!needed ? Work.IDLE : edge ? Work.EDGE : pass.done() ? Work.PASS_DONE : Work.PASS_MORE, 1, System.nanoTime() - t0);
        if (changed > 0) {
            districtSound(level, chunk, dark, now);
            // Distant Horizons LODs: a district goes dark (and relights) in the distance too, without waiting for chunk save
            DhUpdates.mark(level, pos, level.getGameTime() + DhUpdates.SETTLE);
        }
        // scanning section blocks (4096 blocks cost many lamps) is work too, even if no lamps remain
        int work = changed * cost + resignalled * WORLD_LAMP + (needed ? SCAN_COST : 0);
        if (!pass.done()) {
            // a sea lantern tower is not one unit: the rest in following units, from the resume position
            resume.put(c, pass.next() << 1 | (dark ? 1 : 0));
            taken.enqueueFirst(c);
            return work;
        }
        queued.remove(c);
        if (!dark && chunk.hasData(ModAttachments.GRID_DARK)) {
            // no mark — do not touch: removal marks the chunk unsaved
            chunk.removeData(ModAttachments.GRID_DARK);
        }
        return work;
    }

    /** Relay click and hum cutoff (or rising hum) for those near the district, once per district. */
    private void districtSound(ServerLevel level, LevelChunk chunk, boolean dark, long now) {
        ChunkPos pos = chunk.getPos();
        long district = Districts.of(pos.x, pos.z);
        if (sounded.containsKey(district)) return;
        sounded.put(district, now);
        int x = pos.getMiddleBlockX(), z = pos.getMiddleBlockZ();
        // height comes from the chunk in hand: an edge chunk may be below full loading, and level.getHeight would load it immediately
        Vec3 at = new Vec3(x + 0.5, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1, z + 0.5);
        S2C.GridDistrict packet = new S2C.GridDistrict(at, !dark);
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - at.x, dz = p.getZ() - at.z;
            if (dx * dx + dz * dz <= DISTRICT_SOUND_RANGE * DISTRICT_SOUND_RANGE) PacketDistributor.sendToPlayer(p, packet);
        }
    }

    /**
     * Chunk in memory: fully loaded or below full loading (at the visibility edge), but not unloaded; null — absent from
     * memory (as in {@code ScarQueue}).
     */
    @Nullable
    static LevelChunk inMemory(ServerLevel level, long pos) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos);
        return holder != null && holder.getChunkIfPresentUnchecked(ChunkStatus.FULL) instanceof LevelChunk chunk ? chunk : null;
    }

    /** For checks: whether all cascades have been processed. */
    public boolean idle() {
        return sweeps.isEmpty() && relight.isEmpty() && queueEmpty();
    }
}
