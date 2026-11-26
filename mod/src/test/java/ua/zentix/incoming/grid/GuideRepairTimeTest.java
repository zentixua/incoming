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
 * so in a vanilla world power starts coming back about 15 minutes after the show, not after the hit. This replays
 * {@code Blackouts.hold}'s arithmetic once a second under a vanilla day (one world-clock tick per game tick) and reads when
 * the first district may relight.
 */
class GuideRepairTimeTest {
    /** Real seconds until uniform repairs start for a default substation outage whose strike's show lasts {@code showTicks}. */
    static double firstRepairSeconds(long showTicks) {
        long restoreAt = 18_000; // default restore_hours 18, grid clock from 0
        Outage o = new Outage(1, 0, 0, 512, 0, 3, restoreAt, 90 * Blackouts.SPREAD_TICKS_PER_UNIT, 7).heldBy(-1, showTicks);
        RecoveryClock c = RecoveryClock.district();
        long base = o.lag();
        for (long g = 0; g < 10 * 72_000; g += 20) {
            // vanilla day: the grid clock (WorldClock.ticks) and the clock without jumps both advance 1 per game tick
            if (o.held()) {
                c.strike(o.strike());
                if (o.until() != Outage.NO_SHOW) c.until(o.until());
                c.step(g, g, 0, 0, "overworld", RecoveryClock.Shows.NONE_KNOWN);
                Outage next = o.lagging(base + Math.round(c.behind()));
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
        // the guide: the show lasts about 2.5 (Shahed-136) to 16 (cruise missile) minutes
        assertEquals(150, shahed / 20.0, 15, "Shahed-136 show, s");
        assertEquals(960, missile / 20.0, 30, "missile show, s");
        // the guide: power starts coming back about 15 minutes after the show (18 world hours at 50 s each, plus the rate
        // blend's half minute)
        for (long show : new long[] {shahed, missile}) {
            double after = firstRepairSeconds(show) - show / 20.0;
            assertTrue(after >= 900 && after <= 960, "first repair " + after + " s after a " + show / 20.0 + " s show");
        }
    }
}
