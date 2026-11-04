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
    public static void resizeKeepsOnePoolAndShutdownTerminatesQueuedFutures(GameTestHelper h) throws ReflectiveOperationException {
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
        var poolField = RuinWorkers.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        Object originalPool = poolField.get(null);
        Future<?> running = RuinWorkers.submitForTest(() -> {
            started.countDown();
            release.await();
            return null;
        }, RuinWorkers.Priority.FAR);
        Future<?> queued = RuinWorkers.submitForTest(() -> { throw new AssertionError("old queued work ran"); }, RuinWorkers.Priority.FAR);
        int[] stage = {0};
        Future<?>[] waiting = {null};
        int[] observedTicks = {0};
        h.onEachTick(() -> {
            if (++observedTicks[0] % 60 == 1) Incoming.LOG.info("POOLFIXTURE tick={} stage={} configured={} pool={} firstStarted={} secondStarted={} inFlight={}",
                    observedTicks[0], stage[0], IncomingConfig.SERVER.nukeRuinThreads.get(), RuinWorkers.summary(),
                    started.getCount() == 0, false, RuinWorkers.inFlight());
            if (stage[0] == 0) {
                // Diagnostic control: fill the real preceding tick-time window at the existing game pace.
                if (observedTicks[0] < 25 || started.getCount() != 0) return;
                queued.cancel(false);
                h.assertTrue(RuinWorkers.inFlight() == 1, "cancelled queue retained its reservation");
                IncomingConfig.SERVER.nukeRuinThreads.set(2);
                RuinWorkers.executor(RuinWorkers.Priority.SEEN); // apply resize
                Incoming.LOG.info("POOLFIXTURE configured resize: {}", RuinWorkers.summary());
                try {
                    h.assertTrue(poolField.get(null) == originalPool, "resize replaced the worker pool lifetime");
                } catch (IllegalAccessException failure) {
                    throw new AssertionError("cannot inspect resized pool identity", failure);
                }
                // Configured parallelism is an upper bound; adaptation may keep one active worker.
                // The captured executor must accept/count work even while its callback remains queued.
                captured.execute(() -> {
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                h.assertTrue(RuinWorkers.inFlight() == 2, "captured executor left the resized accounting lifetime");
                stage[0] = 1;
            } else if (stage[0] == 1) {
                waiting[0] = RuinWorkers.submitForTest(() -> { throw new AssertionError("shutdown queue ran"); }, RuinWorkers.Priority.FAR);
                h.assertFalse(RuinWorkers.started(waiting[0]), "shutdown probe was not queued behind blocked workers");
                h.assertTrue(RuinWorkers.shutdown(), "shutdown left live worker threads");
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
            long metadata = NuclearPrep.memory(level) - before;
            h.assertTrue(metadata > 0, "Empty preparation/request metadata is not charged");
            field(field(a, "plans").get(a), "bytes").setLong(field(a, "plans").get(a), 300L << 20);
            field(field(b, "plans").get(b), "bytes").setLong(field(b, "plans").get(b), 250L << 20);
            h.assertTrue(NuclearPrep.memory(level) == before + metadata + (550L << 20), "per-strike/dimension estimates were omitted or buffers duplicated");
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

    @GameTest(template = "range", timeoutTicks = 100, batch = "nuke_lifecycle_detached", skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void removedPreparationKeepsUnfinishedReadsCharged(GameTestHelper h) throws ReflectiveOperationException {
        var level = h.getLevel();
        var prep = NuclearWorld.get(level).prep();
        long before = NuclearPrep.memory(level);
        Object old = preparation(prep, -1003);
        var reads = (RuinInbox<Object>) field(old, "reads").get(old);
        var copies = (RuinInbox<Object>) field(field(old, "lods").get(old), "built").get(field(old, "lods").get(old));
        long metadata = NuclearPrep.memory(level) - before;
        var delayed = reads.expect();
        var delayedCopy = copies.expect();
        long reserved = NuclearPrep.memory(level) - before - metadata;
        h.assertTrue(reserved > FrontLods.READ_BYTES, "both reads must be reserved");
        prep.clear(level, new ScarQueue());
        h.assertTrue(prep.idle(), "cancelled preparation still attached");
        h.assertTrue(NuclearPrep.memory(level) == before + reserved, "detached reads disappeared from admission");
        Object next = preparation(prep, -1004);
        long nextMetadata = NuclearPrep.memory(level) - before - reserved;
        h.assertTrue(nextMetadata > 0, "New preparation has no metadata charge");
        try {
            delayed.accept(new Object());
            h.assertTrue(NuclearPrep.memory(level) == before + nextMetadata + FrontLods.READ_BYTES, "disk completion did not release exactly its reservation");
            delayedCopy.accept(new Object());
            h.assertTrue(NuclearPrep.memory(level) == before + nextMetadata, "front-copy completion retained detached bytes");
            h.assertTrue(reads.poll() == null && copies.poll() == null, "cancelled results became publishable");
        } finally {
            delayed.accept(new Object());
            delayedCopy.accept(new Object());
            preparations(prep).remove(next);
        }
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 100, batch = "nuke_lifecycle_far", skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void cancelledFarCopyCannotPublishAfterRefire(GameTestHelper h) throws ReflectiveOperationException {
        var level = h.getLevel();
        var far = FarLods.get(level);
        var pos = new net.minecraft.world.level.ChunkPos(100000, 100000);
        var copy = new net.minecraft.world.level.chunk.ProtoChunk(pos, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                level, level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME), null);
        var reading = (it.unimi.dsi.fastutil.longs.LongOpenHashSet) field(far, "reading").get(far);
        var queue = (java.util.Queue<Object>) field(far, "built").get(far);
        Class<?> type = Class.forName(FarLods.class.getName() + "$Completion");
        var ctor = type.getDeclaredConstructor(FarLods.Built.class, long.class);
        ctor.setAccessible(true);
        long epoch = field(far, "generation").getLong(far), sent = far.stats()[0];
        reading.add(pos.toLong());
        far.clear(level);
        h.assertTrue(reading.contains(pos.toLong()), "cancel released an unfinished read");
        // A new request at the same coordinate waits behind the cancelled read.
        var again = (it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap) field(far, "again").get(far);
        again.put(pos.toLong(), (byte) 1);
        queue.add(ctor.newInstance(new FarLods.Built(pos.toLong(), copy, true, false, null), epoch));
        var tick = FarLods.class.getDeclaredMethod("tick", net.minecraft.server.level.ServerLevel.class);
        tick.setAccessible(true);
        ua.zentix.incoming.compat.DhUpdates.testSink(level, (l, c) -> h.fail("cancelled copy reached LOD sink"));
        try {
            tick.invoke(far, level);
            h.assertTrue(!reading.contains(pos.toLong()), "old completion did not release its read slot");
            h.assertTrue(far.stats()[0] == sent, "cancelled copy was offered for publication");
        } finally {
            far.clear(level);
            ua.zentix.incoming.compat.DhUpdates.testSink(level, null);
        }
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
