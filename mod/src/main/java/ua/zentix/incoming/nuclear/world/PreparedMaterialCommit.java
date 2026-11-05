package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Server-side commit of one already admitted section group; no scheduling, loading or old material ledger. */
final class PreparedMaterialCommit {
    static int depart(ServerLevel level, PreparedMaterialBatch batch, int[] indices, long tick) {
        return depart(level, batch, indices, tick, ColumnScar::replaceState, () -> true);
    }

    static int depart(ServerLevel level, PreparedMaterialBatch batch, int[] indices, long tick, BooleanSupplier permit) {
        return depart(level, batch, indices, tick, ColumnScar::replaceState, permit);
    }

    static int depart(ServerLevel level, PreparedMaterialBatch batch, int[] indices, long tick,
                      PreparedSectionWriter.WorldWrite write) {
        return depart(level, batch, indices, tick, write, () -> true);
    }

    static int depart(ServerLevel level, PreparedMaterialBatch batch, int[] indices, long tick,
                      PreparedSectionWriter.WorldWrite write, BooleanSupplier permit) {
        var selected = select(batch, indices, tick, true);
        LevelChunk chunk = ready(level, batch, selected, true);
        if (chunk == null) return 0;
        var changes = new ArrayList<PreparedSectionWriter.CellChange>(selected.size());
        for (int index : selected) {
            var move = batch.move(index);
            changes.add(new PreparedSectionWriter.CellChange(move.source(), move.before(), move.sourceAfter()));
        }
        var receipt = new PreparedSectionWriter.Receipt(changes.size());
        Throwable failure = null;
        try {
            PreparedSectionWriter.apply(level, chunk, changes, receipt, write, permit, n -> {
                int index = selected.get(n);
                var move = batch.move(index);
                // Capture only after callback admission and exact source validation. Even NBT
                // capture is part of the admitted work; a whole section of containers is not free.
                var entity = move.before().hasBlockEntity() ? level.getBlockEntity(move.source()) : null;
                if (move.before().hasBlockEntity() && entity == null) return false;
                return batch.tryCapture(index, entity == null ? null : entity.saveWithoutMetadata(level.registryAccess()));
            });
        } catch (RuntimeException | Error error) {
            failure = error;
        }
        for (int n = 0; n < selected.size(); n++) {
            int index = selected.get(n);
            try {
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
            } catch (RuntimeException | Error error) {
                failure = combine(failure, error); // Reconcile the other confirmed writes too.
            }
        }
        rethrow(failure);
        return receipt.applied().cardinality();
    }

    static int land(ServerLevel level, PreparedMaterialBatch batch, int[] indices, long tick, boolean destinationScarApplied) {
        return land(level, batch, indices, tick, destinationScarApplied, () -> true);
    }

    static int land(ServerLevel level, PreparedMaterialBatch batch, int[] indices, long tick,
                    boolean destinationScarApplied, BooleanSupplier permit) {
        if (!destinationScarApplied) return 0;
        var selected = select(batch, indices, tick, false);
        LevelChunk chunk = ready(level, batch, selected, false);
        if (chunk == null) return 0;
        var changes = new ArrayList<PreparedSectionWriter.CellChange>(selected.size());
        selected.removeIf(index -> {
            if (replaceable(batch.move(index).destinationBefore())) return false;
            batch.destinationChanged(index);
            return true;
        });
        for (int index : selected) {
            var move = batch.move(index);
            changes.add(new PreparedSectionWriter.CellChange(move.destination(), move.destinationBefore(), move.placed()));
        }
        var receipt = new PreparedSectionWriter.Receipt(changes.size());
        Throwable failure = null;
        try {
            PreparedSectionWriter.apply(level, chunk, changes, receipt, permit);
        } catch (RuntimeException | Error error) {
            failure = error;
        }
        for (int n = 0; n < selected.size(); n++) {
            int index = selected.get(n);
            try {
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
            } catch (RuntimeException | Error error) {
                // Retain escrow and stop automatic placement after a partial cargo callback.
                if (batch.phase(index) == PreparedMaterialBatch.Phase.REMOVED) batch.destinationChanged(index);
                failure = combine(failure, error);
            }
        }
        rethrow(failure);
        return receipt.applied().cardinality();
    }

    private static boolean replaceable(BlockState state) {
        if (state.hasBlockEntity()) return false;
        var props = Blast.props(state);
        return state.isAir() || props.fluid() || props.is(Blast.Props.REPLACEABLE)
                || !props.bearing() && !props.leaves() && !props.fixed();
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

    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable error) {
        if (error instanceof RuntimeException runtime) throw runtime;
        if (error instanceof Error fatal) throw fatal;
    }

    private PreparedMaterialCommit() {}
}
