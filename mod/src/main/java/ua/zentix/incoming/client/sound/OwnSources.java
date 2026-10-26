package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.EXTEfx;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.nuclear.Deafness;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleSupplier;

/**
 * Our own OpenAL sources for terrain echoes ({@link EchoMix}) and nuke-front impact ({@link BlastPulse}): bypass
 * Minecraft's sound engine so Sound Physics Remastered, which sets EFX filters on all engine sources, does not
 * add its reflections to reflections, and so a track of our own samples (rather than a resource file) starts
 * exactly in its frame.
 * <p>
 * Source is 3 blocks from the ear toward its compass direction (like {@link ClientSounds#atEar}), with no distance
 * attenuation; category volume is “Master” (overall volume is listener gain, set by engine). Direct and send EFX
 * filters apply barriers in that direction ({@link SoundFilters#openToward}: Sound Physics does not touch our own
 * sources, so needed with it too) and deafness ({@link Deafness}); send goes to our own reverb slot (tail over
 * tracks). Without EFX, no barriers, deafness or tail.
 * <p>
 * Voice time comes from {@link Anchor}: when engine actually started the direct sound, not its request tick.
 * <p>
 * All on client thread (frame, tick); process has one OpenAL context, made current by engine. AL errors are shared
 * with engine sound thread ({@code alGetError} could consume its error or give it ours), so check success on the
 * objects themselves ({@code alIsSource}, {@code alIsBuffer}…). Engine restart (device change, F3+T) deletes the
 * context and all objects — forget sources without AL calls ({@link #engineLoaded}).
 */
public final class OwnSources {
    /** Maximum own sources: rest belong to engine (OpenAL Soft has 256 total; budget). */
    public static final int MAX = 12;
    /** Start lag above this (s): begin in the middle, not the start (presentation). */
    private static final double LATE = 0.03;
    /**
     * A voice played whole ({@code whole}) more than this late (s) is skipped: a front impact a second after direct
     * sound would be a second impact (presentation).
     */
    private static final double WHOLE_LATE = 1;
    /** Ticks to await the engine starting anchor sound; if not, anchor by tick (margin). */
    private static final double ANCHOR_WAIT = 40;
    /** Voice filter changes when gain or highs move farther than this (EFX-call budget). */
    private static final float FILTER_STEP = 0.005f;

    /** Clock used by waiting voice: ticks with frame fraction. */
    @FunctionalInterface
    public interface Clock {
        double ticks(float partialTick);
    }

    /**
     * Anchor awaited by voices: actual engine start of {@code sound} — {@link PlaySoundSourceEvent} arrives from
     * sound thread immediately after play. A non-preloaded recording decodes on first sound; a headless client's
     * tick runs once per frame: direct sound starts later than its tick, so tick-based echoes would precede it.
     * Set anchor <b>before</b> {@code play}: with a buffer engine already holds, sound starts immediately on the
     * sound thread, before {@code play} returns. Engine refused it (no channels, zero volume) or never started it
     * (another mod replaced sound) — anchor at {@code tick}.
     */
    public static final class Anchor {
        final Object owner;
        @Nullable
        final SoundInstance sound;
        final Clock clock;
        final double tick;
        /** Sound-start {@link System#nanoTime()}, 0 — not started yet (written by sound thread). */
        volatile long started;
        double zero = Double.NaN;
        /** Anchor follows sound start (not tick). */
        boolean heard;

        Anchor(Object owner, @Nullable SoundInstance sound, Clock clock, double tick) {
            this.owner = owner;
            this.sound = sound;
            this.clock = clock;
            this.tick = tick;
        }

        /** Anchor on the clock (ticks), NaN — not known yet. */
        public double zero() {
            return zero;
        }

        /** How much later than its tick the start is, s (NaN — not known yet). */
        public double lateSeconds() {
            return (zero - tick) / 20;
        }

        /** Anchor follows sound start (otherwise tick). */
        public boolean heard() {
            return heard;
        }
    }

