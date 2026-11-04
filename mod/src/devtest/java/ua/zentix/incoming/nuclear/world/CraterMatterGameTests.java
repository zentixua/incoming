package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;

import java.util.List;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CraterMatterGameTests {
    private CraterMatterGameTests() {}

    @GameTest(template = "pad", timeoutTicks = 20, batch = "nuke_crater_material", skyAccess = true)
    public static void rimUsesExcavatedMaterialAndRestartDoesNotDigTwice(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos source = h.absolutePos(new BlockPos(4, 6, 4));
        BlockPos rim = source.east(3);
        for (BlockPos column : List.of(source, rim)) {
            for (int y = -4; y <= 0; y++) level.setBlock(column.offset(0, y, 0), Blocks.STONE.defaultBlockState(), 2);
            for (int y = 1; y <= 5; y++) level.setBlock(column.offset(0, y, 0), Blocks.AIR.defaultBlockState(), 2);
        }
        // An above-ground building belongs to the thermal reservoir, not excavated rim stock.
        level.setBlock(source.above(), Blocks.OAK_PLANKS.defaultBlockState(), 2);
        var ledger = new CraterMatter();
        var pass = ledger.pass(1);
        h.assertTrue(RandomSource.create(4096).nextFloat() < 0.3f, "Fixture must exercise the glazing branch");
        Vec3 centre = Vec3.atCenterOf(source);
        CraterJob.Profile profile = new CraterJob.Profile() {
            public double depth(double r) { return r < 1 ? 2 : 0; }
            public double rim(double r) { return r >= 1 ? 3 : 0; }
        };
        CraterJob.digColumn(level, source.getX(), source.getZ(), centre, 8, 0, profile,
                List.of(Blocks.DIRT.defaultBlockState()), Blocks.STONE.defaultBlockState(), CraterJob.Mode.VAPORIZE, RandomSource.create(4096), pass);
        h.assertTrue(level.getBlockState(source.below()).isAir(), "Bowl did not remove two source blocks");
        h.assertTrue(pass.available.getOrDefault(Blocks.STONE.defaultBlockState(), 0L) == 2, "Excavated stock differs from actual removals");
        var restored = CraterMatter.load(ledger.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        var saved = restored.pass(1);
        CraterJob.digColumn(level, source.getX(), source.getZ(), centre, 8, 0, profile,
                List.of(Blocks.DIRT.defaultBlockState()), null, CraterJob.Mode.VAPORIZE, RandomSource.create(1), saved);
        h.assertTrue(level.getBlockState(source.below(2)).is(Blocks.STONE), "Restart dug the already-completed column again");
        CraterJob.digColumn(level, rim.getX(), rim.getZ(), centre, 8, 0, profile,
                List.of(Blocks.DIRT.defaultBlockState()), null, CraterJob.Mode.VAPORIZE, RandomSource.create(1), saved);
        h.assertTrue(level.getBlockState(rim.above()).is(Blocks.STONE) && level.getBlockState(rim.above(2)).is(Blocks.STONE), "Rim changed the excavated block type");
        h.assertTrue(level.getBlockState(rim.above(3)).isAir(), "Rim invented material after source stock ran out");
        double[] rock = saved.materials.get(Blocks.STONE.defaultBlockState());
        double[] wood = saved.materials.get(Blocks.OAK_PLANKS.defaultBlockState());
        h.assertTrue(rock[0] == 2 && rock[2] == 2 && rock[4] == 0 && rock[6] == 0, "Rock ledger counted unchanged glazing or lost volume");
        h.assertTrue(wood[0] > 0 && wood[0] == wood[6] && wood[1] == wood[7], "Vaporized building lost its thermal reservoir");
        CraterMatter.load(restored.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 20, batch = "nuke_crater_protected", skyAccess = true)
    public static void protectedBowlCannotManufactureRim(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos source = h.absolutePos(new BlockPos(4, 6, 4));
        for (int y = level.getMinBuildHeight(); y <= source.getY(); y++)
            level.setBlock(new BlockPos(source.getX(), y, source.getZ()), Blocks.BEDROCK.defaultBlockState(), 2);
        for (int y = 1; y <= 30; y++) level.setBlock(source.above(y), Blocks.AIR.defaultBlockState(), 2);
        var ledger = new CraterMatter();
        var pass = ledger.pass(1);
        CraterJob.Profile profile = new CraterJob.Profile() {
            public double depth(double r) { return 2; }
            public double rim(double r) { return 0; }
        };
        CraterJob.digColumn(level, source.getX(), source.getZ(), Vec3.atCenterOf(source), 8, 0, profile,
                List.of(Blocks.DIRT.defaultBlockState()), null, CraterJob.Mode.VAPORIZE, RandomSource.create(1), pass);
        h.assertTrue(level.getBlockState(source).is(Blocks.BEDROCK), "Protected material was excavated");
        h.assertTrue(pass.materials.isEmpty() && pass.rim() == null, "Protected bowl manufactured ejecta");
        h.succeed();
    }
}
