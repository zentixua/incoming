package ua.zentix.incoming.work;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;

/**
 * How our in-place block changes (nuclear ruins, blackout lamps) reach the players already holding the chunk.
 * <p>
 * Few changed positions go as vanilla section deltas ({@code ChunkHolder.blockChanged}, like {@code setBlock}). A chunk
 * changed in many positions goes as one whole-chunk packet, the way a chunk normally reaches a client: the client copies
 * its sections at once and takes the light from the packet. Section deltas make the client set every block one by one
 * on its render thread and propagate light for each itself (the server's light is already computed): about 16 µs a
 * block in the host pack (9 setting it, 55–63 % of that Voxy re-snapshotting the section per block; 7 client light), so
 * the 247 thousand positions a 15 kt wave sends within 10 s around a viewer at 1.5 km froze the client 2.2 s and then
 * 1.8 s (wave-hitch run 19.11.2026). A lamp switching off costs the client more: its block-light removal spreads over
 * the lamp's whole light radius, and the 91.6 thousand lamps a 1 Mt blackout darkens around a viewer at 3.9 km during
 * the wave's passage put 42 % of the client's render thread into light updates (passage-stutter run 24.11.2026). A whole
 * chunk costs the client about 0.55 ms (decode, light from the packet, Voxy and Sodium once per chunk; same run, world
 * load).
 * <p>
 * A whole chunk waits for light because a packet built on the write tick could show new blocks in old light.
 * Consecutive writes share one pending send, fenced by the light checks of the latest write. The fence completes
 * on the light thread and the send runs on the chunk cache's main-thread queue, after the section light marks queued
 * there. An earlier fence could send newer blocks in older light. While a delivery waits, vanilla's light broadcast
 * for that chunk is held for up to {@link #HOLD_LIMIT} ticks per hold ({@link #onLevelTickPre}). Light from the
 * chunk's new blocks or from a changed neighbour could otherwise reach the client's old blocks.
 * <p>
 * Chunks whose light is done go out at the next world tick in the order they became ready, at most {@link #PER_TICK}
 * a tick to each viewer: a wave crossing a city in view readies hundreds of chunks within seconds, up to a hundred in
 * one tick, and the client handles every packet that arrived before its next frame. A ready chunk waits for budget only
 * until {@link #READY_LIMIT} ticks after its first write; it stays in its pending delivery meanwhile, so its light
 * broadcast stays held and a later write still joins it. Chunks at the limit go together, beyond the budget: readiness
 * faster than {@link #PER_TICK} a tick for over a second sends each tick's backlog unpaced, a second late.
 */
public final class ChunkDelivery {
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

    /**
     * Client render-thread time per server tick vanilla gives chunk packets: the client asks for as many chunks a tick
     * as it handles in 7 ms ({@code ChunkBatchSizeCalculator.getDesiredChunksPerTick}).
     */
    static final long CLIENT_TICK_NANOS = 7_000_000;
    /** Whole chunks sent to one viewer per server tick: {@link #CLIENT_TICK_NANOS} at {@link #CHUNK_NANOS} a chunk. */
    public static final int PER_TICK = (int) (CLIENT_TICK_NANOS / CHUNK_NANOS);
    /**
     * Ticks after its first write from which a ready chunk no longer waits for its viewers' {@link #PER_TICK}: 1 s, the
     * ruins' acceptance (in view p99 1 s, at most 2 s behind their wave; nuclear-world.md). Pacing adds no delay past
     * it; the write's own lag behind the front and a slower light fence come on top, as before pacing.
     */
    public static final int READY_LIMIT = 20;
    /** Whole chunks per viewer and tick in force ({@link #PER_TICK}; a GameTest sets 0 to reach the wait limit). */
    private static int perTick = PER_TICK;

    /** Chunks waiting to go to their viewers whole (world attachment, never saved). */
    private final Long2ObjectOpenHashMap<PendingWhole> wholeWaiting = new Long2ObjectOpenHashMap<>();
    /** Fenced revisions ready to send, in the order their light completed (server thread). */
    private final ArrayDeque<Ready> ready = new ArrayDeque<>();

