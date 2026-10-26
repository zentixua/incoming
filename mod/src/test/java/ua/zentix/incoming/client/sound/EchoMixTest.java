package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.nuclear.model.BlastModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mixing reflections into tracks on the sides: time, volume, direction, without overload. */
class EchoMixTest {
    private static final double SECTOR = 2 * Math.PI / EchoMix.SECTORS;

    /** seed: click 0.5 at the beginning and a quiet tail. */
    private static float[] click() {
        float[] g = new float[EchoMix.RATE / 2];
        g[0] = 0.5f;
        for (int i = 1; i < g.length; i++) g[i] = (float) (0.1 * Math.exp(-i / 500.0) * Math.sin(i * 0.05));
        return g;
    }

    private static TerrainEcho.Reflection echo(double azimuth, double delayTicks, double energy, double extraPath) {
        return new TerrainEcho.Reflection(delayTicks, 0, azimuth, azimuth, delayTicks, energy, extraPath, 100, delayTicks, 0);
    }

    private static int peakAt(short[] pcm) {
        int at = 0;
        for (int i = 0; i < pcm.length; i++) if (Math.abs(pcm[i]) > Math.abs(pcm[at])) at = i;
        return at;
    }

    @Test
    void oneEchoIsOneTrackAtItsTimeAndLevel() {
        EchoMix.Mix mix = EchoMix.render(click(), List.of(echo(2 * SECTOR, 47.4, 0.25, 100)));
        assertEquals(1, mix.tracks().size());
        EchoMix.Track t = mix.tracks().get(0);
        assertEquals(2 * SECTOR, t.azimuth(), 1e-9);
        assertEquals(47.4, t.start(), 20.0 / EchoMix.RATE, "start - reflection delay, up to sample");
        assertEquals(1, t.gain(), 1e-6);
        assertEquals(0, peakAt(t.pcm()));
        assertEquals(0.5 * 0.5, t.pcm()[0] / 32767.0, 1e-3, "amplitude - √energy × seed");
    }

    @Test
    void echoBetweenSidesSplitsWithEqualPower() {
        EchoMix.Mix mix = EchoMix.render(click(), List.of(echo(SECTOR / 2, 40, 0.16, 100)));
        assertEquals(2, mix.tracks().size());
        double e = 0;
        for (EchoMix.Track t : mix.tracks()) {
            double a = t.pcm()[0] / 32767.0 / 0.5;
            e += a * a;
            assertEquals(Math.sqrt(0.16 / 2), a, 1e-3, "equally");
        }
        assertEquals(0.16, e, 1e-3, "power is maintained");
    }

    @Test
    void laterEchoesStartLaterInTheTrack() {
        EchoMix.Mix mix = EchoMix.render(click(), List.of(echo(0, 20, 0.1, 100), echo(0, 60, 0.05, 100)));
        EchoMix.Track t = mix.tracks().get(0);
        assertEquals(20, t.start(), 0.2);
        int second = (int) Math.round((60 - 20) / 20.0 * EchoMix.RATE);
        assertEquals(Math.sqrt(0.05) * 0.5, t.pcm()[second] / 32767.0, 2e-3, "second echo after 2 s after the first");
    }

