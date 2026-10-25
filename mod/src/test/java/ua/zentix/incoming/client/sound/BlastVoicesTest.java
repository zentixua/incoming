package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.nuclear.model.HearingModel;
import ua.zentix.incoming.strike.WeaponType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Голоса взрывов ({@link BlastVoices}): разовых звуков взрывов в каналах не больше потолка из подсчёта каналов, ближний
 * голос получает всегда, уступивший — в гул ({@link Rumble}) с той же мощностью.
 */
class BlastVoicesTest {
    /** Разовый звук без движка: звучит до {@code end}, уступил — стихает {@link BlastVoices#FADE} тика. */
    static final class Fake implements BlastVoices.Sound {
        final double[] clock;
        final double end;
        double fadeFrom = Double.NaN;
        boolean cut;

        Fake(double[] clock, double end) {
            this.clock = clock;
            this.end = end;
        }

        @Override
        public void fade() {
            if (Double.isNaN(fadeFrom)) fadeFrom = clock[0];
        }

        @Override
        public void cut() {
            cut = true;
        }

        @Override
        public boolean over() {
            return cut || clock[0] >= end || !Double.isNaN(fadeFrom) && clock[0] - fadeFrom >= BlastVoices.FADE;
        }
    }

    private static List<Fake> fakes(double[] clock, int n, double end) {
        List<Fake> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Fake(clock, end));
        return out;
    }

    /** Взрыв ровной мощности {@code p} на {@code ticks} тиков с тика {@code at}. */
    private static Mix.Blast flat(Mix mix, double at, double p, int ticks) {
        Mix.Blast b = mix.blast(at);
        b.add(new Mix.Layer(MixTest.flat(p, ticks), 1, 1));
        return b;
    }

    /**
     * Потолок — из подсчёта каналов: у OpenAL Soft 255 моно и 1 стерео, у движка 247 статических и 8 потоковых, рядом 12
     * своих — статических на деле 236; минус моторы 48, гул 4, ядерка 8, мир 64 и запас на стихающие 12 — 100. Без сведений
     * от устройства движок берёт 30 моно — 25 статических, и взрывам остаётся наименьшее.
     */
    @Test
    void ceilingFromTheChannelCount() {
        assertEquals(236, BlastVoices.reach(255, 1));
        assertEquals(100, BlastVoices.CAP);
        assertEquals(BlastVoices.CAP, BlastVoices.cap(BlastVoices.MONO, BlastVoices.STEREO));
        assertEquals(4, BlastVoices.cap(30, 0));
        assertEquals(236 - VoiceBudget.CAP - Rumble.QUADRANTS - BlastVoices.NUKE - BlastVoices.WORLD - BlastVoices.HEADROOM, BlastVoices.CAP);
    }

    @Test
    void quietNewGoesToTheRumbleWhenFull() {
        double[] clock = {0};
        Mix mix = new Mix();
        Rumble rumble = new Rumble();
        BlastVoices voices = new BlastVoices(rumble, 8);
        for (int i = 0; i < 2; i++) assertNotNull(voices.admit(flat(mix, 0, 0.5, 200), fakes(clock, 4, 200), 0, 0, 1, 1, 1, Integer.MAX_VALUE));
        clock[0] = 20;
        mix.at(20);
        Mix.Blast quiet = flat(mix, 20, 0.001, 100);
        assertNull(voices.admit(quiet, fakes(clock, 1, 120), 20, 1, 1, 1, 1, Integer.MAX_VALUE), "тише всех — сразу в гул");
        assertEquals(1, rumble.size());
        rumble.tick(20);
        assertEquals(quiet.heard(), rumble.total(), 1e-12, "в гуле — с той же мощностью, сразу весь");
        assertEquals(8, voices.steady());
    }

    /** Новый громче — уступает самый тихий хвост: его звуки стихают, мощность — в гул; атаку новый вытесняет, только если громче вдвое. */
    @Test
    void louderNewTakesTheQuietestTailAndSparesAttacks() {
        double[] clock = {0};
        Mix mix = new Mix();
        Rumble rumble = new Rumble();
        BlastVoices voices = new BlastVoices(rumble, 8);
        List<Fake> loud = fakes(clock, 4, 300), quiet = fakes(clock, 4, 300);
        assertNotNull(voices.admit(flat(mix, 0, 0.3, 300), loud, 0, 0, 1, 1, 1, Integer.MAX_VALUE));
        Mix.Blast tail = flat(mix, 0, 0.05, 300);
        assertNotNull(voices.admit(tail, quiet, 0, 0, 1, 1, 1, Integer.MAX_VALUE));
        clock[0] = 30;
        mix.at(30);
        BlastVoices.Voice v = voices.admit(flat(mix, 30, 0.2, 100), fakes(clock, 4, 130), 30, 0, 1, 1, 1, Integer.MAX_VALUE);
        assertNotNull(v);
        assertTrue(quiet.stream().allMatch(f -> !Double.isNaN(f.fadeFrom)), "тихий хвост стихает");
        assertTrue(loud.stream().allMatch(f -> Double.isNaN(f.fadeFrom)), "громкий звучит");
        assertEquals(8, voices.steady());
        assertEquals(12, voices.channels(), "стихающий держит каналы ещё FADE тиков");
        assertEquals(1, voices.yielded);
        // новый громче всех — голос всегда: уступает тише всех по приоритету (атака v — с запасом KEEP)
        clock[0] = 31;
        mix.at(31);
        voices.prune(31);
        Mix.Blast loudest = flat(mix, 31, 1, 50);
        assertNotNull(voices.admit(loudest, fakes(clock, 4, 81), 31, 0, 1, 1, 1, Integer.MAX_VALUE));
        assertEquals(8, voices.steady());
    }

    /** Атаку новый, громче её меньше чем вдвое, не вытесняет: идёт в гул. */
    @Test
    void attackIsKeptFromASlightlyLouderNew() {
        double[] clock = {0};
        Mix mix = new Mix();
        Rumble rumble = new Rumble();
        BlastVoices voices = new BlastVoices(rumble, 4);
        Mix.Blast a = flat(mix, 0, 0.1, 100);
        assertNotNull(voices.admit(a, fakes(clock, 4, 100), 0, 0, 1, 1, 1, Integer.MAX_VALUE));
        clock[0] = 2;
        Mix.Blast b = flat(mix, 2, 0.13, 100);
        mix.at(2);
        assertTrue(b.heard() > a.heard() && b.heard() < BlastVoices.KEEP * a.heard());
        assertNull(voices.admit(b, fakes(clock, 4, 102), 2, 0, 1, 1, 1, Integer.MAX_VALUE));
        // после атаки — уступает тому же
        clock[0] = 2 + BlastVoices.ATTACK;
        mix.at(clock[0]);
        Mix.Blast c = flat(mix, clock[0], 0.13, 100);
        assertTrue(c.heard() > a.heard());
        assertNotNull(voices.admit(c, fakes(clock, 4, 200), clock[0], 0, 1, 1, 1, Integer.MAX_VALUE));
    }

    /** Движок взял не все звуки голоса — голос уступает, взрыв в гуле. */
    @Test
    void refusedVoiceGoesToTheRumble() {
        double[] clock = {0};
        Mix mix = new Mix();
        Rumble rumble = new Rumble();
        BlastVoices voices = new BlastVoices(rumble);
        BlastVoices.Voice v = voices.admit(flat(mix, 0, 0.2, 100), fakes(clock, 3, 100), 0, 0, 1, 1, 1, Integer.MAX_VALUE);
        assertNotNull(v);
        voices.started(v, 2, 0);
        assertTrue(v.fading());
        assertEquals(1, rumble.size());
        assertEquals(2, voices.started);
        assertEquals(1, voices.refused);
    }

    /** Стихающие сверх запаса или сверх свободных каналов движка замолкают сразу; места нет и так — новый в гул. */
    @Test
    void hardLimitCutsTheFadingFirst() {
        double[] clock = {0};
        Mix mix = new Mix();
        Rumble rumble = new Rumble();
        BlastVoices voices = new BlastVoices(rumble, 8);
        List<Fake> all = new ArrayList<>();
        // в одном тике — ещё и ещё громче: уступившие копятся стихающими
        for (int i = 0; i < 12; i++) {
            List<Fake> f = fakes(clock, 4, 200);
            all.addAll(f);
            assertNotNull(voices.admit(flat(mix, 0, 0.01 * Math.pow(1.5, i), 200), f, 0, 0, 1, 1, 1, Integer.MAX_VALUE));
            assertTrue(voices.steady() <= 8);
            assertTrue(voices.channels() <= 8 + BlastVoices.HEADROOM, "каналов " + voices.channels());
        }
        assertTrue(all.stream().anyMatch(f -> f.cut), "сверх запаса — замолкли");
        // свободных у движка мало: новому не хватает — в гул, и ближний не теряется — он в гуле
        BlastVoices tight = new BlastVoices(new Rumble(), 100);
        assertNull(tight.admit(flat(mix, 0, 1, 50), fakes(clock, 4, 50), 0, 0, 1, 1, 1, BlastVoices.SPARE + 2));
        assertEquals(1, tight.toRumble);
    }

    /**
     * Налёт: сто шахедов вокруг по кругу 30–2000 блоков за 40 с, ближний — в 40 блоках на 10-й секунде; записи ракурсов —
     * настоящие файлы. Каждый тик: голосов не больше потолка, мощность голосов и гула — ровно доли взрывов в смеси
     * (уступивший — в гул с той же мощностью); ближний получает голос и звучит в 0,5 дБ от себя одного в тишине. Так — и с
     * потолком 100, и в тесноте (потолок 24).
     */
    @Test
    void raidStaysUnderTheCeilingAndTheNearKeepsItsVoice() throws IOException {
        int rate = 44100;
        float[][] near = MixTest.load("blast_near", 6, rate), mid = MixTest.load("blast_mid", 6, rate), far = MixTest.load("blast_far", 6, rate),
                sub = MixTest.load("blast_sub", 2, rate);
        float[][][] env = {envelopes(near, rate), envelopes(mid, rate), envelopes(far, rate), envelopes(sub, rate)};
        float[][] max = {MixTest.envelope(near, rate), MixTest.envelope(mid, rate), MixTest.envelope(far, rate), MixTest.envelope(sub, rate)};
        for (int cap : new int[]{BlastVoices.CAP, 24}) raid(cap, env, max);
    }

    private static float[][] envelopes(float[][] variants, int rate) {
        float[][] out = new float[variants.length][];
        for (int i = 0; i < variants.length; i++) out[i] = Envelope.energy(variants[i], rate);
        return out;
    }

    private static void raid(int cap, float[][][] energy, float[][] max) {
        BlastSource drone = BlastSource.of(WeaponType.DRONE, false);
        double pitch = BlastMix.pitch(drone.chargeKg()), r0 = Math.min(32_000, HearingModel.audibleRangeM(drone.chargeKg()));
        Random r = new Random(25_10_2026L);
        int n = 100, nearAt = 200;
        double[] at = new double[n], dist = new double[n], az = new double[n];
        for (int i = 0; i < n; i++) {
            at[i] = 20 * 40 * r.nextDouble();
            dist[i] = 30 + 1970 * r.nextDouble();
            az[i] = 2 * Math.PI * r.nextDouble() - Math.PI;
        }
        double[] clock = {0};
        Mix mix = new Mix();
        Rumble rumble = new Rumble();
        BlastVoices voices = new BlastVoices(rumble, cap);
        List<Mix.Blast> all = new ArrayList<>();
        Mix.Blast nearBlast = null;
        double nearAlone = 0, nearHeard = Double.NaN;
        int most = 0;
        for (int tick = 0; tick < 60 * 20; tick++) {
            clock[0] = tick;
            mix.at(tick);
            voices.prune(tick);
            List<Integer> now = new ArrayList<>();
            for (int i = 0; i < n; i++) if ((int) at[i] == tick) now.add(i);
            if (tick == nearAt) now.add(-1);
            for (int i : now) {
                double d = i < 0 ? 40 : dist[i];
                int variant = r.nextInt(6);
                Mix.Blast b = mix.blast(tick);
                List<Fake> sounds = new ArrayList<>();
                if (d < Outdoor.NEAR) {
                    BlastMix.Layers l = BlastMix.at(drone, d);
                    double[] v = {l.near(), l.mid(), l.far(), l.sub()};
                    for (int k = 0; k < 4; k++) {
                        if (v[k] < 0.005) continue;
                        float[] e = energy[k][k == 3 ? variant % 2 : variant];
                        Mix.Layer layer = new Mix.Layer(max[k], e, pitch, v[k], k == 3 ? Mix.Kind.APART : Mix.Kind.VIEW, 0);
                        b.add(layer);
                        sounds.add(new Fake(clock, tick + layer.ticks() + 20));
                    }
                } else {
                    float v = Outdoor.hear(r0, BlastMix.v640(drone), new Outdoor.Path(d, 0, 1, 1.5, 0.5, 0.5, 0, 0, 0)).volume();
                    if (v < 0.005) continue;
                    Mix.Layer layer = new Mix.Layer(max[2], energy[2][variant], pitch, v, Mix.Kind.APART, 0);
                    b.add(layer);
                    sounds.add(new Fake(clock, tick + layer.ticks() + 20));
                }
                all.add(b);
                if (i < 0) {
                    nearBlast = b;
                    nearAlone = Math.min(1, b.power(tick));
                }
                BlastVoices.Voice voice = voices.admit(b, sounds, tick, i < 0 ? 0 : az[i], (float) pitch, 1, 1, Integer.MAX_VALUE);
                if (voice != null) voices.started(voice, sounds.size(), tick);
                if (i < 0) assertNotNull(voice, "ближний — с голосом (потолок " + cap + ")");
            }
            rumble.tick(tick);
            assertTrue(voices.steady() <= cap, "голосов " + voices.steady() + " при потолке " + cap);
            assertTrue(voices.channels() <= cap + BlastVoices.HEADROOM);
            most = Math.max(most, voices.steady());
            // мощность голосов и гула — доли взрывов в смеси: ничего не потеряно и не удвоено
            double exact = 0, heard = rumble.total();
            for (Mix.Blast b : all) {
                double k = b.factor();
                exact += k * k * b.voiced(tick);
            }
            for (BlastVoices.Voice v : voices.voices()) {
                double f = v.fading() ? Rumble.voice(tick, v.fadeFrom, BlastVoices.FADE) : 1, k = v.blast.factor();
                if (v.sounds.stream().noneMatch(s -> ((Fake) s).cut)) heard += f * f * k * k * v.blast.voiced(tick);
            }
            assertEquals(exact, heard, 1e-9 + 1e-9 * exact, "тик " + tick + ": голоса + гул против долей в смеси");
            if (tick == nearAt + 2) nearHeard = nearBlast.heard();
        }
        double off = 10 * Math.log10(nearHeard / nearAlone);
        System.out.printf("raid cap %d: most voices %d, to rumble %d, yielded %d, near %.2f dB vs alone%n", cap, most, voices.toRumble, voices.yielded, off);
        assertEquals(0, off, 0.5, "ближний над кашей — как один в тишине");
    }
}
