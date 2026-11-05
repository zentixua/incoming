package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Clearable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;

import java.util.List;
import java.util.UUID;

/** Real block/cargo commits and a pending region-page reopen, not a whole-server restart acceptance. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedMaterialCommitGameTests {
    private static final int[] FIRST = {0};

    @GameTest(template = "range", batch = "nuke_prepared_commit_reopen", timeoutTicks = 200, skyAccess = true)
    public static void cargoSurvivesPendingPageReopenAndLateDestination(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        BlockPos source = source(h), destination = source.offset(16, -4, 0);
        var state = Blocks.BARREL.defaultBlockState();
        level.setBlock(source, state, 2);
        var entity = level.getBlockEntity(source);
        ((Container) entity).setItem(0, new ItemStack(Items.DIAMOND, 3));
        var batch = batch(source, destination, state);
        // Preparation predates the actual origin; no page/row rewrite is needed when it becomes known.
        long actualOrigin = level.getGameTime() + 4_000_000;
        var planned = batch.move(0);
        // Contents edited after planning must be captured at departure, not resurrect the old three.
        ((Container) entity).setItem(0, new ItemStack(Items.DIAMOND, 7));
        h.assertTrue(PreparedMaterialCommit.depart(level, batch, FIRST, actualOrigin + 99 - actualOrigin) == 0
                && ((Container) entity).getItem(0).getCount() == 7, "Material departed before its causal tick");
        h.assertTrue(PreparedMaterialCommit.depart(level, batch, FIRST, actualOrigin + 100 - actualOrigin) == 1
                && level.getBlockState(source).isAir() && batch.ownsMaterial(0), "Source removal did not transfer ownership");
        var saved = batch.snapshot();
        var root = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data/prepared-commit-test-" + UUID.randomUUID());
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_prepared_material");
        var pages = new RuinPages(info, root);
        StrikeGameTests.afterTest(h, () -> pages.close());
        var reopened = pages.write(batch.owner().toLong(), saved::encode).thenCompose(unused -> pages.flush())
                .thenCompose(unused -> pages.close()).thenApply(unused -> new RuinPages(info, root));
        var read = reopened.thenCompose(store -> store.read(batch.owner().toLong())
                .thenCompose(tag -> store.close().thenApply(unused -> tag)));
        h.startSequence().thenWaitUntil(() -> h.assertTrue(read.isDone(), "Waiting for pending material page reopen"))
                .thenExecute(() -> {
                    var restored = PreparedMaterialBatch.decode(read.join().orElseThrow());
                    h.assertTrue(restored.plan().equals(batch.plan()) && restored.move(0).equals(planned),
                            "Binding or reload rewrote preparation identity or relative times");
                    h.assertTrue(PreparedMaterialCommit.land(level, restored, FIRST, actualOrigin + 139 - actualOrigin, true) == 0,
                            "Material landed before its computed time");
                    h.assertTrue(PreparedMaterialCommit.land(level, restored, FIRST, 1000, false) == 0
                            && level.getBlockState(destination).isAir(), "Material bypassed destination scar phase");
                    h.assertTrue(PreparedMaterialCommit.land(level, restored, FIRST, 1000, true) == 1,
                            "Late destination did not realize the saved operation");
                    var placed = level.getBlockEntity(destination);
                    h.assertTrue(placed instanceof Container && ((Container) placed).getItem(0).is(Items.DIAMOND)
                            && ((Container) placed).getItem(0).getCount() == 7, "Late destination lost exact edited cargo");
                    h.assertTrue(PreparedMaterialCommit.land(level, restored, FIRST, 2000, true) == 0
                            && restored.finished() && !restored.ownsMaterial(0), "Completed operation replayed");
                }).thenSucceed();
    }

    @GameTest(template = "range", batch = "nuke_prepared_commit_failure", skyAccess = true)
    public static void callbackFailureReconcilesCargoBeforeAndAfterSourceRemoval(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos source = source(h), destination = source.offset(16, -4, 0);
        var state = Blocks.BARREL.defaultBlockState();
        level.setBlock(source, state, 2);
        var entity = level.getBlockEntity(source);
        ((Container) entity).setItem(0, new ItemStack(Items.EMERALD, 5));
        var batch = batch(source, destination, state);
        batch.setCapacity(batch.retainedBytes());
        h.assertTrue(PreparedMaterialCommit.depart(level, batch, FIRST, 100) == 0
                && ((Container) entity).getItem(0).getCount() == 5, "Unfunded cargo was removed before admission");
        batch.setCapacity(Long.MAX_VALUE);
        try {
            PreparedMaterialCommit.depart(level, batch, FIRST, 100, (world, at, before, after) -> {
                Clearable.tryClear(world.getBlockEntity(at));
                throw new IllegalStateException("Controlled failure before source state change");
            });
            h.fail("Expected pre-removal callback failure");
        } catch (IllegalStateException expected) {
            h.assertTrue(expected.getMessage().startsWith("Controlled failure before"), "Unexpected failure");
        }
        h.assertTrue(batch.phase(0) == PreparedMaterialBatch.Phase.PLANNED && !batch.ownsMaterial(0)
                && ((Container) entity).getItem(0).getCount() == 5, "Failed source callback lost or duplicated cargo ownership");
        try {
            PreparedMaterialCommit.depart(level, batch, FIRST, 100, (world, at, before, after) -> {
                ColumnScar.replaceState(world, at, before, after);
                throw new IllegalStateException("Controlled failure after source state change");
            });
            h.fail("Expected post-removal callback failure");
        } catch (IllegalStateException expected) {
            h.assertTrue(expected.getMessage().startsWith("Controlled failure after"), "Unexpected failure");
        }
        h.assertTrue(level.getBlockState(source).isAir() && batch.ownsMaterial(0), "Removed source lost its escrow on exception");
        var restored = PreparedMaterialBatch.decode(batch.snapshot().encode());
        h.assertTrue(PreparedMaterialCommit.land(level, restored, FIRST, 1000, true) == 1
                && ((Container) level.getBlockEntity(destination)).getItem(0).getCount() == 5,
                "Exception-owned contents did not survive restore and placement");
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_prepared_commit_edits", skyAccess = true)
    public static void localEditsAndDispersedMaterialKeepIndependentOwnership(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos source = source(h), destination = source.offset(16, -4, 0);
        var state = Blocks.STONE.defaultBlockState();
        level.setBlock(source, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        var edited = batch(source, destination, state);
        h.assertTrue(PreparedMaterialCommit.depart(level, edited, FIRST, 100) == 0
                && edited.phase(0) == PreparedMaterialBatch.Phase.SOURCE_CHANGED
                && level.getBlockState(source).is(Blocks.GOLD_BLOCK), "Source edit was overwritten or acquired twice");
        level.setBlock(source, state, 2);
        var moved = batch(source, destination, state);
        PreparedMaterialCommit.depart(level, moved, FIRST, 100);
        level.setBlock(destination, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
        h.assertTrue(PreparedMaterialCommit.land(level, moved, FIRST, 1000, true) == 0
                && moved.ownsMaterial(0) && moved.phase(0) == PreparedMaterialBatch.Phase.DESTINATION_CHANGED
                && level.getBlockState(destination).is(Blocks.DIAMOND_BLOCK), "Destination edit consumed saved material or was overwritten");
        BlockPos glass = source.above();
        level.setBlock(glass, Blocks.GLASS.defaultBlockState(), 2);
        var fine = new PreparedMaterialBatch(UUID.randomUUID(), new ChunkPos(glass), List.of(new PreparedMaterialBatch.Move(glass, glass,
                Blocks.GLASS.defaultBlockState(), Blocks.GLASS.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                100, 100, 1, 2500, true)));
        h.assertTrue(PreparedMaterialCommit.depart(level, fine, FIRST, 100) == 1
                && fine.phase(0) == PreparedMaterialBatch.Phase.DISPERSED && fine.finished()
                && !fine.ownsMaterial(0) && level.getBlockState(glass).isAir(), "Fine material did not enter its terminal reservoir");
        var restored = PreparedMaterialBatch.decode(fine.snapshot().encode());
        h.assertTrue(restored.phase(0) == PreparedMaterialBatch.Phase.DISPERSED && restored.move(0).mass() == 2500
                && PreparedMaterialCommit.land(level, restored, FIRST, 1000, true) == 0,
                "Dispersed mass vanished from accounting or became a rigid block again");
        BlockPos wetSource = source.above(2), dryDestination = destination.offset(0, 0, 2);
        var wetState = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        var dryState = wetState.setValue(BlockStateProperties.WATERLOGGED, false);
        level.setBlock(wetSource, wetState, 2);
        var wet = new PreparedMaterialBatch(UUID.randomUUID(), new ChunkPos(dryDestination), List.of(new PreparedMaterialBatch.Move(
                wetSource, dryDestination, wetState, dryState, 100, 140, .75, 450)));
        h.assertTrue(PreparedMaterialCommit.depart(level, wet, FIRST, 100) == 1
                && level.getBlockState(wetSource).is(Blocks.WATER), "Source removal lost its waterlogged fluid");
        h.assertTrue(PreparedMaterialCommit.land(level, wet, FIRST, 1000, true) == 1
                && level.getBlockState(dryDestination) == dryState, "Wet source did not preserve exact dry transported state");
        h.succeed();
    }

    private static PreparedMaterialBatch batch(BlockPos source, BlockPos target, BlockState state) {
        return new PreparedMaterialBatch(UUID.randomUUID(), new ChunkPos(target), List.of(new PreparedMaterialBatch.Move(source, target,
                state, state, 100, 140, 1, 600)));
    }

    private static BlockPos source(GameTestHelper h) {
        BlockPos at = h.absolutePos(new BlockPos(20, 64, 20));
        return new BlockPos((at.getX() & ~15) + 5, (at.getY() & ~15) + 12, (at.getZ() & ~15) + 5);
    }

    private PreparedMaterialCommitGameTests() {}
}
