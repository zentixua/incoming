package ua.zentix.incoming.nuclear.world;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;

/**
 * How a ruined chunk reaches the players already holding it.
 * <p>
 * Few changed positions go as vanilla section deltas ({@code ChunkHolder.blockChanged}, like {@code setBlock}). A heavily
 * ruined chunk goes as one whole-chunk packet, the way a chunk normally reaches a client: the client copies its sections
 * at once and takes the light from the packet. Section deltas make the client set every block one by one on its render
 * thread and propagate light for each itself (the server's light is already computed): about 16 µs a block in the host
 * pack (9 setting it, 55–63 % of that Voxy re-snapshotting the section per block; 7 client light), so the 247 thousand
 * positions a 15 kt wave sends within 10 s around a viewer at 1.5 km froze the client 2.2 s and then 1.8 s (wave-hitch
 * run 19.11.2026). A whole chunk costs the client about 0.55 ms (decode, light from the packet, Voxy and Sodium once
 * per chunk; same run, world load).
 * <p>
 * The whole chunk is sent after the server's light engine has finished this chunk's checks (the light fence: the
 * engine's wait completes on its thread, the send runs on the chunk cache's main-thread queue after the section light
 * marks the engine queued there, as {@code work/BlockLighting} does), so it carries the ruins' own light: a packet built
 * on the write tick would show the new blocks in the old light until the light updates arrive. Until it arrives the
 * client keeps the chunk as it was; ruins are applied {@link ScarQueue#EARLY} before the wave, and the fence takes about
 * a tick.
 */
public final class RuinDelivery {
    /** Client render-thread cost of one whole chunk from the server, ns (host pack, wave-hitch run 19.11.2026). */
    static final long CHUNK_NANOS = 550_000;
    /** Client render-thread cost of one position in a section delta, ns: setting it 9 µs plus its light 7 µs (same run). */
    static final long POSITION_NANOS = 16_000;
    /** Changed positions from which a watched chunk goes whole: where the deltas would cost the client more. */
    public static final int WHOLE_FROM = (int) ((CHUNK_NANOS + POSITION_NANOS - 1) / POSITION_NANOS);

    /** Threshold in force ({@link #WHOLE_FROM}; GameTests set it to send the same ruins both ways). */
    private static int wholeFrom = WHOLE_FROM;

    /** Statistics for the ruin summary line (server thread). */
    static long wholeChunks, wholePositions, deltaChunks, deltaPositions, lagTicksSum, lagTicksMax, gone;

    private RuinDelivery() {}

    /** Whether a chunk with {@code changed} positions goes to its viewers whole. */
    public static boolean whole(int changed) {
        return changed >= wholeFrom;
    }

    /** GameTest only: set the threshold, return the previous one. */
    public static int wholeFrom(int threshold) {
        int was = wholeFrom;
        wholeFrom = threshold;
        return was;
    }

    /** Viewers holding the chunk, as vanilla's own broadcast finds them ({@code ChunkHolder.broadcastChanges}). */
    static List<ServerPlayer> viewers(ChunkHolder holder) {
        return holder.playerProvider.getPlayers(holder.getPos(), false);
    }

    /** Count a chunk sent as {@code positions} section-delta positions. */
    static void deltas(int positions) {
        deltaChunks++;
        deltaPositions += positions;
    }

    /**
     * Send the chunk whole to its viewers once the light engine has finished its pending work for it (server thread;
     * the chunk's sections and light checks are already written and queued).
     */
    static void wholeAfterLight(ServerLevel level, LevelChunk chunk, int positions) {
        ChunkPos pos = chunk.getPos();
        long written = level.getGameTime();
        var main = level.getChunkSource().chunkMap.mainThreadExecutor;
        level.getChunkSource().getLightEngine().waitForPendingTasks(pos.x, pos.z)
                .thenRunAsync(() -> send(level, pos, positions, written), main::tell);
    }

    private static void send(ServerLevel level, ChunkPos pos, int positions, long written) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos.toLong());
        if (chunk == null || holder == null) {
            // unloaded meanwhile: a player who loads it again gets it as it is now
            gone++;
            return;
        }
        List<ServerPlayer> players = viewers(holder);
        if (players.isEmpty()) return;
        var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
        for (ServerPlayer p : players) p.connection.send(packet);
        long lag = level.getGameTime() - written;
        wholeChunks++;
        wholePositions += positions;
        lagTicksSum += lag;
        lagTicksMax = Math.max(lagTicksMax, lag);
    }

    /** Summary line part; resets the counters. */
    static String describe() {
        String s = String.format(java.util.Locale.ROOT,
                "whole chunks %d (%d positions, sent %.1f ticks after the write on average, at most %d; unloaded first %d), "
                        + "section deltas %d positions in %d chunks",
                wholeChunks, wholePositions, wholeChunks == 0 ? 0.0 : (double) lagTicksSum / wholeChunks, lagTicksMax, gone, deltaPositions,
                deltaChunks);
        wholeChunks = wholePositions = deltaChunks = deltaPositions = lagTicksSum = lagTicksMax = gone = 0;
        return s;
    }
}
