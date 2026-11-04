package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;

/** A watched consumer must be able to consume a cold plan without funding future solvers. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinDemandProgressGameTests {
    private RuinDemandProgressGameTests() {}

    @GameTest(template = "range", batch = "nuke_demand_progress", timeoutTicks = 240, skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void watchedStoredPlanCompletesWithFixedResidentPressure(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        RuinMatterGameTests.clearFixture(level);
        var grid = RuinStorageGameTests.fixture(h, true);
        var old = grid.ctx().d;
        var d = new Detonation(190002, old.burst(), old.groundY(), old.yieldKt(), old.surface(),
                old.gameTime() - 20000, 0, 0, old.visibility(), 42, old.scale(), false, old.wave());
        var world = NuclearWorld.get(level);
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER));
        long previousReservation = other.materialReservation;
        var content = NuclearPrep.class.getDeclaredMethod("content", net.minecraft.server.level.ServerLevel.class);
        content.setAccessible(true);
        var pass = RuinProgress.get(level).pass(d.id());
        String previousArchive = pass.archive;
        var queue = world.scars();
        long key = grid.centre().toLong();
        BlockPos glass = new BlockPos(grid.centre().getMinBlockX() + 2, (int) d.groundY() + 2,
                grid.centre().getMinBlockZ() + 2);
        h.assertTrue(level.getBlockState(glass).is(Blocks.GLASS), "Fixture glass missing");
        int[] stage = {0};
        long[] fixed = {0}, offered = {0};
        RuinContext[] owner = {null};
        RuinMaintenanceGameTests.Completion[] completed = {null};
        StrikeGameTests.afterTest(h, () -> {
            other.materialReservation = previousReservation;
            ScarQueue.seenForTest(null);
            ua.zentix.incoming.nuclear.NuclearStrikes.clear(level);
            queue.clear(level);
            pass.archive = previousArchive;
            world.forgetRuinsForTest(d.id());
            RuinMatterGameTests.clearFixture(level);
        });
        h.succeedWhen(() -> {
            try {
                if (stage[0] == 0) {
                    String policy = (String) content.invoke(world.prep(), level);
                    h.assertTrue(policy != null && RuinProgress.get(level).writable(), "Storage identity pending");
                    pass.archive = UUID.randomUUID() + "/" + UUID.randomUUID() + "/" + policy;
                    owner[0] = world.ruins(level, d, level.getGameTime());
                    var plans = owner[0].restoredPlans;
                    field(plans, "residentTarget").setLong(plans, 0);
                    var plan = RuinStorageGameTests.plan(owner[0].grid(level, grid.centre(), false));
                    h.assertTrue(plan.changedBlocks() > 0, "Fixture needs a nonempty destructive plan");
                    Incoming.LOG.info("DEMAND_FIXTURE plan bytes {}, removal count {}, known decode {}", plan.bytes(),
                            plan.store().getLongArray("material").length, RuinPlan.restorePublicationBytes(plan.store()));
                    plans.put(key, plan);
                    stage[0] = 1;
                }
                var ctx = owner[0];
                var plans = ctx.restoredPlans;
                if (stage[0] == 1) {
                    world.ruins(level, d, level.getGameTime());
                    h.assertTrue(plans.pendingWrites() == 0, "Real plan spill still pending");
                    // Pump has evicted the actual completed write; no hand-crafted empty page.
                    h.assertTrue(plans.containsKey(key) && plans.bytes() == 48, "Plan must be stored and cold");
                    var pieces = new java.util.ArrayList<RuinMatter.Piece>();
                    for (int i = 0; i < 8192; i++) pieces.add(new RuinMatter.Piece(glass.above(80),
                            Blocks.STONE.defaultBlockState(), null, net.minecraft.world.phys.Vec3.ZERO, level.getGameTime()));
                    RuinMatter.get(level).release(pieces);
                    ((java.util.Map<Integer, RetainedPlans>) field(queue, "prepared").get(queue)).put(d.id(), plans);
                    var source = DiskShots.parse(level, grid.centre(), net.minecraft.world.level.chunk.storage.ChunkSerializer.write(
                            level, level.getChunkSource().getChunkNow(grid.centre().x, grid.centre().z)));
                    completed[0] = RuinMaintenanceGameTests.completedSource(level, ctx, source);
                    other.materialReservation += NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level) - (24L << 20);
                    fixed[0] = other.materialReservation;
                    h.assertTrue(NuclearPrep.memory(level) > NuclearPrep.PLANS_CAP, "Future-worker policy must refuse");
                    ScarQueue.seenForTest(k -> k == key);
                    ((java.util.Map<Integer, RetainedPlans>) field(queue, "prepared").get(queue)).put(d.id(), plans);
                    queue.offer(level.getChunkSource().getChunkNow(grid.centre().x, grid.centre().z), d);
                    h.assertTrue(queue.queued(key), "Visible chunk was not queued");
                    offered[0] = level.getGameTime();
                    Incoming.LOG.info("DEMAND_PROGRESS cold plan queued at {}, resident {}, future {}, fixed {}",
                            offered[0], NuclearPrep.residentMemory(level), NuclearPrep.memory(level), fixed[0]);
                    stage[0] = 2;
                }
                h.assertTrue(other.materialReservation == fixed[0], "Pressure changed during demand read");
                h.assertTrue(NuclearPrep.residentMemory(level) <= NuclearPrep.PLANS_CAP, "Demand exceeded resident cap");
                if (level.getGameTime() - offered[0] == 30) {
                    var reads = (java.util.Map<Long, java.util.concurrent.CompletableFuture<java.util.Optional<net.minecraft.nbt.CompoundTag>>>) field(plans, "reads").get(plans);
                    var read = reads.get(key);
                    var entries = plans.long2ObjectEntrySet().iterator();
                    var stored = entries.hasNext() ? entries.next().getValue() : null;
                    Incoming.LOG.info("DEMAND_STATE read={} done={} residentPlan={} planBytes={} job={} ledgerWritable={}/{}, resident={}, known={}",
                            read, read != null && read.isDone(), stored != null, plans.bytes(),
                            queue.diagState(key, level.getGameTime()), RuinMatter.get(level).writable(), CraterMatter.get(level).writable(),
                            NuclearPrep.residentMemory(level), read != null && read.isDone() && !read.isCompletedExceptionally()
                                    && read.join().isPresent() ? RuinPlan.restorePublicationBytes(read.join().get()) : -1);
                }
                h.assertTrue(!queue.queued(key), "Watched cold plan remains queued, lag=" + (level.getGameTime() - offered[0])
                        + ", reads=" + plans.pendingReads() + ", future=" + NuclearPrep.memory(level));
                h.assertTrue(level.getChunkSource().getChunkNow(grid.centre().x, grid.centre().z)
                        .getExistingData(ua.zentix.incoming.registry.ModAttachments.CHUNK_SCAR).orElse(0) == d.id(),
                        "Completed queue did not record the applied detonation");
                h.assertTrue(!level.getBlockState(glass).is(Blocks.GLASS), "Queue completed without actual glass damage");
                h.assertTrue(!plans.containsKey(key), "Consumed prepared plan was not released");
                h.assertTrue(queue.size() == 0, "Pending visible work remains after the only queued chunk completed");
                h.assertTrue(RuinMatter.get(level).pending() > 0, "Material backlog ended before visible damage");
                h.assertTrue(completed[0].inbox().pending() == 0, "Ready source remained behind material/visible consumers");
                Incoming.LOG.info("DEMAND_PROGRESS actual watched damage completed after {} ticks under fixed pressure {}",
                        level.getGameTime() - offered[0], fixed[0]);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        });
    }

    @GameTest(template = "range", batch = "nuke_demand_publication", timeoutTicks = 240, skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void completedLargePageKeepsCreditUntilDecodeFits(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var grid = RuinStorageGameTests.fixture(h, true);
        var expected = RuinStorageGameTests.plan(grid).store();
        // Accepted storage dimensions can exceed the fixed read allowance. This is a decoder
        // ownership boundary, not a claim that a physical chunk produces this many fire entries.
        expected.putIntArray("fires", new int[1 << 20]);
        var level = h.getLevel();
        var root = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data/ruin-demand-bound-" + UUID.randomUUID());
        var pages = new RuinPages(new net.minecraft.world.level.chunk.storage.RegionStorageInfo(
                "gametest", level.dimension(), "incoming_preparation"), root);
        long key = grid.centre().toLong(), readBytes = 4L << 20;
        var written = pages.write(key, () -> expected);
        var charged = new java.util.concurrent.atomic.AtomicLong();
        var reopened = written.thenCompose(unused -> pages.close()).thenApply(unused ->
                new RuinPages(new net.minecraft.world.level.chunk.storage.RegionStorageInfo(
                        "gametest", level.dimension(), "incoming_preparation"), root));
        var plans = new RetainedPlans();
        long[] cap = {readBytes + 47};

        int[] stage = {0};
        StrikeGameTests.afterTest(h, plans::clear);
        h.succeedWhen(() -> {
            try {
                h.assertTrue(written.isDone(), "Real oversized page write pending");
                written.join();
                if (stage[0] == 0) {
                    h.assertTrue(reopened.isDone(), "Storage close/reopen pending");
                    plans.spill(reopened.join(), grid.ctx(), charged::addAndGet, () -> 0,
                            () -> cap[0] - charged.get() - plans.bytes());
                    plans.discover();
                    h.assertTrue(Boolean.TRUE.equals(reopened.join().existing()), "Directory probe pending");
                    plans.get(key);
                    h.assertTrue(plans.pendingReads() == 0 && charged.get() == 0,
                            "Read entry must fit along with its source allowance");
                    cap[0]++;
                    stage[0] = 1;
                }
                h.assertTrue(plans.get(key) == null, "Oversized page decoded without publication room");
                var reads = (java.util.Map<Long, java.util.concurrent.CompletableFuture<?>>) field(plans, "reads").get(plans);
                var source = reads.get(key);
                h.assertTrue(source != null && source.isDone(), "Actual page read pending");
                h.assertTrue(charged.get() == readBytes, "Completed refused source lost its credit");
                long known = RuinPlan.restorePublicationBytes(expected) + 48;
                h.assertTrue(known > readBytes, "Test page fits the read allowance");
                cap[0] = known + 47; // Existing read entry: one byte short of publication.
                h.assertTrue(plans.get(key) == null && reads.get(key) == source && charged.get() == readBytes,
                        "Refused publication changed the source future or its owner");
                cap[0]++;
                var restored = plans.get(key);
                h.assertTrue(restored != null, "Fully funded publication did not complete");
                h.assertTrue(restored.bytes() <= RuinPlan.storedBytes(expected), "Decoded fields exceeded their bound");
                h.assertTrue(java.util.Arrays.equals(expected.getIntArray("fires"), restored.store().getIntArray("fires")),
                        "Publication changed retained fire data");
                h.assertTrue(charged.get() == 0 && plans.pendingReads() == 0,
                        "Published source retained or double-refunded its credit");
                h.assertTrue(plans.bytes() <= cap[0], "Decoded owner exceeded funded capacity");
                Incoming.LOG.info("DEMAND_PUBLICATION known {}, actual {}, exact boundary {}", known, plans.bytes(), cap[0]);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        });
    }

    private static Field field(Object owner, String name) throws NoSuchFieldException {
        Field result = owner.getClass().getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
