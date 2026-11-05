package ua.zentix.incoming.nuclear.world;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;

/** The compact FIFO does not retain a palette/inventory object for every cargo-free record. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LedgerPackedImportGameTests {
    private LedgerPackedImportGameTests() {}

    @GameTest(template = "pad", batch = "nuke_ledger_cargo_types", timeoutTicks = 100)
    public static void malformedCargoCannotChangeRepresentationAfterAdmission(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        var ledger = new RuinMatter();
        var piece = new RuinMatter.Piece(h.absolutePos(new BlockPos(4, 8, 4)), Blocks.BARREL.defaultBlockState(),
                new CompoundTag(), Vec3.ZERO, level.getGameTime());
        ledger.release(List.of(piece));
        CompoundTag legacy = ledger.save(new CompoundTag(), level.registryAccess());
        var encode = RuinMatter.class.getDeclaredMethod("packed", RuinMatter.Piece.class); encode.setAccessible(true);
        CompoundTag packed = (CompoundTag) encode.invoke(null, piece);
        CompoundTag compact = legacy.copy();
        var moving = new net.minecraft.nbt.ListTag(); moving.add(packed); compact.put("moving", moving);
        var outside = compact.copy(); outside.getList("moving", Tag.TAG_COMPOUND).getCompound(0).getByteArray("motion")[144] = 1;
        var stranded = RuinMatter.load(outside, level.registryAccess());
        CompoundTag reservoir = stranded.save(new CompoundTag(), level.registryAccess()); stranded.unload(level);
        for (CompoundTag valid : List.of(legacy, compact, reservoir)) {
            String list = valid.getList("moving", Tag.TAG_COMPOUND).isEmpty() ? "stranded" : "moving";
            for (Tag invalid : List.of(net.minecraft.nbt.ByteTag.valueOf((byte) 1), net.minecraft.nbt.IntTag.valueOf(1),
                    net.minecraft.nbt.StringTag.valueOf("invalid"), new net.minecraft.nbt.ListTag(), new net.minecraft.nbt.ByteArrayTag(new byte[1]))) {
                CompoundTag data = valid.copy(); data.getList(list, Tag.TAG_COMPOUND).getCompound(0).put("contents", invalid);
                CompoundTag root = new CompoundTag(); root.put("data", data);
                Path file = level.getDataStorage().dataFolder.toPath().resolve("cargo-types-" + UUID.randomUUID() + ".dat");
                net.minecraft.nbt.NbtIo.writeCompressed(root, file);
                byte[] before = java.nio.file.Files.readAllBytes(file);
                boolean scanRejected = false, decodeRejected = false;
                try { LedgerScan.measure(file, "incoming_ruin_matter", Set.of(), NuclearPrep.PLANS_CAP); }
                catch (java.io.IOException expected) { scanRejected = true; }
                try {
                    var decoded = (RuinMatter) LedgerReader.read(file, "incoming_ruin_matter", level.registryAccess(), LedgerData.IO_BYTES, Set.of());
                    decoded.unload(level);
                } catch (java.io.IOException | IllegalStateException expected) { decodeRejected = true; }
                Incoming.LOG.info("CARGO_TYPES list {} type {} scanRejected {} decodeRejected {}", list, invalid.getId(), scanRejected, decodeRejected);
                h.assertTrue(scanRejected && decodeRejected, "Cargo tag changed representation between scan and decode");
                h.assertTrue(java.util.Arrays.equals(before, java.nio.file.Files.readAllBytes(file)), "Rejected cargo changed the original file");
            }
        }
        h.succeed();
    }

    @GameTest(template = "pad", batch = "nuke_ledger_outside_import", timeoutTicks = 100)
    public static void packedOutsideRowsFundReservoirsAndLegacyKeepsItsAllowance(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        var ledger = new RuinMatter();
        var cargo = new CompoundTag(); cargo.putInt("cargo", 7);
        var piece = new RuinMatter.Piece(h.absolutePos(new BlockPos(4, 8, 4)), Blocks.BARREL.defaultBlockState(), cargo, Vec3.ZERO, level.getGameTime());
        ledger.release(List.of(piece));
        CompoundTag legacy = ledger.save(new CompoundTag(), level.registryAccess());
        var encode = RuinMatter.class.getDeclaredMethod("packed", RuinMatter.Piece.class); encode.setAccessible(true);
        CompoundTag packed = (CompoundTag) encode.invoke(null, piece);
        long[] owned = new long[3];
        for (int kind = 0; kind < 3; kind++) {
            CompoundTag data = legacy.copy();
            if (kind != 0) {
                var row = packed.copy();
                if (kind == 2) row.getByteArray("motion")[144] = 1;
                var rows = new net.minecraft.nbt.ListTag(); rows.add(row); data.put("moving", rows);
            }
            CompoundTag root = new CompoundTag(); root.put("data", data);
            Path file = level.getDataStorage().dataFolder.toPath().resolve("outside-import-" + UUID.randomUUID() + ".dat");
            net.minecraft.nbt.NbtIo.writeCompressed(root, file);
            var measured = LedgerScan.measure(file, "incoming_ruin_matter", Set.of(), NuclearPrep.PLANS_CAP);
            owned[kind] = measured.owned();
            RuinMatter read = (RuinMatter) LedgerReader.read(file, "incoming_ruin_matter", level.registryAccess(), measured.row(), Set.of());
            h.assertTrue(read.released() == 1 && read.pending() == 1 && read.retainedBytes() <= measured.owned(), "Import lost or underfunded its record");
            if (kind == 2) {
                var field = RuinMatter.class.getDeclaredField("strandedCount"); field.setAccessible(true);
                h.assertTrue(field.getInt(read) == 1, "Packed outside row did not become a reservoir");
            }
            read.unload(level);
        }
        h.assertTrue(owned[0] >= owned[1] && owned[1] - owned[2] == 62,
                "Import did not distinguish legacy, packed FIFO/cargo index and packed reservoir ownership");
        Incoming.LOG.info("PACKED_IMPORT representations legacy {} packed {} reservoir {}", owned[0], owned[1], owned[2]);
        var malformed = legacy.copy();
        var row = packed.copy(); row.putByteArray("motion", new byte[144]);
        var rows = new net.minecraft.nbt.ListTag(); rows.add(row); malformed.put("moving", rows);
        CompoundTag root = new CompoundTag(); root.put("data", malformed);
        Path bad = level.getDataStorage().dataFolder.toPath().resolve("invalid-motion-" + UUID.randomUUID() + ".dat");
        net.minecraft.nbt.NbtIo.writeCompressed(root, bad);
        boolean rejected = false;
        try { LedgerScan.measure(bad, "incoming_ruin_matter", Set.of(), NuclearPrep.PLANS_CAP); }
        catch (java.io.IOException expected) { rejected = true; }
        h.assertTrue(rejected, "Admission accepted a motion array the decoder rejects");
        h.succeed();
    }

    @GameTest(template = "pad", batch = "nuke_ledger_packed_import", timeoutTicks = 600)
    public static void compactMovingImportFundsPaletteAndActualCargoIndexes(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var ledger = new RuinMatter();
        var origin = h.absolutePos(new BlockPos(0, 4, 0));
        final int count = 100000;
        final long room = 32L << 20;
        for (int i = 0; i < count; i++) {
            CompoundTag cargo = null;
            if (i % 1000 == 0) { cargo = new CompoundTag(); cargo.putInt("cargo", i); }
            var piece = new RuinMatter.Piece(origin.offset(i % 64, i / 64 % 64, i / 4096),
                    cargo == null ? Blocks.STONE.defaultBlockState() : Blocks.BARREL.defaultBlockState(),
                    cargo, new Vec3(1, -2, 3), level.getGameTime());
            piece.windX = 4; piece.windZ = -5; piece.drag = 6; piece.pulse = 7;
            ledger.release(List.of(piece));
        }
        h.assertTrue(ledger.retainedBytes() < room, "Compact live control itself does not fit its import room");
        Path file = level.getDataStorage().dataFolder.toPath().resolve("packed-import-" + UUID.randomUUID() + ".dat");
        ledger.save(file.toFile(), level.registryAccess());
        var reloading = new AtomicReference<CompletableFuture<RuinMatter>>();
        h.succeedWhen(() -> {
            h.assertTrue(ledger.writable(), "Normal compact save is pending");
            if (reloading.get() == null) reloading.set(CompletableFuture.supplyAsync(() -> {
                try {
                    var measured = LedgerScan.measure(file, "incoming_ruin_matter", Set.of(), room);
                    Incoming.LOG.info("PACKED_IMPORT rows {} live {} admitted {} room {}", count, ledger.retainedBytes(), measured.owned(), room);
                    if (measured.owned() > room) throw new IllegalStateException("Compact import still reserves a per-record object index");
                    RuinMatter read = (RuinMatter) LedgerReader.read(file, "incoming_ruin_matter", level.registryAccess(), measured.row(), Set.of());
                    if (read.retainedBytes() > measured.owned()) throw new IllegalStateException("Compact import underfunded its live owner");
                    return read;
                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            }, net.minecraft.Util.ioPool()));
            var result = reloading.get();
            h.assertTrue(result.isDone(), "Normal compact import is pending");
            h.assertFalse(result.isCompletedExceptionally(), "Compact import failed: "
                    + (result.isCompletedExceptionally() ? result.exceptionNow() : ""));
            RuinMatter read = result.join();
            h.assertTrue(read.released() == count && read.pending() == count && read.deposited() == 0,
                    "Compact import lost material records");
            h.assertTrue(read.releasedVolume() == ledger.releasedVolume()
                            && java.util.Arrays.equals(read.balance(Blocks.STONE), ledger.balance(Blocks.STONE))
                            && java.util.Arrays.equals(read.balance(Blocks.BARREL), ledger.balance(Blocks.BARREL)),
                    "Compact import changed volume/mass balances");
            try {
                var field = RuinMatter.class.getDeclaredField("moving"); field.setAccessible(true);
                int rows = 0, cargos = 0;
                for (Object value : (Iterable<?>) field.get(read)) {
                    var piece = (RuinMatter.Piece) value;
                    h.assertTrue(piece.vx == 1 && piece.vy == -2 && piece.vz == 3 && piece.windX == 4
                                    && piece.windZ == -5 && piece.drag == 6 && piece.pulse == 7,
                            "Compact import changed trajectory fields");
                    if (piece.contents != null) {
                        h.assertTrue(piece.contents.getInt("cargo") == rows && rows % 1000 == 0, "Compact import changed cargo ownership");
                        cargos++;
                    }
                    rows++;
                }
                h.assertTrue(rows == count && cargos == count / 1000, "Compact import lost records or cargo indexes");
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }
}
