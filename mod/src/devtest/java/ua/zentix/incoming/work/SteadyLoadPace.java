package ua.zentix.incoming.work;

import net.minecraft.server.MinecraftServer;
import ua.zentix.incoming.registry.ModAttachments;

/**
 * {@link ChunkLoadPace} on game ticks for a GameTest: every tick leaves one whole tick's gap and decodes keep their
 * starting cost, whatever the wall clock does. The real pace measures the gap against the server's tick schedule, which
 * a GameTest server, ticking without pauses, runs ahead of or behind depending on the machine; how many zone-square
 * tickets a tick takes would then follow the runner, not the game.
 */
public final class SteadyLoadPace {
    private SteadyLoadPace() {}

    /** Installs the steady pace; returns the server's own, to put back with {@link #restore}. */
    public static ChunkLoadPace install(MinecraftServer server) {
        ChunkLoadPace own = ChunkLoadPace.of(server);
        // the schedule's own start of this tick: the gap is the next start minus it, one tick; no thread time passes
        ChunkLoadPace steady = new ChunkLoadPace(() -> server.getNextTickTime() - server.tickRateManager().nanosecondsPerTick(), () -> 0L);
        server.overworld().setData(ModAttachments.CHUNK_LOAD_PACE, steady);
        return own;
    }

    public static void restore(MinecraftServer server, ChunkLoadPace own) {
        server.overworld().setData(ModAttachments.CHUNK_LOAD_PACE, own);
    }
}
