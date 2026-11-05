package ua.zentix.incoming.nuclear.world;

import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Detached, ordered pages using vanilla's region-file writer, never live world chunks. */
final class RuinPages {
    private static final class Worker extends IOWorker {
        Worker(RegionStorageInfo info, Path path) { super(info, path, false); }
    }

    private final Worker worker;
    private final CompletableFuture<Boolean> existing;
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
    // Ordering deliberately recovers after failed operations. Durability must not forget their failure.
    private volatile Throwable storageFailure;
    private boolean closed;

    RuinPages(RegionStorageInfo info, Path path) {
        existing = CompletableFuture.supplyAsync(() -> java.nio.file.Files.isDirectory(path), Util.ioPool());
        tail = existing;
        worker = new Worker(info, path);
    }

    /** null while the off-thread directory probe is pending. */
    Boolean existing() { return existing.getNow(null); }

    /** Caller retains/reserves the immutable source until the returned write receipt completes. */
    synchronized CompletableFuture<Void> write(long key, Supplier<CompoundTag> encode) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Ruin pages closed"));
        // Serialize encoding as well as I/O: a slow older encoder must never overwrite a newer page.
        var result = tail.handleAsync((unused, failure) -> encode.get(), Util.ioPool())
                .thenCompose(tag -> worker.store(new ChunkPos(key), tag))
                .whenComplete((unused, failure) -> rememberFailure(failure));
        tail = result;
        return result;
    }

    synchronized CompletableFuture<Optional<CompoundTag>> read(long key) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Ruin pages closed"));
        var result = tail.handleAsync((unused, failure) -> key, Util.ioPool())
                .thenCompose(k -> worker.loadAsync(new ChunkPos(k)));
        // Ordering owns completion only; the reader's reservation owns the returned payload.
        tail = result.handle((unused, failure) -> null);
        return result;
    }

    /**
     * Ordered force-to-storage fence. Every admitted write before this call is included, even if a
     * later read recovered the ordering tail. A write/flush failure remains visible for this handle's
     * lifetime; the owner must retain its failed page and recover explicitly, not infer success from close.
     */
    synchronized CompletableFuture<Void> flush() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Ruin pages closed"));
        var result = tail.handle((unused, failure) -> (Void) null)
                .thenCompose(unused -> worker.synchronize(true))
                .handle((unused, failure) -> {
                    rememberFailure(failure);
                    if (storageFailure != null) throw new CompletionException(storageFailure);
                    return (Void) null;
                });
        tail = result;
        return result;
    }

    private void rememberFailure(Throwable failure) {
        if (failure != null && storageFailure == null) storageFailure = failure;
    }

    /** Always close the worker, including after a failed flush, and retain that failure on repeated close. */
    synchronized CompletableFuture<Void> close() {
        if (closed) return tail.thenApply(unused -> null);
        var flushed = flush();
        closed = true;
        var result = flushed.handleAsync((unused, failure) -> {
            try { worker.close(); }
            catch (java.io.IOException | RuntimeException error) {
                if (failure != null) error.addSuppressed(failure);
                throw new CompletionException(error);
            }
            if (failure != null) throw new CompletionException(failure);
            return (Void) null;
        }, Util.ioPool());
        tail = result;
        return result;
    }
}
