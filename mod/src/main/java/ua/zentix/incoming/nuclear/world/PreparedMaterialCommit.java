package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Server-side commit of one already admitted section group; no scheduling, loading or old material ledger. */
final class PreparedMaterialCommit {
    static int depart(ServerLevel level, PreparedMaterialBatch batch, int[] indices, long tick) {
        return depart(level, batch, indices, tick, ColumnScar::replaceState);
    }

    static int depart(ServerLevel level, PreparedMaterialBatch batch, int[] indices, long tick,
                      PreparedSectionWriter.WorldWrite write) {
        var selected = select(batch, indices, tick, true);
        LevelChunk chunk = ready(level, batch, selected, true);
        if (chunk == null) return 0;
        var changes = new ArrayList<PreparedSectionWriter.CellChange>(selected.size());
        for (int index : selected) {
            var move = batch.move(index);
            // Capture the inventory at departure, not at the earlier terrain-snapshot time.
            var entity = chunk.getBlockState(move.source()) == move.before() && move.before().hasBlockEntity()
                    ? level.getBlockEntity(move.source()) : null;
            batch.capture(index, entity == null ? null : entity.saveWithoutMetadata(level.registryAccess()));
            changes.add(new PreparedSectionWriter.CellChange(move.source(), move.before(), Blocks.AIR.defaultBlockState()));
        }
        var receipt = new PreparedSectionWriter.Receipt(changes.size());
        try {
            PreparedSectionWriter.apply(level, chunk, changes, receipt, write);
        } finally {
            for (int n = 0; n < selected.size(); n++) {
                int index = selected.get(n);
                if (receipt.stale(n)) batch.sourceChanged(index);
                else if (receipt.attempted(n)) {
                    var move = batch.move(index);
                    if (chunk.getBlockState(move.source()) != move.before()) batch.removed(index);
                    else {
                        // Container cleanup precedes setBlock. An exception may leave the source
                        // state unchanged but its inventory empty; restore the captured owner.
                        var cargo = batch.cargo(index);
                        if (cargo != null) {
                            var entity = level.getBlockEntity(move.source());
                            if (entity == null) throw new IllegalStateException("Unchanged source lost its block entity");
                            entity.loadWithComponents(cargo, level.registryAccess());
                            entity.setChanged();
                        }
                    }
                }
            }
        }
        return receipt.applied().cardinality();
    }

    static int land(ServerLevel level, PreparedMaterialBatch batch, int[] indices, long tick, boolean destinationScarApplied) {
        if (!destinationScarApplied) return 0;
        var selected = select(batch, indices, tick, false);
        LevelChunk chunk = ready(level, batch, selected, false);
        if (chunk == null) return 0;
        var changes = new ArrayList<PreparedSectionWriter.CellChange>(selected.size());
        for (int index : selected) {
            var move = batch.move(index);
            changes.add(new PreparedSectionWriter.CellChange(move.destination(), move.destinationBefore(), move.placed()));
        }
        var receipt = new PreparedSectionWriter.Receipt(changes.size());
        try {
            PreparedSectionWriter.apply(level, chunk, changes, receipt);
        } finally {
            for (int n = 0; n < selected.size(); n++) {
                int index = selected.get(n);
                if (receipt.stale(n)) batch.destinationChanged(index);
                else if (receipt.applied(n)) {
                    var cargo = batch.cargo(index);
                    if (cargo != null) {
                        var entity = level.getBlockEntity(batch.move(index).destination());
                        if (entity == null) throw new IllegalStateException("Placed material has no cargo destination");
                        entity.loadWithComponents(cargo, level.registryAccess());
                        entity.setChanged();
                    }
                    batch.placed(index);
                } else if (receipt.changed(n)) batch.destinationChanged(index);
            }
        }
        return receipt.applied().cardinality();
    }

    private static List<Integer> select(PreparedMaterialBatch batch, int[] indices, long tick, boolean departure) {
        if (indices.length > 4096) throw new IllegalArgumentException("Material commit exceeds one section");
        var selected = new ArrayList<Integer>(indices.length);
        var seen = new BitSet(batch.size());
        for (int index : indices) {
            if (index < 0 || index >= batch.size() || seen.get(index)) throw new IllegalArgumentException("Invalid material index");
            seen.set(index);
            var move = batch.move(index);
            if (batch.phase(index) == (departure ? PreparedMaterialBatch.Phase.PLANNED : PreparedMaterialBatch.Phase.REMOVED)
                    && (departure ? move.departureTick() : move.landingTick()) <= tick) selected.add(index);
        }
        return selected;
    }

    /** Validate the whole group before capturing cargo; absent chunks are returned without any load. */
    private static LevelChunk ready(ServerLevel level, PreparedMaterialBatch batch, List<Integer> selected, boolean departure) {
        if (selected.isEmpty()) return null;
        var move = batch.move(selected.getFirst());
        BlockPos first = departure ? move.source() : move.destination();
        for (int index : selected) {
            var next = batch.move(index);
            BlockPos at = departure ? next.source() : next.destination();
            if (at.getX() >> 4 != first.getX() >> 4 || at.getY() >> 4 != first.getY() >> 4 || at.getZ() >> 4 != first.getZ() >> 4)
                throw new IllegalArgumentException("Material commit must contain exactly one section");
        }
        return level.getChunkSource().getChunkNow(first.getX() >> 4, first.getZ() >> 4);
    }

    private PreparedMaterialCommit() {}
}
