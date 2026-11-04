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
            h.assertTrue(NuclearPrep.residentMemory(level) <= NuclearPrep.PLANS_CAP, "workspace producer exceeded global cap");
            if (stage[0] == 0) {
                if (progress.get() < 8) return;
                h.assertTrue(fieldValue(field) == pool, "growth replaced executor lifetime");
                stage[0] = 1;
            } else if (stage[0] == 1) {
                if (progress.get() < 8) return;
                h.assertTrue(progress.get() == 8 && pool.getPoolSize() == 8, "pressure must admit eight workers, never nine");
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
                h.assertTrue(NuclearPrep.residentMemory(level) == NuclearPrep.PLANS_CAP, "material charge lost before callback drain");
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

    private static Object fieldValue(java.lang.reflect.Field field) {
        try { return field.get(null); }
        catch (IllegalAccessException failure) { throw new AssertionError(failure); }
    }
}
