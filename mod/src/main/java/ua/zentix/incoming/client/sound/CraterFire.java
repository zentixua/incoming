package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.FuelBurn;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
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

    /** One tick: what is heard now goes into {@code loops}; false — the fire is over (or the world changed). */
    boolean tick(Minecraft mc, Quarters loops) {
        if (mc.level != world) return false;
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition(), pos = burn.centre();
        double distance = pos.distanceTo(ear), seconds = age++ / 20.0, emitted = seconds - distance / Noise.SOUND;
        double watts = heat(burn, seconds, distance);
        // fuel gone by the law or put out (covered, washed) before it: once heard silent, it stays silent
        if (emitted >= burn.law().seconds() || emitted > 0 && watts <= 0 || seconds > burn.law().seconds() + Hearing.MAX_DELAY / 20.0) return false;
        float volume = watts <= 0 || distance > Hearing.RANGE ? 0 : Sources.volume(Sources.BLAST_FIRE.power(Sources.flame(watts, Spectra.BLAST_FIRE)),
                pos, ear, Propagation.Weather.of(world));
        if (volume < VoiceBudget.AUDIBLE) return true;
        float open = SoundFilters.open(ear, pos.add(0, 1.5, 0));
        loops.add(Math.atan2(pos.z - ear.z, pos.x - ear.x), volume, SoundFilters.blockedGain(open), SoundFilters.air(distance) * SoundFilters.blockedHighs(open));
        return true;
    }
}
