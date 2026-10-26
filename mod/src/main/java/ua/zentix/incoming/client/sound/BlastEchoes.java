package ua.zentix.incoming.client.sound;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Terrain echoes of a blast at this listener — any blast, nuclear ({@code client.nuclear.NukeEchoes}) or conventional
 * ({@link BlastSounds}): while the front travels, rays from the ear across the height map
 * ({@link TerrainEcho}, {@link #RAYS_PER_TICK} per tick) find slopes that will return the impact; the direct-impact
 * recording is read in the background. Once the front arrives and rays are ready, directional tracks are mixed in the
 * background too ({@link EchoMix}), then queued on our own OpenAL sources ({@link OwnSources}) from the actual start
 * of the direct impact. If rays are unfinished (listener entered or teleported just as the front arrived), they are
 * completed at {@link #ARRIVAL_RAYS} per tick, and echoes start later (the elapsed track opening is skipped). Live only.
 */
public class BlastEchoes {
    /** Rays per tick while the front travels (all {@link TerrainEcho#RAYS} in 6 ticks; terrain-read budget). */
    static final int RAYS_PER_TICK = 12;
    /** Rays per tick when the front is already at the listener but rays are unfinished (budget). */
    static final int ARRIVAL_RAYS = 24;
    /** Listener moved farther than this from the ray origin — restart rays (budget: echo path error up to 2×64 m). */
    private static final double MOVED = 64;
    /** The grain uses this many seconds from the start of the direct-impact recording (presentation). */
    public static final double GRAIN_SECONDS = 2.8;
    /** Send to the reverb tail (presentation, −12 dB). */
    private static final float SEND = 0.25f;

    private final OwnSources.Clock clock;
    private final Vec3 burst;
    private final TerrainEcho.Blast blast;
    /** Direct-impact recording: this seed selects its variant — the same seed as the direct impact. */
    public final long seed;
    private final CompletableFuture<float[]> grain;
    private final List<TerrainEcho.Reflection> found = new ArrayList<>();
    @Nullable
    private Vec3 from;
    private int nextRay;
    @Nullable
    private CompletableFuture<EchoMix.Mix> mix;
    @Nullable
    private EchoMix.Mix done;
    @Nullable
    private OwnSources.Anchor anchor;
    /** Direct impact whose start anchors the echoes (for the scenario line: which recording). */
    @Nullable
    private SoundInstance direct;
    /** Longest batch of rays in a tick, ns (for the scenario line). */
    private long traceSlowest;
    /** Where the front caught the listener (rays are completed from here); null — front has not arrived yet. */
    @Nullable
    private Vec3 heard;
    private float volume, pitch;
    /** Blast in the blast mix ({@link #bus}); null — no mix. */
    @Nullable
    private Mix.Blast bus;
    /** Track envelopes for the mix ({@link Envelope#of}, {@link Envelope#energy}) — in the background, with mixing. */
    @Nullable
    private CompletableFuture<float[][][]> profiles;
    private int queued;
    private boolean stopped;

    /**
     * @param clock blast clock that times the start of the direct impact
     * @param burst where the blast occurs
     * @param blast arrival and pressure of its front
     * @param sound direct-impact recording: its opening is the echo grain
     * @param seed  seed that selected the direct-impact recording variant
     */
    public BlastEchoes(OwnSources.Clock clock, Vec3 burst, TerrainEcho.Blast blast, SoundEvent sound, long seed) {
        this.clock = clock;
        this.burst = burst;
        this.blast = blast;
        this.seed = seed;
        this.grain = CompletableFuture.supplyAsync(() -> read(sound, seed), Util.backgroundExecutor())
                .exceptionally(e -> {
                    Incoming.LOG.warn("Terrain echo: can't read the blast sound: {}", e.toString());
                    return new float[0];
                });
    }

    /** Front is still travelling: a batch of rays. */
    public void approach(Vec3 ear) {
        trace(ear, RAYS_PER_TICK);
    }

    /**
     * Direct impact is about to be handed to the engine: echoes start from its actual start. Before {@code play}:
     * with a buffer the engine already holds (second detonation in a session), sound starts on the sound thread
     * before {@code play} returns.
     *
     * @param tick   tick on the blast clock when the direct impact is handed to the engine
     * @param direct direct impact (null — start by tick)
     */
    public void expect(double tick, @Nullable SoundInstance direct) {
        if (stopped) return;
        this.direct = direct;
        this.anchor = OwnSources.anchor(this, direct, clock, tick);
    }

    /**
     * A conventional blast's echoes join the blast mix ({@link Mix}) with its direct impact: tracks are power layers
     * of {@code blast}, each with its own delay (without them, late echoes after the direct impact faded would count
     * the blast as quiet, and its factor would suppress the echoes among other blasts); track volume is multiplied
     * by the blast factor. Nuclear strikes have no mix. The reverb tail ({@link OwnSources#reverb}: send 0.25, late
     * reflections 1.2) is omitted from the mix: it is ~10 dB below the echoes, which are 10–20 dB below the direct
     * impact, adding less than 0.05 dB to the sum. Call before {@link #arrive}.
     */
    void bus(@Nullable Mix.Blast blast) {
        this.bus = blast;
    }

    /**
     * Front at the listener: unfinished rays at {@link #ARRIVAL_RAYS} per tick, then background mixing.
     *
     * @param volume direct-impact volume (echoes are a fraction of it)
     * @param pitch  direct-impact pitch (echoes use the same sound)
     */
    public void arrive(Vec3 ear, float volume, float pitch) {
        if (stopped) return;
        this.heard = ear;
        this.volume = volume;
        this.pitch = pitch;
        finish();
    }

    /** Rays from the front's arrival position — another batch; once all are ready, mix in the background. */
    private void finish() {
        Vec3 ear = heard;
        if (ear == null || mix != null) return;
        trace(ear, ARRIVAL_RAYS);
        // no rays in a dimension with a ceiling
        ClientLevel level = Minecraft.getInstance().level;
        if (nextRay < TerrainEcho.RAYS && level != null && !level.dimensionType().hasCeiling()) return;
        List<TerrainEcho.Reflection> list = List.copyOf(found);
        float p = pitch;
        mix = grain.thenApplyAsync(g -> EchoMix.render(EchoMix.pitch(g, p), list), Util.backgroundExecutor())
                .exceptionally(e -> {
                    Incoming.LOG.warn("Terrain echo: can't mix the echoes: {}", e.toString());
                    return EchoMix.Mix.NONE;
                });
        if (bus != null) profiles = mix.thenApplyAsync(BlastEchoes::profiles, Util.backgroundExecutor()).exceptionally(e -> {
            Incoming.LOG.warn("Terrain echo: can't measure the echoes for the blast mix: {}", e.toString());
            return null;
        });
    }

    /** Envelope and energy of each track ({@link EchoMix#RATE}), for the mix. */
    private static float[][][] profiles(EchoMix.Mix m) {
        float[][][] out = new float[m.tracks().size()][][];
        for (int i = 0; i < out.length; i++) {
            short[] pcm = m.tracks().get(i).pcm();
            float[] x = new float[pcm.length];
            for (int j = 0; j < x.length; j++) x[j] = pcm[j] / 32768f;
            out[i] = new float[][]{Envelope.of(x, EchoMix.RATE), Envelope.energy(x, EchoMix.RATE)};
        }
        return out;
    }

    /** Each tick after impact: remaining rays and ready tracks queued on our own sources. */
    public void tick() {
        if (stopped) return;
        if (mix == null) finish();
        if (mix == null || anchor == null || done != null || !mix.isDone() || profiles != null && !profiles.isDone()) return;
        done = mix.getNow(EchoMix.Mix.NONE);
        if (done.tracks().isEmpty()) return;
        OwnSources.reverb(done.t60(), SEND);
        Mix.Blast blast = bus;
        OwnSources.Anchor start = anchor;
        float[][][] env = blast == null || profiles == null ? null : profiles.getNow(null);
        queued += queue(done, env, volume, blast, (t, gain) -> OwnSources.play(this, start, t.start(), t.azimuth(), gain, t.pcm(), EchoMix.RATE, false,
                false, blast == null ? null : blast::factor));
    }

    /** Echo track to our own source: false — no slot. */
    @FunctionalInterface
    interface Player {
        boolean play(EchoMix.Track track, float gain);
    }

    /**
     * Send tracks {@code done} to our own sources ({@code player}) at direct-impact {@code volume} × track volume;
     * accepted tracks become layers of {@code blast} in the mix (envelopes {@code env}; null — no mix), starting at the
     * direct impact with their own delays, like their voices. Returns the number accepted.
     */
    static int queue(EchoMix.Mix done, float @Nullable [][][] env, float volume, @Nullable Mix.Blast blast, Player player) {
        int queued = 0;
        for (int i = 0; i < done.tracks().size(); i++) {
            EchoMix.Track t = done.tracks().get(i);
            float gain = Math.min(16, volume * t.gain());
            if (!player.play(t, gain)) continue;
            queued++;
            if (blast != null && env != null && i < env.length) blast.add(Mix.Layer.own(env[i][0], env[i][1], gain, t.start()));
        }
        return queued;
    }

    /** Tracks are already queued on our own sources (or there are and will be none): echoes prepare nothing further. */
    public boolean settled() {
        return stopped || done != null;
    }

    /** Blast ended before its echoes were ready (front never reached the ear): discard waiting; ready tracks keep playing. */
    public void release() {
        if (!settled()) stop();
    }

    /** Blast removed or now in the past: discard voices and start waiting; no new ones. */
    public void stop() {
        stopped = true;
        OwnSources.stop(this);
    }

    private void trace(Vec3 ear, int rays) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level.dimensionType().hasCeiling()) return;
        if (from == null || from.distanceTo(ear) > MOVED) {
            from = ear;
            nextRay = 0;
            found.clear();
        }
        if (nextRay >= TerrainEcho.RAYS) return;
        long t0 = System.nanoTime();
        Sightline.Heights heights = ground(level);
        Vec3 b = burst;
        for (int i = 0; i < rays && nextRay < TerrainEcho.RAYS; i++, nextRay++) {
            TerrainEcho.ray(heights, nextRay, TerrainEcho.RAYS, TerrainEcho.RANGE, from.x, from.y, from.z, b.x, b.y, b.z, blast, found);
        }
        traceSlowest = Math.max(traceSlowest, System.nanoTime() - t0);
    }

    /** Height map without void below the world (its top is the world bottom). */
    private static Sightline.Heights ground(ClientLevel level) {
        Sightline.Heights h = FarTerrain.of(level);
        int floor = level.getMinBuildHeight() + 1;
        return (x, z) -> {
            double v = h.at(x, z);
            return v <= floor ? Double.NaN : v;
        };
    }

    /**
     * Opening of the direct-impact recording (the variant selected by the seed) as a grain at {@link EchoMix#RATE}.
     * On a background thread: resources are read just as sound loading reads them.
     */
    private static float[] read(SoundEvent direct, long seed) {
        Minecraft mc = Minecraft.getInstance();
        WeighedSoundEvents event = mc.getSoundManager().getSoundEvent(direct.getLocation());
        if (event == null) return new float[0];
        Sound sound = event.getSound(RandomSource.create(seed));
        try (InputStream in = mc.getResourceManager().open(sound.getPath()); JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
            int rate = (int) ogg.getFormat().getSampleRate(), channels = ogg.getFormat().getChannels();
            int limit = (int) (GRAIN_SECONDS * rate * channels) + 8192;
            FloatArrayList samples = new FloatArrayList(limit);
            while (samples.size() < limit && ogg.readChunk(samples::add)) {
                // read to the required length
            }
            return EchoMix.grain(samples.toFloatArray(), channels, rate, GRAIN_SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** {@code SCENARIO} line: rays, reflections and the strongest ones (delay, direction, level). */
    public String describe() {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "echo rays %d/%d (slowest tick %.2f ms) taps %d", nextRay, TerrainEcho.RAYS,
                traceSlowest / 1e6, found.size()));
        EchoMix.Mix m = done;
        if (m != null) {
            Vec3 e = from, bu = burst;
            if (e != null) sb.append(String.format(Locale.ROOT, " ear %.0f,%.0f,%.0f burst %.0f,%.0f,%.0f", e.x, e.y, e.z, bu.x, bu.y, bu.z));
            double sum = found.stream().mapToDouble(TerrainEcho.Reflection::energy).sum();
            // start: actual direct-impact start in the engine (sound) or tick (tick — engine did not start it in time)
            var sound = direct == null ? null : direct.getSound();
            sb.append(String.format(Locale.ROOT, " sum %.1fdB tracks %d queued %d t60 %.1f vol %.2f start +%.2fs by %s of %s", 10 * Math.log10(Math.max(1e-9, sum)),
                    m.tracks().size(), queued, m.t60(), volume, anchor == null ? Double.NaN : anchor.lateSeconds(),
                    anchor == null ? "-" : anchor.heard() ? "sound" : "tick", sound == null ? "-" : sound.getLocation().getPath()));
            for (EchoMix.Track t : m.tracks()) {
                sb.append(String.format(Locale.ROOT, " [az %.0f start %.2fs len %.1fs x%.2f]", Math.toDegrees(t.azimuth()), t.start() / 20, t.seconds(), t.gain()));
            }
            found.stream().sorted(Comparator.comparingDouble(TerrainEcho.Reflection::energy).reversed()).limit(6).forEach(r ->
                    sb.append(String.format(Locale.ROOT, " {d %.2fs az %.0f %.1fdB r %.0f at %.0f,%.0f,%.0f}", r.delay() / 20, Math.toDegrees(r.azimuth()),
                            10 * Math.log10(r.energy()), r.distance(), r.x(), r.y(), r.z())));
        }
        return sb.toString();
    }
}
