package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.model.Yield;

import java.util.List;
import java.util.Map;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinMemoryFlowGameTests {
    private RuinMemoryFlowGameTests() {}

    private static Detonation event(GameTestHelper h, BlockPos at, float scale) {
        var level = h.getLevel();
        double height = Yield.optimalBurstHeight(15);
        double radius = ua.zentix.incoming.nuclear.model.HobBlast.rangeFor(1, height, 15);
        var d = new Detonation(NuclearEvents.get(level).nextId(), Vec3.atCenterOf(at).add(-radius * scale, height * scale, 0),
                at.getY(), 15, false, level.getGameTime() - 10000, 0, 0, 20000, 42, scale, false, .25f, 6000, false);
        NuclearEvents.get(level).add(d);
        return d;
    }

    @GameTest(template = "range", batch = "nuke_memory_flow", timeoutTicks = 100, skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void waitingPlanCannotStarveMaterialThatFreesItsCapacity(GameTestHelper h) throws ReflectiveOperationException {
        var level = h.getLevel();
        NuclearStrikes.clear(level);
        RuinMatterGameTests.clearFixture(level);
        var world = NuclearWorld.get(level);
        var clock = WorkClock.counting(1_000_000);
        clock.start(4_000_000); world.tick(level, clock); // restore before registering the controlled event
        RuinWorkers.executor(RuinWorkers.Priority.NEAR); RuinWorkers.shutdown();
        long time = level.getGameTime();
        BlockPos at = h.absolutePos(new BlockPos(24, 30, 24));
        BlockPos landing = at.east(16);
        level.setBlock(at.below(), Blocks.OBSIDIAN.defaultBlockState(), 2);
        level.setBlock(at, Blocks.GLASS.defaultBlockState(), 2);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            level.setBlock(landing.below().offset(x, 0, z), Blocks.OBSIDIAN.defaultBlockState(), 2);
        level.setBlock(landing, Blocks.AIR.defaultBlockState(), 2);
        var d = event(h, at, 1);
        var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
        var plan = RuinPlanner.plan(level, d, chunk);
        h.assertTrue(plan != RuinPlan.EMPTY && plan.changedBlocks() > 0, "Fixture has no destructive plan");
        var plans = new Long2ObjectOpenHashMap<RuinPlan>(); plans.put(chunk.getPos().toLong(), plan);
        var prepared = ScarQueue.class.getDeclaredField("prepared"); prepared.setAccessible(true);
        ((Map<Integer, Long2ObjectOpenHashMap<RuinPlan>>) prepared.get(world.scars())).put(d.id(), plans);
        world.scars().offer(chunk, d);
        CompoundTag cargo = new CompoundTag(); cargo.putString("fixture_payload", "x".repeat(12000));
        var falling = new RuinMatter.Piece(landing, Blocks.STONE.defaultBlockState(), cargo, Vec3.ZERO, time - 1);
        falling.y += 1;
        var ledger = RuinMatter.get(level); ledger.release(List.of(falling));
        world.ruins(level, d, time); // include context ownership before occupying the remaining room
        var other = level.getServer().getLevel(Level.NETHER);
        try (var occupied = RuinMemory.reserve(other, NuclearPrep.PLANS_CAP - NuclearPrep.memory(other, false) - 4096)) {
            h.assertTrue(occupied != null, "Could not occupy the controlled budget");
            h.assertFalse(plan.apply(level, chunk, d, new ColumnScar.Budget(false)), "Fixture plan did not initially wait");
            h.assertTrue(plan.memoryWaiting(), "Fixture plan was stale rather than capacity-blocked");
            for (int i = 0; i < 160 && !level.getBlockState(at).isAir(); i++) {
                ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(time + i + 1);
                clock.start(4_000_000); world.tick(level, clock);
            }
            h.assertTrue(ledger.deposited() == 1, "Waiting scar starved the existing moving piece");
            h.assertTrue(level.getBlockState(at).isAir() && ledger.released() == 2,
                    "Freed capacity did not apply the same pending plan exactly once");
        } finally {
            ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(time);
            NuclearStrikes.clear(level); RuinMatterGameTests.clearFixture(level);
        }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_memory_cache", timeoutTicks = 100, skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void waitingPlanCannotBlockTheCacheTrimThatFreesItsCapacity(GameTestHelper h) throws ReflectiveOperationException {
        var level = h.getLevel();
        NuclearStrikes.clear(level);
        RuinMatterGameTests.clearFixture(level);
        var world = NuclearWorld.get(level);
        var clock = WorkClock.counting(1_000_000);
        clock.start(4_000_000); world.tick(level, clock); // restore before registering the controlled event
        RuinWorkers.executor(RuinWorkers.Priority.NEAR); RuinWorkers.shutdown();
        long time = level.getGameTime();
        BlockPos at = h.absolutePos(new BlockPos(24, 30, 24));
        level.setBlock(at.below(), Blocks.OBSIDIAN.defaultBlockState(), 2);
        level.setBlock(at, Blocks.GLASS.defaultBlockState(), 2);
        var d = event(h, at, 1);
        var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
        var plan = RuinPlanner.plan(level, d, chunk);
        h.assertTrue(plan != RuinPlan.EMPTY && plan.changedBlocks() > 0, "Fixture has no destructive plan");
        var plans = new Long2ObjectOpenHashMap<RuinPlan>(); plans.put(chunk.getPos().toLong(), plan);
        var prepared = ScarQueue.class.getDeclaredField("prepared"); prepared.setAccessible(true);
        ((Map<Integer, Long2ObjectOpenHashMap<RuinPlan>>) prepared.get(world.scars())).put(d.id(), plans);
        world.scars().offer(chunk, d);
        var ledger = RuinMatter.get(level);
        var context = world.ruins(level, d, time);
        context.grid(level, chunk.getPos(), false);
        long cached = context.shotBytes();
        h.assertTrue(cached > 100_000, "Fixture lacks re-readable snapshots");
        var other = level.getServer().getLevel(Level.NETHER);
        try (var occupied = RuinMemory.reserve(other, NuclearPrep.PLANS_CAP - NuclearPrep.memory(other, false) - 2048)) {
            h.assertTrue(occupied != null, "Could not occupy the controlled budget");
            h.assertFalse(plan.apply(level, chunk, d, new ColumnScar.Budget(false)), "Fixture plan did not initially wait");
            h.assertTrue(plan.memoryWaiting(), "Fixture plan was stale rather than capacity-blocked");
            for (int i = 0; i < 160 && !level.getBlockState(at).isAir(); i++) {
                ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(time + i + 1);
                clock.start(4_000_000); world.tick(level, clock);
            }
            h.assertTrue(context.shotBytes() < cached, "Admission blocked the cache trim needed to free capacity");
            h.assertTrue(level.getBlockState(at).isAir() && ledger.released() == 1,
                    "Trimmed capacity did not apply the retained plan exactly once");
            for (int i = 0; i < 8; i++) {
                ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(time + 161 + i);
                clock.start(4_000_000); world.tick(level, clock);
            }
            h.assertTrue(ledger.released() == 1, "Retained plan applied more than once");
        } finally {
            ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(time);
            NuclearStrikes.clear(level); RuinMatterGameTests.clearFixture(level);
        }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_memory_retired_workers", timeoutTicks = 200, skyAccess = true)
    public static void retiredLargePoolCannotBlockMaterialCraterOrSettlement(GameTestHelper h) {
        var level = h.getLevel();
        NuclearStrikes.clear(level); RuinMatterGameTests.clearFixture(level);
        int configured = IncomingConfig.SERVER.nukeRuinThreads.get();
        IncomingConfig.SERVER.nukeRuinThreads.set(96);
        RuinWorkers.shutdown();
        var done = RuinWorkers.submitForTest(() -> null, RuinWorkers.Priority.NEAR);
        ua.zentix.incoming.gametest.StrikeGameTests.afterTest(h, () -> {
            RuinWorkers.shutdown(); IncomingConfig.SERVER.nukeRuinThreads.set(configured);
            NuclearStrikes.clear(level); RuinMatterGameTests.clearFixture(level);
        });
        h.succeedWhen(() -> {
            h.assertTrue(done.isDone(), "Worker task has not finished");
            h.assertTrue(RuinWorkers.shutdown(), "Finished worker did not terminate");
            try (var reservation = RuinMemory.reserve(level, 4096)) {
                h.assertTrue(reservation != null, "Nonresident configured buffers blocked material admission");
            }
            BlockPos at = h.absolutePos(new BlockPos(24, 30, 24));
            var d = event(h, at, .03f);
            var crater = new CraterJob(d, 0);
            h.assertTrue(crater.step(level, level.random) == CraterJob.Step.PROGRESS, "Retired pool blocked crater cursor preparation");
            var settle = new RuinSettle();
            var clock = WorkClock.counting(1);
            for (int i = 0; i < 10 && RuinProgress.get(level).pass(d.id()).cursor == 0; i++) {
                clock.start(256); settle.work(level, NuclearEvents.get(level), clock);
            }
            h.assertTrue(RuinProgress.get(level).pass(d.id()).cursor > 0, "Retired pool blocked settlement admission");
            settle.clear(level); crater.release(level);
        });
    }
}
