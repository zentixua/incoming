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
 * Свет копии чанка не из мира ({@link CopyLight}) и её байты для Voxy ({@link LodSections}): руины открывают небо
 * комнатам, погашенная лампа не светит, секции и свет доходят до клиента теми же, а воздух снесённой башни — воздухом.
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

    /** Чанк в 4 секции (y 0–63) по образцу: что поставить в каждое место. */
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

    /** Пол из камня на y 0–9, дом: стены и крыша на y 20 над квадратом 4..11, внутри — воздух. */
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
        assertEquals(15, whole.sky(0, 30, 0), "над домом — небо");
        assertEquals(15, whole.sky(0, 10, 0), "у земли вне дома — небо");
        assertEquals(0, whole.sky(8, 12, 8), "в закрытом доме неба нет");
        assertEquals(0, whole.sky(8, 5, 8), "в камне неба нет");
        assertEquals(15, ruined.sky(8, 12, 8), "крыши нет — в доме небо");
    }

    @Test
    void skyLightSpreadsSidewaysUnderAnOverhang() {
        // навес на y 20 над всем x ≥ 8: под ним свет неба падает на 1 за блок от края
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
        assertEquals(0, off.block(9, 30, 8), "погашенная лампа квартала не светит");
        assertEquals(0, on.sky(8, 40, 8), "без неба у измерения света неба нет");
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
                assertEquals(light.sky(x, i * 16 + y, z), b.skyLight() == null ? 0 : b.skyLight().get(x, y, z), "небо " + x + " " + (i * 16 + y) + " " + z);
            }
        }
        // верхняя секция — воздух под открытым небом: без блоков, свет неба 15 без слоя в байтах
        assertTrue(back.get(3).blocks().hasOnlyAir());
        DataLayer top = back.get(3).skyLight();
        assertNotNull(top);
        assertEquals(15, top.get(3, 3, 3));
        assertNull(back.get(3).blockLight());
        // дом в 4 секции: камень, стены и свет — меньше 20 КБ (свет — только там, где он не однороден)
        assertTrue(data.length < 20_000, data.length + " байт");
    }
}
