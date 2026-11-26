package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.util.StrikeClock;
import ua.zentix.incoming.util.StrikeHistory;
import ua.zentix.incoming.util.WorldClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fallout's ground age ({@code docs/design/time.md} §8.3 rule 4, review probe {@code r2_p4.py} §1): a deposit lands at its
 * motion age and ages on the ground clock from then. A vanilla day (k = 72), 15 kt surface burst in the fit's reference wind
 * (24 km/h), a 20-minute show and the 60-s rate blend.
 */
class FalloutGroundAgeTest {
    private static final double WIND = FalloutModel.REFERENCE_WIND;
    private static final FalloutModel MODEL = new FalloutModel(15, 0, 0, WIND);

    /** A vanilla day simulated tick by tick to real age {@code until}: the clock with its history, unseen jumps at {@code unseenAt}. */
    private record Day(StrikeClock clock, StrikeHistory history, long ageNow, double unseenNow) {
        static Day run(long until, long unseenAt, double unseen) {
            StrikeHistory h = new StrikeHistory();
            StrikeClock c = StrikeClock.start(0);
            double u = 0;
            for (long age = 0; age <= until; age++) {
                if (age == unseenAt) u = unseen;
                boolean force = false;
                if (!c.ended() && age >= 24_000) {
                    c = c.end(age, age, 0);
                    force = true;
                }
                if (c.rampDue(age)) {
                    c = c.ramp(age, age);
                    force = true;
                }
                h.sample(age, age, 0, u, c.motion(age, 0), force);
            }
            return new Day(c, h, until, u);
        }

        /** Ground clock G now, seconds. */
        double ground() {
            return clock.strike(ageNow, ageNow, 0) + unseenNow * WorldClock.SECONDS_PER_TICK;
        }

        /** Landing of fallout arriving at motion age {@code m}, seconds. */
        FalloutModel.Landing landing(double m) {
            double age = history.ageAtMotion(clock, m, ageNow, 0);
            return new FalloutModel.Landing(m, history.groundLead(clock, age, ageNow, ageNow, 0, unseenNow));
        }

        /** Strike clock S at the moment the motion age was {@code m}, seconds. */
        double strikeAtMotion(double m) {
            return history.strikeAt(clock, history.ageAtMotion(clock, m, ageNow, 0), ageNow, ageNow, 0);
        }
    }

    /**
     * The probe's numbers: under one age for the whole field (S since the burst) fallout landing at 13 / 20 / 40 km read
     * 1/53, 1/91 and 1/129 of the law; with its ground age it lands with exactly the law's rate for its age in the air.
     */
    @Test
    void falloutLandsWithTheLawsRateOnAVanillaDay() {
        Day day = Day.run(2 * 72_000, -1, 0);
        double[][] rows = {{8, 0.33, 0.3, 1}, {13, 0.54, 14.7, 53}, {20, 0.83, 35.7, 91}, {40, 1.67, 95.7, 129}};
        for (double[] row : rows) {
            double x = row[0] * 1000;
            double m = MODEL.arrivalHours(x, 0) * 3600;
            assertEquals(row[1], m / 3600, 0.005, row[0] + " km: lands at M");
            double s = day.strikeAtMotion(m);
            assertEquals(row[2], s / 3600, 0.1, row[0] + " km: S at landing");
            double h1 = MODEL.doseRateAtH1(x, 0), law = FalloutModel.groundRate(h1, m / 3600);
            double oneAge = MODEL.doseRate(x, 0, s / 3600);
            assertEquals(row[3], law / oneAge, row[3] * 0.02, row[0] + " km: one age for the whole field reads 1/" + row[3]);
            FalloutModel.Landing at = day.landing(m);
            assertEquals(m, at.groundHours(s + 0) * 3600, 1e-6, row[0] + " km: ground age at landing");
            assertEquals(1, at.rate(h1, s) / law, 1e-9, row[0] + " km: dose rate at landing");
            // then it ages on the ground clock: an hour of the world later
            assertEquals(FalloutModel.groundRate(h1, m / 3600 + 1), at.rate(h1, s + 3600), 1e-9 * law);
        }
    }

    /** Before its landing a deposit gives no dose rate and no dose; a step across the landing counts from it. */
    @Test
    void nothingBeforeTheLanding() {
        FalloutModel.Landing at = new FalloutModel.Landing(1950, 50_000);
        assertEquals(0, at.rate(100, 51_949), 0);
        assertTrue(at.rate(100, 51_950) > 0);
        assertEquals(FalloutModel.groundDose(100, 1950 / 3600.0, 2950 / 3600.0), at.dose(100, 10_000, 52_950), 1e-12);
        assertEquals(0, at.dose(100, 10_000, 51_000), 0);
    }