    /** One voice: track, direction, time and barrier in its direction. */
    public static final class Voice {
        final Object owner;
        final Anchor anchor;
        final double after;
        final double azimuth;
        final float gain;
        final short[] pcm;
        final int rate;
        /** Play from start even if late: front jump is in first milliseconds; its absence makes the middle silent. */
        final boolean whole;
        /** Barrier gain and highs toward voice (1 — open); recompute when voice starts. */
        float blockedGain, blockedHighs;
        int source, buffer;
        boolean started, done;
        /** Was playing when game paused (resume after pause; sounds finished before it do not restart). */
        boolean held;
        /** Current voice-filter values (NaN — nothing yet). */
        float filterGain = Float.NaN, filterHighs = Float.NaN;
        /** Hearing used by this voice (wave's own impact uses before-wave hearing, {@link Deafness#before}); null — current hearing. */
        float @Nullable [] heard;
        /** Blast-mix factor ({@link Mix}), every frame; null — own gain. */
        @Nullable
        DoubleSupplier mix;
        /** Gain currently set on source. */
        float applied;

        Voice(Object owner, Anchor anchor, double after, double azimuth, float gain, short[] pcm, int rate, boolean whole, float open) {
            this.owner = owner;
            this.whole = whole;
            this.anchor = anchor;
            this.after = after;
            this.azimuth = azimuth;
            this.gain = gain;
            this.pcm = pcm;
            this.rate = rate;
            obstruct(open);
        }

        void obstruct(float open) {
            blockedGain = SoundFilters.blockedGain(open);
            blockedHighs = SoundFilters.blockedHighs(open);
        }
    }

    private static final List<Voice> VOICES = new ArrayList<>();
    /** Anchors still awaited (sound thread writes sound start). */
    private static final List<Anchor> ANCHORS = new CopyOnWriteArrayList<>();
    /** Direct-path filter and reverb slot: 0 — not created, −1 — no EFX. */
    private static int filter, slot, effect;
    private static boolean paused;
    private static int started;

    private OwnSources() {}

    /**
     * Anchor for {@code owner}'s voices: start of {@code sound}, to be handed to engine at {@code tick} on
     * {@code clock} (null — tick-based anchor). Call before {@code play} for this sound.
     */
    public static Anchor anchor(Object owner, @Nullable SoundInstance sound, Clock clock, double tick) {
        Anchor a = new Anchor(owner, sound, clock, tick);
        if (sound == null) a.zero = tick;
        else ANCHORS.add(a);
        return a;
    }

    /** Own sources currently free. */
    public static int free() {
        return MAX - VOICES.size();
    }

    /** Sound thread: engine started sound — anchor for those awaiting it. */
    public static void onSource(PlaySoundSourceEvent e) {
        if (ANCHORS.isEmpty()) return;
        long now = System.nanoTime();
        for (Anchor a : ANCHORS) if (a.sound == e.getSound() && a.started == 0) a.started = now;
    }

    /**
     * Queue a track: plays {@code after} ticks after {@code anchor}, from {@code azimuth} (barrier toward it sampled
     * at ear when voice starts). {@code owner} owns the voice (remove all with {@link #stop}).
     *
     * @return false — no slots or OpenAL not ready
     */
    public static boolean play(Object owner, Anchor anchor, double after, double azimuth, float gain, short[] pcm, int rate) {
        return play(owner, anchor, after, azimuth, gain, pcm, rate, false);
    }

    /**
     * Like {@link #play(Object, Anchor, double, double, float, short[], int)}; {@code whole} starts a late voice at
     * the beginning rather than middle (a client frame can exceed the front duration in the track).
     */
    public static boolean play(Object owner, Anchor anchor, double after, double azimuth, float gain, short[] pcm, int rate, boolean whole) {
        return play(owner, anchor, after, azimuth, gain, pcm, rate, whole, false);
    }

    /**
     * Like {@link #play(Object, Anchor, double, double, float, short[], int, boolean)}; {@code before} marks the wave's
     * own impact: heard with the ear as it was before the wave ({@link Deafness#before}).
     */
    public static boolean play(Object owner, Anchor anchor, double after, double azimuth, float gain, short[] pcm, int rate, boolean whole,
                               boolean before) {
        return play(owner, anchor, after, azimuth, gain, pcm, rate, whole, before, null);
    }

