package ua.zentix.incoming.nuclear;

import ua.zentix.incoming.util.StrikeClock;
import ua.zentix.incoming.util.WorldClock;

/**
 * A detonation's show: its first {@link #SHOW_S} seconds (flash, wave, mushroom, the fires taking hold; Hiroshima's
 * firestorm formed about 20 minutes after the burst). Its clocks are a {@link StrikeClock}: the motion age M (real time plus
 * counted jumps) for what is in the air, the strike clock S for what stays in place: M through the show, then the world's
 * rate ({@link WorldClock}) joined by the rate blend, so the firestorm, contamination and fallout decay take as many hours
 * as the world lives through: about 50 s per hour under a vanilla day, an hour per hour with a 24-hour day, a stopped day at
 * its last pace. A jump with nobody awake in the dimension (single-player sleep) ends the show and moves both ages; with
 * someone awake it moves neither.
 * <p>
 * The anchors are recorded by the server ({@link NuclearEvents}) and told to clients ({@code S2C.NukeClock}).
 */
public final class NukeClock {
    /** Show length, real seconds. */
    public static final double SHOW_S = 20 * 60;
    /** Show length in game ticks, shared by the server and replay reconstruction. */
    public static final long SHOW_TICKS = (long) (SHOW_S * 20);

    private NukeClock() {}
}
