package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.nuclear.model.CraterModel;
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
 * Голоса взрывов ({@link BlastVoices}): разовых звуков взрывов в каналах движка не больше потолка из подсчёта каналов —
 * считая и те, что уже остановлены, пока движок их не вернул; самый громкий голос получает (с границами защиты атаки и
 * свободных каналов); уступивший и невзятый — в гул ({@link Rumble}) с той же мощностью.
 */
class BlastVoicesTest {
    /**
     * Разовый звук без движка, с жизнью канала как у движка: звучит до {@code end}; уступил — тик ещё полный, потом стихает
     * {@link BlastVoices#FADE} тика и останавливается; остановленный держит канал ещё тик — движок вернёт его пулу в своём
     * следующем тике ({@code SoundEngine.tickNonPaused}, {@code ChannelAccess.scheduleTick}); движок не взял — канала нет.
     */
    static final class Fake implements BlastVoices.Sound {
        final double[] clock;
        final double end;
        double fadeFrom = Double.NaN, cutAt = Double.NaN;
        boolean taken = true;

        Fake(double[] clock, double end) {
            this.clock = clock;
            this.end = end;
        }

        double stopAt() {
            double at = end;
            if (!Double.isNaN(fadeFrom)) at = Math.min(at, fadeFrom + BlastVoices.FADE + 1);
            if (!Double.isNaN(cutAt)) at = Math.min(at, cutAt);
            return at;
        }

        @Override
        public void fade() {
            if (Double.isNaN(fadeFrom)) fadeFrom = clock[0];
        }

        @Override
        public void cut() {
            if (Double.isNaN(cutAt)) cutAt = clock[0];
        }

        @Override
        public boolean over() {
            return !taken || clock[0] >= stopAt();
        }

        @Override
        public boolean held() {
            return taken && clock[0] < stopAt() + 1;
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
     * своих — статических на деле 236; минус моторы 48, гул взрывов 4, гул пожаров 4, ядерка 8, мир 64 и запас на
     * стихающие 12 — 96. Без сведений от устройства движок берёт 30 моно — 25 статических, и взрывам остаётся наименьшее.
     */
    @Test
    void ceilingFromTheChannelCount() {
        assertEquals(236, BlastVoices.reach(255, 1));
        assertEquals(96, BlastVoices.CAP);
        assertEquals(BlastVoices.CAP, BlastVoices.cap(BlastVoices.MONO, BlastVoices.STEREO));
        assertEquals(4, BlastVoices.cap(30, 0));
        assertEquals(236 - VoiceBudget.CAP - Rumble.QUADRANTS - Quarters.N - BlastVoices.NUKE - BlastVoices.WORLD - BlastVoices.HEADROOM,
                BlastVoices.CAP);
    }

    /**
     * Мир съел запас свободных каналов: самые тихие свои уступают заранее, пока свободных (с теми, что вот-вот вернутся) не
     * станет запас на ближний; запас есть — никто не уступает.
     */
    @Test
    void reserveIsKeptByTheQuietestAhead() {
        double[] clock = {0};
        Mix mix = new Mix();
        BlastVoices voices = new BlastVoices(new Rumble(), 100);
        List<List<Fake>> all = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            List<Fake> f = fakes(clock, 4, 300);
            all.add(f);
            assertNotNull(voices.admit(flat(mix, 0, 0.01 * (i + 1), 300), f, 0, 0, 1, 1, 1, 1000));
        }
        clock[0] = 20;
        mix.at(20);
        assertEquals(0, voices.reserve(20, BlastVoices.SPARE + BlastVoices.GROUP));
        assertEquals(2, voices.reserve(20, 5), "свободно 5, нужно 12: уступают двое тише всех");
        assertTrue(all.get(0).get(0).fadeFrom == 20 && all.get(1).get(0).fadeFrom == 20 && Double.isNaN(all.get(2).get(0).fadeFrom));
        assertEquals(0, voices.reserve(20, 5), "уступившие вот-вот вернут — больше не нужно");
    }

    /** Пул движка без движка: столько статических каналов, сколько даст OpenAL; канал занят, пока звук его держит. */
    static final class Pool {
        final int reach;
        int fixed, foreign;
        final List<Fake> held = new ArrayList<>();
        int refused;

        Pool(int reach, int fixed) {
            this.reach = reach;
            this.fixed = fixed;
        }

