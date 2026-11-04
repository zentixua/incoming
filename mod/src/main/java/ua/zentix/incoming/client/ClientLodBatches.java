package ua.zentix.incoming.client;

import io.netty.util.AttributeKey;
import net.minecraft.client.multiplayer.ChunkBatchSizeCalculator;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import ua.zentix.incoming.client.replay.Replay;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.S2C;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.TimeUnit;

/**
 * Vanilla's receive-rate measurement on the network thread. Measure transfer at the finish marker; waiting for
 * held columns' physical deadlines is backpressure, not a slower connection. Acknowledgement still waits for
 * client capacity and Voxy's queue; jobs already running remain bounded by its worker pool. State belongs to the connection.
 */
public final class ClientLodBatches {
    private static final AttributeKey<ClientLodBatches> STATE = AttributeKey.valueOf("incoming:lod_batches");
    private final ChunkBatchSizeCalculator calculator;
    private int id;
    private boolean active, finished;
    private int chunks;

    ClientLodBatches() { this(new ChunkBatchSizeCalculator()); }

    ClientLodBatches(ChunkBatchSizeCalculator calculator) { this.calculator = calculator; }

    void begin(int batch) {
        id = batch;
        active = true;
        finished = false;
        calculator.onBatchStart();
    }

    boolean end(S2C.LodBatchFinished packet) {
        if (!active || finished || id != packet.id()) return false;
        chunks = packet.chunks();
        finished = true;
        calculator.onBatchFinished(chunks);
        return true;
    }

    @Nullable
    C2S.LodBatchReceived acknowledge(boolean backlogged) {
        if (!active || !finished || backlogged) return null;
        active = false;
        return new C2S.LodBatchReceived(id, calculator.getDesiredChunksPerTick());
    }

    static void start(S2C.LodBatchStart packet, IPayloadContext context) {
        if (Replay.active()) return;
        var attribute = context.connection().channel().attr(STATE);
        ClientLodBatches state = attribute.get();
        if (state == null) {
            state = new ClientLodBatches();
            attribute.set(state);
        }
        state.begin(packet.id());
    }

    static void finish(S2C.LodBatchFinished packet, IPayloadContext context) {
        if (Replay.active()) return;
        ClientLodBatches state = context.connection().channel().attr(STATE).get();
        if (state == null || !state.end(packet)) return;
        state.replyWhenReady(context, packet.id());
    }

    private void replyWhenReady(IPayloadContext context, int batch) {
        var channel = context.connection().channel();
        if (!channel.isActive() || !active || id != batch) return;
        boolean busy = VoxyIngest.backlogged() || ClientHorizon.full();
        C2S.LodBatchReceived ack = acknowledge(busy);
        if (ack != null) {
            if (!VoxyIngest.available()) context.reply(new C2S.LodMods(false));
            context.reply(ack);
        } else {
            // Poll service completion independently of the game tick. A 50 ms polling floor becomes part of
            // vanilla's receive-rate sample and falsely throttles a batch that drained in a few milliseconds.
            // There is still only one outstanding poll per connection; never block either thread.
            channel.eventLoop().schedule(() -> replyWhenReady(context, batch), 2, TimeUnit.MILLISECONDS);
        }
    }

}
