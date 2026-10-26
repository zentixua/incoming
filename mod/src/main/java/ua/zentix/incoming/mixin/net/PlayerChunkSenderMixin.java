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
 * Withholds chunks whose nuclear ruins are due but not applied ({@link ChunkSendGate}). They are removed only during
 * send selection and restored afterward, so other chunks can be sent and held chunks remain pending between calls.
 * This is a presentation gate for Incoming's own ruins, not a crash guard.
 * <p>
 * No equivalent public gate was found in NeoForge 21.1.264 / Minecraft 1.21.1:
 * <ul>
 * <li>{@code ChunkWatchEvent.Watch} is not cancellable and fires after queuing; {@code Sent} fires after the packet.
 * Dropping a queued chunk makes {@code ChunkMap.isChunkTracked} treat it as received (in view and not pending),
 * allowing entity and block updates before the client has the chunk.</li>
 * <li>{@code ChunkHolder.addSendDependency} is public, and NeoForge exposes {@code getVisibleChunkIfPresent}.
 * It can defer a whole chunk, but is not an equivalent per-player filter: {@code collectChunksToSend} selects the
 * nearest quota of pending chunks before checking readiness, so held chunks can starve unrelated ready chunks.
 * A player entering view while that dependency is pending also skips queuing; completing a dependency added after
 * ticking promotion does not itself call {@code ChunkMap.onChunkReadyToSend} to queue that player.</li>
 * <li>{@code ChunkEvent.Load} cannot be cancelled or supplied an asynchronous completion barrier. Waiting there
 * blocks loading; world interaction can deadlock before FULL. Ruins need asynchronous neighbor snapshots and
 * budgeted server-thread placement, including for chunks already FULL. Tickets request a minimum loading level;
 * an Incoming ticket cannot veto a player's stronger ticket. The standard generation/loading pyramids provide
 * no event to insert our asynchronous step before FULL.</li>
 * <li>Resending {@code ClientboundLevelChunkWithLightPacket} after placement cannot retract an intact chunk already
 * sent to the client: the city can still visibly turn into ruins.</li>
 * </ul>
 * Remove when NeoForge provides a retryable pre-send filter that keeps held chunks pending, skips them before quota
 * selection and queues new watchers correctly, or an equivalent public path is verified for already-loaded chunks.
 * Retained under the CL4 fallback, not as a general exception to the no-vanilla-mixins rule. Only Incoming's overdue
 * ruins are held, for at most {@code ScarQueue.WITHHOLD_LIMIT} ticks from the first request after the wave (200 in
 * this version); that existing fail-open limit can still expose an intact chunk if placement cannot finish.
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
        // If the previous call threw between HEAD and RETURN, restore its held chunks before selecting again.
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
