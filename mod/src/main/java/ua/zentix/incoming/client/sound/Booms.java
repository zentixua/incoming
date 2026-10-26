package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.SonicBoom;
import ua.zentix.incoming.strike.Hearing;

/**
 * Supersonic projectile crack: its Mach cone reached the ear. Outside the cone there is no sound at all (no roots
 * of the retarded-time equation, {@link Acoustics#emissions}); as the cone passes the listener, an N-wave arrives:
 * a pressure jump and an equal reverse jump after {@code Δt} (MLRS rocket: milliseconds, dry crackle; large body:
 * tens of milliseconds, double impact), followed only then by projectile sound emitted when it was already closer.
 */
final class Booms {
    /** N-wave duration in {@code sonic.crack} and {@code sonic.boom} recordings, s ({@code tools/build_sounds.py}). */
    static final double CRACK = 0.004, BOOM = 0.032;
    /** Volume 1 from this pressure (1 psi, presentation), quieter by half pressure decibels, like blasts ({@code Outdoor}). */
    static final double FULL_PA = 1000 * BlastModel.kpa(1);

    private Booms() {}

    /**
     * Projectile sound first reached the ear (emission time {@code te}): if it was supersonic then, the Mach cone
     * arrived — play an N-wave. The known path's beginning does not count: sound from the first history point is
     * not a cone, but the edge of what the client knows about the projectile.
     */
    static void reached(FlightTrack track, double te, Vec3 ear, Propagation.Weather weather) {
        Crack c = crack(track, te, ear, weather);
        if (c == null || c.volume < 0.005f) return;
        ClientSounds.atEar((c.longWave ? ModSounds.SONIC_BOOM : ModSounds.SONIC_CRACK).get(), c.at, c.volume, c.pitch);
    }

    /**
     * Crack arriving if sound first reached from time {@code te}: N-wave at the ear, volume, recording (short or
     * long) and pitch stretching it to wave duration; {@code null} — projectile was not supersonic then.
     */
    static Crack crack(FlightTrack track, double te, Vec3 ear, Propagation.Weather weather) {
        // nor on the extension after the last path sample: there it is only extrapolated at the last velocity
        if (te < track.start() + 2 || te > track.lastTick() || !track.covers(te)) return null;
        // SAM path has no weapon: body comes from its spec
        Noise.Body body = track.weapon == null ? InterceptorSound.BODY : EngineSound.noise(track).body();
        Vec3 v = track.velocity(te);
        double mach = v.length() / Acoustics.SPEED;
        if (body == null || mach <= 1) return null;
        double[] p = new double[3];
        track.at(te, p);
        Vec3 at = new Vec3(p[0], p[1], p[2]);
        Vec3 n = ear.subtract(at);
        double d = n.length();
        // distance from flight line to ear
        double cos = d < 1.0e-6 ? 1 : n.dot(v) / (d * v.length());
        SonicBoom.Wave w = SonicBoom.at(mach, d * Math.sqrt(Math.max(0, 1 - cos * cos)), body);
        Outdoor.Path path = new Outdoor.Path(d, 0, Hearing.height(at, ear), Propagation.EAR, 1, 1, weather.shadow(), weather.rain(), weather.thunder());
        boolean longWave = w.seconds() >= Math.sqrt(CRACK * BOOM);
        float pitch = (float) Math.max(0.5, Math.min(2, (longWave ? BOOM : CRACK) / w.seconds()));
        return new Crack(at, w, Outdoor.pressureVolume(w.pressure(), FULL_PA, path), longWave, pitch);
    }

    /** Crack at the ear: direction, wave, volume, whether the recording is long, and its pitch. */
    record Crack(Vec3 at, SonicBoom.Wave wave, float volume, boolean longWave, float pitch) {}
}
