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
 * Narrow crash guard for concurrent access to xaerolib's shared block color cache by the game thread (map tiles
 * before unload) and background map tasks. Its private {@code blockTintIndices} is an unsynchronized
 * {@code Object2IntOpenHashMap}; {@code blockColors} and {@code textureColors} are ordinary {@code HashMap}s.
 * Concurrent inserts can corrupt the table and crash the client with
 * {@code ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 2049} in {@code Object2IntOpenHashMap.rehash}
 * (reported with xaerolib 1.7.3 and Xaero's World Map 1.46.0, after teleporting to ruins with many uncached states).
 * The negative index is a corrupted table slot, not a block state ID.
 * <p>
 * Both entry points call the original method once, with unchanged arguments and results, under the same instance
 * monitor. Cache clearing also happens inside {@code getBlockTextureColor}. No colors, rendering rules or exceptions
 * are replaced. There is no public hook to synchronize Xaero's own callers or replace these private caches.
 * <p>
 * Remove when Xaero fixes concurrent color/tint cache access, including cache clearing and the last-state shortcut;
 * checked on xaerolib 1.7.3 bundled with World Map 1.47.0 and Minimap 26.6.0 (Minecraft 1.21.1,
 * NeoForge 21.1.264). This is a bytecode check, not a new client crash reproduction.
 * Without Xaero this pseudo-mixin is not applied; a missing target method is optional ({@code require = 0}).
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
