package ua.zentix.incoming.warhead;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.work.UnitQueue;

/** Loading failures belong to the queue's bounded wait, not the front's work loop. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurfaceFrontGameTests {
    private SurfaceFrontGameTests() {}

    @GameTest(template = "range", timeoutTicks = 80, batch = "blast_front_loading", skyAccess = true)
    public static void expandedCraftAreaUsesReadinessAndHasAFiniteDeadline(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 centre = h.absolutePos(new BlockPos(32, 12, 32)).getCenter();
        int areasBefore = ua.zentix.incoming.strike.StrikeWorld.get(level).areas().size();
        BlastArea initial = BlastArea.hold(level, centre, 1);
        StagedExplosion job = new StagedExplosion(initial, List.of(), centre, 2, false, false, null, null, null);
        try {
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
}
