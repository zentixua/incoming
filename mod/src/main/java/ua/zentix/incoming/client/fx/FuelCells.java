package ua.zentix.incoming.client.fx;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.List;

/**
 * Burning crater-fuel cells (server {@code warhead/FuelFires}, packet {@code S2C.FuelFire}): their fire belongs to fuel,
 * rather than the burning city. The crater source carries its heat, soot and roar ({@link FireSmoke#pool}); counting it as
 * nuclear city fire ({@link FireWatch}) would add its roar to detonation fires twice. Unsupported fuel drips downward in its
 * column, so fire in that cell's column no higher than it also belongs to fuel. No Minecraft client dependency.
 */
final class FuelCells {
    /** Column (x, z) → cell top in it; column count per chunk. */
    private final Long2IntOpenHashMap columns = new Long2IntOpenHashMap(), chunks = new Long2IntOpenHashMap();

    /** Rebuild from cells of all burning craters (few of them). */
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

    /** Chunk contains fuel cells ({@link #covers} is worth querying). */
    boolean inChunk(long chunk) {
        return !chunks.isEmpty() && chunks.containsKey(chunk);
    }

    /** Fire at (x, y, z) is burning fuel: in a cell's column, no higher than it. */
    boolean covers(int x, int y, int z) {
        long column = BlockPos.asLong(x, 0, z);
        return columns.containsKey(column) && y <= columns.get(column);
    }

    void clear() {
        columns.clear();
        chunks.clear();
    }
}
