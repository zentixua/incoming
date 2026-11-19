package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.HobBlast;

/** Measurement only (wave-proposal): the head's numbers at the camera distances of the run. */
class WaveRollProbeTest {
    @Test
    void print() {
        double[][] cases = {{1000, 3000, 0.35}, {1000, 6000, 0.35}, {15, 1500, 0.35}, {1000, 3000, 1}, {1000, 6000, 1}, {15, 1500, 1}};
        StringBuilder b = new StringBuilder();
        for (double[] c : cases) {
            double y = c[0], R = c[1];
            Detonation d = new Detonation(1, new Vec3(0, 64, 0), 64, y, true, 0, 0, 0, 20_000, 7, 1f, false, (float) c[2]);
            BlastWall w = new BlastWall(d, false);
            // time the front reaches R
            double lo = 0, hi = 2000;
            for (int i = 0; i < 80; i++) {
                double m = 0.5 * (lo + hi);
                if (w.shock(m) < R) lo = m; else hi = m;
            }
            double t = hi, vf = (w.shock(t + 0.05) - w.shock(t - 0.05)) / 0.1;
            double U = w.wind(R), tp = HobBlast.positivePhaseSeconds(R, 0, y), kpa = d.groundOverpressureKpa(d.blocks(R));
            DustModel.Column col = DustModel.column(R, 0, y, true, 20_000, DustModel.Surface.REFERENCE);
            b.append(String.format(java.util.Locale.ROOT, "Y=%.0f kt R=%.0f wave=%.2f: t_arr %.2f s, front %.0f m/s, p %.1f kPa (%.1f psi), U %.0f m/s, t+ %.2f s, carried %.1f m%n",
                    y, R, c[2], t, vf, kpa, kpa / 6.895, U, tp, w.carried(t)));
            b.append(String.format(java.util.Locale.ROOT, "   column: mass %.2f kg/m2, first top %.1f m, buoyant %.1f m, positive %.2f s, load %.4f kg/m3 (drho/rho %.4f)%n",
                    col.mass(), col.first(), col.buoyant(), col.positive(), col.mass() / Math.max(col.first(), 1e-3), col.mass() / Math.max(col.first(), 1e-3) / 1.2));
            for (double dt : new double[]{0, 0.5 * tp, tp, 2 * tp, 4 * tp, 10, 30}) {
                double tt = t + dt;
                double len = w.length(tt), top = w.height(tt), head = w.head(tt);
                b.append(String.format(java.util.Locale.ROOT, "   t=t_arr+%.2f: head %.0f m (front at R + %.0f), wedge %.0f m, top %.1f m, top(u=.1/.3/.6) %.1f/%.1f/%.1f m, carried %.1f, share at R %.2f, colTop@R %.1f m%n",
                        dt, head, head - R, len, top, top * BlastWall.top(0.1), top * BlastWall.top(0.3), top * BlastWall.top(0.6), w.carried(tt),
                        BlastWall.carriedShare(dt / tp), col.height(dt)));
            }
        }
        System.out.println("WAVEROLL\n" + b);
    }
}
