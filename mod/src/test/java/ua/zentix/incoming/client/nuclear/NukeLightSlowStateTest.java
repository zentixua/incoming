package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slow adaptation ({@code NukeLight.slow}) is rebuilt with the exposure after {@link NukeLight#reset} (replay seek, world
 * entry, sync, respawn) and kept per view ({@link NukeLight.ViewState}); a moment's shade keeps it (review probes).
 * Same light curve as {@code FireballLightTest.aLastingLightIsFollowedFurtherThanAFlash}: 1 Mt at 4.5 km by day, noon whites
 * at the eye. Shown = whites the ball's light puts on the world on screen (f·r·ballGain), the quantity that test bounds.
 */
class NukeLightSlowStateTest {
    private static final double[][] MEGATON = {{0, 0}, {0.5, 3000}, {2, 5660}, {4, 1410}, {6, 462}, {8, 188}, {10, 79},
            {13, 26.5}, {16, 9.2}, {18, 5.8}, {21, 2.05}, {24, 0.38}};
    private static final double DT = 1 / 60.0, T0 = 1000;

    @AfterEach void clean() { NukeLight.reset(); }

    /** On-screen whites of the ball's light at the eye's light f, from the current static state. */
    private static double shown(double f) {
        double r = NukeLight.ratio(NukeLight.exposure(), NukeLight.bodyExposure());
        return f * r * NukeLight.frameExposure() / NukeLight.exposure();
    }

    private static double light(double t) {
        if (t < 0) return 0;
        for (int i = 1; i < MEGATON.length; i++) if (t <= MEGATON[i][0]) {
            double u = (t - MEGATON[i - 1][0]) / (MEGATON[i][0] - MEGATON[i - 1][0]);
            return MEGATON[i - 1][1] + u * (MEGATON[i][1] - MEGATON[i - 1][1]);
        }
        return MEGATON[MEGATON.length - 1][1];
    }

    /** A replay seek (or a late join) into the pulse rebuilds the slow share with the exposure, as a live viewer has it. */
    @Test void aSeekIntoTheLastingPulseShowsTheSquareRootLook() {
        NukeLight.reset();
        // watched live from 2 s before the burst to 7 s after it
        for (double t = -2; t <= 7 + 1e-9; t += DT) NukeLight.update(T0 + t, 1, light(t), Math.max(t, 0));
        double live = shown(light(7));
        // the same replay, sought from before the burst (eye unflashed) to 6 s after it, then played 1 s
        for (double t = -2; t <= 0; t += DT) NukeLight.update(T0 + 100 + t, 1, 0);
        NukeLight.reset();
        for (double t = 6; t <= 7 + 1e-9; t += DT) NukeLight.update(T0 + t, 1, light(t), Math.max(t, 0));
        double sought = shown(light(7));
        System.out.printf(java.util.Locale.ROOT, "7 s after 1 Mt at 4.5 km: live %.2f whites, after a seek to 6 s %.2f whites%n", live, sought);
        assertTrue(sought < 1.5 * live, "a seek shows the ball's light on the world at " + sought + " whites against " + live + " live");
    }

    /** A secondary view (a camera feed: FxViews, client-fx.md) that does not see the ball keeps its own slow share. */
    @Test void aSecondaryViewDoesNotUndoThePrimaryAdaptation() {
        NukeLight.reset();
        for (double t = -2; t <= 10 + 1e-9; t += DT) NukeLight.update(T0 + t, 1, light(t), Math.max(t, 0));
        double alone = shown(light(10));
        NukeLight.reset();
        var feed = new NukeLight.ViewState();
        for (double t = -2; t <= 10 + 1e-9; t += DT) {
            NukeLight.update(T0 + t, 1, light(t), Math.max(t, 0));
            feed.swap();
            NukeLight.update(T0 + t, 1, 0, 0); // the feed's camera is indoors / faces away: no ball light at it
            feed.swap();
        }
        double withFeed = shown(light(10));
        System.out.printf(java.util.Locale.ROOT, "10 s after 1 Mt: primary alone %.2f whites, with one unflashed feed %.2f whites%n", alone, withFeed);
        assertTrue(withFeed < 1.5 * alone, "one feed raises the primary's ball light from " + alone + " to " + withFeed + " whites");
    }

    /** The ball hidden for half a second (a tree, a roof edge) keeps most of the adaptation: no jump back to the square root. */
    @Test void aMomentsShadeKeepsTheAdaptation() {
        NukeLight.reset();
        // shade 9–9.5 s, measured 0.5 s after the ball shows again (the exposure's own 0.2 s follow has caught up)
        for (double t = -2; t <= 10 + 1e-9; t += DT) NukeLight.update(T0 + t, 1, t >= 9 && t < 9.5 ? 0 : light(t), Math.max(t, 0));
        double after = shown(light(10));
        NukeLight.reset();
        for (double t = -2; t <= 10 + 1e-9; t += DT) NukeLight.update(T0 + t, 1, light(t), Math.max(t, 0));
        double steady = shown(light(10));
        assertTrue(after < 1.5 * steady, "after half a second of shade " + after + " whites against " + steady);
    }
}
