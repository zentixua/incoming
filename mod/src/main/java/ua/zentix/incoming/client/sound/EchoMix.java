package ua.zentix.incoming.client.sound;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Terrain echoes as sound (without Minecraft or OpenAL, covered by unit tests): {@link TerrainEcho} reflections
 * mix into {@link #SECTORS} tracks around the ear by compass direction. A track is the direct-impact recording
 * (“grain”) convolved with that direction's terrain response; air on the extra path absorbs highs (reflection
 * response through a {@link #CUTOFF} low-pass based on extra path). A reflection between directions splits into
 * both with equal power (cos/sin), so it sounds from its own direction, not the nearest compass point.
 * <p>
 * Facet response is Lambertian scattering: a slope in the height map is rough, each point returns the impact
 * with its own delay and phase; a facet returns noise spread over the path variation across it, not a copy of
 * the impact ({@link TerrainEcho.Reflection#spread}). Modelled as velvet noise (H. Järveläinen, M. Karjalainen,
 * “Reverberation modeling using velvet noise”, AES 30th Conf., 2007): random-sign impulses at random positions,
 * {@link #DENSITY} per second (after air filtering, at most 2·cutoff: denser is indistinguishable from continuous
 * noise), facet energy divided equally; a facet narrower than one step has one impulse. Delays are exact to the
 * sample: a delay grid (formerly 10 ms windows) made copies of one grain into a periodic train, a comb with
 * 100 Hz fundamental and harmonics (“arc” in 3.5.0 echoes, probe P 21.10.2026: 100–600 Hz peaks up to 32 dB above
 * neighbouring frequencies). Convolution uses FFT.
 * <p>
 * A track begins with its first reflection (preceding silence is not stored) and lasts at most {@link #MAX_SECONDS}.
 * If the sum exceeds {@link #PEAK}, the track is scaled down to it, with playback gain raised by the same factor
 * ({@link Track#gain}).
 */
public final class EchoMix {
    /** Track rate: distant-impact echoes are low-frequency; air absorbed the highs. */
    public static final int RATE = 11025;
    /** Directions around the ear. */
    public static final int SECTORS = 8;
    /**
     * Velvet-noise impulses per second: from 1500–2000 per second, it sounds smoother than Gaussian noise, without
     * crackle (Järveläinen, Karjalainen 2007).
     */
    static final double DENSITY = 2000;
    /**
     * Maximum impulses for all echoes (performance: background mixing memory and time). A nuke over hills produces
     * millions (a distant 96-block facet has half a second of spread); above budget, thin every facet equally.
     */
    static final int PULSE_BUDGET = 400_000;
    static final double MAX_SECONDS = 30;
    /** Maximum track sample (no clipping when writing 16-bit). */
    static final double PEAK = 0.98;
    /**
     * Reflection high cutoff by extra path: up to {@code PATH[i]} blocks, filter at {@code CUTOFF[i]} Hz (0 — no filter).
     * Air at 2 kHz: ~9 dB/km, at 4 kHz: ~23 dB/km (ISO 9613-1).
     */
    static final double[] PATH = {250, 1000, 3000, Double.POSITIVE_INFINITY};
    static final double[] CUTOFF = {0, 2000, 900, 450};

    private EchoMix() {}

    /**
     * Track for one direction.
     *
     * @param azimuth sound direction, rad ({@code atan2(dz, dx)}, as in {@link TerrainEcho.Reflection#azimuth})
     * @param start   ticks after direct impact before it starts
     * @param pcm     samples, mono 16-bit, {@link #RATE}
     * @param gain    playback gain multiplier (track scaled down to {@link #PEAK})
     */
    public record Track(double azimuth, double start, short[] pcm, float gain) {
        public double seconds() {
            return pcm.length / (double) RATE;
        }
    }

    /**
     * All detonation echoes at the listener.
     *
     * @param t60 tail decay time (s) for reverb over the tracks: from delay spread, weighted by power
     */
    public record Mix(List<Track> tracks, double t60, int taps) {
        public static final Mix NONE = new Mix(List.of(), 0, 0);
    }

    /** Response impulses of one direction: sample since direct impact, amplitude, grain copy. */
    private static final class Pulses {
        int size;
        long[] at = new long[64];
        float[] amp = new float[64];
        byte[] variant = new byte[64];

        void add(long sample, double a, int v) {
            if (size == at.length) {
                at = Arrays.copyOf(at, size * 2);
                amp = Arrays.copyOf(amp, size * 2);
                variant = Arrays.copyOf(variant, size * 2);
            }
            at[size] = sample;
            amp[size] = (float) a;
            variant[size++] = (byte) v;
        }

        long first() {
            long f = Long.MAX_VALUE;
            for (int i = 0; i < size; i++) f = Math.min(f, at[i]);
            return f;
        }

        /** Samples from first to last impulse, at most {@code max}. */
        int span(int max) {
            long f = first(), l = 0;
            for (int i = 0; i < size; i++) l = Math.max(l, at[i]);
            return (int) Math.min(max, l - f + 1);
        }
    }

    /** Reflections → tracks. {@code grain} is mono, {@link #RATE}, same loudness as direct impact. */
    public static Mix render(float[] grain, List<TerrainEcho.Reflection> reflections) {
        if (grain.length == 0 || reflections.isEmpty()) return Mix.NONE;
        Pulses[] sides = new Pulses[SECTORS];
        double width = 2 * Math.PI / SECTORS;
        double sumE = 0, sumT = 0, sumT2 = 0;
        int taps = 0;
        // impulses per facet from spread; after air filter, denser than 2·cutoff per second is the same continuous noise
        double want = 0;
        for (TerrainEcho.Reflection r : reflections) want += pulses(r, 1);
        double thin = Math.min(1, PULSE_BUDGET / Math.max(1, want));
        for (TerrainEcho.Reflection r : reflections) {
            double seconds = r.delay() / 20;
            if (!(seconds < MAX_SECONDS) || !(r.energy() > 0)) continue;
            int variant = variant(r.extraPath());
            double a = wrap(r.azimuth()) / width;
            int k0 = (int) Math.floor(a) % SECTORS, k1 = (k0 + 1) % SECTORS;
            double f = a - Math.floor(a);
            double c = Math.cos(f * Math.PI / 2), s = Math.sin(f * Math.PI / 2);
            // facet: impulses across its arrival spread, signs and positions from facet position (same terrain — same echoes)
            double span = span(r), early = Double.isFinite(r.early()) ? r.early() / 20 : seconds;
            int n = pulses(r, thin);
            SplittableRandom rnd = new SplittableRandom(Double.doubleToLongBits(r.x()) * 31 + Double.doubleToLongBits(r.z()) * 17
                    + Double.doubleToLongBits(r.y()));
            double amp = Math.sqrt(r.energy() / n);
            for (int i = 0; i < n; i++) {
                double t = n == 1 ? seconds : early + rnd.nextDouble() * span;
                double p = n == 1 ? amp : rnd.nextBoolean() ? amp : -amp;
                long at = Math.round(Math.max(0, t) * RATE);
                if (c > 1e-6) side(sides, k0).add(at, p * c, variant);
                if (s > 1e-6) side(sides, k1).add(at, p * s, variant);
            }
            sumE += r.energy();
            sumT += r.energy() * seconds;
            sumT2 += r.energy() * seconds * seconds;
            taps++;
        }
        if (taps == 0) return Mix.NONE;
        // one FFT length for all directions: grain spectrum computed once
        int grainLength = grain.length, max = (int) (MAX_SECONDS * RATE), longest = 0;
        for (Pulses p : sides) if (p != null) longest = Math.max(longest, p.span(max));
        int size = Integer.highestOneBit(Math.max(1, longest + grainLength - 2)) << 1;
        double[] gr = new double[size], gi = new double[size];
        for (int i = 0; i < grainLength; i++) gr[i] = grain[i];
        fft(gr, gi, false);
        // two directions in one FFT: response and track samples are real, one in real part, the other in imaginary
        // (convolution is linear: (a + i·b) ∗ g = a ∗ g + i·(b ∗ g) for real g)
        List<Track> tracks = new ArrayList<>();
        double[] re = new double[size], im = new double[size], part = new double[size];
        for (int k = 0; k < SECTORS; k += 2) {
            Pulses a = sides[k], b = sides[k + 1];
            if (a == null && b == null) continue;
            Arrays.fill(re, 0);
            Arrays.fill(im, 0);
            if (a != null) response(a, re, part, max);
            if (b != null) response(b, im, part, max);
            fft(re, im, false);
            for (int i = 0; i < size; i++) {
                double r = re[i] * gr[i] - im[i] * gi[i];
                im[i] = re[i] * gi[i] + im[i] * gr[i];
                re[i] = r;
            }
            fft(re, im, true);
            Track ta = a == null ? null : track(k * width, a, re, grainLength, max);
            Track tb = b == null ? null : track((k + 1) * width, b, im, grainLength, max);
            if (ta != null) tracks.add(ta);
            if (tb != null) tracks.add(tb);
        }
        double mean = sumT / sumE, spread = Math.sqrt(Math.max(0, sumT2 / sumE - mean * mean));
        // tail fades over the time in which most reflections arrive
        double t60 = Math.max(1.5, Math.min(8, 1 + mean + 2 * spread));
        return new Mix(List.copyOf(tracks), t60, taps);
    }

    /** Facet arrival spread, s (at most track length; no spread — 0). */
    private static double span(TerrainEcho.Reflection r) {
        double s = r.spread() / 20;
        return s > 0 ? Math.min(MAX_SECONDS, s) : 0;
    }

    /** Facet impulse count: from spread and its grain-copy density, thinned by {@code thin}, at least one. */
    private static int pulses(TerrainEcho.Reflection r, double thin) {
        int v = variant(r.extraPath());
        double density = CUTOFF[v] > 0 ? Math.min(DENSITY, 2 * CUTOFF[v]) : DENSITY;
        return Math.max(1, (int) Math.ceil(span(r) * density * thin));
    }

    private static Pulses side(Pulses[] sides, int k) {
        if (sides[k] == null) sides[k] = new Pulses();
        return sides[k];
    }

    /**
     * Directional response from its first impulse into {@code out}: impulses, distant ones air-filtered (convolution
     * is linear, so filtering response is equivalent to filtering grain).
     */
    private static void response(Pulses p, double[] out, double[] part, int max) {
        long first = p.first();
        int span = p.span(max);
        for (int v = 0; v < CUTOFF.length; v++) {
            boolean any = false, filter = CUTOFF[v] > 0;
            if (filter) Arrays.fill(part, 0);
            double[] into = filter ? part : out;
            for (int i = 0; i < p.size; i++) {
                if (p.variant[i] != v || p.at[i] - first >= span) continue;
                into[(int) (p.at[i] - first)] += p.amp[i];
                any = true;
            }
            if (!any || !filter) continue;
            lowpass(part, CUTOFF[v]);
            for (int i = 0; i < out.length; i++) out[i] += part[i];
        }
    }

    /** Directional track from its convolution with the grain. */
    private static Track track(double azimuth, Pulses p, double[] y, int grain, int max) {
        int length = Math.min(max, p.span(max) + grain - 1);
        double peak = 0;
        for (int i = 0; i < length; i++) peak = Math.max(peak, Math.abs(y[i]));
        if (peak <= 1e-9) return null;
        double scale = peak > PEAK ? PEAK / peak : 1;
        short[] pcm = new short[length];
        for (int i = 0; i < length; i++) pcm[i] = (short) Math.round(Math.max(-1, Math.min(1, y[i] * scale)) * 32767);
        return new Track(azimuth, p.first() * 20.0 / RATE, pcm, (float) (1 / scale));
    }

    /** In-place fast Fourier transform (power-of-two length); inverse divides by length. */
    static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wr = Math.cos(ang), wi = Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < half; k++) {
                    int a = i + k, b = a + half;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }

    /** Which grain copy a reflection with this extra path (blocks) uses. */
    static int variant(double extraPath) {
        for (int i = 0; i < PATH.length; i++) if (extraPath < PATH[i]) return i;
        return PATH.length - 1;
    }

    /** Angle in [0, 2π). */
    static double wrap(double a) {
        double t = a % (2 * Math.PI);
        return t < 0 ? t + 2 * Math.PI : t;
    }

    /** In-place low-pass (two one-pole filters in series — −12 dB per octave). */
    static void lowpass(double[] y, double cutoff) {
        double a = 1 - Math.exp(-2 * Math.PI * cutoff / RATE);
        for (int pass = 0; pass < 2; pass++) {
            double s = 0;
            for (int i = 0; i < y.length; i++) {
                s += a * (y[i] - s);
                y[i] = s;
            }
        }
    }

    /**
     * Grain at {@code pitch}, as played by the engine: one second of sound consumes {@code pitch} seconds of recording
     * (lower means longer), linearly interpolated between samples.
     */
    public static float[] pitch(float[] grain, double pitch) {
        if (Math.abs(pitch - 1) < 1e-6 || grain.length == 0) return grain;
        int n = (int) Math.floor((grain.length - 1) / pitch) + 1;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double at = i * pitch;
            int a = Math.min(grain.length - 1, (int) at);
            int b = Math.min(grain.length - 1, a + 1);
            double w = at - a;
            out[i] = (float) (grain[a] * (1 - w) + grain[b] * w);
        }
        return out;
    }

    /**
     * Direct-impact recording opening → grain: {@code samples} (−1..1, interleaved channels) at {@code rate} to mono
     * {@link #RATE} (window mean — no aliasing), first {@code seconds} seconds fading over the last third.
     */
    public static float[] grain(float[] samples, int channels, int rate, double seconds) {
        int frames = samples.length / Math.max(1, channels);
        double ratio = rate / (double) RATE;
        int n = (int) Math.min(frames / ratio, seconds * RATE);
        float[] out = new float[Math.max(0, n)];
        for (int i = 0; i < n; i++) {
            int a = (int) Math.floor(i * ratio), b = Math.max(a + 1, (int) Math.floor((i + 1) * ratio));
            double s = 0;
            int count = 0;
            for (int j = a; j < b && j < frames; j++) {
                for (int c = 0; c < channels; c++) s += samples[j * channels + c];
                count += channels;
            }
            out[i] = count == 0 ? 0 : (float) (s / count);
        }
        int fade = n / 3;
        for (int i = 0; i < fade; i++) {
            double w = Math.cos(Math.PI / 2 * (i + 1) / fade);
            out[n - fade + i] *= (float) (w * w);
        }
        return out;
    }
}
