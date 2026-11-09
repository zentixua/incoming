package ua.zentix.incoming.work;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

/**
 * Preserve the initialized light ring of our edits through vanilla's save pipeline. An ImposterProtoChunk forwards
 * setUnsaved to its wrapped LevelChunk but always reports isUnsaved=false; vanilla would discard its corrections.
 * Retaining the already FULL root retains its initialized ring without loading more chunks. FULL promotion of that
 * ring was measured to recursively expand blackout loading (49 batches / 80 promotions for one city root).
 * One-time legacy recovery checks saved FULL neighbors in the inner ring, temporarily borrowing them at FULL to obtain
 * another initialized ring of source context. Borrowed loads cannot recursively start recovery. Otherwise a real
 * source just outside the inspected square could lose its spill when a phantom peak is removed inside it.
 * Checks and wrapped-neighbor saves are bounded work units; no tick-thread access waits for loading.
 */
public final class BlockLighting {
    /** Positions only, no chunk references or tickets. One repair admits at most nine FULL leases per world. */
    public static final int PENDING_REPAIRS = 128;
    private static final TicketType<ChunkPos> HOLD = TicketType.create("incoming_block_light", Comparator.comparingLong(ChunkPos::toLong));
    private final Long2ObjectOpenHashMap<Batch> batches = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap leases = new Long2IntOpenHashMap();
    private final UnitQueue queue = new UnitQueue();
    private final LongLinkedOpenHashSet repairs = new LongLinkedOpenHashSet();
    private Batch activeRepair;
    private boolean admissionQueued, repairLogPending;
    private long admitted, droppedUnloaded, droppedCapacity, marked;
    private int peakPending;
    private boolean stopping;
    private long completed, recovered, workNanos, recoveryNanos, checks, readyNanos;
    private int peakHeld;

    /** Cumulative per-world diagnostics; no timing thresholds affect correctness. */
    public record Measurements(long completed, long recovered, long workNanos, long recoveryNanos,
                               long checks, long readyNanos, int peakHeld, long admitted, long droppedUnloaded,
                               long droppedCapacity, long marked, int peakPending) {}
    public Measurements measurements() {
        return new Measurements(completed, recovered, workNanos, recoveryNanos, checks, readyNanos, peakHeld,
                admitted, droppedUnloaded, droppedCapacity, marked, peakPending);
    }

    public static BlockLighting get(ServerLevel level) { return level.getData(ModAttachments.BLOCK_LIGHTING); }

    /** Call in the same server-thread unit as our block changes, before the owner's load ticket can be released. */
    public static void changed(ServerLevel level, ChunkPos pos) {
        get(level).request(level, pos, false);
    }

    public static void changed(ServerLevel level, BlockPos pos) { changed(level, new ChunkPos(pos)); }

    /** Detached copies (LODs and measurement fixtures) have no live light ring to retain. */
    public static void changed(ServerLevel level, LevelChunk chunk) {
        if (ua.zentix.incoming.nuclear.world.NuclearTickets.inMemory(level, chunk.getPos().toLong()) == chunk)
            changed(level, chunk.getPos());
    }

    private void request(ServerLevel level, ChunkPos pos, boolean repair) {
        // Sable owns plot chunks and their storage; its ticket guard cannot create a vanilla light ring there.
        if (ua.zentix.incoming.compat.SubLevels.inPlotGrid(level, pos)) return;
        Batch batch = batches.get(pos.toLong());
        if (batch == null) {
            batch = new Batch(pos);
            batches.put(pos.toLong(), batch);
            // The producer already owns this chunk; keep its initialized light ring alive through the save fence.
            hold(level, pos);
            queue.add(level, batch);
        }
        batch.revision++;
        batch.repair |= repair;
        if (repair) activeRepair = batch;
    }

