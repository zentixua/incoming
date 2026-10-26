package ua.zentix.incoming.nuclear.flash;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.registry.ModAttachments;

/**
 * Flash traces in chunk attachment ({@link ModAttachments#FLASH_MARKS}) and for players (server thread): scorch applied
 * with chunk ruins ({@code RuinPlan.apply}), silhouettes by {@link FlashShadows}; player receiving chunk
 * (login, approach, dimension change) gets traces immediately afterward ({@link ChunkWatchEvent.Sent}); players already
 * viewing chunk get every change. Changed block loses traces immediately ({@link #onNeighborNotify}: broken and replaced
 * identical plank arrives clean); players receive update at world-tick end, one packet per
 * chunk. Changes without neighbor notifications (mods moving blocks) visible only through block itself: before sending,
 * traces on changed blocks (ruins, player construction) removed, as block no longer matches ({@link FlashMarks#pruned}).
 */
public final class FlashSync {
    private FlashSync() {}

    /** Detonation {@code d} scorch in chunk with its ruins; old flash traces on changed blocks removed. */
    public static void chars(ServerLevel level, LevelChunk chunk, Detonation d, int[] chars) {
        FlashMarks old = chunk.getExistingDataOrNull(ModAttachments.FLASH_MARKS.get());
        if (old == null && chars.length == 0) return;
        FlashMarks now = old == null ? FlashMarks.EMPTY : old.pruned(chunk);
        if (chars.length > 0) now = now.withChars(d.id(), d.gameTime(), chars);
        put(level, chunk, old, now);
    }

    /** Detonation {@code d} silhouettes on chunk faces (added to previous). */
    public static void shadows(ServerLevel level, LevelChunk chunk, Detonation d, int[] faces, long[] masks) {
        if (faces.length == 0) return;
        FlashMarks old = chunk.getExistingDataOrNull(ModAttachments.FLASH_MARKS.get());
        FlashMarks base = old == null ? FlashMarks.EMPTY : old.pruned(chunk);
        put(level, chunk, old, base.withShadows(d.id(), d.gameTime(), faces, masks));
    }

    /** Current chunk traces (for tests). */
    public static FlashMarks of(LevelChunk chunk) {
        FlashMarks m = chunk.getExistingDataOrNull(ModAttachments.FLASH_MARKS.get());
        return m == null ? FlashMarks.EMPTY : m;
    }

    private static void put(ServerLevel level, LevelChunk chunk, @Nullable FlashMarks old, FlashMarks now) {
        if (now == old || now.isEmpty() && old == null) return;
        store(chunk, now);
        send(level, chunk, now);
        // deferred send for same chunk no longer needed
        if (level.hasData(ModAttachments.FLASH_PENDING)) level.getData(ModAttachments.FLASH_PENDING).chunks.remove(chunk.getPos().toLong());
    }

    private static void store(LevelChunk chunk, FlashMarks now) {
        if (now.isEmpty()) chunk.removeData(ModAttachments.FLASH_MARKS.get());
        else chunk.setData(ModAttachments.FLASH_MARKS.get(), now);
        chunk.setUnsaved(true);
    }

    /** Chunk traces to its viewers (empty means client forgets them). */
    private static void send(ServerLevel level, LevelChunk chunk, FlashMarks now) {
        S2C.NukeFlashMarks packet = new S2C.NukeFlashMarks(chunk.getPos().toLong(), now);
        for (ServerPlayer p : level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false)) PacketDistributor.sendToPlayer(p, packet);
    }

    /**
     * Block changed and neighbors notified (server thread): player breaking, explosion, fire, piston, command, block
     * placement. Traces removed from attachment immediately if block no longer matches; otherwise an identical plank broken
     * and immediately replaced would show scorch again (same block hash). Sent to players at world-tick end ({@link #onLevelTick}):
     * explosion changes dozens of chunk blocks per tick. Notification without block change (wall lever, wire) leaves traces alone.
     */
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return;
        FlashMarks old = chunk.getExistingDataOrNull(ModAttachments.FLASH_MARKS.get());
        if (old == null) return;
        FlashMarks now = old.prunedAt(pos.getX() & 15, pos.getY(), pos.getZ() & 15, e.getState());
        if (now == old) return;
        store(chunk, now);
        level.getData(ModAttachments.FLASH_PENDING).chunks.add(chunk.getPos().toLong());
    }

    /** World-tick end: updated traces for changed chunks to their viewers. */
    public static void onLevelTick(LevelTickEvent.Post e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !level.hasData(ModAttachments.FLASH_PENDING)) return;
        LongOpenHashSet chunks = level.getData(ModAttachments.FLASH_PENDING).chunks;
        if (chunks.isEmpty()) return;
        for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk != null) send(level, chunk, of(chunk));
        }
        chunks.clear();
    }

    /** World chunks whose traces changed this tick but have not reached players (non-persistent world attachment). */
    public static final class Pending {
        final LongOpenHashSet chunks = new LongOpenHashSet();
    }

    /** Chunk sent to player: traces follow. */
    public static void onChunkSent(ChunkWatchEvent.Sent e) {
        S2C.NukeFlashMarks packet = packet(e.getChunk());
        if (packet != null) PacketDistributor.sendToPlayer(e.getPlayer(), packet);
    }

    /**
     * Chunk trace packet for player receiving chunk (server thread); first remove traces on changed blocks
     * from attachment too. Null if no traces.
     */
    @Nullable
    public static S2C.NukeFlashMarks packet(LevelChunk chunk) {
        FlashMarks old = chunk.getExistingDataOrNull(ModAttachments.FLASH_MARKS.get());
        if (old == null) return null;
        FlashMarks now = old.pruned(chunk);
        if (now != old) store(chunk, now);
        return now.isEmpty() ? null : new S2C.NukeFlashMarks(chunk.getPos().toLong(), now);
    }
}
