package ua.zentix.incoming.client.far;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import ua.zentix.incoming.client.map.TerrainTiles;

import java.util.OptionalInt;

/**
 * Height relief for {@link Sightline} rays on the client: the client's chunks ({@code MOTION_BLOCKING} — with treetops
 * and structures, the client has no other map), further — the relief of the aiming map ({@link TerrainTiles}: with
 * LOD Server Support — everything the server sent, straight from memory; with Distant Horizons — ready tiles, which
 * are read there in the background in advance). Loads and waits for nothing.
 */
public final class FarTerrain {
    private FarTerrain() {}

    public static Sightline.Heights of(ClientLevel level) {
        return (x, z) -> {
            LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
            if (chunk != null) return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
            OptionalInt tile = TerrainTiles.height(x, z);
            return tile.isPresent() ? tile.getAsInt() : Double.NaN;
        };
    }

    /**
     * Surface carrying dense dust: leaves and plants that do not block motion are permeable; follow the column to
     * support below. Solid wood, stone, roofs and water all stop the scan alike. Chunk reads only; no world generation.
     * Cells cache loaded columns for one tick and LOD columns (including unknown cells) for one second. The latter goes
     * through TerrainTiles for LSS, Voxy and DH alike, not just the loaded-chunk branch.
     */
    public static Sightline.Heights dust(ClientLevel level) {
        DustHeightCache cache = DustHeightCache.forWorld(level);
        Sightline.Heights fallback = of(level);
        return (x, z) -> {
            LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
            return cache.at(x, z, level.getGameTime(), chunk == null ? 20 : 1,
                    (cx, cz) -> chunk == null ? fallback.at(cx, cz) : dustY(chunk, cx, cz) + 1);
        };
    }

    static int dustY(LevelChunk chunk, int x, int z) {
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
        return belowCanopy(top, chunk.getMinBuildHeight(), chunk.getBlockState(p).is(net.minecraft.tags.BlockTags.LEAVES), y -> {
            var s = chunk.getBlockState(p.setY(y));
            return !Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(s);
        });
    }

    /** Pure column operation for tests: a full solid is a surface regardless of what it was built for. */
    static int belowCanopy(int top, int bottom, boolean leaves, java.util.function.IntPredicate permeable) {
        if (!leaves) return top;
        int y = top;
        while (y > bottom && permeable.test(y)) y--;
        return y;
    }

    /** Whether water is on top of the column: 1 — water, 0 — land, {@link Double#NaN} — unknown. */
    @FunctionalInterface
    public interface Water {
        double at(int x, int z);
    }

    /**
     * Water on top of the column on the client: in the client's chunks — the top block of the {@code MOTION_BLOCKING}
     * heightmap (water keeps it there) with water; further — water depth in the map relief ({@link TerrainTiles}:
     * client chunks, LOD Server Support, Distant Horizons). Loads and waits for nothing.
     */
    public static Water water(ClientLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return (x, z) -> {
            LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
            if (chunk != null) {
                // heightmap — the first air above the top block
                int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) - 1;
                if (top < chunk.getMinBuildHeight()) return Double.NaN;
                return chunk.getFluidState(pos.set(x, top, z)).is(FluidTags.WATER) ? 1 : 0;
            }
            OptionalInt depth = TerrainTiles.waterDepth(x, z);
            return depth.isPresent() ? (depth.getAsInt() > 0 ? 1 : 0) : Double.NaN;
        };
    }
}