    /** Recover pre-fix spill once in saved scar/outage contexts; borrowed loads do not recursively admit recovery. */
    public static void onLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        if (ua.zentix.incoming.compat.SubLevels.inPlotGrid(level, chunk.getPos())) return;
        if (chunk.hasData(ModAttachments.LIGHT_REPAIRED) && chunk.getData(ModAttachments.LIGHT_REPAIRED)) return;
        if (event.isNewChunk()) {
            chunk.setData(ModAttachments.LIGHT_REPAIRED, true);
            return; // No pre-fix saved field, even if our first scar/outage arrives only after generation.
        }
        ChunkPos pos = chunk.getPos();
        if ((!chunk.hasData(ModAttachments.CHUNK_SCAR) || chunk.getData(ModAttachments.CHUNK_SCAR) == 0) && !PowerGrid.get(level).covered(pos.x, pos.z)) return;
        BlockLighting work = get(level);
        if (!work.stopping && (!work.leases.containsKey(pos.toLong()) || work.batches.containsKey(pos.toLong())))
            work.offerRepair(level, pos);
    }

    private static boolean repaired(LevelChunk chunk) {
        return chunk.hasData(ModAttachments.LIGHT_REPAIRED) && chunk.getData(ModAttachments.LIGHT_REPAIRED);
    }

    private static long distance(ServerLevel level, long key) {
        long nearest = Long.MAX_VALUE;
        for (var player : level.players()) {
            long dx = (long) player.chunkPosition().x - ChunkPos.getX(key);
            long dz = (long) player.chunkPosition().z - ChunkPos.getZ(key);
            nearest = Math.min(nearest, dx * dx + dz * dz);
        }
        return nearest;
    }

    private void offerRepair(ServerLevel level, ChunkPos pos) {
        long key = pos.toLong();
        if (ua.zentix.incoming.compat.SubLevels.inPlotGrid(level, pos)
                || repairs.contains(key) || activeRepair != null && activeRepair.center.equals(pos)) return;
        repairLogPending = true;
        if (repairs.size() == PENDING_REPAIRS) {
            long farthest = repairs.firstLong(), far = distance(level, farthest);
            for (long candidate : repairs) {
                long d = distance(level, candidate);
                if (d > far) { farthest = candidate; far = d; }
            }
            droppedCapacity++;
            if (distance(level, key) >= far) return;
            repairs.remove(farthest); // No marker: its next load may offer it again.
        }
        repairs.add(key);
        peakPending = Math.max(peakPending, repairs.size());
    }

    private void admit(ServerLevel level) {
        long closest = 0, near = Long.MAX_VALUE;
        boolean found = false;
        var it = repairs.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null || repaired(chunk)) {
                it.remove();
                if (chunk == null) droppedUnloaded++;
                continue;
            }
            long d = distance(level, key);
            if (!found || d < near) { closest = key; near = d; found = true; }
        }
        if (!found) return;
        repairs.remove(closest);
        admitted++;
        request(level, new ChunkPos(closest), true); // First repair-owned ticket, only at admission while still FULL.
    }

    private void drainLog(ServerLevel level) {
        if (!repairLogPending || !repairs.isEmpty() || activeRepair != null || admissionQueued) return;
        repairLogPending = false;
        ua.zentix.incoming.Incoming.LOG.info("Block light recovery drained in {}: admitted {}, completed {}, marked {}, dropped unloaded {}, capacity {}, checks {}, work {} ms, pending peak {}",
                level.dimension().location(), admitted, recovered, marked, droppedUnloaded, droppedCapacity, checks,
                String.format(java.util.Locale.ROOT, "%.2f", recoveryNanos / 1e6), peakPending);
    }

    private void hold(ServerLevel level, ChunkPos pos) {
        if (leases.addTo(pos.toLong(), 1) == 0) level.getChunkSource().addRegionTicket(HOLD, pos, 0, pos);
        peakHeld = Math.max(peakHeld, leases.size());
    }

    private void release(ServerLevel level, ChunkPos pos) {
        if (leases.addTo(pos.toLong(), -1) == 1) {
            leases.remove(pos.toLong());
            level.getChunkSource().removeRegionTicket(HOLD, pos, 0, pos);
        }
    }

    public static void work(ServerLevel level, WorkClock clock) {
        if (!level.hasData(ModAttachments.BLOCK_LIGHTING)) return;
        BlockLighting work = get(level);
        if (!work.stopping && work.activeRepair == null && !work.repairs.isEmpty() && !work.admissionQueued) {
            work.admissionQueued = true;
            work.queue.add(level, new UnitQueue.Job() {
                @Override public boolean step(ServerLevel world) { if (!work.stopping) work.admit(world); return false; }
                @Override public void end(ServerLevel world) { work.admissionQueued = false; }
                @Override public String describe() { return "legacy light admission"; }
            });
        }
        work.queue.work(level, clock);
        work.drainLog(level);
    }

    public boolean idle() { return batches.isEmpty() && repairs.isEmpty() && !admissionQueued; }
    public int heldChunks() { return leases.size(); }
    public int pendingRepairs() { return repairs.size(); }
    public int activeRepairs() { return activeRepair == null ? 0 : 1; }
    public boolean repairPending(ChunkPos pos) { return repairs.contains(pos.toLong()); }
    /** Admitted repair center, or null when no repair owns tickets. */
    public ChunkPos repairCenter() { return activeRepair == null ? null : activeRepair.center; }

    /** Pending phase diagnostics for stalled work; only inspected on demand. */
    public String pending(ServerLevel level) {
        return "queued repairs=" + repairs.size() + "; " + batches.values().stream().limit(12).map(b -> b.center + " repair=" + b.repair
                + " borrow=" + b.borrowNext + " scan=" + b.repairChunk + "/" + b.section
                + " save=" + b.saveNext + " revision=" + b.revision + "/" + b.fencedRevision
                + " rootFull=" + (level.getChunkSource().getChunkNow(b.center.x, b.center.z) != null)
                + " fence=" + (b.fence == null ? "none" : b.fence.isDone()))
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private final class Batch implements UnitQueue.Job {
        final ChunkPos center;
        int section, repairChunk, revision, fencedRevision, saveNext;
        boolean repair, scanned;
        final boolean[] borrowed = new boolean[9];
        int borrowNext;
        long paceDeniedAt = Long.MIN_VALUE;
        final long born = System.nanoTime();
        boolean fullSeen, succeeded;
        long ownNanos;
        CompletableFuture<Void> fence;
        Batch(ChunkPos center) { this.center = center; }
        ChunkPos at(int i) { return new ChunkPos(center.x + i % 3 - 1, center.z + i / 3 - 1); }

        @Override public boolean dynamicReadiness() { return true; }

        private net.minecraft.world.level.chunk.ChunkAccess lightChunk(ServerLevel level, ChunkPos p) {
            var holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(p.toLong());
            return holder == null ? null : holder.getChunkIfPresentUnchecked(net.minecraft.world.level.chunk.status.ChunkStatus.INITIALIZE_LIGHT);
        }

        private LevelChunk wrapped(ServerLevel level, ChunkPos p) {
            var chunk = lightChunk(level, p);
            if (chunk instanceof net.minecraft.world.level.chunk.ImposterProtoChunk proxy) return proxy.getWrapped();
            return chunk instanceof LevelChunk full ? full : null;
        }

        @Override public boolean ready(ServerLevel level) {
            if (level.getChunkSource().getChunkNow(center.x, center.z) == null) return false;
            for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++)
                if (lightChunk(level, new ChunkPos(center.x + x, center.z + z)) == null) return false;
            if (repair && borrowNext < 9) return stopping || paceDeniedAt != level.getGameTime();
            if (repair) for (int i = 0; i < 9; i++) if (borrowed[i]) {
                ChunkPos p = at(i);
                if (level.getChunkSource().getChunkNow(p.x, p.z) == null) return false;
                for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++)
                    if (lightChunk(level, new ChunkPos(p.x + x, p.z + z)) == null) return false;
            }
            if (!fullSeen) { readyNanos += System.nanoTime() - born; fullSeen = true; }
            return fence == null || fence.isDone();
        }

        @Override public boolean step(ServerLevel level) {
            long began = System.nanoTime();
            try { return advance(level); }
            finally { long took = System.nanoTime() - began; ownNanos += took; workNanos += took; }
        }

        private boolean advance(ServerLevel level) {
            // Legacy recovery checks a whole ring, so it needs one more initialized ring as source context.
            // Borrow FULL only for this one-time repair; borrowed loads do not recursively start recovery.
            if (repair && borrowNext < 9) {
                int i = borrowNext;
                if (i != 4) {
                    ChunkPos p = at(i);
                    // Only wrapped FULL neighbors suffer the proxy-save loss. Real ProtoChunks save dirty light
                    // normally; promoting them at the explored-world edge would generate terrain for this repair.
                    if (wrapped(level, p) != null) {
                        if (!stopping && !ChunkLoadPace.of(level.getServer()).tryStart(level, p.x, p.z)) {
                            paceDeniedAt = level.getGameTime();
                            return true;
                        }
                        hold(level, p); borrowed[i] = true;
                    }
                }
                borrowNext++;
                return true;
            }
            // A step can start a new asynchronous phase.
            if (!ready(level)) return true;
            if (repair && !scanned) {
                if (repairChunk != 4 && !borrowed[repairChunk]) {
                    scanned = ++repairChunk == 9;
                    return true;
                }
                ChunkPos target = at(repairChunk);
                var chunk = lightChunk(level, target);
                var layers = level.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK);
                while (section < chunk.getSectionsCount()) {
                    var data = layers.getDataLayerData(net.minecraft.core.SectionPos.of(target, chunk.getSectionYFromSectionIndex(section)));
                    if (data != null && !data.isEmpty()) break;
                    section++;
                }
                if (section < chunk.getSectionsCount()) {
                    // One section per unit, including air sections carrying stale arrays.
                    // Recheck boundary spill and unsupported interior maxima (old missed-source checks).
                    int y0 = chunk.getSectionYFromSectionIndex(section++) * 16;
                    BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                    var light = level.getChunkSource().getLightEngine();
                    for (int y = y0; y < y0 + 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        p.set(target.getMinBlockX() + x, y, target.getMinBlockZ() + z);
                        int value = level.getBrightness(LightLayer.BLOCK, p);
                        if (value == 0) continue;
                        boolean peak = true;
                        if (x != 0 && x != 15 && z != 0 && z != 15) {
                            for (var direction : net.minecraft.core.Direction.values()) {
                                if (level.getBrightness(LightLayer.BLOCK, p.relative(direction)) > value) { peak = false; break; }
                            }
                        }
                        if (peak) { light.checkBlock(p); checks++; }
                    }
                    revision++;
                    return true;
                }
                section = 0;
                scanned = ++repairChunk == 9;
                return true;
            }
            if (fence == null || revision != fencedRevision) {
                var waits = new ArrayList<CompletableFuture<?>>();
                for (int i = 0; i < (repair ? 25 : 9); i++) {
                    ChunkPos p = saveAt(i);
                    if (lightChunk(level, p) != null)
                        waits.add(level.getChunkSource().getLightEngine().waitForPendingTasks(p.x, p.z));
                }
                fencedRevision = revision;
                saveNext = 0;
                fence = CompletableFuture.allOf(waits.toArray(CompletableFuture<?>[]::new));
                return true;
            }
            fence.join(); // ready() checked completion; this never waits.
            if (saveNext < (repair ? 25 : 9)) {
                ChunkPos p = saveAt(saveNext++);
                LevelChunk chunk = wrapped(level, p);
                if (chunk != null) {
                    // The arrays are published; do not depend on still queued dirty notifications at world exit.
                    chunk.setUnsaved(true);
                    if (level.getChunkSource().getChunkNow(p.x, p.z) == null) {
                        // A radius-zero root retains this initialized neighbor but its proxy cannot save it.
                        // Vanilla serialization/events/async I/O remain authoritative (including ChunkSaves).
                        level.getChunkSource().chunkMap.save(chunk);
                    }
                }
                return true;
            }
            if (repair) {
                // Every scanned FULL chunk has passed the same checks and fence, not just the admission root.
                for (int i = 0; i < 9; i++) if (i == 4 || borrowed[i]) {
                    ChunkPos p = at(i);
                    LevelChunk chunk = level.getChunkSource().getChunkNow(p.x, p.z);
                    if (!repaired(chunk)) { chunk.setData(ModAttachments.LIGHT_REPAIRED, true); marked++; }
                    chunk.setUnsaved(true);
                }
            }
            succeeded = true;
            return false;
        }

        private ChunkPos saveAt(int i) {
            return repair ? new ChunkPos(center.x + i % 5 - 2, center.z + i / 5 - 2) : at(i);
        }

        @Override public void end(ServerLevel level) {
            if (succeeded) { completed++; if (repair) { recovered++; recoveryNanos += ownNanos; } }
            batches.remove(center.toLong());
            if (activeRepair == this) activeRepair = null;
            for (int i = 0; i < 9; i++) if (borrowed[i]) release(level, at(i));
            release(level, center);
        }
        @Override public String describe() { return "block light " + center; }
    }

    /** Drain already committed edits before vanilla saves worlds; never initiate unrelated recovery at shutdown. */
    public static void onStopping(ServerStoppingEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            finish(level);
            if (level.hasData(ModAttachments.BLOCK_LIGHTING)) get(level).stopping = true;
        }
    }

    /** Same world-exit drain, also exercised with pending leases by the unload/reload regression. */
    public static void finish(ServerLevel level) {
        if (!level.hasData(ModAttachments.BLOCK_LIGHTING)) return;
        BlockLighting work = get(level);
        work.stopping = true;
        work.repairs.clear(); // No committed edit and no ticket; let a future load offer legacy work again.
        WorkClock clock = new WorkClock();
        long deadline = System.nanoTime() + ua.zentix.incoming.util.StopDrain.LIMIT_NANOS;
        while (!work.idle()) {
            if (System.nanoTime() > deadline) {
                ua.zentix.incoming.Incoming.LOG.error("Block lighting did not drain before shutdown: {} batches", work.batches.size());
                work.queue.clear(level);
                break;
            }
            clock.start(1_000_000, () -> true, 0);
            work.queue.work(level, clock);
            level.getChunkSource().runDistanceManagerUpdates();
            level.getChunkSource().getLightEngine().tryScheduleUpdate();
            if (!level.getChunkSource().pollTask()) java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        }
        work.stopping = false;
        work.drainLog(level);
    }
}
