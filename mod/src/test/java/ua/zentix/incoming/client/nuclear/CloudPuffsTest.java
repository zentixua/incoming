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

    /**
     * Fires light the dust from below with their own flame ({@link PallField#fireLight}, the haze's fire light), not a clock of their
     * own: while the wall passes (15 kt: front at the fire-zone edge, 2 km, in ~6 s; 1 Mt: 10.8 km in ~80 s at real wave speed)
     * the fires lit by the flash have barely grown — under 1 % of full flame; full flame only after tens of minutes. The slot
     * carries exactly that value (18.11 complaint: orange patches under a wall lofted seconds after the flash).
     */
    @Test
    void fireLightUnderDustIsTheFiresOwnFlame() {
        for (double kt : new double[]{15, 1000}) {
            Detonation d = new Detonation(1, new Vec3(0, 64, 0), 64, kt, true, 0, 0, 0, 20_000, 7, 1f, false, 1f);
            PallField pall = new PallField(d);
            double edge = pall.fireReach(), ticks = d.arrivalTicks(edge);
            assertTrue(edge > 0, kt + " kt: fire zone");
            assertTrue(pall.fireLight(ticks) < 0.01, kt + " kt: flame when the front leaves the fire zone " + pall.fireLight(ticks));
            assertTrue(pall.fireLight(30 * 60 * 20) > 0.9, kt + " kt: flame after half an hour " + pall.fireLight(30 * 60 * 20));
            float[] light = new float[8];
            assertTrue(new CloudPuffs(d).pallLight(MushroomModel.shape(ticks / 20, kt, 0, true, 3_000), pall.fireLight(ticks), light));
            assertEquals(pall.fireLight(ticks), light[1], 1e-6, kt + " kt: slot fire light");
            assertEquals(pall.fireReach(), light[3], 1e-3 * light[3], kt + " kt: one fire zone for the pall and the light below dust");
        }
    }

    /**
     * The fireball lights dust from above: after rolling into a ring, the cap's exterior mixes with air.
     * At 20 s for 15 kt, its light a kilometer away is less than one thousandth of daylight
     * ({@link MushroomModel#light}). An extinguished fireball does not light dust from above; the pall slot
     * values must agree.
     */
    @Test
    void onlyTheFireballLightsTheDustFromAbove() {
        MushroomModel.Shape cap = MushroomModel.shape(20, 15, 0, true, 3_000);
        assertTrue(MushroomModel.light(cap, 15, true, 1_000) < 1e-3, "cap after 20 s: " + MushroomModel.light(cap, 15, true, 1_000));
        float[] light = new float[8];
        assertTrue(new CloudPuffs(GROUND).pallLight(cap, 0, light));
        double lit = CloudPuffs.ball(luminance(light), light[0], GROUND.blocks(1_000));
        assertTrue(lit < 1e-3, "15 kt after 20 s: light from above in a kilometer " + lit);
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
            assertTrue(puffs.pallLight(s, 0.25, light));
            ua.zentix.incoming.nuclear.model.FlashLight.source(s, 15, true, bb);
            for (int c = 0; c < 3; c++) assertEquals(bb[c], light[4 + c], 1e-6 * bb[c] + 1e-30, t + " s, channel " + c);
            assertTrue(light[4] >= light[5] && light[5] >= light[6], t + " s: warm ball: " + light[4] + " " + light[5] + " " + light[6]);
            for (double m : new double[]{50, 400, 2_000}) {
                double body = MushroomModel.light(s, 15, true, m), dust = CloudPuffs.ball(bb[3], light[0], GROUND.blocks(m));
                assertEquals(body, dust, 1e-5 * body, t + " s, " + m + " m");
            }
            assertEquals(GROUND.blocks(s.capY()), light[7], 1e-3, t + " s: middle cap");
            assertEquals(0.25, light[1], 1e-6);
            assertTrue(light[3] > light[0], "edge of fires " + light[3] + " beyond the radius cap " + light[0]);
        }
        assertFalse(puffs.pallLight(null, 0.25, light));
        for (float v : light) assertEquals(0, v);
    }

    private static double luminance(float[] light) {
        return 0.2126 * light[4] + 0.7152 * light[5] + 0.0722 * light[6];
    }
}
