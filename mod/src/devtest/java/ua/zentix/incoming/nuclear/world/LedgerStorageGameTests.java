package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real SavedData file wrapper, delayed NeoForge I/O, and cold dimension imports. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LedgerStorageGameTests {
    private LedgerStorageGameTests() {}

    private static void folder(net.minecraft.world.level.storage.DimensionDataStorage storage, java.io.File file) {
        try {
            var field = storage.getClass().getDeclaredField("dataFolder"); field.setAccessible(true); field.set(storage, file);
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }

    @GameTest(template = "pad", batch = "nuke_ledger_save", timeoutTicks = 1200, skyAccess = true)
    public static void delayedWrapperSaveOwnsTheLiveLedgerWithoutCopies(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        var ledger = new RuinMatter();
        var pieces = new ArrayList<RuinMatter.Piece>();
        BlockPos at = h.absolutePos(new BlockPos(8, 15, 8));
        for (int i = 0; i < 10_000; i++)
            pieces.add(new RuinMatter.Piece(at, Blocks.STONE.defaultBlockState(), null, Vec3.ZERO, level.getGameTime() - 1));
        CompoundTag cargo = new CompoundTag(); cargo.putString("cargo", "retained inventory");
        pieces.add(new RuinMatter.Piece(at, Blocks.BARREL.defaultBlockState(), cargo, Vec3.ZERO, level.getGameTime() - 1));
        ledger.release(pieces);
        long owned = ledger.retainedBytes();
        var dir = Files.createTempDirectory(level.getDataStorage().dataFolder.toPath(), "ledger-wrapper-");
        var file = dir.resolve("material.dat");
        CountDownLatch gate = new CountDownLatch(1);
        AtomicBoolean queued = new AtomicBoolean();
        IOUtilities.withIOWorker(() -> {
            queued.set(true);
            try { gate.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        StrikeGameTests.afterTest(h, gate::countDown);
        // Invoke the actual SavedData API (not save(CompoundTag,...)). No tree/copy is made here.
        net.minecraft.world.level.saveddata.SavedData wrapper = ledger;
        var allocation = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long beforeSave = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
        wrapper.save(file.toFile(), level.registryAccess());
        long allocated = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - beforeSave;
        h.assertTrue(allocated < 256L << 10, "SavedData wrapper constructed a whole-ledger snapshot on the server thread: " + allocated);
        Incoming.LOG.info("Ledger wrapper: records=10001, caller allocated={} bytes, owned={} bytes", allocated, owned);
        for (int i = 0; i < 8; i++) wrapper.save(file.toFile(), level.registryAccess());
        h.assertTrue(ledger.writing(), "Queued save did not freeze its owner");
        var clock = WorkClock.counting(1); clock.start(100);
        ledger.work(level, clock);
        h.assertTrue(ledger.pending() == 10_001 && ledger.retainedBytes() == owned,
                "Live ownership was released while its save was queued");
        h.assertFalse(Files.exists(file), "Delayed write completed before its I/O gate");
        gate.countDown();
        h.succeedWhen(() -> {
            h.assertTrue(queued.get() && !ledger.writing(), "Atomic save has not completed");
            try {
                var root = NbtIo.readCompressed(file, NbtAccounter.create(64L << 20));
                var restored = RuinMatter.load(root.getCompound("data"), level.registryAccess());
                h.assertTrue(restored.pending() == 10_001 && restored.released() == 10_001,
                        "Wrapper save lost moving ownership");
                h.assertTrue(restored.save(new CompoundTag(), level.registryAccess()).getList("moving", 10)
                        .getCompound(10_000).getCompound("contents").equals(cargo), "Wrapper save lost cargo");
                h.assertTrue(LedgerScan.bytes(file, 64L << 20) >= root.sizeInBytes(), "Preflight undercounts NBT");
                var measured = LedgerScan.measure(file, "incoming_ruin_matter", java.util.Set.of(), NuclearPrep.PLANS_CAP);
                h.assertTrue(measured.owned() >= restored.retainedBytes(), "Import charge omits live ownership");
                h.assertTrue(measured.owned() < 8L << 20, "Import still charges a whole 10,001-row NBT tree");
                var streamed = (RuinMatter) LedgerReader.read(file, "incoming_ruin_matter", level.registryAccess(), measured.row(), java.util.Set.of());
                h.assertTrue(streamed.save(new CompoundTag(), level.registryAccess()).equals(restored.save(new CompoundTag(), level.registryAccess())),
                        "Streaming migration differs from legacy decoding");
            } catch (java.io.IOException error) { throw new RuntimeException(error); }
        });
    }

    @GameTest(template = "pad", batch = "nuke_ledger_cold", timeoutTicks = 1200, skyAccess = true)
    public static void coldDimensionsReserveBeforeDecodingAndResumeAfterCapacityReturns(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        var nether = level.getServer().getLevel(Level.NETHER);
        NuclearWorld.get(level); NuclearWorld.get(nether);
        var dir = Files.createTempDirectory(level.getDataStorage().dataFolder.toPath(), "ledger-cold-");
        var oldFolders = new java.io.File[] { level.getDataStorage().dataFolder, nether.getDataStorage().dataFolder };
        // Dedicated cold stores on the real levels; retain and restore all existing storage/cache state.
        var oldMatter = level.getDataStorage().cache.get("incoming_ruin_matter");
        var oldNether = nether.getDataStorage().cache.get("incoming_ruin_matter");
        var first = dir.resolve("overworld"); var second = dir.resolve("nether");
        Files.createDirectories(first); Files.createDirectories(second);
        var source = new RuinMatter();
        CompoundTag cargo = new CompoundTag(); cargo.putString("payload", "seven diamonds");
        for (int i = 0; i < 512; i++) source.release(java.util.List.of(new RuinMatter.Piece(
                new BlockPos(i, 300, 0), Blocks.BARREL.defaultBlockState(), cargo, Vec3.ZERO, level.getGameTime())));
        CompoundTag legacy = new CompoundTag(); legacy.put("data", source.save(new CompoundTag(), level.registryAccess()));
        NbtIo.writeCompressed(legacy, first.resolve("incoming_ruin_matter.dat"));
        NbtIo.writeCompressed(legacy, second.resolve("incoming_ruin_matter.dat"));
        folder(level.getDataStorage(), first.toFile()); folder(nether.getDataStorage(), second.toFile());
        level.getDataStorage().cache.remove("incoming_ruin_matter"); nether.getDataStorage().cache.remove("incoming_ruin_matter");
        var owner = level.getData(ua.zentix.incoming.registry.ModAttachments.NUCLEAR_WORLD);
        long reserve = NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level) - (64L << 10);
        owner.materialReservation += reserve;
        boolean[] refunded = {false};
        StrikeGameTests.afterTest(h, () -> {
            if (!refunded[0]) owner.materialReservation -= reserve;
            folder(level.getDataStorage(), oldFolders[0]); folder(nether.getDataStorage(), oldFolders[1]);
            level.getDataStorage().set("incoming_ruin_matter", oldMatter);
            nether.getDataStorage().set("incoming_ruin_matter", oldNether);
            NuclearWorld.get(level); NuclearWorld.get(nether);
        });
        var cold = RuinMatter.get(level); var other = RuinMatter.get(nether);
        h.assertFalse(cold.writable() || other.writable(), "Cold lookup decoded on the caller thread");
        h.assertTrue(cold.pending() == 0 && other.pending() == 0, "Cold cache eagerly materialized the journal");
        AtomicBoolean scanned = new AtomicBoolean(); IOUtilities.withIOWorker(() -> scanned.set(true));
        h.succeedWhen(() -> {
            h.assertTrue(scanned.get(), "Cold scans have not completed");
            if (!refunded[0]) {
                h.assertTrue(RuinMatter.get(level) == cold && RuinMatter.get(nether) == other,
                        "Refused imports published live objects");
                h.assertTrue(NuclearPrep.residentMemory(level) <= NuclearPrep.PLANS_CAP,
                        "Cold admission exceeded the shared budget");
                h.assertTrue(owner.materialReservation == reserve && NuclearWorld.get(nether).materialReservation == 0,
                        "Cold decoder was admitted before capacity returned");
                owner.materialReservation -= reserve; refunded[0] = true;
            }
            var a = RuinMatter.get(level); var b = RuinMatter.get(nether);
            h.assertTrue(a.writable() && b.writable(), "Imports have not completed");
            h.assertTrue(a.pending() == 512 && b.pending() == 512 && a.released() == b.released(),
                    "Cross-dimension import lost material");
            h.assertTrue(a.save(new CompoundTag(), level.registryAccess()).getList("moving", 10)
                    .getCompound(0).getCompound("contents").equals(cargo), "Legacy import lost cargo");
        });
    }
    @GameTest(template = "range", batch = "nuke_ledger_drain", timeoutTicks = 1200, skyAccess = true)
    public static void loadedTransportFreesColdImportWorkspace(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var world = NuclearWorld.get(level);
        var storage = level.getDataStorage();
        var oldFolder = storage.dataFolder;
        var oldMatter = storage.cache.get("incoming_ruin_matter");
        var oldProgress = storage.cache.get("incoming_ruin_settle");
        var dir = Files.createTempDirectory(oldFolder.toPath(), "ledger-drain-");
        var source = new RuinProgress();
        var root = new CompoundTag(); root.put("data", source.save(new CompoundTag(), level.registryAccess()));
        var file = dir.resolve("incoming_ruin_settle.dat");
        NbtIo.writeCompressed(root, file);
        long required = LedgerScan.measure(file, "incoming_ruin_settle", java.util.Set.of(), NuclearPrep.PLANS_CAP).owned();
        var resident = new RuinMatter();
        var pieces = new ArrayList<RuinMatter.Piece>();
        for (int i = 0; i < 512; i++) {
            var pos = h.absolutePos(new BlockPos(8 + i % 24, 3, 8 + i / 24));
            pieces.add(new RuinMatter.Piece(pos, Blocks.STONE.defaultBlockState(), null, Vec3.ZERO, level.getGameTime() - 20));
        }
        resident.release(pieces);
        folder(storage, dir.toFile());
        storage.set("incoming_ruin_matter", resident);
        storage.cache.remove("incoming_ruin_settle");
        // Close admission before any poll can finish the small scan; retain a fixed reservation
        // thereafter. Only real transport progress may supply the missing decoder workspace.
        world.materialReservation += NuclearPrep.PLANS_CAP;
        NuclearWorld.get(level);
        long residentBytes = NuclearPrep.residentMemory(level) - NuclearPrep.PLANS_CAP;
        long reserve = NuclearPrep.PLANS_CAP - residentBytes - required + (64L << 10);
        world.materialReservation += reserve - NuclearPrep.PLANS_CAP;
        h.assertTrue(reserve > 0, "Fixture has no admission headroom");
        StrikeGameTests.afterTest(h, () -> {
            world.materialReservation -= reserve;
            folder(storage, oldFolder);
            storage.set("incoming_ruin_matter", oldMatter);
            storage.set("incoming_ruin_settle", oldProgress);
            NuclearWorld.get(level);
        });
        var cold = RuinProgress.get(level);
        h.assertFalse(cold.loaded(), "Cold fixture decoded on caller");
        h.assertTrue(RuinMemory.reserve(level, 1) == null, "New removals admitted behind unknown ownership");
        try (var progress = RuinMemory.reserveProgress(level, 32L << 10)) {
            h.assertTrue(progress != null, "Resident support cannot reserve bounded progress behind cold ownership");
        }
        h.succeedWhen(() -> {
            var clock = WorkClock.counting(1); clock.start(64);
            world.tick(level, clock);
            h.assertTrue(resident.deposited() > 0, "Loaded transport cannot drain behind a cold ledger");
            h.assertTrue(RuinProgress.get(level).loaded(), "Transport did not free cold decoder workspace");
            h.assertTrue(NuclearPrep.residentMemory(level) <= NuclearPrep.PLANS_CAP, "Cold import exceeded capacity");
        });
    }

    @GameTest(template = "pad", batch = "nuke_ledger_admission", timeoutTicks = 1200, skyAccess = true)
    public static void progressCannotSpendAColdDecodersHeadroomTwice(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel(); var owner = NuclearWorld.get(level);
        var storage = level.getDataStorage();
        var oldFolder = storage.dataFolder;
        var oldMatter = storage.cache.get("incoming_ruin_matter");
        var oldProgress = storage.cache.get("incoming_ruin_settle");
        var dir = Files.createTempDirectory(oldFolder.toPath(), "ledger-admission-");
        var root = new CompoundTag(); root.put("data", new RuinProgress().save(new CompoundTag(), level.registryAccess()));
        var file = dir.resolve("incoming_ruin_settle.dat");
        NbtIo.writeCompressed(root, file);
        long required = LedgerScan.measure(file, "incoming_ruin_settle", java.util.Set.of(), NuclearPrep.PLANS_CAP).owned();
        long[] reservation = {NuclearPrep.PLANS_CAP}; owner.materialReservation += reservation[0];
        folder(storage, dir.toFile());
        storage.set("incoming_ruin_matter", new RuinMatter()); storage.cache.remove("incoming_ruin_settle");
        NuclearWorld.get(level); // Queue the scan, with admission closed.
        var gate = new CountDownLatch(1);
        var scanned = new AtomicBoolean();
        IOUtilities.withIOWorker(() -> {
            scanned.set(true);
            try { gate.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        StrikeGameTests.afterTest(h, () -> {
            gate.countDown(); owner.materialReservation -= reservation[0];
            folder(storage, oldFolder);
            storage.set("incoming_ruin_matter", oldMatter); storage.set("incoming_ruin_settle", oldProgress);
            NuclearWorld.get(level);
        });
        boolean[] checked = {false};
        h.succeedWhen(() -> {
            h.assertTrue(scanned.get(), "Sibling scan has not completed");
            if (!checked[0]) {
                // Exactly one decoder fits; no owner getter may run between this adjustment
                // and the real progress helper. The gated decoder keeps its charge live.
                long delta = NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level) - required;
                owner.materialReservation += delta; reservation[0] += delta;
                try (var progress = RuinMemory.reserveProgress(level, 32L << 10)) {
                    h.assertTrue(NuclearPrep.residentMemory(level) <= NuclearPrep.PLANS_CAP,
                            "Progress and cold import spent the same headroom");
                    h.assertTrue(progress == null, "Progress admitted beside a capacity-filling decoder");
                }
                checked[0] = true;
                gate.countDown();
            }
            h.assertTrue(RuinProgress.get(level).loaded(), "Gated import has not published");
        });
    }

    @GameTest(template = "pad", batch = "nuke_ledger_totals", timeoutTicks = 200, skyAccess = true)
    public static void retiredCraterAddsToSavedTotalsInEitherFieldOrder(GameTestHelper h) throws Exception {
        var lookup = h.getLevel().registryAccess();
        var dir = Files.createTempDirectory(h.getLevel().getDataStorage().dataFolder.toPath(), "ledger-totals-");
        for (boolean settledFirst : new boolean[]{false, true}) {
            var file = dir.resolve("order-" + settledFirst + ".dat");
            try (var out = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(Files.newOutputStream(file)))) {
                var writer = new LedgerWriter(out); writer.compound(""); writer.compound("data");
                if (settledFirst) writeSettledFixture(writer);
                writer.list("passes", 1);
                var pass = new CompoundTag(); pass.putInt("event", 7);
                writer.fields(pass); writer.list("materials", 1);
                writer.row(craterTotalsFixture(10)); writer.end();
                if (!settledFirst) writeSettledFixture(writer);
                writer.end(); writer.end();
            }
            var measured = LedgerScan.measure(file, "incoming_crater_matter", java.util.Set.of(), NuclearPrep.PLANS_CAP);
            var restored = (CraterMatter) LedgerReader.read(file, "incoming_crater_matter", lookup, measured.row(), java.util.Set.of());
            var again = CraterMatter.load(restored.save(new CompoundTag(), lookup), lookup);
            double[] expected = {17, 45900, 0, 0, 17, 45900, 0, 0};
            h.assertTrue(java.util.Arrays.equals(restored.settled(Blocks.STONE.defaultBlockState()), expected),
                    "Retired totals overwritten, settledFirst=" + settledFirst);
            h.assertTrue(java.util.Arrays.equals(again.settled(Blocks.STONE.defaultBlockState()), expected),
                    "Second save/read changed merged totals");
        }
        h.succeed();
    }

    private static CompoundTag craterTotalsFixture(int count) {
        var row = new CompoundTag(); row.put("state", net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        row.putDouble("v0", count); row.putDouble("v1", count * 2700);
        row.putDouble("v4", count); row.putDouble("v5", count * 2700);
        row.putLong("available", count);
        return row;
    }

    private static void writeSettledFixture(LedgerWriter writer) throws java.io.IOException {
        writer.list("settled", 1); writer.row(craterTotalsFixture(7));
    }

    @GameTest(template = "pad", batch = "nuke_ledger_legacy_crater", timeoutTicks = 1200, skyAccess = true)
    public static void retiredLegacyCraterSkipsLargeArraysBeforeAllocation(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel(); NuclearWorld.get(level);
        var storage = level.getDataStorage();
        var oldFolder = storage.dataFolder;
        var oldCrater = storage.cache.get("incoming_crater_matter");
        ua.zentix.incoming.nuclear.NuclearEvents.get(level).craterProgress(777778, 0, false);
        var dir = Files.createTempDirectory(oldFolder.toPath(), "ledger-legacy-crater-");
        AtomicBoolean written = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Exception> failure = new java.util.concurrent.atomic.AtomicReference<>();
        IOUtilities.withIOWorker(() -> {
            try {
                IOUtilities.atomicWrite(dir.resolve("incoming_crater_matter.dat"), stream -> {
                    try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.util.zip.GZIPOutputStream(stream)))) {
                        var writer = new LedgerWriter(out); writer.compound(""); writer.compound("data"); writer.list("passes", 2);
                        int columns = 3_000_000;
                        // Event ID deliberately follows the arrays. These old checkpoints would
                        // require over 512 MiB in ground/done maps before post-load compaction.
                        writer.header(12, "columns"); out.writeInt(columns);
                        for (int k = 0; k < columns; k++) out.writeLong(k);
                        writer.header(11, "ground"); out.writeInt(columns);
                        for (int k = 0; k < columns; k++) out.writeInt(64);
                        writer.header(12, "done"); out.writeInt(columns);
                        for (int k = 0; k < columns; k++) out.writeLong(k);
                        CompoundTag meta = new CompoundTag(); meta.putInt("event", 777777); meta.putString("soil", "ROCK"); writer.fields(meta);
                        writer.list("materials", 1);
                        CompoundTag row = new CompoundTag(); row.put("state", net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
                        row.putDouble("v0", 10); row.putDouble("v1", 27000); row.putDouble("v4", 10); row.putDouble("v5", 27000);
                        row.putLong("available", 10); writer.row(row);
                        writer.end();
                        CompoundTag active = new CompoundTag();
                        active.putInt("event", 777778); active.putString("soil", "ROCK");
                        active.putLongArray("columns", new long[]{7}); active.putIntArray("ground", new int[]{63});
                        active.putLongArray("done", new long[]{7}); writer.row(active);
                        writer.end(); writer.end();
                    }
                });
            } catch (Exception error) { failure.set(error); }
            finally { written.set(true); }
        });
        boolean[] installed = {false};
        StrikeGameTests.afterTest(h, () -> {
            folder(storage, oldFolder); storage.set("incoming_crater_matter", oldCrater); NuclearWorld.get(level);
            ua.zentix.incoming.nuclear.NuclearEvents.get(level).craterProgress(777778, 0, true);
        });
        h.succeedWhen(() -> {
            if (failure.get() != null) throw new RuntimeException(failure.get());
            h.assertTrue(written.get(), "Legacy disk fixture has not finished");
            if (!installed[0]) {
                folder(storage, dir.toFile()); storage.cache.remove("incoming_crater_matter"); installed[0] = true;
                h.assertFalse(CraterMatter.get(level).writable(), "Legacy journal loaded synchronously");
            }
            var restored = CraterMatter.get(level);
            h.assertTrue(restored.writable(), "Legacy streaming migration has not finished");
            h.assertTrue(restored.retainedBytes() < 1L << 20, "Completed legacy checkpoint arrays remain resident");
            h.assertTrue(restored.pass(777778).ground.get(7) == 63 && restored.pass(777778).done.contains(7),
                    "Active legacy checkpoint was compacted with completed events");
            double[] totals = restored.settled(Blocks.STONE.defaultBlockState());
            h.assertTrue(totals[0] == 10 && totals[1] == 27000 && totals[4] == 10 && totals[5] == 27000,
                    "Legacy compaction lost crater material totals");
        });
    }

}
