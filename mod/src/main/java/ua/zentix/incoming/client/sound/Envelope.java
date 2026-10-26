package ua.zentix.incoming.client.sound;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.sounds.JOrbisAudioStream;

import java.io.IOException;
import java.io.InputStream;

/**
 * Recording momentary loudness over time (ITU-R BS.1770: K-filter, 400 ms window) at each recording tick (50 ms
 * step): the loudest 400 ms window containing that tick, both the window starting there (impact before it plays)
 * and ones started earlier (impact one tick earlier: its window still sounds, and a following blast falls in the
 * same window). This lets {@link Mix} know what sounds at the ear now, not just initial loudness. Without Minecraft
 * (except Vorbis reading).
 */
public final class Envelope {
    /** Momentary-loudness window, s (BS.1770). */
    static final double WINDOW = 0.4;
    /** Step is one tick, s. */
    static final double STEP = 0.05;

    private Envelope() {}

    /** Mean square of K-weighted mono recording {@code x} at {@code rate}, in {@link #WINDOW} from each tick's start. */
    public static float[] of(float[] x, int rate) {
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[i];
        kWeight(y, rate);
        double[] c = new double[y.length + 1];
        for (int i = 0; i < y.length; i++) c[i + 1] = c[i] + y[i] * y[i];
        int w = (int) Math.round(WINDOW * rate), h = (int) Math.round(STEP * rate), n = Math.max(1, (y.length + h - 1) / h);
        float[] ahead = new float[n];
        for (int k = 0; k < n; k++) {
            int a = k * h, b = Math.min(y.length, a + w);
            ahead[k] = (float) ((c[b] - c[a]) / w);
        }
        // tick k sounds in every 400 ms window containing it, including earlier starts (impact last tick — its window continues)
        int back = (int) Math.ceil(WINDOW / STEP) - 1;
        float[] out = new float[n];
        for (int k = 0; k < n; k++) {
            float m = 0;
            for (int j = Math.max(0, k - back); j <= k; j++) m = Math.max(m, ahead[j]);
            out[k] = m;
        }
        return out;
    }

    /**
     * Recording energy over time: each tick has actual BS.1770 momentary loudness, mean square of the K-weighted
     * signal over the preceding {@link #WINDOW} up to tick end (silence before recording, one tail window after
     * its end). Each sample enters exactly as many tick windows as there are ticks in a window, so tick sum ×
     * step is total recording energy: no window maximum or lookahead. This is one recording's profile for rumble
     * ({@link Rumble}), unlike {@link #of}: that is a ceiling upper bound (largest window containing the tick,
     * and impact before it plays).
     */
    public static float[] energy(float[] x, int rate) {
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[i];
        kWeight(y, rate);
        double[] c = new double[y.length + 1];
        for (int i = 0; i < y.length; i++) c[i + 1] = c[i] + y[i] * y[i];
        int w = (int) Math.round(WINDOW * rate), h = (int) Math.round(STEP * rate), n = Math.max(1, (y.length + h - 1) / h);
        int tail = (w + h - 1) / h - 1;
        float[] out = new float[n + tail];
        for (int k = 0; k < out.length; k++) {
            int b = Math.min(y.length, (k + 1) * h), a = Math.max(0, (k + 1) * h - w);
            out[k] = (float) (b > a ? (c[b] - c[a]) / w : 0);
        }
        return out;
    }

    /** Window loudness, LUFS (BS.1770: −0.691 + 10·lg mean square). */
    public static double lufs(double meanSquare) {
        return -0.691 + 10 * Math.log10(meanSquare + 1e-15);
    }

    /** Mean square of a window at {@code lufs}. */
    public static double meanSquare(double lufs) {
        return Math.pow(10, (lufs + 0.691) / 10);
    }

    /** Vorbis recording to mono (channel mean) and rate: {@code out[0]} — samples; return value — rate. */
    public static int read(InputStream in, float[][] out) throws IOException {
        try (JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
            int rate = (int) ogg.getFormat().getSampleRate(), ch = ogg.getFormat().getChannels();
            FloatArrayList s = new FloatArrayList();
            while (ogg.readChunk(s::add)) {
                // entire file
            }
            float[] all = s.toFloatArray();
            float[] mono = new float[all.length / ch];
            for (int i = 0; i < mono.length; i++) {
                float v = 0;
                for (int c = 0; c < ch; c++) v += all[i * ch + c];
                mono[i] = v / ch;
            }
            out[0] = mono;
            return rate;
        }
    }

    /** ITU-R BS.1770 K-weighting at {@code rate} (same formulas as {@code k_weight} in build_sounds.py). */
    public static void kWeight(double[] x, int rate) {
        double g = 3.99984385397, q = 0.7071752369554193, fc = 1681.9744509555319;
        double k = Math.tan(Math.PI * fc / rate), vh = Math.pow(10, g / 20), vb = Math.pow(vh, 0.499666774155);
        double a0 = 1 + k / q + k * k;
        biquad(x, (vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0);
        q = 0.5003270373253953;
        fc = 38.13547087613982;
        k = Math.tan(Math.PI * fc / rate);
        a0 = 1 + k / q + k * k;
        biquad(x, 1, -2, 1, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0);
    }

    private static void biquad(double[] x, double b0, double b1, double b2, double a1, double a2) {
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double v = x[i], y = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = v;
            y2 = y1;
            y1 = y;
            x[i] = y;
        }
    }
}
