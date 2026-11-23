package ua.zentix.incoming.compat;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.net.S2C;

import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Flashback sends its entire seek snapshot in one tick, including raw replay payloads. Pace only our material
 * frames between the play encoder and compression. The event loop owns the queue and also drains while paused. */
final class ReplayMaterialDelivery extends ChannelOutboundHandlerAdapter {
    private static final String NAME = "incoming_replay_material";
    private static final byte[] NAME_BYTES = S2C.NukeMaterial.TYPE.id().toString().getBytes(StandardCharsets.UTF_8);
    private static final int PAYLOAD = packetId();
    private final LinkedHashMap<UUID, Source> queue = new LinkedHashMap<>();
    private volatile Vec3 eye;
    private boolean scheduled;

    private record Write(ByteBuf bytes, ChannelPromise promise) {}
    private static final class Source {
        final ArrayDeque<Write> parts = new ArrayDeque<>();
        int x, z;
    }

    ReplayMaterialDelivery(Vec3 eye) { this.eye = eye; }

    static void attach(ServerPlayer player) {
        var pipeline = player.connection.getConnection().channel().pipeline();
        var handler = (ReplayMaterialDelivery) pipeline.get(NAME);
        if (handler == null) pipeline.addBefore("encoder", NAME, new ReplayMaterialDelivery(player.position()));
        else handler.eye = player.position();
    }

    private static int packetId() {
        int[] id = {-1};
        GameProtocols.CLIENTBOUND_TEMPLATE.listPackets((type, i) -> {
            if (type.equals(CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD)) id[0] = i;
        });
        return id[0];
    }

    @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        if (message instanceof ByteBuf bytes && bytes.isReadable()
                && bytes.getUnsignedByte(bytes.readerIndex()) == (PAYLOAD < 128 ? PAYLOAD : (PAYLOAD & 127) | 128)) {
            ByteBuf in = bytes.duplicate();
            if (VarInt.read(in) == PAYLOAD && VarInt.read(in) == NAME_BYTES.length && matches(in)) {
                UUID id = new UUID(in.readLong(), in.readLong());
                int part = VarInt.read(in);
                Source source = queue.computeIfAbsent(id, unused -> new Source());
                if (part == 0) {
                    // A second seek restarts this same immutable snapshot. Release its superseded unsent suffix.
                    discard(source, null);
                    VarInt.read(in); // remaining batches
                    VarInt.read(in); // batch length
                    if (in.readableBytes() >= 9) {
                        in.skipBytes(1); // material format
                        source.x = in.readInt();
                        source.z = in.readInt();
                    }
                }
                source.parts.addLast(new Write(bytes, promise));
                if (!scheduled) schedule(ctx);
                return;
            }
        }
        ctx.write(message, promise);
    }

    private static boolean matches(ByteBuf in) {
        if (in.readableBytes() < NAME_BYTES.length) return false;
        for (byte b : NAME_BYTES) if (in.readByte() != b) return false;
        return true;
    }
    private void schedule(ChannelHandlerContext ctx) {
        scheduled = true;
        ctx.executor().schedule(() -> {
            scheduled = false;
            Vec3 at = eye;
            var next = queue.entrySet().stream().min(java.util.Comparator.comparingDouble(e ->
                    Math.hypot(e.getValue().x - at.x, e.getValue().z - at.z))).orElse(null);
            if (next == null) return;
            Source source = next.getValue();
            Write write = source.parts.removeFirst();
            if (source.parts.isEmpty()) queue.remove(next.getKey());
            ctx.writeAndFlush(write.bytes, write.promise);
            if (!queue.isEmpty()) schedule(ctx);
        }, 50, TimeUnit.MILLISECONDS);
    }

    private static void discard(Source source, Throwable error) {
        Write w;
        while ((w = source.parts.poll()) != null) {
            w.bytes.release();
            if (error == null) w.promise.trySuccess();
            else w.promise.tryFailure(error);
        }
    }
    @Override public void handlerRemoved(ChannelHandlerContext ctx) {
        var closed = new ClosedChannelException();
        queue.values().forEach(s -> discard(s, closed));
        queue.clear();
    }
}
