package ua.zentix.incoming.client.fx;

import ua.zentix.incoming.client.far.FarFlightView;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.client.sound.BlastSounds;

/**
 * A jump nobody in this dimension saw (a night everyone slept through, {@code util/WorldClock#counted}): conventional
 * strikes age by it at once ({@code docs/design/time.md} §8.3, rules 2 and 7). Their air lives on the motion age M:
 * blast clouds, fireballs and ground dust ({@link BlastTime}), every smoke source's grid clock ({@link SmokeGrid}: crater
 * and oil smoke, launch clouds) and trails ({@link FarFlightView}). Their crater fuel burns in place within its show, where
 * the strike clock is M, so it ages alike: its smoke source ({@link FireSmoke#jump}) and its roar ({@link BlastSounds#jump}),
 * as the server's cells do ({@code warhead/FuelFires}). Nobody watched, so no crossfade. The detonations read the count
 * themselves ({@code ClientNuclear.Active#motionTicksAt}).
 */
public final class CountedJump {
    private CountedJump() {}

    /** The jump, real ticks (an hour of the day is 72 000). */
    public static void apply(long ticks) {
        if (ticks <= 0) return;
        BlastTime.jump(ticks);
        SmokeGrid.jump(ticks);
        FireSmoke.jump(ticks);
        FarFlightView.jump(ticks);
        BlastSounds.jump(ticks);
    }
}
