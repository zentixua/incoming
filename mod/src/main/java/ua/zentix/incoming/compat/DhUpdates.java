package ua.zentix.incoming.compat;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.nuclear.world.FarLods;
import ua.zentix.incoming.util.Terrain;
import java.util.concurrent.CompletableFuture;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Mod world changes delivered to DH's terrain database and Voxy players through their shared queue.
 * Loaded marks coalesce and resend no sooner than {@link #RESEND}; built distant copies use {@link #offer}, and
 * front-held copies use {@link #sendHeld}. When a marked chunk unloads while delivery is paused, read its saved
 * sections asynchronously instead of dropping the fresh update. Never load a world chunk for LOD delivery.
 */
public final class DhUpdates {
    /** DH calls per server tick (400 chunks per second). */
    static final int PER_TICK = 20;
    /** Not resent sooner than this after the last send: DH still queues it (250 ms, and a packet of ~1 s). */
    static final int RESEND = 40;
    /** Ticks after a change to wait for the next batches of the same explosion. */
    public static final int SETTLE = 10;
    /** Collected chunks not in the world waiting to be sent, at most: beyond them is memory (whole sections). */
    static final int OFFER_LIMIT = 512;
    /**
     * At most this many marks are scanned per tick: marks not yet due go to the end (round-robin) — the queue (nuke
     * ruins —
     * thousands of chunks) is not walked whole each tick, and a mark that is due waits no more than a few ticks.
     */
    static final int SCAN = 256;
    /**
     * DH's own delay from the call to the LOD write in its database, in ticks — the smallest ({@link DhQueue#LEAD}): a
     * held copy
     * ({@link #sendHeld}) goes out this long before the front arrives — the LOD changes no earlier than it.
     */
    public static final int LEAD = DhQueue.LEAD;
    /**
     * How long DH holds a handed-over chunk copy not in the world, ns, at least: until it is written to the database
     * (without
     * confirmation — until the {@link DhQueue#TIMEOUT_NANOS} deadline) and a further {@link DhQueue#CACHE_NANOS} in its
     * own cache:
     * the memory of a copy handed to the front — this long after handing over.
     */
    public static final long HOLD_NANOS = DhQueue.TIMEOUT_NANOS + DhQueue.CACHE_NANOS;

    /** Chunk → tick not before which to send it; in order of marks. */
    private final Long2LongLinkedOpenHashMap due = new Long2LongLinkedOpenHashMap();
    /** Chunk → a later mark than the nearest in {@link #due}: after sending, the chunk waits for it again. */
    private final Long2LongOpenHashMap later = new Long2LongOpenHashMap();
    /** Chunk → tick of the last send (older than {@link #RESEND} — forgotten). */
    private final Long2LongOpenHashMap sent = new Long2LongOpenHashMap();
    /** At most four asynchronous reads; marks remain pending until a fresh copy is delivered. */
    static final class SavedRead {
        final CompletableFuture<ChunkAccess> future;
        boolean obsolete;

        SavedRead(CompletableFuture<ChunkAccess> future) { this.future = future; }
    }

    private final Long2ObjectOpenHashMap<SavedRead> unloaded = new Long2ObjectOpenHashMap<>();
    private final ArrayDeque<ChunkAccess> offered = new ArrayDeque<>();
    /** Chunks in {@link #offered} (how many copies of each are waiting to be sent). */
    private final Long2IntOpenHashMap offeredCount = new Long2IntOpenHashMap();
    /** Held copies, handed over and not confirmed ({@link #sendHeld}). */
    private final DhQueue held = new DhQueue();
    /** Confirmations from DH's stream ({@link #saved}): chunk and when. */
    private final ConcurrentLinkedQueue<long[]> acks = new ConcurrentLinkedQueue<>();
    private long sentTotal, offeredTotal;
    /** For checks: the most marks scanned in a single tick. */
    private int scannedMax;
    /**
     * Where chunks go: in the game — {@link DhChunks} and {@link VoxyLods}; checks substitute their own sink ({@link
     * #testSink}).
     */
    private Sink sink = DhUpdates::deliver;
    private boolean test;
    private boolean listening;
    /** Non-owning callback fast path; queues and world references remain in level attachments. */
    private static volatile int waitingLevels;

    private void setListening(boolean value) {
        if (listening == value) return;
        listening = value;
        waitingLevels += value ? 1 : -1;
    }

    /** Server lifecycle boundary: the previous server's level attachments no longer receive acknowledgements. */
    static void resetWaiting() {
        waitingLevels = 0;
    }

    /** Chunk sink. */
    public interface Sink {
        void overwrite(ServerLevel level, ChunkAccess chunk);
    }

    /** For {@link ModAttachments#DH_UPDATES}: each world has its own, not saved. */
    public DhUpdates() {}

    /**
     * The world's queue, if there is somewhere to hand chunks (DH is installed, a player with Voxy is in the world, or
     * a check substituted a sink), else null.
     */
    private static DhUpdates active(ServerLevel level) {
        DhUpdates u = level.getData(ModAttachments.DH_UPDATES);
        return u.test || DhChunks.available() || VoxyLods.wanted(level) ? u : null;
    }

    /** Chunk — to DH (if installed) and to players with Voxy (if any). Server thread. */
    private static void deliver(ServerLevel level, ChunkAccess chunk) {
        DhChunks.overwrite(level, chunk);
        VoxyLods.send(level, chunk);
    }

    /** The pace is held by the DH queue (write confirmations): DH is installed or a check substituted a sink. */
    private boolean paced() {
        return test || DhChunks.available();
    }

    /** Checks: chunks of world {@code level} go to {@code sink} (and not to DH); null — back to DH, queue empty. */
    public static void testSink(ServerLevel level, @Nullable Sink sink) {
        DhUpdates u = level.getData(ModAttachments.DH_UPDATES);
        u.sink = sink != null ? sink : DhUpdates::deliver;
        u.test = sink != null;
        if (sink == null) {
            u.due.clear();
            u.unloaded.clear();
            u.later.clear();
            u.offered.clear();
            u.offeredCount.clear();
            u.held.clear();
            u.setListening(false);
            u.acks.clear();
        }
    }

    /** The mod changed blocks of a loaded chunk: to DH not before tick {@code at}. Server thread. */
    public static void mark(ServerLevel level, ChunkPos pos, long at) {
        DhUpdates u = active(level);
        if (u == null) return;
        SavedRead read = u.unloaded.get(pos.toLong());
        if (read != null) read.obsolete = true; // A later change must not reuse the previous save's result.
        Long2LongLinkedOpenHashMap due = u.due;
        long key = pos.toLong();
        // a repeated mark does not postpone the early one, it queues behind it: after sending, the chunk waits for that
        // one too
        if (!due.containsKey(key)) {
            due.put(key, at);
            return;
        }
        long was = due.get(key);
        if (at < was) due.put(key, at);
        long last = Math.max(was, at);
        if (last > due.get(key) && last > u.later.get(key)) u.later.put(key, last);
    }

    /** {@link #mark} of all chunks of the square around {@code centre} with radius {@code reach} blocks. */
    public static void markArea(ServerLevel level, Vec3 centre, double reach, long at) {
        if (active(level) == null) return;
        int x0 = Mth.floor(centre.x - reach) >> 4, x1 = Mth.floor(centre.x + reach) >> 4;
        int z0 = Mth.floor(centre.z - reach) >> 4, z1 = Mth.floor(centre.z + reach) >> 4;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) mark(level, new ChunkPos(x, z), at);
    }

    /** Mark of a loaded chunk still waiting to be sent to DH ({@link #mark}). Server thread. */
    public static boolean pending(ServerLevel level, long chunk) {
        DhUpdates u = active(level);
        return u != null && u.due.containsKey(chunk);
    }

    /** Somewhere to hand chunks: DH is installed (or a check substituted a sink). */
    public static boolean enabled(ServerLevel level) {
        return active(level) != null;
    }

    /**
     * Whether there is somewhere to hand chunks, and how many more collected chunks not in the world can be handed over
     * ({@link #offer}) right now: no more than the room left in the queues of players with Voxy ({@link
     * VoxyLods#room}).
     */
    public static int room(ServerLevel level) {
        DhUpdates u = active(level);
        return u == null ? 0 : Math.max(0, Math.min(OFFER_LIMIT - u.offered.size(), VoxyLods.room(level) - u.offered.size()));
    }

    /**
     * A chunk not from the world (its copy with changes, {@code ProtoChunk}) — into the LOD as is: do not change it
     * after this.
     * Server thread.
     */
    public static void offer(ServerLevel level, ChunkAccess chunk) {
        DhUpdates u = active(level);
        if (u == null) return;
        u.offered.add(chunk);
        u.offeredCount.addTo(chunk.getPos().toLong(), 1);
        u.offeredTotal++;
    }

    /**
     * How many more held copies can be handed over now ({@link #sendHeld}): the window is half the smallest DH queue
     * per world
     * by its number of threads ({@link DhQueue#window}), minus those handed over and not confirmed; without DH — 0.
     */
    public static int heldRoom(ServerLevel level) {
        DhUpdates u = active(level);
        if (u == null) return 0;
        int dh = u.paced() ? DhQueue.window(DhChunks.threads()) - u.held.waiting() : Integer.MAX_VALUE;
        return Math.min(dh, VoxyLods.room(level));
    }

    /**
     * A held copy of a chunk not in the world (assembled in advance, {@code ProtoChunk}) — to DH now, past {@link
     * #PER_TICK}: its
     * deadline is the arrival of the front, and the pace is held by the window ({@link #heldRoom}); do not change the
     * copy after this.
     *
     * @return false — not now: the same chunk was handed to DH less than {@link #RESEND} ticks ago or is waiting to be
     * sent
     * ({@link #offer}) — DH would skip the copy or overlap it with that one
     */
    public static boolean sendHeld(ServerLevel level, ChunkAccess copy) {
        DhUpdates u = active(level);
        if (u == null) return true;
        long key = copy.getPos().toLong(), now = level.getGameTime();
        if (u.offeredCount.containsKey(key) || u.sent.containsKey(key) && now - u.sent.get(key) < RESEND) return false;
        // write confirmations — only from DH: without it the pace holds room in the Voxy queues (heldRoom)
        boolean paced = u.paced();
        if (paced && u.held.waiting() == 0) {
            u.setListening(true);
            DhChunks.listen();
        }
        if (u.test) {
            u.sink.overwrite(level, copy);
        } else {
            DhChunks.overwrite(level, copy);
            // DH sets the LOD LEAD ticks after the call, Voxy — at once: the copy for players with Voxy — right up to
            // the front
            VoxyLods.send(level, copy, now + LEAD);
        }
        if (paced) u.held.sent(key, System.nanoTime());
        u.sent.put(key, now);
        return true;
    }

    /**
     * DH wrote the chunk's LOD to its database ({@code DhApiChunkModifiedEvent}; DH's thread, checks — any): the
     * confirmation of a
     * held copy ({@link #sendHeld}). The world — the one DH wraps ({@code getWrappedMcObject}).
     */
    public static void saved(Object level, int x, int z) {
        if (waitingLevels == 0 || !(level instanceof ServerLevel world)) return;
        var server = world.getServer();
        if (!server.isRunning()) return;
        long at = System.nanoTime();
        // Attachment access belongs to the server thread; DH callbacks can arrive after world shutdown.
        server.execute(() -> {
            if (!server.isRunning() || server.getLevel(world.dimension()) != world || !world.hasData(ModAttachments.DH_UPDATES)) return;
            DhUpdates u = world.getData(ModAttachments.DH_UPDATES);
            if (u.listening) u.acks.add(new long[] {ChunkPos.asLong(x, z), at});
        });
    }

    /**
     * Held copies of the world since the last call (counting starts over): handed over, confirmed, unconfirmed past the
     * deadline,
     * the delay of confirmed ones — average and maximum (ms), waiting for confirmation now.
     */
    public static long[] takeHeldStats(ServerLevel level) {
        return level.getData(ModAttachments.DH_UPDATES).held.take();
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.hasData(ModAttachments.DH_UPDATES)) level.getData(ModAttachments.DH_UPDATES).tick(level);
    }

    private void tick(ServerLevel level) {
        long now = level.getGameTime();
        unloaded.long2ObjectEntrySet().removeIf(e -> e.getValue().future.isDone() && !due.containsKey(e.getLongKey()));
        if (held.waiting() > 0) {
            for (long[] a; (a = acks.poll()) != null; ) held.ack(a[0], a[1]);
            held.expire(System.nanoTime());
            if (held.waiting() == 0) setListening(false);
        }
        if (due.isEmpty() && offered.isEmpty()) return;
        // players with Voxy (and with DH): the pace — and the room in their queues, or they would not get the extra
        int budget = Math.min(PER_TICK, VoxyLods.room(level));
        // collected chunks first: marks of chunks in the world will wait a tick, while collected ones hold memory; a
        // chunk handed to DH
        // less than RESEND ticks ago (as a held copy or as a previous collected one) — to the end: DH would skip it
        // silently
        for (int i = 0, n = offered.size(); budget > 0 && i < n; i++) {
            ChunkAccess c = offered.poll();
            long key = c.getPos().toLong();
            if (sent.containsKey(key) && now - sent.get(key) < RESEND) {
                offered.add(c);
                continue;
            }
            if (offeredCount.addTo(key, -1) <= 1) offeredCount.remove(key);
            sink.overwrite(level, c);
            sent.put(key, now);
            budget--;
        }
        // of those sent within RESEND ticks — no more than (PER_TICK + the window of held copies) × RESEND
        sent.long2LongEntrySet().removeIf(e -> now - e.getLongValue() >= RESEND);
        Pending pending = new Pending();
        LongArrayList notYet = new LongArrayList();
        ObjectIterator<Long2LongMap.Entry> it = due.long2LongEntrySet().fastIterator();
        int scanned = 0;
        while (budget > 0 && scanned < SCAN && it.hasNext()) {
            Long2LongMap.Entry e = it.next();
            scanned++;
            long key = e.getLongKey();
            long at = Math.max(e.getLongValue(), sent.containsKey(key) ? sent.get(key) + RESEND : Long.MIN_VALUE);
            if (at > now) {
                e.setValue(at);
                notYet.add(key);
                continue;
            }
            ChunkAccess chunk = Terrain.inMemory(level, key);
            if (chunk == null) {
                SavedRead read = unloaded.get(key);
                if (read == null) {
                    if (unloaded.size() < 4) unloaded.put(key, new SavedRead(FarLods.savedCopy(level, key)));
                } else if (read.future.isDone()) {
                    unloaded.remove(key);
                    if (read.obsolete) {
                        // Retain/count the old read until completion, then retry the newest mark in a later tick.
                        notYet.add(key);
                        continue;
                    }
                    if (read.future.isCompletedExceptionally()) {
                        e.setValue(now + 20); // Retry I/O failure; do not lose the update or block the tick.
                    } else {
                        chunk = read.future.getNow(null);
                        if (chunk == null) {
                            // No final saved blocks exist here (markArea also covers untouched world-edge chunks).
                            it.remove();
                            later.remove(key);
                            continue;
                        }
                    }
                }
                if (chunk == null) {
                    notYet.add(key);
                    continue;
                }
            }
            it.remove();
            if (later.containsKey(key)) {
                long next = later.remove(key);
                if (next > now) pending.add(key, next);
            }
            sink.overwrite(level, chunk);
            sent.put(key, now);
            sentTotal++;
            budget--;
        }
        scannedMax = Math.max(scannedMax, scanned);
        // did not go through everything: not-yet-due marks go to the end, the next tick looks further
        if (it.hasNext()) for (int i = 0; i < notYet.size(); i++) due.getAndMoveToLast(notYet.getLong(i));
        for (int i = 0; i < pending.keys.size(); i++) due.put(pending.keys.getLong(i), pending.ats.getLong(i));
    }

    /** Marks that stand again after sending (into the map — after the walk). */
    private static final class Pending {
        final LongArrayList keys = new LongArrayList(), ats = new LongArrayList();

        void add(long key, long at) {
            keys.add(key);
            ats.add(at);
        }
    }

    /**
     * For checks: waiting to be sent (chunks of the world, collected), sent since the world's start (chunks of the
     * world, collected), the most
     * marks scanned per tick.
     */
    public static long[] stats(ServerLevel level) {
        DhUpdates u = level.getData(ModAttachments.DH_UPDATES);
        return new long[] {u.due.size(), u.offered.size(), u.sentTotal, u.offeredTotal - u.offered.size(), u.scannedMax};
    }
}
