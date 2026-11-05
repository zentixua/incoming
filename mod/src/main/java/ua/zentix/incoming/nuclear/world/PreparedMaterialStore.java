package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;

/**
 * Bounded hot material pages, backed by ordinary region files. Public operations belong to the server
 * thread; completion callbacks only publish detached data and receipts under this owner's monitor.
 * No chunk loading, tickets, cold-page index or per-material tick loop. Cold terminal receipts remain
 * on disk. This owner is not yet wired to world save/stop events.
 */
final class PreparedMaterialStore {
    record Key(int event, long section) {
        Key { if (event < 0) throw new IllegalArgumentException("Invalid material event"); }
        static Key of(PreparedMaterialBatch batch) {
            return new Key(batch.event(), SectionPos.asLong(batch.owner().x, batch.sectionY(), batch.owner().z));
        }
        long chunk() { return ChunkPos.asLong(SectionPos.x(section), SectionPos.z(section)); }
        Directory directory() { return new Directory(event, SectionPos.y(section)); }
    }

    record Limits(int residents, int reads, int writes, int handles, long pageBytes) {
        Limits {
            if (residents < 1 || reads < 1 || writes < 1 || handles < 1 || pageBytes < 512)
                throw new IllegalArgumentException("Positive material store limits required");
        }
    }
    enum State { READY, PENDING, ABSENT, WAITING, FAILED }
    record Lookup(State state, PreparedMaterialBatch batch, Throwable failure) {}
    record Counters(int residents, int reads, int writes, int pending, int handles, long writesStarted, long reservedBytes) {}
    private record Directory(int event, int y) {}
    private static final class Handle {
        final RuinPages pages;
        int pins;
        CompletableFuture<Void> closing;
        Handle(RuinPages pages) { this.pages = pages; }
    }
    private static final class Generation {
        final long revision;
        final PreparedMaterialBatch.Snapshot snapshot;
        final CompletableFuture<Void> done;
        Generation(long revision, PreparedMaterialBatch.Snapshot snapshot, CompletableFuture<Void> done) {
            this.revision = revision; this.snapshot = snapshot; this.done = done;
        }
    }
    private static final class Entry {
        final Key key;
        final Handle handle;
        PreparedMaterialBatch batch;
        boolean reading;
        Throwable failure;
        CompletableFuture<?> read;
        long writtenRevision = -1;
        Generation active, pending;
        Entry(Key key, Handle handle) { this.key = key; this.handle = handle; }
    }

    private final RegionStorageInfo info;
    private final Path root;
    private final Limits limits;
    private final BiFunction<RegionStorageInfo, Path, RuinPages> factory;
    private final LinkedHashMap<Key, Entry> hot = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<Directory, Handle> handles = new LinkedHashMap<>();
    private int reads, writes;
    private long writesStarted;
    private Throwable failure;
    private boolean closing;
    private CompletableFuture<Void> closeReceipt;

    PreparedMaterialStore(RegionStorageInfo info, Path root, Limits limits) { this(info, root, limits, RuinPages::new); }
    PreparedMaterialStore(RegionStorageInfo info, Path root, Limits limits, BiFunction<RegionStorageInfo, Path, RuinPages> factory) {
        this.info = info; this.root = root; this.limits = limits; this.factory = factory;
    }

    /** ABSENT is an actual completed page read, never a missing cache entry or an admission refusal. */
    synchronized Lookup request(Key key) {
        requireOpen();
        Entry existing = hot.get(key);
        if (existing != null) return lookup(existing);
        if (failure != null) return new Lookup(State.FAILED, null, failure);
        if (reads >= limits.reads || !room()) return new Lookup(State.WAITING, null, null);
        Handle handle = handle(key.directory());
        if (handle == null) return new Lookup(State.WAITING, null, null);
        Entry entry = new Entry(key, handle);
        entry.reading = true; reads++; hot.put(key, entry);
        entry.read = handle.pages.read(key.chunk()).thenApply(tag -> {
            if (tag.isEmpty()) return null;
            // Our writer admits bounded new-format pages. Vanilla's parser itself is not a hostile-NBT heap cap.
            if (tag.get().sizeInBytes() > encodedLimit()) throw new IllegalArgumentException("Material page NBT exceeds capacity");
            var batch = PreparedMaterialBatch.decode(tag.get());
            if (!Key.of(batch).equals(key)) throw new IllegalArgumentException("Material page identity mismatch");
            batch.setCapacity(limits.pageBytes);
            return batch;
        }).whenComplete((batch, error) -> {
            synchronized (PreparedMaterialStore.this) {
                reads--; entry.reading = false;
                if (error != null) { entry.failure = error; fail(error); }
                else { entry.batch = batch; entry.writtenRevision = batch == null ? -1 : batch.revision(); }
            }
        });
        return lookup(entry);
    }

