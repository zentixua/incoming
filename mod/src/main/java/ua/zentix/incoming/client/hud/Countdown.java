package ua.zentix.incoming.client.hud;

import java.util.Locale;

/**
 * Remaining time of one HUD countdown (time to impact, time to detonation), as drawn: tenths of a second, so the digits
 * run every frame instead of stepping once a second. The raw value comes from client ticks since the last server
 * packet ({@link ClientFlights}, every 5 ticks) or from the client's game time (corrected by the server once a second);
 * a packet that arrives a few ticks late or a one-tick time correction makes it rise by fractions of a second, which
 * tenths would show as the counter running backward. Such small rises are held until the countdown passes them;
 * a larger one (the server moved the deadline, a resync after a stall) is shown at once.
 */
public final class Countdown {
    /**
     * Largest rise taken as packet jitter, seconds: in a client recording (Zearth 10.11, 595 server ticks of ICBM
     * approach packets) packets arrived up to 7 ticks late in a burst; 10 ticks covers it.
     */
    static final double JITTER = 0.5;
    private double shown = Double.NaN;

    /** Seconds to show for this frame from the raw remaining time. */
    public double next(double raw) {
        if (Double.isNaN(shown) || raw <= shown || raw - shown > JITTER) shown = raw;
        return shown;
    }

    /** "1:05.3": minutes, seconds and tenths, rounded up (reaches "0:00.0" only at zero). */
    public static String clock(double seconds) {
        long t = tenths(seconds);
        return String.format(Locale.ROOT, "%d:%02d.%d", t / 600, t / 10 % 60, t % 10);
    }

    /** "65.3": seconds and tenths, rounded up. */
    public static String seconds(double seconds) {
        long t = tenths(seconds);
        return String.format(Locale.ROOT, "%d.%d", t / 10, t % 10);
    }

    private static long tenths(double seconds) {
        // 1e-6 s keeps an exact tenth (raw 4.0 from tick arithmetic arriving as 4.0000000001) from showing the next one up
        return Math.max(0, (long) Math.ceil(seconds * 10 - 1e-6));
    }
}
