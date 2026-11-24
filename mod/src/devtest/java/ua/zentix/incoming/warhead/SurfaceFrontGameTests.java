package ua.zentix.incoming.warhead;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.work.UnitQueue;

/** Loading failures and the front's arrival times belong to the queue's waits, not the front's work loop. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurfaceFrontGameTests {
    private SurfaceFrontGameTests() {}

    @GameTest(template = "range", timeoutTicks = 80, batch = "blast_front_loading", skyAccess = true)
    public static void expandedCraftAreaUsesReadinessAndHasAFiniteDeadline(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // Previous batches may have loaded the whole test grid. Only the initial chunk should be ready here.
        BlockPos remote = h.absolutePos(new BlockPos(32, 12, 32)).offset(16_384, 0, 16_384);
        ChunkPos chunk = new ChunkPos(remote);
        Vec3 centre = new Vec3(chunk.getMiddleBlockX(), remote.getY() + 0.5, chunk.getMiddleBlockZ());
        level.getChunk(chunk.x, chunk.z);
        int areasBefore = ua.zentix.incoming.strike.StrikeWorld.get(level).areas().size();
        BlastArea initial = BlastArea.hold(level, centre, 1);
        StagedExplosion job = new StagedExplosion(initial, List.of(), centre, 2, false, false, null, null, null);
        try {
            h.assertTrue(job.ready(level), "test's initial area was not ready before expansion");
            // As when a larger craft enters between impact start and scan: radius grows beyond the held metre.
            job.holdFrontArea(level, 60);
            try {
                // Exercise FRONT itself, including blocked() before ready(), without requiring a live craft.
                var phase = StagedExplosion.class.getDeclaredField("phase");
                phase.setAccessible(true);
                for (Object value : phase.getType().getEnumConstants())
                    if (value.toString().equals("FRONT")) phase.set(job, value);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
            h.assertTrue(!job.ready(level), "test's expanded area was already ready");
            UnitQueue queue = new UnitQueue();
            queue.add(0, job);
            WorkClock clock = WorkClock.counting(1_000_000);
            clock.start(30_000_000);
            queue.work(level, UnitQueue.GIVE_UP_TICKS - 1, clock);
            h.assertTrue(!queue.isEmpty() && !job.done(), "job did not wait for missing chunks");
            clock.start(30_000_000);
            queue.work(level, UnitQueue.GIVE_UP_TICKS, clock);
            h.assertTrue(queue.isEmpty() && job.done(), "expanded front can wait forever");
            h.assertTrue(ua.zentix.incoming.strike.StrikeWorld.get(level).areas().size() == areasBefore, "front left held loading areas");
            h.succeed();
        } finally {
            if (!job.done()) job.end(level);
        }
    }

    /**
     * Shutdown drain ({@code UnitQueue.finish}) with a front still on its way: world time stops during the drain, and the
     * front reaches a block at least one tick after the blast starts (front speed at least sound speed, 17 blocks a tick).
     * The front reaches every chosen block now; waiting for a later tick here would hold the server thread forever.
     * GameTest {@code work_stop_front} checks a started front's whole selection.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "blast_front_stop", skyAccess = true)
    public static void shutdownDrainCompletesAWaitingFront(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos wall = h.absolutePos(new BlockPos(34, 14, 30));
        for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) level.setBlock(wall.offset(0, y, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(31, 16, 32)));
        BlastArea area = BlastArea.hold(level, at, 14);
        StagedExplosion job = new StagedExplosion(area, List.of(), at, 40.0, SlabBreach.Placement.NONE, null, true, null, null);
        area.queued(job, level.getGameTime());
        UnitQueue queue = new UnitQueue();
        queue.add(level, job);
        // start, rays and the front in one call, all in this world tick
        queue.finish(level);
        h.assertTrue(queue.isEmpty() && job.done(), "blast still queued after the shutdown drain");
        int left = 0;
        for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) if (level.getBlockState(wall.offset(0, y, z)).is(Blocks.OAK_PLANKS)) left++;
        h.assertTrue(left < 25, "front did not reach the wall during the shutdown drain");
        h.succeed();
    }
}
