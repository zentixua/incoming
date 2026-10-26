package ua.zentix.incoming.strike;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AreaLoaderReleaseTest {
    private static final TicketType<UUID> TYPE = TicketType.create("incoming_test_release_scan", Comparator.<UUID>naturalOrder());
    private ServerLevel level;
    private MinecraftServer server;
    private ServerChunkCache chunks;
    private ChunkMap map;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void world() throws ReflectiveOperationException {
        level = mock(ServerLevel.class);
        server = mock(MinecraftServer.class);
        chunks = mock(ServerChunkCache.class);
        map = mock(ChunkMap.class);
        var field = ServerChunkCache.class.getDeclaredField("chunkMap");
        field.setAccessible(true);
        field.set(chunks, map);
        when(level.getServer()).thenReturn(server);
        when(level.getChunkSource()).thenReturn(chunks);
        when(map.getDistanceManager()).thenReturn(mock(DistanceManager.class));
        when(server.isRunning()).thenReturn(true);
    }

    @Test
    void readyReleaseQueuesWithoutScanningGeneration() {
        when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(mock(LevelChunk.class));
        for (boolean ticks : List.of(true, false)) {
            AreaLoader areas = new AreaLoader();
            var area = new AreaLoader.Area(TYPE, new ChunkPos(0, 0), 2, UUID.randomUUID(), ticks);
            areas.hold(level, area);
            clearInvocations(map);
            areas.release(level, area);
            assertEquals(1, areas.releasing(), "ready area stays loaded through its RELEASE ticket");
            verify(map, never()).getUpdatingChunkIfPresent(anyLong());
        }
    }

    @Test
    void loadingReleaseRetainsCanceledGeneration() {
        AreaLoader areas = new AreaLoader();
        var area = new AreaLoader.Area(TYPE, new ChunkPos(0, 0), 2, UUID.randomUUID());
        areas.hold(level, area);
        ChunkHolder holder = busyHolder(area.centre());
        clearInvocations(map);
        areas.release(level, area);
        assertEquals(0, areas.releasing());
        verify(map, atLeastOnce()).getUpdatingChunkIfPresent(anyLong());
        assertEquals(1, areas.inFlight(level));
        when(holder.getGenerationRefCount()).thenReturn(0);
        assertEquals(0, areas.inFlight(level));
    }

    @Test
    void shutdownStillScansAReadyArea() {
        when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(mock(LevelChunk.class));
        AreaLoader areas = new AreaLoader();
        var area = new AreaLoader.Area(TYPE, new ChunkPos(0, 0), 2, UUID.randomUUID());
        areas.hold(level, area);
        busyHolder(area.centre());
        when(server.isRunning()).thenReturn(false);
        areas.release(level, area);
        assertEquals(0, areas.releasing());
        assertEquals(1, areas.inFlight(level));
    }

    private ChunkHolder busyHolder(ChunkPos pos) {
        ChunkHolder holder = mock(ChunkHolder.class);
        when(holder.getGenerationRefCount()).thenReturn(1);
        when(map.getUpdatingChunkIfPresent(pos.toLong())).thenReturn(holder);
        return holder;
    }
}
