package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.StrikeGameTests;

/** Exercise the public preparation path with a real initialized and expired high-count worker pool. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinPreparationMemoryGameTests {
    private RuinPreparationMemoryGameTests() {}

    @GameTest(template = "range", batch = "nuke_prelaunch_workers_46", timeoutTicks = 600, skyAccess = true)
    public static void automatic48CoreEquivalentSurvivesPoolInitializationAndExpiry(GameTestHelper h) throws Exception {
        admission(h, 48 - RuinWorkers.reserve(false));
    }

    @GameTest(template = "range", batch = "nuke_prelaunch_workers_256", timeoutTicks = 600, skyAccess = true)
    public static void maximumConfiguredCountSurvivesPoolInitializationAndExpiry(GameTestHelper h) throws Exception {
        admission(h, 256);
    }

    private static void admission(GameTestHelper h, int desired) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        int previous = IncomingConfig.SERVER.nukeRuinThreads.get();
        RuinWorkers.shutdown();
        IncomingConfig.SERVER.nukeRuinThreads.set(desired);
        UUID owner = UUID.randomUUID(), handle = UUID.randomUUID();
        CountDownLatch workersRelease = new CountDownLatch(1);
        StrikeGameTests.afterTest(h, () -> {
            workersRelease.countDown();
            RuinPreparation.cancel(level, handle);
            RuinWorkers.shutdown();
            IncomingConfig.SERVER.nukeRuinThreads.set(previous);
        });
        var target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 1, 32))).add(0, 500, 0);
        // No geometry/layout injection: this is the same facade and registered tick path used by an order.
        RuinPreparation.prepare(level, handle, owner, target, .000001, true, false);
        assertAdmission(h, handle, "before pool initialization");
        h.assertTrue(RuinWorkers.stopped(), "admission unexpectedly initialized the pool");
        RuinWorkers.submitForTest(() -> true, RuinWorkers.Priority.FAR).get(5, TimeUnit.SECONDS);
        ThreadPoolExecutor pool = pool();
        int admitted = RuinWorkers.memoryThreads(desired, level.getHeight());
        h.assertTrue(pool.prestartAllCoreThreads() == 0, "direct prestart bypassed server workspace admission");
        CountDownLatch workersStarted = new CountDownLatch(admitted);
        var workers = new ArrayList<Future<?>>();
        // Hold actual admitted work: direct ThreadPoolExecutor prestart has no shared-room grant.
        // Submitting in one server step keeps this memory fixture independent of CPU tick adaptation.
        for (int i = 0; i < admitted; i++) workers.add(RuinWorkers.submitForTest(() -> {
            workersStarted.countDown();
            workersRelease.await();
            return null;
        }, RuinWorkers.Priority.FAR));
        h.assertTrue(workersStarted.await(5, TimeUnit.SECONDS), "admitted work did not start all funded workers");
        h.assertTrue(pool.getMaximumPoolSize() == admitted && pool.getPoolSize() == admitted,
                "actual executor parallelism did not follow workspace admission");
        h.assertTrue(RuinWorkers.threads(false) == desired, "memory bound overwrote the configured upper bound");
        assertAdmission(h, handle, "after pool initialization");
        workersRelease.countDown();
        for (Future<?> worker : workers) worker.get(5, TimeUnit.SECONDS);
        pool.setKeepAliveTime(1, TimeUnit.MILLISECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.getPoolSize() != 0 && System.nanoTime() < deadline) Thread.sleep(1);
        h.assertTrue(pool.getPoolSize() == 0, "fixture workers failed to expire");
        h.assertTrue(RuinWorkers.inFlight() == 0, "expired workers retained task accounting");
        assertAdmission(h, handle, "after actual idle expiry");
        pool.setKeepAliveTime(30, TimeUnit.SECONDS);
        h.succeedWhen(() -> {
            RuinPreparation.keepAlive(level, handle, owner);
            var status = RuinPreparation.status(level, handle);
            h.assertTrue(status.resolvedTarget() != null, "surface admission made no progress: " + status);
            h.assertTrue(status.stage() == RuinPreparation.Stage.READY,
                    "real preparation did not reach READY after high-count pool initialization/expiry: " + status);
        });
    }

    private static void assertAdmission(GameTestHelper h, UUID handle, String phase) {
        var status = RuinPreparation.status(h.getLevel(), handle);
        h.assertTrue(status.reason() != RuinPreparation.Reason.MEMORY, phase + " permanently closed preparation: " + status);
        long memory = NuclearPrep.memory(h.getLevel());
        h.assertTrue(memory >= RuinMemory.TRANSITION_ROOM + RuinWorkers.bufferBytes(h.getLevel().getHeight()),
                phase + " lost the future worker or transition reservation");
        h.assertTrue(memory + (32L << 20) < NuclearPrep.PLANS_CAP - (32L << 20),
                phase + " left no workspace for actual preparation inputs");
    }

    private static ThreadPoolExecutor pool() throws ReflectiveOperationException {
        Field field = RuinWorkers.class.getDeclaredField("pool");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(null);
    }
}
