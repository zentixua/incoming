package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.FlashLight;
import ua.zentix.incoming.nuclear.model.MushroomModel;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rebuilt state (seek, late join) takes its slow adaptation from the age of the light that lasts, not from an earlier
 * burst's cooled cloud: that cloud still emits a little (its temperature clamps at the ambient one) and stays in the
 * detonation list for minutes (review 2 probe). Two 1 Mt bursts 120 s apart, 4.5 km away by day; a seek to 1 s after the
 * second.
 */
class NukeLightRebuildAgeTest {
    private static final double[][] MEGATON = {{0, 0}, {0.5, 3000}, {2, 5660}, {4, 1410}, {6, 462}, {8, 188}, {10, 79},
            {13, 26.5}, {16, 9.2}, {18, 5.8}, {21, 2.05}, {24, 0.38}};
    private static final double DT = 1 / 60.0, T0 = 1000, SECOND = 120;

    @AfterEach void clean() { NukeLight.reset(); }

    private static double light(double t) {
        if (t < 0) return 0;
        for (int i = 1; i < MEGATON.length; i++) if (t <= MEGATON[i][0]) {
            double u = (t - MEGATON[i - 1][0]) / (MEGATON[i][0] - MEGATON[i - 1][0]);
            return MEGATON[i - 1][1] + u * (MEGATON[i][1] - MEGATON[i - 1][1]);
        }
        // the cooled cloud: still above zero (as FlashLight's clamped temperature gives), far below the daylight
        return 0.38 * Math.exp(-(t - 24) / 3) + 1e-9;
    }

    private static double shown(double f) {
        double r = NukeLight.ratio(NukeLight.exposure(), NukeLight.bodyExposure());
        return f * r * NukeLight.frameExposure() / NukeLight.exposure();
    }

    /** The age frame() passes ({@link NukeLight#lasting}) for the two sources. */
    private static double lasting(double t) {
        return NukeLight.lasting(1, new double[]{light(t), light(t - SECOND)}, new double[]{Math.max(t, 0), Math.max(t - SECOND, 0)});
    }

    @Test void theOldCloudStillCountsAsLit() {
        var s = MushroomModel.shape(SECOND, 1000, 2000, false, 20000);
        double[] rgb = new double[4];
        FlashLight.source(s, 1000, false, rgb);
        System.out.printf(java.util.Locale.ROOT, "1 Mt cloud at %.0f s: shape %s, source luminance %.3g%n", SECOND, s != null, rgb[3]);
        assertTrue(s != null && rgb[3] > 0, "the first burst's cloud is no longer a lit source at " + SECOND + " s");
    }

    @Test void aSeekIntoTheSecondBurstTakesTheFirstBurstsAge() {
        NukeLight.reset();
        double at = SECOND + 1;
        for (double t = -2; t <= at + 1e-9; t += DT) NukeLight.update(T0 + t, 1, light(t) + light(t - SECOND), lasting(t));
        double live = shown(light(at) + light(at - SECOND));
        NukeLight.reset();
        // sought to 0.5 s after the second burst, played 0.5 s
        for (double t = SECOND + 0.5; t <= at + 1e-9; t += DT) NukeLight.update(T0 + t, 1, light(t) + light(t - SECOND), lasting(t));
        double sought = shown(light(at) + light(at - SECOND));
        System.out.printf(java.util.Locale.ROOT, "1 s after the second 1 Mt: live %.2f whites, after a seek %.2f whites%n", live, sought);
        assertTrue(sought > live / 1.5, "a seek shows the second ball's light on the world at " + sought + " whites against " + live + " live");
    }
}