    /** A fenced revision of a pending delivery. */
    private record Ready(long key, long revision) {}
    /** Unique write revisions for whole deliveries, including across chunk reloads. */
    private long wholeRevision;

    /** Statistics for the ruin summary line (server thread). */
    static long wholeChunks, wholePositions, deltaChunks, deltaPositions, lagTicksSum, lagTicksMax, gone, held, expired;
    /** Ticks ready chunks waited for their viewers' budget (sum, max) and those sent beyond it at {@link #READY_LIMIT}. */
    static long paceSum, paceMax, paceForced;
    /** Blackout lamp positions among them (whole, deltas). */
    static long lampWhole, lampDeltas;

    /** One pending whole-chunk delivery, owned by the server thread. */
    static final class PendingWhole {
        private final LevelChunk chunk;
        private final long firstWrite;
        private long holdSince;
        private long revision;
        private long positions;
        /** Game time its first fence completed (-1: none yet): the wait for the viewers' budget counts from it. */
        private long readySince = -1;
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

    /** World attachment ({@link ModAttachments#CHUNK_DELIVERY}). */
    public ChunkDelivery() {}

    private static ChunkDelivery get(ServerLevel level) {
        return level.getData(ModAttachments.CHUNK_DELIVERY);
    }

    /** Whether a chunk with {@code changed} positions goes to its viewers whole. */
    public static boolean whole(int changed) {
        return changed >= wholeFrom;
    }

    /** GameTest only: set the whole chunks per viewer and tick, return the previous value. */
    public static int perTick(int n) {
        int was = perTick;
        perTick = n;
        return was;
    }

    /** GameTest only: set the threshold, return the previous one. */
    public static int wholeFrom(int threshold) {
        int was = wholeFrom;
        wholeFrom = threshold;
        return was;
    }

    /** Viewers holding the chunk, as vanilla's own broadcast finds them ({@code ChunkHolder.broadcastChanges}). */
    public static List<ServerPlayer> viewers(ChunkHolder holder) {
        return holder.playerProvider.getPlayers(holder.getPos(), false);
    }

    /** Count a chunk sent as {@code positions} section-delta positions. */
    public static void deltas(int positions) {
        deltaChunks++;
        deltaPositions += positions;
    }

    /**
     * Fence this revision of a pending whole-chunk delivery (server thread; the chunk's sections and light checks
     * are already written and queued). Only the latest revision can serialize the live chunk.
     */
    public static void wholeAfterLight(ServerLevel level, LevelChunk chunk, int positions) {
        ChunkPos pos = chunk.getPos();
        long key = pos.toLong();
        ChunkDelivery world = get(level);
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
                .thenRunAsync(() -> fenced(level, pos, revision), main::tell);
    }

    /** The fence of {@code revision} completed (server thread): the latest revision joins the send queue. */
    private static void fenced(ServerLevel level, ChunkPos pos, long revision) {
        ChunkDelivery world = get(level);
        PendingWhole pending = world.wholeWaiting.get(pos.toLong());
        if (pending == null || pending.revision != revision) return;
        if (pending.readySince < 0) pending.readySince = level.getGameTime();
        world.ready.add(new Ready(pos.toLong(), revision));
    }

    /**
     * Send ready chunks in order while their viewers have budget this tick; a chunk {@link #READY_LIMIT} ticks after its
     * first write goes regardless. A chunk goes to all its viewers at once, charging each.
     */
    private void sendReady(ServerLevel level) {
        if (ready.isEmpty()) return;
        long now = level.getGameTime();
        Object2IntOpenHashMap<ServerPlayer> spent = new Object2IntOpenHashMap<>();
        int exhausted = 0, players = level.players().size();
        for (Iterator<Ready> it = ready.iterator(); it.hasNext(); ) {
            Ready r = it.next();
            PendingWhole pending = wholeWaiting.get(r.key());
            if (pending == null || pending.revision != r.revision()) {
                // a later write superseded this revision; its own fence queues it again
                it.remove();
                continue;
            }
            long pace = now - pending.readySince;
            boolean forced = now - pending.firstWrite >= READY_LIMIT;
            if (!forced && players > 0 && exhausted >= players) continue;
            ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(r.key());
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(r.key()), ChunkPos.getZ(r.key()));
            if (chunk != pending.chunk || holder == null) {
                // unloaded meanwhile: a player who loads it again gets it as it is now
                it.remove();
                wholeWaiting.remove(r.key());
                gone++;
                continue;
            }
            List<ServerPlayer> viewers = viewers(holder);
            boolean within = budget(viewers, spent);
            if (!forced && !within) continue;
            it.remove();
            wholeWaiting.remove(r.key());
            if (viewers.isEmpty()) continue;
            var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
            for (ServerPlayer p : viewers) {
                p.connection.send(packet);
                if (spent.addTo(p, 1) + 1 == perTick) exhausted++;
            }
            long lag = now - pending.firstWrite;
            wholeChunks++;
            wholePositions += pending.positions;
            lagTicksSum += lag;
            lagTicksMax = Math.max(lagTicksMax, lag);
            paceSum += pace;
            paceMax = Math.max(paceMax, pace);
            if (!within) paceForced++;
        }
    }

