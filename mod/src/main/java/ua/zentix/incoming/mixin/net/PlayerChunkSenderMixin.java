package ua.zentix.incoming.mixin.net;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.zentix.incoming.nuclear.world.ChunkSendGate;

/**
 * Chunks whose ruins are due but not applied are withheld from the player ({@link ChunkSendGate}): while selecting
 * chunks to send, remove them from the player's queue and restore them afterward — they go out once ruins are applied.
 * <p>
 * Why a mixin. There is no public way to defer a chunk send: NeoForge's {@code ChunkWatchEvent.Watch} fires when the chunk
 * is already in the player's queue; it can be removed ({@code PlayerChunkSender.dropChunk}), but vanilla then considers
 * the player to have the chunk ({@code ChunkMap.isChunkTracked}: in view and not queued) — and sends entities and block
 * changes for a chunk absent from the client, for every mod in the pack. Sending an intact chunk followed by ruin packets
 * shows a whole city suddenly turning into ruins before the player's eyes. Applying ruins before the chunk becomes full
 * is also impossible: the plan reads neighbors (from disk) and is built on background threads. The mixin does not change
 * other chunk sends: without a detonation, one empty ruin queue check; it removes only chunks reached by the wave whose
 * ruins the mod has not yet applied, for at most {@code ScarQueue.WITHHOLD_LIMIT} ticks — they remain in the player's queue.
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin implements ChunkSendGate.Gated {
    @Shadow
    @Final
    private LongSet pendingChunks;

    @Unique
    private LongArrayList incoming$held;

    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void incoming$withhold(ServerPlayer player, CallbackInfo ci) {
        // previous call did not finish (exception between HEAD and RETURN): restore chunks removed then to the queue
        if (incoming$held != null) pendingChunks.addAll(incoming$held);
        incoming$held = ChunkSendGate.withhold(player.serverLevel(), pendingChunks);
    }

    @Inject(method = "sendNextChunks", at = @At("RETURN"))
    private void incoming$restore(ServerPlayer player, CallbackInfo ci) {
        if (incoming$held == null) return;
        pendingChunks.addAll(incoming$held);
        incoming$held = null;
    }
}
