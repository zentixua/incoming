package ua.zentix.incoming.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Карта высот из файла чанка ({@link TerrainSurvey#decode}): упаковка как у {@code Heightmap} мира −64..320 (9 бит на
 * колонку, значение — первый воздух минус низ мира, колонка {@code x + 16 z}); верить можно только окончательным блокам
 * в формате 1.18 и новее с длиной по высоте мира.
 */
class TerrainSurveyTest {
    private static final int MIN_Y = -64, HEIGHT = 384;

    /** Файл чанка, как его пишет {@code ChunkSerializer}: статус и карта высот {@code MOTION_BLOCKING}. */
    private static CompoundTag chunk(String status, int[] firstAir, int height) {
        SimpleBitStorage storage = new SimpleBitStorage(Mth.ceillog2(height + 1), 256);
        for (int i = 0; i < 256; i++) storage.set(i, firstAir[i] - MIN_Y);
        CompoundTag maps = new CompoundTag();
        maps.putLongArray(Heightmap.Types.MOTION_BLOCKING.getSerializationKey(), storage.getRaw());
        CompoundTag tag = new CompoundTag();
        tag.putString("Status", status);
        tag.put("Heightmaps", maps);
        return tag;
    }

    private static int[] hill() {
        int[] y = new int[256];
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) y[x + z * 16] = 64 + x * 3 + z - (x == 15 && z == 0 ? 128 : 0);
        return y;
    }

    @Test
    void fullChunkGivesItsHeights() {
        int[] y = hill();
        y[255] = 320; // верх мира: первый воздух над последним блоком
        y[0] = MIN_Y; // пустая колонка: воздух с самого низа
        assertArrayEquals(y, TerrainSurvey.decode(chunk("minecraft:full", y, HEIGHT), Heightmap.Types.MOTION_BLOCKING, MIN_Y, HEIGHT));
    }

    @Test
    void untrustedHeightsAreRejected() {
        int[] y = hill();
        // недогенерированный: карта высот без украшений и построек
        assertNull(TerrainSurvey.decode(chunk("minecraft:features", y, HEIGHT), Heightmap.Types.MOTION_BLOCKING, MIN_Y, HEIGHT));
        // другой вид карты в файле не записан
        assertNull(TerrainSurvey.decode(chunk("minecraft:full", y, HEIGHT), Heightmap.Types.WORLD_SURFACE, MIN_Y, HEIGHT));
        // длина не по высоте мира (мир был другой высоты): упаковка с другим числом бит
        assertNull(TerrainSurvey.decode(chunk("minecraft:full", y, 4064), Heightmap.Types.MOTION_BLOCKING, MIN_Y, HEIGHT));
        // формат до 1.18: высоты от другого низа мира
        CompoundTag old = chunk("minecraft:full", y, HEIGHT);
        CompoundTag level = new CompoundTag();
        level.putString("Status", "full");
        old.remove("Status");
        old.put("Level", level);
        assertNull(TerrainSurvey.decode(old, Heightmap.Types.MOTION_BLOCKING, MIN_Y, HEIGHT));
    }
}
