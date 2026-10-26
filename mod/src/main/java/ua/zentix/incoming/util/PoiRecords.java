package ua.zentix.incoming.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.state.BlockState;

/**
 * POI positions (beds, villager job sites, bells) absent from world POI data. Vanilla does not register buildings
 * inserted outside {@code setBlock} (WorldEdit, map schematics, Sable craft assembled directly in sections); removing
 * such a block through the world ({@code ServerLevel.onBlockStateChange} → {@code PoiManager.remove}) logs PoiSection
 * “POI data mismatch: never registered” per block: over a hundred lines in an evening of salvos on a city map.
 */
public final class PoiRecords {
    private PoiRecords() {}

    /**
     * Register POI for block {@code state} at {@code pos} if absent. Call before world removal: data removal runs as a
     * server task after block change and will find the entry.
     */
    public static void recordIfMissing(ServerLevel level, BlockPos pos, BlockState state) {
        PoiTypes.forState(state).ifPresent(type -> {
            if (level.getPoiManager().getType(pos).isEmpty()) level.getPoiManager().add(pos, type);
        });
    }

    /**
     * The same for a position and its six neighbors. Shape updates remove neighbors with a removed block: bed foot removes
     * head (bed POI is at the head); support removes bell. The neighbor disappears through the world outside the original
     * remover, and absent POI data logs an error (after missiles at houses, adjacent-position pairs on floors). A surviving
     * neighbor remains registered as if placed through the world.
     */
    public static void recordWithNeighbours(ServerLevel level, BlockPos pos, BlockState state) {
        recordIfMissing(level, pos, state);
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            n.setWithOffset(pos, d);
            if (!Terrain.ready(level, n)) continue;
            BlockState s = level.getBlockState(n);
            if (PoiTypes.hasPoi(s)) recordIfMissing(level, n.immutable(), s);
        }
    }
}
