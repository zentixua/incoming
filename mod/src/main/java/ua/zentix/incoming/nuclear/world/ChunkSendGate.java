package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * A chunk reached by the wave but not yet ruined (loaded after detonation, by the zone or a player) is not sent intact:
 * while selecting player chunks ({@code PlayerChunkSender.sendNextChunks}), remove such chunks from the queue and restore
 * them immediately afterward ({@code mixin/net/PlayerChunkSenderMixin}). Held at most {@code ScarQueue.WITHHOLD_LIMIT}
 * ticks from the first post-wave chunk request; chunks sent before ruins count in the detonation summary.
 */
public final class ChunkSendGate {
    /** Mixin marker: {@code PlayerChunkSender} has it (verifies mixin application). */
    public interface Gated {}

    private ChunkSendGate() {}

    /**
     * Remove chunks that cannot be sent from the player's queue (server thread).
     *
     * @return removed chunks (restore after selection); null — none removed
     */
    @Nullable
    public static LongArrayList withhold(ServerLevel level, LongSet pending) {
        if (pending.isEmpty()) return null;
        NuclearWorld world = NuclearWorld.get(level);
        if (!world.mayWithhold()) return null;
        LongArrayList out = null;
        for (LongIterator it = pending.iterator(); it.hasNext(); ) {
            long c = it.nextLong();
            if (!world.withholds(level, c)) continue;
            it.remove();
            if (out == null) out = new LongArrayList();
            out.add(c);
        }
        return out;
    }
}
