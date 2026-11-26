package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.multiplayer.ClientLevel;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.util.WorldClock;

import java.lang.ref.WeakReference;

/**
 * The client's copy of the world clocks ({@link WorldClock}): the clock with every jump ({@link #now}), the clock without
 * jumps ({@link #rateClock}), the world's rate and the jumps counted and not counted in this dimension. The server's values
 * arrive once a second ({@code S2C.NukeClock}, also kept in Flashback snapshots, so a replay seek restores them); between
 * those the client counts by the same rule ({@link WorldClock.Steps}) from its own time of day over its level's game ticks
 * (a frozen or paused level stops it; real time is game time), and leaves the counted jumps to the server. A time packet
 * that moves game time too (a new client world after a portal, lag correction beyond a second) is a resync, not elapsed
 * time. Without server values only differences mean anything (hearing recovery).
 */
public final class ClientClock {
    /** A game-time step further than this from one tick is a resync. */
    private static final long RESYNC = 20;
    private static double ticks, rate, counted, unseen;
    private static long lastDay, lastGame;
    /** The dimension the server told the jump counters for ({@link #counted}, {@link #unseen} are per dimension). */
    private static Object dimension;
    private static WeakReference<ClientLevel> lastLevel = new WeakReference<>(null);
    private static final WorldClock.Steps STEPS = new WorldClock.Steps();

    private ClientClock() {}

    /** Client tick (before readers). */
    static void tick(ClientLevel level, boolean paused) {
        long day = level.getDayTime(), game = level.getGameTime();
        if (lastLevel.get() == level) {
            long real = game - lastGame;
            // a frozen tick manager or a paused game: nothing elapsed, the day's change counts when time runs again
            if (paused || real == 0) return;
            if (real < 0 || real - 1 > RESYNC) {
                double c = STEPS.pace() * WorldClock.REAL_RATE;
                ticks += c;
                rate += c;
            } else {
                STEPS.step(day - lastDay, real, false);
                ticks += STEPS.rate() + STEPS.jump();
                rate += STEPS.rate();
            }
        } else {
            lastLevel = new WeakReference<>(level);
        }
        lastDay = day;
        lastGame = game;
    }

    /** The server's clocks now: the client continues from them (a lagging server pulls it back). */
    static void set(S2C.NukeClock p) {
        ticks = p.clock();
        ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        dimension = level == null ? null : level.dimension();
        S2C.NukeClock.Clocks c = p.clocks();
        if (c.known()) {
            rate = c.rate();
            STEPS.pace(c.pace());
            counted = c.counted();
            unseen = c.unseen();
        } else {
            // a recording made before the clock without jumps: no jumps
            rate = p.clock();
            counted = 0;
            unseen = 0;
        }
    }

    /** Clock now with every jump, world-clock ticks (1000 an hour): what nobody sees change. */
    public static double now() {
        return ticks;
    }

    /** Clock now without jumps, world-clock ticks. */
    public static double rateClock() {
        return rate;
    }

    /** The world's rate k (world time per real time). */
    public static double pace() {
        return STEPS.pace();
    }

    /** Jumps counted in this dimension, world-clock ticks ({@link WorldClock#counted}). */
    public static double counted() {
        return counted;
    }

    /** The dimension whose jumps {@link #counted} and {@link #unseen} are (the one the server last told them for). */
    public static Object dimension() {
        return dimension;
    }

    /** Jumps not counted in this dimension, world-clock ticks ({@link WorldClock#unseen}). */
    public static double unseen() {
        return unseen;
    }
}
