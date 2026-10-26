package ua.zentix.replayscan.film;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

/**
 * Export sounds: each start writes {@code sounds.jsonl} (tick, ID, position, camera volume, source in
 * frame). Count loud sounds ({@link Plan#loud}) on/off-screen; off-screen must not exceed on-screen.
 * Remember engines ({@link Plan#engine}); {@link #engine} gives subject engine volume in this frame.
 * Camera volume matches game: linear attenuation {@code volume × (1 − distance / range)}, otherwise volume.
 */
final class Sounds implements SoundEventListener {
    /** Minimum camera volume for counting a loud sound (distant rolls are quieter). */
    static final double LOUD = 0.1;

    private final Pattern loud, engine;
    private final DoubleSupplier tick;
    private final Writer out;
    private final List<SoundInstance> engines = new ArrayList<>();
    /** Last-frame camera: eye, forward/right/up axes, half-frame tangents. */
    @Nullable
    private Vec3 eye, fwd, right, up;
    private double tanX, tanY;
    int loudOn, loudOff;

    Sounds(Path file, Pattern loud, Pattern engine, DoubleSupplier tick) throws IOException {
        this.loud = loud;
        this.engine = engine;
        this.tick = tick;
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
    }

    /** Frame camera determines sound in-frame status. */
    void camera(Vec3 eye, Vec3 fwd, Vec3 right, Vec3 up, double tanX, double tanY) {
        this.eye = eye;
        this.fwd = fwd;
        this.right = right;
        this.up = up;
        this.tanX = tanX;
        this.tanY = tanY;
    }

    @Override
    public void onPlaySound(SoundInstance sound, WeighedSoundEvents events, float range) {
        double t = tick.getAsDouble();
        if (Double.isNaN(t) || eye == null) return;
        String id = sound.getLocation().toString();
        if (engine.matcher(id).find()) engines.add(sound);
        boolean relative = sound.isRelative();
        Vec3 at = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        double gain = relative ? sound.getVolume() : gain(sound, at, range);
        Boolean on = relative ? null : onScreen(at);
        if (!relative && gain >= LOUD && loud.matcher(id).find() && !engine.matcher(id).find()) {
            if (Boolean.TRUE.equals(on)) loudOn++;
            else loudOff++;
        }
        try {
            out.write(String.format(Locale.ROOT, "{\"t\":%.2f,\"id\":\"%s\",\"x\":%.1f,\"y\":%.1f,\"z\":%.1f,\"gain\":%.3f,\"on\":%s}\n", t, id, at.x,
                    at.y, at.z, gain, on));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Sound volume at camera; {@code range} is the game-assigned range. */
    private double gain(SoundInstance s, Vec3 at, double range) {
        double v = s.getVolume();
        if (s.getAttenuation() == SoundInstance.Attenuation.NONE) return v;
        return v * Math.max(0, 1 - at.distanceTo(eye) / Math.max(1e-3, range));
    }

    /** Source in frame with one-tenth-half-frame margins, using last-frame camera. */
    private boolean onScreen(Vec3 at) {
        Vec3 d = at.subtract(eye);
        double z = d.dot(fwd);
        if (z <= 0.1) return false;
        return Math.abs(d.dot(right) / z) <= tanX * 1.1 && Math.abs(d.dot(up) / z) <= tanY * 1.1;
    }

    /** Loudest camera engine whose source is within {@code radius} of subject; 0 inaudible. */
    double engine(@Nullable Vec3 subject, double radius) {
        var manager = Minecraft.getInstance().getSoundManager();
        engines.removeIf(s -> !manager.isActive(s));
        if (subject == null || eye == null) return 0;
        double best = 0;
        for (SoundInstance s : engines) {
            Vec3 at = new Vec3(s.getX(), s.getY(), s.getZ());
            if (s.isRelative() || s.getSound() == null || at.distanceTo(subject) > radius) continue;
            double range = Math.max(s.getVolume(), 1) * s.getSound().getAttenuationDistance();
            best = Math.max(best, gain(s, at, range));
        }
        return best;
    }

    void close() {
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
