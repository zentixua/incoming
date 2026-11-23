package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Districts;
import ua.zentix.incoming.grid.Node;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.grid.SubstationBlock;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.util.SignalEdge;
import ua.zentix.incoming.util.Terrain;

/**
 * Regression probes from round 2 of the independent review (PR #747, the notice queue): a substation replacing a
 * notifier never shows a false 15 (it is dead until the grid says it works), a removal's notice survives a restart (the
 * queue is saved with the world), and a waiting shape update dies with its source, so an observer placed later facing
 * air never pulses.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RedstoneQueueReviewGameTests {
    private RedstoneQueueReviewGameTests() {}

    @GameTest(template = "range", batch = "redstone_queue_review_replacement", skyAccess = true)
    public static void replacingRepeaterWithDeadStationDoesNotPrimeTnt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = GridGameTests.CENTER;
        BlockPos absolute = h.absolutePos(at);
        ChunkPos chunk = new ChunkPos(absolute);
        long district = Districts.of(chunk.x, chunk.z);
        long now = Blackouts.now(level);
        PowerGrid grid = PowerGrid.get(level);
        Outage outage = grid.addOutage(new Outage(0, Districts.seedX(district), Districts.seedZ(district), 64, now,
                1, Outage.NEVER, 0, -1, 64, 64));
        StrikeGameTests.afterTest(h, () -> grid.replace(outage.restoring(Blackouts.now(level), 0)));
        h.assertTrue(grid.destroyed(chunk.x, chunk.z, now), "fixture district is intact");
        h.setBlock(at.below(), Blocks.STONE);
        h.setBlock(at, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.EAST));
        h.setBlock(at.west(), Blocks.TNT);
        h.assertFalse(h.getBlockState(at).getValue(RepeaterBlock.POWERED), "repeater is already powered");
        h.assertBlockPresent(Blocks.TNT, at.west());

        String command = "setblock " + absolute.getX() + " " + absolute.getY() + " " + absolute.getZ() + " incoming:substation";
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level), command);
        h.assertBlockPresent(ModBlocks.SUBSTATION.get(), at);
        h.assertFalse(h.getBlockState(at).getValue(SubstationBlock.POWERED), "the installed substation stays on");
        h.assertBlockPresent(Blocks.TNT, at.west());
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 1200, batch = "redstone_queue_review_restart", skyAccess = true)
    public static void removedSourceStillDepowersWireAfterRestart(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos site = site(h, 141);
        ChunkPos east = new ChunkPos(site.x + 1, site.z);
        BlockPos source = source(site);
        BlockPos wire = source.west();
        hold(h, site);
        level.getChunkSource().addRegionTicket(GridGameTests.HOLD, site, 0, site);
        StrikeGameTests.afterTest(h, () -> release(level, site, 0));
        level.setBlock(wire.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wire, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(source, ModBlocks.SUBSTATION.get().defaultBlockState(), Block.UPDATE_ALL);
        var beforeUnload = level.getChunkAt(source);
        release(level, site, 2);
        h.startSequence()
                .thenWaitUntil(() -> h.assertFalse(Terrain.ready(level, east.x, east.z), "east chunk is still ready"))
                .thenExecute(() -> {
                    h.assertTrue(power(level, wire) == 15, "wire was never powered");
                    quietRemove(level, source);
                    h.assertTrue(SignalEdge.waiting(level, source), "removal has no pending notice");
                    h.assertTrue(PowerGrid.get(level).nodeAt(source) == null, "removed station still has a node");
                    h.assertTrue(power(level, wire) == 15, "fall was delivered before the save");
                    release(level, site, 0);
                })
                .thenWaitUntil(() -> {
                    GridGameTests.unloaded(h, level, site);
                    h.assertTrue(Terrain.inMemory(level, site.toLong()) == null, "chunk still resides in the unload queue");
                })
                .thenExecute(() -> {
                    h.assertTrue(level.getChunkSource().chunkMap.read(site).join().isPresent(), "chunk did not save to disk");
                    // A restart: the queue comes back from what the world saved (the attachment's codec), nothing else.
                    var live = level.getData(ModAttachments.SIGNAL_EDGES);
                    var saved = SignalEdge.Queue.CODEC.encodeStart(NbtOps.INSTANCE, live).getOrThrow();
                    level.setData(ModAttachments.SIGNAL_EDGES, SignalEdge.Queue.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow());
                    h.assertTrue(SignalEdge.waiting(level, source), "the removal's notice did not survive the restart");
                    hold(h, site);
                    h.assertTrue(level.getChunkAt(source) != beforeUnload, "chunk did not reload from disk");
                    h.assertTrue(level.getBlockState(source).isAir(), "removed source reappeared on reload");
                })
                .thenWaitUntil(() -> h.assertTrue(SignalEdge.ready(level, source), "the halo is still unready"))
                .thenIdle(40)
                .thenExecute(() -> {
                    int strength = power(level, wire);
                    h.assertTrue(strength == 0, "wire still at " + strength + " after 40 ticks with the halo ready and no source");
                })
                .thenSucceed();
    }

    @GameTest(template = "range", timeoutTicks = 1200, batch = "redstone_queue_review_stale_shape", skyAccess = true)
    public static void deferredEdgeDoesNotPulseAnObserverInstalledFacingAir(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos site = site(h, 157);
        ChunkPos east = new ChunkPos(site.x + 1, site.z);
        BlockPos source = source(site);
        BlockPos observer = source.west();
        hold(h, site);
        level.getChunkSource().addRegionTicket(GridGameTests.HOLD, site, 1, site);
        StrikeGameTests.afterTest(h, () -> release(level, site, 1));
        level.setBlock(source, ModBlocks.SUBSTATION.get().defaultBlockState(), Block.UPDATE_ALL);
        Node node = PowerGrid.get(level).nodeAt(source);
        h.assertTrue(node != null, "station has no node");
        StrikeGameTests.afterTest(h, () -> Blackouts.restore(level, Vec3.atCenterOf(source), 1, 0));
        release(level, site, 2);
        h.startSequence()
                .thenWaitUntil(() -> h.assertFalse(Terrain.ready(level, east.x + 1, east.z), "outer east chunk is still ready"))
                .thenExecute(() -> {
                    h.assertFalse(SignalEdge.ready(level, source), "the halo is ready");
                    Blackouts.knockOut(level, node);
                    h.assertFalse(level.getBlockState(source).getValue(SubstationBlock.POWERED), "station is still on");
                    quietRemove(level, source);
                    level.setBlock(observer, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.EAST),
                            Block.UPDATE_ALL);
                })
                .thenExecuteFor(6, () -> assertQuietObserver(h, source, observer))
                .thenExecute(() -> {
                    h.assertTrue(SignalEdge.waiting(level, source), "no queued notice to drain");
                    hold(h, site);
                })
                .thenWaitUntil(() -> {
                    assertQuietObserver(h, source, observer);
                    h.assertFalse(SignalEdge.waiting(level, source), "the notice has not drained");
                })
                .thenExecuteFor(8, () -> assertQuietObserver(h, source, observer))
                .thenSucceed();
    }

    private static void assertQuietObserver(GameTestHelper h, BlockPos source, BlockPos observer) {
        ServerLevel level = h.getLevel();
        h.assertTrue(level.getBlockState(source).isAir(), "the watched position changed");
        h.assertFalse(level.getBlockState(observer).getValue(ObserverBlock.POWERED),
                "observer pulsed although it has only ever faced air");
    }

    private static ChunkPos site(GameTestHelper h, int offset) {
        ChunkPos origin = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        return new ChunkPos(origin.x + offset, origin.z - offset);
    }

    private static BlockPos source(ChunkPos site) {
        return new BlockPos(site.getMinBlockX() + 13, 100, site.getMiddleBlockZ());
    }

    private static void hold(GameTestHelper h, ChunkPos site) {
        GridGameTests.holdWide(h.getLevel(), site);
        StrikeGameTests.afterTest(h, () -> release(h.getLevel(), site, 2));
    }

    private static void release(ServerLevel level, ChunkPos site, int distance) {
        level.getChunkSource().removeRegionTicket(GridGameTests.HOLD, site, distance, site);
    }

    private static void quietRemove(ServerLevel level, BlockPos pos) {
        ColumnScar.replaceKeeping(level, pos, level.getBlockState(pos), Blocks.AIR.defaultBlockState());
    }

    private static int power(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getValue(RedStoneWireBlock.POWER);
    }
}
