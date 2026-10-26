package ua.zentix.incoming.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModTags;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.work.UnitQueue;
import ua.zentix.incoming.work.WorkScheduler;

import java.util.List;
import java.util.function.Consumer;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ImpactDebtGameTests {
    private ImpactDebtGameTests() {}

    /** Real Ignition and Shatter sweeps; the later charge must finish its front while both old sweeps still live. */
    @GameTest(template = "range", timeoutTicks = 1000, batch = "work_impact_debt", skyAccess = true)
    public static void frontPassesOldSweeps(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UnitQueue queue = StrikeWorld.get(level).impacts();
        h.startSequence().thenWaitUntil(() -> h.assertTrue(queue.isEmpty(), "previous impacts"))
                .thenExecute(() -> scene(h));
    }

    private static void scene(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var server = level.getServer();
        UnitQueue queue = StrikeWorld.get(level).impacts();
        int budget = IncomingConfig.SERVER.workBudgetMs.get();
        WorkClock previous = WorkScheduler.impactClock(server);
        WorkClock clock = WorkClock.counting(1_000_000L);
        IncomingConfig.SERVER.workBudgetMs.set(3);
        WorkScheduler.useImpactClock(server, clock);
        StrikeGameTests.afterTest(h, () -> {
            queue.finish(level);
            IncomingConfig.SERVER.workBudgetMs.set(budget);
            WorkScheduler.useImpactClock(server, previous);
        });
        for (int x = 16; x < 48; x++) for (int z = 16; z < 48; z++) h.setBlock(new BlockPos(x, 11, z), Blocks.GLASS);
        BlockPos wall = h.absolutePos(new BlockPos(34, 14, 30));
        for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) level.setBlock(wall.offset(0, y, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
        Vec3 oldAt = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 28, 32)));
        BlastArea oldArea = BlastArea.hold(level, oldAt, 14);
        Tracked ignition = new Tracked(new Craters.Ignition(oldArea.retain(), List.of(), () -> true, oldAt, 300));
        Tracked shatter = new Tracked(new Shatter(oldArea.retain(), List.of(), oldAt,
                new Shatter.Box(ModTags.SHATTERS, h.absolutePos(new BlockPos(16, 11, 16)), h.absolutePos(new BlockPos(47, 11, 47)))));
        oldArea.release(level);
        queue.add(level, ignition);
        queue.add(level, shatter);
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(31, 16, 32)));
        long born = level.getGameTime();
        long[] firstBlock = {-1}, mainDone = {-1}, summary = {-1};
        StagedExplosion[] main = {null};
        Consumer<BlockEvent.NeighborNotifyEvent> removed = e -> {
            BlockPos p = e.getPos();
            if (e.getLevel() == level && p.getX() == wall.getX() && p.getY() >= wall.getY() && p.getY() < wall.getY() + 5
                    && p.getZ() >= wall.getZ() && p.getZ() < wall.getZ() + 5 && e.getState().isAir() && firstBlock[0] < 0) {
                firstBlock[0] = level.getGameTime() - born;
            }
        };
        NeoForge.EVENT_BUS.addListener(removed);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(removed));
        h.runAfterDelay(2, () -> {
            BlastArea area = BlastArea.hold(level, at, 14);
            main[0] = new StagedExplosion(area, List.of(), at, 40.0, SlabBreach.Placement.NONE, null, true, null, null);
            area.queued(main[0], level.getGameTime());
            queue.add(level, new Tracked(main[0]) {
                @Override public boolean step(ServerLevel world) {
                    boolean more = super.step(world);
                    if (!more) mainDone[0] = world.getGameTime() - born;
                    return more;
                }
                @Override public void end(ServerLevel world) {
                    super.end(world); // releases the final area reference and emits Strike summary
                    summary[0] = world.getGameTime() - born;
                }
            });
        });
        h.succeedWhen(() -> {
            h.assertTrue(main[0] != null && main[0].done(), "main front still pending");
            h.assertTrue(firstBlock[0] >= 0, "front removed no wall block");
            h.assertTrue(!ignition.ended && !shatter.ended, "old sweep completed before the new main front");
            h.assertTrue(firstBlock[0] <= mainDone[0] && mainDone[0] <= summary[0], "phase order");
            h.assertTrue(clock.maxTickNanos() <= 4_000_000L, "shared budget exceeded");
            Incoming.LOG.info("PL10 PHASES first_block={} main_done={} summary={} old_ignition_done={} old_shatter_done={}",
                    firstBlock[0], mainDone[0], summary[0], ignition.ended, shatter.ended);
        });
    }

    private static class Tracked implements UnitQueue.Job {
        private final UnitQueue.Job job;
        boolean ended;
        Tracked(UnitQueue.Job job) { this.job = job; }
        @Override public boolean ready(ServerLevel level) { return job.ready(level); }
        @Override public boolean blocked() { return job.blocked(); }
        @Override public int unitKind() { return job.unitKind(); }
        @Override public boolean step(ServerLevel level) { return job.step(level); }
        @Override public void end(ServerLevel level) { job.end(level); ended = true; }
        @Override public String describe() { return job.describe(); }
    }
}
