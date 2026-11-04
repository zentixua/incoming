package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;

/** A fixed other-dimension owner stays held through real source decoding, publication and physical results. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinSourceProgressGameTests {
    private RuinSourceProgressGameTests() {}

    @GameTest(template = "range", batch = "nuke_source_progress", timeoutTicks = 600, setupTicks = 700, skyAccess = true)
    public static void disjointColdWindowsFinishWithoutExternalRefund(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var blueprint = RuinStorageGameTests.fixture(h, true);
        var world = NuclearWorld.get(level);
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER));
        long originalReservation = other.materialReservation;
        var requests = new ArrayList<RuinContext>();
        var centres = new ArrayList<ChunkPos>();
        var expected = new ArrayList<CompoundTag>();
        var sources = new ArrayList<CompletableFuture<?>>();
        var results = new ArrayList<Future<RuinPlan>>();
        long[] fixed = {Long.MIN_VALUE};
        int[] stage = {0}, ticks = {0}, completed = {0};
        long[] peak = {0}, startupRoom = {-1}, admittedAt = {-1};
        StrikeGameTests.afterTest(h, () -> {
            for (RuinContext ctx : requests) world.forgetRuinsForTest(ctx.d.id());
            RuinWorkers.shutdown();
            other.materialReservation = originalReservation;
        });
        for (int index = 0; index < 3; index++) {
            var centre = new ChunkPos(blueprint.centre().x + 1024 + index * 8, blueprint.centre().z + 1024);
            centres.add(centre);
            var old = blueprint.ctx().d;
            var d = new Detonation(150000 + index, old.burst().add((centre.x - blueprint.centre().x) * 16.0, 0,
                    (centre.z - blueprint.centre().z) * 16.0), old.groundY(), old.yieldKt(), old.surface(),
                    old.gameTime(), 0, 0, old.visibility(), 0, old.scale(), false, old.wave());
            var reference = new RuinContext(level, d);
            // Use the same timestamped owner as gameplay; raw map insertion makes a late-batch
            // context immediately idle and lets the world sweep detach its still-used inputs.
            var ctx = world.ruins(level, d, level.getGameTime()); requests.add(ctx); results.add(null);
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                var pos = new ChunkPos(centre.x + dx, centre.z + dz);
                var live = level.getChunkSource().getChunkNow(blueprint.centre().x + dx, blueprint.centre().z + dz);
                CompoundTag tag = ChunkSerializer.write(level, live);
                tag.putInt("xPos", pos.x); tag.putInt("zPos", pos.z);
                // Actual editor-map priming path, not fabricated ready/empty futures.
                tag.remove("Heightmaps");
                level.getChunkSource().chunkMap.write(pos, tag);
                sources.add(level.getChunkSource().chunkMap.read(pos).thenAccept(found -> {
                    if (found.isEmpty()) throw new AssertionError("Written source absent " + pos);
                }));
                if (!reference.putDisk(level, DiskShots.parse(level, pos, tag))) throw new AssertionError("Reference setup refused");
            }
            RuinPlan referencePlan = RuinStorageGameTests.plan(reference.cachedGridForTest(level, centre));
            h.assertTrue(referencePlan.changedBlocks() > 0 && referencePlan.store().getLongArray("material").length > 0,
                    "Reference must contain actual collapse/removal and material");
            expected.add(referencePlan.store());
        }
        h.succeedWhen(() -> {
            try {
                if (stage[0] == 0) {
                    // Start after the idle horizon and cross a real sweep with the cold reads held.
                    h.assertTrue(level.getGameTime() % 100 >= 90, "Waiting for the next world sweep boundary");
                    h.assertTrue(sources.stream().allMatch(CompletableFuture::isDone), "Source-region writes/reads still pending");
                    for (var source : sources) source.join();
                    RuinWorkers.shutdown();
                    RuinWorkers.executor(RuinWorkers.Priority.NEAR); // Create the current pool without starting a thread.
                    ThreadPoolExecutor pool = (ThreadPoolExecutor) field(RuinWorkers.class, "pool").get(null);
                    h.assertTrue(pool.getPoolSize() == 0 && RuinWorkers.residentBufferBytes(level.getHeight()) == 0,
                            "Fixture must start with no resident worker credit");
                    long room = 48L << 20;
                    other.materialReservation += NuclearPrep.PLANS_CAP - room - NuclearPrep.residentMemory(level);
                    fixed[0] = other.materialReservation;
                    // All requests run in this one server step: callbacks can only enqueue into the cold pool.
                    for (int i = 0; i < requests.size(); i++) requests.get(i).requestWindow(level, centres.get(i));
                    long pending = requests.stream().mapToLong(RuinContext::pendingWindowReads).sum();
                    h.assertTrue(pending > 0 && pending < 75, "Disjoint source demand did not exercise aggregate admission");
                    long spare = NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level);
                    startupRoom[0] = spare;
                    admittedAt[0] = level.getGameTime();
                    stage[0] = 1;
                    Incoming.LOG.info("SOURCE_PROGRESS admitted {} real reads; cold pool {}, room {}, fixed other owner {}",
                            pending, pool.getPoolSize(), spare, fixed[0]);
                    stage[0] = 1;
                }
                ticks[0]++;
                h.assertTrue(startupRoom[0] >= RuinWorkers.threadBufferBytes(level.getHeight()),
                        "Source reads spent the last decoder restart: initial room=" + startupRoom[0]);
                h.assertTrue(other.materialReservation == fixed[0], "External owner was refunded during source progress");
                RuinWorkers.adapt(level.getServer());
                for (int i = 0; i < requests.size(); i++) {
                    var ctx = requests.get(i); var centre = centres.get(i);
                    h.assertTrue(world.ruinsIfKept(ctx.d.id()) == ctx,
                            "Active fixture context expired: now=" + level.getGameTime() + ", used=" + ctx.used);
                    ctx.drainWindowReads(level);
                    long observed = NuclearPrep.residentMemory(level); peak[0] = Math.max(peak[0], observed);
                    h.assertTrue(observed <= NuclearPrep.PLANS_CAP, "Publication exceeded shared estimate: " + observed);
                    if (results.get(i) == null && ctx.requestWindow(level, centre) == 0) {
                        var grid = ctx.grid(level, centre, true);
                        if (grid != null) results.set(i, ctx.submitPlanWorker(level, grid,
                                () -> RuinPlanner.finish(grid, Collapse.solve(grid)), RuinWorkers.Priority.NEAR));
                    }
                    var future = results.get(i);
                    if (future != null && RuinWorkers.finished(future)) {
                        RuinPlan plan = future.resultNow();
                        h.assertTrue(plan.changedBlocks() > 0 && plan.store().equals(expected.get(i)),
                                "Same actual disk inputs changed full physical/material output for context " + i);
                    }
                    h.assertFalse(ctx.windowFailed(), "Memory waiting was converted into an I/O failure");
                    h.assertTrue(level.getChunkSource().getChunkNow(centre.x, centre.z) == null, "Cold source loaded/generated a world chunk");
                }
                completed[0] = (int) results.stream().filter(f -> f != null && RuinWorkers.finished(f)).count();
                if (ticks[0] % 20 == 0) Incoming.LOG.info("SOURCE_PROGRESS tick {} complete {}/3, pending {}, known {}, fixed {}",
                        ticks[0], completed[0], requests.stream().mapToInt(RuinContext::pendingWindowReads).sum(), NuclearPrep.residentMemory(level), fixed[0]);
                h.assertTrue(completed[0] == requests.size(), "Owned source work still progressing: " + completed[0] + "/3");
                h.assertTrue(requests.stream().mapToInt(RuinContext::pendingWindowReads).sum() == 0, "Completed windows orphaned read ownership");
                h.assertTrue(ticks[0] > 1 && other.materialReservation == fixed[0], "Progress did not preserve the fixed external owner");
                h.assertTrue(level.getGameTime() > (admittedAt[0] / 100 + 1) * 100,
                        "Source ownership must survive an actual world sweep before completion");
                Incoming.LOG.info("SOURCE_PROGRESS PASS: 3 nonempty identical plans in {} callbacks, peak known {}, external {} held throughout",
                        ticks[0], peak[0], fixed[0]);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        });
    }

    private static Field field(Object owner, String name) throws ReflectiveOperationException {
        var type = owner instanceof Class<?> c ? c : owner.getClass();
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
