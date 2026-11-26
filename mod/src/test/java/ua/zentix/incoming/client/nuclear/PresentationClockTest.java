package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class PresentationClockTest {
    @Test
    void recordedServerDeliveriesIncludingLastBatchAndDrain() throws IOException {
        var batches = recordedBatches();
        var clock = new ClientNuclear.AgeClock(0, true);
        int held = 0, holdFrames = 0, longest = 0, freshFrames = 0, longestFresh = 0, lastReceipt = 0;
        long confirmed = 0;
        double last = 0, previousRate = 0, min = Double.POSITIVE_INFINITY, max = 0, slew = 0, lead = 0;
        int endFrame = (batches.lastKey() + 1) * 3;
        for (int frame = 0; frame <= endFrame; frame++) {
            int own = frame / 3;
            if (frame % 3 == 0) {
                for (long anchor : batches.getOrDefault(own, List.of())) {
                    clock.anchor(anchor);
                    confirmed = anchor;
                    lastReceipt = own;
                }
                if (frame > 0) clock.tick(1 + own * 50_000_000L, ua.zentix.incoming.client.ClientTicks.NOMINAL_NANOS);
            }
            double shown = clock.at((frame % 3) / 3.0);
            lead = Math.max(lead, shown - confirmed);
            if (frame > 0) {
                double rate = 3 * (shown - last);
                if (frame > 1) slew = Math.max(slew, 60 * Math.abs(rate - previousRate));
                previousRate = rate;
                min = Math.min(min, rate);
                max = Math.max(max, rate);
                if (rate < 0.1) held++;
                holdFrames = rate < 0.1 ? holdFrames + 1 : 0;
                freshFrames = rate < 0.1 && frame - 3 * lastReceipt < 120 ? freshFrames + 1 : 0;
                longest = Math.max(holdFrames, longest);
                longestFresh = Math.max(freshFrames, longestFresh);
            }
            last = shown;
        }
        assertEquals(1444, confirmed);
        String metrics = String.format(Locale.ROOT,
                "%d/%d held, longest %.1f ms (fresh %.1f ms), speed %.4f..%.4fx, slew %.4fx/s, peak lead %.3f ticks",
                held, endFrame, longest * 1000.0 / 60, longestFresh * 1000.0 / 60, min, max, slew, lead);
        boolean fresh = longestFresh < 6, speed = min >= 0.7 - 1e-8 && max <= 1.2 + 1e-8;
        boolean smooth = slew <= 0.2 + 1e-8, bounded = lead <= 200 + 1e-8;
        System.out.println(metrics);
        assertAll(metrics, () -> assertTrue(fresh, "fresh holds"), () -> assertTrue(speed, "speed"),
                () -> assertTrue(smooth, "adjacent frames"), () -> assertTrue(bounded, "lead"));
    }

    private static TreeMap<Integer, List<Long>> recordedBatches() throws IOException {
        var batches = new TreeMap<Integer, List<Long>>();
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                PresentationClockTest.class.getResourceAsStream("/age-clock/artem-25567-1mt.txt")), StandardCharsets.UTF_8))) {
            long next = 0;
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\\s+");
                long age = Long.parseLong(fields[1]);
                assertEquals(next++, age);
                batches.computeIfAbsent(Integer.parseInt(fields[0]), ignored -> new ArrayList<>()).add(age);
            }
            assertEquals(1445, next);
        }
        assertEquals(List.of(0L, 1L, 2L), batches.get(0));
        assertEquals(1589, batches.lastKey().intValue());
        assertEquals(List.of(1442L, 1443L, 1444L), batches.lastEntry().getValue());
        return batches;
    }

    @Test
    void frameSlewAcrossTickBoundariesAndIrregularFrameSpacing() throws IOException {
        var batches = recordedBatches();
        // Intervals in 120ths of a second, including alternating frames on both sides of a tick boundary.
        for (int[] steps : new int[][]{{4}, {1}, {1, 3, 2, 5, 1}}) {
            var clock = new ClientNuclear.AgeClock(0, true);
            int own = -1, units = 0, prevDt = 0, lastAnchor = 0;
            double prevAge = 0, prevSpeed = 1;
            for (int frame = 0; ; frame++) {
                while (own < units / 6) {
                    own++;
                    for (long a : batches.getOrDefault(own, List.of())) {
                        clock.anchor(a);
                        lastAnchor = (int) a;
                    }
                    if (own > 0) clock.tick(1 + own * 50_000_000L, ua.zentix.incoming.client.ClientTicks.NOMINAL_NANOS);
                }
                double shown = clock.at((units % 6) / 6.0);
                int dt = frame == 0 ? 0 : steps[(frame - 1) % steps.length];
                if (dt > 0) {
                    double speed = (shown - prevAge) * 6 / dt;
                    assertTrue(speed >= 0.7 - 1e-8 && speed <= 1.2 + 1e-8);
                    if (prevDt > 0) assertEquals(prevSpeed, speed, 0.2 * (dt + prevDt) / 240 + 1e-8,
                            "frame-average speeds at consecutive interval midpoints");
                    prevSpeed = speed;
                }
                assertTrue(shown <= lastAnchor + 200 + 1e-8);
                if (units >= 1590 * 6) break;
                prevDt = dt;
                prevAge = shown;
                units += steps[frame % steps.length];
            }
            assertEquals(1444, lastAnchor);
        }
    }

    @Test
    void independentJoinAtRecoveryFromOneMinuteAtTenTps() {

        join(1600, 1200, t -> t < 400 || t >= 1600 || t % 2 == 0);
    }

    @Test
    void joinAfterSteadyServerOrTwoSecondStall() {
        join(400, 200, t -> true);
        join(400, 200, t -> t <= 360 || t >= 400);
    }

    private static void join(int recovery, int deadline, java.util.function.IntPredicate delivery) {
        var resident = new ClientNuclear.AgeClock(0, true);
        ClientNuclear.AgeClock joined = null;
        long nextAnchor = 0;
        for (int frame = 0; frame <= (recovery + 2400) * 3; frame++) {
            int own = frame / 3;
            if (frame % 3 == 0) {
                if (own == recovery) joined = new ClientNuclear.AgeClock(nextAnchor, false);
                if (delivery.test(own)) {
                    resident.anchor(nextAnchor);
                    if (joined != null) joined.anchor(nextAnchor);
                    nextAnchor++;
                }
                if (own > 0) resident.tick(1 + own * 50_000_000L, ua.zentix.incoming.client.ClientTicks.NOMINAL_NANOS);
                if (own > recovery) joined.tick(1 + own * 50_000_000L, ua.zentix.incoming.client.ClientTicks.NOMINAL_NANOS);
            }
            double a = resident.at((frame % 3) / 3.0);
            if (joined != null) {
                double b = joined.at((frame % 3) / 3.0);
                if (frame % 3 == 0 && (own == recovery || own == recovery + 200 || own == recovery + 1200))
                    System.out.printf(Locale.ROOT, "join t=%d deadline=%d +%ds gap %.4f%n", recovery, deadline,
                            (own - recovery) / 20, a - b);
                if (own >= recovery + deadline) assertEquals(a, b, 10, "own tick " + own);
            }
        }
    }

    @Test
    void sustainedOverloadSilenceRestartAndShowEnd() {
        for (int interval : new int[]{2, 4}) {
            var clock = new ClientNuclear.AgeClock(0, true);
            long server = 0;
            double age = 0, rate = 1;
            for (int f = 1; f <= 3 * 6800; f++) {
                int t = f / 3;
                if (f % 3 == 0) {
                    if (t <= 4000 && t % interval == 0 || t > 6000) clock.anchor(++server);
                    clock.tick();
                }
                double shown = clock.at((f % 3) / 3.0);
                double speed = (shown - age) * 3;
                assertTrue(speed >= -1e-9 && shown <= server + 200 + 1e-9);
                assertEquals(rate, speed, 0.2 / 60 + 1e-8, "slew at frame " + f);
                if (t == 6000 && f % 3 != 0) assertEquals(0, speed);
                if (t == 6800) assertTrue(speed > 0.8);
                age = shown;
                rate = speed;
            }
            clock.ended();
            for (int i = 0; i < 600; i++) clock.tick();
            assertTrue(clock.shown() >= age + 595);
        }
    }

    @Test
    void oneEndedShowReleasesFromRestWhileAnotherKeepsItsBrake() {
        var ended = new ClientNuclear.AgeClock(0, true);
        var active = new ClientNuclear.AgeClock(0, true);
        for (int i = 0; i < 2500; i++) {
            ended.tick();
            active.tick();
        }
        assertEquals(200, ended.shown());
        ended.ended();
        double previous = 0;
        for (int i = 1; i <= 600; i++) {
            double before = ended.shown();
            ended.anchor(i);
            ended.tick();
            active.tick();
            double speed = ended.shown() - before;
            assertEquals(previous, speed, 0.01 + 1e-9);
            previous = speed;
        }
        assertEquals(1, previous, 1e-9);
        assertTrue(ended.shown() > 700);
        assertEquals(200, active.shown());
    }

    @Test
    void aShownEstimateNeverRewindsOnConfirmation() {
        var clock = new ClientNuclear.AgeClock(100, false);
        for (int i = 0; i < 5; i++) clock.tick();
        double age = clock.at(0);
        clock.anchor(80);
        assertEquals(age, clock.at(0));
        for (int i = 1; i <= 60; i++) {
            clock.anchor(80 + i);
            clock.tick();
            assertTrue(clock.shown() - age >= 0.5 - 1e-9);
            age = clock.shown();
        }
    }

    @Test
    void lowFrameRateWithAllClientTicksProcessedKeepsNormalSlew() {
        var clock = new ClientNuclear.AgeClock(0, true);
        double age = 0, speed = 1;
        for (int frame = 1; frame <= 600; frame++) {
            for (int i = 0; i < 3; i++) clock.anchor((frame - 1) * 3 + i + 1);
            for (int i = 0; i < 3; i++) {
                clock.tick(1 + frame * 150_000_000L, ua.zentix.incoming.client.ClientTicks.NOMINAL_NANOS);
                double next = clock.at(0);
                assertEquals(speed, next - age, 0.01 + 1e-9, "catch-up batch at frame " + frame);
                speed = next - age;
                age = next;
            }
        }
    }
}
