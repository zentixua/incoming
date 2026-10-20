package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcousticsTest {
    /** Источник летит по оси X с постоянной скоростью v (блоков/тик), в момент 0 — в точке x0, на высоте y. */
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
        // неподвижный источник в 343 блоках: звук идёт ровно 20 тиков (1 с)
        double te = Acoustics.emissionTime(straight(343, 0), 100, 0, 0, 0);
        assertEquals(100 - 343 / Acoustics.SPEED, te, 1e-3);
    }

    @Test
    void emittedSoundReachesListenerExactlyThen() {
        // ракета 11.5 блока/тик летит на слушателя: время излучения удовлетворяет |p(t) - L| = c (now - t)
        Acoustics.Path p = straight(500, -11.5);
        double now = 30;
        double te = Acoustics.emissionTime(p, now, 0, 0, 0);
        double[] at = new double[3];
        p.at(te, at);
        assertEquals(Math.abs(at[0]), Acoustics.SPEED * (now - te), 1e-2);
    }

    @Test
    void nothingIsHeardBeforeTheSoundArrives() {
        // путь известен с момента 0 (там источник в 1000 блоках): его звук идёт до уха 58 тиков, раньше — тишина,
        // а не звук первой точки пути
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
            assertEquals(1, Acoustics.emissions(straight(300, 40, v), 100, 0, 0, 0, roots), "скорость " + v);
        }
    }

    @Test
    void supersonicSourceIsSilentOutsideTheMachConeAndHasTwoEmissionsInside() {
        // 1,4 М на высоте 100 блоков, над слушателем в момент 0: конус Маха (sin μ = 1/M) приходит к уху позже
        double v = 1.4 * Acoustics.SPEED, h = 100;
        Acoustics.Path p = straight(0, h, v);
        double[] roots = new double[4];
        // когда конус приходит: у земли под линией полёта он позади источника на h·√(M² − 1)
        double behind = h * Math.sqrt(1.4 * 1.4 - 1);
        double arrival = behind / v;
        assertEquals(0, Acoustics.emissions(p, arrival - 0.5, 0, 0, 0, roots), "до прихода конуса — тишина");
        assertEquals(0, Acoustics.emissions(p, -3, 0, 0, 0, roots), "сверхзвуковой источник на подходе не слышно");
        assertEquals(2, Acoustics.emissions(p, arrival + 2, 0, 0, 0, roots), "внутри конуса — два момента излучения");
        assertTrue(roots[0] > roots[1]);
        double[] at = new double[3];
        for (int i = 0; i < 2; i++) {
            p.at(roots[i], at);
            double d = Math.hypot(at[0], at[1]);
            assertEquals(d, Acoustics.SPEED * (arrival + 2 - roots[i]), 1e-2, "корень " + i);
        }
        // поздний — источник уходит (тон ниже), ранний — звук с подхода, частота отрицательна (задом наперёд)
        p.at(roots[0], at);
        double d0 = Math.hypot(at[0], at[1]);
        assertTrue(Acoustics.doppler(v, 0, 0, -at[0] / d0, -at[1] / d0, 0) > 0);
        p.at(roots[1], at);
        double d1 = Math.hypot(at[0], at[1]);
        assertTrue(Acoustics.doppler(v, 0, 0, -at[0] / d1, -at[1] / d1, 0) < 0);
    }

    @Test
    void supersonicLaterEmissionFollowsTheSourceForAnySpeed() {
        // любая сверхзвуковая скорость: внутри конуса поздний корень есть и Доплер у него конечный и меньше 1
        double[] roots = new double[2];
        for (double mach : new double[] {1.05, 1.4, 2, 5}) {
            double v = mach * Acoustics.SPEED;
            Acoustics.Path p = straight(0, 60, v);
            double now = 60 * Math.sqrt(mach * mach - 1) / v + 20;
            assertTrue(Acoustics.emissions(p, now, 0, 0, 0, roots) >= 1, "М " + mach);
            double[] at = new double[3];
            p.at(roots[0], at);
            double d = Math.hypot(at[0], at[1]);
            double f = Acoustics.doppler(v, 0, 0, -at[0] / d, -at[1] / d, 0);
            assertTrue(f > 0 && f < 1, "М " + mach + ": " + f);
        }
    }

    @Test
    void rootIsFoundEvenWhenTheSpeedBoundIsFarTooHigh() {
        // скачок пути между тиками завышает скорость сверху: шаги поиска мельчают, но корень в 19 км находится
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
        // приближается со скоростью c/2: частота ×2; удаляется с c/2: ×2/3
        assertEquals(2.0, Acoustics.doppler(c / 2, 0, 0, 1, 0, 0), 1e-9);
        assertEquals(2.0 / 3.0, Acoustics.doppler(-c / 2, 0, 0, 1, 0, 0), 1e-9);
        // поперёк луча — без сдвига
        assertEquals(1.0, Acoustics.doppler(0, 0, 5, 1, 0, 0), 1e-9);
        // уходит со скоростью 5c: ×1/6, без предела
        assertEquals(1.0 / 6, Acoustics.doppler(-5 * c, 0, 0, 1, 0, 0), 1e-9);
    }
}
