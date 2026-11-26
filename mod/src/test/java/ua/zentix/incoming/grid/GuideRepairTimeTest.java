package ua.zentix.incoming.grid;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.util.RecoveryClock;
import ua.zentix.incoming.warhead.StrikeShow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guide's blackout timing (docs/guide/blackout.md, both languages): a substation a Shahed-136 or missile knocks out
 * holds its outage's recovery clock at real rate through the strike's show ({@link Blackouts}, {@link RecoveryClock#district}),
 * so in a vanilla world power starts coming back about 15 minutes after the show, not after the hit. This steps the outage
 * through {@code Blackouts.step} once a second, with {@code Blackouts.hold}'s release rule, under a vanilla day (one world-clock tick per game tick) and reads when
 * the first district may relight.
 */
class GuideRepairTimeTest {
    /** Real seconds until uniform repairs start for a default substation outage whose strike's show lasts {@code showTicks}. */
    static double firstRepairSeconds(long showTicks) {
        // the defaults of restore_hours (world hours, 1000 world-clock ticks each) and restore_spread_seconds; grid clock from 0
        long restoreAt = Math.round(ua.zentix.incoming.IncomingConfig.SERVER.gridRestoreHours.getDefault() * 1000);
        int spread = ua.zentix.incoming.IncomingConfig.SERVER.gridRestoreSpread.getDefault() * Blackouts.SPREAD_TICKS_PER_UNIT;
        Outage o = new Outage(1, 0, 0, 512, 0, 3, restoreAt, spread, 7).heldBy(-1, showTicks);
        RecoveryClock c = RecoveryClock.district();
        long base = o.lag();
        for (long g = 0; g < 10 * 72_000; g += 20) {
            // vanilla day: the grid clock (WorldClock.ticks) and the clock without jumps both advance 1 per game tick
            if (o.held()) {
                Outage next = Blackouts.step(o, c, base, g, g, 0, 0, "overworld", RecoveryClock.Shows.NONE_KNOWN);
                if (c.settled() || g >= next.restoreFrom()) next = next.released();
                o = next;
            }
            if (g >= o.restoreFrom()) return g / 20.0;
        }
        return Double.NaN;
    }

    @Test
    void repairsStartFifteenMinutesAfterTheShowInAVanillaWorld() {
        long shahed = StrikeShow.fuelTicks(WeaponSpec.DRONE, 1_000_000), missile = StrikeShow.fuelTicks(WeaponSpec.MISSILE, 1_000_000);
        // the guide: a conventional strike's show lasts about 2 to 16 minutes (the cloud's apex 1.7-5 min, a Shahed-136's
        // fuel 2.5 min, a cruise missile's 16 min)
        assertEquals(150, shahed / 20.0, 15, "Shahed-136 fuel, s");
        assertEquals(960, missile / 20.0, 30, "missile fuel, s");
        // the guide: power starts coming back about 15 minutes after the show (18 world hours at 50 s each, plus the rate
        // blend's half minute), from the shortest show (the apex alone) to the missile's
        long apexMin = Math.round(StrikeShow.seconds(Math.PI / (1.7 * 60), 0) * 20);
        for (long show : new long[] {apexMin, shahed, missile}) {
            double after = firstRepairSeconds(show) - show / 20.0;
            assertTrue(after >= 900 && after <= 960, "first repair " + after + " s after a " + show / 20.0 + " s show");
        }
    }
}
