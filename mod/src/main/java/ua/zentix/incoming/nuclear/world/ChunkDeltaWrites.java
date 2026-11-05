package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Shared in-place section writes and chunk metadata publication, independent of material ownership. */
final class ChunkDeltaWrites {
    private static final Heightmap.Types[] HEIGHTMAP_TYPES = RuinPlan.HEIGHTMAP_TYPES;

    static BlockState set(LevelChunkSection[] sections, long[] touched, long[] wasEmpty, int section,
                          int x, int y, int z, BlockState state) {
        touch(sections, touched, wasEmpty, section);
        return sections[section].setBlockState(x, y, z, state, false);
    }

    static void touch(LevelChunkSection[] sections, long[] touched, long[] wasEmpty, int section) {
        long bit = 1L << (section & 63);
        int word = section >>> 6;
        if ((touched[word] & bit) == 0) {
            touched[word] |= bit;
            if (sections[section].hasOnlyAir()) wasEmpty[word] |= bit;
        }
    }

    static void sectionStatus(ServerLevel level, LevelChunk chunk, long[] touched, long[] wasEmpty) {
        var sections = chunk.getSections();
        var light = level.getChunkSource().getLightEngine();
        for (int i = 0; i < sections.length; i++) {
            long bit = 1L << (i & 63);
            if ((touched[i >>> 6] & bit) == 0) continue;
            boolean empty = sections[i].hasOnlyAir();
            if (((wasEmpty[i >>> 6] & bit) != 0) != empty)
                light.updateSectionStatus(SectionPos.of(chunk.getPos(), chunk.getSectionYFromSectionIndex(i)), empty);
        }
    }

    static Delivery delivery(ServerLevel level, LevelChunk chunk) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(chunk.getPos().toLong());
        List<ServerPlayer> players = holder == null ? List.of() : level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false);
        return new Delivery(chunk, holder, players, !players.isEmpty() && holder.getTickingChunk() != null);
    }

    record Delivery(LevelChunk chunk, @Nullable ChunkHolder holder, List<ServerPlayer> players, boolean sections) {
        void changed(BlockPos at) { if (sections) holder.blockChanged(at); }
        void finish() {
            if (!sections) for (ServerPlayer player : players) player.connection.chunkSender.markChunkPendingToSend(chunk);
        }
    }

    static void rescanColumns(LevelChunk chunk, int[] top) {
        int minY = chunk.getMinBuildHeight();
        ChunkSkyLightSources sky = chunk.getSkyLightSources();
        Heightmap[] maps = new Heightmap[HEIGHTMAP_TYPES.length];
        for (int i = 0; i < maps.length; i++) maps[i] = chunk.getOrCreateHeightmapUnprimed(HEIGHTMAP_TYPES[i]);
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos upper = new BlockPos.MutableBlockPos(), lower = new BlockPos.MutableBlockPos();
        for (int column = 0; column < 256; column++) {
            int t = top[column];
            if (t == Integer.MIN_VALUE) continue;
            int lx = column & 15, lz = column >> 4, x = x0 + lx, z = z0 + lz;
            // search values at/below highest plan position (above belongs to unchanged block over plan)
            int open = 0;
            for (int i = 0; i < maps.length; i++) if (maps[i].getFirstAvailable(lx, lz) <= t + 1) open |= 1 << i;
            boolean skyOpen = sky.get(column) <= t + 1;
            BlockState above = chunk.getBlockState(upper.set(x, t + 1, z));
            for (int y = t; y >= minY - 1 && (open != 0 || skyOpen); y--) {
                BlockState below = chunk.getBlockState(lower.set(x, y, z));
                for (int i = 0; open != 0 && i < maps.length; i++) {
                    if ((open & 1 << i) != 0 && y >= minY && HEIGHTMAP_TYPES[i].isOpaque().test(below)) {
                        maps[i].setHeight(lx, lz, y + 1);
                        open &= ~(1 << i);
                    }
                }
                if (skyOpen) {
                    upper.set(x, y + 1, z);
                    if (below.getLightBlock(chunk, lower) != 0 || Shapes.faceShapeOccludes(LightEngine.getOcclusionShape(chunk, upper, above, Direction.DOWN),
                            LightEngine.getOcclusionShape(chunk, lower, below, Direction.UP))) {
                        sky.set(column, y + 1);
                        skyOpen = false;
                    }
                }
                above = below;
            }
            for (int i = 0; i < maps.length; i++) if ((open & 1 << i) != 0) maps[i].setHeight(lx, lz, minY);
            if (skyOpen) sky.set(column, minY - 1);
        }
    }

    static void checkLight(LevelLightEngine light, ChunkPos pos, long[] at) {
        if (at.length == 0) return;
        // block engine always present; absent means another mod computes light (Starlight, ScalableLux): task would be empty
        if (light instanceof ThreadedLevelLightEngine threaded && light.blockEngine != null && vanilla(light.blockEngine, BlockLightEngine.class)
                && vanilla(light.skyEngine, SkyLightEngine.class)) {
            var block = light.blockEngine;
            var sky = light.skyEngine;
            threaded.addTask(pos.x, pos.z, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, () -> {
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                for (long a : at) {
                    p.set(a);
                    block.checkBlock(p);
                    if (sky != null) sky.checkBlock(p);
                }
            });
            return;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (long a : at) light.checkBlock(p.set(a));
    }

    private static boolean vanilla(@Nullable Object engine, Class<?> type) {
        return engine == null || engine.getClass() == type;
    }

    private ChunkDeltaWrites() {}
}
