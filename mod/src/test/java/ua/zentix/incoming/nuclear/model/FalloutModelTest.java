package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FalloutModelTest {
    /** 15 kt ground, wind 24 km/h east (+x). */
    private static final FalloutModel GROUND = new FalloutModel(15, 0, 0, FalloutModel.REFERENCE_WIND);

    /** Axial contours H+1 against reference NUKEMAP, tolerance 25%. */
    @ParameterizedTest
    @CsvSource({"1000, 4000", "100, 13000", "10, 40000"})
    void contoursDownwind(double rate, double rangeM) {
        assertEquals(rangeM, contour(GROUND, rate, 0), rangeM * 0.25);
    }

    @Test
    void upwindShorterThanDownwind() {
        for (double rate : new double[]{1000, 100, 10}) {
            assertTrue(contour(GROUND, rate, Math.PI) < contour(GROUND, rate, 0) / 3, "outline " + rate);
        }
        // across the trail is also narrower than along
        assertTrue(contour(GROUND, 100, Math.PI / 2) < contour(GROUND, 100, 0) / 3);
    }

    @Test
    void windDirectionRotatesTrail() {
        FalloutModel north = new FalloutModel(15, 0, Math.PI / 2, FalloutModel.REFERENCE_WIND);
        assertEquals(GROUND.doseRateAtH1(8000, 300), north.doseRateAtH1(-300, 8000), 1e-6);
    }

    @Test
    void decaySevenTen() {
        double h1 = GROUND.doseRate(5000, 0, 1);
        assertEquals(GROUND.doseRateAtH1(5000, 0), h1, 1e-9);
        assertEquals(0.1, GROUND.doseRate(5000, 0, 7) / h1, 0.01);
        assertEquals(0.01, GROUND.doseRate(5000, 0, 49) / h1, 0.001);
    }

    @Test
    void zeroBeforeArrival() {
        double arrival = GROUND.arrivalHours(10000, 0);
        assertEquals(10000 / FalloutModel.REFERENCE_WIND / 3600, arrival, 1e-9);
        assertEquals(0, GROUND.doseRate(10000, 0, arrival - 1e-3), 0);
        assertTrue(GROUND.doseRate(10000, 0, arrival + 1e-3) > 0);
    }

    /**
     * Under the cloud, against the wind and from the side, precipitation is not before large particles reach: 4·Y^0.2 min (Miller model),
     * 1 Mt - 16 min, as in “Attack Environment Manual" (1973).
     */
    @Test
    void nothingFallsBeforeLargestParticles() {
        assertEquals(6.9 / 60, GROUND.firstArrivalHours(), 0.1 / 60);
        assertEquals(16.0 / 60, new FalloutModel(1000, 0, 0, FalloutModel.REFERENCE_WIND).firstArrivalHours(), 0.2 / 60);
        double first = GROUND.firstArrivalHours();
        for (double[] p : new double[][]{{0, 0}, {-1000, 0}, {0, 1000}, {500, 0}}) {
            assertEquals(first, GROUND.arrivalHours(p[0], p[1]), 1e-9);
            assertEquals(0, GROUND.doseRate(p[0], p[1], first - 1e-3), 0);
            assertTrue(GROUND.doseRate(p[0], p[1], first + 1e-3) > 0);
        }
    }

    @Test
    void airBurstHasNoFallout() {
        FalloutModel air = new FalloutModel(15, Yield.optimalBurstHeight(15), 0, FalloutModel.REFERENCE_WIND);
        assertEquals(0, air.surfaceFraction(), 0);
        assertEquals(0, air.doseRateAtH1(2000, 0), 0);
        assertEquals(1, GROUND.surfaceFraction(), 0);
        double low = new FalloutModel(15, 100, 0, FalloutModel.REFERENCE_WIND).surfaceFraction();
        assertTrue(low > 0 && low < 1);
    }

    /**
     * Change 8 of time.md §9 by the review probe's numbers ({@code r2_p4.py} §2): a 15 kt surface burst in the reference wind
     * peaks at 1.5·10⁵ R/h at H+1 at ground zero; its trace still lands above the floor 270 km downwind, 11.24 h after the burst
     * (the probe's 1 % search steps read 271 km, 11.3 h);
     * ground zero falls below the floor after 9.55·10⁵ world hours, 109 world years (1.5 real years on a vanilla day).
     */
    @Test
    void falloutFollowsItsLawToTheFloor() {
        assertEquals(1.5e5, GROUND.peakH1(), 1);
        double last = GROUND.lastLandingHours();
        assertEquals(11.24, last, 0.01);
        assertEquals(270, last * 3600 * FalloutModel.REFERENCE_WIND / 1000, 0.5);
        // the landing dose rate there is the floor
        double x = last * 3600 * FalloutModel.REFERENCE_WIND;
        assertEquals(FalloutModel.FLOOR, FalloutModel.groundRate(GROUND.doseRateAtH1(x, 0), last), FalloutModel.FLOOR * 1e-6);
        double end = Math.pow(GROUND.peakH1() / FalloutModel.FLOOR, 1 / FalloutModel.DECAY_EXP);
        assertEquals(9.55e5, end, 0.01e5);
        assertEquals(109, end / 8760, 1);
        assertEquals(1.5, end * 50 / (365 * 86400), 0.05, "real years on a vanilla day");
        // a weaker wind lands longer and nearer; an air burst has nothing to land
        assertTrue(new FalloutModel(15, 0, 0, 2).lastLandingHours() > last);
        assertEquals(0, new FalloutModel(15, Yield.optimalBurstHeight(15), 0, FalloutModel.REFERENCE_WIND).lastLandingHours(), 0);
    }

    /**
     * The reach bound ({@link FalloutModel#boundH1}, {@link FalloutModel#reachMetres}) never misses fallout: every point at
     * least r from ground zero is at most the bound, for several yields, winds and directions; and after one world year the
     * reach above the floor holds the axis' 5.5 km (probe §2).
     */
    @Test
    void theReachBoundsTheField() {
        java.util.Random rnd = new java.util.Random(7);
        for (FalloutModel m : new FalloutModel[]{GROUND, new FalloutModel(1000, 0, 1, 2), new FalloutModel(0.1, 5, 4, 15),
                new FalloutModel(15, 0, -2, 0.1)}) {
            for (int i = 0; i < 20_000; i++) {
                double r = Math.exp(rnd.nextDouble() * Math.log(2e6)), a = rnd.nextDouble() * 2 * Math.PI;
                double x = r * Math.cos(a), z = r * Math.sin(a);
                assertTrue(m.doseRateAtH1(x, z) <= m.boundH1(r) * (1 + 1e-9), m + " at " + x + ", " + z);
            }
            for (int i = 1; i < 2000; i++) assertTrue(m.boundH1(i * 100) <= m.boundH1((i - 1) * 100), "bound grows at " + i * 100);
        }
        double year = 8760, reach = GROUND.reachMetres(year, FalloutModel.FLOOR);
        assertTrue(reach >= 5500 && reach < 15_000, "reach after a year " + reach);
        assertTrue(GROUND.reachMetres(10, FalloutModel.FLOOR * 1e-3) > GROUND.reachMetres(year, FalloutModel.FLOOR * 1e-3));
        assertEquals(0, GROUND.reachMetres(1e6, FalloutModel.FLOOR), 0, "past the floor: nowhere");
        // the cost bound of an old field: beyond this a player or mob costs one distance check (GroundFallout.NEGLIGIBLE)
        assertEquals(344, GROUND.reachMetres(7 * 24, FalloutModel.FLOOR / 10) / 1000, 5, "km after a world week");
        assertEquals(36, GROUND.reachMetres(year, FalloutModel.FLOOR / 10) / 1000, 1, "km after a world year");
    }

    /** The distance from the epicenter is in the direction of angle, where the power at H+1 drops to rate. */
    private static double contour(FalloutModel m, double rate, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return Solve.decreasingRoot(r -> m.doseRateAtH1(r * c, r * s), rate, 1, 1e6);
    }
}
