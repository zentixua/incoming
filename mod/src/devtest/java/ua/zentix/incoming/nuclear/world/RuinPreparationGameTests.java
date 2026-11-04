package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearWarhead;

/** Lifecycle admission and receipts, independently of whole-zone performance acceptance. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinPreparationGameTests {
    private RuinPreparationGameTests() {}

    @GameTest(template = "range", batch = "nuke_prelaunch_identity", skyAccess = true)
    public static void ownerLeaseCancelAndDimensions(GameTestHelper h) {
        var level = h.getLevel();
        var other = level.getServer().getLevel(Level.NETHER);
        UUID owner = UUID.randomUUID(), handle = UUID.randomUUID(), next = UUID.randomUUID();
        var target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 1, 32)));
        RuinPreparation.prepare(level, handle, owner, target, 1, true, false);
        h.assertTrue(!RuinPreparation.keepAlive(level, handle, UUID.randomUUID()), "foreign owner renewed handle");
        h.assertTrue(RuinPreparation.keepAlive(level, handle, owner), "owner could not renew");
        h.assertTrue(!RuinPreparation.bind(level, handle, 123), "unready handle bound");
        RuinPreparation.prepare(other, next, owner, target, 1, true, false);
        h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.ABSENT, "owner kept two unlaunched handles");
        h.assertTrue(!RuinPreparation.keepAlive(level, next, owner), "dimension mismatch renewed handle");
        RuinPreparation.cancelOwner(level.getServer(), owner);
        h.assertTrue(RuinPreparation.status(other, next).stage() == RuinPreparation.Stage.ABSENT, "cancelOwner missed dimension");
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_prelaunch_lease", skyAccess = true)
    public static void watchedRequestOutlivesReviewButExpiredCannotRevive(GameTestHelper h) throws ReflectiveOperationException {
        var level = h.getLevel();
        UUID owner = UUID.randomUUID(), handle = UUID.randomUUID();
        var target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 1, 32)));
        RuinPreparation.prepare(level, handle, owner, target, 1, true, false);
        Object p = request(level, handle);
        Object oldCache = field(p, "cacheId").get(p);
        RuinPreparation.cancel(level, handle);
        RuinPreparation.prepare(level, handle, owner, target, 1, true, false);
        p = request(level, handle);
        h.assertTrue(!oldCache.equals(field(p, "cacheId").get(p)), "replacement lifetime reopens a still-draining archive writer");
        // Simulate elapsed lease using the request's tick deadline; never mutate the world's clock.
        field(p, "expires").setLong(p, level.getGameTime() + 1);
        h.assertTrue(RuinPreparation.keepAlive(level, handle, owner), "valid long preparation did not renew");
        h.assertTrue(field(p, "expires").getLong(p) == level.getGameTime() + NuclearPrep.LEASE_TICKS, "renew did not extend lease");
        field(p, "expires").setLong(p, level.getGameTime());
        h.assertTrue(!RuinPreparation.keepAlive(level, handle, owner), "expired handle revived");
        h.assertTrue(RuinPreparation.status(level, handle).reason() == RuinPreparation.Reason.EXPIRED, "expiry hidden");
        var prep = NuclearWorld.get(level).prep();
        var clock = WorkClock.counting(1_000_000);
        clock.start(10_000_000);
        prep.tick(level, new NuclearEvents(), new ScarQueue(), clock);
        h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.ABSENT, "expired handle kept live ownership");
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_prelaunch_receipts", skyAccess = true)
    public static void readyRequiresReceiptsAndBindUsesExactPaidOrder(GameTestHelper h) throws ReflectiveOperationException {
        var level = h.getLevel();
        UUID owner = UUID.randomUUID(), handle = UUID.randomUUID();
        var target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 1, 32)));
        RuinPreparation.prepare(level, handle, owner, target, 1, false, false);
        Object p = request(level, handle);
        field(p, "geometry").set(p, NuclearWarhead.geometry(level, target, 1, false, (float) IncomingConfig.SERVER.effectsScale()));
        field(p, "resolvedTarget").set(p, target);
        field(p, "order").set(p, new long[] {123L});
        field(p, "nextFar").setInt(p, 1);
        var plans = (RetainedPlans) field(p, "plans").get(p);
        plans.put(123L, RuinPlan.EMPTY);
        // A plan waiting for submission is required work too, even before an I/O future exists.
        var pending = (it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet) field(plans, "toWrite").get(plans);
        pending.add(123L);
        h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.STORING, "announced/finished solver bypassed pending write");
        h.assertTrue(!RuinPreparation.bind(level, handle, 123), "pending write allowed launch");
        pending.clear();
        h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.READY, "complete receipt fixture not ready");
        var events = NuclearEvents.get(level);
        var wrong = new NuclearEvents.ScheduledStrike(123, target.add(1, 0, 0), 1, false, 0, Long.MAX_VALUE, target, Optional.of(owner), false);
        events.schedule(wrong);
        h.assertTrue(!RuinPreparation.bind(level, handle, 123), "incompatible target accepted");
        events.unschedule(wrong);
        var right = new NuclearEvents.ScheduledStrike(124, target, 1, false, 0, Long.MAX_VALUE, target, Optional.of(owner), false);
        events.schedule(right);
        try {
            h.assertTrue(RuinPreparation.bind(level, handle, 124), "exact accepted order rejected");
            field(p, "expires").setLong(p, 0);
            h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.READY, "scheduled binding expired");
            var geometry = (ua.zentix.incoming.nuclear.Detonation) field(p, "geometry").get(p);
            var prep = NuclearWorld.get(level).prep();
            var fresh = new ua.zentix.incoming.nuclear.Detonation(999, geometry.burst(), geometry.groundY(), geometry.yieldKt(),
                    geometry.surface(), 200, 0, 0, geometry.visibility(), 0, geometry.scale(), false, geometry.wave());
            h.assertTrue(prep.planned(fresh) == fresh, "diagnostic stole bound preparation");
            h.assertTrue(prep.planned(fresh, 123) == fresh, "other strike stole bound preparation");
            h.assertTrue(prep.planned(fresh, 124) == geometry, "bound geometry unavailable by ID");
            field(p, "generation").setInt(p, Blast.generation - 1);
            h.assertTrue(RuinPreparation.status(level, handle).reason() == RuinPreparation.Reason.INCOMPATIBLE, "resource reload retained readiness");
        } finally {
            events.unschedule(right);
            RuinPreparation.cancel(level, handle);
        }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_prelaunch_surface", timeoutTicks = 600, skyAccess = true)
    public static void surfaceUsesAuthoritativeTerrainAndPolicyInvalidates(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        UUID owner = UUID.randomUUID(), handle = UUID.randomUUID();
        var target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 1, 32))).add(0, 500, 0);
        RuinPreparation.prepare(level, handle, owner, target, .000001, true, false);
        StrikeGameTests.afterTest(h, () -> RuinPreparation.cancel(level, handle));
        h.succeedWhen(() -> {
            RuinPreparation.keepAlive(level, handle, owner);
            var status = RuinPreparation.status(level, handle);
            h.assertTrue(status.resolvedTarget() != null, "terrain not resolved yet");
            var expected = NuclearWarhead.surfaceAt(level, target);
            h.assertTrue(status.resolvedTarget().equals(expected), "client Y became authoritative surface");
            h.assertTrue(status.stage() == RuinPreparation.Stage.READY, "tiny real preparation not yet stored: " + status);
        });
    }

    @GameTest(template = "range", batch = "nuke_prelaunch_cap", skyAccess = true)
    public static void requestsShareMetadataAndResidentCap(GameTestHelper h) throws ReflectiveOperationException {
        var level = h.getLevel();
        var other = level.getServer().getLevel(Level.NETHER);
        UUID owner = UUID.randomUUID(), handle = UUID.randomUUID();
        var target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 1, 32)));
        RuinPreparation.prepare(level, handle, owner, target, 1, true, false);
        Object p = request(level, handle);
        var plans = (RetainedPlans) field(p, "plans").get(p);
        long before = field(plans, "bytes").getLong(plans);
        field(plans, "bytes").setLong(plans, NuclearPrep.PLANS_CAP);
        try {
            UUID otherOwner = UUID.randomUUID(), otherHandle = UUID.randomUUID();
            RuinPreparation.prepare(other, otherHandle, otherOwner, target, 1, true, false);
            h.assertTrue(RuinPreparation.status(other, otherHandle).reason() == RuinPreparation.Reason.MEMORY,
                    "other dimension bypassed shared resident cap");
            RuinPreparation.cancelOwner(level.getServer(), otherOwner);
        } finally {
            field(plans, "bytes").setLong(plans, before);
            RuinPreparation.cancel(level, handle);
        }
        // Count draining owners as well: replacing requests cannot grow unbounded metadata before the next tick.
        var owners = new java.util.ArrayList<UUID>();
        try {
            for (int i = 0; i < NuclearPrep.REQUEST_LIMIT + 2; i++) {
                UUID nextOwner = UUID.randomUUID(), next = UUID.randomUUID();
                owners.add(nextOwner);
                RuinPreparation.prepare(i % 2 == 0 ? level : other, next, nextOwner, target, 1, true, false);
            }
            int count = 0;
            for (var dimension : level.getServer().getAllLevels()) {
                if (!dimension.hasData(ua.zentix.incoming.registry.ModAttachments.NUCLEAR_WORLD)) continue;
                for (Object entry : (List<?>) field(NuclearWorld.get(dimension).prep(), "preps").get(NuclearWorld.get(dimension).prep()))
                    if (field(entry, "handle").get(entry) != null) count++;
            }
            h.assertTrue(count <= NuclearPrep.REQUEST_LIMIT, "request limit grew across dimensions");
        } finally {
            for (UUID id : owners) RuinPreparation.cancelOwner(level.getServer(), id);
        }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_prelaunch_disk_surface", skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void diskSurfacePrimesMissingEditorHeightmaps(GameTestHelper h) throws ReflectiveOperationException {
        var level = h.getLevel();
        var local = h.absolutePos(new BlockPos(32, 1, 32));
        var chunk = level.getChunkAt(local);
        var tag = net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk);
        tag.remove("Heightmaps");
        var target = new Vec3(12_000_000 + (local.getX() & 15), 500, 12_000_000 + (local.getZ() & 15));
        UUID owner = UUID.randomUUID(), handle = UUID.randomUUID();
        RuinPreparation.prepare(level, handle, owner, target, 1, true, false);
        Object p = request(level, handle);
        var pos = new net.minecraft.world.level.ChunkPos(BlockPos.containing(target));
        var read = DiskShots.parse(level, pos, tag);
        h.assertTrue(read.skip() == null && read.unprimed() != 0, "fixture must contain final blocks without heightmaps");
        var inbox = (RuinInbox<DiskShots.Read>) field(p, "terrainReads").get(p);
        field(p, "resolvingRead").setBoolean(p, true);
        inbox.expect().accept(read);
        var prep = NuclearWorld.get(level).prep();
        var method = NuclearPrep.class.getDeclaredMethod("resolveSurface", net.minecraft.server.level.ServerLevel.class, p.getClass(), WorkClock.class);
        method.setAccessible(true);
        var clock = WorkClock.counting(1_000_000);
        clock.start(10_000_000);
        var result = (ua.zentix.incoming.util.Terrain.Surface) method.invoke(prep, level, p, clock);
        h.assertTrue(result != null && result.source() == ua.zentix.incoming.util.Terrain.Source.DISK, "editor source replaced with generator/client Y");
        int expected = ua.zentix.incoming.util.Terrain.height(level, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                local.getX(), local.getZ());
        h.assertTrue(result.y() == expected, "primed disk ground differs from actual block column");
        h.assertTrue(!ua.zentix.incoming.util.Terrain.ready(level, BlockPos.containing(target)), "surface resolution loaded distant world chunk");
        RuinPreparation.cancel(level, handle);
        h.succeed();
    }

    @SuppressWarnings("unchecked")
    private static Object request(net.minecraft.server.level.ServerLevel level, UUID handle) throws ReflectiveOperationException {
        var prep = NuclearWorld.get(level).prep();
        for (Object p : (List<Object>) field(prep, "preps").get(prep)) if (handle.equals(field(p, "handle").get(p)) && !field(p, "draining").getBoolean(p)) return p;
        throw new AssertionError("request absent");
    }

    private static Field field(Object value, String name) throws ReflectiveOperationException {
        Field f = value.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
