package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.Yield;

/** Real queue progress with finite watched demand, fixed pressure and an independently busy material owner. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinSustainedProgressGameTests {
    private RuinSustainedProgressGameTests() {}

    @GameTest(template = "range", batch = "nuke_sustained_progress", timeoutTicks = 240, skyAccess = true)
    public static void everyWatchedWindowFinishesAtCapacityWithMaterialBacklog(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        NuclearStrikes.clear(level);
        RuinMatterGameTests.clearFixture(level);
        var world = NuclearWorld.get(level);
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER));
        long previous = other.materialReservation;
        int threads = IncomingConfig.SERVER.nukeRuinThreads.get();
        var originalClock = NuclearWorld.clock(level.getServer());
        var clock = WorkClock.counting(1_000_000);
        NuclearWorld.useClock(level.getServer(), clock);
        StrikeGameTests.afterTest(h, () -> {
            other.materialReservation = previous;
            ScarQueue.seenForTest(null);
            NuclearWorld.useClock(level.getServer(), originalClock);
            NuclearStrikes.clear(level);
            RuinMatterGameTests.clearFixture(level);
            RuinWorkers.shutdown();
            IncomingConfig.SERVER.nukeRuinThreads.set(threads);
        });
        clock.start(4_000_000);
        world.tick(level, clock);
        RuinWorkers.shutdown();
        IncomingConfig.SERVER.nukeRuinThreads.set(1);
        RuinWorkers.executor(RuinWorkers.Priority.SEEN);
        var pool = (ThreadPoolExecutor) field(RuinWorkers.class, "pool").get(null);
        h.assertTrue(pool.getPoolSize() == 0, "Fixture must restart a cold worker pool");
        var centre = new ChunkPos(h.absolutePos(new BlockPos(32, 1, 32)));
        var area = new ua.zentix.incoming.strike.AreaLoader.Area(
                net.minecraft.server.level.TicketType.create("incoming_sustained_test", java.util.Comparator.<UUID>naturalOrder()),
                new ChunkPos(centre.x + 1, centre.z + 1), 5, UUID.randomUUID(), false);
        ua.zentix.incoming.strike.StrikeWorld.get(level).areas().hold(level, area);
        StrikeGameTests.afterTest(h, () -> ua.zentix.incoming.strike.StrikeWorld.get(level).areas().release(level, area));
        int y = h.absolutePos(new BlockPos(0, 38, 0)).getY();
        var targets = new ArrayList<BlockPos>();
        var keys = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int z = -2; z < 6; z++) for (int x = -2; x < 6; x++) level.getChunk(centre.x + x, centre.z + z);
        for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
            var at = new BlockPos((centre.x + x) * 16 + 8, y, (centre.z + z) * 16 + 8);
            level.setBlock(at.below(), Blocks.OBSIDIAN.defaultBlockState(), 18);
            level.setBlock(at, Blocks.GLASS.defaultBlockState(), 18);
            targets.add(at);
            keys.add(new ChunkPos(at).toLong());
        }
        double height = Yield.optimalBurstHeight(15), distance = HobBlast.rangeFor(1.2, height, 15);
        var d = new Detonation(NuclearEvents.get(level).nextId(),
                new Vec3(centre.getMiddleBlockX() - distance, y + height, centre.getMiddleBlockZ()),
                y, 15, false, level.getGameTime() - 20000, 0, 0, 20000, 42, 1, false, .25f);
        NuclearEvents.get(level).add(d);
        var ctx = world.ruins(level, d, level.getGameTime());
        long need = 0;
        for (var at : targets) {
            var grid = ctx.grid(level, new ChunkPos(at), true);
            h.assertTrue(grid != null, "Unpressured fixture window refused");
            need = Math.max(need, RuinPlan.maxResultBytes(grid) + RuinPlan.maxFragmentBytes(grid)
                    + RuinResults.OWNER_BYTES + RuinWorkers.threadBufferBytes(level.getHeight()));
        }
        long cached = ctx.shotBytes();
        h.assertTrue(cached > 250_000, "Fixture needs reclaimable original snapshots");
        var root = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data/sustained-progress-" + UUID.randomUUID());
        ctx.archive(new RuinSnapshots(new RuinPages(new RegionStorageInfo("gametest", level.dimension(), "incoming_preparation"), root),
                world.prep().readAccounting(), () -> Math.max(0, NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level))));
        var matter = RuinMatter.get(level);
        var pieces = new ArrayList<RuinMatter.Piece>();
        for (int i = 0; i < 8192; i++) pieces.add(new RuinMatter.Piece(targets.getFirst().above(80),
                Blocks.STONE.defaultBlockState(), null, Vec3.ZERO, level.getGameTime()));
        matter.release(pieces);
        ScarQueue.seenForTest(keys::contains);
        // All demands are offered before the first pressured tick; none has an admitted solver yet.
        for (var at : targets) {
            var pos = new ChunkPos(at);
            ctx.requestWindow(level, pos, RuinWorkers.Priority.SEEN);
            world.scars().offer(level.getChunkSource().getChunkNow(pos.x, pos.z), d);
        }
        // A complete path includes one source publication and the authoritative cohort's material,
        // in addition to the solver/startup bound. The earlier smaller fixture did not fund that path.
        long room = need + cached + (4L << 20) + matter.removalBytes(targets.size());
        h.assertTrue(room > RuinWorkers.threadBufferBytes(level.getHeight()), "Fixture must fund source decoding");
        other.materialReservation += NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level) - room;
        long fixed = other.materialReservation, began = level.getGameTime();
        long[] peak = {NuclearPrep.residentMemory(level)};
        Incoming.LOG.info("SUSTAINED start demand {}, cache {}, solver+restart {}, room {}, fixed {}",
                targets.size(), cached, need, room, fixed);
        h.succeedWhen(() -> {
            h.assertTrue(other.materialReservation == fixed, "External pressure was refunded");
            long resident = NuclearPrep.residentMemory(level);
            peak[0] = Math.max(peak[0], resident);
            h.assertTrue(resident <= NuclearPrep.PLANS_CAP, "Resident ownership exceeded unchanged cap: " + resident);
            int pending = 0;
            for (var at : targets) {
                var pos = new ChunkPos(at);
                var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                h.assertTrue(chunk != null, "Watched cohort unloaded instead of completing");
                if (world.scars().queued(pos.toLong())) pending++;
                else {
                    h.assertTrue(chunk.getExistingData(ua.zentix.incoming.registry.ModAttachments.CHUNK_SCAR).orElse(0) == d.id(),
                            "Queue dropped an unapplied watched chunk");
                    h.assertTrue(!chunk.getBlockState(at).is(Blocks.GLASS), "Queue completed without actual glass damage");
                }
            }
            if ((level.getGameTime() - began) % 20 == 0) Incoming.LOG.info(
                    "SUSTAINED age {} pending {}/{} tasks {} reads {} snapshots {} resident {} material {}",
                    level.getGameTime() - began, pending, targets.size(), ctx.tasks(), ctx.pendingWindowReads(),
                    ctx.shotCount(), resident, matter.pending());
            if ((level.getGameTime() - began) % 20 == 0) Incoming.LOG.info(
                    "SUSTAINED owners ctx {} shots {} results {} detached {} workspace {} ledger {} prep {} scar {} released {}",
                    ctx.retainedBytes(), ctx.shotBytes(), RuinResults.bytes(), RuinInputs.detachedBytes(),
                    RuinWorkers.residentBufferBytes(level.getHeight()), matter.retainedBytes(), world.prep().retainedBytes(),
                    world.scars().retainedBytes(), matter.released());
            h.assertTrue(pending == 0 && world.scars().size() == 0,
                    "Watched demand remains: " + pending + "/" + targets.size() + ", tasks=" + ctx.tasks() + ", snapshots=" + ctx.shotCount());
            h.assertTrue(matter.released() >= 8192 + targets.size(), "Applied glass lost its material ownership");
            h.assertTrue(matter.pending() > 0, "Competing backlog drained before watched completion");
            h.assertTrue(ctx.tasks() == 0 && ctx.pendingWindowReads() == 0, "Completed cohort orphaned source/solver work");
            h.assertTrue(pool.getLargestPoolSize() > 0, "Actual worker never restarted");
            Incoming.LOG.info("SUSTAINED all {} watched applied in {} ticks; peak {}, material released {}, remaining {}",
                    targets.size(), level.getGameTime() - began, peak[0], matter.released(), matter.pending());
        });
    }

    private static Field field(Object owner, String name) throws ReflectiveOperationException {
        Field result = (owner instanceof Class<?> c ? c : owner.getClass()).getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
