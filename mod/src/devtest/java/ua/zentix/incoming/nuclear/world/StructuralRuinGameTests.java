package ua.zentix.incoming.nuclear.world;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.Yield;

/** Structural regressions on immutable snapshots; no player world or map-specific solver branches. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StructuralRuinGameTests {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int FLOOR = 64, TOP = 160;

    private StructuralRuinGameTests() { }

    @FunctionalInterface
    interface Shape {
        BlockState at(int x, int y, int z);
    }

    static Detonation event(double psi) {
        double height = Yield.optimalBurstHeight(15);
        double range = HobBlast.rangeFor(psi, height, 15);
        return new Detonation(700, new Vec3(8 - range, 65 + height, 8), 65, 15,
                false, 0, 0, 3, 20000, 42, 1, false, .25f, 6000, false);
    }

    static RuinContext fixture(ServerLevel level, Shape shape, double psi) {
        RuinContext context = new RuinContext(level, event(psi));
        for (int z = -3; z <= 3; z++) for (int x = -3; x <= 3; x++) {
            context.putDisk(level, snapshot(new ChunkPos(x, z), level.getMinBuildHeight(), level.getHeight(), shape));
        }
        RuinPlanner.ensureOwnStates();
        return context;
    }

    @SuppressWarnings("unchecked")
    private static DiskShots.Read snapshot(ChunkPos pos, int minY, int height, Shape shape) {
        var sections = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[height / 16];
        for (int y = FLOOR - 1; y <= TOP; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockState state = y <= FLOOR ? Blocks.STONE.defaultBlockState() : shape.at(pos.x * 16 + x, y, pos.z * 16 + z);
            if (state.isAir()) continue;
            int section = (y - minY) >> 4;
            if (sections[section] == null) sections[section] = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                    AIR, PalettedContainer.Strategy.SECTION_STATES);
            sections[section].set(x, y & 15, z, state);
            Blast.ensure(state);
        }
        return new DiskShots.Read(pos, minY, sections, new int[256 * RuinPlan.HEIGHTMAP_TYPES.length], 15, null, 0);
    }

    static Shape hall(Block material) {
        return (x, y, z) -> {
            int dx = Math.abs(x - 8), dz = Math.abs(z - 8);
            if (dx > 12 || dz > 12) return AIR;
            if (y == 70) return material.defaultBlockState();
            if (y < 70 && (dx == 12 || dz == 12)) return Blocks.GLASS.defaultBlockState();
            return AIR;
        };
    }

    static Shape tree(int height, boolean crown) {
        return (x, y, z) -> {
            if (x == 8 && z == 8 && y < 65 + height) return Blocks.OAK_LOG.defaultBlockState();
            if (crown && Math.abs(x - 8) <= 1 && Math.abs(z - 8) <= 1 && y >= 65 + height && y <= 66 + height)
                return Blocks.OAK_LEAVES.defaultBlockState();
            return AIR;
        };
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_structure_roof", skyAccess = true)
    public static void wideStoneRoofLosesEveryUnsupportedBlock(GameTestHelper helper) {
        var context = fixture(helper.getLevel(), hall(Blocks.STONE), 2);
        var collapse = Collapse.solve(context.cachedGridForTest(helper.getLevel(), new ChunkPos(0, 0)));
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            helper.assertTrue(collapse.isGone(x + 16, 70, z + 16), "Roof retained without any surviving wall at " + x + "," + z);
        }
        helper.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_structure_timber", skyAccess = true)
    public static void timberWithoutLeavesIsNotImmune(GameTestHelper helper) {
        var context = fixture(helper.getLevel(), tree(16, false), 20);
        var grid = context.cachedGridForTest(helper.getLevel(), new ChunkPos(0, 0));
        helper.assertTrue(grid.blast(0, 0).removed().length >= 16, "Bare timber column survived the load");
        helper.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_structure_tree", skyAccess = true)
    public static void treeCrownAboveTwentyFourBlocksIsRecognized(GameTestHelper helper) {
        var context = fixture(helper.getLevel(), tree(30, true), 20);
        var grid = context.cachedGridForTest(helper.getLevel(), new ChunkPos(0, 0));
        var blast = grid.blast(0, 0);
        helper.assertTrue(blast.trees().size() == 1 && blast.trees().getFirst()[1] == 30,
                "Tree recognition stopped below the crown");
        helper.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_structure_seam", skyAccess = true)
    public static void roofReleaseCrossesTheChunkSeam(GameTestHelper helper) {
        Shape original = hall(Blocks.STONE);
        Shape shifted = (x, y, z) -> original.at(x - 8, y, z - 8);
        var context = fixture(helper.getLevel(), shifted, 2);
        for (int cz = 0; cz <= 1; cz++) for (int cx = 0; cx <= 1; cx++) {
            var collapse = Collapse.solve(context.cachedGridForTest(helper.getLevel(), new ChunkPos(cx, cz)));
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (!shifted.at(cx * 16 + x, 70, cz * 16 + z).is(Blocks.STONE)) continue;
                helper.assertTrue(collapse.isGone(x + 16, 70, z + 16), "Roof seam retained in " + cx + "," + cz + " at " + x + "," + z);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_structure_existing", skyAccess = true)
    public static void preexistingFloatingStoneDoesNotAcquireGravity(GameTestHelper helper) {
        Shape shape = (x, y, z) -> {
            if (x >= 0 && x < 16 && z >= 0 && z < 16 && y == 80) return Blocks.STONE.defaultBlockState();
            if (x == 8 && z == 8 && y < 70) return Blocks.GLASS.defaultBlockState();
            return AIR;
        };
        var context = fixture(helper.getLevel(), shape, 2);
        var collapse = Collapse.solve(context.cachedGridForTest(helper.getLevel(), new ChunkPos(0, 0)));
        helper.assertTrue(!collapse.isGone(24, 80, 24), "Unrelated preexisting floating roof fell");
        helper.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_structure_protection", skyAccess = true)
    public static void protectedBlocksRemainAnchors(GameTestHelper helper) {
        var context = fixture(helper.getLevel(), hall(Blocks.OBSIDIAN), 20);
        var collapse = Collapse.solve(context.cachedGridForTest(helper.getLevel(), new ChunkPos(0, 0)));
        helper.assertTrue(!collapse.isGone(24, 70, 24), "Protected material was detached");
        helper.assertTrue(Blast.props(Blocks.ANCIENT_DEBRIS.defaultBlockState()).response().kind() != BlockResponse.Kind.NONE,
                "Hard breakable material gained fallback immunity");
        helper.assertTrue(BlockResponse.of(Blocks.ANCIENT_DEBRIS.defaultBlockState()).kind() == BlockResponse.Kind.NONE,
                "The nuclear change leaked into conventional weapon response");
        helper.assertTrue(BlockResponse.of(Blocks.BEDROCK.defaultBlockState()).kind() == BlockResponse.Kind.NONE,
                "Unbreakable material lost protection");
        helper.succeed();
    }
}
