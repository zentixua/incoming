package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.compat.SableTerrain;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Bounded section deltas. The caller owns due times, persistence and cargo before entering this writer. */
final class PreparedSectionWriter {
    record CellChange(BlockPos pos, BlockState expected, BlockState state) {
        CellChange {
            pos = pos.immutable();
            Objects.requireNonNull(expected);
            Objects.requireNonNull(state);
        }
    }

    /** Caller-created before mutation, so even an exceptional world callback leaves an observable receipt. */
    static final class Receipt {
        private final int size;
        private final BitSet attempted = new BitSet(), applied = new BitSet(), stale = new BitSet(), changed = new BitSet();
        private boolean started;
        private int fastWrites, worldWrites, lightChecks;

        Receipt(int size) {
            if (size < 0 || size > 4096) throw new IllegalArgumentException("Section receipt exceeds cell bound");
            this.size = size;
        }

        boolean attempted(int i) { return attempted.get(i); }
        boolean applied(int i) { return applied.get(i); }
        boolean stale(int i) { return stale.get(i); }
        boolean changed(int i) { return changed.get(i); }
        BitSet attempted() { return (BitSet) attempted.clone(); }
        BitSet applied() { return (BitSet) applied.clone(); }
        BitSet stale() { return (BitSet) stale.clone(); }
        BitSet changed() { return (BitSet) changed.clone(); }
        int fastWrites() { return fastWrites; }
        int worldWrites() { return worldWrites; }
        int lightChecks() { return lightChecks; }
    }

    @FunctionalInterface
    interface WorldWrite {
        void replace(ServerLevel level, BlockPos pos, BlockState before, BlockState after);
    }

    static void apply(ServerLevel level, LevelChunk chunk, List<CellChange> changes, Receipt receipt) {
        apply(level, chunk, changes, receipt, () -> true);
    }

    /** The scheduler admits the ordinary section unit; expensive callbacks additionally consume its live permit. */
    static void apply(ServerLevel level, LevelChunk chunk, List<CellChange> changes, Receipt receipt, BooleanSupplier worldPermit) {
        apply(level, chunk, changes, receipt, ColumnScar::replaceState, worldPermit);
    }

    /** The callback seam also permits a native failure control after the actual world mutation. */
    static void apply(ServerLevel level, LevelChunk chunk, List<CellChange> changes, Receipt receipt, WorldWrite worldWrite) {
        apply(level, chunk, changes, receipt, worldWrite, () -> true);
    }

