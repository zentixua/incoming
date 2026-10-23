package ua.zentix.incoming.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

    /**
     * Рельеф генератора по узлам через {@link TerrainSurvey#GENERATOR_STEP}: колонка — наибольший из четырёх узлов своей
     * клетки (оценка сверху для склона между узлами); узлы на краю чанка — общие с соседом.
     */
    @Test
    void generatorCellTakesHighestOfFourNodes() {
        int step = TerrainSurvey.GENERATOR_STEP;
        // склон вверх к +x на блок за блок и пик в узле (8, 16)
        java.util.function.IntBinaryOperator node = (x, z) -> x == 8 && z == 16 ? 200 : 60 + x;
        int[] y = TerrainSurvey.cells(0, 0, node);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                int i = x / step * step, j = z / step * step;
                int expect = Math.max(Math.max(node.applyAsInt(i, j), node.applyAsInt(i + step, j)),
                        Math.max(node.applyAsInt(i, j + step), node.applyAsInt(i + step, j + step)));
                assertEquals(expect, y[x + z * 16], "колонка " + x + ", " + z);
            }
        // клетки, которые касаются пика, видят его целиком
        assertEquals(200, y[3 + 12 * 16]);
        assertEquals(200, y[12 + 12 * 16]);
        assertEquals(76, y[12 + 3 * 16]);
    }

    private static final Heightmap.Types TYPE = Heightmap.Types.MOTION_BLOCKING;

    @Test
    void generatorQueueIsBoundedAndDemandPromotesAnExistingPrefetch() {
        TerrainSurvey.Orders jobs = new TerrainSurvey.Orders();
        TerrainSurvey.Order first = new TerrainSurvey.Order(TYPE, 1, false);
        TerrainSurvey.Order needed = new TerrainSurvey.Order(TYPE, 2, false);
        assertTrue(jobs.add(first));
        assertTrue(jobs.add(needed));
        for (int i = 2; i < TerrainSurvey.MAX_QUEUED; i++)
            assertTrue(jobs.add(new TerrainSurvey.Order(TYPE, i + 1, false)));
        assertFalse(jobs.add(new TerrainSurvey.Order(TYPE, -1, true)));
        jobs.promote(needed);
        assertEquals(TerrainSurvey.MAX_QUEUED, jobs.size());
        assertSame(needed, jobs.poll(), "нужный сейчас чанк обходит очередь предзаказа");
        assertSame(first, jobs.poll(), "внутри приоритета — FIFO");
        jobs.remove(needed);
        jobs.remove(first);
        assertEquals(TerrainSurvey.MAX_QUEUED - 2, jobs.size());
    }

    @Test
    void totalPendingIncludesJobsAlreadyWaitingForGenerator() {
        TerrainSurvey survey = new TerrainSurvey();
        for (int i = 0; i < TerrainSurvey.MAX_QUEUED / 2; i++) assertNotNull(survey.order(TYPE, i, false));
        assertNull(survey.order(TYPE, -1, false), "предзаказ оставляет место вопросам датчика");
        for (int i = TerrainSurvey.MAX_QUEUED / 2; i < TerrainSurvey.MAX_QUEUED; i++)
            assertNotNull(survey.order(TYPE, i, true));
        assertNull(survey.order(TYPE, -1, true));
        assertEquals(TerrainSurvey.MAX_QUEUED, survey.pendingCount());
        survey.invalidate(0);
        assertNotNull(survey.order(TYPE, -1, true), "отмена освобождает место");
    }

    @Test
    void unloadInvalidatesEveryTypeAndLateAnswerCannotReplaceItsMap() {
        TerrainSurvey survey = new TerrainSurvey();
        TerrainSurvey.Order motion = survey.order(TYPE, 12, false);
        TerrainSurvey.Order surface = survey.order(Heightmap.Types.WORLD_SURFACE, 12, true);
        survey.invalidate(12); // то же до проверки полноты чанка / hasPrimedHeightmap
        assertEquals(0, survey.pendingCount());
        int[] fresh = hill();
        survey.remember(TYPE, 12, new TerrainSurvey.Heights(fresh, Terrain.Source.DISK, 100));
        survey.finish(motion, new int[256], Terrain.Source.GENERATOR, false);
        survey.finish(surface, new int[256], Terrain.Source.DISK, false);
        survey.publish(101);
        assertSame(fresh, survey.cached(TYPE, 12, 101).y);
        assertNull(survey.cached(Heightmap.Types.WORLD_SURFACE, 12, 101));
        assertEquals(0, survey.pendingCount());
    }

    @Test
    void generatorFailureStaysUnknownUntilRetryEvenWhenAskedEveryTick() {
        TerrainSurvey survey = new TerrainSurvey();
        TerrainSurvey.Order job = survey.order(TYPE, 5, true);
        survey.finish(job, null, Terrain.Source.UNKNOWN, true);
        assertNull(survey.cached(TYPE, 5, 100), "ответ не виден посреди тика");
        survey.publish(100);
        assertEquals(0, survey.pendingCount());
        for (int tick = 100; tick < 100 + TerrainSurvey.RETRY_TICKS; tick++) {
            TerrainSurvey.Heights h = survey.cached(TYPE, 5, tick);
            assertNotNull(h);
            assertNull(h.y);
            assertEquals(Terrain.Source.UNKNOWN, h.source);
            h.used = tick; // at обновляет последнее обращение; это не продлевает retryAt
        }
        assertNull(survey.cached(TYPE, 5, 100 + TerrainSurvey.RETRY_TICKS));
        assertNotNull(survey.order(TYPE, 5, true));
    }

    @Test
    void unloadCacheIsBoundedWithoutWaitingForPeriodicCleanup() {
        TerrainSurvey survey = new TerrainSurvey();
        for (int i = 0; i <= TerrainSurvey.MAX_KNOWN; i++)
            survey.remember(TYPE, i, new TerrainSurvey.Heights(hill(), Terrain.Source.DISK, 100));
        assertNull(survey.cached(TYPE, 0, 100));
        assertNotNull(survey.cached(TYPE, 1, 100));
        assertNotNull(survey.cached(TYPE, TerrainSurvey.MAX_KNOWN, 100));
    }

}
