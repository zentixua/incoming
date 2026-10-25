package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Гул взрывов без голоса ({@link Rumble}): сумма по мощности в четвертях вокруг уха, доля каждого члена — его доля в
 * смеси × энергия его записи, переход голос → гул без скачка суммы, профиль энергии записи — её настоящий срок.
 */
class RumbleTest {
    private static float[] flat(double power, int ticks) {
        return MixTest.flat(power, ticks);
    }

    /** Взрыв в смеси {@code mix} мощностью {@code p} на {@code ticks} тиков (энергия записи — та же). */
    private static Mix.Blast blast(Mix mix, double p, int ticks) {
        Mix.Blast b = mix.blast(0);
        b.add(new Mix.Layer(flat(p, ticks), 1, 1));
        return b;
    }

    @Test
    void quarterByDirection() {
        Mix mix = new Mix();
        Rumble r = new Rumble();
        r.add(blast(mix, 0.01, 40), 0, 1, 1, 1, 0, 0);
        mix.at(1);
        r.tick(1);
        // на оси петли — вся мощность в ней
        assertEquals(0.01, r.power(0), 1e-8);
        assertEquals(0, r.power(1) + r.power(2) + r.power(3), 1e-12);
        // между двумя петлями — поровну (cos²/sin²), сумма та же
        Mix mix2 = new Mix();
        Rumble r2 = new Rumble();
        r2.add(blast(mix2, 0.01, 40), Math.PI / 4, 1, 1, 1, 0, 0);
        r2.add(blast(mix2, 0.01, 40), -Math.PI / 2, 1, 1, 1, 0, 0);
        mix2.at(1);
        r2.tick(1);
        double each = mix2.heard() / 2;
        assertEquals(each / 2, r2.power(0), 1e-8);
        assertEquals(each / 2, r2.power(1), 1e-8);
        assertEquals(each, r2.power(3), 1e-8, "−90° (север, −z) — петля 3");
        assertEquals(mix2.heard(), r2.total(), 1e-8);
    }

    /**
     * Как {@code FireRoarTest}: сумма петель против точной суммы членов — в 0,5 дБ (здесь точно: раздел по направлению
     * сохраняет мощность), гул — та же доля в смеси, что звучала бы голосами; за преградой — тише на её долю.
     */
    @Test
    void matchesTheExactSum() {
        Random rnd = new Random(7);
        Mix mix = new Mix();
        Rumble r = new Rumble();
        double exact = 0;
        Mix.Blast[] all = new Mix.Blast[80];
        float[] gain = new float[all.length];
        for (int i = 0; i < all.length; i++) {
            all[i] = blast(mix, Math.pow(10, -3 * rnd.nextDouble()), 100);
            gain[i] = rnd.nextBoolean() ? 1 : 0.3f;
            r.add(all[i], 2 * Math.PI * rnd.nextDouble() - Math.PI, 1, gain[i], 1, 0, 0);
        }
        mix.at(10);
        r.tick(10);
        for (int i = 0; i < all.length; i++) exact += gain[i] * all[i].heard();
        System.out.printf("rumble: %d members, loops %.2f %.2f %.2f %.2f dB vs ceiling, sum vs exact %.4f dB%n", all.length,
                10 * Math.log10(r.power(0)), 10 * Math.log10(r.power(1)), 10 * Math.log10(r.power(2)), 10 * Math.log10(r.power(3)),
                10 * Math.log10(r.total() / exact));
        assertEquals(0, 10 * Math.log10(r.total() / exact), 0.5);
        assertEquals(exact, r.total(), 1e-9 * exact);
    }

    /** Голос стихает за {@link BlastVoices#FADE} тика, член растёт так же: мощность голоса f² + гула 1 − f² — та же. */
    @Test
    void handoverKeepsThePower() {
        Mix mix = new Mix();
        Rumble r = new Rumble();
        Mix.Blast b = blast(mix, 0.2, 100);
        Rumble.Member m = r.add(b, 0, 1, 1, 1, 10, BlastVoices.FADE);
        for (double t = 10; t <= 10 + BlastVoices.FADE + 1; t += 0.5) {
            mix.at(t);
            r.tick(t);
            double f = Rumble.voice(t, 10, BlastVoices.FADE), voice = f * f * b.heard();
            assertEquals(b.heard(), voice + r.total(), 1e-12, "тик " + t);
        }
        assertEquals(1, m.weight(10 + BlastVoices.FADE), 1e-12);
        m.settle(20);
        assertEquals(1, m.weight(20), 1e-12, "замолк сразу — весь в гуле");
    }

