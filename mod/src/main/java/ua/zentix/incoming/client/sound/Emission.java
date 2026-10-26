package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectrum;
import ua.zentix.incoming.strike.Hearing;

/**
 * What the listener hears from a projectile this tick: “retarded” emission {@code time} and everything sound layers
 * derive from it ({@link EngineSound.Layer#tone}) — position, distance, direction to ear, velocity, Doppler,
 * flight phase and sound path to ear ({@link #heard}: each noisy part's level after propagation). Computed once
 * per projectile per tick, shared by all its layers.
 *
 * @param nx      unit vector from source to ear
 * @param radial  velocity projected onto it (positive — projectile approaches listener)
 * @param height  source height above ground below listener, m ({@link Hearing#height})
 * @param onboard listener is on the projectile itself (projectile camera)
 */
record Emission(double time, double x, double y, double z, double distance, double nx, double ny, double nz, Vec3 velocity,
                double radial, double doppler, int phase, double phaseAge, double height, Propagation.Weather weather, boolean onboard,
                Heard[] parts) {
    /** Listener ahead if projectile approaches within this angle of heading; behind from {@link #BEHIND_DEG} (est.). */
    static final double AHEAD_DEG = 45, BEHIND_DEG = 100;
    private static final double AHEAD_COS = Math.cos(Math.toRadians(AHEAD_DEG)), BEHIND_COS = Math.cos(Math.toRadians(BEHIND_DEG));

    /**
     * Noisy part at the ear after an open-air path: source sound power, A-level at ear, margin above background
     * in the best octave ({@link Propagation#margin}) and high fraction (4 kHz octave relative to all sound).
     */
    record Heard(double power, double level, double margin, double highs) {
        static final Heard SILENT = new Heard(Noise.SILENT, Noise.SILENT, Double.NEGATIVE_INFINITY, 1);
    }

    /** Audible from time te: projectile data must exist for it ({@link FlightTrack#covers}). */
    static Emission at(FlightTrack track, double te, Vec3 ear, boolean onboard, Propagation.Weather weather) {
        double[] p = new double[3];
        track.at(te, p);
        double dx = ear.x - p[0], dy = ear.y - p[1], dz = ear.z - p[2];
        double d = Math.max(0.5, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double nx = dx / d, ny = dy / d, nz = dz / d;
        Vec3 v = track.velocity(te);
        return new Emission(te, p[0], p[1], p[2], d, nx, ny, nz, v, v.x * nx + v.y * ny + v.z * nz,
                Acoustics.doppler(v.x, v.y, v.z, nx, ny, nz), track.phase(te), track.phaseAge(te),
                Hearing.height(new Vec3(p[0], p[1], p[2]), ear), weather, onboard, new Heard[Noise.Part.values().length]);
    }

    /** Sound path to the ear: ear {@link Propagation#EAR} above meadow, source at {@link #height}. */
    Propagation.Path path(double distance) {
        return Propagation.Path.over(distance, height, weather);
    }

    /** Projectile noise {@code part} at the ear (computed once per tick). */
    Heard heard(Noise noise, Noise.Part part, boolean drilling) {
        Heard h = parts[part.ordinal()];
        if (h != null) return h;
        double lw = noise.power(part, FlightPhase.byId(phase), phaseAge, velocity.length(), drilling);
        if (lw == Noise.SILENT) h = Heard.SILENT;
        else {
            Spectrum spectrum = noise.spectrum(part);
            double[] bands = new double[Propagation.BANDS];
            double level = Propagation.level(lw, spectrum, path(distance), bands);
            h = new Heard(lw, level, Propagation.margin(bands, weather), highs(lw, spectrum, path(distance)));
        }
        parts[part.ordinal()] = h;
        return h;
    }

    /**
     * High fraction of {@code part} as if the ear were {@code distance} blocks away (same source height and weather,
     * air at recording conditions, {@link Propagation.Weather#recorded}): what the path already contained in the
     * distant recording would have absorbed.
     */
    double heardAt(Noise noise, Noise.Part part, boolean drilling, double distance) {
        double lw = noise.power(part, FlightPhase.byId(phase), phaseAge, velocity.length(), drilling);
        return lw == Noise.SILENT ? 1 : highs(lw, noise.spectrum(part), Propagation.Path.over(distance, height, weather.recorded()));
    }

    /**
     * High fraction of sound power {@code lw} with {@code spectrum} after {@code path}: how much more the 4 kHz
     * octave attenuated along the path than sound overall (EFX muffles above 5 kHz).
     */
    static double highs(double lw, Spectrum spectrum, Propagation.Path path) {
        double[] bands = new double[Propagation.BANDS];
        double level = Propagation.level(lw, spectrum, path, bands);
        int hf = Propagation.BANDS - 2;
        double emitted = lw + spectrum.aWeighting(), lost4k = lw + spectrum.z(hf) + Propagation.aWeight(hf) - bands[hf];
        return Math.min(1, Math.pow(10, -(lost4k - (emitted - level)) / 20));
    }

    /**
     * How far ahead of the projectile the listener is: 1 — projectile approaches within {@link #AHEAD_DEG} of heading,
     * 0 — listener sideways/behind (beyond {@link #BEHIND_DEG}), smoothly between. Fan and airflow noise project
     * forward: they fade in fractions of a second on a pass, rather than cutting off at closest approach.
     */
    double ahead() {
        double v = velocity.length();
        return v < 1.0e-6 ? 0 : Acoustics.smoothstep(BEHIND_COS, AHEAD_COS, radial / v);
    }

    Vec3 position() {
        return new Vec3(x, y, z);
    }

    boolean approaching() {
        return radial > 0;
    }
}
