package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
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
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;

/** Real nonempty near plans, selectively stalled far work, and a newly loaded binding boundary. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinNearReadyGameTests {
    private RuinNearReadyGameTests() {}

    @GameTest(template = "range", batch = "nuke_near_ready", timeoutTicks = 1800, skyAccess = true)
    public static void nearPlansCanLaunchWhileFarWorkContinues(GameTestHelper h) throws ReflectiveOperationException {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(32, 1, 32));
        ChunkPos centre = new ChunkPos(at);
        for (int y = 1; y <= 5; y++) for (int x = -3; x <= 3; x++)
            level.setBlock(at.offset(x, y, 0), Blocks.GLASS.defaultBlockState(), 18);
        Vec3 target = new Vec3(centre.getMiddleBlockX(), at.getY(), centre.getMiddleBlockZ());
        UUID handle = UUID.randomUUID(), owner = UUID.randomUUID();
        RuinPreparation.prepare(level, handle, owner, target, .01, false, true);
        var prep = NuclearWorld.get(level).prep();
        Object p = request(prep, handle);
        StrikeGameTests.afterTest(h, () -> RuinPreparation.cancel(level, handle));
        boolean[] stalled = {false}, loaded = {false};
        long[] added = {0};
        h.succeedWhen(() -> {
            try {
                RuinPreparation.keepAlive(level, handle, owner);
                var status = RuinPreparation.status(level, handle);
                h.assertTrue(field(p, "requiredInitialized").getBoolean(p), "Critical layout not installed yet: " + status);
                long[] order = (long[]) field(p, "order").get(p);
                if (!stalled[0]) {
                    h.assertTrue(order.length > 1, "Fixture has no actual remaining far work");
                    // Stop only the ordinary far read cursor at a cold last centre. Near window I/O is untouched.
                    field(p, "nextRead").setInt(p, order.length);
                    field(p, "nextFar").setInt(p, order.length - 1);
                    stalled[0] = true;
                }
                h.assertTrue(status.stage() == RuinPreparation.Stage.READY, "Near plans not ready: " + status);
                h.assertTrue(status.total() > 0 && status.ready() == status.total(), "Critical gate was empty");
                var plans = (RetainedPlans) field(p, "plans").get(p);
                var near = plans.get(centre.toLong());
                h.assertTrue(near != null && near.changedBlocks() > 0, "Actual target plan is absent or vacuous");
                h.assertTrue(field(p, "nextFar").getInt(p) < order.length, "READY waited for the full far walk");
                if (!loaded[0]) {
                    var geometry = (Detonation) field(p, "geometry").get(p);
                    ChunkPos fresh = null;
                    for (int dx = 3; dx * 16 < geometry.ruinRadius() / 2; dx++) {
                        var candidate = new ChunkPos(centre.x + dx, centre.z);
                        if (level.getChunkSource().getChunkNow(candidate.x, candidate.z) == null) { fresh = candidate; break; }
                    }
                    h.assertTrue(fresh != null, "Fixture has no cold near-player chunk");
                    level.getChunk(fresh.x, fresh.z); // Fixture setup, never preparation's world path.
                    added[0] = fresh.toLong();
                    h.assertTrue(!plans.containsKey(added[0]), "New live source already had a plan");
                    int rejected = RuinPreparation.bindCarrier(level, handle, owner, status.resolvedTarget(), .01, false, true);
                    h.assertTrue(rejected == -1, "Binding skipped newly loaded surroundings");
                    h.assertTrue(((it.unimi.dsi.fastutil.longs.LongOpenHashSet) field(p, "required").get(p)).contains(added[0]),
                            "Late binding failed to admit the new critical chunk");
                    loaded[0] = true;
                    h.fail("Waiting for newly loaded surroundings");
                }
                h.assertTrue(plans.containsKey(added[0]), "New critical plan did not complete");
                // A critical pending receipt blocks READY; an unrelated pending receipt does not.
                var queued = (it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet) field(plans, "toWrite").get(plans);
                long remote = order[order.length - 1];
                h.assertTrue(!((it.unimi.dsi.fastutil.longs.LongOpenHashSet) field(p, "required").get(p)).contains(remote),
                        "Remote receipt fixture is launch-critical");
                queued.add(remote);
                h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.READY, "Remote write recreated full-zone gate");
                queued.remove(remote);
                queued.add(centre.toLong());
                h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.STORING, "Critical pending write bypassed READY");
                h.assertTrue(RuinPreparation.bindCarrier(level, handle, owner, status.resolvedTarget(), .01, false, true) == -1,
                        "Critical pending receipt allowed binding");
                queued.remove(centre.toLong());
                field(p, "failure").set(p, RuinPreparation.Reason.IO);
                h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.FAILED, "Distant source error was hidden by near readiness");
                field(p, "failure").set(p, RuinPreparation.Reason.NONE);
                int identity = RuinPreparation.bindCarrier(level, handle, owner, status.resolvedTarget(), .01, false, true);
                h.assertTrue(identity >= 0, "Actual near-ready carrier could not bind");
                Detonation geometry = (Detonation) field(p, "geometry").get(p);
                Detonation event = new Detonation(1_903_001, geometry.burst(), geometry.groundY(), geometry.yieldKt(), geometry.surface(),
                        level.getGameTime(), 0, 0, geometry.visibility(), 0, geometry.scale(), false, geometry.wave());
                var ready = prep.handOff(level, event, level.getGameTime(), identity);
                h.assertTrue(ready != null && ready.plans() == plans && plans.get(centre.toLong()) == near,
                        "Partial handoff changed or discarded actual near output");
                h.assertTrue(!field(p, "draining").getBoolean(p), "Partial handoff discarded unfinished far ownership");
                field(p, "nextRead").setInt(p, 0);
                var far = NuclearPrep.class.getDeclaredMethod("far", ServerLevel.class, p.getClass(), WorkClock.class);
                far.setAccessible(true);
                ((WorkClock) field(prep, "clock").get(prep)).start(1_000_000_000);
                var clock = WorkClock.counting(1_000_000); clock.start(1_000_000_000);
                far.invoke(prep, level, p, clock);
                h.assertTrue(field(p, "nextRead").getInt(p) > 0 || !((it.unimi.dsi.fastutil.longs.LongOpenHashSet) field(p, "reading").get(p)).isEmpty(),
                        "Existing far pipeline did not continue after partial handoff");
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        });
    }

    @SuppressWarnings("unchecked")
    private static Object request(NuclearPrep prep, UUID handle) throws ReflectiveOperationException {
        for (Object p : (List<Object>) field(prep, "preps").get(prep)) if (handle.equals(field(p, "handle").get(p))) return p;
        throw new AssertionError("Missing real request");
    }

    private static Field field(Object owner, String name) throws ReflectiveOperationException {
        Field result = owner.getClass().getDeclaredField(name); result.setAccessible(true); return result;
    }
}
