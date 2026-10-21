package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;

import java.util.Locale;

/** Проба гибрида (handoff P): числа облака обычного оружия по времени — радиус, σ, толща поперёк середины, свет шара. */
class ProbeCloudNumbersTest {
    @Test
    void print() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET}) {
            CloudModel m = new CloudModel(BlastSource.of(w, false), CloudModel.Air.STANDARD);
            System.out.printf(Locale.ROOT, "PROBE %s soot=%.2f kg dust=%.1f kg fuelBall D=%.1f m %.2f s heBall r=%.1f m %.2f s glowEnd=%.2f s pool=%.0f s life=%.0f s top=%.0f m @ %.0f s%n",
                    w, m.soot, m.dust, m.fuelBall, m.fuelBallSeconds, m.heBall, m.heBallSeconds, m.fireSeconds, m.poolFireSeconds(), m.life, m.topZ, m.topSeconds);
            double[] bb = new double[4];
            for (double t : new double[]{0.1, 0.5, 1, 2, 3, 5, 10, 20, 30, 60}) {
                m.ballLight(t, bb);
                System.out.printf(Locale.ROOT, "PROBE %s t=%4.1f h=%5.1f R=%5.1f sh=%5.1f sv=%5.1f tau=%.3f ball r=%.1f K=%.0f Y=%.3g%n", w, t, m.height(t), m.radius(t),
                        m.sigmaH(t), m.sigmaV(t), m.tau(t), m.ballRadius(t), m.ballKelvin(t), bb[3]);
            }
        }
    }
}
