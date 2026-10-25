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
 * Смесь взрывов ({@link Mix}): громкость в игре — половина децибел суммарного уровня, потолок — у суммы, доля каждого
 * взрыва — его доля мощности в жизни (Q_b = G·p_b²/Σp²). Залп «Града» в 30 блоках (сцена шлагбаума grad-day/grad-night)
 * из настоящих записей не выше потолка (в 3.5.x — на 4–5 дБ выше и срезы на полной шкале); ближний над сотней дальних
 * звучит как один.
 */
class MixTest {
    /** Огибающая, ровная {@code ticks} тиков, мощностью {@code power} против потолка при громкости 1. */
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
        // ракурсы одной записи — по амплитуде: два по половине громкости — одна запись целиком
        Mix mix = new Mix();
        Mix.Blast b = mix.blast(0);
        b.add(new Mix.Layer(flat(0.5, 40), 1, Math.sqrt(0.5)));
        b.add(new Mix.Layer(flat(0.5, 40), 1, Math.sqrt(0.5)));
        assertEquals(0.5 * (0.5 + 0.5 + 2 * 0.5), mix.heard(3), 1e-6);
        // удар в грудь — своя запись: с ракурсом — по мощности; взрыв с 1 psi (ракурс на потолке) — на потолке, а не на
        // 3 дБ ниже, как вышло бы по амплитуде (ревью F4)
        Mix one = new Mix();
        Mix.Blast c = one.blast(0);
        c.add(new Mix.Layer(flat(1, 40), 1, 1));
        c.add(new Mix.Layer(flat(0.8, 40), 1, 1, true));
        assertEquals(1, one.heard(3), 1e-6);
        assertEquals(1 / 1.8, c.factor() * c.factor(), 1e-6, "по мощности: 1 + 0,8, а не (1 + √0,8)²");
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
            // в жизни уровень n равных — +10·lg n, в игре — половина: мощность × √n, не выше потолка
            assertEquals(Math.min(1, p * Math.sqrt(n)), mix.heard(3), 1e-6, n + " взрывов");
            // у каждого множитель 1/√n, выше потолка — 1/(n·p)
            double k2 = Math.min(1 / Math.sqrt(n), 1 / (n * p));
            for (Mix.Blast b : all) assertEquals(k2, b.factor() * b.factor(), 1e-9, n + " взрывов");
        }
    }

    /**
     * Ближний взрыв на потолке и сто дальних по 0,01 (мощность в игре): ближний — в 0,5 дБ от себя одного в тишине (−0,04),
     * каждый дальний на 40 дБ ниже него, все дальние вместе — на 20 дБ (в жизни их сумма — 1 % его мощности); с общим
     * множителем 3.6 ближний терял 3 дБ, а дальние вместе звучали вровень с ним. Ближний стих — дальние возвращаются к
     * своему уровню, как сто равных.
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
        assertEquals(0, db(nearHeard / alone), 0.5, "ближний — как один в тишине");
        assertEquals(-0.043, db(nearHeard / alone), 0.01);
        assertEquals(40, db(nearHeard / farOne), 0.1, "каждый дальний — на жизненную разницу (0,01/1)²");
        assertEquals(20, db(nearHeard / farAll), 0.1, "все дальние вместе — 1 % мощности ближнего");
        assertEquals(1, mix.heard(), 1e-6, "сумма — потолок");
        // ближний стих: сто дальних — как сто равных, каждый с 1/√100
        mix.at(30);
        assertEquals(0.1, mix.heard(), 1e-6);
        assertEquals(0.1, far.get(0).factor() * far.get(0).factor(), 1e-6);
    }

    /** Сумма долей — G и не выше потолка, отношение долей — квадрат отношения мощностей (жизненная разница). */
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
     * Эхо — слои своего взрыва в смеси: когда прямой удар уже стих, эхо звучит своей долей, а не гаснет с ним (с
     * множителем от одного прямого удара у взрыва рядом оно ушло бы в ноль); один взрыв с эхом в тишине — как есть.
     */
    @Test
    void lateEchoKeepsItsOwnShare() {
        Mix mix = new Mix();
        Mix.Blast a = mix.blast(0);
        a.add(new Mix.Layer(flat(0.5, 20), 1, 1));
        a.add(Mix.Layer.own(flat(0.05, 20), flat(0.05, 20), 1, 30));
        mix.at(40);
        assertEquals(1, a.factor(), 1e-12, "один: эхо как есть");
        assertEquals(0.05, mix.heard(), 1e-6);
        Mix.Blast b = mix.blast(0);
        b.add(new Mix.Layer(flat(0.5, 100), 1, 1));
        mix.at(40);
        // эхо 0,05 против соседа 0,5 — на жизненную разницу (0,1)², 20 дБ
        assertEquals(20, db(b.heard() / a.heard()), 0.01);
        assertTrue(a.heard() > 0);
        // задержанный слой продлевает жизнь взрыва
        assertEquals(51, a.end - a.start, 1e-9);
    }

    /**
     * Дорожки эха, которые взяли свои источники, — слои своего взрыва со своей задержкой и громкостью; не взятые — нет; без
     * смеси (ядерка) — ничего.
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
        assertEquals(1, own.get(0).volume(), 1e-6, "громкость прямого удара × своя");
        assertEquals(55, own.get(1).delay(), 1e-12);
        assertEquals(16, own.get(1).volume(), 1e-6, "не громче AL_MAX_GAIN своего источника");
        mix.at(31);
        assertEquals(0.1 * 1 * 1, b.power(31) - 0, 1e-6, "через 31 тик звучит эхо");
        assertEquals(3, BlastEchoes.queue(done, env, 2, null, (t, gain) -> true), "без смеси — только голоса");
    }

    /** Слой с задержкой — от неё; за тик до начала — уже первый (голос, что ведёт кадр, начинается с долей тика раньше). */
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
        // звук на деле начался на 5 тиков позже — смесь от настоящего начала
        assertEquals(0, b.power(3), 1e-12);
        assertEquals(0.3, b.power(14), 1e-6);
        assertEquals(0, b.power(16.5), 1e-12);
    }

    /** Энергия голосов движка (по ней гул) — без своих источников и по профилю звучащей записи. */
    @Test
    void voicedEnergyIsTheEngineVoicesOnly() {
        Mix mix = new Mix();
        Mix.Blast b = mix.blast(0);
        b.add(new Mix.Layer(flat(0.5, 40), flat(0.2, 10), 1, 1, Mix.Kind.VIEW, 0));
        b.add(new Mix.Layer(flat(0.1, 40), flat(0.1, 40), 1, 1, Mix.Kind.APART, 0));
        b.add(Mix.Layer.own(flat(0.3, 40), flat(0.3, 40), 1, 0));
        assertEquals(0.5 + 0.1 + 0.3, b.power(5), 1e-6);
        assertEquals(0.2 + 0.1, b.voiced(5), 1e-6, "энергия ракурса и удара в грудь, без удара у уха");
        assertEquals(0.1, b.voiced(20), 1e-6, "запись ракурса кончилась на 10-м тике — энергии нет, хоть огибающая потолка и дольше");
    }

    @Test
    void endedBlastLeavesTheMix() {
        Mix mix = new Mix();
        Mix.Blast a = mix.blast(0);
        a.add(new Mix.Layer(flat(2, 20), 1, 1));
        mix.at(10);
        assertTrue(a.factor() < 1);
        // запись кончилась: следующий взрыв звучит так, будто первого не было
        Mix.Blast b = mix.blast(30);
        b.add(new Mix.Layer(flat(0.5, 20), 1, 1));
        mix.at(31);
        assertEquals(1, b.factor(), 1e-12);
        assertEquals(1, mix.size());
        // тон 2 — запись вдвое короче
        Mix fast = new Mix();
        Mix.Blast c = fast.blast(0);
        c.add(new Mix.Layer(flat(2, 20), 2, 1));
        Mix.Blast d = fast.blast(11);
        d.add(new Mix.Layer(flat(0.5, 20), 1, 1));
        fast.at(11);
        assertEquals(1, d.factor(), 1e-12);
    }

    /**
     * Залп сцены шлагбаума: 40 разрывов «Града» (6,4 кг) с разбросом 15 блоков вокруг точки в 30 блоках от зрителя,
     * приход — за 13 с (как в прогоне RV1). Записи ракурсов — настоящие файлы, тон по Саксу, громкость по давлению
     * ({@link BlastMix#at}), множитель каждого взрыва — раз в тик, как у движка. Сумма — в K-взвешенной мгновенной громкости
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
        assertTrue(unmixed > Loudness.CEILING_LUFS + 2, "без смеси залп выше потолка: " + unmixed);
        assertTrue(top <= Loudness.CEILING_LUFS + 1, "залп в смеси — у потолка " + Loudness.CEILING_LUFS + " LUFS: " + top);
        // и не упирается в полную шкалу суммой (в прогоне RV1 — ~20 срезов за сцену, без смеси здесь — тысячи): пик выше
        // полной шкалы — только в тик удара (первые 50 мс), где пики ближнего ракурса и удара в грудь одного взрыва совпадают (оба
        // с удара с первого отсчёта; так срезал и одиночный взрыв в 50 м у 3.4.1) — это ловит ограничитель OpenAL Soft
        int impact = tickLen, tails = 0;
        for (int i = 0; i < n; i++) {
            if (Math.abs(mixed[i]) < 1) continue;
            boolean onImpact = false;
            for (Mix.Blast b : mb) if (b != null && i >= b.start * tickLen && i < b.start * tickLen + impact) onImpact = true;
            if (!onImpact) tails++;
        }
        assertEquals(0, tails, "отсчётов на полной шкале вне удара, пик " + peak(mixed));
        assertTrue(over(mixed) * 100 < over(raw), "срезов " + over(mixed) + " против " + over(raw) + " без смеси");
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

    /** Варианты записи {@code name}_1…_count, все на частоте {@code rate}. */
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

    /** Огибающая события — наибольшая по вариантам, как у {@link Envelopes}. */
    static float[] envelope(float[][] variants, int rate) {
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
