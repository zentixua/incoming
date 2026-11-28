package ua.zentix.incoming.compat;

import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LodSections#prints} walks every value of a section's palette, and a
 * global palette (over 256 states in a section, a detailed city) reports the whole block-state registry as its size,
 * so walking it would visit every block state of the game instead of the section's storage alone. {@code ChunkShot.rawPrint}
 * (the same fingerprint for ruin plans) skips the global palette for this reason.
 */
class LodSectionsGlobalPaletteTest {
    private static Registry<Biome> biomes;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
        if (Block.BLOCK_STATE_REGISTRY.size() == 0) {
            for (Block block : BuiltInRegistries.BLOCK) block.getStateDefinition().getPossibleStates().forEach(Block.BLOCK_STATE_REGISTRY::add);
        }
        MappedRegistry<Biome> r = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        r.register(Biomes.PLAINS, new Biome.BiomeBuilder().hasPrecipitation(false).temperature(0.8f).downfall(0.4f)
                .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0xc0d8ff).waterColor(0x3f76e4).waterFogColor(0x050533).skyColor(0x78a7ff).build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build(), RegistrationInfo.BUILT_IN);
        biomes = r.freeze();
    }

    @Test
    void aGlobalPaletteSectionIsFingerprintedFromItsStorageNotTheRegistry() {
        LevelChunkSection s = new LevelChunkSection(biomes);
        int n = 0;
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            if (n >= 400) break;
            if (state.isAir()) continue;
            s.setBlockState(n & 15, (n >> 8) & 15, (n >> 4) & 15, state, false);
            n++;
        }
        assertTrue(s.getStates().data.palette() instanceof GlobalPalette, "400 states should give a global palette");
        long[] before = LodSections.prints(new LevelChunkSection[]{s});
        for (int i = 0; i < 2000; i++) LodSections.prints(new LevelChunkSection[]{s}); // warm-up
        long[] times = new long[101];
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            LodSections.prints(new LevelChunkSection[]{s});
            times[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(times);
        // the storage is 4096 × 15 bits (~1 000 longs, a few µs); walking the registry's 26 684 states took ~430 µs
        assertTrue(times[50] < 150_000, "a global-palette fingerprint takes " + times[50] / 1000 + " µs: it walks the registry");
        s.setBlockState(15, 15, 15, Blocks.STONE.defaultBlockState(), false);
        assertTrue(before[0] != LodSections.prints(new LevelChunkSection[]{s})[0], "a changed block left the fingerprint as it was");
    }
}
