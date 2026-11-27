package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

class ScratchWaterProbe {
    @Test
    void print() {
        for (WeaponType w : new WeaponType[] {WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET}) {
            WaterBlast b = new WaterBlast(w.spec().charge(), 0, 4, CloudModel.Air.STANDARD);
            System.out.printf(java.util.Locale.ROOT, "PROBE %s kg %.1f H %.1f R %.1f jet %.2f halo %.2f v %.1f rise %.2f landing %.2f cone %.3f water %.0f surgeReach %.1f surgeTop %.2f mistLife %.1f suspended %.2f sv0 %.2f tau0 %.3f edge0 %.2f%n",
                    w, w.spec().charge(), b.height, b.radius, b.jet, b.halo, b.speed, b.rise, b.landing(), b.cone, b.water, b.surgeReach(), b.surgeTop(), b.mistLife(),
                    b.suspended(), b.mistSigmaV(0), b.mistTau(0), b.mistSigmaV(0));
            for (double t : new double[] {1, 2, 3, 4, 5, 6, 7, 8, 10, 15, 20})
                System.out.printf(java.util.Locale.ROOT, "PROBE   t %.0f top %.1f aloft %.2f surgeR %.1f sv %.2f tau %.3f%n", t, b.top(t), b.aloft(t),
                        b.surgeRadius(t - b.landing()), b.mistSigmaV(Math.max(0, t - b.landing())), b.mistTau(Math.max(0, t - b.landing())));
        }
    }
}
