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
    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_store_demand", skyAccess = true)
    public static void demandReadSurvivesFinishedPreparationCursor(GameTestHelper h) {
        var grid = fixture(h, true);
        var expected = plan(grid).store();
        var level = h.getLevel();
        var root = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data/ruin-demand-test-" + java.util.UUID.randomUUID());
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_preparation");
        var pages = new RuinPages(info, root);
        long key = grid.centre().toLong();
        var reopened = pages.write(key, () -> expected).thenCompose(unused -> pages.close())
                .thenApply(unused -> new RuinPages(info, root));
        var plans = new RetainedPlans();
        var charged = new java.util.concurrent.atomic.AtomicLong();
        boolean[] installed = {false};
        ua.zentix.incoming.gametest.StrikeGameTests.gameSpeed(h);
        h.succeedWhen(() -> {
            h.assertTrue(reopened.isDone(), "Original write/close still pending");
            if (!installed[0]) {
                plans.spill(reopened.join(), grid.ctx(), charged::addAndGet, () -> 64L << 20);
                plans.discover();
                installed[0] = true;
            }
            // The scar consumer requests a page after the preparation cursor has reached the end.
            var restored = plans.get(key);
            plans.prefetch(new long[0], 0);
            h.assertTrue(restored != null, "Demand read must survive preparation read-ahead cleanup");
            h.assertTrue(expected.equals(restored.store()), "Demand result changed fields");
            h.assertTrue(charged.get() == 0, "Completed read retained its reservation");
            plans.clear();
        });
    }

    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_store_source", skyAccess = true)
    public static void reopenedPagesValidateLaterSourceEdits(GameTestHelper h) {
        ua.zentix.incoming.gametest.StrikeGameTests.gameSpeed(h);
        var level = h.getLevel(); var grid = fixture(h, true);
        long key = grid.centre().toLong(); var shot = grid.at(0, 0); var payload = shot.saved();
        var root = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data/ruin-source-test-" + java.util.UUID.randomUUID());
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_preparation");
        var pages = new RuinPages(info, root);
        var result = pages.write(key, payload::encode).thenCompose(unused -> pages.close())
                .thenApply(unused -> new RuinPages(info, root))
                .thenCompose(store -> store.read(key).thenCompose(tag -> store.close().thenApply(unused -> tag)));
        var unchanged = new DiskShots.Read(grid.centre(), payload.minY(), payload.states(), payload.heights(), 0, null, 0);
        var changed = payload.states().clone();
        int y = (int) grid.ctx().d.groundY() + 5, section = (y - payload.minY()) >> 4;
        changed[section] = RuinPlan.copyStates(changed[section]);
        changed[section].set(7, y & 15, 7, Blocks.BEDROCK.defaultBlockState());
        var edited = new DiskShots.Read(grid.centre(), payload.minY(), changed, payload.heights(), 0, null, 0);
        var scarred = new DiskShots.Read(grid.centre(), payload.minY(), changed, payload.heights(), 0, null, 0, grid.ctx().d.id());
        h.succeedWhen(() -> {
            h.assertTrue(result.isDone(), "Cache close/reopen still running");
            var data = result.join().orElseThrow();
            var first = new RuinContext(level, grid.ctx().d);
            h.assertTrue(first.restoreArchived(key, data, unchanged), "Unchanged source rejected");
            var second = new RuinContext(level, grid.ctx().d);
            h.assertFalse(second.restoreArchived(key, data, edited), "Stored source overwrote a later unscarred edit");
            h.assertTrue(second.restoreArchived(key, data, scarred), "Scarred neighbour lost its pre-wave world");
            var plan = RuinPlan.restore(plan(grid).store(), grid.ctx());
            var chunk = level.getChunkSource().getChunkNow(grid.centre().x, grid.centre().z);
            h.assertTrue(plan.current(chunk, grid.ctx().d), "Unedited plan already stale");
            int cell = plan.store().getIntArray("cells")[0];
            var at = new BlockPos(grid.centre().getMinBlockX() + (cell & 15),
                    level.getMinBuildHeight() + ((cell >>> RuinPlan.SECTION_SHIFT) << 4) + ((cell >> 8) & 15),
                    grid.centre().getMinBlockZ() + ((cell >> 4) & 15));
            level.setBlock(at, Blocks.BEDROCK.defaultBlockState(), 18);
            h.assertFalse(plan.current(chunk, grid.ctx().d), "Restored plan accepted a changed source cell");
            h.assertFalse(plan.apply(level, chunk, grid.ctx().d, new ColumnScar.Budget(false)), "Stale plan applied");
            h.assertTrue(level.getBlockState(at).is(Blocks.BEDROCK), "Stale plan removed the later edit");
        });
    }

    /** Shared raw-input corpus for correctness and same-grid paired timing; no map-specific solver paths. */
    static RuinContext.Grid corpus(GameTestHelper h, String kind, double psi) {
        var base = fixture(h, kind.equals("dense"), psi);
        if (kind.equals("dense") || kind.equals("natural")) return base;
        var level = h.getLevel();
        int ground = (int) base.ctx().d.groundY();
        var centre = base.centre();
        var pos = new BlockPos.MutableBlockPos();
        // Flat earth isolates partial contacts, an existing floating build and a boundary-crossing span.
        for (int z = -16; z < 32; z++) for (int x = -16; x < 32; x++) for (int y = 1; y <= 40; y++)
            level.setBlock(pos.set(centre.getMinBlockX() + x, ground + y, centre.getMinBlockZ() + z), Blocks.AIR.defaultBlockState(), 18);
        for (int z = -16; z < 32; z++) for (int x = -16; x < 32; x++)
            level.setBlock(pos.set(centre.getMinBlockX() + x, ground, centre.getMinBlockZ() + z), Blocks.STONE.defaultBlockState(), 18);
        if (kind.equals("columns")) {
            for (int y = 1; y <= 20; y++) for (int z = 5; z <= 10; z++) {
                level.setBlock(pos.set(centre.getMinBlockX() + 5, ground + y, centre.getMinBlockZ() + z), Blocks.GLASS.defaultBlockState(), 18);
                level.setBlock(pos.set(centre.getMinBlockX() + 6, ground + y, centre.getMinBlockZ() + z), Blocks.OBSIDIAN.defaultBlockState(), 18);
            }
        } else if (kind.equals("floating")) {
            for (int x = 3; x < 11; x++) for (int z = 3; z < 11; z++)
                level.setBlock(pos.set(centre.getMinBlockX() + x, ground + 22, centre.getMinBlockZ() + z), Blocks.CALCITE.defaultBlockState(), 18);
        } else if (kind.equals("partial") || kind.equals("border")) {
            int left = kind.equals("border") ? -6 : 3, right = kind.equals("border") ? 23 : 12;
            for (int x : new int[]{left, right}) for (int y = 1; y <= 18; y++)
                level.setBlock(pos.set(centre.getMinBlockX() + x, ground + y, centre.getMinBlockZ() + 8), Blocks.GLASS.defaultBlockState(), 18);
            for (int x = left; x <= right; x++) {
                BlockState state = x % 3 == 0 ? Blocks.STONE_BRICK_SLAB.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE,
                                net.minecraft.world.level.block.state.properties.SlabType.TOP)
                        : x % 3 == 1 ? Blocks.STONE_BRICK_STAIRS.defaultBlockState() : Blocks.CALCITE.defaultBlockState();
                level.setBlock(pos.set(centre.getMinBlockX() + x, ground + 19, centre.getMinBlockZ() + 8), state, 18);
            }
            // A fence and a bottom slab do not share every vertical contact face.
            level.setBlock(pos.set(centre.getMinBlockX() + left, ground + 18, centre.getMinBlockZ() + 8), Blocks.OAK_FENCE.defaultBlockState(), 18);
        } else throw new IllegalArgumentException("Unknown collapse corpus " + kind);
        return new RuinContext(level, base.ctx().d).grid(level, centre, false);
    }

    static void sameCollapse(GameTestHelper h, ReferenceCollapse old, Collapse now, String sample) {
        h.assertTrue(java.util.Arrays.equals(old.gone, now.gone), sample + ": fallen positions");
        h.assertTrue(java.util.Arrays.equals(old.drained, now.drained), sample + ": drained positions");
        h.assertTrue(old.changes.equals(now.changes), sample + ": changed states");
        h.assertTrue(java.util.Arrays.equals(old.removed, now.removed), sample + ": removed counts");
        h.assertTrue(java.util.Arrays.equals(old.wood, now.wood), sample + ": wood counts");
        h.assertTrue(java.util.Arrays.equals(old.ground, now.ground), sample + ": ground heights");
        h.assertTrue(old.leaves.equals(now.leaves), sample + ": leaf order");
        h.assertTrue(old.fluidTicks.equals(now.fluidTicks), sample + ": fluid order");
    }

    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_opt_corpus", skyAccess = true)
    public static void completeCollapseCorpusAndBothSupportPaths(GameTestHelper h) {
        int fast = 0, full = 0;
        for (String kind : new String[]{"columns", "partial", "floating", "border"})
            for (double psi : new double[]{1, 6, 20, 50}) {
                var grid = corpus(h, kind, psi);
                var old = ReferenceCollapse.solve(grid); var now = Collapse.solve(grid);
                sameCollapse(h, old, now, kind + " " + psi + " psi");
                if (now.supportChecked) { if (now.directSupport) fast++; else full++; }
            }
        h.assertTrue(fast > 0, "Corpus never exercised the sufficient vertical-support exit");
        h.assertTrue(full > 0, "Corpus never exercised full support after a gap/partial contact");
        h.succeed();
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