    /**
     * Like {@link #play(Object, Anchor, double, double, float, short[], int, boolean, boolean)}; {@code mix} is blast-mix
     * factor ({@link Mix}): voice gain is {@code gain} × it, every frame.
     */
    public static boolean play(Object owner, Anchor anchor, double after, double azimuth, float gain, short[] pcm, int rate, boolean whole,
                               boolean before, @Nullable DoubleSupplier mix) {
        if (pcm.length == 0 || VOICES.size() >= MAX || !ready()) return false;
        // barrier sampled at voice start (frame): it waits up to ~24 s before that
        Voice v = new Voice(owner, anchor, after, azimuth, gain, pcm, rate, whole, 1);
        if (before) v.heard = new float[]{Deafness.gain(), Deafness.highs()};
        v.mix = mix;
        v.applied = mix == null ? gain : (float) (gain * mix.getAsDouble());
        try {
            v.buffer = AL10.alGenBuffers();
            if (v.buffer == 0 || !AL10.alIsBuffer(v.buffer)) {
                v.buffer = 0;
                spent();
                return false;
            }
            ShortBuffer data = BufferUtils.createShortBuffer(pcm.length).put(pcm).flip();
            AL10.alBufferData(v.buffer, AL10.AL_FORMAT_MONO16, data, rate);
            v.source = AL10.alGenSources();
            if (v.source == 0 || !AL10.alIsSource(v.source) || AL10.alGetBufferi(v.buffer, AL10.AL_SIZE) <= 0) {
                if (v.source != 0 && !AL10.alIsSource(v.source)) v.source = 0;
                free(v);
                spent();
                return false;
            }
            AL10.alSourcei(v.source, AL10.AL_BUFFER, v.buffer);
            AL10.alSourcei(v.source, AL10.AL_LOOPING, AL10.AL_FALSE);
            AL10.alSourcei(v.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            // no distance attenuation: gain is ours (like Channel.disableAttenuation)
            AL10.alSourcei(v.source, 0xD000, AL10.AL_NONE);
            AL10.alSourcef(v.source, AL10.AL_MAX_GAIN, 16);
            AL10.alSourcef(v.source, AL10.AL_GAIN, v.applied);
            if (effects()) filter(v, v.heard == null ? Deafness.gain() : v.heard[0], v.heard == null ? Deafness.highs() : v.heard[1]);
        } catch (RuntimeException | LinkageError e) {
            Incoming.LOG.warn("Own OpenAL source failed: {}", e.toString());
            free(v);
            return false;
        }
        VOICES.add(v);
        return true;
    }

    /** Own slot's reverb tail: decay time and send fraction. */
    public static void reverb(double t60, float send) {
        if (!ready() || !effects() || slot <= 0) return;
        EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_TIME, (float) Math.max(0.1, Math.min(20, t60)));
        EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
        EXTEfx.alAuxiliaryEffectSlotf(slot, EXTEfx.AL_EFFECTSLOT_GAIN, Math.max(0, Math.min(1, send)));
    }

