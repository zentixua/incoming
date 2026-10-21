package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;

import java.util.Locale;

class ScratchNoiseTest {
    @Test
    void print() {
        Object[][] rows = {
                {"drone", WeaponSpec.DRONE_NOISE, 51.4, FlightPhase.CRUISE, 300.0},
                {"loiter", WeaponSpec.LOITER_NOISE, 30.6, FlightPhase.CRUISE, 300.0},
                {"missile", WeaponSpec.MISSILE_NOISE, 250.0, FlightPhase.CRUISE, 40.0},
                {"bomber", WeaponSpec.BOMBER_NOISE, 250.0, FlightPhase.CRUISE, 12000.0},
        };
        for (double add : new double[]{10, 15, 20}) {
            Noise d = WeaponSpec.DRONE_NOISE;
            Noise.Propeller e = (Noise.Propeller) d.engine();
            Noise m = new Noise(new Noise.Propeller(e.power(), e.rpm(), e.blades(), e.speed(), e.idle(), e.limit(), e.refA() + add, e.refPower(), e.spectrum()), d.motor(), d.body(), d.drill(), d.catapult(), d.separation());
            double[] b = new double[Propagation.BANDS];
            for (double h : new double[]{100, 300, 1000, 2000}) {
                System.out.println(String.format(Locale.ROOT, "NOISE drone+%.0f h %.0f: L@slant100 %.1f range night %.0f day %.0f", add, h,
                        m.hear(FlightPhase.CRUISE, 100, 51.4 / 20, false, Propagation.Path.over(100, 100, Propagation.Weather.NIGHT), b),
                        Hearing.range(m, FlightPhase.CRUISE, 100, 51.4 / 20, false, h, Propagation.Weather.NIGHT),
                        Hearing.range(m, FlightPhase.CRUISE, 100, 51.4 / 20, false, h, Propagation.Weather.NOON)));
            }
        }
        for (Object[] r : rows) {
            Noise n = (Noise) r[1];
            double v = (Double) r[2] / 20;
            FlightPhase ph = (FlightPhase) r[3];
            double lw = n.power(Noise.Part.ENGINE, ph, 100, v, false);
            double[] b = new double[Propagation.BANDS];
            double l100 = n.hear(ph, 100, v, false, Propagation.Path.over(100, 100, Propagation.Weather.NIGHT), b);
            double l1k = n.hear(ph, 100, v, false, Propagation.Path.over(1000, 300, Propagation.Weather.NIGHT), b);
            double range = Hearing.range(n, ph, 100, v, false, (Double) r[4], Propagation.Weather.NIGHT);
            double rangeDay = Hearing.range(n, ph, 100, v, false, (Double) r[4], Propagation.Weather.NOON);
            System.out.println(String.format(Locale.ROOT, "NOISE %s LW %.1f dB (A %.1f) L@100m %.1f dBA L@1km %.1f dBA range night %.0f m day %.0f m (h %.0f)",
                    r[0], lw, lw + n.spectrum(Noise.Part.ENGINE).aWeighting(), l100, l1k, range, rangeDay, r[4]));
        }
    }
}
