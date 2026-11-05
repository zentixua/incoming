package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.BitSet;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** One admitted source-section attempt. The caller owns scheduling, page pins, original inputs and repair. */
final class PreparedSourceCommit {
    enum State { COMPLETE, PENDING, STALE }

    record Result(State state, BitSet stale) {
        Result { stale = (BitSet) stale.clone(); }
        @Override public BitSet stale() { return (BitSet) stale.clone(); }
    }

    /**
     * The complete initial footprint contains every static source candidate; actual flash
     * candidates are added once before installation. Previously captured flash candidates may
     * also be supplied (for example when a fire vanished between publication and this attempt).
     * A source descriptor must already verify each page reference against its UUID/source/destination row.
     * Tick arguments are offsets from the actual detonation. The caller admits this one section
     * under its work deadline; worldPermit separately admits each potentially expensive callback.
     */
    static Result attempt(ServerLevel level, LevelChunk chunk, Detonation d, PreparedSourceProgress.Identity id,
                          RuinPlan plan, Map<Integer, BitSet> completeFootprint, int sectionY,
                          long dueAgeTick, long ageTick, ColumnScar.Budget budget, boolean originalsDurable,
                          PreparedMaterialStore.Lease owner, int[] indices, BooleanSupplier worldPermit) {
        if (!level.getServer().isSameThread() || chunk.getLevel() != level)
            throw new IllegalStateException("Source commit requires its server thread and world");
        if (id.event() != d.id() || dueAgeTick < 0 || sectionY < chunk.getMinSection() || sectionY >= chunk.getMaxSection())
            throw new IllegalArgumentException("Invalid source event or section timing");
        PreparedSourceProgress progress = PreparedSourceProgress.get(chunk);
        if (!progress.isEmpty() && !id.equals(progress.identity()))
            throw new IllegalStateException("Another source identity still owns this chunk");
        int scar = chunk.getData(ModAttachments.CHUNK_SCAR);
        if (scar >= id.event()) {
            if (!progress.isEmpty()) {
                if (!progress.finished()) throw new IllegalStateException("Scar watermark overtook unfinished source progress");
                PreparedSourceProgress.retire(chunk, id);
            }
            return result(State.COMPLETE);
        }
        if (ageTick < dueAgeTick || !originalsDurable
                || level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) != chunk)
            return result(State.PENDING);
        validatePage(chunk, sectionY, id, owner, indices);
        var baseline = plan.sourceFootprint(chunk.getMinSection());
        if (progress.isEmpty()) {
            if (completeFootprint.size() > chunk.getSections().length) throw new IllegalArgumentException("Source footprint exceeds chunk height");
            for (var entry : completeFootprint.entrySet())
                if (entry.getKey() < chunk.getMinSection() || entry.getKey() >= chunk.getMaxSection() || entry.getValue().length() > 4096)
                    throw new IllegalArgumentException("Source footprint exceeds chunk section bounds");
            for (var entry : baseline.entrySet()) {
                BitSet missing = (BitSet) entry.getValue().clone();
                missing.andNot(completeFootprint.getOrDefault(entry.getKey(), new BitSet()));
                if (!missing.isEmpty()) throw new IllegalArgumentException("Source footprint omits a planned candidate");
            }
            var installed = plan.waveFootprint(chunk, d);
            for (var entry : completeFootprint.entrySet())
                installed.computeIfAbsent(entry.getKey(), ignored -> new BitSet(4096)).or(entry.getValue());
            progress = PreparedSourceProgress.install(chunk, id, installed);
        }
        if (!progress.begun()) {
            plan.beginWave(level, chunk); // Original inputs already have their external durable owner.
            progress = PreparedSourceProgress.begun(chunk, id);
        }
        if (progress.finished()) return finish(level, chunk, d, id, plan, budget);

