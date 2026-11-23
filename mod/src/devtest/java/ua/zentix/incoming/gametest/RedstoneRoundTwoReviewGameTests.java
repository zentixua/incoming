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
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.defense.SamBlock;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Districts;
import ua.zentix.incoming.grid.Node;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.grid.SubstationBlock;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.util.SignalEdge;
import ua.zentix.incoming.util.Terrain;

/**
 * Regression probes from round 2 of the independent review of the redstone sources (PR #747), turned into the contract
 * they now hold: a notice whose circuit reaches an unready chunk (dust then a piston resolving its push, {@link
 * SignalEdge#REACH}) waits without loading it and arrives once the chunks are ready, whether it comes from a change, a
 * placement where the grid is destroyed, a removal by a write that skips updates, or a SAM whose chunk unloads meanwhile
 * (an observer still sees that change).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RedstoneRoundTwoReviewGameTests {
    private RedstoneRoundTwoReviewGameTests() {}

    @GameTest(template = "range", timeoutTicks = 1200, batch = "redstone_r2_piston_halo", skyAccess = true)
    public static void dustAndPistonDoNotLoadBeyondSignalHalo(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos site = site(h, 81);
        ChunkPos outer = new ChunkPos(site.x + 2, site.z);
        BlockPos source = position(site, 13);
        BlockState station = ModBlocks.SUBSTATION.get().defaultBlockState();
        hold(h, site);
        keep(h, site, 1);
        level.setBlock(source, station, Block.UPDATE_ALL);
        Node node = PowerGrid.get(level).nodeAt(source);
        h.assertTrue(node != null, "missing substation node");
        restoreAfterTest(h, source);
        Blackouts.knockOut(level, node);
        for (int distance = 1; distance <= 15; distance++) placeWire(level, source.east(distance));
        level.setBlock(source.east(16), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(source.east(18), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(source.east(17), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST),
                Block.UPDATE_ALL);
        h.assertTrue(power(level, source.east(15)) == 0, "wire tail begins powered");
        release(level, site, 2);
        h.startSequence()
                .thenWaitUntil(() -> h.assertFalse(Terrain.ready(level, outer.x, outer.z), "outer east chunk is still ready"))
                .thenExecute(() -> {
                    h.assertFalse(SignalEdge.ready(level, source), "the piston's push reaches an unready chunk, yet the source counts as ready");
                    Blackouts.restore(level, Vec3.atCenterOf(source), 1, 0);
                    h.assertTrue(level.getBlockState(source).getValue(SubstationBlock.POWERED), "the restore did not bring the substation back");
                    h.assertFalse(Terrain.ready(level, outer.x, outer.z), "the wire and piston loaded +19 during restore");
                    h.assertTrue(power(level, source.east(15)) == 0 && SignalEdge.waiting(level, source), "the rising edge was not held");
                    hold(h, site);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(power(level, source.east(15)) == 1, "the held rising edge never reached the wire tail");
                    // the block the last wire points into powers the piston: the edge ran the push resolution too
                    BlockState piston = level.getBlockState(source.east(17));
                    h.assertTrue(piston.is(Blocks.PISTON) && piston.getValue(PistonBaseBlock.EXTENDED), "the piston at the wire's end did not extend");
                })
                .thenSucceed();
    }

    @GameTest(template = "range", timeoutTicks = 1200, batch = "redstone_r2_quiet_placement", skyAccess = true)
    public static void quietPlacementInDestroyedDistrictDoesNotLoadChunk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos site = site(h, 97);
        ChunkPos east = new ChunkPos(site.x + 1, site.z);
        BlockPos source = position(site, 13);
        BlockPos control = source.north(4);
        BlockState station = ModBlocks.SUBSTATION.get().defaultBlockState();
        int quiet = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        hold(h, site);
        keep(h, site, 0);
        for (BlockPos at : new BlockPos[] {source, control}) {
            placeWire(level, at.east());
            level.setBlock(at.east(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        PowerGrid grid = PowerGrid.get(level);
        long district = Districts.of(site.x, site.z);
        long now = Blackouts.now(level);
        Outage outage = grid.addOutage(new Outage(0, Districts.seedX(district), Districts.seedZ(district), 64, now,
                1, Outage.NEVER, 0, -1, 64, 64));
        StrikeGameTests.afterTest(h, () -> grid.replace(outage.restoring(Blackouts.now(level), 0)));
        release(level, site, 2);
        h.startSequence()
                .thenWaitUntil(() -> h.assertFalse(Terrain.ready(level, east.x, east.z), "east chunk is still ready"))
                .thenExecute(() -> {
                    h.assertTrue(grid.destroyed(site.x, site.z, Blackouts.now(level)), "district is not destroyed");
                    h.assertTrue(level.setBlock(control, station.setValue(SubstationBlock.POWERED, false), quiet),
                            "control placement failed");
                    h.assertFalse(Terrain.ready(level, east.x, east.z), "quiet placement loaded the control's east chunk");
                    // a write of a working state (as `/setblock …[powered=true]`): the substation takes the grid's dead state
                    h.assertTrue(level.setBlock(source, station.setValue(SubstationBlock.POWERED, true), quiet), "placement failed");
                    h.assertFalse(level.getBlockState(source).getValue(SubstationBlock.POWERED), "placed substation is on");
                    h.assertFalse(Terrain.ready(level, east.x, east.z), "nested placement notified dust and loaded the east chunk");
                    h.assertTrue(SignalEdge.waiting(level, source), "the dead substation's notice was dropped, not held");
                    hold(h, site);
                })
                .thenWaitUntil(() -> h.assertFalse(SignalEdge.waiting(level, source), "the held notice never went out"))
                .thenExecute(() -> h.assertTrue(power(level, source.east()) == 0, "dust powered by a substation that never worked"))
                .thenSucceed();
    }

    @GameTest(template = "range", timeoutTicks = 1200, batch = "redstone_r2_removal", skyAccess = true)
    public static void quietRemovalDeliversFallingEdgeAfterHaloLoads(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos site = site(h, 113);
        ChunkPos east = new ChunkPos(site.x + 1, site.z);
        BlockPos working = position(site, 13);
        BlockPos knockedOut = working.north(4);
        BlockState station = ModBlocks.SUBSTATION.get().defaultBlockState();
        hold(h, site);
        keep(h, site, 0);
        for (BlockPos at : new BlockPos[] {working, knockedOut}) {
            placeWire(level, at.west());
            level.setBlock(at, station, Block.UPDATE_ALL);
            h.assertTrue(power(level, at.west()) == 15, "fixture wire is not powered");
        }
        Node node = PowerGrid.get(level).nodeAt(knockedOut);
        h.assertTrue(node != null, "missing substation node");
        restoreAfterTest(h, knockedOut);
        release(level, site, 2);
        h.startSequence()
                .thenWaitUntil(() -> h.assertFalse(Terrain.ready(level, east.x, east.z), "east chunk is still ready"))
                .thenExecute(() -> {
                    h.assertFalse(SignalEdge.ready(level, working), "the working station's halo is ready");
                    quietRemove(level, working);
                    h.assertFalse(Terrain.ready(level, east.x, east.z), "quiet removal loaded the east chunk");
                    Blackouts.knockOut(level, node);
                    h.assertFalse(level.getBlockState(knockedOut).getValue(SubstationBlock.POWERED), "the station is still on");
                    h.assertTrue(power(level, knockedOut.west()) == 15, "the falling edge was not held");
                    h.assertTrue(SignalEdge.waiting(level, knockedOut), "the knock-out's falling edge is not waiting");
                    hold(h, site);
                })
                // the outer ring of the reach loads in the background
                .thenWaitUntil(() -> h.assertTrue(SignalEdge.ready(level, knockedOut), "the knocked-out station's halo is not ready"))
                .thenExecute(() -> quietRemove(level, knockedOut))
                .thenIdle(40)
                .thenExecute(() -> {
                    h.assertTrue(SignalEdge.ready(level, working) && SignalEdge.ready(level, knockedOut), "a halo became unready");
                    h.assertTrue(level.getBlockState(working).isAir() && level.getBlockState(knockedOut).isAir(), "a source survived");
                    int afterWorking = power(level, working.west());
                    int afterKnockout = power(level, knockedOut.west());
                    h.assertTrue(afterWorking == 0 && afterKnockout == 0,
                            "surviving wires after 40 ticks: removed while on=" + afterWorking + ", after deferred off=" + afterKnockout);
                })
                .thenSucceed();
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "redstone_r2_observer_reload", skyAccess = true)
    public static void samObserverReceivesDeferredEdgeAfterChunkReload(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos site = site(h, 129);
        BlockPos source = position(site, 14);
        BlockPos observer = source.west();
        Vec3 antenna = Vec3.atCenterOf(source).add(0, 1, 0);
        hold(h, site);
        level.setBlock(source, ModBlocks.SAM.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(observer, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
        SamBlockEntity original = (SamBlockEntity) level.getBlockEntity(source);
        h.assertTrue(original != null && !original.alarm(), "SAM does not begin off");
        // the SAM keeps ticking (level 32), the outer ring of its reach unloads
        keep(h, site, 1);
        release(level, site, 2);
        h.startSequence()
                .thenWaitUntil(() -> h.assertFalse(SignalEdge.ready(level, source), "the SAM's whole halo is ready"))
                .thenExecute(() -> {
                    var target = SamGameTests.virtualDrone(h, antenna.add(0, 150, 230), antenna.add(0, 150, 8000), null);
                    StrikeGameTests.afterTest(h, () -> VirtualFlights.get(level).clear(level, p -> p == target));
                })
                .thenWaitUntil(() -> h.assertTrue(original.alarm(), "the SAM did not detect its target"))
                .thenExecute(() -> {
                    h.assertFalse(SignalEdge.ready(level, source), "the edge was not deferred");
                    h.assertFalse(level.getBlockState(observer).getValue(ObserverBlock.POWERED), "observer received the deferred edge early");
                    release(level, site, 1);
                })
                .thenWaitUntil(() -> GridGameTests.unloaded(h, level, site))
                .thenExecute(() -> {
                    hold(h, site);
                })
                .thenWaitUntil(() -> h.assertTrue(SignalEdge.ready(level, source), "the reloaded SAM's halo is not ready"))
                .thenExecute(() -> {
                    h.assertTrue(level.getBlockEntity(source) instanceof SamBlockEntity reloaded && reloaded != original,
                            "the SAM block entity did not reload");
                    h.assertTrue(level.getBlockState(source).getValue(SamBlock.ALARM), "the reloaded SAM did not retain the on state");
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(level.getBlockState(source).getValue(SamBlock.ALARM), "target left before the deferred edge arrived");
                    h.assertTrue(level.getBlockState(observer).getValue(ObserverBlock.POWERED), "observer never received the deferred rising edge");
                })
                .thenSucceed();
    }

    private static ChunkPos site(GameTestHelper h, int offset) {
        ChunkPos origin = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        return new ChunkPos(origin.x + offset, origin.z - offset);
    }

    private static BlockPos position(ChunkPos chunk, int localX) {
        return new BlockPos(chunk.getMinBlockX() + localX, 100, chunk.getMiddleBlockZ());
    }

    private static void hold(GameTestHelper h, ChunkPos site) {
        GridGameTests.holdWide(h.getLevel(), site);
        StrikeGameTests.afterTest(h, () -> release(h.getLevel(), site, 2));
    }

    private static void keep(GameTestHelper h, ChunkPos site, int distance) {
        ServerLevel level = h.getLevel();
        level.getChunkSource().addRegionTicket(GridGameTests.HOLD, site, distance, site);
        StrikeGameTests.afterTest(h, () -> release(level, site, distance));
    }

    private static void release(ServerLevel level, ChunkPos site, int distance) {
        level.getChunkSource().removeRegionTicket(GridGameTests.HOLD, site, distance, site);
    }

    private static void restoreAfterTest(GameTestHelper h, BlockPos pos) {
        StrikeGameTests.afterTest(h, () -> Blackouts.restore(h.getLevel(), Vec3.atCenterOf(pos), 1, 0));
    }

    private static void placeWire(ServerLevel level, BlockPos pos) {
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static int power(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getValue(RedStoneWireBlock.POWER);
    }

    private static void quietRemove(ServerLevel level, BlockPos pos) {
        ColumnScar.replaceKeeping(level, pos, level.getBlockState(pos), Blocks.AIR.defaultBlockState());
    }
}
