package ua.zentix.incoming.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import ua.zentix.incoming.compat.SplitGuard;

/**
 * Splitting a Sable craft does not crash the world — see {@link SplitGuard}. Without Sable the mixin simply does not
 * apply; likewise with another Sable version where {@code split} is absent or different ({@code require = 0}: otherwise
 * Mixin throws {@code InjectionError}, which the config's {@code "required": false} does not catch, crashing the game on
 * class load). GameTest {@code sableSplitGuardApplied} verifies that the wrapper was applied in the pack with Sable.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager", remap = false)
public abstract class SubLevelSplitGuardMixin implements SplitGuard {
    @WrapMethod(method = "split", require = 0)
    private void incoming$guardSplit(Operation<Void> original) {
        try {
            original.call();
        } catch (RuntimeException e) {
            if (!SplitGuard.isRemovedPlot(e)) throw e;
            SplitGuard.swallowed(e);
        }
    }
}
