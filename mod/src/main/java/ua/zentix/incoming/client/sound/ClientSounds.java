package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.client.flight.FlightTracks;
import ua.zentix.incoming.sound.Propagation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * Client projectile sound. Each projectile has its own independent set of looping sounds (no shared slots or
 * whistles cutting each other off); {@link EngineSound} computes delay and Doppler.
 * Path comes from {@link FlightTracks}: entity, or server packets while the client has no entity (far away or
 * flying outside the world); the same sound switches between them by UUID. Where sound sinks into background
 * ({@code Hearing}, same calculation as the server), layer volumes are zero: a path sent only for distant visuals
 * occupies no channel.
 * <p>
 * Each tick computes what is heard from each projectile ({@link Emission}) and each layer's volume; only audible
 * layers play (hold an OpenAL channel), at most {@link VoiceBudget#CAP} simultaneously.
 */
public final class ClientSounds {
    private static final Map<UUID, Tracked> TRACKS = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();
    /** Engines dip under a loud blast. */
    private static final Ducking DUCK = new Ducking();
    /** Engine refused a layer a channel (all busy): retry after this many ticks (budget). */
    private static final int RETRY = 10;
    /**
     * Projectile engine fades over this many ticks after the flight's end reaches the ear (a layer without data
     * halves volume per tick), then stops: cutting it at full volume would click; cutting it a tick before the
     * blast front would leave a silent gap before it. Presentation.
     */
    private static final int FADE = 4;

    private ClientSounds() {}

    /** One projectile's sound: path, layers and flight phase already “heard” by the listener (−1 — none yet). */
    private static final class Tracked {
        final FlightTrack track;
        final Occlusion occlusion = new Occlusion();
        final List<Voice> voices;
        /** Has a booster layer: launch one-shots mark phase changes ({@link EngineSound#launchEvents}). */
        final boolean launchEvents;
        int heardPhase = -1;
        /** Projectile sound reached the ear last tick (supersonic: listener is already inside the Mach cone). */
        boolean reached;

        Tracked(FlightTrack track, List<EngineSound.Layer> layers) {
            this.track = track;
            this.voices = layers.stream().map(l -> new Voice(track, occlusion, l)).toList();
            this.launchEvents = layers.contains(EngineSound.Layer.BOOSTER);
        }

        void kill() {
            voices.forEach(Voice::kill);
        }
    }

    /** Projectile sound layer: this tick's state and sound while the layer plays. */
    private static final class Voice {
        final FlightTrack track;
        final Occlusion occlusion;
        final EngineSound.Layer layer;
        /** What is heard from the projectile this tick ({@code null} — no data), and layer volume and pitch. */
        Emission heard;
        EngineSound.Tone tone;
        /** Consecutive ticks the layer has been inaudible. */
        int quiet;
        /** Do not start before this tick: engine refused a channel. */
        long retryAt;
        /** Playing now; {@code null} — holds no channel. */
        EngineSound sound;

        Voice(FlightTrack track, Occlusion occlusion, EngineSound.Layer layer) {
            this.track = track;
            this.occlusion = occlusion;
            this.layer = layer;
        }

        void hear(Emission e) {
            heard = e;
            tone = e == null ? null : layer.tone(track, e);
            quiet = gain() >= VoiceBudget.AUDIBLE ? 0 : quiet + 1;
            if (sound != null) sound.aim(heard, tone);
        }

        float gain() {
            return tone == null ? 0 : (float) tone.gain();
        }

        /**
         * Layer plays: sound has not stopped itself and holds an engine channel ({@link #playing}). F3+T and device
         * changes discard all sounds; category slider at zero removes the channel — restart the layer instead of
         * leaving it silent for the rest of the flight.
         */
        boolean live() {
            if (sound != null && (sound.isStopped() || !playing(sound))) kill();
            return sound != null;
        }

        /** Start the layer; engine refused a channel — retry later. */
        void start() {
            SoundManager manager = Minecraft.getInstance().getSoundManager();
            EngineSound s = new EngineSound(track, occlusion, layer);
            s.aim(heard, tone);
            // volume, pitch, position and filter before start: vanilla ticks delayed sound this way before play
            s.tick();
            manager.play(s);
            if (playing(s)) sound = s;
            else retryAt = FlightTracks.now() + RETRY;
        }

        void kill() {
            if (sound != null) {
                sound.kill();
                // zero category slider removed the channel: vanilla removes the channel but not the ticking sound (SoundEngine.tickNonPaused),
                // so it would live until engine reset — each move to zero would accumulate one sound per layer
                if (!playing(sound)) Minecraft.getInstance().getSoundManager().soundEngine.tickingSounds.remove(sound);
            }
            sound = null;
        }
    }

    /** Current sound time, client ticks. */
    static double now() {
        return FlightTracks.now();
    }

    /** Once per tick after {@link FlightTracks#tick}: each path's sound layers, volumes, channels. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        BlastSounds.tick();
        Sources.tick();
        if (mc.level == null) {
            reset();
            return;
        }
        // path forgotten or new flight with the same UUID — stop old sound
        Iterator<Tracked> it = TRACKS.values().iterator();
        while (it.hasNext()) {
            Tracked t = it.next();
            if (current(t.track) != t.track) {
                t.kill();
                it.remove();
            }
        }
        // path starts engine sound, not entity: projectiles and SAMs absent on the client too (server packets)
        for (FlightTrack track : FlightTracks.all()) {
            if (!TRACKS.containsKey(track.id)) TRACKS.put(track.id, new Tracked(track, layers(track)));
        }
        for (FlightTrack track : FlightTracks.interceptors()) {
            if (!TRACKS.containsKey(track.id)) TRACKS.put(track.id, new Tracked(track, layers(track)));
        }
        DUCK.tick();
        budget(hear(mc));
    }

    /**
     * Sound holds an engine channel ({@code SoundEngine.instanceToChannel}), not just a start record: {@code isActive}
     * reports “playing” even after a zero category slider removed its channel.
     */
    static boolean playing(SoundInstance s) {
        return Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.containsKey(s);
    }

    /** Engine volume under blasts ({@link Ducking}). */
    static float duck() {
        return DUCK.factor();
    }

    /** Blast with direct-sound loudness lufs reached the ear: engines dip ({@link Ducking}). */
    static void ducked(double lufs) {
        DUCK.blast(lufs);
    }

    /** What is heard from each projectile this tick: each layer's volume and launch one-shots. */
    private static List<Voice> hear(Minecraft mc) {
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        Entity camera = mc.getCameraEntity();
        Propagation.Weather weather = Propagation.Weather.of(mc.level);
        List<Voice> voices = new ArrayList<>();
        double tick = now();
        double[] roots = new double[1];
        for (Tracked t : TRACKS.values()) {
            FlightTrack track = t.track;
            // sound has not arrived (or listener outside Mach cone) — no emission time, layers silent
            boolean reached = Acoustics.emissions(track, tick, ear.x, ear.y, ear.z, roots) > 0;
            double te = reached ? roots[0] : Double.NaN;
            if (reached && !t.reached) Booms.reached(track, te, ear, weather);
            t.reached = reached;
            if (track.isDead() && te >= track.deathTick() + FADE) {
                // blast front arrived and engine faded: listener now hears the blast itself
                t.kill();
                continue;
            }
            // no projectile data at this time (exploded or left hearing range) — fade layers without moving source abruptly
            Emission e = track.covers(te) ? Emission.at(track, te, ear, camera != null && camera.getUUID().equals(track.id), weather) : null;
            if (e != null && t.launchEvents && e.phase() != t.heardPhase) {
                EngineSound.launchEvents(track, e, t.heardPhase);
                t.heardPhase = e.phase();
            } else if (e != null && track.weapon == null && t.heardPhase < 0) {
                // SAM: ignition is heard if engine sound began at the ear from the very launch rail exit
                InterceptorSound.heard(e);
                t.heardPhase = e.phase();
            }
            for (Voice v : t.voices) {
                v.hear(e);
                voices.add(v);
            }
        }
        return voices;
    }

    /** Layers selected by {@link VoiceBudget} play: extras release their channels, missing ones start. */
    private static void budget(List<Voice> voices) {
        int n = voices.size();
        float[] gain = new float[n];
        boolean[] live = new boolean[n];
        int[] quiet = new int[n];
        for (int i = 0; i < n; i++) {
            Voice v = voices.get(i);
            gain[i] = v.gain();
            live[i] = v.live();
            quiet[i] = v.quiet;
        }
        boolean[] keep = VoiceBudget.select(gain, live, quiet, VoiceBudget.CAP);
        for (int i = 0; i < n; i++) {
            Voice v = voices.get(i);
            if (live[i]) {
                if (keep[i]) v.sound.keep();
                else v.sound.release();
            } else if (keep[i] && now() >= v.retryAt) {
                v.start();
            }
        }
    }

    /** Projectile sound layers from its entity's client spec (B-2 and its bomb have separate sets); SAM uses its engine. */
    static List<EngineSound.Layer> layers(FlightTrack track) {
        return track.weapon == null ? List.of(EngineSound.Layer.SAM) : ClientWeaponSpec.of(track.weapon).airframe(!track.bomber).layers();
    }

    /** Current path for the same UUID ({@code null} — forgotten): projectile or SAM. */
    private static FlightTrack current(FlightTrack track) {
        return track.weapon == null ? FlightTracks.interceptor(track.id) : FlightTracks.get(track.id);
    }

    /**
     * Currently audible layers: “weapon (path source: e — entity, s — server packets; distance to ear, blocks) layer
     * volume×pitch”, then how many layers hold a channel; for sound debugging in logs.
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double[] p = new double[3];
        int live = 0;
        for (Tracked t : TRACKS.values()) {
            StringBuilder layers = new StringBuilder();
            for (Voice v : t.voices) {
                EngineSound s = v.sound;
                if (s == null) continue;
                live++;
                // without the sound the engine substitutes at start (no device), the layer does not play
                // gain relative to reference (Loudness.gain), not file volume: line values do not depend on file mixing
                if (s.getSound() != null && s.loudness() > 0.01) layers.append(String.format(Locale.ROOT, " %s %.2f×%.2f", s.describe(), s.loudness(), s.getPitch()));
            }
            if (layers.isEmpty()) continue;
            t.track.at(t.track.lastTick(), p);
            sb.append(String.format(Locale.ROOT, " %s(%s %.0f)", t.track.weapon == null ? "sam" : t.track.weapon.getSerializedName(), t.track.fromServer() ? "s" : "e",
                    Math.sqrt((p[0] - ear.x) * (p[0] - ear.x) + (p[1] - ear.y) * (p[1] - ear.y) + (p[2] - ear.z) * (p[2] - ear.z)))).append(layers);
        }
        return sb.append(" live=").append(live).toString();
    }

    /** All-clear, replay rewind or world exit: silence everything immediately (including echoes on our own sources). */
    public static void reset() {
        TRACKS.values().forEach(Tracked::kill);
        TRACKS.clear();
        DUCK.reset();
        BlastSounds.reset();
        Sources.reset();
        OwnSources.reset();
    }

    /** Conventional all-clear: silence cancelled projectiles; remaining ones (nuclear) keep sounding. */
    public static void cancelled(Collection<UUID> projectiles) {
        for (UUID id : projectiles) {
            Tracked t = TRACKS.remove(id);
            if (t != null) t.kill();
        }
    }

    // ---------------------------------------------------------------- one-shots

    /**
     * Sound arriving at the ear “from everywhere at once” — blast-wave impact: volume is independent of distance
     * (set by the caller), but direction is accurate — a point 3 blocks from the ear toward the source.
     * Along the path air absorbs highs; an intervening hill or house muffles ({@link SoundFilters}).
     */
    public static SoundInstance atEar(SoundEvent event, Vec3 source, float volume, float pitch) {
        return atEar(event, source, volume, pitch, SoundSource.AMBIENT);
    }

    /**
     * Nuclear impact on the ears: like {@link #atEar}, but controlled by “Master Volume” — it should not get
     * quieter because the player lowered “Ambient”.
     *
     * @return sound handed over
     */
    public static SoundInstance atEarLoud(SoundEvent event, Vec3 source, float volume, float pitch) {
        return atEar(event, source, volume, pitch, SoundSource.MASTER);
    }

    /** Like {@link #atEarLoud(SoundEvent, Vec3, float, float)}, but seed selects recording (terrain echoes repeat it). */
    public static SoundInstance atEarLoud(SoundEvent event, Vec3 source, float volume, float pitch, long seed) {
        SoundInstance s = loudShot(event, source, volume, pitch, seed);
        play(s);
        return s;
    }

    /**
     * Sound {@link #atEarLoud(SoundEvent, Vec3, float, float, long)}, not yet handed to the engine ({@link #start}):
     * anyone awaiting its start sets up waiting before {@code play} (terrain echoes).
     */
    public static SoundInstance loudShot(SoundEvent event, Vec3 source, float volume, float pitch, long seed) {
        return shot(event, source, volume, pitch, SoundSource.MASTER, RandomSource.create(seed));
    }

    /**
     * Like {@link #loudShot(SoundEvent, Vec3, float, float, long)}, but caller supplies the path filter
     * ({@link Outdoor} already computed intervening hill, ground, weather, air): no ray to source or own air.
     * Barrier at the ear itself (house walls and roof, {@link SoundFilters#openToward}: 12 blocks toward source;
     * {@code Sightline} handles terrain beyond 16 blocks) is ours; omitted when sound path is disabled in settings.
     */
    public static SoundInstance loudShot(SoundEvent event, Vec3 source, float volume, float pitch, long seed, float gain, float highs) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float open = SoundFilters.pathEffects() ? SoundFilters.openToward(ear, Math.atan2(source.z - ear.z, source.x - ear.x)) : 1;
        return new Shot(event.getLocation(), SoundSource.MASTER, volume, pitch, RandomSource.create(seed), SoundInstance.Attenuation.NONE,
                toward(ear, source), false, gain * SoundFilters.blockedGain(open), highs * SoundFilters.blockedHighs(open));
    }

    /**
     * Sound {@link #atEar(SoundEvent, Vec3, float, float, long)} (seed-selected recording, “Ambient” category), not
     * yet handed to the engine ({@link #start}): anyone awaiting its start sets up waiting before {@code play}.
     */
    public static SoundInstance shot(SoundEvent event, Vec3 source, float volume, float pitch, long seed) {
        return shot(event, source, volume, pitch, SoundSource.AMBIENT, RandomSource.create(seed));
    }

    /**
     * Blast one-shot in the blast mix ({@link Mix}), not yet handed to the engine ({@link #start}): like
     * {@link #shot(SoundEvent, Vec3, float, float, long)}, but volume is {@code volume} × its blast's factor in
     * {@code mix}, recomputed each tick (sound ticks). {@link BlastSounds} hands it to the engine if it has a voice
     * ({@link BlastVoices}).
     */
    static MixedShot blastShot(SoundEvent event, Vec3 source, float volume, float pitch, long seed, DoubleSupplier mix, int life) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float open = SoundFilters.open(ear, source.add(0, 1.5, 0));
        float highs = farAir(ear.distanceTo(source)) * SoundFilters.blockedHighs(open);
        return new MixedShot(event, SoundSource.AMBIENT, volume, pitch, RandomSource.create(seed), toward(ear, source), SoundFilters.blockedGain(open), highs, mix,
                life);
    }

    /** Like {@link #blastShot}, but caller supplies path filter ({@link Outdoor}: intervening hill, ground, weather, air). */
    static MixedShot blastAtEar(SoundEvent event, Vec3 source, float volume, float pitch, long seed, float gain, float highs, DoubleSupplier mix, int life) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return new MixedShot(event, SoundSource.AMBIENT, volume, pitch, RandomSource.create(seed), toward(ear, source), gain, highs, mix, life);
    }

    /** Hand sound to the engine. */
    public static void start(SoundInstance sound) {
        play(sound);
    }

    private static SoundInstance atEar(SoundEvent event, Vec3 source, float volume, float pitch, SoundSource category) {
        return atEar(event, source, volume, pitch, category, RANDOM);
    }

    /**
     * Like {@link #atEar(SoundEvent, Vec3, float, float)}, but seed selects a recording from event variants:
     * perspectives of one blast with the same seed use the same recording (perspective events have equal variant counts).
     */
    public static void atEar(SoundEvent event, Vec3 source, float volume, float pitch, long seed) {
        atEar(event, source, volume, pitch, SoundSource.AMBIENT, RandomSource.create(seed));
    }

    private static SoundInstance atEar(SoundEvent event, Vec3 source, float volume, float pitch, SoundSource category, RandomSource random) {
        SoundInstance s = shot(event, source, volume, pitch, category, random);
        play(s);
        return s;
    }

    private static SoundInstance shot(SoundEvent event, Vec3 source, float volume, float pitch, SoundSource category, RandomSource random) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float open = SoundFilters.open(ear, source.add(0, 1.5, 0));
        float highs = farAir(ear.distanceTo(source)) * SoundFilters.blockedHighs(open);
        return new Shot(event.getLocation(), category, volume, pitch, random, SoundInstance.Attenuation.NONE, toward(ear, source), false,
                SoundFilters.blockedGain(open), highs);
    }

    /**
     * Like {@link #atEar(SoundEvent, Vec3, float, float, long)}, but caller supplies the path filter (distant blast:
     * {@link Outdoor} already computed intervening hill, ground, weather): no block ray or own air.
     */
    public static void atEar(SoundEvent event, Vec3 source, float volume, float pitch, long seed, float gain, float highs) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        play(new Shot(event.getLocation(), SoundSource.AMBIENT, volume, pitch, RandomSource.create(seed), SoundInstance.Attenuation.NONE,
                toward(ear, source), false, gain, highs));
    }

    /** Rumble recording highs surviving air: distant recordings are already muffled — air adds at most −12 dB. */
    static float farAir(double d) {
        return Math.max(0.25f, SoundFilters.air(d));
    }

    /** Point 3 blocks from the ear toward the source (if closer, the source itself). */
    private static Vec3 toward(Vec3 ear, Vec3 source) {
        Vec3 dir = source.subtract(ear);
        return dir.lengthSqr() < 9 ? source : ear.add(dir.normalize().scale(3));
    }

    private static void play(SoundInstance sound) {
        Minecraft.getInstance().getSoundManager().play(sound);
    }

    /**
     * Blast one-shot in the mix ({@link Mix}): ticks, and the engine rereads volume each tick — own volume × its
     * blast factor in the mix (this blast's tail yields to the next instead of holding its initial volume).
     * Voice yielded its slot ({@link BlastVoices}) — one full-volume tick, then fades over {@link BlastVoices#FADE}
     * ticks ({@link Rumble#voice}: its power transitions to rumble in those same engine ticks) and goes away.
     */
    static final class MixedShot extends AbstractTickableSoundInstance implements SoundFilters.Muffled, BlastVoices.Sound {
        private final float gain, highs;
        private final DoubleSupplier mix;
        /** Ticks until recording end with margin: sound removes itself afterward. */
        private int life;
        /** Engine ticks since voice yielded; −1 — playing. */
        private int fading = -1;

        MixedShot(SoundEvent event, SoundSource category, float volume, float pitch, RandomSource random, Vec3 at, float gain, float highs,
                  DoubleSupplier mix, int life) {
            super(event, category, random);
            this.volume = volume;
            this.pitch = pitch;
            this.attenuation = Attenuation.NONE;
            this.relative = false;
            this.looping = false;
            this.delay = 0;
            this.x = at.x;
            this.y = at.y;
            this.z = at.z;
            this.gain = gain;
            this.highs = highs;
            this.mix = mix;
            this.life = life;
        }

        @Override
        public float getVolume() {
            return super.getVolume() * level();
        }

        /** Current volume multiplier: mix × yielded voice fade. */
        float level() {
            return (float) (mix.getAsDouble() * (fading < 0 ? 1 : Rumble.voice(fading, 0, BlastVoices.FADE)));
        }

        @Override
        public void tick() {
            // engine tick: volume from getVolume, position unchanged; recording ended or voice faded — remove
            if (fading >= 0 && Rumble.voice(++fading, 0, BlastVoices.FADE) <= 0 || --life <= 0) stop();
        }

        @Override
        public void fade() {
            if (fading < 0) fading = 0;
        }

        @Override
        public void cut() {
            stop();
        }

        /** Playing: engine accepted and retained a channel (channel shortage, engine restart, zero category slider). */
        boolean playing() {
            return !isStopped() && Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.containsKey(this);
        }

        @Override
        public boolean over() {
            return !playing();
        }

        /**
         * Channel still held by engine: a stopped sound holds it until engine stops the channel in its tick and
         * sound thread returns it to the pool ({@code ChannelAccess.ChannelHandle.isStopped}); a sound refused by
         * the engine holds no channel.
         */
        @Override
        public boolean held() {
            ChannelAccess.ChannelHandle h = Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.get(this);
            return h != null && !h.isStopped();
        }

        /** Recording volume it was given, before mixing. */
        float volume0() {
            return volume;
        }

        @Override
        public float lowpassGain() {
            return gain;
        }

        @Override
        public float lowpassHighs() {
            return highs;
        }
    }

    /** One-shot with a path filter (air, barriers). */
    private static final class Shot extends SimpleSoundInstance implements SoundFilters.Muffled {
        private final float gain, highs;

        Shot(ResourceLocation id, SoundSource category, float volume, float pitch, RandomSource random, Attenuation attenuation, Vec3 at,
             boolean relative, float gain, float highs) {
            super(id, category, volume, pitch, random, false, 0, attenuation, at.x, at.y, at.z, relative);
            this.gain = gain;
            this.highs = highs;
        }

        @Override
        public float lowpassGain() {
            return gain;
        }

        @Override
        public float lowpassHighs() {
            return highs;
        }
    }
}
