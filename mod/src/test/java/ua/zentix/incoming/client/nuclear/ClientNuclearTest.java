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
     * A heavy client drops every other tick (full host pack: 24–26 client ticks while the server ran 44–49); the server's time
     * packet sets the client level time once a second. Wherever the clocks are equal, the detonation clock is within a tick of
     * the server's, so what the server times by the wave (ruins, throws, debris) cannot precede the drawn wave by more than
     * a tick. Own ticks alone (the old clock) fell 40+ ticks behind in 200 server ticks.
     */
    @Test
    void heavyClientFollowsTheServerWave() {
        long age = 0, own = 0, level = 0;
        for (long server = 1; server <= 200; server++) {
            boolean clientTick = server % 2 == 0;
            if (server % 20 == 0) level = server; // time packet: equal clocks
            else if (clientTick) level++;
            if (!clientTick) continue;
            age = ClientNuclear.Active.follow(age, level);
            own++;
            if (server % 20 == 0) assertTrue(age >= server - 1, "server tick " + server + ": detonation clock " + age);
        }
        assertTrue(200 - own > 40, "own ticks alone lagged only " + (200 - own) + " ticks");
    }

    /** Level time jumping back (server catch-up) never rewinds the detonation clock; a packet before the detonation tick starts it at zero. */
    @Test
    void detonationClockNeverRunsBackward() {
        long age = ClientNuclear.Active.follow(0, -1);
        assertEquals(1, age);
        long level = 1;
        for (int i = 0; i < 50; i++) {
            level = i == 20 ? level - 30 : level + 1;
            long next = ClientNuclear.Active.follow(age, level);
            assertEquals(age + 1, Math.min(next, age + 1), "tick " + i);
            assertTrue(next > age, "tick " + i + ": " + age + " → " + next);
            age = next;
        }
    }
}
