package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first seconds of a nuclear burst seen through one eye (DESIGN fireball-light): the frame's one exposure by day, the
 * eye's flash blindness as a bounded overlay, the afterimage's colours. Artem, 27.11.2026: the fireball read as a
 * high-contrast texture pasted on an ordinary day, and the blindness ended abruptly.
 */
class FireballLightTest {
    private static final Path SHADERS = Path.of("src/main/resources/assets/incoming/shaders");

    @Test
    void theDayFollowsTheFlashPartlyAndTheNightAsBefore() {
        assertEquals(1, NukeLight.dayShare(1), 1e-12);
        assertEquals(0, NukeLight.dayShare(NukeLight.NIGHT_AMBIENT), 1e-12);
        assertEquals(0, NukeLight.dayShare(0.12), 1e-12);
        // 15 kt at 2 km, 0.5 s: the ball's light ~158 noons, eye exposure 1/159 of the unflashed one
        double r = 1 / 159.0;
        double daylight = NukeLight.daylightGain(r, 1), ball = NukeLight.ballGain(r, 1);
        assertEquals(Math.sqrt(r), daylight, 1e-12, "by day the ordinary light steps back by the square root");
        // one exposure (review 3, M1): the ball's light in eye units (× E = E0·r) and the daylight (× E0) reach the same frame
        // exposure, for any follow share, any day share
        double e0 = 1, e = e0 * r;
        assertEquals(e0 * NukeLight.daylightGain(r, 1), e * NukeLight.ballGain(r, 1), 1e-12, "by day: one frame exposure");
        // at night both keep the earlier exposures (daylight unflashed, the ball's light at the eye's)
        assertEquals(1, NukeLight.daylightGain(r, 0) * NukeLight.ballGain(r, 0), 1e-12);
        assertTrue(ball * 158 * r > 10, "faces toward the ball blow out white (" + ball * 158 * r + " whites)");
        // at night nothing changes: the dark-adapted view already takes the ball's light in full
        assertEquals(1, NukeLight.daylightGain(r, 0), 1e-12);
        assertEquals(1, NukeLight.ballGain(r, 0), 1e-12);
        // without a flash, or once the eye has caught up, the frame is the unflashed one
        assertEquals(1, NukeLight.daylightGain(NukeLight.ratio(1, 1), 1), 1e-12);
        assertEquals(1, NukeLight.ratio(2, 1), 1e-12, "a ratio never brightens the day");
    }

    @Test
    void flashBlindnessIsBoundedSoTheWorldStaysVisible() {
        for (float dark : new float[]{0, 0.5f, 1}) {
            float[] start = NukeFlash.look(0, 30, 1, dark, 1);
            assertTrue(start[0] <= NukeFlash.GREY_MAX && start[1] <= NukeFlash.LIFT_MAX);
            assertTrue(start[0] < 1 && start[1] < 1, "never a solid card");
            double before = Double.MAX_VALUE;
            for (double t = 0; t < 30; t += 0.5) {
                float[] l = NukeFlash.look(t, 30, 1, dark, 1);
                double sum = l[0] + l[1];
                assertTrue(sum <= before + 1e-6, "fades, never comes back: t " + t);
                before = sum;
            }
            float[] end = NukeFlash.look(30, 30, 1, dark, 1);
            assertEquals(0, end[0] + end[1], 1e-9, "gone by its deadline");
        }
        // by day the bleached eye's glow and colour loss pale the picture; at night the orange-lit night keeps its colour
        assertTrue(NukeFlash.look(10, 60, 1, 0, 1)[1] > NukeFlash.look(10, 60, 1, 1, 1)[1]);
        assertEquals(0, NukeFlash.look(10, 60, 1, 1, 1)[0], 1e-9);
        // a weak dose: a weak veil
        assertTrue(NukeFlash.look(0, 30, 0.2f, 0, 1)[0] < NukeFlash.look(0, 30, 1, 0, 1)[0]);
    }

