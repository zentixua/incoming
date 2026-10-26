package ua.zentix.incoming.client.fx;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fire on fuel cells is not wood fire: the smoke of a fire does not count it twice. */
class FuelCellsTest {
    @Test
    void fuelCellsAndWhatDripsBelowThemAreFuelNotWood() {
        FuelCells cells = new FuelCells();
        List<BlockPos> crater = List.of(new BlockPos(10, 63, 10), new BlockPos(11, 63, 10), new BlockPos(16, 64, -1));
        List<BlockPos> roof = List.of(new BlockPos(10, 70, 10));
        cells.index(List.of(crater, roof));
        assertTrue(cells.covers(10, 63, 10));
        assertTrue(cells.covers(11, 63, 10));
        // the fuel drips down in its column: the fire below the cell is also fuel; above the top cell of the column - no
        assertTrue(cells.covers(11, 40, 10));
        assertTrue(cells.covers(10, 70, 10));
        assertFalse(cells.covers(10, 71, 10));
        assertFalse(cells.covers(11, 64, 10));
        // adjacent column - tree
        assertFalse(cells.covers(12, 63, 10));
        assertTrue(cells.inChunk(ChunkPos.asLong(0, 0)));
        assertTrue(cells.inChunk(ChunkPos.asLong(1, -1)));
        assertFalse(cells.inChunk(ChunkPos.asLong(1, 0)));
        // crater has gone out - its speakers are wood fire again, the other has fuel
        cells.index(List.of(roof));
        assertFalse(cells.covers(11, 63, 10));
        assertTrue(cells.covers(10, 63, 10));
        assertFalse(cells.inChunk(ChunkPos.asLong(1, -1)));
        cells.clear();
        assertFalse(cells.covers(10, 63, 10));
    }
}
