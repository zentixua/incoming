package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.NukeClock;

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

    /** /tick freeze holds a live show (age and spawners); a replay keeps its editor clock, whose pause is itself a freeze. */
    @Test
    void freezeHoldsLiveShowNotReplay() {
        assertTrue(ClientNuclear.holds(false, false));
        assertFalse(ClientNuclear.holds(true, false));
        assertFalse(ClientNuclear.holds(false, true));
        assertFalse(ClientNuclear.holds(true, true));
    }

    /** A held show gets no client ticks: server anchors arriving meanwhile leave its shown age where it stood. */
    @Test
    void heldClockStandsStillUnderAnchors() {
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(0, true);
        for (int t = 1; t <= 100; t++) {
            clock.anchor(t);
            clock.tick(t * 50_000_000L);
        }
        double held = clock.shown();
        for (int t = 101; t <= 400; t++) clock.anchor(t);
        assertEquals(held, clock.shown(), 1e-9);
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
     * invariants: never backward, no jump (at most three ticks a client tick), never more
     * than {@link ClientNuclear.AgeClock#LEAD} ticks ahead of the server's age. Returns the shown age after each server tick.
     */
    private static double[] run(int serverTicks, java.util.function.IntUnaryOperator clientTicks, java.util.function.IntUnaryOperator delay) {
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(0, true);
        double[] shown = new double[serverTicks + 1];
        java.util.ArrayDeque<long[]> wire = new java.util.ArrayDeque<>();
        long lastArrival = 0;
        for (int t = 0; t <= serverTicks; t++) {
            if (t > 0) {
                lastArrival = Math.max(lastArrival, t + delay.applyAsInt(t));
                wire.add(new long[]{lastArrival, t});
            }
            while (!wire.isEmpty() && wire.peek()[0] <= t) clock.anchor(wire.poll()[1]);
            for (int c = clientTicks.applyAsInt(t); c > 0; c--) {
                double before = clock.shown();
                clock.tick((1L + t) * 50_000_000L);
                double step = clock.shown() - before;
                assertTrue(step >= 0, "server tick " + t + ": the clock ran backward, " + before + " → " + clock.shown());
                assertTrue(step <= 3 + 1e-9, "server tick " + t + ": jump of " + step);
                assertTrue(clock.shown() <= t + 200 + 1e-9, "server tick " + t + ": shown " + clock.shown() + ", ahead of the server");
            }
            shown[t] = clock.shown();
        }
        return shown;
    }

    /**
     * Review of #668, round 1: the client runs at 20 TPS, the server at 5 (four client ticks a server tick). The shown age
     * follows the server's pace instead of running ahead.
     */
    @Test
    void clientAheadOfALaggingServer() {
        double[] shown = run(200, t -> 4, t -> 0);
        for (int t = 1; t <= 200; t++) assertTrue(shown[t] >= t - 2, "server tick " + t + ": shown " + shown[t]);
    }

    /**
     * Review of #668, round 2: a heavy client ticking every other server tick (also the full host pack: 24–26 client ticks
     * while the server ran 44–49). At server tick 20 the age is about 20, not 50 (a lag at arrival is never imported), and it
     * stays within a few ticks behind the server throughout.
     */
    @Test
    void heavyClientSkippingTicks() {
        double[] shown = run(400, t -> t % 2 == 0 ? 1 : 0, t -> 0);
        assertTrue(shown[20] >= 14 && shown[20] <= 21, "server tick 20: shown " + shown[20]);
        System.out.printf(java.util.Locale.ROOT, "half-rate at 20 and 60: %.3f %.3f%n", shown[20], shown[60]);
        for (int t = 60; t <= 400; t += 2) assertTrue(shown[t] >= t - 3, "server tick " + t + ": shown " + shown[t]);
    }

    /**
     * Review of #668, round 2: anchors alternately delayed by 10 ticks for a second and on time for the next (order kept).
     * The rule before #668 summed recovered offsets: 200 shown at server tick 160. The shown age is never more than
     * {@link ClientNuclear.AgeClock#LEAD} ahead of the server and at most the delay and a little behind it.
     */
    @Test
    void alternatingDelayedAnchors() {
        double[] shown = run(400, t -> 1, t -> (t / 20) % 2 == 1 ? 10 : 0);
        assertTrue(shown[160] >= 160 - 12 && shown[160] <= 160 + ClientNuclear.AgeClock.LEAD, "server tick 160: shown " + shown[160]);
        for (int t = 20; t <= 400; t++) assertTrue(shown[t] >= t - 13, "server tick " + t + ": shown " + shown[t]);
    }

    /**
     * Backward sync: the server stalls for two seconds while the client keeps ticking; anchors stop, and the shown age eases
     * to a stop at most {@link ClientNuclear.AgeClock#LEAD} past the last instead of running ahead, then follows the server
     * again. An anchor below the shown age (the client ran ahead) never pulls it back.
     */
    @Test
    void serverStallAndBackwardAnchor() {
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(0, true);
        for (int t = 1; t <= 20; t++) {
            clock.anchor(t);
            clock.tick();
        }
        for (int c = 0; c < 10; c++) clock.tick();
        double held = clock.shown();
        assertTrue(held <= 20 + ClientNuclear.AgeClock.LEAD, "stalled server: held at " + held);
        for (int c = 0; c < 30; c++) clock.tick();
        assertTrue(clock.shown() > held + 20, "the presentation continues through short server stalls");
        double before = clock.shown();
        clock.anchor(15);
        clock.tick();
        assertTrue(clock.shown() >= before);
        for (int t = 21; t <= 420; t++) {
            clock.anchor(t);
            clock.tick();
        }
        assertEquals(420, clock.shown(), 3, "after recovery the client converges");
    }

    /** A 2.9 s client freeze with no ticks (full host pack, at the wave): the age catches up afterwards without a jump. */
    @Test
    void clientFreezeCatchesUpWithoutAJump() {
        double[] shown = run(400, t -> t >= 100 && t < 158 ? 0 : 1, t -> 0);
        assertTrue(shown[158] < 158 - 40, "frozen: the age waits, " + shown[158]);
        int caught = -1;
        for (int t = 158; t <= 400 && caught < 0; t++) if (shown[t] >= t - 2) caught = t;
        assertTrue(caught > 0 && caught <= 218, "caught up at server tick " + caught);
        for (int t = 218; t <= 400; t++) assertEquals(t, shown[t], 2, "settled age at " + t);
        System.out.printf(java.util.Locale.ROOT, "freeze first within two at %d, age at 218 %.3f%n", caught, shown[218]);
    }

    /** After show end, a dimension-wide anchor for a different show leaves this age alone. */
    @Test
    void endedShowKeepsAdvancingOnItsOwnTime() {
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(0, true);
        clock.ended();
        for (int c = 0; c < 50; c++) clock.tick();
        assertEquals(50, clock.shown(), 1e-9);
        clock.anchor(30);
        for (int c = 0; c < 10; c++) clock.tick();
        assertEquals(60, clock.shown(), 1e-9);
    }

    @Test
    void postShowSeekDoesNotRewindToTheSnapshotAnchor() {
        long lastShowTick = NukeClock.SHOW_TICKS - 1;
        long seekAge = lastShowTick + 400;
        var clock = new ClientNuclear.AgeClock(seekAge, false);
        // The snapshot has both the end of the show and its last per-tick anchor.
        clock.ended();
        clock.anchor(lastShowTick);
        assertEquals(seekAge, clock.shown(), 1e-9);
        clock.tick();
        assertEquals(seekAge + 1, clock.shown(), 1e-9);
    }

    @Test
    void staleAnchorBeforeShowEndPacketDoesNotRewindAPostShowSeek() {
        long lastShowTick = NukeClock.SHOW_TICKS - 1;
        long seekAge = lastShowTick + 20 * 60 * 60;
        var clock = new ClientNuclear.AgeClock(seekAge, false);
        clock.anchor(lastShowTick); // Snapshot NukeTick arrives first.
        clock.ended();              // Snapshot NukeClock arrives second.
        assertEquals(seekAge, clock.shown(), 1e-9);
        clock.tick();
        assertEquals(seekAge + 1, clock.shown(), 1e-9);
    }

    @Test
    void reconstructedShowUsesTheTimePacketToleranceAtTheEnd() {
        long boundary = NukeClock.SHOW_TICKS - ClientNuclear.TIME_SYNC_TICKS;
        var within = new ClientNuclear.AgeClock(boundary, false);
        within.anchor(NukeClock.SHOW_TICKS - 1);
        assertEquals(boundary, within.shown(), 1e-9);
        var before = new ClientNuclear.AgeClock(boundary - 1, false);
        before.anchor(boundary - 2);
        assertEquals(boundary - 2, before.shown(), 1e-9);
    }

    /**
     * A recording made before per-tick anchors (3.13 and older) has the live detonation packet alone: the age runs on own ticks instead
     * of easing to a stop {@link ClientNuclear.AgeClock#LEAD} past it (review of #678: the fresh burst froze at 3 ticks until a seek).
     */
    @Test
    void replayWithoutPerTickAnchorsFollowsRecordingPosition() {
        long[] position = {360};
        var clock = new ClientNuclear.AgeClock(0, true, () -> position[0], () -> 360);
        for (int t = 0; t <= 120; t++) {
            position[0] = 360 + t;
            assertEquals(t, clock.at(0));
        }
    }

    /**
     * A live server can send its first per-tick anchor late (post-burst work): the burst waits at most {@link ClientNuclear.AgeClock#LEAD}
     * past its packet however long, then follows the server without running ahead of it (review of #678: a timeout on the first anchor
     * showed 24 ticks at server age 1 after a 30-tick gap and then froze).
     */
    @Test
    void lateFirstAnchorFromALiveServer() {
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(0, true);
        for (int c = 0; c < 30; c++) clock.tick();
        assertTrue(clock.shown() <= 200 + 1e-9, "gap: held at " + clock.shown());
        double last = clock.shown();
        for (int t = 1; t <= 400; t++) {
            clock.anchor(t);
            clock.tick();
            assertTrue(clock.shown() <= t + 200 + 1e-9, "server age " + t + ": shown " + clock.shown());
            assertTrue(clock.shown() >= last, "never back");
            last = clock.shown();
        }
        assertEquals(400, clock.shown(), 3, "follows the server");
    }

    /**
     * Tick-time callers ask the clock for the tick's own age (partial 0): a query a whole tick ahead (partial 1) advances the clock past
     * every frame of the tick, which then all draw the same age (review of #678: firestorm tracking held the wave in 20 Hz steps).
     */
    @Test
    void tickTimeQueriesDoNotRunAheadOfFrames() throws java.io.IOException {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/ua/zentix/incoming/client/nuclear/ClientNuclear.java"));
        assertTrue(!src.matches("(?s).*\\.ticks\\(1[fF]?\\).*"), "a tick-time query of the next tick's age");
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(0, true);
        double last = -1;
        for (int t = 1; t <= 40; t++) {
            clock.anchor(t);
            clock.tick();
            clock.at(0);
            for (double f : new double[]{0.1, 0.35, 0.6, 0.9}) {
                double a = clock.at(f);
                if (t > 25) assertTrue(a > last, "tick " + t + " frame " + f + ": held at " + a);
                last = a;
            }
        }
    }

    /** A past detonation (world entry, replay seek) starts from the level clock's estimate and takes the server's first anchor whole. */
    @Test
    void pastDetonationTakesTheFirstAnchor() {
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(130, false);
        clock.anchor(100);
        assertEquals(100, clock.shown(), 1e-9);
        for (int t = 101; t <= 140; t++) {
            clock.anchor(t);
            clock.tick();
        }
        assertEquals(140, clock.shown(), 2);
        double held = clock.shown();
        clock.anchor(90);
        clock.at(0.5);
        assertTrue(clock.shown() >= held, "later anchors never pull it back");
    }

    /** Frames between ticks never step back, also when anchors stop. */
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
            // other renderers ask again within the same frame, and a tick-time caller asks at partial 0
            assertEquals(last, clock.at(0.75), 1e-12);
            assertEquals(last, clock.at(0), 1e-12);
        }
    }

    /**
     * The 1 Mt wave rig's recorded stream (full host pack, integrated server; {@code age-clock/J3.txt}, {@code J6.txt}: own
     * ticks, partial tick and latest anchor per rendered frame), replayed as recorded and with ±25 ms of extra arrival jitter.
     * Streaming anchors must keep the front moving, with ordinary speeds in [0.85, 1.2] of nominal. The final
     * age must recover the opening stall to within the fixture's 2.5-tick tolerance.
     */
    @Test
    void recordedAnchorStream() throws java.io.IOException {
        for (String run : new String[]{"J3", "J6"}) {
            java.util.List<double[]> rows = new java.util.ArrayList<>();
            try (var in = ClientNuclearTest.class.getResourceAsStream("/age-clock/" + run + ".txt")) {
                for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] f = line.trim().split(" ");
                    rows.add(new double[]{Long.parseLong(f[0]), Double.parseDouble(f[1]), Long.parseLong(f[2])});
                }
            }
            for (double jitter : new double[]{0, 0.5}) replay(run + (jitter > 0 ? " jittered" : ""), rows, jitter);
        }
    }

    private static void replay(String name, java.util.List<double[]> rows, double jitter) {
        java.util.Random random = new java.util.Random(7);
        // arrivals in own time: as recorded (the frame that processed it), or shifted by up to ±jitter ticks, in order
        java.util.ArrayDeque<double[]> wire = new java.util.ArrayDeque<>();
        double lastAnchor = -1, lastArrival = 0;
        for (double[] r : rows) {
            if (r[2] == lastAnchor) continue;
            lastAnchor = r[2];
            lastArrival = Math.max(lastArrival, r[0] + r[1] + (random.nextDouble() * 2 - 1) * jitter);
            wire.add(new double[]{lastArrival, r[2]});
        }
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(0, true);
        long own = 0;
        double anchor = 0, anchorAt = 0, prevTau = 0, prev = 0;
        int frames = 0, held = 0, ordinary = 0, offPace = 0;
        // the recording starts a few ticks after the packet: the client ticked there before the first recorded anchor
        while (own < rows.get(0)[0]) {
            own++;
            clock.tick();
        }
        prevTau = own;
        for (double[] r : rows) {
            double tau = r[0] + r[1];
            while (!wire.isEmpty() && wire.peek()[0] <= tau) {
                anchor = wire.poll()[1];
                anchorAt = prevTau;
                clock.anchor((long) anchor);
            }
            while (own < r[0]) {
                own++;
                clock.tick();
            }
            double shown = clock.at(r[1]);
            frames++;
            assertTrue(shown <= anchor + 200 + 1e-9, name + " frame " + frames + ": " + shown + " past anchor " + anchor);
            double dt = tau - prevTau;
            boolean fresh = tau - anchorAt <= 2;
            if (dt > 0 && fresh && frames > 1) {
                if (shown <= prev) held++;
                if (dt <= 1.5 && tau > 20) {
                    ordinary++;
                    double speed = (shown - prev) / dt;
                    if (speed < 0.85 - 1e-9 || speed > 1.2 + 1e-9) offPace++;
                }
            }
            prevTau = tau;
            prev = shown;
        }
        assertEquals(0, held, name + ": held frames while anchors stream");
        assertTrue(offPace <= ordinary / 100, name + ": " + offPace + " of " + ordinary + " ordinary frames outside [0.85, 1.2] of nominal speed");
        double lag = anchor + (prevTau - anchorAt) - prev;
        assertTrue(lag >= 0 && lag <= 2.5, name + ": lag at the end " + lag);
    }
}
