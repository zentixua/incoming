package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;

/** The actual dense-plan/material/support path, with one unresolved contact retaining provenance. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinSupportCapacityGameTests {
    private RuinSupportCapacityGameTests() {}
    private static final int[] DENSITY = {22_054, 14_820, 9_214};
    // Both versions can publish the 22,054-cell seed plan. Once its 4 KiB/source credit has
    // been spent, the old historical charge leaves less than the next 14,820-cell debit.
    // This is fixed across the baseline/candidate, not fitted from either retained counter.
    private static final long COMPETING = 344L << 20;

    private record Dense(LevelChunk chunk, long[] positions, BlockPos barrel) {}

    @GameTest(template = "range", batch = "nuke_support_capacity", timeoutTicks = 240, skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void consumedSourcesFundEveryDenseWatchedPlanBeforeSupportDrains(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        NuclearStrikes.clear(level);
        RuinMatterGameTests.clearFixture(level);
        var world = NuclearWorld.get(level);
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER));
        long previous = other.materialReservation, time = level.getGameTime();
        var clock = WorkClock.counting(1_000_000);
        clock.start(4_000_000); world.tick(level, clock);
        RuinWorkers.shutdown();
        StrikeGameTests.afterTest(h, () -> {
            other.materialReservation = previous;
            ScarQueue.seenForTest(null);
            NuclearStrikes.clear(level);
            RuinMatterGameTests.clearFixture(level);
        });
        ChunkPos centre = new ChunkPos(h.absolutePos(new BlockPos(32, 1, 32)));
        for (int z = -2; z <= 2; z++) for (int x = -2; x <= 4; x++) level.getChunk(centre.x + x, centre.z + z);
        int bottom = h.absolutePos(new BlockPos(0, 6, 0)).getY();
        var dense = new ArrayList<Dense>();
        for (int c = 0; c < DENSITY.length; c++) {
            ChunkPos pos = new ChunkPos(centre.x + c, centre.z);
            long[] positions = new long[DENSITY[c]];
            for (int i = 0; i < positions.length; i++) {
                BlockPos at = new BlockPos(pos.getMinBlockX() + (i & 15), bottom + (i >> 8), pos.getMinBlockZ() + ((i >> 4) & 15));
                positions[i] = at.asLong();
                level.setBlock(at, Blocks.STONE_BRICKS.defaultBlockState(), 18);
            }
            BlockPos barrel = BlockPos.of(positions[positions.length / 2]);
            level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 18);
            ((BarrelBlockEntity) level.getBlockEntity(barrel)).setItem(0, new ItemStack(Items.DIAMOND, 7));
            dense.add(new Dense(level.getChunkSource().getChunkNow(pos.x, pos.z), positions, barrel));
        }
        var d = new Detonation(NuclearEvents.get(level).nextId(),
                new Vec3(centre.getMiddleBlockX(), bottom + 120, centre.getMiddleBlockZ()), bottom,
                15, false, time - 20000, 0, 0, 20000, 42, 1, false, .25f, 6000, false);
        NuclearEvents.get(level).add(d);
        var ctx = world.ruins(level, d, time);
        var plans = new RetainedPlans();
        for (Dense f : dense) {
            var grid = ctx.grid(level, f.chunk.getPos(), false);
            h.assertTrue(grid != null, "Dense source window missing");
            var sections = new RuinPlanner.Sections(grid.at(0, 0), grid.view());
            for (long position : f.positions) {
                BlockPos at = BlockPos.of(position);
                sections.set(at.getX() & 15, at.getY(), at.getZ() & 15, Blocks.AIR.defaultBlockState());
            }
            var plan = sections.finish(ctx, new long[0], null);
            plan.material(f.positions);
            h.assertTrue(plan.changedBlocks() == f.positions.length, "Dense encoder lost removals");
            plans.put(f.chunk.getPos().toLong(), plan);
        }
        ((Map<Integer, RetainedPlans>) field(world.scars(), "prepared").get(world.scars())).put(d.id(), plans);
        var keys = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (Dense f : dense) keys.add(f.chunk.getPos().toLong());
        ScarQueue.seenForTest(keys::contains);

        // A real removed boundary support leaves an unknown face. Its asynchronous ticket cannot
        // promote a chunk during this synchronous sequence of bounded work invocations. Keep that
        // source/provenance, rather than clearing maps to manufacture the memory release under test.
        ChunkPos cold = new ChunkPos(centre.x + 128, centre.z + 128);
        level.getChunk(cold.x, cold.z);
        BlockPos source = new BlockPos(cold.getMaxBlockX(), bottom + 5, cold.getMaxBlockZ());
        level.setBlock(source, Blocks.STONE_BRICKS.defaultBlockState(), 18);
        var piece = RuinMatter.capture(level, source, level.getBlockState(source), d);
        h.assertTrue(piece != null, "Boundary support was not captured");
        level.setBlock(source, Blocks.AIR.defaultBlockState(), 18);
        var ledger = RuinMatter.get(level);
        ledger.release(List.of(piece)); ledger.support(level, List.of(piece), d);
        other.materialReservation = previous + COMPETING;
        long fixed = other.materialReservation;
        h.assertTrue(NuclearPrep.residentMemory(level) + ledger.removalBytes(DENSITY[0]) + (1L << 20) < NuclearPrep.PLANS_CAP,
                "Fixture does not fund the initial real dense publication");
        world.scars().offer(dense.getFirst().chunk, d);
        clock.start(1_000_000); world.scars().work(level, time, clock);
        h.assertTrue(!world.scars().queued(dense.getFirst().chunk.getPos().toLong()), "Warm dense plan did not apply");
        long chargedBefore = ledger.retainedBytes();
        int expanded = 0;
        var sources = (RuinRecords<?>) field(ledger, "supportSources").get(ledger);
        while (!sources.isEmpty() && expanded++ < 2000) {
            clock.start(1_000_000); ledger.work(level, clock, 1);
            h.assertTrue(NuclearPrep.residentMemory(level) <= NuclearPrep.PLANS_CAP, "Source expansion exceeded its existing credit");
        }
        h.assertTrue(sources.isEmpty() && ledger.pendingSupport() > 0,
                "Fixture must consume sources while support and provenance remain pending");
        var originals = (Map<?, ?>) field(ledger, "originals").get(ledger);
        h.assertTrue(originals.size() >= DENSITY[0], "Pending provenance was discarded");
        Incoming.LOG.info("SUPPORT_CAPACITY expanded {} units, ledger {} -> {}, original {}, support {}, arrays {}",
                expanded, chargedBefore, ledger.retainedBytes(), originals.size(), ledger.pendingSupport(), backing(ledger));
        for (int i = 1; i < dense.size(); i++) world.scars().offer(dense.get(i).chunk, d);
        int ticks = 0;
        try {
            for (; ticks < 120 && dense.stream().anyMatch(f -> world.scars().queued(f.chunk.getPos().toLong())); ticks++) {
                ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(time + ticks + 1);
                clock.start(4_000_000); world.tick(level, clock);
                h.assertTrue(other.materialReservation == fixed, "Competing ownership changed");
                h.assertTrue(NuclearPrep.residentMemory(level) <= NuclearPrep.PLANS_CAP, "Dense consumer exceeded the shared cap");
            }
        } finally { ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(time); }
        for (Dense f : dense) Incoming.LOG.info("SUPPORT_CAPACITY consumer {} queued {} state {} scar {} retained {}",
                f.chunk.getPos(), world.scars().queued(f.chunk.getPos().toLong()),
                world.scars().diagState(f.chunk.getPos().toLong(), time + ticks),
                f.chunk.getExistingData(ua.zentix.incoming.registry.ModAttachments.CHUNK_SCAR).orElse(0), NuclearPrep.residentMemory(level));
        h.assertTrue(dense.stream().noneMatch(f -> world.scars().queued(f.chunk.getPos().toLong())),
                "Dense watched work remains after spent source credit");
        long total = 1;
        for (Dense f : dense) {
            total += f.positions.length;
            h.assertTrue(f.chunk.getExistingData(ua.zentix.incoming.registry.ModAttachments.CHUNK_SCAR).orElse(0) == d.id(),
                    "Watched chunk was removed without its applied scar");
            // Material can already land in a cleared source cell. The retained original journal
            // records each actual removal, whereas testing the final block type confuses a deposit
            // with an untouched source. Also require the top of each removed column to be open.
            for (long position : f.positions) h.assertTrue(originals.containsKey(position), "Dense source has no removal provenance");
            h.assertTrue(level.getBlockState(BlockPos.of(f.positions[f.positions.length - 1])).isAir(),
                    "Dense column survived queue completion");
        }
        h.assertTrue(ledger.released() == total && ledger.pendingSupport() > 0, "Material duplicated/lost or support drained before completion");
        CompoundTag expected = ledger.save(new CompoundTag(), level.registryAccess());
        h.assertTrue(ledger.released() == ledger.deposited() + ledger.pending() + expected.getLong("dispersed"), "Material count is not conserved");
        h.assertTrue(expected.getList("moving", Tag.TAG_COMPOUND).stream().filter(t -> ((CompoundTag) t).contains("contents")).count() == 3,
                "Dense capture lost live barrel cargo");
        Path file = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data/support-capacity-" + UUID.randomUUID() + ".dat");
        ledger.save(file.toFile(), level.registryAccess());
        Incoming.LOG.info("SUPPORT_CAPACITY all {} dense watched plans / {} cells applied in {} ticks, fixed {}, retained {}",
                dense.size(), total, ticks, fixed, NuclearPrep.residentMemory(level));
        var reloading = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<RuinMatter>>();
        h.succeedWhen(() -> {
            h.assertTrue(ledger.writable(), "Real ledger save is pending");
            if (reloading.get() == null) reloading.set(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    var measured = LedgerScan.measure(file, "incoming_ruin_matter", java.util.Set.of(), NuclearPrep.PLANS_CAP - COMPETING);
                    if (measured.owned() > NuclearPrep.PLANS_CAP - COMPETING)
                        throw new java.io.IOException("Saved ownership does not fit the same fixed competing owner");
                    return (RuinMatter) LedgerReader.read(file, "incoming_ruin_matter", level.registryAccess(), measured.row(), java.util.Set.of());
                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            }, net.minecraft.Util.ioPool()));
            var read = reloading.get();
            h.assertTrue(read.isDone(), "Real streaming ledger import is pending");
            h.assertFalse(read.isCompletedExceptionally(), "Streaming import failed: "
                    + (read.isCompletedExceptionally() ? read.exceptionNow() : ""));
            var restored = read.join();
            h.assertTrue(normalized(restored.save(new CompoundTag(), level.registryAccess())).equals(normalized(expected)),
                    "Real save/reload changed complete material, cargo or support ownership");
            h.assertTrue(restored.retainedBytes() < NuclearPrep.PLANS_CAP - COMPETING,
                    "Restored live payload no longer fits the same competing ownership");
            restored.unload(level);
        });
    }

    private static CompoundTag normalized(CompoundTag tag) {
        var result = tag.copy();
        for (String key : List.of("materials", "support_originals", "cold_edges")) {
            List<Tag> rows = new ArrayList<>(result.getList(key, Tag.TAG_COMPOUND));
            String id = key.equals("materials") ? "block" : "position";
            rows.sort(java.util.Comparator.comparing(row -> ((CompoundTag) row).get(id).toString()));
            var ordered = new ListTag(); ordered.addAll(rows); result.put(key, ordered);
        }
        return result;
    }

    private static long backing(RuinMatter ledger) throws ReflectiveOperationException {
        long bytes = 0;
        for (String name : List.of("supports", "uncertain", "originals")) {
            Object map = field(ledger, name).get(ledger);
            for (String array : List.of("key", "value", "link")) {
                Field f;
                try { f = field(map, array); } catch (NoSuchFieldException absent) { continue; }
                Object values = f.get(map);
                bytes += 32L + java.lang.reflect.Array.getLength(values) * (values instanceof int[] ? 4L : 8L);
            }
        }
        return bytes;
    }

    private static Field field(Object owner, String name) throws NoSuchFieldException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { Field value = type.getDeclaredField(name); value.setAccessible(true); return value; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
