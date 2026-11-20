package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Light of the substance around the eye by direction ({@link SmokePuffs.Veil#byHeight}): inside dense dust the frame is
 * not one flat colour (a player's frame inside B-2 dust: RGB 139/127/115 with SD 0 over the whole dust).
 */
class VeilLightTest {
    private static double linear(float shown) {
        return Math.pow(shown, 2.2);
    }

    @Test
    void upAndDownAreTheTwoStreamsOfTheSameLayer() {
        for (float shown : new float[]{SmokePuffs.thick(0, 1, 0, SmokeGrid.dustAlbedo(0.6f)), SmokePuffs.thick(1, 0, 0, 0.9f),
                SmokePuffs.thick(0, 0, 1, 0.9f)}) {
            double r = linear(shown);
            // the horizon keeps the thick-layer colour the puffs of the same cloud have
            assertEquals(shown, SmokePuffs.Veil.byHeight(shown, 0), 1e-6);
            // the upward stream is R∞ of the downward one (the closure of SmokePuffs.thick)
            double up = linear(SmokePuffs.Veil.byHeight(shown, 1)), down = linear(SmokePuffs.Veil.byHeight(shown, -1));
            assertEquals(r, down / up, 1e-4, "shown " + shown);
            // monotone in the ray's height
            float previous = 0;
            for (double y = -1; y <= 1; y += 0.125) {
                float c = SmokePuffs.Veil.byHeight(shown, y);
                assertTrue(c >= previous, "y " + y);
                previous = c;
            }
        }
    }

    @Test
    void fogStaysEvenDustAndSootDoNot() {
        float drops = SmokePuffs.thick(0, 0, 1, 0.9f), dust = SmokePuffs.thick(0, 1, 0, SmokeGrid.dustAlbedo(0.6f)),
                soot = SmokePuffs.thick(1, 0, 0, 0.9f);
        double fog = linear(SmokePuffs.Veil.byHeight(drops, 1)) / linear(SmokePuffs.Veil.byHeight(drops, -1));
        double dusty = linear(SmokePuffs.Veil.byHeight(dust, 1)) / linear(SmokePuffs.Veil.byHeight(dust, -1));
        double black = linear(SmokePuffs.Veil.byHeight(soot, 1)) / linear(SmokePuffs.Veil.byHeight(soot, -1));
        assertTrue(fog < 1.3, "fog " + fog);
        assertTrue(dusty > 2.5 && dusty < 4, "dust " + dusty);
        assertTrue(black > 10, "soot " + black);
    }

    /** The eye inside a dense dust Gaussian, looking at the horizon: the frame grades from the top to the bottom. */
    @Test
    void denseDustAroundTheEyeIsBrighterOverhead() {
        FarView view = SmokeFillTest.view(Vec3.ZERO, new Vector3f(0, 0, 1));
        SmokePuffs.Veil veil = new SmokePuffs.Veil();
        double sigma = 20, ext = 30 * 2 * Math.PI * sigma * sigma;
        float dust = SmokePuffs.thick(0, 1, 0, SmokeGrid.dustAlbedo(0.6f));
        veil.fill(view, new double[]{0, 0, 0, sigma, sigma, ext, dust, dust, dust, 1, 0}, 1);
        int n = SmokePuffs.Veil.N, mid = n / 2;
        float top = veil.r[SmokePuffs.Veil.index(mid, n - 1)], centre = veil.r[SmokePuffs.Veil.index(mid, mid)],
                bottom = veil.r[SmokePuffs.Veil.index(mid, 0)];
        assertTrue(veil.alpha(SmokePuffs.Veil.index(mid, mid)) > 0.99, "dense");
        assertEquals(dust, centre, 1e-4);
        assertTrue(top > centre + 0.05 && centre > bottom + 0.05, top + " " + centre + " " + bottom);
    }
}
