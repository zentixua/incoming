package ua.zentix.incoming.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.TickRateManager;

/**
 * The client level's tick: the clock of every conventional effect that moves (blast clouds, ground shake, crater and
 * oil smoke, particles, heat shimmer). Real time is game time: these clocks advance only on client ticks in which the
 * level runs, so {@code /tick freeze}, a paused game and a paused Flashback replay stop them, {@code /tick step} moves
 * them one tick, and a slower {@code /tick rate} slows them with the world. In a replay the client ticks at the
 * replay's speed (Flashback sets the client timer's tick length) and its pause freezes the level's tick rate, so the
 * same clocks follow the replay position; a seek clears them ({@code IncomingClient.rewound}).
 */
public final class ClientTicks {
    /** Minecraft's client tick at 20 ticks a second, ns: the client timer's default length. */
    public static final long NOMINAL_NANOS = 50_000_000L;
    private static boolean runs;
    private static long tickNanos = NOMINAL_NANOS;

    private ClientTicks() {}

    /** Client pre-tick: whether the level runs in this client tick ({@link #runs()}) and the client tick's length. */
    public static void pre(Minecraft mc) {
        runs = mc.level != null && runs(mc.isPaused(), mc.level.tickRateManager());
        // the length the client timer advances by: the level's tick (never under 50 ms; a frozen level 50 ms), or the
        // replay's speed under Flashback, which answers this same call
        tickNanos = tickNanos(mc.getTickTargetMillis(NOMINAL_NANOS / 1e6f));
    }

    /**
     * The level runs in this client tick: not paused, and not frozen or a frozen step. {@code Minecraft.tick} calls
     * {@link TickRateManager#tick()} after the pre-tick event and it sets {@code runsNormally()} from this same state, so
     * clocks stamped in the pre-tick and those ticking after the world (particles) agree.
     */
    static boolean runs(boolean paused, TickRateManager rates) {
        return !paused && (!rates.isFrozen() || rates.frozenTicksToRun() > 0);
    }

    /** The level runs in the current client tick (decided in its pre-tick). */
    public static boolean runs() {
        return runs;
    }

    /** Real nanoseconds per client tick now: what a wall-clock interval is worth in client ticks. */
    public static long nanos() {
        return tickNanos;
    }

    /** Client tick length, ns, from the client timer's milliseconds per tick; a broken value reads as nominal. */
    static long tickNanos(float millis) {
        return millis > 0 && Float.isFinite(millis) ? Math.max(1, Math.round(millis * 1e6)) : NOMINAL_NANOS;
    }

    /** Client ticks in {@code nanos} of wall time at tick length {@code tickNanos}. */
    public static double ticks(long nanos, long tickNanos) {
        return nanos / (double) tickNanos;
    }
}
