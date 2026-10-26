package ua.zentix.incoming.client.sound;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Blast mix at the ear: game loudness is half the <b>total</b> level's decibels, ceiling on the sum, and each
 * blast's share is its real-life power share (without Minecraft, covered by a unit test).
 * <p>
 * Each blast's volume follows pressure at the ear — half the pressure decibels, ceiling from 1 psi
 * ({@link BlastMix#loudness}, {@link Loudness#CEILING_LUFS}). This maps to speakers, not a law: outside has ~110 dB
 * from quiet night to near blast, home speakers ~60. But mapping was per sound, while speaker sounds sum by power:
 * two simultaneous blasts give +3 dB in game though real level rose 3 dB, mapped to 1.5; a Grad salvo of 40 bursts
 * at 30 blocks ran 4–5 dB above ceiling (−8.6 LUFS vs −13.5 ceiling), hitting full scale (0 dBFS clipping in RV1,
 * 3.4.1 had 0–1). So map the sum and divide within it:
 * <ul>
 * <li>a blast is one wave: perspectives of one recording sum by amplitude, separate recordings (chest hit, model
 * impact at ear, terrain echoes) by power; p_b is game power relative to ceiling before allocation;</li>
 * <li>game sum is half the sum's decibels: G = min(1, (Σ p_b²)^½), no louder than ceiling;</li>
 * <li>blast b's share is Q_b = G·p_b² / Σ p², its power factor k_b² = Q_b / p_b = G·p_b / Σ p². One blast below
 * ceiling plays as before (k = 1); N equal blasts: k² = 1/√N, above ceiling: 1/(N·p); near at p = 1 plus a hundred
 * distant at 0.01: near is 0.04 dB quieter than alone, each distant 40 dB below it, all together 20 dB below
 * (a common k² = G/Σp would give −3 dB and equal loudness); ΣQ_b = G.</li>
 * </ul>
 * Games with a “loudness window” maintain volume this way (HDR audio: Wwise, Frostbite — Clerwall, GDC 2009): the
 * audible sum sets the window top; ratios inside are preserved. Presentation, not a reconstruction of life: p_b
 * is power after half-decibel mapping over the largest 400 ms window ({@link Envelope#of}); its square is only
 * approximately physical power (real life uses mean-square pressure, no window or variant maximum); masking is
 * by loudness only, without bands or temporal masking.
 * <p>
 * Current sound follows the recording's own envelope ({@link Envelope}: momentary loudness looking 400 ms ahead
 * from each tick, faster or slower with pitch), so an early blast's tail reduces a later one only as much as it
 * still sounds, and distant blasts return as near fades. A blast without an engine voice (yielded its slot,
 * {@link BlastVoices}) stays in the mix with the same power, played by rumble ({@link Rumble}); rumble itself is
 * not mixed again, or the sum would depend on channel count.
 */
final class Mix {
    /** Ceiling momentary-loudness mean square ({@link Loudness#CEILING_LUFS}). */
    static final double CEILING = Envelope.meanSquare(Loudness.CEILING_LUFS);

    /** What plays a layer. */
    enum Kind {
        /** Perspective of the shared blast recording — engine voice; amplitude sum with other perspectives. */
        VIEW,
        /** Separate recording on an engine voice (chest hit, bomb sounds): power sum. */
        APART,
        /** Own track on our own OpenAL source (impact at ear, echoes): power sum; never goes to rumble — not an engine voice. */
        OWN
    }

    /**
     * One layer: recording envelope for ceiling ({@link Envelope#of}, tickwise mean square at volume 1), energy of
     * the playing recording ({@link Envelope#energy}: used by rumble), pitch, volume, what plays it and ticks after
     * blast start before it begins (echoes after direct impact).
     */
    record Layer(float[] envelope, float[] energy, double pitch, double volume, Kind kind, double delay) {
        Layer(float[] envelope, double pitch, double volume) {
            this(envelope, envelope, pitch, volume, Kind.VIEW, 0);
        }

        /** {@code apart} is a separate recording, not a shared perspective: incoherent with perspectives, summed by power. */
        Layer(float[] envelope, double pitch, double volume, boolean apart) {
            this(envelope, envelope, pitch, volume, apart ? Kind.APART : Kind.VIEW, 0);
        }

        /** Own track on our own source ({@link OwnSources}), {@code delay} ticks after blast start. */
        static Layer own(float[] envelope, float[] energy, double volume, double delay) {
            return new Layer(envelope, energy, 1, volume, Kind.OWN, delay);
        }

        boolean apart() {
            return kind != Kind.VIEW;
        }

        /** Layer power {@code age} ticks after blast start, relative to ceiling (envelope is an upper bound). */
        double power(double age) {
            return volume * volume * at(envelope, age) / CEILING;
        }

        /** Playing recording energy after {@code age} ticks, relative to ceiling. */
        double energy(double age) {
            return volume * volume * at(energy, age) / CEILING;
        }

        private double at(float[] e, double age) {
            double at = (age - delay) * pitch;
            // already use first sample a tick before start: a frame- or sound-thread-driven voice starts a fraction of a tick
            // before mix tick counting, and its first frame's volume already follows this layer
            if (at < 0 && at >= -pitch) at = 0;
            if (at < 0 || e.length == 0) return 0;
            int i = (int) Math.floor(at);
            if (i >= e.length) return 0;
            double f = at - i;
            return i + 1 < e.length ? e[i] + (e[i + 1] - e[i]) * f : e[i] * (1 - f);
        }

        /** Ticks from blast start to layer end: both ceiling envelope and energy (its tail window follows recording). */
        double ticks() {
            return delay + Math.max(envelope.length, energy.length) / Math.max(pitch, 1e-6) + 1;
        }
    }

    /** Blast: start tick and layers; factor comes from mix ({@link #factor}). */
    final class Blast {
        double start;
        final List<Layer> layers = new ArrayList<>(5);
        double end;
        /** Power before allocation and volume factor at {@link Mix#now} tick. */
        private double p, k = 1;
        /** In the mix (not ended). */
        private boolean live = true;

        Blast(double start) {
            this.start = start;
            this.end = start;
        }

        /** Add layer to mix; blast whose previous layers ended (echo ready after direct impact) rejoins it. */
        void add(Layer l) {
            layers.add(l);
            end = Math.max(end, start + l.ticks());
            if (!live) {
                live = true;
                blasts.add(this);
            }
            dirty = true;
        }

        /**
         * Sound actually started {@code ticks} after the tick it was requested ({@link OwnSources.Anchor}): all blast
         * layers start from the actual start, like their voices.
         */
        void late(double ticks) {
            if (!(ticks > 0)) return;
            start += ticks;
            end += ticks;
            dirty = true;
        }

        /**
         * Blast power at {@code tick}, relative to ceiling: perspectives sum by amplitude (one recording, amplitude
         * mixed, {@link BlastMix}; power sum would underestimate by up to 3 dB), separate recordings
         * ({@link Layer#apart}: chest hit, impact at ear, echoes) by power (amplitude sum would double a blast
         * from 1 psi, making it play 3 dB below ceiling).
         */
        double power(double tick) {
            double a = 0, apart = 0;
            for (Layer l : layers) {
                double p = l.power(tick - start);
                if (l.apart()) apart += p;
                else a += Math.sqrt(p);
            }
            return a * a + apart;
        }

        /**
         * This blast's engine-voice energy at {@code tick} (perspectives and separate recordings, excluding our
         * own sources), relative to ceiling, before factor: summed like {@link #power} but from playing recordings'
         * energy, used by rumble when voices are absent.
         */
        double voiced(double tick) {
            return voiced(tick, null);
        }

        /**
         * Like {@link #voiced(double)}, but only {@code voices}: i-th is i-th blast layer played by an engine voice
         * (not {@link Kind#OWN}), in layer order; null — all.
         */
        double voiced(double tick, boolean @Nullable [] voices) {
            double a = 0, apart = 0;
            int i = 0;
            for (Layer l : layers) {
                if (l.kind() == Kind.OWN) continue;
                boolean on = voices == null || i < voices.length && voices[i];
                i++;
                if (!on) continue;
                double e = l.energy(tick - start);
                if (l.kind() == Kind.APART) apart += e;
                else a += Math.sqrt(e);
            }
            return a * a + apart;
        }

        /** Mix tick for which factors were computed. */
        double now() {
            return now;
        }

        /** Current volume factor for this blast's sounds (0..1): k_b = (G·p_b / Σ p²)^½. */
        double factor() {
            update();
            return k;
        }

        /** Blast share in currently audible sound, relative to ceiling: Q_b = k_b²·p_b. */
        double heard() {
            update();
            return k * k * p;
        }

        boolean ended(double tick) {
            return end < tick;
        }
    }

    private final List<Blast> blasts = new ArrayList<>();
    /** Tick for which factors were computed. */
    private double now = Double.NaN;
    private boolean dirty = true;

    /** Blast starting at {@code tick}: add layers with {@link Blast#add}. */
    Blast blast(double tick) {
        at(tick);
        Blast b = new Blast(tick);
        blasts.add(b);
        dirty = true;
        return b;
    }

    /** Mix tick: remove ended blasts, compute factors for this tick. */
    void at(double tick) {
        if (tick != now) {
            now = tick;
            dirty = true;
        }
        blasts.removeIf(b -> {
            if (!b.ended(tick)) return false;
            b.live = false;
            return true;
        });
    }

    private void update() {
        if (!dirty) return;
        dirty = false;
        double sq = 0;
        for (Blast b : blasts) {
            b.p = b.power(now);
            sq += b.p * b.p;
        }
        double g = Math.min(1, Math.sqrt(sq));
        // silence — factor 1: sound unknown to the envelope is not attenuated
        for (Blast b : blasts) b.k = sq > 0 ? Math.sqrt(Math.min(1, g * b.p / sq)) : 1;
    }

    /** Game mix power at {@code tick}, relative to ceiling: Σ Q_b = G (checked in tests). */
    double heard(double tick) {
        at(tick);
        return heard();
    }

    /** Current game mix power, relative to ceiling. */
    double heard() {
        update();
        double sum = 0;
        for (Blast b : blasts) sum += b.k * b.k * b.p;
        return sum;
    }

    void reset() {
        blasts.forEach(b -> b.live = false);
        blasts.clear();
        dirty = true;
    }

    int size() {
        return blasts.size();
    }
}
