package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EyeInjuryTest {
    private static final double V = ThermalModel.VISIBILITY_CLEAR;

    /** At a distance fig. 12.88 dazzle - the same 10 s, day and night, for any pattern power. */
    @Test
    void figureRangeGivesTenSeconds() {
        for (double y : new double[]{1, 15, 300, 10_000}) {
            for (double dark : new double[]{0, 0.5, 1}) {
                assertEquals(EyeInjury.EDGE_S, EyeInjury.dazzleSeconds(EyeInjury.edgeFluence(y, dark), y, dark), 1e-9, y + " kt, darkness " + dark);
            }
        }
    }

    /** Closer - longer, but not longer than the eye comes to life after complete fading; without light - nothing. */
    @Test
    void longerCloserUpToFullBleach() {
        assertEquals(0, EyeInjury.dazzleSeconds(0, 15, 0));
        assertEquals(0, EyeInjury.dazzleSeconds(1e-9, 15, 0));
        double prev = 0;
        for (double r = 40_000; r >= 300; r *= 0.9) {
            double t = EyeInjury.dazzleSeconds(ThermalModel.fluenceCalPerCm2(r, 15, false, V), 15, 0);
            assertTrue(t >= prev, "on " + r + " m: " + t + " < " + prev);
            prev = t;
        }
        assertEquals(EyeInjury.FULL_DAY_S, prev, 1e-9);
        assertEquals(EyeInjury.FULL_NIGHT_S, EyeInjury.dazzleSeconds(1000, 15, 1), 1e-9);
    }

    /** 15 kt: during the day in 3 km - less than a minute, at night in 10 km - minutes (an eye accustomed to the dark is illuminated more strongly). */
    @Test
    void fifteenKilotons() {
        double day = EyeInjury.dazzleSeconds(ThermalModel.fluenceCalPerCm2(3000, 15, false, V), 15, 0);
        assertTrue(day > 30 && day < EyeInjury.FULL_DAY_S, "during the day at 3 km: " + day);
        double night = EyeInjury.dazzleSeconds(ThermalModel.fluenceCalPerCm2(10_000, 15, false, V), 15, 1);
        assertTrue(night > 300 && night < EyeInjury.FULL_NIGHT_S, "at night in 10 km: " + night);
        double q = ThermalModel.fluenceCalPerCm2(10_000, 15, false, V);
        assertTrue(EyeInjury.dazzleSeconds(q, 15, 1) > EyeInjury.dazzleSeconds(q, 15, 0));
    }

    /** Retinal burn - as shown in Fig. 12.88 (10 kt during the day 18,5 miles from the epicenter when detonated at 10 000 feet), at night further, closer in the haze. */
    @Test
    void retinaRangeFollowsFigure() {
        assertEquals(Math.hypot(18.5 * 1609.344, 3048), EyeInjury.retinaRange(10, 0, V), 1);
        assertEquals(Math.hypot(30.5 * 1609.344, 3048), EyeInjury.retinaRange(10, 1, V), 1);
        assertTrue(EyeInjury.retinaRange(15, 1, V) > EyeInjury.retinaRange(15, 0, V));
        assertEquals(EyeInjury.retinaRange(15, 0, V) / 2, EyeInjury.retinaRange(15, 0, V / 2), 1e-6);
    }

    /** Light at the eye: during the day 15, at night in the open air 4 (the sky is darker on 11) - night, darker too. */
    @Test
    void darknessByLight() {
        assertEquals(0, EyeInjury.darkness(15));
        assertEquals(1, EyeInjury.darkness(4));
        assertEquals(5 / 11.0, EyeInjury.darkness(10), 1e-12);
        assertEquals(1, EyeInjury.darkness(0));
        assertEquals(1, EyeInjury.darkness(-3));
        assertEquals(0, EyeInjury.darkness(20));
    }
}
