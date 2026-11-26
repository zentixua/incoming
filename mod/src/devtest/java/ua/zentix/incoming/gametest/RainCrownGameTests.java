package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.warhead.Warheads;

import java.util.Locale;

/**
 * In rain a whole tree crown is wet: rain wets every leaf it falls through ({@code Warheads.soaked} reads the
 * {@code MOTION_BLOCKING_NO_LEAVES} heightmap; vanilla {@code isRainingAt} stops at the top leaf of each column and would
 * leave the crown's sides and underside dry for a fuel fireball). Grows a vanilla oak under open sky in rain and requires
 * every leaf to be soaked; fails with the dry share.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RainCrownGameTests {
    private static final BlockPos TRUNK = new BlockPos(32, 12, 32);

    @GameTest(template = "range", timeoutTicks = 400, batch = "fuel_fire_rain_crown", skyAccess = true)
    public static void rainSoaksTheWholeCrown(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos base = h.absolutePos(TRUNK);
        level.setBlock(base.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        var oak = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolderOrThrow(TreeFeatures.OAK).value();
        h.assertTrue(oak.place(level, level.getChunkSource().getGenerator(), RandomSource.create(7), base), "oak not grown");
        StrikeGameTests.afterTest(h, () -> level.setWeatherParameters(24000, 0, false, false));
        level.setWeatherParameters(0, 6000, true, false);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(level.isRaining(), "no rain yet"))
                .thenExecute(() -> {
                    int leaves = 0, dry = 0;
                    for (BlockPos p : BlockPos.betweenClosed(base.offset(-3, 0, -3), base.offset(3, 9, 3))) {
                        if (!level.getBlockState(p).is(BlockTags.LEAVES)) continue;
                        leaves++;
                        if (!Warheads.soaked(level, p)) dry++;
                    }
                    level.setWeatherParameters(24000, 0, false, false);
                    h.assertTrue(leaves > 0, "no leaves");
                    h.assertTrue(dry == 0, String.format(Locale.ROOT, "%d of %d crown leaves (%.0f %%) count dry in rain", dry, leaves, 100.0 * dry / leaves));
                })
                .thenSucceed();
    }
}
