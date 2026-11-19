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
     * the server's, both ways, so what the server times by the wave (ruins, throws, debris) cannot precede the drawn wave by
     * more than a tick, and the drawn wave cannot run ahead of it. Own ticks alone (the old clock) fell 40+ ticks behind.
     */
    @Test
    void heavyClientFollowsTheServerWave() {
        long age = 0, own = 0, level = 0, last = 0;
        for (long server = 1; server <= 200; server++) {
            boolean clientTick = server % 2 == 0;
            if (server % 20 == 0) level = server; // time packet: equal clocks
            else if (clientTick) level++;
            if (!clientTick) continue;
            age = ClientNuclear.Active.follow(age, level - last);
            last = level;
            own++;
            if (server % 20 == 0) {
                assertTrue(age >= server - 1 && age <= server + 1, "server tick " + server + ": detonation clock " + age);
            }
        }
        assertTrue(200 - own > 40, "own ticks alone lagged only " + (200 - own) + " ticks");
    }

    /**
     * Review of #668: the last time packet set both clocks to 990, then the server ran at 5 TPS and the client at 20; the
     * server detonates at 1000 while the client level is already at 1030. The burst starts at its packet: one tick later it
     * is one tick old, not 31 (a 15 kt front 621 m out instead of 174 m, wave sound and body effects 500 m away on the first
     * tick). Time packets then pull the level clock back; the detonation clock never runs backward and never exceeds own
     * ticks plus forward corrections since arrival (here none: the client runs ahead of the server).
     */
    @Test
    void levelLeadAtArrivalIsNotImported() {
        long server = 1000, level = 1030, last = level, age = 0, own = 0, forward = 0;
        for (int serverTicks = 0; serverTicks < 100; serverTicks++) {
            server++;
            for (int c = 0; c < 4; c++) {
                level++;
                long step = level - last;
                if (step > 1) forward += step - 1;
                long next = ClientNuclear.Active.follow(age, step);
                assertTrue(next > age, "the clock ran backward: " + age + " → " + next);
                age = next;
                last = level;
                own++;
                if (own == 1) assertEquals(1, age, "first tick after arrival");
                assertTrue(age <= own + forward, "age " + age + " beyond own ticks " + own + " and forward corrections " + forward);
            }
            if (server % 20 == 10) level = server; // time packet (last at 990): backward sync
        }
        assertEquals(own, age, "client ahead of the server: no forward correction, the clock is own ticks");
    }

    /** Level time stepping back (server catch-up) never rewinds the detonation clock; every tick advances it. */
    @Test
    void detonationClockNeverRunsBackward() {
        long age = 0;
        for (int i = 0; i < 50; i++) {
            long next = ClientNuclear.Active.follow(age, i == 20 ? -30 : 1);
            assertEquals(age + 1, next, "tick " + i);
            age = next;
        }
        assertEquals(1, ClientNuclear.Active.follow(0, 0), "a tick without level movement (pause, packet before the detonation tick) still counts one");
    }
}
