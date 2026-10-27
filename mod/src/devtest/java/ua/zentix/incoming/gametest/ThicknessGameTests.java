package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.nuclear.world.CraterJob;
import ua.zentix.incoming.registry.ModDataMaps.Penetrability;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.SurfaceDamage;
import ua.zentix.incoming.warhead.Warheads;

import java.util.List;

/** Material metres versus world geometry, using the actual loaded data map and shared production paths. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ThicknessGameTests {
    private ThicknessGameTests() {}
    private static final Vec3 DOWN = new Vec3(0, -1, 0);

    @GameTest(template = "range", timeoutTicks = 30, batch = "thickness_material", skyAccess = true)
    public static void loadedTagsAndHalfShapeAgreeWithSupport(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos p = h.absolutePos(new BlockPos(32, 18, 32));
        var slab = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        level.setBlock(p, slab, 2);
        double charge = WeaponType.ROCKET.spec().charge();
        var support = Warheads.support(level, new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5), charge, DOWN, null).orElseThrow();
        h.assertTrue(Math.abs(Penetrability.of(slab).thickness() - 0.04) < 1e-9, "wooden slab tag was not loaded");
        h.assertTrue(Math.abs(support.slab() - 0.5) < 1e-6, "shape counted twice or ignored: " + support);
        double expected = BreachModel.Material.POOR.radius(charge, BreachModel.UNTAMPED) / 0.04;
        h.assertTrue(Math.abs(support.breach() - expected) < 1e-4, "material path and geometric support disagree: " + support);
        for (var block : List.of(Blocks.DIRT, Blocks.STONE, Blocks.IRON_ORE, Blocks.TERRACOTTA, Blocks.OAK_LOG)) {
            h.assertTrue(Penetrability.of(block.defaultBlockState()).thickness() == 1, "natural material was thinned: " + block);
        }
        h.succeed();
    }

    /** Adding thickness must not also change the former tool-based breach group of a newly mapped block. */
    @GameTest(template = "range", timeoutTicks = 30, batch = "thickness_material", skyAccess = true)
    public static void newEntriesPreserveLegacyMaterialFallback(GameTestHelper h) {
        for (var block : List.of(
                Blocks.BRICK_SLAB, Blocks.BRICK_STAIRS, Blocks.BRICK_WALL,
                Blocks.COBBLESTONE_SLAB, Blocks.COBBLESTONE_STAIRS, Blocks.COBBLESTONE_WALL,
                Blocks.CRACKED_DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_TILES, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS,
                Blocks.DEEPSLATE_BRICK_SLAB, Blocks.DEEPSLATE_BRICK_STAIRS, Blocks.DEEPSLATE_BRICK_WALL,
                Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_WALL,
                Blocks.END_STONE_BRICK_SLAB, Blocks.END_STONE_BRICK_STAIRS, Blocks.END_STONE_BRICK_WALL,
                Blocks.MOSSY_COBBLESTONE_SLAB, Blocks.MOSSY_COBBLESTONE_STAIRS, Blocks.MOSSY_COBBLESTONE_WALL,
                Blocks.MOSSY_STONE_BRICK_SLAB, Blocks.MOSSY_STONE_BRICK_STAIRS, Blocks.MOSSY_STONE_BRICK_WALL,
                Blocks.MUD_BRICK_SLAB, Blocks.MUD_BRICK_STAIRS, Blocks.MUD_BRICK_WALL,
                Blocks.NETHER_BRICK_SLAB, Blocks.NETHER_BRICK_STAIRS, Blocks.NETHER_BRICK_WALL,
                Blocks.POLISHED_BLACKSTONE_BRICK_SLAB, Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Blocks.POLISHED_BLACKSTONE_BRICK_WALL,
                Blocks.RED_NETHER_BRICK_SLAB, Blocks.RED_NETHER_BRICK_STAIRS, Blocks.RED_NETHER_BRICK_WALL,
                Blocks.STONE_BRICK_SLAB, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_WALL,
                Blocks.OAK_SLAB, Blocks.OAK_STAIRS, Blocks.OAK_DOOR,
                Blocks.OAK_TRAPDOOR, Blocks.OAK_FENCE, Blocks.OAK_FENCE_GATE)) {
            var state = block.defaultBlockState();
            BreachModel.Material expected = state.is(BlockTags.MINEABLE_WITH_AXE) ? BreachModel.Material.POOR
                    : state.is(BlockTags.MINEABLE_WITH_SHOVEL) || state.is(BlockTags.MINEABLE_WITH_HOE) ? BreachModel.Material.EARTH
                    : state.is(BlockTags.MINEABLE_WITH_PICKAXE) && block.defaultDestroyTime() <= 2 ? BreachModel.Material.ROCK
                    : BreachModel.Material.REINFORCED;
            var entry = Penetrability.of(state);
            h.assertTrue(entry.breaching() == expected, "new thickness entry changed the legacy breach group: " + block + " " + entry);
            h.assertTrue(entry.kind() == ua.zentix.incoming.warhead.Penetration.Kind.ROCK && entry.s() == 1,
                    "new thickness entry changed the legacy bomb medium: " + block + " " + entry);
        }
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 30, batch = "thickness_excavation", skyAccess = true)
    public static void excavationConsumesLayersAndStoresFractionOfRemainingBlock(GameTestHelper h) {
        var level = h.getLevel();
        for (int i = 0; i < 4; i++) {
            BlockPos top = h.absolutePos(new BlockPos(24 + i * 4, 15, 32));
            for (int y = 11; y <= 15; y++) level.setBlock(new BlockPos(top.getX(), h.absolutePos(new BlockPos(0, y, 0)).getY(), top.getZ()), Blocks.STONE.defaultBlockState(), 2);
            if (i < 2) level.setBlock(top, Blocks.GRAY_CONCRETE.defaultBlockState(), 2);
            if (i == 3) {
                level.setBlock(top, Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(top.below(), Blocks.GRAY_CONCRETE.defaultBlockState(), 2);
            }
            double depth = i == 0 ? 0.1 : i == 3 ? 1.3 : 0.3;
            var profile = new CraterJob.Profile() {
                public double depth(double r) { return depth; }
                public double rim(double r) { return 0; }
            };
            CraterJob.digColumn(level, top.getX(), top.getZ(), Vec3.atBottomCenterOf(top.above()), 1, 0, profile,
                    List.of(), null, CraterJob.Mode.DIG, RandomSource.create(1));
            BlockPos remainder = i == 3 ? top.below(2) : i == 1 ? top.below() : top;
            double expected = i == 0 ? 0.5 : i == 2 ? 0.3 : 0.1;
            h.assertTrue(level.getBlockState(top).isAir() == (i == 1 || i == 3), "full represented layer removal wrong at " + top);
            if (i == 3) h.assertTrue(level.getBlockState(top.below()).isAir(), "buried represented layer was counted as a metre");
            h.assertTrue(Math.abs(SurfaceDamage.depth(level, remainder) - expected) < 1e-9,
                    "remainder must be a fraction of its own material thickness: " + SurfaceDamage.depth(level, remainder));
        }
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 30, batch = "thickness_bomb", skyAccess = true)
    public static void bombSensorCountsMaterialWhileTravelCountsWorldDistance(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos top = h.absolutePos(new BlockPos(32, 30, 32));
        // Ten full blocks of concrete represent only two material metres, below the four-metre void-sensor threshold.
        for (int i = 0; i < 10; i++) level.setBlock(top.below(i), Blocks.GRAY_CONCRETE.defaultBlockState(), 2);
        var path = BunkerBusterEntity.plan(level, Vec3.atBottomCenterOf(top.above()), DOWN, 500, 0);
        var step = path.steps().stream().filter(s -> s.pos().equals(top.below(9))).findFirst().orElseThrow();
        h.assertTrue(Math.abs(step.s() - 10) < 1e-6, "represented thickness compressed world travel");
        h.assertTrue(Math.abs(step.armed() - 2) < 1e-6, "void sensor counted full blocks instead of material");
        h.assertTrue(path.end() != BunkerBusterEntity.Path.End.VOID, "two material metres armed the four-metre sensor");
        h.succeed();
    }
}