    @Test
    void manyLoudEchoesAreScaledNotClipped() {
        List<TerrainEcho.Reflection> list = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) list.add(new TerrainEcho.Reflection(i, 0, 0, 0, 10, 0.5, 100, 100, 10, 0));
        EchoMix.Track t = EchoMix.render(click(), list).tracks().get(0);
        int peak = 0;
        for (short s : t.pcm()) peak = Math.max(peak, Math.abs(s));
        assertTrue(peak <= Math.round(EchoMix.PEAK * 32767) + 1, "peak " + peak);
        assertTrue(t.gain() > 1, "volume returned when playing");
    }

    @Test
    void longExtraPathLosesHighs() {
        float[] noise = new float[EchoMix.RATE];
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < noise.length; i++) noise[i] = (float) (r.nextGaussian() * 0.1);
        short[] near = EchoMix.render(noise, List.of(echo(0, 20, 0.5, 100))).tracks().get(0).pcm();
        short[] far = EchoMix.render(noise, List.of(echo(0, 20, 0.5, 5000))).tracks().get(0).pcm();
        assertTrue(highs(far) < 0.2 * highs(near), "far reflection top: " + highs(far) + " against " + highs(near));
    }

    /** The power of the differences between neighboring samples is a measure of the tops. */
    private static double highs(short[] pcm) {
        double s = 0;
        for (int i = 1; i < pcm.length; i++) s += Math.pow(pcm[i] - pcm[i - 1], 2);
        return s;
    }

    @Test
    void tailTimeGrowsWithTheSpreadOfDelays() {
        double tight = EchoMix.render(click(), List.of(echo(0, 20, 0.1, 100), echo(1, 24, 0.1, 100))).t60();
        double wide = EchoMix.render(click(), List.of(echo(0, 20, 0.1, 100), echo(1, 140, 0.1, 100))).t60();
        assertTrue(wide > tight, wide + " against " + tight);
    }

    @Test
    void grainIsDecimatedMonoWithAFade() {
        float[] stereo = new float[44100 * 2 * 4];
        java.util.Arrays.fill(stereo, 0.5f);
        float[] g = EchoMix.grain(stereo, 2, 44100, 3);
        assertEquals(3 * EchoMix.RATE, g.length);
        assertEquals(0.5, g[10], 1e-3);
        assertEquals(0, g[g.length - 1], 1e-3, "the end fades out");
    }

    @Test
    void lowerPitchStretchesTheGrain() {
        float[] ramp = new float[1001];
        for (int i = 0; i < ramp.length; i++) ramp[i] = i / 1000f;
        float[] g = EchoMix.pitch(ramp, 0.75);
        assertEquals(1334, g.length, "on 0,75 - a third longer");
        assertEquals(0.75 * 0.1, g[100], 1e-6, "second of sound - 0,75 s recording");
        assertEquals(1, g[g.length - 1], 1e-3);
        assertSame(ramp, EchoMix.pitch(ramp, 1));
    }

    @Test
    void wideFacetSpreadsItsEnergyOverItsArrivals() {
        // edge with spread of arrival 0,1 s (2 tick): impulses throughout the spread, the energy is the same as that of the narrow one
        EchoMix.Track t = EchoMix.render(click(), List.of(new TerrainEcho.Reflection(300, 70, 20, 0, 40, 0.2, 100, 300, 39, 2))).tracks().get(0);
        assertEquals(39, t.start(), 0.05, "beginning - the earliest arrival across the border");
        float[] g = click();
        double e = 0, ref = 0;
        for (float x : g) ref += x * x;
        for (short x : t.pcm()) e += Math.pow(x / 32767.0 * t.gain(), 2);
        assertEquals(0.2, e / ref, 0.2 * 0.15, "the edge energy is conserved (within the limits of random pulse coincidences)");
        int pulses = 0;
        for (int i = 0; i < (int) (0.1 * EchoMix.RATE); i++) if (Math.abs(t.pcm()[i]) > 0.3 * 32767 * Math.sqrt(0.2 / 200) / t.gain()) pulses++;
        assertTrue(pulses > 100, "impulses spread: " + pulses);
    }

    /**
     * Terrain echo without comb filtering. Measure the largest normalized track autocorrelation at 2–50 ms
     * lags with white-noise seed (then it is the impulse response autocorrelation): for a sound and its copy
     * at fraction a, it is a/(1+a²). Threshold 0,1 means a copy at −20 dB: a single reflection on noise becomes
     * audible as coloration around −15…−20 dB relative to direct sound (⚠ order of magnitude: S. E. Olive,
     * F. E. Toole, "The detection of reflections in typical rooms", JAES 37, 1989); the 3.5.0 comb (10 ms windows)
     * gave 0,4–0,5 at 10–50 ms (probe P, Shahed WAV at 300 m).
     */
    @Test
    void terrainEchoHasNoComb() {
        // hills around the ear, Shahed explosion in 300 blocks
        Sightline.Heights hills = (x, z) -> 64 + 30 * Math.sin(x / 150.0) * Math.cos(z / 170.0) + (Math.hypot(x, z) > 600 ? 60 : 0);
        TerrainEcho.Blast shahed = new TerrainEcho.Blast() {
            @Override
            public double arrivalTicks(double blocks) {
                return blocks / Acoustics.SPEED;
            }

            @Override
            public double pressure(double blocks) {
                return BlastModel.surfaceOverpressureKpa(blocks, 50);
            }
        };
        List<TerrainEcho.Reflection> found = TerrainEcho.trace(hills, 0, 100, 0, 300, 70, 40, shahed);
        assertTrue(found.size() > 50, "reflections " + found.size());
        double comb = comb(EchoMix.render(white(), found));
        assertTrue(comb < 0.1, "relief echo comb: " + comb);
        // the same measure catches the delay grid: copies after 10 ms
        List<TerrainEcho.Reflection> grid = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) grid.add(new TerrainEcho.Reflection(i, 0, 0, 0, 40 + i * 0.2, 0.01 * Math.exp(-i / 30.0), 100, 300, 40 + i * 0.2, 0));
        assertTrue(comb(EchoMix.render(white(), grid)) > 0.3, "delay grid - comb");
    }

    /** White noise 2,8 s - seed for response measures. */
    private static float[] white() {
        java.util.Random r = new java.util.Random(7);
        float[] g = new float[(int) (2.8 * EchoMix.RATE)];
        for (int i = 0; i < g.length; i++) g[i] = (float) (r.nextGaussian() * 0.1);
        return g;
    }

    /**
     * The largest normalized autocorrelation of the entire echo is at 2–50 ms: the ear hears the sum of the tracks, and the tracks of different
     *  sides have independent responses - the autocorrelation of the sum is the sum of their autocorrelations (over the entire length of each). One
     *  edge has velvety noise and a seed, the top of which has been cut off by the air up to ~1 kHz, themselves giving random protrusions up to ~0,1–0,15
     *  at individual delays (real scattering has the same thing: a fine random structure of the spectrum); The grid comb
     * delays is the same for all sides and does not go out in total.
     */
    static double comb(EchoMix.Mix mix) {
        int lo = (int) (0.002 * EchoMix.RATE), hi = (int) (0.05 * EchoMix.RATE);
        double e0 = 0;
        double[] r = new double[hi + 1];
        for (EchoMix.Track t : mix.tracks()) {
            short[] x = t.pcm();
            int n = x.length - hi;
            e0 += energy(x, 0, n, 0);
            for (int lag = lo; lag <= hi; lag++) r[lag] += energy(x, 0, n, lag);
        }
        double worst = 0;
        for (int lag = lo; lag <= hi; lag++) worst = Math.max(worst, Math.abs(r[lag]) / e0);
        return worst;
    }

    private static double energy(short[] x, int s, int n, int lag) {
        double sum = 0;
        for (int i = s; i < s + n; i++) sum += (double) x[i] * x[i + lag];
        return sum;
    }
}