    synchronized PreparedMaterialBatch get(Key key) {
        requireOpen();
        if (failure != null) return null;
        Entry entry = hot.get(key);
        return entry == null || entry.reading || entry.failure != null ? null : entry.batch;
    }

    /** Caller must first obtain ABSENT. A new plan cannot overwrite a cold completed receipt. */
    synchronized boolean admit(PreparedMaterialBatch batch) {
        requireOpen();
        Entry entry = hot.get(Key.of(batch));
        if (failure != null || entry == null || entry.reading || entry.failure != null || entry.batch != null) return false;
        batch.setCapacity(limits.pageBytes);
        entry.batch = batch;
        return true;
    }

    synchronized CompletableFuture<Void> save(Key key) {
        requireOpen();
        Entry entry = hot.get(key);
        if (entry == null || entry.batch == null || entry.reading)
            return CompletableFuture.failedFuture(new IllegalStateException("Material page is not resident"));
        return save(entry);
    }

    private CompletableFuture<Void> save(Entry entry) {
        if (failure != null) return CompletableFuture.failedFuture(failure);
        long revision = entry.batch.revision();
        if (entry.writtenRevision >= revision) return CompletableFuture.completedFuture(null);
        if (entry.active != null && entry.active.revision == revision) return entry.active.done;
        if (entry.pending != null && entry.pending.revision == revision) return entry.pending.done;
        if (entry.batch.retainedBytes() > limits.pageBytes || entry.batch.snapshotBytes() > limits.pageBytes)
            return CompletableFuture.failedFuture(new IllegalStateException("Material page escaped its admitted capacity"));
        // At most one not-yet-started snapshot per hot page. Its receipt may be satisfied by a newer revision.
        var done = entry.pending == null ? new CompletableFuture<Void>() : entry.pending.done;
        entry.pending = new Generation(revision, entry.batch.snapshot(), done);
        startWrites();
        return done;
    }

    private void startWrites() {
        while (failure == null && writes < limits.writes) {
            Entry next = null;
            for (Entry entry : hot.values()) if (entry.active == null && entry.pending != null) { next = entry; break; }
            if (next == null) return;
            Entry entry = next;
            Generation generation = entry.pending;
            entry.pending = null; entry.active = generation; writes++; writesStarted++;
            entry.handle.pages.write(entry.key.chunk(), () -> {
                var tag = generation.snapshot.encode();
                if (tag.sizeInBytes() > encodedLimit()) throw new IllegalArgumentException("Material page NBT exceeds capacity");
                return tag;
            }).whenComplete((unused, error) -> {
                synchronized (PreparedMaterialStore.this) {
                    writes--; entry.active = null;
                    if (error != null) {
                        entry.failure = error; fail(error); generation.done.completeExceptionally(error);
                    } else {
                        entry.writtenRevision = Math.max(entry.writtenRevision, generation.revision);
                        generation.done.complete(null);
                    }
                    startWrites();
                }
            });
        }
    }

    /** Capture latest hot revisions now; I/O callbacks never inspect the mutable live batches. */
    synchronized CompletableFuture<Void> flush() {
        requireOpen();
        return flushHot();
    }

    private CompletableFuture<Void> flushHot() {
        var receipts = new ArrayList<CompletableFuture<?>>();
        for (Entry entry : hot.values()) if (entry.batch != null) receipts.add(save(entry));
        var pinned = new ArrayList<>(handles.values());
        for (Handle handle : pinned) handle.pins++;
        return CompletableFuture.allOf(receipts.toArray(CompletableFuture[]::new)).handle((unused, writeError) -> writeError)
                .thenCompose(writeError -> {
                    var fences = new ArrayList<CompletableFuture<?>>();
                    for (Handle handle : pinned) fences.add(handle.closing == null ? handle.pages.flush() : handle.closing);
                    return CompletableFuture.allOf(fences.toArray(CompletableFuture[]::new)).handle((unused, flushError) -> {
                        synchronized (PreparedMaterialStore.this) {
                            if (writeError != null) fail(writeError);
                            if (flushError != null) fail(flushError);
                            if (failure != null) throw new CompletionException(failure);
                            return (Void) null;
                        }
                    });
                }).whenComplete((unused, error) -> {
                    synchronized (PreparedMaterialStore.this) { for (Handle handle : pinned) handle.pins--; }
                });
    }

