package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinSettleGameTests {
    private RuinSettleGameTests() {}

    /** Isolate older ticket-order tests whose fixture deliberately keeps an existing neighbor unloaded. */
    public static void manualLoadingOnly(ServerLevel level, int detonation) {
        RuinProgress progress = RuinProgress.get(level);
        RuinProgress.Pass pass = progress.pass(detonation);
        pass.cursor = Long.MAX_VALUE;
        pass.complete = true;
        pass.pending.clear();
        progress.setDirty();
    }

    /** Model a restarted scan with only this already-queued chunk still unconfirmed on disk. */
    public static void resumeOnly(ServerLevel level, int detonation, ChunkPos chunk) {
        RuinProgress progress = RuinProgress.get(level);
        RuinProgress.Pass pass = progress.pass(detonation);
        pass.cursor = Long.MAX_VALUE;
        pass.complete = true;
        pass.pending.add(chunk.toLong());
        progress.setDirty();
    }

    /** Real disk-only chunk outside 5 psi: neither a player nor a LOD client ever brings it into memory. */
    @GameTest(template = "range", batch = "nuke_settle_disk", timeoutTicks = 2400, skyAccess = true)
    public static void oldDiskChunkGetsPlannedRuinsWithoutPlayers(GameTestHelper h) {
        diskPass(h, false);
    }

    /** Restart while the scan cursor has advanced but the chunk's new scar has not reached disk. */
    @GameTest(template = "range", batch = "nuke_settle_restart", timeoutTicks = 2400, skyAccess = true)
    public static void restartRetainsUnconfirmedChunks(GameTestHelper h) {
        diskPass(h, true);
    }

    private static void diskPass(GameTestHelper h, boolean restart) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        ChunkPos pad = new ChunkPos(h.absolutePos(new BlockPos(32, 1, 32)));
        ChunkPos target = new ChunkPos(pad.x + 200, pad.z + 200);
        int ground = 64;
        LevelChunk source = fixture(level, target, ground);
        CompoundTag before = ChunkSerializer.write(level, source);
        level.getChunkSource().chunkMap.write(target, before);
        h.assertTrue(NuclearTickets.inMemory(level, target.toLong()) == null, "fixture loaded into world");
        NuclearEvents events = NuclearEvents.get(level);
        Detonation d = new Detonation(events.nextId(), new Vec3(target.getMiddleBlockX() - 48, ground + 1, target.getMiddleBlockZ()),
                ground + 1, 7, true, level.getGameTime() - 10000, 0, 0, 20000, 42, 0.03f, false, 1, 0, false);
        h.assertTrue(NuclearPrep.horizontal(target, d) > NuclearPrep.heavyRadius(d), "fixture is inside heavy zone");
        h.assertTrue(ScarQueue.inRange(target, d), "fixture is outside ruin radius");
        RuinContext ctx = new RuinContext(level, d);
        ctx.putDisk(level, DiskShots.parse(level, target, before));
        RuinContext.Grid grid = ctx.cachedGridForTest(level, target);
        RuinPlan plan = RuinPlanner.finish(grid, Collapse.solve(grid));
        h.assertTrue(plan.changedBlocks() > 0, "fixture has no planned changes");
        LevelChunkSection[] expected = plan.testAppliedCopy(level, source);
        // As in a 3.10.3 save: retained event, with no settlement data and no live onDetonation call.
        events.past().add(d);
        events.setDirty();
        RuinProgress progress = RuinProgress.get(level);
        RuinProgress.Pass p = progress.pass(d.id());
        h.assertTrue(p.cursor == 0 && p.pending.isEmpty(), "old event already has settlement progress");
        StrikeGameTests.afterTest(h, () -> {
            events.past().remove(d);
            events.setDirty();
            NuclearWorld.get(level).clear(level);
        });
        long started = System.nanoTime();
        long[] nanos = {0, 0};
        boolean[] restarted = {!restart};
        @SuppressWarnings({"unchecked", "rawtypes"})
        CompletableFuture<Optional<CompoundTag>>[] reading = new CompletableFuture[1];
        h.onEachTick(() -> {
            long[] costs = NuclearWorld.get(level).lastNanos();
            long tick = java.util.Arrays.stream(costs).sum();
            nanos[0] += tick;
            nanos[1] = Math.max(nanos[1], tick);
            if (!restarted[0] && p.pending.contains(target.toLong())) {
                // Reconstruct exactly the saved progress; discard all unsaved work and tickets as shutdown does.
                CompoundTag saved = progress.save(new CompoundTag(), level.registryAccess());
                RuinProgress restored = RuinProgress.load(saved, level.registryAccess());
                h.assertTrue(restored.pass(d.id()).cursor == p.cursor && restored.pass(d.id()).pending.contains(target.toLong()),
                        "restart lost cursor or unconfirmed chunk");
                NuclearWorld.get(level).clear(level);
                level.getDataStorage().set("incoming_ruin_settle", restored);
                level.setData(ModAttachments.NUCLEAR_WORLD, new NuclearWorld());
                restarted[0] = true;
            }
            if (reading[0] == null) reading[0] = level.getChunkSource().chunkMap.read(target);
            if (!reading[0].isDone()) return;
            CompoundTag saved = reading[0].join().orElseThrow();
            reading[0] = null;
            if (saved.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY).getInt("incoming:chunk_scar") < d.id()) return;
            h.assertTrue(restarted[0], "restart case was not exercised");
            DiskShots.Sections result = DiskShots.parseSections(DiskShots.Format.of(level), target, saved);
            h.assertTrue(result.skip() == null, "saved chunk cannot be parsed: " + result.skip());
            int changes = 0, trace = 0;
            for (int s = 0; s < expected.length; s++) {
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    var want = expected[s].getBlockState(x, y, z);
                    var got = result.sections()[s].getBlockState(x, y, z);
                    h.assertTrue(got == want, "saved plan mismatch at section " + s + ", " + x + "," + y + "," + z + ": " + got + " != " + want);
                    if (got != source.getSections()[s].getBlockState(x, y, z)) {
                        changes++;
                        if (source.getSections()[s].getBlockState(x, y, z).is(Blocks.GRASS_BLOCK) || source.getSections()[s].getBlockState(x, y, z).is(Blocks.SHORT_GRASS)) trace++;
                    }
                }
            }
            h.assertTrue(changes > 0, "saved chunk is unchanged");
            h.assertTrue(trace > 0, "saved chunk has no ground trace");
            Incoming.LOG.info("SETTLEBENCH restart={} chunks=1 changed={} wallMs={} nuclearTotalMs={} nuclearMaxMs={}",
                    restart, changes, (System.nanoTime() - started) / 1_000_000, nanos[0] / 1_000_000.0, nanos[1] / 1_000_000.0);
            h.succeed();
        });
    }

    /** Unchanged neighbors are finished too: they must not pin old city plans for the whole zone. */
    @GameTest(template = "range", batch = "nuke_settle_empty", timeoutTicks = 100, skyAccess = true)
    public static void emptyPlansReleaseSurroundingOldBlocks(GameTestHelper h) throws ReflectiveOperationException {
        ServerLevel level = h.getLevel();
        ChunkPos center = new ChunkPos(10000, 10000);
        Detonation d = new Detonation(NuclearEvents.get(level).nextId(),
                new Vec3(center.getMiddleBlockX() - 48, 65, center.getMiddleBlockZ()),
                65, 7, true, -10000, 0, 0, 20000, 42, 0.03f, false, 1);
        RuinContext ctx = NuclearWorld.get(level).ruins(level, d, level.getGameTime());
        LevelChunk source = fixture(level, center, 64);
        ctx.putDisk(level, DiskShots.parse(level, center, ChunkSerializer.write(level, source)));
        RuinContext.Grid grid = ctx.cachedGridForTest(level, center);
        RuinPlan plan = RuinPlanner.finish(grid, Collapse.solve(grid));
        h.assertTrue(plan.changedBlocks() > 0, "fixture needs a nonempty retained plan");
        ctx.applied(source, plan, new short[256]);
        h.assertTrue(ctx.applied(center.toLong()).plan() != null, "plan was not retained");
        var heightsField = RuinContext.class.getDeclaredField("heights"); heightsField.setAccessible(true);
        var shadows = (java.util.Map<?, ?>) heightsField.get(ctx);
        Object originalShadow = shadows.get(center.toLong());
        h.assertTrue(originalShadow != null, "fixture did not retain its original shadow tile");
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            if (x == 0 && z == 0) continue;
            RuinPlan.EMPTY.apply(level, new LevelChunk(level, new ChunkPos(center.x + x, center.z + z)), d, null);
        }
        h.assertTrue(ctx.applied(center.toLong()) == null,
                "completed window retained an area-sized history of old blocks/motion arrays");
        h.assertTrue(shadows.get(center.toLong()) == originalShadow, "retirement changed pre-wave shadow heights");
        NuclearWorld.get(level).clear(level);
        h.succeed();
    }

    /** Evicting a re-readable snapshot preserves the old-world data needed to reconstruct neighboring ruins. */
    @GameTest(template = "range", batch = "nuke_settle_cache", timeoutTicks = 100, skyAccess = true)
    public static void snapshotTrimKeepsOriginalBlocksAndUsesWorkClock(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos center = new ChunkPos(12000, 12000);
        Detonation d = new Detonation(NuclearEvents.get(level).nextId(),
                new Vec3(center.getMiddleBlockX() - 48, 65, center.getMiddleBlockZ()),
                65, 7, true, -10000, 0, 0, 20000, 42, 0.03f, false, 1);
        RuinContext ctx = NuclearWorld.get(level).ruins(level, d, level.getGameTime());
        LevelChunk source = fixture(level, center, 64);
        var before = ChunkSerializer.write(level, source);
        ctx.putDisk(level, DiskShots.parse(level, center, before));
        var grid = ctx.cachedGridForTest(level, center);
        var plan = RuinPlanner.finish(grid, Collapse.solve(grid));
        h.assertTrue(plan.changedBlocks() > 0, "fixture has no changes");
        LevelChunkSection[] ruined = plan.testAppliedCopy(level, source);
        for (int i = 0; i < ruined.length; i++) source.getSections()[i] = ruined[i];
        ctx.applied(source, plan, new short[256]);
        WorkClock clock = WorkClock.counting(1_000_000);
        clock.start(1_000_000);
        ctx.trimShots(Long.MAX_VALUE, clock);
        h.assertTrue(clock.usedThisTickNanos() <= 1_000_000 && !ctx.hasShot(center.toLong()), "trim bypassed budget or kept cold snapshot");
        h.assertTrue(ctx.applied(center.toLong()).plan() == plan, "trim discarded original block deltas");
        ctx.putDisk(level, DiskShots.parse(level, center, ChunkSerializer.write(level, source)));
        var rebuilt = ctx.cachedGridForTest(level, center);
        // Re-read ruined blocks must reconstruct exactly the original snapshot through the retained delta.
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 64; y < 70; y++)
            h.assertTrue(rebuilt.at(0, 0).get(x, y, z) == grid.at(0, 0).get(x, y, z), "trim changed original world at " + x + "," + y + "," + z);
        NuclearWorld.get(level).clear(level);
        h.succeed();
    }

    /** Disk windows arrive in batches; eviction must preserve them before a worker can be submitted. */
    @GameTest(template = "range", batch = "nuke_settle_window", timeoutTicks = 400, skyAccess = true)
    public static void snapshotTrimProtectsIncompleteDiskWindows(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        ChunkPos center = new ChunkPos(14000, 14000);
        Detonation d = new Detonation(NuclearEvents.get(level).nextId(),
                new Vec3(center.getMiddleBlockX(), 65, center.getMiddleBlockZ()),
                65, 7, true, -10000, 0, 0, 20000, 42, 0.03f, false, 1);
        RuinContext ctx = new RuinContext(level, d);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            ChunkPos pos = new ChunkPos(center.x + x, center.z + z);
            CompoundTag tag = ChunkSerializer.write(level, fixture(level, pos, 64));
            tag.remove("Heightmaps"); // old editor maps: only four snapshots are primed per requestWindow call
            level.getChunkSource().chunkMap.write(pos, tag);
        }
        boolean[] partial = {false};
        h.onEachTick(() -> {
            int missing = ctx.requestWindow(level, center);
            int captured = ctx.shotCount();
            if (captured > 0 && missing > 0) partial[0] = true;
            WorkClock clock = WorkClock.counting(1_000_000);
            clock.start(30_000_000);
            ctx.trimShots(Long.MAX_VALUE, clock);
            h.assertTrue(ctx.shotCount() == captured, "trim discarded a window awaiting disk reads or worker admission");
            if (missing > 0) return;
            h.assertTrue(partial[0] && captured == 25, "partial disk window was not exercised");
            ctx.cancelTasks();
            clock.start(30_000_000);
            ctx.trimShots(Long.MAX_VALUE, clock);
            h.assertTrue(ctx.shotCount() == 0, "cancel retained the protected window");
            h.succeed();
        });
    }

    /** A grass field with an exposed glass wall; allocated as chunk data, never installed in the live world. */
    static LevelChunk fixture(ServerLevel level, ChunkPos pos, int ground) {
        LevelChunk chunk = new LevelChunk(level, pos);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            for (int y = level.getMinBuildHeight(); y < ground; y++) chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x, y & 15, z, Blocks.STONE.defaultBlockState(), false);
            chunk.getSection(chunk.getSectionIndex(ground)).setBlockState(x, ground & 15, z, Blocks.GRASS_BLOCK.defaultBlockState(), false);
        }
        for (int x = 1; x < 7; x++) for (int z = 1; z < 15; z++)
            chunk.getSection(chunk.getSectionIndex(ground + 1)).setBlockState(x, (ground + 1) & 15, z, Blocks.SHORT_GRASS.defaultBlockState(), false);
        for (int z = 4; z < 12; z++) for (int y = ground + 1; y < ground + 5; y++)
            chunk.getSection(chunk.getSectionIndex(y)).setBlockState(8, y & 15, z, Blocks.GLASS.defaultBlockState(), false);
        net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(chunk, java.util.Set.of(RuinPlan.HEIGHTMAP_TYPES));
        return chunk;
    }

    /** Sustained, saved-world pass across a 10 by 10 field with glass structures, with bounded I/O and tickets. */
    @GameTest(template = "range", batch = "nuke_settle_many", timeoutTicks = 3000, skyAccess = true)
    public static void wholeSavedAreaSettles(GameTestHelper h) {
        wholeSavedAreaSettles(h, false);
    }

    static void wholeSavedAreaSettles(GameTestHelper h, boolean checkLss) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        ChunkPos pad = new ChunkPos(h.absolutePos(new BlockPos(32, 1, 32)));
        ChunkPos center = new ChunkPos(pad.x + 400, pad.z + 400);
        NuclearEvents events = NuclearEvents.get(level);
        Detonation d = new Detonation(events.nextId(), new Vec3(center.getMiddleBlockX() - 130, 65, center.getMiddleBlockZ()),
                65, 7, true, level.getGameTime() - 10000, 0, 0, 20000, 42, 0.1f, false, 1, 0, false);
        java.util.List<ChunkPos> targets = new java.util.ArrayList<>();
        LssSettleCheck lss = checkLss ? new LssSettleCheck(h) : null;
        for (int x = -5; x < 5; x++) for (int z = -5; z < 5; z++) {
            ChunkPos pos = new ChunkPos(center.x + x, center.z + z);
            targets.add(pos);
            LevelChunk source = fixture(level, pos, 64);
            level.getChunkSource().chunkMap.write(pos, ChunkSerializer.write(level, source));
            if (lss != null) lss.add(source);
        }
        boolean[] started = {false};
        StrikeGameTests.afterTest(h, () -> { events.past().remove(d); events.setDirty(); NuclearWorld.get(level).clear(level); });
        long[] began = {System.nanoTime()};
        long[] cost = {0, 0};
        int[] ticks = {0}, confirmed = {0};
        java.util.ArrayDeque<ChunkPos> remaining = new java.util.ArrayDeque<>(targets);
        java.util.Map<ChunkPos, CompletableFuture<Optional<CompoundTag>>> reads = new java.util.LinkedHashMap<>();
        h.onEachTick(() -> {
            if (!started[0]) {
                if (lss != null && !lss.ready()) return;
                events.past().add(d);
                events.setDirty();
                began[0] = System.nanoTime();
                started[0] = true;
            }
            if (lss != null) lss.sample();
            long took = java.util.Arrays.stream(NuclearWorld.get(level).lastNanos()).sum();
            cost[0] += took;
            cost[1] = Math.max(cost[1], took);
            ticks[0]++;
            var iterator = reads.entrySet().iterator();
            while (iterator.hasNext()) {
                var e = iterator.next();
                if (!e.getValue().isDone()) continue;
                CompoundTag tag = e.getValue().join().orElseThrow();
                if (tag.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY).getInt("incoming:chunk_scar") >= d.id()) {
                    confirmed[0]++;
                    if (lss != null) lss.saved(e.getKey());
                } else remaining.addLast(e.getKey());
                iterator.remove();
            }
            while (reads.size() < 4 && !remaining.isEmpty()) {
                ChunkPos pos = remaining.removeFirst();
                reads.put(pos, level.getChunkSource().chunkMap.read(pos));
            }
            if (confirmed[0] < targets.size()) return;
            Incoming.LOG.info("SETTLEBENCH chunks={} wallMs={} ticks={} nuclearTotalMs={} nuclearMeanMs={} nuclearMaxMs={}",
                    confirmed[0], (System.nanoTime() - began[0]) / 1_000_000, ticks[0], cost[0] / 1_000_000.0,
                    cost[0] / 1_000_000.0 / ticks[0], cost[1] / 1_000_000.0);
            if (lss != null) lss.report();
            h.succeed();
        });
    }

    /** A final disk chunk is requested only with a non-ticking ticket; partial disk chunks are never requested. */
    @GameTest(template = "range", batch = "nuke_settle_load", timeoutTicks = 400, skyAccess = true)
    public static void diskAdmissionNeverLoadsSynchronouslyOrGeneratesPartialChunks(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        ChunkPos pad = new ChunkPos(h.absolutePos(new BlockPos(32, 1, 32)));
        ChunkPos full = new ChunkPos(pad.x + 600, pad.z + 600);
        ChunkPos partial = new ChunkPos(full.x + 10, full.z);
        CompoundTag data = ChunkSerializer.write(level, fixture(level, full, 64));
        level.getChunkSource().chunkMap.write(full, data);
        CompoundTag unfinished = data.copy();
        unfinished.putInt("xPos", partial.x);
        unfinished.putString("Status", "minecraft:noise");
        level.getChunkSource().chunkMap.write(partial, unfinished);
        NuclearEvents events = new NuclearEvents();
        int id = NuclearEvents.get(level).nextId();
        events.add(new Detonation(id, new Vec3(full.getMiddleBlockX(), 65, full.getMiddleBlockZ()),
                65, 7, true, -10000, 0, 0, 20000, 5, 0.1f, false, 1));
        RuinProgress.Pass p = RuinProgress.get(level).pass(id);
        p.cursor = Long.MAX_VALUE; // restored scan: only these two unconfirmed positions remain
        p.pending.add(full.toLong());
        p.pending.add(partial.toLong());
        RuinSettle settle = new RuinSettle();
        WorkClock clock = WorkClock.counting(1_000_000);
        boolean[] inside = {false}, admitted = {false};
        int[] syncLoads = {0};
        java.util.function.Consumer<net.neoforged.neoforge.event.level.ChunkEvent.Load> listener = e -> {
            if (inside[0] && e.getLevel() == level) syncLoads[0]++;
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
        StrikeGameTests.afterTest(h, () -> {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
            settle.clear(level);
            NuclearWorld.get(level).clear(level);
        });
        h.onEachTick(() -> {
            clock.start(3_000_000);
            inside[0] = true;
            try { settle.work(level, events, clock); } finally { inside[0] = false; }
            h.assertTrue(syncLoads[0] == 0, "settlement synchronously loaded a chunk");
            h.assertTrue(clock.usedThisTickNanos() <= 3_000_000, "ticket admission bypassed budget");
            if (settle.held() > 0) admitted[0] = true;
            if (!admitted[0] || p.pending.contains(partial.toLong())) return;
            h.assertTrue(NuclearTickets.inMemory(level, partial.toLong()) == null, "partial chunk loaded by settlement");
            settle.clear(level);
            h.assertTrue(settle.held() == 0, "shutdown retained loading ticket");
            h.succeed();
        });
    }

    /** Admission and polling share the counting deadline; even an exhausted lane performs no unbounded scan. */
    @GameTest(template = "range", batch = "nuke_settle_budget", timeoutTicks = 100, skyAccess = true)
    public static void admissionUsesSharedBudgetAndStopsCleanly(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        RuinSettle settle = new RuinSettle();
        NuclearEvents events = new NuclearEvents();
        int id = NuclearEvents.get(level).nextId();
        events.add(new Detonation(id, new Vec3(80000, 64, 80000), 64, 7, true, -10000, 0, 0, 20000, 3, 0.03f, false, 1));
        WorkClock clock = WorkClock.counting(1_000_000);
        RuinProgress.Pass p = RuinProgress.get(level).pass(id);
        int[] ticks = {0};
        h.onEachTick(() -> {
            long before = p.cursor;
            clock.start(3_000_000);
            settle.work(level, events, clock);
            h.assertTrue(clock.usedThisTickNanos() <= 3_000_000, "settlement exceeded common budget");
            h.assertTrue(p.cursor - before <= 3, "scan bypassed work clock");
            h.assertTrue(settle.held() <= RuinSettle.PENDING && settle.reads() <= RuinSettle.READS, "unbounded loading or reads");
            h.assertTrue(p.pending.size() <= RuinSettle.PENDING, "unbounded saved backlog");
            if (++ticks[0] < 40) return;
            h.assertTrue(p.cursor > 0, "budget check never reached scan admission");
            settle.clear(level);
            h.assertTrue(settle.held() == 0 && settle.reads() == 0, "stop retained tickets or work");
            h.succeed();
        });
    }
}
