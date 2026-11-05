package ua.zentix.incoming.client.far;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FarTerrainWaterTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    void surfaceWaterReadsTheActualHeightmapTopNotTheBlockBelow() {
        ClientLevel level = mock(ClientLevel.class);
        ClientChunkCache chunks = mock(ClientChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        FluidState wet = mock(FluidState.class), dry = mock(FluidState.class);
        when(wet.is(FluidTags.WATER)).thenReturn(true);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunk(-2, 1, false)).thenReturn(chunk);
        when(chunk.getMinBuildHeight()).thenReturn(-64);
        // ChunkAccess.getHeight is the top occupied Y, unlike Heightmap.getFirstAvailable.
        when(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, 15, 1)).thenReturn(63);
        FarTerrain.Water surface = FarTerrain.water(level);
        when(chunk.getFluidState(any(BlockPos.class))).thenAnswer(call ->
                ((BlockPos) call.getArgument(0)).getY() == 63 ? wet : dry);
        assertEquals(1, surface.at(-17, 17), 0, "one-block-deep water over dry ground is water");
        when(chunk.getFluidState(any(BlockPos.class))).thenAnswer(call ->
                ((BlockPos) call.getArgument(0)).getY() == 63 ? dry : wet);
        assertEquals(0, surface.at(-17, 17), 0, "solid roof over water is a terrestrial surface");
        verify(chunks, times(2)).getChunk(-2, 1, false);
        when(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, 15, 1)).thenReturn(-65);
        assertTrue(Double.isNaN(surface.at(-17, 17)), "empty columns stay unknown");
    }
}
