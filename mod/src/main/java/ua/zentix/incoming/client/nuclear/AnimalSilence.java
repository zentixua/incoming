package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * Silence after the flash (DESIGN-nuke §5): animals around ground zero — out to {@link ua.zentix.incoming.nuclear.Detonation#radiusMax}
 * — fall silent: their voices ({@code entity.*.ambient}, "Creatures" and "Ambient" categories — bats) are muted for
 * {@link #FULL} ticks after detonation, then gradually return by {@link #BACK}. Only unprompted calls: cries
 * from hits, deaths and footsteps still sound. Detonation age follows its client clock: a detonation encountered on world entry or after
 * a replay seek is also silent for its allotted time.
 */
public final class AnimalSilence {
    static final double FULL = 4 * 60 * 20, BACK = 8 * 60 * 20;
    private static final RandomSource RANDOM = RandomSource.create();

    private AnimalSilence() {}

    public static void onPlay(PlaySoundEvent e) {
        SoundInstance s = e.getSound();
        if (s == null || s.getSource() != SoundSource.NEUTRAL && s.getSource() != SoundSource.AMBIENT) return;
        String path = s.getLocation().getPath();
        if (!path.startsWith("entity.") || !path.endsWith(".ambient")) return;
        double share = silence(s.getX(), s.getZ());
        if (share >= 1 || share > 0 && RANDOM.nextDouble() < share) e.setSound(null);
    }

    /** What fraction of animal voices at this location is currently inaudible (0..1). */
    static double silence(double x, double z) {
        double best = 0;
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            Vec3 b = a.d.burst();
            double r = a.d.radiusMax();
            if ((x - b.x) * (x - b.x) + (z - b.z) * (z - b.z) > r * r) continue;
            best = Math.max(best, share(a.ticks(0)));
        }
        return best;
    }

    /** Silence fraction {@code ticks} after detonation. */
    static double share(double ticks) {
        if (ticks < 0 || ticks > BACK) return 0;
        return ticks < FULL ? 1 : 1 - (ticks - FULL) / (BACK - FULL);
    }
}
