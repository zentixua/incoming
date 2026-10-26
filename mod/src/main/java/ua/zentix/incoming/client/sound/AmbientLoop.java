package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * An ambient loop driven by its owner (a burning city's roar, wind feeding a fire): each tick the owner sets
 * its direction, volume and source distance (air absorbs highs, {@link SoundFilters}); volume changes smoothly.
 * When the owner stops driving it (detonation removed, replay rewound), the loop fades and goes away itself.
 */
public class AmbientLoop extends AbstractTickableSoundInstance implements SoundFilters.Muffled {
    /** Volume approaches the target by this much per tick (silence to full in one second; presentation). */
    private static final float SLEW = 0.05f;
    /** After this many ticks without the owner, the loop goes away (budget: no channel held for a departed owner). */
    private static final int ORPHAN = 10;

    private float slew;
    private float target, highs = 1, open = 1;
    private int quiet;
    private boolean ending;

    public AmbientLoop(SoundEvent event, SoundSource source, float pitch, long seed) {
        this(event, source, pitch, seed, SLEW);
    }

    /** {@code slew} is how far volume approaches the target per tick (1 — immediately: gust behind the wave front). */
    public AmbientLoop(SoundEvent event, SoundSource source, float pitch, long seed, float slew) {
        super(event, source, RandomSource.create(seed));
        this.slew = slew;
        looping = true;
        delay = 0;
        volume = 0.001f;
        this.pitch = pitch;
        relative = false;
        attenuation = Attenuation.NONE;
    }

    /**
     * Owner: play from direction {@code toward} relative to the ear (3 blocks from it), at {@code volume}, with the
     * source {@code distance} blocks away.
     */
    public void set(Vec3 ear, Vec3 toward, float volume, double distance) {
        set(ear, toward, volume, distance, 1);
    }

    /** As above, with the current openness of the path (walls/terrain); 1 leaves the path unobstructed. */
    public void set(Vec3 ear, Vec3 toward, float volume, double distance, float open) {
        Vec3 dir = toward.subtract(ear);
        Vec3 at = dir.lengthSqr() < 9 ? toward : ear.add(dir.normalize().scale(3));
        x = at.x;
        y = at.y;
        z = at.z;
        target = Math.max(0, Math.min(1, volume));
        highs = SoundFilters.air(distance);
        this.open = open;
        quiet = 0;
    }

    /**
     * Like {@link #set(Vec3, Vec3, float, double)}, but the owner has already computed path highs ({@code highs}:
     * fraction of highs, 1 — all) and also sets the pitch (blast rumble: averages over its members, {@link Rumble}).
     */
    public void set(Vec3 ear, Vec3 toward, float volume, float highs, float pitch) {
        set(ear, toward, volume, 0);
        this.highs = highs;
        this.pitch = pitch;
    }

    /** Set volume to the target immediately, without slewing (a loop starting amid ongoing sound: volume is already computed). */
    public void prime() {
        volume = target;
    }

    /** Fade and go away: no faster than normal, even for a loop with fast slew (no click). */
    public void end() {
        ending = true;
        target = 0;
        slew = Math.min(slew, SLEW);
    }

    /**
     * Whether it plays: the engine may have refused it (no channels) or removed it (sound restart, category slider at 0).
     * Not {@code SoundManager.isActive}: based on the start record it still considers a channel removed by the slider
     * ({@code SoundEngine.tickNonPaused}) to be playing (as with {@link SirenSound#playing}).
     */
    public boolean playing() {
        return !isStopped() && Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.containsKey(this);
    }

    @Override
    public void tick() {
        if (++quiet > ORPHAN) end();
        volume += Math.max(-slew, Math.min(slew, target - volume));
        if (ending && volume <= 0.002f) {
            stop();
            return;
        }
        SoundFilters.update(this, lowpassGain(), lowpassHighs());
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public float lowpassGain() {
        return SoundFilters.blockedGain(open);
    }

    @Override
    public float lowpassHighs() {
        return highs * SoundFilters.blockedHighs(open);
    }
}
