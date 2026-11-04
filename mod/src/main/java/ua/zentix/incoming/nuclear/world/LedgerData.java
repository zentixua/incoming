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
        writing = true; // Before enqueue: delayed I/O owns the ledger too.
        failed = false;
        setDirty(false);
        IOUtilities.withIOWorker(() -> {
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
            } catch (IOException | RuntimeException error) {
                failed = true; // Keep the old atomic file, retry on the next save even after shutdown.
                Incoming.LOG.error("Could not save material ledger {}", file, error);
            } finally {
                writing = false;
            }
        });
    }
}
