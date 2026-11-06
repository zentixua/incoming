package ua.zentix.incoming.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a chunk's region file holds data, before asking the chunk I/O worker. Vanilla opens (and so creates) the region
 * file for every read: a 1 Mt zone reads headers of half a million chunks, most of them never generated, and left 516
 * empty {@code .mca} files in a 56-region world; settlement then took the empty files for saved regions and scanned their
 * positions. A chunk of a region without data reads as absent. A region a chunk save went to this session counts as
 * having data at once ({@link #onSave}): its write may still be queued in the I/O worker, which then answers the read.
 */
public final class RegionFiles {
    /** Region files seen with data: they never lose it, so only absent answers are checked again. */
    private static final java.util.Set<Path> WITH_DATA = ConcurrentHashMap.newKeySet();

    private RegionFiles() {}

    /** Region folder of the level (any thread). */
    public static Path folder(ServerLevel level) {
        return DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
    }

    /** Region file of the chunk holds data (file size; a positive answer is remembered). */
    public static boolean present(Path folder, int chunkX, int chunkZ) {
        Path file = folder.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
        if (WITH_DATA.contains(file)) return true;
        if (!hasData(file)) return false;
        WITH_DATA.add(file);
        return true;
    }

    /** A chunk saved into a region (queued for writing or written): reads of that region go to the I/O worker. */
    public static void onSave(net.neoforged.neoforge.event.level.ChunkDataEvent.Save e) {
        if (e.getLevel() instanceof ServerLevel level) written(level, e.getChunk().getPos());
    }

    /** Chunk data queued for writing into its region: reads of that region go to the I/O worker from now on. */
    public static void written(ServerLevel level, ChunkPos pos) {
        WITH_DATA.add(folder(level).resolve("r." + (pos.x >> 5) + "." + (pos.z >> 5) + ".mca"));
    }

    /** Direct chunk write past {@code ChunkMap.save} (which fires the save event), e.g. a test fixture written to disk. */
    public static CompletableFuture<Void> write(ServerLevel level, ChunkPos pos, CompoundTag data) {
        written(level, pos);
        return level.getChunkSource().chunkMap.write(pos, data);
    }

    /** Region file exists and is not empty (an empty one is what a read of a never-saved region leaves). */
    static boolean hasData(Path file) {
        try {
            return Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code ChunkMap.read} without creating a region file for a chunk that was never saved. */
    public static CompletableFuture<Optional<CompoundTag>> read(ServerLevel level, ChunkPos pos) {
        return read(level.getChunkSource().chunkMap, folder(level), pos);
    }

    /** Same with a captured chunk map and folder (background threads). */
    public static CompletableFuture<Optional<CompoundTag>> read(net.minecraft.server.level.ChunkMap map, Path folder, ChunkPos pos) {
        return present(folder, pos.x, pos.z) ? map.read(pos) : CompletableFuture.completedFuture(Optional.empty());
    }
}
