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
        h.assertTrue(RuinPreparation.bindCarrier(level, handle, owner, target, 1, true, false) == -1, "unready carrier handle bound");
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
        field(p, "requiredInitialized").setBoolean(p, true);
        field(p, "requiredTarget").setLong(p, 123L);
        ((it.unimi.dsi.fastutil.longs.LongOpenHashSet) field(p, "required").get(p)).add(123L);
        // This controlled receipt/identity fixture excludes unrelated live chunks; real refresh is tested separately.
        var considered = (it.unimi.dsi.fastutil.longs.LongOpenHashSet) field(p, "considered").get(p);
        for (var holder : level.getChunkSource().chunkMap.getChunks()) considered.add(holder.getPos().toLong());
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
            // An older legacy preparation at the same point must not steal an explicitly identified event.
            var ctor = p.getClass().getDeclaredConstructor(int.class, long.class, java.util.function.LongConsumer.class);
            ctor.setAccessible(true);
            Object legacy = ctor.newInstance(125, Long.MAX_VALUE, (java.util.function.LongConsumer) ignored -> {});
            field(legacy, "geometry").set(legacy, fresh);
            @SuppressWarnings("unchecked")
            var all = (List<Object>) field(prep, "preps").get(prep);
            all.add(0, legacy);
            try {
                h.assertTrue(prep.planned(fresh, 124) == geometry, "coincident legacy strike stole exact-ID binding");
            } finally { all.remove(legacy); }
            field(p, "generation").setInt(p, Blast.generation - 1);
            h.assertTrue(RuinPreparation.status(level, handle).reason() == RuinPreparation.Reason.INCOMPATIBLE, "resource reload retained readiness");
            h.assertTrue(!RuinPreparation.bind(level, handle, 124), "idempotent bind accepted invalidated resources");
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
        int[] carrier = {-1};
        long[] boundAt = {-1};
        h.succeedWhen(() -> {
            RuinPreparation.keepAlive(level, handle, owner);
            var status = RuinPreparation.status(level, handle);
            h.assertTrue(status.resolvedTarget() != null, "terrain not resolved yet");
            var expected = NuclearWarhead.surfaceAt(level, target);
            h.assertTrue(status.resolvedTarget().equals(expected), "client Y became authoritative surface");
            h.assertTrue(status.stage() == RuinPreparation.Stage.READY, "tiny real preparation not yet stored: " + status);
            if (carrier[0] < 0) {
                h.assertTrue(RuinPreparation.bindCarrier(level, handle, UUID.randomUUID(), expected, .000001, true, false) == -1, "foreign carrier owner accepted");
                h.assertTrue(RuinPreparation.bindCarrier(level, handle, owner, expected.add(1, 0, 0), .000001, true, false) == -1,
                        "carrier bound incompatible accepted target");
                h.assertTrue(RuinPreparation.bindCarrier(level, handle, owner, expected, 1, true, false) == -1,
                        "carrier bound incompatible accepted yield");
                carrier[0] = RuinPreparation.bindCarrier(level, handle, owner, expected, .000001, true, false);
                h.assertTrue(carrier[0] >= 0, "ready accepted carrier did not bind");
                boundAt[0] = level.getGameTime();
                h.assertTrue(NuclearEvents.get(level).scheduled().stream().noneMatch(scheduled -> scheduled.id() == carrier[0]),
                        "carrier binding invented a timed scheduled impact");
            }
            h.assertTrue(level.getGameTime() >= boundAt[0] + 3, "awaiting carrier binding through registered world ticks");
            h.assertTrue(RuinPreparation.bindCarrier(level, handle, owner, expected, .000001, true, false) == -1,
                    "bound handle accepted a second carrier launch");
            RuinPreparation.cancelCarrier(level, carrier[0]);
            h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.ABSENT, "lost carrier retained its preparation");
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

    @GameTest(template = "range", batch = "nuke_prelaunch_no_ruins", timeoutTicks = 200, skyAccess = true)
    public static void disabledBlockDamageHasTruthfulEmptyReadinessAndCarrierBinding(GameTestHelper h) {
        var level = h.getLevel();
        boolean before = IncomingConfig.SERVER.nukeBlockDamage.get();
        UUID owner = UUID.randomUUID(), handle = UUID.randomUUID();
        StrikeGameTests.afterTest(h, () -> {
            RuinPreparation.cancel(level, handle);
            IncomingConfig.SERVER.nukeBlockDamage.set(before);
        });
        IncomingConfig.SERVER.nukeBlockDamage.set(false);
        Vec3 target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 1, 32)));
        RuinPreparation.prepare(level, handle, owner, target, 1, true, false);
        var prep = NuclearWorld.get(level).prep();
        var clock = WorkClock.counting(1_000_000); clock.start(10_000_000);
        prep.tick(level, NuclearEvents.get(level), new ScarQueue(), clock);
        var status = RuinPreparation.status(level, handle);
        h.assertTrue(status.stage() == RuinPreparation.Stage.READY && status.reason() == RuinPreparation.Reason.NONE,
                "Legal flash/radiation-only mode cannot launch");
        h.assertTrue(status.total() == 0 && status.ready() == 0 && status.reading() == 0 && status.planning() == 0 && status.writing() == 0,
                "Disabled destruction performed or claimed ruin preparation");
        int identity = RuinPreparation.bindCarrier(level, handle, owner, status.resolvedTarget(), 1, true, false);
        h.assertTrue(identity >= 0, "Empty required work rejected carrier binding");
        var geometry = NuclearWarhead.geometry(level, status.resolvedTarget(), 1, false, (float) IncomingConfig.SERVER.effectsScale());
        var event = new ua.zentix.incoming.nuclear.Detonation(777, geometry.burst(), geometry.groundY(), geometry.yieldKt(), geometry.surface(),
                level.getGameTime(), 0, 0, geometry.visibility(), 0, geometry.scale(), false, geometry.wave());
        h.assertTrue(prep.handOff(level, event, level.getGameTime(), identity) == null, "Empty readiness claimed a heavy-zone cache");
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_prelaunch_event_marker", skyAccess = true)
    public static void handedOffContextUsesActualScarIdentity(GameTestHelper h) throws ReflectiveOperationException {
        var level = h.getLevel(); var grid = RuinStorageGameTests.fixture(h, true);
        Vec3 target = new Vec3(grid.centre().getMiddleBlockX(), grid.ctx().d.groundY(), grid.centre().getMiddleBlockZ());
        UUID handle = UUID.randomUUID(), owner = UUID.randomUUID();
        RuinPreparation.prepare(level, handle, owner, target, 15, false, true);
        try {
            Object p = request(level, handle);
            var geometry = NuclearWarhead.geometry(level, target, 15, true, (float) IncomingConfig.SERVER.effectsScale());
            var ctx = new RuinContext(level, geometry);
            field(p, "geometry").set(p, geometry); field(p, "resolvedTarget").set(p, target); field(p, "ruins").set(p, ctx);
            int identity = 1_900_776;
            field(p, "strike").setInt(p, identity); field(p, "bound").setBoolean(p, true); field(p, "carrier").setBoolean(p, true);
            var event = new ua.zentix.incoming.nuclear.Detonation(1_900_778, geometry.burst(), geometry.groundY(), geometry.yieldKt(), geometry.surface(),
                    level.getGameTime(), 0, 0, geometry.visibility(), 0, geometry.scale(), false, geometry.wave());
            var ready = NuclearWorld.get(level).prep().handOff(level, event, level.getGameTime(), identity);
            h.assertTrue(ready != null && ready.ruins() == ctx, "Actual handoff did not transfer preparation context");
            var original = grid.at(0, 0).saved(); var states = original.states().clone();
            int y = (int) grid.ctx().d.groundY() + 5, section = (y - original.minY()) >> 4;
            states[section] = RuinPlan.copyStates(states[section]);
            states[section].set(7, y & 15, 7, net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState());
            var current = new DiskShots.Read(grid.centre(), original.minY(), states, original.heights(), 0, null, 0, event.id());
            h.assertTrue(ctx.restoreArchived(grid.centre().toLong(), original.encode(), current),
                    "Preparation geometry ID -1 rejected an original belonging to the actual scarred event");
        } finally { RuinPreparation.cancel(level, handle); }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_prelaunch_frozen_hint", timeoutTicks = 600, skyAccess = true)
    public static void detonationDoesNotWaitForFrozenArchiveHint(GameTestHelper h) throws Exception {
        var level = h.getLevel(); UUID handle = UUID.randomUUID(), owner = UUID.randomUUID();
        Vec3 target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 1, 32)));
        RuinPreparation.prepare(level, handle, owner, target, 1, false, false);
        Object p = request(level, handle); var prep = NuclearWorld.get(level).prep();
        var geometry = NuclearWarhead.geometry(level, target, 1, false, (float) IncomingConfig.SERVER.effectsScale());
        field(p, "geometry").set(p, geometry); field(p, "resolvedTarget").set(p, target);
        field(p, "ruins").set(p, new RuinContext(level, geometry));
        field(p, "archivePath").set(p, "frozen-hint-test");
        int identity = 1_900_775;
        field(p, "strike").setInt(p, identity); field(p, "bound").setBoolean(p, true); field(p, "carrier").setBoolean(p, true);
        var progress = RuinProgress.get(level);
        var gate = new java.util.concurrent.CountDownLatch(1);
        StrikeGameTests.afterTest(h, gate::countDown);
        StrikeGameTests.afterTest(h, () -> RuinPreparation.cancelCarrier(level, identity));
        net.neoforged.neoforge.common.IOUtilities.withIOWorker(() -> {
            try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        var dir = java.nio.file.Files.createTempDirectory(level.getDataStorage().dataFolder.toPath(), "frozen-prep-");
        progress.setDirty(); progress.save(dir.resolve("progress.dat").toFile(), level.registryAccess());
        h.assertTrue(!progress.writable(), "Save did not freeze actual ledger");
        int eventId = 1_900_777;
        var event = new ua.zentix.incoming.nuclear.Detonation(eventId, geometry.burst(), geometry.groundY(), geometry.yieldKt(), geometry.surface(),
                level.getGameTime(), 0, 0, geometry.visibility(), 0, geometry.scale(), false, geometry.wave());
        h.assertTrue(prep.handOff(level, event, level.getGameTime(), identity) != null, "Frozen optional hint postponed physical detonation");
        h.assertTrue(progress.archive(eventId).isEmpty(), "Frozen ledger mutated during handoff");
        gate.countDown();
        h.succeedWhen(() -> {
            h.assertTrue(progress.writable(), "Actual ledger save still running");
            var clock = WorkClock.counting(1_000_000); clock.start(10_000_000);
            prep.tick(level, new NuclearEvents(), new ScarQueue(), clock);
            h.assertTrue(progress.archive(eventId).equals("frozen-hint-test"), "Writable continuation lost deferred archive hint");
            var restored = RuinProgress.load(progress.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess()), level.registryAccess());
            h.assertTrue(restored.archive(eventId).equals("frozen-hint-test"), "Deferred hint did not survive ledger roundtrip");
        });
    }

    @GameTest(template = "range", batch = "nuke_prelaunch_ceiling", skyAccess = true)
    public static void ceilingTargetsMatchOrdinaryGeometryWithoutDiskReads(GameTestHelper h) throws ReflectiveOperationException {
        var level = h.getLevel().getServer().getLevel(Level.NETHER);
        h.assertTrue(level != null && level.dimensionType().hasCeiling(), "Missing ceiling dimension fixture");
        // Fixture setup alone may load a chunk; the second target remains cold.
        level.getChunk(0, 0);
        for (Vec3 target : List.of(new Vec3(8.5, 64.25, 8.5), new Vec3(12_000_008.5, 64.25, 12_000_008.5))) {
            UUID handle = UUID.randomUUID();
            RuinPreparation.prepare(level, handle, UUID.randomUUID(), target, 1, true, false);
            try {
                Object p = request(level, handle);
                var prep = NuclearWorld.get(level).prep();
                var work = NuclearPrep.class.getDeclaredMethod("work", net.minecraft.server.level.ServerLevel.class,
                        NuclearEvents.ScheduledStrike.class, p.getClass(), WorkClock.class);
                work.setAccessible(true);
                // Avoid an unrelated asynchronous policy calculation; geometry is the acceptance here.
                var policy = field(prep, "policy"); Object previousPolicy = policy.get(prep);
                var generation = field(prep, "policyGeneration"); int previousGeneration = generation.getInt(prep);
                try {
                    policy.set(prep, java.util.concurrent.CompletableFuture.completedFuture("0".repeat(64)));
                    generation.setInt(prep, Blast.generation);
                    var clock = WorkClock.counting(1_000_000); clock.start(10_000_000);
                    work.invoke(prep, level, null, p, clock);
                } finally { policy.set(prep, previousPolicy); generation.setInt(prep, previousGeneration); }
                var ordinaryTarget = NuclearWarhead.surfaceAt(level, target);
                var ordinary = NuclearWarhead.geometry(level, ordinaryTarget, 1, false, (float) IncomingConfig.SERVER.effectsScale());
                var geometry = (ua.zentix.incoming.nuclear.Detonation) field(p, "geometry").get(p);
                h.assertTrue(field(p, "resolvedTarget").get(p).equals(ordinaryTarget), "Ceiling roof replaced requested target");
                h.assertTrue(geometry.burst().equals(ordinary.burst()) && geometry.groundY() == ordinary.groundY(), "Ceiling preparation differs from ordinary geometry");
                h.assertFalse(field(p, "resolvingRead").getBoolean(p), "Ceiling fallback issued unnecessary disk read");
            } finally { RuinPreparation.cancel(level, handle); }
        }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_prep_far_source", skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void farArchivedCompletionValidatesEditedAndScarredSources(GameTestHelper h) throws ReflectiveOperationException {
        var level = h.getLevel(); var grid = RuinStorageGameTests.fixture(h, true);
        var original = grid.at(0, 0).saved(); long key = grid.centre().toLong();
        var states = original.states().clone(); int y = (int) grid.ctx().d.groundY() + 5;
        int section = (y - original.minY()) >> 4;
        states[section] = RuinPlan.copyStates(states[section]);
        states[section].set(7, y & 15, 7, net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState());
        var record = Class.forName(NuclearPrep.class.getName() + "$ArchivedShot");
        var constructor = record.getDeclaredConstructor(long.class, Optional.class, DiskShots.Read.class); constructor.setAccessible(true);
        for (int scar : new int[] {0, grid.ctx().d.id()}) {
            UUID handle = UUID.randomUUID();
            RuinPreparation.prepare(level, handle, UUID.randomUUID(), grid.ctx().d.burst(), 15, false, false);
            try {
                Object p = request(level, handle); var ctx = new RuinContext(level, grid.ctx().d);
                field(p, "geometry").set(p, grid.ctx().d); field(p, "ruins").set(p, ctx);
                var read = new DiskShots.Read(grid.centre(), original.minY(), states, original.heights(), 0, null, 0, scar);
                var inbox = (RuinInbox<Object>) field(p, "archived").get(p);
                inbox.expect().accept(constructor.newInstance(key, Optional.of(original.encode()), read));
                var far = NuclearPrep.class.getDeclaredMethod("far", net.minecraft.server.level.ServerLevel.class, p.getClass(), WorkClock.class);
                far.setAccessible(true); var clock = WorkClock.counting(1_000_000); clock.start(10_000_000);
                ((WorkClock) field(NuclearWorld.get(level).prep(), "clock").get(NuclearWorld.get(level).prep())).start(1_000_000_000);
                far.invoke(NuclearWorld.get(level).prep(), level, p, clock);
                var expected = scar == 0 ? ChunkShot.fromDisk(read, null, null, false) : grid.at(0, 0);
                var actual = ((it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<ChunkShot>) field(ctx, "shots").get(ctx)).get(key);
                h.assertTrue(java.util.Arrays.equals(actual.normalPrints(Blast.SERVER), expected.normalPrints(Blast.SERVER)),
                        scar == 0 ? "Far cached source overwrote later edit" : "Far cached source lost scarred original");
                var shots = grid.shots().clone(); shots[(RuinContext.Grid.R) * RuinContext.Grid.SIDE + RuinContext.Grid.R] = expected;
                var expectedGrid = new RuinContext.Grid(ctx, grid.centre(), grid.minY(), grid.maxY(), shots, grid.view(), grid.blockDamage(), grid.treeFall(), grid.fires());
                shots = shots.clone(); shots[RuinContext.Grid.R * RuinContext.Grid.SIDE + RuinContext.Grid.R] = actual;
                var actualGrid = new RuinContext.Grid(ctx, grid.centre(), grid.minY(), grid.maxY(), shots, grid.view(), grid.blockDamage(), grid.treeFall(), grid.fires());
                h.assertTrue(RuinStorageGameTests.plan(expectedGrid).store().equals(RuinStorageGameTests.plan(actualGrid).store()), "Far source completion changed plan");
                h.assertTrue(((java.util.concurrent.atomic.AtomicLong) field(p, "asyncBytes").get(p)).get() == 0, "Far completion kept read reservation");
            } finally { RuinPreparation.cancel(level, handle); }
        }
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
