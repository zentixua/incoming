package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;

/** Review R483 regression: returning street dust must not move onto roofs. */
class R483RoofTest {
    static CloudModel model(WeaponType w) {
        var ground = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0),
                GroundDust.Ground.filled(0), GroundDust.Ground.filled(0),
                (gx, gz) -> Math.abs(gx) % 30 < 8 ? 0 : 20);
        var site = new ua.zentix.incoming.util.LocalWind(0, 15, 20, 10, 1, 2, .6, 30);
        return new CloudModel(BlastSource.of(w, false), new CloudModel.Air(0.01, 5, -2, 0.2, 3, 0.6, site), false, 0, ground);
    }

    static double[] roof(CloudModel m, double t) {
        double[] s = new double[2];
        m.ground.blobs(t, 1, (x, y, z, sh, sv, d) -> { s[1] += d; if (y > 10) s[0] += d; });
        return s;
    }

    @Test void streetDustOnRoofs() {
        for (WeaponType w : new WeaponType[]{WeaponType.MISSILE, WeaponType.DRONE, WeaponType.ROCKET, WeaponType.LOITER})
            for (double[] gap : new double[][]{{2, 6}, {5, 30}, {30, 120}, {60, 300}}) {
                var cont = model(w); var ret = model(w);
                for (double t = 1.0 / 30; t < gap[1]; t += 1.0 / 30) {
                    cont.blobs(t, (x, y, z, sh, sv, s, d, wa, k, r) -> {});
                    if (t < gap[0]) ret.blobs(t, (x, y, z, sh, sv, s, d, wa, k, r) -> {});
                }
                ret.blobs(gap[1], (x, y, z, sh, sv, s, d, wa, k, r) -> {});
                double[] a = roof(cont, gap[1]), b = roof(ret, gap[1]);
                assertEquals(a[0] / Math.max(1e-12, a[1]), b[0] / Math.max(1e-12, b[1]), 1e-8,
                        w + " hidden=" + gap[0] + ".." + gap[1] + ": return moved street dust onto roofs");
                System.out.printf(java.util.Locale.ROOT, "R483ROOF %s hidden %.0f-%.0f s: ground dust above 10 m (roofs) %.1f%% watched vs %.1f%% returned%n",
                        w, gap[0], gap[1], 100 * a[0] / Math.max(1e-12, a[1]), 100 * b[0] / Math.max(1e-12, b[1]));
            }
    }
}
