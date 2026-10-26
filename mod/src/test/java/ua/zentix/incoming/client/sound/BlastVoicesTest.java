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
 * Voices of explosions ({@link BlastVoices}): one-time sounds of explosions in the engine channels are not more than the ceiling from the channel count -
 * , including those that have already been stopped until the engine returns them; the loudest voice receives (with attack defense boundaries and
 *  free channels); yielded and not taken - into a hum ({@link Rumble}) with the same power.
 */
class BlastVoicesTest {
    /**
     * One-time sound without an engine, with a channel life like the engine: sounds up to {@code end}; gave in - the tick is still full, then the tick subsides
     * {@link BlastVoices#FADE} and stops; the stopped channel holds the channel for another tick - the engine will return it to the pool in its
     * next tick ({@code SoundEngine.tickNonPaused}, {@code ChannelAccess.scheduleTick}); I didn’t take the engine - there is no channel.
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

    /** Explosion of smooth power {@code p} on {@code ticks} tick with tick {@code at}. */
    private static Mix.Blast flat(Mix mix, double at, double p, int ticks) {
        Mix.Blast b = mix.blast(at);
        b.add(new Mix.Layer(MixTest.flat(p, ticks), 1, 1));
        return b;
    }

    /**
     * The ceiling is from the channel count: OpenAL Soft 255 mono and 1 stereo, the engine has 247 static and 8 streaming, nearby 12
     *  its own - static in fact 236; minus the engines 48, the roar of explosions 4, the roar of fires 4, the nuke 8, the world 64 and the reserve for
     *  subsiding 12 - 96. Without information from the device, the engine takes 30 mono - 25 static, and the least explosions are left.
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
     * The world has eaten up the stock of free channels: the quietest ones give way in advance, until there are no free channels (with those that are about to return) for the next one; There is a reserve - no one gives in.
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
        assertEquals(2, voices.reserve(20, 5), "free 5, need 12: two give way the quietest of all");
        assertTrue(all.get(0).get(0).fadeFrom == 20 && all.get(1).get(0).fadeFrom == 20 && Double.isNaN(all.get(2).get(0).fadeFrom));
        assertEquals(0, voices.reserve(20, 5), "those who gave in are about to return - no longer needed");
    }

    /**
     * The reserve does not cancel the reception (review R7): only the one who would be supplanted by an explosion in the volume of the loudest
     *  voice - who is quieter than him (attack - twice) or whose recording has ended - is inferior in advance. The neighbor, who took four of the twelve
     *  free and left the world eight, the next tick is not inferior to the reserve either in the attack or after - he is the loudest;
     */
    @Test
    void reserveNeverYieldsTheLoudestNorAProtectedAttack() {
        double[] clock = {0};
        Mix mix = new Mix();
        BlastVoices voices = new BlastVoices(new Rumble());
        BlastVoices.Voice near = voices.admit(flat(mix, 0, 1, 300), fakes(clock, 4, 300), 0, 0, 1, 1, 1, 12);
        assertNotNull(near, "the reserve launched a nearby attack");
        voices.started(near, 4, 0);
        for (int t = 1; t < 300; t++) {
            clock[0] = t;
            mix.at(t);
            assertEquals(0, voices.reserve(t, BlastVoices.SPARE), "tick " + t + ": the world took nothing, the near event is loudest");
            assertTrue(!near.fading(), "tick " + t + ": neighbor alive after stock");
        }

        clock[0] = 0;
        mix = new Mix();
        voices = new BlastVoices(new Rumble());
        BlastVoices.Voice tail = voices.admit(flat(mix, 0, 0.9, 300), fakes(clock, 4, 300), 0, 0, 1, 1, 1, 1000);
        clock[0] = 20;
        mix.at(20);
        BlastVoices.Voice loud = voices.admit(flat(mix, 20, 1, 300), fakes(clock, 4, 300), 20, 0, 1, 1, 1, 1000);
        BlastVoices.Voice strong = voices.admit(flat(mix, 20, 0.8, 300), fakes(clock, 4, 300), 20, 0, 1, 1, 1, 1000);
        BlastVoices.Voice weak = voices.admit(flat(mix, 20, 0.6, 300), fakes(clock, 4, 300), 20, 0, 1, 1, 1, 1000);
        for (BlastVoices.Voice v : List.of(tail, loud, strong, weak)) assertNotNull(v);
        // shares: loud 1/2,81, tail 0,81/2,81, attacks 0,64/2,81 and 0,36/2,81 - double: 1,28 and 0,72 loud
        clock[0] = 21;
        mix.at(21);
        assertEquals(2, voices.reserve(21, 0), "the world has eaten everything: the silent attack and the tail give way");
        assertTrue(weak.fading() && tail.fading(), "quieter than loud: double attack, tail");
        assertTrue(!strong.fading() && !loud.fading(), "loud attack is protected, the loudest is not the victim");
        for (int t = 22; t < 300; t++) {
            clock[0] = t;
            mix.at(t);
            voices.reserve(t, 0);
            assertEquals(t >= 20 + BlastVoices.ATTACK, strong.fading(), "tick " + t + ": the attack is over - gives way like a tail");
            assertTrue(!loud.fading(), "tick " + t + ": the loudest event does not yield to the reserve");
        }
    }

