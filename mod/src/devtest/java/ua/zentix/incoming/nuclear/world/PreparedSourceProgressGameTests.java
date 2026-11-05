package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Chunk-native source checkpoint round trips; no cross-file crash-atomicity claim. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedSourceProgressGameTests {
    @GameTest(template = "range", batch = "nuke_prepared_source_progress", timeoutTicks = 200, skyAccess = true)
    public static void partialWritesSaveWithBlocksAndLocalRepairPreservesOtherReceipts(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos origin = h.absolutePos(new BlockPos(8, 48, 8));
        BlockPos base = new BlockPos(origin.getX() & ~15, origin.getY() & ~15, origin.getZ() & ~15);
        LevelChunk chunk = level.getChunkSource().getChunkNow(base.getX() >> 4, base.getZ() >> 4);
        h.assertTrue(chunk != null, "Fixture source chunk is not ready");
        preserve(h, chunk);
        BlockPos first = base.offset(4, 1, 4), stale = base.offset(5, 1, 4), denied = base.offset(6, 1, 4);
        BlockPos repaired = base.offset(7, 1, 4), upper = base.offset(4, 17, 4);
        var id = new PreparedSourceProgress.Identity(1_800_000_001, UUID.randomUUID(), 9);
        int lowerY = first.getY() >> 4, upperY = upper.getY() >> 4;
        for (BlockPos at : List.of(first, stale, repaired, upper)) level.setBlock(at, Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(denied, Blocks.CHEST.defaultBlockState(), 2);
        level.setBlock(stale, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
        PreparedSourceProgress.install(chunk, id, Map.of(lowerY, mask(first, stale, denied), upperY, mask(upper)));
        rejects(h, () -> PreparedSourceProgress.recordApplied(chunk, id, lowerY, mask(first)), "Recorded source before begin");
        PreparedSourceProgress.begun(chunk, id);
        var changes = List.of(change(first, Blocks.STONE), change(stale, Blocks.STONE), change(denied, Blocks.CHEST));
        var receipt = new PreparedSectionWriter.Receipt(changes.size());
        PreparedSectionWriter.apply(level, chunk, changes, receipt, () -> false);
        PreparedSourceProgress.record(chunk, id, lowerY, changes, receipt);
        h.assertTrue(receipt.applied(0) && receipt.stale(1) && !receipt.attempted(2), "Fixture missed applied/stale/denied operands");
        h.assertTrue(chunk.isUnsaved(), "Source checkpoint did not dirty its chunk");
        h.assertTrue(PreparedSourceProgress.get(chunk).pending(lowerY).equals(mask(stale, denied)), "Stale or denied source counted complete");

        CompoundTag saved = ChunkSerializer.write(level, chunk);
        h.assertTrue(saved.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY).contains("incoming:prepared_source_progress"),
                "Source progress was not serialized with the chunk");
        var info = new RegionStorageInfo("gametest", level.dimension(), "chunk");
        LevelChunk copy = ((ImposterProtoChunk) ChunkSerializer.read(level, level.getPoiManager(), info, chunk.getPos(), saved)).getWrapped();
        PreparedSourceProgress restored = PreparedSourceProgress.get(copy);
        h.assertTrue(id.equals(restored.identity()) && restored.begun() && !restored.finished()
                        && restored.pending(lowerY).equals(mask(stale, denied)) && restored.pending(upperY).equals(mask(upper))
                        && copy.getBlockState(first).isAir() && copy.getBlockState(stale).is(Blocks.DIAMOND_BLOCK)
                        && copy.getBlockState(denied).is(Blocks.CHEST),
                "Native chunk reload separated block state from its partial source receipts");
        rejects(h, () -> PreparedSourceProgress.finish(copy, id), "Partial source finished after reload");
        var wrong = new PreparedSourceProgress.Identity(id.event(), id.preparation(), id.generation() + 1);
        rejects(h, () -> PreparedSourceProgress.recordApplied(copy, wrong, lowerY, mask(stale)), "Wrong generation advanced source");
        rejects(h, () -> PreparedSourceProgress.install(copy, wrong, Map.of()), "New generation replaced unfinished source");

        // The caller has accepted a local repair that keeps the player's diamond and introduces one new source.
        rejects(h, () -> PreparedSourceProgress.replacePending(chunk, id, lowerY, mask(first)), "Repair replayed completed source");
        PreparedSourceProgress.replacePending(chunk, id, lowerY, mask(denied, repaired));
        var repairedProgress = PreparedSourceProgress.get(chunk);
        h.assertTrue(repairedProgress.pending(lowerY).equals(mask(denied, repaired))
                        && repairedProgress.pending(upperY).equals(mask(upper)) && !repairedProgress.cellsComplete(),
                "Section repair reset completed cells or another section");
        var repairedSave = ChunkSerializer.write(level, chunk);
        var repairedCopy = ((ImposterProtoChunk) ChunkSerializer.read(level, level.getPoiManager(), info, chunk.getPos(), repairedSave)).getWrapped();
        h.assertTrue(PreparedSourceProgress.get(repairedCopy).pending(lowerY).equals(mask(denied, repaired)), "Repair footprint was not persisted");
        apply(h, chunk, id, lowerY, List.of(change(denied, Blocks.CHEST), change(repaired, Blocks.STONE)));
        rejects(h, () -> PreparedSourceProgress.finish(chunk, id), "Other section was silently retired");
        apply(h, chunk, id, upperY, List.of(change(upper, Blocks.STONE)));
        PreparedSourceProgress.finish(chunk, id);
        rejects(h, () -> PreparedSourceProgress.replacePending(chunk, id, lowerY, new BitSet()), "Finished owner accepted repair");
        rejects(h, () -> PreparedSourceProgress.retire(chunk, id), "Progress retired before the scar watermark");
        chunk.setData(ModAttachments.CHUNK_SCAR, id.event());
        PreparedSourceProgress.retire(chunk, id);
        h.assertTrue(PreparedSourceProgress.get(chunk).isEmpty() && level.getBlockState(stale).is(Blocks.DIAMOND_BLOCK),
                "Final retirement lost player edit or retained partial owner");
        rejects(h, () -> PreparedSourceProgress.install(chunk, id, Map.of()), "Completed event replayed after retirement");
        h.succeed();
    }

    @GameTest(template = "pad", batch = "nuke_prepared_source_progress_codec", timeoutTicks = 100, skyAccess = true)
    public static void codecAndFootprintRejectUnfundedOrContradictoryCompletion(GameTestHelper h) {
        var chunk = new LevelChunk(h.getLevel(), new net.minecraft.world.level.ChunkPos(h.absolutePos(BlockPos.ZERO)));
        var id = new PreparedSourceProgress.Identity(1_800_000_002, UUID.randomUUID(), 4);
        int section = chunk.getMinSection();
        BitSet planned = new BitSet(); planned.set(3); planned.set(4095);
        PreparedSourceProgress.install(chunk, id, Map.of(section, planned));
        planned.clear();
        h.assertTrue(PreparedSourceProgress.get(chunk).pending(section).cardinality() == 2, "Caller mutation altered stored footprint");
        BitSet returned = PreparedSourceProgress.get(chunk).pending(section); returned.clear();
        h.assertTrue(PreparedSourceProgress.get(chunk).pending(section).cardinality() == 2, "Returned mask exposed stored footprint");
        PreparedSourceProgress.begun(chunk, id);
        BitSet one = new BitSet(); one.set(3);
        PreparedSourceProgress.recordApplied(chunk, id, section, one);
        BitSet extra = new BitSet(); extra.set(4);
        rejects(h, () -> PreparedSourceProgress.recordApplied(chunk, id, section, extra), "Unplanned cell gained a receipt");
        rejects(h, () -> PreparedSourceProgress.replacePending(chunk, id, chunk.getMaxSection(), one), "Repair escaped chunk height");
        var encoded = (CompoundTag) PreparedSourceProgress.CODEC.encodeStart(NbtOps.INSTANCE, PreparedSourceProgress.get(chunk)).getOrThrow();
        CompoundTag badDone = encoded.copy();
        badDone.getCompound("active").getList("sections", 10).getCompound(0).putLongArray("completed", new long[] {1L << 4});
        h.assertTrue(PreparedSourceProgress.CODEC.parse(NbtOps.INSTANCE, badDone).error().isPresent(), "Codec accepted unplanned completion");
        CompoundTag badStage = encoded.copy(); badStage.getCompound("active").putInt("stage", 2);
        h.assertTrue(PreparedSourceProgress.CODEC.parse(NbtOps.INSTANCE, badStage).error().isPresent(), "Codec accepted finished partial source");
        CompoundTag tooWide = encoded.copy();
        tooWide.getCompound("active").getList("sections", 10).getCompound(0).putLongArray("planned", new long[65]);
        h.assertTrue(PreparedSourceProgress.CODEC.parse(NbtOps.INSTANCE, tooWide).error().isPresent(), "Codec accepted oversized source section");
        h.succeed();
    }

    private static void apply(GameTestHelper h, LevelChunk chunk, PreparedSourceProgress.Identity id, int section,
                              List<PreparedSectionWriter.CellChange> changes) {
        var receipt = new PreparedSectionWriter.Receipt(changes.size());
        PreparedSectionWriter.apply(h.getLevel(), chunk, changes, receipt);
        h.assertTrue(receipt.applied().cardinality() == changes.size(), "Source completion fixture write was refused");
        PreparedSourceProgress.record(chunk, id, section, changes, receipt);
    }

    private static PreparedSectionWriter.CellChange change(BlockPos at, net.minecraft.world.level.block.Block block) {
        return new PreparedSectionWriter.CellChange(at, block.defaultBlockState(), Blocks.AIR.defaultBlockState());
    }

    private static BitSet mask(BlockPos... positions) {
        BitSet result = new BitSet(4096);
        for (BlockPos at : positions) result.set(PreparedSourceProgress.local(at));
        return result;
    }

    private static void rejects(GameTestHelper h, Runnable operation, String message) {
        try { operation.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { return; }
        h.fail(message);
    }

    private static void preserve(GameTestHelper h, LevelChunk chunk) {
        int scar = chunk.getData(ModAttachments.CHUNK_SCAR);
        var progress = PreparedSourceProgress.get(chunk);
        StrikeGameTests.afterTest(h, () -> {
            chunk.setData(ModAttachments.CHUNK_SCAR, scar);
            if (progress.isEmpty()) chunk.removeData(ModAttachments.PREPARED_SOURCE_PROGRESS);
            else chunk.setData(ModAttachments.PREPARED_SOURCE_PROGRESS, progress);
            chunk.setUnsaved(true);
        });
    }

    private PreparedSourceProgressGameTests() {}
}
