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
 * same cost when ruins remove block entities in bulk. The order of the remaining tickers is unchanged; a ticker
 * removed during the loop is also dropped at its end instead of on the next tick, and it no longer ticks either way.
 * <p>
 * Remove when vanilla or NeoForge removes tickers in one pass.
 */
@Mixin(Level.class)
public abstract class BlockEntityTickerRemovalMixin {
    @Shadow
    @Final
    protected List<TickingBlockEntity> blockEntityTickers;

    @Unique
    private boolean incoming$removedTickers;

    @WrapOperation(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Ljava/util/Iterator;remove()V"))
    private void incoming$removeLater(Iterator<TickingBlockEntity> iterator, Operation<Void> remove) {
        incoming$removedTickers = true;
    }

    @Inject(method = "tickBlockEntities", at = @At("TAIL"))
    private void incoming$removeAtOnce(CallbackInfo ci) {
        if (!incoming$removedTickers) return;
        incoming$removedTickers = false;
        blockEntityTickers.removeIf(TickingBlockEntity::isRemoved);
    }
}
