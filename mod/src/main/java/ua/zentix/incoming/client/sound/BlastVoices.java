package ua.zentix.incoming.client.sound;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which blasts hold engine voices — one-shots: perspectives, chest hit, distant rumble, bomb sounds (without
 * Minecraft, covered by a unit test). Like {@link VoiceBudget} for engines, but a one-shot cannot be released and
 * acquired again: it yields its whole slot, and the yielding blast continues in rumble ({@link Rumble}) with the
 * same power in the mix ({@link Mix}).
 * <p>
 * Channels are shared: when static channels run out, the engine silently refuses new sounds ({@code SoundEngine.play}:
 * “Failed to create new sound handle” appears only in development), including the near blast. Device sets their
 * count ({@code Library.init}: OpenAL mono sources minus streaming clamp(√mono, 2, 8)); OpenAL Soft shipped with
 * Minecraft 1.21.1 has 255 mono and 1 stereo — 247 static and 8 streaming. Our {@link OwnSources#MAX} sources
 * (echoes, impact at the ear) come from the same 256 OpenAL sources, so actual static count is at most
 * 256 − 8 − 12 = 236. Of these:
 * <ul>
 * <li>projectile engines — {@link VoiceBudget#CAP};</li>
 * <li>blast rumble — {@link Rumble#QUADRANTS} loops, crater-fire roar — {@link Quarters#N} (sum of all fires,
 * {@link CraterFire});</li>
 * <li>the nuke ({@link #NUKE}): four fire-roar loops and wind feeding the fire ({@code NukeAfter}), tinnitus, two sirens;</li>
 * <li>the rest of the world ({@link #WORLD}, estimate): vanilla (rain, mobs, footsteps, blocks), other mods (Create
 * machines), our unbudgeted world sounds ({@link Sources}: ruins, substations, sirens) — their count is unlimited,
 * so beyond the estimate, use free engine channels at impact ({@link #admit}, {@code free}) and reserve for the
 * loudest ({@link #reserve}). Crater ejecta fall is a blast one-shot within this budget;</li>
 * <li>yielded voices hold channels while fading and for another tick until the engine returns them — {@link #HEADROOM};</li>
 * <li>the remainder for blast one-shots: {@link #CAP}, ~96 (24 near blasts with 4 simultaneous voices each).</li>
 * </ul>
 * A voice channel stays occupied while the engine holds it ({@link Sound#held}): a sound's {@code stop()} is only a
 * flag; the engine stops its channel next tick and returns it to the pool in a subsequent sound-thread task
 * ({@code SoundEngine.tickNonPaused}, {@code ChannelAccess.scheduleTick}). Thus eviction provides no slot now: a
 * new sound plays in channels already free at the engine; yielded voices return theirs later, restoring the world's
 * {@link #SPARE} reserve.
 * <p>
 * No slot — voice goes to the blast with higher mix loudness (Q_b, {@link Mix.Blast#heard}): quietest yields, usually
 * a tail (attack is louder than its tail), and a distant tail is rumble anyway. During the attack — first
 * {@link #ATTACK} ticks, the BS.1770 momentary-loudness window — a new blast evicts only if {@link #KEEP} times
 * louder: an audible attack cut into rumble sounds like a glitch; a tail does not. A new blast gets a voice, even
 * the loudest, only if all three conditions hold:
 * <ol>
 * <li>engine currently has at least the required free channels (eviction cannot free them this tick); a newcomer
 * quieter than a playing blast must leave {@link #GROUP} in reserve;</li>
 * <li>including it, blast channels, counting yielded voices not yet returned, stay within {@link #cap} +
 * {@link #HEADROOM} (remaining headroom fits its group);</li>
 * <li>yielding voices quieter than it (attack: {@link #KEEP} times quieter) restore both our ceiling and the world's
 * {@link #SPARE} reserve once their channels return; for a newcomer quieter than a playing blast, also
 * {@link #GROUP} reserved for the loudest.</li>
 * </ol>
 * The {@link #GROUP} free-channel reserve belongs to the loudest: it may take it, and quieter voices restore it
 * (as they would yield on its admission). A newcomer quieter than a playing blast cannot take it: the reserve would
 * immediately take its voice away mid-attack even though the world took nothing (review R7); it plays in rumble
 * immediately. Only the world exceeding its estimate spends the reserve — then {@link #reserve} yields in advance
 * those voices that the loudest would evict.
 * Otherwise it plays in rumble with the same power. This is channel virtualization, not a hearing law: the
 * “impact or rumble” threshold from attack audibility above background (temporal masking) is not computed — while
 * channels suffice, an impact remains an impact, even the quietest.
 * <p>
 * Clock {@code now} here is sound-engine ticks ({@code SoundEngine.tickCount}): voices fade and rumble rises by it.
 */
final class BlastVoices {
    /** OpenAL Soft default OpenAL sources (alsoft {@code sources}, {@code alcOpenDevice} in 1.23.1): 255 mono and 1 stereo. */
    static final int MONO = 255, STEREO = 1;
    /** Nuke channels: four fire-roar loops, wind feeding the fire, tinnitus, two sirens. */
    static final int NUKE = 4 + 1 + 1 + 2;
    /**
     * Rest of the world, estimate: vanilla during combat — rain (new sound every few ticks, lasting 1–2 s), footsteps
     * and mobs; Create machines; our world sounds and crater fires. Estimate, not measurement: free-channel check
     * handles anything beyond it.
     */
    static final int WORLD = 64;
    /**
     * Channels of yielded voices while fading ({@link #FADE} plus a full-volume tick) and awaiting engine return
     * (one tick): three near blasts with 4 voices each during these ~5 ticks (budget).
     */
    static final int HEADROOM = 12;
    /** Free engine channels to leave for the world (its sounds this tick; budget). */
    static final int SPARE = 8;
    /** Maximum voices per blast: near, mid, far perspectives and chest hit. */
    static final int GROUP = 4;
    /** Yielded voice fade ticks (no click), after a full-volume tick ({@link Rumble#voice}; presentation). */
    static final int FADE = 3;
    /** Blast attack ticks — momentary-loudness window (BS.1770, 400 ms). */
    static final int ATTACK = 8;
    /** How many times louder (power, +3 dB) a newcomer must be than an attack to take its slot (presentation). */
    static final double KEEP = 2;
    /** Blast one-shot channels with default OpenAL Soft. */
    static final int CAP = cap(MONO, STEREO);

    /** Blast one-shot on an engine channel. */
    interface Sound {
        /** Fade over {@link #FADE} ticks (after a full-volume tick) and go away. */
        void fade();

        /** Silence (reset). */
        void cut();

        /** Finished playing or removed by the engine. */
        boolean over();

        /** Engine still holds its channel (a stopped sound holds it until returned to the pool). */
        default boolean held() {
            return !over();
        }
    }

    /** Voiced blast: its sounds (i-th is i-th engine-voice blast layer) and what rumble needs if it yields. */
    static final class Voice {
        final Mix.Blast blast;
        final List<? extends Sound> sounds;
        final double born, azimuth;
        final float pitch, gain, highs;
        /** Sounds the engine accepted; all until {@link #started}. */
        boolean[] took;
        /** Fades from this tick (yielded); NaN — playing. */
        double fadeFrom = Double.NaN;
        Rumble.Member member;

        Voice(Mix.Blast blast, List<? extends Sound> sounds, double born, double azimuth, float pitch, float gain, float highs) {
            this.blast = blast;
            this.sounds = sounds;
            this.born = born;
            this.azimuth = azimuth;
            this.pitch = pitch;
            this.gain = gain;
            this.highs = highs;
            this.took = new boolean[sounds.size()];
            java.util.Arrays.fill(took, true);
        }

        boolean fading() {
            return !Double.isNaN(fadeFrom);
        }

        /** Channels currently held at the engine. */
        int held() {
            int n = 0;
            for (Sound s : sounds) if (s.held()) n++;
            return n;
        }
    }

    private final List<Voice> voices = new ArrayList<>();
    private final Rumble rumble;
    private int cap = CAP;
    /** Scenario-line counters: voices requested, engine accepted, refused; blasts straight to rumble, yielded. */
    int requested, started, refused, toRumble, yielded;

    BlastVoices(Rumble rumble) {
        this(rumble, CAP);
    }

    /** {@code cap} — blast one-shot channels (capacity-constrained checks). */
    BlastVoices(Rumble rumble, int cap) {
        this.rumble = rumble;
        this.cap = cap;
    }

    /**
     * Blast one-shot channels on a device with {@code mono} mono and {@code stereo} stereo sources: engine static
     * channels ({@code Library.init}), how many OpenAL can supply alongside streaming and our own sources, minus
     * engines, rumble, the nuke, world and headroom for fading voices.
     */
    static int cap(int mono, int stereo) {
        return Math.max(4, reach(mono, stereo) - VoiceBudget.CAP - Rumble.QUADRANTS - Quarters.N - NUKE - WORLD - HEADROOM);
    }

    /**
     * Static engine channels OpenAL can actually supply: {@code Library.init} pool (mono minus streaming
     * clamp(√mono, 2, 8), from 8 to 255), but at most device sources without streaming and ours ({@link OwnSources#MAX}).
     */
    static int reach(int mono, int stereo) {
        int streaming = Math.max(2, Math.min(8, (int) Math.sqrt(mono))), pool = Math.max(8, Math.min(255, mono - streaming));
        return Math.min(pool, mono + stereo - streaming - OwnSources.MAX);
    }

    /** Sound device changed (engine restart): ceiling follows its sources. */
    void device(int mono, int stereo) {
        cap = cap(mono, stereo);
    }

    int cap() {
        return cap;
    }

    /**
     * New {@code blast} (layers already in the mix), requiring as many voices as {@code sounds}: voice or rumble.
     * A newcomer plays in engine channels free now; if our ceiling ({@link #cap}) or the world's reserve
     * ({@link #SPARE}, after yielded channels return) has no room, quietest voices yield (fade, their power
     * transitioning to rumble); if none yield, newcomer goes straight to rumble. A newcomer quieter than a playing
     * blast cannot take {@link #GROUP} free channels: these are reserved for the loudest.
     *
     * @param now     sound-engine tick
     * @param azimuth blast direction from the ear, atan2(dz, dx)
     * @param pitch   pitch of its recordings
     * @param gain    power fraction passing the barrier at the ear (voice uses an EFX filter)
     * @param highs   path high fraction
     * @param free    free static engine channels now; {@link Integer#MAX_VALUE} — unknown
     * @return voice — play its sounds; null — blast plays in rumble
     */
    Voice admit(Mix.Blast blast, List<? extends Sound> sounds, double now, double azimuth, float pitch, float gain, float highs, int free) {
        prune(now);
        int need = sounds.size();
        long steady = 0, releasing = 0;
        double loudest = 0;
        for (Voice v : voices) {
            if (v.fading()) releasing += v.held();
            else {
                steady += v.held();
                loudest = Math.max(loudest, v.blast.heard());
            }
        }
        // GROUP free channels reserve the loudest ({@link #reserve}): quieter than a playing blast cannot take them,
        // or the reserve would immediately take its voice back (review R7)
        if (blast.heard() < loudest) free -= GROUP;
        // channels are needed now, eviction returns them to the engine only after ticks: none now — rumble
        if (need > free) return rumble(blast, now, azimuth, pitch, gain, highs);
        // yielded voices hold channels while fading and awaiting engine return: no headroom left — rumble
        if (steady + releasing + need > (long) cap + HEADROOM) return rumble(blast, now, azimuth, pitch, gain, highs);
        double fresh = blast.heard();
        List<Voice> quieter = new ArrayList<>();
        for (Voice v : voices) if (!v.fading() && priority(v, now) < fresh) quieter.add(v);
        quieter.sort(Comparator.comparingDouble(v -> priority(v, now)));
        // own ceiling and world reserve after yielded channels return: quietest voices yield
        List<Voice> evict = new ArrayList<>();
        long freed = 0;
        for (Voice v : quieter) {
            if (steady - freed + need <= cap && (long) free - need + releasing + freed >= SPARE) break;
            evict.add(v);
            freed += v.held();
        }
        if (steady - freed + need > cap || (long) free - need + releasing + freed < SPARE) return rumble(blast, now, azimuth, pitch, gain, highs);
        for (Voice v : evict) give(v, now);
        Voice v = new Voice(blast, sounds, now, azimuth, pitch, gain, highs);
        voices.add(v);
        requested += need;
        return v;
    }

    /**
     * Mix loudness with which a voice holds its slot; recording finished (sound holds channel for up to 20 extra
     * ticks) — zero: mix no longer counts it, and its share there is the old one.
     */
    private static double priority(Voice v, double now) {
        return v.blast.ended(v.blast.now()) ? 0 : v.blast.heard() * (now - v.born < ATTACK ? KEEP : 1);
    }

    private Voice rumble(Mix.Blast blast, double now, double azimuth, float pitch, float gain, float highs) {
        rumble.add(blast, azimuth, pitch, gain, highs, now, 0);
        toRumble++;
        return null;
    }

    /** Engine accepted the first {@code taken} sounds of voice {@code v} (checks). */
    void started(Voice v, int taken, double now) {
        boolean[] took = new boolean[v.sounds.size()];
        for (int i = 0; i < took.length; i++) took[i] = i < taken;
        started(v, took, now);
    }

    /**
     * Engine accepted {@code took} sounds of voice {@code v}; if not all, voice yields: accepted sounds fade while
     * refused power joins the rumble immediately (they do not play at all).
     */
    void started(Voice v, boolean[] took, double now) {
        v.took = took.clone();
        int taken = 0;
        for (boolean t : took) if (t) taken++;
        started += taken;
        refused += took.length - taken;
        if (taken < took.length) give(v, now);
    }

    /** Voice yields: playing sounds fade, blast power transitions to rumble over the same time (nonplaying sounds immediately). */
    void give(Voice v, double now) {
        if (v.fading()) return;
        v.fadeFrom = now;
        v.sounds.forEach(Sound::fade);
        v.member = rumble.add(v.blast, v.azimuth, v.pitch, v.gain, v.highs, v.took, now, FADE);
        yielded++;
    }

    /**
     * Reserve for the loudest, once per tick: free engine channels (including those yielded voices will soon
     * return) below {@link #SPARE} + {@link #GROUP} means the world consumed the reserve; quietest own voices yield
     * early until restored, so a later near blast plays in those channels rather than rumble. Channels cannot be
     * freed in the same tick (engine returns them next tick), hence in advance, not on impact. Returns how many yielded.
     * <p>
     * Only voices that a blast as loud as the current loudest would evict on admission ({@link #admit}) yield early:
     * quieter than it (attack: {@link #KEEP} times) or recording finished. The loudest itself never yields: reserve
     * is for loud sound, already playing; a near blast that spent the reserve on its attack would be evicted by
     * that very reserve (review R7). A louder newcomer evicts its tail on admission itself if engine channels are
     * free now; if none (world consumed all, and the loudest is our only voice), newcomer goes to rumble: giving up
     * an audible voice early for a blast that does not exist yet certainly loses audible sound. Our quiet sounds
     * do not spend reserve ({@link #admit}), so only the world or the loudest that used it can force yielding.
     */
    int reserve(double now, int free) {
        prune(now);
        long releasing = 0;
        double loudest = 0;
        for (Voice v : voices) {
            if (v.fading()) releasing += v.held();
            else loudest = Math.max(loudest, v.blast.heard());
        }
        if ((long) free + releasing >= SPARE + GROUP) return 0;
        List<Voice> quiet = new ArrayList<>();
        for (Voice v : voices) if (!v.fading() && (priority(v, now) < loudest || v.blast.ended(v.blast.now()))) quiet.add(v);
        quiet.sort(Comparator.comparingDouble(v -> priority(v, now)));
        int n = 0;
        long freed = 0;
        for (Voice v : quiet) {
            if ((long) free + releasing + freed >= SPARE + GROUP) break;
            freed += v.held();
            give(v, now);
            n++;
        }
        return n;
    }

    /** Remove voices whose channels the engine has returned. */
    void prune(double now) {
        voices.removeIf(v -> v.held() == 0);
    }

    /** Channels blast voices currently hold: playing, fading and stopped until engine return. */
    int channels() {
        int n = 0;
        for (Voice v : voices) n += v.held();
        return n;
    }

    /** Channels of playing (unyielded) voices. */
    int steady() {
        int n = 0;
        for (Voice v : voices) if (!v.fading()) n += v.held();
        return n;
    }

    List<Voice> voices() {
        return voices;
    }

    void reset() {
        voices.forEach(v -> v.sounds.forEach(Sound::cut));
        voices.clear();
        requested = started = refused = toRumble = yielded = 0;
    }
}
