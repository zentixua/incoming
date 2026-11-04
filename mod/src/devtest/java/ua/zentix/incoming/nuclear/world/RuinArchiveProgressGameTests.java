package ua.zentix.incoming.nuclear.world;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;

/** Reclaiming existing snapshots must not reserve an entire future solver pool first. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinArchiveProgressGameTests {
    private RuinArchiveProgressGameTests() {}

    @GameTest(template = "range", batch = "nuke_archive_progress", timeoutTicks = 200, skyAccess = true)
    public static void unpinnedOriginalSpillsWithoutAnExternalRefund(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var blueprint = RuinStorageGameTests.fixture(h, true);
        var old = blueprint.ctx().d;
        var event = new Detonation(150101, old.burst(), old.groundY(), old.yieldKt(), old.surface(),
                old.gameTime(), 0, 0, old.visibility(), 0, old.scale(), false, old.wave());
        var world = NuclearWorld.get(level);
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER));
        long originalReservation = other.materialReservation;
        var prep = world.prep();
        var content = NuclearPrep.class.getDeclaredMethod("content", net.minecraft.server.level.ServerLevel.class);
        content.setAccessible(true);
        RuinContext[] owner = {null};
        RuinProgress.Pass[] pass = {null};
        String[] previousArchive = {null};
        CompoundTag[] expected = {null};
        long[] fixed = {0}, peak = {0}, initial = {0};
        int[] callbacks = {0};
        var recovered = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<java.util.Optional<CompoundTag>>>();
        long key = blueprint.centre().toLong();
        StrikeGameTests.afterTest(h, () -> {
            other.materialReservation = originalReservation;
            if (owner[0] != null) world.forgetRuinsForTest(owner[0].d.id());
            if (pass[0] != null) pass[0].archive = previousArchive[0];
            RuinWorkers.shutdown();
        });
        h.succeedWhen(() -> {
            try {
                if (owner[0] == null) {
                    String policy = (String) content.invoke(prep, level);
                    h.assertTrue(policy != null, "Waiting for the actual resource-policy fingerprint");
                    var progress = RuinProgress.get(level);
                    h.assertTrue(progress.writable(), "Waiting for the actual progress ledger");
                    pass[0] = progress.pass(event.id());
                    previousArchive[0] = pass[0].archive;
                    pass[0].archive = UUID.randomUUID() + "/" + UUID.randomUUID() + "/" + policy;
                    var ctx = world.ruins(level, event, level.getGameTime());
                    h.assertTrue(ctx.archive() != null, "Production restore did not install the snapshot archive");
                    var shot = ctx.shot(level, blueprint.centre().x, blueprint.centre().z);
                    h.assertTrue(shot != null && shot.bytes > 4096, "Fixture has no nonempty original snapshot");
                    expected[0] = shot.saved().encode();
                    initial[0] = ctx.shotBytes();
                    RuinWorkers.shutdown();
                    RuinWorkers.executor(RuinWorkers.Priority.NEAR); // Configured capacity, no resident threads.
                    long room = Math.max(4L << 20, shot.bytes * 6);
                    h.assertTrue(room < RuinMemory.TRANSITION_ROOM, "Fixture leaves too much room to exercise the competing reserve");
                    other.materialReservation += NuclearPrep.PLANS_CAP - room - NuclearPrep.residentMemory(level);
                    fixed[0] = other.materialReservation;
                    owner[0] = ctx;
                    h.assertTrue(NuclearPrep.memory(level) > NuclearPrep.PLANS_CAP,
                            "Future solver/transition reservation must exceed the remaining resident room");
                    Incoming.LOG.info("ARCHIVE_PROGRESS fixed owner {}, resident {}, future admission {}, original {} bytes, spill room {}",
                            fixed[0], NuclearPrep.residentMemory(level), NuclearPrep.memory(level), initial[0], room);
                }
                var ctx = owner[0];
                world.ruins(level, ctx.d, level.getGameTime());
                callbacks[0]++;
                h.assertTrue(other.materialReservation == fixed[0], "External owner was refunded during archive reclamation");
                var clock = WorkClock.counting(1_000_000);
                clock.start(4_000_000);
                ctx.trimShots(Long.MAX_VALUE, clock);
                peak[0] = Math.max(peak[0], NuclearPrep.residentMemory(level));
                h.assertTrue(peak[0] <= NuclearPrep.PLANS_CAP, "Archive reclamation exceeded known resident ownership");
                h.assertTrue(ctx.shotCount() == 0,
                        "Unpinned original cannot spill/reclaim: callbacks=" + callbacks[0] + ", cached=" + ctx.shotBytes()
                                + ", writes=" + ctx.archive().pendingWrites() + ", resident=" + NuclearPrep.residentMemory(level)
                                + ", future=" + NuclearPrep.memory(level));
                if (recovered.get() == null) recovered.set(ctx.archive().read(key));
                h.assertTrue(recovered.get().isDone(), "Waiting for the real archived original");
                var stored = recovered.get().join();
                h.assertTrue(stored.isPresent() && stored.get().equals(expected[0]), "Reclamation changed or lost the exact original payload");
                Incoming.LOG.info("ARCHIVE_PROGRESS reclaimed exact original after {} callbacks, peak known {}, fixed owner {}",
                        callbacks[0], peak[0], fixed[0]);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        });
    }
}
