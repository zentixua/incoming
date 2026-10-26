package ua.zentix.incoming.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Terrain from chunks present on the client (render distance): like a vanilla map, the top block from
 * {@code MOTION_BLOCKING} heightmap (no others arrive on the client) and water depth. Read client world only on the game thread.
 */
final class LoadedChunksTerrain implements TerrainSource {
    /** Water deeper than this has the same map darkness. */
    private static final int MAX_WATER_DEPTH = 16;
    /** Blocks to search downward for a coloured block below transparent ones (glass, light). */
    private static final int MAX_COLORLESS = 8;

    /**
     * Chunks ({@link ChunkPos#asLong}) received since last tick and their world: a tile built beforehand has holes
     * and must be reread, without waiting for the incomplete-tile timer (chunks arrive over seconds after login).
     * Written and read by the game thread.
     */
    private static final Set<Long> arrived = new HashSet<>();
    @Nullable
    private static ClientLevel arrivedIn;

    /** Chunk received by client (NeoForge event from {@code ClientChunkCache.replaceWithPacketData}). */
    static void onChunkLoad(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ClientLevel level)) return;
        if (level != arrivedIn) {
            arrived.clear();
            arrivedIn = level;
        }
        arrived.add(e.getChunk().getPos().toLong());
    }

    /** World exit: do not retain it. */
    static void clearArrivals() {
        arrived.clear();
        arrivedIn = null;
    }

    @Override
    public boolean offThread() {
        return false;
    }

    /** Client chunks change visibly (explosions): reread a visible tile every 30 s. */
    @Override
    public long refreshNanos() {
        return 30_000_000_000L;
    }

    @Override
    public void changes(ClientLevel level, ChunkSink sink) {
        if (arrived.isEmpty()) return;
        if (level == arrivedIn) {
            for (long c : arrived) sink.changed(ChunkPos.getX(c), ChunkPos.getZ(c));
        }
        arrived.clear();
    }

    @Override
    public boolean arrivals() {
        return true;
    }

    @Override
    public Reader open(ClientLevel level) {
        return new Reader() {
            private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            @Nullable
            @Override
            public Column column(int x, int z) {
                if (!level.hasChunk(x >> 4, z >> 4)) return null;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int bottom = Math.max(level.getMinBuildHeight(), top - MAX_COLORLESS);
                for (int y = top - 1; y >= bottom; y--) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    MapColor color = state.getMapColor(level, pos);
                    if (color == MapColor.NONE) continue;
                    return new Column(y + 1, color, state.getFluidState().is(FluidTags.WATER) ? waterDepth(level, x, y, z) : 0);
                }
                return null;
            }

            private int waterDepth(ClientLevel level, int x, int surface, int z) {
                int depth = 1;
                while (depth < MAX_WATER_DEPTH && level.getFluidState(pos.set(x, surface - depth, z)).is(FluidTags.WATER)) depth++;
                return depth;
            }

            @Override
            public void close() {}
        };
    }
}
