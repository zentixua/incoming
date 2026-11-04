package ua.zentix.incoming.client;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.lod.TimedLodBuffer;
import ua.zentix.incoming.net.S2C;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ClientHorizonTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test void aFullOldDimensionRetiresWithoutWaitingForAnotherTerrainPacket() throws ReflectiveOperationException {
        ClientLevel first = mock(ClientLevel.class), second = mock(ClientLevel.class);
        var packet = new S2C.VoxyChunk(ResourceLocation.withDefaultNamespace("overworld"), 0, 0,
                new byte[0], Long.MAX_VALUE, 1);
        ClientHorizon.reset().join();
        try {
            assertTrue(ClientHorizon.offer(first, packet));
            var field = ClientHorizon.class.getDeclaredField("QUEUE");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var queue = (TimedLodBuffer<S2C.VoxyChunk>) field.get(null);
            // Charge the production capacity without allocating a 96 MiB test payload.
            var ticket = queue.reserve(2, Long.MAX_VALUE, ClientHorizon.QUEUED_BYTES - queue.bytes(), 1, 1);
            assertNotNull(ticket);
            assertTrue(queue.complete(ticket, packet));
            assertTrue(ClientHorizon.full());
            var receiver = new ClientLodBatches();
            receiver.begin(7);
            assertTrue(receiver.end(new S2C.LodBatchFinished(7, 2)));
            assertNull(receiver.acknowledge(ClientHorizon.full()));

            ClientHorizon.tick(second, 0); // no new B packet can arrive while A's batch is unacknowledged
            ClientHorizon.barrier().join(); // only our submitted calls; no foreign renderer fence
            assertFalse(ClientHorizon.full());
            assertEquals(0, ClientHorizon.bytes());
            assertNotNull(receiver.acknowledge(ClientHorizon.full()));
            assertTrue(ClientHorizon.offer(second, packet));
        } finally {
            ClientHorizon.reset().join();
        }
    }
}
