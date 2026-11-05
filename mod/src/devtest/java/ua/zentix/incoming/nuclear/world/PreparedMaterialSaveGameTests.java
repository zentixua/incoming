package ua.zentix.incoming.nuclear.world;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.registry.ModAttachments;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Native save events and fresh attachment reopen; no claim of an abrupt-crash transaction. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedMaterialSaveGameTests {
    @GameTest(template = "pad", batch = "nuke_prepared_save_boundary", timeoutTicks = 200, skyAccess = true)
    public static void nativeCleanSaveIncludesTheLatestRevisionBehindADelayedWriter(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var gate = new CountDownLatch(1);
        StrikeGameTests.afterTest(h, gate::countDown);
        var world = install(h, level);
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_material_save");
        var path = level.getDataStorage().dataFolder.toPath().resolve("prepared-save-fixture-" + UUID.randomUUID());
        var pages = new RuinPages[1];
        var store = new PreparedMaterialStore(info, path, new PreparedMaterialStore.Limits(1, 1, 1, 1, 65536),
                (storage, folder) -> pages[0] = new RuinPages(storage, folder));
        var ownerField = NuclearWorld.class.getDeclaredField("preparedMaterials");
        ownerField.setAccessible(true);
        ownerField.set(world, store);
        var boundary = new AtomicBoolean();
        boolean[] releaseOnSave = {false};
        Consumer<LevelEvent.Save> listener = event -> {
            if (event.getLevel() != level || !releaseOnSave[0]) return;
            var counts = store.counters();
            boundary.set(counts.writes() == 1 && counts.pending() == 1);
            CompletableFuture.runAsync(gate::countDown, Util.ioPool());
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
        var batch = batch(UUID.randomUUID(), true);
        var key = PreparedMaterialStore.Key.of(batch);
        var reopened = new CompletableFuture<?>[1];
        int[] stage = {0};
        h.succeedWhen(() -> {
            if (stage[0] == 0) {
                h.assertTrue(store.request(key).state() == PreparedMaterialStore.State.ABSENT, "Waiting for page absence");
                h.assertTrue(store.admit(batch), "Could not admit the native-save fixture");
                pages[0].write(ChunkPos.asLong(99, 99), () -> {
                    try {
                        if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Native save did not release its writer");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(error);
                    }
                    return cargo(99);
                });
                batch.capture(0, cargo(7)); batch.removed(0);
                level.save(null, false, false);
                h.assertTrue(store.counters().writes() == 1 && !store.evict(key),
                        "Native autosave did not enqueue the live owner, or waited for its held writer");
                // A new revision after autosave must be included by the next actual clean-save boundary.
                batch.placed(0);
                batch.capture(1, cargo(19)); batch.removed(1);
                releaseOnSave[0] = true;
                level.save(null, true, false);
                releaseOnSave[0] = false;
                h.assertTrue(boundary.get(), "Clean save failed to capture the latest revision while the old writer was held");
                h.assertTrue(store.evict(key), "Native flushing save returned before the latest page receipt");
                world.closePreparedMaterials().join();
                var reader = new RuinPages(info, path.resolve("plan-" + key.plan()).resolve("section-" + batch.sectionY()));
                reopened[0] = reader.read(key.chunk()).thenCompose(tag -> reader.close().thenApply(unused -> {
                    var restored = PreparedMaterialBatch.decode(tag.orElseThrow());
                    if (restored.revision() != batch.revision() || restored.phase(0) != PreparedMaterialBatch.Phase.PLACED
                            || restored.cargo(0) != null || restored.phase(1) != PreparedMaterialBatch.Phase.REMOVED
                            || restored.cargo(1).getInt("count") != 19)
                        throw new IllegalStateException("Native save lost the latest phase, receipt or exact cargo");
                    return null;
                }));
                stage[0] = 1;
            }
            h.assertTrue(reopened[0].isDone(), "Waiting for the cleanly saved page to reopen");
            reopened[0].join();
        });
    }

    @GameTest(template = "pad", batch = "nuke_prepared_save_dimensions", timeoutTicks = 200, skyAccess = true)
    public static void lazyDimensionOwnersSaveCloseAndReopenTheSameKeyIndependently(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var nether = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "Native Nether level missing");
        var worlds = new NuclearWorld[] {install(h, level), install(h, nether)};
        var levels = new ServerLevel[] {level, nether};
        var ownerField = NuclearWorld.class.getDeclaredField("preparedMaterials");
        ownerField.setAccessible(true);
        level.save(null, true, false);
        nether.save(null, true, false);
        h.assertTrue(ownerField.get(worlds[0]) == null && ownerField.get(worlds[1]) == null,
                "Save-only dimensions allocated a prepared owner");
        var stores = new PreparedMaterialStore[] {
                worlds[0].getPreparedMaterials(level), worlds[1].getPreparedMaterials(nether)};
        var plan = UUID.randomUUID();
        var batches = new PreparedMaterialBatch[] {batch(plan, false), batch(plan, false)};
        var key = PreparedMaterialStore.Key.of(batches[0]);
        int[] stage = {0};
        h.succeedWhen(() -> {
            if (stage[0] == 0) {
                for (var store : stores)
                    h.assertTrue(store.request(key).state() == PreparedMaterialStore.State.ABSENT, "Waiting for dimension-local absence");
                for (int i = 0; i < stores.length; i++) {
                    h.assertTrue(stores[i].admit(batches[i]), "Dimension page was not absent");
                    batches[i].capture(0, cargo(i == 0 ? 23 : 47)); batches[i].removed(0);
                    levels[i].save(null, true, false);
                    h.assertTrue(stores[i].evict(key), "Dimension clean save did not await its page");
                }
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                // Reload then make one final dirty revision. The production stop helper must capture it.
                for (var store : stores)
                    h.assertTrue(store.request(key).state() == PreparedMaterialStore.State.READY, "Waiting for saved dimension page");
                stores[0].get(key).placed(0);
                for (int i = 0; i < worlds.length; i++) {
                    worlds[i].closePreparedMaterials().join();
                    boolean refused = false;
                    try { worlds[i].getPreparedMaterials(levels[i]); }
                    catch (IllegalStateException expected) { refused = true; }
                    h.assertTrue(refused, "Stopping dimension reopened its material owner");
                    // Vanilla performs another level save after ServerStoppingEvent: closed owner is skipped.
                    levels[i].save(null, true, false);
                    worlds[i] = new NuclearWorld();
                    levels[i].setData(ModAttachments.NUCLEAR_WORLD, worlds[i]);
                    stores[i] = worlds[i].getPreparedMaterials(levels[i]);
                }
                stage[0] = 2;
            }
            for (var store : stores)
                h.assertTrue(store.request(key).state() == PreparedMaterialStore.State.READY, "Waiting for fresh attachment reopen");
            h.assertTrue(stores[0].get(key).phase(0) == PreparedMaterialBatch.Phase.PLACED
                    && stores[0].get(key).cargo(0) == null
                    && stores[1].get(key).phase(0) == PreparedMaterialBatch.Phase.REMOVED
                    && stores[1].get(key).cargo(0).getInt("count") == 47,
                    "Dimension paths collided or stop lost the final phase/cargo");
            var relative = "incoming-prepared-material-v5/plan-" + plan + "/section-4/r.0.0.mca";
            var first = level.getDataStorage().dataFolder.toPath().resolve(relative);
            var second = nether.getDataStorage().dataFolder.toPath().resolve(relative);
            try {
                h.assertTrue(Files.exists(first) && Files.exists(second) && !Files.isSameFile(first, second),
                        "Dimension metadata did not produce independent region files");
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        });
    }

    private static NuclearWorld install(GameTestHelper h, ServerLevel level) {
        var previous = level.hasData(ModAttachments.NUCLEAR_WORLD) ? level.getData(ModAttachments.NUCLEAR_WORLD) : null;
        var owner = new NuclearWorld();
        level.setData(ModAttachments.NUCLEAR_WORLD, owner);
        StrikeGameTests.afterTest(h, () -> {
            try { level.getData(ModAttachments.NUCLEAR_WORLD).closePreparedMaterials().join(); }
            finally {
                if (previous == null) level.removeData(ModAttachments.NUCLEAR_WORLD);
                else level.setData(ModAttachments.NUCLEAR_WORLD, previous);
            }
        });
        return owner;
    }

    private static PreparedMaterialBatch batch(UUID plan, boolean two) {
        var at = new BlockPos(17, 64, 17);
        var first = new PreparedMaterialBatch.Move(at.east(64), at, Blocks.BARREL.defaultBlockState(),
                Blocks.BARREL.defaultBlockState(), 100, 140, 1, 600);
        var second = new PreparedMaterialBatch.Move(at.east(64).above(), at.above(), Blocks.BARREL.defaultBlockState(),
                Blocks.BARREL.defaultBlockState(), 101, 141, 1, 600);
        return new PreparedMaterialBatch(plan, new ChunkPos(at), two ? List.of(first, second) : List.of(first));
    }

    private static CompoundTag cargo(int count) { var tag = new CompoundTag(); tag.putInt("count", count); return tag; }

    private PreparedMaterialSaveGameTests() {}
}

