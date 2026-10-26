package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.sound.Spectrum;
import ua.zentix.incoming.strike.WeaponType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loudness measure: the same as in the sound assembly; the volume grows with the level from the ear to the ceiling - a nearby explosion from 1 psi -
 *  and not a single sound rests on the volume of its file before (3.5.0: motor files on the measure, and the impact of igniting a rocket
 *  closer ~ 45 m to launcher, the roar of the accelerator closer ~20 m sounded the same).
 */
class LoudnessTest {
    private static final double[] BIG = {200};

    @Test
    void numbersAreTheBuildScriptOnes() throws IOException {
        String script = Files.readString(Path.of(System.getProperty("user.dir")).getParent().resolve("tools/build_sounds.py"));
        assertEquals(constant(script, "ENGINE_LUFS"), Loudness.ENGINE_LUFS, 1e-9);
        assertEquals(constant(script, "MOTOR_LUFS"), Loudness.MOTOR_LUFS, 1e-9);
        Matcher m = Pattern.compile("(?m)^ROCKET_LAUNCH_LUFS = MOTOR_LUFS - ([\\d.]+)").matcher(script);
        assertTrue(m.find(), "ROCKET_LAUNCH_LUFS to build_sounds.py");
        assertEquals(Loudness.MOTOR_LUFS - Double.parseDouble(m.group(1)), Loudness.ROCKET_LAUNCH_LUFS, 1e-9);
        // ceiling - close-up view of the explosion with 1 psi: there is nothing louder in the game
        assertEquals(BlastMix.NEAR_LUFS, Loudness.CEILING_LUFS, 1e-9);
    }

    private static double constant(String script, String name) {
        Matcher m = Pattern.compile("(?m)^" + name + " = (-?[\\d.]+)").matcher(script);
        assertTrue(m.find(), name + " in build_sounds.py");
        return Double.parseDouble(m.group(1));
    }

    /** The motor is 2 dB louder at the ear - 1 dB louder in the game, and so on all the way to the ceiling; on the ceiling is the entire file. */
    @Test
    void motorsGrowUpToTheCeiling() {
        double prev = 0;
        for (double level = Propagation.NIGHT; level <= Loudness.CEILING_LEVEL; level += 2) {
            double v = Loudness.motor(Loudness.gain(level, BIG[0]));
            assertTrue(v > prev, level + " dBA: " + v + " no louder " + prev);
            if (prev > 0) assertEquals(1, 20 * Math.log10(v / prev), 1e-4, level + " dBA");
            prev = v;
        }
        assertEquals(1, Loudness.motor(Loudness.gain(Loudness.CEILING_LEVEL, BIG[0])), 1e-6);
        assertEquals(1, Loudness.motor(Loudness.gain(Loudness.CEILING_LEVEL + 30, BIG[0])), 1e-6);
        assertEquals(Loudness.ENGINE_LUFS, Loudness.MOTOR_LUFS + 20 * Math.log10(Loudness.motor(Loudness.gain(Loudness.REF, BIG[0]))),
                1e-5, "on REF - measure");
    }

    /**
     * The impact of the rocket accelerator ignition is louder than {@link Loudness#REF} for tens of meters around (solid propellant rocket motor according to NASA SP-8072) - that
     *  is the very case that rested on the file: in 5 m it sounds louder than in 15 m, and in 15 m it is louder than in 25 m.
     */
    @Test
    void missileLaunchIsLouderUpClose() {
        Noise.Rocket motor = WeaponType.MISSILE.spec().airframe().noise().motor();
        double lw = motor.power(FlightPhase.BOOST, 0);
        double v5 = launchVolume(lw, 5), v15 = launchVolume(lw, 15), v25 = launchVolume(lw, 25);
        assertTrue(Loudness.gain(level(lw, Spectra.LAUNCH_BOOSTER, 25), BIG[0]) > 1, "in 25 m - louder than measurements");
        assertTrue(v5 > v15 && v15 > v25, "in 5 m " + v5 + ", in 15 m " + v15 + ", in 25 m " + v25);
    }

    private static double launchVolume(double lw, double d) {
        return Loudness.motor(Loudness.gain(level(lw, Spectra.LAUNCH_BOOSTER, d), BIG[0]));
    }

    private static double level(double lw, Spectrum spectrum, double d) {
        double[] bands = new double[Propagation.BANDS];
        return Propagation.level(lw, spectrum, Propagation.Path.over(d, 2, Propagation.Weather.NIGHT), bands);
    }

    /**
     * The clap of the accelerator compartment and the catapult are assembled on the measure ({@link Loudness#ENGINE_LUFS}): louder than {@link Loudness#REF}
     *  they do not exist in a meter - otherwise their file would cut off the volume.
     */
    @Test
    void quietLaunchCuesNeverReachTheirFile() {
        double sep = WeaponType.MISSILE.spec().airframe().noise().separation() - Spectra.BOOSTER_SEPARATE.aWeighting();
        double cat = WeaponType.LOITER.spec().airframe().noise().catapult() - Spectra.LOITER_LAUNCH.aWeighting();
        assertTrue(level(sep, Spectra.BOOSTER_SEPARATE, 1) < Loudness.REF, "branch in 1 m");
        assertTrue(level(cat, Spectra.LOITER_LAUNCH, 1) < Loudness.REF, "catapult in 1 m");
    }

    /**
     * The sounds of the world according to the law of the source: the file is not quieter than how loud the source sounds a meter away (or the ceiling, if that
     *  is lower) - otherwise everything closer would sound the same.
     */
    @Test
    void sourceFilesHoldTheirLoudestLevel() {
        for (Sources.Source s : List.of(Sources.SIREN, Sources.GRID_FAIL, Sources.GRID_SPARK, Sources.GRID_HUM,
                Sources.GRID_POWER_DOWN, Sources.GRID_POWER_UP)) {
            double l = level(s.lwA() - s.spectrum().aWeighting(), s.spectrum(), 1);
            double want = Math.min(s.ceilingLufs(), Loudness.ENGINE_LUFS + (l - Loudness.REF) / 2);
            assertTrue(s.fileLufs() >= want - 0.05, s + ": file " + s.fileLufs() + " LUFS, and in the meter you need " + want);
        }
        assertEquals(1, Loudness.fileGain(Loudness.ENGINE_LUFS), 1e-12);
    }

    /** A nuclear strike does not change: its sounds have the same ceiling - a measure, as in 3.5.0. */
    @Test
    void nukeKeepsItsCeiling() {
        for (Sources.Source s : List.of(Sources.NUKE_ALARM, Sources.NUKE_COLLAPSE, Sources.RUIN_DEBRIS, Sources.NUKE_LAUNCH)) {
            assertEquals(Loudness.ENGINE_LUFS, s.ceilingLufs(), 1e-12, s.toString());
        }
        for (double level = 100; level < 180; level += 5) {
            double old = Math.min(1, Math.pow(10, (level - Loudness.REF) / 40));
            assertEquals(old, Loudness.gain(level, BIG[0], Loudness.ENGINE_LUFS), 1e-12);
            assertEquals(Math.min(1, old * Loudness.fileGain(Spectra.NUKE_LAUNCH_LUFS)),
                    Loudness.volume(Loudness.gain(level, BIG[0], Loudness.ENGINE_LUFS), Spectra.NUKE_LAUNCH_LUFS), 1e-6);
            assertEquals(Math.min(1, old) * Loudness.fileGain(Loudness.MOTOR_LUFS),
                    Loudness.motor(Loudness.gain(level, BIG[0], Loudness.ENGINE_LUFS)), 1e-6, "ICBM: accelerator loop");
        }
    }
}
