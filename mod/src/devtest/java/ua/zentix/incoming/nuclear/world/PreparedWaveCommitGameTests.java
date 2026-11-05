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
import ua.zentix.incoming.gametest.StrikeGameTests;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Native wave-to-page-to-world ownership; not a whole-strike performance or restart acceptance. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedWaveCommitGameTests {
    @GameTest(template = "range", batch = "nuke_prepared_wave_commit", timeoutTicks = 200, skyAccess = true)
    public static void onePinnedDestinationPageOwnsSourcesWhileOtherPagesWait(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        BlockPos anchor = h.absolutePos(new BlockPos(20, 64, 20));
        BlockPos base = new BlockPos((anchor.getX() & ~15) + 4, (anchor.getY() & ~15) + 8, (anchor.getZ() & ~15) + 5);
        BlockPos chest = base, burning = base.offset(2, 0, 0), delayed = base.offset(4, 0, 0),
                edited = base.offset(6, 0, 0), soil = base.offset(0, 0, 2);
        BlockState air = Blocks.AIR.defaultBlockState(), stone = Blocks.STONE.defaultBlockState(), barrel = Blocks.BARREL.defaultBlockState();
        var changes = List.of(change(chest, barrel, air), change(burning, stone, air), change(delayed, barrel, air),
                change(edited, stone, air), change(soil, Blocks.DIRT.defaultBlockState(), Blocks.COARSE_DIRT.defaultBlockState()));
        for (var cell : changes) level.setBlock(cell.pos(), cell.expected(), 2);
        level.setBlock(burning.below(), stone, 2);
        ((Container) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 3));
        ((Container) level.getBlockEntity(delayed)).setItem(0, new ItemStack(Items.EMERALD, 11));
        LevelChunk chunk = level.getChunkSource().getChunkNow(base.getX() >> 4, base.getZ() >> 4);
        BlockPos toA = chest.offset(16, -4, 0), toB = delayed.offset(32, -4, 0);
        level.getChunk(toA.getX() >> 4, toA.getZ() >> 4);
        level.getChunk(toB.getX() >> 4, toB.getZ() >> 4);
        var event = StructuralRuinGameTests.event(2);
        var preparation = UUID.randomUUID();
        var a = new PreparedMaterialBatch(preparation, new ChunkPos(toA), List.of(
                move(chest, toA, barrel, 100), move(burning, toA.offset(2, 0, 0), stone, 100),
                move(edited, toA.offset(6, 0, 0), stone, 100)));
        var b = new PreparedMaterialBatch(preparation, new ChunkPos(toB), List.of(move(delayed, toB, barrel, 120)));
        var beforeBinding = a.snapshot();
        var plan = plan(chunk, changes, burning, new long[]{chest.asLong(), burning.asLong(), delayed.asLong(), edited.asLong()});
        var budget = new ColumnScar.Budget(true);
        var completed = new BitSet(4096);
        int section = chunk.getSectionIndex(base.getY());
        var store = new PreparedMaterialStore(new RegionStorageInfo("gametest", level.dimension(), "incoming_wave_commit"),
                level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/wave-commit-" + UUID.randomUUID()),
                new PreparedMaterialStore.Limits(1, 1, 1, 1, 1L << 20));
        StrikeGameTests.afterTest(h, () -> store.close());
        var keyA = PreparedMaterialStore.Key.of(a);
        var keyB = PreparedMaterialStore.Key.of(b);
        // Actual edits after planning: contents travel; a replaced source remains the player's block.
        ((Container) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 7));
        level.setBlock(edited, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
        var absent = plan.bindSection(chunk, event, budget, section);
        var noOwner = new PreparedSectionWriter.Receipt(absent.changes().size());
        PreparedMaterialCommit.waveDepart(level, chunk, absent, null, new int[0], 100, noOwner, () -> true);
        collect(absent, noOwner, completed);
        h.assertTrue(level.getBlockState(chest) == barrel && level.getBlockState(burning) == stone
                        && level.getBlockState(delayed) == barrel && level.getBlockState(soil).is(Blocks.COARSE_DIRT),
                "Missing material pages blocked independent soil work or allowed unowned removal");
        int[] step = {0};
        h.succeedWhen(() -> {
            if (step[0] == 0) {
                h.assertTrue(store.request(keyA).state() == PreparedMaterialStore.State.ABSENT, "Waiting for first page absence");
                h.assertTrue(store.admit(a), "First page admission failed");
                var binding = plan.bindSection(chunk, event, budget, section, local -> !completed.get(local));
                var receipt = new PreparedSectionWriter.Receipt(binding.changes().size());
                try (var lease = store.pin(keyA)) {
                    h.assertTrue(lease != null, "First source owner was not pinned");
                    PreparedMaterialCommit.waveDepart(level, chunk, binding, lease, new int[]{0, 1, 2}, 100, receipt, () -> true);
                }
                collect(binding, receipt, completed);
                h.assertTrue(level.getBlockState(chest).isAir() && level.getBlockState(burning).is(Blocks.FIRE)
                                && level.getBlockState(delayed) == barrel && level.getBlockState(edited).is(Blocks.DIAMOND_BLOCK),
                        "Wave did not limit source writes to current admitted material owners");
                h.assertTrue(a.ownsMaterial(0) && a.ownsMaterial(1) && a.phase(2) == PreparedMaterialBatch.Phase.SOURCE_CHANGED
                                && a.move(1).sourceAfter().is(Blocks.FIRE), "Wave receipt did not bind exact replacement/ownership");
                h.assertTrue(PreparedMaterialBatch.decode(beforeBinding.encode()).move(1).sourceAfter().isAir(),
                        "Rebinding changed an already detached page snapshot");
                h.assertTrue(budget.firesForTest() == 1, "Wave material fire was charged more than once");
                store.save(keyA); step[0] = 1;
            }
            if (step[0] == 1) {
                h.assertTrue(store.request(keyB).state() == PreparedMaterialStore.State.ABSENT, "Waiting for second page with one hot slot");
                h.assertTrue(store.admit(b), "Second page admission failed");
                for (long tick : new long[]{119, 120}) {
                    var binding = plan.bindSection(chunk, event, budget, section, local -> !completed.get(local));
                    var receipt = new PreparedSectionWriter.Receipt(binding.changes().size());
                    try (var lease = store.pin(keyB)) {
                        PreparedMaterialCommit.waveDepart(level, chunk, binding, lease, new int[]{0}, tick, receipt, () -> true);
                    }
                    collect(binding, receipt, completed);
                    h.assertTrue(tick == 119 ? level.getBlockState(delayed) == barrel : level.getBlockState(delayed).isAir(),
                            "Source ignored its causal departure time");
                }
                store.save(keyB); step[0] = 2;
            }
            if (step[0] == 2) {
                h.assertTrue(store.request(keyA).state() == PreparedMaterialStore.State.READY, "Waiting for first destination page reload");
                try (var lease = store.pin(keyA)) {
                    var restored = lease.batch();
                    h.assertTrue(restored.move(1).sourceAfter().is(Blocks.FIRE) && restored.ownsMaterial(0),
                            "Cold page lost bound fire replacement or cargo owner");
                    h.assertTrue(PreparedMaterialCommit.land(level, restored, new int[]{0, 1, 2}, 1000, false) == 0,
                            "Material overwrote a destination with an unapplied scar");
                    h.assertTrue(PreparedMaterialCommit.land(level, restored, new int[]{0, 1, 2}, 1000, true) == 2,
                            "Ready destination did not receive its two saved blocks");
                    var contents = ((Container) level.getBlockEntity(toA)).getItem(0);
                    h.assertTrue(contents.is(Items.DIAMOND) && contents.getCount() == 7 && restored.finished(),
                            "Material placement lost edited cargo or terminal source receipt");
                }
                store.save(keyA); step[0] = 3;
            }
            h.assertTrue(store.request(keyB).state() == PreparedMaterialStore.State.READY, "Waiting for second destination page reload");
            try (var lease = store.pin(keyB)) {
                h.assertTrue(PreparedMaterialCommit.land(level, lease.batch(), new int[]{0}, 1000, true) == 1,
                        "Second destination did not receive its material");
                var contents = ((Container) level.getBlockEntity(toB)).getItem(0);
                h.assertTrue(contents.is(Items.EMERALD) && contents.getCount() == 11 && lease.batch().finished(),
                        "Second page lost exact contents");
            }
            h.assertTrue(budget.firesForTest() == 1, "Retry recounted a completed fire cell");
        });
    }

    private static void collect(RuinPlan.WaveSection binding, PreparedSectionWriter.Receipt receipt, BitSet completed) {
        for (int i = 0; i < binding.changes().size(); i++) if (receipt.applied(i)) completed.set(local(binding.changes().get(i).pos()));
    }

    private static int local(BlockPos p) { return (p.getY() & 15) << 8 | (p.getZ() & 15) << 4 | p.getX() & 15; }

    private static PreparedMaterialBatch.Move move(BlockPos from, BlockPos to, BlockState state, long tick) {
        return new PreparedMaterialBatch.Move(from, to, state, state, tick, tick + 40, 1, 600);
    }

    private static PreparedSectionWriter.CellChange change(BlockPos p, BlockState before, BlockState after) {
        return new PreparedSectionWriter.CellChange(p, before, after);
    }

    private static RuinPlan plan(LevelChunk chunk, List<PreparedSectionWriter.CellChange> changes, BlockPos fire, long[] material) {
        var sorted = new ArrayList<>(changes);
        sorted.sort(Comparator.comparingInt(c -> local(c.pos())));
        var palette = new LinkedHashMap<BlockState, Integer>();
        for (var change : sorted) palette.computeIfAbsent(change.state(), ignored -> palette.size());
        palette.put(Blocks.FIRE.defaultBlockState(), palette.size());
        int[] cells = new int[sorted.size()];
        BlockState[] olds = new BlockState[cells.length];
        long[] hashes = new long[chunk.getSections().length];
        for (int i = 0; i < cells.length; i++) {
            var change = sorted.get(i);
            int section = chunk.getSectionIndex(change.pos().getY());
            cells[i] = section << RuinPlan.SECTION_SHIFT | local(change.pos()) | palette.get(change.state()) << RuinPlan.STATE_SHIFT
                    | (Blast.props(change.expected()).is(Blast.Props.SLOW) ? RuinPlan.SLOW : 0);
            olds[i] = change.expected();
            hashes[section] = RuinPlan.mix(hashes[section], change.expected());
        }
        int burning = chunk.getSectionIndex(fire.getY()) << RuinPlan.SECTION_SHIFT | local(fire)
                | palette.get(Blocks.FIRE.defaultBlockState()) << RuinPlan.STATE_SHIFT;
        var plan = new RuinPlan(cells, hashes, palette.keySet().toArray(BlockState[]::new), olds, new int[]{burning},
                new int[0], new long[0], null, null, null, null, null, new long[0], null);
        plan.material(material);
        return plan;
    }

    private PreparedWaveCommitGameTests() {}
}
