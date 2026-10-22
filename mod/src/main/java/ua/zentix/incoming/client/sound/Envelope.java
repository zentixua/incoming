package ua.zentix.incoming.client.sound;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.sounds.JOrbisAudioStream;

import java.io.IOException;
import java.io.InputStream;

/**
 * Мгновенная громкость записи во времени (ITU-R BS.1770: K-фильтр, окно 400 мс) — средний квадрат K-взвешенного сигнала
 * в окне, которое начинается в каждом тике записи (шаг 50 мс): сколько звук даст в ближайшие 400 мс с этого мига. По ней
 * {@link Mix} знает, что звучит у уха сейчас, а не только громкость в начале. Окно — вперёд: у взрыва громче всего
 * первые доли секунды, и смесь узнаёт об ударе до него, а не через 400 мс. Без Minecraft (кроме чтения Vorbis).
 */
public final class Envelope {
    /** Окно мгновенной громкости, с (BS.1770). */
    static final double WINDOW = 0.4;
    /** Шаг — тик, с. */
    static final double STEP = 0.05;

    private Envelope() {}

    /** Средний квадрат K-взвешенного сигнала в окне {@link #WINDOW} с начала каждого тика записи {@code x} (моно) частоты {@code rate}. */
    public static float[] of(float[] x, int rate) {
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[i];
        kWeight(y, rate);
        double[] c = new double[y.length + 1];
        for (int i = 0; i < y.length; i++) c[i + 1] = c[i] + y[i] * y[i];
        int w = (int) Math.round(WINDOW * rate), h = (int) Math.round(STEP * rate), n = Math.max(1, (y.length + h - 1) / h);
        float[] out = new float[n];
        for (int k = 0; k < n; k++) {
            int a = k * h, b = Math.min(y.length, a + w);
            out[k] = (float) ((c[b] - c[a]) / w);
        }
        return out;
    }

    /** Громкость окна, LUFS (BS.1770: −0,691 + 10·lg среднего квадрата). */
    public static double lufs(double meanSquare) {
        return -0.691 + 10 * Math.log10(meanSquare + 1e-15);
    }

    /** Средний квадрат окна громкостью {@code lufs}. */
    public static double meanSquare(double lufs) {
        return Math.pow(10, (lufs + 0.691) / 10);
    }

    /** Запись Vorbis — в моно (каналы средним) и частота: {@code out[0]} — отсчёты, частота — возврат. */
    public static int read(InputStream in, float[][] out) throws IOException {
        try (JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
            int rate = (int) ogg.getFormat().getSampleRate(), ch = ogg.getFormat().getChannels();
            FloatArrayList s = new FloatArrayList();
            while (ogg.readChunk(s::add)) {
                // весь файл
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

    /** К-взвешивание ITU-R BS.1770 на частоте {@code rate} (те же формулы, что {@code k_weight} в build_sounds.py). */
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
