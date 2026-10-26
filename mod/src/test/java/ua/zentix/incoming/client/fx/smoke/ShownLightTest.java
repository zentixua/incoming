package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Outward fireball light ({@link CloudModel#emittedLight}): the budget does not depend on the day/night contrast of the body
 * against smoke. Temperature fade near the Draper point only reduces the flux; the bare black body does not radiate outward.
 */
class ShownLightTest {
    private static final double DAY = 1, NIGHT = 8e-4;

    @Test
    void sourceDoesNotFollowTheDayNightBodyBlend() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET, WeaponType.LOITER}) {
            CloudModel m = new CloudModel(BlastSource.of(w, false), CloudModel.Air.STANDARD);
            double[] bare = new double[4], shown = new double[4];
            boolean differentBody = false;
            for (double t = 0; t < m.fireSeconds; t += 0.05) {
                m.ballLight(t, bare);
                for (double sky : new double[]{DAY, NIGHT}) {
                    // Sky light changes the body visibility in smoke but is not part of the source flux API.
                    double body = CloudModel.glowShare(m.ballKelvin(t), sky);
                    m.emittedLight(t, shown);
                    double g = CloudModel.glowShare(m.ballKelvin(t));
                    for (int i = 0; i < 4; i++) assertEquals(bare[i] * g, shown[i], 1e-12 * Math.max(1, bare[i]), w + " " + t + " s");
                    assertTrue(shown[3] <= bare[3] + 1e-12, w + " " + t + " s: no brighter outward than the black body");
                    if (sky == DAY && bare[3] > 0 && body == 0 && shown[3] > 0) differentBody = true;
                }
            }
            if (m.source.fuelKg() > 0)
                assertTrue(differentBody, w + ": smoke hid the faint glow against the daytime background, but the energy did not disappear");
        }
    }
}
