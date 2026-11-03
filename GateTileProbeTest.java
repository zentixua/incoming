package ua.zentix.incoming.compat;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.S2C;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Temporary MT-B1 transport experiment, not a rendered Voxy integration test. */
class GateTileProbeTest {
    @Test
    void onePreparedTileCannotActivateWhileItsTransportIsBlocked() {
        class Channel extends EmbeddedChannel {
            boolean draining;
            @Override protected void doWrite(ChannelOutboundBuffer buffer) throws Exception {
                if (draining) super.doWrite(buffer);
            }
        }
        Channel channel = new Channel();
        try {
            var viewer = new VoxyLods.Viewer();
            var tile = new S2C.VoxyChunk(ResourceLocation.withDefaultNamespace("overworld"), 20, 20, new byte[4096]);
            channel.writeAndFlush(Unpooled.buffer(65536).writeZero(65536));
            viewer.offer(20, tile); // Earliest delivery has arrived; the real sender still needs transport.
            List<S2C.VoxyChunk> delivered = new ArrayList<>();
            for (int tick = 0; tick < 100; tick++) viewer.drain(channel, (p, done) -> {
                if (p instanceof S2C.VoxyChunk c) delivered.add(c);
                if (done != null) done.onSuccess();
            });
            assertTrue(delivered.isEmpty());
            assertEquals(4096, viewer.bytes);
            channel.draining = true;
            channel.flush();
            viewer.drain(channel, (p, done) -> {
                if (p instanceof S2C.VoxyChunk c) delivered.add(c);
                if (done != null) done.onSuccess();
            });
            assertEquals(List.of(tile), delivered);
            assertEquals(0, viewer.bytes);
            System.out.println("MT_B1_TILE: one 4096-byte tile, 100 overdue drain attempts, zero delivered while blocked; one after drain. Transport delivery is not hidden client staging or render activation.");
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
