package ua.zentix.incoming.client.sound;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Эхо от рельефа — в звук (без Minecraft и OpenAL, проверяется юнит-тестами): отражения {@link TerrainEcho} сводятся
 * в {@link #SECTORS} дорожек по сторонам света вокруг уха. Дорожка — запись прямого удара («зерно»), пропущенная через
 * отклик рельефа этой стороны; воздух на лишнем пути съедает верха (отклик отражения — через фильтр нижних частот
 * {@link #CUTOFF} по лишнему пути). Отражение между двумя сторонами делится на обе с равной мощностью (cos/sin), так что
 * оно звучит со своего направления, а не с ближней стороны.
 * <p>
 * Отклик грани — рассеяние по Ламберту: склон в карте высот шероховат, каждая его точка возвращает удар со своей
 * задержкой и фазой, и от грани приходит не копия удара, а шум, растянутый на разброс пути через грань
 * ({@link TerrainEcho.Reflection#spread}). Его модель — бархатный шум (H. Järveläinen, M. Karjalainen, «Reverberation
 * modeling using velvet noise», AES 30th Conf., 2007): импульсы случайного знака в случайных местах, {@link #DENSITY}
 * в секунду, энергия грани поровну; грань уже {@code 1/DENSITY} — один импульс. Задержки — точные, до отсчёта: сетка
 * задержек (раньше окна по 10 мс) делала из копий одного зерна периодический ряд, гребёнку с тоном 100 Гц и
 * гармониками («дуга» в эхе 3.5.0, проба P 21.10.2026: выступы 100–600 Гц до 32 дБ над соседними частотами). Свёртка
 * — через БПФ.
 * <p>
 * Дорожка начинается со своего первого отражения (тишину до него не хранит) и не длиннее {@link #MAX_SECONDS}.
 * Если сумма вышла за {@link #PEAK}, дорожка ужата до него, а громкость при игре — поднята на столько же
 * ({@link Track#gain}).
 */
public final class EchoMix {
    /** Частота дорожек: эхо далёкого удара — низы, верха съел воздух. */
    public static final int RATE = 11025;
    /** Сторон вокруг уха. */
    public static final int SECTORS = 8;
    /**
     * Импульсов бархатного шума в секунду: с 1500–2000 в секунду он звучит ровнее гауссова шума, без треска
     * (Järveläinen, Karjalainen 2007).
     */
    static final double DENSITY = 2000;
    static final double MAX_SECONDS = 30;
    /** Наибольший отсчёт дорожки (без перегруза при записи в 16 бит). */
    static final double PEAK = 0.98;
    /**
     * Срез верхов отражения по лишнему пути: до {@code PATH[i]} блоков — фильтр {@code CUTOFF[i]} Гц (0 — без фильтра).
     * Воздух на 2 кГц — ~9 дБ/км, на 4 кГц — ~23 дБ/км (ISO 9613-1).
     */
    static final double[] PATH = {250, 1000, 3000, Double.POSITIVE_INFINITY};
    static final double[] CUTOFF = {0, 2000, 900, 450};

    private EchoMix() {}

    /**
     * Дорожка одной стороны.
     *
     * @param azimuth откуда звучит, рад ({@code atan2(dz, dx)}, как у {@link TerrainEcho.Reflection#azimuth})
     * @param start   через сколько тиков после прямого удара начинается
     * @param pcm     отсчёты, моно 16 бит, {@link #RATE}
     * @param gain    во сколько раз громче играть (дорожка ужата до {@link #PEAK})
     */
    public record Track(double azimuth, double start, short[] pcm, float gain) {
        public double seconds() {
            return pcm.length / (double) RATE;
        }
    }

    /**
     * Всё эхо подрыва у слушателя.
     *
     * @param t60 время спада хвоста (с) — для реверберации поверх дорожек: по разбросу задержек, с весом мощности
     */
    public record Mix(List<Track> tracks, double t60, int taps) {
        public static final Mix NONE = new Mix(List.of(), 0, 0);
    }

    /** Импульсы отклика одной стороны: отсчёт от прямого удара, амплитуда, копия зерна. */
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

        /** Отсчётов от первого импульса до последнего, не больше {@code max}. */
        int span(int max) {
            long f = first(), l = 0;
            for (int i = 0; i < size; i++) l = Math.max(l, at[i]);
            return (int) Math.min(max, l - f + 1);
        }
    }

    /** Отражения → дорожки. {@code grain} — моно, {@link #RATE}, громкость как у прямого удара. */
    public static Mix render(float[] grain, List<TerrainEcho.Reflection> reflections) {
        if (grain.length == 0 || reflections.isEmpty()) return Mix.NONE;
        Pulses[] sides = new Pulses[SECTORS];
        double width = 2 * Math.PI / SECTORS;
        double sumE = 0, sumT = 0, sumT2 = 0;
        int taps = 0;
        for (TerrainEcho.Reflection r : reflections) {
            double seconds = r.delay() / 20;
            if (seconds >= MAX_SECONDS || !(r.energy() > 0)) continue;
            int variant = variant(r.extraPath());
            double a = wrap(r.azimuth()) / width;
            int k0 = (int) Math.floor(a) % SECTORS, k1 = (k0 + 1) % SECTORS;
            double f = a - Math.floor(a);
            double c = Math.cos(f * Math.PI / 2), s = Math.sin(f * Math.PI / 2);
            // грань: импульсы по её разбросу прихода, знаки и места — от места грани (тот же рельеф — то же эхо)
            double span = Math.max(0, r.spread()) / 20;
            int n = Math.max(1, (int) Math.ceil(span * DENSITY));
            SplittableRandom rnd = new SplittableRandom(Double.doubleToLongBits(r.x()) * 31 + Double.doubleToLongBits(r.z()) * 17
                    + Double.doubleToLongBits(r.y()));
            double amp = Math.sqrt(r.energy() / n);
            for (int i = 0; i < n; i++) {
                double t = n == 1 ? seconds : seconds + (rnd.nextDouble() - 0.5) * span;
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
        // одна длина БПФ на все стороны: спектр зерна — один раз
        int grainLength = grain.length, max = (int) (MAX_SECONDS * RATE), longest = 0;
        for (Pulses p : sides) if (p != null) longest = Math.max(longest, p.span(max));
        int size = Integer.highestOneBit(Math.max(1, longest + grainLength - 2)) << 1;
        double[] gr = new double[size], gi = new double[size];
        for (int i = 0; i < grainLength; i++) gr[i] = grain[i];
        fft(gr, gi, false);
        // две стороны на одно БПФ: у отклика и дорожки отсчёты вещественные, одна — в вещественной части, другая — в мнимой
        // (свёртка линейна: (a + i·b) ∗ g = a ∗ g + i·(b ∗ g) при вещественном g)
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
        // хвост гаснет за время, за которое приходит основная масса отражений
        double t60 = Math.max(1.5, Math.min(8, 1 + mean + 2 * spread));
        return new Mix(List.copyOf(tracks), t60, taps);
    }

    private static Pulses side(Pulses[] sides, int k) {
        if (sides[k] == null) sides[k] = new Pulses();
        return sides[k];
    }

    /**
     * Отклик стороны от её первого импульса — в {@code out}: импульсы, у дальних — через фильтр воздуха (свёртка
     * линейна, и фильтр отклика — то же, что фильтр зерна).
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

    /** Дорожка стороны из её свёртки с зерном. */
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

    /** Быстрое преобразование Фурье на месте (длина — степень двойки); обратное — с делением на длину. */
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

    /** Какая копия зерна у отражения с этим лишним путём (блоки). */
    static int variant(double extraPath) {
        for (int i = 0; i < PATH.length; i++) if (extraPath < PATH[i]) return i;
        return PATH.length - 1;
    }

    /** Угол в [0, 2π). */
    static double wrap(double a) {
        double t = a % (2 * Math.PI);
        return t < 0 ? t + 2 * Math.PI : t;
    }

    /** Фильтр нижних частот на месте (два однополюсных подряд — −12 дБ на октаву). */
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
     * Зерно с высотой тона {@code pitch}, как её играет движок: секунда звука берёт {@code pitch} секунд записи (ниже —
     * длиннее), между отсчётами — по прямой.
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
     * Начало записи прямого удара → зерно: отсчёты {@code samples} (−1..1, каналы вперемежку) частоты {@code rate}
     * в моно {@link #RATE} (среднее по окну — без наложения частот), первые {@code seconds} секунд с затуханием на
     * последней трети.
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
