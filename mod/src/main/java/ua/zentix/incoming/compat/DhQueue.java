package ua.zentix.incoming.compat;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * Distant Horizons 3.3.3 update queue as seen by the mod (Minecraft-free for unit tests): DH's own delay from
 * API call to database LOD write ({@link #LEAD}) and held-copy window in its bounded queue ({@link #window};
 * an instance tracks sent, unacknowledged copies, {@link DhUpdates#sendHeld}).
 * <p>
 * Delay comes from DH code: updates wait at least {@code ChunkUpdateQueueManager.UPDATE_DEBOUNCE_NANOS}
 * ({@link #DEBOUNCE_MS}); a built chunk LOD is written to a 64×64-block section (4×4 chunks), and the section to the
 * database no earlier than {@code saveDelayInMs} after its last write ({@code AbstractDhLevel}: {@link #SAVE_DELAY_MS},
 * each write postpones it again). The save thread ({@code AbstractDelayedSaveCache.runCleanupLoop}) wakes once a second,
 * then waits another {@link #SAVE_PAUSE_MS} before writing; DH then distributes the section (to players and its renderer)
 * and sends {@code DhApiChunkModifiedEvent} for each written chunk. Coarse LOD levels catch up afterward — up to 250 ms
 * per level ({@code FullDataUpdatePropagatorV2}).
 * <p>
 * DH queue is bounded ({@code ChunkUpdateQueueManager.maxSize}): {@link #PER_THREAD} positions per processing thread
 * ({@code numberOfThreads}), per player in single-player, per 1 + players on a server; overflow silently
 * drops the farthest from the player (from 0 0 on dedicated servers) and logs “overloaded” once per 30 s. It retains
 * chunk copies until processing, one at a time on the queue thread (heightmaps and chunk hash, then DH-thread building).
 */
final class DhQueue {
    /** {@code ChunkUpdateQueueManager.UPDATE_DEBOUNCE_NANOS}: minimum update queue wait, ms. */
    static final int DEBOUNCE_MS = 250;
    /** {@code AbstractDhLevel}: minimum delay from last section write to database save, ms. */
    static final int SAVE_DELAY_MS = 1000;
    /** {@code AbstractDelayedSaveCache.cleanUp}: save-thread pause before writing collected sections, ms. */
    static final int SAVE_PAUSE_MS = 100;
    /**
     * Minimum DH-internal delay, ticks (5 + 20 + 2 ticks of 50 ms, excluding processing and save-thread wait):
     * a copy sent this far ahead of front arrival changes the LOD no earlier than the front; further delay comes from
     * processing, up to a second of save-thread wait, and coarse levels.
     */
    static final int LEAD = (DEBOUNCE_MS + SAVE_DELAY_MS + SAVE_PAUSE_MS + 49) / 50;
    /** {@code ChunkUpdateQueueManager.MAX_UPDATING_CHUNK_COUNT_PER_THREAD_AND_PLAYER}. */
    static final int PER_THREAD = 1000;
    /**
     * Acknowledgment timeout, ns: twice DH's maximum delay without queuing (250 ms, up to 2 s waiting for
     * save and 100 ms). Without acknowledgment (read-only DH — Flashback replay; DH unchanged-chunk check
     * disabled — no event; unchanged chunk — DH does not write it), the copy leaves the window on timeout.
     */
    static final long TIMEOUT_NANOS = 5_000_000_000L;
    /**
     * {@code ChunkUpdateQueueManager.queuedChunkWrapperByChunkPos}: DH's cache retains a submitted chunk copy this long
     * after last access ({@code expireAfterAccess(10, SECONDS)}), ns.
     */
    static final long CACHE_NANOS = 10_000_000_000L;

    /** Sent, unacknowledged held copies: chunk → send time ({@code System.nanoTime}). */
    private final Long2LongOpenHashMap out = new Long2LongOpenHashMap();
    /** Log line ({@link #take}): sent, acknowledged, expired; sum and maximum acknowledgment delay, ns. */
    private long sent, acked, expired, latencySum, latencyMax;

    /**
     * Maximum held copies waiting in the DH queue together: half its smallest limit (single-player, one
     * player), leaving the other half for DH's own updates and mod marks ({@link DhUpdates#mark}).
     *
     * @param threads DH processing threads ({@code numberOfThreads}; unknown: 1)
     */
    static int window(int threads) {
        return PER_THREAD * Math.max(1, threads) / 2;
    }

    /** Sent and unacknowledged. */
    int waiting() {
        return out.size();
    }

    void sent(long chunk, long nanos) {
        out.put(chunk, nanos);
        sent++;
    }

    /** DH wrote the chunk to its database: release window slot; false if not awaited (DH's own update, previous world). */
    boolean ack(long chunk, long nanos) {
        if (!out.containsKey(chunk)) return false;
        long took = nanos - out.remove(chunk);
        acked++;
        latencySum += took;
        latencyMax = Math.max(latencyMax, took);
        return true;
    }

    /** Remove copies awaiting acknowledgment longer than {@link #TIMEOUT_NANOS} from the window. */
    void expire(long nanos) {
        if (out.isEmpty()) return;
        for (var it = out.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
            Long2LongMap.Entry e = it.next();
            if (nanos - e.getLongValue() >= TIMEOUT_NANOS) {
                it.remove();
                expired++;
            }
        }
    }

    /** Tests: empty the window (retain counters). */
    void clear() {
        out.clear();
    }

    /**
     * Sent, acknowledged, expired, mean and maximum acknowledgment delay (ms) since last call, and currently awaiting
     * acknowledgment; reset the counters.
     */
    long[] take() {
        long[] s = {sent, acked, expired, acked == 0 ? 0 : latencySum / acked / 1_000_000, latencyMax / 1_000_000, out.size()};
        sent = acked = expired = latencySum = latencyMax = 0;
        return s;
    }
}
