package ua.zentix.incoming.client.map;

import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Верх колонок из чанков LSS — как у слоя чанков клиента ({@link LoadedChunksTerrain}), и их хранилище. */
class LssColumnsTest {
    @BeforeAll
    static void boot() {
        // как S2CGoldenTest: FML в юнит-тесте нет — загрузке флагов NeoForge пустой список модов
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    /** Секции чанка с одной колонкой (x, z), заданной снизу вверх с {@code fromY}; остальные колонки — воздух. */
    private static LssColumns.Section[] column(int minSection, int sections, int x, int z, int fromY, BlockState... blocks) {
        BlockState[][] states = new BlockState[sections][4096];
        for (int i = 0; i < blocks.length; i++) {
            int y = fromY + i - minSection * 16;
            states[y >> 4][(y & 15) << 8 | z << 4 | x] = blocks[i];
        }
        LssColumns.Section[] out = new LssColumns.Section[sections];
        for (int s = 0; s < sections; s++) {
            BlockState[] st = states[s];
            boolean any = false;
            for (BlockState b : st) any |= b != null;
            if (!any) continue;
            out[s] = new LssColumns.Section() {
                @Override
                public boolean empty() {
                    return false;
                }

                @Override
                public BlockState block(int bx, int by, int bz) {
                    BlockState b = st[by << 8 | bz << 4 | bx];
                    return b == null ? Blocks.AIR.defaultBlockState() : b;
                }
            };
        }
        return out;
    }

    @Nullable
    private static TerrainSource.Column top(LssColumns.Section[] sections, int minSection, int x, int z) {
        return LssColumns.column(LssColumns.summarize(minSection, sections)[z * 16 + x]);
    }

    /** Земля: высота — первый воздух над верхним блоком (как карта высот), цвет — его цвет на карте. */
    @Test
    void groundTopAndColour() {
        var s = column(-4, 12, 3, 5, 60, Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState());
        TerrainSource.Column c = top(s, -4, 3, 5);
        assertEquals(63, c.height());
        assertEquals(MapColor.GRASS, c.color());
        assertEquals(0, c.waterDepth());
        assertNull(top(s, -4, 4, 5), "колонка без блоков — неизвестно");
    }

    /** Трава и цветы не держат карту высот MOTION_BLOCKING (её же берёт слой чанков): верх — блок под ними; листва держит. */
    @Test
    void plantsDoNotCountLeavesDo() {
        var grass = column(0, 8, 0, 0, 64, Blocks.DIRT.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState());
        assertEquals(65, top(grass, 0, 0, 0).height());
        assertEquals(MapColor.DIRT, top(grass, 0, 0, 0).color());
        var tree = column(0, 8, 0, 0, 64, Blocks.DIRT.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState());
        assertEquals(67, top(tree, 0, 0, 0).height(), "крона — преграда для лучей");
    }

    /** Стекло держит карту высот, но цвета на карте у него нет: цвет и высота — первого блока с цветом под ним. */
    @Test
    void colourlessTopLooksBelow() {
        var s = column(0, 8, 0, 0, 70, Blocks.STONE.defaultBlockState(), Blocks.GLASS.defaultBlockState());
        TerrainSource.Column c = top(s, 0, 0, 0);
        assertEquals(71, c.height());
        assertEquals(MapColor.STONE, c.color());
    }

    /** Вода: глубина до дна, не больше 16; через пустую секцию (LSS её не шлёт) верх находится. */
    @Test
    void waterDepthAndGaps() {
        var pond = column(0, 8, 2, 2, 60, Blocks.SAND.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.WATER.defaultBlockState());
        TerrainSource.Column c = top(pond, 0, 2, 2);
        assertEquals(64, c.height());
        assertEquals(MapColor.WATER, c.color());
        assertEquals(3, c.waterDepth());
        BlockState[] sea = new BlockState[30];
        java.util.Arrays.fill(sea, Blocks.WATER.defaultBlockState());
        sea[0] = Blocks.SAND.defaultBlockState();
        assertEquals(LssColumns.MAX_WATER_DEPTH, top(column(0, 8, 0, 0, 30, sea), 0, 0, 0).waterDepth());
        // камень в секции 0, секция 1 пустая (null), листва в секции 2
        var gap = column(0, 4, 1, 1, 5, Blocks.STONE.defaultBlockState());
        var leaves = column(0, 4, 1, 1, 40, Blocks.OAK_LEAVES.defaultBlockState());
        gap[2] = leaves[2];
        assertEquals(41, top(gap, 0, 1, 1).height());
    }

    /** Ниже нуля и высоко — упаковка без потерь; ничего — 0. */
    @Test
    void packing() {
        int p = LssColumns.pack(-63, MapColor.DEEPSLATE.id, 0);
        assertTrue(LssColumns.present(p));
        assertEquals(-63, LssColumns.height(p));
        assertEquals(MapColor.DEEPSLATE.id, LssColumns.color(p));
        p = LssColumns.pack(320, MapColor.WATER.id, 16);
        assertEquals(320, LssColumns.height(p));
        assertEquals(16, LssColumns.waterDepth(p));
        assertFalse(LssColumns.present(0));
        assertNull(LssColumns.column(0));
    }

    private static int[] chunk(int base) {
        int[] c = new int[LssColumns.FULL];
        for (int i = 0; i < c.length; i++) c[i] = LssColumns.pack(base + i, MapColor.STONE.id, 0);
        return c;
    }

    /** Грубый чанк — середины клеток 4×4 (колонки плитки уровня 2): любая колонка клетки отвечает её серединой. */
    @Test
    void coarseKeepsCellCentres() {
        int[] full = chunk(0), small = LssColumns.coarse(full);
        assertEquals(LssColumns.COARSE, small.length);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(full[((z & ~3) + 2) * 16 + (x & ~3) + 2], LssColumns.at(small, x, z));
                assertEquals(full[z * 16 + x], LssColumns.at(full, x, z));
            }
        }
    }

    /** Хранилище: пришедшее заменяет прежнее, с диска — не затирает пришедшее; отрицательные координаты на месте. */
    @Test
    void storeReplacesAndFindsChunks() {
        LssColumns.Store s = new LssColumns.Store();
        s.put(-1, -33, chunk(10));
        assertEquals(LssColumns.at(chunk(10), -1, -513), s.get(-1, -513));
        assertEquals(0, s.get(16, 0), "чанка нет — неизвестно");
        s.put(-1, -33, chunk(20));
        assertFalse(s.putIfAbsent(-1, -33, chunk(30)));
        assertEquals(LssColumns.at(chunk(20), -16, -528), s.get(-16, -528));
        assertEquals(1, s.chunks());
        assertEquals(1, s.fullChunks());
    }

    /** Подробных сверх предела — грубыми самые дальние от игрока; ближние остаются подробными. */
    @Test
    void coarsensFarthestFirst() {
        LssColumns.Store s = new LssColumns.Store();
        for (int cx = 0; cx < 10; cx++) s.put(cx, 0, chunk(cx));
        assertEquals(0, s.coarsen(0, 0, 10, 5), "в пределе — ничего");
        assertEquals(5, s.coarsen(0, 0, 8, 5));
        assertEquals(5, s.fullChunks());
        assertEquals(10, s.chunks());
        for (int cx = 0; cx < 5; cx++) assertEquals(LssColumns.FULL, s.chunk(cx, 0).length, "ближний подробный");
        for (int cx = 5; cx < 10; cx++) assertEquals(LssColumns.COARSE, s.chunk(cx, 0).length, "дальний грубый");
        s.put(9, 0, chunk(9));
        assertEquals(6, s.fullChunks(), "пришёл снова — подробный");
    }

    /** Чанков всего сверх предела — уходят самые дальние от игрока, подробные и грубые одинаково. */
    @Test
    void trimsFarthestChunks() {
        LssColumns.Store s = new LssColumns.Store();
        for (int cx = -5; cx < 5; cx++) s.put(cx, 0, chunk(cx));
        s.coarsen(0, 0, 4, 4);
        assertEquals(0, s.trim(0, 0, 10, 6), "в пределе — ничего");
        assertEquals(4, s.trim(0, 0, 8, 6));
        assertEquals(6, s.chunks());
        for (int cx = -3; cx < 3; cx++) assertTrue(s.chunk(cx, 0) != null, "ближний остался");
        assertNull(s.chunk(-5, 0));
        assertNull(s.chunk(4, 0));
        assertEquals(4, s.fullChunks(), "подробные (ближние) не тронуты");
        s.put(4, 0, chunk(4));
        assertEquals(7, s.chunks(), "пришёл снова — на месте");
    }

    /** Файл: всё, что было (и грубое), читается тем же; чужой файл — ошибка, а не мусор в карте. */
    @Test
    void fileRoundTrip() throws IOException {
        LssColumns.Store s = new LssColumns.Store();
        s.put(0, 0, chunk(1));
        s.put(-40, 70, chunk(2));
        s.coarsen(0, 0, 1, 1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        s.write(new DataOutputStream(bytes));
        LssColumns.Store back = new LssColumns.Store();
        assertEquals(2, back.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        assertArrayEquals(s.chunk(0, 0), back.chunk(0, 0));
        assertArrayEquals(s.chunk(-40, 70), back.chunk(-40, 70));
        assertEquals(1, back.fullChunks());
        byte[] junk = new byte[16];
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> new LssColumns.Store().read(new DataInputStream(new ByteArrayInputStream(junk))));
    }
}
