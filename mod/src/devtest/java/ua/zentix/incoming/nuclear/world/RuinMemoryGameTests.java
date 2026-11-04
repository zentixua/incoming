package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;

import java.util.List;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinMemoryGameTests {
    private RuinMemoryGameTests() {}

    private static Detonation register(ServerLevel level, BlockPos at) {
        var events = NuclearEvents.get(level);
        double height = ua.zentix.incoming.nuclear.model.Yield.optimalBurstHeight(15);
        double range = ua.zentix.incoming.nuclear.model.HobBlast.rangeFor(1, height, 15);
        var d = new Detonation(events.nextId(), Vec3.atCenterOf(at).add(-range, height, 0), at.getY(),
                15, false, level.getGameTime(), 0, 3, 20000, 42, 1, false, .25f, 6000, false);
        events.add(d);
        return d;
    }

    private static RuinMemory.Reservation fill(ServerLevel other, long spare) {
        return RuinMemory.reserve(other, NuclearPrep.PLANS_CAP - NuclearPrep.memory(other) + RuinMemory.TRANSITION_ROOM - spare);
    }

    private static void work(ServerLevel level, RuinMatter ledger, int units) {
        var clock = WorkClock.counting(1);
        clock.start(units); ledger.work(level, clock);
    }

    @GameTest(template = "range", batch = "nuke_material_memory", timeoutTicks = 80, skyAccess = true)
    public static void registeredSupportWaitsDurablyForSharedCapacityThenReleases(GameTestHelper h) {
        var level = h.getLevel();
        var other = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(other != null, "Missing second dimension");
        RuinMatterGameTests.clearFixture(level);
        var ledger = RuinMatter.get(level);
        BlockPos start = h.absolutePos(new BlockPos(4, 30, 32));
        var d = register(level, start);
        try {
            for (int x = 0; x < 56; x++) level.setBlock(start.east(x), Blocks.WHITE_CONCRETE.defaultBlockState(), 2);
            level.setBlock(start.below(), Blocks.GLASS.defaultBlockState(), 2);
            level.setBlock(start.below(2), Blocks.OBSIDIAN.defaultBlockState(), 2);
            var piece = RuinMatter.capture(level, start.below(), level.getBlockState(start.below()), d);
            try (var reservation = RuinMemory.reserve(level, RuinMatter.removalBytes(piece))) {
                h.assertTrue(reservation != null, "Fixture cannot reserve its initial removal");
                ColumnScar.replace(level, start.below(), piece.state, Blocks.AIR.defaultBlockState());
                ledger.release(List.of(piece)); ledger.support(level, List.of(piece), d);
            }
            // Reach a real live graph before exhausting another dimension's shared capacity.
            for (int i = 0; i < 100 && !ledger.validatingSupport(); i++) work(level, ledger, 1);
            h.assertTrue(ledger.validatingSupport(), "Fixture did not reach validation");
            long graphBytes = ledger.retainedBytes();
            CompoundTag saved = ledger.save(new CompoundTag(), level.registryAccess());
            ledger.unload(level);
            ledger = RuinMatter.load(saved, level.registryAccess());
            level.getDataStorage().set("incoming_ruin_matter", ledger);
            h.assertTrue(ledger.retainedBytes() < graphBytes, "Restart retained disposable live graph");
            try (var occupied = fill(other, 0)) {
                h.assertTrue(occupied != null, "Failed to reserve shared capacity");
                h.assertTrue(RuinMemory.reserve(level, 1) == null, "Other dimension did not close admission");
                for (int i = 0; i < 20; i++) work(level, ledger, 4);
                h.assertTrue(ledger.released() == 1 && ledger.pendingSupport() > 0, "Capacity pause removed structure or lost its seeds");
                for (int x = 0; x < 56; x++) h.assertTrue(level.getBlockState(start.east(x)).is(Blocks.WHITE_CONCRETE), "Block removed without material capacity");
                h.assertTrue(NuclearPrep.memory(level) - RuinMemory.TRANSITION_ROOM <= NuclearPrep.PLANS_CAP,
                        "Paused support exceeded the shared reservation cap");
            }
            for (int i = 0; i < 200; i++) work(level, ledger, 1);
            h.assertTrue(ledger.released() == 57 && ledger.pendingSupport() == 0, "Reopened capacity did not resume saved reconciliation");
            var restored = RuinMatter.load(ledger.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            h.assertTrue(restored.pending() == 56, "Capacity/restart lost moving ownership");
        } finally {
            NuclearEvents.get(level).detonations().remove(d);
            RuinMatterGameTests.clearFixture(level);
        }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_material_plan_memory", timeoutTicks = 80, skyAccess = true)
    public static void planReservesBeforeRemovalAndDoesNotCallCapacityStaleness(GameTestHelper h) {
        var level = h.getLevel();
        var other = level.getServer().getLevel(Level.NETHER);
        RuinMatterGameTests.clearFixture(level);
        BlockPos at = h.absolutePos(new BlockPos(24, 30, 24));
        level.setBlock(at.below(), Blocks.OBSIDIAN.defaultBlockState(), 2);
        level.setBlock(at, Blocks.GLASS.defaultBlockState(), 2);
        var d = register(level, at);
        var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
        var plan = RuinPlanner.plan(level, d, chunk);
        try {
            h.assertTrue(plan != RuinPlan.EMPTY && plan.changedBlocks() > 0, "Fixture needs a destructive plan");
            try (var occupied = fill(other, 0)) {
                h.assertTrue(occupied != null, "Failed to occupy memory");
                h.assertTrue(!plan.apply(level, chunk, d, new ColumnScar.Budget(false)) && plan.memoryWaiting(),
                        "Full ledger was treated as success/stale plan");
                h.assertTrue(level.getBlockState(at).is(Blocks.GLASS) && RuinMatter.get(level).released() == 0,
                        "Plan removed its source before reserving ownership");
            }
            h.assertTrue(plan.apply(level, chunk, d, new ColumnScar.Budget(false)) && !plan.memoryWaiting(),
                    "Unchanged plan did not resume after capacity reopened");
            h.assertTrue(level.getBlockState(at).isAir() && RuinMatter.get(level).released() > 0, "Resumed plan lost material");
        } finally {
            NuclearEvents.get(level).detonations().remove(d);
            RuinMatterGameTests.clearFixture(level);
        }
        h.succeed();
    }
}
