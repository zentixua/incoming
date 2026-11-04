package ua.zentix.incoming.nuclear.world;

import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;

/** Actual full solvers, with sparse centre and taller neighbouring fracture/fluid input. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinResultOwnershipGameTests {
    private RuinResultOwnershipGameTests() {}

    @GameTest(template = "pad", batch = "nuke_result_bound", timeoutTicks = 100, skyAccess = true)
    public static void sourceBoundCoversActualCollapseFlashAndScorch(GameTestHelper h) throws Exception {
        long baseline = RuinResults.bytes();
        for (double pressure : new double[]{2, 20, 50}) {
            StructuralRuinGameTests.Shape hall = StructuralRuinGameTests.hall(Blocks.STONE_BRICKS);
            var ctx = StructuralRuinGameTests.fixture(h.getLevel(), (x, y, z) -> {
                // Outside-centre tower and pool widen the true collapse/fracture height domain.
                if (x >= 16 && x <= 18 && z >= 6 && z <= 9 && y <= 145)
                    return (y == 145 ? Blocks.WATER : Blocks.GLASS).defaultBlockState();
                if (x >= 4 && x <= 11 && z >= 4 && z <= 11 && y == 79)
                    return Blocks.GRASS_BLOCK.defaultBlockState();
                if (x == 5 && z == 5 && y == 80) return Blocks.TALL_GRASS.defaultBlockState();
                return hall.at(x, y, z);
            }, pressure);
            var grid = ctx.cachedGridForTest(h.getLevel(), new ChunkPos(0, 0));
            long bound = RuinPlan.maxResultBytes(grid);
            long fullHeight = 256L * (grid.maxY() - grid.minY());
            h.assertTrue(bound < fullHeight * 40, "Sparse source lost its tighter section-domain admission");
            long fragmentBound = RuinPlan.maxFragmentBytes(grid);
            RuinPlan[] plans = {RuinPlanner.finish(grid, Collapse.solve(grid)), RuinPlanner.flash(grid), RuinPlanner.scorch(grid)};
            for (var plan : plans) {
                h.assertTrue(plan != RuinPlan.EMPTY, "Fixture did not produce an owned plan");
                h.assertTrue(plan.bytes() <= bound, "Actual full solver output exceeded bound");
                var lease = RuinResults.tryReserve(bound, bound + RuinResults.OWNER_BYTES);
                h.assertTrue(lease != null, "Result reservation unexpectedly refused");
                try {
                    lease.markResult(plan);
                    lease.bindFuture(CompletableFuture.completedFuture(plan));
                    lease.actualExit();
                    h.assertTrue(RuinResults.bytes() == baseline + plan.bytes() + RuinResults.OWNER_BYTES,
                            "Completed/uncollected output lost its charge");
                    var field = RuinPlan.class.getDeclaredField("resultOwner"); field.setAccessible(true);
                    var detached = plan.detached();
                    var restored = RuinPlan.restore(plan.store(), ctx);
                    h.assertTrue(field.get(detached) == null && field.get(restored) == null,
                            "Detached or restored plan copied mutable producer ownership");
                    h.assertTrue(plan.differs(detached) == null && plan.differs(restored) == null,
                            "Result ownership changed actual full-store payload");
                    plan.releaseResultToCountedOwner();
                    h.assertTrue(RuinResults.bytes() == baseline, "Counted consumer did not release producer charge");
                } finally { lease.cancel(); lease.actualExit(); }
            }
            long fragments = 0;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
                if (grid.at(dx, dz) != null) fragments += RuinPlan.fragmentBytes(grid.blast(dx, dz));
            h.assertTrue(fragments <= fragmentBound, "Actual retained fracture cache exceeded source bound");
            Incoming.LOG.info("Separate fragment estimate pressure={} bound={} actual={}", pressure, fragmentBound, fragments);
            Incoming.LOG.info("Result ownership fixture pressure={} bound={} ruin={} flash={} scorch={}", pressure,
                    bound, plans[0].bytes(), plans[1].bytes(), plans[2].bytes());
        }
        h.succeed();
    }
}
