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
 * <p>
 * A rise of the counted jumps within one level is a jump nobody in this dimension saw ({@link #set} returns it): the air of
 * every strike here ages by it (its motion age M, {@code util/StrikeClock}). A new level (another dimension, another world)
 * adopts the server's count without a jump; the jumps someone was awake for ({@link #unseen}) never move the air.
 */
public final class ClientClock {
    /** A game-time step further than this from one tick is a resync. */
    private static final long RESYNC = 20;
    private static double ticks, rate, counted, unseen;
    private static long lastDay, lastGame;
    /** The dimension the server told the jump counters for ({@link #counted}, {@link #unseen} are per dimension). */
    private static Object dimension;
    private static WeakReference<ClientLevel> lastLevel = new WeakReference<>(null);
    /** The level whose count {@link #counted} holds; null — none yet. */
    private static WeakReference<Object> countedIn = new WeakReference<>(null);
    /** Real ticks per world-clock tick of a jump: an hour of the day (1000 ticks) is 3600 real seconds of the air. */
    public static final double AIR_TICKS_PER_CLOCK_TICK = WorldClock.SECONDS_PER_TICK * 20;
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
            // a new client world (login, portal): its clocks are not known until the server tells them
            dimension = null;
        }
        lastDay = day;
        lastGame = game;
    }

    /**
     * The server's clocks now, received in {@code level}: the client continues from them (a lagging server pulls it back).
     * Returns the jump nobody in this dimension saw since the last packet in the same level, in real ticks of the air; 0 —
     * none (no rise, another level, or a recording made before the counter).
     */
    static double set(S2C.NukeClock p, Object level) {
        ticks = p.clock();
        ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        dimension = level == null ? null : level.dimension();
        S2C.NukeClock.Clocks c = p.clocks();
        double was = counted;
        boolean same = level != null && countedIn.get() == level;
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
        countedIn = new WeakReference<>(level);
        return same && counted > was ? (counted - was) * AIR_TICKS_PER_CLOCK_TICK : 0;
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
