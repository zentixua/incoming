package ua.zentix.incoming.claim;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.api.ClaimQuery;

import java.util.List;
import java.util.function.Function;

/**
 * One conventional strike's claim decisions, held by its blast area while its jobs run (impact work is not saved, so
 * neither are they). The real charge decides its own blocks through its own {@code ExplosionEvent.Start} and
 * {@code Detonate}; a chunk where that {@code Detonate} removed any position is denied to every later writer of the
 * strike ({@link #deny}), so a crater, glass, fire, fragments, collapse landing or debris never reopen it. Elsewhere each
 * writer asks at its own positions ({@link #allows}, {@link #filter}) on a {@link ClaimQuery} attributed as the strike's
 * explosion.
 */
public final class StrikeClaims implements BlockGate {
    private final Function<ServerLevel, ClaimQuery> query;
    @Nullable
    private ClaimQuery made;
    private final LongOpenHashSet denied = new LongOpenHashSet();

    /** @param query the strike's query, built when first needed (its explosion's source is known by then) */
    public StrikeClaims(Function<ServerLevel, ClaimQuery> query) {
        this.query = query;
    }

    /** The real {@code Detonate} of this strike removed a position in {@code chunk} ({@link ChunkPos#toLong}). */
    public void deny(long chunk) {
        denied.add(chunk);
    }

    /** Chunk denied by this strike's real explosion. */
    public boolean denied(long chunk) {
        return denied.contains(chunk);
    }

    /** Whether the strike may change {@code pos}. */
    @Override
    public boolean allows(ServerLevel level, BlockPos pos) {
        if (denied.contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4))) return false;
        return Claims.allows(level, query(level), pos);
    }

    /** Remove from {@code positions} what the strike may not change; one event per {@link Claims#BATCH} positions. */
    @Override
    public void filter(ServerLevel level, List<BlockPos> positions) {
        positions.removeIf(p -> denied.contains(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4)));
        if (positions.isEmpty()) return;
        long[] at = new long[positions.size()];
        for (int i = 0; i < at.length; i++) at[i] = positions.get(i).asLong();
        LongOpenHashSet refused = new LongOpenHashSet();
        if (Claims.offer(level, query(level), at, 0, at.length, refused::add, false) > 0) positions.removeIf(p -> refused.contains(p.asLong()));
    }

    private ClaimQuery query(ServerLevel level) {
        if (made == null) made = query.apply(level);
        return made;
    }
}
