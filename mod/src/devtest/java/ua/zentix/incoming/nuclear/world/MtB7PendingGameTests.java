package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Map;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.registry.ModAttachments;

/** TEST ONLY: deterministic observer checks, not a runtime latency/performance assertion. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MtB7PendingGameTests {
    private MtB7PendingGameTests() {}

    @GameTest(template = "range", batch = "mt_b7_pending", timeoutTicks = 100, skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void blockedOmittedAndCompletedChunksHaveDistinctEvidence(GameTestHelper h) throws ReflectiveOperationException {
        var level = h.getLevel();
        NuclearStrikes.clear(level);
        RuinMatterGameTests.clearFixture(level);
        var at = h.absolutePos(new BlockPos(24, 30, 24));
        level.setBlock(at.below(), Blocks.OBSIDIAN.defaultBlockState(), 2);
        level.setBlock(at, Blocks.GLASS.defaultBlockState(), 2);
        var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
        h.assertTrue(chunk != null, "Fixture centre unavailable");
        long key = chunk.getPos().toLong(), now = level.getGameTime();
        var d = new Detonation(NuclearEvents.get(level).nextId(), Vec3.atCenterOf(at).add(-100, 50, 0),
                at.getY(), 15, false, now - 10000, 0, 0, 20000, 42, 1, false, .25f, 6000, false);
        var observer = new MtB7Pending(d.id());
        var queue = new ScarQueue(observer);
        var plansField = ScarQueue.class.getDeclaredField("prepared"); plansField.setAccessible(true);
        var prepared = (Map<Integer, Long2ObjectOpenHashMap<RuinPlan>>) plansField.get(queue);
        var plans = new Long2ObjectOpenHashMap<RuinPlan>();
        var plan = RuinPlanner.plan(level, d, chunk);
        h.assertTrue(plan != RuinPlan.EMPTY && plan.changedBlocks() > 0, "Fixture must remove glass");
        plans.put(key, plan); prepared.put(d.id(), plans); queue.offer(chunk, d);
        ScarQueue.seenForTest(c -> c == key);
        try {
            var other = level.getServer().getLevel(Level.NETHER);
            try (var occupied = RuinMemory.reserve(other, NuclearPrep.PLANS_CAP - NuclearPrep.memory(other, false) - 4096)) {
                h.assertTrue(occupied != null, "Could not apply controlled pressure");
                var clock = WorkClock.counting(1_000_000); clock.start(4_000_000);
                queue.work(level, now, clock);
                h.assertTrue(plan.memoryWaiting(), "Plan must actually be blocked at material admission");
                h.assertTrue(queue.ruinStats()[0] == 0 && queue.ruinStats()[1] == 0, "Completed-only counters must be zero");
                long memory = NuclearPrep.memory(level, false);
                var first = observer.census(level, queue, d, now, () -> 0);
                var later = observer.census(level, queue, d, now + 20, () -> 0);
                h.assertTrue(first.complete() && first.pending() == 1 && first.overdue() == 1 && first.withoutJob() == 0,
                        "Blocked watched loaded chunk must remain pending");
                h.assertTrue(first.traversal().equals("holders"), "Additive override must retain exhaustive holder fallback");
                h.assertTrue(first.maxLag() == now - ScarQueue.due(d, chunk.getPos()) && later.maxLag() == first.maxLag() + 20,
                        "Observer must use wave game time, independently of retry due or wall time");
                h.assertTrue(NuclearPrep.memory(level, false) == memory && queue.size() == 1
                        && chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) < d.id(),
                        "Census must not admit, collect, mark or dequeue work");
                queue.dropPrepared(d.id(), c -> false);
                var dropped = observer.census(level, queue, d, now + 40, () -> 0);
                h.assertTrue(dropped.pending() == 1 && dropped.maxLag() == first.maxLag() + 40,
                        "Prepared expiry must not erase pending lag");
                queue.drop(level, chunk.getPos()); // Deliberately omit the still-loaded chunk from jobs.
                var omitted = observer.census(level, queue, d, now + 60, () -> 0);
                h.assertTrue(omitted.pending() == 1 && omitted.withoutJob() == 1,
                        "A missing job cannot turn an unscarred loaded chunk into completion");
                h.assertTrue(observer.row(omitted).contains("unloaded_pending=1")
                        && observer.row(omitted).contains("prepared_drops=1")
                        && observer.row(omitted).contains("prepared_success=0"), "Exit counters must not count success");
                prepared.put(d.id(), plans); queue.offer(chunk, d);
            }
            var clock = WorkClock.counting(1_000_000); clock.start(4_000_000);
            queue.work(level, now, clock);
            var completed = observer.census(level, queue, d, now, () -> 0);
            h.assertTrue(completed.complete() && completed.pending() == 0 && completed.scarred() == 1
                    && chunk.getData(ModAttachments.CHUNK_SCAR) == d.id(), "Only actual scar commit closes the pending chunk");
            h.assertTrue(observer.row(completed).contains("prepared_success=1")
                    && observer.row(completed).contains("seen_success=1"), "Successful application must have complementary evidence");
            queue.dropPrepared(d.id(), c -> false);
            queue.clear(level);
            var afterReset = observer.census(level, queue, d, now, () -> 0);
            h.assertTrue(observer.row(afterReset).contains("prepared_success=1")
                    && observer.row(afterReset).contains("clears=1"), "Queue/prepared reset must not reset diagnostic history");
        } finally {
            ScarQueue.seenForTest(null);
            queue.clear(level);
            NuclearStrikes.clear(level);
            RuinMatterGameTests.clearFixture(level);
        }
        h.succeed();
    }

    @GameTest(template = "range", batch = "mt_b7_views", timeoutTicks = 100, skyAccess = true)
    public static void nativeViewUnionIsCompletePassiveAndBounded(GameTestHelper h) {
        var level = h.getLevel();
        var at = h.absolutePos(new BlockPos(24, 30, 24));
        var centre = new ChunkPos(at);
        var d = new Detonation(123, Vec3.atCenterOf(at), at.getY(), 1000, false,
                level.getGameTime() - 10000, 0, 0, 20000, 42, 1, false, .25f);
        var observer = new MtB7Pending(d.id());
        var queue = new ScarQueue(observer);
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "b7-census"));
        var peer = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "b7-census-peer"));
        player.setChunkTrackingView(ChunkTrackingView.of(centre, 2));
        peer.setChunkTrackingView(ChunkTrackingView.of(new ChunkPos(centre.x + 1, centre.z), 2));
        level.players().add(player); level.players().add(peer);
        try {
            int expected = 0, scars = 0;
            for (var holder : level.getChunkSource().chunkMap.getChunks()) {
                var p = holder.getPos();
                if (!ScarQueue.watched(level, p)) continue;
                var chunk = level.getChunkSource().getChunkNow(p.x, p.z);
                if (chunk == null) continue;
                expected++;
                if (chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= d.id()) scars++;
            }
            long memory = NuclearPrep.memory(level, false);
            var census = observer.census(level, queue, d, level.getGameTime(), () -> 0);
            h.assertTrue(expected > 0 && census.complete() && census.traversal().equals("views"), "Native cohort missing");
            h.assertTrue(census.watchedLoaded() == expected && census.scarred() == scars
                    && census.pending() == expected - scars && census.withoutJob() == census.pending(),
                    "Native union must equal independent current-holder census, including absent jobs");
            h.assertTrue(NuclearPrep.memory(level, false) == memory && queue.size() == 0, "Native census must remain passive");
            long[] timed = {0};
            var partial = observer.census(level, queue, d, level.getGameTime(),
                    () -> timed[0]++ < 2 ? 0 : MtB7Pending.BUDGET_NS);
            h.assertTrue(!partial.complete() && partial.cutoff().equals("time") && partial.candidates() == 32,
                    "Native per-cell clock cutoff must preserve incomplete lower-bound evidence");
            var immediate = observer.census(level, queue, d, level.getGameTime(),
                    new java.util.function.LongSupplier() {
                        int calls;
                        @Override public long getAsLong() { return calls++ == 0 ? 0 : MtB7Pending.BUDGET_NS; }
                    });
            h.assertTrue(!immediate.complete() && immediate.cutoff().equals("time") && immediate.candidates() == 0,
                    "First native candidate must honor the observer clock");
            player.setChunkTrackingView(ChunkTrackingView.EMPTY);
            peer.setChunkTrackingView(ChunkTrackingView.EMPTY);
            ScarQueue.seenForTest(key -> key == centre.toLong());
            try {
                var extra = observer.census(level, queue, d, level.getGameTime(), () -> 0);
                h.assertTrue(extra.complete() && extra.traversal().equals("holders") && extra.watchedLoaded() == 1,
                        "Additive test visibility outside real players' native views must remain in the cohort");
            } finally { ScarQueue.seenForTest(null); }
            player.setChunkTrackingView(new ChunkTrackingView() {
                @Override public boolean contains(int x, int z, boolean outer) { return x == centre.x && z == centre.z; }
                @Override public void forEach(java.util.function.Consumer<ChunkPos> action) {
                    throw new AssertionError("Custom forEach must never run");
                }
            });
            peer.setChunkTrackingView(ChunkTrackingView.EMPTY);
            var fallback = observer.census(level, queue, d, level.getGameTime(), () -> 0);
            h.assertTrue(fallback.complete() && fallback.traversal().equals("holders") && fallback.watchedLoaded() == 1,
                    "Custom tracking view must preserve the bounded holder fallback");
            player.setChunkTrackingView(ChunkTrackingView.of(centre, 1000));
            peer.setChunkTrackingView(ChunkTrackingView.EMPTY);
            var bounded = observer.census(level, queue, d, level.getGameTime(), () -> 0);
            h.assertTrue(!bounded.complete() && bounded.cutoff().equals("candidates")
                    && bounded.candidates() == MtB7Pending.CANDIDATE_LIMIT, "Candidate cap must also cover rejected square corners");
            for (int i = 0; i < MtB7Pending.PLAYER_LIMIT; i++) level.players().add(player);
            try {
                var capped = observer.census(level, queue, d, level.getGameTime(), () -> 0);
                h.assertTrue(!capped.complete() && capped.cutoff().equals("players") && capped.candidates() == 0,
                        "Excess players cannot certify empty completion");
            } finally {
                for (int i = 0; i < MtB7Pending.PLAYER_LIMIT; i++) level.players().remove(player);
            }
        } finally {
            level.players().remove(player); level.players().remove(peer);
        }
        h.succeed();
    }

    @GameTest(template = "range", batch = "mt_b7_cutoff", timeoutTicks = 100, skyAccess = true)
    public static void missingEventAndTimeCutoffsAreNeverComplete(GameTestHelper h) {
        var level = h.getLevel();
        var observer = new MtB7Pending(123);
        var queue = new ScarQueue(observer);
        var missing = observer.census(level, queue, null, 100, () -> 0);
        h.assertFalse(missing.complete(), "Missing/expired event cannot certify a zero queue");
        h.assertTrue(missing.cutoff().equals("event_missing"), "Missing event provenance lost");
        var d = new Detonation(123, Vec3.ZERO, 0, 15, false, 0, 0, 0, 20000, 42, 1, false, .25f);
        long[] clock = {0};
        var cutoff = observer.census(level, queue, d, 100, () -> clock[0]++ * MtB7Pending.BUDGET_NS);
        h.assertFalse(cutoff.complete(), "Truncated sample cannot certify completion");
        h.assertTrue(cutoff.cutoff().equals("time") && cutoff.holders() == 0 && cutoff.observerNs() >= MtB7Pending.BUDGET_NS,
                "Time cutoff and observer cost must be explicit");
        h.succeed();
    }
}
