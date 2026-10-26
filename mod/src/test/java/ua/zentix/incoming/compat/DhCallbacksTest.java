package ua.zentix.incoming.compat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DhCallbacksTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    void idleCallbacksDoNotPostTasksAndTheLastWorldDisablesPosting() throws ReflectiveOperationException {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isRunning()).thenReturn(true);
        ServerLevel first = world(server), second = world(server);
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getPos()).thenReturn(new ChunkPos(0, 0));
        // Exercise the adapter's real no-DH path without a loader or a global static mock.
        var state = DhChunks.class.getDeclaredField("state");
        state.setAccessible(true);
        int previous = state.getInt(null);
        state.setInt(null, -1);
        try {
            DhUpdates.saved(first, 0, 0);
            verify(server, never()).execute(any(Runnable.class));
            DhUpdates.testSink(first, (l, c) -> {});
            DhUpdates.testSink(second, (l, c) -> {});
            DhUpdates.sendHeld(first, chunk);
            DhUpdates.sendHeld(second, chunk);
            DhUpdates.testSink(first, null);
            DhUpdates.saved(second, 0, 0);
            verify(server).execute(any(Runnable.class));
            clearInvocations(server);
            DhUpdates.testSink(second, null);
            DhUpdates.saved(first, 0, 0);
            DhUpdates.saved(second, 0, 0);
            verify(server, never()).execute(any(Runnable.class));
        } finally {
            DhUpdates.testSink(first, null);
            DhUpdates.testSink(second, null);
            state.setInt(null, previous);
        }
    }

    private static ServerLevel world(MinecraftServer server) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        when(level.getData(ModAttachments.DH_UPDATES)).thenReturn(new DhUpdates());
        return level;
    }
}
