package ua.zentix.incoming.nuclear.world;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;

/** Consumption-boundary reproduction of the 22,054-cell parked plan in the 1 Mt run. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinQueueProgressGameTests {
    private static final int CELLS = 22_054;
    private static final long ROOM = 160L << 20;

    private RuinQueueProgressGameTests() {}

    private record Fixture(LevelChunk chunk, Detonation event, RuinPlan plan, long[] positions) {}

    private static Fixture prepare(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        NuclearStrikes.clear(level);
        RuinMatterGameTests.clearFixture(level);
        var clock = WorkClock.counting(1);
        clock.start(32); NuclearWorld.get(level).tick(level, clock);
        ChunkPos centre = new ChunkPos(h.absolutePos(new BlockPos(32, 1, 32)));
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
            level.getChunk(centre.x + dx, centre.z + dz); // Fixture setup, never the live tick path.
        int bottom = h.absolutePos(new BlockPos(0, 4, 0)).getY();
        long[] positions = new long[CELLS];
        for (int i = 0; i < CELLS; i++) {
            BlockPos at = new BlockPos(centre.getMinBlockX() + (i & 15), bottom + (i >> 8),
                    centre.getMinBlockZ() + ((i >> 4) & 15));
            positions[i] = at.asLong();
            level.setBlock(at, Blocks.STONE_BRICKS.defaultBlockState(), 18);
        }
        var event = new Detonation(NuclearEvents.get(level).nextId(),
                new Vec3(centre.getMiddleBlockX(), bottom + 100, centre.getMiddleBlockZ()), bottom,
                15, false, level.getGameTime(), 0, 0, 20000, 42, 1, false, .25f, 6000, false);
        NuclearEvents.get(level).add(event);
        var context = NuclearWorld.get(level).ruins(level, event, level.getGameTime());
        var grid = context.grid(level, centre, false);
        h.assertTrue(grid != null, "Fixture could not capture its live source window");
        // The measured consumer already had its plan. Rebuild the same payload cardinality
        // through the real section encoder, without making a new fracture/geometry claim.
        var sections = new RuinPlanner.Sections(grid.at(0, 0), grid.view());
        for (long position : positions) {
            BlockPos at = BlockPos.of(position);
            sections.set(at.getX() & 15, at.getY(), at.getZ() & 15, Blocks.AIR.defaultBlockState());
        }
        var plan = sections.finish(context, new long[0], null);
        plan.material(positions);
        h.assertTrue(plan.changedBlocks() == CELLS, "Fixture lost destructive plan positions");
        return new Fixture(level.getChunkSource().getChunkNow(centre.x, centre.z), event, plan, positions);
    }

    @GameTest(template = "range", batch = "nuke_large_plan_admission", timeoutTicks = 100, skyAccess = true)
    public static void densePlanUsesItsFundedPublicationFootprint(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        h.assertTrue(bean.isThreadAllocatedMemorySupported(), "Allocator measurement is unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        try {
            Fixture reference = prepare(h);
            long before = NuclearPrep.residentMemory(level), allocationStart = bean.getThreadAllocatedBytes(thread);
            h.assertTrue(reference.plan.apply(level, reference.chunk, reference.event, new ColumnScar.Budget(false)),
                    "Unpressured reference failed to apply");
            long allocated = bean.getThreadAllocatedBytes(thread) - allocationStart;
            long growth = NuclearPrep.residentMemory(level) - before;
            var referenceLedger = RuinMatter.get(level);
            h.assertTrue(referenceLedger.released() == CELLS && referenceLedger.pending() == CELLS
                            && referenceLedger.pendingSupport() == CELLS,
                    "Reference did not retain all material and support seeds");
            double volume = referenceLedger.releasedVolume();
            double[] balance = referenceLedger.balance(Blocks.STONE_BRICKS);
            Incoming.LOG.info("Large-plan reference: cells={} serverAllocated={} retainedGrowth={} controlledRoom={}",
                    CELLS, allocated, growth, ROOM);
            h.assertTrue(allocated + growth < ROOM,
                    "Fixture room does not fund measured capture allocation plus retained ownership");

            Fixture pressured = prepare(h);
            var other = level.getServer().getLevel(Level.NETHER);
            try (var occupied = RuinMemory.reserve(other,
                    NuclearPrep.PLANS_CAP - NuclearPrep.memory(other, false) - ROOM)) {
                h.assertTrue(occupied != null, "Could not establish the fixed external owner");
                long initial = NuclearPrep.memory(level, false);
                boolean applied = pressured.plan.apply(level, pressured.chunk, pressured.event, new ColumnScar.Budget(false));
                Incoming.LOG.info("Large-plan pressured: cells={} initialOwned={} room={} applied={} memoryWaiting={} retainedNow={}",
                        CELLS, initial, NuclearPrep.PLANS_CAP - initial, applied, pressured.plan.memoryWaiting(),
                        NuclearPrep.memory(level, false));
                h.assertTrue(applied, "Funded dense plan refused application under the unchanged external owner");
                var ledger = RuinMatter.get(level);
                h.assertTrue(ledger.released() == CELLS && ledger.pending() == CELLS && ledger.pendingSupport() == CELLS,
                        "Pressure discarded or duplicated material/support ownership");
                h.assertTrue(ledger.releasedVolume() == volume
                                && java.util.Arrays.equals(balance, ledger.balance(Blocks.STONE_BRICKS)),
                        "Pressure changed conserved volume/mass");
                for (long position : pressured.positions)
                    h.assertTrue(level.getBlockState(BlockPos.of(position)).isAir(), "A planned source block survived");
                h.assertTrue(NuclearPrep.memory(level, false) <= NuclearPrep.PLANS_CAP,
                        "Application exceeded the unchanged server-wide ownership cap");
            }
        } finally {
            NuclearStrikes.clear(level);
            RuinMatterGameTests.clearFixture(level);
        }
        h.succeed();
    }
}
