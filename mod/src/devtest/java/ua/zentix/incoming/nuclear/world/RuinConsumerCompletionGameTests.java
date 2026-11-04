package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;

/** Complete lower-priority work funds an actual dense visible removal under fixed ownership. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinConsumerCompletionGameTests {
    private RuinConsumerCompletionGameTests() {}

    @GameTest(template = "range", batch = "nuke_consumer_completion", timeoutTicks = 600, skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void denseVisibleWaitCannotHideCompletedUnseenConsumers(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        NuclearStrikes.clear(level); RuinMatterGameTests.clearFixture(level);
        var world = NuclearWorld.get(level);
        var clock = WorkClock.counting(1_000_000);
        clock.start(4_000_000); world.tick(level, clock);
        var oldClock = NuclearWorld.clock(level.getServer());
        NuclearWorld.useClock(level.getServer(), clock);
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER));
        long previous = other.materialReservation;
        int threads = IncomingConfig.SERVER.nukeRuinThreads.get();
        RuinWorkers.shutdown(); IncomingConfig.SERVER.nukeRuinThreads.set(1);
        var gate = new CompletableFuture<Void>();
        var root = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data/consumer-completion-" + UUID.randomUUID());
        var pages = new RuinPages(new RegionStorageInfo("gametest", level.dimension(), "incoming_preparation"), root);
        var plans = new RetainedPlans(0);
        StrikeGameTests.afterTest(h, () -> {
            gate.complete(null);
            other.materialReservation = previous;
            ScarQueue.seenForTest(null);
            NuclearWorld.useClock(level.getServer(), oldClock);
            NuclearStrikes.clear(level); RuinMatterGameTests.clearFixture(level);
            RuinWorkers.shutdown(); IncomingConfig.SERVER.nukeRuinThreads.set(threads);
            plans.clear();
        });
        ChunkPos centre = new ChunkPos(h.absolutePos(new BlockPos(32, 1, 32)));
        ChunkPos unseen = new ChunkPos(centre.x + 1, centre.z);
        for (int z = -2; z <= 2; z++) for (int x = -2; x <= 3; x++) level.getChunk(centre.x + x, centre.z + z);
        int bottom = h.absolutePos(new BlockPos(0, 6, 0)).getY();
        long[] positions = new long[14_820];
        for (int i = 0; i < positions.length; i++) {
            BlockPos at = new BlockPos(centre.getMinBlockX() + (i & 15), bottom + (i >> 8), centre.getMinBlockZ() + ((i >> 4) & 15));
            positions[i] = at.asLong();
            level.setBlock(at, Blocks.STONE_BRICKS.defaultBlockState(), 18);
        }
        BlockPos glass = new BlockPos(unseen.getMiddleBlockX(), bottom + 70, unseen.getMiddleBlockZ());
        level.setBlock(glass.below(), Blocks.OBSIDIAN.defaultBlockState(), 18);
        level.setBlock(glass, Blocks.GLASS.defaultBlockState(), 18);
        var d = new Detonation(NuclearEvents.get(level).nextId(),
                new Vec3(centre.getMiddleBlockX() - 1000, bottom + 500, centre.getMiddleBlockZ()), bottom,
                15, false, level.getGameTime() - 20000, 0, 0, 20000, 42, 1, false, .25f, 6000, false);
        NuclearEvents.get(level).add(d);
        var ctx = world.ruins(level, d, level.getGameTime());
        var grid = ctx.grid(level, centre, false);
        h.assertTrue(grid != null, "Dense live window missing");
        var sections = new RuinPlanner.Sections(grid.at(0, 0), grid.view());
        for (long position : positions) {
            BlockPos at = BlockPos.of(position);
            sections.set(at.getX() & 15, at.getY(), at.getZ() & 15, Blocks.AIR.defaultBlockState());
        }
        var dense = sections.finish(ctx, new long[0], null);
        dense.material(positions);
        // The dense resident plan belongs to the scar; the fourteen real stored empty plans have
        // no remaining visible consumer, as after unloading. Their read reservations still do.
        var prepared = new RetainedPlans(); prepared.put(centre.toLong(), dense);
        ((Map<Integer, RetainedPlans>) field(world.scars(), "prepared").get(world.scars())).put(d.id(), prepared);
        ctx.restoredPlans = plans;
        plans.spill(pages, ctx, world.prep().readAccounting(),
                () -> Math.max(0, NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level)));
        long[] reads = new long[14];
        for (int i = 0; i < reads.length; i++) {
            reads[i] = ChunkPos.asLong(centre.x + 64 + i, centre.z);
            plans.put(reads[i], RuinPlan.nothing(ctx, null));
        }
        plans.pump(NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level));
        h.assertTrue(ctx.submit(level, level.getChunkSource().getChunkNow(unseen.x, unseen.z), RuinWorkers.Priority.NEAR),
                "Actual unseen solver was not admitted");
        int[] stage = {0};
        long[] started = {0}, fixed = {0};
        h.succeedWhen(() -> {
            try {
                world.ruins(level, d, level.getGameTime());
                if (stage[0] == 0) {
                    plans.pump(NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level));
                    h.assertTrue(plans.pendingWrites() == 0 && ctx.done(unseen.toLong()), "Real page writes/solver still running");
                    h.assertTrue(plans.bytes() == reads.length * 48L, "Stored pages were not evicted");
                    // Fence the actual vanilla reads, so admission/ownership is observed before
                    // any can complete. Releasing this gate releases no memory reservation.
                    field(pages, "tail").set(pages, gate);
                    for (long key : reads) h.assertTrue(plans.get(key) == null, "Read completed before its I/O fence");
                    h.assertTrue(plans.pendingReads() == reads.length, "Demand read owners missing");
                    other.materialReservation = previous + (400L << 20);
                    fixed[0] = other.materialReservation;
                    h.assertTrue(NuclearPrep.residentMemory(level) <= NuclearPrep.PLANS_CAP, "Fixture overfilled its actual owned set");
                    var chunk = level.getChunkSource().getChunkNow(centre.x, centre.z);
                    h.assertFalse(dense.apply(level, chunk, d, new ColumnScar.Budget(false)), "Dense fixture must initially wait for consumer capacity");
                    h.assertTrue(dense.memoryWaiting(), "Dense fixture is stale, not capacity-blocked");
                    ScarQueue.seenForTest(key -> key == centre.toLong());
                    world.scars().offer(chunk, d);
                    world.scars().offer(level.getChunkSource().getChunkNow(unseen.x, unseen.z), d);
                    started[0] = level.getGameTime(); stage[0] = 1;
                    Incoming.LOG.info("CONSUMER_COMPLETION start resident {} fixed {} readOwners {} tasks {} denseDebit {}",
                            NuclearPrep.residentMemory(level), fixed[0], world.prep().readAccounting(), ctx.tasks(),
                            RuinMatter.get(level).removalBytes(positions.length));
                    gate.complete(null);
                }
                h.assertTrue(other.materialReservation == fixed[0], "External ownership was released");
                h.assertTrue(NuclearPrep.residentMemory(level) <= NuclearPrep.PLANS_CAP, "Consumer publication exceeded capacity");
                h.assertTrue(world.scars().size() == 0 && ctx.tasks() == 0 && plans.pendingReads() == 0,
                        "Funded consumers remain: scars=" + world.scars().size() + ", tasks=" + ctx.tasks() + ", reads=" + plans.pendingReads());
                for (long position : positions) h.assertFalse(level.getBlockState(BlockPos.of(position)).is(Blocks.STONE_BRICKS),
                        "Dense watched source survived completion");
                h.assertFalse(level.getBlockState(glass).is(Blocks.GLASS), "Unseen completion lost its real solver output");
                for (ChunkPos pos : java.util.List.of(centre, unseen)) h.assertTrue(level.getChunkSource().getChunkNow(pos.x, pos.z)
                        .getExistingData(ua.zentix.incoming.registry.ModAttachments.CHUNK_SCAR).orElse(0) == d.id(), "Missing applied scar");
                h.assertTrue(RuinMatter.get(level).released() >= positions.length + 1, "Completed dense plan lost material ownership");
                Incoming.LOG.info("CONSUMER_COMPLETION dense {}, all reads/tasks/scars drained in {} ticks, resident {}",
                        positions.length, level.getGameTime() - started[0], NuclearPrep.residentMemory(level));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        });
    }

    private static Field field(Object owner, String name) throws NoSuchFieldException {
        Field result = owner.getClass().getDeclaredField(name); result.setAccessible(true); return result;
    }
}
