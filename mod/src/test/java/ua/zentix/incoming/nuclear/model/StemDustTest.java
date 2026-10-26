package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dust stem is the one that the suction took from the wave dust layer: in the air 15 kt at 592 m (like Hiroshima, SHOB ~790
 * ft/kt^⅓) the dusty stem does not catch up with the cap (NRC 1985, p. 22: for 10 kt on 450 m - “did not merge”), for ground - leg
 *  to cap from the first seconds; before the start of suction there is no airborne dust in the stem.
 */
class StemDustTest {
    @Test
    void airBurstDustyStemDoesNotReachTheCap() {
        StemDust d = StemDust.of(15, 592, false, StemDust.CLEAR);
        assertEquals(0, d.lambda(5), 0, "before suction (downward flow of the ball at the axis) there is no dust");
        for (double t : new double[]{30, 60, 120, 240, 400}) {
            MushroomModel.Shape s = MushroomModel.shape(t, 15, 592, false, 1e9, d);
            assertTrue(d.lambda(t) > 0, "t = " + t);
            assertTrue(s.stemTop() < 0.8 * s.capBottom(), "t = " + t + ": stem " + s.stemTop() + ", bottom cap " + s.capBottom());
        }
    }

    @Test
    void groundBurstStemIsTheFireballTrail() {
        StemDust d = StemDust.of(15, 0, true, StemDust.CLEAR);
        for (double t : new double[]{5, 30, 120}) {
            MushroomModel.Shape s = MushroomModel.shape(t, 15, 0, true, 1e9, d);
            assertTrue(d.lambda(t) > 0, "the ball on the ground carries away the dust immediately, t = " + t);
            assertTrue(s.stemTop() >= s.capBottom(), "t = " + t);
            assertTrue(s.stemTau() > 1, "ground stem is opaque: " + s.stemTau());
        }
    }

    /** Below the explosion there is more dust under it and earlier suction: the dust stems growing towards the ground. */
    @Test
    void lowerBurstFeedsMoreDust() {
        double high = StemDust.of(15, 592, false, StemDust.CLEAR).lambda(60), low = StemDust.of(15, 300, false, StemDust.CLEAR).lambda(60);
        assertTrue(low > high, low + " > " + high);
    }
}
