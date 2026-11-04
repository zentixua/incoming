package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.IOUtilities;
import ua.zentix.incoming.Incoming;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/**
 * One immutable ownership interval per save, not a queue of copied ledgers. The live ledger is
 * its snapshot: mutation pauses until the atomic write completes, and its full memory charge
 * remains live throughout queued/delayed I/O. All row encoding runs on NeoForge's I/O worker;
 * vanilla shutdown already joins that worker. Other dimensions and non-material work may run.
 */
abstract class LedgerData extends SavedData {
    // Deflater, buffered streams, one ordinary row, and the cold-load scanner. Cargo is already
    // charged by its owner (and written directly, without constructing/copying another cargo tree).
    static final long IO_BYTES = 256L << 10;
    private volatile boolean writing;
    private volatile boolean failed;
    private boolean loading;

    final boolean loaded() { return !loading; }
    final boolean writable() { return !writing && !loading; }
    final boolean writing() { return writing; }
    final void loading(boolean value) { loading = value; }
    final void requireWritable() {
        if (!writable()) throw new IllegalStateException("Material ledger is awaiting storage");
    }

    abstract void write(LedgerWriter writer, HolderLookup.Provider registries) throws IOException;

    @Override public final void save(File file, HolderLookup.Provider registries) {
        if (!writable() || !isDirty() && !failed) return;
        long mtB7Id = MtB7Metrics.saveId();
        long mtB7Session = MtB7Metrics.session();
        String mtB7File = mtB7Id == 0 ? "" : MtB7Metrics.fileKey(file);
        long mtB7Frozen = mtB7Id == 0 ? 0 : System.nanoTime();
        writing = true; // Before enqueue: delayed I/O owns the ledger too.
        failed = false;
        setDirty(false);
        IOUtilities.withIOWorker(() -> {
            long mtB7Worker = mtB7Id == 0 ? 0 : System.nanoTime();
            MtB7Metrics.save(mtB7Id, mtB7Session, mtB7File, this, "worker_start_queue", mtB7Worker - mtB7Frozen, true);
            long mtB7Atomic = MtB7Stages.begin(5);
            boolean mtB7Success = false;
            try {
                IOUtilities.atomicWrite(file.toPath(), stream -> {
                    try (var output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(stream)))) {
                        var writer = new LedgerWriter(output);
                        writer.compound("");
                        CompoundTag version = new CompoundTag();
                        NbtUtils.addCurrentDataVersion(version);
                        writer.fields(version);
                        writer.compound("data");
                        write(writer, registries);
                        writer.end(); writer.end();
                    }
                });
                mtB7Success = true;
            } catch (IOException | RuntimeException error) {
                MtB7Stages.fail(5);
                failed = true; // Keep the old atomic file, retry on the next save even after shutdown.
                Incoming.LOG.error("Could not save material ledger {}", file, error);
            } finally {
                long mtB7Done = mtB7Id == 0 ? 0 : System.nanoTime();
                writing = false;
                MtB7Stages.end(5, mtB7Atomic);
                MtB7Metrics.save(mtB7Id, mtB7Session, mtB7File, this, "atomic_write_complete", mtB7Atomic == 0 ? 0 : mtB7Done - mtB7Atomic, mtB7Success);
                MtB7Metrics.save(mtB7Id, mtB7Session, mtB7File, this, "freeze_complete", mtB7Id == 0 ? 0 : mtB7Done - mtB7Frozen, mtB7Success);
            }
        });
        MtB7Metrics.save(mtB7Id, mtB7Session, mtB7File, this, "enqueue_return", mtB7Id == 0 ? 0 : System.nanoTime() - mtB7Frozen, true);
    }
}
