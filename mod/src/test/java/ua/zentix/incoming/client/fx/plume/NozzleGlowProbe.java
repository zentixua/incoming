package ua.zentix.incoming.client.fx.plume;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponType;

class NozzleGlowProbe {
    @Test
    void print() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            long t0 = System.nanoTime();
            NozzleGlow g = ClientWeaponSpec.nozzleGlow(w);
            System.out.printf("%s: %.1f ms%n", w, (System.nanoTime() - t0) / 1e6);
            Motor m = g.motor();
            System.out.printf("  pc %.2f MPa c* %.0f rt %.4f re %.4f depth %.3f mu %.2e Pr %.3f h_t %.0f h_c %.0f Taw_t %.0f%n", m.chamberPressure() / 1e6, NozzleGlow.cStar(m),
                    NozzleGlow.throatRadius(m), m.exitRadius(), g.at(0).depth(), NozzleGlow.viscosity(m), NozzleGlow.prandtl(m), NozzleGlow.bartz(m, 1),
                    NozzleGlow.bartz(m, (1 + Math.pow(m.exitRadius() / NozzleGlow.throatRadius(m), 2)) / 2), NozzleGlow.recovery(m, 1));
            for (double s : new double[]{0.5, 1, 2, g.burn() - 0.5, g.burn() - 0.1, g.burn()}) {
                var sh = g.at(s);
                System.out.printf("  burn t=%.2f cone %.0f throat %.0f%n", s, sh.coneKelvin(), sh.throatKelvin());
            }
            for (double s : new double[]{0.1, 0.5, 1, 2, 5, 10, 20, 30, 45, 60}) {
                var sh = g.spent(s * 20);
                System.out.printf("  after %4.1f s: cone %4.0f K L %.2e | throat %4.0f K L %.2e%n", s, sh.coneKelvin(), sh.coneLuminance(), sh.throatKelvin(), sh.throatLuminance());
            }
        }
    }
}
