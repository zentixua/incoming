package ua.zentix.incoming.nuclear.world;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;

/** Real page receipts and live ownership boundaries, independently of the large runtime scene. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinProtectedCohortGameTests {
    private RuinProtectedCohortGameTests() {}

    @GameTest(template = "range", batch = "nuke_protected_pages", timeoutTicks = 600, skyAccess = true)
    public static void currentCohortSurvivesSpillThenReleasesOnUnloadAndConsume(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var grid = RuinStorageGameTests.fixture(h, true);
        var original = RuinStorageGameTests.plan(grid);
        h.assertTrue(original.changedBlocks() > 0, "Fixture produced no actual ruin delta");
        var level = h.getLevel();
        var plans = new RetainedPlans(0);
        var charged = new AtomicLong();
        var path = level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data/protected-pages-" + UUID.randomUUID());
        plans.spill(new RuinPages(new RegionStorageInfo("gametest", level.dimension(), "incoming_preparation"), path),
                grid.ctx(), charged::addAndGet, () -> 64L << 20);
        StrikeGameTests.afterTest(h, plans::clear);
        long key = grid.centre().toLong();
        plans.protect(key);
        plans.put(key, original);
        int[] phase = {0};
        h.onEachTick(() -> {
            plans.pump(64L << 20);
            if (plans.pendingWrites() != 0) return;
            if (phase[0] == 0) {
                h.assertTrue(plans.resident(key) && plans.get(key) == original,
                        "Completed native spill evicted the current critical plan");
                h.assertTrue(plans.bytes() >= original.bytes(), "Protected resident ownership was not charged");
                plans.unprotect(key); // Registered unload uses this same ownership boundary.
                plans.pump(64L << 20);
                h.assertTrue(!plans.resident(key) && plans.containsKey(key),
                        "Unloaded cohort member retained a protected resident plan");
                plans.protect(key); // A returning chunk claims its historical stored page.
                phase[0] = 1;
            }
            var restored = plans.get(key);
            if (restored == null) return;
            h.assertTrue(restored != original && original.store().equals(restored.store()), "Native page restore changed the delta");
            plans.pump(64L << 20);
            h.assertTrue(plans.resident(key), "A returning protected page was immediately evicted again");
            plans.remove(key);
            h.assertTrue(!plans.protectedPlan(key) && plans.bytes() == 0 && charged.get() == 0,
                    "Consumed plan leaked pin, payload or native I/O ownership");
            plans.clear();
            h.succeed();
        });
    }

    @GameTest(template = "range", batch = "nuke_funded_zero_growth", timeoutTicks = 100)
    public static void alreadyFundedCaptureDoesNotReenterAdmission(GameTestHelper h) {
        var level = h.getLevel();
        var owner = NuclearWorld.get(level);
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER));
        long previous = other.materialReservation;
        long initial = owner.materialReservation;
        var reservation = RuinMemory.reserve(level, 1024);
        h.assertTrue(reservation != null, "Fixture could not fund initial capture");
        try {
            other.materialReservation += NuclearPrep.PLANS_CAP;
            h.assertTrue(reservation.grow(level, 0), "Already funded ordinary block was refused by unrelated pressure");
            h.assertTrue(!reservation.grow(level, 1), "Positive capture growth bypassed global admission");
            h.assertTrue(owner.materialReservation == initial + 1024, "Zero/refused growth changed ownership");
        } finally {
            other.materialReservation = previous;
            reservation.close();
        }
        h.assertTrue(owner.materialReservation == initial, "Capture close leaked reservation");
        boolean rejected = false;
        try { reservation.grow(level, 0); }
        catch (IllegalStateException expected) { rejected = true; }
        h.assertTrue(rejected, "Closed capture accepted new use");
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_loaded_demand_early", timeoutTicks = 200, skyAccess = true)
    public static void missingLoadedPlanStartsBeforeFinalWaveLookahead(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var grid = RuinStorageGameTests.fixture(h, true);
        var level = h.getLevel();
        var queue = new ScarQueue();
        var chunk = level.getChunkSource().getChunkNow(grid.centre().x, grid.centre().z);
        var event = grid.ctx().d;
        long early = ScarQueue.due(event, grid.centre()) - 200;
        long started = level.getGameTime();
        var clock = WorkClock.counting(1_000_000);
        var world = NuclearWorld.get(level);
        queue.offer(chunk, event);
        StrikeGameTests.afterTest(h, () -> {
            queue.clear(level);
            world.forgetRuinsForTest(event.id());
        });
        h.succeedWhen(() -> {
            clock.start(100_000_000);
            queue.work(level, early + Math.min(100, level.getGameTime() - started), clock);
            var context = world.ruinsIfKept(event.id());
            h.assertTrue(context != null && (context.running(grid.centre().toLong())
                            || queue.hasPlan(context, event, grid.centre().toLong())),
                    "Loaded demand did not start until the final one-second wave lookahead");
            h.assertTrue(chunk.getExistingData(ua.zentix.incoming.registry.ModAttachments.CHUNK_SCAR).orElse(0) < event.id(),
                    "Early preparation applied ruins before the wave");
        });
    }
}
