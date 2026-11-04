package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;

/** Producer boundaries under cross-dimension pressure, using real snapshots and existing ownership counters. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinSnapshotAdmissionGameTests {
    private RuinSnapshotAdmissionGameTests() {}

    @GameTest(template = "range", batch = "nuke_snapshot_window", timeoutTicks = 400, skyAccess = true)
    public static void wholeLiveWindowRefusesBeforeItsFirstSnapshotAndRetriesExactly(GameTestHelper h) throws Exception {
        var reference = RuinStorageGameTests.fixture(h, true);
        ServerLevel level = h.getLevel();
        RuinContext ctx = registered(h, reference.ctx());
        var owner = NuclearWorld.get(level);
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER));
        long previous = other.materialReservation;
        long own = owner.materialReservation;
        try {
            // Enough for the centre alone, deliberately insufficient for the complete new 5x5 window.
            long centreBytes = reference.at(0, 0).bytes;
            pressure(level, other, centreBytes + 4096);
            long serial = serials();
            h.assertTrue(ctx.grid(level, reference.centre(), false) == null, "Partial window was admitted");
            h.assertTrue(serials() == serial, "Refusal allocated a snapshot before checking the whole window");
            h.assertTrue(ctx.shotCount() == 0 && ctx.shotBytes() == 0, "Refusal published a partial cache/height band");
            h.assertTrue(owner.materialReservation == own, "Refused window orphaned its reservation");
            other.materialReservation = previous;
            var actual = ctx.grid(level, reference.centre(), false);
            h.assertTrue(actual != null, "Released headroom did not reopen identical live demand");
            for (int i = 0; i < actual.shots().length; i++)
                h.assertTrue(actual.shots()[i].saved().encode().equals(reference.shots()[i].saved().encode()), "Retry changed source snapshot " + i);
            h.assertTrue(RuinStorageGameTests.plan(actual).store().equals(RuinStorageGameTests.plan(reference).store()), "Admission changed complete physical/material output");
            h.assertTrue(owner.materialReservation == own, "Successful window orphaned its reservation");
            pressure(level, other, 0);
            var reused = ctx.grid(level, reference.centre(), false);
            h.assertTrue(reused != null && reused.at(0, 0) == actual.at(0, 0), "Current window could not reuse its admitted snapshots at capacity");
        } finally { other.materialReservation = previous; }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_snapshot_disk", timeoutTicks = 400, skyAccess = true)
    public static void largerThanReadSlotWaitsWithoutPublishingOrLoadingSource(GameTestHelper h) throws Exception {
        var reference = RuinStorageGameTests.fixture(h, false);
        ServerLevel level = h.getLevel(); RuinContext ctx = registered(h, reference.ctx());
        ChunkPos cold = new ChunkPos(reference.centre().x + 1024, reference.centre().z + 1024);
        var read = largeRead(level, cold);
        ChunkShot expected = ChunkShot.fromDisk(read, null, null, false);
        h.assertTrue(expected.bytes > 96L << 10, "Fixture must exceed the preparation read slot");
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER));
        long previous = other.materialReservation, own = NuclearWorld.get(level).materialReservation;
        try {
            pressure(level, other, 96L << 10);
            long serial = serials();
            h.assertFalse(ctx.putDisk(level, read), "Known publication larger than available room was admitted");
            h.assertTrue(serials() == serial, "Refusal constructed a disk snapshot before checking growth");
            h.assertTrue(ctx.shotCount() == 0 && ctx.shotBytes() == 0, "Refused disk source published data");
            h.assertTrue(NuclearWorld.get(level).materialReservation == own, "Disk refusal leaked a reservation");
            other.materialReservation = previous;
            h.assertTrue(ctx.putDisk(level, read), "Same owned source did not publish after capacity returned");
            h.assertTrue(ctx.shot(level, cold.x, cold.z).saved().encode().equals(expected.saved().encode()), "Retry changed actual disk palette/height data");
            h.assertTrue(level.getChunkSource().getChunkNow(cold.x, cold.z) == null, "Snapshot producer loaded cold terrain");
            h.assertTrue(NuclearWorld.get(level).materialReservation == own, "Successful publication leaked its reservation");
        } finally { other.materialReservation = previous; }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_snapshot_inbox", timeoutTicks = 400, skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void readyInboxSlotFundsActualPublicationAtCapacity(GameTestHelper h) throws Exception {
        var reference = RuinStorageGameTests.fixture(h, false);
        ServerLevel level = h.getLevel(); RuinContext ctx = registered(h, reference.ctx());
        ChunkPos cold = new ChunkPos(reference.centre().x + 1024, reference.centre().z + 1024);
        var read = largeRead(level, cold);
        var inbox = (RuinInbox<Object>) field(ctx, "reads").get(ctx);
        Class<?> record = Class.forName(RuinContext.class.getName() + "$WindowRead");
        var ctor = record.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        Consumer<Object> accept = inbox.expect(); accept.accept(ctor.newInstance(cold.toLong(), read, null));
        h.assertTrue(ctx.pendingWindowBytes() > 0, "Ready source has no input owner");
        ChunkShot expected = ChunkShot.fromDisk(read, null, null, false);
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER));
        long previous = other.materialReservation;
        try {
            pressure(level, other, 0);
            long held = other.materialReservation;
            ctx.drainWindowReads(level);
            h.assertTrue(inbox.pending() == 0 && ctx.pendingWindowBytes() == 0 && ctx.hasShot(cold.toLong()),
                    "Already-funded source could not publish without an external refund");
            h.assertTrue(other.materialReservation == held, "Publication borrowed the fixed external owner");
            h.assertTrue(NuclearPrep.residentMemory(level) <= NuclearPrep.PLANS_CAP, "Ready-source transfer exceeded the shared cap");
            h.assertTrue(ctx.windowReads == 1 && ctx.windowAbsent == 0 && !ctx.windowFailed(), "Publication changed source classification");
            h.assertTrue(ctx.shot(level, cold.x, cold.z).saved().encode().equals(expected.saved().encode()),
                    "Funded publication changed the actual source snapshot");
            inbox.invalidate();
            h.assertTrue(ctx.pendingWindowBytes() == 0, "Finished inbox still owns reservation");
        } finally { other.materialReservation = previous; inbox.invalidate(); }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_snapshot_retired", timeoutTicks = 400, skyAccess = true)
    public static void replacementRetainsOldWorkerInputAndReusesCurrentAtCapacity(GameTestHelper h) throws Exception {
        var reference = RuinStorageGameTests.fixture(h, false);
        ServerLevel level = h.getLevel(); RuinContext ctx = registered(h, reference.ctx());
        var centre = reference.centre(); ChunkShot first = ctx.shot(level, centre.x, centre.z);
        RuinInputs inputs = (RuinInputs) field(ctx, "inputs").get(ctx); inputs.acquire();
        long retained = ctx.retainedBytes();
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER)); long previous = other.materialReservation;
        var chunk = level.getChunkSource().getChunkNow(centre.x, centre.z);
        var pos = new net.minecraft.core.BlockPos(centre.getMiddleBlockX(), level.getMaxBuildHeight() - 2, centre.getMiddleBlockZ());
        var was = chunk.getBlockState(pos); level.setBlock(pos, Blocks.GLASS.defaultBlockState(), 18);
        try {
            pressure(level, other, 0);
            h.assertTrue(ctx.shot(level, centre.x, centre.z) == null, "Invalid live source was replaced without headroom");
            h.assertTrue(ctx.retainedBytes() == retained, "Refusal retired or replaced valid worker input");
            other.materialReservation = previous;
            ChunkShot second = ctx.shot(level, centre.x, centre.z);
            h.assertTrue(second != null && second != first, "Retry failed to refresh the actual changed source");
            h.assertTrue(ctx.retainedBytes() == ctx.shotBytes() + first.bytes, "Old worker snapshot lost its numeric owner");
            pressure(level, other, 0);
            h.assertTrue(ctx.shot(level, centre.x, centre.z) == second, "Current source needed a second reservation");
        } finally { other.materialReservation = previous; inputs.release(); level.setBlock(pos, was, 18); }
        h.assertTrue(ctx.retainedBytes() == ctx.shotBytes(), "Actual consumer exit did not refund replaced snapshot");
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_snapshot_lazy", timeoutTicks = 400, skyAccess = true)
    public static void publishedChargePrepaysLazyGlobalAndRuinedProperties(GameTestHelper h) throws Exception {
        var reference = RuinStorageGameTests.fixture(h, true);
        ServerLevel level = h.getLevel();
        var read = largeRead(level, new ChunkPos(reference.centre().x + 1024, reference.centre().z + 1024));
        ChunkShot global = ChunkShot.fromDisk(read, null, null, false);
        long serialized = 0;
        for (var section : read.states()) serialized += section.getSerializedSize();
        h.assertTrue(global.bytes >= serialized + read.states().length * 4096L * 8, "Global lazy position arrays were not prepaid");
        long charged = global.bytes;
        for (int i = 0; i < global.sections; i++) global.props(i, Blast.SERVER);
        h.assertTrue(global.bytes == charged, "Lazy property publication changed an already-admitted charge");
        var plan = RuinStorageGameTests.plan(reference);
        var ordinary = DiskShots.parse(level, reference.centre(), net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level,
                level.getChunkSource().getChunkNow(reference.centre().x, reference.centre().z)));
        ChunkShot ruined = ChunkShot.fromDisk(ordinary, new RuinContext.Applied(plan, new short[256]), null, false);
        h.assertTrue(ruined.bytes >= plan.bytes() + ruined.sections * 4096L * 8, "Ruined lazy/embedded-plan arrays were not prepaid");
        charged = ruined.bytes;
        for (int i = 0; i < ruined.sections; i++) ruined.props(i, Blast.SERVER);
        h.assertTrue(ruined.bytes == charged, "Lazy ruined properties escaped their prepaid charge");
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_snapshot_archive", timeoutTicks = 400, skyAccess = true)
    public static void archivedOldPlanRemainsChargedAndMemoryRefusalRetries(GameTestHelper h) throws Exception {
        var reference = RuinStorageGameTests.fixture(h, true);
        ServerLevel level = h.getLevel(); RuinContext ctx = registered(h, reference.ctx());
        var plan = RuinStorageGameTests.plan(reference);
        h.assertTrue(plan.changedBlocks() > 0, "Archived embedded-plan fixture is empty");
        ChunkPos cold = new ChunkPos(reference.centre().x + 1024, reference.centre().z + 1024);
        var data = net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level,
                level.getChunkSource().getChunkNow(reference.centre().x, reference.centre().z));
        data.putInt("xPos", cold.x); data.putInt("zPos", cold.z);
        var parsed = DiskShots.parse(level, cold, data);
        var current = new DiskShots.Read(cold, parsed.minY(), parsed.states(), parsed.heights(), parsed.unprimed(), null, 0, ctx.d.id());
        var old = ChunkShot.fromDisk(current.primed(Blast.SERVER), new RuinContext.Applied(plan, new short[256]), null, false);
        var stored = old.saved().encode(); ChunkShot expected = ChunkShot.restore(stored, ctx);
        h.assertTrue(expected.bytes >= plan.detached().bytes() + expected.sections * 4096L * 8, "Restored old plan is unowned");
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER)); long previous = other.materialReservation;
        try {
            pressure(level, other, 0);
            h.assertTrue(ctx.restoreArchived(level, cold.toLong(), stored, current) == null, "Archive MEMORY was not retryable");
            h.assertTrue(ctx.shotCount() == 0 && ctx.shotBytes() == 0, "Refused archive published before admission");
            other.materialReservation = previous;
            h.assertTrue(Boolean.TRUE.equals(ctx.restoreArchived(level, cold.toLong(), stored, current)), "Same archived source did not retry");
            ChunkShot actual = ctx.shot(level, cold.x, cold.z);
            h.assertTrue(actual.bytes == expected.bytes && ctx.shotBytes() >= actual.bytes, "Published archived old plan lost known ownership");
            h.assertTrue(actual.saved().encode().equals(expected.saved().encode()), "Archive retry changed source data");
            h.assertTrue(level.getChunkSource().getChunkNow(cold.x, cold.z) == null, "Archive producer loaded cold terrain");
        } finally { other.materialReservation = previous; }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_snapshot_flash_heights", timeoutTicks = 400, skyAccess = true)
    public static void storedFlashHeightPreflightRefusesBeforeGrowthAndRetries(GameTestHelper h) throws Exception {
        var reference = RuinStorageGameTests.fixture(h, false);
        ServerLevel level = h.getLevel(); RuinContext ctx = registered(h, reference.ctx());
        var chunk = level.getChunkSource().getChunkNow(reference.centre().x, reference.centre().z);
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER)); long previous = other.materialReservation;
        try {
            pressure(level, other, 0);
            h.assertFalse(ctx.flashHeightsReady(level, chunk), "Standalone flash height growth ignored capacity");
            h.assertTrue(ctx.shotBytes() == 0, "Standalone height refusal published a shadow tile");
            other.materialReservation = previous;
            h.assertTrue(ctx.flashHeightsReady(level, chunk), "Standalone height demand failed after capacity returned");
            h.assertTrue(ctx.shotBytes() > 0, "Admitted standalone height tile was not charged");
            pressure(level, other, 0);
            h.assertTrue(ctx.flashHeightsReady(level, chunk), "Stored flash could not reuse its existing shadow tile");
        } finally { other.materialReservation = previous; }
        h.succeed();
    }

    @SuppressWarnings("unchecked")
    private static RuinContext registered(GameTestHelper h, RuinContext reference) throws Exception {
        var world = NuclearWorld.get(h.getLevel());
        var contexts = (Int2ObjectOpenHashMap<RuinContext>) field(world, "ruins").get(world);
        int key = 17000 + contexts.size(); var ctx = new RuinContext(h.getLevel(), reference.d);
        var was = contexts.put(key, ctx);
        ua.zentix.incoming.gametest.StrikeGameTests.afterTest(h, () -> { if (was == null) contexts.remove(key); else contexts.put(key, was); });
        return ctx;
    }

    private static void pressure(ServerLevel level, NuclearWorld other, long room) {
        long extra = NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level) - room;
        if (extra < 0) throw new AssertionError("Fixture exceeds the requested headroom");
        other.materialReservation += extra;
    }

    @SuppressWarnings("unchecked")
    private static DiskShots.Read largeRead(ServerLevel level, ChunkPos pos) {
        var states = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[level.getSectionsCount()];
        for (int section = 0; section < states.length; section++) {
            var palette = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
            for (int i = 0; i < 4096; i++) palette.getAndSetUnchecked(i & 15, i >> 8, i >> 4 & 15, Block.BLOCK_STATE_REGISTRY.byId(i % 300));
            states[section] = palette;
        }
        int[] heights = new int[RuinPlan.HEIGHTMAP_TYPES.length * 256]; java.util.Arrays.fill(heights, level.getMinBuildHeight());
        return new DiskShots.Read(pos, level.getMinBuildHeight(), states, heights, 0, null, 0);
    }

    private static long serials() throws Exception {
        Field f = ChunkShot.class.getDeclaredField("SERIALS"); f.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicLong) f.get(null)).get();
    }

    private static Field field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f;
    }
}
