package ua.zentix.incoming.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import ua.zentix.incoming.compat.SplitGuard;

/**
 * Narrow crash guard for Sable's removed-plot failure during craft splitting (see {@link SplitGuard}). A massless
 * fragment is removed during the split; subsequent assembly can resolve a reused plot to a removed sub-level.
 * {@code SubLevelAssemblyHelper.assembleBlocks} then throws
 * {@code RuntimeException: Sub-level assembly attempted inside plot of already removed sub-level}.
 * Only that exact message is caught and logged around {@code split}; all other exceptions propagate. The wrapper
 * does not change assembly, mass, blocks or plot ownership, and does not promise to retry the aborted split.
 * Sable's public {@code SplitListener.addBlocks} can add blocks to a fragment, but cannot handle this exception
 * or control plot removal/reuse; {@code split} itself is private.
 * <p>
 * Remove when Sable fixes removed-sub-level lookup during plot reuse in splitting; checked on Sable 2.0.6
 * (Minecraft 1.21.1, NeoForge 21.1.264; also reported on Sable 2.0.5).
 * <p>
 * Without Sable this pseudo-mixin is not applied. A missing target method is optional ({@code require = 0});
 * otherwise Mixin can throw {@code InjectionError}, which the config's {@code "required": false} does not catch.
 * GameTest {@code sableSplitGuardApplied} checks that the wrapper was applied with Sable installed.
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