    static void apply(ServerLevel level, LevelChunk chunk, List<CellChange> changes, Receipt receipt,
                      WorldWrite worldWrite, BooleanSupplier worldPermit) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Section writes require the server thread");
        if (receipt.started || receipt.size != changes.size()) throw new IllegalArgumentException("Receipt does not own this attempt");
        int section = validate(chunk, changes);
        receipt.started = true;
        if (changes.isEmpty() || level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) != chunk) return;
        boolean sable = SableTerrain.watched(level, chunk);
        boolean radiusOne = NuclearTickets.neighbourhoodLoaded(level, chunk.getPos(), 1);
        if (sable && !radiusOne) return;
        var sections = chunk.getSections();
        long[] touched = new long[(sections.length + 63) >>> 6], wasEmpty = new long[touched.length];
        int[] top = new int[256];
        Arrays.fill(top, Integer.MIN_VALUE);
        var written = new LongArrayList(changes.size());
        Throwable failure = null;
        try {
            for (int i = 0; i < changes.size(); i++) {
                CellChange change = changes.get(i);
                BlockPos at = change.pos;
                BlockState before = chunk.getBlockState(at);
                // Exact expected state. Blackout equivalence remains confined to the old ruin-plan contract.
                if (before != change.expected) { receipt.stale.set(i); continue; }
                if (before == change.state) { receipt.applied.set(i); continue; }
                boolean fast = ordinary(before, change.state);
                if (!fast) {
                    int radius = Math.max(RuinPlan.callbackRadius(before), RuinPlan.callbackRadius(change.state));
                    if (radius > 0 && !NuclearTickets.neighbourhoodLoaded(level, chunk.getPos(), radius)) continue;
                    int x = at.getX() & 15, z = at.getZ() & 15;
                    if (!radiusOne && (x < RuinPlan.EDGE || x > 15 - RuinPlan.EDGE || z < RuinPlan.EDGE || z > 15 - RuinPlan.EDGE)) continue;
                    if (!worldPermit.getAsBoolean()) continue;
                }
                // A container may be emptied before setBlock throws while its state remains unchanged.
                // The escrow owner must reconcile every attempted world write, not only changed states.
                receipt.attempted.set(i);
                ChunkDeltaWrites.touch(sections, touched, wasEmpty, section);
                try {
                    if (fast) {
                        ChunkDeltaWrites.set(sections, touched, wasEmpty, section, at.getX() & 15, at.getY() & 15, at.getZ() & 15, change.state);
                        receipt.fastWrites++;
                    } else {
                        receipt.worldWrites++;
                        worldWrite.replace(level, at, before, change.state);
                    }
                } finally {
                    BlockState actual = chunk.getBlockState(at);
                    if (actual == change.state) receipt.applied.set(i);
                    if (actual != before) {
                        receipt.changed.set(i);
                        written.add(at.asLong());
                        int column = (at.getZ() & 15) << 4 | at.getX() & 15;
                        top[column] = Math.max(top[column], at.getY());
                        if (fast && sable) SableTerrain.changed(level, chunk, at.getX(), at.getY(), at.getZ(), before, actual);
                    }
                }
            }
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            // World callbacks can stop this attempt after earlier direct writes. Finish their chunk
            // metadata and publication even then; never erase the caller's partial ownership receipt.
            try {
                if (!written.isEmpty()) {
                    chunk.setUnsaved(true);
                    ChunkDeltaWrites.sectionStatus(level, chunk, touched, wasEmpty);
                    ChunkDeltaWrites.rescanColumns(chunk, top);
                    long[] light = written.toLongArray();
                    ChunkDeltaWrites.checkLight(level.getChunkSource().getLightEngine(), chunk.getPos(), light);
                    receipt.lightChecks = light.length;
                    var delivery = ChunkDeltaWrites.delivery(level, chunk);
                    var at = new BlockPos.MutableBlockPos();
                    for (long position : light) delivery.changed(at.set(position));
                    delivery.finish();
                    DhUpdates.mark(level, chunk.getPos(), level.getGameTime());
                }
            } catch (RuntimeException | Error publicationError) {
                if (failure == null) throw publicationError;
                failure.addSuppressed(publicationError);
            }
        }
    }

    private static int validate(LevelChunk chunk, List<CellChange> changes) {
        if (changes.size() > 4096) throw new IllegalArgumentException("Section delta exceeds cell bound");
        int section = -1;
        BitSet cells = new BitSet(4096);
        for (CellChange change : changes) {
            BlockPos at = change.pos;
            int current = chunk.getSectionIndex(at.getY());
            if (at.getX() >> 4 != chunk.getPos().x || at.getZ() >> 4 != chunk.getPos().z
                    || current < 0 || current >= chunk.getSections().length || section != -1 && current != section)
                throw new IllegalArgumentException("Delta must contain one loaded chunk section");
            section = current;
            int key = (at.getY() & 15) << 8 | (at.getZ() & 15) << 4 | at.getX() & 15;
            if (cells.get(key)) throw new IllegalArgumentException("Duplicate section delta position");
            cells.set(key);
        }
        return section;
    }

    private static boolean ordinary(BlockState before, BlockState after) {
        if (Blast.props(before).is(Blast.Props.SLOW) || Blast.props(after).is(Blast.Props.SLOW)) return false;
        // Preserve the established bulk-removal path. For deposition, only plain Block has no
        // specialised onPlace callback; falling blocks, partial shapes and mod classes use the world.
        return after.isAir() || after.getBlock().getClass() == Block.class;
    }

    private PreparedSectionWriter() {}
}
