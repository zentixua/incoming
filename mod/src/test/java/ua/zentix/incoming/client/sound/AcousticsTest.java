package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcousticsTest {
    /** The source flies along the X axis with a constant speed v (blocks/tick), at the moment 0 - at the point x0, at an altitude of y. */
    private static Acoustics.Path straight(double x0, double y, double v) {
        return new Acoustics.Path() {
            @Override
            public void at(double t, double[] out) {
                out[0] = x0 + v * t;
                out[1] = y;
                out[2] = 0;
            }

            @Override
            public double start() {
                return -1000;
            }

            @Override
            public double maxSpeed() {
                return Math.abs(v);
            }
        };
    }

    private static Acoustics.Path straight(double x0, double v) {
        return straight(x0, 0, v);
    }

    @Test
    void stillSourceIsHeardAfterDistanceOverSpeed() {
        // stationary source in 343 blocks: sound goes exactly 20 tick (1 s)
        double te = Acoustics.emissionTime(straight(343, 0), 100, 0, 0, 0);
        assertEquals(100 - 343 / Acoustics.SPEED, te, 1e-3);
    }

    @Test
    void emittedSoundReachesListenerExactlyThen() {
        // rocket 11.5 block/tick flies towards the listener: radiation time satisfies |p(t) - L| = c (now - t)
        Acoustics.Path p = straight(500, -11.5);
        double now = 30;
        double te = Acoustics.emissionTime(p, now, 0, 0, 0);
        double[] at = new double[3];
        p.at(te, at);
        assertEquals(Math.abs(at[0]), Acoustics.SPEED * (now - te), 1e-2);
    }

    @Test
    void nothingIsHeardBeforeTheSoundArrives() {
        // the path is known from the moment 0 (there is a source in 1000 blocks): its sound reaches the ear 58 tick, before there was silence,
        //  and not the sound of the first point of the path
        Acoustics.Path p = new Acoustics.Path() {
            @Override
            public void at(double t, double[] out) {
                out[0] = 1000;
                out[1] = out[2] = 0;
            }

            @Override
            public double start() {
                return 0;
            }

            @Override
            public double maxSpeed() {
                return 0;
            }
        };
        assertTrue(Double.isNaN(Acoustics.emissionTime(p, 50, 0, 0, 0)));
        assertEquals(60 - 1000 / Acoustics.SPEED, Acoustics.emissionTime(p, 60, 0, 0, 0), 1e-3);
    }

    @Test
    void subsonicSourceHasOneEmission() {
        double[] roots = new double[4];
        for (double v : new double[] {-15, -4, 0.5, 4, 15}) {
            assertEquals(1, Acoustics.emissions(straight(300, 40, v), 100, 0, 0, 0, roots), "speed " + v);
        }
    }

    @Test
    void supersonicSourceIsSilentOutsideTheMachConeAndHasTwoEmissionsInside() {
        // 1,4 M at a height of 100 blocks, above the listener at moment 0: the Mach cone (sin μ = 1/M) comes to the ear later
        double v = 1.4 * Acoustics.SPEED, h = 100;
        Acoustics.Path p = straight(0, h, v);
        double[] roots = new double[4];
        // when the cone arrives: at the ground below the flight line it is behind the source by h·√(M² − 1)
        double behind = h * Math.sqrt(1.4 * 1.4 - 1);
        double arrival = behind / v;
        assertEquals(0, Acoustics.emissions(p, arrival - 0.5, 0, 0, 0, roots), "until the cone arrives - silence");
        assertEquals(0, Acoustics.emissions(p, -3, 0, 0, 0, roots), "supersonic source on approach cannot be heard");
        assertEquals(2, Acoustics.emissions(p, arrival + 2, 0, 0, 0, roots), "inside the cone - two moments of radiation");
        assertTrue(roots[0] > roots[1]);
        double[] at = new double[3];
        for (int i = 0; i < 2; i++) {
            p.at(roots[i], at);
            double d = Math.hypot(at[0], at[1]);
            assertEquals(d, Acoustics.SPEED * (arrival + 2 - roots[i]), 1e-2, "root " + i);
        }
        // late - the source leaves (the tone is lower), early - the sound is approaching, the frequency is negative (backwards)
        p.at(roots[0], at);
        double d0 = Math.hypot(at[0], at[1]);
        assertTrue(Acoustics.doppler(v, 0, 0, -at[0] / d0, -at[1] / d0, 0) > 0);
        p.at(roots[1], at);
        double d1 = Math.hypot(at[0], at[1]);
        assertTrue(Acoustics.doppler(v, 0, 0, -at[0] / d1, -at[1] / d1, 0) < 0);
    }

    @Test
    void supersonicLaterEmissionFollowsTheSourceForAnySpeed() {
        // any supersonic speed: inside the cone there is a late root and its Doppler is finite and less than 1
        double[] roots = new double[2];
        for (double mach : new double[] {1.05, 1.4, 2, 5}) {
            double v = mach * Acoustics.SPEED;
            Acoustics.Path p = straight(0, 60, v);
            double now = 60 * Math.sqrt(mach * mach - 1) / v + 20;
            assertTrue(Acoustics.emissions(p, now, 0, 0, 0, roots) >= 1, "M " + mach);
            double[] at = new double[3];
            p.at(roots[0], at);
            double d = Math.hypot(at[0], at[1]);
            double f = Acoustics.doppler(v, 0, 0, -at[0] / d, -at[1] / d, 0);
            assertTrue(f > 0 && f < 1, "M " + mach + ": " + f);
        }
    }

    @Test
    void rootIsFoundEvenWhenTheSpeedBoundIsFarTooHigh() {
        // a jump in the path between ticks increases the speed from above: the search steps become smaller, but the root is located in 19 km
        Acoustics.Path still = new Acoustics.Path() {
            @Override
            public void at(double t, double[] out) {
                out[0] = 19_000;
                out[1] = out[2] = 0;
            }

            @Override
            public double start() {
                return -1300;
            }

            @Override
            public double maxSpeed() {
                return 1000;
            }
        };
        assertEquals(-19_000 / Acoustics.SPEED, Acoustics.emissionTime(still, 0, 0, 0, 0), 1e-3);
    }

    @Test
    void approachingSourceSoundsHigherRecedingLower() {
        double c = Acoustics.SPEED;
        // approaching at speed c/2: frequency ×2; removed from c/2: ×2/3
        assertEquals(2.0, Acoustics.doppler(c / 2, 0, 0, 1, 0, 0), 1e-9);
        assertEquals(2.0 / 3.0, Acoustics.doppler(-c / 2, 0, 0, 1, 0, 0), 1e-9);
        // across the beam - without shift
        assertEquals(1.0, Acoustics.doppler(0, 0, 5, 1, 0, 0), 1e-9);
        // leaves at the speed of 5c: ×1/6, without limit
        assertEquals(1.0 / 6, Acoustics.doppler(-5 * c, 0, 0, 1, 0, 0), 1e-9);
    }
}