    /** Eviction acknowledges only the current revision; it never deletes its cold page/terminal receipt. */
    synchronized boolean evict(Key key) {
        requireOpen();
        Entry entry = hot.get(key);
        if (entry == null) return true;
        if (!evictable(entry)) return false;
        hot.remove(key);
        return true;
    }

    private boolean room() {
        if (hot.size() < limits.residents) return true;
        for (var iterator = hot.entrySet().iterator(); iterator.hasNext();) {
            Entry entry = iterator.next().getValue();
            if (evictable(entry)) { iterator.remove(); return true; }
        }
        // Progress requires saving the existing owners, not dropping them to admit another page.
        for (Entry entry : hot.values()) if (entry.batch != null) save(entry);
        return false;
    }

    private static boolean evictable(Entry entry) {
        return !entry.reading && entry.failure == null && entry.active == null && entry.pending == null
                && (entry.batch == null || entry.writtenRevision >= entry.batch.revision());
    }

    private Handle handle(Directory directory) {
        Handle existing = handles.get(directory);
        if (existing != null) return existing.closing == null ? existing : null;
        if (handles.size() < limits.handles) {
            Handle handle = new Handle(factory.apply(info, root.resolve("event-" + directory.event).resolve("section-" + directory.y)));
            handles.put(directory, handle);
            return handle;
        }
        // The handle limit is independent of page slots. Retire only idle handles, including their close receipt.
        for (var pair : new ArrayList<>(handles.entrySet())) {
            Handle handle = pair.getValue();
            if (handle.closing != null || handle.pins != 0) continue;
            boolean busy = false;
            for (Entry entry : hot.values()) if (entry.handle == handle && !evictable(entry)) {
                busy = true;
                if (entry.batch != null) save(entry);
            }
            if (busy) continue;
            hot.values().removeIf(entry -> entry.handle == handle);
            handle.closing = handle.pages.close();
            handle.closing.whenComplete((unused, error) -> {
                synchronized (PreparedMaterialStore.this) {
                    handles.remove(pair.getKey(), handle);
                    if (error != null) fail(error);
                }
            });
            break;
        }
        return null;
    }

    synchronized CompletableFuture<Void> close() {
        if (closeReceipt != null) return closeReceipt;
        closing = true;
        var readsDone = hot.values().stream().filter(entry -> entry.reading).map(entry -> entry.read).toArray(CompletableFuture[]::new);
        var flushed = flushHot();
        closeReceipt = CompletableFuture.allOf(flushed, CompletableFuture.allOf(readsDone)).handle((unused, error) -> error)
                .thenCompose(error -> {
                    synchronized (PreparedMaterialStore.this) {
                        if (error != null) fail(error);
                        var closes = handles.values().stream().map(handle -> handle.closing == null ? handle.pages.close() : handle.closing)
                                .toArray(CompletableFuture[]::new);
                        return CompletableFuture.allOf(closes).handle((unused, closeError) -> {
                            synchronized (PreparedMaterialStore.this) {
                                if (closeError != null) fail(closeError);
                                if (failure != null) throw new CompletionException(failure);
                                hot.clear(); handles.clear();
                                return (Void) null;
                            }
                        });
                    }
                });
        return closeReceipt;
    }

    synchronized Counters counters() {
        int pending = 0;
        for (Entry entry : hot.values()) if (entry.pending != null) pending++;
        // Conservative module reservation, not total JVM heap. Region handles include their vanilla file cache.
        long bytes = (long) hot.size() * limits.pageBytes + (reads + writes + pending) * (8192L + 4L * limits.pageBytes)
                + handles.size() * (4L << 20);
        return new Counters(hot.size(), reads, writes, pending, handles.size(), writesStarted, bytes);
    }

    private Lookup lookup(Entry entry) {
        if (failure != null) return new Lookup(State.FAILED, null, failure);
        if (entry.failure != null) return new Lookup(State.FAILED, null, entry.failure);
        if (entry.reading) return new Lookup(State.PENDING, null, null);
        return new Lookup(entry.batch == null ? State.ABSENT : State.READY, entry.batch, null);
    }

    private void fail(Throwable error) {
        if (failure == null) failure = error;
        for (Entry entry : hot.values()) if (entry.pending != null) {
            entry.pending.done.completeExceptionally(error); entry.pending = null;
        }
    }

    private void requireOpen() { if (closing) throw new IllegalStateException("Material store is closing"); }

    /** Fixed codec/header allowance plus bounded palette/row NBT; identical on write and read. */
    private long encodedLimit() { return 8192L + 2L * limits.pageBytes; }
}