        int used() {
            held.removeIf(f -> !f.held());
            return fixed + foreign + held.size();
        }

        int free() {
            return reach - used();
        }

        /** Звук просит канал у движка: нет места — движок молча не играет. */
        boolean take(Fake f) {
            if (used() >= reach) {
                f.taken = false;
                refused++;
                return false;
            }
            held.add(f);
            return true;
        }
    }

    /**
     * Сцена перегрузки WAV-прогона (Sol, 25.10): 299 шахедов разом на 9-й секунде в 30–200 блоках, через секунду — осыпь
     * выброса каждой воронки (разовый звук взрыва, в бюджете), ближний — в 40 блоках на 10-й секунде; пул движка — 236
     * каналов, гул взрывов и пожаров держат 8. Ближний получает голос, движок берёт все его звуки, и ни одному нашему звуку
     * движок не отказывает; так же, когда к приходу ближнего чужие звуки забили весь пул: запас на ближний самые тихие свои
     * отдали заранее. В 33a23c16 ближний уходил в гул: пул держали ~300 петель гула пожаров и осыпь, мимо бюджета.
     */
    @Test
    void stressSceneOf300KeepsTheNearVoice() throws IOException {
        int rate = 44100;
        float[][] near = MixTest.load("blast_near", 6, rate), mid = MixTest.load("blast_mid", 6, rate), far = MixTest.load("blast_far", 6, rate),
                sub = MixTest.load("blast_sub", 2, rate), debris = MixTest.load("debris_fall", 4, rate);
        float[][][] energy = {envelopes(near, rate), envelopes(mid, rate), envelopes(far, rate), envelopes(sub, rate), envelopes(debris, rate)};
        float[][] max = {MixTest.envelope(near, rate), MixTest.envelope(mid, rate), MixTest.envelope(far, rate), MixTest.envelope(sub, rate),
                MixTest.envelope(debris, rate)};
        for (boolean crowded : new boolean[]{false, true}) stress(energy, max, crowded);
    }

