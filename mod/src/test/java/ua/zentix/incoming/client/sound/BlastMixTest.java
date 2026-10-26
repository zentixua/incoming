package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sound of an ordinary explosion by charge and pressure at the ear: volume increases with charge, tone and length - according to ∛W (Sax), angles -
 *  as one recording without steps, junction with the distant model on 640 blocks, impact at the ear from the model - where the near angle leaves.
 */
class BlastMixTest {
    private static final WeaponType[] SURFACE = {WeaponType.LOITER, WeaponType.ROCKET, WeaponType.DRONE, WeaponType.MISSILE};

    private static BlastSource of(WeaponType w) {
        return BlastSource.of(w, false);
    }

    /** Direct sound volume, LUFS: angles of one recording (one option) are added by amplitude. */
    private static double direct(BlastMix.Layers l) {
        return 20 * Math.log10(l.near() * Math.pow(10, BlastMix.NEAR_LUFS / 20) + l.mid() * Math.pow(10, BlastMix.MID_LUFS / 20)
                + l.far() * Math.pow(10, BlastMix.FAR_LUFS / 20));
    }

    @Test
    void louderWithChargeAtEveryDistance() {
        for (double d = 5; d <= Outdoor.NEAR; d *= 1.3) {
            double prev = Double.NEGATIVE_INFINITY;
            for (double kg : new double[]{1, 3, 6.4, 20, 50, 150, 450, 2000}) {
                double l = BlastMix.loudness(BlastMix.pressure(new BlastSource(null, kg, 0, true, false), d));
                assertTrue(l >= prev, kg + " kg per " + d + " bl: " + l + " < " + prev);
                prev = l;
            }
        }
    }

    /** Recording time - × ∛(W/W₀): tone ∛(W₀/W) (Minecraft compresses and stretches the recording with tone), within the engine. */
    @Test
    void durationScalesWithCubeRootOfCharge() {
        assertEquals(1, BlastMix.pitch(BlastMix.REFERENCE_KG), 1e-6);
        for (double kg : new double[]{6.25, 10, 50, 150, 400}) {
            assertEquals(Math.cbrt(kg / BlastMix.REFERENCE_KG), 1 / BlastMix.pitch(kg), 1e-5, kg + " kg");
        }
        // rocket (150 kg) - lower and longer Shahed, "Grad" (6,4 kg) - higher and shorter; "Lancet" (3 kg) - at the engine limit
        assertTrue(BlastMix.pitch(150) < BlastMix.pitch(50) && BlastMix.pitch(6.4) > BlastMix.pitch(50));
        assertEquals(2, BlastMix.pitch(3), 1e-6);
        assertEquals(0.5, BlastMix.pitch(2400), 1e-6);
    }

    @Test
    void perspectivesAreOneRecordingAndQuietDownSmoothly() {
        for (WeaponType w : SURFACE) {
            BlastSource s = of(w);
            double prev = Double.POSITIVE_INFINITY;
            for (double d = 2; d <= Outdoor.NEAR; d += 0.5) {
                BlastMix.Layers l = BlastMix.at(s, d);
                double near = BlastMix.far(d), crack = BlastMix.crack(BlastMix.pressure(s, d));
                // shares of angles in the sum 1
                assertEquals(1, (1 - near) * crack + (1 - near) * (1 - crack) + near, 1e-9);
                double v = direct(l);
                assertTrue(v <= prev + 0.05, w + ": louder on " + d + " bl");
                // volume - by pressure: the angles together give it (the near and middle are equal, the far is quieter)
                assertEquals(BlastMix.loudness(BlastMix.pressure(s, d)), v, 1.6, w + " on " + d);
                assertTrue(l.near() <= 1 && l.mid() <= 1 && l.far() <= 1 && l.sub() <= 1 && l.pulse() <= 1);
                prev = v;
            }
        }
    }

    @Test
    void edgeOfTheNearModelIsTheFarModel() {
        for (WeaponType w : SURFACE) {
            BlastMix.Layers l = BlastMix.at(of(w), Outdoor.NEAR);
            assertEquals(0, l.near(), 1e-6);
            assertEquals(0, l.mid(), 1e-6);
            assertEquals(BlastMix.v640(of(w)), l.far(), 1e-6, w + ": far angle on the edge - volume of the far model");
        }
    }

    /** Up close - close-up view with a jump; where the jump grows slowly (weaker ~1 kPa), it is played by the model. */
    @Test
    void crackIsTheNearPerspectiveUpCloseAndTheModelFurther() {
        BlastSource shahed = of(WeaponType.DRONE);
        BlastMix.Layers close = BlastMix.at(shahed, 10);
        assertTrue(close.near() > 0.95 && close.mid() < 0.05 && close.pulse() < 0.05, "in 10 m: " + close);
        assertEquals(close.near(), close.sub(), 1e-6, "blow to the chest - low close-up");
        assertTrue(BlastMix.crack(10_000) > 0.99 && BlastMix.crack(300) < 0.1);
        BlastMix.Layers mid = BlastMix.at(shahed, 400);
        assertTrue(mid.pulse() > 0.1 && mid.far() > mid.near() + mid.mid(), "in 400 m: " + mid);
        // less charge - the jump is weaker at the same distance: the closest one leaves earlier
        assertTrue(BlastMix.at(of(WeaponType.LOITER), 150).near() < BlastMix.at(of(WeaponType.MISSILE), 150).near());
    }
}