    /** Громкость петли — корень мощности против потолка у файла на потолке; тон и верха — средние по мощности. */
    @Test
    void loopVolumePitchAndHighs() {
        assertEquals(1, Rumble.volume(1, Loudness.CEILING_LUFS), 1e-6);
        assertEquals(0.1, Rumble.volume(0.01, Loudness.CEILING_LUFS), 1e-6);
        assertEquals(1, Rumble.volume(4, Loudness.CEILING_LUFS), 1e-6, "громче файла Minecraft не играет");
        assertEquals(0.1 * Math.pow(10, 6 / 20.0), Rumble.volume(0.01, Loudness.CEILING_LUFS - 6), 1e-6);
        Mix mix = new Mix();
        Rumble r = new Rumble();
        r.add(blast(mix, 0.03, 40), 0, 2, 1, 0.4f, 0, 0);
        r.add(blast(mix, 0.01, 40), 0, 1, 1, 1, 0, 0);
        mix.at(1);
        r.tick(1);
        double w2 = 0.03 * 0.03, w1 = 0.01 * 0.01;
        assertEquals((2 * w2 + w1) / (w1 + w2), r.pitch(0), 1e-5);
        assertEquals((0.4 * w2 + w1) / (w1 + w2), r.highs(0), 1e-5);
        assertEquals(1, r.pitch(1), 1e-12, "пустая петля — тон 1");
    }

    /** Кончился взрыв — ушёл и из гула; гул без членов молчит. */
    @Test
    void endedMembersLeave() {
        Mix mix = new Mix();
        Rumble r = new Rumble();
        r.add(blast(mix, 0.1, 20), 1, 1, 1, 1, 0, 0);
        mix.at(5);
        r.tick(5);
        assertTrue(r.total() > 0);
        mix.at(30);
        r.tick(30);
        assertEquals(0, r.size());
        assertEquals(0, r.total(), 0);
    }

    /**
     * Профиль энергии записи ({@link Envelope#energy}) — её настоящие энергия и срок: сумма по тикам × шаг — энергия всей
     * записи, удара нет до того, как он прозвучал; огибающая потолка ({@link Envelope#of}) поднимается за 350 мс до удара,
     * а у события — по самому долгому варианту: для гула это дало бы рост до удара и чужой хвост.
     */
    @Test
    void energyProfileIsTheRecordingsOwn() {
        int rate = 44100;
        float[] x = new float[2 * rate];
        Random rnd = new Random(1);
        // тишина, удар 50 мс на первой секунде, тишина
        for (int i = rate; i < rate + rate / 20; i++) x[i] = (float) rnd.nextGaussian() * 0.5f;
        float[] e = Envelope.energy(x, rate), max = Envelope.of(x, rate);
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[i];
        Envelope.kWeight(y, rate);
        double total = 0;
        for (double v : y) total += v * v;
        double sum = 0;
        for (float v : e) sum += v * Envelope.STEP * rate;
        assertEquals(total, sum, 1e-3 * total, "энергия записи");
        int before = 20 - 4;
        assertEquals(0, e[before], 1e-12, "до удара — тишина");
        assertTrue(max[before] > 0, "огибающая потолка — уже с ударом");
        assertTrue(e[20] > 0);
        // вариант вдвое длиннее: у огибающей события его хвост
        float[] longer = new float[4 * rate];
        System.arraycopy(x, 0, longer, 0, x.length);
        for (int i = 2 * rate; i < longer.length; i++) longer[i] = (float) rnd.nextGaussian() * 0.05f;
        float[] event = Envelopes.max(max, Envelope.of(longer, rate));
        assertTrue(event.length > 1.9 * max.length && event[max.length + 5] > 0, "огибающая события — по самому долгому варианту");
    }
}
