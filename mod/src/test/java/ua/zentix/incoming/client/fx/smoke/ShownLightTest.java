package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Свет огненного шара наружу ({@link CloudModel#shownLight}) — один выход источника для тела на экране, дыма, мира и
 * облаков: потемневший шар не светит на землю и небо ярче своего пламени (CONSIST C08 — раньше мир и облака брали
 * голое чёрное тело шара, а тело на экране уже таяло в дым).
 */
class ShownLightTest {
    private static final double DAY = 1, NIGHT = 8e-4;

    @Test
    void lightOutIsTheFireShareOfTheBall() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET, WeaponType.LOITER}) {
            CloudModel m = new CloudModel(BlastSource.of(w, false), CloudModel.Air.STANDARD);
            double[] bare = new double[4], shown = new double[4];
            boolean dark = false;
            for (double t = 0; t < m.fireSeconds; t += 0.05) {
                m.ballLight(t, bare);
                for (double sky : new double[]{DAY, NIGHT}) {
                    m.shownLight(t, sky, shown);
                    double g = CloudModel.glowShare(m.ballKelvin(t), sky);
                    for (int i = 0; i < 4; i++) assertEquals(bare[i] * g, shown[i], 1e-12 * Math.max(1, bare[i]), w + " " + t + " с");
                    assertTrue(shown[3] <= bare[3] + 1e-12, w + " " + t + " с: наружу не ярче чёрного тела");
                    if (sky == DAY && bare[3] > 0 && shown[3] == 0) dark = true;
                }
            }
            assertTrue(dark, w + ": днём остывающий шар — уже дым, наружу не светит, хотя чёрное тело ещё светится");
        }
    }
}
