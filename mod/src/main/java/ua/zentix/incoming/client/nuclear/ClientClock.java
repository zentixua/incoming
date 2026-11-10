package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.multiplayer.ClientLevel;
import ua.zentix.incoming.util.WorldClock;

import java.lang.ref.WeakReference;

/**
 * The client's copy of the world clock ({@link WorldClock}): counted by the same rule from the client's own time of day,
 * so only differences mean anything (time since a detonation's show ended, hearing recovery). Counts unpaused client ticks:
 * the time of day's advance, never less than real time; a sleep or {@code /time add} jumps it forward. A time packet that
 * moves game time too (a new client world after a portal, lag correction beyond a second) is a resync, not elapsed time.
 */
public final class ClientClock {
    /** A game-time step further than this from one tick is a resync. */
    private static final long RESYNC = 20;
    private static double ticks;
    private static long lastDay, lastGame;
    private static WeakReference<ClientLevel> lastLevel = new WeakReference<>(null);

    private ClientClock() {}

    /** Client tick (before readers). */
    static void tick(ClientLevel level, boolean paused) {
        long day = level.getDayTime(), game = level.getGameTime();
        if (lastLevel.get() == level && !paused) {
            ticks += Math.abs(game - lastGame - 1) > RESYNC ? WorldClock.REAL_RATE : WorldClock.step(day - lastDay);
        }
        if (lastLevel.get() != level) lastLevel = new WeakReference<>(level);
        lastDay = day;
        lastGame = game;
    }

    /** Clock now, world-clock ticks (1000 an hour). */
    public static double now() {
        return ticks;
    }
}
