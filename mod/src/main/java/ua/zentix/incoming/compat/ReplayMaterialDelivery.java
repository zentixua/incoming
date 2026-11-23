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
    private static final byte[] MANIFEST = S2C.NukeMaterialSources.TYPE.id().toString().getBytes(StandardCharsets.UTF_8);
    private static final byte[] SYNC = S2C.NukeSync.TYPE.id().toString().getBytes(StandardCharsets.UTF_8);
    private S2C.NukeSync sync;
    private Write syncFrame;
    private int syncPrefix;
    private static final byte[] BIRTH = S2C.NukeDetonation.TYPE.id().toString().getBytes(StandardCharsets.UTF_8);
    private java.util.Set<UUID> active;
    private final LinkedHashMap<UUID, Write> births = new LinkedHashMap<>();
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
        attach(player.connection.getConnection(), player.position());
    }

    static void attach(net.minecraft.network.Connection connection, Vec3 eye) {
        var pipeline = connection.channel().pipeline();
        var handler = (ReplayMaterialDelivery) pipeline.get(NAME);
        if (handler == null) pipeline.addBefore("encoder", NAME, new ReplayMaterialDelivery(eye));
        else handler.eye = eye;
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
            if (VarInt.read(in) != PAYLOAD) { ctx.write(message, promise); return; }
            int length = VarInt.read(in);
            if (matches(in, length, MANIFEST)) {
                active = java.util.Set.copyOf(S2C.NukeMaterialSources.CODEC.decode(in).sources());
            } else if (matches(in, length, SYNC)) {
                int prefix = in.readerIndex() - bytes.readerIndex();
                var packet = S2C.NukeSync.CODEC.decode(in);
                if (!packet.materials().isEmpty()) {
                    if (syncFrame != null) release(syncFrame, null);
                    syncFrame = new Write(bytes, promise);
                    sync = packet;
                    syncPrefix = prefix;
                    if (!scheduled) schedule(ctx);
                    return;
                }
            } else if (matches(in, length, BIRTH)) {
                var id = S2C.NukeDetonation.CODEC.decode(in).id();
                if (id != null && (active == null || !active.contains(id))) {
                    Write previous = births.put(id, new Write(bytes, promise));
                    if (previous != null) release(previous, null);
                    if (!scheduled) schedule(ctx);
                    return;
                }
            } else if (matches(in, length, NAME_BYTES)) {
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

    private static boolean matches(ByteBuf in, int length, byte[] name) {
        if (length != name.length || in.readableBytes() < length) return false;
        for (int i = 0; i < length; i++) if (in.getByte(in.readerIndex() + i) != name[i]) return false;
        in.skipBytes(length);
        return true;
    }
    private void schedule(ChannelHandlerContext ctx) {
        scheduled = true;
        ctx.executor().schedule(() -> {
            scheduled = false;
            // A cold recording can emit its membership after the cached packets. Filter the complete batch
            // before creating client Active objects or using a transfer slot.
            flushSync(ctx);
            births.forEach((id, write) -> {
                if (active == null || active.contains(id)) ctx.writeAndFlush(write.bytes, write.promise);
                else release(write, null);
            });
            births.clear();
            if (active != null) queue.entrySet().removeIf(e -> {
                if (active.contains(e.getKey())) return false;
                discard(e.getValue(), null);
                return true;
            });
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

    private void flushSync(ChannelHandlerContext ctx) {
        if (syncFrame == null) return;
        var frame = syncFrame;
        syncFrame = null;
        if (active == null || active.containsAll(sync.materials().values())) {
            ctx.writeAndFlush(frame.bytes, frame.promise);
        } else {
            var retained = sync.detonations().stream().filter(d -> !sync.materials().containsKey(d.id())
                    || active.contains(sync.materials().get(d.id()))).toList();
            var references = new java.util.HashMap<>(sync.materials());
            references.values().retainAll(active);
            ByteBuf out = ctx.alloc().buffer();
            out.writeBytes(frame.bytes, frame.bytes.readerIndex(), syncPrefix);
            S2C.NukeSync.CODEC.encode(out, new S2C.NukeSync(retained, sync.warnings(), references));
            frame.bytes.release();
            ctx.writeAndFlush(out, frame.promise);
        }
        sync = null;
    }

    private static void discard(Source source, Throwable error) {
        Write w;
        while ((w = source.parts.poll()) != null) release(w, error);
    }
    private static void release(Write w, Throwable error) {
        w.bytes.release();
        if (error == null) w.promise.trySuccess();
        else w.promise.tryFailure(error);
    }
    @Override public void handlerRemoved(ChannelHandlerContext ctx) {
        var closed = new ClosedChannelException();
        queue.values().forEach(s -> discard(s, closed));
        queue.clear();
        births.values().forEach(w -> release(w, closed));
        births.clear();
        if (syncFrame != null) { release(syncFrame, closed); syncFrame = null; }
    }
}
