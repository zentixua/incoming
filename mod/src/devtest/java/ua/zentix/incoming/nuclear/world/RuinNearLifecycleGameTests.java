package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearWarhead;

/** Collector lifetime and the actual near-window inbox, separate from ordinary far reads. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinNearLifecycleGameTests {
    private RuinNearLifecycleGameTests() {}
    private record Fixture(NuclearPrep prep, Object owner, RuinContext ctx, ChunkPos centre, UUID handle, UUID player, Vec3 target) {}

    @SuppressWarnings("unchecked")
    private static Fixture fixture(GameTestHelper h, boolean dense) throws ReflectiveOperationException {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var grid = dense ? RuinStorageGameTests.fixture(h, true, 1) : null;
        var origin = new ChunkPos(h.absolutePos(new BlockPos(32, 1, 32)));
        var centre = grid == null ? new ChunkPos(origin.x + 32, origin.z) : grid.centre();
        if (grid == null) level.getChunk(centre.x, centre.z);
        var target = grid == null ? new Vec3(centre.getMiddleBlockX(), h.absolutePos(new BlockPos(32, 1, 32)).getY(), centre.getMiddleBlockZ())
                : new Vec3(grid.ctx().d.burst().x, grid.ctx().d.groundY(), grid.ctx().d.burst().z);
        UUID handle = UUID.randomUUID(), player = UUID.randomUUID();
        RuinPreparation.prepare(level, handle, player, target, 15, false, false);
        NuclearPrep prep = NuclearWorld.get(level).prep(); Object p = null;
        for (Object candidate : (List<Object>) field(prep, "preps").get(prep))
            if (handle.equals(field(candidate, "handle").get(candidate))) p = candidate;
        if (p == null) throw new AssertionError("Request absent");
        var geometry = grid == null ? NuclearWarhead.geometry(level, target, 15, false, 1) : grid.ctx().d;
        var ctx = grid == null ? new RuinContext(level, geometry) : grid.ctx();
        field(p, "geometry").set(p, geometry); field(p, "resolvedTarget").set(p, target);
        field(p, "ruins").set(p, ctx); field(p, "requiredInitialized").setBoolean(p, true);
        field(p, "requiredTarget").setLong(p, centre.toLong()); field(p, "nextRefresh").setLong(p, Long.MAX_VALUE);
        ((LongOpenHashSet) field(p, "required").get(p)).add(centre.toLong());
        StrikeGameTests.afterTest(h, () -> RuinPreparation.cancel(level, handle));
        return new Fixture(prep, p, ctx, centre, handle, player, target);
    }

    @GameTest(template = "range", batch = "nuke_near_unload", timeoutTicks = 600, skyAccess = true)
    public static void registeredUnloadPreservesActualNearCollector(GameTestHelper h) throws Exception {
        var f = fixture(h, true); var level = h.getLevel();
        var grid = f.ctx.grid(level, f.centre, true); var gate = new CountDownLatch(1);
        StrikeGameTests.afterTest(h, gate::countDown);
        var future = f.ctx.submitWorker(() -> { gate.await(10, TimeUnit.SECONDS); return RuinPlanner.finish(grid, Collapse.solve(grid)); }, RuinWorkers.Priority.NEAR);
        task(f, future, System.identityHashCode(level.getChunkSource().getChunkNow(f.centre.x, f.centre.z)), level.getGameTime());
        NuclearWorld.get(level).onChunkUnload(level, level.getChunkSource().getChunkNow(f.centre.x, f.centre.z));
        h.assertTrue(((LongArrayList) field(f.owner, "submitted").get(f.owner)).contains(f.centre.toLong()), "Registered unload lost the only collector");
        gate.countDown();
        h.succeedWhen(() -> {
            try {
                h.assertTrue(RuinWorkers.finished(future), "Actual worker still running");
                invoke(f, level, "harvest");
                var plan = ((RetainedPlans) field(f.owner, "plans").get(f.owner)).get(f.centre.toLong());
                h.assertTrue(plan != null && plan.changedBlocks() > 0, "Unloaded nonempty plan was never harvested");
                h.assertFalse(f.ctx.running(f.centre.toLong()), "Completed task has no owner/collector");
                NuclearWorld.get(level).onChunkLoad(level, level.getChunkSource().getChunkNow(f.centre.x, f.centre.z));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        });
    }

    @GameTest(template = "range", batch = "nuke_near_failed_task", timeoutTicks = 400, skyAccess = true)
    public static void completedExceptionalNearTaskFailsRequest(GameTestHelper h) throws Exception { failedTask(h, false); }

    @GameTest(template = "range", batch = "nuke_near_cancelled_task", timeoutTicks = 400, skyAccess = true)
    public static void completedCancelledNearTaskFailsRequest(GameTestHelper h) throws Exception { failedTask(h, true); }

    private static void failedTask(GameTestHelper h, boolean cancelled) throws Exception { failedTask(h, cancelled, false); }

    private static void failedTask(GameTestHelper h, boolean cancelled, boolean reloaded) throws Exception {
        var f = fixture(h, true); var gate = new CountDownLatch(1);
        StrikeGameTests.afterTest(h, gate::countDown);
        var future = f.ctx.<RuinPlan>submitWorker(() -> {
            gate.await(10, TimeUnit.SECONDS);
            throw new IllegalStateException("Injected actual near solver failure");
        }, RuinWorkers.Priority.NEAR);
        var original = h.getLevel().getChunkSource().getChunkNow(f.centre.x, f.centre.z);
        task(f, future, reloaded ? System.identityHashCode(original) : 0, h.getLevel().getGameTime());
        if (cancelled) future.cancel(true);
        gate.countDown();
        h.succeedWhen(() -> {
            try {
                h.assertTrue(RuinWorkers.finished(future), "Actual execution has not exited");
                if (reloaded && f.ctx.running(f.centre.toLong())) {
                    NuclearWorld.get(h.getLevel()).onChunkUnload(h.getLevel(), original);
                    // Exercise the actual different-instance collector boundary, without replacing vanilla holders.
                    var replacement = new net.minecraft.world.level.chunk.LevelChunk(h.getLevel(), f.centre);
                    h.assertTrue(System.identityHashCode(replacement) != System.identityHashCode(original), "Reload identity did not change");
                    h.assertTrue(f.ctx.collect(h.getLevel(), replacement) == null, "Failed old task produced a plan");
                    h.assertFalse(f.ctx.running(f.centre.toLong()), "Reload hid the failed old future behind a new task");
                }
                invoke(f, h.getLevel(), "harvest"); requireFailed(h, f);
                h.assertFalse(f.ctx.running(f.centre.toLong()), "Failed actual task remained ownerless");
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        });
    }

    @GameTest(template = "range", batch = "nuke_near_window_read", timeoutTicks = 400, skyAccess = true)
    public static void failedNearWindowReadCannotCertifyReady(GameTestHelper h) throws Exception { window(h, "read error: injected", true); }
    @GameTest(template = "range", batch = "nuke_near_window_decode", timeoutTicks = 400, skyAccess = true)
    public static void failedNearWindowDecodeCannotCertifyReady(GameTestHelper h) throws Exception { window(h, "section 0 not parsed", true); }
    @GameTest(template = "range", batch = "nuke_near_window_absent", timeoutTicks = 600, skyAccess = true)
    public static void absentNearWindowNeighbourIsLegal(GameTestHelper h) throws Exception { window(h, "not on disk", false); }
    @GameTest(template = "range", batch = "nuke_near_window_partial", timeoutTicks = 600, skyAccess = true)
    public static void unfinishedNearWindowNeighbourIsLegal(GameTestHelper h) throws Exception { window(h, "not fully generated on disk", false); }

    @SuppressWarnings("unchecked")
    private static void window(GameTestHelper h, String reason, boolean failed) throws Exception {
        var f = fixture(h, false);
        ChunkPos cold = null;
        for (int dx = -2; dx <= 2 && cold == null; dx++) for (int dz = -2; dz <= 2 && cold == null; dz++)
            if (h.getLevel().getChunkSource().getChunkNow(f.centre.x + dx, f.centre.z + dz) == null)
                cold = new ChunkPos(f.centre.x + dx, f.centre.z + dz);
        h.assertTrue(cold != null, "Actual near window has no cold neighbour");
        long key = cold.toLong();
        var type = Class.forName(RuinContext.class.getName() + "$WindowRead");
        var ctor = type.getDeclaredConstructor(long.class, DiskShots.Read.class, Optional.class); ctor.setAccessible(true);
        var reads = (RuinInbox<Object>) field(f.ctx, "reads").get(f.ctx);
        reads.expect().accept(ctor.newInstance(key, DiskShots.Read.skipped(new ChunkPos(key), reason), null));
        ((LongArrayList) field(f.owner, "todo").get(f.owner)).add(f.centre.toLong());
        var other = NuclearWorld.get(h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER));
        long reservation = other.materialReservation;
        try {
            if (failed) other.materialReservation += Math.max(0, NuclearPrep.PLANS_CAP - (32L << 20) - NuclearPrep.memory(h.getLevel()) + 1);
            invoke(f, h.getLevel(), "submit");
            h.assertTrue(f.ctx.windowReads > 0, "Admission pressure prevented draining the owned window result");
        } finally { other.materialReservation = reservation; }
        if (failed) { requireFailed(h, f); h.succeed(); }
        else h.succeedWhen(() -> {
            h.assertFalse(f.ctx.windowFailed(), "Legal absence was treated as source failure");
            h.assertTrue(RuinPreparation.status(h.getLevel(), f.handle).stage() == RuinPreparation.Stage.READY,
                    "Actual near plan failed to finish with legal absent neighbour");
        });
    }

    private static void requireFailed(GameTestHelper h, Fixture f) {
        var status = RuinPreparation.status(h.getLevel(), f.handle);
        h.assertTrue(status.stage() == RuinPreparation.Stage.FAILED && status.reason() == RuinPreparation.Reason.IO,
                "Actual near failure left request ready/planning: " + status);
        h.assertTrue(RuinPreparation.bindCarrier(h.getLevel(), f.handle, f.player, f.target, 15, false, false) == -1, "Failed near source bound carrier");
        h.assertFalse(RuinPreparation.bind(h.getLevel(), f.handle, 1_903_123), "Failed near source bound scheduled strike");
    }

    @GameTest(template = "range", batch = "nuke_near_reload_failed", timeoutTicks = 400, skyAccess = true)
    public static void failedOldInstanceCannotBecomeSuccessfulReload(GameTestHelper h) throws Exception { failedTask(h, false, true); }
    @GameTest(template = "range", batch = "nuke_near_reload_cancelled", timeoutTicks = 400, skyAccess = true)
    public static void cancelledOldInstanceCannotBecomeSuccessfulReload(GameTestHelper h) throws Exception { failedTask(h, true, true); }

    @GameTest(template = "range", batch = "nuke_near_reload_success", timeoutTicks = 600, skyAccess = true)
    public static void successfulOldInstanceStillReplans(GameTestHelper h) throws Exception {
        var f = fixture(h, true); var level = h.getLevel();
        var original = level.getChunkSource().getChunkNow(f.centre.x, f.centre.z);
        var grid = f.ctx.grid(level, f.centre, true);
        var future = f.ctx.submitWorker(() -> RuinPlanner.finish(grid, Collapse.solve(grid)), RuinWorkers.Priority.NEAR);
        task(f, future, System.identityHashCode(original), level.getGameTime());
        boolean[] replaced = {false};
        h.succeedWhen(() -> {
            try {
                if (!replaced[0]) {
                    h.assertTrue(RuinWorkers.finished(future), "Successful old worker still running");
                    var replacement = new net.minecraft.world.level.chunk.LevelChunk(level, f.centre);
                    h.assertTrue(f.ctx.collect(level, replacement) == null, "Stale successful result applied to another instance");
                    h.assertTrue(f.ctx.running(f.centre.toLong()), "Successful reload did not resubmit");
                    replaced[0] = true;
                }
                invoke(f, level, "harvest");
                var plan = ((RetainedPlans) field(f.owner, "plans").get(f.owner)).get(f.centre.toLong());
                h.assertTrue(plan != null && plan.changedBlocks() > 0, "Successful current-source replacement did not finish");
                h.assertTrue(field(f.owner, "failure").get(f.owner) == RuinPreparation.Reason.NONE, "Successful reload became an error");
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        });
    }

    @GameTest(template = "range", batch = "nuke_near_late_success", timeoutTicks = 600, skyAccess = true)
    public static void overtakenTodoStillDrainsSuccessfulWindowPayload(GameTestHelper h) throws Exception { late(h, null); }
    @GameTest(template = "range", batch = "nuke_near_late_read", timeoutTicks = 600, skyAccess = true)
    public static void overtakenTodoStillRejectsLateReadFailure(GameTestHelper h) throws Exception { late(h, "read error: delayed"); }
    @GameTest(template = "range", batch = "nuke_near_late_decode", timeoutTicks = 600, skyAccess = true)
    public static void overtakenTodoStillRejectsLateDecodeFailure(GameTestHelper h) throws Exception { late(h, "section 0 not parsed"); }

    @SuppressWarnings("unchecked")
    private static void late(GameTestHelper h, String reason) throws Exception {
        var f = fixture(h, true); var level = h.getLevel();
        var grid = f.ctx.grid(level, f.centre, false);
        var actual = RuinPlanner.finish(grid, Collapse.solve(grid));
        h.assertTrue(actual.changedBlocks() > 0, "Overtaking plan is empty");
        ((RetainedPlans) field(f.owner, "plans").get(f.owner)).put(f.centre.toLong(), actual);
        h.assertTrue(((LongArrayList) field(f.owner, "todo").get(f.owner)).isEmpty(), "Fixture still has a todo collector");
        var neighbour = new ChunkPos(f.centre.x + 1, f.centre.z);
        var reads = (RuinInbox<Object>) field(f.ctx, "reads").get(f.ctx);
        long resident = RuinPreparation.status(level, f.handle).residentBytes();
        var complete = reads.expect();
        h.assertTrue(RuinPreparation.status(level, f.handle).residentBytes() >= resident + (4L << 20),
                "Known pending window reservation disappeared from preparation status");
        ((LongOpenHashSet) field(f.ctx, "reading").get(f.ctx)).add(neighbour.toLong());
        var read = reason == null ? DiskShots.parse(level, neighbour, net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level,
                level.getChunkSource().getChunkNow(neighbour.x, neighbour.z))) : DiskShots.Read.skipped(neighbour, reason);
        var type = Class.forName(RuinContext.class.getName() + "$WindowRead");
        var ctor = type.getDeclaredConstructor(long.class, DiskShots.Read.class, Optional.class); ctor.setAccessible(true);
        Object payload = ctor.newInstance(neighbour.toLong(), read, null);
        h.assertTrue(RuinPreparation.status(level, f.handle).stage() != RuinPreparation.Stage.READY,
                "Overtaking plan certified READY before its outstanding required source arrived");
        h.runAfterDelay(2, () -> complete.accept(payload));
        h.succeedWhen(() -> {
            h.assertTrue(f.ctx.pendingWindowReads() == 0, "Late owned result lost its independent collector");
            if (reason == null) {
                h.assertTrue(RuinPreparation.status(level, f.handle).stage() == RuinPreparation.Stage.READY,
                        "Successful late source never released readiness");
                h.assertFalse(f.ctx.windowFailed(), "Successful late source became an error");
            } else requireFailed(h, f);
        });
    }

    @GameTest(template = "range", batch = "nuke_near_handoff_reads", timeoutTicks = 400, skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void partialHandoffRetiresLateWindowWithoutLosingReservation(GameTestHelper h) throws Exception {
        var f = fixture(h, true); var level = h.getLevel();
        var grid = f.ctx.grid(level, f.centre, false);
        var actual = RuinPlanner.finish(grid, Collapse.solve(grid));
        h.assertTrue(actual.changedBlocks() > 0, "Handoff ownership fixture has no actual physical output");
        var plans = (RetainedPlans) field(f.owner, "plans").get(f.owner);
        plans.put(f.centre.toLong(), actual);
        var reads = (RuinInbox<Object>) field(f.ctx, "reads").get(f.ctx);
        var complete = reads.expect();
        long key = ChunkPos.asLong(f.centre.x + 1, f.centre.z);
        ((LongOpenHashSet) field(f.ctx, "reading").get(f.ctx)).add(key);
        ((LongOpenHashSet) field(f.ctx, "pendingWindows").get(f.ctx)).add(f.centre.toLong());
        // This is an already accepted flight, not an empty READY or API binding certificate.
        int identity = 1_904_010;
        field(f.owner, "bound").setBoolean(f.owner, true); field(f.owner, "carrier").setBoolean(f.owner, true);
        field(f.owner, "strike").setInt(f.owner, identity);
        var geometry = f.ctx.d;
        var event = new ua.zentix.incoming.nuclear.Detonation(geometry.id(), geometry.burst(), geometry.groundY(), geometry.yieldKt(),
                geometry.surface(), level.getGameTime(), 0, 0, geometry.visibility(), 0, geometry.scale(), false, geometry.wave());
        var handed = f.prep.handOff(level, event, level.getGameTime(), identity);
        h.assertTrue(handed != null && handed.plans() == plans && handed.plans().get(f.centre.toLong()) == actual,
                "Retiring window I/O changed the actual handed-off plan owner");
        h.assertTrue(f.ctx.pendingWindowReads() == 1, "Handoff forgot a still-outstanding I/O reservation");
        h.assertTrue(((LongOpenHashSet) field(f.ctx, "reading").get(f.ctx)).isEmpty()
                && ((LongOpenHashSet) field(f.ctx, "pendingWindows").get(f.ctx)).isEmpty(), "New scar owner inherited obsolete read/window cursors");
        var type = Class.forName(RuinContext.class.getName() + "$WindowRead");
        var ctor = type.getDeclaredConstructor(long.class, DiskShots.Read.class, Optional.class); ctor.setAccessible(true);
        complete.accept(ctor.newInstance(key, DiskShots.Read.skipped(new ChunkPos(key), "read error: obsolete flight window"), null));
        h.assertTrue(f.ctx.pendingWindowReads() == 0 && !f.ctx.readyWindowReads(), "Late old callback retained its reservation/payload after handoff");
        h.assertFalse(f.ctx.windowFailed(), "Obsolete callback contaminated the new scar owner's source state");
        h.succeed();
    }

    @SuppressWarnings("unchecked")
    private static void task(Fixture f, java.util.concurrent.Future<RuinPlan> future, int chunkId, long now) throws ReflectiveOperationException {
        var type = Class.forName(RuinContext.class.getName() + "$Task");
        var ctor = type.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        ((Long2ObjectOpenHashMap<Object>) field(f.ctx, "tasks").get(f.ctx)).put(f.centre.toLong(), ctor.newInstance(future, chunkId, now, RuinWorkers.Priority.NEAR));
        ((LongArrayList) field(f.owner, "submitted").get(f.owner)).add(f.centre.toLong());
    }

    private static void invoke(Fixture f, ServerLevel level, String name) throws ReflectiveOperationException {
        var method = NuclearPrep.class.getDeclaredMethod(name, ServerLevel.class, f.owner.getClass(), WorkClock.class); method.setAccessible(true);
        ((WorkClock) field(f.prep, "clock").get(f.prep)).start(1_000_000_000);
        var clock = WorkClock.counting(1_000_000); clock.start(1_000_000_000);
        method.invoke(f.prep, level, f.owner, clock);
    }
    private static Field field(Object owner, String name) throws ReflectiveOperationException {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f;
    }
}
