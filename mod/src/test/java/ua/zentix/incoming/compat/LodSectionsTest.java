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
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The light of a copy of a chunk not from the world ({@link CopyLight}) and its bytes for Voxy ({@link LodSections}): the ruins open the sky to
 *  rooms, the extinguished lamp does not shine, the sections and light reach the client the same, and the air of the demolished tower is air.
 */
class LodSectionsTest {
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
        for (var key : List.of(Biomes.PLAINS, Biomes.DESERT)) {
            r.register(key, new Biome.BiomeBuilder().hasPrecipitation(false).temperature(0.8f).downfall(0.4f)
                    .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0xc0d8ff).waterColor(0x3f76e4).waterFogColor(0x050533).skyColor(0x78a7ff).build())
                    .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build(), RegistrationInfo.BUILT_IN);
        }
        biomes = r.freeze();
    }

    /** Chunk in the 4 section (y 0–63) according to the model: what to put in each place. */
    private interface Fill {
        BlockState at(int x, int y, int z);
    }

    private static LevelChunkSection[] sections(Fill fill) {
        LevelChunkSection[] s = new LevelChunkSection[4];
        for (int i = 0; i < s.length; i++) {
            s[i] = new LevelChunkSection(biomes);
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState b = fill.at(x, i * 16 + y, z);
                if (!b.isAir()) s[i].setBlockState(x, y, z, b, false);
            }
        }
        return s;
    }

    /** Stone floor on y 0–9, house: walls and roof on y 20 above the square 4..11, air inside. */
    private static BlockState house(int x, int y, int z, boolean roof) {
        if (y < 10) return Blocks.STONE.defaultBlockState();
        boolean inside = x >= 4 && x <= 11 && z >= 4 && z <= 11;
        boolean wall = inside && (x == 4 || x == 11 || z == 4 || z == 11) && y <= 20;
        if (wall || roof && inside && y == 20) return Blocks.STONE_BRICKS.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
    }

    @Test
    void ruinsOpenTheSkyToRooms() {
        CopyLight whole = CopyLight.of(sections((x, y, z) -> house(x, y, z, true)), true);
        CopyLight ruined = CopyLight.of(sections((x, y, z) -> house(x, y, z, false)), true);
        assertEquals(15, whole.sky(0, 30, 0), "above the house is the sky");
        assertEquals(15, whole.sky(0, 10, 0), "the earth outside the house is the sky");
        assertEquals(0, whole.sky(8, 12, 8), "there is no sky in a closed house");
        assertEquals(0, whole.sky(8, 5, 8), "there is no sky in stone");
        assertEquals(15, ruined.sky(8, 12, 8), "there is no roof - there is sky in the house");
    }

    @Test
    void skyLightSpreadsSidewaysUnderAnOverhang() {
        // canopy on y 20 over all x ≥ 8: below it the light of the sky falls on 1 a block from the edge
        CopyLight l = CopyLight.of(sections((x, y, z) -> y < 10 ? Blocks.STONE.defaultBlockState()
                : y == 20 && x >= 8 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState()), true);
        assertEquals(15, l.sky(7, 15, 8));
        assertEquals(14, l.sky(8, 15, 8));
        assertEquals(10, l.sky(12, 15, 8));
        assertEquals(8, l.sky(14, 15, 8));
    }

    @Test
    void waterDimsTheSkyByOnePerBlock() {
        CopyLight l = CopyLight.of(sections((x, y, z) -> y < 10 ? Blocks.STONE.defaultBlockState()
                : y < 20 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState()), true);
        assertEquals(15, l.sky(8, 20, 8));
        assertEquals(14, l.sky(8, 19, 8));
        assertEquals(5, l.sky(8, 10, 8));
    }

    @Test
    void anUnlitLampDoesNotLight() {
        BlockState lit = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true);
        BlockState unlit = Blocks.REDSTONE_LAMP.defaultBlockState();
        CopyLight on = CopyLight.of(sections((x, y, z) -> x == 8 && y == 30 && z == 8 ? lit : Blocks.AIR.defaultBlockState()), false);
        CopyLight off = CopyLight.of(sections((x, y, z) -> x == 8 && y == 30 && z == 8 ? unlit : Blocks.AIR.defaultBlockState()), false);
        assertEquals(15, on.block(8, 30, 8));
        assertEquals(14, on.block(9, 30, 8));
        assertEquals(10, on.block(8, 35, 8));
        assertEquals(0, off.block(9, 30, 8), "the extinguished lamp of the quarter does not shine");
        assertEquals(0, on.sky(8, 40, 8), "without sky the dimension of light has no sky");
    }

    @Test
    void sectionsAndLightReachTheClientUnchanged() {
        LevelChunkSection[] s = sections((x, y, z) -> house(x, y, z, true));
        ProtoChunk copy = new ProtoChunk(new ChunkPos(-3, 7), UpgradeData.EMPTY, LevelHeightAccessor.create(0, 64), biomes, null);
        for (int i = 0; i < s.length; i++) copy.getSections()[i] = s[i];
        byte[] data = LodSections.ofCopy(copy, true);
        List<LodSections.Section> back = LodSections.read(data, biomes);
        CopyLight light = CopyLight.of(s, true);
        assertEquals(4, back.size());
        for (int i = 0; i < 4; i++) {
            LodSections.Section b = back.get(i);
            assertEquals(i, b.y());
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                assertEquals(s[i].getBlockState(x, y, z), b.blocks().getBlockState(x, y, z));
                assertEquals(light.sky(x, i * 16 + y, z), b.skyLight() == null ? 0 : b.skyLight().get(x, y, z), "sky " + x + " " + (i * 16 + y) + " " + z);
            }
        }
        // top section - open air: no blocks, sky light 15 no layer in bytes
        assertTrue(back.get(3).blocks().hasOnlyAir());
        DataLayer top = back.get(3).skyLight();
        assertNotNull(top);
        assertEquals(15, top.get(3, 3, 3));
        assertNull(back.get(3).blockLight());
        // house in 4 section: stone, walls and light - less than 20 KB (light - only where it is not uniform)
        assertTrue(data.length < 20_000, data.length + " byte");
    }
}
