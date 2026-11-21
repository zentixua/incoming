package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.function.Function;

/** Server-thread journal of conventional support losses, shared by overlapping impacts in one world. */
public final class StrikeRemovals {
    private final LinkedHashSet<Source> active = new LinkedHashSet<>();
    private Long2ObjectOpenHashMap<Chunk> chunks = new Long2ObjectOpenHashMap<>();
    private @Nullable Entry first, last;
    private long sequence;

    /** The original impact's claim context. Its overlap interval begins at its first structural removal. */
    public final class Source {
        private final Function<ServerLevel, Explosion> attribution;
        private long began;
        private long ended = Long.MAX_VALUE;

        private Source(Function<ServerLevel, Explosion> attribution) {
            this.attribution = attribution;
        }

        public Explosion explosion(ServerLevel level) {
            return attribution.apply(level);
        }

        public void record(long at, BlockState was) {
            long now = ++sequence;
            if (began == 0) {
                began = now;
                active.add(this);
            }
            long chunk = ChunkPos.asLong(BlockPos.getX(at) >> 4, BlockPos.getZ(at) >> 4);
            Chunk list = chunks.computeIfAbsent(chunk, key -> new Chunk());
            Entry entry = new Entry(at, was, this, now, chunk);
            if (list.last == null) list.first = entry;
            else list.last.nextInChunk = entry;
            list.last = entry;
            if (last == null) first = entry;
            else last.next = entry;
            last = entry;
            prune(1);
        }

        public void close() {
            ended = ++sequence;
            if (!active.remove(this)) return;
            if (active.isEmpty()) {
                chunks = new Long2ObjectOpenHashMap<>();
                first = last = null;
            } else {
                prune(StrikeSupport.INDEX_UNIT);
            }
        }

        /** Pin the end of this chunk's journal at the same instant as its block snapshot. */
        Cursor snapshot(long chunk) {
            Chunk journal = chunks.get(chunk);
            return new Cursor(journal == null ? null : journal.first, sequence, began);
        }
    }

    public Source source(Function<ServerLevel, Explosion> attribution) {
        return new Source(attribution);
    }

    private void prune(int limit) {
        long oldest = active.getFirst().began;
        for (int i = 0; i < limit && first != null && first.source.ended < oldest; i++) {
            Entry entry = first;
            Chunk list = chunks.get(entry.chunk);
            if (entry == list.last) chunks.remove(entry.chunk);
            else list.first = entry.nextInChunk;
            first = entry.next;
            entry.next = null;
            if (first == null) last = null;
        }
    }

    private static final class Chunk {
        private @Nullable Entry first, last;
    }

    static final class Entry {
        final long at;
        final BlockState was;
        final Source source;
        private final long sequence, chunk;
        private @Nullable Entry nextInChunk, next;

        Entry(long at, BlockState was, Source source, long sequence, long chunk) {
            this.at = at;
            this.was = was;
            this.source = source;
            this.sequence = sequence;
            this.chunk = chunk;
        }
    }

    static final class Cursor {
        private @Nullable Entry next;
        private final long through, since;

        Cursor(@Nullable Entry next, long through, long since) {
            this.next = next;
            this.through = through;
            this.since = since;
        }

        /** Advance even for an expired entry so the caller charges it to its current slice. */
        @Nullable Entry step() {
            Entry entry = next;
            if (entry == null || entry.sequence > through) return null;
            next = entry.nextInChunk;
            return entry.source.ended >= since ? entry : null;
        }

        boolean hasNext() {
            return next != null && next.sequence <= through;
        }
    }
}
