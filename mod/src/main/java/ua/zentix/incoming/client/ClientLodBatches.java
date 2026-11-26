package ua.zentix.incoming.client;

import io.netty.util.AttributeKey;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.replay.Replay;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.LodDiag;
import ua.zentix.incoming.net.S2C;

import java.util.concurrent.TimeUnit;

/**
 * Acknowledges Voxy LOD batch marks on the network thread that ingested their chunks, cumulatively (the newest mark
 * covers every earlier one). Several batches are in flight; an acknowledgement waits only while Voxy's ingest queue is
 * deeper than LSS's own halt mark ({@link VoxyIngest#INGEST_HALT_SECTIONS}), so the server sends no faster than Voxy
 * takes sections in. Jobs already running remain bounded by Voxy's worker pool. State belongs to the connection.
 */
public final class ClientLodBatches {
    private static final AttributeKey<ClientLodBatches> STATE = AttributeKey.valueOf("incoming:lod_batches");
    /** Newest mark received and newest acknowledged; marks arrive in order on one connection. */
    private int marked, acknowledged;
    private boolean polling;
    /** {@link LodDiag}: when the oldest unacknowledged mark arrived, polls while Voxy was behind, its deepest queue. */
    private long diagSince;
    private int diagPolls, diagQueueMax;

    ClientLodBatches() {}

    /** A later mark than any before; stale or repeated marks are ignored. */
    boolean mark(S2C.LodBatchFinished packet) {
        if (packet.id() <= marked) return false;
        if (LodDiag.ON) {
            if (marked == acknowledged) diagSince = LodDiag.now();
            LodDiag.log("cli mark batch=%d chunks=%d bytes=%d ingestCallMs=%.1f voxyQueue=%d", packet.id(), packet.chunks(),
                    VoxyIngest.diagBytes(), VoxyIngest.diagIngestNanos() / 1e6, VoxyIngest.taskCount());
            VoxyIngest.diagReset();
        }
        marked = packet.id();
        return true;
    }

    /** Acknowledgement of every mark so far, unless nothing new arrived or Voxy is behind. */
    @Nullable
    C2S.LodBatchReceived acknowledge(boolean backlogged) {
        if (marked == acknowledged || backlogged) return null;
        acknowledged = marked;
        if (LodDiag.ON) {
            LodDiag.log("cli ack batch=%d waitMs=%d polls=%d voxyQueueMax=%d", acknowledged, LodDiag.now() - diagSince, diagPolls, diagQueueMax);
            diagPolls = 0;
            diagQueueMax = 0;
        }
        return new C2S.LodBatchReceived(acknowledged);
    }

    static void finish(S2C.LodBatchFinished packet, IPayloadContext context) {
        if (Replay.active()) return;
        var attribute = context.connection().channel().attr(STATE);
        ClientLodBatches state = attribute.get();
        if (state == null) {
            state = new ClientLodBatches();
            attribute.set(state);
        }
        if (state.mark(packet)) state.replyWhenReady(context);
    }

    private void replyWhenReady(IPayloadContext context) {
        var channel = context.connection().channel();
        if (!channel.isActive()) return;
        boolean busy = VoxyIngest.backlogged();
        if (LodDiag.ON && busy) {
            diagPolls++;
            diagQueueMax = Math.max(diagQueueMax, VoxyIngest.taskCount());
        }
        C2S.LodBatchReceived ack = acknowledge(busy);
        if (ack != null) {
            if (!VoxyIngest.available()) context.reply(new C2S.LodMods(false));
            context.reply(ack);
        } else if (busy && !polling) {
            // One poll per tick, on the event loop: never wait/block here or queue work on the game thread.
            polling = true;
            channel.eventLoop().schedule(() -> {
                polling = false;
                replyWhenReady(context);
            }, 50, TimeUnit.MILLISECONDS);
        }
    }
}
