package ua.zentix.incoming.work;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.metrics.MetricCategory;
import ua.zentix.incoming.nuclear.world.WorkClock;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.LongToIntFunction;
import java.util.function.Supplier;

/**
 * A passive observer of vanilla's existing profiler sections, installed only for one server tick.
 * Level events bracket simulation but include ChunkMap's optional autosave. Only a chunk_unload section with
 * no unload work required without hasTime is excluded; pending futures alone do not keep it in the reserve.
 * No section name suppliers are evaluated, no stacks/paths are allocated, and only section boundaries read time.
 */
public final class ImpactTiming implements ProfilerFiller {
    final TickReserve reserve = new TickReserve();
    private final LongSupplier time;
    private ProfilerFiller previous, installed;
    private BooleanSupplier savingOnly = () -> false;
    private long started, optionalStarted, optionalNanos;
    private int depth, optionalDepth = -1;
    private int chunkUnloadSections;
    private boolean unloadDebt;
    boolean earlyVisited;
    boolean active;
    WorkClock earlyClock;
    long earlyUsed, earlyBudget;

    public ImpactTiming() {
        this(Util::getNanos);
    }

    /** Controlled clock for policy tests; this observer never changes the supplied hasTime signal. */
    public ImpactTiming(LongSupplier time) {
        this.time = time;
    }

    void begin(MinecraftServer server) {
        begin(() -> false);
        active = true;
        previous = server.getProfiler();
        installed = ProfilerFiller.tee(previous, this);
        server.profiler = installed;
    }

    public void begin(BooleanSupplier savingOnly) {
        this.savingOnly = savingOnly;
        started = time.getAsLong();
        optionalNanos = 0;
        depth = 0;
        optionalDepth = -1;
        chunkUnloadSections = 0;
        unloadDebt = false;
        earlyVisited = false;
        earlyClock = null;
        active = false;
        earlyUsed = 0;
        earlyBudget = 0;
    }

    void level(ServerLevel level) {
        var map = level.getChunkSource().chunkMap;
        LongToIntFunction generationRefs = pos -> {
            var holder = map.getUpdatingChunkIfPresent(pos);
            return holder == null ? 0 : holder.getGenerationRefCount();
        };
        savingOnly = () -> !hasMandatoryUnloads(map.toDrop, generationRefs, map.unloadQueue);
    }

    /** Match processUnloads with hasTime=false: removable holders, or queued callbacks beyond 2000. */
    static boolean hasMandatoryUnloads(LongSet toDrop, LongToIntFunction generationRefs, Iterable<?> unloadQueue) {
        for (var it = toDrop.iterator(); it.hasNext(); ) {
            // Missing holders are stale IDs that vanilla removes; generation-held entries are left alone.
            if (generationRefs.applyAsInt(it.nextLong()) == 0) return true;
        }
        // pendingUnloads may only be waiting on save futures. Even ready callbacks below this threshold wait
        // for hasTime. Stop counting at the threshold rather than walking an arbitrarily large queue twice.
        int queued = 0;
        for (Object ignored : unloadQueue) if (++queued > 2000) return true;
        return false;
    }

    void finish(MinecraftServer server, long ownNanos) {
        if (server.profiler == installed) server.profiler = previous;
        if (server.tickRateManager().runsNormally() && !server.tickRateManager().isSprinting()) {
            reserve.observe(mandatoryNanos(ownNanos));
        }
        previous = installed = null;
        active = false;
        savingOnly = () -> false;
        earlyClock = null;
    }

    public long mandatoryNanos(long ownNanos) {
        return Math.max(0, time.getAsLong() - started - optionalNanos - ownNanos);
    }

    public long optionalNanos() {
        return optionalNanos;
    }

    /** Actual vanilla sections seen this tick, also exposed to the short native integration test. */
    public int chunkUnloadSections() {
        return chunkUnloadSections;
    }

    public boolean hasUnloadDebt() {
        return unloadDebt;
    }

    @Override public void push(String name) {
        depth++;
        if (optionalDepth < 0 && "chunk_unload".equals(name)) {
            chunkUnloadSections++;
            if (savingOnly.getAsBoolean()) {
                optionalDepth = depth;
                optionalStarted = time.getAsLong();
            } else {
                unloadDebt = true;
            }
        }
    }

    @Override public void push(Supplier<String> name) { depth++; }

    @Override public void pop() {
        if (optionalDepth >= 0 && depth == optionalDepth) {
            optionalNanos += time.getAsLong() - optionalStarted;
            optionalDepth = -1;
        }
        depth--;
    }

    @Override public void popPush(String name) { pop(); push(name); }
    @Override public void popPush(Supplier<String> name) { pop(); push(name); }
    @Override public void startTick() {}
    @Override public void endTick() {}
    @Override public void markForCharting(MetricCategory category) {}
    @Override public void incrementCounter(String name, int increment) {}
    @Override public void incrementCounter(Supplier<String> name, int increment) {}
}