    /** Frame: anchors, start due tracks, ear-relative position, filter, pause; remove finished voices. */
    public static void frame(RenderFrameEvent.Pre e) {
        if (VOICES.isEmpty() && ANCHORS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        float partial = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        boolean pause = mc.isPaused();
        if (!pause) anchors(mc, partial);
        if (VOICES.isEmpty()) {
            paused = pause;
            return;
        }
        if (!ready()) {
            VOICES.clear();
            return;
        }
        if (pause) {
            // paused: stop playing voices; only these resume afterward
            if (!paused) {
                for (Voice v : VOICES) {
                    if (v.started && AL10.alGetSourcei(v.source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
                        AL10.alSourcePause(v.source);
                        v.held = true;
                    }
                }
            }
            paused = true;
            return;
        }
        if (paused) {
            for (Voice v : VOICES) {
                if (v.held) AL10.alSourcePlay(v.source);
                v.held = false;
            }
            paused = false;
        }
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        float deafGain = Deafness.gain(), deafHighs = Deafness.highs();
        boolean efx = effects();
        for (Iterator<Voice> it = VOICES.iterator(); it.hasNext(); ) {
            Voice v = it.next();
            if (v.started && AL10.alGetSourcei(v.source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                free(v);
                it.remove();
                continue;
            }
            AL10.alSource3f(v.source, AL10.AL_POSITION, (float) (ear.x + Math.cos(v.azimuth) * 3), (float) ear.y,
                    (float) (ear.z + Math.sin(v.azimuth) * 3));
            if (efx) filter(v, v.heard == null ? deafGain : v.heard[0], v.heard == null ? deafHighs : v.heard[1]);
            if (v.mix != null) {
                float g = (float) (v.gain * v.mix.getAsDouble());
                if (Math.abs(g - v.applied) > 0.002f * Math.max(v.gain, 1e-6f)) {
                    AL10.alSourcef(v.source, AL10.AL_GAIN, g);
                    v.applied = g;
                }
            }
            if (v.started) continue;
            double at = v.anchor.zero + v.after;
            if (Double.isNaN(at)) continue;
            double late = (v.anchor.clock.ticks(partial) - at) / 20;
            if (late < 0) continue;
            if (late > (v.whole ? WHOLE_LATE : v.pcm.length / (double) v.rate)) {
                free(v);
                it.remove();
                continue;
            }
            if (late > LATE && !v.whole) AL10.alSourcef(v.source, AL11.AL_SEC_OFFSET, (float) late);
            // voice waited up to ~24 s: player may have entered or left a house — barrier at ear's current position
            v.obstruct(SoundFilters.openToward(ear, v.azimuth));
            if (efx) filter(v, v.heard == null ? deafGain : v.heard[0], v.heard == null ? deafHighs : v.heard[1]);
            AL10.alSourcePlay(v.source);
            v.started = true;
            started++;
        }
    }

    /**
     * Consume error from our failure: AL errors are shared with sound thread; its next check ({@code Channel.create})
     * would treat it as its own, losing an engine source already allocated.
     */
    private static void spent() {
        AL10.alGetError();
    }

    /**
     * Voice filter on direct path and send: barrier in its direction and hearing ({@link Deafness}: gain and high
     * fraction). Source copies filter parameters when set: one filter object for all voices.
     */
    private static void filter(Voice v, float deafGain, float deafHighs) {
        float gain = v.blockedGain * deafGain, highs = v.blockedHighs * Math.max(0.01f, deafHighs);
        if (Math.abs(gain - v.filterGain) < FILTER_STEP && Math.abs(highs - v.filterHighs) < FILTER_STEP) return;
        v.filterGain = gain;
        v.filterHighs = highs;
        boolean on = gain < 0.999f || highs < 0.999f;
        if (on) {
            EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, gain);
            EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, highs);
        }
        int f = on ? filter : EXTEfx.AL_FILTER_NULL;
        AL10.alSourcei(v.source, EXTEfx.AL_DIRECT_FILTER, f);
        if (slot > 0) AL11.alSource3i(v.source, EXTEfx.AL_AUXILIARY_SEND_FILTER, slot, 0, f);
    }

    /**
     * Anchors whose sound has started (clock now minus its elapsed playing time), was refused by engine
     * ({@code play} refuses immediately: no sound in engine), or did not start before timeout.
     */
    private static void anchors(Minecraft mc, float partial) {
        if (ANCHORS.isEmpty()) return;
        long now = System.nanoTime();
        for (Anchor a : ANCHORS) {
            double clock = a.clock.ticks(partial);
            long started = a.started;
            if (started != 0) {
                a.zero = Math.max(a.tick, clock - (now - started) * 20e-9);
                a.heard = true;
            } else if (clock > a.tick + ANCHOR_WAIT || a.sound != null && !mc.getSoundManager().isActive(a.sound)) {
                a.zero = a.tick;
            }
            if (!Double.isNaN(a.zero)) ANCHORS.remove(a);
        }
    }

    /** Resolve awaited anchors by tick: engine will no longer start the awaited sound. */
    private static void settle() {
        for (Anchor a : ANCHORS) if (Double.isNaN(a.zero)) a.zero = a.tick;
        ANCHORS.clear();
    }

    /** Remove owner's voices and anchors (detonation removed or now in the past). */
    public static void stop(Object owner) {
        ANCHORS.removeIf(a -> a.owner == owner);
        for (Iterator<Voice> it = VOICES.iterator(); it.hasNext(); ) {
            Voice v = it.next();
            if (v.owner != owner) continue;
            if (ready()) free(v);
            it.remove();
        }
    }

    /** Replay rewind, world exit: silence. */
    public static void reset() {
        if (ready()) for (Voice v : VOICES) free(v);
        VOICES.clear();
        settle();
        paused = false;
    }

    /** Sound engine reloaded: old context deleted with sources and filters. */
    static void engineLoaded() {
        VOICES.clear();
        settle();
        filter = slot = effect = 0;
        paused = false;
    }

    /**
     * For {@code SCENARIO} line: voices waiting and playing, total started, voice barriers (strongest and mean
     * gain behind barrier, 1 — open).
     */
    public static String describe() {
        int playing = 0;
        float least = 1, sum = 0;
        for (Voice v : VOICES) {
            if (v.started) playing++;
            least = Math.min(least, v.blockedGain);
            sum += v.blockedGain;
        }
        return String.format(Locale.ROOT, "own %d/%d started %d efx %s blocked %.2f/%.2f", playing, VOICES.size(), started,
                filter > 0 ? "yes" : filter < 0 ? "no" : "-", least, VOICES.isEmpty() ? 1 : sum / VOICES.size());
    }

    /**
     * Device mono and stereo sources ({@code ALC_MONO_SOURCES}, {@code ALC_STEREO_SOURCES}), used by engine to divide
     * channels ({@code Library.getChannelCount}); unspecified by device — engine default, 30 mono; null — no
     * context yet (load event from engine constructor before {@code Library.init}).
     */
    static int @Nullable [] deviceSources() {
        try {
            long context = ALC10.alcGetCurrentContext(), device = context == 0 ? 0 : ALC10.alcGetContextsDevice(context);
            if (device == 0) return null;
            int size = ALC10.alcGetInteger(device, ALC10.ALC_ATTRIBUTES_SIZE);
            if (size > 0) {
                int[] attrs = new int[size];
                ALC10.alcGetIntegerv(device, ALC10.ALC_ALL_ATTRIBUTES, attrs);
                int mono = -1, stereo = 0;
                for (int i = 0; i + 1 < size && attrs[i] != 0; i += 2) {
                    if (attrs[i] == ALC11.ALC_MONO_SOURCES) mono = attrs[i + 1];
                    else if (attrs[i] == ALC11.ALC_STEREO_SOURCES) stereo = attrs[i + 1];
                }
                if (mono > 0) return new int[]{mono, stereo};
            }
            return new int[]{30, 0};
        } catch (RuntimeException | LinkageError e) {
            Incoming.LOG.warn("OpenAL device sources unreadable: {}", e.toString());
            return null;
        }
    }

    private static boolean ready() {
        try {
            return ALC10.alcGetCurrentContext() != 0;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static void free(Voice v) {
        try {
            if (v.source != 0) {
                AL10.alSourceStop(v.source);
                AL10.alSourcei(v.source, AL10.AL_BUFFER, 0);
                AL10.alDeleteSources(v.source);
            }
            if (v.buffer != 0) AL10.alDeleteBuffers(v.buffer);
        } catch (RuntimeException | LinkageError ignored) {
            // context already gone — objects deleted with it
        }
        v.source = v.buffer = 0;
        v.done = true;
    }

    /** EFX filter and slot (created on first need); false — no EFX. */
    private static boolean effects() {
        if (filter == 0) create();
        return filter > 0;
    }

    private static void create() {
        try {
            long device = ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext());
            if (device == 0 || !ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
                filter = -1;
                return;
            }
            // success checked on objects themselves (AL errors shared with sound thread): a type not set reads back as old type
            int f = EXTEfx.alGenFilters();
            if (f == 0 || !EXTEfx.alIsFilter(f)) {
                filter = -1;
                return;
            }
            EXTEfx.alFilteri(f, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
            if (EXTEfx.alGetFilteri(f, EXTEfx.AL_FILTER_TYPE) != EXTEfx.AL_FILTER_LOWPASS) {
                EXTEfx.alDeleteFilters(f);
                filter = -1;
                return;
            }
            filter = f;
            // tail: outdoor reverb — sparse late reflections, highs decay faster; failed to set — no tail
            slot = -1;
            int e = EXTEfx.alGenEffects();
            if (e == 0 || !EXTEfx.alIsEffect(e)) return;
            EXTEfx.alEffecti(e, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
            int s = EXTEfx.alGetEffecti(e, EXTEfx.AL_EFFECT_TYPE) == EXTEfx.AL_EFFECT_REVERB ? EXTEfx.alGenAuxiliaryEffectSlots() : 0;
            if (s == 0 || !EXTEfx.alIsAuxiliaryEffectSlot(s)) {
                EXTEfx.alDeleteEffects(e);
                return;
            }
            effect = e;
            slot = s;
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DENSITY, 0.6f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DIFFUSION, 0.8f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_TIME, 4);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_HFRATIO, 0.5f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_REFLECTIONS_GAIN, 0.05f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_LATE_REVERB_GAIN, 1.2f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_LATE_REVERB_DELAY, 0.08f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_AIR_ABSORPTION_GAINHF, 0.95f);
            EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
            EXTEfx.alAuxiliaryEffectSlotf(slot, EXTEfx.AL_EFFECTSLOT_GAIN, 0.25f);
        } catch (RuntimeException | LinkageError ex) {
            Incoming.LOG.warn("OpenAL EFX unavailable for terrain echo: {}", ex.toString());
            filter = -1;
        }
    }
}
