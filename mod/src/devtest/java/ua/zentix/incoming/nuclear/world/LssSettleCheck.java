package ua.zentix.incoming.nuclear.world;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.store.LodStoreService;
import dev.vox.lss.common.store.SqliteLodStore;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.networking.server.RequestProcessingService;
import dev.vox.lss.networking.server.SectionSerializer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import ua.zentix.incoming.Incoming;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** SQLite baseline and stale-row assertions around the real saved-zone settlement test. Devtest only. */
final class LssSettleCheck {
    private final GameTestHelper h;
    private final RequestProcessingService service;
    private final LodStoreService store;
    private final String dimension;
    private final Map<Long, byte[]> intact = new LinkedHashMap<>();
    private CompletableFuture<Void> deletes;
    private boolean deposited;
    private long heapStart, heapPeak;
    private int confirmed;

    LssSettleCheck(GameTestHelper h) {
        this.h = h;
        service = LSSServerNetworking.getRequestService();
        h.assertTrue(service != null && service.getLodStore() != null, "LSS store not active");
        store = service.getLodStore();
        dimension = h.getLevel().dimension().location().toString();
    }

    void add(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        byte[] bytes = SectionSerializer.serializeColumn(h.getLevel(), chunk, pos.x, pos.z).serializedSections();
        service.getDirtyContentFilter().seed(dimension, pos.x, pos.z, bytes);
        intact.put(PositionUtil.packPosition(pos.x, pos.z), bytes);
    }

    boolean ready() {
        if (!deposited) {
            for (long pos : intact.keySet()) if (service.getDirtyTracker().isPending(dimension, pos)) return false;
            if (deletes == null) {
                var sqlite = (SqliteLodStore) store;
                var policy = sqlite.adoptedPolicy();
                deletes = sqlite.updatePolicy(policy.maxDbBytes(), policy.resweepSeconds(), policy.revision());
                return false;
            }
            if (!deletes.isDone()) return false;
            deletes.join();
            for (var e : intact.entrySet())
                h.assertTrue(store.deposit(dimension, e.getKey(), e.getValue(), LSSConstants.epochSeconds(), 0), "baseline refused");
            deposited = true;
        }
        for (var e : intact.entrySet()) {
            var hit = store.get(dimension, e.getKey());
            if (hit == null) return false;
            h.assertTrue(Arrays.equals(hit.sectionBytes(), e.getValue()), "baseline is not intact");
        }
        heapStart = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        heapPeak = heapStart;
        return true;
    }

    void sample() {
        heapPeak = Math.max(heapPeak, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    }

    void saved(ChunkPos pos) {
        long packed = PositionUtil.packPosition(pos.x, pos.z);
        h.assertTrue(store.get(dimension, packed) == null, "saved ruined chunk still has intact LSS row: " + pos);
        confirmed++;
    }

    void report() {
        Incoming.LOG.info("LSSSETTLE chunks={} staleRows=0 heapStartMiB={} heapPeakMiB={}", confirmed,
                heapStart / 1048576, heapPeak / 1048576);
    }
}
