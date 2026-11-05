package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.Clearable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Native section mutation, metadata and exceptional ownership receipts; no persisted material service. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedSectionWriterGameTests {
    @GameTest(template = "range", batch = "nuke_prepared_section_bulk", timeoutTicks = 200, skyAccess = true)
    public static void bulkSectionPreservesEditsAndPublishesNativeMetadata(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        BlockPos base = base(h);
        LevelChunk chunk = level.getChunkSource().getChunkNow(base.getX() >> 4, base.getZ() >> 4);
        h.assertTrue(chunk != null, "Fixture chunk is not ready");
        var changes = new ArrayList<PreparedSectionWriter.CellChange>();
        BlockPos lamp = base.offset(8, 15, 8), edited = base.offset(1, 1, 1);
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockPos at = base.offset(x, y, z);
            h.assertTrue(chunk.getBlockState(at).isAir(), "Bulk fixture section must initially be air");
            changes.add(new PreparedSectionWriter.CellChange(at, Blocks.AIR.defaultBlockState(),
                    at.equals(lamp) ? Blocks.GLOWSTONE.defaultBlockState() : Blocks.STONE.defaultBlockState()));
        }
        level.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        var receipt = new PreparedSectionWriter.Receipt(changes.size());
        Object ledger = level.getDataStorage().cache.get("incoming_ruin_matter");
        long start = System.nanoTime();
        PreparedSectionWriter.apply(level, chunk, changes, receipt);
        long elapsed = System.nanoTime() - start;
        h.assertTrue(receipt.applied().cardinality() == 4095 && receipt.stale().cardinality() == 1
                        && receipt.fastWrites() == 4095 && receipt.worldWrites() == 0,
                "Bulk writer did not isolate the single edited cell");
        h.assertTrue(chunk.getBlockState(edited).is(Blocks.GOLD_BLOCK), "Bulk delta erased a later edit");
        h.assertTrue(level.getDataStorage().cache.get("incoming_ruin_matter") == ledger, "Section writer created a legacy material owner");
        assertHeights(h, chunk);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            h.assertTrue(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) == base.getY() + 15, "Top of deposited section is stale");

        // Exercise the extracted delivery branch with a real ticking holder and vanilla packet
        // payload. This is a section-packet contract, not a real connected-client runtime receipt.
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(chunk.getPos().toLong());
        h.assertTrue(holder != null && holder.getTickingChunk() != null, "Fixture holder is not ticking");
        var delivery = new ChunkDeltaWrites.Delivery(chunk, holder, List.of(), true);
        delivery.changed(lamp);
        delivery.changed(base.offset(9, 15, 8));
        var field = ChunkHolder.class.getDeclaredField("changedBlocksPerSection");
        field.setAccessible(true);
        ShortSet marks = ((ShortSet[]) field.get(holder))[chunk.getSectionIndex(lamp.getY())];
        h.assertTrue(marks != null && marks.contains(SectionPos.sectionRelativePos(lamp)), "Section position was not queued for viewers");
        var packet = new ClientboundSectionBlocksUpdatePacket(SectionPos.of(chunk.getPos(), lamp.getY() >> 4), marks,
                chunk.getSection(chunk.getSectionIndex(lamp.getY())));
        int[] sent = {0};
        packet.runUpdates((at, state) -> {
            h.assertTrue(chunk.getBlockState(at) == state, "Native section packet contains a pre-write state");
            sent[0]++;
        });
        h.assertTrue(sent[0] >= 2, "Native section packet missed committed positions");
        Incoming.LOG.info("PREPARED_SECTION_COST cells={} changed={} stale={} fast={} world={} light_checks={} apply_ns={} viewers=0",
                changes.size(), receipt.changed().cardinality(), receipt.stale().cardinality(), receipt.fastWrites(),
                receipt.worldWrites(), receipt.lightChecks(), elapsed);
        StrikeGameTests.gameSpeed(h);
        h.succeedWhen(() -> h.assertTrue(level.getBrightness(LightLayer.BLOCK, lamp.above()) == 14,
                "Bulk deposit did not publish its block light"));
    }

    @GameTest(template = "range", batch = "nuke_prepared_section_receipt", timeoutTicks = 100, skyAccess = true)
    public static void callbackFailureKeepsPartialAndAttemptedCargoReceipts(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos base = base(h), plain = base.offset(4, 4, 4), cargo = base.offset(8, 4, 8);
        LevelChunk chunk = level.getChunkSource().getChunkNow(base.getX() >> 4, base.getZ() >> 4);
        level.setBlock(plain, Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(cargo, Blocks.BARREL.defaultBlockState(), 2);
        var changes = List.of(new PreparedSectionWriter.CellChange(plain, Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState()),
                new PreparedSectionWriter.CellChange(cargo, Blocks.BARREL.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        var receipt = new PreparedSectionWriter.Receipt(2);
        try {
            PreparedSectionWriter.apply(level, chunk, changes, receipt, (world, at, before, after) -> {
                h.assertTrue(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, plain.getX() & 15, plain.getZ() & 15) < plain.getY(),
                        "World callback observed a stale height above an earlier direct removal");
                ColumnScar.replaceState(world, at, before, after);
                throw new IllegalStateException("Controlled failure after native world write");
            });
            h.fail("World callback failure did not escape");
        } catch (IllegalStateException expected) {
            h.assertTrue(expected.getMessage().equals("Controlled failure after native world write"), "Unexpected writer failure");
        }
        h.assertTrue(receipt.attempted().cardinality() == 2 && receipt.applied().cardinality() == 2
                        && receipt.changed().cardinality() == 2, "Exception lost a successfully changed source receipt");
        assertHeights(h, chunk);
        h.assertTrue(chunk.getBlockState(plain).isAir() && chunk.getBlockState(cargo).isAir(), "Callback control did not mutate both cells");

        level.setBlock(cargo, Blocks.BARREL.defaultBlockState(), 2);
        var entity = level.getBlockEntity(cargo);
        ((Container) entity).setItem(0, new ItemStack(Items.DIAMOND, 3));
        var saved = entity.saveWithoutMetadata(level.registryAccess());
        var attempted = new PreparedSectionWriter.Receipt(1);
        try {
            PreparedSectionWriter.apply(level, chunk, List.of(changes.getLast()), attempted, (world, at, before, after) -> {
                Clearable.tryClear(world.getBlockEntity(at));
                throw new IllegalStateException("Controlled failure between cargo clear and state write");
            });
            h.fail("Cargo-clear failure did not escape");
        } catch (IllegalStateException expected) {
            h.assertTrue(expected.getMessage().startsWith("Controlled failure between"), "Unexpected cargo control failure");
        }
        h.assertTrue(attempted.attempted(0) && !attempted.changed(0) && !attempted.applied(0)
                        && ((Container) entity).isEmpty(), "Cargo mutation without state mutation was not observable");
        entity.loadWithComponents(saved, level.registryAccess());
        h.assertTrue(((Container) entity).getItem(0).getCount() == 3, "Escrow could not restore untouched source cargo");
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_prepared_section_cargo", timeoutTicks = 100, skyAccess = true)
    public static void cargoUsesNativeBlockEntitiesAndDuplicateInputsDoNotMutate(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos base = base(h), source = base.offset(5, 5, 5), destination = base.offset(9, 5, 9);
        LevelChunk chunk = level.getChunkSource().getChunkNow(base.getX() >> 4, base.getZ() >> 4);
        level.setBlock(source, Blocks.BARREL.defaultBlockState(), 2);
        var old = level.getBlockEntity(source);
        ((Container) old).setItem(0, new ItemStack(Items.EMERALD, 7));
        var saved = old.saveWithoutMetadata(level.registryAccess());
        var removal = new PreparedSectionWriter.CellChange(source, Blocks.BARREL.defaultBlockState(), Blocks.AIR.defaultBlockState());
        var duplicate = new PreparedSectionWriter.Receipt(2);
        try {
            PreparedSectionWriter.apply(level, chunk, List.of(removal, removal), duplicate);
            h.fail("Duplicate positions were accepted");
        } catch (IllegalArgumentException expected) {
            h.assertTrue(!duplicate.attempted(0) && level.getBlockEntity(source) == old, "Invalid input mutated a source");
        }
        var receipt = new PreparedSectionWriter.Receipt(2);
        PreparedSectionWriter.apply(level, chunk, List.of(removal,
                new PreparedSectionWriter.CellChange(destination, Blocks.AIR.defaultBlockState(), Blocks.BARREL.defaultBlockState())), receipt);
        h.assertTrue(receipt.applied().cardinality() == 2 && receipt.worldWrites() == 2, "Cargo bypassed the native world path");
        var placed = level.getBlockEntity(destination);
        h.assertTrue(level.getBlockEntity(source) == null && placed != null, "Native block-entity lifecycle was not preserved");
        placed.loadWithComponents(saved, level.registryAccess());
        h.assertTrue(((Container) placed).getItem(0).getCount() == 7, "Destination lost exact cargo");

        BlockPos deferred = base.offset(11, 5, 11);
        level.setBlock(deferred, Blocks.BARREL.defaultBlockState(), 2);
        var limited = new PreparedSectionWriter.Receipt(3);
        int[] permits = {0};
        PreparedSectionWriter.apply(level, chunk, List.of(
                new PreparedSectionWriter.CellChange(destination, Blocks.BARREL.defaultBlockState(), Blocks.AIR.defaultBlockState()),
                new PreparedSectionWriter.CellChange(deferred, Blocks.BARREL.defaultBlockState(), Blocks.AIR.defaultBlockState()),
                new PreparedSectionWriter.CellChange(source, Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState())),
                limited, () -> permits[0]++ == 0);
        h.assertTrue(limited.applied(0) && !limited.attempted(1) && !limited.changed(1) && limited.applied(2)
                        && limited.worldWrites() == 1 && limited.fastWrites() == 1
                        && level.getBlockState(deferred).is(Blocks.BARREL),
                "Callback permit either mutated a denied owner or blocked later ordinary cells");

        BlockPos allowed = base.offset(3, 5, 3);
        var admitted = new java.util.BitSet();
        var admission = new PreparedSectionWriter.Receipt(4);
        PreparedSectionWriter.apply(level, chunk, List.of(
                new PreparedSectionWriter.CellChange(destination, Blocks.BARREL.defaultBlockState(), Blocks.AIR.defaultBlockState()),
                new PreparedSectionWriter.CellChange(deferred, Blocks.BARREL.defaultBlockState(), Blocks.AIR.defaultBlockState()),
                new PreparedSectionWriter.CellChange(source, Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState()),
                new PreparedSectionWriter.CellChange(allowed, Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState())),
                admission, ColumnScar::replaceState, () -> false, index -> { admitted.set(index); return index == 3; });
        h.assertTrue(admitted.cardinality() == 2 && admitted.get(2) && admitted.get(3)
                        && admission.stale(0) && !admission.attempted(1) && !admission.attempted(2) && admission.applied(3)
                        && level.getBlockState(source).is(Blocks.STONE),
                "Capture admission ran before current/permit checks or mutated a refused cell");
        h.succeed();
    }

    private static BlockPos base(GameTestHelper h) {
        BlockPos at = h.absolutePos(new BlockPos(8, 48, 8));
        return new BlockPos(at.getX() & ~15, at.getY() & ~15, at.getZ() & ~15);
    }

    private static void assertHeights(GameTestHelper h, LevelChunk chunk) {
        int[][] actual = new int[RuinPlan.HEIGHTMAP_TYPES.length][256];
        for (int i = 0; i < actual.length; i++) for (int col = 0; col < 256; col++)
            actual[i][col] = chunk.getHeight(RuinPlan.HEIGHTMAP_TYPES[i], col & 15, col >> 4);
        Heightmap.primeHeightmaps(chunk, EnumSet.copyOf(List.of(RuinPlan.HEIGHTMAP_TYPES)));
        for (int i = 0; i < actual.length; i++) for (int col = 0; col < 256; col++)
            h.assertTrue(chunk.getHeight(RuinPlan.HEIGHTMAP_TYPES[i], col & 15, col >> 4) == actual[i][col],
                    "Bulk column metadata differs from native full rescan");
    }

    private PreparedSectionWriterGameTests() {}
}
