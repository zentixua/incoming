package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.FuelBurn;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.strike.Hearing;

/** A recording supplies the fire's timbre; the burning fuel supplies its envelope, at emission time. */
final class CraterFire {
    private static final int RETRY = 10;
    private final FuelBurn burn;
    private final ClientLevel world;
    private AmbientLoop sound;
    private int age, retry;

    /** Fuel {@code burn} (its law and where it burns now), {@code age} ticks after the strike. */
    CraterFire(FuelBurn burn, ClientLevel world, int age) {
        this.burn = burn;
        this.world = world;
        this.age = age;
        this.retry = age;
    }

    /** Source heat release at the emission time heard now; delay never changes the recording's playback rate. */
    static double heat(FuelBurn burn, double ageSeconds, double distance) {
        return burn.law().watts() * burn.share(ageSeconds - distance / Noise.SOUND);
    }

    boolean tick(Minecraft mc) {
        if (mc.level != world) {
            stop();
            return false;
        }
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition(), pos = burn.centre();
        double distance = pos.distanceTo(ear), seconds = age++ / 20.0, emitted = seconds - distance / Noise.SOUND;
        double watts = heat(burn, seconds, distance);
        // fuel gone by the law or put out (covered, washed) before it: once heard silent, it stays silent
        if (emitted >= burn.law().seconds() || emitted > 0 && watts <= 0 || seconds > burn.law().seconds() + Hearing.MAX_DELAY / 20.0) {
            stop();
            return false;
        }
        float volume = watts <= 0 || distance > Hearing.RANGE ? 0 : Sources.volume(Sources.BLAST_FIRE.power(Sources.flame(watts, Spectra.BLAST_FIRE)),
                pos, ear, Propagation.Weather.of(world));
        if (volume < VoiceBudget.AUDIBLE || mc.options.getSoundSourceVolume(SoundSource.AMBIENT) <= 0
                || mc.options.getSoundSourceVolume(SoundSource.MASTER) <= 0) {
            stop();
            return true;
        }
        float open = SoundFilters.open(ear, pos.add(0, 1.5, 0));
        // A device reload or a category slider can remove a channel without stopping its SoundInstance.
        if (sound != null && !sound.playing() && age >= retry) stop();
        if (sound == null && age >= retry) {
            sound = new AmbientLoop(ModSounds.BLAST_FIRE.get(), SoundSource.AMBIENT, 1, pos.hashCode());
            sound.set(ear, pos, volume, distance, open);
            mc.getSoundManager().play(sound);
            retry = age + RETRY;
        } else if (sound != null) {
            sound.set(ear, pos, volume, distance, open);
        }
        return true;
    }

    void stop() {
        if (sound != null) sound.end();
        sound = null;
    }
}
