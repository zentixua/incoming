package ua.zentix.incoming.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropagationTest {
    private static final Spectrum FLAT = Spectrum.of(0, 0, 0, 0, 0, 0, 0, 0);

    @Test
    void groundFollowsIsoTable3() {
        // far (middle of the path - all): 63 Hz - reflection +6 dB at any ground; meadow at the top - without amendment
        double q = 1 - 30 * (2 + 1.7) / 5000;
        assertEquals(-3 - 3 * q, Propagation.ground(0, 5000, 2, 1.7, 1), 1e-9);
        assertEquals(0, Propagation.ground(6, 5000, 2, 1.7, 1), 1e-3);
        // solid ground (G = 0) at 500 Hz: −1,5 at the source and at the ear and −3 in the middle
        assertEquals(-3 - 3 * q, Propagation.ground(3, 5000, 2, 1.7, 0), 1e-9);
        // the meadow at the ear at 1,7 m extinguishes 250 Hz (b′ = 1,5 + 8,6·e^(−0,09·h²))
        assertEquals(8.6 * Math.exp(-0.09 * 1.7 * 1.7), Propagation.ground(2, 5000, 50, 1.7, 1), 1e-6);
    }

    @Test
    void divergenceIsSpherical() {
        // 20·lg d + 11: twice as far - 6 dB quieter
        assertEquals(6.02, Propagation.divergence(200) - Propagation.divergence(100), 0.01);
        assertEquals(11, Propagation.divergence(1), 1e-12);
    }

    @Test
    void shadowAtDayMatchesFarBlasts() {
        // shadow zone - the same law as for distant explosions (Outdoor): from 1 km at the source to 2 m, to 4 km - the entire depth
        assertEquals(1000, Propagation.shadowFrom(2, 1.7), 1e-9);
        assertEquals(0, Propagation.shadow(2000, 900, 2, 1.7, 1), 1e-12);
        assertEquals(20, Propagation.shadow(2000, 4000, 2, 1.7, 1), 1e-9);
        assertEquals(10, Propagation.shadow(63, 4000, 2, 1.7, 1), 1e-9);
        assertEquals(0, Propagation.shadow(2000, 4000, 2, 1.7, 0), 1e-12);
    }

    @Test
    void louderSourceIsHeardFartherAndNightFartherThanDay() {
        double prev = 0;
        for (double lw = 90; lw <= 150; lw += 10) {
            double r = Propagation.range(lw, FLAT, 20, Propagation.Weather.NIGHT, 50_000);
            assertTrue(r > prev, lw + " dB: " + r);
            prev = r;
            assertTrue(Propagation.range(lw, FLAT, 20, Propagation.Weather.NOON, 50_000) < r, "closer to the day: " + lw);
            assertTrue(Propagation.range(lw, FLAT, 20, new Propagation.Weather(0, 1, 0, 0), 50_000) < r, "closer in the rain: " + lw);
        }
    }

    @Test
    void toneIsHeardBelowTheBackgroundLevel() {
        // all power in the octave 500 Hz: at the edge of audibility its A level is lower than the A background level - the background masks it only
        //  with its part in the same octave
        Spectrum tone = Spectrum.of(-60, -60, -60, 0, -60, -60, -60, -60);
        double r = Propagation.range(110, tone, 20, Propagation.Weather.NIGHT, 50_000);
        double[] bands = new double[Propagation.BANDS];
        double level = Propagation.level(110, tone, Propagation.Path.over(r * 0.99, 20, Propagation.Weather.NIGHT), bands);
        assertTrue(Propagation.margin(bands, Propagation.Weather.NIGHT) > 0);
        assertTrue(level < Propagation.background(Propagation.Weather.NIGHT) - 5, "A-level on the edge " + level);
    }

    @Test
    void backgroundBandsSumToTheBackground() {
        double sum = 0;
        for (int b = 0; b < Propagation.BANDS; b++) sum += Math.pow(10, Propagation.background(b, Propagation.Weather.NIGHT) / 10);
        assertEquals(Propagation.NIGHT, 10 * Math.log10(sum), 1e-9);
    }

    /**
     * The air of the path is the humidity of the air of the world near the ground ({@link Propagation.Weather#humidity}): in the rain, the top 4 kHz per
     *  kilometer loses less (ISO 9613-1 at 20 °C: 23,1 dB/km at 70 %, 19,6 at 100 %); clear weather is the recording conditions.
     */
    @Test
    void airFollowsWorldHumidity() {
        assertEquals(Air.HUMIDITY / 100, Propagation.Weather.NIGHT.humidity(), 1e-12, "without peace - terms of records");
        assertEquals(Air.alpha(4000), Propagation.alpha(6, Propagation.Weather.NIGHT.humidity()), 1e-12);
        assertEquals(23.1, 1000 * Propagation.alpha(6, 0.7), 0.1);
        assertEquals(19.6, 1000 * Propagation.alpha(6, 1.0), 0.1);
        Propagation.Path clear = Propagation.Path.over(1000, 2, Propagation.Weather.NIGHT);
        Propagation.Path wet = Propagation.Path.over(1000, 2, new Propagation.Weather(0, 0, 0, 0, 1));
        assertEquals(1000 * (Propagation.alpha(6, 0.7) - Propagation.alpha(6, 1.0)),
                Propagation.attenuation(6, clear) - Propagation.attenuation(6, wet), 1e-9);
        // table for 1%: within the humidity of the world near the earth (70–100%) - as a formula
        for (double h = 0.7; h <= 1.0; h += 0.0137) {
            for (int b = 0; b < Propagation.BANDS; b++) {
                double exact = Air.alphaAt(Propagation.OCTAVES[b], h);
                assertEquals(exact, Propagation.alpha(b, h), 0.01 * exact, h + ", " + Propagation.OCTAVES[b] + " Hz");
            }
        }
        assertEquals(Air.alpha(5000) * 1000, Air.alphaAt(5000, Air.HUMIDITY / 100) * 1000, 1e-9);
    }
}
