package ua.zentix.incoming.work;

import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
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
 * A load may start only while the decodes already requested fit before the next tick's scheduled start: the time left
 * to it, less {@link #MARGIN_NANOS}, divided by the measured decode cost, less requested loads not yet decoded. The cost
 * is measured from consecutive {@code ChunkDataEvent.Load} events (fired at the end of {@code ChunkSerializer.read}) in
 * the same gap; a longer pause between two of them is a wait for disk and not counted. A server with no time left still
 * starts one load per {@link #FLOOR_TICKS} ticks, so background work ends eventually.
 * <p>
 * State is per server (overworld attachment), not saved.
 */
public final class ChunkLoadPace {
    /** Decodes should end this long before the next tick's scheduled start (other between-tick tasks, estimate error). */
    static final long MARGIN_NANOS = 5_000_000;
    /** Starting decode cost until measured: Zearth city/terrain chunks, r4 run6. */
    static final double DEFAULT_COST_NANOS = 3e6;
    /** A pause between two decodes longer than this is a wait for the disk read, not decoding. */
    static final long IDLE_NANOS = 15_000_000;
    /** Per-sample weight of the decode cost average (about a hundred decodes a second during settlement). */
    static final double WEIGHT = 0.05;
    /** Requested loads not decoded by the next tick keep this share per tick: their reads may still be queued. */
    static final double CARRY = 0.8;
    /** A server with no time left still starts one load per this many ticks. */
    static final int FLOOR_TICKS = 4;
    /** Budget per tick while sprinting ({@code /tick sprint}: no schedule to keep). */
    static final long SPRINT_NANOS = 25_000_000;

    private final LongSupplier time;
    private double cost = DEFAULT_COST_NANOS;
    /** Loads started here and not yet seen decoded. */
    private double outstanding;
    /** Time of the previous decode in the current gap; 0 before the first. */
    private long lastDecode;
    private long tickEnd;
    private int lastStartTick = Integer.MIN_VALUE / 2;
    /** Report counters: loads started, of them through the floor, decodes seen, decode-cost samples. */
    private long started, floored, decoded, samples;
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
     * Whether one more chunk load may start now; if so it is counted as requested. {@code nextTick}: the server's next
     * scheduled tick start ({@code MinecraftServer.getNextTickTime}); {@code tick}: its tick count.
     */
    boolean tryStart(long nextTick, int tick, boolean sprinting) {
        long left = sprinting ? SPRINT_NANOS : nextTick - time.getAsLong() - MARGIN_NANOS;
        boolean fits = left > 0 && (outstanding + 1) * cost <= left;
        boolean floor = !fits && tick - lastStartTick >= FLOOR_TICKS;
        if (!fits && !floor) return false;
        outstanding++;
        lastStartTick = tick;
        started++;
        if (floor) floored++;
        return true;
    }

    /** Same for the running server. */
    public boolean tryStart(MinecraftServer server) {
        return tryStart(server.getNextTickTime(), server.getTickCount(), server.tickRateManager().isSprinting());
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

    /** The tick's work is over (last Post listener): decodes start from here. */
    void tickEnded() {
        tickEnd = time.getAsLong();
        lastDecode = 0;
    }

    /**
     * A new tick starts: the gap's decodes are over; loads still not decoded stay requested only partly. {@code scheduled}:
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
                        + "paced chunk loads %d (%d at the floor), decoded %d, decode %.2f ms (%d samples)",
                longIntervals * 100.0 / n, late * 100.0 / n, maxInterval / 1e6, started, floored, decoded, cost / 1e6, samples);
        started = floored = decoded = samples = ticks = longIntervals = late = maxInterval = 0;
        return out;
    }

    public static void onTickStart(ServerTickEvent.Pre e) {
        MinecraftServer server = e.getServer();
        // vanilla advanced its schedule by one tick before this tick began
        long scheduled = server.tickRateManager().isSprinting() ? 0 : server.getNextTickTime() - server.tickRateManager().nanosecondsPerTick();
        of(server).tickStarted(scheduled);
    }

    public static void onTickEnd(ServerTickEvent.Post e) {
        of(e.getServer()).tickEnded();
    }

    public static void onChunkData(ChunkDataEvent.Load e) {
        if (e.getLevel() instanceof net.minecraft.server.level.ServerLevel level && level.getServer().isSameThread()) of(level.getServer()).decoded();
    }
}