    /** Whether every viewer still has budget this tick. */
    private static boolean budget(List<ServerPlayer> players, Object2IntOpenHashMap<ServerPlayer> spent) {
        for (ServerPlayer p : players) if (spent.getInt(p) >= perTick) return false;
        return true;
    }

    /**
     * Before the world ticks (its chunk cache broadcasts light changes at the end of its tick): send ready chunks, then
     * drop the pending light broadcast of chunks still waiting to go whole; the whole chunk carries their light.
     */
    public static void onLevelTickPre(LevelTickEvent.Pre e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        ChunkDelivery w = get(level);
        w.sendReady(level);
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
        return get(level).wholeWaiting.containsKey(pos.toLong());
    }

    /** A further write to the same live chunk joins its pending whole delivery, regardless of size. */
    public static boolean waiting(ServerLevel level, LevelChunk chunk) {
        PendingWhole pending = get(level).wholeWaiting.get(chunk.getPos().toLong());
        return pending != null && pending.chunk == chunk;
    }

    /**
     * Deliver blackout lamps switched in place in a live chunk (server thread; the sections are written and their light
     * checks queued): as section deltas, or whole after its light when they are many, the chunk has more lamps to switch
     * in a later pass ({@code more}: a budgeted unit stops mid-chunk, and the threshold is about the chunk, not the
     * pass), or a whole delivery is already pending. The ruins decide the same way ({@code RuinPlan.write}).
     */
    public static void lamps(ServerLevel level, LevelChunk chunk, LongList positions, boolean more) {
        if (positions.isEmpty()) return;
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(chunk.getPos().toLong());
        boolean ticking = holder != null && holder.getTickingChunk() != null && !viewers(holder).isEmpty();
        if ((ticking && (more || whole(positions.size()))) || waiting(level, chunk)) {
            lampWhole += positions.size();
            wholeAfterLight(level, chunk, positions.size());
            return;
        }
        if (ticking) {
            deltas(positions.size());
            lampDeltas += positions.size();
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < positions.size(); i++) level.getChunkSource().blockChanged(m.set(positions.getLong(i)));
    }

    /** Summary line part; resets the counters. */
    public static String describe() {
        String s = String.format(java.util.Locale.ROOT,
                "whole chunks %d (%d positions, sent %.1f ticks after the write on average, at most %d; waited for the "
                        + "viewers' %d a tick %.1f ticks on average, at most %d, sent beyond it at %d ticks after the write %d; unloaded first %d; "
                        + "light broadcasts held for them %d, holds expired %d), section deltas %d positions in %d chunks; "
                        + "of the positions blackout lamps: %d whole, %d as deltas",
                wholeChunks, wholePositions, wholeChunks == 0 ? 0.0 : (double) lagTicksSum / wholeChunks, lagTicksMax, PER_TICK,
                wholeChunks == 0 ? 0.0 : (double) paceSum / wholeChunks, paceMax, READY_LIMIT, paceForced, gone, held, expired,
                deltaPositions, deltaChunks, lampWhole, lampDeltas);
        wholeChunks = wholePositions = deltaChunks = deltaPositions = lagTicksSum = lagTicksMax = gone = held = expired = 0;
        paceSum = paceMax = paceForced = 0;
        lampWhole = lampDeltas = 0;
        return s;
    }
}
