package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mixture of explosions ({@link Mix}): the volume in the game is half the decibels of the total level, the ceiling is the sum, the share of each
 *  explosion is its share of power in life (Q_b = G·p_b²/Σp²). The "Grad" salvo in 30 blocks (look gate scene grad-day/grad-night)
 *  from real recordings is not higher than the ceiling (in 3.5.x - at 4–5 dB higher and cuts at full scale); near over a hundred distant
 *  sounds like one.
 */
class MixTest {
    /** Envelope, flat {@code ticks} tick, power {@code power} against the ceiling at volume 1. */
    static float[] flat(double power, int ticks) {
        float[] e = new float[ticks];
        java.util.Arrays.fill(e, (float) (power * Mix.CEILING));
        return e;
    }

    private static double db(double ratio) {
        return 10 * Math.log10(ratio);
    }

    @Test
    void oneBlastUnderTheCeilingIsUntouched() {
        Mix mix = new Mix();
        Mix.Blast b = mix.blast(0);
        b.add(new Mix.Layer(flat(0.3, 40), 1, 1));
        mix.at(5);
        assertEquals(1, b.factor(), 1e-12);
        assertEquals(0.3, mix.heard(5), 1e-6);
    }

    @Test
    void layersOfOneBlastAddUpToTheCeiling() {
        // angles of one recording - by amplitude: two at half volume - one entire recording
        Mix mix = new Mix();
        Mix.Blast b = mix.blast(0);
        b.add(new Mix.Layer(flat(0.5, 40), 1, Math.sqrt(0.5)));
        b.add(new Mix.Layer(flat(0.5, 40), 1, Math.sqrt(0.5)));
        assertEquals(0.5 * (0.5 + 0.5 + 2 * 0.5), mix.heard(3), 1e-6);
        // blow to the chest - your own record: from an angle - according to power; explosion from 1 psi (angle on the ceiling) - on the ceiling, and not at
        // 3 dB lower, as would be the case in amplitude (review F4)
        Mix one = new Mix();
        Mix.Blast c = one.blast(0);
        c.add(new Mix.Layer(flat(1, 40), 1, 1));
        c.add(new Mix.Layer(flat(0.8, 40), 1, 1, true));
        assertEquals(1, one.heard(3), 1e-6);
        assertEquals(1 / 1.8, c.factor() * c.factor(), 1e-6, "by power: 1 + 0,8, not (1 + √0,8)²");
    }

