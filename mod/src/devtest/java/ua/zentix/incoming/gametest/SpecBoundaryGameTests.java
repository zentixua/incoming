package ua.zentix.incoming.gametest;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.registry.ModDataMaps;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.warhead.DebrisSpawner;

import java.util.Arrays;
import java.util.List;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SpecBoundaryGameTests {
    private SpecBoundaryGameTests() {}

    @GameTest(template = "range", batch = "spec_boundaries")
    public static void debrisKeepsPieceCountsAndOrder(GameTestHelper h) {
        palette(h, ModDataMaps.DEBRIS_DRONE, "gray_concrete", "light_gray_concrete", "create:metal_girder",
                "black_concrete", "iron_trapdoor", "chain", "create:metal_girder", "light_gray_concrete");
        palette(h, ModDataMaps.DEBRIS_MISSILE, "light_gray_concrete", "light_gray_concrete", "light_gray_concrete",
                "create:industrial_iron_block", "create:metal_girder", "create:metal_girder", "create:metal_girder",
                "iron_bars", "iron_bars", "iron_trapdoor", "iron_trapdoor", "gray_concrete", "chain", "black_concrete");
        palette(h, ModDataMaps.DEBRIS_EMBERS, "blackstone", "basalt", "magma_block", "coal_block", "blackstone", "magma_block");
        palette(h, ModDataMaps.DEBRIS_ASH, "supplementaries:ash", "supplementaries:ash");
        h.succeed();
    }

    private static void palette(GameTestHelper h, DataMapType<Block, List<Integer>> type, String... oldIds) {
        var blocks = h.getLevel().registryAccess().registryOrThrow(Registries.BLOCK);
        List<BlockState> expected = Arrays.stream(oldIds).map(ResourceLocation::parse)
                .flatMap(id -> blocks.getOptional(id).stream()).map(Block::defaultBlockState).toList();
        List<BlockState> actual = DebrisSpawner.palette(h.getLevel(), type);
        h.assertTrue(actual.equals(expected), type.id() + ": expected " + expected + ", got " + actual);
    }

    @GameTest(template = "range", batch = "spec_weather")
    public static void weatherMatchesThePreviousWorldSampling(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        long time = level.getDayTime();
        float rain = level.getRainLevel(1), thunder = level.getThunderLevel(1);
        try {
            for (long day : new long[]{0, 6000, 12000, 18000}) {
                for (float wet : new float[]{0, 0.5f, 1}) {
                    level.setDayTime(day);
                    level.setRainLevel(wet);
                    level.setThunderLevel(wet);
                    weather(h, level);
                }
            }
            weather(h, level.getServer().getLevel(Level.NETHER));
            weather(h, level.getServer().getLevel(Level.END));
        } finally {
            level.setDayTime(time);
            level.setRainLevel(rain);
            level.setThunderLevel(thunder);
        }
        h.succeed();
    }

    private static void weather(GameTestHelper h, ServerLevel level) {
        Air air = Air.of(level);
        Propagation.Weather expected = new Propagation.Weather(Propagation.day(Air.sun(level)),
                level.getRainLevel(1), level.getThunderLevel(1),
                level.dimensionType().hasSkyLight() ? Propagation.refraction(air.stability()) : 0, air.surfaceHumidity());
        h.assertTrue(expected.equals(Air.weather(level)), "Weather changed in " + level.dimension().location());
    }
}
