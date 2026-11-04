package ua.zentix.incoming.nuclear.world;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.StrikeGameTests;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real shared owner traversal and tick-driven admission, with controlled work rather than throughput timing. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinWorkerAdmissionGameTests {
    private RuinWorkerAdmissionGameTests() {}

    @GameTest(template = "range", timeoutTicks = 300, batch = "nuke_worker_admission", skyAccess = true)
    public static void pressureBoundsBirthGrowthAndCapturedCallbackRebirth(GameTestHelper h) throws ReflectiveOperationException {
        StrikeGameTests.gameSpeed(h);
        // This fixture holds non-worker ownership constant. Previous batches can leave cached
        // snapshots and material that normal tick reclamation legitimately frees under pressure.
        for (var previous : h.getLevel().getServer().getAllLevels()) {
            ua.zentix.incoming.nuclear.NuclearStrikes.clear(previous);
            RuinMatterGameTests.clearFixture(previous);
        }
        RuinWorkers.shutdown();
        var level = h.getLevel();
        var other = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        h.assertTrue(other != null, "second dimension absent");
        var owner = NuclearWorld.get(other);
        int configured = IncomingConfig.SERVER.nukeRuinThreads.get();
        long before = owner.materialReservation;
        var release = new CountDownLatch(1);
        var progress = new AtomicInteger();
        StrikeGameTests.afterTest(h, () -> {
            release.countDown();
            RuinWorkers.shutdown();
            owner.materialReservation = before;
            IncomingConfig.SERVER.nukeRuinThreads.set(configured);
        });
        IncomingConfig.SERVER.nukeRuinThreads.set(10);
        var captured = RuinWorkers.executor(RuinWorkers.Priority.SEEN);
        var field = RuinWorkers.class.getDeclaredField("pool");
        field.setAccessible(true);
        var pool = (ThreadPoolExecutor) field.get(null);
        long base = 417_653_525L;
        owner.materialReservation += base - NuclearPrep.residentMemory(level);
        h.assertTrue(NuclearPrep.residentMemory(level) == base, "shared non-worker fixture charge differs");
        Future<?> first = RuinWorkers.submitForTest(() -> { progress.incrementAndGet(); release.await(); return null; }, RuinWorkers.Priority.SEEN);
        h.assertTrue(pool.getPoolSize() == 1, "one-worker control did not start");
        h.assertTrue(NuclearPrep.memory(level) > NuclearPrep.PLANS_CAP, "future-worker policy must refuse far admission");
        var futures = new ArrayList<Future<?>>();
        // Submit in one server step: later CPU adaptation can legitimately retain just one active
        // worker on a game-paced test server. The memory bound is independent of that CPU policy.
        for (int i = 0; i < 9; i++) futures.add(RuinWorkers.submitForTest(() -> {
            progress.incrementAndGet(); release.await(); return null;
        }, RuinWorkers.Priority.NEAR));
        int[] stage = {0};
        h.onEachTick(() -> {
            long resident = NuclearPrep.residentMemory(level);
            h.assertTrue(resident <= NuclearPrep.PLANS_CAP, "workspace producer exceeded global cap: " + resident);
            if (stage[0] == 0) {
                if (progress.get() < 8) return;
                h.assertTrue(fieldValue(field) == pool, "growth replaced executor lifetime");
                stage[0] = 1;
            } else if (stage[0] == 1) {
                if (progress.get() < 8) return;
                h.assertTrue(progress.get() == 8 && pool.getPoolSize() == 8, "pressure must admit eight workers, never nine: progress=" + progress.get()
                                + ", pool=" + pool.getPoolSize() + ", resident=" + resident);
                first.cancel(false);
                h.assertFalse(RuinWorkers.finished(first), "running cancellation released actual execution");
                h.assertTrue(RuinWorkers.residentBufferBytes(384) == 8 * RuinWorkers.threadBufferBytes(384), "cancel lent live workspace");
                pool.setKeepAliveTime(1, TimeUnit.MILLISECONDS);
                release.countDown();
                stage[0] = 2;
            } else if (stage[0] == 2) {
                if (futures.stream().anyMatch(f -> !RuinWorkers.finished(f)) || !RuinWorkers.finished(first)
                        || RuinWorkers.residentBufferBytes(384) != 0) return;
                // Fill every byte left to existing material, then deliver an already-captured callback.
                owner.materialReservation += NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level);
                var callback = new Thread(() -> captured.execute(progress::incrementAndGet), "Worker admission test callback");
                callback.start();
                stage[0] = 3;
            } else if (stage[0] == 3) {
                if (RuinWorkers.inFlight() == 0) return;
                h.assertTrue(progress.get() == 10 && pool.getPoolSize() == 0, "callback borrowed charged material workspace");
                h.assertTrue(resident == NuclearPrep.PLANS_CAP, "material charge lost before callback drain: resident=" + resident);
                owner.materialReservation -= RuinWorkers.threadBufferBytes(384);
                stage[0] = 4;
            } else if (stage[0] == 4) {
                if (progress.get() != 11 || RuinWorkers.inFlight() != 0) return;
                h.assertTrue(fieldValue(field) == pool, "idle rebirth replaced executor lifetime");
                h.assertTrue(pool.getLargestPoolSize() == 8, "callback/adaptation exceeded pressure bound");
                owner.materialReservation = before;
                h.succeed();
            }
        });
    }

    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_worker_exit", skyAccess = true)
    public static void additionalExitOwnershipFollowsPromotionCancellationAndShutdown(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        RuinWorkers.shutdown();
        int configured = IncomingConfig.SERVER.nukeRuinThreads.get();
        IncomingConfig.SERVER.nukeRuinThreads.set(1);
        long baseline = RuinInputs.detachedBytes();
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var exits = new AtomicInteger();
        var inputs = new RuinInputs();
        StrikeGameTests.afterTest(h, () -> {
            release.countDown();
            RuinWorkers.shutdown();
            IncomingConfig.SERVER.nukeRuinThreads.set(configured);
        });
        Future<?> running = RuinWorkers.submit(() -> { started.countDown(); release.await(); return null; },
                RuinWorkers.Priority.SEEN, inputs, exits::incrementAndGet);
        int[] stage = {0};
        Future<?>[] normal = {null}, stopRunning = {null};
        var normalExits = new AtomicInteger();
        var stopExits = new AtomicInteger();
        var stopStarted = new CountDownLatch(1);
        var stopRelease = new CountDownLatch(1);
        StrikeGameTests.afterTest(h, stopRelease::countDown);
        h.onEachTick(() -> {
            if (stage[0] == 0) {
                if (started.getCount() != 0) return;
                var queuedInputs = new RuinInputs();
                var queuedExits = new AtomicInteger();
                Future<?> queued = RuinWorkers.submit(() -> { throw new AssertionError("cancelled promotion ran"); },
                        RuinWorkers.Priority.FAR, queuedInputs, queuedExits::incrementAndGet);
                Future<?> moved = RuinWorkers.promote(queued, RuinWorkers.Priority.NEAR);
                h.assertTrue(moved != null && queued.isCancelled(), "queued task did not transfer through promotion");
                h.assertTrue(queuedExits.get() == 0, "promotion signalled exit of transferred callable");
                queuedInputs.detach(5678);
                inputs.detach(12345);
                moved.cancel(false);
                h.assertTrue(queuedExits.get() == 1 && RuinWorkers.finished(moved), "queued removal missed or duplicated exit");
                h.assertTrue(RuinInputs.detachedBytes() == baseline + 12345, "promotion released wrong input owner");
                running.cancel(false);
                h.assertTrue(exits.get() == 0 && !RuinWorkers.finished(running), "Future cancellation signalled actual exit");
                release.countDown();
                stage[0] = 1;
            } else if (stage[0] == 1) {
                if (!RuinWorkers.finished(running)) return;
                h.assertTrue(exits.get() == 1 && RuinInputs.detachedBytes() == baseline, "actual exit did not release both owners once");
                normal[0] = RuinWorkers.submit(() -> true, RuinWorkers.Priority.SEEN, new RuinInputs(), normalExits::incrementAndGet);
                stage[0] = 2;
            } else if (stage[0] == 2) {
                if (!RuinWorkers.finished(normal[0])) return;
                h.assertTrue(normalExits.get() == 1 && !normal[0].isCancelled(), "normal completion missed its exit hook");
                stopRunning[0] = RuinWorkers.submit(() -> { stopStarted.countDown(); stopRelease.await(); return null; },
                        RuinWorkers.Priority.SEEN, new RuinInputs(), stopExits::incrementAndGet);
                stage[0] = 3;
            } else if (stage[0] == 3) {
                if (stopStarted.getCount() != 0) return;
                var queuedExits = new AtomicInteger();
                Future<?> queued = RuinWorkers.submit(() -> { throw new AssertionError("shutdown queue ran"); },
                        RuinWorkers.Priority.FAR, new RuinInputs(), queuedExits::incrementAndGet);
                h.assertTrue(RuinWorkers.shutdown(), "shutdown left executing work");
                h.assertTrue(queued.isCancelled() && RuinWorkers.finished(queued), "shutdown queue retained ownership");
                h.assertTrue(RuinWorkers.finished(stopRunning[0]) && stopExits.get() == 1 && queuedExits.get() == 1,
                        "shutdown missed or duplicated an actual-exit callback");
                h.assertTrue(RuinInputs.detachedBytes() == baseline, "exit hook changed input accounting");
                h.succeed();
            }
        });
    }

    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_worker_exit_failure", skyAccess = true)
    public static void failedObserverCannotAbandonTheRestOfShutdownQueue(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        RuinWorkers.shutdown();
        int configured = IncomingConfig.SERVER.nukeRuinThreads.get();
        IncomingConfig.SERVER.nukeRuinThreads.set(1);
        long baseline = RuinInputs.detachedBytes();
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        StrikeGameTests.afterTest(h, () -> {
            release.countDown();
            RuinWorkers.shutdown();
            IncomingConfig.SERVER.nukeRuinThreads.set(configured);
        });
        var firstInputs = new RuinInputs();
        var firstExits = new AtomicInteger();
        Future<?> first = RuinWorkers.submit(() -> { started.countDown(); release.await(); return null; },
                RuinWorkers.Priority.SEEN, firstInputs, firstExits::incrementAndGet);
        h.onEachTick(() -> {
            if (started.getCount() != 0) return;
            var failedInputs = new RuinInputs();
            var lastInputs = new RuinInputs();
            var failedExits = new AtomicInteger();
            var lastExits = new AtomicInteger();
            Future<?> failed = RuinWorkers.submit(() -> { throw new AssertionError("shutdown failure probe ran"); },
                    RuinWorkers.Priority.FAR, failedInputs, () -> {
                        failedExits.incrementAndGet();
                        throw new IllegalStateException("controlled shutdown ownership observer failure");
                    });
            Future<?> last = RuinWorkers.submit(() -> { throw new AssertionError("later shutdown probe ran"); },
                    RuinWorkers.Priority.FAR, lastInputs, lastExits::incrementAndGet);
            firstInputs.detach(1122);
            failedInputs.detach(2233);
            lastInputs.detach(3344);
            h.assertTrue(RuinInputs.detachedBytes() == baseline + 6699, "fixture did not retain all three input owners");
            RuntimeException escaped = null;
            boolean stopped = false;
            try { stopped = RuinWorkers.shutdown(); } catch (RuntimeException failure) { escaped = failure; }
            h.assertTrue(failed.isCancelled() && last.isCancelled() && RuinWorkers.finished(first)
                    && RuinWorkers.finished(failed) && RuinWorkers.finished(last), "shutdown abandoned a drained future");
            h.assertTrue(stopped && escaped == null, "observer failure prevented complete shutdown");
            h.assertTrue(firstExits.get() == 1 && failedExits.get() == 1 && lastExits.get() == 1,
                    "shutdown observer failure skipped or duplicated another exit");
            h.assertTrue(RuinInputs.detachedBytes() == baseline && RuinWorkers.inFlight() == 0,
                    "shutdown observer failure retained input or task ownership");
            h.assertTrue(RuinWorkers.shutdown(), "repeat shutdown changed terminal state");
            h.succeed();
        });
    }

    private static Object fieldValue(java.lang.reflect.Field field) {
        try { return field.get(null); }
        catch (IllegalAccessException failure) { throw new AssertionError(failure); }
    }
}
