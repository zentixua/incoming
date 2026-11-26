package ua.zentix.incoming.client.fx;

import ua.zentix.incoming.client.far.FarFlightView;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

/**
 * A jump nobody in this dimension saw (a night everyone slept through, {@code util/WorldClock#counted}): what conventional
 * strikes put into the air ages by it at once, as its motion age M has it ({@code util/StrikeClock}, {@code docs/design/time.md}
 * §8.3 rule 2). Blast clouds, ground dust and fireballs ({@link BlastTime}), every smoke source's grid clock ({@link SmokeGrid}:
 * crater and oil smoke, launch clouds) and trails ({@link FarFlightView}) move together; nobody was watching, so no crossfade.
 * What stays in place keeps its own clock: the crater fuel fire burns on the server's cells ({@link FireSmoke#jump}).
 * The detonations' air reads the count itself ({@code ClientNuclear.Active#motionTicksAt}).
 */
public final class AirJump {
    private AirJump() {}

    /** The jump, real ticks of the air (an hour of the day is 72 000). */
    public static void apply(long ticks) {
        if (ticks <= 0) return;
        BlastTime.jump(ticks);
        SmokeGrid.jump(ticks);
        FireSmoke.jump(ticks);
        FarFlightView.jump(ticks);
    }
}
