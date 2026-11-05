package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Section consumer timing, partial ownership and explicit no-op dispositions; no scheduler acceptance claim. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedSourceCommitGameTests {
    @GameTest(template = "range", batch = "nuke_prepared_source_commit", timeoutTicks = 200, skyAccess = true)
    public static void partialSectionWaitsForOnePageAtATimeAndExplicitLocalRepair(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        BlockPos base = base(h), a = base.offset(4, 4, 4), b = base.offset(6, 4, 4),
                dirt = base.offset(4, 4, 6), edited = base.offset(6, 4, 6), upper = dirt.above(16);
        var air = Blocks.AIR.defaultBlockState();
        var barrel = Blocks.BARREL.defaultBlockState();
        var changes = List.of(change(a, barrel, air), change(b, barrel, air),
                soil(dirt), soil(edited), soil(upper));
        for (var c : changes) level.setBlock(c.pos(), c.expected(), 2);
        ((Container) level.getBlockEntity(a)).setItem(0, new ItemStack(Items.DIAMOND, 7));
        ((Container) level.getBlockEntity(b)).setItem(0, new ItemStack(Items.EMERALD, 11));
        LevelChunk chunk = level.getChunkSource().getChunkNow(base.getX() >> 4, base.getZ() >> 4);
        preserve(h, chunk);
        var d = StructuralRuinGameTests.event(2);
        var id = new PreparedSourceProgress.Identity(d.id(), UUID.randomUUID(), 8);
        var plan = plan(chunk, changes, List.of(), a, b);
        var footprint = plan.waveFootprint(chunk, d);
        var budget = new ColumnScar.Budget(false);
        int lowerY = a.getY() >> 4, upperY = upper.getY() >> 4;
        for (boolean durable : new boolean[]{false, true}) {
            var result = PreparedSourceCommit.attempt(level, chunk, d, id, plan, footprint, lowerY,
                    10, durable ? 9 : 10, budget, durable, null, new int[0], () -> true);
            h.assertTrue(result.state() == PreparedSourceCommit.State.PENDING && PreparedSourceProgress.get(chunk).isEmpty()
                    && level.getBlockState(dirt).is(Blocks.DIRT), "Source began before its due/durable gate");
        }
        level.setBlock(edited, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
        var first = PreparedSourceCommit.attempt(level, chunk, d, id, plan, footprint, lowerY,
                10, 10, budget, true, null, new int[0], () -> true);
        h.assertTrue(first.state() == PreparedSourceCommit.State.STALE && first.stale().equals(mask(edited))
                && PreparedSourceProgress.get(chunk).pending(lowerY).equals(mask(a, b, edited))
                && level.getBlockState(dirt).is(Blocks.COARSE_DIRT), "Missing pages or local edit corrupted partial completion");
        // The revised plan keeps the player's edited block; no material row ever owned that cell.
        var revised = plan(chunk, List.of(change(a, barrel, air), change(b, barrel, air), soil(dirt), soil(upper)), List.of(), a, b);
        PreparedSourceProgress.replacePending(chunk, id, lowerY, mask(a, b));
        BlockPos toA = a.offset(16, -4, 0), toB = b.offset(32, -4, 0);
        var pageA = new PreparedMaterialBatch(id.preparation(), new ChunkPos(toA), List.of(move(a, toA, 10)));
        var pageB = new PreparedMaterialBatch(id.preparation(), new ChunkPos(toB), List.of(move(b, toB, 12)));
        var store = new PreparedMaterialStore(new RegionStorageInfo("gametest", level.dimension(), "incoming_source_commit"),
                level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/source-commit-" + UUID.randomUUID()),
                new PreparedMaterialStore.Limits(1, 1, 1, 1, 1L << 20));
        StrikeGameTests.afterTest(h, store::close);
        var keyA = PreparedMaterialStore.Key.of(pageA);
        var keyB = PreparedMaterialStore.Key.of(pageB);
        int[] stage = {0};
        h.succeedWhen(() -> {
            if (stage[0] == 0) {
                h.assertTrue(store.request(keyA).state() == PreparedMaterialStore.State.ABSENT, "Waiting for first page absence");
                h.assertTrue(store.admit(pageA), "First page admission failed");
                try (var lease = store.pin(keyA)) {
                    var result = PreparedSourceCommit.attempt(level, chunk, d, id, revised, footprint, lowerY,
                            10, 10, budget, true, lease, new int[]{0}, () -> true);
                    h.assertTrue(result.state() == PreparedSourceCommit.State.PENDING && result.stale().isEmpty()
                            && level.getBlockState(a).isAir() && level.getBlockState(b).is(Blocks.BARREL)
                            && pageA.ownsMaterial(0) && PreparedSourceProgress.get(chunk).pending(lowerY).equals(mask(b)),
                            "First pinned page required the other page or lost its source receipt");
                }
                store.save(keyA);
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                h.assertTrue(store.request(keyB).state() == PreparedMaterialStore.State.ABSENT, "Waiting for second page absence");
                h.assertTrue(store.admit(pageB), "Second page admission failed");
                try (var lease = store.pin(keyB)) {
                    for (long age : new long[]{11, 12}) {
                        var result = PreparedSourceCommit.attempt(level, chunk, d, id, revised, footprint, lowerY,
                                10, age, budget, true, lease, new int[]{0}, () -> true);
                        h.assertTrue(result.state() == PreparedSourceCommit.State.PENDING && result.stale().isEmpty()
                                && (age == 11 ? level.getBlockState(b).is(Blocks.BARREL) : level.getBlockState(b).isAir()),
                                "Material departure ignored its offset or unfinished other section");
                    }
                    h.assertTrue(pageB.ownsMaterial(0), "Second page lost captured source ownership");
                }
                var result = PreparedSourceCommit.attempt(level, chunk, d, id, revised, footprint, upperY,
                        20, 19, budget, true, null, new int[0], () -> true);
                h.assertTrue(result.state() == PreparedSourceCommit.State.PENDING && chunk.getData(ModAttachments.CHUNK_SCAR) < d.id(),
                        "Chunk completed before the upper section's wave");
                result = PreparedSourceCommit.attempt(level, chunk, d, id, revised, footprint, upperY,
                        20, 20, budget, true, null, new int[0], () -> true);
                h.assertTrue(result.state() == PreparedSourceCommit.State.COMPLETE && PreparedSourceProgress.get(chunk).isEmpty()
                        && chunk.getData(ModAttachments.CHUNK_SCAR) == d.id() && level.getBlockState(edited).is(Blocks.DIAMOND_BLOCK),
                        "Final source completion lost repair or failed to retire its masks");
                chunk.setData(ModAttachments.CHUNK_SCAR, d.id() + 1);
                result = PreparedSourceCommit.attempt(level, chunk, d, id, revised, footprint, lowerY,
                        10, 20, budget, true, null, new int[0], () -> true);
                h.assertTrue(result.state() == PreparedSourceCommit.State.COMPLETE && chunk.getData(ModAttachments.CHUNK_SCAR) == d.id() + 1,
                        "Completed retry replayed sources or regressed a later watermark");
                stage[0] = 2;
            }
        });
    }

    @GameTest(template = "range", batch = "nuke_prepared_source_commit_exception", timeoutTicks = 100, skyAccess = true)
    public static void callbackAdmissionFailureKeepsConfirmedOrdinaryCells(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos base = base(h), dirt = base.offset(4, 4, 4), barrel = base.offset(6, 4, 4);
        var changes = List.of(soil(dirt), change(barrel, Blocks.BARREL.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        for (var c : changes) level.setBlock(c.pos(), c.expected(), 2);
        LevelChunk chunk = level.getChunkSource().getChunkNow(base.getX() >> 4, base.getZ() >> 4);
        preserve(h, chunk);
        var d = StructuralRuinGameTests.event(2);
        var id = new PreparedSourceProgress.Identity(d.id(), UUID.randomUUID(), 1);
        var plan = plan(chunk, changes, List.of(), barrel);
        var footprint = plan.waveFootprint(chunk, d);
        var budget = new ColumnScar.Budget(false);
        RuntimeException injected = new IllegalStateException("Injected source callback admission failure");
        try {
            PreparedSourceCommit.attempt(level, chunk, d, id, plan, footprint, dirt.getY() >> 4,
                    0, 0, budget, true, null, new int[0], () -> { throw injected; });
            h.fail("Fixture did not reach callback admission");
        } catch (RuntimeException error) {
            if (error != injected) throw error;
        }
        h.assertTrue(level.getBlockState(dirt).is(Blocks.COARSE_DIRT) && level.getBlockState(barrel).is(Blocks.BARREL)
                && PreparedSourceProgress.get(chunk).pending(dirt.getY() >> 4).equals(mask(barrel))
                && chunk.getData(ModAttachments.CHUNK_SCAR) < d.id(), "Exceptional attempt lost confirmed receipts or finished pending work");
        chunk.setData(ModAttachments.CHUNK_SCAR, d.id() + 1);
        try {
            PreparedSourceCommit.attempt(level, chunk, d, id, plan, footprint, dirt.getY() >> 4,
                    0, 0, budget, true, null, new int[0], () -> true);
            h.fail("Later scar hid an unfinished source owner");
        } catch (IllegalStateException expected) {
            h.assertTrue(expected.getMessage().contains("overtook"), "Unexpected inconsistent-owner failure");
        }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_prepared_source_commit_noop", timeoutTicks = 100, skyAccess = true)
    public static void evaluatedFireAndQuenchNoOpsFinishWithoutErasingPlayerEdits(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos base = base(h), capped = base.offset(4, 4, 4), occupied = base.offset(6, 4, 4), gone = base.offset(8, 4, 4);
        LevelChunk chunk = level.getChunkSource().getChunkNow(base.getX() >> 4, base.getZ() >> 4);
        preserve(h, chunk);
        var d = StructuralRuinGameTests.event(2);
        StrikeGameTests.afterTest(h, () -> FlashScorch.unmark(chunk, d.id()));
        for (boolean enabled : new boolean[]{true, false}) {
            chunk.setData(ModAttachments.CHUNK_SCAR, 0);
            for (BlockPos pos : List.of(capped, occupied, gone)) level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(capped, Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(occupied, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
            level.setBlock(gone, Blocks.FIRE.defaultBlockState(), 2);
            FlashScorch.mark(chunk, d.id());
            var plan = plan(chunk, List.of(), List.of(capped, occupied));
            var footprint = plan.waveFootprint(chunk, d);
            h.assertTrue(footprint.get(gone.getY() >> 4).get(PreparedSourceProgress.local(gone)), "Initial footprint omitted restarted flash fire");
            level.setBlock(gone, Blocks.AIR.defaultBlockState(), 2);
            var id = new PreparedSourceProgress.Identity(d.id(), UUID.randomUUID(), 1);
            var budget = new ColumnScar.Budget(enabled);
            budget.fires = IncomingConfig.SERVER.nukeMaxFires.get();
            var result = PreparedSourceCommit.attempt(level, chunk, d, id, plan, footprint, capped.getY() >> 4,
                    0, 0, budget, true, null, new int[0], () -> true);
            h.assertTrue(result.state() == PreparedSourceCommit.State.COMPLETE && result.stale().isEmpty()
                    && level.getBlockState(capped).isAir() && level.getBlockState(occupied).is(Blocks.DIAMOND_BLOCK)
                    && PreparedSourceProgress.get(chunk).isEmpty() && !FlashScorch.marked(chunk, d.id()),
                    "Evaluated fire no-op remained pending or erased an occupied cell");
        }
        chunk.setData(ModAttachments.CHUNK_SCAR, 0);
        var unknown = PreparedSourceCommit.attempt(level, chunk, d,
                new PreparedSourceProgress.Identity(d.id(), UUID.randomUUID(), 2), RuinPlan.EMPTY,
                java.util.Map.of(gone.getY() >> 4, mask(gone)), gone.getY() >> 4,
                0, 0, new ColumnScar.Budget(false), true, null, new int[0], () -> true);
        h.assertTrue(unknown.state() == PreparedSourceCommit.State.STALE && unknown.stale().equals(mask(gone))
                && !PreparedSourceProgress.get(chunk).cellsComplete(), "Unexplained pending cell silently became a no-op");
        h.succeed();
    }

    private static PreparedMaterialBatch.Move move(BlockPos from, BlockPos to, long tick) {
        return new PreparedMaterialBatch.Move(from, to, Blocks.BARREL.defaultBlockState(), Blocks.BARREL.defaultBlockState(), tick, tick + 40, 1, 600);
    }

    private static PreparedSectionWriter.CellChange soil(BlockPos at) {
        return change(at, Blocks.DIRT.defaultBlockState(), Blocks.COARSE_DIRT.defaultBlockState());
    }

    private static PreparedSectionWriter.CellChange change(BlockPos at, BlockState before, BlockState after) {
        return new PreparedSectionWriter.CellChange(at, before, after);
    }

    private static RuinPlan plan(LevelChunk chunk, List<PreparedSectionWriter.CellChange> changes, List<BlockPos> fires, BlockPos... material) {
        var sorted = new ArrayList<>(changes);
        sorted.sort(Comparator.comparingInt(c -> key(chunk, c.pos())));
        var palette = new LinkedHashMap<BlockState, Integer>();
        for (var c : sorted) palette.computeIfAbsent(c.state(), ignored -> palette.size());
        if (!fires.isEmpty()) palette.computeIfAbsent(Blocks.FIRE.defaultBlockState(), ignored -> palette.size());
        int[] cells = new int[sorted.size()];
        BlockState[] olds = new BlockState[cells.length];
        long[] hashes = new long[chunk.getSections().length];
        for (int i = 0; i < cells.length; i++) {
            var c = sorted.get(i);
            cells[i] = key(chunk, c.pos()) | palette.get(c.state()) << RuinPlan.STATE_SHIFT
                    | (Blast.props(c.expected()).is(Blast.Props.SLOW) ? RuinPlan.SLOW : 0);
            olds[i] = c.expected();
            int section = chunk.getSectionIndex(c.pos().getY());
            hashes[section] = RuinPlan.mix(hashes[section], c.expected());
        }
        int[] burning = fires.stream().mapToInt(at -> key(chunk, at) | palette.get(Blocks.FIRE.defaultBlockState()) << RuinPlan.STATE_SHIFT).toArray();
        var plan = new RuinPlan(cells, hashes, palette.keySet().toArray(BlockState[]::new), olds, burning,
                new int[0], new long[0], null, null, null, null, null, new long[0], null);
        plan.material(java.util.Arrays.stream(material).mapToLong(BlockPos::asLong).toArray());
        return plan;
    }

    private static int key(LevelChunk chunk, BlockPos at) {
        return chunk.getSectionIndex(at.getY()) << RuinPlan.SECTION_SHIFT | PreparedSourceProgress.local(at);
    }

    private static BitSet mask(BlockPos... positions) {
        BitSet result = new BitSet(4096);
        for (BlockPos at : positions) result.set(PreparedSourceProgress.local(at));
        return result;
    }

    private static BlockPos base(GameTestHelper h) {
        BlockPos at = h.absolutePos(new BlockPos(20, 48, 20));
        return new BlockPos(at.getX() & ~15, at.getY() & ~15, at.getZ() & ~15);
    }

    private static void preserve(GameTestHelper h, LevelChunk chunk) {
        int scar = chunk.getData(ModAttachments.CHUNK_SCAR);
        var progress = PreparedSourceProgress.get(chunk);
        chunk.setData(ModAttachments.CHUNK_SCAR, 0);
        chunk.removeData(ModAttachments.PREPARED_SOURCE_PROGRESS);
        StrikeGameTests.afterTest(h, () -> {
            chunk.setData(ModAttachments.CHUNK_SCAR, scar);
            if (progress.isEmpty()) chunk.removeData(ModAttachments.PREPARED_SOURCE_PROGRESS);
            else chunk.setData(ModAttachments.PREPARED_SOURCE_PROGRESS, progress);
            chunk.setUnsaved(true);
        });
    }

    private PreparedSourceCommitGameTests() {}
}