    /**
     * Rule 7 for the ground: a night someone stayed awake for (an unseen jump) moves the dose of fallout already down, not
     * the motion age or the strike clock; fallout landing after that night lands with its law's rate all the same.
     */
    @Test
    void anUnseenNightAgesTheFalloutOnTheGround() {
        double night = 8_000; // 8 world hours
        long jumpAge = 60_000; // 50 real minutes after the burst
        Day awake = Day.run(80_000, jumpAge, night), none = Day.run(80_000, -1, 0);
        assertEquals(none.clock().motion(80_000, 0), awake.clock().motion(80_000, 0), 0, "the motion age moved");
        assertEquals(none.clock().strike(80_000, 80_000, 0), awake.clock().strike(80_000, 80_000, 0), 0, "the strike clock moved");
        assertEquals(none.ground() + night * WorldClock.SECONDS_PER_TICK, awake.ground(), 1e-6, "the ground clock");
        // 13 km landed before the night: it is 8 hours older on the ground
        double m13 = MODEL.arrivalHours(13_000, 0) * 3600;
        FalloutModel.Landing early = awake.landing(m13);
        assertEquals(none.landing(m13).groundHours(none.ground()) + 8, early.groundHours(awake.ground()), 1e-6);
        double h1 = MODEL.doseRateAtH1(13_000, 0);
        assertTrue(early.rate(h1, awake.ground()) < none.landing(m13).rate(h1, none.ground()) * 0.9, "the dose did not move");
        // 40 km lands at 1.67 h real age, after the night: ground age at landing is its arrival
        double m40 = MODEL.arrivalHours(40_000, 0) * 3600;
        Day later = Day.run(130_000, jumpAge, night);
        FalloutModel.Landing late = later.landing(m40);
        assertEquals(m40, late.groundHours(late.shift() + m40) * 3600, 1e-6);
        assertEquals(later.history().strikeAt(later.clock(), m40 * 20, 130_000, 130_000, 0) + night * WorldClock.SECONDS_PER_TICK - m40,
                late.shift(), 1e-3, "the lead at landing holds the night");
    }

    /**
     * Black-rain dust lands when its rain reaches the point, on the motion age like the plume (time.md §9 change 4): 15 km
     * downwind of a 2 km fire zone the rain arrives at M = 52.5 min, so on a vanilla day the dust lands 52.5 real minutes after
     * the burst with ground age 0.875 h. Read on the strike clock, which a vanilla day passes about a minute after the show,
     * it would land 21 real minutes after the burst, half an hour ahead of its rain, at 1.96 times the rate (review probe
     * {@code RainDepositLandingProbeGameTests}).
     */
    @Test
    void blackRainDustLandsWithItsRain() {
        BlackRainModel rain = new BlackRainModel(15, 2000, 0, WIND, 1);
        double onset = rain.startSeconds(15_000, 0);
        assertEquals(1200 + 13_000 / WIND, onset, 1e-9);
        Day day = Day.run(80_000, -1, 0);
        double age = day.history().ageAtMotion(day.clock(), onset, day.ageNow(), 0);
        assertEquals(onset * 20, age, 1e-6, "real ticks to the landing");
        FalloutModel.Landing at = day.landing(onset);
        double g = day.history().strikeAt(day.clock(), age, day.ageNow(), day.ageNow(), 0);
        assertEquals(onset / 3600, at.groundHours(g), 1e-9, "ground age at landing");
        assertTrue(!at.landed(day.history().strikeAt(day.clock(), age - 20, day.ageNow(), day.ageNow(), 0)), "landed a second early");
        // the strike-clock reading: the moment S passed the onset, about a minute after the show
        double early = 0;
        for (long t = 0; t <= day.ageNow(); t++) {
            if (day.history().strikeAt(day.clock(), t, day.ageNow(), day.ageNow(), 0) >= onset) {
                early = t / 20.0;
                break;
            }
        }
        assertEquals(21, early / 60, 0.5, "real minutes after the burst on the strike clock");
        assertEquals(1.96, FalloutModel.groundRate(1, early / 3600) / FalloutModel.groundRate(1, onset / 3600), 0.01);
    }

    /** {@link FalloutModel#groundDose} is the integral of {@link FalloutModel#groundRate}, through the half-hour floor. */
    @Test
    void groundDoseIntegratesTheRate() {
        double[][] spans = {{0.1, 0.4}, {0.2, 3}, {1, 24}, {5, 5000}};
        for (double[] s : spans) {
            int n = 200_000;
            double sum = 0, lo = s[0], q = Math.pow(s[1] / s[0], 1.0 / n);
            for (int i = 0; i < n; i++) {
                double hi = lo * q;
                sum += FalloutModel.groundRate(1, Math.sqrt(lo * hi)) * (hi - lo);
                lo = hi;
            }
            assertEquals(sum, FalloutModel.groundDose(1, s[0], s[1]), sum * 1e-6, s[0] + "–" + s[1] + " h");
        }
        assertEquals(FalloutModel.decayHours(5, 9) * Math.pow(9, -FalloutModel.DECAY_EXP), FalloutModel.groundDose(1, 5, 9), 1e-12);
    }
}
