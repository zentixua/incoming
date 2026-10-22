package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;

import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Смесь взрывов ({@link Mix}): громкость в игре — половина децибел суммарного уровня, потолок — у суммы. Залп «Града» в
 * 30 блоках (сцена шлагбаума grad-day/grad-night) из настоящих записей не выше потолка (в 3.5.x — на 4–5 дБ выше и
 * срезы на полной шкале).
 */
class MixTest {
    /** Огибающая, ровная {@code ticks} тиков, мощностью {@code power} против потолка при громкости 1. */
    private static float[] flat(double power, int ticks) {
        float[] e = new float[ticks];
        java.util.Arrays.fill(e, (float) (power * Mix.CEILING));
        return e;
    }

    @Test
    void oneBlastUnderTheCeilingIsUntouched() {
        Mix mix = new Mix();
        mix.blast(0).add(new Mix.Layer(flat(0.3, 40), 1, 1));
        assertEquals(1, mix.factor(5), 1e-12);
        assertEquals(0.3, mix.heard(5), 1e-6);
    }

    @Test
    void layersOfOneBlastAddUpToTheCeiling() {
        // ближний ракурс на потолке и удар в грудь — одна волна: вместе не громче потолка
        Mix mix = new Mix();
        Mix.Blast b = mix.blast(0);
        b.add(new Mix.Layer(flat(1, 40), 1, 1));
        b.add(new Mix.Layer(flat(0.5, 40), 1, 1));
        assertEquals(1, mix.heard(3), 1e-6);
    }

    @Test
    void equalBlastsAtOnceAddInHalfDecibels() {
        for (int n = 1; n <= 40; n++) {
            Mix mix = new Mix();
            double p = 0.05;
            for (int i = 0; i < n; i++) mix.blast(0).add(new Mix.Layer(flat(p, 40), 1, 1));
            // в жизни уровень n равных — +10·lg n, в игре — половина: мощность × √n, не выше потолка
            assertEquals(Math.min(1, p * Math.sqrt(n)), mix.heard(3), 1e-6, n + " взрывов");
        }
    }

    @Test
    void endedBlastLeavesTheMix() {
        Mix mix = new Mix();
        mix.blast(0).add(new Mix.Layer(flat(2, 20), 1, 1));
        assertTrue(mix.factor(10) < 1);
        // запись кончилась: следующий взрыв звучит так, будто первого не было
        mix.blast(30).add(new Mix.Layer(flat(0.5, 20), 1, 1));
        assertEquals(1, mix.factor(31), 1e-12);
        // тон 2 — запись вдвое короче
        Mix fast = new Mix();
        fast.blast(0).add(new Mix.Layer(flat(2, 20), 2, 1));
        assertEquals(1, fast.factor(11), 1e-12);
    }

    /**
     * Залп сцены шлагбаума: 40 разрывов «Града» (6,4 кг) с разбросом 15 блоков вокруг точки в 30 блоках от зрителя,
     * приход — за 13 с (как в прогоне RV1). Записи ракурсов — настоящие файлы, тон по Саксу, громкость по давлению
     * ({@link BlastMix#at}), множитель смеси — раз в тик, как у движка. Сумма — в K-взвешенной мгновенной громкости
     * не выше потолка (с допуском на шаг тика), а без смеси была бы выше на дБ.
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
        // пары разрывов в один тик — бывают в залпе
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
                if (l.sub() > 0) mb[next].add(new Mix.Layer(eSub, pitch, l.sub()));
                next++;
            }
            double k = mix.factor(tick);
            for (int i = 0; i < next; i++) {
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
        System.out.printf("salvo: mixed %.1f LUFS, unmixed %.1f LUFS, peak %.2f, over full scale %d samples%n", top, unmixed, peak(mixed), over(mixed));
        assertTrue(unmixed > Loudness.CEILING_LUFS + 2, "без смеси залп выше потолка: " + unmixed);
        assertTrue(top <= Loudness.CEILING_LUFS + 1, "залп в смеси — у потолка " + Loudness.CEILING_LUFS + " LUFS: " + top);
        // и не упирается в полную шкалу (в прогоне RV1 — ~20 срезов за сцену)
        assertEquals(0, over(mixed), "отсчётов на полной шкале, пик " + peak(mixed));
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

    private static double sample(float[] x, double at) {
        if (at < 0) return 0;
        int i = (int) at;
        if (i + 1 >= x.length) return 0;
        double f = at - i;
        return x[i] + (x[i + 1] - x[i]) * f;
    }

    /** Варианты записи {@code name}_1…_count, все на частоте {@code rate}. */
    private static float[][] load(String name, int count, int rate) throws IOException {
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

    /** Огибающая события — наибольшая по вариантам, как у {@link Envelopes}. */
    private static float[] envelope(float[][] variants, int rate) {
        float[] max = new float[0];
        for (float[] v : variants) max = Envelopes.max(max, Envelope.of(v, rate));
        return max;
    }

    /** Наибольшая K-взвешенная мгновенная громкость (окно 400 мс через 100 мс), LUFS. */
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
