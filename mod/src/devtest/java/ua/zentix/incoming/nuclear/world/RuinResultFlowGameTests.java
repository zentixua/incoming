package ua.zentix.incoming.nuclear.world;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;

/** Actual pool/solver and consumer transitions, beyond the numeric lease component. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinResultFlowGameTests {
    private RuinResultFlowGameTests() {}

    @GameTest(template = "pad", batch = "nuke_result_flow", timeoutTicks = 400, skyAccess = true)
    public static void outputAdmissionAndCompletedConsumerTransfer(GameTestHelper h) {
        var level = h.getLevel();
        var ctx = StructuralRuinGameTests.fixture(level, StructuralRuinGameTests.hall(Blocks.STONE_BRICKS), 20);
        var grid = ctx.cachedGridForTest(level, new ChunkPos(0, 0));
        long baseline = RuinResults.bytes();
        long room = NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level);
        var pressure = RuinResults.tryReserve(room - RuinResults.OWNER_BYTES, room);
        h.assertTrue(pressure != null, "Could not reserve the actual remaining producer room");
        var called = new AtomicBoolean();
        try {
            var refused = ctx.submitPlanWorker(level, grid, () -> { called.set(true); return RuinPlan.EMPTY; }, RuinWorkers.Priority.NEAR);
            h.assertTrue(refused == null && !called.get(), "Solver ran before its output could be admitted");
        } finally { pressure.abortBeforeSubmit(); }
        var future = ctx.submitPlanWorker(level, grid, () -> RuinPlanner.finish(grid, Collapse.solve(grid)), RuinWorkers.Priority.NEAR);
        h.assertTrue(future != null, "Output did not retry when shared room returned");
        StrikeGameTests.afterTest(h, () -> ctx.cancelWorker(future, true));
        h.succeedWhen(() -> {
            h.assertTrue(RuinWorkers.finished(future), "Actual solver still running");
            try {
                var raw = future.get();
                h.assertTrue(raw != RuinPlan.EMPTY && raw.changedBlocks() > 0, "Fixture has no actual material output");
                h.assertTrue(RuinResults.bytes() == baseline + raw.bytes() + RuinResults.OWNER_BYTES,
                        "Completed output or temporary fragments have the wrong owner");
                var plan = ctx.takeWorker(future);
                h.assertTrue(RuinResults.bytes() > baseline, "Collection refunded output before the consumer counted it");
                var plans = new RetainedPlans();
                plans.put(0, plan);
                h.assertTrue(plans.bytes() >= plan.bytes() && RuinResults.bytes() == baseline,
                        "Counted consumer did not replace the producer reservation");
                h.assertTrue(plan.differs(RuinPlanner.finish(grid, Collapse.solve(grid))) == null,
                        "Ownership changed the same-input physical/material output");
                plans.clear();
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
    }

    @GameTest(template = "pad", batch = "nuke_result_cancel", timeoutTicks = 400, skyAccess = true)
    public static void cancelledFutureKeepsProducerUntilActualExit(GameTestHelper h) {
        var level = h.getLevel();
        var ctx = StructuralRuinGameTests.fixture(level, StructuralRuinGameTests.hall(Blocks.STONE_BRICKS), 20);
        var grid = ctx.cachedGridForTest(level, new ChunkPos(0, 0));
        long baseline = RuinResults.bytes();
        var gate = new CountDownLatch(1);
        var entered = new AtomicBoolean();
        var future = ctx.submitPlanWorker(level, grid, () -> {
            entered.set(true);
            while (gate.getCount() != 0) {
                try { gate.await(1, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Deliberately model a callable finishing after cancellation. */ }
            }
            return RuinPlanner.finish(grid, Collapse.solve(grid));
        }, RuinWorkers.Priority.NEAR);
        h.assertTrue(future != null, "Producer admission unexpectedly refused");
        StrikeGameTests.afterTest(h, () -> { gate.countDown(); ctx.cancelWorker(future, true); });
        boolean[] cancelled = {false};
        h.succeedWhen(() -> {
            h.assertTrue(entered.get(), "Callable has not started");
            if (!cancelled[0]) {
                ctx.cancelWorker(future, true);
                cancelled[0] = true;
                h.assertTrue(future.isCancelled() && !RuinWorkers.finished(future), "Fixture missed the running cancellation boundary");
                h.assertTrue(RuinResults.bytes() > baseline, "Future cancellation lent a running producer's reservation");
                gate.countDown();
            }
            h.assertTrue(RuinWorkers.finished(future), "Actual callable has not exited");
            h.assertTrue(RuinResults.bytes() == baseline, "Abandoned output retained a producer reservation after actual exit");
        });
    }
}
