package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.util.Terrain;

import java.util.Comparator;
import java.util.UUID;

/**
 * Chunk loading for a nuclear strike — crater footprint and destruction queue edge. Vanilla tickets load and generate
 * chunks in the background (NeoForge {@code forceChunk} would load immediately, stalling the server), are not world-saved,
 * and vanish on restart; callers awaiting a chunk check {@link Terrain#ready}.
 * <p>
 * Blocks can change only with loaded neighbors: Sable reads neighboring blocks on every block change (craft physics),
 * which at the loaded-world edge would synchronously load the neighbor — 30–300 ms per column.
 * <p>
 * Chunks acquired through {@link AreaLoader}: a region ticket would start ticking while neighbors still generate,
 * making a trial spawner load a neighbor synchronously (up to 2.5 s for destruction-queue chunks).
 */
public final class NuclearTickets {
    private static final TicketType<UUID> TYPE = TicketType.create("incoming_nuclear", Comparator.<UUID>naturalOrder());
    /**
     * Separate type for destruction queue: vanilla shares identical tickets (type, level, value), so queue removal would
     * remove a crater ticket on the same chunk.
     */
    private static final TicketType<UUID> SCAR = TicketType.create("incoming_nuclear_scar", Comparator.<UUID>naturalOrder());
    /** Chunk and neighbors fully loaded (neighboring columns and crater edges too). */
    private static final int RADIUS = 1;

    private NuclearTickets() {}

    /** Chunk and all eight neighbors fully loaded. */
    public static boolean neighbourhoodLoaded(ServerLevel level, ChunkPos pos) {
        return neighbourhoodLoaded(level, pos, 1);
    }

    /** Chunk and every chunk within radius {@code r} fully loaded. */
    public static boolean neighbourhoodLoaded(ServerLevel level, ChunkPos pos, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!Terrain.ready(level, pos.x + dx, pos.z + dz)) return false;
            }
        }
        return true;
    }

    /**
     * Chunk in memory: fully loaded, below full loading (visibility edge), or pending unload until its
     * {@code ChunkEvent.Unload}; null — absent from memory or never fully loaded ({@code ChunkEvent.Load} adds it itself).
     * Vanilla immediately removes a ticketless holder from the visible chunk map, unloading the chunk later when save-ready
     * ({@code ChunkMap.processUnloads} → {@code pendingUnloads} → {@code scheduleUnload}); a ticket returning in that time
     * revives the holder from {@code pendingUnloads} with the same chunk and no new {@code ChunkEvent.Load}. Thus “absent
     * from visible map” does not mean “unloaded”: a queue removing it would never see it again — zone chunk left intact,
     * holding the zone tile. AT: {@code ChunkMap.pendingUnloads}, read-only.
     */
    @Nullable
    public static LevelChunk inMemory(ServerLevel level, long pos) {
        return Terrain.inMemory(level, pos);
    }

    /**
     * Blocks read by Sable around a changed position: on block change ({@code LevelChunk.setBlockState}), its neighbors,
     * and solid neighbors' neighbors ({@code VoxelNeighborhoodState}).
     */
    public static final int SABLE_REACH = 2;

    /**
     * All chunks within {@link #SABLE_REACH} blocks of a position loaded (including diagonal corners): Sable would load an
     * unready chunk inside the call (ruin queue tick up to 3.5 s: server thread waits in {@code ColumnScar.replace};
     * GameTest {@code fallenLogNextToUnreadyChunkLoadsNothing}).
     */
    public static boolean aroundLoaded(Level level, BlockPos pos) {
        for (int cx = (pos.getX() - SABLE_REACH) >> 4; cx <= (pos.getX() + SABLE_REACH) >> 4; cx++) {
            for (int cz = (pos.getZ() - SABLE_REACH) >> 4; cz <= (pos.getZ() + SABLE_REACH) >> 4; cz++) {
                if (!Terrain.ready(level, cx, cz)) return false;
            }
        }
        return true;
    }

    public static void hold(ServerLevel level, ChunkPos pos, boolean hold) {
        hold(level, TYPE, pos, hold);
    }

    /**
     * Chunk at the loaded world's edge and surrounding chunks within {@code radius} (neighbors its ruins need),
     * held until the destruction queue processes it ({@link ScarQueue}).
     */
    static void holdForScar(ServerLevel level, ChunkPos pos, boolean hold, int radius) {
        hold(level, SCAR, pos, hold, radius);
    }

    private static void hold(ServerLevel level, TicketType<UUID> type, ChunkPos pos, boolean hold) {
        hold(level, type, pos, hold, RADIUS);
    }

    private static void hold(ServerLevel level, TicketType<UUID> type, ChunkPos pos, boolean hold, int radius) {
        // one area per chunk and type, like one vanilla ticket (type, level, value): repeated hold adds nothing
        AreaLoader.Area area = new AreaLoader.Area(type, pos, radius, new UUID(0L, pos.toLong()));
        if (hold) StrikeWorld.get(level).areas().hold(level, area);
        else StrikeWorld.get(level).areas().release(level, area);
    }
}
