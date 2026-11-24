package ua.zentix.incoming.claim;

import it.unimi.dsi.fastutil.longs.LongConsumer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.api.ClaimQuery;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * Claim protection for Incoming's world writes outside a real charge's own explosion list: candidate
 * {@code ExplosionEvent.Detonate} batches on a {@link ClaimQuery} (claim mods filter {@code Detonate}: Open Parties and
 * Claims and FTB Chunks by claimed chunk, others by position). Server thread only. A position in a chunk not in memory is
 * refused without asking: a handler reading its position must never load a chunk on the tick.
 */
public final class Claims {
    /**
     * Positions per event. Open Parties and Claims removes protected positions through the list iterator, each removal
     * shifting the rest, so one fully protected batch of n positions costs n²/2 moves: 1024 keeps it under a millisecond.
     */
    public static final int BATCH = 1024;

    /** Counters for logs and tests (server thread): events posted, positions offered and refused, time in handlers. */
    static long events, offered, refused, nanos;

    private Claims() {}

    /**
     * Offer {@code at[from, to)} ({@link BlockPos#asLong}) on {@code query}; each refused position goes to {@code onRefused}.
     *
     * @param stopAtRefusal stop after the first batch with a refusal (a caller that withholds everything once one
     *                      position is refused needs no more answers)
     * @return refused count (positions not offered after an early stop are not counted)
     */
    public static int offer(ServerLevel level, ClaimQuery query, long[] at, int from, int to, @Nullable LongConsumer onRefused, boolean stopAtRefusal) {
        long t = System.nanoTime();
        int count = 0;
        List<BlockPos> list = query.getToBlow();
        long[] asked = new long[Math.min(BATCH, Math.max(0, to - from))];
        // positions come chunk by chunk: one readiness lookup per run of the same chunk
        long lastChunk = Long.MIN_VALUE;
        boolean lastReady = false;
        try {
            for (int i = from; i < to; i += BATCH) {
                int end = Math.min(to, i + BATCH);
                list.clear();
                int n = 0;
                for (int k = i; k < end; k++) {
                    long p = at[k];
                    int cx = BlockPos.getX(p) >> 4, cz = BlockPos.getZ(p) >> 4;
                    long chunk = ChunkPos.asLong(cx, cz);
                    if (chunk != lastChunk) {
                        lastChunk = chunk;
                        lastReady = Terrain.ready(level, cx, cz);
                    }
                    if (!lastReady) {
                        count++;
                        refused++;
                        if (onRefused != null) onRefused.accept(p);
                        continue;
                    }
                    list.add(BlockPos.of(p));
                    asked[n++] = p;
                }
                if (n > 0) {
                    events++;
                    offered += n;
                    EventHooks.onExplosionDetonate(level, query, new ArrayList<Entity>(), 0);
                    if (!unchanged(list, asked, n)) {
                        LongOpenHashSet kept = new LongOpenHashSet(list.size());
                        for (BlockPos p : list) kept.add(p.asLong());
                        for (int k = 0; k < n; k++) {
                            if (kept.contains(asked[k])) continue;
                            count++;
                            refused++;
                            if (onRefused != null) onRefused.accept(asked[k]);
                        }
                    }
                }
                if (stopAtRefusal && count > 0) return count;
            }
            return count;
        } finally {
            list.clear();
            nanos += System.nanoTime() - t;
        }
    }

    /** One position: whether handlers leave it ({@link #offer}). */
    public static boolean allows(ServerLevel level, ClaimQuery query, BlockPos pos) {
        return offer(level, query, new long[] {pos.asLong()}, 0, 1, null, true) == 0;
    }

    /** Handlers left every asked position, in order (a handler that only removes keeps the order). */
    private static boolean unchanged(List<BlockPos> list, long[] asked, int n) {
        if (list.size() != n) return false;
        for (int k = 0; k < n; k++) if (list.get(k).asLong() != asked[k]) return false;
        return true;
    }

    /** Log fragment: events, positions offered and refused, handler time since the last call; resets the counters. */
    public static String drain() {
        String s = String.format(java.util.Locale.ROOT, "claim queries %d (%d positions, %d refused, %.1f ms)", events, offered, refused, nanos / 1e6);
        events = offered = refused = nanos = 0;
        return s;
    }

    /** Counters without resetting (tests): events, offered, refused. */
    public static long[] counters() {
        return new long[] {events, offered, refused, nanos};
    }
}
