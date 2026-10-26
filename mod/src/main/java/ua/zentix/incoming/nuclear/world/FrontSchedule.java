package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.PriorityQueue;

/**
 * Queue of held front LOD copies ({@link FrontLods}) grouped by Distant Horizons section: section chunks sent together at
 * section deadline, sections ordered by deadline (Minecraft-free unit tests). DH writes LOD database in 64×64-block areas
 * ({@code FullDataSourceV2.WIDTH}), 4×4 chunks, no earlier than a second after last area write: a section chunk
 * sent later would delay entire area.
 */
final class FrontSchedule {
    /** Chunks per DH section axis. */
    static final int SECTION_CHUNKS = 4;

    /** Chunks of one section with send deadline; order breaks deadline ties. */
    private record Batch(long section, long at, long order, LongArrayList chunks) {}

    private final PriorityQueue<Batch> queue = new PriorityQueue<>((a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.order, b.order));
    /** Section → unsent batch (additional chunks of same section join it). */
    private final Long2ObjectOpenHashMap<Batch> open = new Long2ObjectOpenHashMap<>();
    private long added;
    private int size;

    /** Chunk's DH section (like {@code ChunkPos.asLong}: x lower 32 bits, z upper). */
    static long section(long chunk) {
        int x = Math.floorDiv((int) chunk, SECTION_CHUNKS), z = Math.floorDiv((int) (chunk >>> 32), SECTION_CHUNKS);
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }

    /** DH section midpoint on axis, blocks (section index along axis). */
    static double middle(int section) {
        return (section * SECTION_CHUNKS + SECTION_CHUNKS / 2) * 16.0;
    }

    /**
     * Queue chunk for tick {@code at}. One deadline per section: chunk joins its section's unsent batch
     * (using its deadline), otherwise starts new batch (copy built after section sent leaves immediately).
     */
    void add(long chunk, long at) {
        long s = section(chunk);
        Batch b = open.get(s);
        if (b == null) {
            b = new Batch(s, at, added++, new LongArrayList(1));
            open.put(s, b);
            queue.add(b);
        }
        b.chunks.add(chunk);
        size++;
    }

    /** Chunk no longer awaiting send (loaded, evicted); false if absent from queue. */
    boolean remove(long chunk) {
        Batch b = open.get(section(chunk));
        if (b == null || !b.chunks.rem(chunk)) return false;
        size--;
        return true;
    }

    /** Chunks of earliest section whose deadline arrived (skip empty batches); null if none. Does not remove. */
    @Nullable
    LongArrayList head(long now) {
        for (Batch b; (b = queue.peek()) != null; ) {
            if (!b.chunks.isEmpty()) return b.at <= now ? b.chunks : null;
            queue.poll();
            open.remove(b.section, b);
        }
        return null;
    }

    /** Remove earliest section ({@link #head}): its chunks sent. */
    void pop() {
        Batch b = queue.poll();
        if (b == null) return;
        open.remove(b.section, b);
        size -= b.chunks.size();
    }

    /** Chunks awaiting send. */
    int size() {
        return size;
    }

    void clear() {
        queue.clear();
        open.clear();
        size = 0;
    }
}
