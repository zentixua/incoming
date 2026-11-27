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

    /**
     * A world lit far above white washes toward white, never to neon: at 4.5 km from 1 Mt by day the ball's light holds the
     * world at tens of whites for seconds (the frame's exposure follows the flash by the square root), and the hue-kept
     * ceiling with the added {@code CROSS} share alone showed dark green leaves as neon green and shaded red earth as pink
     * stripes, the same colour at every brightness. Copy of {@code ballLitWorld}'s steps after the relight (leaves, earth and
     * hazy distance as a shader pack draws them, the warm ball light).
     */
    @Test
    void aWorldFarAboveWhiteWashesTowardWhiteNotNeon() throws IOException {
        String pall = Files.readString(SHADERS.resolve("include/pall.glsl"));
        GlslFunction over = GlslFunction.parse(pall, "pallOverWhite");
        String composite = Files.readString(SHADERS.resolve("core/fx_pall_composite.fsh"));
        String heat = Files.readString(SHADERS.resolve("core/fx_heat.fsh"));
        String lighting = body(composite, "vec3 ballLitWorld(");
        int cross = lighting.indexOf("lit += vec3(CROSS"), wash = lighting.indexOf("lit = pallWashWhite(lit);"), display = lighting.indexOf("vec3 shown = ");
        assertTrue(cross >= 0 && cross < wash && wash < display, "the nuclear world: CROSS, then the wash, then the display");
        assertTrue(heat.contains("lit = pallWashWhite(lit + vec3(CROSS * max(dot(lit, LUMA) - 1.0, 0.0)));"),
                "the conventional world light takes the same wash (one law for the world above white)");
        double[][] worlds = {{0.25, 0.45, 0.15}, {0.45, 0.25, 0.2}, {0.6, 0.62, 0.55}};
        double[] ball = {1, 0.78, 0.55};
        for (double[] w : worlds) {
            double before = 1;
            for (double gain : new double[]{2, 5, 10, 20, 40, 75}) {
                double[] lit = new double[3];
                for (int c = 0; c < 3; c++) lit[c] = Math.min(PallLight.linear(w[c]), 1) * ball[c] * gain;
                double lum = 0.2126 * lit[0] + 0.7152 * lit[1] + 0.0722 * lit[2];
                for (int c = 0; c < 3; c++) lit[c] += PallLight.CROSS * Math.max(lum - 1, 0);
                double l2 = 0.2126 * lit[0] + 0.7152 * lit[1] + 0.0722 * lit[2];
                double[] shown = new double[3];
                double max = 0, min = Double.MAX_VALUE;
                for (int c = 0; c < 3; c++) {
                    shown[c] = PallLight.display(over.call(java.util.Map.of("c", lit[c], "lum", l2)));
                    max = Math.max(max, shown[c]);
                }
                for (int c = 0; c < 3; c++) min = Math.min(min, shown[c]);
                double saturation = (max - min) / Math.max(max, 1e-9);
                if (l2 > 1) assertTrue(saturation <= before + 1e-9, "brighter is never more coloured: " + gain + " × " + java.util.Arrays.toString(w));
                if (l2 >= 10) assertTrue(saturation < 0.12, "tens of whites read white, not neon: saturation " + saturation + " at " + gain + " × "
                        + java.util.Arrays.toString(w));
                before = l2 > 1 ? saturation : before;
            }
        }
        // under white nothing changes
        assertEquals(0.3, over.call(java.util.Map.of("c", 0.3, "lum", 0.8)), 1e-12);
        assertEquals(0.3, over.call(java.util.Map.of("c", 0.3, "lum", 1.0)), 1e-12);
    }

    /**
     * A lasting light is followed further than a flash: the eye at 4.5 km from 1 Mt by day takes 5660 noon whites of ball light
     * at 2 s, 79 at 10 s, 9 at 16 s and 2 at 21 s (its pulse, Silo-angle geometry); held at the flash's square root the world
     * and dust the ball lights stood at 3–9 whites for 15 s, one flat white and orange fill. 15 kt at 2 km (a flash of about a
     * second: 137 whites falling to 3 within one second) keeps nearly the square root its day look was judged by.
     */
    @Test
    void aLastingLightIsFollowedFurtherThanAFlash() {
        double[][] megaton = {{0, 0}, {0.5, 3000}, {2, 5660}, {4, 1410}, {6, 462}, {8, 188}, {10, 79}, {13, 26.5}, {16, 9.2}, {18, 5.8},
                {21, 2.05}, {24, 0.38}};
        double[][] flash = {{0, 0}, {0.1, 137}, {0.5, 80}, {0.9, 17.9}, {1.5, 3.0}, {2.1, 0.7}, {2.9, 0.14}};
        double[] mt = run(megaton, new double[]{10, 16});
        // the ball's light on screen (whites) where it lights the world: under 2.5 at 10 s and 1.6 at 16 s (the square root: 8.8, 2.9)
        assertTrue(mt[0] < 2.5 && mt[1] < 1.6, "a megaton's lasting light is followed: " + mt[0] + ", " + mt[1] + " whites");
        double[] kt = run(flash, new double[]{0.5, 0.9});
        for (int i = 0; i < kt.length; i++) {
            double f = new double[]{80, 17.9}[i], r = 1 / (1 + f), sqrt = f * r * NukeLight.ballGain(r, 1, 0);
            assertTrue(kt[i] > 0.8 * sqrt, "a 15 kt flash keeps nearly its square-root look: " + kt[i] + " against " + sqrt);
        }
        assertEquals(1, NukeLight.daylightGain(0.01, 1, 0) / Math.pow(0.01, NukeLight.FOLLOW_DAY), 1e-12, "no slow share: the square root");
        assertEquals(Math.pow(0.01, NukeLight.FOLLOW_LASTING), NukeLight.daylightGain(0.01, 1, 1), 1e-12, "fully adapted: the lasting share");
        assertEquals(1, NukeLight.daylightGain(0.01, 0, 1), 1e-12, "the night as before");
        double a = 1;
        for (int i = 0; i < 60 * 20; i++) a = NukeLight.adapt(a, 0, 1 / 60.0);
        assertTrue(a < 0.01, "back in daylight the adaptation fades within 20 s: " + a);
        assertEquals(1, NukeLight.daylightGain(1, 1, 1), 1e-12, "and an adapted eye without a flash sees the plain day");
    }

    /** The ball's light on screen in whites (noon whites at the eye × ball gain × r) at the given seconds, the slow state stepped at 60 fps. */
    private static double[] run(double[][] curve, double[] at) {
        double slow = 0, dt = 1 / 60.0;
        double[] out = new double[at.length];
        for (double t = 0; t <= at[at.length - 1] + 1e-9; t += dt) {
            double f = interp(curve, t), r = 1 / (1 + f);
            slow = NukeLight.adapt(slow, Math.log(1 / r), dt);
            for (int i = 0; i < at.length; i++) if (Math.abs(t - at[i]) < dt / 2) out[i] = f * r * NukeLight.ballGain(r, 1, slow);
        }
        return out;
    }

    private static double interp(double[][] c, double t) {
        for (int i = 1; i < c.length; i++) {
            if (t <= c[i][0]) {
                double u = (t - c[i - 1][0]) / (c[i][0] - c[i - 1][0]);
                return c[i - 1][1] + u * (c[i][1] - c[i - 1][1]);
            }
        }
        return c[c.length - 1][1];
    }

    /**
     * The frame's light is sampled again at every new game time, not only after a frame event: a Flashback video export draws
     * its frames without the frame events, and the exposure stayed at its pre-export value through a whole export (the world
     * white, the dust a flat orange for a megaton's pulse).
     */
    @Test
    void theLightIsSampledAtEveryNewGameTime() throws IOException {
        assertTrue(NukeLight.fresh(false, 10, 10), "after a frame event");
        assertTrue(NukeLight.fresh(true, 10, 10.25), "an exported frame, no frame event");
        assertFalse(NukeLight.fresh(true, 10.25, 10.25), "the same frame's later fog calls");
        String light = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/nuclear/NukeLight.java"));
        assertTrue(light.contains("if (!fresh(prepared, preparedAt, at)) return;"), "the fog-colour hook samples by game time");
    }

    private static String body(String source, String head) {
        int from = source.indexOf(head), depth = 0;
        for (int i = source.indexOf('{', from); i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(from, i + 1);
        }
        throw new AssertionError(head);
    }
}
