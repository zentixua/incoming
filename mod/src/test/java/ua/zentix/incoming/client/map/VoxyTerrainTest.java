package ua.zentix.incoming.client.map;

import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Чтение базы Voxy отражением — на подделках его классов тех же имён ({@code src/test/java/me/cortex/voxy}). */
class VoxyTerrainTest {
    private WorldEngine engine;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void voxy() {
        engine = new WorldEngine();
        WorldEngine.current = engine;
        assertTrue(VoxyTerrain.init(VoxyTerrainTest.class.getClassLoader()), "классы Voxy 0.2.16 найдены");
    }

    @AfterEach
    void gone() {
        WorldEngine.current = null;
    }

    /** Блок в секции уровня {@code lvl} базы подделки: воксель по мировым координатам. */
    private void put(int lvl, int x, int y, int z, int id) {
        int vx = x >> lvl, vy = y >> lvl, vz = z >> lvl;
        long[] s = engine.sections.computeIfAbsent(WorldEngine.key(lvl, vx >> 5, vy >> 5, vz >> 5), k -> new long[VoxyColumns.VOLUME]);
        s[(vy & 31) << 10 | (vz & 31) << 5 | vx & 31] = me.cortex.voxy.common.world.other.Mapper.voxel(id);
    }

    @Test
    void tileReadsItsLevelAndPeekAnswersFromMemory() {
        int grass = engine.mapper.id(Blocks.GRASS_BLOCK.defaultBlockState());
        put(0, 5, 64, 7, grass);
        put(0, -40, 120, -3, grass);
        VoxyTerrain t = new VoxyTerrain(null, -64, 320);
        assertTrue(t.active(), "мир Voxy есть");
        assertNull(t.peek(5, 7), "до чтения в памяти ничего");
        TerrainSource.Reader r = t.open(null, 0);
        assertNotNull(r);
        TerrainSource.Column c = r.column(5, 7);
        assertNotNull(c);
        assertEquals(65, c.height());
        assertEquals(MapColor.GRASS, c.color());
        assertEquals(121, r.column(-40, -3).height(), "отрицательные координаты — свой столбец секций");
        assertNull(r.column(6, 7), "Voxy этого не видел");
        assertEquals(65, t.peek(5, 7).height(), "прочитанное — сразу из памяти");
        assertEquals(0, engine.refs, "acquireRef и releaseRef парами");
        assertEquals(engine.acquired, engine.released, "каждая секция отпущена");
    }

    @Test
    void coarseTileReadsCoarseSections() {
        int stone = engine.mapper.id(Blocks.STONE.defaultBlockState());
        put(4, 100, 64, 100, stone);
        put(0, 100, 70, 100, stone);
        VoxyTerrain t = new VoxyTerrain(null, -64, 320);
        assertTrue(t.active());
        // плитка уровня 4 берёт воксель в 16 блоков, а не блочный
        assertEquals(VoxyColumns.height(4, 64), t.open(null, 4).column(100, 100).height());
        assertEquals(VoxyColumns.height(4, 64), t.peek(100, 100).height(), "известна только грубая");
        t.open(null, 0).column(100, 100);
        assertEquals(71, t.peek(100, 100).height(), "подробная — первой");
    }

    @Test
    void closedVoxyWorldIsNotAFailure() {
        int grass = engine.mapper.id(Blocks.GRASS_BLOCK.defaultBlockState());
        put(0, 0, 64, 0, grass);
        VoxyTerrain t = new VoxyTerrain(null, -64, 320);
        assertTrue(t.active());
        TerrainSource.Reader r = t.open(null, 0);
        engine.live = false;
        assertNull(r.column(0, 0), "мир Voxy закрыт под чтением — не знаем");
        assertFalse(t.describe().contains("off"), "источник не выключен: найдёт мир заново");
    }

    @Test
    void otherVoxyClassesTurnTheSourceOff() {
        assertFalse(VoxyTerrain.init(new ClassLoader(null) {}), "классов нет — источника нет");
        assertFalse(new VoxyTerrain(null, -64, 320).active());
    }

    @Test
    void scanAroundIsNearestFirstAndInsideTheRadius() {
        List<long[]> cols = VoxyTerrain.around(0, 0, 4, 2048);
        assertTrue(cols.get(0)[2] < 512L * 512, "первым — столбец у игрока");
        for (int i = 1; i < cols.size(); i++) assertTrue(cols.get(i)[2] >= cols.get(i - 1)[2]);
        for (long[] c : cols) assertTrue(c[2] <= 2048L * 2048);
        // круг 2048 блоков столбцами по 512: около π·4² ≈ 50
        assertTrue(cols.size() > 40 && cols.size() < 70, "столбцов " + cols.size());
    }

    @Test
    void trimDropsTheFarthest() {
        Map<Long, VoxyTerrain.Section> cache = new HashMap<>();
        VoxyTerrain.Section s = new VoxyTerrain.Section(new int[1024], 0, false);
        for (int i = -10; i < 10; i++) cache.put(VoxyTerrain.key(4, i, -3), s);
        assertEquals(0, VoxyTerrain.trim(cache, 0, 0, 20));
        assertEquals(20 - 9, VoxyTerrain.trim(cache, 0, 0, 10));
        assertTrue(cache.containsKey(VoxyTerrain.key(4, 0, -3)) && cache.containsKey(VoxyTerrain.key(4, -1, -3)), "ближние остались");
        assertFalse(cache.containsKey(VoxyTerrain.key(4, -10, -3)), "дальний ушёл");
    }
}
