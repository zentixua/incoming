package ua.zentix.incoming.gametest;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.model.Yield;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.RuinPlan;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.List;
import java.util.Locale;

import static ua.zentix.incoming.gametest.NuclearGameTests.CENTER;
import static ua.zentix.incoming.gametest.NuclearGameTests.HOLD;
import static ua.zentix.incoming.gametest.NuclearGameTests.atPsi;
import static ua.zentix.incoming.gametest.NuclearGameTests.chunkCorner;
import static ua.zentix.incoming.gametest.NuclearGameTests.detonation;
import static ua.zentix.incoming.gametest.NuclearGameTests.scarAll;
import static ua.zentix.incoming.gametest.NuclearGameTests.surface;

/**
 * Ruins of a nuclear strike in physics ({@code Blast}, {@code Collapse}): difficult cases - spans wider than the span of the block,
 *  pressure in the depths of the building, supports, suspended, water, indestructible, edge of a busy world, boundaries of squares
 *  preparation. The area and scale are the same as in {@link NuclearGameTests}: turf on y = 11, surface y = 12.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinGameTests {
    private RuinGameTests() {}

    private static String psi(Detonation d, GameTestHelper h, BlockPos at) {
        return String.format(Locale.ROOT, "%.1f psi", d.psi(Vec3.atCenterOf(h.absolutePos(at))));
    }

    /**
     * Glass halls with a roof at ~2 psi: the glass is broken, and there is nothing for the roof to stand on. The hall 17×17 has a stone roof
     *  (the middle is 8 blocks from the wall, further than the span of the stone: 7 and 8 fall according to the rule “support further on 3”), hall
     * 21×21 - concrete (the middle is further than the bypass of the supports: the support was not visible before the impact, it falls in a cascade from the edges). Not a block remains from the roofs of
     * .
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_hall", skyAccess = true)
    public static void glassHallRoofFalls(GameTestHelper h) {
        BlockPos small = new BlockPos(50, 12, 13), big = new BlockPos(50, 12, 44);
        Detonation d = atPsi(h, new BlockPos(32, 12, 28), true, small.above(5), 2);
        hall(h, small, 8, Blocks.STONE);
        hall(h, big, 10, Blocks.WHITE_CONCRETE);
        scarAll(h, d, new ColumnScar.Budget(false));
        roofGone(h, d, small, 8, Blocks.STONE);
        roofGone(h, d, big, 10, Blocks.WHITE_CONCRETE);
        h.succeed();
    }

    /** Hall (2r+1)×(2r+1): glass walls in 5 blocks, roof on 5-m from {@code roof}. */
    private static void hall(GameTestHelper h, BlockPos c, int r, Block roof) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) == r || Math.abs(dz) == r) {
                    for (int y = 0; y < 5; y++) h.setBlock(c.offset(dx, y, dz), Blocks.GLASS);
                }
                h.setBlock(c.offset(dx, 5, dz), roof);
            }
        }
    }

    private static void roofGone(GameTestHelper h, Detonation d, BlockPos c, int r, Block roof) {
        int left = 0;
        BlockPos any = null;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int y = 1; y <= 5; y++) {
                    if (h.getBlockState(c.offset(dx, y, dz)).is(roof)) {
                        left++;
                        any = c.offset(dx, y, dz);
                    }
                    if (h.getBlockState(c.offset(dx, y, dz)).is(Blocks.GLASS)) h.fail("the glass of the hall is intact " + c.offset(dx, y, dz).toShortString());
                }
            }
        }
        if (left > 0) h.fail("from the roof of the hall " + (2 * r + 1) + "×" + (2 * r + 1) + " left " + left + " blocks, for example " + any.toShortString() + " (" + psi(d, h, c.above(5)) + ")");
    }

    /**
     * Chunk on the edge of a busy world: its ruins rise without loading its neighbors (the background window is from the disk, there is no neighbor
     *  on the disk - a solid array). If the plan still holds neighbors with a ticket of radius 1 ({@code RuinPlan.needsNeighbours}),
     * , the chunks that loaded this ticket do not take the ticket themselves - otherwise the loading would spread from the edge to the entire radius.
     *  Each chunk holding a ticket was full in memory even before the detonation.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_hold_spread", skyAccess = true)
    public static void edgeHoldDoesNotSpread(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        var chunks = level.getChunkSource();
        ChunkPos e = new ChunkPos(h.absolutePos(CENTER.north(80)));
        BlockPos[] glass = new BlockPos[1];
        LongOpenHashSet loaded = new LongOpenHashSet();
        String[] spread = {null};
        int[] most = {0};
        chunks.addRegionTicket(HOLD, e, 0, e);
        h.onEachTick(() -> {
            if (loaded.isEmpty()) return;
            long[] held = NuclearWorld.get(level).scarHolds();
            most[0] = Math.max(most[0], held.length);
            for (long k : held) {
                if (spread[0] == null && !loaded.contains(k)) spread[0] = new ChunkPos(k) + " (hold " + held.length + ")";
            }
        });
        Runnable done = () -> {
            chunks.removeRegionTicket(HOLD, e, 0, e);
            NuclearStrikes.clear(level);
        };
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(e.x, e.z) != null, "E is loading"))
                .thenExecute(() -> {
                    glass[0] = surface(level, e);
                    level.setBlock(glass[0], Blocks.GLASS.defaultBlockState(), 3);
                    for (ChunkHolder holder : chunks.chunkMap.getChunks()) {
                        // in memory complete (and lowered at the edge of visibility - the photo of the explosion takes them too)
                        if (holder.getChunkIfPresentUnchecked(net.minecraft.world.level.chunk.status.ChunkStatus.FULL) instanceof net.minecraft.world.level.chunk.LevelChunk) {
                            loaded.add(holder.getPos().toLong());
                        }
                    }
                    NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(CENTER)), 15, true, null, 0.1f);
                })
                .thenWaitUntil(() -> {
                    if (spread[0] != null) {
                        done.run();
                        throw new GameTestAssertException("ticket took a chunk loaded by someone else's ticket: " + spread[0]);
                    }
                    h.assertFalse(level.getBlockState(glass[0]).is(Blocks.GLASS), "glass in E is intact");
                })
                // and after the ruins of E: his loaded ticket chunks don’t carry anything further
                .thenIdle(100)
                .thenExecute(() -> {
                    var chunk = chunks.getChunkNow(e.x, e.z);
                    boolean scarred = chunk != null && chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) > 0;
                    done.run();
                    if (spread[0] != null) throw new GameTestAssertException("ticket took a chunk loaded by someone else's ticket: " + spread[0]);
                    h.assertTrue(scarred, "e does not have a ruin mark");
                    h.assertTrue(most[0] <= 9, "the edge held more than its square 3×3: " + most[0]);
                })
                .thenSucceed();
    }

    /**
     * Tower 21×21×80 at the epicenter (15 kt in the air, 1 block = 3.3 m): concrete walls with windows, ceilings through 4 block,
     *  core 5×5 made of concrete. The pressure enters the broken windows and reaches the floors up to the core (flows into the entire air behind the
     *  breaks, and not onto several blocks): the core overturns, the tower collapses - no more than 5% of the blocks above the rubble,
     *  nothing hangs.
     */
    @GameTest(template = "range", timeoutTicks = 80, batch = "nuke_wide_tower", skyAccess = true)
    public static void wideTowerWithCoreFalls(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = CENTER;
        int half = 10, height = 80, built = 0;
        for (int y = 0; y < height; y++) {
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    boolean wall = Math.abs(dx) == half || Math.abs(dz) == half, core = Math.abs(dx) <= 2 && Math.abs(dz) <= 2;
                    BlockState st = null;
                    if (wall) st = (y % 4 == 1 || y % 4 == 2) && Math.floorMod(dx + dz, 2) == 1 ? Blocks.GLASS.defaultBlockState() : Blocks.GRAY_CONCRETE.defaultBlockState();
                    else if (core) st = Blocks.CYAN_CONCRETE.defaultBlockState();
                    else if (y % 4 == 3) st = Blocks.WHITE_CONCRETE.defaultBlockState();
                    if (st != null && level.setBlock(h.absolutePos(c.offset(dx, y, dz)), st, 2)) built++;
                }
            }
        }
        Detonation d = detonation(h, c.offset(14, 0, 9), Yield.optimalBurstHeight(15) * 0.3, 15, 0.3f);
        scarAll(h, d, new ColumnScar.Budget(false));
        int standing = 0, core = 0;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                int air = 0;
                for (int y = 0; y < height; y++) {
                    BlockState st = h.getBlockState(c.offset(dx, y, dz));
                    if (st.isAir()) {
                        air++;
                        continue;
                    }
                    if (y >= 4) {
                        standing++;
                        if (st.is(Blocks.CYAN_CONCRETE)) core++;
                    }
                    if (air >= 3) h.fail("hanging in the air " + st + " in " + c.offset(dx, y, dz).toShortString() + " over the void in " + air + " blocks");
                    air = 0;
                }
            }
        }
        h.assertTrue(standing * 20 <= built, "tower 21×21 stands: above the rubble " + standing + " blocks of " + built + ", of which the kernels " + core
                + " (at the base of the core " + psi(d, h, c.above(1)) + ")");
        h.succeed();
    }

    /**
     * Pier on the lake 20×20 (water is large, not a swimming pool): fence posts in the water (there is water in the block), flooring made of boards over the
     *  water. At ~7 psi the pier collapses, the water blocks do not hang over the lake, nothing hangs in the air, the lake is still there.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_dock", skyAccess = true)
    public static void dockOverLakeFalls(GameTestHelper h) {
        BlockPos lake = new BlockPos(40, 9, 22); // bottom - y = 8, water - 9...11
        for (BlockPos p : BlockPos.betweenClosed(lake, lake.offset(19, 2, 19))) h.setBlock(p, Blocks.WATER);
        BlockPos deck = lake.offset(8, 4, 2); // flooring on y = 13, 3 × 12 from the shore
        BlockState post = Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        for (int dz = 0; dz < 12; dz += 3) {
            for (int dx : new int[] {0, 2}) {
                for (int y = 9; y <= 11; y++) h.setBlock(new BlockPos(deck.getX() + dx, y, deck.getZ() + dz), post);
                h.setBlock(new BlockPos(deck.getX() + dx, 12, deck.getZ() + dz), Blocks.OAK_FENCE);
            }
        }
        for (BlockPos p : BlockPos.betweenClosed(deck.offset(0, 0, -2), deck.offset(2, 0, 11))) h.setBlock(p, Blocks.OAK_PLANKS);
        Detonation d = atPsi(h, CENTER.west(20), true, deck.offset(1, 0, 6), 7);
        scarAll(h, d, new ColumnScar.Budget(false));
        int water = 0;
        for (int dx = 0; dx < 20; dx++) {
            for (int dz = 0; dz < 20; dz++) {
                for (int y = 9; y <= 11; y++) if (h.getBlockState(lake.offset(dx, y - 9, dz)).getFluidState().is(Fluids.WATER)) water++;
                int air = 0;
                for (int y = 12; y <= 20; y++) {
                    BlockPos p = new BlockPos(lake.getX() + dx, y, lake.getZ() + dz);
                    BlockState st = h.getBlockState(p);
                    if (!st.getFluidState().isEmpty()) h.fail("water over the lake: " + st + " in " + p.toShortString() + " (" + psi(d, h, deck) + ")");
                    if (st.isAir()) {
                        air++;
                        continue;
                    }
                    if (air > 0 || y > 12 && h.getBlockState(p.below()).getFluidState().is(Fluids.WATER)) {
                        h.fail("hanging over the water: " + st + " in " + p.toShortString() + " (" + psi(d, h, deck) + ")");
                    }
                }
            }
        }
        for (BlockPos p : BlockPos.betweenClosed(deck.offset(0, 0, -2), deck.offset(2, 0, 11))) {
            if (h.getBlockState(p).is(Blocks.OAK_PLANKS)) h.fail("the flooring is in " + p.toShortString() + " (" + psi(d, h, deck) + ")");
        }
        h.assertTrue(water >= 20 * 20 * 3 * 9 / 10, "lake gone: water " + water);
        h.succeed();
    }

    /**
     * A pool on the roof of a brick house 7×7×8 at ~15 psi: the house collapses, the pool water (small) goes with it - not a single
     *  block of water above the void.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_roof_pool", skyAccess = true)
    public static void rooftopPoolDrainsWithHouse(GameTestHelper h) {
        BlockPos c = new BlockPos(44, 12, 29);
        for (int dx = 0; dx < 7; dx++) {
            for (int dz = 0; dz < 7; dz++) {
                boolean edge = dx == 0 || dx == 6 || dz == 0 || dz == 6;
                for (int y = 0; y < 8; y++) if (edge || y == 4) h.setBlock(c.offset(dx, y, dz), Blocks.BRICKS);
                h.setBlock(c.offset(dx, 8, dz), Blocks.BRICKS);
                h.setBlock(c.offset(dx, 9, dz), edge ? Blocks.BRICKS : Blocks.WATER);
            }
        }
        Detonation d = atPsi(h, CENTER.west(10), true, c.offset(3, 8, 3), 15);
        scarAll(h, d, new ColumnScar.Budget(false));
        boolean roof = false;
        for (int dx = -2; dx < 9; dx++) {
            for (int dz = -2; dz < 9; dz++) {
                if (h.getBlockState(c.offset(dx, 8, dz)).is(Blocks.BRICKS)) roof = true;
                for (int y = 0; y <= 10; y++) {
                    BlockPos p = c.offset(dx, y, dz);
                    if (!h.getBlockState(p).getFluidState().isEmpty() && h.getBlockState(p.below()).isAir()) {
                        h.fail("pool water hanging: " + p.toShortString() + " (" + psi(d, h, c.offset(3, 8, 3)) + ")");
                    }
                }
            }
        }
        h.assertFalse(roof, "the roof of the house with a swimming pool stands (" + psi(d, h, c.offset(3, 8, 3)) + "): the test did not check anything");
        h.succeed();
    }

    /**
     * Overlap with one hole (a glass block in a slab, a wave knocks it out from above) at ~3 psi: the hole does not cut any cracks.
     *  Brick slab 9×9 - only glass falls out; the same 15×15, where in the middle the support is further than the span of the brick, -
     * , too (the support has become less longer than 3); stone 9×9 (soil in the building - masonry without reinforcement, holds,
     *  only until the support is lengthened) - no more falls out than the neighbors of the hole. Detonation near the ground: airborne on the site
     *  (scale ~0.02) - in 12 blocks above it, and the bottom of the near wall of the box would be under 5 psi (reflected 12 - the masonry
     *  breaks, the slab loses support), and in a real house 2 km from the air 15 kt the altitude pressure is almost the same.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_floor_hole", skyAccess = true)
    public static void floorWithHoleHasNoSlits(GameTestHelper h) {
        BlockPos masonry = new BlockPos(40, 12, 4), wide = new BlockPos(40, 12, 16), stone = new BlockPos(40, 12, 34);
        Detonation d = atPsi(h, new BlockPos(8, 12, 24), false, wide.offset(7, 5, 7), 3);
        floor(h, masonry, 9, Blocks.STONE_BRICKS, 2, 4);
        floor(h, wide, 15, Blocks.STONE_BRICKS, 6, 7);
        floor(h, stone, 9, Blocks.STONE, 2, 4);
        scarAll(h, d, new ColumnScar.Budget(false));
        String at = " (" + psi(d, h, wide.offset(7, 5, 7)) + ")";
        slab(h, masonry, 9, Blocks.STONE_BRICKS, 2, 4, 1, "brick 9×9" + at);
        slab(h, wide, 15, Blocks.STONE_BRICKS, 6, 7, 1, "brick 15×15" + at);
        slab(h, stone, 9, Blocks.STONE, 2, 4, 2, "stone 9×9" + at);
        h.succeed();
    }

    /** Box n×n: walls made of stone bricks in 5 blocks, slab on 5 from {@code slab}, glass in slab in (hx, hz). */
    private static void floor(GameTestHelper h, BlockPos c, int n, Block slab, int hx, int hz) {
        for (int dx = 0; dx < n; dx++) {
            for (int dz = 0; dz < n; dz++) {
                if (dx == 0 || dz == 0 || dx == n - 1 || dz == n - 1) {
                    for (int y = 0; y < 5; y++) h.setBlock(c.offset(dx, y, dz), Blocks.STONE_BRICKS);
                }
                h.setBlock(c.offset(dx, 5, dz), dx == hx && dz == hz ? Blocks.GLASS : slab);
            }
        }
    }

    /** The whole slab remains except the hole and positions within {@code keep} of it (on either side). */
    private static void slab(GameTestHelper h, BlockPos c, int n, Block slab, int hx, int hz, int keep, String what) {
        h.assertFalse(h.getBlockState(c.offset(hx, 5, hz)).is(Blocks.GLASS), "glass in the ceiling " + what + " whole");
        for (int dx = 0; dx < n; dx++) {
            for (int dz = 0; dz < n; dz++) {
                if (Math.abs(dx - hx) + Math.abs(dz - hz) < keep) continue;
                BlockPos p = c.offset(dx, 5, dz);
                if (!h.getBlockState(p).is(slab)) h.fail("overlap " + what + " with a hole in " + hx + "," + hz + " lost " + dx + "," + dz + ": " + h.getBlockState(p));
            }
        }
    }

    /**
     * The attachment leaves with what it was holding on: the wooden house at ~8 psi collapses, and neither the torch on the wall, nor the door, nor the
     *  carpet on the ceiling remains hanging in the air - each one that remains stands on its own.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_attached", skyAccess = true)
    public static void attachedBlocksGoWithSupport(GameTestHelper h) {
        BlockPos c = new BlockPos(44, 12, 28);
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 7; dz++) {
                boolean wall = dx == 0 || dx == 4 || dz == 0 || dz == 6;
                for (int y = 0; y <= 6; y++) {
                    if (wall || y == 3 || y == 6) h.setBlock(c.offset(dx, y, dz), Blocks.OAK_PLANKS);
                }
                if (!wall) h.setBlock(c.offset(dx, 4, dz), Blocks.RED_CARPET);
            }
        }
        // door in the western wall, torches outside on the eastern and northern
        BlockPos door = c.offset(0, 0, 3);
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST);
        h.getLevel().setBlock(h.absolutePos(door), lower, 2);
        h.getLevel().setBlock(h.absolutePos(door.above()), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
        for (int dz = 1; dz < 6; dz += 2) h.setBlock(c.offset(5, 2, dz), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST));
        for (int dx = 1; dx < 4; dx += 2) h.setBlock(c.offset(dx, 2, -1), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.NORTH));
        Detonation d = atPsi(h, CENTER.west(10), true, c.offset(2, 3, 3), 8);
        scarAll(h, d, new ColumnScar.Budget(false));
        ServerLevel level = h.getLevel();
        int planks = 0;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-1, 0, -1), c.offset(5, 7, 7))) {
            BlockState st = h.getBlockState(p);
            if (st.is(Blocks.OAK_PLANKS) && p.getY() - c.getY() >= 3) planks++;
            boolean attached = st.is(BlockTags.WOOL_CARPETS) || st.is(BlockTags.DOORS) || st.is(Blocks.WALL_TORCH) || st.is(Blocks.TORCH);
            if (attached && !st.canSurvive(level, h.absolutePos(p))) h.fail("hanging without support: " + st + " in " + p.toShortString() + " (" + psi(d, h, c) + ")");
        }
        h.assertTrue(planks < 20, "the house is standing (" + psi(d, h, c.offset(2, 3, 3)) + ", boards above 3 block " + planks + "): the test did not check anything");
        h.succeed();
    }

    /**
     * The sandy canopy over the sandstone cliff (natural: there was no support under it even before the impact) at ~3 psi stands - the wave
     *  did not break it and did not take away the support. Near the canopy, a wave breaks the glass: the collapse is considered, and the canopy passes
     *  its check, and is not skipped.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_sand", skyAccess = true)
    public static void sandOverhangStays(GameTestHelper h) {
        BlockPos cliff = new BlockPos(46, 12, 28);
        for (BlockPos p : BlockPos.betweenClosed(cliff, cliff.offset(4, 3, 4))) h.setBlock(p, Blocks.SANDSTONE);
        BlockPos overhang = cliff.offset(-2, 4, 0);
        for (BlockPos p : BlockPos.betweenClosed(overhang, overhang.offset(6, 0, 4))) h.setBlock(p, Blocks.SAND);
        BlockPos glass = overhang.offset(7, 0, 2);
        h.setBlock(glass, Blocks.GLASS);
        Detonation d = atPsi(h, CENTER.west(10), true, overhang, 3);
        scarAll(h, d, new ColumnScar.Budget(false));
        h.assertFalse(h.getBlockState(glass).is(Blocks.GLASS), "the glass of the canopy is intact: collapse was not considered (" + psi(d, h, overhang) + ")");
        for (BlockPos p : BlockPos.betweenClosed(overhang, overhang.offset(6, 0, 4))) {
            if (!h.getBlockState(p).is(Blocks.SAND)) h.fail("sand canopy fell into " + p.toShortString() + ": " + h.getBlockState(p) + " (" + psi(d, h, overhang) + ")");
        }
        h.succeed();
    }

    /**
     * The water behind the glass does not remove the difference: the wall of the aquarium is made of glass, behind it there is water, facing the explosion there is air, at ~2 psi
     *  it bursts (pressure on the face, there is nothing solid behind the fragile glass). Glass, surrounded on all sides by water, is
     *  whole: there is nothing to apply pressure to.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_aquarium", skyAccess = true)
    public static void glassBackedByWaterBreaks(GameTestHelper h) {
        BlockPos front = new BlockPos(44, 12, 28); // glass on x = 44, water - 45...47, back wall - 48
        for (int dz = -1; dz <= 5; dz++) {
            for (int y = 0; y < 3; y++) {
                for (int dx = 0; dx <= 4; dx++) {
                    boolean side = dz == -1 || dz == 5;
                    Block b = side || dx == 4 ? Blocks.STONE_BRICKS : dx == 0 ? Blocks.GLASS : Blocks.WATER;
                    h.setBlock(front.offset(dx, y, dz), b);
                }
            }
        }
        BlockPos sunk = front.offset(2, 0, 2); // at the bottom: and when the water drains, it stands on the ground
        h.setBlock(sunk, Blocks.GLASS);
        Detonation d = atPsi(h, CENTER.west(10), true, front.offset(0, 1, 2), 2);
        scarAll(h, d, new ColumnScar.Budget(false));
        String at = " (" + psi(d, h, front.offset(0, 1, 2)) + ")";
        for (int dz = 0; dz < 5; dz++) {
            for (int y = 0; y < 3; y++) {
                if (h.getBlockState(front.offset(0, y, dz)).is(Blocks.GLASS)) h.fail("the glass of the aquarium with water behind it is intact " + front.offset(0, y, dz).toShortString() + at);
            }
        }
        h.assertTrue(h.getBlockState(sunk).is(Blocks.GLASS), "glass in the water is broken" + at);
        h.succeed();
    }

    /**
     * Indestructible (bedrock, obsidian, barrier, block of light) does not fall or come off even in the air without support and
     *  holds like the earth: concrete stands on hanging bedrock, although a nearby wave knocked out the glass.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_fixed", skyAccess = true)
    public static void indestructibleBlocksStandAndHold(GameTestHelper h) {
        BlockPos at = new BlockPos(44, 12, 30);
        for (int y = 0; y < 6; y++) h.setBlock(at.offset(0, y, 0), Blocks.BARRIER);
        h.setBlock(at.offset(0, 6, 0), Blocks.LIGHT);
        h.setBlock(at.offset(3, 8, 0), Blocks.OBSIDIAN);
        BlockPos rock = at.offset(3, 8, 4);
        h.setBlock(rock, Blocks.BEDROCK);
        h.setBlock(rock.above(), Blocks.WHITE_CONCRETE);
        h.setBlock(rock.above().east(), Blocks.GLASS);
        Detonation d = atPsi(h, CENTER.west(10), true, rock, 2);
        scarAll(h, d, new ColumnScar.Budget(false));
        String where = " (" + psi(d, h, rock) + ")";
        for (int y = 0; y < 6; y++) h.assertTrue(h.getBlockState(at.offset(0, y, 0)).is(Blocks.BARRIER), "the barrier fell on " + y + where);
        h.assertTrue(h.getBlockState(at.offset(0, 6, 0)).is(Blocks.LIGHT), "light block removed" + where);
        h.assertTrue(h.getBlockState(at.offset(3, 8, 0)).is(Blocks.OBSIDIAN), "hanging obsidian fell" + where);
        h.assertTrue(h.getBlockState(rock).is(Blocks.BEDROCK), "hanging bedrock fell" + where);
        h.assertFalse(h.getBlockState(rock.above().east()).is(Blocks.GLASS), "the glass is intact: the test did not check anything" + where);
        h.assertTrue(h.getBlockState(rock.above()).is(Blocks.WHITE_CONCRETE), "concrete on bedrock fell" + where);
        h.succeed();
    }

    /**
     * tick liquid: a wave knocks out glass blocks in a checkerboard pattern over a water spill 32×32 (large water - does not drain;
     *  glass does not break in water: the water behind it does not give a drop) with ~3 psi - water under each tick. The chunk plan sets tick no more than the limit per second, the rest - in
     *  the next seconds; at least one chunk has more than the limit.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_fluid_ticks", skyAccess = true)
    public static void fluidTicksStayUnderLimit(GameTestHelper h) {
        BlockPos a = new BlockPos(16, 12, 16);
        for (int dx = -1; dx <= 32; dx++) {
            for (int dz = -1; dz <= 32; dz++) {
                boolean rim = dx < 0 || dz < 0 || dx == 32 || dz == 32;
                h.setBlock(a.offset(dx, 0, dz), rim ? Blocks.STONE_BRICKS : Blocks.WATER);
                if (!rim && (dx + dz) % 2 == 0) h.setBlock(a.offset(dx, 1, dz), Blocks.GLASS);
            }
        }
        Detonation d = atPsi(h, new BlockPos(-60, 12, 32), true, a.offset(16, 0, 16), 3);
        List<RuinPlan> plans = scarAll(h, d, new ColumnScar.Budget(false));
        int most = 0;
        for (RuinPlan p : plans) {
            most = Math.max(most, p.fluidTickCount());
            h.assertTrue(p.maxFluidTicksPerSecond() <= 64, "tick liquid per second " + p.maxFluidTicksPerSecond() + " from " + p.fluidTickCount());
        }
        h.assertTrue(most > 64, "chunks have no more than 64 tick of liquid (" + most + ", " + psi(d, h, a.offset(16, 0, 16)) + "): limit not checked");
        h.succeed();
    }

    /**
     * Ruins in advance on the border of the preparation squares (each 5-th chunk): a blank terracotta wall in the last column of the
     *  chunk of one square, a house with ceilings in the adjacent square (15 kt in the air, 1 block = 20 m). The squares
     *  are prepared separately, and the ruins at their border are the same as in the middle: the wall is collapsing along with the house. If there is no
     *  border of squares on the site (1 times from 25 in its place), the wall stands on the usual border of chunks. When the zone chunks are already on the disk
     *  (the tests unloaded them before), after the detonation the zone behind the wave works: headers and chunks from the disk and plans - according to the
     *  wall clock, so the wait for the ruins goes on at the pace of the game (on CI the server GameTest ticks without pauses, ~2000 tick/s, and
     * 850 tick after detonation ends before the zone releases squares).
     */
    @GameTest(template = "range", timeoutTicks = 3000, batch = "nuke_prep_border", skyAccess = true)
    public static void wallOnPrepTileBorderFalls(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // border of squares along x: chunk cx ≡ 0 (mod 5), the house is in it, the wall is in the last column of the chunk cx − 1
        BlockPos wall = null;
        boolean alongX = true;
        for (int rx = 8; rx <= 50 && wall == null; rx++) {
            int ax = h.absolutePos(new BlockPos(rx, 0, 0)).getX();
            if ((ax & 15) == 0 && Math.floorMod(ax >> 4, 5) == 0) wall = new BlockPos(rx - 1, 12, 26);
        }
        for (int rz = 8; rz <= 50 && wall == null; rz++) {
            int az = h.absolutePos(new BlockPos(0, 0, rz)).getZ();
            if ((az & 15) == 0 && Math.floorMod(az >> 4, 5) == 0) {
                wall = new BlockPos(26, 12, rz - 1);
                alongX = false;
            }
        }
        if (wall == null) wall = chunkCorner(h).west();
        int height = 30;
        for (int y = 0; y < height; y++) {
            for (int u = 0; u <= 6; u++) {
                for (int v = 0; v <= 6; v++) {
                    // u - across the border (0 - wall), v - along
                    boolean edge = u == 0 || u == 6 || v == 0 || v == 6;
                    BlockState st = null;
                    if (u == 0) st = Blocks.BROWN_TERRACOTTA.defaultBlockState();
                    else if (edge) st = y % 5 == 2 && (u + v) % 2 == 0 ? Blocks.GLASS.defaultBlockState() : Blocks.BROWN_TERRACOTTA.defaultBlockState();
                    else if (y % 5 == 4) st = Blocks.WHITE_TERRACOTTA.defaultBlockState();
                    if (st != null) level.setBlock(h.absolutePos(alongX ? wall.offset(u, y, v) : wall.offset(v, y, u)), st, 2);
                }
            }
        }
        BlockPos wallAt = wall;
        boolean x = alongX;
        detonation(h, CENTER, 0, 15, 0.05f); // only backfilling of the layer under the site: blasting - according to schedule
        double scale = IncomingConfig.SERVER.nukeEffectsScale.get();
        IncomingConfig.SERVER.nukeEffectsScale.set(0.05);
        NuclearWorld w = NuclearWorld.get(level);
        NuclearWorld.useClock(level.getServer(), WorkClock.counting(1_000_000L));
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        events.schedule(new NuclearEvents.ScheduledStrike(events.nextId(), target, 15, true, now, now + 300, target, java.util.Optional.empty(), false));
        Runnable restore = () -> {
            IncomingConfig.SERVER.nukeEffectsScale.set(scale);
            NuclearWorld.useClock(level.getServer(), new WorkClock());
            NuclearStrikes.clear(level);
        };
        long[] tickAt = {System.nanoTime()};
        h.onEachTick(() -> {
            Detonation d = events.detonations().stream().filter(e -> e.burst().distanceTo(target) < 400).findFirst().orElse(null);
            String failure = null;
            if (d != null) {
                boolean scarred = true;
                for (int v = 0; v <= 6 && scarred; v++) {
                    BlockPos p = h.absolutePos(x ? wallAt.offset(0, 0, v) : wallAt.offset(v, 0, 0));
                    var chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
                    var house = level.getChunkSource().getChunkNow((p.getX() + (x ? 1 : 0)) >> 4, (p.getZ() + (x ? 0 : 1)) >> 4);
                    scarred = chunk != null && house != null && chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= d.id()
                            && house.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= d.id();
                }
                if (!scarred || w.plannedChunks() > 0 || w.prepTiles() > 0) {
                    RuinBackgroundGameTests.gamePace(tickAt);
                    if (level.getGameTime() < d.gameTime() + 2400) return;
                    failure = "the ruins near the wall did not stand behind 2400 tick after the explosion: left " + w.plannedChunks() + ", squares " + w.prepTiles();
                } else if (w.ruinStats()[0] == 0) {
                    failure = "not a single chunk fell into place according to a ready-made plan";
                } else {
                    for (int v = 0; v <= 6 && failure == null; v++) {
                        for (int y = 5; y < height; y++) {
                            BlockPos p = x ? wallAt.offset(0, y, v) : wallAt.offset(v, y, 0);
                            if (!h.getBlockState(p).isAir()) {
                                failure = "the wall on the border of the squares stands: " + h.getBlockState(p) + " in " + p.toShortString();
                                break;
                            }
                        }
                    }
                }
                restore.run();
                if (failure != null) throw new GameTestAssertException(failure);
                h.succeed();
            } else if (level.getGameTime() > now + 1150) {
                restore.run();
                throw new GameTestAssertException("there was no explosion");
            }
        });
    }
}
