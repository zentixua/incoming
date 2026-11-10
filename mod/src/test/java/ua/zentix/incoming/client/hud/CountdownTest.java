package ua.zentix.incoming.client.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CountdownTest {
    /** Digits run in tenths and round up: the counter reaches zero only at zero. */
    @Test
    void clockShowsTenthsRoundedUp() {
        assertEquals("0:45.0", Countdown.clock(45));
        assertEquals("0:44.7", Countdown.clock(44.65));
        assertEquals("1:05.3", Countdown.clock(65.25));
        assertEquals("0:00.1", Countdown.clock(0.01));
        assertEquals("0:00.0", Countdown.clock(0));
        assertEquals("0:00.0", Countdown.clock(-1));
        assertEquals("44.7", Countdown.seconds(44.65));
        assertEquals("4.0", Countdown.seconds(80 / 20.0 + 1e-12), "tick arithmetic noise does not show the next tenth");
        assertEquals("1799.9", Countdown.seconds(1799.9));
    }

    /**
     * A packet a few ticks late (or a one-tick game-time correction) does not run the counter backward; the counter
     * waits until time passes the late value. A real deadline change is shown at once.
     */
    @Test
    void lateValuesDoNotRunTheCounterBackward() {
        Countdown c = new Countdown();
        assertEquals(10.0, c.next(10.0));
        assertEquals(9.95, c.next(9.95));
        assertEquals(9.95, c.next(10.25), "packet 6 ticks late: held");
        assertEquals(9.95, c.next(10.2));
        assertEquals(9.9, c.next(9.9), "time passed it: runs on");
        assertEquals(15.0, c.next(15.0), "deadline moved by 5 s: shown at once");
        assertEquals(14.0, c.next(14.0));
        // a frame sequence with jitter: what is drawn never rises by less than the jitter allowance
        Countdown d = new Countdown();
        double prev = Double.MAX_VALUE;
        for (int tick = 0; tick < 400; tick++) {
            int late = (tick * 7919) % 9 == 0 ? (tick % 8) : 0; // irregular late packets, up to 7 ticks
            double raw = (600 - tick + late) / 20.0;
            double shown = d.next(raw);
            assertTrue(shown <= prev, "tick " + tick);
            prev = shown;
        }
    }
}
