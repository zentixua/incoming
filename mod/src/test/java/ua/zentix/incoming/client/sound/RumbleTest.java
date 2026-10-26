package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hum of explosions without a voice ({@link Rumble}): the sum of the power in quarters around the ear, the share of each member is its share in the
 *  mixture × the energy of its recording, the transition voice → hum without a jump in the sum, the energy profile of the recording is its real term.
 */
class RumbleTest {
    private static float[] flat(double power, int ticks) {
        return MixTest.flat(power, ticks);
    }

    /** Explosion in a mixture of {@code mix} with the power of {@code p} at {@code ticks} tick (recording energy is the same). */
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
        // on the hinge axis - all the power is in it
        assertEquals(0.01, r.power(0), 1e-8);
        assertEquals(0, r.power(1) + r.power(2) + r.power(3), 1e-12);
        // between two loops - equally (cos²/sin²), the same amount
        Mix mix2 = new Mix();
        Rumble r2 = new Rumble();
        r2.add(blast(mix2, 0.01, 40), Math.PI / 4, 1, 1, 1, 0, 0);
        r2.add(blast(mix2, 0.01, 40), -Math.PI / 2, 1, 1, 1, 0, 0);
        mix2.at(1);
        r2.tick(1);
        double each = mix2.heard() / 2;
        assertEquals(each / 2, r2.power(0), 1e-8);
        assertEquals(each / 2, r2.power(1), 1e-8);
        assertEquals(each, r2.power(3), 1e-8, "−90° (north, −z) - loop 3");
        assertEquals(mix2.heard(), r2.total(), 1e-8);
    }

    /**
     * Like {@code FireRoarTest}: the sum of the loops against the exact sum of the terms - in 0,5 dB (here exactly: the section in the direction of
     *  preserves power), hum - the same proportion in the mixture that would sound like voices; behind the barrier - quieter for her part.
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

    /**
     * The voice that gave way to the tick of the 10 engine: tick is still full, then subsides after {@link BlastVoices#FADE} tick, and the hum member in the same
     *  tick takes the rest: the power of the voice f² + hum (1 − f²) is the same; there are no voices at all - the whole explosion is a rumble at once.
     */
    @Test
    void handoverKeepsThePower() {
        Mix mix = new Mix();
        Rumble r = new Rumble();
        Mix.Blast b = blast(mix, 0.2, 100);
        r.add(b, 0, 1, 1, 1, new boolean[]{true}, 10, BlastVoices.FADE);
        assertEquals(1, Rumble.voice(11, 10, BlastVoices.FADE), 1e-12, "the first tick after the concession - the voice is still full");
        assertEquals(0, Rumble.voice(11 + BlastVoices.FADE, 10, BlastVoices.FADE), 1e-12);
        for (double t = 10; t <= 10 + BlastVoices.FADE + 2; t += 0.5) {
            mix.at(t);
            r.tick(t);
            double f = Rumble.voice(t, 10, BlastVoices.FADE), voice = f * f * b.heard();
            assertEquals(b.heard(), voice + r.total(), 1e-12, "tick " + t);
        }
        Rumble direct = new Rumble();
        direct.add(b, 0, 1, 1, 1, 20, 0);
        mix.at(20);
        direct.tick(20);
        assertEquals(b.heard(), direct.total(), 1e-12, "without a voice - all in a rumble from this tick");
    }

    /**
     * The loop, which has nothing to play, holds the channel for a second - 20 tick - and releases it; member is heard again - counting again (
     *  each quarter has its own).
     */
    @Test
    void silentLoopIsReleasedAfterASecond() {
        Rumble r = new Rumble();
        for (int t = 0; t < 20; t++) assertTrue(r.keep(2, 0), "tick " + t);
        assertTrue(!r.keep(2, 0), "silence longer than a second - let go");
        Mix mix = new Mix();
        r.add(blast(mix, 0.5, 300), Rumble.azimuth(2), 1, 1, 1, 0, 0);
        mix.at(1);
        r.tick(1);
        assertTrue(r.keep(2, 0.5f), "heard again - hold");
        assertTrue(r.keep(1, 0) && r.keep(3, 0.2f), "each quarter has its own account");
    }

    /**
     * Is it audible - the sum decides, not each loop (review R9): a fire that is audible itself (the volume of the 1,2 threshold), on the axis of the loop -
     *  one loop with all the power, in the middle between the two - both, with half the power, although each is quieter than the threshold (threshold on
     *  each I removed both, and the fire disappeared depending on the azimuth); the amount is quieter than the threshold - none plays. The same hum: a member,
     * , audible in its entirety, between two loops sounds in both.
     */
    @Test
    void audibilityIsDecidedByTheSum() {
        double v = 1.2 * VoiceBudget.AUDIBLE;
        for (double az : new double[]{0, Math.PI / 8, Math.PI / 4, Math.PI / 2, -3 * Math.PI / 4}) {
            Quarters f = new Quarters();
            f.add(az, v, 1, 1);
            double played = 0;
            for (int q = 0; q < Quarters.N; q++) if (f.plays(q)) played += f.volume(q) * f.volume(q);
            assertEquals(v * v, played, 1e-9, "azimuth " + az + ": the loops that play carry all the fire");
        }
        Quarters axis = new Quarters(), mid = new Quarters(), quiet = new Quarters();
        axis.add(0, v, 1, 1);
        assertTrue(axis.plays(0) && !axis.plays(1) && !axis.plays(3), "on the axis - one loop");
        mid.add(Math.PI / 4, v, 1, 1);
        assertTrue(mid.volume(0) < VoiceBudget.AUDIBLE && mid.volume(1) < VoiceBudget.AUDIBLE, "each half is quieter than the threshold");
        assertTrue(mid.plays(0) && mid.plays(1) && !mid.plays(2) && !mid.plays(3), "in the middle - both loops");
        assertTrue(!quiet.heard() && !quiet.plays(0), "there are no fires - no one is playing");
        quiet.add(Math.PI / 4, 0.8 * VoiceBudget.AUDIBLE, 1, 1);
        assertTrue(quiet.heard() && quiet.plays(0) && quiet.plays(1), "an audible fire (is it audible - it’s decided by the ear) plays and is quieter than the previous file threshold");

        Mix mix = new Mix();
        Rumble r = new Rumble();
        double p = v * v * Envelope.meanSquare(BlastSounds.RUMBLE_LUFS) / Mix.CEILING;
        r.add(blast(mix, p, 300), Math.PI / 4, 1, 1, 1, 0, 0);
        mix.at(1);
        r.tick(1);
        float total = Rumble.volume(r.potentialTotal(), BlastSounds.RUMBLE_LUFS);
        assertEquals(v, total, 1e-4, "the whole penis can be heard");
        assertTrue(Rumble.volume(r.potential(0), BlastSounds.RUMBLE_LUFS) < VoiceBudget.AUDIBLE, "half in the loop is quieter than the threshold");
        assertTrue(r.plays(0, total) && r.plays(1, total) && !r.plays(2, total) && !r.plays(3, total), "hum between two loops - in both");
        for (int t = 0; t < 40; t++) assertTrue(r.keep(0, total) && r.keep(1, total), "tick " + t + ": hinges hold with some audible hum");
        // can you hear a hum - not from the volume of the loop file (review R11): the same hum in the file on 8,1 dB quieter - the same solution
        Rumble faint = new Rumble();
        faint.add(blast(new Mix(), p * 0.1, 300), Math.PI / 4, 1, 1, 1, 0, 0);
        faint.tick(1);
        for (double file : new double[]{BlastSounds.RUMBLE_LUFS, BlastSounds.RUMBLE_LUFS - 8.1}) {
            float t = Rumble.volume(faint.potentialTotal(), file);
            assertTrue(faint.plays(0, t) && faint.plays(1, t), "file " + file + " LUFS: loop volume " + t + " - the same hum, plays");
        }
    }

    /**
     * The tone and top of the sounding hum is determined by what sounds in it: the voice, which has just given way and is still full (its share in the hum is
     * zero), does not affect the timbre; as it subsides, the timbre goes to its beat. There is no hum yet - the timbre of those who will come into it
     *  (review R5: the tone of the sounding loop jumped 0,5 → 1,985, the top 0,1 → 0,991).
     */
    @Test
    void timbreFollowsWhatSoundsInTheRumble() {
        Mix mix = new Mix();
        Rumble r = new Rumble();
        Mix.Blast present = blast(mix, 0.1, 300), yielding = blast(mix, 1, 300);
        mix.at(10);
        r.add(present, 0, 0.5f, 1, 0.1f, 0, 0);
        Rumble.Member m = r.add(yielding, 0, 2, 1, 1, new boolean[]{true}, 10, BlastVoices.FADE);
        for (int t = 10; t <= 11; t++) {
            r.tick(t);
            assertEquals(0, m.power(t), 1e-12, "conceded still full");
            assertEquals(0.5, r.pitch(0), 1e-6, "tone - sounding");
            assertEquals(0.1, r.highs(0), 1e-6, "top - sounding");
        }
        r.tick(12);
        double a = present.heard(), b = yielding.heard() * (1 - 4 / 9.0);
        assertEquals((0.5 * a + 2 * b) / (a + b), r.pitch(0), 1e-5, "tone - by beats in the hum");
        assertEquals((0.1 * a + 1 * b) / (a + b), r.highs(0), 1e-5);
        Rumble soon = new Rumble();
        soon.add(yielding, 0, 2, 1, 0.3f, new boolean[]{true}, 10, BlastVoices.FADE);
        soon.tick(10);
        assertEquals(0, soon.power(0), 1e-12);
        assertEquals(2, soon.pitch(0), 1e-6, "there is no hum yet - the timbre of the one who will come");
        assertEquals(0.3, soon.highs(0), 1e-6);
    }

    /**
     * Fires in the crater - the sum of powers in quarters ({@link Quarters}): the loudness of the loop is the root of the sum, on the axis of the loop -
     *  all the power in it, between the two - equally, the tops are average in power, the sum in quarters is the sum of fires.
     */
    @Test
    void firesAddUpByPowerIntoQuarters() {
        Quarters f = new Quarters();
        f.add(0, 0.3, 1, 1);
        f.add(0, 0.4, 1, 0.5);
        assertEquals(0.5, f.volume(0), 1e-6, "√(0,3² + 0,4²)");
        assertEquals((0.09 + 0.16 * 0.5) / 0.25, f.highs(0), 1e-6);
        Quarters g = new Quarters();
        g.add(Math.PI / 4, 0.2, 0.5, 1);
        assertEquals(0.1 * Math.sqrt(0.5), g.volume(0), 1e-6, "behind the barrier - × its share, between the loops - equally");
        assertEquals(g.volume(0), g.volume(1), 1e-6);
        assertEquals(0, g.volume(2) + g.volume(3), 1e-9);
        Quarters many = new Quarters();
        Random rnd = new Random(3);
        double sum = 0;
        for (int i = 0; i < 300; i++) {
            double v = 0.02 * rnd.nextDouble();
            many.add(2 * Math.PI * rnd.nextDouble() - Math.PI, v, 1, 1);
            sum += v * v;
        }
        double got = 0;
        for (int q = 0; q < Quarters.N; q++) got += many.volume(q) * many.volume(q);
        assertEquals(sum, got, 1e-6 * sum, "three hundred fires - four loops of the same power");
    }

    /**
     * The hum takes the energy of the version of the recording that is playing, and not the envelope of the event (the largest in the options is for the ceiling):
     *  from a far angle, the options are from 2,4 to 9,4 s, and the short one would sound in the hum with the tail of the long one.
     */
    @Test
    void rumbleTakesTheEnergyOfThePlayingVariant() throws IOException {
        int rate = 44100;
        float[][] far = MixTest.load("blast_far", 6, rate);
        java.util.Map<net.minecraft.resources.ResourceLocation, float[]> energy = new java.util.HashMap<>();
        float[] max = new float[0];
        int shortest = 0;
        for (int i = 0; i < far.length; i++) {
            energy.put(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("incoming", "blast_far_" + (i + 1)), Envelope.energy(far[i], rate));
            max = Envelopes.max(max, Envelope.of(far[i], rate));
            if (far[i].length < far[shortest].length) shortest = i;
        }
        net.minecraft.resources.ResourceLocation event = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("incoming", "blast.far");
        Envelopes.Prepared before = Envelopes.INSTANCE.prepared();
        try {
            Envelopes.INSTANCE.prepared(new Envelopes.Prepared(java.util.Map.of("blast.far", max), energy));
            net.minecraft.resources.ResourceLocation variant = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("incoming", "blast_far_" + (shortest + 1));
            float[] got = Envelopes.energy(variant, event);
            assertTrue(java.util.Arrays.equals(Envelope.energy(far[shortest], rate), got), "energy of the sounding version");
            assertTrue(got.length + 20 < max.length, "the event envelope is longer than the tail of the longest option: " + got.length + " against " + max.length);
            assertTrue(java.util.Arrays.equals(max, Envelopes.energy(null, event)), "we don’t know the option - upper estimate");
        } finally {
            Envelopes.INSTANCE.prepared(before);
        }
    }

    /** The loop volume is the root of the power against the ceiling of the file on the ceiling; tone and highs are average in power. */
    @Test
    void loopVolumePitchAndHighs() {
        assertEquals(1, Rumble.volume(1, Loudness.CEILING_LUFS), 1e-6);
        assertEquals(0.1, Rumble.volume(0.01, Loudness.CEILING_LUFS), 1e-6);
        assertEquals(1, Rumble.volume(4, Loudness.CEILING_LUFS), 1e-6, "minecraft does not play louder than the file");
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
        assertEquals(1, r.pitch(1), 1e-12, "empty loop - tone 1");
    }

    /** The explosion ended and the roar went away; the hum without members is silent. */
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
     * The energy profile of a recording ({@link Envelope#energy}) is its real energy and duration: the sum of tick × step is the energy of the entire
     *  recording, there is no strike before it sounds; the ceiling envelope ({@link Envelope#of}) rises 350 ms before the impact,
     *  and for the event - according to the longest option: for the hum this would give rise before the impact and an alien tail.
     */
    @Test
    void energyProfileIsTheRecordingsOwn() {
        int rate = 44100;
        float[] x = new float[2 * rate];
        Random rnd = new Random(1);
        // silence, shock 50 ms in the first second, silence
        for (int i = rate; i < rate + rate / 20; i++) x[i] = (float) rnd.nextGaussian() * 0.5f;
        float[] e = Envelope.energy(x, rate), max = Envelope.of(x, rate);
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[i];
        Envelope.kWeight(y, rate);
        double total = 0;
        for (double v : y) total += v * v;
        double sum = 0;
        for (float v : e) sum += v * Envelope.STEP * rate;
        assertEquals(total, sum, 1e-3 * total, "write energy");
        int before = 20 - 4;
        assertEquals(0, e[before], 1e-12, "until the impact - silence");
        assertTrue(max[before] > 0, "ceiling envelope - already with impact");
        assertTrue(e[20] > 0);
        // option is twice as long: the event envelope has its tail
        float[] longer = new float[4 * rate];
        System.arraycopy(x, 0, longer, 0, x.length);
        for (int i = 2 * rate; i < longer.length; i++) longer[i] = (float) rnd.nextGaussian() * 0.05f;
        float[] event = Envelopes.max(max, Envelope.of(longer, rate));
        assertTrue(event.length > 1.9 * max.length && event[max.length + 5] > 0, "event envelope - according to the longest option");
    }
}
