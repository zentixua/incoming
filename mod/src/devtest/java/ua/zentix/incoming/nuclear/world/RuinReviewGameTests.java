package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;

import java.util.List;

/** Review regressions exercise the registered event and production continuation, not a snapshot-only solver. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinReviewGameTests {
    private RuinReviewGameTests() {}

    private static Detonation register(ServerLevel level, BlockPos at) {
        var events = NuclearEvents.get(level);
        double height = ua.zentix.incoming.nuclear.model.Yield.optimalBurstHeight(15);
        double range = ua.zentix.incoming.nuclear.model.HobBlast.rangeFor(1, height, 15);
        var d = new Detonation(events.nextId(), Vec3.atCenterOf(at).add(-range, height, 0), at.getY(),
                15, false, level.getGameTime(), 0, 3, 20000, 42, 1, false, .25f, 6000, false);
        events.add(d);
        return d;
    }

    private static void unit(ServerLevel level, RuinMatter ledger) {
        WorkClock clock = WorkClock.counting(1);
        clock.start(1);
        ledger.work(level, clock);
    }

    private static void remove(ServerLevel level, RuinMatter ledger, Detonation d, BlockPos at) {
        var piece = RuinMatter.capture(level, at, level.getBlockState(at), d);
        if (piece == null) throw new AssertionError("Fixture did not provide a material source");
        ColumnScar.replace(level, at, level.getBlockState(at), Blocks.AIR.defaultBlockState());
        ledger.release(List.of(piece));
        ledger.support(level, List.of(piece), d);
    }

    @GameTest(template = "range", batch = "nuke_review_floating", timeoutTicks = 40, skyAccess = true)
    public static void registeredWaveDoesNotGiveFloatingTerrainGlobalGravity(GameTestHelper h) {
        var level = h.getLevel();
        RuinMatterGameTests.clearFixture(level);
        var ledger = RuinMatter.get(level);
        BlockPos base = h.absolutePos(new BlockPos(24, 35, 24));
        var d = register(level, base);
        try {
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++)
                level.setBlock(base.offset(x, 0, z), Blocks.END_STONE.defaultBlockState(), 2);
            // A broken glass decoration above the island and a pendant below a separate platform.
            // Neither was a foundation. The latter requires the original-graph proof, not just UP seeding.
            level.setBlock(base.above(), Blocks.GLASS.defaultBlockState(), 2);
            BlockPos pendant = base.offset(0, 8, 0);
            level.setBlock(pendant, Blocks.GLASS.defaultBlockState(), 2);
            level.setBlock(pendant.above(), Blocks.STONE.defaultBlockState(), 2);
            remove(level, ledger, d, base.above());
            remove(level, ledger, d, pendant);
            ledger = RuinMatter.load(ledger.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            level.getDataStorage().set("incoming_ruin_matter", ledger);
            for (int i = 0; i < 200; i++) unit(level, ledger);
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++)
                h.assertTrue(level.getBlockState(base.offset(x, 0, z)).is(Blocks.END_STONE), "Unchanged floating island acquired gravity");
            h.assertTrue(level.getBlockState(pendant.above()).is(Blocks.STONE), "Pre-existing floating platform lost its pendant, not its foundation");
            h.assertTrue(ledger.released() == 2 && !ledger.busy(), "Unanchored continuation did not finish without releasing terrain");
        } finally {
            NuclearEvents.get(level).detonations().remove(d);
            RuinMatterGameTests.clearFixture(level);
        }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_review_release", timeoutTicks = 40, skyAccess = true)
    public static void registeredSupportLossReleasesAcrossSeamsAndDrainsItsSeeds(GameTestHelper h) {
        var level = h.getLevel();
        RuinMatterGameTests.clearFixture(level);
        var ledger = RuinMatter.get(level);
        BlockPos start = h.absolutePos(new BlockPos(4, 30, 32));
        var d = register(level, start);
        try {
            for (int x = 0; x < 56; x++) level.setBlock(start.east(x), Blocks.WHITE_CONCRETE.defaultBlockState(), 2);
            level.setBlock(start.below(), Blocks.GLASS.defaultBlockState(), 2);
            level.setBlock(start.below(2), Blocks.OBSIDIAN.defaultBlockState(), 2);
            remove(level, ledger, d, start.below());
            for (int i = 0; i < 200; i++) unit(level, ledger);
            h.assertTrue(ledger.released() == 57 && ledger.pending() == 56, "Registered support loss failed to transfer the complete cross-chunk roof");
            h.assertTrue(ledger.pendingSupport() == 0, "Released cells left redundant support searches behind");
            for (int x = 0; x < 56; x++) h.assertTrue(level.getBlockState(start.east(x)).isAir(), "Detached roof remains at a seam");
        } finally {
            NuclearEvents.get(level).detonations().remove(d);
            RuinMatterGameTests.clearFixture(level);
        }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_review_callback", timeoutTicks = 40, skyAccess = true)
    public static void slopingBeltContinuationWaitsForCallbackChunks(GameTestHelper h) {
        var level = h.getLevel();
        var home = new net.minecraft.world.level.ChunkPos(h.absolutePos(BlockPos.ZERO));
        var chunkPos = new net.minecraft.world.level.ChunkPos(home.x + 75, home.z - 75);
        var cold = new net.minecraft.world.level.ChunkPos(chunkPos.x + 1, chunkPos.z);
        var hold = net.minecraft.server.level.TicketType.create("incoming_review_belt", java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));
        level.getChunkSource().addRegionTicket(hold, chunkPos, 0, chunkPos);
        RuinMatterGameTests.clearFixture(level);
        var ledger = RuinMatter.get(level);
        BlockPos start = new BlockPos(chunkPos.getMiddleBlockX(), level.getMaxBuildHeight() - 32, chunkPos.getMiddleBlockZ());
        var d = register(level, start);
        try {
            // Synchronous loading belongs only to fixture setup. The production continuation below
            // must keep the next chunk cold even though Create's diagonal chain enters it.
            var chunk = level.getChunk(chunkPos.x, chunkPos.z);
            h.assertFalse(ua.zentix.incoming.util.Terrain.ready(level, cold.x, cold.z), "Belt boundary was not cold");
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("create:belt"));
            var belt = block.getStateDefinition().getPossibleStates().stream()
                    .filter(state -> state.toString().contains("facing=east") && state.toString().contains("slope=upward")
                            && state.toString().contains("part=middle") && state.toString().contains("waterlogged=false"))
                    .findFirst().orElseThrow();
            h.assertTrue(belt.hasBlockEntity() && RuinPlan.callbackRadius(belt) == 2, "Fixture did not select Create's callback-bearing belt");
            for (int i = 0; i < 8; i++) {
                BlockPos at = start.offset(i, i, 0);
                chunk.getSection(chunk.getSectionIndex(at.getY())).setBlockState(at.getX() & 15, at.getY() & 15, at.getZ() & 15, belt);
            }
            level.setBlock(start.below(), Blocks.GLASS.defaultBlockState(), 2);
            level.setBlock(start.below(2), Blocks.OBSIDIAN.defaultBlockState(), 2);
            remove(level, ledger, d, start.below());
            for (int i = 0; i < 30; i++) unit(level, ledger);
            h.assertTrue(ledger.releasingSupport(), "Fixture did not reach support release before the callback guard");
            h.assertTrue(level.getBlockState(start) == belt, "Belt callback ran without its chunk neighbourhood");
            h.assertFalse(ua.zentix.incoming.util.Terrain.ready(level, cold.x, cold.z), "Belt removal synchronously loaded a cold chunk");
            BlockPos landing = start.below(5);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                level.setBlock(landing.offset(x, -1, z), Blocks.OBSIDIAN.defaultBlockState(), 2);
            var arriving = new RuinMatter.Piece(landing, belt, null, Vec3.ZERO, level.getGameTime() - 1);
            ledger.release(List.of(arriving));
            unit(level, ledger);
            h.assertTrue(level.getBlockState(landing).isAir() && ledger.deposited() == 0,
                    "Deposition did not guard the new block's onPlace callback");
            h.assertFalse(ua.zentix.incoming.util.Terrain.ready(level, cold.x, cold.z), "Deposition synchronously loaded a cold chunk");
        } finally {
            NuclearEvents.get(level).detonations().remove(d);
            RuinMatterGameTests.clearFixture(level);
            level.getChunkSource().removeRegionTicket(hold, chunkPos, 0, chunkPos);
        }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_review_repair", timeoutTicks = 40, skyAccess = true)
    public static void repairBetweenValidationSlicesCancelsRelease(GameTestHelper h) {
        repair(h, false, false);
    }

    @GameTest(template = "range", batch = "nuke_review_repair_release", timeoutTicks = 40, skyAccess = true)
    public static void repairBetweenReleaseSlicesKeepsTheRemainingRoof(GameTestHelper h) {
        repair(h, true, false);
    }

    @GameTest(template = "range", batch = "nuke_review_repair_batched", timeoutTicks = 40, skyAccess = true)
    public static void batchedPlacementInvalidatesPreviouslyValidatedContacts(GameTestHelper h) {
        repair(h, false, true);
    }

    private static void repair(GameTestHelper h, boolean duringRelease, boolean batched) {
        var level = h.getLevel();
        RuinMatterGameTests.clearFixture(level);
        var ledger = RuinMatter.get(level);
        BlockPos start = h.absolutePos(new BlockPos(4, 30, 32));
        var d = register(level, start);
        try {
            for (int x = 0; x < 56; x++) level.setBlock(start.east(x), Blocks.WHITE_CONCRETE.defaultBlockState(), 2);
            level.setBlock(start.below(), Blocks.GLASS.defaultBlockState(), 2);
            level.setBlock(start.below(2), Blocks.OBSIDIAN.defaultBlockState(), 2);
            remove(level, ledger, d, start.below());
            boolean inserted = false;
            long before = -1;
            for (int i = 0; i < 500; i++) {
                unit(level, ledger);
                if (!inserted && (duringRelease ? ledger.releasingSupport() : ledger.validatingSupport())) {
                    before = ledger.released();
                    // Player placement uses neighbour notification; this contact is behind the scan cursor.
                    BlockPos repair = duringRelease ? start.east(55).below() : start.east(6).below();
                    if (batched) ColumnScar.replace(level, repair, level.getBlockState(repair), Blocks.OBSIDIAN.defaultBlockState());
                    else level.setBlock(repair, Blocks.OBSIDIAN.defaultBlockState(), 3);
                    inserted = true;
                }
            }
            h.assertTrue(inserted, "Fixture never reached the requested resumable phase");
            h.assertTrue(ledger.released() == before, "Release used a certificate invalidated by a player's new anchor");
            h.assertTrue(level.getBlockState(start.east(55)).is(Blocks.WHITE_CONCRETE), "Repaired roof was removed");
            if (!duringRelease) h.assertTrue(before == 1, "Validation test inserted its repair after the first removal");
        } finally {
            NuclearEvents.get(level).detonations().remove(d);
            RuinMatterGameTests.clearFixture(level);
        }
        h.succeed();
    }
}
