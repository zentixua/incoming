package ua.zentix.incoming.compat;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.PacketSendListener;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.S2C;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoxyLodsTest {
    private static S2C.VoxyChunk packet(int x, byte[] data) {
        return new S2C.VoxyChunk(ResourceLocation.withDefaultNamespace("overworld"), x, 0, data);
    }

    /** A real Netty outbound buffer whose transport cannot drain until explicitly released. */
    private static final class SlowChannel extends EmbeddedChannel {
        private boolean draining;

        @Override
        protected void doWrite(ChannelOutboundBuffer buffer) throws Exception {
            if (draining) super.doWrite(buffer);
        }

        void resume() {
            draining = true;
            flush();
        }
    }

    @Test
    void unwritableConnectionKeeps48MiBInOurQueueFor1200Ticks() {
        SlowChannel channel = new SlowChannel();
        try {
            channel.writeAndFlush(Unpooled.buffer(64 * 1024).writeZero(64 * 1024));
            assertFalse(channel.isWritable());
            VoxyLods.Viewer viewer = new VoxyLods.Viewer();
            byte[] data = new byte[64 * 1024];
            for (int i = 0; i < 768; i++) viewer.offer(i, packet(i, data));
            assertEquals(48L << 20, viewer.bytes);
            for (int tick = 0; tick < 1200; tick++) viewer.drain(channel, (p, done) -> fail("blocked connection accepted a LOD"));
            assertEquals(768, viewer.queue.size());
            assertEquals(48L << 20, viewer.bytes);
            assertEquals(0, viewer.sent);
            channel.resume();
            viewer.drain(channel, (p, done) -> done.onSuccess());
            assertEquals(2, viewer.sent, "128 KiB tick budget after the connection resumes");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void waitingForEventLoopCountsAsOutstandingEvenWhileChannelIsWritable() {
        SlowChannel channel = new SlowChannel();
        try {
            VoxyLods.Viewer viewer = new VoxyLods.Viewer();
            byte[] data = new byte[40 * 1024];
            for (int i = 0; i < 20; i++) viewer.offer(i, packet(i, data));
            List<PacketSendListener> writes = new ArrayList<>();
            for (int tick = 0; tick < 1200; tick++) viewer.drain(channel, (p, done) -> writes.add(done));
            assertTrue(channel.isWritable(), "event-loop dispatch has not encoded anything yet");
            assertEquals(1, writes.size());
            assertEquals(19, viewer.queue.size());
            writes.getFirst().onSuccess();
            viewer.drain(channel, (p, done) -> writes.add(done));
            assertEquals(2, writes.size(), "write completion permits progress");
            writes.getLast().onFailure();
            viewer.drain(channel, (p, done) -> writes.add(done));
            assertEquals(3, writes.size(), "failed write also releases its reservation");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void oversizedChunkMakesProgressButCannotBuildAnOutboundBacklog() {
        SlowChannel channel = new SlowChannel();
        try {
            VoxyLods.Viewer viewer = new VoxyLods.Viewer();
            byte[] large = new byte[250 * 1024];
            viewer.offer(1, packet(1, large));
            viewer.offer(2, packet(2, large));
            for (int tick = 0; tick < 1200; tick++) viewer.drain(channel, (p, done) ->
                    channel.writeAndFlush(Unpooled.wrappedBuffer(p.sections())).addListener(future -> {
                        if (future.isSuccess()) done.onSuccess();
                        else done.onFailure();
                    }));
            assertFalse(channel.isWritable());
            assertEquals(1, viewer.sent);
            assertEquals(1, viewer.queue.size());
            assertEquals(250 * 1024L, viewer.bytes);
            channel.resume();
            viewer.drain(channel, (p, done) -> done.onSuccess());
            assertEquals(2, viewer.sent);
            assertEquals(0, viewer.bytes);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void normalTrafficHeadroomAndAbsolutePendingLimitBothApply() {
        SlowChannel channel = new SlowChannel();
        try {
            VoxyLods.Viewer viewer = new VoxyLods.Viewer();
            viewer.offer(1, packet(1, new byte[1024]));
            channel.writeAndFlush(Unpooled.buffer(50 * 1024).writeZero(50 * 1024));
            assertTrue(channel.isWritable());
            viewer.drain(channel, (p, done) -> fail("insufficient game-packet headroom"));
            channel.config().setWriteBufferHighWaterMark(4 * 1024 * 1024);
            assertTrue(channel.bytesBeforeUnwritable() > VoxyLods.WRITE_HEADROOM);
            viewer.drain(channel, (p, done) -> fail("raised watermark bypassed absolute pending limit"));
            channel.resume();
            viewer.drain(channel, (p, done) -> done.onSuccess());
            assertEquals(1, viewer.sent);
            channel.close();
            viewer.offer(2, packet(2, new byte[1024]));
            viewer.drain(channel, (p, done) -> fail("closed channel accepted a LOD"));
            assertEquals(1, viewer.queue.size());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void freshCopyReplacesWaitingCopyAtCapacityAndArrivesAfterResume() {
        SlowChannel channel = new SlowChannel();
        try {
            VoxyLods.Viewer viewer = new VoxyLods.Viewer();
            byte[] data = new byte[64 * 1024];
            for (int i = 0; i < 768; i++) viewer.offer(i, packet(i, data));
            S2C.VoxyChunk fresh = packet(3, new byte[64 * 1024]);
            viewer.offer(3, fresh);
            viewer.offer(900, packet(900, data));
            assertEquals(1, viewer.dropped);
            assertEquals(768, viewer.queue.size());
            assertEquals(48L << 20, viewer.bytes);
            List<S2C.VoxyChunk> received = new ArrayList<>();
            while (!viewer.queue.isEmpty()) viewer.drain(channel, (p, done) -> {
                received.add(p);
                done.onSuccess();
            });
            assertEquals(768, received.size());
            assertSame(fresh, received.stream().filter(p -> p.x() == 3).findFirst().orElseThrow());
            assertEquals(0, viewer.bytes);
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
