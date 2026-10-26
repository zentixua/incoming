package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.world.RuinPlan;
import ua.zentix.incoming.nuclear.world.RuinPlanner;

import java.util.Locale;

/**
 * The price of a plan for ruins in a dense city: the site is built up with houses 12×12 with a height of 12–60 (concrete, windows, floors through 4,
 * core), plans are built in chunks, whose neighbors are also on the site - like a queue on the spot. Time - only in log
 *  (line “Price of ruins”): on general runners CI the wall clock is not measured. The test checks that the plan was built and something
 *  was destroyed, and the price is measured by a line in the log, locally and with the profile JFR.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinCostGameTests {
    private RuinCostGameTests() {}

    /** House in block {@code (i, j)} site 4×4 blocks along 16 blocks. */
    private static int height(int i, int j) {
        return 12 + Math.floorMod(i * 73 + j * 151 + i * j * 37, 49);
    }

    static void city(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockState concrete = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), glass = Blocks.GLASS.defaultBlockState(),
                floor = Blocks.SMOOTH_STONE.defaultBlockState(), core = Blocks.CALCITE.defaultBlockState(), brick = Blocks.BRICKS.defaultBlockState();
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                int top = height(i, j);
                BlockPos base = new BlockPos(i * 16 + 2, 1, j * 16 + 2);
                for (int y = 0; y <= top; y++) {
                    for (int dx = 0; dx < 12; dx++) {
                        for (int dz = 0; dz < 12; dz++) {
                            boolean wall = dx == 0 || dx == 11 || dz == 0 || dz == 11;
                            BlockState st = null;
                            if (y == top || y % 4 == 0) st = floor;
                            else if (wall) st = y % 4 != 3 && (dx + dz) % 3 != 0 ? glass : concrete;
                            else if (dx >= 5 && dx <= 6 && dz >= 5 && dz <= 6) st = core;
                            else if (dx == 3 || dz == 8) st = (dx + dz + y) % 5 == 0 ? null : brick;
                            if (st != null) level.setBlock(h.absolutePos(base.offset(dx, y, dz)), st, 2);
                        }
                    }
                }
            }
        }
    }

    private static void cost(GameTestHelper h, double psi) {
        city(h);
        // explosion to the side: at the block there is a given pressure, as in the middle zone of a real blow
        Detonation d = NuclearGameTests.atPsi(h, new BlockPos(-600, 0, 32), true, new BlockPos(32, 1, 32), psi);
        ServerLevel level = h.getLevel();
        // chunks in the middle of the site (under points 24 and 40 by x and z): their window 3×3 is almost the entire city
        java.util.LinkedHashSet<ChunkPos> inner = new java.util.LinkedHashSet<>();
        for (int rx : new int[]{24, 40}) for (int rz : new int[]{24, 40}) inner.add(new ChunkPos(h.absolutePos(new BlockPos(rx, 1, rz))));
        for (ChunkPos c : inner) {
            for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) level.getChunk(c.x + dx, c.z + dz);
            }
        }
        long blastNanos = 0, planNanos = 0, worst = 0;
        int blasts = 0, plans = 0, cells = 0;
        long bytes = 0, collapseNanos = 0, finishNanos = 0;
        for (ChunkPos c : inner) {
            LevelChunk chunk = level.getChunk(c.x, c.z);
            long t0 = System.nanoTime();
            while (!RuinPlanner.blastsReady(level, d, chunk)) blasts++;
            long t1 = System.nanoTime();
            long[] parts = RuinPlanner.timedParts(level, d, chunk);
            collapseNanos += parts[0];
            finishNanos += parts[1];
            RuinPlan plan = RuinPlanner.plan(level, d, chunk);
            long t2 = t1 + parts[0] + parts[1];
            blastNanos += t1 - t0;
            planNanos += t2 - t1;
            worst = Math.max(worst, t2 - t0);
            plans++;
            cells += plan.changedBlocks();
            bytes += plan.bytes();
        }
        Incoming.LOG.info("Price of ruins ({} psi): {} plans, {} places; faults {} pcs. for {} ms, plans (support, ground, light) {} ms, "
                        + "average {} ms per chunk, longest {} ms, average plan {} KB", psi, plans, cells, blasts, ms(blastNanos), ms(planNanos),
                ms(plans == 0 ? 0 : (blastNanos + planNanos) / plans), ms(worst), plans == 0 ? 0 : bytes / plans / 1024);
        Incoming.LOG.info("Price of ruins ({} psi): collapse on average {} ms, completion in the server thread {} ms per chunk", psi,
                ms(plans == 0 ? 0 : collapseNanos / plans), ms(plans == 0 ? 0 : finishNanos / plans));
        h.assertTrue(plans > 0 && cells > 0, "the city is not destroyed: " + plans + " plans, " + cells + " places");
        h.succeed();
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e6);
    }

    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_cost_6", skyAccess = true)
    public static void ruinCostCityAt6Psi(GameTestHelper h) {
        cost(h, 6);
    }

    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_cost_20", skyAccess = true)
    public static void ruinCostCityAt20Psi(GameTestHelper h) {
        cost(h, 20);
    }
}
