package ua.zentix.incoming.work;

import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.function.LongSupplier;

/**
 * Pace of the mod's background chunk loads from disk (ruin settlement, the zone behind a nuclear wave). Vanilla decodes
 * a chunk read from disk on the server thread ({@code ChunkMap.scheduleChunkLoad}: {@code ChunkSerializer.read} on the
 * main-thread executor), between ticks, and keeps running such tasks up to 50 ms after a tick ends
 * ({@code MinecraftServer.delayedTasksMaxNextTickTimeNanos}), past the next tick's scheduled start. Loads requested in
 * batches therefore start the next tick late: during the ruin settlement after 1 Mt over Zearth (r4 run6, 08.11.2026)
 * the tick itself took 20 ms (p50), but 20 % of tick-start intervals were 68–82 ms (spark "ticks over 60 ms"), with
 * 84 % of the server thread's between-tick JFR samples in {@code ChunkSerializer.read}, ~2.6 ms per chunk.
 * <p>
 * Loading one full chunk decodes its whole 3×3 square: vanilla's loading pyramid lights a chunk only with its ring-1
 * neighbors at {@code INITIALIZE_LIGHT} ({@code ChunkPyramid.LOADING_PYRAMID}), each read from disk. A load is charged
 * the chunks of that square that are not in memory at any status ({@link #decodes}).
 * <p>
 * The gap a tick leaves for decodes is measured where it really starts, at the end of the last Post listener: the next
 * scheduled start minus that moment. Requests come earlier, from the middle of the work lanes, so they use the smallest
 * gap of the last {@link #GAPS} ticks less {@link #MARGIN_NANOS}. A load may start while the decodes requested this tick
 * and not yet seen, times the measured decode cost, fit in it. The cost comes from consecutive
 * {@code ChunkDataEvent.Load} events (fired at the end of {@code ChunkSerializer.read}) within one gap; a pause over
 * {@link #IDLE_NANOS} between two is a wait for disk and not counted. A server with no gap still starts one load per
 * {@link #FLOOR_TICKS} ticks, so background work ends eventually.
 * <p>
 * State is per server (overworld attachment), not saved.
 */
public final class ChunkLoadPace {
    /** Decodes should end this long before the next tick's scheduled start (other between-tick tasks, estimate error). */
    static final long MARGIN_NANOS = 5_000_000;
    /** Starting decode cost until measured: Zearth city/terrain chunks, r4 run6. */
    static final double DEFAULT_COST_NANOS = 3e6;
    /**
     * A pause between two decodes longer than this is a wait for the disk read, not decoding. Generous: a disk wait taken
     * for a decode only slows loading, a heavy chunk (block entities) dropped from the average would overrun gaps.
     */
    static final long IDLE_NANOS = 30_000_000;
    /** Per-sample weight of the decode cost average (about a hundred decodes a second during settlement). */
    static final double WEIGHT = 0.05;
    /** Requested decodes not seen by the next tick keep this share per tick: their reads may still be queued. */
    static final double CARRY = 0.8;
    /** A server with no gap still starts one load per this many ticks. */
    static final int FLOOR_TICKS = 4;
    /** Gaps remembered; requests use the smallest (a slow tick, an autosave, holds loads back for a moment). */
    static final int GAPS = 10;
    /** Budget per tick while sprinting ({@code /tick sprint}: no schedule to keep). */
    static final long SPRINT_NANOS = 25_000_000;

    private final LongSupplier time;
    private double cost = DEFAULT_COST_NANOS;
    /** Decodes requested here and not yet seen. */
    private double outstanding;
    /** Gaps of the last ticks (next scheduled start minus tick end), ns. */
    private final long[] gaps = new long[GAPS];
    private int gapCount, gapNext;
    /** Time of the previous decode in the current gap; 0 before the first. */
    private long lastDecode;
    private long tickEnd;
    private int lastStartTick = Integer.MIN_VALUE / 2;
    /** Report counters: loads started, of them through the floor, decodes requested, decodes seen, decode-cost samples. */
    private long started, floored, requested, decoded, samples;
    /** Report counters: ticks, tick-start intervals over 60 ms, ticks starting over 10 ms after schedule, longest interval. */
    private long ticks, longIntervals, late, maxInterval, lastStart;

    public ChunkLoadPace() {
        this(Util::getNanos);
    }

    /** Controlled clock (tests). */
    ChunkLoadPace(LongSupplier time) {
        this.time = time;
    }

    public static ChunkLoadPace of(MinecraftServer server) {
        return server.overworld().getData(ModAttachments.CHUNK_LOAD_PACE);
    }

