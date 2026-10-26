package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackRainModelTest {
    /** Like in Hiroshima: 15 air temperature, fires up to 2 km, wind 2,5 m/s to the east (+x). */
    private static final BlackRainModel HIROSHIMA = new BlackRainModel(15, 2000, 0, 2.5, 1);

    @Test
    void noFiresNoRain() {
        BlackRainModel dry = new BlackRainModel(15, 0, 0, 2.5, 1);
        for (double s = 0; s < 2000; s += 10) assertFalse(dry.raining(0, 0, s));
        assertEquals(0, dry.depositH1(0, 0, 1e6), 0);
        assertEquals(0, dry.endSeconds(), 0);
    }

    /** Above fires - 20 minutes after the explosion and an hour and a half, on the clock of a storm - 15 times faster. */
    @Test
    void overTheFiresWhileTheyBurn() {
        double start = BlackRainModel.START_S / BlackRainModel.COMPRESSION;
        assertEquals(80, start, 1e-9);
        assertFalse(HIROSHIMA.raining(0, 0, start - 1));
        assertTrue(HIROSHIMA.raining(0, 0, start + 1));
        assertTrue(HIROSHIMA.raining(-1500, 0, start + 300));
        assertFalse(HIROSHIMA.raining(0, 0, start + BlackRainModel.durationSeconds() + 1));
    }

    /** Uda: heavy rain - 19 × 11 km downwind, light rain - up to 29 km; There is no rain behind the fires against the wind. */
    @Test
    void streakDownwindLikeHiroshima() {
        double late = 1e9;
        assertTrue(HIROSHIMA.startSeconds(17000, 0) < late);
        assertTrue(HIROSHIMA.startSeconds(17000, 5000) < late, "half-width ~5,5 km");
        assertFalse(HIROSHIMA.startSeconds(17000, 7000) < late);
        assertFalse(HIROSHIMA.startSeconds(25000, 0) < late);
        assertFalse(HIROSHIMA.startSeconds(-3000, 0) < late);
        // in the wind the rain comes later - on the path of the clouds
        double near = HIROSHIMA.startSeconds(2000, 0), far = HIROSHIMA.startSeconds(11000, 0);
        assertEquals(9000 / 2.5 / BlackRainModel.COMPRESSION, far - near, 1e-6);
        assertTrue(HIROSHIMA.endSeconds() > far + BlackRainModel.durationSeconds());
    }

    /**
     * Washed onto the ground near an air explosion - between Hiroshima (Koi–Takasu, 1–3 R for all time) and Nagasaki (Nishiyama,
     * 20–40 R): dose in an open place near the fire zone from the beginning of the rain and forever, law t^−1.2.
     */
    @Test
    void airBurstRainIsWeaklyRadioactive() {
        double start = HIROSHIMA.startSeconds(2500, 0);
        assertEquals(0, HIROSHIMA.depositH1(2500, 0, start - 1), 0);
        double h1 = HIROSHIMA.depositH1(2500, 0, start);
        double hours = start * 20 / 1000;
        double total = 5 * h1 * Math.pow(hours, -0.2);
        assertTrue(total > 1 && total < 40, "dose for the entire time, R: " + total);
        assertEquals(0, new BlackRainModel(15, 2000, 0, 2.5, 0).depositH1(2500, 0, start), 0, "for the ground-based, everything is in the trace");
        assertTrue(HIROSHIMA.depositH1(15000, 0, 1e6) < h1, "weaker downwind");
    }
}
