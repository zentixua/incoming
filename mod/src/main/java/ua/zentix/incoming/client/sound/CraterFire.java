package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.FuelBurn;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.strike.Hearing;

/**
 * A recording supplies the fire's timbre; the burning fuel supplies its envelope, at emission time. Its sound is one
 * contribution to the fire loops around the ear ({@link Quarters}, played by {@link BlastSounds}), not a loop of its
 * own: a raid lights hundreds of craters, and a loop each took most of the engine's channels.
 */
final class CraterFire {
    private final FuelBurn burn;
    private final ClientLevel world;
    private int age;
    /** Open share of the path from the ear by the last obstruction ray ({@link FireRays}); NaN — no ray yet. */
    float open = Float.NaN;
    /** Tick of the last ray and the fire's turn among the not yet rayed of its sector this tick ({@link FireRays}). */
    long rayAt = Long.MIN_VALUE;
    int turn;
    /** What {@link #hear} found this tick: heard at all, loop volume, azimuth from the ear (atan2(dz, dx)), distance. */
    boolean heard;
    private float volume;
    double azimuth;
    private double distance;

    /** Fuel {@code burn} (its law and where it burns now), {@code age} ticks after the strike. */
    CraterFire(FuelBurn burn, ClientLevel world, int age) {
        this.burn = burn;
        this.world = world;
        this.age = age;
    }

    /** Source heat release at the emission time heard now; delay never changes the recording's playback rate. */
    static double heat(FuelBurn burn, double ageSeconds, double distance) {
        return burn.law().watts() * burn.share(ageSeconds - distance / Noise.SOUND);
    }

    /**
     * Loop volume (the file's) of a fire releasing {@code watts} at {@code pos}, 0 if it is not heard at all: below the
     * background in every octave ({@link Loudness#gain}, the calibrated level at the ear, no file in it). Audibility is a
     * property of the sound at the ear, not of the file: gated on the file's volume, the threshold moved with every
     * relevel of the file (at the ceiling, 8.1 dB louder, a 2 MW fire went silent at 790 m instead of 1630 m — review R11).
     */
    static float volume(Sources.Source source, double watts, Vec3 pos, Vec3 ear, Propagation.Weather weather) {
        Sources.Source s = source.power(Sources.flame(watts, source.spectrum()));
        double gain = Sources.gain(s, pos, ear, weather);
        return gain > 0 ? Loudness.volume(gain, s.fileLufs()) : 0;
    }

    /** One tick: is it heard now ({@link #hear}); false — the fire is over (or the world changed). */
    boolean tick(Minecraft mc) {
        if (mc.level != world) return false;
        return hear(mc.gameRenderer.getMainCamera().getPosition(), Propagation.Weather.of(world));
    }

    /**
     * One tick at the ear {@code ear} (no Minecraft): heard now or not and how loud. Into the loops it goes after this
     * tick's rays ({@link FireRays#tick}, {@link #mix}). False — the fire is over.
     */
    boolean hear(Vec3 ear, Propagation.Weather weather) {
        Vec3 pos = burn.centre();
        distance = pos.distanceTo(ear);
        double seconds = age++ / 20.0, emitted = seconds - distance / Noise.SOUND;
        double watts = heat(burn, seconds, distance);
        // fuel gone by the law or put out (covered, washed) before it: once heard silent, it stays silent
        if (emitted >= burn.law().seconds() || emitted > 0 && watts <= 0 || seconds > burn.law().seconds() + Hearing.MAX_DELAY / 20.0) return false;
        volume = watts <= 0 || distance > Hearing.RANGE ? 0 : volume(Sources.BLAST_FIRE, watts, pos, ear, weather);
        heard = volume > 0;
        azimuth = Math.atan2(pos.z - ear.z, pos.x - ear.x);
        return true;
    }

    /** Into {@code loops}, if heard, through the open share {@code open} of the path (air and settings through {@code path}). */
    void mix(Quarters loops, SoundFilters.Path path, float open) {
        if (heard) loops.add(azimuth, volume, SoundFilters.blockedGain(open), path.air(distance) * SoundFilters.blockedHighs(open));
    }

    /** Has the fire had an obstruction ray yet. */
    boolean rayed() {
        return !Float.isNaN(open);
    }

    /** Where the fire burns now (checks). */
    Vec3 burnCentre() {
        return burn.centre();
    }

    /** Heat release heard now at {@code ear}, as the last {@link #hear} took it (checks). */
    double wattsNow(Vec3 ear) {
        double distance = burn.centre().distanceTo(ear);
        return heat(burn, (age - 1) / 20.0, distance);
    }

    /** Obstruction ray from the ear {@code ear} to the fire along {@code path}, in tick {@code tick}. */
    void ray(Vec3 ear, SoundFilters.Path path, long tick) {
        open = path.open(ear, burn.centre().add(0, 1.5, 0));
        rayAt = tick;
    }
}
