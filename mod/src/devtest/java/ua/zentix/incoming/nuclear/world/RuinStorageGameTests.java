package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.Yield;

/** Complete durable outputs, including data omitted by the older geometry-only plan comparator. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinStorageGameTests {
    private RuinStorageGameTests() {}

    static RuinContext.Grid fixture(GameTestHelper h, boolean dense) {
        return fixture(h, dense, 6);
    }

    static RuinContext.Grid fixture(GameTestHelper h, boolean dense, double psi) {
        var level = h.getLevel();
        ChunkPos centre = new ChunkPos(h.absolutePos(new BlockPos(32, 1, 32)));
        int ground = h.absolutePos(new BlockPos(0, 0, 0)).getY();
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
            level.getChunk(centre.x + dx, centre.z + dz);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = -16; z < 32; z++) for (int x = -16; x < 32; x++) {
            int lx = Math.floorMod(x, 16), lz = Math.floorMod(z, 16);
            int top = dense ? 36 : 3 + Math.floorMod(x * x + z * 3, 9);
            for (int y = -2; y <= 40; y++) {
                BlockState state = Blocks.AIR.defaultBlockState();
                if (y <= 0) state = Blocks.STONE.defaultBlockState();
                else if (dense && y <= top && lx >= 2 && lx < 14 && lz >= 2 && lz < 14) {
                    boolean edge = lx == 2 || lx == 13 || lz == 2 || lz == 13;
                    if (y % 4 == 0) state = Blocks.SMOOTH_STONE.defaultBlockState();
                    else if (edge) state = (y % 4 == 3 ? Blocks.WHITE_CONCRETE : Blocks.GLASS).defaultBlockState();
                    else if (lx == 7 && lz == 7) state = Blocks.CALCITE.defaultBlockState();
                } else if (!dense && y <= top) {
                    state = (y == top ? Blocks.GRASS_BLOCK : y > top - 3 ? Blocks.DIRT : Blocks.STONE).defaultBlockState();
                }
                level.setBlock(pos.set(centre.getMinBlockX() + x, ground + y, centre.getMinBlockZ() + z), state, 18);
            }
        }
        double height = Yield.optimalBurstHeight(15), distance = HobBlast.rangeFor(psi, height, 15);
        Detonation d = new Detonation(100, new Vec3(centre.getMiddleBlockX() - distance, ground + height,
                centre.getMiddleBlockZ()), ground, 15, false, level.getGameTime(), 0, 3, 20000, 42, 1, false, .25f, 0, true);
        return new RuinContext(level, d).grid(level, centre, false);
    }

    static RuinPlan plan(RuinContext.Grid grid) { return RuinPlanner.finish(grid, Collapse.solve(grid)); }

    private static void roundTrip(GameTestHelper h, boolean dense) {
        var grid = fixture(h, dense);
        RuinPlan original = plan(grid);
        CompoundTag encoded = original.store();
        RuinPlan restored = RuinPlan.restore(encoded.copy(), grid.ctx());
        h.assertTrue(encoded.equals(restored.store()), "Durable plan changed a field");
        h.assertTrue(original.differs(restored) == null, "Restored geometry differs");
        var chunk = h.getLevel().getChunkSource().getChunkNow(grid.centre().x, grid.centre().z);
        h.assertTrue(original.current(chunk, grid.ctx().d) == restored.current(chunk, grid.ctx().d), "Restored validity differs");
        if (dense) h.assertTrue(original.changedBlocks() > 0, "Dense fixture did not fracture");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_store_dense", skyAccess = true)
    public static void densePlanRoundTrip(GameTestHelper h) { roundTrip(h, true); }

    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_store_natural", skyAccess = true)
    public static void naturalPlanRoundTrip(GameTestHelper h) { roundTrip(h, false); }

    @GameTest(template = "range", timeoutTicks = 400, batch = "nuke_store_snapshots", skyAccess = true)
    public static void snapshotsRoundTrip(GameTestHelper h) {
        var grid = fixture(h, true);
        var copy = grid.shots().clone();
        for (int i = 0; i < copy.length; i++) if (copy[i] != null) {
            var encoded = copy[i].saved().encode();
            copy[i] = ChunkShot.restore(encoded.copy(), grid.ctx());
            h.assertTrue(encoded.equals(copy[i].saved().encode()), "Snapshot round trip changed fields");
        }
        var restored = new RuinContext.Grid(grid.ctx(), grid.centre(), grid.minY(), grid.maxY(), copy,
                grid.view(), grid.blockDamage(), grid.treeFall(), grid.fires());
        h.assertTrue(plan(grid).store().equals(plan(restored).store()), "Restored snapshots changed the complete plan");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_store_restart", skyAccess = true)
    public static void pagesSurviveCloseAndReopen(GameTestHelper h) {
        var level = h.getLevel();
        var grid = fixture(h, true);
        CompoundTag expected = plan(grid).store();
        var root = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data/ruin-page-test-" + java.util.UUID.randomUUID());
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_preparation");
        var pages = new RuinPages(info, root);
        long key = grid.centre().toLong();
        var written = pages.write(key, () -> expected.copy());
        var reopened = written.thenCompose(unused -> pages.close()).thenApply(unused -> new RuinPages(info, root));
        var result = reopened.thenCompose(store -> store.read(key).thenCompose(tag -> store.close().thenApply(unused -> tag)));
        h.succeedWhen(() -> {
            h.assertTrue(result.isDone(), "Asynchronous storage still running");
            h.assertTrue(result.join().orElseThrow().equals(expected), "Region-file round trip changed plan data");
        });
    }
    private static void compareCollapse(GameTestHelper h, boolean dense) {
        for (double psi : new double[]{1, 6, 20, 50}) {
            var grid = fixture(h, dense, psi);
            var old = ReferenceCollapse.solve(grid);
            var now = Collapse.solve(grid);
            String sample = (dense ? "dense" : "natural") + " " + psi + " psi";
            h.assertTrue(java.util.Arrays.equals(old.gone, now.gone), sample + ": fallen positions");
            h.assertTrue(java.util.Arrays.equals(old.drained, now.drained), sample + ": drained positions");
            h.assertTrue(old.changes.equals(now.changes), sample + ": changed states");
            h.assertTrue(java.util.Arrays.equals(old.removed, now.removed), sample + ": removed counts");
            h.assertTrue(java.util.Arrays.equals(old.wood, now.wood), sample + ": wood counts");
            h.assertTrue(java.util.Arrays.equals(old.ground, now.ground), sample + ": ground heights");
            h.assertTrue(old.leaves.equals(now.leaves), sample + ": leaf order");
            h.assertTrue(old.fluidTicks.equals(now.fluidTicks), sample + ": fluid order");
        }
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "nuke_opt_dense", skyAccess = true)
    public static void denseCollapseIsExact(GameTestHelper h) { compareCollapse(h, true); }

    @GameTest(template = "range", timeoutTicks = 400, batch = "nuke_opt_natural", skyAccess = true)
    public static void naturalCollapseIsExact(GameTestHelper h) { compareCollapse(h, false); }

}