    /**
     * Chunks a full load of {@code (x, z)} reads from disk: its 3×3 square without a chunk in memory at any status
     * (holder absent, waiting to unload, or not yet holding a chunk). A chunk still loading counts again; that errs slow.
     */
    public static int decodes(ServerLevel level, int x, int z) {
        ChunkMap map = level.getChunkSource().chunkMap;
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = ChunkPos.asLong(x + dx, z + dz);
                ChunkHolder holder = map.getUpdatingChunkIfPresent(key);
                if (holder == null) holder = map.pendingUnloads.get(key);
                if (holder == null || holder.getLatestChunk() == null) n++;
            }
        }
        return n;
    }

    /** The smallest gap of the last ticks, less the margin; unknown before the first measured tick. */
    long gap() {
        if (gapCount == 0) return 0;
        long min = Long.MAX_VALUE;
        for (int i = 0; i < gapCount; i++) min = Math.min(min, gaps[i]);
        return min - MARGIN_NANOS;
    }

    /**
     * Whether a load decoding {@code decodes} chunks may start now; if so they are counted as requested. {@code tick}:
     * the server tick count.
     */
    boolean tryStart(int decodes, int tick, boolean sprinting) {
        if (decodes <= 0) return true;
        long left = sprinting ? SPRINT_NANOS : gap();
        boolean fits = left > 0 && (outstanding + decodes) * cost <= left;
        boolean floor = !fits && tick - lastStartTick >= FLOOR_TICKS;
        if (!fits && !floor) return false;
        outstanding += decodes;
        lastStartTick = tick;
        started++;
        requested += decodes;
        if (floor) floored++;
        return true;
    }

    /** Same for a full load of chunk {@code (x, z)} on the running server. */
    public boolean tryStart(ServerLevel level, int x, int z) {
        MinecraftServer server = level.getServer();
        return tryStart(decodes(level, x, z), server.getTickCount(), server.tickRateManager().isSprinting());
    }

    /** A chunk was decoded from disk on the server thread. */
    void decoded() {
        long now = time.getAsLong();
        long since = now - (lastDecode != 0 ? lastDecode : tickEnd);
        if (tickEnd != 0 && since > 0 && since <= IDLE_NANOS) {
            cost += WEIGHT * (since - cost);
            samples++;
        }
        lastDecode = now;
        outstanding = Math.max(0, outstanding - 1);
        decoded++;
    }

    /** The tick's work is over (last Post listener): decodes start from here. {@code nextTick}: next scheduled start. */
    void tickEnded(long nextTick) {
        tickEnd = time.getAsLong();
        lastDecode = 0;
        gaps[gapNext] = nextTick - tickEnd;
        gapNext = (gapNext + 1) % GAPS;
        gapCount = Math.min(GAPS, gapCount + 1);
    }

    /**
     * A new tick starts: the gap's decodes are over; decodes still not seen stay requested only partly. {@code scheduled}:
     * the start vanilla scheduled for this tick (0 unknown), counted for the report with the interval since the last start.
     */
    void tickStarted(long scheduled) {
        long now = time.getAsLong();
        tickEnd = 0;
        lastDecode = 0;
        outstanding *= CARRY;
        if (lastStart != 0) {
            long interval = now - lastStart;
            ticks++;
            if (interval > 60_000_000L) longIntervals++;
            maxInterval = Math.max(maxInterval, interval);
            if (scheduled != 0 && now - scheduled > 10_000_000L) late++;
        }
        lastStart = now;
    }

    double costNanos() { return cost; }
    double outstanding() { return outstanding; }

    /**
     * Report fragment, counters since the last report: tick starts (what spark's tick monitor sees: intervals include the
     * tasks vanilla runs between ticks, which the tick time does not), then the paced loads.
     */
    public String report() {
        long n = Math.max(1, ticks);
        String out = String.format(java.util.Locale.ROOT,
                "tick starts: intervals over 60 ms %.1f%%, late over 10 ms %.1f%%, longest interval %.1f ms; "
                        + "paced chunk loads %d (%d at the floor) for %d decodes, decoded %d, decode %.2f ms (%d samples)",
                longIntervals * 100.0 / n, late * 100.0 / n, maxInterval / 1e6, started, floored, requested, decoded, cost / 1e6, samples);
        started = floored = requested = decoded = samples = ticks = longIntervals = late = maxInterval = 0;
        return out;
    }

    public static void onTickStart(ServerTickEvent.Pre e) {
        MinecraftServer server = e.getServer();
        // vanilla advanced its schedule by one tick before this tick began
        long scheduled = server.tickRateManager().isSprinting() ? 0 : server.getNextTickTime() - server.tickRateManager().nanosecondsPerTick();
        of(server).tickStarted(scheduled);
    }

    public static void onTickEnd(ServerTickEvent.Post e) {
        of(e.getServer()).tickEnded(e.getServer().getNextTickTime());
    }

    public static void onChunkData(ChunkDataEvent.Load e) {
        // a proto chunk (not fully generated on disk) may carry no level
        MinecraftServer server = e.getLevel() instanceof ServerLevel level ? level.getServer()
                : net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.isSameThread() && server.overworld() != null) of(server).decoded();
    }
}
