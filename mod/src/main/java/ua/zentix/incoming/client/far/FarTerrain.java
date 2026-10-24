package ua.zentix.incoming.client.far;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import ua.zentix.incoming.client.map.TerrainTiles;

import java.util.OptionalInt;

/**
 * Высоты рельефа для лучей {@link Sightline} на клиенте: чанки клиента ({@code MOTION_BLOCKING} — с кронами и
 * постройками, другой карты у клиента нет), дальше — рельеф карты наведения ({@link TerrainTiles}: с LOD Server Support
 * — всё, что прислал сервер, сразу из памяти; с Distant Horizons — готовые плитки, которые читаются там в фоне заранее).
 * Ничего не грузит и не ждёт.
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
     * Surface carrying dense dust: leaves and plants that do not block motion are permeable; follow the column to support below.
     * Solid wood, stone, roofs and water all stop the scan alike. Chunk reads only; no world generation.
     * The frame-local cache also bounds repeated column scans while rebuilding a puff's path after a rewind.
     */
    public static Sightline.Heights dust(ClientLevel level) {
        java.util.Map<Long, Integer> cache = new java.util.HashMap<>();
        long[] tick = {Long.MIN_VALUE};
        Sightline.Heights fallback = of(level);
        return (x, z) -> {
            if (tick[0] != level.getGameTime()) { cache.clear(); tick[0] = level.getGameTime(); }
            LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
            if (chunk == null) return fallback.at(x, z);
            long key = ((long) x << 32) ^ (z & 0xffffffffL);
            return cache.computeIfAbsent(key, k -> dustY(chunk, x, z) + 1);
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

    /** Вода ли сверху колонки: 1 — вода, 0 — суша, {@link Double#NaN} — неизвестно. */
    @FunctionalInterface
    public interface Water {
        double at(int x, int z);
    }

    /**
     * Вода сверху колонки на клиенте: в чанках клиента — верхний блок карты высот {@code MOTION_BLOCKING} (вода её держит)
     * с водой, дальше — глубина воды в рельефе карты ({@link TerrainTiles}: чанки клиента, LOD Server Support, Distant Horizons).
     * Ничего не грузит и не ждёт.
     */
    public static Water water(ClientLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return (x, z) -> {
            LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
            if (chunk != null) {
                // карта высот — первый воздух над верхним блоком
                int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) - 1;
                if (top < chunk.getMinBuildHeight()) return Double.NaN;
                return chunk.getFluidState(pos.set(x, top, z)).is(FluidTags.WATER) ? 1 : 0;
            }
            OptionalInt depth = TerrainTiles.waterDepth(x, z);
            return depth.isPresent() ? (depth.getAsInt() > 0 ? 1 : 0) : Double.NaN;
        };
    }
}
