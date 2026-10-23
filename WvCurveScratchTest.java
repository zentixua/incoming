package ua.zentix.incoming.client.fx;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.BodySway;
import ua.zentix.incoming.strike.WeaponType;

import java.io.PrintWriter;

class WvCurveScratchTest {
    static double wobble(double s, double hz, double phase) {
        return 0.7 * Math.sin(s * hz * 2 * Math.PI + phase) + 0.3 * Math.sin(s * hz * 1.73 * 2 * Math.PI + phase * 2.1);
    }

    @Test
    void curves() throws Exception {
        BlastSource src = BlastSource.of(WeaponType.DRONE, false);
        try (PrintWriter out = new PrintWriter("camera.csv")) {
            out.println("kg," + src.chargeKg());
            out.println("m,t,v341,main,branch");
            for (double m : new double[]{20, 50, 100}) {
                double pa = 1000 * BlastModel.overpressureKpa(m, src.chargeKg(), true), tp = BlastModel.chemicalPhaseSeconds(m, src.chargeKg(), true);
                double q = 1000 * BlastModel.dynamicPressureKpa(pa / 1000);
                CameraShake.reset();
                double[] body = BodySway.tilt(pa, q, 1, tp, BodySway.BACK, BodySway.FORWARD);
                CameraShake.sway(pa, q, 1, tp, tp, 0, new Vec3(0, 0, -1), 0, true);
                CameraShake.tremor(pa, BlastModel.chemicalImpulse(m, src.chargeKg(), true), 0, 7);
                int band = (int) (m / 17.15) + 1, shake0 = band == 1 ? 26 : band == 2 ? 22 : band <= 4 ? 16 : band <= 8 ? 10 : 0;
                for (int tick = 0; tick < 100; tick++) {
                    for (int k = 0; k < 4; k++) {
                        double t = (tick + k / 4.0) / 20;
                        int shake = shake0 - tick;
                        double v341 = 0;
                        if (shake > 0) {
                            double a = shake >= 26 ? 3.5 : shake >= 18 ? 2 : shake >= 8 ? 1 : 0.4;
                            v341 = a * Math.min(1, shake / 4.0) * wobble(t, 12.3, 1.7);
                        }
                        int i = (int) (t * BodySway.RATE);
                        double main = i + 1 < body.length ? -Math.toDegrees(body[i]) : 0;
                        double branch = CameraShake.offsets(k / 4.0, 0, 0, 0, 0, 1)[1];
                        out.printf(java.util.Locale.ROOT, "%.0f,%.4f,%.4f,%.4f,%.4f%n", m, t, v341, main, branch);
                    }
                    CameraShake.tick(true);
                }
            }
        }
    }
}