    private static void stress(float[][][] energy, float[][] max, boolean crowded) {
        BlastSource drone = BlastSource.of(WeaponType.DRONE, false);
        double pitch = BlastMix.pitch(drone.chargeKg());
        double kg = CraterModel.chargeVolume(drone.chargeKg(), CraterModel.Soil.DRY) * CloudModel.SOIL_DENSITY * (1 - CloudModel.DUST_LOFTED);
        Sources.Source ejecta = Sources.EJECTA.power(Sources.fall(kg, 2 * CraterModel.chargeRadius(drone.chargeKg(), CraterModel.Soil.DRY), 2,
                ua.zentix.incoming.sound.Spectra.DEBRIS_FALL));
        Vec3 ear = new Vec3(0.5, 66.62, 0.5);
        Random r = new Random(20261025L);
        int n = 300, nearId = 299;
        double[] dist = new double[n], az = new double[n];
        for (int i = 0; i < n; i++) {
            dist[i] = i == nearId ? 40 : 30 * Math.pow(200 / 30.0, i / 298.0);
            az[i] = i == nearId ? 0 : 2 * Math.PI * r.nextDouble();
        }
        double[] clock = {0};
        Mix mix = new Mix();
        Rumble rumble = new Rumble();
        BlastVoices voices = new BlastVoices(rumble);
        Pool pool = new Pool(BlastVoices.reach(BlastVoices.MONO, BlastVoices.STEREO), Rumble.QUADRANTS + Quarters.N);
        BlastVoices.Voice nearVoice = null;
        List<Fake> nearSounds = null;
        int maxUsed = 0;
        for (int tick = 0; tick < 40 * 20; tick++) {
            clock[0] = tick;
            mix.at(tick);
            voices.prune(tick);
            if (crowded && tick == 195) pool.foreign = pool.free();
            voices.reserve(tick, pool.free());
            for (int i = 0; i < n; i++) {
                double born = i == nearId ? 200 : 180, blast = born + dist[i] / 17.15, fall = born + 20 + dist[i] / 17.15;
                List<Fake> sounds = new ArrayList<>();
                Mix.Blast b = null;
                if ((int) blast == tick) {
                    b = mix.blast(tick);
                    BlastMix.Layers l = BlastMix.at(drone, dist[i]);
                    double[] v = {l.near(), l.mid(), l.far(), l.sub()};
                    for (int k = 0; k < 4; k++) {
                        if (v[k] < 0.005) continue;
                        Mix.Layer layer = new Mix.Layer(max[k], energy[k][k == 3 ? i % 2 : i % 6], pitch, v[k], k == 3 ? Mix.Kind.APART : Mix.Kind.VIEW, 0);
                        b.add(layer);
                        sounds.add(new Fake(clock, tick + layer.ticks() + 20));
                    }
                } else if ((int) fall == tick) {
                    float v = Sources.volume(ejecta, new Vec3(ear.x + dist[i] * Math.cos(az[i]), 65, ear.z + dist[i] * Math.sin(az[i])), ear,
                            ua.zentix.incoming.sound.Propagation.Weather.NOON);
                    if (v < 0.005f) continue;
                    b = mix.blast(tick);
                    Mix.Layer layer = new Mix.Layer(max[4], energy[4][i % 4], 1, v, Mix.Kind.APART, 0);
                    b.add(layer);
                    sounds.add(new Fake(clock, tick + layer.ticks() + 20));
                }
                if (b == null || sounds.isEmpty()) continue;
                BlastVoices.Voice voice = voices.admit(b, sounds, tick, az[i], (float) pitch, 1, 1, pool.free());
                if (voice != null) {
                    boolean[] took = new boolean[sounds.size()];
                    for (int k = 0; k < took.length; k++) took[k] = pool.take(sounds.get(k));
                    voices.started(voice, took, tick);
                }
                if (i == nearId && (int) blast == tick) {
                    nearVoice = voice;
                    nearSounds = sounds;
                }
            }
            maxUsed = Math.max(maxUsed, pool.used());
            assertTrue(pool.used() <= pool.reach, "тик " + tick + ": каналов " + pool.used());
            assertEquals(voices.channels(), pool.used() - pool.fixed - pool.foreign, "тик " + tick + ": счёт бюджета — как у движка");
        }
        System.out.printf("stress 300%s: most channels %d of %d, blast voices requested %d started %d refused by the engine %d, to rumble %d, yielded %d, near %s%n",
                crowded ? " (pool crowded at 9.75 s)" : "", maxUsed, pool.reach, voices.requested, voices.started, pool.refused, voices.toRumble,
                voices.yielded, nearVoice == null ? "RUMBLE" : "voice");
        assertNotNull(nearVoice, "ближний — с голосом" + (crowded ? " и когда пул забит чужими" : ""));
        assertTrue(nearSounds.stream().allMatch(f -> f.taken), "движок взял все звуки ближнего");
        assertEquals(0, pool.refused, "ни одному нашему звуку движок не отказал");
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

    /** Голос, чья запись кончилась (звук ещё держит канал), уступает первым, даже новому тихому. */
    @Test
    void endedRecordingYieldsFirst() {
        double[] clock = {0};
        Mix mix = new Mix();
        Rumble rumble = new Rumble();
        BlastVoices voices = new BlastVoices(rumble, 4);
        List<Fake> old = fakes(clock, 4, 60);
        assertNotNull(voices.admit(flat(mix, 0, 0.8, 30), old, 0, 0, 1, 1, 1, Integer.MAX_VALUE));
        clock[0] = 40;
        mix.at(40);
        assertNotNull(voices.admit(flat(mix, 40, 0.001, 100), fakes(clock, 4, 140), 40, 0, 1, 1, 1, Integer.MAX_VALUE));
        assertTrue(old.stream().allMatch(f -> !Double.isNaN(f.fadeFrom)));
    }

    /**
     * Движок не взял звуки голоса — их мощность в гуле сразу и вся (они не звучат вовсе), взятые стихают и отдают свою в
     * тех же тиках: сумма голосов и гула — доля взрыва в смеси с первого тика (ревью R3: было 0 и −3 дБ).
     */
    @Test
    void refusedSoundsReachTheRumbleAtOnce() {
        double[] clock = {0};
        Mix mix = new Mix();
        Rumble rumble = new Rumble();
        BlastVoices voices = new BlastVoices(rumble);
        // весь голос не взят
        Mix.Blast all = flat(mix, 0, 0.2, 100);
        BlastVoices.Voice v = voices.admit(all, fakes(clock, 3, 100), 0, 0, 1, 1, 1, Integer.MAX_VALUE);
        assertNotNull(v);
        voices.started(v, new boolean[3], 0);
        rumble.tick(0);
        assertEquals(all.heard(), rumble.total(), 1e-9, "не взят ни один — весь в гуле с этого тика");
        assertEquals(3, voices.refused);
        // взят один из двух равных: невзятый — в гуле сразу, взятый стихает, сумма та же каждый тик
        Mix mix2 = new Mix();
        Rumble rumble2 = new Rumble();
        BlastVoices voices2 = new BlastVoices(rumble2);
        Mix.Blast two = mix2.blast(0);
        two.add(new Mix.Layer(MixTest.flat(0.1, 100), 1, 1, true));
        two.add(new Mix.Layer(MixTest.flat(0.1, 100), 1, 1, true));
        List<Fake> sounds = fakes(clock, 2, 100);
        sounds.get(1).taken = false;
        BlastVoices.Voice w = voices2.admit(two, sounds, 0, 0, 1, 1, 1, Integer.MAX_VALUE);
        assertNotNull(w);
        voices2.started(w, new boolean[]{true, false}, 0);
        assertTrue(w.fading());
        for (int t = 0; t <= BlastVoices.FADE + 2; t++) {
            clock[0] = t;
            mix2.at(t);
            rumble2.tick(t);
            double k = two.factor(), f = Rumble.voice(t, 0, BlastVoices.FADE), voice = f * f * k * k * two.voiced(t, w.took);
            assertEquals(k * k * two.voiced(t), voice + rumble2.total(), 1e-9, "тик " + t);
        }
    }

    /**
     * Мир съел запас каналов, а свой потолок не занят: громкий новый звучит в свободных сейчас каналах, а тихий хвост
     * уступает, чтобы миру вернулся его запас, когда движок отдаст его каналы (ревью R1: новый уходил в гул). Свободных
     * меньше, чем нужно новому, — вытеснение сейчас их не даст: новый в гуле, хвост звучит.
     */
    @Test
    void loudestTakesAQuietTailWhenTheWorldUsedTheReserve() {
        double[] clock = {0};
        Mix mix = new Mix();
        BlastVoices voices = new BlastVoices(new Rumble(), 100);
        List<Fake> tail = fakes(clock, 4, 300);
        assertNotNull(voices.admit(flat(mix, 0, 0.01, 300), tail, 0, 0, 1, 1, 1, 1000));
        clock[0] = 20;
        mix.at(20);
        assertNotNull(voices.admit(flat(mix, 20, 1, 300), fakes(clock, 4, 320), 20, 0, 1, 1, 1, BlastVoices.SPARE + 2));
        assertTrue(tail.stream().allMatch(f -> !Double.isNaN(f.fadeFrom)), "тихий хвост уступил");
        BlastVoices tight = new BlastVoices(new Rumble(), 100);
        List<Fake> quiet = fakes(clock, 4, 300);
        assertNotNull(tight.admit(flat(mix, 20, 0.01, 300), quiet, 20, 0, 1, 1, 1, 1000));
        clock[0] = 40;
        mix.at(40);
        assertNull(tight.admit(flat(mix, 40, 1, 300), fakes(clock, 4, 340), 40, 0, 1, 1, 1, 3));
        assertTrue(quiet.stream().allMatch(f -> Double.isNaN(f.fadeFrom)));
    }

    /** Свободных у движка мало, уступить некому: новому не хватает запаса миру — в гул, и ближний не теряется: он в гуле. */
    @Test
    void worldReserveSendsTheNewToTheRumbleWhenNoOneCanYield() {
        double[] clock = {0};
        Mix mix = new Mix();
        BlastVoices tight = new BlastVoices(new Rumble(), 100);
        assertNull(tight.admit(flat(mix, 0, 1, 50), fakes(clock, 4, 50), 0, 0, 1, 1, 1, BlastVoices.SPARE + 2));
        assertEquals(1, tight.toRumble);
    }

    /**
     * Запас уступивших — не «все 12 заняты»: остатка не хватает на группу нового — он в гуле, хоть два места ещё свободны.
     */
    @Test
    void headroomResidueTooSmallForTheGroupSendsItToTheRumble() {
        double[] clock = {0};
        Mix mix = new Mix();
        BlastVoices voices = new BlastVoices(new Rumble(), 8);
        for (int i = 0; i < 9; i++) {
            assertNotNull(voices.admit(flat(mix, 0, 0.01 * Math.pow(1.5, i), 200), fakes(clock, 2, 200), 0, 0, 1, 1, 1, Integer.MAX_VALUE), "голос " + i);
        }
        assertEquals(8, voices.steady());
        assertEquals(18, voices.channels(), "звучат 8, уступившие держат 10 из 12 запаса");
        assertNull(voices.admit(flat(mix, 0, 100, 200), fakes(clock, 4, 200), 0, 0, 1, 1, 1, Integer.MAX_VALUE), "группе из 4 не хватает остатка 2");
        assertNotNull(voices.admit(flat(mix, 0, 100, 200), fakes(clock, 2, 200), 0, 0, 1, 1, 1, Integer.MAX_VALUE), "группе из 2 — хватает");
    }

    /**
     * Остановленный звук держит канал, пока движок его не вернул: голос уходит из счёта только тогда, и серия громких в
     * одном тике не держит каналов больше потолка с запасом — лишние в гуле с той же мощностью (ревью R2: счёт видел 20,
     * у движка было 48). Через тики, когда уступившие отдали каналы, места снова есть.
     */
    @Test
    void stoppedSoundsHoldTheirChannelsUntilTheEngineReleasesThem() {
        double[] clock = {0};
        Mix mix = new Mix();
        Rumble rumble = new Rumble();
        BlastVoices voices = new BlastVoices(rumble, 8);
        List<Fake> all = new ArrayList<>();
        int admitted = 0;
        for (int i = 0; i < 12; i++) {
            List<Fake> f = fakes(clock, 4, 200);
            if (voices.admit(flat(mix, 0, 0.01 * Math.pow(1.5, i), 200), f, 0, 0, 1, 1, 1, Integer.MAX_VALUE) != null) {
                all.addAll(f);
                admitted++;
            }
            long held = all.stream().filter(Fake::held).count();
            assertEquals(held, voices.channels(), "счёт — как у движка");
            assertTrue(held <= 8 + BlastVoices.HEADROOM, "каналов у движка " + held);
        }
        assertEquals(5, admitted, "потолок 8 и запас 12: два звучат, три стихают, остальные — в гуле");
        assertEquals(7, voices.toRumble);
        // уступившие стихли (тик полной громкости, FADE тиков) и движок вернул их каналы ещё тиком позже
        for (int t = 1; t <= BlastVoices.FADE + 2; t++) {
            clock[0] = t;
            mix.at(t);
            voices.prune(t);
            assertEquals(all.stream().filter(Fake::held).count(), voices.channels(), "тик " + t);
        }
        assertEquals(8, voices.channels(), "уступившие вернули каналы — держат только звучащие");
        assertNotNull(voices.admit(flat(mix, clock[0], 100, 200), fakes(clock, 4, 300), clock[0], 0, 1, 1, 1, Integer.MAX_VALUE));
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
            long physical = 0;
            for (BlastVoices.Voice v : voices.voices()) for (BlastVoices.Sound s : v.sounds) if (s.held()) physical++;
            assertEquals(physical, voices.channels());
            most = Math.max(most, voices.steady());
            // мощность голосов и гула — доли взрывов в смеси: ничего не потеряно и не удвоено
            double exact = 0, heard = rumble.total();
            for (Mix.Blast b : all) {
                double k = b.factor();
                exact += k * k * b.voiced(tick);
            }
            for (BlastVoices.Voice v : voices.voices()) {
                double f = v.fading() ? Rumble.voice(tick, v.fadeFrom, BlastVoices.FADE) : 1, k = v.blast.factor();
                heard += f * f * k * k * v.blast.voiced(tick, v.took);
            }
            assertEquals(exact, heard, 1e-9 + 1e-9 * exact, "тик " + tick + ": голоса + гул против долей в смеси");
            if (tick == nearAt + 2) nearHeard = nearBlast.heard();
        }
        double off = 10 * Math.log10(nearHeard / nearAlone);
        System.out.printf("raid cap %d: most voices %d, to rumble %d, yielded %d, near %.2f dB vs alone%n", cap, most, voices.toRumble, voices.yielded, off);
        assertEquals(0, off, 0.5, "ближний над кашей — как один в тишине");
    }
}
