package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.NukeClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ua.zentix.incoming.nuclear.model.SmokeParcelsTest.day;

/** Black rain is the rain-out of the fires' drawn smoke parcels ({@code docs/design/time.md} §8.3, rule 4). */
class BlackRainModelTest {
    /**
     * As in Hiroshima: 15 kt airburst, fires out to 2 km, wind 2.5 m/s east (+x).
     */
    private static final BlackRainModel HIROSHIMA = new BlackRainModel(15, 2000, 0, 2.5, 1);

    @Test
    void noFiresNoRain() {
        BlackRainModel dry = new BlackRainModel(15, 0, 0, 2.5, 1);
        for (double s = 0; s < 8000; s += 10) assertFalse(dry.raining(0, 0, day(1, s)));
        assertEquals(0, dry.depositH1(0, 0, 1e6), 0);
        assertEquals(0, dry.endSeconds(), 0);
    }

    /**
     * Above the fires: 20 minutes after the burst (the end of the show, the same on every day) for an hour and a half at a 24-hour
     * day; never before the fires had 20 minutes to build the storm cloud.
     */
    @Test
    void overTheFiresWhileTheyBurn() {
        double start = BlackRainModel.START_S;
        assertEquals(20 * 60, start, 1e-9);
        for (double x = -20_000; x <= 20_000; x += 500)
            for (double z = -8000; z <= 8000; z += 500) assertTrue(HIROSHIMA.startSeconds(x, z) >= start, "no rain before its cause");
        for (double k : new double[]{1, 72}) {
            assertFalse(HIROSHIMA.raining(0, 0, day(k, start - 1)));
            assertTrue(HIROSHIMA.raining(0, 0, day(k, start + 1)));
            assertTrue(HIROSHIMA.raining(-1500, 0, day(k, start + 30)));
        }
        assertTrue(HIROSHIMA.raining(-1500, 0, day(1, start + 300)));
        assertFalse(HIROSHIMA.raining(0, 0, day(1, start + BlackRainModel.durationSeconds() + 1)));
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
        assertEquals(9000 / 2.5, far - near, 1e-6);
        assertTrue(HIROSHIMA.endSeconds() > far + BlackRainModel.durationSeconds());
        // the first rain arrives as its parcel does, at a 24-hour day and on a vanilla day alike
        for (double k : new double[]{1, 72}) {
            assertFalse(HIROSHIMA.raining(11000, 0, day(k, far - 2)), "k " + k + ": before the parcel");
            assertTrue(HIROSHIMA.raining(11000, 0, day(k, far + 2)), "k " + k + ": under the parcel");
        }
    }

    /** Seconds of rain at a point over a run of the motion age, one-second steps. */
    private static double rainSeconds(BlackRainModel m, double x, double z, double k) {
        double n = 0;
        for (double t = 0; t < 6 * 3600; t += 1) if (m.raining(x, z, day(k, t))) n++;
        return n;
    }

    /**
     * The rain's window is the parcels' law: at a 24-hour day a point gets its 90 minutes; on a vanilla day the fires stop
     * feeding the parcels about 10 minutes after the show, so a point gets at most that.
     */
    @Test
    void ninetyMinutesAtARealDayMinutesOnAVanillaDay() {
        for (double x : new double[]{0, 5000, 12_000}) {
            double real = rainSeconds(HIROSHIMA, x, 0, 1), vanilla = rainSeconds(HIROSHIMA, x, 0, 72);
            assertEquals(BlackRainModel.durationSeconds(), real, 2, "24-hour day, " + x + " m downwind");
            assertTrue(vanilla > 5 * 60 && vanilla <= 10.5 * 60, "vanilla day, " + x + " m downwind: " + vanilla / 60 + " min");
        }
    }

    /**
     * Rain never falls from a clear sky or ahead of its smoke. The earlier law, its window on the strike clock, put rain at 20 km
     * at 119 minutes of that clock, about 21.5 real minutes after a vanilla burst, while the haze reached 4 km and was gone by
     * 28–30 minutes. Now, on every day, rain falls only under a drawn parcel that has travelled there.
     */
    @Test
    void rainNeverFromAClearSkyNorAheadOfItsSmoke() {
        SmokeParcels p = HIROSHIMA.parcels();
        for (double k : new double[]{1, 12, 72}) {
            for (double t = 0; t < 4 * 3600; t += 61) {
                SmokeParcels.Clock c = day(k, t);
                for (double x = -3000; x <= 40_000; x += 1000) {
                    for (double z = -12_000; z <= 12_000; z += 2000) {
                        if (!HIROSHIMA.raining(x, z, c)) continue;
                        double s = p.shift(x, c);
                        assertTrue(s <= p.wind() * c.motion(), "k " + k + ": rain ahead of its smoke at " + x + " m, " + t + " s");
                        assertTrue(p.drawn(s, c) * p.depth() >= SmokeParcels.GONE && p.under(x, z, c), "k " + k + ": rain from a clear sky at " + x + ", " + z + ", " + t + " s");
                    }
                }
            }
        }
        // the earlier probe's moment: 20 km downwind, strike clock 119 min on a vanilla day
        double t = NukeClock.SHOW_S;
        while (day(72, t).strikeAt(day(72, t).motion()) < 119 * 60) t += 1;
        assertTrue(t < 23 * 60, "strike clock 119 min at " + t / 60 + " real min");
        assertFalse(HIROSHIMA.raining(20_000, 0, day(72, t)), "rain 20 km out at strike-clock 119 min");
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
        double hours = start / 3600;
        double total = 5 * h1 * Math.pow(hours, -0.2);
        assertTrue(total > 1 && total < 40, "dose for the entire time, R: " + total);
        assertEquals(0, new BlackRainModel(15, 2000, 0, 2.5, 0).depositH1(2500, 0, start), 0, "for the ground-based, everything is in the trace");
        assertTrue(HIROSHIMA.depositH1(15000, 0, 1e6) < h1, "weaker downwind");
    }
}
