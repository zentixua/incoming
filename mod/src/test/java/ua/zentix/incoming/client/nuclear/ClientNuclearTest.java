package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientNuclearTest {
    /** The client's clock runs smoothly - there is no reserve, it is “delayed” only after {@link ClientNuclear#DELAY_GRACE_TICKS}. */
    @Test
    void steadyClockNeedsOnlyGrace() {
        ClientNuclear.ClockLead clock = new ClientNuclear.ClockLead();
        for (long t = 0; t < 100; t++) clock.tick(t);
        assertEquals(0, clock.lead(), 1e-9);
        assertFalse(ClientNuclear.overdue(1020, 1000, clock.lead()), "20 tick after the deadline - not yet a delay");
        assertTrue(ClientNuclear.overdue(1000 + ClientNuclear.DELAY_GRACE_TICKS + 1, 1000, clock.lead()));
    }

    /**
     * The server is lagging behind (10 and 5 tick per second): the client's clock runs off by 20–60 tick per second and jumps back. The countdown
     *  on the client’s clock reaches zero before the detonation - “delayed” is not shown.
     */
    @Test
    void laggingServerIsNotADelay() {
        for (int slow : new int[] {2, 4}) { // server in 10 and in 5 tick per second
            ClientNuclear.ClockLead clock = new ClientNuclear.ClockLead();
            long detonate = 400, server = 0, client = 0;
            boolean shown = false;
            for (int tick = 1; server < detonate; tick++) {
                client++;
                if (tick % slow == 0) server++;
                // the server sends the time once in 20 of its tick
                if (tick % (20 * slow) == 0) client = server;
                clock.tick(client);
                if (ClientNuclear.overdue(client, detonate, clock.lead())) shown = true;
            }
            assertTrue(clock.lead() >= 20 * (slow - 1) - 1, "stock " + clock.lead());
            assertFalse(shown, "server in " + 20 / slow + " tick/s");
        }
    }

    /** The jump back is forgotten in half a minute. */
    @Test
    void leadDecays() {
        ClientNuclear.ClockLead clock = new ClientNuclear.ClockLead();
        clock.tick(100);
        clock.tick(80);
        assertEquals(21, clock.lead(), 1e-9);
        for (long t = 81; t < 81 + 600; t++) clock.tick(t);
        assertEquals(0, clock.lead(), 1e-9);
    }

    /**
     * Server and client on one timeline of server ticks (detonation at 0): the server sends its age for the detonation every
     * tick ({@code S2C.NukeTick}; the detonation packet is the anchor at 0), which reaches the client {@code delay} ticks later,
     * in order (TCP); the client runs {@code clientTicks} ticks at each server tick. Every client tick checks the law's
     * invariants: never backward, at most {@link ClientNuclear.AgeClock#MAX_STEP} per tick, never more than one tick ahead of
     * the server's age. Returns the shown age after each server tick.
     */
    private static long[] run(int serverTicks, java.util.function.IntUnaryOperator clientTicks, java.util.function.IntUnaryOperator delay) {
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(0, true);
        long[] shown = new long[serverTicks + 1];
        java.util.ArrayDeque<long[]> wire = new java.util.ArrayDeque<>();
        long lastArrival = 0;
        for (int t = 0; t <= serverTicks; t++) {
            if (t > 0) {
                lastArrival = Math.max(lastArrival, t + delay.applyAsInt(t));
                wire.add(new long[]{lastArrival, t});
            }
            while (!wire.isEmpty() && wire.peek()[0] <= t) clock.anchor(wire.poll()[1]);
            for (int c = clientTicks.applyAsInt(t); c > 0; c--) {
                long before = clock.shown();
                clock.tick();
                long step = clock.shown() - before;
                assertTrue(step >= 0, "server tick " + t + ": the clock ran backward, " + before + " → " + clock.shown());
                assertTrue(step <= ClientNuclear.AgeClock.MAX_STEP, "server tick " + t + ": jump of " + step);
                assertTrue(clock.shown() <= t + 1, "server tick " + t + ": shown " + clock.shown() + ", more than a tick ahead of the server");
            }
            shown[t] = clock.shown();
        }
        return shown;
    }

    /**
     * Review of #668, round 1: the client runs at 20 TPS, the server at 5 (four client ticks a server tick). The burst is one
     * tick old one tick after its packet, and the shown age waits for the server's anchors instead of running ahead.
     */
    @Test
    void clientAheadOfALaggingServer() {
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(0, true);
        clock.tick();
        assertEquals(1, clock.shown(), "first tick after the packet");
        long[] shown = run(200, t -> 4, t -> 0);
        for (int t = 1; t <= 200; t++) assertTrue(shown[t] >= t - 1, "server tick " + t + ": shown " + shown[t]);
    }

    /**
     * Review of #668, round 2: a heavy client ticking every other server tick (also the full host pack: 24–26 client ticks
     * while the server ran 44–49). At server tick 20 the age is about 20, not 50 (a lag at arrival is never imported), and it
     * stays within a few ticks behind the server throughout, never ahead.
     */
    @Test
    void heavyClientSkippingTicks() {
        long[] shown = run(400, t -> t % 2 == 0 ? 1 : 0, t -> 0);
        assertTrue(shown[20] >= 18 && shown[20] <= 21, "server tick 20: shown " + shown[20]);
        for (int t = 40; t <= 400; t += 2) assertTrue(shown[t] >= t - 3, "server tick " + t + ": shown " + shown[t]);
    }

    /**
     * Review of #668, round 2: anchors alternately delayed by 10 ticks for a second and on time for the next (order kept).
     * The old rule summed recovered offsets: 200 shown at server tick 160. Now the shown age is never ahead of the server and at
     * most the delay behind it.
     */
    @Test
    void alternatingDelayedAnchors() {
        long[] shown = run(400, t -> 1, t -> (t / 20) % 2 == 1 ? 10 : 0);
        assertTrue(shown[160] >= 160 - 11 && shown[160] <= 161, "server tick 160: shown " + shown[160]);
        for (int t = 20; t <= 400; t++) assertTrue(shown[t] >= t - 11, "server tick " + t + ": shown " + shown[t]);
    }

    /**
     * Backward sync: the server stalls for two seconds while the client keeps ticking; anchors stop, and the shown age holds one
     * tick past the last instead of running ahead, then follows the server again. An anchor below the shown age (the client
     * ran ahead) never pulls it back.
     */
    @Test
    void serverStallAndBackwardAnchor() {
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(0, true);
        for (int t = 1; t <= 20; t++) {
            clock.anchor(t);
            clock.tick();
        }
        long held = clock.shown();
        for (int c = 0; c < 40; c++) clock.tick();
        assertEquals(held, clock.shown(), "the server stalled: the age waits one tick past its last anchor");
        clock.anchor(15);
        clock.tick();
        assertEquals(held, clock.shown(), "an anchor below the shown age never pulls it back");
        for (int t = 21; t <= 40; t++) {
            clock.anchor(t);
            clock.tick();
        }
        assertEquals(41, clock.shown(), "after the stall the age follows the server again");
    }

    /** A 2.9 s client frame freeze (full host pack, at the wave): the age catches up afterwards without a jump. */
    @Test
    void clientFreezeCatchesUpWithoutAJump() {
        long[] shown = run(300, t -> t >= 100 && t < 158 ? 0 : 1, t -> 0);
        assertTrue(shown[158] < 158 - 40, "frozen: the age waits, " + shown[158]);
        int caught = -1;
        for (int t = 158; t <= 300 && caught < 0; t++) if (shown[t] >= t) caught = t;
        assertTrue(caught > 0 && caught <= 158 + 58 / (ClientNuclear.AgeClock.MAX_STEP - 1) + 1, "caught up at server tick " + caught);
    }

    /** Without anchors (a recording made before them, a show that ended) the age is the client's own ticks, as before. */
    @Test
    void withoutAnchorsTheAgeIsOwnTicks() {
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(0, true);
        clock.ended();
        for (int c = 0; c < 50; c++) clock.tick();
        assertEquals(50, clock.shown());
        clock.anchor(30);
        for (int c = 0; c < 10; c++) clock.tick();
        assertEquals(50, clock.shown(), "after its show the anchor no longer caps the age, nor pulls it back");
        for (int c = 0; c < 30; c++) clock.tick();
        assertEquals(70, clock.shown(), "own ticks from the anchor once past it");
    }

    /** A past detonation (world entry, replay seek) starts from the level clock's estimate and takes the server's first anchor whole. */
    @Test
    void pastDetonationTakesTheFirstAnchor() {
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(130, false);
        clock.anchor(100);
        assertEquals(100, clock.shown());
        clock.tick();
        assertEquals(101, clock.shown());
        clock.anchor(90);
        clock.tick();
        assertEquals(101, clock.shown(), "later anchors never pull it back");
    }

    /** Frames between ticks never step back, also when the next tick holds. */
    @Test
    void framesNeverStepBack() {
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(0, true);
        double last = 0;
        for (int t = 1; t <= 60; t++) {
            if (t < 30 || t > 40) clock.anchor(t);
            clock.tick();
            for (double p = 0; p < 1; p += 0.25) {
                double v = clock.at(p);
                assertTrue(v >= last, "tick " + t + ", partial " + p + ": " + v + " after " + last);
                last = v;
            }
        }
    }
}
