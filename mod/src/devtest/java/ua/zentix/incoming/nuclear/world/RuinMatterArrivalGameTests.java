package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.registry.ModAttachments;

/** Settlement cannot invalidate the source event's still-pending destination plan. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinMatterArrivalGameTests {
    private RuinMatterArrivalGameTests() {}

    private static void work(RuinMatter matter, ServerLevel level) {
        var clock = WorkClock.counting(1); clock.start(1); matter.work(level, clock);
    }

    private static BlockPos floor(GameTestHelper h) {
        var level = h.getLevel();
        var at = h.absolutePos(new BlockPos(32, 3, 32));
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            level.setBlock(at.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState(), 2);
            for (int y = 0; y <= 3; y++) level.setBlock(at.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        StrikeGameTests.afterTest(h, () -> { NuclearStrikes.clear(level); RuinMatterGameTests.clearFixture(level); });
        return at;
    }

    private static Detonation event(ServerLevel level, BlockPos at, long time) {
        var events = NuclearEvents.get(level);
        var d = new Detonation(events.nextId(), Vec3.atCenterOf(at.west(80)), at.getY() - 1,
                1, false, time, 0, 0, 20_000, 42, 1, false, 1);
        events.detonations().add(d); events.setDirty();
        return d;
    }

    private static RuinMatter barrel(GameTestHelper h, BlockPos at, int event) {
        var level = h.getLevel(); var source = at.above(3);
        level.setBlock(source, Blocks.BARREL.defaultBlockState(), 2);
        var entity = (BarrelBlockEntity) level.getBlockEntity(source);
        entity.setItem(0, new ItemStack(Items.DIAMOND, 7));
        var piece = new RuinMatter.Piece(source, Blocks.BARREL.defaultBlockState(),
                entity.saveWithoutMetadata(level.registryAccess()), new Vec3(0, -1, 0), level.getGameTime() - 1);
        piece.y = at.getY(); piece.scorchEvent = event;
        ColumnScar.replace(level, source, piece.state, Blocks.AIR.defaultBlockState());
        var matter = new RuinMatter(); matter.release(java.util.List.of(piece));
        StrikeGameTests.afterTest(h, () -> matter.unload(level));
        return matter;
    }

    @GameTest(template = "pad", timeoutTicks = 1200, batch = "nuke_material_arrival_before", skyAccess = true)
    public static void materialWaitsForSourceRuinsBeforeWave(GameTestHelper h) throws Exception {
        pending(h, true, false);
    }

    @GameTest(template = "pad", timeoutTicks = 1200, batch = "nuke_material_arrival_after", skyAccess = true)
    public static void materialWaitsForDelayedSourceRuinsAfterWave(GameTestHelper h) throws Exception {
        pending(h, false, false);
    }

    @GameTest(template = "pad", timeoutTicks = 1200, batch = "nuke_material_arrival_unchanged", skyAccess = true)
    public static void unchangedDestinationStillCompletesItsRuinBeforeSettlement(GameTestHelper h) throws Exception {
        pending(h, true, true);
    }

    private static void pending(GameTestHelper h, boolean before, boolean unchanged) throws Exception {
        var level = h.getLevel(); var at = floor(h);
        var d = event(level, at, level.getGameTime() + (before ? 400 : -400));
        var matter = barrel(h, at, d.id());
        level.setBlock(at, Blocks.STONE_PRESSURE_PLATE.defaultBlockState(), 2);
        var chunk = level.getChunkAt(at);
        var plan = unchanged ? RuinPlan.EMPTY : RuinPlanner.plan(level, d, chunk);
        if (!unchanged) h.assertTrue(plan.changedBlocks() > 0, "Fixture did not prepare actual destination changes");
        var plans = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<RuinPlan>();
        plans.put(chunk.getPos().toLong(), plan);
        var scars = NuclearWorld.get(level).scars();
        scars.scanLoaded(d, plans, new long[]{chunk.getPos().toLong()});
        // Leave the before-wave case in the not-yet-offered Scan/prepared path. The late case
        // has a real queued job whose front has already crossed the destination.
        if (!before) scars.offer(chunk, d);
        work(matter, level);
        h.assertTrue(matter.pending() == 1 && matter.deposited() == 0,
                "Material overtook its source event's pending destination ruins");
        h.assertTrue(level.getBlockState(at).is(Blocks.STONE_PRESSURE_PLATE), "Pending material changed the destination snapshot");
        var expected = matter.save(new CompoundTag(), level.registryAccess());
        var file = java.nio.file.Files.createTempDirectory(level.getDataStorage().dataFolder.toPath(), "material-arrival-")
                .resolve("material.dat");
        matter.save(file.toFile(), level.registryAccess());
        RuinMatter[] restored = {null};
        StrikeGameTests.afterTest(h, () -> { if (restored[0] != null) restored[0].unload(level); });
        h.succeedWhen(() -> {
            h.assertFalse(matter.writing(), "Native material save has not completed");
            if (restored[0] == null) {
                try {
                    var scan = LedgerScan.measure(file, "incoming_ruin_matter", java.util.Set.of(), NuclearPrep.PLANS_CAP);
                    restored[0] = (RuinMatter) LedgerReader.read(file, "incoming_ruin_matter", level.registryAccess(), scan.row(), java.util.Set.of());
                    h.assertTrue(restored[0].save(new CompoundTag(), level.registryAccess()).equals(expected), "Waiting changed material identity or cargo across native reload");
                } catch (java.io.IOException error) { throw new RuntimeException(error); }
            }
            var clock = WorkClock.counting(1); clock.start(16);
            scars.work(level, ScarQueue.due(d, chunk.getPos()) + 1, clock);
            h.assertTrue(chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= d.id(), "Destination ruin has not completed");
            h.assertTrue(scars.ruinStats()[2] == 0, "Material made its destination plan stale");
            work(restored[0], level);
            h.assertTrue(restored[0].deposited() == 1 && restored[0].pending() == 0, "Material did not settle after its destination ruin");
            var placed = level.getBlockEntity(at);
            h.assertTrue(placed instanceof BarrelBlockEntity && ((BarrelBlockEntity) placed).getItem(0).is(Items.DIAMOND)
                    && ((BarrelBlockEntity) placed).getItem(0).getCount() == 7, "Destination ruin lost or duplicated transported cargo");
        });
    }

    @GameTest(template = "pad", timeoutTicks = 40, batch = "nuke_material_arrival_unrelated", skyAccess = true)
    public static void unrelatedPendingEventDoesNotHoldCompletedSourceMaterial(GameTestHelper h) {
        var level = h.getLevel(); var at = floor(h);
        var source = event(level, at, level.getGameTime() - 400);
        var later = event(level, at, level.getGameTime() + 400);
        var chunk = level.getChunkAt(at);
        chunk.setData(ModAttachments.CHUNK_SCAR, source.id());
        NuclearWorld.get(level).scars().offer(chunk, later);
        var matter = barrel(h, at, source.id());
        work(matter, level);
        h.assertTrue(matter.deposited() == 1 && matter.pending() == 0, "Unrelated later event held already-ruined source material");
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 40, batch = "nuke_material_arrival_outside", skyAccess = true)
    public static void sourceScanOutsideItsZoneDoesNotHoldMaterial(GameTestHelper h) {
        var level = h.getLevel(); var at = floor(h);
        var source = event(level, at.west(8192), level.getGameTime() + 400);
        h.assertFalse(ScarQueue.inRange(new ChunkPos(at), source), "Fixture destination must be outside the source ruin zone");
        NuclearWorld.get(level).scars().scanLoaded(source);
        var matter = barrel(h, at, source.id());
        work(matter, level);
        h.assertTrue(matter.deposited() == 1 && matter.pending() == 0, "Out-of-range pending scan held material");
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 40, batch = "nuke_material_arrival_scan", skyAccess = true)
    public static void unofferedSourceScanAlreadyOwnsItsDestination(GameTestHelper h) {
        var level = h.getLevel(); var at = floor(h);
        var source = event(level, at, level.getGameTime() + 400);
        var scars = NuclearWorld.get(level).scars();
        scars.scanLoaded(source);
        h.assertFalse(scars.queued(new ChunkPos(at).toLong()), "Fixture already offered a destination job");
        var matter = barrel(h, at, source.id());
        work(matter, level);
        h.assertTrue(matter.pending() == 1 && matter.deposited() == 0 && level.getBlockState(at).isAir(),
                "Material overtook an in-range source scan before its job was offered");
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 40, batch = "nuke_material_arrival_unmarked", skyAccess = true)
    public static void unrelatedEventDoesNotHoldOutOfRangeSourceWithoutScar(GameTestHelper h) {
        var level = h.getLevel(); var at = floor(h);
        var source = event(level, at.west(8192), level.getGameTime() - 400);
        var later = event(level, at, level.getGameTime() + 400);
        var chunk = level.getChunkAt(at);
        chunk.setData(ModAttachments.CHUNK_SCAR, 0);
        NuclearWorld.get(level).scars().offer(chunk, later);
        var matter = barrel(h, at, source.id());
        work(matter, level);
        h.assertTrue(matter.deposited() == 1 && matter.pending() == 0, "Unrelated job held an unaffected source destination");
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 40, batch = "nuke_material_arrival_cleared", skyAccess = true)
    public static void clearedSourceEventDoesNotStrandItsMaterial(GameTestHelper h) {
        var level = h.getLevel(); var at = floor(h);
        var source = event(level, at, level.getGameTime() + 400);
        NuclearWorld.get(level).scars().offer(level.getChunkAt(at), source);
        var matter = barrel(h, at, source.id());
        NuclearStrikes.clear(level);
        work(matter, level);
        h.assertTrue(matter.deposited() == 1 && matter.pending() == 0, "Cleared source event stranded preserved material");
        h.succeed();
    }
}
