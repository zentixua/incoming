package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Civil-defense siren at a fixed world position — loops during alarm. Siren pitch is rotor speed: when switched
 * on it spins up (pitch and volume rise), when off ({@link #off}) it coasts down and falls silent itself; sound
 * power is full-speed rotor power ({@link Sources#NUKE_ALARM}) × squared speed fraction. Volume at ear follows
 * source law ({@link Sources#volume}: ISO 9613 path and background) each tick: vanilla takes attenuation range
 * from volume only once at start, but a spinning-up siren's volume rises from zero. Air and barriers between
 * siren and ear update each tick ({@link SoundFilters}), like a projectile engine: alarm lasts the whole ICBM
 * flight, while listener leaves and re-enters a house.
 */
public final class SirenSound extends AbstractTickableSoundInstance implements SoundFilters.Muffled {
    /** Fraction of remaining rotor-speed path to full per tick: spin-up ~3 s. */
    static final float SPIN_UP = 0.05f;
    /** Speed fraction lost per tick while coasting: falls silent in ~4 s. */
    static final float SPIN_DOWN = 0.035f;

    private final Vec3 at;
    /** Full-speed pitch: neighbouring sirens differ slightly and “drift” together, as outdoors. */
    private final float tone;
    private final Occlusion occlusion = new Occlusion();
    /** Rotor speed, fraction of full. */
    private float speed;
    private boolean on = true;
    private float filterGain = 1, filterHighs = 1;

    public SirenSound(SoundEvent event, Vec3 at, float tone) {
        super(event, SoundSource.AMBIENT, RandomSource.create());
        this.at = at;
        this.tone = tone;
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = false;
        this.x = at.x;
        this.y = at.y;
        this.z = at.z;
        this.volume = 0;
        this.pitch = 0.5f * tone;
    }

    /** Alarm ended: rotor coasts down, sound fades itself. */
    public void off() {
        on = false;
    }

    /** Silence immediately (world exit, engine removed channel). */
    public void kill() {
        stop();
    }

    /** Plays from zero: spin-up begins in silence. */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public float lowpassGain() {
        return filterGain;
    }

    @Override
    public float lowpassHighs() {
        return filterHighs;
    }

    /**
     * Engine holds siren channel. Not {@code SoundManager.isActive}: category slider at 0 removes channel
     * ({@code SoundEngine.tickNonPaused}), but {@code isActive} still reports “playing” from start record.
     */
    public boolean playing() {
        return Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.containsKey(this);
    }

    @Override
    public void tick() {
        // engine ticks even a stopped sound until removed, and one whose channel the category slider removed until
        // all sounds stop (world exit, dimension change)
        if (isStopped()) return;
        speed = on ? speed + (1 - speed) * SPIN_UP : speed * (1 - SPIN_DOWN);
        float power = speed * speed;
        if (!on && power <= VoiceBudget.AUDIBLE) {
            stop();
            return;
        }
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double d = ear.distanceTo(at);
        this.volume = power <= 0 ? 0 : Sources.volume(Sources.NUKE_ALARM.power(Sources.NUKE_ALARM.lwA() + 10 * Math.log10(power)), at);
        // vanilla cannot play below half pitch: rotor falls silent there
        this.pitch = tone * (0.5f + 0.5f * speed);
        if (volume > VoiceBudget.AUDIBLE) {
            float open = occlusion.open(ClientSounds.now(), ear, at);
            filterGain = SoundFilters.blockedGain(open);
            filterHighs = SoundFilters.air(d) * SoundFilters.blockedHighs(open);
            SoundFilters.update(this, filterGain, filterHighs);
        }
    }
}
