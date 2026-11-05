package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Native page-store controls. World mutation and cross-chunk clean restart are separate integration tests. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedMaterialStoreGameTests {
    @GameTest(template = "pad", batch = "nuke_prepared_store_generation", timeoutTicks = 200, skyAccess = true)
    public static void dirtyGenerationsCoalesceWithoutFreezingOrEvictingTheLiveOwner(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_material_store");
        var path = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/material-generation-" + UUID.randomUUID());
        var pages = new RuinPages[1];
        var store = new PreparedMaterialStore(info, path, new PreparedMaterialStore.Limits(1, 1, 1, 1, 1L << 20),
                (storage, folder) -> pages[0] = new RuinPages(storage, folder));
        var batch = batch(4, 64, true);
        var key = PreparedMaterialStore.Key.of(batch);
        var gate = new CountDownLatch(1);
        StrikeGameTests.afterTest(h, () -> { gate.countDown(); store.close(); });
        int[] step = {0};
        var firstFlush = new CompletableFuture<?>[1];
        var latestFlush = new CompletableFuture<?>[1];
        var close = new CompletableFuture<?>[1];
        h.succeedWhen(() -> {
            if (step[0] == 0) {
                h.assertTrue(store.request(key).state() == PreparedMaterialStore.State.ABSENT, "Waiting for absence receipt");
                h.assertTrue(store.admit(batch), "Failed to admit an absent material page");
                pages[0].write(ChunkPos.asLong(99, 99), () -> {
                    try {
                        if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture did not release page writer");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(error);
                    }
                    return cargo(99);
                });
                batch.capture(0, cargo(7)); batch.removed(0);
                var first = store.save(key);
                batch.placed(0);
                firstFlush[0] = store.flush();
                batch.capture(1, cargo(11)); batch.removed(1);
                latestFlush[0] = store.flush();
                h.assertTrue(!first.isDone() && !latestFlush[0].isDone(), "Pending writer failed to hold the flush fence");
                h.assertTrue(!store.evict(key), "An old in-flight revision evicted the current owner");
                h.assertTrue(store.counters().writes() == 1 && store.counters().pending() == 1,
                        "Coalescing retained more than one pending snapshot");
                gate.countDown(); step[0] = 1;
            }
            if (step[0] == 1) {
                h.assertTrue(latestFlush[0].isDone() && firstFlush[0].isDone(), "Waiting for latest-generation flush");
                latestFlush[0].join(); firstFlush[0].join();
                h.assertTrue(store.counters().writesStarted() == 2, "Superseded pending generation was still written");
                h.assertTrue(store.evict(key), "Successful current revision was not evictable");
                step[0] = 2;
            }
            if (step[0] == 2) {
                h.assertTrue(store.request(key).state() == PreparedMaterialStore.State.READY, "Waiting for evicted page reload");
                var restored = store.get(key);
                h.assertTrue(restored.revision() == batch.revision() && restored.phase(0) == PreparedMaterialBatch.Phase.PLACED
                        && restored.cargo(0) == null && restored.phase(1) == PreparedMaterialBatch.Phase.REMOVED
                        && restored.cargo(1).getInt("count") == 11, "Reload lost current phase, terminal receipt or exact cargo");
                close[0] = store.close(); step[0] = 3;
            }
            h.assertTrue(close[0].isDone(), "Waiting for material store close");
            close[0].join();
        });
    }

    @GameTest(template = "pad", batch = "nuke_prepared_store_bounds", timeoutTicks = 200, skyAccess = true)
    public static void eventAndVerticalSectionPagesReopenWithinOneHotSlotAndHandle(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_material_bounds");
        var path = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/material-bounds-" + UUID.randomUUID());
        var store = new PreparedMaterialStore(info, path, new PreparedMaterialStore.Limits(1, 1, 1, 1, 65536));
        StrikeGameTests.afterTest(h, store::close);
        var batches = List.of(batch(5, 64, false), batch(5, 80, false), batch(6, 64, false));
        h.assertTrue(!store.admit(batches.getFirst()), "Unknown cold page was assumed absent");
        int[] cursor = {0}, stage = {0};
        var fence = new CompletableFuture<?>[1];
        h.succeedWhen(() -> {
            var counters = store.counters();
            h.assertTrue(counters.residents() <= 1 && counters.reads() <= 1 && counters.writes() <= 1
                    && counters.pending() <= 1 && counters.handles() <= 1, "Store exceeded an explicit hot/I/O/handle limit");
            if (stage[0] < 2) {
                var batch = batches.get(cursor[0]);
                var key = PreparedMaterialStore.Key.of(batch);
                if (stage[0] == 0) {
                    h.assertTrue(store.request(key).state() == PreparedMaterialStore.State.ABSENT, "Waiting for independent page absence");
                    h.assertTrue(store.admit(batch), "Cold page collision prevented admission");
                    batch.capture(0, cargo(cursor[0] + 1)); batch.removed(0);
                    fence[0] = store.flush(); stage[0] = 1;
                }
                h.assertTrue(fence[0].isDone(), "Waiting for bounded page write"); fence[0].join();
                h.assertTrue(store.evict(key), "Saved bounded page retained its hot slot");
                cursor[0]++;
                if (cursor[0] < batches.size()) { stage[0] = 0; h.assertTrue(false, "Continue next event/section"); }
                cursor[0] = batches.size() - 1; stage[0] = 2;
            }
            if (stage[0] == 2) {
                var key = PreparedMaterialStore.Key.of(batches.get(cursor[0]));
                h.assertTrue(store.request(key).state() == PreparedMaterialStore.State.READY, "Waiting for cold event/section reload");
                h.assertTrue(store.get(key).cargo(0).getInt("count") == cursor[0] + 1, "Event or vertical-section key collided");
                h.assertTrue(store.evict(key), "Reloaded clean page could not retire");
                cursor[0]--;
                if (cursor[0] >= 0) h.assertTrue(false, "Continue previous event/section");
                fence[0] = store.close(); stage[0] = 3;
            }
            h.assertTrue(fence[0].isDone(), "Waiting for bounded owner close"); fence[0].join();
        });
    }

    @GameTest(template = "pad", batch = "nuke_prepared_store_failure", timeoutTicks = 200, skyAccess = true)
    public static void failedWriteCannotEvictOrAcknowledgeItsMaterialOwner(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_material_failure");
        var path = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/material-store-failure-" + UUID.randomUUID());
        var pages = new RuinPages[1];
        var store = new PreparedMaterialStore(info, path, new PreparedMaterialStore.Limits(1, 1, 1, 1, 65536),
                (storage, folder) -> pages[0] = new RuinPages(storage, folder));
        StrikeGameTests.afterTest(h, store::close);
        var batch = batch(8, 64, false);
        var key = PreparedMaterialStore.Key.of(batch);
        int[] stage = {0};
        var closed = new CompletableFuture<?>[1];
        h.succeedWhen(() -> {
            if (stage[0] == 0) {
                h.assertTrue(store.request(key).state() == PreparedMaterialStore.State.ABSENT, "Waiting for failure-fixture page absence");
                h.assertTrue(store.admit(batch), "Failure fixture could not admit its page");
                batch.capture(0, cargo(31)); batch.removed(0);
                pages[0].close(); // Deterministic refusal by the real writer, not a fake successful receipt.
                var save = store.save(key);
                h.assertTrue(save.isCompletedExceptionally() && !store.evict(key), "Failed page was acknowledged or evicted");
                h.assertTrue(store.request(key).state() == PreparedMaterialStore.State.FAILED && batch.cargo(0).getInt("count") == 31,
                        "Write failure lost cargo or hid the owner failure");
                closed[0] = store.close(); stage[0] = 1;
            }
            h.assertTrue(closed[0].isDone(), "Waiting for failed owner close");
            h.assertTrue(closed[0].isCompletedExceptionally(), "Close concealed the failed material page");
        });
    }

    @GameTest(template = "pad", batch = "nuke_prepared_store_nbt_bound", timeoutTicks = 200, skyAccess = true)
    public static void smallLivePageBudgetAlsoFundsItsCodecEnvelope(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_material_nbt_bound");
        var path = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/material-nbt-bound-" + UUID.randomUUID());
        var store = new PreparedMaterialStore(info, path, new PreparedMaterialStore.Limits(1, 1, 1, 1, 512));
        StrikeGameTests.afterTest(h, store::close);
        var batch = batch(9, 64, false);
        var key = PreparedMaterialStore.Key.of(batch);
        int[] stage = {0};
        var fence = new CompletableFuture<?>[1];
        h.succeedWhen(() -> {
            if (stage[0] == 0) {
                h.assertTrue(store.request(key).state() == PreparedMaterialStore.State.ABSENT, "Waiting for small page absence");
                h.assertTrue(store.admit(batch), "Small live page was refused");
                batch.removed(0); batch.placed(0);
                long encoded = batch.snapshot().encode().sizeInBytes();
                h.assertTrue(batch.retainedBytes() <= 512 && encoded > 512,
                        "Fixture no longer distinguishes resident ownership from its NBT envelope");
                Incoming.LOG.info("PREPARED_STORE_NBT_BOUND resident={} encoded={} residentLimit=512", batch.retainedBytes(), encoded);
                fence[0] = store.flush(); stage[0] = 1;
            }
            if (stage[0] == 1) {
                h.assertTrue(fence[0].isDone(), "Waiting for small page write"); fence[0].join();
                h.assertTrue(store.evict(key), "Small current page was not evictable"); stage[0] = 2;
            }
            if (stage[0] == 2) {
                h.assertTrue(store.request(key).state() == PreparedMaterialStore.State.READY, "Waiting for small page reload");
                h.assertTrue(store.get(key).finished(), "Codec/header allowance rejected its writer's own terminal receipt");
                fence[0] = store.close(); stage[0] = 3;
            }
            h.assertTrue(fence[0].isDone(), "Waiting for small page close"); fence[0].join();
        });
    }

    private static PreparedMaterialBatch batch(int event, int y, boolean two) {
        var target = new BlockPos(112, y, -64);
        var first = new PreparedMaterialBatch.Move(target.east(64), target, Blocks.BARREL.defaultBlockState(),
                Blocks.BARREL.defaultBlockState(), 100, 140, 1, 600);
        var second = new PreparedMaterialBatch.Move(target.east(64).above(), target.above(), Blocks.BARREL.defaultBlockState(),
                Blocks.BARREL.defaultBlockState(), 101, 141, 1, 600);
        return new PreparedMaterialBatch(event, new ChunkPos(target), two ? List.of(first, second) : List.of(first));
    }

    private static CompoundTag cargo(int count) {
        var tag = new CompoundTag(); tag.putInt("count", count); return tag;
    }

    private PreparedMaterialStoreGameTests() {}
}
