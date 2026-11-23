package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Districts;
import ua.zentix.incoming.grid.GridLights;
import ua.zentix.incoming.grid.Node;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.grid.SubstationBlock;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.util.SignalEdge;
import ua.zentix.incoming.util.Terrain;

/**
 * Regression probes from the independent review of the redstone sources (PR #747, round 1), each turned into the
 * contract it now holds: a source never loads a chunk through its circuit (the neighbour update waits until every chunk
 * within {@link SignalEdge#REACH} is ready), a substation placed where the grid is destroyed never shows 15, observers
 * see both sources change, and a source removed by quiet ruin or collapse writes depowers its surviving dust.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RedstoneReviewGameTests {
    private RedstoneReviewGameTests() {}

    @GameTest(template = "range", timeoutTicks = 600, batch = "redstone_review_substation_dust_edge", skyAccess = true)
    public static void substationDustDoesNotLoadAnUnreadyChunk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // other batches leave outages and destroyed zones (a CI shard's nuclear tests): start from a quiet grid
        GridGameTests.quiet(level);
        ChunkPos chunk = remote(h);
        ChunkPos east = new ChunkPos(chunk.x + 1, chunk.z);
        BlockPos edge = new BlockPos(chunk.getMaxBlockX(), 100, chunk.getMiddleBlockZ());
        BlockPos station = edge.west(2);
        BlockPos dust = edge.west();
        hold(h, chunk, 0);
        GridGameTests.holdWide(level, chunk);
        StrikeGameTests.afterTest(h, () -> level.getChunkSource().removeRegionTicket(GridGameTests.HOLD, chunk, 2, chunk));
        placeWire(level, dust);
        level.setBlock(station, ModBlocks.SUBSTATION.get().defaultBlockState(), Block.UPDATE_ALL);
        Node node = PowerGrid.get(level).nodeAt(station);
        restoreAfterTest(h, station);
        h.assertTrue(level.getBlockState(dust).getValue(RedStoneWireBlock.POWER) == 15, "fixture dust is not powered");
        level.getChunkSource().removeRegionTicket(GridGameTests.HOLD, chunk, 2, chunk);
        h.startSequence()
                .thenWaitUntil(() -> h.assertFalse(Terrain.ready(level, east.x, east.z), "east chunk has not become unready"))
                .thenExecute(() -> {
                    h.assertTrue(Terrain.ready(level, station), "source chunk is not ready");
                    Blackouts.knockOut(level, node);
                    h.assertFalse(h.getLevel().getBlockState(station).getValue(SubstationBlock.POWERED), "the substation did not go down");
                    h.assertFalse(Terrain.ready(level, east.x, east.z), "knockout synchronously loaded the east chunk through the dust");
                    // the circuit reaches into the unready chunk: its neighbours learn of it once that chunk is ready
                    h.assertTrue(level.getBlockState(dust).getValue(RedStoneWireBlock.POWER) == 15, "the dust was told while its circuit's chunks were unready");
                    GridGameTests.holdWide(level, chunk);
                })
                .thenWaitUntil(() -> h.assertTrue(level.getBlockState(dust).getValue(RedStoneWireBlock.POWER) == 0, "the deferred update never reached the dust"))
                .thenSucceed();
    }

    @GameTest(template = "range", timeoutTicks = 600, batch = "redstone_review_substation_stale_edge", skyAccess = true)
    public static void loadedSubstationUpdatesWithUnreadyChunkAcrossItsBorder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // other batches leave outages and destroyed zones (a CI shard's nuclear tests): start from a quiet grid
        GridGameTests.quiet(level);
        ChunkPos chunk = remote(h);
        ChunkPos east = new ChunkPos(chunk.x + 1, chunk.z);
        BlockPos station = new BlockPos(chunk.getMaxBlockX(), 100, chunk.getMiddleBlockZ());
        BlockPos dust = station.west();
        hold(h, chunk, 0);
        GridGameTests.holdWide(level, chunk);
        StrikeGameTests.afterTest(h, () -> level.getChunkSource().removeRegionTicket(GridGameTests.HOLD, chunk, 2, chunk));
        placeWire(level, dust);
        level.setBlock(station, ModBlocks.SUBSTATION.get().defaultBlockState(), Block.UPDATE_ALL);
        Node node = PowerGrid.get(level).nodeAt(station);
        restoreAfterTest(h, station);
        h.assertTrue(level.getBlockState(dust).getValue(RedStoneWireBlock.POWER) == 15, "fixture dust is not powered");
        level.getChunkSource().removeRegionTicket(GridGameTests.HOLD, chunk, 2, chunk);
        h.startSequence()
                .thenWaitUntil(() -> h.assertFalse(Terrain.ready(level, east.x, east.z), "east chunk has not become unready"))
                .thenExecute(() -> Blackouts.knockOut(level, node))
                .thenIdle(21)
                .thenExecute(() -> {
                    h.assertTrue(Terrain.ready(level, station), "source chunk stopped being ready");
                    h.assertFalse(Terrain.ready(level, east.x, east.z), "east chunk unexpectedly became ready");
                    h.assertTrue(PowerGrid.get(level).downOutage(node.id(), Blackouts.now(level)).isPresent(), "knockout is not active");
                    // At the edge of the loaded world the change waits for the ground around it (block and dust together).
                    boolean powered = level.getBlockState(station).getValue(SubstationBlock.POWERED);
                    int power = level.getBlockState(dust).getValue(RedStoneWireBlock.POWER);
                    h.assertTrue(power == (powered ? 15 : 0), "dust reads " + power + " beside a substation with powered=" + powered);
                    GridGameTests.holdWide(level, chunk);
                })
                .thenWaitUntil(() -> {
                    h.assertFalse(level.getBlockState(station).getValue(SubstationBlock.POWERED), "the substation stays up once its ground is ready");
                    h.assertTrue(level.getBlockState(dust).getValue(RedStoneWireBlock.POWER) == 0, "dust still powered once the ground is ready");
                })
                .thenSucceed();
    }

    @GameTest(template = "range", timeoutTicks = 600, batch = "redstone_review_sam_dust_edge", skyAccess = true)
    public static void samDustDoesNotLoadBeyondTheFullChunkHalo(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos chunk = remote(h);
        ChunkPos outerEast = new ChunkPos(chunk.x + 2, chunk.z);
        BlockPos sam = new BlockPos(chunk.getMaxBlockX(), 100, chunk.getMiddleBlockZ());
        hold(h, chunk, 1);
        GridGameTests.holdWide(level, chunk);
        StrikeGameTests.afterTest(h, () -> level.getChunkSource().removeRegionTicket(GridGameTests.HOLD, chunk, 2, chunk));
        level.getChunk(outerEast.x, outerEast.z);
        level.setBlock(sam, ModBlocks.SAM.get().defaultBlockState(), Block.UPDATE_ALL);
        for (int dx = 1; dx <= 15; dx++) placeWire(level, sam.east(dx));
        var be = (SamBlockEntity) level.getBlockEntity(sam);
        h.assertFalse(be.alarm(), "SAM alarm is already on");
        level.getChunkSource().removeRegionTicket(GridGameTests.HOLD, chunk, 2, chunk);
        h.startSequence()
                .thenWaitUntil(() -> h.assertFalse(Terrain.ready(level, outerEast.x, outerEast.z), "outer east chunk has not become unready"))
                .thenExecute(() -> {
                    Vec3 antenna = Vec3.atCenterOf(sam).add(0, 1, 0);
                    SamGameTests.virtualDrone(h, antenna.add(0, 150, 230), antenna.add(0, 150, 8000), null);
                })
                .thenWaitUntil(() -> h.assertTrue(be.alarm(), "the ticking SAM has not picked up the target"))
                .thenExecute(() -> {
                    h.assertFalse(Terrain.ready(level, outerEast.x, outerEast.z), "SAM sweep synchronously loaded the chunk beyond the full east halo");
                    int power = level.getBlockState(sam.east(1)).getValue(RedStoneWireBlock.POWER);
                    h.assertTrue(power == 0, "the dust was told while its circuit's chunks were unready: " + power);
                    // the alarm's reach (SignalEdge.REACH) covers two chunks around the SAM's
                    GridGameTests.holdWide(level, chunk);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(level.getBlockState(sam.east(1)).getValue(RedStoneWireBlock.POWER) == 15, "the deferred alarm never reached the dust");
                    h.assertTrue(level.getBlockState(sam.east(15)).getValue(RedStoneWireBlock.POWER) == 1, "the last of 15 dust blocks is not at 1");
                })
                .thenSucceed();
    }

    @GameTest(template = "range", batch = "redstone_review_substation_destroyed_placement", skyAccess = true)
    public static void placementInDestroyedDistrictDoesNotPrimeTnt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // other batches leave outages and destroyed zones (a CI shard's nuclear tests): start from a quiet grid
        GridGameTests.quiet(level);
        BlockPos station = GridGameTests.CENTER;
        BlockPos absolute = h.absolutePos(station);
        ChunkPos chunk = new ChunkPos(absolute);
        long district = Districts.of(chunk.x, chunk.z);
        long now = Blackouts.now(level);
        PowerGrid grid = PowerGrid.get(level);
        var outage = grid.addOutage(new Outage(0, Districts.seedX(district), Districts.seedZ(district), 64, now,
                1, Outage.NEVER, 0, -1, 64, 64));
        StrikeGameTests.afterTest(h, () -> grid.replace(outage.restoring(now, 0)));
        h.assertTrue(grid.destroyed(chunk.x, chunk.z, now), "fixture district has not been destroyed");
        h.setBlock(station.west(), Blocks.TNT);
        h.setBlock(station, ModBlocks.SUBSTATION.get());
        h.assertBlockPresent(Blocks.TNT, station.west());
        h.assertFalse(h.getBlockState(station).getValue(SubstationBlock.POWERED), "fresh substation in the destroyed district emits 15");
        h.succeed();
    }

    @GameTest(template = "range", batch = "redstone_review_substation_observer", skyAccess = true)
    public static void observerSeesSubstationKnockout(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // other batches leave outages and destroyed zones (a CI shard's nuclear tests): start from a quiet grid
        GridGameTests.quiet(level);
        BlockPos source = GridGameTests.CENTER;
        BlockPos observer = source.east();
        h.setBlock(source, ModBlocks.SUBSTATION.get());
        h.setBlock(observer, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST));
        Node node = PowerGrid.get(level).nodeAt(h.absolutePos(source));
        restoreAfterTest(h, h.absolutePos(source));
        // past the first sync and sweep after placement: their recheck of the neighbours must not pulse an observer
        h.startSequence().thenExecuteFor(25, () -> h.assertFalse(h.getBlockState(observer).getValue(ObserverBlock.POWERED),
                        "the observer pulsed with no change of the source"))
                .thenExecute(() -> {
                    Blackouts.knockOut(level, node);
                    h.assertFalse(h.getBlockState(source).getValue(SubstationBlock.POWERED), "substation did not knock out");
                })
                .thenIdle(2)
                .thenExecute(() -> h.assertTrue(h.getBlockState(observer).getValue(ObserverBlock.POWERED),
                        "direct observer missed the substation's POWERED state change"))
                .thenSucceed();
    }

    @GameTest(template = "runway", timeoutTicks = 200, batch = "redstone_review_sam_observer", skyAccess = true)
    public static void observerSeesSamAlarmEdge(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos sam = SamGameTests.SAM;
        BlockPos observer = sam.east();
        SamBlockEntity be = SamGameTests.sam(h, sam, null, 0);
        h.setBlock(observer, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST));
        // past the first sync and sweep after placement: their recheck of the neighbours must not pulse an observer
        h.startSequence().thenExecuteFor(25, () -> h.assertFalse(h.getBlockState(observer).getValue(ObserverBlock.POWERED),
                        "the observer pulsed with no change of the source"))
                .thenExecute(() -> {
                    SamGameTests.drone(h, 16, null);
                })
                .thenWaitUntil(() -> h.assertTrue(be.alarm(), "SAM has not picked up the target"))
                .thenIdle(2)
                .thenExecute(() -> h.assertTrue(h.getBlockState(observer).getValue(ObserverBlock.POWERED),
                        "direct observer missed the SAM's rising edge"))
                .thenSucceed();
    }

    @GameTest(template = "runway", timeoutTicks = 100, batch = "redstone_review_damage_removal", skyAccess = true)
    public static void damageRemovingSignalSourcesDepowersSurvivingDust(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // other batches leave outages and destroyed zones (a CI shard's nuclear tests): start from a quiet grid
        GridGameTests.quiet(level);
        BlockPos samPos = SamGameTests.SAM;
        BlockPos substation = samPos.east(8);
        BlockPos samDust = samPos.west();
        BlockPos substationDust = substation.east();
        SamBlockEntity sam = SamGameTests.sam(h, samPos, null, 0);
        h.setBlock(substation, ModBlocks.SUBSTATION.get());
        placeWire(level, h.absolutePos(samDust));
        placeWire(level, h.absolutePos(substationDust));
        h.startSequence()
                .thenExecute(() -> SamGameTests.drone(h, 16, null))
                .thenWaitUntil(() -> h.assertTrue(sam.alarm(), "SAM has not picked up the target"))
                .thenExecute(() -> {
                    h.assertTrue(h.getBlockState(samDust).getValue(RedStoneWireBlock.POWER) == 15, "SAM dust is not powered");
                    h.assertTrue(h.getBlockState(substationDust).getValue(RedStoneWireBlock.POWER) == 15, "substation dust is not powered");
                    ColumnScar.replace(level, h.absolutePos(samPos), h.getBlockState(samPos), Blocks.AIR.defaultBlockState());
                    ColumnScar.replaceKeeping(level, h.absolutePos(substation), h.getBlockState(substation), Blocks.AIR.defaultBlockState());
                })
                .thenIdle(21)
                .thenExecute(() -> {
                    h.assertBlockPresent(Blocks.AIR, samPos);
                    h.assertBlockPresent(Blocks.AIR, substation);
                    int samPower = h.getBlockState(samDust).getValue(RedStoneWireBlock.POWER);
                    int stationPower = h.getBlockState(substationDust).getValue(RedStoneWireBlock.POWER);
                    h.assertTrue(samPower == 0 && stationPower == 0,
                            "dust remains powered after source removal: SAM=" + samPower + ", substation=" + stationPower);
                })
                .thenSucceed();
    }

    @GameTest(template = "range", timeoutTicks = 1500, batch = "redstone_review_substation_lamp", skyAccess = true)
    public static void blackoutLampObeysDistrictAndResamplesSubstationOnRestore(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        GridGameTests.quiet(level);
        BlockPos station = GridGameTests.CENTER;
        BlockPos lamp = station.west();
        h.setBlock(lamp, Blocks.REDSTONE_LAMP);
        BlockPos absolute = h.absolutePos(station);
        Vec3 center = Vec3.atCenterOf(absolute);
        restoreAfterTest(h, absolute);
        h.assertFalse(h.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "fixture lamp already has power");
        h.startSequence()
                .thenExecute(() -> Blackouts.blackout(level, center, 128, 1000, -1))
                .thenWaitUntil(() -> h.assertTrue(GridLights.isUnlit(h.getBlockState(lamp)), "district blackout has not converted the lamp"))
                .thenExecute(() -> {
                    PowerGrid grid = PowerGrid.get(level);
                    ChunkPos chunk = new ChunkPos(absolute);
                    long now = Blackouts.now(level);
                    h.assertTrue(grid.dark(chunk.x, chunk.z, now) && !grid.destroyed(chunk.x, chunk.z, now), "station district is not dark and intact");
                    h.setBlock(station, ModBlocks.SUBSTATION.get());
                    h.assertTrue(h.getBlockState(station).getValue(SubstationBlock.POWERED), "a new substation is off in an intact district");
                    h.assertTrue(GridLights.isUnlit(h.getBlockState(lamp)) && !h.getBlockState(lamp).getValue(RedstoneLampBlock.LIT),
                            "the substation woke up the blackout lamp before district restoration");
                    Blackouts.restore(level, center, 1, 0);
                })
                .thenWaitUntil(() -> {
                    h.assertBlockPresent(Blocks.REDSTONE_LAMP, lamp);
                    h.assertTrue(h.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "restored lamp did not resample the powered substation");
                })
                .thenSucceed();
    }

    private static void restoreAfterTest(GameTestHelper h, BlockPos pos) {
        StrikeGameTests.afterTest(h, () -> Blackouts.restore(h.getLevel(), Vec3.atCenterOf(pos), 1, 0));
    }

    private static ChunkPos remote(GameTestHelper h) {
        ChunkPos origin = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        return new ChunkPos(origin.x + 65, origin.z - 65);
    }

    private static void hold(GameTestHelper h, ChunkPos chunk, int distance) {
        var cache = h.getLevel().getChunkSource();
        cache.addRegionTicket(GridGameTests.HOLD, chunk, distance, chunk);
        StrikeGameTests.afterTest(h, () -> cache.removeRegionTicket(GridGameTests.HOLD, chunk, distance, chunk));
    }

    private static void placeWire(ServerLevel level, BlockPos pos) {
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
    }
}
