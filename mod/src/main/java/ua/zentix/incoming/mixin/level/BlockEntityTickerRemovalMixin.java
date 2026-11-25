package ua.zentix.incoming.mixin.level;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.Iterator;
import java.util.List;

/**
 * Removed block-entity tickers leave the level's ticker list in one pass per tick instead of one {@code ArrayList}
 * removal each (vanilla bug MC-117075: each removal shifts the rest of the list, so a tick costs removed × tickers).
 * <p>
 * Why it is ours: a nuclear wave crossing a city in view replaces hundreds of the client's chunks with their ruins
 * ({@code work/ChunkDelivery}), and the client re-creates every block entity of a replaced chunk. On the Zearth Silo
 * map the client ticks 20,074 chests; the tick after a frame that loaded 30–134 ruined chunks spent 200–300 ms in this
 * loop's removal alone (36 render-thread samples in a row at that call, passage-burst run 24.11.2026), worst while G1
 * marks concurrently, since every shifted reference then passes its write barrier. No NeoForge event covers the ticker
 * list, and neither Lithium 0.15.4 nor ModernFix in the pack changes this loop. The server's own ticker list has the
 * same cost when ruins remove block entities in bulk. Exactly the tickers vanilla's loop finds removed leave, in one
 * compaction after the loop: the order of the rest is unchanged, and a ticker removed after its turn stays listed until
 * the next loop as in vanilla (a chunk may rebind that wrapper to a new block entity meanwhile).
 * <p>
 * Remove when vanilla or NeoForge removes tickers in one pass.
 */
@Mixin(Level.class)
public abstract class BlockEntityTickerRemovalMixin {
    @Shadow
    @Final
    protected List<TickingBlockEntity> blockEntityTickers;

    /** Tickers this loop found removed (identity: a wrapper's equality is its own). */
    @Unique
    private final ReferenceOpenHashSet<TickingBlockEntity> incoming$removed = new ReferenceOpenHashSet<>();

    @WrapOperation(method = "tickBlockEntities",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;isRemoved()Z"))
    private boolean incoming$noteRemoved(TickingBlockEntity ticker, Operation<Boolean> isRemoved) {
        boolean removed = isRemoved.call(ticker);
        if (removed) incoming$removed.add(ticker);
        return removed;
    }

    @WrapOperation(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Ljava/util/Iterator;remove()V"))
    private void incoming$removeLater(Iterator<TickingBlockEntity> iterator, Operation<Void> remove) {
        // left for the one pass at the end of the loop
    }

    @Inject(method = "tickBlockEntities", at = @At("TAIL"))
    private void incoming$removeAtOnce(CallbackInfo ci) {
        if (incoming$removed.isEmpty()) return;
        blockEntityTickers.removeIf(incoming$removed::contains);
        incoming$removed.clear();
    }
}
