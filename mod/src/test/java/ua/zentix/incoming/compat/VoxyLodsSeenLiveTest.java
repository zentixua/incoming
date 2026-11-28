package ua.zentix.incoming.compat;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.S2C;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link VoxyLods.Viewer#offer} skips a chunk whose bytes equal the last ones
 * queued, but the client's Voxy also rebuilds a chunk from the player's own view of it (vanilla chunk packets), and
 * nothing tells the viewer so.
 * <p>
 * Scenario: district outage 1, the dark copy D of a chunk outside the world goes to player P. P walks into the district;
 * the lights come back while P is there (in place, P's Voxy now holds the lit live chunk); P leaves, the chunk saves
 * the same disk sections as before. Outage 2: the dark copy D' built from that disk is byte-identical to D and is not
 * queued, so P's LOD keeps the lit lamps through the whole second outage.
 * <p>
 * The server forgets the chunk when it sends it to the player itself ({@code VoxyLods.onChunkSent}).
 */
class VoxyLodsSeenLiveTest {
    private static S2C.VoxyChunk packet(byte[] data) {
        return new S2C.VoxyChunk(ResourceLocation.withDefaultNamespace("overworld"), 7, 0, data);
    }

    @Test
    void aChunkThePlayerSawLiveIsSentAgainWithTheSameBytes() {
        VoxyLods.Viewer viewer = new VoxyLods.Viewer(null, (p, l) -> {});
        byte[] dark = new byte[30_000];
        dark[11] = 5;
        viewer.offer(7, packet(dark));
        viewer.queue.clear(); // delivered
        viewer.bytes = 0;
        // P sees chunk 7 live while its lamps come back: its Voxy now holds the lit chunk.
        viewer.forget(7); // what the chunk-sent hook does (GameTest voxy_seen holds the hook itself)
        viewer.offer(7, packet(dark.clone())); // outage 2: the same dark copy
        assertEquals(1, viewer.queue.size(), "the second outage's dark copy was not queued: P's Voxy keeps the lit lamps");
    }
}
