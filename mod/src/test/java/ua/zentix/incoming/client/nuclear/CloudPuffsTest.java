package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.MushroomModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudPuffsTest {
    /** 15 kt near the ground at a height of 64, without wind, on a scale 1 block = 4 m. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 0, 20_000, 7, 0.25f, false, Detonation.OLD_WAVE);

    /** The reflection of fires under the dust of the wall at 15 kt is more than a minute: at 70 s - almost at full strength, at 90 s - more than half. */
    @Test
    void wallFireGlowLastsMoreThanAMinute() {
        assertTrue(CloudPuffs.glow(30, 15) > 0.99);
        assertTrue(CloudPuffs.glow(70, 15) > 0.75, "70 s: " + CloudPuffs.glow(70, 15));
        assertTrue(CloudPuffs.glow(90, 15) > 0.5, "90 s: " + CloudPuffs.glow(90, 15));
        assertTrue(CloudPuffs.glow(200, 15) < 0.01);
        assertTrue(CloudPuffs.glow(50, 1) < CloudPuffs.glow(50, 15) - 0.3, "1 has noticeably shorter");
    }

    /**
     * The light from above on the dust is a ball: curled up into a ring, the cap is mixed with air on the outside, and through 20 s 15 its light
     *  a kilometer away is less than a thousandth of a day ({@link MushroomModel#light}); the extinguished ball does not illuminate the dust from above - and according to
     *  the numbers of the pall slot.
     */
    @Test
    void onlyTheFireballLightsTheDustFromAbove() {
        MushroomModel.Shape cap = MushroomModel.shape(20, 15, 0, true, 3_000);
        assertTrue(MushroomModel.light(cap, 15, true, 1_000) < 1e-3, "cap via 20 s: " + MushroomModel.light(cap, 15, true, 1_000));
        float[] light = new float[8];
        assertTrue(new CloudPuffs(GROUND).pallLight(cap, light));
        double lit = CloudPuffs.ball(luminance(light), light[0], GROUND.blocks(1_000));
        assertTrue(lit < 1e-3, "15 kt through 20 s: light from above in a kilometer " + lit);
    }

    /**
     * The light of the ball on the dust is the same source as that of the body of the mushroom and particles ({@link MushroomModel#light}): according to the slot numbers
     *  ({@link CloudPuffs#ball}) - the same strength at any distance, color - black body of visible temperature cap, middle -
     *  at its height; fires - to the edge of the ignition zone. Without a mushroom (before detonation and after) - zeros: no reflection.
     */
    @Test
    void pallLightIsTheMushroomLight() {
        CloudPuffs puffs = new CloudPuffs(GROUND);
        float[] light = new float[8];
        double[] bb = new double[4];
        for (double t : new double[]{0.5, 2, 5, 9}) {
            MushroomModel.Shape s = MushroomModel.shape(t, 15, 0, true, 3_000);
            assertTrue(puffs.pallLight(s, light));
            BlackBody.light(MushroomModel.edgeTemperature(t, 15, true), bb);
            for (int c = 0; c < 3; c++) assertEquals(bb[c], light[4 + c], 1e-6 * bb[c] + 1e-30, t + " s, channel " + c);
            assertTrue(light[4] >= light[5] && light[5] >= light[6], t + " with: warm ball: " + light[4] + " " + light[5] + " " + light[6]);
            for (double m : new double[]{50, 400, 2_000}) {
                double body = MushroomModel.light(s, 15, true, m), dust = CloudPuffs.ball(bb[3], light[0], GROUND.blocks(m));
                assertEquals(body, dust, 1e-5 * body, t + " s, " + m + " m");
            }
            assertEquals(GROUND.blocks(s.capY()), light[7], 1e-3, t + " with: middle cap");
            assertEquals(CloudPuffs.glow(t, 15), light[1], 1e-6);
            assertTrue(light[3] > light[0], "edge of fires " + light[3] + " beyond the radius cap " + light[0]);
        }
        assertFalse(puffs.pallLight(null, light));
        for (float v : light) assertEquals(0, v);
    }

    private static double luminance(float[] light) {
        return 0.2126 * light[4] + 0.7152 * light[5] + 0.0722 * light[6];
    }
}
