package ua.zentix.incoming.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Air absorption was checked against the table 2 ISO 9613-2 (according to ISO 9613-1) at mid-octave frequencies. */
class AirTest {
    private static final double[] OCTAVES = {63, 125, 250, 500, 1000, 2000, 4000, 8000};

    private static void table(double celsius, double[] dbPerKm) {
        for (int i = 0; i < OCTAVES.length; i++) {
            double got = Air.alpha(OCTAVES[i], celsius, 70, 101.325) * 1000;
            // the table is rounded to tenths, and the frequencies in it are the exact midpoints of octaves, not round ones
            assertEquals(dbPerKm[i], got, Math.max(0.05, 0.02 * dbPerKm[i]), celsius + " °C, " + OCTAVES[i] + " Hz");
        }
    }

    @Test
    void matchesIsoTableAtTenDegrees() {
        table(10, new double[] {0.1, 0.4, 1.0, 1.9, 3.7, 9.7, 32.8, 117});
    }

    @Test
    void matchesIsoTableAtTwentyDegrees() {
        // the same numbers as the distant entries in tools/build_sounds.py (AIR_DB_KM)
        table(20, new double[] {0.1, 0.3, 1.1, 2.8, 5.0, 9.0, 22.9, 76.6});
    }
}
