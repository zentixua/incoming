package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;

/** Actual populated Grid consumers through registered preparation cancellation and world-owner removal. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinInputLifetimeGameTests {
    private RuinInputLifetimeGameTests() {}

    @GameTest(template = "range", batch = "nuke_inputs_cancel", timeoutTicks = 400, skyAccess = true)
    public static void cancelledPreparationKeepsActualGridInputs(GameTestHelper h) throws Exception { run(h, "cancel"); }

    @GameTest(template = "range", batch = "nuke_inputs_clear", timeoutTicks = 400, skyAccess = true)
    public static void clearedPreparationKeepsActualGridInputs(GameTestHelper h) throws Exception { run(h, "clear"); }

    @GameTest(template = "range", batch = "nuke_inputs_forget", timeoutTicks = 400, skyAccess = true)
    public static void forgottenTaskAndWorldContextKeepActualGridInputs(GameTestHelper h) throws Exception { run(h, "forget"); }

    @GameTest(template = "range", batch = "nuke_inputs_expiry", timeoutTicks = 400, skyAccess = true)
    public static void expiredWorkerKeepsInputsUntilActualExit(GameTestHelper h) throws Exception { run(h, "expiry"); }

    @GameTest(template = "range", batch = "nuke_inputs_queued", timeoutTicks = 400, skyAccess = true)
    public static void queuedCancellationReleasesGridWithoutRunning(GameTestHelper h) throws Exception { run(h, "queued"); }

    @SuppressWarnings("unchecked")
    private static void run(GameTestHelper h, String route) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var other = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(other != null, "second dimension missing");
        var world = NuclearWorld.get(level);
        var prep = world.prep();
        int threads = IncomingConfig.SERVER.nukeRuinThreads.get();
        IncomingConfig.SERVER.nukeRuinThreads.set(1);
        long initialDetached = RuinInputs.detachedBytes();
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var blockerStarted = new CountDownLatch(1);
        UUID owner = UUID.randomUUID(), handle = UUID.randomUUID();
        var point = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 2, 32)));
        h.setBlock(new BlockPos(32, 2, 32), Blocks.STONE);
        h.setBlock(new BlockPos(32, 3, 32), Blocks.OAK_LOG);
        var d = new Detonation(9191, point.add(0, 20, 0), point.y, 1, true, 100, 0, 0, 20000, 42, 0.25f, false, 1);
        RuinPreparation.prepare(level, handle, owner, point, 1, true, false);
        Object p = request(prep, handle);
        var ctx = new RuinContext(level, d);
        field(p, "ruins").set(p, ctx);
        var centre = new ChunkPos(BlockPos.containing(point));
        var grid = ctx.grid(level, centre, true);
        h.assertTrue(grid.at(0, 0) != null && ctx.shotCount() > 0 && ctx.retainedBytes() > 0,
                "fixture must retain real snapshots and shadow heights");
        long known = ctx.retainedBytes();
        double light = grid.lit(BlockPos.containing(point));
        grid.blast(0, 0);
        var failure = new AtomicReference<Throwable>();
        var consumed = new AtomicBoolean();
        Future<?> blocker = route.equals("queued") ? RuinWorkers.submit(() -> {
            blockerStarted.countDown();
            release.await();
            return null;
        }, RuinWorkers.Priority.SEEN) : null;
        if (blocker != null) h.assertTrue(blockerStarted.await(5, TimeUnit.SECONDS), "blocker did not start");
        var priority = blocker == null ? RuinWorkers.Priority.NEAR : RuinWorkers.Priority.FAR;
        Future<RuinPlan> original = ctx.submitWorker(() -> {
            started.countDown();
            try {
                // An interrupted/expired callable may still be unwinding or inside code ignoring interruption.
                boolean open = false;
                while (!open) {
                    try { open = release.await(20, TimeUnit.SECONDS); }
                    catch (InterruptedException expected) { continue; }
                    if (!open) throw new AssertionError("Grid gate not released");
                }
                if (grid.at(0, 0) == null || grid.lit(BlockPos.containing(point)) != light || !grid.hasBlast(0, 0))
                    throw new AssertionError("cancel mutated running Grid inputs");
                var result = RuinPlanner.finish(grid, Collapse.solve(grid));
                consumed.set(true);
                return result;
            } catch (Throwable t) {
                failure.set(t); // Future cancellation hides callable exceptions: check independently after actual exit.
                throw t;
            }
        }, priority);
        var taskType = Class.forName(RuinContext.class.getName() + "$Task");
        var constructor = taskType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var tasks = (Long2ObjectOpenHashMap<Object>) field(ctx, "tasks").get(ctx);
        tasks.put(centre.toLong(), constructor.newInstance(original, 1, level.getGameTime(), priority));
        if (blocker != null) ctx.promote(centre.toLong(), RuinWorkers.Priority.SEEN);
        Future<RuinPlan> future = (Future<RuinPlan>) get(tasks.get(centre.toLong()), "future");
        if (blocker != null) h.assertTrue(original.isCancelled() && original != future, "queued promotion did not transfer the actual callback");
        if (blocker == null) h.assertTrue(started.await(5, TimeUnit.SECONDS), "actual Grid worker did not start");
        var otherWorld = NuclearWorld.get(other);
        long oldReservation = otherWorld.materialReservation;
        StrikeGameTests.afterTest(h, () -> {
            release.countDown();
            RuinPreparation.cancel(level, handle);
            world.forgetRuinsForTest(d.id());
            ctx.cancelTasks();
            otherWorld.materialReservation = oldReservation;
            IncomingConfig.SERVER.nukeRuinThreads.set(threads);
        });
        if (route.equals("expiry")) {
            ctx.expire(level.getGameTime() + RuinContext.TASK_AGE_LIMIT + 1);
            h.assertTrue(future.isDone() && !ctx.done(centre.toLong()), "expiry confused cancellation with actual exit");
            RuinPreparation.cancel(level, handle);
        } else if (route.equals("forget")) {
            ctx.forget(centre.toLong());
            h.assertTrue(future.isDone() && !RuinWorkers.finished(future), "forget must cancel the running future");
            h.assertTrue(ctx.retainedBytes() >= known, "forgotten Grid snapshots disappeared while context attached");
            // A task removed from ctx.tasks must still keep the context charged when its world owner leaves.
            var contexts = (it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<RuinContext>) field(world, "ruins").get(world);
            contexts.put(d.id(), ctx);
            field(p, "ruins").set(p, null);
            world.forgetRuinsForTest(d.id());
            RuinPreparation.cancel(level, handle);
        } else if (route.equals("clear")) {
            prep.clear(level, new ScarQueue());
        } else {
            RuinPreparation.cancel(level, handle);
        }
        int[] phase = {0};
        h.onEachTick(() -> {
            if (phase[0] == 0) {
                if (((java.util.List<?>) get(prep, "preps")).contains(p)) return; // Real registered drain.
                h.assertTrue(future.isDone(), "drain did not cancel future");
                if (blocker != null) {
                    h.assertTrue(RuinWorkers.finished(future) && started.getCount() == 1,
                            "queued Grid callable ran or retained its owner");
                    h.assertTrue(RuinInputs.detachedBytes() == initialDetached, "queued cancellation leaked inputs");
                    Incoming.LOG.info("INPUT_LIFETIME {} known={} detached={} queuedCallableStarted={}", route, known, RuinInputs.detachedBytes(), started.getCount() == 0);
                    release.countDown();
                    phase[0] = 1;
                    return;
                }
                h.assertTrue(!RuinWorkers.finished(future), "gate failed to keep actual worker alive after Future.isDone");
                h.assertTrue(RuinInputs.detachedBytes() >= initialDetached + known, "detached actual Grid input charge lost");
                h.assertTrue(NuclearPrep.residentMemory(other) >= known, "resident cross-dimension counter omitted inputs");
                // Fill only the remaining room. This reservation does not stand in for the actual Grid payload above.
                long total = NuclearPrep.memory(other, false);
                h.assertTrue(total < NuclearPrep.PLANS_CAP / 2, "fixture lacks admission headroom before filling room");
                otherWorld.materialReservation += Math.max(0, NuclearPrep.PLANS_CAP / 2 - total + known / 2);
                h.assertTrue(otherWorld.settleMemoryRoom(other) == 0, "another dimension spent running Grid room");
                Incoming.LOG.info("INPUT_LIFETIME {} held known={} detached={} futureDone={} actualFinished={} otherRoom={}",
                        route, known, RuinInputs.detachedBytes(), future.isDone(), RuinWorkers.finished(future), otherWorld.settleMemoryRoom(other));
                release.countDown();
                phase[0] = 1;
            } else if (RuinWorkers.finished(future) && (blocker == null || RuinWorkers.finished(blocker))) {
                h.assertTrue(failure.get() == null, "actual cancelled callable failed: " + failure.get());
                h.assertTrue(consumed.get() == (blocker == null), "actual Grid was not consumed after cancellation");
                h.assertTrue(RuinInputs.detachedBytes() == initialDetached, "actual exit did not refund exactly once");
                if (blocker == null) h.assertTrue(otherWorld.settleMemoryRoom(other) > 0, "actual exit did not reopen admission");
                ctx.cancelTasks();
                h.assertTrue(RuinInputs.detachedBytes() == initialDetached, "repeated clear refunded twice");
                Incoming.LOG.info("INPUT_LIFETIME {} exited detached={} consumed={} otherRoom={}",
                        route, RuinInputs.detachedBytes(), consumed.get(), otherWorld.settleMemoryRoom(other));
                h.succeed();
            }
        });
    }

    private static Object request(NuclearPrep prep, UUID handle) {
        try {
            var method = NuclearPrep.class.getDeclaredMethod("request", UUID.class);
            method.setAccessible(true);
            return method.invoke(prep, handle);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Object get(Object owner, String name) {
        try { return field(owner, name).get(owner); }
        catch (IllegalAccessException e) { throw new AssertionError(e); }
    }

    private static Field field(Object owner, String name) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
