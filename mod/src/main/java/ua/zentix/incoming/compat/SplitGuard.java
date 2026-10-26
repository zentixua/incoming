package ua.zentix.incoming.compat;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Guard against the Sable 2.0.5–2.0.6 crash when a craft splits (by our explosion or another one).
 * <p>
 * When a craft breaks into many pieces, Sable assembles them one by one; a massless piece is removed immediately,
 * and its plot is assigned to the next one. If a piece in that plot splits in the same tick, the plot owner lookup finds
 * the removed craft, and {@code SubLevelAssemblyHelper.assembleBlocks} throws RuntimeException — crashing the world tick
 * and the game (“Exception ticking world”). The {@code SubLevelSplitGuardMixin} wraps {@code SubLevelHeatMapManager.split}:
 * this specific exception is logged, the piece's split is deferred (its blocks remain one craft), and the world continues.
 * Other exceptions propagate as before.
 * <p>
 * The interface is a marker: the mixin adds it to the Sable class so GameTest can see that the wrapper was applied.
 */
public interface SplitGuard {
    Logger LOG = LogUtils.getLogger();
    String REMOVED_PLOT = "Sub-level assembly attempted inside plot of already removed sub-level";

    /** Whether this is the specific Sable failure that can be skipped. */
    static boolean isRemovedPlot(RuntimeException e) {
        return REMOVED_PLOT.equals(e.getMessage());
    }

    static void swallowed(RuntimeException e) {
        LOG.warn("Sable: aircraft split skipped, otherwise the world would crash ({})", e.getMessage());
    }
}
