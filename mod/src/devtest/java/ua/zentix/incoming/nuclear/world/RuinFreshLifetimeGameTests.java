package ua.zentix.incoming.nuclear.world;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;

/** Fresh diagnostic contexts must release numeric owners without invalidating their returned plans. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinFreshLifetimeGameTests {
    private RuinFreshLifetimeGameTests() {}

    private static RuinContext owner(RuinPlan plan) {
        try {
            var field = RuinPlan.class.getDeclaredField("ctx");
            field.setAccessible(true);
            return (RuinContext) field.get(plan);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void repeat(GameTestHelper h, boolean window) {
        var level = h.getLevel();
        var grid = RuinStorageGameTests.fixture(h, true);
        var chunk = level.getChunkSource().getChunkNow(grid.centre().x, grid.centre().z);
        h.assertTrue(chunk != null, "Fixture centre is not loaded");
        var collapse = Collapse.solve(grid);
        RuinStorageGameTests.sameCollapse(h, ReferenceCollapse.solve(grid), collapse, "fresh helper input");
        var expected = RuinPlanner.finish(grid, collapse);
        var encoded = expected.store();
        h.assertTrue(expected.changedBlocks() > 0, "Fixture did not fracture");
        h.assertTrue(encoded.getLongArray("material").length > 0, "Fixture produced no structural material");
        long baseline = RuinResults.bytes(), inputs = RuinInputs.detachedBytes();
        try {
            for (int i = 0; i < 3; i++) {
                var reads = new int[2];
                RuinPlan plan = window ? RuinPlanner.planWithWindow(level, grid.ctx().d, chunk, reads)
                        : RuinPlanner.planFresh(level, grid.ctx().d, chunk, true);
                RuinContext ctx = owner(plan);
                try {
                    long results = RuinResults.bytes(), cache = ctx.fragmentCacheBytes();
                    Incoming.LOG.info("FRESH_LIFETIME helper={} iteration={} baseline={} results={} cache={} detached={} inputBaseline={} changed={} material={} reads={} absent={}",
                            window ? "window" : "fresh", i, baseline, results, cache, RuinInputs.detachedBytes(), inputs,
                            plan.changedBlocks(), plan.store().getLongArray("material").length, reads[0], reads[1]);
                    h.assertTrue(plan.store().equals(encoded), "Fresh helper changed full physical/material output");
                    h.assertTrue(RuinPlan.restore(plan.store().copy(), ctx).store().equals(encoded),
                            "Fresh helper store/restore changed full physical/material output");
                    h.assertTrue(plan.current(chunk, grid.ctx().d) && plan.sameBlocks(chunk),
                            "Closing fresh context invalidated old blocks or heightmap fingerprints");
                    h.assertTrue(cache == 0 && results == baseline,
                            "Fresh helper retained result/cache ownership: baseline=" + baseline + " results=" + results + " cache=" + cache);
                    h.assertTrue(RuinInputs.detachedBytes() == inputs, "Fresh helper retained detached worker inputs");
                    if (window) h.assertTrue(reads[0] == 0 && reads[1] == 0, "Loaded fixture unexpectedly needed disk input");
                    if (i == 2) {
                        h.assertTrue(plan.apply(level, chunk, grid.ctx().d, new ColumnScar.Budget(false)),
                                "Returned plan could not apply after closing its fresh context");
                        h.assertTrue(plan.store().equals(encoded), "Applying the returned plan mutated its durable output");
                    }
                } finally {
                    // Also isolate the expected-red run on an old helper: clean only the returned owner.
                    ctx.cancelTasks();
                }
            }
            h.assertTrue(RuinResults.bytes() == baseline, "Result ownership changed after apply");
            h.assertTrue(RuinInputs.detachedBytes() == inputs, "Detached input ownership changed after apply");
            h.succeed();
        } finally {
            grid.ctx().cancelTasks();
            RuinMatterGameTests.clearFixture(level);
        }
    }

    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_fresh_lifetime", skyAccess = true)
    public static void freshBackgroundContextReleasesOwners(GameTestHelper h) { repeat(h, false); }

    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_window_lifetime", skyAccess = true)
    public static void freshWindowContextReleasesOwners(GameTestHelper h) { repeat(h, true); }
}