        BitSet pending = progress.pending(sectionY);
        if (!pending.isEmpty()) {
            // Only these extra, previously installed candidates carry flash-quench provenance.
            // Each is still evaluated by binding; this is not pending-minus-changes completion.
            BitSet quenches = (BitSet) pending.clone();
            quenches.andNot(baseline.getOrDefault(sectionY, new BitSet()));
            var binding = plan.bindSection(chunk, d, budget, sectionY - chunk.getMinSection(), pending::get, quenches);
            var receipt = new PreparedSectionWriter.Receipt(binding.changes().size());
            BitSet stale = new BitSet(4096);
            for (long position : binding.stalePositions()) stale.set(PreparedSourceProgress.local(BlockPos.of(position)));
            BitSet accounted = binding.resolvedNoOps();
            accounted.or(stale);
            for (var change : binding.changes()) accounted.set(PreparedSourceProgress.local(change.pos()));
            BitSet unexplained = (BitSet) pending.clone();
            unexplained.andNot(accounted);
            stale.or(unexplained);
            Throwable failure = null;
            try {
                PreparedMaterialCommit.waveDepart(level, chunk, binding, owner, indices, ageTick, receipt, worldPermit);
            } catch (RuntimeException | Error error) {
                failure = error;
                throw error;
            } finally {
                try {
                    BitSet confirmed = binding.resolvedNoOps();
                    for (int n = 0; n < binding.changes().size(); n++) {
                        int local = PreparedSourceProgress.local(binding.changes().get(n).pos());
                        if (receipt.stale(n)) stale.set(local);
                        else if (receipt.applied(n)) confirmed.set(local);
                    }
                    if (owner != null) for (int index : indices)
                        if (owner.batch().phase(index) == PreparedMaterialBatch.Phase.SOURCE_CHANGED)
                            stale.set(PreparedSourceProgress.local(owner.batch().move(index).source()));
                    confirmed.andNot(stale);
                    PreparedSourceProgress.recordApplied(chunk, id, sectionY, confirmed);
                } catch (RuntimeException | Error checkpointFailure) {
                    if (failure == null) throw checkpointFailure;
                    failure.addSuppressed(checkpointFailure);
                }
            }
            if (!stale.isEmpty()) return new Result(State.STALE, stale);
        }
        return PreparedSourceProgress.get(chunk).cellsComplete() ? finish(level, chunk, d, id, plan, budget) : result(State.PENDING);
    }

    private static Result finish(ServerLevel level, LevelChunk chunk, Detonation d, PreparedSourceProgress.Identity id,
                                 RuinPlan plan, ColumnScar.Budget budget) {
        PreparedSourceProgress progress = PreparedSourceProgress.get(chunk);
        if (!progress.finished()) {
            plan.finishWave(level, chunk, d, budget);
            PreparedSourceProgress.finish(chunk, id);
        }
        // The initial gate rejects an unfinished owner overtaken by another event. Never regress a later scar.
        chunk.setData(ModAttachments.CHUNK_SCAR, Math.max(chunk.getData(ModAttachments.CHUNK_SCAR), id.event()));
        PreparedSourceProgress.retire(chunk, id);
        return result(State.COMPLETE);
    }

    private static void validatePage(LevelChunk chunk, int sectionY, PreparedSourceProgress.Identity id,
                                     PreparedMaterialStore.Lease owner, int[] indices) {
        if (indices.length > 4096 || owner == null && indices.length != 0)
            throw new IllegalArgumentException("Source attempt needs at most one section of pinned rows");
        if (owner == null) return;
        var batch = owner.batch();
        if (!batch.plan().equals(id.preparation())) throw new IllegalArgumentException("Material page belongs to another preparation");
        BitSet sources = new BitSet(4096);
        for (int index : indices) {
            if (index < 0 || index >= batch.size()) throw new IllegalArgumentException("Invalid source material row");
            BlockPos pos = batch.move(index).source();
            int local = PreparedSourceProgress.local(pos);
            if (pos.getX() >> 4 != chunk.getPos().x || pos.getZ() >> 4 != chunk.getPos().z
                    || pos.getY() >> 4 != sectionY || sources.get(local))
                throw new IllegalArgumentException("Material rows do not form one unique source section");
            sources.set(local);
        }
    }

    private static Result result(State state) { return new Result(state, new BitSet()); }
    private PreparedSourceCommit() {}
}