    @Test
    void equalBlastsAtOnceAddInHalfDecibels() {
        for (int n = 1; n <= 40; n++) {
            Mix mix = new Mix();
            double p = 0.05;
            List<Mix.Blast> all = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Mix.Blast b = mix.blast(0);
                b.add(new Mix.Layer(flat(p, 40), 1, 1));
                all.add(b);
            }
            // in life the level of n is equal - +10·lg n, in the game - half: power × √n, not higher than the ceiling
            assertEquals(Math.min(1, p * Math.sqrt(n)), mix.heard(3), 1e-6, n + " explosions");
            // each has a multiplier 1/√n, above the ceiling - 1/(n·p)
            double k2 = Math.min(1 / Math.sqrt(n), 1 / (n * p));
            for (Mix.Blast b : all) assertEquals(k2, b.factor() * b.factor(), 1e-9, n + " explosions");
        }
    }

    /**
     * A near explosion on the ceiling and a hundred distant ones at 0,01 (power in the game): the near one is 0,5 dB from itself alone in silence (−0,04),
     *  each distant one is 40 dB below it, all the distant ones together are at 20 dB (in life their sum is 1% of its power); with a common
     *  multiplier 3.6, the near one lost 3 dB, and the distant ones together sounded on par with it. The near verse - the distant ones return to
     *  their level, like one hundred equals.
     */
    @Test
    void nearStaysOverAHundredFar() {
        Mix mix = new Mix();
        Mix.Blast near = mix.blast(0);
        near.add(new Mix.Layer(flat(1, 20), 1, 1));
        List<Mix.Blast> far = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Mix.Blast b = mix.blast(0);
            b.add(new Mix.Layer(flat(0.01, 200), 1, 1));
            far.add(b);
        }
        mix.at(5);
        double alone = 1, nearHeard = near.heard(), farOne = far.get(0).heard(), farAll = 0;
        for (Mix.Blast b : far) farAll += b.heard();
        System.out.printf("near over 100 far: near %.3f dB vs alone, each far %.1f dB, all far %.1f dB below the near%n", db(nearHeard / alone),
                db(nearHeard / farOne), db(nearHeard / farAll));
        assertEquals(0, db(nearHeard / alone), 0.5, "neighbor - as one in silence");
        assertEquals(-0.043, db(nearHeard / alone), 0.01);
        assertEquals(40, db(nearHeard / farOne), 0.1, "each distant one - for a life difference (0,01/1)²");
        assertEquals(20, db(nearHeard / farAll), 0.1, "all distant ones together - 1% of the power of the near one");
        assertEquals(1, mix.heard(), 1e-6, "amount - ceiling");
        // near verse: a hundred distant ones are like a hundred equals, each with 1/√100
        mix.at(30);
        assertEquals(0.1, mix.heard(), 1e-6);
        assertEquals(0.1, far.get(0).factor() * far.get(0).factor(), 1e-6);
    }

    /** The sum of shares is G and not higher than the ceiling, the ratio of shares is the square of the power ratio (vital difference). */
    @Test
    void shareKeepsLifeRatiosAndTheSumUnderTheCeiling() {
        Random r = new Random(25_10_2026L);
        for (int trial = 0; trial < 200; trial++) {
            Mix mix = new Mix();
            int n = 1 + r.nextInt(60);
            List<Mix.Blast> all = new ArrayList<>();
            double sq = 0;
            for (int i = 0; i < n; i++) {
                Mix.Blast b = mix.blast(0);
                double p = Math.pow(10, -4 * r.nextDouble()) * (r.nextDouble() < 0.1 ? 3 : 1);
                b.add(new Mix.Layer(flat(p, 40), 1, 1));
                all.add(b);
                sq += p * p;
            }
            mix.at(1);
            double g = Math.min(1, Math.sqrt(sq));
            assertEquals(g, mix.heard(), 1e-6 * g);
            assertTrue(mix.heard() <= 1 + 1e-9);
            Mix.Blast a = all.get(0), b = all.get(all.size() - 1);
            double pa = a.power(1), pb = b.power(1);
            assertEquals(db(pa * pa / (pb * pb)), db(a.heard() / b.heard()), 1e-6);
            for (Mix.Blast x : all) assertTrue(x.factor() <= 1 + 1e-12);
        }
    }

    /**
     * The echo is the layers of its explosion in the mixture: when the direct blow has already subsided, the echo sounds in its own share, and does not go out with it (with the
     *  multiplier from one direct blow from the explosion next to it, it would go to zero); one explosion with an echo in silence - as it is.
     */
    @Test
    void lateEchoKeepsItsOwnShare() {
        Mix mix = new Mix();
        Mix.Blast a = mix.blast(0);
        a.add(new Mix.Layer(flat(0.5, 20), 1, 1));
        a.add(Mix.Layer.own(flat(0.05, 20), flat(0.05, 20), 1, 30));
        mix.at(40);
        assertEquals(1, a.factor(), 1e-12, "one: echo as is");
        assertEquals(0.05, mix.heard(), 1e-6);
        Mix.Blast b = mix.blast(0);
        b.add(new Mix.Layer(flat(0.5, 100), 1, 1));
        mix.at(40);
        // echo 0,05 against neighbor 0,5 - for life difference (0,1)², 20 dB
        assertEquals(20, db(b.heard() / a.heard()), 0.01);
        assertTrue(a.heard() > 0);
        // the delayed layer extends the life of the explosion
        assertEquals(51, a.end - a.start, 1e-9);
    }

    /**
     * Echo tracks that took their sources - layers of their own explosion with their own delay and volume; not taken - no; without the
     *  mixture (the nuke) - nothing.
     */
    @Test
    void echoTracksJoinTheirBlastWhenTheirSourcesTakeThem() {
        Mix mix = new Mix();
        Mix.Blast b = mix.blast(0);
        b.add(new Mix.Layer(flat(0.5, 20), 1, 1));
        EchoMix.Track t0 = new EchoMix.Track(0, 30, new short[2205], 0.5f), t1 = new EchoMix.Track(1, 40, new short[2205], 0.5f),
                t2 = new EchoMix.Track(2, 55, new short[2205], 20f);
        EchoMix.Mix done = new EchoMix.Mix(List.of(t0, t1, t2), 2, 3);
        float[][][] env = {{flat(0.1, 20), flat(0.1, 20)}, {flat(0.1, 20), flat(0.1, 20)}, {flat(0.1, 20), flat(0.1, 20)}};
        assertEquals(2, BlastEchoes.queue(done, env, 2, b, (t, gain) -> t != t1));
        List<Mix.Layer> own = b.layers.stream().filter(l -> l.kind() == Mix.Kind.OWN).toList();
        assertEquals(2, own.size());
        assertEquals(30, own.get(0).delay(), 1e-12);
        assertEquals(1, own.get(0).volume(), 1e-6, "direct impact volume × custom");
        assertEquals(55, own.get(1).delay(), 1e-12);
        assertEquals(16, own.get(1).volume(), 1e-6, "no louder than AL_MAX_GAIN its source");
        mix.at(31);
        assertEquals(0.1 * 1 * 1, b.power(31) - 0, 1e-6, "echo sounds through 31 tick");
        assertEquals(3, BlastEchoes.queue(done, env, 2, null, (t, gain) -> true), "no mixture - only voices");
    }

    /** The delay layer is from it; a tick before the beginning - already the first (the voice that leads the frame starts a fraction of a tick earlier). */
    @Test
    void delayedLayerStartsOnTimeAndBlastFollowsTheRealStart() {
        Mix.Layer echo = Mix.Layer.own(flat(0.2, 10), flat(0.2, 10), 1, 30);
        assertEquals(0, echo.power(28.5), 1e-12);
        assertEquals(0.2, echo.power(29.5), 1e-6);
        assertEquals(0.2, echo.power(30), 1e-6);
        assertEquals(0, echo.power(41), 1e-12);
        Mix mix = new Mix();
        Mix.Blast b = mix.blast(0);
        b.add(new Mix.Layer(flat(0.3, 10), 1, 1));
        b.late(5);
        // the sound actually started 5 tick later - a mixture from the real beginning
        assertEquals(0, b.power(3), 1e-12);
        assertEquals(0.3, b.power(14), 1e-6);
        assertEquals(0, b.power(16.5), 1e-12);
    }

    /** The energy of the engine voices (the hum along it) is without its own sources and according to the profile of the sounding recording. */
    @Test
    void voicedEnergyIsTheEngineVoicesOnly() {
        Mix mix = new Mix();
        Mix.Blast b = mix.blast(0);
        b.add(new Mix.Layer(flat(0.5, 40), flat(0.2, 10), 1, 1, Mix.Kind.VIEW, 0));
        b.add(new Mix.Layer(flat(0.1, 40), flat(0.1, 40), 1, 1, Mix.Kind.APART, 0));
        b.add(Mix.Layer.own(flat(0.3, 40), flat(0.3, 40), 1, 0));
        assertEquals(0.5 + 0.1 + 0.3, b.power(5), 1e-6);
        assertEquals(0.2 + 0.1, b.voiced(5), 1e-6, "the energy of the angle and the blow to the chest, without the blow to the ear");
        assertEquals(0.1, b.voiced(20), 1e-6, "the recording of the angle ended at 10-th tick - there is no energy, although the ceiling envelope is longer");
    }

    @Test
    void endedBlastLeavesTheMix() {
        Mix mix = new Mix();
        Mix.Blast a = mix.blast(0);
        a.add(new Mix.Layer(flat(2, 20), 1, 1));
        mix.at(10);
        assertTrue(a.factor() < 1);
        // the recording ended: the next explosion sounds as if the first one did not happen
        Mix.Blast b = mix.blast(30);
        b.add(new Mix.Layer(flat(0.5, 20), 1, 1));
        mix.at(31);
        assertEquals(1, b.factor(), 1e-12);
        assertEquals(1, mix.size());
        // tone 2 - recording is half as long
        Mix fast = new Mix();
        Mix.Blast c = fast.blast(0);
        c.add(new Mix.Layer(flat(2, 20), 2, 1));
        Mix.Blast d = fast.blast(11);
        d.add(new Mix.Layer(flat(0.5, 20), 1, 1));
        fast.at(11);
        assertEquals(1, d.factor(), 1e-12);
    }

    /**
     * Scene volley look gate: 40 "Grad" bursts (6,4 kg) with a scatter of 15 blocks around a point in 30 blocks from the viewer,
     *  arrival - for 13 s (as in the run RV1). Recording angles - real files, tone according to Sachs, volume according to pressure
     *  ({@link BlastMix#at}), multiplier of each explosion - once per tick, like the engine. The sum is in K-weighted instantaneous loudness
     *  not higher than the ceiling (with a tick step tolerance), and without a mixture it would be higher by dB.
     */
    @Test
    void gradSalvoStaysUnderTheCeiling() throws IOException {
        int rate = 44100;
        float[][] near = load("blast_near", 6, rate), mid = load("blast_mid", 6, rate), far = load("blast_far", 6, rate), sub = load("blast_sub", 2, rate);
        float[] eNear = envelope(near, rate), eMid = envelope(mid, rate), eFar = envelope(far, rate), eSub = envelope(sub, rate);
        BlastSource rocket = BlastSource.of(WeaponType.ROCKET, false);
        double pitch = BlastMix.pitch(rocket.chargeKg());
        Random r = new Random(22_10_2026L);
        int blasts = 40, seconds = 18, n = seconds * rate, tickLen = rate / 20;
        double[] at = new double[blasts], dist = new double[blasts];
        for (int i = 0; i < blasts; i++) {
            at[i] = 20 * 13 * r.nextDouble();
            double a = 2 * Math.PI * r.nextDouble(), rr = 15 * Math.sqrt(r.nextDouble());
            dist[i] = Math.hypot(30 + rr * Math.cos(a), rr * Math.sin(a));
        }
        // pairs of bursts in one tick - happen in a salvo
        at[1] = at[0];
        at[3] = at[2] + 0.2;
        java.util.Arrays.sort(at);
        Mix mix = new Mix();
        Mix.Blast[] mb = new Mix.Blast[blasts];
        BlastMix.Layers[] layers = new BlastMix.Layers[blasts];
        double[] mixed = new double[n], raw = new double[n];
        int next = 0;
        for (int tick = 0; tick < seconds * 20; tick++) {
            while (next < blasts && at[next] <= tick) {
                layers[next] = BlastMix.at(rocket, dist[next]);
                BlastMix.Layers l = layers[next];
                mb[next] = mix.blast(tick);
                if (l.near() > 0) mb[next].add(new Mix.Layer(eNear, pitch, l.near()));
                if (l.mid() > 0) mb[next].add(new Mix.Layer(eMid, pitch, l.mid()));
                if (l.far() > 0) mb[next].add(new Mix.Layer(eFar, pitch, l.far()));
                if (l.sub() > 0) mb[next].add(new Mix.Layer(eSub, pitch, l.sub(), true));
                next++;
            }
            mix.at(tick);
            for (int i = 0; i < next; i++) {
                double k = mb[i].factor();
                int start = (int) Math.round(mb[i].start * tickLen);
                BlastMix.Layers l = layers[i];
                for (int s = tick * tickLen; s < (tick + 1) * tickLen && s < n; s++) {
                    double t = (s - start) * pitch;
                    double v = l.near() * sample(near[i % 6], t) + l.mid() * sample(mid[i % 6], t) + l.far() * sample(far[i % 6], t)
                            + l.sub() * sample(sub[i % 2], t);
                    raw[s] += v;
                    mixed[s] += k * v;
                }
            }
        }
        double top = momentaryMax(mixed, rate), unmixed = momentaryMax(raw, rate);
        System.out.printf("salvo: mixed %.1f LUFS, unmixed %.1f LUFS, peak %.2f, over full scale %d samples (unmixed %d)%n", top, unmixed, peak(mixed),
                over(mixed), over(raw));
        assertTrue(unmixed > Loudness.CEILING_LUFS + 2, "without mixture volley above the ceiling: " + unmixed);
        assertTrue(top <= Loudness.CEILING_LUFS + 1, "volley in the mixture - at the ceiling " + Loudness.CEILING_LUFS + " LUFS: " + top);
        // and does not rest on the full scale amount (in the run RV1 - ~20 cuts per scene, without a mixture here - thousands): the peak is above
        // full scale - only in the tick of the blow (the first 50 ms), where the peaks of the close-up angle and the blow to the chest of one explosion coincide (both
        //  from the impact from the first sample; so a single explosion in 50 and 3.4.1 was cut off) - this is caught by the limiter OpenAL Soft
        int impact = tickLen, tails = 0;
        for (int i = 0; i < n; i++) {
            if (Math.abs(mixed[i]) < 1) continue;
            boolean onImpact = false;
            for (Mix.Blast b : mb) if (b != null && i >= b.start * tickLen && i < b.start * tickLen + impact) onImpact = true;
            if (!onImpact) tails++;
        }
        assertEquals(0, tails, "sample at full scale outside of shock, peak " + peak(mixed));
        assertTrue(over(mixed) * 100 < over(raw), "slices " + over(mixed) + " against " + over(raw) + " without mixture");
    }

    private static double peak(double[] x) {
        double m = 0;
        for (double v : x) m = Math.max(m, Math.abs(v));
        return m;
    }

    private static int over(double[] x) {
        int c = 0;
        for (double v : x) if (Math.abs(v) >= 1) c++;
        return c;
    }

    static double sample(float[] x, double at) {
        if (at < 0) return 0;
        int i = (int) at;
        if (i + 1 >= x.length) return 0;
        double f = at - i;
        return x[i] + (x[i + 1] - x[i]) * f;
    }

    /** Recording options {@code name}_1…_count, all at frequency {@code rate}. */
    static float[][] load(String name, int count, int rate) throws IOException {
        float[][] out = new float[count][];
        for (int i = 0; i < count; i++) {
            String path = "/assets/incoming/sounds/" + name + "_" + (i + 1) + ".ogg";
            try (InputStream in = MixTest.class.getResourceAsStream(path)) {
                assertNotNull(in, path);
                float[][] pcm = new float[1][];
                assertEquals(rate, Envelope.read(in, pcm), path);
                out[i] = pcm[0];
            }
        }
        return out;
    }

    /** The event envelope is the largest in the options, like {@link Envelopes}. */
    static float[] envelope(float[][] variants, int rate) {
        float[] max = new float[0];
        for (float[] v : variants) max = Envelopes.max(max, Envelope.of(v, rate));
        return max;
    }

    /** Largest K-weighted instantaneous loudness (window 400 ms through 100 ms), LUFS. */
    private static double momentaryMax(double[] x, int rate) {
        double[] y = x.clone();
        Envelope.kWeight(y, rate);
        double[] c = new double[y.length + 1];
        for (int i = 0; i < y.length; i++) c[i + 1] = c[i] + y[i] * y[i];
        int w = (int) (0.4 * rate), h = rate / 10;
        double best = 0;
        for (int i = 0; i + w <= y.length; i += h) best = Math.max(best, (c[i + w] - c[i]) / w);
        return Envelope.lufs(best);
    }
}