    /**
     * The stock of {@link BlastVoices#GROUP} free - to the loudest: the new one, which is quieter than someone else sounding, does not take it -
     *  with 14 free and the group in 4 sounds in the hum immediately, and not in a voice that the stock would take away with the next tick (review R7,
     *  for anyone, not just the loudest); with 18 - a voice, and the supply does not touch it, until the world has taken nothing. The loudest
     *  reserve is taken, and the reserve is returned quieter than it - as they would yield to its reception.
     */
    @Test
    void theReserveBelongsToTheLoudest() {
        double[] clock = {0};
        Mix mix = new Mix();
        BlastVoices voices = new BlastVoices(new Rumble());
        BlastVoices.Voice first = voices.admit(flat(mix, 0, 0.5, 300), fakes(clock, 4, 300), 0, 0, 1, 1, 1, 1000);
        assertNotNull(first);
        clock[0] = 10;
        mix.at(10);
        assertNull(voices.admit(flat(mix, 10, 0.3, 300), fakes(clock, 4, 300), 10, 0, 1, 1, 1, 14), "quieter than the sound, the reserve is not his - into the hum");
        BlastVoices.Voice second = voices.admit(flat(mix, 10, 0.3, 300), fakes(clock, 4, 300), 10, 0, 1, 1, 1, 18);
        assertNotNull(second, "stock remains - voice");
        for (int t = 11; t < 40; t++) {
            clock[0] = t;
            mix.at(t);
            assertEquals(0, voices.reserve(t, 14), "tick " + t + ": the world didn’t take anything - the stock is intact");
            assertTrue(!second.fading() && !first.fading(), "tick " + t);
        }
        BlastVoices.Voice loudest = voices.admit(flat(mix, 40, 1, 300), fakes(clock, 4, 300), 40, 0, 1, 1, 1, 14);
        assertNotNull(loudest, "the loudest one takes stock");
        clock[0] = 41;
        mix.at(41);
        assertEquals(1, voices.reserve(41, 10), "the stock returns the quietest one");
        assertTrue(second.fading() && !first.fading() && !loudest.fading());
    }

    /** Engine pool without engine: as many static channels as OpenAL will give; the channel is busy while the sound is on it. */
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

