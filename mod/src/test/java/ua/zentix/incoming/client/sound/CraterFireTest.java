package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.sound.Spectrum;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraterFireTest {
    private static CloudModel model(WeaponType weapon) {
        return new CloudModel(BlastSource.of(weapon, false), CloudModel.Air.STANDARD);
    }

    @Test
    void burningFuelDoesNotFadeOrGoSilentAtTheOldRecordingBoundaries() {
        for (WeaponType weapon : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            CloudModel m = model(weapon);
            assertTrue(m.poolSeconds > 30);
            // The old recording faded after 9 s and restarted every 13 s, with weapon-dependent gaps/overlaps.
            for (double t : new double[]{1, 9, 12.9, 13, 13.1, 26, 30}) {
                assertEquals(m.poolWatts, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), t, 0), 1e-6, weapon + " at " + t);
            }
        }
    }

    @Test
    void envelopeArrivesAtSoundSpeedAndEndsWithTheFuel() {
        CloudModel m = model(WeaponType.DRONE);
        double distance = 2 * Noise.SOUND;
        assertEquals(0, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 1.9, distance));
        assertEquals(m.poolWatts, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 2.1, distance), 1e-6);
        assertEquals(m.poolWatts / 2, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 0.9 * m.poolSeconds + 2, distance), 1e-6);
        assertEquals(0, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), m.poolSeconds + 2, distance));
    }

    /** Fire volume {@code watts} W in {@code d} m on an open path at night - as in {@link CraterFire#tick} (without an obstacle). */
    private static float volume(double watts, double d, Sources.Source source) {
        return Sources.volume(source.power(Sources.flame(watts, source.spectrum())), new Vec3(d, 64, 0), new Vec3(0, 65.62, 0), Propagation.Weather.NIGHT);
    }

    /**
     * The sum of fires in a quarter sounds as it was calculated, right up to the very ceiling of the game - and does not previously rest on the volume of the
     *  file (it does not play louder than the Minecraft file): fire loops are collected on the ceiling (review #456, circle 5 - collected by peak
     * −1 dBFS, −21,6 LUFS, they cut 300 fires by 2 MW in 30 m by 5,1 dB). Above the ceiling - the ceiling is for the sum of all
     *  quarters, and not for each. On the axis of the quarter and in the middle between the two.
     */
    @Test
    void summedFiresPlayAsComputedUpToTheCeiling() throws java.io.IOException {
        double file = SoundAssetsTest.loopMean("blast.fire");
        assertEquals(Loudness.CEILING_LUFS, file, 0.3, "fire loops - on the ceiling");
        float v = volume(2e6, 30, Sources.BLAST_FIRE);
        boolean crossed = false;
        for (int n : new int[]{1, 10, 100, 300, 1000, 3000, 10000}) {
            // as calculated: the volume in the game of one fire (according to the law) and the amount of n in power
            double wanted = Loudness.ENGINE_LUFS + 20 * Math.log10(v / Loudness.fileGain(Spectra.BLAST_FIRE_LUFS)) + 10 * Math.log10(n);
            for (double azimuth : new double[]{0, Math.PI / 4}) {
                Quarters q = new Quarters();
                for (int i = 0; i < n; i++) q.add(azimuth, v, 1, 1);
                double power = 0;
                for (int k = 0; k < Quarters.N; k++) power += q.volume(k) * q.volume(k);
                // what it sounds like: files with their real average volume at loop volumes
                double played = file + 10 * Math.log10(power);
                if (wanted <= Loudness.CEILING_LUFS) assertEquals(wanted, played, 0.3, n + " fires, azimuth " + azimuth + ": amount below ceiling - as calculated");
                else assertEquals(Loudness.CEILING_LUFS, played, 0.3, n + " fires, azimuth " + azimuth + ": above the ceiling - the amount has a ceiling");
                crossed |= wanted > Loudness.CEILING_LUFS;
            }
            if (n == 300) assertTrue(wanted < Loudness.CEILING_LUFS, "review scene - below the ceiling: " + wanted);
        }
        assertTrue(crossed, "check reaches the ceiling");
    }

    /**
     * A single fire sounds the same as in 6763a1c8 (average loop volume according to the law of flame): the files are louder
     *  (on the ceiling), and the volume in the game is the same, at any distance. It sounds like the real average volume of files at the
     *  volume that the client gives; for 6763a1c8 - its spec (spectrum, −21,6 LUFS) and the average of its files according to the measurement
     *  (−21,726, −21,704, −21,718, −21,712 - circle review 5; for power −21,715).
     */
    @Test
    void aSingleFireSoundsAsIn6763a1c8() throws java.io.IOException {
        Spectrum before = Spectrum.of(-24.1, -6.5, -6.2, -7.6, -6.3, 0.0, -3.8, -18.7);
        Sources.Source then = new Sources.Source(Double.NaN, before, -21.6);
        double file = SoundAssetsTest.loopMean("blast.fire"), fileThen = -21.715;
        for (double watts : new double[]{2e5, 2e6, 2e7}) {
            for (double d : new double[]{5, 15, 40, 100, 300}) {
                double now = file + 20 * Math.log10(volume(watts, d, Sources.BLAST_FIRE));
                double was = fileThen + 20 * Math.log10(volume(watts, d, then));
                assertEquals(was, now, 0.2, watts + " W in " + d + " m: average volume in game, LUFS");
            }
        }
    }

    /**
     * Whether a fire can be heard is determined by the sound at the ear, not the volume of the file (review R11): the real decision to launch
     *  ({@link CraterFire#volume}, then {@link Quarters#plays}) for 0,2, 2 and 20 MW 5 m – 12 km at night the same file
     *  on the ceiling and the same file collected on 8,1 dB quieter; nowhere is the fire that sounded in 6763a1c8 (the volume of the file
     * −21,6 LUFS is not quieter than {@code AUDIBLE}) is now silent; the border is where the fire drowns in the background in all octaves.
     */
    @Test
    void audibilityIsAtTheEarNotInTheFile() {
        Sources.Source quiet = new Sources.Source(Double.NaN, Spectra.BLAST_FIRE, Spectra.BLAST_FIRE_LUFS - 8.1);
        Sources.Source then = new Sources.Source(Double.NaN, Spectrum.of(-24.1, -6.5, -6.2, -7.6, -6.3, 0.0, -3.8, -18.7), -21.6);
        Vec3 ear = new Vec3(0, 65.62, 0);
        for (double watts : new double[]{2e5, 2e6, 2e7}) {
            double last = 0, lastThen = 0;
            for (int d = 5; d <= 12000; d += 5) {
                Vec3 at = new Vec3(d, 64, 0);
                boolean now = plays(CraterFire.volume(Sources.BLAST_FIRE, watts, at, ear, Propagation.Weather.NIGHT));
                assertEquals(now, plays(CraterFire.volume(quiet, watts, at, ear, Propagation.Weather.NIGHT)), watts + " W in " + d + " m: the file is quieter - the solution is the same");
                boolean wasPlaying = volume(watts, d, then) >= VoiceBudget.AUDIBLE;
                assertTrue(!wasPlaying || now, watts + " W in " + d + " m: in 6763a1c8 it sounded - it still sounds");
                double gain = Sources.gain(Sources.BLAST_FIRE.power(Sources.flame(watts, Spectra.BLAST_FIRE)), at, ear, Propagation.Weather.NIGHT);
                assertEquals(gain > 0, now, watts + " W in " + d + " m: sounds until it drowns in the background");
                if (now) last = d;
                if (wasPlaying) lastThen = d;
            }
            System.out.printf(java.util.Locale.ROOT, "fire %.0f W heard to %.0f m (6763a1c8: %.0f m)%n", watts, last, lastThen);
            assertTrue(last > 300 && last >= lastThen, watts + " W: audible beyond 300 m and no closer than in 6763a1c8");
        }
    }

    /** The decision to start a loop of one fire with a volume of {@code volume}: as in {@link BlastSounds#fireLoops}. */
    private static boolean plays(float volume) {
        Quarters q = new Quarters();
        q.add(0, volume, 1, 1);
        return q.plays(0);
    }

    @Test
    void noFuelOrWaterMeansNoFireSound() {
        CloudModel dry = model(WeaponType.ROCKET);
        CloudModel water = new CloudModel(BlastSource.of(WeaponType.DRONE, false), CloudModel.Air.STANDARD, true);
        for (CloudModel m : new CloudModel[]{dry, water}) {
            assertEquals(0, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 1, 0));
            assertEquals(0, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 30, 0));
        }
    }
}
