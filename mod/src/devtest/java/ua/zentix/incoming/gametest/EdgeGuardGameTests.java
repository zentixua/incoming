package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.world.RuinPlan;
import ua.zentix.incoming.registry.ModTags;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.warhead.GroundMaterial;
import ua.zentix.incoming.warhead.Warheads;

import java.util.Comparator;
import java.util.List;

/**
 * An explosion at the edge of loading does not load chunks synchronously: the soil and surface of the impact point, the ring of fire of a rocket, broken glass
 *  and logs of trunks felled by a nuclear explosion, read and change only ready-made chunks. Checking - on a fresh chunk without a
 *  ticket: reading an unready chunk on the server loads it directly in the call, and the chunk would be ready after the call.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EdgeGuardGameTests {
    private static final TicketType<ChunkPos> HOLD = TicketType.create("incoming_test_edge_guard", Comparator.comparingLong(ChunkPos::toLong));

    /** The fresh chunk is far behind the platform: no one loaded it. */
    private static ChunkPos fresh(GameTestHelper h, int dx, int dz) {
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos c = new ChunkPos(base.x + dx, base.z + dz);
        h.assertFalse(Terrain.ready(h.getLevel(), c.x, c.z), "chunk " + c + " already loaded - the scan will not check anything");
        return c;
    }

    private static Vec3 middle(ChunkPos c) {
        return new Vec3(c.getMiddleBlockX() + 0.5, 90, c.getMiddleBlockZ() + 0.5);
    }

    /** Ring of Fire Rocket: A pillar in an unfinished chunk does not ignite or load. */
    @GameTest(template = "range", batch = "edge_guard_fire")
    public static void igniteGroundAtUnreadyChunkLoadsNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        h.assertTrue(IncomingConfig.SERVER.fire.get(), "the fire is turned off in the setting - the ring of fire cannot be checked");
        ChunkPos ring = fresh(h, 45, -55);
        Warheads.igniteGround(level, middle(ring).x, middle(ring).z);
        h.assertFalse(Terrain.ready(level, ring.x, ring.z), "setting the earth on fire loaded the chunk " + ring);
        h.succeed();
    }

    /** The ground of the impact point in an unfinished chunk is gravel, the chunk will not load. */
    @GameTest(template = "range", batch = "edge_guard_ground")
    public static void groundSampleAtUnreadyChunkLoadsNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos ground = fresh(h, -55, 45);
        GroundMaterial sampled = GroundMaterial.sample(level, BlockPos.containing(middle(ground)));
        h.assertFalse(Terrain.ready(level, ground.x, ground.z), "impact point ground loaded chunk " + ground);
        h.assertTrue(sampled == GroundMaterial.GRAVEL, "unfinished chunk soil " + sampled + ", waiting for gravel");
        h.succeed();
    }

    /**
     * The detonation itself: soil in the constructor, vanilla explosion - postponed until the area in the background is loaded. Then the test loads the area
     *  itself and waits until the delayed explosions and the detonation timeline are over: otherwise they would load chunks in other people’s tests.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "edge_guard_blast")
    public static void blastAtUnreadyChunkLoadsNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos blast = fresh(h, 55, 55);
        detonateAndFinish(h, blast);
    }

    /**
     * Detonation a chunk that is already loading (there is a ticket, but there is no full download yet): reading a block from it would wait for
     *  to be loaded into {@code managedBlock} directly at tick.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "edge_guard_loading")
    public static void blastAtLoadingChunkWaitsForNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos blast = fresh(h, -60, -40);
        // level 33 without tick: chunk loading started (holder with ticket), getChunkNow - null until loaded
        level.getChunkSource().addRegionTicket(HOLD, blast, 0, blast);
        level.getChunkSource().chunkMap.getDistanceManager().runAllUpdates(level.getChunkSource().chunkMap);
        try {
            h.assertFalse(Terrain.ready(level, blast.x, blast.z), "chunk " + blast + " already loaded - the scan will not check anything");
            detonateAndFinish(h, blast);
        } finally {
            level.getChunkSource().removeRegionTicket(HOLD, blast, 0, blast);
        }
    }

    private static void detonateAndFinish(GameTestHelper h, ChunkPos blast) {
        ServerLevel level = h.getLevel();
        Vec3 at = middle(blast);
        Warheads.detonate(level, WeaponType.DRONE, at, null, null);
        h.assertFalse(Terrain.ready(level, blast.x, blast.z), "detonation loaded impact point chunk " + blast);
        // shahed impact area - up to ~23 blocks from the point (wave rays, Warheads.chargeReach): with a margin
        double area = 32;
        h.runAfterDelay(1, () -> {
            for (int cx = Mth.floor(at.x - area) >> 4; cx <= Mth.floor(at.x + area) >> 4; cx++)
                for (int cz = Mth.floor(at.z - area) >> 4; cz <= Mth.floor(at.z + area) >> 4; cz++) level.getChunk(cx, cz);
        });
        // delayed detonation is triggered in the first tick of the ready area
        h.runAfterDelay(40, h::succeed);
    }

    /**
     * The glass of the unready neighboring chunk remains: {@code setBlock} would update the shape of the neighbor and load it. The glass
     *  inside the chunk is broken.
     */
    @GameTest(template = "range", batch = "edge_guard_glass")
    public static void shatterKeepsGlassNextToUnreadyChunk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = fresh(h, -45, -65);
        ChunkPos east = new ChunkPos(c.x + 1, c.z);
        // level 33: chunk is fully loaded, neighbors are below full load
        level.getChunkSource().addRegionTicket(HOLD, c, 0, c);
        try {
            LevelChunk chunk = level.getChunk(c.x, c.z);
            h.assertFalse(Terrain.ready(level, east.x, east.z), "neighbor " + east + " ready - the check will not check anything");
            // above the relief: natural glass within the scope of inspection will not be included in the bill
            int y = level.getMaxBuildHeight() - 8;
            BlockPos edge = new BlockPos(c.getMaxBlockX(), y, c.getMiddleBlockZ());
            BlockPos inner = new BlockPos(c.getMiddleBlockX(), y, c.getMiddleBlockZ());
            // directly to the section: setBlock would update the neighbor itself
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            section.setBlockState(edge.getX() & 15, y & 15, edge.getZ() & 15, Blocks.GLASS.defaultBlockState());
            section.setBlockState(inner.getX() & 15, y & 15, inner.getZ() & 15, Blocks.GLASS.defaultBlockState());
            // two connected panels in 2 and 1 block from a neighbor: the knocked-out far one changes the shape of the near one, which reads the neighbor
            BlockPos pane = edge.offset(-1, 0, 2), paneNear = edge.offset(0, 0, 2);
            section.setBlockState(pane.getX() & 15, y & 15, pane.getZ() & 15, Blocks.GLASS_PANE.defaultBlockState().setValue(CrossCollisionBlock.EAST, true));
            section.setBlockState(paneNear.getX() & 15, y & 15, paneNear.getZ() & 15, Blocks.GLASS_PANE.defaultBlockState().setValue(CrossCollisionBlock.WEST, true));
            // panel, door, boards in 3, 2 and 1 block from a neighbor: a knocked-out panel wakes up the door, the door checks the
            // redstone signal, and the boards conduct it - their neighbors are read, that is, the neighboring chunk
            BlockPos signal = edge.offset(-2, 0, -2), door = edge.offset(-1, 0, -2), planks = edge.offset(0, 0, -2);
            section.setBlockState(signal.getX() & 15, y & 15, signal.getZ() & 15, Blocks.GLASS_PANE.defaultBlockState());
            section.setBlockState(door.getX() & 15, y & 15, door.getZ() & 15, Blocks.OAK_DOOR.defaultBlockState());
            section.setBlockState(door.getX() & 15, (y + 1) & 15, door.getZ() & 15, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            section.setBlockState(planks.getX() & 15, y & 15, planks.getZ() & 15, Blocks.OAK_PLANKS.defaultBlockState());

            int broken = Warheads.shatter(level, Vec3.atCenterOf(inner), 10, 2, 2, ModTags.SHATTERS);

            h.assertFalse(Terrain.ready(level, east.x, east.z), "broken glass at the edge loaded the neighboring chunk " + east);
            h.assertTrue(section.getBlockState(edge.getX() & 15, y & 15, edge.getZ() & 15).is(Blocks.GLASS), "unprepared neighbor's glass broken");
            h.assertTrue(section.getBlockState(pane.getX() & 15, y & 15, pane.getZ() & 15).is(Blocks.GLASS_PANE), "panel in 2 blocks from an unfinished neighbor is knocked out");
            h.assertTrue(section.getBlockState(signal.getX() & 15, y & 15, signal.getZ() & 15).isAir(), "the door panel is not knocked out");
            h.assertTrue(section.getBlockState(inner.getX() & 15, y & 15, inner.getZ() & 15).isAir(), "the glass inside the chunk is not broken");
            h.assertTrue(broken == 2, "knocked out " + broken + " (waiting for 2: glass inside and panel by the door)");
        } finally {
            level.getChunkSource().removeRegionTicket(HOLD, c, 0, c);
        }
        h.succeed();
    }

    /**
     * The log of a fallen trunk in the second block from an unready neighbor is not placed: to replace the block Sable reads neighbors
     *  places, for solid ones - and their neighbors, and would load the neighbor directly in the call. The log is placed in the middle of the chunk.
     */
    @GameTest(template = "range", batch = "edge_guard_log")
    public static void fallenLogNextToUnreadyChunkLoadsNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = fresh(h, 65, -45);
        ChunkPos east = new ChunkPos(c.x + 1, c.z);
        // level 33: chunk is fully loaded, neighbors are below full load
        level.getChunkSource().addRegionTicket(HOLD, c, 0, c);
        try {
            LevelChunk chunk = level.getChunk(c.x, c.z);
            h.assertFalse(Terrain.ready(level, east.x, east.z), "neighbor " + east + " ready - the check will not check anything");
            // a stone higher than the relief - a support for a log with air above it and, at the edge, a wall next to the log: the neighbors of the solid
            //  block Sable read, but there is no air. Directly into the section and height map: setBlock at the edge I could read the neighbor myself
            int y = level.getMaxBuildHeight() - 8;
            BlockPos edge = new BlockPos(c.getMaxBlockX() - 1, y, c.getMiddleBlockZ());
            BlockPos inner = new BlockPos(c.getMiddleBlockX(), y, c.getMiddleBlockZ());
            for (BlockPos p : List.of(edge, edge.offset(1, 1, 0), inner)) {
                chunk.getSection(chunk.getSectionIndex(p.getY())).setBlockState(p.getX() & 15, p.getY() & 15, p.getZ() & 15, Blocks.STONE.defaultBlockState());
                chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES).update(p.getX() & 15, p.getY(), p.getZ() & 15, Blocks.STONE.defaultBlockState());
            }
            BlockState log = Blocks.OAK_LOG.defaultBlockState();
            RuinPlan.placeLog(level, edge.asLong(), log);
            h.assertFalse(Terrain.ready(level, east.x, east.z), "the log in the second block from the edge loaded the neighboring chunk " + east);
            h.assertTrue(chunk.getBlockState(edge.above()).isAir(), "the log was delivered to an unprepared neighbor");
            RuinPlan.placeLog(level, inner.asLong(), log);
            h.assertTrue(chunk.getBlockState(inner.above()).is(Blocks.OAK_LOG), "the log in the middle of the chunk is not placed");
        } finally {
            level.getChunkSource().removeRegionTicket(HOLD, c, 0, c);
        }
        h.succeed();
    }
}
