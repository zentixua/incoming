package ua.zentix.incoming.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

/** Map terrain source: ground top and colour in each column. */
interface TerrainSource {
    /**
     * Column top: first air height above ground (as in a heightmap), top block's map colour, and water depth
     * above the bottom (0 means land).
     */
    record Column(int height, MapColor color, int waterDepth) {}

    /** Column reader for one tile; owns its caches, closed after the tile. */
    interface Reader extends AutoCloseable {
        /** Column (x, z), or null if nothing is known about it. */
        @Nullable
        Column column(int x, int z);

        @Override
        void close();
    }

    /** Whether reads may run off the game thread (otherwise tiles are built gradually within frames). */
    boolean offThread();

    /**
     * Useful concurrent background reads while the map waits only for this source: as many as its own read threads
     * (more would wait in its own queue). Not needed by a source read on the game thread.
     */
    default int parallelism() {
        return 1;
    }

    /** Reader for the client's current world; null if this source is unavailable there. */
    @Nullable
    Reader open(ClientLevel level);

    /**
     * Reader for a level {@code detail} tile (column at the centre of a {@code 2^detail}-block cell): a source with coarse
     * data (Voxy) reads that level rather than individual blocks; others do not need the level.
     */
    @Nullable
    default Reader open(ClientLevel level, int detail) {
        return open(level);
    }

    /** Time before rebuilding a complete terrain tile (source may also report changes: {@link #changes}). */
    long refreshNanos();

    /** Chunk whose terrain the source updated. */
    @FunctionalInterface
    interface ChunkSink {
        void changed(int chunkX, int chunkZ);
    }

    /** Once per tick on the game thread: chunks in world {@code level} updated by the source since last call. */
    default void changes(ClientLevel level, ChunkSink sink) {}

    /**
     * {@link #changes} reports chunks whose data appeared (arrived on the client), rather than changed:
     * only tiles with holes need rereading.
     */
    default boolean arrivals() {
        return false;
    }

    /**
     * Column immediately from source memory, any thread, without waiting; null if unknown or unsupported (then use
     * ready tiles). Rays, sound and the nuke ({@code TerrainTiles.height}) ask this first.
     */
    @Nullable
    default Column peek(int x, int z) {
        return null;
    }

    /**
     * Source operates in this world (for distant sources: server distributes terrain). Use the first active distant
     * source by preference ({@code TerrainTiles}); the others remain idle.
     */
    default boolean active() {
        return true;
    }

    /** Source operates but has supplied no terrain yet: map shows {@link #pendingNote}. */
    default boolean waiting() {
        return false;
    }

    /** Map label key while source waits ({@link #waiting} or its reads are queued). */
    default String pendingNote() {
        return "incoming.map.far_pending";
    }

    /** Once per tick, game thread: all prior source data may have changed (cache read), so reread every tile. */
    default boolean invalidated(ClientLevel level) {
        return false;
    }

    /** Once per tick, game thread, while the layer is active: player is here (source may prefetch around them). */
    default void tickNear(double playerX, double playerZ) {}

    /** Client world changed: source no longer needed, end its background work. */
    default void close() {}

    /** For logs: data supplied since world entry (columns, failures and reasons). */
    default String describe() {
        return "";
    }
}
