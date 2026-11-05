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
        var batch = batch(UUID.randomUUID(), 64, true);
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
    public static void planAndVerticalSectionPagesReopenWithinOneHotSlotAndHandle(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_material_bounds");
        var path = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/material-bounds-" + UUID.randomUUID());
        var store = new PreparedMaterialStore(info, path, new PreparedMaterialStore.Limits(1, 1, 1, 1, 65536));
        StrikeGameTests.afterTest(h, store::close);
        var planA = new UUID(1, 0);
        var planB = new UUID(0, 1);
        h.assertTrue(!planA.equals(planB) && planA.hashCode() == planB.hashCode(), "Fixture UUIDs must collide only in hash");
        var batches = List.of(batch(planA, 64, false), batch(planA, 80, false), batch(planB, 64, false));
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
                if (cursor[0] < batches.size()) { stage[0] = 0; h.assertTrue(false, "Continue next plan/section"); }
                cursor[0] = batches.size() - 1; stage[0] = 2;
            }
            if (stage[0] == 2) {
                var key = PreparedMaterialStore.Key.of(batches.get(cursor[0]));
                h.assertTrue(store.request(key).state() == PreparedMaterialStore.State.READY, "Waiting for cold plan/section reload");
                h.assertTrue(store.get(key).cargo(0).getInt("count") == cursor[0] + 1, "Plan UUID or vertical-section key collided");
                h.assertTrue(store.evict(key), "Reloaded clean page could not retire");
                cursor[0]--;
                if (cursor[0] >= 0) h.assertTrue(false, "Continue previous plan/section");
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
        var batch = batch(UUID.randomUUID(), 64, false);
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
        var batch = batch(UUID.randomUUID(), 64, false);
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

    @GameTest(template = "pad", batch = "nuke_prepared_store_lease", timeoutTicks = 200, skyAccess = true)
    public static void consumerLeaseRetainsDirtyOwnershipUntilReleaseAndCloseRefusesLiveConsumers(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_material_lease");
        var path = level.getDataStorage().dataFolder.toPath().resolve("material-lease-" + UUID.randomUUID());
        var store = new PreparedMaterialStore(info, path, new PreparedMaterialStore.Limits(1, 1, 1, 1, 65536));
        var lease = new PreparedMaterialStore.Lease[1];
        StrikeGameTests.afterTest(h, () -> {
            if (lease[0] != null) lease[0].close();
            store.close();
        });
        var plan = UUID.randomUUID();
        var first = batch(plan, 64, true);
        var second = batch(plan, 80, false);
        var a = PreparedMaterialStore.Key.of(first);
        var b = PreparedMaterialStore.Key.of(second);
        var fence = new CompletableFuture<?>[1];
        int[] stage = {0};
        h.succeedWhen(() -> {
            if (stage[0] == 0) {
                h.assertTrue(store.request(a).state() == PreparedMaterialStore.State.ABSENT, "Waiting for first page absence");
                h.assertTrue(store.admit(first), "Could not admit first page");
                first.capture(0, cargo(5)); first.removed(0);
                fence[0] = store.flush(); stage[0] = 1;
            }
            if (stage[0] == 1) {
                h.assertTrue(fence[0].isDone(), "Waiting for initial clean page"); fence[0].join();
                lease[0] = store.pin(a);
                h.assertTrue(lease[0] != null, "Ready page could not be leased");
                h.assertTrue(store.request(b).state() == PreparedMaterialStore.State.WAITING
                        && store.get(a) == lease[0].batch() && store.counters().residents() == 1,
                        "Another request evicted the leased consumer's owner");
                lease[0].batch().placed(0);
                lease[0].batch().capture(1, cargo(61)); lease[0].batch().removed(1);
                fence[0] = store.flush(); stage[0] = 2;
            }
            if (stage[0] == 2) {
                h.assertTrue(fence[0].isDone(), "Waiting for leased page's new revision"); fence[0].join();
                h.assertTrue(!store.evict(a) && store.request(b).state() == PreparedMaterialStore.State.WAITING,
                        "A successful write retired a page still owned by its consumer");
                lease[0].close(); lease[0].close();
                boolean refused = false;
                try { lease[0].batch(); } catch (IllegalStateException expected) { refused = true; }
                h.assertTrue(refused, "Closed lease still exposed its mutable owner");
                stage[0] = 3;
            }
            if (stage[0] == 3) {
                h.assertTrue(store.request(b).state() == PreparedMaterialStore.State.ABSENT,
                        "Released page/handle did not permit the second request to progress");
                h.assertTrue(store.admit(second), "Could not admit second page after consumer release");
                fence[0] = store.flush(); stage[0] = 4;
            }
            if (stage[0] == 4) {
                h.assertTrue(fence[0].isDone(), "Waiting for second page receipt"); fence[0].join();
                h.assertTrue(store.request(a).state() == PreparedMaterialStore.State.READY, "Waiting for evicted first page reload");
                lease[0] = store.pin(a);
                var restored = lease[0].batch();
                h.assertTrue(restored.revision() == first.revision() && restored.phase(0) == PreparedMaterialBatch.Phase.PLACED
                        && restored.cargo(0) == null && restored.phase(1) == PreparedMaterialBatch.Phase.REMOVED
                        && restored.cargo(1).getInt("count") == 61, "Consumer mutation escaped durable ownership");
                var refusedClose = store.close();
                h.assertTrue(refusedClose.isCompletedExceptionally() && store.counters().residents() == 1
                        && lease[0].batch() == restored, "Close waited silently or discarded its leased owner");
                lease[0].close();
                fence[0] = store.close(); stage[0] = 5;
            }
            h.assertTrue(fence[0].isDone(), "Waiting for close retry after consumer release"); fence[0].join();
        });
    }

    private static PreparedMaterialBatch batch(UUID plan, int y, boolean two) {
        var target = new BlockPos(112, y, -64);
        var first = new PreparedMaterialBatch.Move(target.east(64), target, Blocks.BARREL.defaultBlockState(),
                Blocks.BARREL.defaultBlockState(), 100, 140, 1, 600);
        var second = new PreparedMaterialBatch.Move(target.east(64).above(), target.above(), Blocks.BARREL.defaultBlockState(),
                Blocks.BARREL.defaultBlockState(), 101, 141, 1, 600);
        return new PreparedMaterialBatch(plan, new ChunkPos(target), two ? List.of(first, second) : List.of(first));
    }

    private static CompoundTag cargo(int count) {
        var tag = new CompoundTag(); tag.putInt("count", count); return tag;
    }

    private PreparedMaterialStoreGameTests() {}
}
