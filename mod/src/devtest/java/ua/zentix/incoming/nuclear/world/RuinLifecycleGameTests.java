package ua.zentix.incoming.nuclear.world;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.StrikeGameTests;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/** Controlled worker ownership, without timing-dependent throughput assertions. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinLifecycleGameTests {
    private RuinLifecycleGameTests() {}

    @GameTest(template = "range", timeoutTicks = 300, batch = "nuke_lifecycle_pool", skyAccess = true)
    public static void resizeKeepsOnePoolAndShutdownTerminatesQueuedFutures(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        RuinWorkers.shutdown();
        int configured = IncomingConfig.SERVER.nukeRuinThreads.get();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        StrikeGameTests.afterTest(h, () -> {
            release.countDown();
            RuinWorkers.shutdown();
            IncomingConfig.SERVER.nukeRuinThreads.set(configured);
        });
        IncomingConfig.SERVER.nukeRuinThreads.set(1);
        Executor captured = RuinWorkers.executor(RuinWorkers.Priority.FAR);
        Future<?> running = RuinWorkers.submitForTest(() -> {
            started.countDown();
            release.await();
            return null;
        }, RuinWorkers.Priority.FAR);
        Future<?> queued = RuinWorkers.submitForTest(() -> { throw new AssertionError("old queued work ran"); }, RuinWorkers.Priority.FAR);
        int[] stage = {0};
        CountDownLatch secondStarted = new CountDownLatch(1);
        Future<?>[] waiting = {null};
        h.onEachTick(() -> {
            if (stage[0] == 0) {
                if (started.getCount() != 0) return;
                queued.cancel(false);
                h.assertTrue(RuinWorkers.inFlight() == 1, "cancelled queue retained its reservation");
                IncomingConfig.SERVER.nukeRuinThreads.set(2);
                RuinWorkers.executor(RuinWorkers.Priority.SEEN); // apply resize
                // The executor captured BEFORE resize must still accept work in the same lifetime.
                captured.execute(() -> {
                    secondStarted.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                stage[0] = 1;
            } else if (stage[0] == 1) {
                if (secondStarted.getCount() != 0) return;
                waiting[0] = RuinWorkers.submitForTest(() -> { throw new AssertionError("shutdown queue ran"); }, RuinWorkers.Priority.FAR);
                RuinWorkers.shutdown();
                h.assertTrue(running.isDone() && queued.isDone() && waiting[0].isCancelled(), "shutdown left unresolved futures");
                try {
                    captured.execute(() -> { throw new AssertionError("old executor resurrected"); });
                    h.fail("closed executor accepted work");
                } catch (RejectedExecutionException expected) {
                    h.assertTrue(RuinWorkers.inFlight() == 0, "late callback changed next-lifetime accounting");
                }
                stage[0] = 2;
                h.succeed();
            }
        });
    }

    @GameTest(template = "range", timeoutTicks = 100, batch = "nuke_lifecycle_layout", skyAccess = true)
    public static void detonationNeverJoinsUnfinishedLayout(GameTestHelper h) throws ReflectiveOperationException {
        var level = h.getLevel();
        var prep = new NuclearPrep();
        var d = new ua.zentix.incoming.nuclear.Detonation(123, new net.minecraft.world.phys.Vec3(0, 80, 0),
                65, 1, true, 100, 0, 0, 20000, 42, 0.25f, false, 1);
        Object pending = preparation(prep, 123);
        field(pending, "geometry").set(pending, d);
        var future = new java.util.concurrent.CompletableFuture<Object>() {
            @Override public Object join() { throw new AssertionError("detonation blocked on layout"); }
        };
        field(pending, "layout").set(pending, future);
        h.assertTrue(prep.handOff(level, d, 100) == null, "unfinished layout was handed off");
        h.assertTrue(future.isCancelled(), "unused layout retained its result ownership");
        var clock = WorkClock.counting(1_000_000);
        clock.start(10_000_000);
        prep.tick(level, new ua.zentix.incoming.nuclear.NuclearEvents(), new ScarQueue(), clock);
        h.assertTrue(prep.idle(), "unprepared detonation left a live preparation");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 100, batch = "nuke_lifecycle_memory", skyAccess = true)
    public static void preparationAdmissionCountsOtherStrikesAndDimensions(GameTestHelper h) throws ReflectiveOperationException {
        var level = h.getLevel();
        var other = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        h.assertTrue(other != null, "missing second dimension");
        var first = NuclearWorld.get(level).prep();
        var second = NuclearWorld.get(other).prep();
        long before = NuclearPrep.memory(level);
        Object a = preparation(first, -1001), b = preparation(second, -1002);
        try {
            field(field(a, "plans").get(a), "bytes").setLong(field(a, "plans").get(a), 300L << 20);
            field(field(b, "plans").get(b), "bytes").setLong(field(b, "plans").get(b), 250L << 20);
            h.assertTrue(NuclearPrep.memory(level) == before + (550L << 20), "per-strike/dimension estimates were omitted or buffers duplicated");
            h.assertTrue(NuclearPrep.memory(level) > NuclearPrep.PLANS_CAP, "other dimension bypassed admission cap");
            h.assertTrue(!NuclearWorld.get(level).settleCanAdmit(level), "settlement ignored cross-dimension preparation");
            h.assertTrue(NuclearWorld.get(level).settleMemoryRoom(level) == 0, "settlement ignored shared retained memory");
        } finally {
            preparations(first).remove(a);
            preparations(second).remove(b);
        }
        h.assertTrue(NuclearPrep.memory(level) == before, "released preparations still charged");
        h.succeed();
    }

    // Test-only fixture injection: delayed completion and estimated byte counts without a large world/allocation.
    private static Object preparation(NuclearPrep prep, int id) throws ReflectiveOperationException {
        Class<?> type = Class.forName(NuclearPrep.class.getName() + "$Prep");
        var ctor = type.getDeclaredConstructor(int.class, long.class, java.util.function.LongConsumer.class);
        ctor.setAccessible(true);
        var reads = (java.util.concurrent.atomic.AtomicLong) field(prep, "readBytes").get(prep);
        Object value = ctor.newInstance(id, 100L, (java.util.function.LongConsumer) reads::addAndGet);
        preparations(prep).add(value);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> preparations(NuclearPrep prep) throws ReflectiveOperationException {
        return (java.util.List<Object>) field(prep, "preps").get(prep);
    }

    private static java.lang.reflect.Field field(Object value, String name) throws ReflectiveOperationException {
        var field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
