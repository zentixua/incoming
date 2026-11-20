package ua.zentix.incoming.client.sound;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.FuelBurn;
import ua.zentix.incoming.nuclear.model.FuelPool;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.sound.Spectrum;
import ua.zentix.incoming.strike.WeaponType;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crater fuel roar level from the pool-flame law ({@link Sources#POOL_FLAME_EFFICIENCY}) through the real client path
 * ({@link CraterFire#hear}, {@link CraterFire#mix}, {@link Quarters}): the levels the guide states, and the in-game change
 * against the 10⁻⁶ law and recording of 6763a1c8 (game volume is half the decibels, {@link Loudness}). Review probe of #706.
 */
class CraterFireLevelTest {
    private static final Vec3 EAR = new Vec3(0.5, 65.62, 0.5);
    // The fire law and recording calibration at 6763a1c8.
    private static final double OLD_EFFICIENCY = 1e-6;
    private static final Spectrum OLD_SPECTRUM = Spectrum.of(-24.1, -6.5, -6.2, -7.6, -6.3, 0, -3.8, -18.7);
    private static final double OLD_FILE_LUFS = -21.6;
    private static final double OLD_LOOP_MEAN_LUFS = -21.715;
    private static final float OLD_AUDIBLE = 0.005f;
    private static final SoundFilters.Path OPEN_PATH = new SoundFilters.Path() {
        @Override
        public float open(Vec3 from, Vec3 to) {
            return 1;
        }

        @Override
        public float air(double distance) {
            return 1;
        }
    };

    /** 2 MW at 100 m: 4.1 dB quieter in game than with the 10⁻⁶ law; the recording alone changes nothing; beyond the new masking edge silent. */
    @Test
    void historicalBaselineIncludesItsOwnFlameEfficiency() {
        double watts = 2e6;
        Vec3 near = new Vec3(100.5, 64, 0.5);
        float current = CraterFire.volume(Sources.BLAST_FIRE, watts, near, EAR, Propagation.Weather.NIGHT);
        float historical = historicalVolume(watts, near, OLD_EFFICIENCY);
        float sameLawAsCurrent = historicalVolume(watts, near, Sources.POOL_FLAME_EFFICIENCY);
        double playbackDelta = Spectra.BLAST_FIRE_LUFS - OLD_LOOP_MEAN_LUFS + 20 * Math.log10(current / historical);
        double equalLawDelta = Spectra.BLAST_FIRE_LUFS - OLD_LOOP_MEAN_LUFS + 20 * Math.log10(current / sameLawAsCurrent);
        assertEquals(-4.1, playbackDelta, 0.3);
        assertEquals(0, equalLawDelta, 0.3);

        Vec3 far = new Vec3(1500.5, 64, 0.5);
        float oldFar = historicalVolume(watts, far, OLD_EFFICIENCY);
        float equalLawFar = historicalVolume(watts, far, Sources.POOL_FLAME_EFFICIENCY);
        assertTrue(oldFar >= OLD_AUDIBLE);
        assertTrue(equalLawFar < OLD_AUDIBLE);
        assertEquals(0f, CraterFire.volume(Sources.BLAST_FIRE, watts, far, EAR, Propagation.Weather.NIGHT));
        System.out.printf(Locale.ROOT,
                "2 MW, 100 m: playback delta vs historical %.2f dB, equal-law delta %.2f dB; 1500 m: old %.6f, equal-law old %.6f, cutoff %.3f%n",
                playbackDelta, equalLawDelta, oldFar, equalLawFar, OLD_AUDIBLE);
    }

    /** Guide numbers: a fully burning cruise missile pool, open night meadow: 78.8 dBA at 20 m, 63.7 at 100 m; one burning cell 54.2 at 100 m. */
    @Test
    void guideLevelsDescribeTheFullPoolAtPeakOutput() {
        var missile = WeaponType.MISSILE.spec();
        double fullAt20 = mixedLevel(missile.pool(), 20);
        double fullAt100 = mixedLevel(missile.pool(), 100);
        double oneCellAt100 = mixedLevel(missile.pool(1), 100);
        assertEquals(78.8, fullAt20, 0.3);
        assertEquals(63.7, fullAt100, 0.3);
        assertEquals(54.2, oneCellAt100, 0.3);
        System.out.printf(Locale.ROOT, "Full missile pool at 20/100 m: %.2f / %.2f dBA; one-cell pool at 100 m: %.2f dBA%n",
                fullAt20, fullAt100, oneCellAt100);
    }

    private static float historicalVolume(double watts, Vec3 source, double efficiency) {
        double lwA = Sources.lw(efficiency * watts) + OLD_SPECTRUM.aWeighting();
        Sources.Source old = new Sources.Source(lwA, OLD_SPECTRUM, OLD_FILE_LUFS);
        return Sources.volume(old, source, EAR, Propagation.Weather.NIGHT);
    }

    private static double mixedLevel(FuelPool pool, int distance) {
        FuelBurn burn = FuelBurn.at(pool, new BlockPos(distance, 64, 0));
        CraterFire fire = new CraterFire(burn, null, 200);
        Quarters mix = new Quarters();
        assertTrue(fire.hear(EAR, Propagation.Weather.NIGHT));
        fire.mix(mix, OPEN_PATH, 1);
        // Undo the game's playback mapping for an unmasked source below the ceiling.
        return Loudness.REF + 40 * Math.log10(mix.volume(0) / Loudness.fileGain(Spectra.BLAST_FIRE_LUFS));
    }
}
