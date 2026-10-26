package ua.zentix.incoming.compat;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Recognizes the removed-plot crash reported with Sable 2.0.5–2.0.6 when a craft splits after an explosion.
 * <p>
 * Sable assembles fragments one at a time, removing massless fragments and reusing their plots. Assembly in a
 * reused plot can resolve a removed sub-level, making {@code SubLevelAssemblyHelper.assembleBlocks} throw a
 * {@code RuntimeException} that escapes the world tick. {@code SubLevelSplitGuardMixin} wraps
 * {@code SubLevelHeatMapManager.split}, logging only this exact failure and letting other exceptions propagate.
 * The failed call is aborted: the guard neither rolls back already assembled fragments nor schedules a retry.
 * <p>
 * The mixin adds this marker interface to Sable's class so GameTest can check that it was applied.
 */
public interface SplitGuard {
    Logger LOG = LogUtils.getLogger();
    String REMOVED_PLOT = "Sub-level assembly attempted inside plot of already removed sub-level";

    /** Whether this is the specific Sable failure handled by the guard. */
    static boolean isRemovedPlot(RuntimeException e) {
        return REMOVED_PLOT.equals(e.getMessage());
    }

    static void swallowed(RuntimeException e) {
        LOG.warn("Sable: aircraft split skipped, otherwise the world would crash ({})", e.getMessage());
    }
}
