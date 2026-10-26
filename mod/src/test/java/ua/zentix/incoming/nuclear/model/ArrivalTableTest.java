package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrivalTableTest {
    private static final ArrivalTable T15 = ArrivalTable.of(15, 0, 20000), AIR = ArrivalTable.of(15, 592, 20000);

    /** Arrival time of the ground front 15 kt from section 1.1 (Broad, PT; standard DNA for 2W gives the same 0.07 s to 200 m), tolerance 15%. */
    @ParameterizedTest
    @CsvSource({"200, 0.07", "1000, 1.6", "2000, 4.3", "5000, 12.8", "10000, 27"})
    void arrivalTimes15kt(double rangeM, double seconds) {
        assertEquals(seconds, T15.arrivalSeconds(rangeM), seconds * 0.15);
    }

    /** Air 15 kt on 592 m: to the ground - along Broad at the same slant range, under the detonation - free air. */
    @Test
    void airBurstReachesTheGroundByBrode() {
        for (double g : new double[]{0, 300, 1000, 5000}) {
            double s = Math.hypot(g, 592);
            assertEquals(HobBlast.arrivalSeconds(g, 592, 15), AIR.arrivalSeconds(s), 0.005, g + " m");
        }
        assertEquals(HobBlast.freeAirArrivalSeconds(300, 15), AIR.arrivalSeconds(300), 0.01);
    }

    @Test
    void inverseMatches() {
        for (double r = 0.5; r < 40000; r *= 1.7) {
            assertEquals(r, T15.radiusAt(T15.arrivalSeconds(r)), r * 1e-3 + 1e-6);
        }
        assertEquals(0, T15.radiusAt(0), 0);
        assertEquals(0, T15.arrivalSeconds(0), 0);
    }

    @Test
    void beyondTableAtSoundSpeed() {
        double t = T15.arrivalSeconds(20000);
        assertEquals(t + 10, T15.arrivalSeconds(20000 + 3430), 1e-9);
        assertEquals(20000 + 343 * 5, T15.radiusAt(t + 5), 1e-6);
    }

    @Test
    void monotonic() {
        for (ArrivalTable t : new ArrivalTable[]{T15, AIR}) {
            double prev = -1;
            for (double s = 0; s < 60; s += 0.05) {
                double r = t.radiusAt(s);
                assertTrue(r > prev || s == 0);
                prev = r;
            }
        }
    }
}
