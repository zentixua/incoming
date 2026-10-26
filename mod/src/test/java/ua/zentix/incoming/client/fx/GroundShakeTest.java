package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.GroundShock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Player's ground push ({@link GroundShake}): strength - ground feel and speed, without jumps. */
class GroundShakeTest {
    private static GroundShock.Sample sample(double ppvMmPerS, double felt) {
        return new GroundShock.Sample(0.1, ppvMmPerS / 1000, 15, felt);
    }

    /**
     * The intangible does not shake; stronger - with each decade of ground speed, full - to 100 mm/s; grows without jumps (the sensitivity of the
     *  model is saturated at 0,3 mm/s and does not distinguish between forces).
     */
    @Test
    void joltGrowsWithGroundVelocity() {
        assertEquals(0, GroundShake.strength(sample(0.05, 0)));
        assertEquals(0, GroundShake.strength(sample(50, 0)), "outside the model's perception band - no");
        assertEquals(1, GroundShake.strength(sample(100, 1)), 1e-9);
        assertEquals(1, GroundShake.strength(sample(500, 1)), 1e-9);
        double before = GroundShake.strength(sample(0.3, 1));
        for (double v = 0.3 * 1.05; v <= 100; v *= 1.05) {
            double s = GroundShake.strength(sample(v, 1));
            assertTrue(s > before && s - before < 0.03, "jump at " + v + " mm/s: " + before + " → " + s);
            before = s;
        }
        assertTrue(GroundShake.strength(sample(1, 1)) < GroundShake.strength(sample(10, 1)));
    }

    /** The push at the camera lasts the impulse of the ground and the attenuation of the body after it: a short blow of the rock is no shorter than a nod. */
    @Test
    void bodyRingsAfterAShortPulse() {
        GroundShock.Sample rock = new GroundShock.Sample(0.1, 0.1, 15, 1);
        assertTrue(rock.durationSeconds() + GroundShake.BODY_RING_S > 1 / CameraShake.GROUND_HZ, "i wouldn't have time to nod");
    }

    /** The arrivals of the push that recorded the waiting effect: how much tick later than the arrival. */
    private static final java.util.List<Double> FIRED = new java.util.ArrayList<>();

    private static GroundShake.Arrival arrival(double arrivalTicks, double lengthTicks, double ageAtBirth) {
        FIRED.clear();
        BlastTime.reset();
        return new GroundShake.Arrival(arrivalTicks, lengthTicks, ageAtBirth, BlastTime.ticks(), FIRED::add);
    }

    /**
     * The wave in the rock at 300 m comes through 2,2 tick after the source: no shock, no rumble before - even when the queue of
     *  effects calls for waiting twice in the tick of birth (immediately and in its tick); in the first tick after arrival - with a share of tick.
     */
    @Test
    void nothingBeforeTheWaveArrives() {
        GroundShake.Arrival a = arrival(2.2, 9.4, 0);
        assertTrue(a.tick(null, 0), "tick birth");
        assertTrue(a.tick(null, 1), "same tick, second queue call");
        BlastTime.advance();
        assertTrue(a.tick(null, 2));
        BlastTime.advance();
        assertTrue(a.tick(null, 3), "2 tick - the wave is still on the way");
        assertTrue(FIRED.isEmpty(), "push before arrival: " + FIRED);
        BlastTime.advance();
        assertTrue(!a.tick(null, 4), "arrived - the wait is over");
        assertEquals(1, FIRED.size());
        assertEquals(0.8, FIRED.getFirst(), 1e-9, "later than the arrival of the tick");
    }

    /** The packet was moving until the server passed the 5 tick: the wave (2,2 tick) is already coming - a push from the place where it was already there. */
    @Test
    void lateDeliveryJoinsTheJoltMidway() {
        GroundShake.Arrival a = arrival(2.2, 9.4, 5);
        assertTrue(!a.tick(null, 0));
        assertEquals(1, FIRED.size());
        assertEquals(2.8, FIRED.getFirst(), 1e-9);
    }

    /** The package was late for the entire push (5 s - entered later, the network stopped): the past push is not played again. */
    @Test
    void lateDeliveryDoesNotReplayAPastJolt() {
        GroundShake.Arrival a = arrival(2.2, 9.4, 100);
        assertTrue(!a.tick(null, 0), "the wait is over");
        assertTrue(FIRED.isEmpty(), "a fresh push on an old event: " + FIRED);
    }
}