        /** The sound asks the engine for a channel: there is no space - the engine does not play silently. */
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
     * Overload scene of WAV run (Sol, 25.10): 299 Shahed at once on the 9 second in 30–200 blocks, after a second - a scree of
     *  ejection of each crater (one-time explosion sound, in the budget), near - in 40 blocks at the 10 second; engine pool - 236
     *  channels, the roar of explosions and fires hold 8. The neighbor receives a voice, the engine takes all his sounds, and the engine does not refuse a single sound of ours; just like when, before the neighbor’s arrival, alien sounds filled the entire pool: the quietest ones gave up the reserve for the neighbor in advance. In 33a23c16 the neighbor went into a rumble: the pool was held by ~300 loops of the rumble of fires and scree, past the budget.
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
        double nearBorn = Double.NaN, nearYield = Double.NaN;
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
                    nearBorn = tick;
                }
            }
            // the neighbor is alive and after the supply of the following tick: not only started (review R7)
            if (nearVoice != null && nearVoice.fading() && Double.isNaN(nearYield)) nearYield = tick;
            if (nearVoice != null && tick < nearBorn + BlastVoices.ATTACK) assertTrue(!nearVoice.fading(), "tick " + tick + ": Melee attack failed");
            maxUsed = Math.max(maxUsed, pool.used());
            assertTrue(pool.used() <= pool.reach, "tick " + tick + ": channels " + pool.used());
            assertEquals(voices.channels(), pool.used() - pool.fixed - pool.foreign, "tick " + tick + ": budget account - like an engine");
        }
        System.out.printf("stress 300%s: most channels %d of %d, blast voices requested %d started %d refused by the engine %d, to rumble %d, yielded %d, near %s%s%n",
                crowded ? " (pool crowded at 9.75 s)" : "", maxUsed, pool.reach, voices.requested, voices.started, pool.refused, voices.toRumble,
                voices.yielded, nearVoice == null ? "RUMBLE" : "voice", Double.isNaN(nearYield) ? ", kept to its end" : ", yielded " + (nearYield - nearBorn) + " ticks after its start");
        assertNotNull(nearVoice, "neighbor - with voice" + (crowded ? " and when the pool is full of strangers" : ""));
        assertTrue(nearSounds.stream().allMatch(f -> f.taken), "the engine took all the sounds of the neighbor");
        assertEquals(0, pool.refused, "the engine did not refuse any of our sounds");
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
        assertNull(voices.admit(quiet, fakes(clock, 1, 120), 20, 1, 1, 1, 1, Integer.MAX_VALUE), "quieter than everyone - straight into the noise");
        assertEquals(1, rumble.size());
        rumble.tick(20);
        assertEquals(quiet.heard(), rumble.total(), 1e-12, "in a hum - with the same power, all at once");
        assertEquals(8, voices.steady());
    }

    /** The new one is louder - the quietest tail gives way: its sounds subside, its power turns into a hum; the attack is replaced by a new one only if it is twice as loud. */
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
        assertTrue(quiet.stream().allMatch(f -> !Double.isNaN(f.fadeFrom)), "the quiet tail subsides");
        assertTrue(loud.stream().allMatch(f -> Double.isNaN(f.fadeFrom)), "sounds loud");
        assertEquals(8, voices.steady());
        assertEquals(12, voices.channels(), "subsiding keeps channels still FADE tick");
        assertEquals(1, voices.yielded);
        // new loudest event always gets a voice: the lowest-priority event yields (attack v has a KEEP margin)
        clock[0] = 31;
        mix.at(31);
        voices.prune(31);
        Mix.Blast loudest = flat(mix, 31, 1, 50);
        assertNotNull(voices.admit(loudest, fakes(clock, 4, 81), 31, 0, 1, 1, 1, Integer.MAX_VALUE));
        assertEquals(8, voices.steady());
    }

    /** A new attack, less than twice as loud as it, does not displace it: it goes into a rumble. */
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
        // after the attack, yields to the same event
        clock[0] = 2 + BlastVoices.ATTACK;
        mix.at(clock[0]);
        Mix.Blast c = flat(mix, clock[0], 0.13, 100);
        assertTrue(c.heard() > a.heard());
        assertNotNull(voices.admit(c, fakes(clock, 4, 200), clock[0], 0, 1, 1, 1, Integer.MAX_VALUE));
    }

    /** A voice whose recording ended (its sound still holds a channel) yields first, even to a quiet new event. */
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
     * The engine did not take the sounds of the voice - their power is in the hum at once and all (they do not sound at all), the taken ones die down and give up theirs in
     *  of the same tick: the sum of the voices and the hum is the share of the explosion in the mixture from the first tick (review R3: there were 0 and −3 dB).
     */
    @Test
    void refusedSoundsReachTheRumbleAtOnce() {
        double[] clock = {0};
        Mix mix = new Mix();
        Rumble rumble = new Rumble();
        BlastVoices voices = new BlastVoices(rumble);
        // the whole vote is not taken
        Mix.Blast all = flat(mix, 0, 0.2, 100);
        BlastVoices.Voice v = voices.admit(all, fakes(clock, 3, 100), 0, 0, 1, 1, 1, Integer.MAX_VALUE);
        assertNotNull(v);
        voices.started(v, new boolean[3], 0);
        rumble.tick(0);
        assertEquals(all.heard(), rumble.total(), 1e-9, "not a single one was taken - all in a rumble from this tick");
        assertEquals(3, voices.refused);
        // one of two equals is taken: the untaken one - in a rumble immediately, the taken one subsides, the sum is the same every tick
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
            assertEquals(k * k * two.voiced(t), voice + rumble2.total(), 1e-9, "tick " + t);
        }
    }

    /**
     * The world has eaten up its supply of channels, but its ceiling is not occupied: the loud new one sounds in the currently free channels, and the quiet tail
     *  gives way so that the world can get its supply back when the engine gives up its channels (review R1: the new one went into rumble). There are fewer free
     *  than the new one needs - displacement now will not give them: the new one is in a hum, the tail sounds.
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
        assertTrue(tail.stream().allMatch(f -> !Double.isNaN(f.fadeFrom)), "the quiet tail gave way");
        BlastVoices tight = new BlastVoices(new Rumble(), 100);
        List<Fake> quiet = fakes(clock, 4, 300);
        assertNotNull(tight.admit(flat(mix, 20, 0.01, 300), quiet, 20, 0, 1, 1, 1, 1000));
        clock[0] = 40;
        mix.at(40);
        assertNull(tight.admit(flat(mix, 40, 1, 300), fakes(clock, 4, 340), 40, 0, 1, 1, 1, 3));
        assertTrue(quiet.stream().allMatch(f -> Double.isNaN(f.fadeFrom)));
    }

    /** The engine has few free ones, there is no one to give in to: the new one doesn’t have enough supply, the world is in a rumble, and the neighbor is not lost: he is in a rumble. */
    @Test
    void worldReserveSendsTheNewToTheRumbleWhenNoOneCanYield() {
        double[] clock = {0};
        Mix mix = new Mix();
        BlastVoices tight = new BlastVoices(new Rumble(), 100);
        assertNull(tight.admit(flat(mix, 0, 1, 50), fakes(clock, 4, 50), 0, 0, 1, 1, 1, BlastVoices.SPARE + 2));
        assertEquals(1, tight.toRumble);
    }

    /**
     * The supply of those who have given up is not “all 12 are occupied”: the remainder is not enough for a new group - it is in a rumble, although two places are still free.
     */
    @Test
    void headroomResidueTooSmallForTheGroupSendsItToTheRumble() {
        double[] clock = {0};
        Mix mix = new Mix();
        BlastVoices voices = new BlastVoices(new Rumble(), 8);
        for (int i = 0; i < 9; i++) {
            assertNotNull(voices.admit(flat(mix, 0, 0.01 * Math.pow(1.5, i), 200), fakes(clock, 2, 200), 0, 0, 1, 1, 1, Integer.MAX_VALUE), "voice " + i);
        }
        assertEquals(8, voices.steady());
        assertEquals(18, voices.channels(), "sound 8, the losers keep 10 of 12 stock");
        assertNull(voices.admit(flat(mix, 0, 100, 200), fakes(clock, 4, 200), 0, 0, 1, 1, 1, Integer.MAX_VALUE), "the group of 4 lacks the balance 2");
        assertNotNull(voices.admit(flat(mix, 0, 100, 200), fakes(clock, 2, 200), 0, 0, 1, 1, 1, Integer.MAX_VALUE), "group of 2 - enough");
    }

    /**
     * The stopped sound holds the channel until the engine returns it: the voice leaves the account only then, and a series of loud ones in
     *  one tick does not hold channels more than the ceiling with a margin - the extra ones in the hum with the same power (review R2: the account saw 20,
     *  the engine had 48). After a tick, when those who gave up gave up the channels, there are places again.
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
            assertEquals(held, voices.channels(), "score - like an engine");
            assertTrue(held <= 8 + BlastVoices.HEADROOM, "channels on the engine " + held);
        }
        assertEquals(5, admitted, "ceiling 8 and stock 12: two sound, three subside, the rest are humming");
        assertEquals(7, voices.toRumble);
        // those who gave in died down (tick full volume, FADE tick) and the engine returned their channels another tick later
        for (int t = 1; t <= BlastVoices.FADE + 2; t++) {
            clock[0] = t;
            mix.at(t);
            voices.prune(t);
            assertEquals(all.stream().filter(Fake::held).count(), voices.channels(), "tick " + t);
        }
        assertEquals(8, voices.channels(), "those who gave in returned the channels - they only keep the ones that sound");
        assertNotNull(voices.admit(flat(mix, clock[0], 100, 200), fakes(clock, 4, 300), clock[0], 0, 1, 1, 1, Integer.MAX_VALUE));
    }

    /**
     * Raid: one hundred Shahed around in a circle 30–2000 blocks in 40 s, close - in 40 blocks in 10 second; camera angle records -
     * real files. Each tick: there are no more voices than the ceiling, the power of voices and rumble is exactly a fraction of explosions in the mixture
     *  (the one that gave way - into a rumble with the same power); the neighbor receives a voice and sounds in 0,5 dB from himself alone in silence. So - both with
     *  ceiling 100, and in cramped spaces (ceiling 24).
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
                if (i < 0) assertNotNull(voice, "neighbor - with voice (ceiling " + cap + ")");
            }
            rumble.tick(tick);
            assertTrue(voices.steady() <= cap, "votes " + voices.steady() + " at the ceiling " + cap);
            assertTrue(voices.channels() <= cap + BlastVoices.HEADROOM);
            long physical = 0;
            for (BlastVoices.Voice v : voices.voices()) for (BlastVoices.Sound s : v.sounds) if (s.held()) physical++;
            assertEquals(physical, voices.channels());
            most = Math.max(most, voices.steady());
            // the power of voices and hum - the proportion of explosions in the mixture: nothing is lost or doubled
            double exact = 0, heard = rumble.total();
            for (Mix.Blast b : all) {
                double k = b.factor();
                exact += k * k * b.voiced(tick);
            }
            for (BlastVoices.Voice v : voices.voices()) {
                double f = v.fading() ? Rumble.voice(tick, v.fadeFrom, BlastVoices.FADE) : 1, k = v.blast.factor();
                heard += f * f * k * k * v.blast.voiced(tick, v.took);
            }
            assertEquals(exact, heard, 1e-9 + 1e-9 * exact, "tick " + tick + ": voices + hum against beats in the mixture");
            if (tick == nearAt + 2) nearHeard = nearBlast.heard();
        }
        double off = 10 * Math.log10(nearHeard / nearAlone);
        System.out.printf("raid cap %d: most voices %d, to rumble %d, yielded %d, near %.2f dB vs alone%n", cap, most, voices.toRumble, voices.yielded, off);
        assertEquals(0, off, 0.5, "the neighbor over the porridge - like one in silence");
    }
}
