package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.NuclearStrikes;

/** Preparation expiry must hand existing consumers to the surviving context or retire their work explicitly. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinCompletionRetirementGameTests {
    private RuinCompletionRetirementGameTests() {}

    @GameTest(template = "range", batch = "nuke_completion_retirement", timeoutTicks = 240, skyAccess = true)
    public static void expiredPreparationTransfersPlansAndRetiresUnclaimedFarResult(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        NuclearStrikes.clear(level);
        RuinMatterGameTests.clearFixture(level);
        var reference = RuinStorageGameTests.fixture(h, true);
        var old = reference.ctx().d;
        var d = new ua.zentix.incoming.nuclear.Detonation(old.id(), old.burst(), old.groundY(), old.yieldKt(),
                old.surface(), level.getGameTime() - 20000, 0, 0, old.visibility(), old.seed(), old.scale(), false, old.wave());
        var world = NuclearWorld.get(level);
        var ctx = world.ruins(level, d, level.getGameTime());
        var centre = reference.centre();
        var far = new ChunkPos(centre.x + 1, centre.z);
        var chunk = level.getChunkSource().getChunkNow(centre.x, centre.z);
        BlockPos glass = new BlockPos(centre.getMinBlockX() + 2, (int) d.groundY() + 2, centre.getMinBlockZ() + 2);
        var plan = RuinStorageGameTests.plan(ctx.grid(level, centre, false));
        h.assertTrue(plan.changedBlocks() > 0 && chunk.getBlockState(glass).is(Blocks.GLASS), "Fixture has no glass plan");
        h.assertTrue(ctx.submitDisk(level, far), "Initial far task was not admitted");
        var source = DiskShots.parse(level, centre, ChunkSerializer.write(level, chunk));
        int[] stage = {0};
        RetainedPlans[] transferred = {null};
        long[] heldResult = {0};
        StrikeGameTests.afterTest(h, () -> {
            ScarQueue.seenForTest(null);
            NuclearStrikes.clear(level);
            RuinMatterGameTests.clearFixture(level);
            RuinWorkers.shutdown();
        });
        h.succeedWhen(() -> {
            try {
                world.ruins(level, d, level.getGameTime());
                if (stage[0] == 0) {
                    h.assertTrue(ctx.done(far.toLong()), "Actual solver has not exited");
                    heldResult[0] = RuinResults.bytes();
                    h.assertTrue(heldResult[0] > 0, "Completed result was already unowned");
                    RuinMaintenanceGameTests.completedSource(level, ctx, source);
                    Object p = ((List<?>) field(world.prep(), "preps").get(world.prep())).getFirst();
                    transferred[0] = (RetainedPlans) field(p, "plans").get(p);
                    transferred[0].put(centre.toLong(), plan);
                    ((LongArrayList) field(p, "farRunning").get(p)).add(far.toLong());
                    field(p, "handedOff").setLong(p, level.getGameTime() - 12001);
                    stage[0] = 1;
                    h.assertTrue(false, "Waiting for actual preparation expiry");
                }
                if (stage[0] == 1) {
                    h.assertTrue(((List<?>) field(world.prep(), "preps").get(world.prep())).isEmpty(), "Expired owner still retiring");
                    h.assertTrue(!ctx.running(far.toLong()) && RuinResults.bytes() < heldResult[0],
                            "Expired preparation abandoned its completed FAR result/window");
                    h.assertTrue(ctx.restoredPlans == transferred[0] && transferred[0].containsKey(centre.toLong()),
                            "Expiry discarded the still-needed prepared plan owner");
                    h.assertTrue(ctx.retainedBytes() >= transferred[0].bytes(), "Plan transfer refunded before consumer ownership");
                    ScarQueue.seenForTest(key -> key == centre.toLong());
                    world.scars().offer(chunk, d);
                    stage[0] = 2;
                }
                h.assertTrue(!world.scars().queued(centre.toLong()), "Transferred plan did not drain through the real queue");
                h.assertTrue(!chunk.getBlockState(glass).is(Blocks.GLASS) && RuinMatter.get(level).released() > 0,
                        "Expiry handoff lost actual damage/material");
                h.assertTrue(!transferred[0].containsKey(centre.toLong()), "Applied transferred plan retained a second owner");
                Incoming.LOG.info("RETIREMENT completed FAR released; retained plan applied with material {}", RuinMatter.get(level).released());
                h.succeed();
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        });
    }

    private static Field field(Object owner, String name) throws ReflectiveOperationException {
        Field result = owner.getClass().getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
