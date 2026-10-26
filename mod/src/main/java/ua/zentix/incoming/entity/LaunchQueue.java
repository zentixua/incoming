package ua.zentix.incoming.entity;

/**
 * Launcher rack launch queue ({@link Launcher}): when each cell becomes free and when the last ignition is
 * scheduled. Server only, not saved: projectiles already on rails save themselves and launch at their tick after
 * loading; new ones queue again.
 */
public final class LaunchQueue {
    /** The rack is not quiet for this many ticks after the last ignition: no turning ({@link Launcher#turnedYaw}). */
    static final int SETTLE_TICKS = 20;

    private long[] slotFreeAt = new long[0];
    private long lastStart = Long.MIN_VALUE / 2;

    /**
     * Reserve a launch cell.
     *
     * @param raised when the rack is raised and ready for its first launch
     * @param minReady minimum ticks from order to ignition
     * @param busyTicks ticks the projectile occupies the cell after readiness (ignition and launch rail exit)
     * @return [cell, ticks until ignition], no earlier than rack raised and cell free
     */
    public int[] reserve(LauncherRack rack, long now, long raised, int minReady, int busyTicks) {
        if (slotFreeAt.length != rack.slots()) slotFreeAt = new long[rack.slots()];
        int best = 0;
        for (int i = 1; i < slotFreeAt.length; i++) {
            if (slotFreeAt[i] < slotFreeAt[best]) best = i;
        }
        long start = Math.max(now + minReady, Math.max(slotFreeAt[best], raised));
        // Launches ordered while the rack was raising do not all depart together: queue them with spacing.
        start = Math.max(start, lastStart + rack.spacing());
        lastStart = start;
        slotFreeAt[best] = start + busyTicks;
        return new int[]{best, (int) (start - now)};
    }

    /** Rack quiet: last ignition was at least {@link #SETTLE_TICKS} ticks ago (and none are pending). */
    public boolean silent(long now) {
        return now >= lastStart + SETTLE_TICKS;
    }
}
