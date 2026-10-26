package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.WaterDecal;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.WaterModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NukeWaterTest {
    /** Ground detonation 15 kt on water (surface surface - 63), scale 1: blocks = meters. */
    private static final Detonation SEA = new Detonation(1, new Vec3(0.5, 63, 0.5), 63, 15, true, 0, 0, 0, 20_000, 7, 1f, false, 0.5f);

    /**
     * Profile of the dark ring: near the front - the darkness of the ripples in the wind there, behind the front - lighter; foam - only at the epicenter
     *  (wind stronger than 25 m/s) and not at the front itself.
     */
    @Test
    void ringProfileIsDarkestAtTheFront() {
        float[] dark = new float[WaterDecal.SAMPLES], foam = new float[WaterDecal.SAMPLES];
        double ticks = 400, r = SEA.frontRadius(ticks);
        assertTrue(NukeWater.profile(SEA, ticks, r, 0, dark, foam));
        double wind = SEA.groundWind(SEA.groundOf(r));
        assertEquals(WaterModel.darkness(wind, 0, NukeWater.BAND, NukeWater.CALM), dark[0], 0.02);
        assertTrue(dark[0] > 0.3, "at the front in " + Math.round(r) + " m dark: " + dark[0]);
        assertTrue(dark[WaterDecal.SAMPLES / 2] < dark[0]);
        double late = 0;
        for (float f : foam) late = Math.max(late, f);
        assertTrue(late < 0.15, "at 3 km the foam at the epicenter has already cleared: " + late);
        // through 5 s a front of ~1,2 km, wind behind it ~80 m/s: foam behind the front
        assertTrue(NukeWater.profile(SEA, 100, SEA.frontRadius(100), 0, dark, foam));
        assertEquals(0, foam[0], 1e-6, "there is no foam yet at the front itself");
        double max = 0;
        for (float f : foam) max = Math.max(max, f);
        assertTrue(max > 0.4, "foam behind the front: " + max);
        // at 10 km the wind is weak: the ring is pale, there is no foam
        double far = SEA.arrivalTicks(10_000);
        assertTrue(NukeWater.profile(SEA, far, 10_000, 0, dark, foam));
        assertTrue(dark[0] < 0.25 && dark[0] > 0.02, "in 10 km: " + dark[0]);
        assertEquals(0, foam[0], 1e-6);
    }

    /** The profile samples are denser near the front: the first one is on it, the last one is near the center. */
    @Test
    void profileSamplesAreDenseAtTheFront() {
        double length = 3000;
        assertEquals(0, WaterDecal.sampleAt(0, length));
        assertEquals(length, WaterDecal.sampleAt(WaterDecal.SAMPLES - 1, length), 1e-9);
        assertTrue(WaterDecal.sampleAt(1, length) < 1, "at the front the step is less than a block");
        double prev = -1;
        for (int i = 0; i < WaterDecal.SAMPLES; i++) {
            double x = WaterDecal.sampleAt(i, length);
            assertTrue(x > prev);
            prev = x;
        }
    }

    /**
     * The head of the wall above the water is splashes behind the dark ring: its leading edge lags behind the edge of the dust on the path of the head behind the last {@link WaterModel#SPRAY_LAG} (the drops do not rise immediately; {@link BlastWall#slot}), and the dust above the land goes
     *  with the head.
     */
    @Test
    void sprayWallTrailsTheDarkRing() {
        BlastWall wall = new BlastWall(SEA, false);
        float[] s = new float[BlastWall.SLOT];
        for (double t : new double[]{3, 6}) {
            assertTrue(wall.slot(t, s));
            double head = s[0], lag = s[5], low = 0.1 * s[2] * s[6];
            assertTrue(lag > 30, t + " c: path of the head during the rise of the spray " + lag);
            assertTrue(s[7] > 0, t + " s: no splashes");
            assertTrue(BlastWall.sigma(s, head - 0.25 * lag, 0, low, 0) > 0, t + " s: there is no dust on the head");
            assertEquals(0, BlastWall.sigma(s, head - 0.7 * lag, 0, low, 1), t + " c: splashes ahead, near the dark ring");
            assertTrue(BlastWall.sigma(s, head - lag - 0.5 * s[1], 0, low, 1) > 0, t + " c: no splashes behind the ring");
        }
    }

    /**
     * A guess without data: the first air above the earth is exactly at sea level - the sea, above - the land; the surface of the sea from the sight
     *  (beam with liquid: the top of the water on the 8/9 block, 62,889) is also the sea.
     */
    @Test
    void seaLevelGuess() {
        assertEquals(1, NukeWater.guess(63, 63));
        assertEquals(1, NukeWater.guess(62 + 8.0 / 9.0, 63), "the surface of the sea at sight");
        assertEquals(0, NukeWater.guess(64, 63));
        assertEquals(0, NukeWater.guess(70.5, 63));
        assertEquals(0, NukeWater.guess(61, 63), "the bottom below the sea is not a guess “sea”");
    }

    /**
     * A block of surface under the epicenter: at a whole height (the first air above the ground) - below it, at the surface from the sight (62,889)
     *  - the block of water itself, and not the block below it (otherwise shallow water - dry land, without a column, fog and waves).
     */
    @Test
    void surfaceBlockUnderTheBurst() {
        assertEquals(62, NukeWater.surfaceBlock(63));
        assertEquals(62, NukeWater.surfaceBlock(62 + 8.0 / 9.0));
        assertEquals(63, NukeWater.surfaceBlock(64));
        assertEquals(70, NukeWater.surfaceBlock(70.5), "the top of the semi-block is the semi-block itself");
        assertEquals(-1, NukeWater.surfaceBlock(-0.11));
    }

    /**
     * The guess about the water under the epicenter lasts as long as the explosion on the client is young in its tick from the arrival of the packet: for
     *  that entered in the middle of the explosion (or in a repeat), it will have to wait longer for chunks, and what is known is probably resolved immediately.
     */
    @Test
    void guessSettlesByOwnAge() {
        var guess = new NukeWater.Under(1, NukeWater.Source.SEA_LEVEL);
        assertTrue(NukeWater.settles(new NukeWater.Under(0, NukeWater.Source.CHUNK), 0, false), "chunk - immediately");
        assertTrue(NukeWater.settles(new NukeWater.Under(1, NukeWater.Source.TILES), 0, true), "tiles - immediately");
        assertTrue(!NukeWater.settles(guess, NukeWater.GUESS_TICKS, true));
        assertTrue(NukeWater.settles(guess, NukeWater.GUESS_TICKS + 1, true));
        assertTrue(!NukeWater.settles(guess, NukeWater.GUESS_TICKS + 1, false), "the one who came last waits longer");
        assertTrue(NukeWater.settles(guess, NukeWater.GUESS_TICKS_PAST + 1, false));
    }
}
