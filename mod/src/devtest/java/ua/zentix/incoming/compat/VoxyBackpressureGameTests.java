package ua.zentix.incoming.compat;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;

import java.util.UUID;

/** Exercises the player's send wrapper and vanilla Connection write-completion callback, without a client or GPU. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VoxyBackpressureGameTests {
    private VoxyBackpressureGameTests() {}

    @GameTest(template = "range", timeoutTicks = 100, batch = "voxy_backpressure", skyAccess = true)
    public static void playerConnectionPausesAndResumesLods(GameTestHelper h) {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        // Encode only the bulk bytes here: the test is about transport completion, not payload registration/codec.
        MessageToByteEncoder<Packet<?>> encoder = new MessageToByteEncoder<>() {
            @Override
            protected void encode(ChannelHandlerContext context, Packet<?> packet, ByteBuf out) {
                if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof S2C.VoxyChunk lod) {
                    out.writeBytes(lod.sections());
                }
            }
        };
        class SlowChannel extends EmbeddedChannel {
            boolean draining;

            SlowChannel() {
                super(encoder, connection);
            }

            @Override
            protected void doWrite(ChannelOutboundBuffer buffer) throws Exception {
                if (draining) super.doWrite(buffer);
            }
        }
        SlowChannel channel = new SlowChannel();
        try {
            FakePlayer player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "lod-watcher"));
            player.connection = new ServerGamePacketListenerImpl(player.server, connection, player,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
                @Override
                public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
                    connection.send(packet, listener);
                }
            };
            VoxyLods.Viewer viewer = new VoxyLods.Viewer();
            byte[] data = new byte[250 * 1024];
            var dimension = h.getLevel().dimension().location();
            for (int i = 0; i < 8; i++) viewer.offer(i, new S2C.VoxyChunk(dimension, i, 0, data));
            for (int tick = 0; tick < 1200; tick++) viewer.drain(player);
            h.assertTrue(viewer.sent == 1 && viewer.queue.size() == 7, "slow connection did not stop the player drain");
            h.assertFalse(channel.isWritable(), "test transport did not retain the encoded LOD");
            S2C.VoxyChunk fresh = new S2C.VoxyChunk(dimension, 1, 0, new byte[250 * 1024]);
            viewer.offer(1, fresh);
            channel.draining = true;
            channel.flush();
            for (int tick = 0; tick < 8; tick++) viewer.drain(player);
            h.assertTrue(viewer.sent == 8 && viewer.bytes == 0, "Connection completion did not resume delivery");
            int received = 0;
            for (ByteBuf bytes; (bytes = channel.readOutbound()) != null; ) {
                received++;
                bytes.release();
            }
            h.assertTrue(received == 8, "not all chunks reached the transport");
            h.succeed();
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
