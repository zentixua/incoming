package ua.zentix.incoming.client;

import io.netty.util.AttributeKey;
import net.minecraft.client.multiplayer.ChunkBatchSizeCalculator;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import ua.zentix.incoming.client.replay.Replay;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.S2C;

/**
 * Vanilla's receive-rate measurement, on the same network thread as LOD ingestion. Start and finish surround both
 * transfer and ingestion, including a slow downstream behind a proxy. State belongs to the connection, not a world.
 */
public final class ClientLodBatches {
    private static final AttributeKey<ClientLodBatches> STATE = AttributeKey.valueOf("incoming:lod_batches");
    private final ChunkBatchSizeCalculator calculator = new ChunkBatchSizeCalculator();
    private int id;
    private boolean active;

    private ClientLodBatches() {}

    static void start(S2C.LodBatchStart packet, IPayloadContext context) {
        if (Replay.active()) return;
        var attribute = context.connection().channel().attr(STATE);
        ClientLodBatches state = attribute.get();
        if (state == null) {
            state = new ClientLodBatches();
            attribute.set(state);
        }
        state.id = packet.id();
        state.active = true;
        state.calculator.onBatchStart();
    }

    static void finish(S2C.LodBatchFinished packet, IPayloadContext context) {
        if (Replay.active()) return;
        ClientLodBatches state = context.connection().channel().attr(STATE).get();
        if (state == null || !state.active || state.id != packet.id()) return;
        state.active = false;
        state.calculator.onBatchFinished(packet.chunks());
        context.reply(new C2S.LodBatchReceived(packet.id(), state.calculator.getDesiredChunksPerTick()));
    }
}