    @Test
    void aNearFacingLookClearsWithinASecondAFarOrTurnedOneSooner() {
        // Artem 27.11: the films stay white for seconds; ours follows the dose at the eye, the same in creative and survival
        double near = NukeFlash.whiteSeconds(58, 1, 0, NukeFlash.gaze(10, true), 0.4f);
        double turned = NukeFlash.whiteSeconds(58, 1, 0, NukeFlash.gaze(120, true), 0.4f);
        double far = NukeFlash.whiteSeconds(19, 1, 0, NukeFlash.gaze(10, true), 0.4f);
        assertTrue(near >= 0.4 && near <= 1.2, "near and facing: under a second of near white as it clears, " + near);
        assertTrue(turned < 0.5, "turned away: brief, " + turned);
        assertTrue(far < near * 0.75, "far: shorter, " + far);
        assertEquals(NukeFlash.TURNED_AWAY, NukeFlash.gaze(10, false), 1e-6, "an unseen ball bleaches by scattered light only");
    }

    @Test
    void theAfterimageTurnsFromWhiteToVioletAsItFades() {
        float[] first = NukeFlash.afterColour(0), mid = NukeFlash.afterColour(1.5f), late = NukeFlash.afterColour(8);
        assertTrue(first[0] > 0.9f && first[1] > 0.85f, "a positive white-yellow image first");
        assertTrue(mid[0] > mid[1] && mid[1] > mid[2], "then orange");
        assertTrue(late[2] > late[1] && late[0] > late[1], "then violet (Feynman's purple splotch)");
    }

    @Test
    void creativePlayersHaveEyesSpectatorsAreCameras() throws IOException {
        String flash = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/nuclear/NukeFlash.java"));
        String eyes = flash.substring(flash.indexOf("static boolean eyes("), flash.indexOf("static boolean eyes(") + 200);
        assertTrue(eyes.contains("!p.isSpectator()"));
        assertFalse(eyes.contains("isCreative"), "flash blindness is perception, not injury: creative gets it too");
        assertFalse(flash.contains("g.fill(0, 0, w, h, (Math.min(255, (int) (a * 255)) << 24)"), "no uniform card over the picture");
    }

    @Test
    void byDayTheEyeQuadLeavesItsVeilToTheCompositeAtAnySetting() throws IOException {
        // one model of one phenomenon (review 3 S2, review 4 N1): the quad's veil share follows the sky alone, while the
        // composite's flashVeil takes day × Flash setting; a calm or lower setting must not draw both
        String renderer = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/nuclear/NukeRenderer.java"));
        assertTrue(renderer.contains("shown, 1 - NukeLight.daySky(), GLARE)"));
        assertFalse(renderer.contains("1 - NukeLight.day()"));
        String pall = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/nuclear/NukePall.java"));
        assertTrue(pall.contains("veil ? (float) NukeLight.day() : 0"), "the composite's veil carries the setting");
    }

    @Test
    void theVeilCopyMatchesTheShader() throws IOException {
        String composite = Files.readString(SHADERS.resolve("core/fx_pall_composite.fsh"));
        assertTrue(composite.contains("const float FLASH_VEIL = " + (float) NukePall.FLASH_VEIL + ";"), "NukePall.veilPeak copies flashVeil");
        assertTrue(composite.contains("float k = FLASH_VEIL * 31.4 / (th * th)"));
    }

    @Test
    void theEyesVeilStaysOffTheBallAndOutOfTheNight() throws IOException {
        String composite = Files.readString(SHADERS.resolve("core/fx_pall_composite.fsh"));
        assertTrue(composite.contains("vec3 veil = flashVeil(dir0, behind) * PallFlash.y;"), "the veil by day only");
        assertTrue(composite.contains("max(behind, smoothstep(disc * 1.02, disc * 1.25, theta))"),
                "in the eye: over the shell and smoke, never over the ball's own disc");
        assertTrue(composite.contains("return min(veil, vec3(FLASH_VEIL_PEAK));") && composite.contains("const float FLASH_VEIL_PEAK = 0.3;"),
                "under the ball's on-screen brightness: its pattern stays readable");
        assertTrue(composite.contains("laid.rgb += held * (1.0 - shown) * flashAmber(dir0);"),
                "the amber dome is ball light: only the sky under it dims");
    }
}
