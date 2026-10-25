package ua.zentix.incoming.client.sound;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.sounds.JOrbisAudioStream;

import java.io.IOException;
import java.io.InputStream;

/**
 * Мгновенная громкость записи во времени (ITU-R BS.1770: K-фильтр, окно 400 мс) в каждом тике записи (шаг 50 мс) — самое
 * громкое окно 400 мс, в котором этот тик есть: и то, что с него начинается (удар — до того, как прозвучал), и начатые
 * до него (удар тиком раньше: его окно ещё звучит, и взрыв, пришедший следом, ложится в то же окно). По ней {@link Mix}
 * знает, что звучит у уха сейчас, а не только громкость в начале. Без Minecraft (кроме чтения Vorbis).
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
        float[] ahead = new float[n];
        for (int k = 0; k < n; k++) {
            int a = k * h, b = Math.min(y.length, a + w);
            ahead[k] = (float) ((c[b] - c[a]) / w);
        }
        // в тик k звучит всякое окно 400 мс, где он есть: и начатые до него (удар был тиком раньше — его окно ещё идёт)
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
     * Энергия записи во времени: в каждом тике — мгновенная громкость BS.1770 как она есть, средний квадрат K-взвешенного
     * сигнала за прошедшие {@link #WINDOW} до конца тика (до начала записи — тишина, после конца — ещё окно хвоста). Каждый
     * отсчёт попадает в окна ровно стольких тиков, сколько их в окне, поэтому сумма по тикам × шаг — энергия всей записи:
     * без максимума окон и без заглядывания вперёд. Это профиль одной записи для гула ({@link Rumble}), а не {@link #of}:
     * тот — верхняя оценка для потолка (наибольшее окно, где тик есть, и удар — до того, как прозвучал).
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
