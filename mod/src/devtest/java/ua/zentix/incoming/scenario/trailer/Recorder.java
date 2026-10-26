package ua.zentix.incoming.scenario.trailer;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundSource;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Trailer plan recording: 60 frames per second of video, frame k is exactly the moment {@code k·step()} tick from the beginning of plan
 *  (the game clock is kept by {@link FrameClock}), so the video is smooth at any rendering speed. The sound is not recorded from the device (the game is running
 *  slowly), but is logged: what file, where, volume, tone, range - {@code tools/trailer/edit.py}
 *  reassembles the track from this. Everything is in {@code timeline.jsonl} next to the frames.
 */
final class Recorder implements SoundEventListener {
    static final double VIDEO_FPS = 60, TPS = 20;
    /** How long can PNG wait to write to disk before rendering waits. */
    private static final int MAX_PENDING = 12;

    private final Path root;
    private final Writer log;
    private final AtomicInteger pending = new AtomicInteger();
    /** Sounds that still sound: their volume and tone change (motors, Doppler) - we write every frame. */
    private final Map<SoundInstance, Integer> playing = new IdentityHashMap<>();
    private int nextSoundId;

    private String shot;
    private double speed;
    private int nextFrame;
    private double time;

    Recorder(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
            log = Files.newBufferedWriter(root.resolve("timeline.jsonl"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    boolean recording() {
        return shot != null;
    }

    double speed() {
        return speed;
    }

    /**
     * Start plan: frames will go to {@code frames/<name>/}. {@code speed} < 1 - deceleration, > 1 - acceleration. {@code hud} —
     *  in the frame there is the text of the game interface: this editing plan is taken from the recording in the language of the video (the language of the game is in the title of the plan).
     */
    void start(String name, double speed, boolean hud) {
        this.shot = name;
        this.speed = speed;
        this.nextFrame = 0;
        this.time = 0;
        playing.clear(); // we don’t bring the sounds of the previous plan into this one
        try {
            Files.createDirectories(root.resolve("frames").resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        write(String.format(Locale.ROOT, "{\"type\":\"shot\",\"shot\":\"%s\",\"speed\":%.4f,\"hud\":%b,\"lang\":\"%s\"}", name, speed, hud,
                Minecraft.getInstance().options.languageCode));
        Incoming.LOG.info("TRAILER shot {} x{}", name, speed);
        // loops that sounded before the plan and sound further (motors of projectiles in flight, started at launch) - from the beginning of the
        //  plan: the engine reports the sound only at launch, and without this the chase plans would go without a motor
        for (SoundInstance sound : List.copyOf(Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.keySet())) {
            if (sound.isLooping()) log(sound);
        }
    }

    void stop() {
        if (shot == null) return;
        write(String.format(Locale.ROOT, "{\"type\":\"end\",\"shot\":\"%s\",\"frames\":%d}", shot, nextFrame));
        Incoming.LOG.info("TRAILER shot {} done: {} frames", shot, nextFrame);
        shot = null;
        flush();
    }

    /** Mark for installation (impact, explosion...) at the current moment of the plan. */
    void mark(String what) {
        if (shot == null) return;
        write(String.format(Locale.ROOT, "{\"type\":\"mark\",\"shot\":\"%s\",\"t\":%.3f,\"what\":\"%s\"}", shot, time, what));
    }

    /** tick the game per frame of video in the current plan. */
    double step() {
        return TPS * speed / VIDEO_FPS;
    }

    int frames() {
        return nextFrame;
    }

    /** Take a frame of the plan (moment {@code t} tick from the beginning of the plan - exactly {@code frames()·step()}). */
    void frame(double t, Camera camera) {
        if (shot == null) return;
        time = t;
        int k = nextFrame++;
        var cam = camera.getPosition();
        write(String.format(Locale.ROOT, "{\"type\":\"frame\",\"shot\":\"%s\",\"k\":%d,\"t\":%.3f,\"cam\":[%.2f,%.2f,%.2f,%.2f,%.2f]}",
                shot, k, t, cam.x, cam.y, cam.z, camera.getYRot(), camera.getXRot()));
        soundUpdates(t);
        grab(root.resolve("frames").resolve(shot).resolve(String.format(Locale.ROOT, "%05d.png", k)));
    }

    private void grab(Path file) {
        NativeImage image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        if (pending.get() >= MAX_PENDING) {
            save(image, file);
            return;
        }
        pending.incrementAndGet();
        Util.ioPool().execute(() -> {
            save(image, file);
            pending.decrementAndGet();
        });
    }

    private static void save(NativeImage image, Path file) {
        try (image) {
            image.writeToFile(file);
        } catch (IOException e) {
            Incoming.LOG.warn("TRAILER frame {} not recorded", file, e);
        }
    }

    /** The sound started playing (the engine had already selected the file). We don’t use music or background sounds of the game - the trailer has its own music. */
    @Override
    public void onPlaySound(SoundInstance sound, WeighedSoundEvents accessor, float range) {
        if (shot != null) log(sound);
    }

    private void log(SoundInstance sound) {
        SoundSource source = sound.getSource();
        if (source == SoundSource.MUSIC || source == SoundSource.RECORDS) return;
        Sound s = sound.getSound();
        if (s == null) return;
        int id = nextSoundId++;
        playing.put(sound, id);
        write(String.format(Locale.ROOT,
                "{\"type\":\"sound\",\"shot\":\"%s\",\"t\":%.3f,\"id\":%d,\"event\":\"%s\",\"file\":\"%s\",\"pos\":[%.2f,%.2f,%.2f],"
                        + "\"volume\":%.4f,\"pitch\":%.4f,\"range\":%.1f,\"relative\":%b,\"linear\":%b,\"loop\":%b,\"stream\":%b,\"lp\":%s}",
                shot, time, id, sound.getLocation(), s.getPath(), sound.getX(), sound.getY(), sound.getZ(), sound.getVolume(),
                sound.getPitch(), Math.max(sound.getVolume(), 1f) * s.getAttenuationDistance(), sound.isRelative(),
                sound.getAttenuation() == SoundInstance.Attenuation.LINEAR, sound.isLooping(), s.shouldStream(), lowpass(sound)));
    }

    /** Mod path filter (air, obstacles): [volume, high share]; without filter - [1, 1]. */
    private static String lowpass(SoundInstance s) {
        if (s instanceof ua.zentix.incoming.client.sound.SoundFilters.Muffled m) {
            return String.format(Locale.ROOT, "[%.3f,%.3f]", m.lowpassGain(), m.lowpassHighs());
        }
        return "[1,1]";
    }

    /** Changing volume, tone and location of sounds; the end is when the engine lets them go. */
    private void soundUpdates(double t) {
        var manager = Minecraft.getInstance().getSoundManager();
        for (Iterator<Map.Entry<SoundInstance, Integer>> it = playing.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            SoundInstance s = e.getKey();
            if (!manager.isActive(s)) {
                write(String.format(Locale.ROOT, "{\"type\":\"stop\",\"shot\":\"%s\",\"t\":%.3f,\"id\":%d}", shot, t, e.getValue()));
                it.remove();
                continue;
            }
            if (s instanceof net.minecraft.client.resources.sounds.TickableSoundInstance) {
                write(String.format(Locale.ROOT, "{\"type\":\"update\",\"shot\":\"%s\",\"t\":%.3f,\"id\":%d,\"pos\":[%.2f,%.2f,%.2f],\"volume\":%.4f,\"pitch\":%.4f,\"lp\":%s}",
                        shot, t, e.getValue(), s.getX(), s.getY(), s.getZ(), s.getVolume(), s.getPitch(), lowpass(s)));
            }
        }
    }

    private void write(String line) {
        try {
            log.write(line);
            log.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void flush() {
        try {
            log.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Wait until all frames are recorded (before exiting the game). */
    void finish() {
        stop();
        while (pending.get() > 0) Thread.onSpinWait();
        try {
            log.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
