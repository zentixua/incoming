package ua.zentix.incoming.nuclear.world;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

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
 * A whole chunk waits for light because a packet built on the write tick could show new blocks in old light.
 * Consecutive ruin writes share one pending send, fenced by the light checks of the latest write. The fence completes
 * on the light thread and the send runs on the chunk cache's main-thread queue, after the section light marks queued
 * there. An earlier fence could send newer blocks in older light. While a delivery waits, vanilla's light broadcast
 * for that chunk is held for up to {@link #HOLD_LIMIT} ticks per hold ({@link #onLevelTickPre}). Light from the
 * chunk's new blocks or from a ruined neighbour could otherwise reach the client's old blocks.
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

    /**
     * Ticks a chunk's light broadcast may be held for its whole-chunk send: a light fence that never completes (stopped
     * light engine) must not leave the client's light stale for good. 10 s, far beyond the 35 ticks measured at most.
     */
    static final int HOLD_LIMIT = 200;

    /** Statistics for the ruin summary line (server thread). */
    static long wholeChunks, wholePositions, deltaChunks, deltaPositions, lagTicksSum, lagTicksMax, gone, held, expired;

    /** One pending whole-chunk delivery, owned by the server thread. */
    static final class PendingWhole {
        private final LevelChunk chunk;
        private final long firstWrite;
        private long holdSince;
        private long revision;
        private long positions;
        private boolean holding = true;

        private PendingWhole(LevelChunk chunk, long firstWrite) {
            this.chunk = chunk;
            this.firstWrite = firstWrite;
            this.holdSince = firstWrite;
        }

        private void expireHold(long now) {
            if (holding && now - holdSince >= HOLD_LIMIT) {
                holding = false;
                expired++;
            }
        }
    }

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
     * Fence this revision of a pending whole-chunk delivery (server thread; the chunk's sections and light checks
     * are already written and queued). Only the latest revision can serialize the live chunk.
     */
    static void wholeAfterLight(ServerLevel level, LevelChunk chunk, int positions) {
        ChunkPos pos = chunk.getPos();
        long key = pos.toLong();
        NuclearWorld world = NuclearWorld.get(level);
        PendingWhole pending = world.wholeWaiting.get(key);
        long written = level.getGameTime();
        if (pending == null || pending.chunk != chunk) {
            pending = new PendingWhole(chunk, written);
            world.wholeWaiting.put(key, pending);
        }
        pending.expireHold(written);
        if (!pending.holding) {
            pending.holdSince = written;
            pending.holding = true;
        }
        pending.positions += positions;
        long revision = ++world.wholeRevision;
        pending.revision = revision;
        var main = level.getChunkSource().chunkMap.mainThreadExecutor;
        level.getChunkSource().getLightEngine().waitForPendingTasks(pos.x, pos.z)
                .thenRunAsync(() -> send(level, pos, revision), main::tell);
    }

    private static void send(ServerLevel level, ChunkPos pos, long revision) {
        var waiting = NuclearWorld.get(level).wholeWaiting;
        long key = pos.toLong();
        PendingWhole pending = waiting.get(key);
        if (pending == null || pending.revision != revision) return;
        waiting.remove(key);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(key);
        if (chunk != pending.chunk || holder == null) {
            // unloaded meanwhile: a player who loads it again gets it as it is now
            gone++;
            return;
        }
        List<ServerPlayer> players = viewers(holder);
        if (players.isEmpty()) return;
        var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
        for (ServerPlayer p : players) p.connection.send(packet);
        long lag = level.getGameTime() - pending.firstWrite;
        wholeChunks++;
        wholePositions += pending.positions;
        lagTicksSum += lag;
        lagTicksMax = Math.max(lagTicksMax, lag);
    }

    /**
     * Before the world ticks (its chunk cache broadcasts light changes at the end of its tick): drop the pending light
     * broadcast of chunks waiting to go whole; the whole chunk carries their light.
     */
    public static void onLevelTickPre(LevelTickEvent.Pre e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        NuclearWorld w = NuclearWorld.get(level);
        if (w.wholeWaiting.isEmpty()) return;
        long now = level.getGameTime();
        for (var it = w.wholeWaiting.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            var en = it.next();
            PendingWhole pending = en.getValue();
            long key = en.getLongKey();
            pending.expireHold(now);
            if (!pending.holding) {
                // The latest fence can still deliver the blocks after the vanilla light broadcast resumes.
                if (level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != pending.chunk) {
                    it.remove();
                    gone++;
                }
                continue;
            }
            ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(key);
            if (holder != null && holder.getTickingChunk() == pending.chunk && hold(holder)) held++;
        }
    }

    /** Clear the holder's pending light broadcast; whether there was one. */
    public static boolean hold(ChunkHolder holder) {
        boolean any = !holder.skyChangedLightSectionFilter.isEmpty() || !holder.blockChangedLightSectionFilter.isEmpty();
        holder.skyChangedLightSectionFilter.clear();
        holder.blockChangedLightSectionFilter.clear();
        return any;
    }

    /** Whether the chunk waits to go whole (GameTests). */
    public static boolean waiting(ServerLevel level, ChunkPos pos) {
        return NuclearWorld.get(level).wholeWaiting.containsKey(pos.toLong());
    }

    /** A further ruin write to the same live chunk joins its pending whole delivery, regardless of size. */
    static boolean waiting(ServerLevel level, LevelChunk chunk) {
        PendingWhole pending = NuclearWorld.get(level).wholeWaiting.get(chunk.getPos().toLong());
        return pending != null && pending.chunk == chunk;
    }

    /** Summary line part; resets the counters. */
    static String describe() {
        String s = String.format(java.util.Locale.ROOT,
                "whole chunks %d (%d positions, sent %.1f ticks after the write on average, at most %d; unloaded first %d; "
                        + "light broadcasts held for them %d, holds expired %d), section deltas %d positions in %d chunks",
                wholeChunks, wholePositions, wholeChunks == 0 ? 0.0 : (double) lagTicksSum / wholeChunks, lagTicksMax, gone, held, expired,
                deltaPositions, deltaChunks);
        wholeChunks = wholePositions = deltaChunks = deltaPositions = lagTicksSum = lagTicksMax = gone = held = expired = 0;
        return s;
    }
}
