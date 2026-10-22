package ua.zentix.incoming.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Прямая видимость между двумя точками над рельефом: закрывают её земля и постройки — колонка, чей верх выше прямой
 * там, где прямая над ней проходит. Знают только готовые чанки (карта высот {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES}):
 * колонка в неготовом чанке прямую не закрывает — чанков не грузить ({@code CLAUDE.md}). Листва не закрывает: карта
 * высот без листвы. Нависающий край (арка, навес) колонка считает сплошной до верха.
 */
public final class LineOfSight {
    private LineOfSight() {}

    /** Верх колонки неизвестен: чанк не готов. */
    public static final int UNKNOWN = Integer.MIN_VALUE;

    /** Первый воздух над колонкой (x, z) или {@link #UNKNOWN}. */
    @FunctionalInterface
    public interface Columns {
        int top(int x, int z);
    }

    /**
     * Ничто из известного не закрывает прямую {@code from → to}: каждая колонка, которую она пересекает (по сетке, без
     * пропусков на срезанных углах — {@link GridWalk}), ниже прямой на всём её отрезке над колонкой.
     */
    public static boolean clear(Vec3 from, Vec3 to, Columns columns) {
        double dy = to.y - from.y;
        return GridWalk.walk(from.x, from.z, to.x, to.z, 1, (GridWalk.SpanVisitor) (x, z, t0, t1) -> {
            int top = columns.top(x, z);
            // прямая над колонкой — отрезок, ниже всего она на одном из его концов
            return top == UNKNOWN || Math.min(from.y + dy * t0, from.y + dy * t1) >= top;
        }) >= 1;
    }

    /** {@link #clear(Vec3, Vec3, Columns)} по картам высот готовых чанков мира. */
    public static boolean clear(ServerLevel level, Vec3 from, Vec3 to) {
        return clear(from, to, new ReadyColumns(level));
    }

    /** Карты высот готовых чанков: прямая идёт по колонкам подряд, чанк ищется один раз на 16 колонок. */
    private static final class ReadyColumns implements Columns {
        private final ServerLevel level;
        private int cx = Integer.MIN_VALUE, cz;
        private LevelChunk chunk;

        ReadyColumns(ServerLevel level) {
            this.level = level;
        }

        @Override
        public int top(int x, int z) {
            if (x >> 4 != cx || z >> 4 != cz) {
                cx = x >> 4;
                cz = z >> 4;
                chunk = level.getChunkSource().getChunkNow(cx, cz);
            }
            return chunk == null ? UNKNOWN : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        }
    }
}
