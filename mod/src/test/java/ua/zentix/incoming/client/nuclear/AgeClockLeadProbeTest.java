package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;

/** Server removal two ticks after site crossing, measured against the front drawn by a 60 fps client. */
class AgeClockLeadProbeTest {
    @BeforeAll static void setup() { WaveEventsTest.bootstrap(); }

    @Test void measuredStall() { run("S", t -> t < 400 || t >= 503, 503, 499); }
    @Test void sustainedOverload() { run("T", t -> t < 400 || t >= 1600 || t % 2 == 0, 1600, 1468); }

    private static void run(String name, IntPredicate serverTicks, int recovery, int expectedSkipped) {
        var clock = new ClientNuclear.AgeClock(0, true);
        var events = new WaveEvents();
        long server = 0;
        double[] maxScatter = {0};
        double maxRuinLag = 0;
        int[] scatter = {0}, expired = {0};
        for (int own = 1; own <= recovery + 3000; own++) {
            if (serverTicks.test(own)) {
                clock.anchor(++server);
                int due = (int) server - 2;
                var receipt = events.receive(WaveEventsTest.batch(due), clock.confirmed(), own, p -> p.pos().getX());
                assertNotNull(receipt);
                maxRuinLag = Math.max(maxRuinLag, clock.at(0) - due);
            }
            drain(events, clock.at(0), scatter, expired, maxScatter);
            clock.tick(1 + own * 50_000_000L);
            for (int f = 0; f < 3; f++) {
                drain(events, clock.at(f / 3.0), scatter, expired, maxScatter);
            }
        }
        assertEquals(expectedSkipped, expired[0]);
        assertEquals(server, scatter[0] + expired[0]);
        System.out.printf(Locale.ROOT, "%s: %d scattered, %d expired, max scatter %.3f ticks, max ruin lag %.3f ticks%n",
                name, scatter[0], expired[0], maxScatter[0], maxRuinLag);
    }

    private static void drain(WaveEvents events, double shown, int[] scatter, int[] expired, double[] maxScatter) {
        events.drain(shown, (batch, pieces) -> {
            for (var p : pieces) {
                double lag = shown - p.pos().getX();
                assertTrue(lag >= 0 && lag <= 20, "scattered at " + lag);
                maxScatter[0] = Math.max(maxScatter[0], lag);
                scatter[0]++;
            }
        }, (batch, due) -> {
            assertTrue(shown - due > 20);
            expired[0]++;
        });
    }
}
