package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinMatterGameTests {
    private RuinMatterGameTests() {}

    @GameTest(template = "pad", timeoutTicks = 20, batch = "nuke_material_geometry", skyAccess = true)
    public static void supportUsesContactFacesAndPartialVolumes(GameTestHelper h) {
        var full = Blast.props(Blocks.STONE.defaultBlockState());
        var slab = Blast.props(Blocks.STONE_SLAB.defaultBlockState());
        var glass = Blast.props(Blocks.GLASS.defaultBlockState());
        h.assertTrue(slab.volume() == 0.5, "Slab geometry was expanded to a whole cell");
        h.assertTrue(!slab.touches(net.minecraft.core.Direction.UP, full), "Half metre gap was treated as a structural connection");
        h.assertTrue(full.touches(net.minecraft.core.Direction.UP, slab), "A slab resting on a full block lost its support");
        h.assertTrue(glass.touches(net.minecraft.core.Direction.UP, full), "Transparent load-bearing material lost its contact face");
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 20, batch = "nuke_material_void", skyAccess = true)
    public static void materialOutsideBuildHeightRemainsAccountedWithoutWork(GameTestHelper h) {
        var level = h.getLevel();
        RuinMatter ledger = new RuinMatter();
        var source = h.absolutePos(new BlockPos(5, 3, 5));
        var piece = new RuinMatter.Piece(source, Blocks.STONE.defaultBlockState(), null, Vec3.ZERO, level.getGameTime() - 1);
        piece.y = level.getMinBuildHeight() - 1;
        ledger.release(java.util.List.of(piece));
        WorkClock clock = WorkClock.counting(1);
        clock.start(1000); ledger.work(level, clock);
        h.assertTrue(ledger.pending() == 1 && ledger.deposited() == 0, "Build-height overflow erased material");
        clock.start(1000); ledger.work(level, clock);
        h.assertTrue(clock.usedThisTickNanos() == 0, "Stranded material repeatedly acquired chunks or consumed work");
        var restored = RuinMatter.load(ledger.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        h.assertTrue(restored.pending() == 1, "Stranded reservoir was not saved");
        restored.unload(level);
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 30, batch = "nuke_material_overlap", skyAccess = true)
    public static void aLaterRemovalTransfersOnlyTheMaterialStillPresent(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(5, 4, 5));
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            level.setBlock(at.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState(), 2);
            level.setBlock(at.offset(x, 0, z), Blocks.AIR.defaultBlockState(), 2);
        }
        // A replaceable, non-colliding block must not leave rubble stuck above a valid floor.
        level.setBlock(at, Blocks.SHORT_GRASS.defaultBlockState(), 2);
        RuinMatter ledger = new RuinMatter();
        var first = new RuinMatter.Piece(at, Blocks.WHITE_CONCRETE.defaultBlockState(), null, Vec3.ZERO, level.getGameTime());
        ledger.release(java.util.List.of(first));
        WorkClock clock = WorkClock.counting(1);
        h.onEachTick(() -> { clock.start(1000); ledger.work(level, clock); });
        h.runAtTickTime(5, () -> {
            h.assertTrue(level.getBlockState(at).is(Blocks.WHITE_CONCRETE), "Replaceable plant trapped falling material");
            var next = new RuinMatter.Piece(at, level.getBlockState(at), null, Vec3.ZERO, level.getGameTime());
            ColumnScar.replace(level, at, next.state, Blocks.AIR.defaultBlockState());
            ledger.release(java.util.List.of(first, next)); // retry old handoff and release a new generation
        });
        h.runAtTickTime(10, () -> {
            h.assertTrue(ledger.released() == 2 && ledger.deposited() == 2 && ledger.pending() == 0,
                    "Ordered removal/retry duplicated or erased material");
            h.assertTrue(level.getBlockState(at).is(Blocks.WHITE_CONCRETE), "Second generation did not settle");
            var leaf = new RuinMatter.Piece(at.above(3), Blocks.OAK_LEAVES.defaultBlockState(), null, Vec3.ZERO, level.getGameTime());
            ledger.release(java.util.List.of(leaf));
            var restored = RuinMatter.load(ledger.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            double[] organic = restored.balance(Blocks.OAK_LEAVES);
            h.assertTrue(organic[0] > 0 && organic[0] == organic[4] && organic[2] == 0,
                    "Dispersed foliage lost its material-specific reservoir");
            restored.unload(level);
            h.succeed();
        });
    }

    @GameTest(template = "range", timeoutTicks = 180, batch = "nuke_material_seam", skyAccess = true)
    public static void supportBeyondThePlannerWindowIsReconciled(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos base = h.absolutePos(new BlockPos(4, 12, 32));
        for (int x = 0; x <= 55; x++) {
            for (int z = -2; z <= 2; z++) for (int y = 1; y <= 12; y++)
                level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(base.offset(x, 10, 0), Blocks.WHITE_CONCRETE.defaultBlockState(), 2);
        }
        // The only anchor is more than three chunks away, beyond the original local window.
        level.setBlock(base.offset(55, 9, 0), Blocks.BEDROCK.defaultBlockState(), 2);
        long seed = base.above(10).asLong();
        RuinMatter ledger = new RuinMatter();
        RuinSupport[] search = {new RuinSupport(seed)};
        var d = StructuralRuinGameTests.event(2);
        boolean[] held = {false}, released = {false};
        h.onEachTick(() -> {
            for (int i = 0; i < 32; i++) {
                if (search[0] == null || !search[0].step(level, ledger, d)) continue;
                if (!held[0]) {
                    for (int x = 0; x <= 55; x++) h.assertTrue(level.getBlockState(base.offset(x, 10, 0)).is(Blocks.WHITE_CONCRETE),
                            "Cross-chunk anchor was ignored");
                    held[0] = true;
                    level.setBlock(base.offset(55, 9, 0), Blocks.AIR.defaultBlockState(), 2);
                    search[0] = new RuinSupport(seed);
                } else {
                    search[0] = null;
                    released[0] = true;
                }
            }
        });
        h.runAtTickTime(150, () -> {
            h.assertTrue(held[0] && released[0], "Boundary reconciliation did not finish");
            for (int x = 0; x <= 55; x++) h.assertTrue(level.getBlockState(base.offset(x, 10, 0)).isAir(),
                    "Unsupported beam survives across a chunk seam");
            h.assertTrue(ledger.released() == 56 && ledger.pending() == 56, "Support release lost or duplicated material");
            ledger.unload(level);
            h.succeed();
        });
    }

    @GameTest(template = "pad", timeoutTicks = 150, batch = "nuke_material", skyAccess = true)
    public static void releasedBlocksFallAndSurviveSave(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos base = h.absolutePos(new BlockPos(5, 3, 5));
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
            level.setBlock(base.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState(), 2);
            for (int y = 0; y < 12; y++) level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        RuinMatter original = new RuinMatter();
        var pieces = new ArrayList<RuinMatter.Piece>();
        var types = new net.minecraft.world.level.block.Block[]{Blocks.OAK_PLANKS, Blocks.WHITE_CONCRETE, Blocks.STONE_SLAB, Blocks.BARREL};
        for (int i = 0; i < types.length; i++) {
            BlockPos at = base.offset(i - 2, 8, 0);
            var state = types[i].defaultBlockState();
            level.setBlock(at, state, 2);
            CompoundTag contents = null;
            if (level.getBlockEntity(at) instanceof BarrelBlockEntity barrel) {
                barrel.setItem(0, new ItemStack(Items.DIAMOND, 7));
                contents = barrel.saveWithoutMetadata(level.registryAccess());
            }
            pieces.add(new RuinMatter.Piece(at, state, contents, Vec3.ZERO, level.getGameTime()));
            ColumnScar.replace(level, at, state, Blocks.AIR.defaultBlockState());
        }
        original.release(pieces);
        original.release(pieces);
        h.assertTrue(original.released() == 4 && original.deposited() == 0 && original.pending() == 4,
                "Failure must create pending material, not an instant finished pile");
        double volume = 0.04 + 0.20 + 0.5 + 1; // existing boarding/concrete representation, natural slab, barrel fallback
        h.assertTrue(Math.abs(original.releasedVolume() - volume) < 1e-9, "Shape and represented thickness must each be counted once");
        RuinMatter[] ledger = {original};
        WorkClock clock = WorkClock.counting(1);
        h.onEachTick(() -> {
            clock.start(1000);
            ledger[0].work(level, clock);
        });
        h.runAtTickTime(5, () -> {
            h.assertTrue(ledger[0].deposited() == 0, "Eight metre fall cannot finish in 0.25 seconds");
            ledger[0] = RuinMatter.load(ledger[0].save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            h.assertTrue(ledger[0].pending() == 4, "Save must retain all moving material");
        });
        h.runAtTickTime(100, () -> {
            h.assertTrue(ledger[0].pending() == 0 && ledger[0].deposited() == 4,
                    "All four blocks must settle exactly once; pending=" + ledger[0].pending());
            h.assertTrue(Math.abs(ledger[0].depositedVolume() - volume) < 1e-9, "Deposited solid volume differs from released volume");
            for (int i = 0; i < types.length; i++) {
                BlockPos at = base.offset(i - 2, 0, 0);
                h.assertTrue(level.getBlockState(at).is(types[i]), "Settled material changed identity: " + types[i]);
                double[] account = ledger[0].balance(types[i]);
                h.assertTrue(account[0] > 0 && Math.abs(account[0] - account[2]) < 1e-9 && account[4] == 0,
                        "Material-specific balance changed on save: " + types[i]);
            }
            BarrelBlockEntity barrel = (BarrelBlockEntity) level.getBlockEntity(base.offset(1, 0, 0));
            h.assertTrue(barrel != null && barrel.getItem(0).is(Items.DIAMOND) && barrel.getItem(0).getCount() == 7,
                    "Falling container lost or duplicated its contents");
            ledger[0].unload(level);
            h.succeed();
        });
    }

    @GameTest(template = "pad", timeoutTicks = 150, batch = "nuke_material_pile", skyAccess = true)
    public static void aColumnSpreadsIntoASupportedPile(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos base = h.absolutePos(new BlockPos(5, 3, 5));
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
            level.setBlock(base.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState(), 2);
            for (int y = 0; y < 15; y++) level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        RuinMatter ledger = new RuinMatter();
        var pieces = new ArrayList<RuinMatter.Piece>();
        for (int y = 5; y < 13; y++) pieces.add(new RuinMatter.Piece(base.above(y),
                Blocks.WAXED_COPPER_BLOCK.defaultBlockState(), null, Vec3.ZERO, level.getGameTime()));
        ledger.release(pieces);
        WorkClock clock = WorkClock.counting(1);
        h.onEachTick(() -> { clock.start(1000); ledger.work(level, clock); });
        h.runAtTickTime(120, () -> {
            h.assertTrue(ledger.deposited() == 8 && ledger.pending() == 0, "Pile must retain all eight blocks: "
                    + ledger.save(new CompoundTag(), level.registryAccess()));
            int found = 0;
            for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) for (int y = 0; y < 14; y++) {
                BlockPos at = base.offset(x, y, z);
                if (!level.getBlockState(at).is(Blocks.WAXED_COPPER_BLOCK)) continue;
                found++;
                h.assertTrue(!level.getBlockState(at.below()).isAir(), "Pile contains a floating block");
                h.assertTrue(y <= 2, "Eight loose blocks formed an unstable vertical needle");
            }
            h.assertTrue(found == 8, "Deposited count differs from world material: " + found);
            ledger.unload(level);
            h.succeed();
        });
    }
}
