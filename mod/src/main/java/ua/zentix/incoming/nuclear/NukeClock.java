package ua.zentix.incoming.nuclear;

import ua.zentix.incoming.util.WorldClock;

/**
 * A detonation's own clock: what everyone sees moving runs in real seconds, slow consequences follow the world.
 * <p>
 * For the first {@link #SHOW_S} seconds (the show: flash, wave, mushroom, the fires taking hold; Hiroshima's firestorm
 * formed about 20 minutes after the burst) the clock is real time. Then it follows the world clock ({@link WorldClock}:
 * time of day, never slower than real time), joining it smoothly over {@link #RAMP_S} real seconds, so the firestorm,
 * black rain and fallout decay take as many hours as the world lives through: about two minutes per hour under a vanilla
 * day, an hour per hour with a day-length mod or a stopped day; sleep moves them on. The clock only runs forward and
 * never runs behind real time: an effect never comes before its cause, and nothing that moves in front of the player
 * skips. Sleep during the show changes nothing (the show is the same for everyone).
 * <p>
 * The world clock at the show's end is recorded once per detonation by the server ({@code NuclearEvents}) and told to
 * clients ({@code S2C.NukeClock}).
 */
public final class NukeClock {
    /** Show length, real seconds: the clock is real time until then. */
    public static final double SHOW_S = 20 * 60;
    /** Real seconds over which the clock joins the world clock after the show (no visible jump in fire or smoke). */
    public static final double RAMP_S = 60;

    private NukeClock() {}

    /**
     * Detonation-clock seconds.
     *
     * @param realS  real seconds since the burst
     * @param worldS world-clock seconds since the show's end ({@link WorldClock#SECONDS_PER_TICK} per tick); NaN — unknown
     *               (read as real time)
     */
    public static double seconds(double realS, double worldS) {
        if (realS <= SHOW_S) return realS;
        double r = realS - SHOW_S;
        if (Double.isNaN(worldS)) return realS;
        double u = Math.min(1, r / RAMP_S), ramp = u * u * (3 - 2 * u);
        return SHOW_S + r + Math.max(0, worldS - r) * ramp;
    }

    /** Detonation-clock seconds from world-clock ticks since the show's end. */
    public static double fromClock(double realS, double clockSinceShowEnd) {
        return seconds(realS, clockSinceShowEnd * WorldClock.SECONDS_PER_TICK);
    }
}
