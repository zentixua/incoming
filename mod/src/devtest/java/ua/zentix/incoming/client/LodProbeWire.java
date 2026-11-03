package ua.zentix.incoming.client;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;

/** Development-only wire protocol. Never registered or shipped by a release jar. */
@Mod(value = Incoming.MOD_ID, dist = Dist.CLIENT)
public final class LodProbeWire {
    public LodProbeWire(IEventBus bus) {
        if ("lod-buffer".equals(System.getProperty("incoming.scenario"))) bus.addListener(LodProbeWire::register);
    }
    private static void register(RegisterPayloadHandlersEvent e) {
        e.registrar("lod-probe-1").executesOn(HandlerThread.NETWORK)
                .playToClient(Column.TYPE, Column.CODEC, (p, ctx) -> LodBufferScenario.receive(p));
        e.registrar("lod-probe-1").playToServer(Ack.TYPE, Ack.CODEC, (p, ctx) -> LodBufferScenario.ack(p));
    }
    public record Column(int epoch, int phase, long due, int total, int yield, S2C.VoxyChunk column) implements CustomPacketPayload {
        static final Type<Column> TYPE = new Type<>(Incoming.id("test_lod_column"));
        public static final StreamCodec<ByteBuf, Column> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Column::epoch, ByteBufCodecs.VAR_INT, Column::phase,
                ByteBufCodecs.VAR_LONG, Column::due, ByteBufCodecs.VAR_INT, Column::total,
                ByteBufCodecs.VAR_INT, Column::yield, S2C.VoxyChunk.CODEC, Column::column, Column::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Ack(int epoch, int count) implements CustomPacketPayload {
        static final Type<Ack> TYPE = new Type<>(Incoming.id("test_lod_ack"));
        static final StreamCodec<ByteBuf, Ack> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Ack::epoch, ByteBufCodecs.VAR_INT, Ack::count, Ack::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
