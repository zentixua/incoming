package ua.zentix.incoming.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The projectile locations at the client by tick ({@link ArrivalQueue}): the projectile flies along the 2 block per server tick, and the tick server
 *  packets arrive at the client tick unevenly - none, two, one. The client’s projectile goes smoothly, along 2 block per tick.
 */
class ArrivalQueueTest {
    private static ArrivalQueue.Sample at(double x) {
        return new ArrivalQueue.Sample(x, 64, 0, 0, 0);
    }

    /** client tick: how many packets arrived in each; the answer is the location of the projectile after each tick. */
    private static List<Double> play(int... packets) {
        ArrivalQueue q = new ArrivalQueue();
        List<Double> out = new ArrayList<>();
        double x = 0, sent = 0;
        for (int n : packets) {
            for (int i = 0; i < n; i++) q.add(at(sent += 2));
            ArrivalQueue.Sample s = q.next(x, 64, 0, 0, 0);
            if (s != null) x = s.x();
            out.add(x);
        }
        return out;
    }

    @Test
    void evenPacketsPassAsIs() {
        assertEquals(List.of(2.0, 4.0, 6.0, 8.0), play(1, 1, 1, 1));
    }

    @Test
    void missedTickGoesOnByLastStep() {
        // without queue: 2, 2, 6, 8 - stands tick and jumps two steps
        assertEquals(List.of(2.0, 4.0, 6.0, 8.0), play(1, 0, 2, 1));
    }

    @Test
    void burstQueuesOneTickBehind() {
        // two packages at once: the projectile goes step by step, lagging behind by a tick, until the pass eats up the reserve
        assertEquals(List.of(2.0, 4.0, 6.0, 8.0, 10.0, 12.0), play(1, 2, 1, 1, 0, 1));
    }

    @Test
    void longStallStandsAfterDebt() {
        // the server costs 4 tick: two ticks with the forecast, then stand; then catches up in batches at once
        List<Double> x = play(1, 1, 0, 0, 0, 0, 5, 1);
        assertEquals(List.of(2.0, 4.0, 6.0, 8.0, 8.0, 8.0), x.subList(0, 6));
        // for the debt, two packets were missed, extra old ones too: no further than a tick from the server
        assertEquals(16.0, x.get(7), 2.0);
    }

    @Test
    void jumpResetsQueue() {
        ArrivalQueue q = new ArrivalQueue();
        q.add(at(2));
        assertEquals(2.0, q.next(0, 64, 0, 0, 0).x());
        assertEquals(4.0, q.next(2, 64, 0, 0, 0).x());  // in debt
        q.add(at(500));
        ArrivalQueue.Sample s = q.next(4, 64, 0, 0, 0);
        assertEquals(500.0, s.x());
        assertEquals(500.0, q.latest().x());
    }

    @Test
    void slowServerKeepsToItsPath() {
        // server 15 tick per second (packets less than the client tick: the debt is not cleared), the projectile circles with a radius of 60 along the
        // 2,1 block per server tick: the forecast with the last step from its place would go tangentially for hundreds of blocks
        ArrivalQueue q = new ArrivalQueue();
        double x = 0, z = 0, clock = 0;
        int sent = 0;
        for (int t = 1; t <= 400; t++) {
            clock += 15 / 20.0;
            while (sent < (int) clock) {
                double a = ++sent * 2.1 / 60;
                q.add(new ArrivalQueue.Sample(60 * Math.sin(a), 64, 60 * (1 - Math.cos(a)), 0, 0));
            }
            ArrivalQueue.Sample s = q.next(x, 64, z, 0, 0);
            if (s != null) {
                x = s.x();
                z = s.z();
            }
            double a = sent * 2.1 / 60;
            double off = Math.hypot(x - 60 * Math.sin(a), z - 60 * (1 - Math.cos(a)));
            assertTrue(off <= ArrivalQueue.MAX_DEBT * 2.1 + 0.2, "tick " + t + ": " + off + " block from the server");
        }
    }

    @Test
    void missedTickWithoutCatchUpGoesOnEvenly() {
        // skip tick without catching up: then the projectile goes tick ahead of the last place, exactly in step
        assertEquals(List.of(2.0, 4.0, 6.0, 8.0, 10.0, 12.0), play(1, 0, 1, 1, 1, 1));
    }

    @Test
    void afterJumpNoForecastByJump() {
        ArrivalQueue q = new ArrivalQueue();
        q.add(at(2));
        q.next(0, 64, 0, 0, 0);
        q.add(at(500));
        assertEquals(500.0, q.next(2, 64, 0, 0, 0).x());
        // one place after the jump: forecast does not repeat the jump
        assertEquals(500.0, q.next(500, 64, 0, 0, 0).x());
    }

    @Test
    void nothingYetStands() {
        assertNull(new ArrivalQueue().next(0, 64, 0, 0, 0));
    }
}
