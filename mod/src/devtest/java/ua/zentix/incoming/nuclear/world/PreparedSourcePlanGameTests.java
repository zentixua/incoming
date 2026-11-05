package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;

import java.util.List;
import java.util.UUID;

/** Bounded source descriptor persistence and identity checks; the pipeline fixture owns actual wave execution. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedSourcePlanGameTests {
    @GameTest(template = "pad", batch = "nuke_prepared_source_plan_io", timeoutTicks = 200, skyAccess = true)
    public static void descriptorReopensWithExactDeltaOffsetsAndReverseRows(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var fixture = fixture();
        var plan = fixture.plan;
        var level = h.getLevel();
        var path = level.getDataStorage().dataFolder.toPath().resolve("source-plan-" + UUID.randomUUID());
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_source_plan");
        var pages = new RuinPages(info, path);
        StrikeGameTests.afterTest(h, pages::close);
        var detached = plan.detached();
        var read = pages.write(plan.source().toLong(), detached::store).thenCompose(unused -> pages.flush())
                .thenCompose(unused -> pages.close()).thenApply(unused -> new RuinPages(info, path))
                .thenCompose(reader -> reader.read(plan.source().toLong()).thenCompose(tag -> reader.close().thenApply(unused -> tag)));
        h.succeedWhen(() -> {
            h.assertTrue(read.isDone(), "Waiting for source descriptor region reopen");
            CompoundTag tag = read.join().orElseThrow();
            var restored = PreparedSourcePlan.restore(tag, null, PreparedSourcePlan.restorePublicationBytes(tag));
            h.assertTrue(restored.preparation().equals(plan.preparation()) && restored.generation() == 12
                            && restored.source().equals(plan.source()) && restored.minSection() == -4
                            && restored.sections() == 24 && restored.dueTick(4) == 100
                            && restored.delta().differs(plan.delta()) == null,
                    "Source descriptor lost identity, relative due time or exact ruin delta");
            var refs = restored.references(4);
            h.assertTrue(refs.size() == 2 && refs.getFirst().sourceCell() < refs.getLast().sourceCell()
                            && restored.verify(refs.getFirst(), fixture.batch) == 0 && restored.verify(refs.getLast(), fixture.batch) == 1
                            && restored.references(3).isEmpty(), "Reverse rows do not identify their exact source/page cells");
            h.assertTrue(!tag.contains("originals_durable") && !tag.contains("footprint"),
                    "Descriptor duplicated a runtime gate or source footprint");
        });
    }

    @GameTest(template = "pad", batch = "nuke_prepared_source_plan_bounds", skyAccess = true)
    public static void descriptorRejectsMalformedDimensionsDuplicateOwnersAndUnfundedRestore(GameTestHelper h) {
        var fixture = fixture();
        var plan = fixture.plan;
        var encoded = plan.store();
        rejected(h, () -> PreparedSourcePlan.restore(encoded, null, PreparedSourcePlan.restorePublicationBytes(encoded) - 1),
                "Unfunded source restore allocated an owner");
        var wrongType = encoded.copy(); wrongType.putInt("generation", 12);
        rejected(h, () -> PreparedSourcePlan.restore(wrongType, null, Long.MAX_VALUE), "Numeric identity coercion was accepted");
        var negative = encoded.copy(); negative.putLong("generation", -1);
        rejected(h, () -> PreparedSourcePlan.restore(negative, null, Long.MAX_VALUE), "Negative generation was accepted");
        var tooTall = encoded.copy(); tooTall.putLongArray("due", new long[257]);
        rejected(h, () -> PreparedSourcePlan.restore(tooTall, null, Long.MAX_VALUE), "Unbounded source height was accepted");
        var nested = encoded.copy(); nested.getCompound("delta").putLongArray("old", new long[]{0, 0});
        rejected(h, () -> PreparedSourcePlan.restore(nested, null, Long.MAX_VALUE), "Nested old-state type coercion was accepted");
        var duplicateSource = encoded.copy();
        int[] cells = duplicateSource.getIntArray("sources"); cells[1] = cells[0];
        rejected(h, () -> PreparedSourcePlan.restore(duplicateSource, null, Long.MAX_VALUE), "Duplicate source cell was accepted");
        var duplicateRow = encoded.copy(); duplicateRow.getIntArray("rows")[1] = 0;
        rejected(h, () -> PreparedSourcePlan.restore(duplicateRow, null, Long.MAX_VALUE), "One material row acquired two sources");
        var foreignRow = encoded.copy(); foreignRow.getIntArray("rows")[0] = PreparedMaterialBatch.MAX_MOVES;
        rejected(h, () -> PreparedSourcePlan.restore(foreignRow, null, Long.MAX_VALUE), "Out-of-range material row was accepted");
        var ref = plan.references(4).getFirst();
        var foreignBatch = new PreparedMaterialBatch(UUID.randomUUID(), fixture.batch.owner(), List.of(fixture.batch.move(0)));
        rejected(h, () -> plan.verify(ref, foreignBatch), "Another preparation's row was accepted");
        var move = fixture.batch.move(0);
        var wrongSource = new PreparedMaterialBatch(plan.preparation(), fixture.batch.owner(), List.of(new PreparedMaterialBatch.Move(
                move.source().east(4), move.destination(), move.before(), move.placed(), 100, 140, 1, 600)));
        rejected(h, () -> plan.verify(ref, wrongSource), "Destination row with a different source was accepted");
        var legacyLogs = encoded.copy();
        var legacyDelta = legacyLogs.getCompound("delta");
        legacyDelta.putLongArray("outside", new long[]{move.destination().asLong()});
        legacyDelta.putIntArray("outside_states", new int[]{legacyDelta.getIntArray("old")[1]});
        h.assertTrue(RuinPlan.restore(legacyDelta, null).store().getLongArray("outside").length == 1,
                "Shared ruin decoder lost legacy outside logs");
        rejected(h, () -> PreparedSourcePlan.restore(legacyLogs, null, Long.MAX_VALUE),
                "New source path accepted legacy logs without a destination material owner");
        var empty = new PreparedSourcePlan(UUID.randomUUID(), 0, new ChunkPos(0, 0), -4, RuinPlan.EMPTY, new long[24], List.of());
        var restoredEmpty = PreparedSourcePlan.restore(empty.store(), null, Long.MAX_VALUE);
        h.assertTrue(restoredEmpty.sections() == 24 && restoredEmpty.references(4).isEmpty(), "Empty delta lost declared chunk height");
        h.succeed();
    }

    private record Fixture(PreparedSourcePlan plan, PreparedMaterialBatch batch) {}

    private static Fixture fixture() {
        var preparation = UUID.randomUUID();
        BlockPos source = new BlockPos(-27, 76, 40), target = source.offset(32, -4, 0);
        var sourceChunk = new ChunkPos(source);
        BlockState barrel = Blocks.BARREL.defaultBlockState(), stone = Blocks.STONE.defaultBlockState();
        int section = (source.getY() >> 4) + 4;
        int first = section << 12 | PreparedSourceProgress.local(source), second = first + 2;
        long[] hashes = new long[24];
        hashes[section] = RuinPlan.mix(RuinPlan.mix(0, barrel), stone);
        var delta = new RuinPlan(new int[]{first | RuinPlan.SLOW, second}, hashes,
                new BlockState[]{Blocks.AIR.defaultBlockState()}, new BlockState[]{barrel, stone}, new int[0], new int[0],
                new long[0], null, null, null, null, null, new long[0], null);
        delta.material(new long[]{source.asLong(), source.east(2).asLong()});
        var batch = new PreparedMaterialBatch(preparation, new ChunkPos(target), List.of(
                new PreparedMaterialBatch.Move(source, target, barrel, barrel, 100, 140, 1, 600),
                new PreparedMaterialBatch.Move(source.east(2), target.east(2), stone, stone, 100, 140, 1, 2500)));
        long destination = SectionPos.asLong(target);
        long[] due = new long[24]; due[section] = 100;
        var plan = new PreparedSourcePlan(preparation, 12, sourceChunk, -4, delta, due,
                List.of(new PreparedSourcePlan.Reference(second, destination, 1), new PreparedSourcePlan.Reference(first, destination, 0)));
        due[section] = 999; // Caller timing-array reuse must not change a published descriptor.
        return new Fixture(plan, batch);
    }

    private static void rejected(GameTestHelper h, Runnable action, String message) {
        boolean failed = false;
        try { action.run(); } catch (IllegalArgumentException expected) { failed = true; }
        h.assertTrue(failed, message);
    }

    private PreparedSourcePlanGameTests() {}
}
