package ua.zentix.incoming.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import ua.zentix.incoming.Incoming;

import java.util.concurrent.locks.LockSupport;

/**
 * Server shutdown: chunk generation and loading begun before exit are drained in {@code ServerStoppingEvent}, before
 * vanilla's unload loop in {@code MinecraftServer.stopServer}.
 * <p>
 * Why. A generation task ({@code ChunkGenerationTask}) holds every chunk holder in its window ({@code acquireGeneration}:
 * {@code getGenerationRefCount() > 0}) until its stages complete, even when canceled; a stage (disk chunk decoding, FULL
 * step) completes only through a server-thread task ({@code ServerChunkCache.pollTask}). Vanilla shutdown calls
 * {@code ServerChunkCache.tick(() -> true, false)}: {@code ChunkMap.processUnloads} drains the unload queue without limit,
 * while an unload task for a generation-held holder ({@code isReadyForSaving} false) immediately requeues itself
 * ({@code scheduleUnload}). The call never returns, server-thread tasks do not run, generation never ends — exit hangs
 * at “Saving worlds” (after a nuclear strike, 632 s in {@code processUnloads}). Player tickets also start generation,
 * but mod areas and zones (ruins, preparation, target areas, approach corridors, player arrival) make this frequent.
 * <p>
 * What it does. After mod tickets and work are released (normal-priority handlers; areas via {@code StrikeWorld.releaseAreas}),
 * this handler (last, {@code LOWEST}) polls each world's chunk tasks ({@code pollTask}: also applies removed tickets via
 * {@code runDistanceManagerUpdates} and wakes lighting) until no chunk-map holder is generating ({@link #inFlight}),
 * at most {@link #LIMIT_NANOS} (then logs and proceeds with vanilla shutdown). Nobody requests new generation afterward:
 * shutdown only removes tickets. Vanilla code is unchanged. Checked by GameTest {@code stopDrainLetsVanillaUnloadFinish}.
 */
public final class StopDrain {
    /** Wait limit for the whole server. */
    public static final long LIMIT_NANOS = 30_000_000_000L;
    /** How often to log ongoing waiting. */
    private static final long REPORT_NANOS = 5_000_000_000L;

    private StopDrain() {}

    /** {@code ServerStoppingEvent}, {@code LOWEST} priority: after the mod releases its tickets and work. */
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        if (!server.isSameThread()) return;
        if (drain(server.getAllLevels(), System.nanoTime() + LIMIT_NANOS)) return;
        StringBuilder s = new StringBuilder();
        for (ServerLevel level : server.getAllLevels()) {
            int n = inFlight(level);
            if (n > 0) s.append(' ').append(level.dimension().location()).append(" — ").append(n);
        }
        Incoming.LOG.warn("Shutdown: chunk generation not finished within {} s, holders busy:{} — exit will probably hang at \"Saving worlds\"",
                LIMIT_NANOS / 1_000_000_000L, s);
    }

    /**
     * Run world chunk tasks while generation or loading is active.
     *
     * @param deadline {@code System.nanoTime()} after which to stop
     * @return no generation remains in any world
     */
    public static boolean drain(Iterable<ServerLevel> levels, long deadline) {
        long report = System.nanoTime() + REPORT_NANOS;
        while (true) {
            boolean busy = false;
            for (ServerLevel level : levels) {
                ServerChunkCache cache = level.getChunkSource();
                while (cache.pollTask()) {
                    if (System.nanoTime() > deadline) return false;
                }
                if (inFlight(level) > 0) busy = true;
            }
            if (!busy) return true;
            long now = System.nanoTime();
            if (now > deadline) return false;
            if (now >= report) {
                report = now + REPORT_NANOS;
                int n = 0;
                for (ServerLevel level : levels) n += inFlight(level);
                Incoming.LOG.info("Shutdown: waiting for chunk generation started before exit (holders busy {})", n);
            }
            // generation stages run on generation, I/O and light threads; they submit server-thread tasks themselves
            LockSupport.parkNanos(1_000_000L);
        }
    }

    /**
     * World chunk-map holders busy generating or loading (generation task takes center and whole window, releases only
     * after its stages complete). Apply tickets first ({@code runDistanceManagerUpdates}) so visible map matches working
     * map; a generation-busy holder remains in the working map ({@code processUnloads} skips it).
     */
    public static int inFlight(ServerLevel level) {
        ServerChunkCache cache = level.getChunkSource();
        cache.runDistanceManagerUpdates();
        int n = 0;
        for (ChunkHolder holder : cache.chunkMap.getChunks()) {
            if (holder.getGenerationRefCount() > 0) n++;
        }
        return n;
    }
}
