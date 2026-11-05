package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Real block/cargo/flash operands at the new plan-to-section ownership boundary. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedWaveBindingGameTests {
    @GameTest(template = "range", batch = "nuke_prepared_wave_binding", timeoutTicks = 100, skyAccess = true)
    public static void localEditsAndActualFlashSurvivorsBindBeforeCargoCapture(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos base = base(h), edited = base.offset(3, 4, 3), burned = base.offset(4, 4, 3),
                wet = base.offset(5, 4, 3), cargo = base.offset(6, 4, 3), changedByFlash = base.offset(7, 4, 3),
                editedAfterBinding = base.offset(8, 4, 3);
        LevelChunk chunk = level.getChunkSource().getChunkNow(base.getX() >> 4, base.getZ() >> 4);
        var event = StructuralRuinGameTests.event(2);
        var context = new RuinContext(level, event);
        BlockState air = Blocks.AIR.defaultBlockState(), stone = Blocks.STONE.defaultBlockState();
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState();
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        var cells = List.of(change(edited, stone, air), change(burned, leaf, air),
                change(wet, fence, Blocks.WATER.defaultBlockState()), change(cargo, Blocks.BARREL.defaultBlockState(), air),
                change(changedByFlash, Blocks.DIRT.defaultBlockState(), air), change(editedAfterBinding, stone, air));
        for (var cell : cells) level.setBlock(cell.pos(), cell.expected(), 2);
        // Native placement updates adjacent fence shapes. The planner's snapshot is taken after
        // construction, before flash and player edits, not from the placement request defaults.
        for (var cell : cells) if (level.getBlockState(cell.pos()) != cell.expected())
            Incoming.LOG.info("PREPARED_WAVE_FIXTURE_SHAPE pos={} requested={} actual={}", cell.pos(), cell.expected(), level.getBlockState(cell.pos()));
        cells = cells.stream().map(cell -> change(cell.pos(), level.getBlockState(cell.pos()), cell.state())).toList();
        ((Container) level.getBlockEntity(cargo)).setItem(0, new ItemStack(Items.DIAMOND, 7));
        var flash = plan(chunk, List.of(change(burned, cells.get(1).expected(), air),
                change(changedByFlash, cells.get(4).expected(), Blocks.COARSE_DIRT.defaultBlockState())), List.of(), context, new long[0], null);
        h.assertTrue(flash.applyFlash(level, chunk, event, new ColumnScar.Budget(false)) >= 0,
                "Native fixture flash did not accept its original snapshot");
        var plan = plan(chunk, cells, List.of(), context, new long[0], null);
        plan.material(cells.stream().mapToLong(c -> c.pos().asLong()).toArray());
        level.setBlock(edited, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
        Object oldLedger = level.getDataStorage().cache.get("incoming_ruin_matter");
        var binding = plan.bindSection(chunk, event, new ColumnScar.Budget(false), chunk.getSectionIndex(base.getY()));
        h.assertTrue(Arrays.equals(binding.stalePositions(), new long[]{edited.asLong()}), "Unexpected stale operands: " + Arrays.toString(binding.stalePositions()));
        int wetIndex = index(binding, wet), burnedIndex = index(binding, burned), cargoIndex = index(binding, cargo);
        h.assertTrue(binding.changes().get(wetIndex).state().is(Blocks.WATER) && binding.structural(wetIndex), "Exact wet structural replacement was lost");
        h.assertTrue(binding.changes().get(burnedIndex).expected().isAir(), "Binding resurrected a pre-flash material source");
        h.assertTrue(binding.changes().get(index(binding, changedByFlash)).expected().is(Blocks.COARSE_DIRT), "Binding did not retain actual post-flash source state");
        level.setBlock(editedAfterBinding, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
        var receipt = new PreparedSectionWriter.Receipt(binding.changes().size());
        CompoundTag[] captured = {null};
        int[] captures = {0};
        binding.apply(level, chunk, receipt, () -> true, index -> {
            if (index == cargoIndex) {
                captured[0] = level.getBlockEntity(cargo).saveWithoutMetadata(level.registryAccess());
                captures[0]++;
            }
            h.assertTrue(index != burnedIndex, "Unchanged air source requested material ownership");
            return true;
        });
        h.assertTrue(level.getBlockState(edited).is(Blocks.DIAMOND_BLOCK) && level.getBlockState(editedAfterBinding).is(Blocks.DIAMOND_BLOCK)
                        && receipt.stale(index(binding, editedAfterBinding)), "Bound write overwrote a later edit");
        h.assertTrue(receipt.applied(wetIndex) && receipt.worldWrites() == 1 && level.getBlockState(wet).is(Blocks.WATER),
                "Trusted ruin mode changed water/cargo callback routing");
        h.assertTrue(captures[0] == 1 && captured[0] != null && level.getBlockEntity(cargo) == null, "Cargo was not captured before its only source removal");
        level.setBlock(cargo, Blocks.BARREL.defaultBlockState(), 2);
        level.getBlockEntity(cargo).loadWithComponents(captured[0], level.registryAccess());
        h.assertTrue(((Container) level.getBlockEntity(cargo)).getItem(0).getCount() == 7, "Captured cargo did not retain exact contents");
        h.assertTrue(context.applied(chunk.getPos().toLong()) == null && level.getDataStorage().cache.get("incoming_ruin_matter") == oldLedger,
                "Binding silently retained an old applied-plan/material owner");
        // The two stale positions deliberately remain unrepaired: finishWave is not called.
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_prepared_wave_fire", timeoutTicks = 100, skyAccess = true)
    public static void knownAndRestartedFlashKeepCountersFinalFireAndDeferredTicks(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos base = base(h), keep = base.offset(3, 4, 5), quench = base.offset(5, 4, 5), fresh = base.offset(7, 4, 5);
        LevelChunk chunk = level.getChunkSource().getChunkNow(base.getX() >> 4, base.getZ() >> 4);
        var event = StructuralRuinGameTests.event(2);
        for (int mode = 0; mode < 3; mode++) {
            for (BlockPos at : List.of(keep, quench, fresh)) level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(keep, Blocks.FIRE.defaultBlockState(), 2);
            level.setBlock(quench, Blocks.FIRE.defaultBlockState(), 2);
            level.setBlock(fresh, Blocks.STONE.defaultBlockState(), 2);
            var context = mode == 0 ? new RuinContext(level, event) : null;
            if (context != null) context.flashed(chunk, plan(chunk, List.of(), List.of(keep, quench), context, new long[0], null), false);
            else FlashScorch.mark(chunk, event.id());
            var plan = plan(chunk, List.of(change(fresh, Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState())),
                    List.of(keep, fresh), context, new long[0], null);
            plan.material(new long[]{fresh.asLong()});
            var budget = new ColumnScar.Budget(true);
            budget.fires = mode == 2 ? 0 : 2;
            if (mode != 2) budget.layer.put(chunk.getPos().toLong(), 2);
            var binding = plan.bindSection(chunk, event, budget, chunk.getSectionIndex(base.getY()));
            int source = index(binding, fresh);
            h.assertTrue(binding.changes().get(source).state().is(Blocks.FIRE) && binding.structural(source),
                    "Final fire was not part of the source's single replacement");
            var receipt = new PreparedSectionWriter.Receipt(binding.changes().size());
            binding.apply(level, chunk, receipt, () -> true, index -> true);
            h.assertTrue(receipt.worldWrites() == 0 && level.getBlockState(keep).is(Blocks.FIRE)
                            && level.getBlockState(fresh).is(Blocks.FIRE) && level.getBlockState(quench).isAir(),
                    "Wave fire bypassed its existing bulk-write law");
            h.assertTrue(budget.firesForTest() == 2 && budget.layerForTest(chunk.getPos()) == 0,
                    "Known or restarted flash fire counter was not reconciled");
            h.assertTrue(level.getBlockTicks().hasScheduledTick(keep, Blocks.FIRE) && level.getBlockTicks().hasScheduledTick(fresh, Blocks.FIRE),
                    "Continuing fire did not receive its deferred wave-time tick");
            var completed = plan.bindSection(chunk, event, budget, chunk.getSectionIndex(base.getY()), local -> false);
            completed.apply(level, chunk, new PreparedSectionWriter.Receipt(0), () -> true, index -> true);
            h.assertTrue(budget.firesForTest() == 2, "Completed section cells were charged again on retry");
            plan.finishWave(level, chunk, event, budget);
            h.assertFalse(FlashScorch.marked(chunk, event.id()), "Finished wave retained its flash-layer mark");
            if (context != null) h.assertTrue(context.applied(chunk.getPos().toLong()) == null, "Finish retained a historical Applied plan");
        }
        // A forgotten layer split over sections must not lend the upper live fire's count
        // to a new lower fire before that upper section is serviced.
        for (BlockPos at : List.of(keep, quench, fresh)) level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
        BlockPos upper = keep.above(16);
        level.setBlock(upper.below(), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(keep, Blocks.FIRE.defaultBlockState(), 2);
        level.setBlock(upper, Blocks.FIRE.defaultBlockState(), 2);
        level.setBlock(fresh, Blocks.STONE.defaultBlockState(), 2);
        FlashScorch.mark(chunk, event.id());
        var capped = new ColumnScar.Budget(true);
        capped.fires = ua.zentix.incoming.IncomingConfig.SERVER.nukeMaxFires.get();
        capped.layer.put(chunk.getPos().toLong(), 2);
        var split = plan(chunk, List.of(change(fresh, Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState())),
                List.of(keep, upper, fresh), null, new long[0], null);
        var lower = split.bindSection(chunk, event, capped, chunk.getSectionIndex(keep.getY()));
        lower.apply(level, chunk, new PreparedSectionWriter.Receipt(lower.changes().size()), () -> true, index -> true);
        h.assertTrue(level.getBlockState(fresh).isAir() && level.getBlockState(upper).is(Blocks.FIRE)
                        && capped.layerForTest(chunk.getPos()) == 1
                        && capped.firesForTest() == ua.zentix.incoming.IncomingConfig.SERVER.nukeMaxFires.get(),
                "Partial section borrowed the cap slot of an unprocessed flash fire");
        var higher = split.bindSection(chunk, event, capped, chunk.getSectionIndex(upper.getY()));
        higher.apply(level, chunk, new PreparedSectionWriter.Receipt(higher.changes().size()), () -> true, index -> true);
        split.finishWave(level, chunk, event, capped);
        h.assertTrue(capped.layerForTest(chunk.getPos()) == 0, "Completed upper section retained its count slot");

        // Previewing a quench is insufficient: a source admission can still refuse that cell.
        level.setBlock(keep, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(upper, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(quench, Blocks.FIRE.defaultBlockState(), 2);
        level.setBlock(fresh, Blocks.STONE.defaultBlockState(), 2);
        var known = new RuinContext(level, event);
        known.flashed(chunk, plan(chunk, List.of(), List.of(quench), known, new long[0], null), false);
        var refused = plan(chunk, List.of(change(quench, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()),
                        change(fresh, Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState())),
                List.of(fresh), known, new long[0], null);
        var refusedBudget = new ColumnScar.Budget(true);
        refusedBudget.fires = ua.zentix.incoming.IncomingConfig.SERVER.nukeMaxFires.get();
        var attempt = refused.bindSection(chunk, event, refusedBudget, chunk.getSectionIndex(base.getY()));
        int quenchIndex = index(attempt, quench), freshIndex = index(attempt, fresh);
        var receipt = new PreparedSectionWriter.Receipt(attempt.changes().size());
        attempt.apply(level, chunk, receipt, () -> true, index -> index != quenchIndex);
        h.assertTrue(!receipt.attempted(quenchIndex) && !receipt.attempted(freshIndex)
                        && level.getBlockState(quench).is(Blocks.FIRE) && level.getBlockState(fresh).is(Blocks.STONE)
                        && refusedBudget.firesForTest() == ua.zentix.incoming.IncomingConfig.SERVER.nukeMaxFires.get(),
                "Uncommitted quench financed a fresh fire beyond the cap");
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_prepared_wave_ancillary", timeoutTicks = 100, skyAccess = true)
    public static void biomesPrecedeSourceWritesAndFluidsFollowCompletion(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos base = base(h), wet = base.offset(6, 5, 6);
        LevelChunk chunk = level.getChunkSource().getChunkNow(base.getX() >> 4, base.getZ() >> 4);
        int section = chunk.getSectionIndex(base.getY());
        var plains = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        chunk.getSection(section).fillBiomesFromNoise((x, y, z, sampler) -> plains, null, 0, 0, 0);
        level.setBlock(wet, Blocks.STONE.defaultBlockState(), 2);
        byte[] tiers = new byte[16]; Arrays.fill(tiers, (byte) 2);
        int[] from = new int[16]; Arrays.fill(from, base.getY());
        var plan = plan(chunk, List.of(change(wet, Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState())),
                List.of(), null, new long[]{wet.asLong()}, new ScorchedBiomes.Cells(tiers, from));
        var event = StructuralRuinGameTests.event(2);
        var motion = plan.beginWave(level, chunk);
        h.assertTrue(motion.length == 256 && chunk.getSection(section).getNoiseBiome(0, 0, 0).is(ScorchedBiomes.LEVELS.get(1)),
                "Biome precursor was not published before block changes");
        var budget = new ColumnScar.Budget(false);
        var binding = plan.bindSection(chunk, event, budget, section);
        var receipt = new PreparedSectionWriter.Receipt(binding.changes().size());
        binding.apply(level, chunk, receipt, () -> true, index -> true);
        h.assertTrue(receipt.applied(0) && receipt.fastWrites() == 1 && level.getBlockState(wet).is(Blocks.WATER),
                "Final water delta did not use the original fast ruin path");
        h.assertFalse(level.getFluidTicks().hasScheduledTick(wet, Fluids.WATER), "Bulk source write woke water before the completion phase");
        plan.finishWave(level, chunk, event, budget);
        h.assertTrue(level.getFluidTicks().hasScheduledTick(wet, Fluids.WATER), "Completion omitted the planned fluid wake-up");
        h.succeed();
    }

    private static PreparedSectionWriter.CellChange change(BlockPos pos, BlockState before, BlockState after) {
        return new PreparedSectionWriter.CellChange(pos, before, after);
    }

    private static int index(RuinPlan.WaveSection binding, BlockPos pos) {
        for (int i = 0; i < binding.changes().size(); i++) if (binding.changes().get(i).pos().equals(pos)) return i;
        throw new IllegalStateException("Fixture source absent from bound section: " + pos);
    }

    private static RuinPlan plan(LevelChunk chunk, List<PreparedSectionWriter.CellChange> changes, List<BlockPos> fires,
                                 RuinContext context, long[] fluids, ScorchedBiomes.Cells biomes) {
        var sorted = new ArrayList<>(changes);
        sorted.sort(Comparator.comparingInt(c -> key(chunk, c.pos())));
        var palette = new LinkedHashMap<BlockState, Integer>();
        for (var change : sorted) palette.computeIfAbsent(change.state(), ignored -> palette.size());
        if (!fires.isEmpty()) palette.computeIfAbsent(Blocks.FIRE.defaultBlockState(), ignored -> palette.size());
        int[] cells = new int[sorted.size()];
        BlockState[] olds = new BlockState[sorted.size()];
        long[] hashes = new long[chunk.getSections().length];
        for (int i = 0; i < cells.length; i++) {
            var change = sorted.get(i);
            int section = chunk.getSectionIndex(change.pos().getY());
            cells[i] = key(chunk, change.pos()) | palette.get(change.state()) << RuinPlan.STATE_SHIFT
                    | (Blast.props(change.expected()).is(Blast.Props.SLOW) ? RuinPlan.SLOW : 0);
            olds[i] = change.expected();
            hashes[section] = RuinPlan.mix(hashes[section], change.expected());
        }
        int[] burning = fires.stream().mapToInt(pos -> key(chunk, pos) | palette.get(Blocks.FIRE.defaultBlockState()) << RuinPlan.STATE_SHIFT).toArray();
        return new RuinPlan(cells, hashes, palette.keySet().toArray(BlockState[]::new), olds, burning, new int[0], new long[0],
                null, null, null, null, context, fluids, biomes);
    }

    private static int key(LevelChunk chunk, BlockPos at) {
        return chunk.getSectionIndex(at.getY()) << RuinPlan.SECTION_SHIFT | (at.getY() & 15) << 8 | (at.getZ() & 15) << 4 | at.getX() & 15;
    }

    private static BlockPos base(GameTestHelper h) {
        BlockPos at = h.absolutePos(new BlockPos(8, 48, 8));
        return new BlockPos(at.getX() & ~15, at.getY() & ~15, at.getZ() & ~15);
    }

    private PreparedWaveBindingGameTests() {}
}
