package ua.zentix.incoming.work;

import net.minecraft.server.MinecraftServer;
import ua.zentix.incoming.registry.ModAttachments;

/**
 * {@link ChunkLoadPace} that admits every load, for a GameTest that loads what tickets ask for at once
 * ({@code InstantChunks.settle}): the real pace measures the gap against the server's tick schedule, which a GameTest
 * server, ticking without pauses, runs ahead of or behind depending on the machine, and counts a decode as outstanding
 * until it lands, between ticks or inside the test's tick; how many zone-square tickets a tick takes would follow the
 * runner, not the game. Here the gap is an hour on every tick, so no count of outstanding decodes reaches it.
 */
public final class OpenLoadPace {
    /** Gap every tick reports, ns: far above any count of outstanding decodes at the starting cost (1 ms). */
    private static final long GAP_NANOS = 3_600_000_000_000L;

    private OpenLoadPace() {}

    /** Installs the open pace; returns the server's own, to put back with {@link #restore}. */
    public static ChunkLoadPace install(MinecraftServer server) {
        ChunkLoadPace own = ChunkLoadPace.of(server);
        // "now" is always the gap before the next scheduled start; no thread time passes, so the cost is never resampled
        ChunkLoadPace open = new ChunkLoadPace(() -> server.getNextTickTime() - GAP_NANOS, () -> 0L);
        server.overworld().setData(ModAttachments.CHUNK_LOAD_PACE, open);
        return own;
    }

    public static void restore(MinecraftServer server, ChunkLoadPace own) {
        server.overworld().setData(ModAttachments.CHUNK_LOAD_PACE, own);
    }
}
