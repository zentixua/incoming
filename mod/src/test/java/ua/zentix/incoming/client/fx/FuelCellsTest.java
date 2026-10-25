package ua.zentix.incoming.client.fx;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Огонь на клетках топлива — не огонь дерева: дым пожара не считает его дважды. */
class FuelCellsTest {
    @Test
    void fuelCellsAndWhatDripsBelowThemAreFuelNotWood() {
        FuelCells cells = new FuelCells();
        List<BlockPos> crater = List.of(new BlockPos(10, 63, 10), new BlockPos(11, 63, 10), new BlockPos(16, 64, -1));
        List<BlockPos> roof = List.of(new BlockPos(10, 70, 10));
        cells.index(List.of(crater, roof));
        assertTrue(cells.covers(10, 63, 10));
        assertTrue(cells.covers(11, 63, 10));
        // топливо капает вниз в своей колонке: огонь ниже клетки — тоже топливо; выше верхней клетки колонки — нет
        assertTrue(cells.covers(11, 40, 10));
        assertTrue(cells.covers(10, 70, 10));
        assertFalse(cells.covers(10, 71, 10));
        assertFalse(cells.covers(11, 64, 10));
        // соседняя колонка — дерево
        assertFalse(cells.covers(12, 63, 10));
        assertTrue(cells.inChunk(ChunkPos.asLong(0, 0)));
        assertTrue(cells.inChunk(ChunkPos.asLong(1, -1)));
        assertFalse(cells.inChunk(ChunkPos.asLong(1, 0)));
        // воронка погасла — её колонки снова огонь дерева, у другой — топливо
        cells.index(List.of(roof));
        assertFalse(cells.covers(11, 63, 10));
        assertTrue(cells.covers(10, 63, 10));
        assertFalse(cells.inChunk(ChunkPos.asLong(1, -1)));
        cells.clear();
        assertFalse(cells.covers(10, 63, 10));
    }
}
