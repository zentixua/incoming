package ua.zentix.incoming.client.fx;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.List;

/**
 * Клетки горящего топлива воронок (сервер, {@code warhead/FuelFires}, пакет {@code S2C.FuelFire}): огонь на них — топливо,
 * а не горящий город. Его тепло, сажу и гул несёт очаг воронки ({@link FireSmoke#pool}); посчитанный ещё и огнём города
 * ядерки ({@link FireWatch}), он шёл бы в гул пожаров подрыва дважды. Топливо, потерявшее опору, капает вниз в своей
 * колонке, поэтому огонь в колонке клетки не выше её — тоже его. Без Minecraft-клиента.
 */
final class FuelCells {
    /** Колонка (x, z) → верх клетки в ней; колонок в чанке. */
    private final Long2IntOpenHashMap columns = new Long2IntOpenHashMap(), chunks = new Long2IntOpenHashMap();

    /** Заново — по клеткам всех горящих воронок (их немного). */
    void index(Collection<List<BlockPos>> pools) {
        columns.clear();
        chunks.clear();
        for (List<BlockPos> cells : pools) {
            for (BlockPos p : cells) {
                long column = BlockPos.asLong(p.getX(), 0, p.getZ());
                if (columns.containsKey(column)) {
                    columns.put(column, Math.max(columns.get(column), p.getY()));
                } else {
                    columns.put(column, p.getY());
                    chunks.addTo(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4), 1);
                }
            }
        }
    }

    /** В чанке есть клетки топлива ({@link #covers} стоит спрашивать). */
    boolean inChunk(long chunk) {
        return !chunks.isEmpty() && chunks.containsKey(chunk);
    }

    /** Огонь в (x, y, z) — горящее топливо: в колонке клетки и не выше её. */
    boolean covers(int x, int y, int z) {
        long column = BlockPos.asLong(x, 0, z);
        return columns.containsKey(column) && y <= columns.get(column);
    }

    void clear() {
        columns.clear();
        chunks.clear();
    }
}
