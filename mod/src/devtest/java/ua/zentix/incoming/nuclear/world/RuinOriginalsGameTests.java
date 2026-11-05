package ua.zentix.incoming.nuclear.world;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;

/** Dense original-state lifetime, with the global table boundary from the actual 1 Mt failure. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinOriginalsGameTests {
    private RuinOriginalsGameTests() {}

    private static long source(int i) {
        return BlockPos.asLong(-4096 + (i & 255), -32 + (i >>> 16), -4096 + ((i >>> 8) & 255));
    }

    @GameTest(template = "pad", batch = "nuke_original_sections", timeoutTicks = 100)
    public static void denseJournalHasNoWorldSizedRehash(GameTestHelper h) {
        RuinOriginals journal = new RuinOriginals();
        int count = 1_570_626;
        for (int i = 0; i < count; i++) journal.put(source(i), Blocks.STONE_BRICKS.defaultBlockState(), 2, false);
        long before = journal.bytes();
        h.assertTrue(journal.size() == count, "Original provenance was lost");
        h.assertTrue(before < 64L << 20, "Dense original journal retained per-block object ownership");
        long[] next = new long[22_144];
        for (int i = 0; i < next.length; i++) next[i] = BlockPos.asLong(-208 + (i & 15), 64 + (i >>> 8), -864 + ((i >>> 4) & 15));
        long growth = journal.growthBytes(next);
        h.assertTrue(growth < 4L << 20, "One dense chunk requests a global journal expansion");
        for (long at : next) journal.put(at, Blocks.BRICKS.defaultBlockState(), 3, false);
        h.assertTrue(journal.bytes() <= before + growth, "Journal publication exceeded its complete growth allowance");
        for (int i = 0; i < count; i++) h.assertTrue(journal.state(source(i)) == Blocks.STONE_BRICKS.defaultBlockState(),
                "Growth changed an existing original");
        Incoming.LOG.info("ORIGINAL_SECTIONS old count {} retained {}, next {} growth {}, final {}",
                count, before, next.length, growth, journal.bytes());
        journal.clear();
        h.assertTrue(journal.bytes() < 8192 && journal.isEmpty(), "Completed journal retains its backing pages");
        h.succeed();
    }

    @GameTest(template = "pad", batch = "nuke_original_stream", timeoutTicks = 200)
    public static void firstRemovedStatesSurviveBothLegacyAndNormalStream(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        RuinMatter ledger = new RuinMatter();
        CompoundTag part = new CompoundTag(), row = new CompoundTag();
        ListTag rows = new ListTag(); rows.add(row); part.put("support_originals", rows);
        for (int i = 0; i < 8195; i++) {
            // Exceed the legacy scanner's bounded section-count cache with real distinct sections.
            row.putLong("position", BlockPos.asLong(i * 16, -16, -4096)); row.putInt("event", 9);
            row.put("state", NbtUtils.writeBlockState(Blocks.BRICKS.defaultBlockState()));
            RuinMatter.read(part, level.registryAccess(), ledger, false);
            row.putInt("event", 3); row.put("state", NbtUtils.writeBlockState(Blocks.STONE_BRICKS.defaultBlockState()));
            RuinMatter.read(part, level.registryAccess(), ledger, false);
            row.putInt("event", 6); row.put("state", NbtUtils.writeBlockState(Blocks.GLASS.defaultBlockState()));
            RuinMatter.read(part, level.registryAccess(), ledger, false);
        }
        RuinMatter.read(new CompoundTag(), level.registryAccess(), ledger, true);
        CompoundTag expected = ledger.save(new CompoundTag(), level.registryAccess());
        for (Tag value : expected.getList("support_originals", Tag.TAG_COMPOUND)) {
            CompoundTag original = (CompoundTag) value;
            h.assertTrue(original.getInt("event") == 3, "Overlapping legacy events replaced the first removal");
        }
        Path file = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data/original-sections-" + UUID.randomUUID() + ".dat");
        ledger.setDirty(); ledger.save(file.toFile(), level.registryAccess());
        var restored = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<RuinMatter>>();
        h.succeedWhen(() -> {
            h.assertTrue(ledger.writable(), "Normal frozen ledger save is pending");
            if (restored.get() == null) restored.set(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    var measure = LedgerScan.measure(file, "incoming_ruin_matter", java.util.Set.of(), 8L << 20);
                    if (measure.owned() >= 8L << 20) throw new IllegalStateException("Section import requires per-block object credit");
                    RuinMatter read = (RuinMatter) LedgerReader.read(file, "incoming_ruin_matter", level.registryAccess(),
                            measure.row(), java.util.Set.of());
                    if (read.retainedBytes() > measure.owned()) throw new IllegalStateException("Section import exceeded admission");
                    Path legacy = file.resolveSibling(file.getFileName() + ".legacy");
                    CompoundTag root = new CompoundTag(); root.put("data", expected);
                    net.minecraft.nbt.NbtIo.writeCompressed(root, legacy);
                    var oldMeasure = LedgerScan.measure(legacy, "incoming_ruin_matter", java.util.Set.of(), 8L << 20);
                    if (oldMeasure.owned() >= 8L << 20) throw new IllegalStateException("Bounded legacy fallback does not fund sparse sections");
                    RuinMatter oldRead = (RuinMatter) LedgerReader.read(legacy, "incoming_ruin_matter", level.registryAccess(),
                            oldMeasure.row(), java.util.Set.of());
                    if (oldRead.retainedBytes() > oldMeasure.owned()
                            || !normalized(oldRead.save(new CompoundTag(), level.registryAccess())).equals(normalized(expected.copy())))
                        throw new IllegalStateException("Legacy section accounting changed or underfunded originals");
                    return read;
                } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            }, net.minecraft.Util.ioPool()));
            h.assertTrue(restored.get().isDone(), "Normal streaming import is pending");
            RuinMatter read = restored.get().join();
            h.assertTrue(normalized(read.save(new CompoundTag(), level.registryAccess())).equals(normalized(expected)),
                    "Streaming sections changed state, event, support or material ownership");
        });
    }

    @GameTest(template = "pad", batch = "nuke_original_bounds", timeoutTicks = 100)
    public static void sectionHeadersCannotUnderstateImportedOwnership(GameTestHelper h) {
        for (int entries = 1; entries <= 4096; entries++) h.assertTrue(RuinOriginals.importBytes(entries) <= entries * 256L,
                "Section density regressed the legacy per-original admission bound");
        CompoundTag row = new CompoundTag();
        row.putLong("section", net.minecraft.core.SectionPos.asLong(-1, -1, -1));
        row.putInt("original_entries", 1);
        ListTag palette = new ListTag(); palette.add(NbtUtils.writeBlockState(Blocks.BRICKS.defaultBlockState()));
        row.put("palette", palette); row.putLongArray("cells", new long[]{3L << 24, (3L << 24) | 1});
        h.assertTrue(rejected(() -> new RuinOriginals().read(row)), "Underdeclared section was accepted");
        row.putInt("original_entries", 4097);
        h.assertTrue(rejected(() -> new RuinOriginals().read(row)), "Oversized section was allocated");
        row.remove("original_entries");
        h.assertTrue(rejected(() -> new RuinOriginals().read(row)), "Headerless section was accepted");
        var uncharged = new RuinOriginals(); long fixed = uncharged.bytes();
        row.putLong("original_entries", 2);
        h.assertTrue(rejected(() -> uncharged.read(row)) && uncharged.bytes() == fixed && uncharged.isEmpty(),
                "Long header allocated an uncharged section");
        row.putShort("original_entries", (short) 2);
        h.assertTrue(rejected(() -> uncharged.read(row)) && uncharged.bytes() == fixed && uncharged.isEmpty(),
                "Short header allocated an uncharged section");
        row.putInt("original_entries", 3);
        var incomplete = new RuinOriginals(); incomplete.read(row);
        h.assertTrue(rejected(incomplete::loaded), "Incomplete section was published");
        h.succeed();
    }

    @GameTest(template = "pad", batch = "nuke_original_growth", timeoutTicks = 100)
    public static void existingPageFundsTheLastIntermediateRehash(GameTestHelper h) throws Exception {
        var journal = new RuinOriginals();
        for (int i = 0; i < 2; i++) journal.put(cell(i), Blocks.BRICKS.defaultBlockState(), 1, false);
        long before = journal.bytes();
        long[] positions = new long[4094];
        for (int i = 0; i < positions.length; i++) positions[i] = cell(i + 2);
        long grant = journal.growthBytes(positions);
        for (int i = 2; i < 3072; i++) journal.put(cell(i), Blocks.BRICKS.defaultBlockState(), 1, false);
        var pages = (java.util.Map<?, ?>) field(journal, "pages");
        Object table = field(pages.values().iterator().next(), "table");
        // Keep the actual last old arrays alive across their replacement, as they coexist
        // with the new arrays during rehash. This observes the peak missed by final size alone.
        int[] oldKeys = (int[]) field(table, "key");
        long[] oldValues = (long[]) field(table, "value");
        for (int i = 3072; i < 4096; i++) journal.put(cell(i), Blocks.BRICKS.defaultBlockState(), 1, false);
        long overlapping = 64L + oldKeys.length * 4L + oldValues.length * 8L;
        long peakGrowth = journal.bytes() - before + overlapping;
        Incoming.LOG.info("ORIGINAL_GROWTH grant {} finalGrowth {} overlapping {} peakGrowth {}",
                grant, journal.bytes() - before, overlapping, peakGrowth);
        java.lang.ref.Reference.reachabilityFence(oldKeys);
        java.lang.ref.Reference.reachabilityFence(oldValues);
        h.assertTrue(peakGrowth <= grant, "Existing section did not fund its intermediate old/new arrays");
        h.succeed();
    }

    private static long cell(int i) { return BlockPos.asLong(i & 15, i >>> 8, (i >>> 4) & 15); }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { var f = type.getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static boolean rejected(Runnable action) {
        try { action.run(); return false; }
        catch (IllegalArgumentException expected) { return true; }
    }

    @GameTest(template = "pad", batch = "nuke_original_min", timeoutTicks = 100)
    public static void emptyAndSingletonImportsFundTheirFixedOwners(GameTestHelper h) throws Exception {
        for (int count = 0; count < 2; count++) {
            var ledger = new RuinMatter();
            if (count > 0) {
                var row = new CompoundTag(); row.putLong("position", 0); row.putInt("event", 1);
                row.put("state", NbtUtils.writeBlockState(Blocks.BRICKS.defaultBlockState()));
                var rows = new ListTag(); rows.add(row);
                var part = new CompoundTag(); part.put("support_originals", rows);
                RuinMatter.read(part, h.getLevel().registryAccess(), ledger, true);
            }
            Path file = h.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("data/original-min-" + UUID.randomUUID() + ".dat");
            try (var output = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(file)))) {
                var writer = new LedgerWriter(output);
                writer.compound(""); writer.compound("data"); ledger.write(writer, h.getLevel().registryAccess());
                writer.end(); writer.end();
            }
            var measure = LedgerScan.measure(file, "incoming_ruin_matter", java.util.Set.of(), NuclearPrep.PLANS_CAP);
            Incoming.LOG.info("ORIGINAL_MIN count {} import {} resident {}", count, measure.owned(), ledger.retainedBytes());
            h.assertTrue(measure.owned() >= ledger.retainedBytes(), "Small ledger import omitted fixed journal ownership");
        }
        h.succeed();
    }

    private static CompoundTag normalized(CompoundTag tag) {
        List<Tag> rows = new ArrayList<>(tag.getList("support_originals", Tag.TAG_COMPOUND));
        rows.sort(java.util.Comparator.comparingLong(row -> ((CompoundTag) row).getLong("position")));
        ListTag sorted = new ListTag(); sorted.addAll(rows); tag.put("support_originals", sorted);
        return tag;
    }
}
