package ua.zentix.incoming.client.sound;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Rumble of blasts without engine voices (without Minecraft, covered by a unit test): those yielding slots
 * ({@link BlastVoices}) or never admitted play as a sum in {@link #QUADRANTS} loops around the ear, rather than
 * their own one-shots — like fire roar ({@link FireRoar}) and nuclear destruction ({@link Clatter}). Blasts at
 * different sites are incoherent, so powers add; loop is noise with far-perspective tail spectrum
 * ({@code blast.rumble}, {@code build_sounds.py rumble}), gain is square root of summed members each engine tick:
 * <ul>
 * <li>member is a blast in the mix ({@link Mix}): its audible share k_b² × its voices' energy from playing
 * recordings' profiles ({@link Mix.Blast#voiced}, {@link Envelope#energy}: momentary loudness of the seed-selected
 * recording over its actual duration, without lookahead or variant maximum — ceiling envelope would raise
 * rumble before member impact and give it the longest variant's tail); rumble is not mixed again — blast
 * power is already there;</li>
 * <li>voices still playing and fading carry their share themselves: member is all the blast would play with
 * voices, minus f² × what they play (f is their gain, {@link #voice}); engine-refused layers join rumble
 * immediately and entirely;</li>
 * <li>direction is cos²/sin² between two neighbouring loops (weights sum to 1); loops are east, south, west, north;</li>
 * <li>loop pitch is power-weighted member pitch (Sachs: Grad recording faster, missile slower), highs likewise
 * power-weighted path highs: a still-playing voice does not affect rumble timbre; no rumble yet (loop starts
 * early) — timbre from those that will enter it.</li>
 * </ul>
 * Fade clock is sound-engine ticks: engine reads voice gain there ({@code SoundEngine.tickNonPaused}), and loop
 * computes its gain in its own tick, not the client tick afterward (rumble would lag voice by a tick, losing
 * up to 3.5 dB in transition).
 * <p>
 * Approximation: all members share one rumble-tail spectrum; attacks are inaudible in rumble (envelope uses
 * 400 ms windows), sparse members leave rumble uneven — only the loop is steady.
 */
final class Rumble {
    /** Quarters around ear (budget, like {@link Quarters#N}). */
    static final int QUADRANTS = 4;
    /** Loop without members for this many ticks releases its channel (budget). */
    static final int HOLD = 20;

    /**
     * Blast in rumble: direction, pitch, power fraction behind ear barrier and its voices' highs; {@code voiced}
     * says which engine voices (layer order, {@link Mix.Blast#voiced}) still play and fade from {@code from} over
     * {@code fade} ticks (null — no voices: whole blast in rumble).
     */
    static final class Member {
        final Mix.Blast blast;
        final double azimuth;
        final float pitch, gain, highs;
        final boolean @Nullable [] voiced;
        final double from;
        final int fade;

        Member(Mix.Blast blast, double azimuth, float pitch, float gain, float highs, boolean @Nullable [] voiced, double from, int fade) {
            this.blast = blast;
            this.azimuth = azimuth;
            this.pitch = pitch;
            this.gain = gain;
            this.highs = highs;
            this.voiced = voiced;
            this.from = from;
            this.fade = fade;
        }

        /**
         * Member power in rumble at engine tick {@code sound}, mix at its own tick, relative to ceiling: everything
         * blast would play through voices, minus f² × what its fading voices still play.
         */
        double power(double sound) {
            double k = blast.factor(), all = blast.voiced(blast.now(), null);
            if (voiced != null) {
                double f = voice(sound, from, fade);
                all -= f * f * blast.voiced(blast.now(), voiced);
            }
            return gain * k * k * Math.max(0, all);
        }

        /** Power after voices fully fade: loop uses it to start early and leave. */
        double potential() {
            double k = blast.factor();
            return gain * k * k * blast.voiced(blast.now(), null);
        }
    }

    /**
     * Gain (amplitude) at {@code now} of a voice yielded at engine tick {@code from}: first tick after yielding
     * stays full (loop not yet present starts next client tick), then fades to zero over {@code fade} ticks.
     */
    static double voice(double now, double from, int fade) {
        if (fade <= 0) return 0;
        return Math.max(0, Math.min(1, 1 - (now - from - 1) / fade));
    }

    private final List<Member> members = new ArrayList<>();
    private final double[] power = new double[QUADRANTS], potential = new double[QUADRANTS], pitch = new double[QUADRANTS],
            highs = new double[QUADRANTS], soonPitch = new double[QUADRANTS], soonHighs = new double[QUADRANTS];
    private final int[] quiet = new int[QUADRANTS];

    /** Entire {@code blast} (in mix) enters rumble, from {@code azimuth} relative to ear (atan2(dz, dx)). */
    Member add(Mix.Blast blast, double azimuth, float pitch, float gain, float highs, double now, int fade) {
        return add(blast, azimuth, pitch, gain, highs, null, now, fade);
    }

    /** Blast enters rumble, its {@code voiced} voices fading from engine tick {@code now} over {@code fade} ticks. */
    Member add(Mix.Blast blast, double azimuth, float pitch, float gain, float highs, boolean @Nullable [] voiced, double now, int fade) {
        Member m = new Member(blast, azimuth, pitch, gain, highs, voiced, now, fade);
        members.add(m);
        return m;
    }

    /**
     * Engine tick {@code sound} (mix at its own tick, {@link Mix#at}): remove finished members, loop powers, pitches and highs.
     */
    void tick(double sound) {
        members.removeIf(m -> m.blast.ended(m.blast.now()));
        Arrays.fill(power, 0);
        Arrays.fill(potential, 0);
        Arrays.fill(pitch, 0);
        Arrays.fill(highs, 0);
        Arrays.fill(soonPitch, 0);
        Arrays.fill(soonHighs, 0);
        double sector = 2 * Math.PI / QUADRANTS;
        for (Member m : members) {
            double e = m.power(sound), full = m.potential();
            if (!(full > 0)) continue;
            double u = (m.azimuth < 0 ? m.azimuth + 2 * Math.PI : m.azimuth) / sector;
            int q0 = (int) Math.floor(u) % QUADRANTS, q1 = (q0 + 1) % QUADRANTS;
            double frac = u - Math.floor(u), c = Math.cos(frac * Math.PI / 2), s = Math.sin(frac * Math.PI / 2);
            put(q0, e * c * c, full * c * c, m);
            put(q1, e * s * s, full * s * s, m);
        }
        // playing rumble timbre follows what plays in it; a just-yielded, still-full voice does not affect it.
        // No rumble yet but members exist (loop starts early) — timbre from those that will enter it
        for (int q = 0; q < QUADRANTS; q++) {
            if (power[q] > 0) {
                pitch[q] /= power[q];
                highs[q] /= power[q];
            } else if (potential[q] > 0) {
                pitch[q] = soonPitch[q] / potential[q];
                highs[q] = soonHighs[q] / potential[q];
            } else {
                pitch[q] = 1;
                highs[q] = 1;
            }
        }
    }

    private void put(int q, double e, double full, Member m) {
        power[q] += e;
        potential[q] += full;
        pitch[q] += e * m.pitch;
        highs[q] += e * m.highs;
        soonPitch[q] += full * m.pitch;
        soonHighs[q] += full * m.highs;
    }

    /** Game power of loop {@code q} after {@link #tick}, relative to ceiling (like {@link Mix.Blast#heard}). */
    double power(int q) {
        return power[q];
    }

    /** Loop {@code q} power when its members' voices fade. */
    double potential(int q) {
        return potential[q];
    }

    /**
     * Play loop {@code q}: rumble has something to play — whole sum {@code total} gain (from all quarters'
     * {@link #potential}, {@link #potentialTotal}) is positive — and quarter has a share. Rumble members are blasts
     * audible at ear (decided by their sound, {@link BlastSounds}); no loop file-volume threshold here: remixing
     * changes it (review R11). Per-loop thresholds would remove a member between two loops depending on azimuth
     * (review R9).
     */
    boolean plays(int q, float total) {
        return potential[q] > 0 && total > 0;
    }

    /**
     * Hold loop {@code q} (once per client tick): playing ({@link #plays}: entire rumble audible once voices fade,
     * quarter has a share), or silent for no more than {@link #HOLD} ticks.
     */
    boolean keep(int q, float total) {
        quiet[q] = plays(q, total) ? 0 : quiet[q] + 1;
        return quiet[q] <= HOLD;
    }

    /** Loop {@code q} direction from ear: azimuth atan2(dz, dx). */
    static double azimuth(int q) {
        return 2 * Math.PI * q / QUADRANTS;
    }

    float pitch(int q) {
        return (float) Math.max(BlastMix.PITCH_MIN, Math.min(BlastMix.PITCH_MAX, pitch[q]));
    }

    float highs(int q) {
        return (float) highs[q];
    }

    /** Loop gain (0..1) for a {@code fileLufs} file to play at {@code power} relative to ceiling. */
    static float volume(double power, double fileLufs) {
        return (float) Math.min(1, Math.sqrt(Math.max(0, power) * Mix.CEILING / Envelope.meanSquare(fileLufs)));
    }

    /** All-rumble power once its members' voices fade. */
    double potentialTotal() {
        double s = 0;
        for (double p : potential) s += p;
        return s;
    }

    /** All-rumble power relative to ceiling. */
    double total() {
        double s = 0;
        for (double p : power) s += p;
        return s;
    }

    int size() {
        return members.size();
    }

    void clear() {
        members.clear();
        Arrays.fill(power, 0);
        Arrays.fill(potential, 0);
        Arrays.fill(quiet, 0);
    }
}
