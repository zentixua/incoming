package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import ua.zentix.incoming.Incoming;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Bounded asynchronous snapshot spill; the region index remains on disk. Server-thread owner. */
final class RuinSnapshots {
    private static final int LIMIT = 64;
    private final RuinPages pages;
    private final LongConsumer charge;
    private final LongSupplier room;
    private final Long2LongOpenHashMap saved = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<Write> writes = new Long2ObjectOpenHashMap<>();
    private record Write(long serial, CompletableFuture<Void> done) {}

    RuinSnapshots(RuinPages pages, LongConsumer charge, LongSupplier room) {
        this.pages = pages;
        this.charge = charge;
        this.room = room;
    }

    long bytes() { return 48L * (saved.size() + writes.size()); }

    /** Do not drop the original snapshot until its exact payload has reached the region writer. */
    boolean evictable(long key, ChunkShot shot) {
        if (saved.get(key) == shot.serial) return true;
        Write write = writes.get(key);
        if (write != null) {
            if (!write.done.isDone()) return false;
            writes.remove(key);
            if (!write.done.isCompletedExceptionally()) saved.put(key, write.serial);
            else Incoming.LOG.error("Could not spill nuclear snapshot at {}", new net.minecraft.world.level.ChunkPos(key));
            if (saved.get(key) == shot.serial) return true;
        }
        long reserve = Math.max(4096, shot.bytes * 3);
        if (writes.size() >= LIMIT || reserve > room.getAsLong()) return false;
        var detached = shot.saved();
        charge.accept(reserve);
        LongConsumer accounting = charge;
        writes.put(key, new Write(shot.serial, pages.write(key, detached::encode)
                .whenComplete((unused, failure) -> accounting.accept(-reserve))));
        return false;
    }

    boolean mayHave(long key) {
        return saved.containsKey(key) || !Boolean.FALSE.equals(pages.existing());
    }

    CompletableFuture<Optional<CompoundTag>> read(long key) { return pages.read(key); }

    void restored(long key, ChunkShot shot) { saved.put(key, shot.serial); }

    CompletableFuture<Void> close() { return pages.close(); }
}
