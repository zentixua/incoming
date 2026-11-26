package ua.zentix.incoming.grid;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.util.RecoveryClock;
import ua.zentix.incoming.util.WorldClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A substation outage held by a conventional strike's show ({@link Blackouts#step}, once a second as {@code Blackouts.hold})
 * after a night nobody was awake for: the counted jump ends the show (time rule 7), so the remaining repair runs at the
 * world's rate after the 60 s blend, not at real rate again until the strike's planned show end. The release audit's probe.
 */
class OutageShowAfterJumpTest {
    private long game = 1000;
    private double rate;

    /** Steps {@code o} once a second for {@code ticks} under a vanilla day; returns the outage after them. */
    private Outage run(Outage o, RecoveryClock c, double counted, long ticks) {
        for (long t = 0; t < ticks; t += 20) {
            game += 20;
            rate += 20; // vanilla day: one world-clock tick per game tick
            o = Blackouts.step(o, c, 0, game, rate, counted, 0, "overworld", id -> RecoveryClock.NONE);
        }
        return o;
    }

    @Test
    void aCountedNightEndsTheOutagesShow() {
        long until = game + 900 * 20; // a cruise missile's show: cloud apex and crater fuel, about 15 min
        Outage o = new Outage(1, 0, 0, 512, 0, 3, 18_000, 90, 7).heldBy(-1, until);
        RecoveryClock c = RecoveryClock.district();
        o = run(o, c, 0, 120 * 20); // two minutes after the hit, in the show: real rate
        double night = 8000; // everyone sleeps: 8 hours counted
        o = run(o, c, night, 20);
        assertEquals(game, o.until(), "the counted night ended the outage's show");
        double before = c.clock();
        o = run(o, c, night, 600 * 20);
        double gained = c.clock() - before, world = 600 * 20;
        // the next 10 real minutes: the blend's minute, then the world's rate (real rate would give 167)
        assertTrue(gained > 0.9 * world, String.format(java.util.Locale.ROOT,
                "after a counted night the outage gained %.0f clock ticks in 10 real minutes; the world's rate gives %.0f (real %.0f)",
                gained, world, world * WorldClock.REAL_RATE));
    }

    @Test
    void anAwakeNightKeepsTheShow() {
        long until = game + 900 * 20;
        Outage o = new Outage(1, 0, 0, 512, 0, 3, 18_000, 90, 7).heldBy(-1, until);
        RecoveryClock c = RecoveryClock.district();
        o = run(o, c, 0, 120 * 20);
        o = run(o, c, 0, 20); // the night skipped with someone awake: unseen, the show goes on
        assertEquals(until, o.until(), "a night someone stayed awake through keeps the show's end");
    }
}
