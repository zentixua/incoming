package ua.zentix.incoming.mixin.xaero;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import ua.zentix.incoming.Incoming;

/**
 * The xaerolib block color cache (Xaero's World Map and Minimap) is one client object with ordinary unlocked maps
 * ({@code Object2IntOpenHashMap blockTintIndices}, {@code blockColors}), read by both the game thread (map tiles before
 * unload) and background map tasks. Two threads inserting concurrently corrupt the table: the client crashes with
 * {@code ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 2049} in {@code Object2IntOpenHashMap.rehash}
 * (xaerolib 1.7.3, Xaero's World Map 1.46.0), for example just after teleporting to ruins: ruins add hundreds of states new
 * to the cache, with batches of insertions and table growth. −1 is a table slot, not a block state ID. Cache accesses here
 * are locked on the object itself. Without Xaero the mixin does not apply ({@code require = 0}, as with Sable).
 */
@Pseudo
@Mixin(targets = "xaero.lib.client.level.block.BlockTextureColorUtils", remap = false)
public abstract class BlockTextureColorLockMixin {
    private static volatile boolean incoming$reported;

    @WrapMethod(method = "getBlockTextureColor", require = 0)
    private int incoming$lockColor(BlockState state, boolean flag, Level level, Registry<Block> blocks, BlockPos pos, Operation<Integer> original) {
        if (!incoming$reported) {
            incoming$reported = true;
            Incoming.LOG.info("Xaero: xaerolib block colour cache is guarded by a lock (first access, thread {})", Thread.currentThread().getName());
        }
        synchronized (this) {
            return original.call(state, flag, level, blocks, pos);
        }
    }

    @WrapMethod(method = "getBlockTintIndex", require = 0)
    private int incoming$lockTint(BlockState state, Operation<Integer> original) {
        synchronized (this) {
            return original.call(state);
        }
    }
}
