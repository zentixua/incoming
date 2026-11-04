package ua.zentix.incoming.nuclear.world;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;

/** Shared reuse is optional, physically identical, and never owns a running reader's final reference. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinFragmentCacheGameTests {
    private RuinFragmentCacheGameTests() {}

    @GameTest(template = "pad", batch = "nuke_fragment_reuse", timeoutTicks = 400, skyAccess = true)
    public static void repeatedActualSolverReusesBoundedFragments(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        var ctx = StructuralRuinGameTests.fixture(level, StructuralRuinGameTests.hall(Blocks.STONE_BRICKS), 20);
        var grid = ctx.cachedGridForTest(level, new ChunkPos(0, 0));
        var expected = RuinPlanner.finish(grid, Collapse.solve(grid));
        var adjacent = ctx.cachedGridForTest(level, new ChunkPos(1, 0));
        var adjacentReference = ReferenceCollapse.solve(adjacent);
        var adjacentExpected = RuinPlanner.finish(adjacent, Collapse.solve(adjacent));
        long baseline = RuinResults.bytes();
        StrikeGameTests.afterTest(h, ctx::cancelTasks);
        var first = ctx.submitPlanWorker(level, grid, () -> RuinPlanner.finish(grid, Collapse.solve(grid)), RuinWorkers.Priority.NEAR);
        h.assertTrue(first != null, "First producer was not admitted");
        var second = new AtomicReference<java.util.concurrent.Future<RuinPlan>>();
        var third = new AtomicReference<java.util.concurrent.Future<RuinPlan>>();
        var adjacentActual = new AtomicReference<Collapse>();
        long[] hits = {-1};
        h.succeedWhen(() -> {
            try {
                RuinFragmentCache cache = cache(ctx);
                h.assertTrue(cache != null, "Optional cache was not admitted despite an empty shared budget");
                if (second.get() == null) {
                    h.assertTrue(RuinWorkers.finished(first), "First actual solver still running");
                    RuinPlan result = ctx.takeWorker(first);
                    try { h.assertTrue(expected.differs(result) == null, "First physical/material result differs"); }
                    finally { result.discardResult(); }
                    h.assertTrue(cache.size() > 0 && cache.retainedBytes() <= (16L << 20) && cache.retainedBytes() <= cache.reservedBytes(), "Cache exceeded its prepaid ownership");
                    hits[0] = cache.hits();
                    second.set(ctx.submitPlanWorker(level, grid, () -> RuinPlanner.finish(grid, Collapse.solve(grid)), RuinWorkers.Priority.NEAR));
                    h.assertTrue(second.get() != null, "Repeated solver was not admitted");
                }
                if (third.get() == null) {
                    h.assertTrue(RuinWorkers.finished(second.get()), "Repeated actual solver still running");
                    RuinPlan result = ctx.takeWorker(second.get());
                    try { h.assertTrue(expected.differs(result) == null, "Cached physical/material result differs"); }
                    finally { result.discardResult(); }
                    h.assertTrue(cache.hits() >= hits[0] + 9, "Repeated actual solve did not reuse all nine same-input fragments");
                    hits[0] = cache.hits();
                    third.set(ctx.submitPlanWorker(level, adjacent, () -> {
                        Collapse solved = Collapse.solve(adjacent);
                        adjacentActual.set(solved);
                        return RuinPlanner.finish(adjacent, solved);
                    }, RuinWorkers.Priority.NEAR));
                    h.assertTrue(third.get() != null, "Overlapping window solver was not admitted");
                }
                h.assertTrue(RuinWorkers.finished(third.get()), "Overlapping actual solver still running");
                RuinPlan result = ctx.takeWorker(third.get());
                try { h.assertTrue(adjacentExpected.differs(result) == null, "Overlapping physical/material plan differs"); }
                finally { result.discardResult(); }
                RuinStorageGameTests.sameCollapse(h, adjacentReference, adjacentActual.get(), "Actual overlapping cache window");
                h.assertTrue(cache.hits() >= hits[0] + 6, "Overlapping window did not reuse its six shared fractures");
                ctx.cancelTasks();
                h.assertTrue(RuinResults.bytes() == baseline, "Closed context retained its cache/output ownership");
            } catch (ReflectiveOperationException | java.util.concurrent.ExecutionException | InterruptedException error) {
                throw new IllegalStateException(error);
            }
        });
    }

    @GameTest(template = "pad", batch = "nuke_fragment_lifetime", timeoutTicks = 400, skyAccess = true)
    public static void evictionAndContextCancelKeepBorrowedFragmentOwned(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        var ctx = StructuralRuinGameTests.fixture(level, StructuralRuinGameTests.hall(Blocks.STONE_BRICKS), 20);
        var grid = ctx.cachedGridForTest(level, new ChunkPos(0, 0));
        long largest = 0;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            largest = Math.max(largest, RuinFragmentCache.entryBytes(grid.blast(dx, dz)));
        var limit = RuinContext.class.getDeclaredField("fragmentCacheLimit"); limit.setAccessible(true); limit.setLong(ctx, largest);
        long baseline = RuinResults.bytes();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        StrikeGameTests.afterTest(h, () -> { release.countDown(); ctx.cancelTasks(); });
        var future = ctx.submitPlanWorker(level, grid, () -> {
            try {
                Blast borrowed = grid.blast(0, 0);
                var cache = cache(ctx);
                if (cache.get(0, borrowed.stamp(), grid.blockDamage(), grid.treeFall()) != borrowed)
                    throw new AssertionError("Borrowed entry was not cached before eviction");
                int[] before = borrowed.removed().clone();
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
                    if (dx != 0 || dz != 0) grid.blast(dx, dz);
                if (cache.get(0, borrowed.stamp(), grid.blockDamage(), grid.treeFall()) != null) throw new AssertionError("Fixture did not evict the borrowed centre");
                entered.countDown();
                while (release.getCount() != 0) {
                    try { release.await(1, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { /* Keep the actual borrowed reference alive after cancellation. */ }
                }
                if (!Arrays.equals(before, borrowed.removed())) throw new AssertionError("Eviction/cancel changed borrowed fracture data");
                cache.put(0, borrowed, grid.blockDamage(), grid.treeFall()); // A late producer may not reopen the closed cache.
                if (cache.size() != 0) throw new AssertionError("Late publication reopened a closed cache");
                return RuinPlan.EMPTY;
            } catch (Throwable error) { failure.set(error); throw error; }
        }, RuinWorkers.Priority.NEAR);
        h.assertTrue(future != null, "Borrowing producer was not admitted");
        boolean[] cancelled = {false};
        h.succeedWhen(() -> {
            h.assertTrue(failure.get() == null, "Borrowing worker failed: " + failure.get());
            h.assertTrue(entered.getCount() == 0, "Actual borrowing worker not ready");
            try {
                var cache = cache(ctx);
                if (!cancelled[0]) {
                    h.assertTrue(cache.retainedBytes() <= cache.reservedBytes(), "Eviction exceeded its prepaid cache budget");
                    ctx.cancelTasks();
                    cancelled[0] = true;
                    h.assertTrue(future.isCancelled() && !RuinWorkers.finished(future), "Context cancellation missed the running reader");
                    h.assertTrue(cache.size() == 0 && ctx.fragmentCacheBytes() == 0, "Context did not release its optional cache");
                    h.assertTrue(RuinResults.bytes() > baseline, "Closing the cache refunded the running reader's fragment permit");
                    release.countDown();
                }
                h.assertTrue(RuinWorkers.finished(future), "Actual borrowed reference still in use");
                h.assertTrue(failure.get() == null, "Borrowed fragment failed: " + failure.get());
                h.assertTrue(RuinResults.bytes() == baseline, "Actual reader exit retained its permit");
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        });
    }

    @GameTest(template = "pad", batch = "nuke_fragment_optional", timeoutTicks = 400, skyAccess = true)
    public static void cacheRefusalDoesNotBlockAnAdmittedSolver(GameTestHelper h) {
        var level = h.getLevel();
        var ctx = StructuralRuinGameTests.fixture(level, StructuralRuinGameTests.hall(Blocks.STONE_BRICKS), 20);
        var grid = ctx.cachedGridForTest(level, new ChunkPos(0, 0));
        long room = NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level);
        long result = RuinPlan.maxResultBytes(grid) + RuinPlan.maxFragmentBytes(grid) + RuinResults.OWNER_BYTES;
        long startup = RuinWorkers.threadBufferBytes(level.getHeight());
        long fixed = room - result - startup - (8L << 20);
        h.assertTrue(fixed > RuinResults.OWNER_BYTES, "No room for the held-pressure fixture");
        var held = RuinResults.tryReserveCache(fixed - RuinResults.OWNER_BYTES, room);
        h.assertTrue(held != null, "Could not establish held producer pressure");
        long submittedAt = System.nanoTime();
        var enteredAt = new AtomicLong();
        var exitedAt = new AtomicLong();
        var worker = new AtomicReference<Thread>();
        var failure = new AtomicReference<Throwable>();
        var submitted = new AtomicReference<Future<RuinPlan>>();
        StrikeGameTests.afterTest(h, () -> {
            try {
                if (h.testInfo.hasFailed()) {
                    Future<RuinPlan> task = submitted.get();
                    Thread thread = worker.get();
                    // Direct counters only: residentMemory services ownership and is not a passive probe.
                    Incoming.LOG.error("Uncached solver terminal: tick={} elapsed_ns={} entered_ns={} exited_ns={} "
                                    + "admitted={} started={} done={} cancelled={} finished={} worker_error={} "
                                    + "held={} initial_room={} result_bound={} startup={} results={} detached={} cache={} "
                                    + "inflight={} workers={}",
                            level.getGameTime(), System.nanoTime() - submittedAt,
                            enteredAt.get() == 0 ? -1 : enteredAt.get() - submittedAt,
                            exitedAt.get() == 0 ? -1 : exitedAt.get() - submittedAt,
                            task != null, task != null && RuinWorkers.started(task), task != null && task.isDone(),
                            task != null && task.isCancelled(), task != null && RuinWorkers.finished(task),
                            String.valueOf(failure.get()), held.bytes(), room, result, startup, RuinResults.bytes(),
                            RuinInputs.detachedBytes(), ctx.fragmentCacheBytes(), RuinWorkers.inFlight(), RuinWorkers.summary());
                    if (thread != null) Incoming.LOG.error("Uncached solver worker: name={} state={} stack={}",
                            thread.getName(), thread.getState(), Arrays.toString(thread.getStackTrace()));
                }
            } finally { ctx.cancelTasks(); held.close(); }
        });
        var future = ctx.submitPlanWorker(level, grid, () -> {
            worker.set(Thread.currentThread());
            enteredAt.set(System.nanoTime());
            try { return RuinPlanner.finish(grid, Collapse.solve(grid)); }
            catch (Throwable error) { failure.set(error); throw error; }
            finally { exitedAt.set(System.nanoTime()); }
        }, RuinWorkers.Priority.NEAR);
        submitted.set(future);
        h.assertTrue(future != null && ctx.fragmentCacheBytes() == 0, "Optional cache blocked the admitted uncached solver");
        h.succeedWhen(() -> {
            h.assertTrue(RuinWorkers.finished(future), "Uncached solver has not progressed with pressure still held");
            try {
                RuinPlan plan = ctx.takeWorker(future);
                try { h.assertTrue(plan.changedBlocks() > 0, "Held-pressure solver produced no actual physical output"); }
                finally { plan.discardResult(); }
                h.assertTrue(ctx.fragmentCacheBytes() == 0 && held.bytes() == fixed && RuinResults.bytes() >= fixed,
                        "Cache allocated or pressure was refunded before real output completed");
            } catch (java.util.concurrent.ExecutionException | InterruptedException error) { throw new IllegalStateException(error); }
        });
    }

    @GameTest(template = "pad", batch = "nuke_fragment_policy", timeoutTicks = 400, skyAccess = true)
    public static void sameSnapshotCacheUsesCapturedDamageAndTreePolicy(GameTestHelper h) {
        var level = h.getLevel();
        var ctx = StructuralRuinGameTests.fixture(level, StructuralRuinGameTests.tree(30, true), 20);
        var base = ctx.cachedGridForTest(level, new ChunkPos(0, 0));
        var grids = new java.util.ArrayList<RuinContext.Grid>();
        var references = new java.util.ArrayList<ReferenceCollapse>();
        var expected = new java.util.ArrayList<RuinPlan>();
        for (boolean[] flags : new boolean[][]{{true, true}, {false, true}, {true, false}, {true, true}}) {
            var grid = new RuinContext.Grid(ctx, base.centre(), base.minY(), base.maxY(), base.shots(), base.view(),
                    flags[0], flags[1], base.fires());
            grids.add(grid);
            references.add(ReferenceCollapse.solve(grid));
            expected.add(RuinPlanner.finish(grid, Collapse.solve(grid)));
        }
        h.assertTrue(grids.getFirst().blast(0, 0).trees().size() == 1, "Policy fixture has no falling tree");
        h.assertTrue(grids.get(1).blast(0, 0).removed().length == 0 && grids.get(1).blast(0, 0).trees().isEmpty(),
                "Disabled damage is not a discriminating policy");
        h.assertTrue(grids.get(2).blast(0, 0).trees().isEmpty(), "Disabled tree fall is not a discriminating policy");
        StrikeGameTests.afterTest(h, ctx::cancelTasks);
        var future = new AtomicReference<java.util.concurrent.Future<RuinPlan>>();
        var actual = new AtomicReference<Collapse>();
        int[] next = {0};
        h.succeedWhen(() -> {
            if (future.get() == null) {
                var grid = grids.get(next[0]);
                future.set(ctx.submitPlanWorker(level, grid, () -> {
                    Collapse result = Collapse.solve(grid); actual.set(result);
                    return RuinPlanner.finish(grid, result);
                }, RuinWorkers.Priority.NEAR));
                h.assertTrue(future.get() != null, "Policy solver not admitted");
            }
            h.assertTrue(RuinWorkers.finished(future.get()), "Policy solver still running");
            try {
                RuinPlan result = ctx.takeWorker(future.get());
                try {
                    RuinStorageGameTests.sameCollapse(h, references.get(next[0]), actual.get(), "Captured cache policy " + next[0]);
                    h.assertTrue(expected.get(next[0]).differs(result) == null, "Cached policy changed complete material plan " + next[0]);
                } finally { result.discardResult(); }
            } catch (java.util.concurrent.ExecutionException | InterruptedException error) { throw new IllegalStateException(error); }
            next[0]++;
            future.set(null);
            h.assertTrue(next[0] == grids.size(), "Policy sequence still running");
            h.assertTrue(ctx.fragmentCacheBytes() > 0, "Policy test bypassed optional cache");
        });
    }

    private static RuinFragmentCache cache(RuinContext ctx) throws ReflectiveOperationException {
        var field = RuinContext.class.getDeclaredField("fragmentCache"); field.setAccessible(true);
        return (RuinFragmentCache) field.get(ctx);
    }
}
